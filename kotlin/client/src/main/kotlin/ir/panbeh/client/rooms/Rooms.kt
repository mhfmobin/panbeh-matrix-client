package ir.panbeh.client.rooms

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAccountData
import de.connect2x.trixnity.client.room.getAllState
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.isEncrypted
import de.connect2x.trixnity.client.store.joinedMemberCount
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.client.store.relatesTo
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.client.store.type
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.RedactedEventContent
import de.connect2x.trixnity.core.model.events.m.DirectEventContent
import de.connect2x.trixnity.core.model.events.m.IgnoredUserListEventContent
import de.connect2x.trixnity.core.model.events.m.MarkedUnreadEventContent
import de.connect2x.trixnity.core.model.events.m.Presence
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.TagEventContent
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.model.events.m.space.ChildEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import de.connect2x.trixnity.core.model.push.PushRuleKind
import de.connect2x.trixnity.clientserverapi.model.push.SetPushRule
import ir.panbeh.client.KeyValueStore
import ir.panbeh.client.errcode
import ir.panbeh.client.events.FamedlyMarkedUnread
import ir.panbeh.client.events.FolderOrder
import ir.panbeh.client.events.MuteUntil
import ir.panbeh.client.events.PollStart
import ir.panbeh.client.events.PollStartStable
import ir.panbeh.client.events.RtcNotification
import ir.panbeh.client.events.RtcNotificationStable
import ir.panbeh.client.events.Sticker
import ir.panbeh.client.events.json
import ir.panbeh.core.rooms.BASE_FOLDERS
import ir.panbeh.core.rooms.Folder
import ir.panbeh.core.rooms.NameState
import ir.panbeh.core.rooms.applyFolderOrder
import ir.panbeh.core.rooms.byListOrder
import ir.panbeh.core.rooms.moveFolder
import ir.panbeh.core.rooms.roomName
import ir.panbeh.core.rooms.spaceRooms
import ir.panbeh.core.time.lastSeen
import ir.panbeh.core.util.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val PINNED = "m.favourite"
private const val ARCHIVED = "u.archived"

/** How long a timed mute lasts. */
enum class MuteSpan(val ms: Long?) {
    HOUR(3_600_000), HOURS_8(8 * 3_600_000), DAY(24 * 3_600_000), WEEK(7 * 24 * 3_600_000L), FOREVER(null)
}

/** A person's online state and last-seen text (null = unknown). */
data class PresenceInfo(val online: Boolean, val text: String?)

private data class Counts(val notify: Int, val highlight: Int)
private class Seen(val online: Boolean, val ts: Long?, val at: Long)

private class Base(
    val room: Room,
    val tags: Set<String>,
    val marked: Boolean,
    val muteUntil: Long?,
    val ownMember: MemberEventContent?,
    val inviter: String?,
    val inviterName: String?,
    val inviterAvatar: String?,
    val heroNames: Map<String, String>,
    val children: Set<String>?,
    val last: LastMessage?,
    val fallbackTs: Long,
)

