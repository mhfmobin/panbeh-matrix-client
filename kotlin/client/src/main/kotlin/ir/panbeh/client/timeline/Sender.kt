package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.flattenNotNull
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.PowerLevel
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import ir.panbeh.core.text.Mention
import ir.panbeh.core.util.string
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** The event a new message replies to. */
data class ReplyTarget(val eventId: String, val sender: String)

fun TimelineItem.replyTarget() = ReplyTarget(eventId ?: error("not sent yet"), sender)

/** Everything beyond the text that goes into a new message. */
data class TextOptions(
    /** people picked from the "@" list (typed "@Name" is detected on its own) */
    val mentions: List<Mention> = emptyList(),
    /** custom emoji ":code:" → mxc, first pack's wins */
    val emotes: Map<String, String> = emptyMap(),
    /** send as an emote (m.emote) */
    val emote: Boolean = false,
    val replyTo: ReplyTarget? = null,
    /** send into this thread (the event id of its root) */
    val thread: String? = null,
)

/**
 * Sending messages and acting on them in a room. Everything goes through Trixnity's outbox (survives restarts, encrypts,
 * retries) except redactions and reports, which are plain requests. Functions return the outbox transaction id where there is one.
 */
class Sender(val client: MatrixClient) {
    internal val codec = Codec(client)
    private val me get() = client.userId.full
    private val typingAt = HashMap<String, Long>()
    private val typingLock = Mutex()

    /** Puts a message of [type] with [content] in the outbox. [thread]/[replyTo] add the relation unless [content] has one. */
    suspend fun sendEvent(roomId: String, type: String, content: JsonObject, thread: String? = null, replyTo: ReplyTarget? = null): String {
        var c = content
        if ("m.relates_to" !in c && (thread != null || replyTo != null))
            c = with(Compose) { c.withRelation(relation(replyTo?.eventId, thread, thread?.let { latestInThread(roomId, it) })) }
        val out = codec.outgoing(type, c)
        return client.room.sendMessage(RoomId(roomId)) { content(out) }
    }

    /** The newest event of a thread (the fallback "reply" target of a new thread message). */
    suspend fun latestInThread(roomId: String, root: String): String =
        client.related(RoomId(roomId), EventId(root), RelationType.Thread).first().maxByOrNull { it.originTimestamp }?.event?.id?.full ?: root

    /** Joined members other than me, to detect typed "@Name" mentions. */
    suspend fun mentionable(roomId: String): List<Compose.Candidate> {
        client.user.loadMembers(RoomId(roomId))
        return client.user.getAll(RoomId(roomId)).flattenNotNull().first().values
            .filter { it.event.content.membership == Membership.JOIN && it.userId.full != me }
            .map { Compose.Candidate(it.name, it.userId.full) }
    }

    /** Whether "@room" in a message pings the room: my power level reaches notifications.room. */
    suspend fun canPingRoom(roomId: String): Boolean {
        val need = client.room.getState<PowerLevelsEventContent>(RoomId(roomId)).first()?.content?.notifications?.get("room") ?: 50L
        return when (val l = client.user.getPowerLevel(RoomId(roomId), client.userId).first()) {
            is PowerLevel.Creator -> true
            is PowerLevel.User -> l.level >= need
        }
    }

    /** The content JSON of a new text message (also what scheduling needs). */
    suspend fun composeText(roomId: String, text: String, o: TextOptions = TextOptions()): JsonObject {
        val body = text.trim()
        val c = Compose.text(
            body, if (o.emote) "m.emote" else "m.text", o.mentions, mentionable(roomId), o.emotes, o.replyTo?.sender, me,
            canPingRoom = Regex("(^|\\s)@room\\b").containsMatchIn(body) && canPingRoom(roomId),
        )
        return with(Compose) { c.withRelation(relation(o.replyTo?.eventId, o.thread, o.thread?.let { latestInThread(roomId, it) })) }
    }

    /** Sends a text (or emote) message: markdown subset, mention links + m.mentions, custom emoji, reply / thread. */
    suspend fun sendText(roomId: String, text: String, o: TextOptions = TextOptions()): String =
        sendEvent(roomId, "m.room.message", composeText(roomId, text, o))

    /**
     * Edits one of my messages ([item]'s current content has the edits applied). [newText] is the whole new text (a caption for media).
     * Null when nothing changed.
     */
    suspend fun edit(roomId: String, item: TimelineItem, newText: String, o: TextOptions = TextOptions()): String? {
        val id = item.eventId ?: error("not sent yet")
        val text = newText.trim()
        val next = Compose.text(text, item.msgtype ?: "m.text", o.mentions, mentionable(roomId), o.emotes, null, me, canPingRoom = false)
        val content = (if (item.type == "m.room.message") Compose.edit(id, item.content, next, text) else null) ?: return null
        return sendEvent(roomId, "m.room.message", content)
    }

