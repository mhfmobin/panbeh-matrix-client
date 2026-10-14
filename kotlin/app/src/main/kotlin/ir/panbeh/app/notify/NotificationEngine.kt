package ir.panbeh.app.notify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.client.notification
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.joinedMemberCount
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.model.push.SetPushRule
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.m.IgnoredUserListEventContent
import de.connect2x.trixnity.core.model.events.m.ReceiptEventContent
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.room.RedactionEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import de.connect2x.trixnity.core.model.push.PushCondition
import de.connect2x.trixnity.core.model.push.PushRuleKind
import ir.panbeh.app.MainActivity
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.sync.SyncControl
import ir.panbeh.client.events.MuteUntil
import ir.panbeh.client.events.rawMessage
import ir.panbeh.core.rooms.NameState
import ir.panbeh.core.rooms.roomName
import ir.panbeh.core.util.number
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Decides what to notify, port of the web app's src/notify.ts: new timeline events after the initial sync, not mine, not from
 * ignored users, decrypted first; Trixnity's push-rule evaluation says notify/sound/highlight; the local switches
 * (notify, notifyDMs, notifyGroups) come on top. Also clears notifications (read elsewhere, deleted, chat opened) and ends
 * timed mutes. Start once with [start].
 */
object NotificationEngine {
    /** The notification's Mute button. */
    const val MUTE_BUTTON_MS = 8 * 3600_000L

    private val seen = LinkedHashSet<String>()

    /** Runs for the life of the process, following whichever client is running. */
    fun start(app: PanbehApp) {
        app.scope.launch {
            app.accounts.client.collectLatest { c -> if (c != null) run(app, c) }
        }
    }

    private suspend fun run(app: PanbehApp, c: MatrixClient) = coroutineScope {
        launch { guarded { notifications(app, c) } }
        launch { guarded { clearing(app, c) } }
        launch { guarded { muteExpiry(c) } }
        launch {
            guarded {
                // opening a chat clears its notification
                combine(NotifyState.openRoom, SyncControl.visible) { r, v -> if (v) r else null }.collect { r -> if (r != null) Notifier.cancel(app, r) }
            }
        }
    }