/**
 * The chat list and what you do to chats in it: folders, pin/archive/read/mute/leave, invites, typing, presence.
 * Creating and joining rooms is in [Chats]. `store` (optional) keeps the server's unread counters across restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class Rooms(
    private val client: MatrixClient,
    private val scope: CoroutineScope,
    private val store: KeyValueStore? = null,
) {
    private val me = client.userId
    private val api get() = client.api

    // ---------- server counters ----------

    private val countsKey = "panbeh.unread:${me.full}"
    private val counts = MutableStateFlow(loadCounts())

    private fun loadCounts(): Map<String, Counts> = runCatching {
        store?.get(countsKey)?.let { s -> Json.decodeFromString<Map<String, List<Int>>>(s).mapValues { Counts(it.value[0], it.value[1]) } }
    }.getOrNull() ?: emptyMap()

    init {
        val unsubscribe = api.sync.subscribe { events ->
            val rooms = events.syncResponse.room ?: return@subscribe
            val changed = HashMap<String, Counts>()
            rooms.join?.forEach { (id, r) ->
                val c = r.unreadNotifications ?: return@forEach
                changed[id.full] = Counts((c.notificationCount ?: 0).toInt(), (c.highlightCount ?: 0).toInt())
            }
            val gone = rooms.leave?.keys?.map { it.full }.orEmpty()
            if (changed.isEmpty() && gone.isEmpty()) return@subscribe
            val next = counts.value.toMutableMap().apply { gone.forEach(::remove); putAll(changed) }
            if (next != counts.value) {
                counts.value = next
                runCatching { store?.set(countsKey, Json.encodeToString(next.mapValues { listOf(it.value.notify, it.value.highlight) })) }
            }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { unsubscribe() }
    }

    // ---------- shared account data ----------

    private val direct: StateFlow<Map<String, String>> = client.user.getAccountData<DirectEventContent>()
        .map { d -> buildMap { d?.mappings?.forEach { (user, rooms) -> rooms?.forEach { putIfAbsent(it.full, user.full) } } } }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val ignored: StateFlow<Set<String>> = client.user.getAccountData<IgnoredUserListEventContent>()
        .map { it?.ignoredUsers?.keys?.map { u -> u.full }?.toSet() ?: emptySet() }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** rooms muted by a room or override push rule that doesn't notify (ours, or Element's) */
    private val mutedRooms: StateFlow<Set<String>> = client.user.getAccountData<de.connect2x.trixnity.core.model.events.m.PushRulesEventContent>()
        .map { rules ->
            val g = rules?.global
            fun mutes(r: de.connect2x.trixnity.core.model.push.PushRule) = r.enabled && PushAction.Notify !in r.actions
            buildSet {
                g?.room?.filter(::mutes)?.forEach { add(it.ruleId) }
                g?.override?.filter(::mutes)?.forEach { add(it.ruleId) }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet())

    // ---------- per-room data ----------

    @Suppress("UNCHECKED_CAST")
    private fun base(id: RoomId, roomFlow: Flow<Room?>): Flow<Base?> {
        val rooms = client.room
        val tags = rooms.getAccountData<TagEventContent>(id).map { c -> c?.tags?.keys?.map { it.name }?.toSet() ?: emptySet() }.distinctUntilChanged()
        val marked = combine(rooms.getAccountData<MarkedUnreadEventContent>(id), rooms.getAccountData<FamedlyMarkedUnread>(id)) { a, b ->
            a?.unread ?: (b?.raw?.get("unread") as? JsonPrimitive)?.booleanOrNull ?: false
        }.distinctUntilChanged()
        val mute = rooms.getAccountData<MuteUntil>(id).map { c -> (c?.raw?.get("until") as? JsonPrimitive)?.doubleOrNull?.toLong()?.takeIf { it > 0 } }.distinctUntilChanged()
        val own = rooms.getState<MemberEventContent>(id, me.full).map { it?.content to it?.sender?.full }.distinctUntilChanged()
        val inviter = own.flatMapLatest { (m, sender) ->
            if (m?.membership != Membership.INVITE || sender == null) flowOf(Triple<String?, String?, String?>(null, null, null))
            else rooms.getState<MemberEventContent>(id, sender).map { Triple(sender, it?.content?.displayName, it?.content?.avatarUrl) }
        }.distinctUntilChanged()
        val roomKey = roomFlow.filterNotNullRoom()
        val heroes = roomKey.map { r -> if (r.name?.explicitName != null) emptyList() else r.name?.heroes.orEmpty() }.distinctUntilChanged().flatMapLatest { hs ->
            if (hs.isEmpty()) flowOf(emptyMap())
            else combine(hs.map { h -> rooms.getState<MemberEventContent>(id, h.full).map { h.full to (it?.content?.displayName?.takeIf(String::isNotEmpty) ?: h.full) } }) { it.toMap() }
        }.distinctUntilChanged()
        val children = roomKey.map { it.type == CreateEventContent.RoomType.Space && it.membership == Membership.JOIN }.distinctUntilChanged().flatMapLatest { space ->
            if (!space) flowOf<Set<String>?>(null) else rooms.getAllState<ChildEventContent>(id).flatMapLatest { m ->
                if (m.isNullOrEmpty()) flowOf(emptySet())
                else combine(m.values) { evs -> evs.mapNotNull { e -> e?.takeIf { it.content.via.isNotEmpty() }?.stateKey }.toSet() }
            }
        }.distinctUntilChanged()
        val last = roomKey.map { it.lastRelevantEventId }.distinctUntilChanged().flatMapLatest { e -> if (e == null) flowOf(null) else lastMessage(id, e) }.distinctUntilChanged()
        val fallbackTs = roomKey.map { Triple(it.lastRelevantEventTimestamp, it.lastEventId, it.membership) }.distinctUntilChanged().flatMapLatest { (ts, e, _) ->
            when {
                ts != null -> flowOf(ts.toEpochMilliseconds())
                e != null -> rooms.getTimelineEvent(id, e) { fetchTimeout = Duration.ZERO; decryptionTimeout = Duration.ZERO }.map { it?.originTimestamp ?: 0L }
                else -> flowOf(0L)
            }
        }.distinctUntilChanged()

        val flows: List<Flow<Any?>> = listOf(roomFlow, tags, marked, mute, own, inviter, heroes, children, last, fallbackTs)
        // a forgotten room can linger in getAll() as null: it must not hold the whole list back
        return roomFlow.map { it != null }.distinctUntilChanged().flatMapLatest { present -> if (!present) flowOf(null) else combine(flows) { a ->
            @Suppress("UNCHECKED_CAST")
            val room = a[0] as Room? ?: return@combine null
            val (member, _) = a[4] as Pair<MemberEventContent?, String?>
            val inv = a[5] as Triple<String?, String?, String?>
            Base(
                room, a[1] as Set<String>, a[2] as Boolean, a[3] as Long?, member, inv.first, inv.second, inv.third,
                a[6] as Map<String, String>, a[7] as Set<String>?, a[8] as LastMessage?, a[9] as Long,
            )
        } }
    }

    private fun Flow<Room?>.filterNotNullRoom(): Flow<Room> = flow { collect { if (it != null) emit(it) } }

    private val timelineCfg: de.connect2x.trixnity.client.room.GetTimelineEventConfig.() -> Unit = { fetchTimeout = Duration.ZERO; decryptionTimeout = 10.seconds }

    private fun lastMessage(room: RoomId, event: EventId): Flow<LastMessage?> =
        ignored.flatMapLatest { ig -> client.room.getTimelineEvent(room, event, timelineCfg).flatMapLatest { walk(it, ig, 25) } }
            .catch { emit(null) }

    private fun walk(te: TimelineEvent?, ig: Set<String>, depth: Int): Flow<LastMessage?> {
        if (te == null || depth == 0) return flowOf(null)
        val d = describe(te, ig)
            ?: return client.room.getPreviousTimelineEvent(te, timelineCfg)?.flatMapLatest { walk(it, ig, depth - 1) } ?: flowOf(null)
        val roomId = te.event.roomId
        return client.room.getState<MemberEventContent>(roomId, d.sender).map { m ->
            val name = m?.content?.displayName?.takeIf(String::isNotEmpty) ?: d.sender
            LastMessage(te.event.id.full, d.sender, name, d.ts, d.text(name), d.sender == me.full, d.call)
        }
    }

    private class Draft(val sender: String, val ts: Long, val call: Boolean, val text: (String) -> String)

    /** The list-worthy description of an event, or null when it isn't a message (reactions, edits, state, blocked senders…). */
    private fun describe(te: TimelineEvent, ig: Set<String>): Draft? {
        val sender = te.sender.full
        if (sender in ig || te.relatesTo is RelatesTo.Replace) return null
        val ts = te.originTimestamp
        val c = te.content ?: return if (te.isEncrypted) Draft(sender, ts, false) { "🔒 در حال رمزگشایی…" } else null
        val content = c.getOrNull() ?: return if (te.isEncrypted) Draft(sender, ts, false) { "🔒 پیام رمزنگاری‌شده" } else null
        return when (content) {
            is RoomMessageEventContent.Unknown -> {
                val j = content.json
                if (j.string("msgtype") == "m.key.verification.request") null
                else Draft(sender, ts, false) { previewText("m.room.message", j, it) }
            }
            is Sticker -> Draft(sender, ts, false) { "استیکر" }
            is PollStart -> Draft(sender, ts, false) { previewText("org.matrix.msc3381.poll.start", content.raw) }
            is PollStartStable -> Draft(sender, ts, false) { previewText("m.poll.start", content.raw) }
            is RtcNotification -> Draft(sender, ts, true) { callPreview(content.raw) }
            is RtcNotificationStable -> Draft(sender, ts, true) { callPreview(content.raw) }
            is RedactedEventContent -> null
            else -> null
        }
    }

    // ---------- the list ----------

    private val bases: Flow<List<Base>> = client.room.getAll().flatMapLatest { m ->
        if (m.isEmpty()) flowOf(emptyList())
        else combine(m.map { (id, f) -> base(id, f) }) { it.filterNotNull() }
    }

    /** Joined and invited chats (replaced/tombstoned rooms hidden, spaces separate), sorted invites > pinned > newest. */
    val list: StateFlow<RoomList> = combine(bases, direct, mutedRooms, counts) { b, d, m, c -> assemble(b, d, m, c) }
        .debounce(40)
        .retryWhen { _, _ -> delay(1000); true }
        .stateIn(scope, SharingStarted.Eagerly, RoomList())

    private fun assemble(bases: List<Base>, direct: Map<String, String>, muted: Set<String>, counts: Map<String, Counts>): RoomList {
        val live = bases.filter { it.room.membership == Membership.JOIN || it.room.membership == Membership.INVITE }
        val joined = live.filter { it.room.membership == Membership.JOIN }.map { it.room.roomId.full }.toSet()
        val visible = live.filter { b -> b.room.nextRoomId?.full?.let { it in joined } != true }
        val spaces = visible.filter { it.room.type == CreateEventContent.RoomType.Space && it.room.membership == Membership.JOIN }
        val children = spaces.associate { it.room.roomId.full to it.children.orEmpty() }
        val nested = children.values.flatten().toSet()
        val top = spaces.filter { it.room.roomId.full !in nested }.map { it.room.roomId.full }
        val membership = HashMap<String, MutableList<String>>()
        for (s in top) for (r in spaceRooms(s) { children[it]?.toList().orEmpty() }) membership.getOrPut(r) { mutableListOf() }.add(s)

        val rows = visible.filter { it.room.type != CreateEventContent.RoomType.Space || it.room.membership == Membership.INVITE }.map { b ->
            val r = b.room
            val id = r.roomId.full
            val invite = r.membership == Membership.INVITE
            val peerOfDirect = direct[id]
            val isDM = peerOfDirect != null || r.isDirect || (invite && b.ownMember?.isDirect == true)
            val peer = if (!isDM) null else peerOfDirect ?: (if (invite) b.inviter else r.name?.heroes?.firstOrNull()?.full)
            val c = counts[id]
            val last = b.last
            val muteNow = !invite && id in muted
            RoomSummary(
                id = id,
                name = displayName(b, invite),
                avatar = r.avatarUrl?.takeIf { it.isNotEmpty() } ?: if (invite && isDM) b.inviterAvatar else null,
                isDM = isDM, peer = peer, invite = invite, inviter = b.inviter, inviterName = b.inviterName,
                unread = if (invite) 0 else c?.notify ?: 0,
                highlight = if (invite) 0 else c?.highlight ?: 0,
                marked = !invite && b.marked,
                pinned = PINNED in b.tags, archived = ARCHIVED in b.tags,
                muted = muteNow, muteUntil = if (muteNow) b.muteUntil else null,
                encrypted = r.encrypted, last = last,
                preview = if (invite) "دعوت‌نامه" else preview(last, isDM),
                ts = last?.ts ?: b.fallbackTs,
                spaces = membership[id].orEmpty(),
                memberCount = r.joinedMemberCount,
            )
        }.sortedWith(byListOrder)

        val spaceRows = spaces.map { b ->
            val id = b.room.roomId.full
            SpaceSummary(id, displayName(b, false), b.room.avatarUrl?.takeIf { it.isNotEmpty() }, b.room.joinedMemberCount, children[id].orEmpty(), id in top)
        }
        return RoomList(rows, spaceRows, ready = true)
    }

    private fun preview(last: LastMessage?, isDM: Boolean): String {
        last ?: return ""
        if (last.call) return last.text
        val who = if (last.mine) "شما" else if (isDM) "" else "⁨" + last.senderName.split(" ")[0] + "⁩"
        return (if (who.isNotEmpty()) "$who: " else "") + last.text
    }

    private fun displayName(b: Base, invite: Boolean): String {
        val dn = b.room.name
        dn?.explicitName?.let { return it }
        val names = dn?.heroes?.map { b.heroNames[it.full] ?: it.full }.orEmpty()
        val count = names.size + (dn?.otherUsersCount ?: 0) + 1
        val state = if (names.isNotEmpty()) NameState.Names(names, count) else NameState.Empty()
        return roomName(state, if (invite) b.inviterName ?: b.inviter else null) ?: ""
    }

    /** The chat with this id from the list, if any. */
    fun room(id: String): Flow<RoomSummary?> = list.map { l -> l.rows.firstOrNull { it.id == id } }.distinctUntilChanged()

    // ---------- folders ----------

    private val orderKey = "panbeh.folderOrder:${me.full}"
    private val remoteOrder: StateFlow<List<String>?> = client.user.getAccountData<FolderOrder>()
        .map { c -> (c?.raw?.get("order") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    private val mine = MutableStateFlow<List<String>?>(null)

    init {
        scope.launch { remoteOrder.collect { mine.value = null; if (it != null) runCatching { store?.set(orderKey, Json.encodeToString(it)) } } }
    }

    private fun cachedOrder() = runCatching { store?.get(orderKey)?.let { Json.decodeFromString<List<String>>(it) } }.getOrNull() ?: emptyList()

    /** The saved tab order: account data, updated at once on a change, cached locally for the first paint. */
    val folderOrder: StateFlow<List<String>> = combine(mine, remoteOrder) { m, r -> m ?: r ?: cachedOrder() }
        .stateIn(scope, SharingStarted.Eagerly, cachedOrder())

    /** The tabs in the saved order: the four base folders and a folder per top-level space, with unread badges. */
    val folders: StateFlow<List<FolderTab>> = combine(list, folderOrder) { l, order ->
        val spaces = l.folderSpaces.associateBy { it.id }
        val all = BASE_FOLDERS + l.folderSpaces.map { Folder(it.id, it.name) }
        applyFolderOrder(all, order).map { FolderTab(it, l.unreadIn(it.id), spaces[it.id]) }
    }.stateIn(scope, SharingStarted.Eagerly, BASE_FOLDERS.map { FolderTab(it, 0) })

    /** Saves the tab order (app.panbeh.folder_order). */
    suspend fun setFolderOrder(order: List<String>) {
        mine.value = order
        runCatching { store?.set(orderKey, Json.encodeToString(order)) }
        api.user.setAccountData(FolderOrder(JsonObject(mapOf("order" to JsonArray(order.map(::JsonPrimitive))))), me)
            .onFailure { mine.value = null }.getOrThrow()
    }

    /** Moves a tab (drag and drop) and saves; the "all" tab stays first. */
    suspend fun moveFolder(from: Int, to: Int) {
        val ids = folders.value.map { it.folder.id }
        val next = moveFolder(ids, from, to)
        if (next != ids) setFolderOrder(next)
    }

    // ---------- chat actions ----------

    private suspend fun setTag(roomId: String, tag: String, on: Boolean) {
        if (on) api.room.setTag(me, RoomId(roomId), tag, TagEventContent.Tag()).getOrThrow()
        else api.room.deleteTag(me, RoomId(roomId), tag).getOrThrow()
    }

    /** Pins (m.favourite) or unpins a chat. */
    suspend fun pin(roomId: String, on: Boolean) = setTag(roomId, PINNED, on)

    /** Archives (u.archived) or restores a chat. */
    suspend fun archive(roomId: String, on: Boolean) = setTag(roomId, ARCHIVED, on)

    private suspend fun marked(rid: RoomId): Pair<Boolean?, Boolean?> =
        client.room.getAccountData<MarkedUnreadEventContent>(rid).first()?.unread to
            (client.room.getAccountData<FamedlyMarkedUnread>(rid).first()?.raw?.get("unread") as? JsonPrimitive)?.booleanOrNull

    /** Sets or clears "marked as unread" (m.marked_unread; the legacy com.famedly one is cleared too). */
    suspend fun setMarkedUnread(roomId: String, unread: Boolean) {
        val rid = RoomId(roomId)
        api.room.setAccountData(MarkedUnreadEventContent(unread), rid, me).getOrThrow()
        if (!unread && marked(rid).second == true)
            api.room.setAccountData(FamedlyMarkedUnread(JsonObject(mapOf("unread" to JsonPrimitive(false)))), rid, me).getOrThrow()
    }

    /** Read receipt and fully-read marker on the newest event, and the manual mark cleared. */
    suspend fun markRead(roomId: String) {
        val rid = RoomId(roomId)
        val last = client.room.getById(rid).first()?.lastEventId
        if (last != null) api.room.setReadMarkers(rid, fullyRead = last, read = last).getOrThrow()
        val (stable, legacy) = marked(rid)
        if (stable == true || legacy == true) setMarkedUnread(roomId, false)
    }

    /** The context-menu action: unread chats are marked read, read ones marked unread. */
    suspend fun toggleRead(row: RoomSummary) = if (row.isUnread) markRead(row.id) else setMarkedUnread(row.id, true)

    // ---------- mute ----------

    private suspend fun setMuted(roomId: String, mute: Boolean, until: Long?) {
        val rid = RoomId(roomId)
        val g = api.push
        val roomRules = g.getPushRules().getOrThrow().global.room.orEmpty()
        val overrides = g.getPushRules().getOrNull()?.global?.override.orEmpty()
        if (mute) {
            g.setPushRule("global", PushRuleKind.ROOM, roomId, SetPushRule.Request(actions = emptySet())).getOrThrow()
            overrides.firstOrNull { it.ruleId == roomId }?.let { g.deletePushRule("global", PushRuleKind.OVERRIDE, roomId) }
        } else {
            if (roomRules.any { it.ruleId == roomId }) g.deletePushRule("global", PushRuleKind.ROOM, roomId).getOrThrow()
            if (overrides.any { it.ruleId == roomId }) g.deletePushRule("global", PushRuleKind.OVERRIDE, roomId).getOrThrow()
        }
        val hadUntil = client.room.getAccountData<MuteUntil>(rid).first()?.raw?.get("until") != null
        if (until != null || hadUntil) {
            val c = if (mute && until != null) JsonObject(mapOf("until" to JsonPrimitive(until))) else JsonObject(emptyMap())
            api.room.setAccountData(MuteUntil(c), rid, me).getOrThrow()
        }
    }

    /** Mutes until turned back on. */
    suspend fun mute(roomId: String) = setMuted(roomId, true, null)

    /** Mutes for a span (or forever). */
    suspend fun mute(roomId: String, span: MuteSpan) = setMuted(roomId, true, span.ms?.let { System.currentTimeMillis() + it })

    /** Mutes until `until` (epoch ms); whichever client is running then unmutes. */
    suspend fun muteUntil(roomId: String, until: Long) = setMuted(roomId, true, until)

    suspend fun unmute(roomId: String) = setMuted(roomId, false, null)

    /** Unmutes every chat whose timed mute has run out. */
    suspend fun unmuteExpired(now: Long = System.currentTimeMillis()) {
        for (r in list.value.rows) if (r.muted && (r.muteUntil ?: Long.MAX_VALUE) <= now) runCatching { unmute(r.id) }
    }

    private var muteTimer: Job? = null

    /** Runs [unmuteExpired] every minute (after the first sync) until the scope ends. Safe to call twice. */
    fun startMuteTimer() {
        if (muteTimer?.isActive == true) return
        muteTimer = scope.launch {
            client.initialSyncDone.first { it }
            while (true) { unmuteExpired(); delay(60_000) }
        }
    }

    // ---------- leaving, invites ----------

    /** Leaves and forgets a chat (server and local), so it's gone from the list for good. */
    suspend fun leaveAndForget(roomId: String) {
        val rid = RoomId(roomId)
        val room = client.room.getById(rid).first()
        if (room?.membership != Membership.LEAVE) {
            val left = api.room.leaveRoom(rid)
            val e = left.exceptionOrNull()
            if (e != null) {
                if (e.errcode != "M_FORBIDDEN") throw e
                // not in the room at all: just drop what we have
                api.room.forgetRoom(rid)
                client.room.forgetRoom(rid, force = true)
                return
            }
            withTimeoutOrNull(10_000) { client.room.getById(rid).first { it == null || it.membership == Membership.LEAVE } }
        }
        api.room.forgetRoom(rid).getOrThrow()
        client.room.forgetRoom(rid, force = true)
    }

    /** Accepts an invite; a DM invite is recorded in m.direct. Returns the room id. */
    suspend fun acceptInvite(roomId: String): String {
        val rid = RoomId(roomId)
        val row = list.value.rows.firstOrNull { it.id == roomId }
        api.room.joinRoom(rid).getOrThrow()
        if (row?.isDM == true) row.inviter?.let { addDirect(it, roomId) }
        withTimeoutOrNull(10_000) { client.room.getById(rid).first { it?.membership == Membership.JOIN } }
        return roomId
    }

    /** Declines an invite and forgets it. */
    suspend fun declineInvite(roomId: String) = leaveAndForget(roomId)

    /** Adds a room to m.direct for a user (no-op when it is there already). */
    suspend fun addDirect(userId: String, roomId: String) = client.addDirect(userId, roomId)

    // ---------- typing, presence ----------

    /** Who is typing in each room (user ids, without me). */
    val typingByRoom: StateFlow<Map<String, Set<String>>> = client.room.usersTyping
        .map { m -> m.mapKeys { it.key.full }.mapValues { (_, t) -> t.users.map { it.full }.filter { it != me.full }.toSet() }.filterValues { it.isNotEmpty() } }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Display names of those typing in a room (me excluded). */
    fun typing(roomId: String): Flow<List<String>> = typingByRoom.map { it[roomId].orEmpty().toList() }.distinctUntilChanged().flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList())
        else combine(ids.map { u -> client.room.getState<MemberEventContent>(RoomId(roomId), u).map { it?.content?.displayName?.takeIf(String::isNotEmpty) ?: u } }) { it.toList() }
    }

    /** "در حال نوشتن" line for a row: the typist's first name in groups. */
    fun typingText(isDM: Boolean, names: List<String>): String? =
        names.firstOrNull()?.let { (if (isDM) "" else "⁨" + it.split(" ")[0] + "⁩ ") + "در حال نوشتن" }

    private val seen = ConcurrentHashMap<String, Seen>()

    /** A person's presence: the live online flag from sync, the last-seen time from /presence (cached for a minute). */
    fun presence(userId: String): Flow<PresenceInfo> {
        val u = UserId(userId)
        val fetched = flow {
            while (true) {
                val cached = seen[userId]
                if (cached == null || System.currentTimeMillis() - cached.at >= 60_000) {
                    api.user.getPresence(u).onSuccess { r ->
                        seen[userId] = Seen(r.presence == Presence.ONLINE || r.isCurrentlyActive == true, r.lastActiveAgo?.let { System.currentTimeMillis() - it }, System.currentTimeMillis())
                    }.onFailure { seen[userId] = Seen(false, null, System.currentTimeMillis()) }
                }
                emit(seen[userId])
                delay(60_000)
            }
        }.onStart { emit(seen[userId]) }
        return combine(client.user.getPresence(u), fetched) { p, f ->
            val online = p?.presence == Presence.ONLINE || f?.online == true
            PresenceInfo(online, lastSeen(online, f?.ts ?: p?.lastActive?.toEpochMilliseconds()))
        }.distinctUntilChanged().catch { emit(PresenceInfo(false, null)) }
    }
}
