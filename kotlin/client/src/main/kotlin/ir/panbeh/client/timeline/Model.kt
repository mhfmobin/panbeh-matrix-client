package ir.panbeh.client.timeline

import ir.panbeh.core.timeline.Row
import kotlinx.serialization.json.JsonObject

/** Local-echo state of a message I sent (null on events that came from the server). */
enum class SendState { SENDING, SENT, FAILED }

/** Whether the content of an event is readable. */
enum class Decryption { OK, DECRYPTING, FAILED }

enum class ItemKind {
    /** a message, sticker or poll */
    MESSAGE,
    /** a centered pill: [TimelineItem.notice] holds the text */
    NOTICE,
}

/** One reaction key on a message. key is an emoji, or an mxc:// URI for a custom emoji ([shortcode] = ":code:"). */
data class ReactionGroup(
    val key: String,
    val count: Int,
    val mine: Boolean,
    /** user ids, oldest reaction first */
    val senders: List<String>,
    val shortcode: String?,
    /** the event to redact to take my reaction back */
    val myEventId: String?,
)

/** Someone whose read receipt covers an event; ts = when the receipt was set (0 if unknown). */
data class ReadBy(val userId: String, val name: String, val avatar: String?, val ts: Long)

data class ThreadSummary(val count: Int, val participated: Boolean)

/** A UI-ready timeline entry. [content] is the JSON of the event after the latest edit by its author. */
data class TimelineItem(
    /** stable across local echo → server echo (the transaction id, else the event id): use as list key */
    val key: String,
    /** null while a local echo hasn't been sent yet */
    val eventId: String?,
    val txnId: String?,
    val roomId: String,
    val sender: String,
    val senderName: String,
    /** mxc:// of the sender's current avatar */
    val senderAvatar: String?,
    val ts: Long,
    /** event type ("m.room.message", "m.sticker", "org.matrix.msc3381.poll.start", state types, ...) */
    val type: String,
    val msgtype: String?,
    val content: JsonObject,
    val isMine: Boolean,
    val kind: ItemKind,
    /** text of a [ItemKind.NOTICE] pill */
    val notice: String? = null,
    val redacted: Boolean = false,
    val edited: Boolean = false,
    val decryption: Decryption = Decryption.OK,
    /** the event this one replies to (not the thread fallback) */
    val replyTo: String? = null,
    /** the root of the thread this event is a reply in */
    val threadRoot: String? = null,
    /** set on a thread root */
    val thread: ThreadSummary? = null,
    val reactions: List<ReactionGroup> = emptyList(),
    /** receipts covering this event, newest first (me excluded) */
    val readBy: List<ReadBy> = emptyList(),
    val sendState: SendState? = null,
    /** Persian text of why a send failed */
    val sendError: String? = null,
    /** buildRows album tag: "t:<id>", "h" (may group by time) or null */
    val album: String? = null,
    /** the text a search finds / the one-line preview of this item */
    val preview: String = "",
    val forwardedFrom: Forwarded? = null,
    /** I (or whoever) pinned it */
    val pinned: Boolean = false,
) {
    val isLocal get() = eventId == null || sendState != null && sendState != SendState.SENT
}

/** app.panbeh.forwarded {sender, name} */
data class Forwarded(val sender: String, val name: String?)

/** What the paged timeline shows now. */
data class TimelineState(
    val items: List<TimelineItem> = emptyList(),
    /** [ir.panbeh.core.timeline.buildRows] over [items] (row ids are item keys) */
    val rows: List<Row> = emptyList(),
    /** the unread divider follows the item with this key; placed once on open */
    val unreadAfter: String? = null,
    val initialized: Boolean = false,
    val loadingOlder: Boolean = false,
    val loadingNewer: Boolean = false,
    val canLoadOlder: Boolean = false,
    /** true in a window around an old event until paging reaches the newest messages */
    val canLoadNewer: Boolean = false,
    /** showing the newest messages (following the live end makes sense) */
    val atLive: Boolean = true,
    /** the event the last jump landed on; [focusSeq] changes with every jump (scroll + flash it) */
    val focus: String? = null,
    val focusSeq: Int = 0,
    /** bumped when the view is replaced by a window (the list should re-anchor / remount) */
    val windowSeq: Int = 0,
) {
    val byKey: Map<String, TimelineItem> by lazy { items.associateBy { it.key } }
}

/** A person typing in a room. */
data class TypingUser(val userId: String, val name: String)