    private suspend fun guarded(f: suspend () -> Unit) {
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* a bad event must not end the engine */ }
    }

    private suspend fun <T> Flow<T?>.firstQuick(ms: Long = 2000): T? = withTimeoutOrNull(ms) { first() }

    // ---------- notifying ----------

    @Suppress("DEPRECATION") // the new notification system needs enableExternalNotifications; this one just reports each sync's events with their push actions
    private suspend fun notifications(app: PanbehApp, c: MatrixClient) {
        c.notification.getNotifications().collect { n ->
            guarded {
                val ev = n.event as? ClientEvent.RoomEvent.MessageEvent<*> ?: return@guarded
                handle(app, c, ev, n.actions)
            }
        }
    }

    private suspend fun handle(app: PanbehApp, c: MatrixClient, ev: ClientEvent.RoomEvent.MessageEvent<*>, actions: Set<PushAction>) {
        val prefs = app.state.prefs.value
        if (!prefs.notify || ev.sender == c.userId) return
        if (c.syncState.value == SyncState.INITIAL_SYNC) return // history, not news
        if (PushAction.Notify !in actions) return // server push rules: mute, mentions, keywords, edits…
        val content: Any = ev.content
        if (isEdit(content)) return
        val sender = senderName(c, ev.roomId, ev.sender)
        val text = previewText(content, sender) ?: return
        val ignored = c.user.getAccountData(IgnoredUserListEventContent::class).firstQuick()?.ignoredUsers
        if (ignored != null && ev.sender in ignored) return
        val id = ev.id.full
        if (!seen.add(id)) return
        if (seen.size > 500) seen.remove(seen.first())
        val room = c.room.getById(ev.roomId).firstQuick() ?: return
        val group = isGroup(room)
        val mention = actions.any { it is PushAction.SetHighlightTweak && it.value }
        // the DM/group switches silence ordinary messages only; mentions and keywords still come through
        if (!mention && !(if (group) prefs.notifyGroups else prefs.notifyDMs)) return
        post(app, c, room, ev.sender, sender, text, actions.any { it is PushAction.SetSoundTweak }, ev.originTimestamp, mention, id, notice = false)
    }

    /** A notification about a call that didn't ring (missed, or a group call starting). [group]: obeys the group switch. */
    suspend fun callNotice(roomId: String, body: String, group: Boolean = false) {
        val app = PanbehApp.instance
        val c = app.accounts.client.value ?: return
        val prefs = app.state.prefs.value
        if (!prefs.notify || (group && !prefs.notifyGroups)) return
        val room = c.room.getById(RoomId(roomId)).firstQuick() ?: return
        post(app, c, room, null, "", body, sound = true, ts = System.currentTimeMillis(), mention = false, eventId = null, notice = true)
    }

    private suspend fun post(
        app: PanbehApp, c: MatrixClient, room: Room, senderId: UserId?, senderName: String, text0: String,
        sound: Boolean, ts: Long, mention: Boolean, eventId: String?, notice: Boolean,
    ) {
        val roomId = room.roomId.full
        // already looking at it
        if (MainActivity.visible && NotifyState.openRoom.value == roomId) return
        val preview = app.state.prefs.value.notifyPreview
        val hidden = preview == "none"
        val text = if (preview != "all" && !notice) "پیام تازه" else text0 // a notice (missed call, failed reply) isn't someone's message
        val group = isGroup(room)
        val sender = if (hidden || !group) "" else senderName
        val title = if (hidden) "پنبه" else roomTitle(c, room)
        val (icon, senderIcon) = if (hidden) null to null else coroutineScope {
            val i = async { avatar(c, roomAvatar(c, room, senderId, group)) }
            val s = async { if (group && senderId != null) avatar(c, memberAvatar(c, room.roomId, senderId)) else null }
            i.await() to s.await()
        }
        Notifier.show(
            app,
            MessageNote(roomId, eventId, title, sender, senderId?.full ?: roomId, text, ts, group, sound, mention, hidden, icon, senderIcon),
        )
    }

    // ---------- names and pictures ----------

    private fun isGroup(room: Room) = (room.joinedMemberCount ?: if (room.isDirect) 2L else 3L) > 2

    private suspend fun senderName(c: MatrixClient, roomId: RoomId, user: UserId): String =
        c.user.getById(roomId, user).firstQuick()?.name ?: user.full

    private suspend fun roomTitle(c: MatrixClient, room: Room): String {
        val n = room.name
        n?.explicitName?.takeIf { it.isNotEmpty() }?.let { return it }
        val names = n?.heroes.orEmpty().map { senderName(c, room.roomId, it) }
        val state = if (names.isEmpty()) NameState.Empty() else NameState.Names(names, (n?.otherUsersCount ?: 0) + names.size + 1)
        return roomName(state) ?: "گفتگو"
    }

    private suspend fun memberAvatar(c: MatrixClient, roomId: RoomId, user: UserId): String? =
        c.user.getById(roomId, user).firstQuick()?.event?.content?.avatarUrl

    private suspend fun roomAvatar(c: MatrixClient, room: Room, sender: UserId?, group: Boolean): String? {
        room.avatarUrl?.let { return it }
        if (group) return null
        val other = room.name?.heroes?.firstOrNull() ?: sender ?: return null
        return memberAvatar(c, room.roomId, other)
    }

    /** An avatar for a notification, or null if it isn't there in time. */
    private suspend fun avatar(c: MatrixClient, mxc: String?): Bitmap? {
        if (mxc == null) return null
        return withTimeoutOrNull(1500) {
            val bytes = c.media.getThumbnail(mxc, 96L, 96L, maxSize = 1_000_000L).getOrNull()?.toByteArray(maxSize = 1_000_000L) ?: return@withTimeoutOrNull null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    // ---------- clearing ----------

    /** Notifications stay until dealt with: a chat's goes when it's read (here or on another device) or its message is deleted. */
    private suspend fun clearing(app: PanbehApp, c: MatrixClient) {
        val unsubscribe = c.api.sync.subscribe { events ->
            val rooms = events.syncResponse.room?.join ?: return@subscribe
            for ((roomId, joined) in rooms) {
                for (e in joined.timeline?.events.orEmpty()) {
                    val content = e.content
                    if (content is RedactionEventContent) Notifier.remove(app, roomId.full, content.redacts.full)
                }
                for (e in joined.ephemeral?.events.orEmpty()) {
                    val receipts = (e.content as? ReceiptEventContent)?.events ?: continue
                    val mine = receipts.filter { (_, byType) ->
                        listOf(ReceiptType.Read, ReceiptType.PrivateRead).any { byType[it]?.containsKey(c.userId) == true }
                    }.keys
                    if (mine.isEmpty()) continue
                    // its unread count can lag in encrypted chats, so a receipt on the newest event is enough
                    val last = c.room.getById(roomId).firstQuick()?.lastEventId
                    if (last == null || last in mine) Notifier.cancel(app, roomId.full)
                }
            }
        }
        try { awaitCancellation() } finally { unsubscribe() }
    }

    // ---------- muting ----------

    /** Mute via an override rule for the room (what the web SDK's setRoomMutePushRule does); [until] (ms) also stored as room account data. */
    suspend fun mute(c: MatrixClient, roomId: String, until: Long? = null) {
        c.api.push.setPushRule(
            "global", PushRuleKind.OVERRIDE, roomId,
            SetPushRule.Request(actions = emptySet(), conditions = setOf(PushCondition.EventMatch("room_id", roomId))),
        ).getOrThrow()
        if (until != null) c.api.room.setAccountData(MuteUntil(buildJsonObject { put("until", until) }), RoomId(roomId), c.userId).getOrThrow()
    }

    suspend fun unmute(c: MatrixClient, roomId: String) {
        c.api.push.deletePushRule("global", PushRuleKind.OVERRIDE, roomId)
        c.api.push.deletePushRule("global", PushRuleKind.ROOM, roomId)
        c.api.room.setAccountData(MuteUntil(buildJsonObject {}), RoomId(roomId), c.userId)
    }

    /** A timed mute's end is room account data: whichever of our clients is running then unmutes. */
    private suspend fun muteExpiry(c: MatrixClient) {
        while (true) {
            delay(60_000)
            if (c.syncState.value == SyncState.INITIAL_SYNC) continue
            guarded {
                for (id in c.room.getAll().first().keys) {
                    val until = c.room.getAccountData(id, MuteUntil::class).firstQuick()?.raw?.number("until")?.toLong() ?: continue
                    if (until in 1..System.currentTimeMillis()) unmute(c, id.full)
                }
            }
        }
    }

    // ---------- notification buttons ----------

    /** Reply, mark-as-read or mute pressed on a message notification (see [NotifyReceiver]). Replying also marks the chat read. */
    suspend fun onButton(action: String, roomId: String, text: String?) {
        val app = PanbehApp.instance
        val failed = { if (action == "reply" && text != null) Notifier.replyFailed(app, roomId, "پاسخ شما ارسال نشد: «$text»") }
        if (app.accounts.client.value == null) runCatching { app.accounts.restore() }
        // a notification button may have woken a process that's still starting: wait up to a minute for the client and the room
        val c = withTimeoutOrNull(60_000) { app.accounts.client.filterNotNull().first() } ?: return failed()
        val rid = RoomId(roomId)
        val room = withTimeoutOrNull(60_000) { c.room.getById(rid).filterNotNull().first() } ?: return failed()
        if (action == "mute") {
            runCatching { mute(c, roomId, System.currentTimeMillis() + MUTE_BUTTON_MS) }
            return
        }
        if (action == "reply" && !text.isNullOrBlank()) {
            val ok = try {
                val msg = rawMessage(buildJsonObject { put("msgtype", "m.text"); put("body", text) })
                val tx = c.room.sendMessage(rid) { content(msg) }
                val out = withTimeoutOrNull(30_000) { c.room.getOutbox(rid, tx).first { it == null || it.sentAt != null || it.sendError != null } }
                out?.sendError == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) return failed()
        }
        runCatching {
            val last = c.room.getById(rid).first()?.lastEventId ?: room.lastEventId
            if (last != null) c.api.room.setReadMarkers(rid, fullyRead = last, read = last).getOrThrow()
        }
        Notifier.cancel(app, roomId)
    }
}