    /**
     * Reaction on [target]: takes mine back when I already reacted with [key], else adds it. A custom emoji's key is its mxc and
     * [shortcode] its ":code:". Returns true when a reaction was added.
     */
    suspend fun toggleReaction(roomId: String, target: TimelineItem, key: String, shortcode: String? = null): Boolean {
        val mine = target.reactions.firstOrNull { it.key == key }?.myEventId
        if (mine != null) { client.api.room.redactEvent(RoomId(roomId), EventId(mine)).getOrThrow(); return false }
        sendEvent(roomId, "m.reaction", Compose.reaction(target.eventId ?: error("not sent yet"), key, shortcode))
        return true
    }

    /** Deletes messages for everyone (all items of an album), and any of their other items; throws the first failure. */
    suspend fun redact(roomId: String, items: List<TimelineItem>, reason: String? = null) {
        for (i in items) client.api.room.redactEvent(RoomId(roomId), EventId(i.eventId ?: continue), reason).getOrThrow()
    }

    suspend fun redact(roomId: String, item: TimelineItem, reason: String? = null) = redact(roomId, listOf(item), reason)

    /** Copies messages into other rooms: rooms in parallel, messages in order. Polls can't be forwarded. */
    suspend fun forward(roomIds: List<String>, items: List<TimelineItem>) {
        val list = items.filter { !isPollStart(it.type) && it.eventId != null }
        coroutineScope {
            roomIds.map { r ->
                async {
                    for (i in list) sendEvent(r, i.type, Compose.forward(i.content, i.sender, i.senderName))
                }
            }.forEach { it.await() }
        }
    }

    /** Reports to the homeserver's admins (score -100 = most offensive) with an optional reason. */
    suspend fun report(roomId: String, item: TimelineItem, reason: String? = null) {
        client.reportEvent(roomId, item.eventId ?: error("not sent yet"), -100, reason?.ifBlank { null })
    }

    /** An m.sticker. */
    suspend fun sendSticker(roomId: String, body: String, url: String, info: JsonObject? = null, replyTo: ReplyTarget? = null, thread: String? = null): String =
        sendEvent(roomId, "m.sticker", Compose.sticker(body, url, info), thread, replyTo)

    /** A saved GIF: its content as saved, sent again. */
    suspend fun sendGif(roomId: String, gif: JsonObject, replyTo: ReplyTarget? = null, thread: String? = null): String =
        sendEvent(roomId, "m.room.message", gif, thread, replyTo)

    /** A location (geo: URI with accuracy). */
    suspend fun sendLocation(roomId: String, lat: Double, lon: Double, accuracyMeters: Int, replyTo: ReplyTarget? = null, thread: String? = null): String =
        sendEvent(roomId, "m.room.message", Compose.location(lat, lon, accuracyMeters), thread, replyTo)

    /** Typing notification: on is throttled to once per 3 s and lasts 5 s on the server; off at once. Errors are ignored. */
    suspend fun typing(roomId: String, on: Boolean) {
        val now = System.currentTimeMillis()
        typingLock.withLock {
            if (on && now - (typingAt[roomId] ?: 0) < 3000) return
            if (!on && (typingAt[roomId] ?: 0) == 0L) return
            typingAt[roomId] = if (on) now else 0
        }
        client.api.room.setTyping(RoomId(roomId), client.userId, on, 5000)
    }

    /**
     * Read receipt (+ fully-read marker in the main timeline) for [eventId]. Call it only while the user is looking at the room
     * (visible, focused, unlocked, recent input). A thread's receipt carries the thread id and sets no marker.
     */
    suspend fun markRead(roomId: String, eventId: String, thread: String? = null) {
        val r = RoomId(roomId)
        val e = EventId(eventId)
        if (thread == null) client.api.room.setReadMarkers(r, fullyRead = e, read = e).getOrThrow()
        else client.api.room.setReceipt(r, e, threadId = EventId(thread)).getOrThrow()
    }

    /** Sends a failed outbox message again. */
    suspend fun retry(roomId: String, txnId: String) = client.room.retrySendMessage(RoomId(roomId), txnId)

    /** Removes an unsent / failed outbox message. */
    suspend fun cancel(roomId: String, txnId: String) = client.room.cancelSendMessage(RoomId(roomId), txnId)
}

internal fun JsonObject.str(k: String) = string(k)
