package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.RoomUser
import de.connect2x.trixnity.client.store.RoomUserReceipts
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.RedactedEventContent
import de.connect2x.trixnity.core.model.events.UnsignedRoomEventData
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.ServerAggregation
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import ir.panbeh.client.events.Reaction
import ir.panbeh.client.events.Types
import ir.panbeh.core.admin.errorText
import ir.panbeh.core.calls.CallEv
import ir.panbeh.core.calls.isVideoOffer
import ir.panbeh.core.calls.legacyOutcome
import ir.panbeh.core.calls.rtcOutcome
import ir.panbeh.core.media.isGif
import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** An event with what is derived from other events: reactions, whether it was edited, thread replies. */
internal data class Entry(val te: TimelineEvent, val reactions: List<TimelineEvent> = emptyList(), val edited: Boolean = false, val threadReplies: Int = 0)

/** Everything besides the events themselves that items are made from. */
internal class Ctx(
    val members: Map<UserId, RoomUser> = emptyMap(),
    val receipts: Map<UserId, RoomUserReceipts> = emptyMap(),
    val outbox: List<RoomOutboxMessage<*>> = emptyList(),
    val pinned: Set<String> = emptySet(),
)

/** The result of one assembly pass. */
internal class Built(val items: List<TimelineItem>, val slim: List<Slim>) {
    val eventIds get() = slim.map { it.id }
}

/** An event as the unread-marker logic sees it; [key] is the item's key when it's shown. */
internal class Slim(val id: String, val sender: String, val key: String?, val isMessage: Boolean)

private val HIDDEN_TYPES = setOf("m.reaction", "m.room.redaction")

/** Turns Trixnity timeline events into [TimelineItem]s (see Model.kt). Pure apart from [receiptTs]. */
internal class Assembler(private val codec: Codec, private val roomId: String, private val me: String) {

    private fun nameOf(ctx: Ctx, id: String): String? = ctx.members[UserId(id)]?.name

    private fun relatesOf(c: Any?): RelatesTo? = (c as? MessageEventContent)?.relatesTo

    /** The content with its relation put back (edits replace the whole content, keeping the original's relation). */
    private fun contentJson(content: de.connect2x.trixnity.core.model.events.RoomEventContent, rel: RelatesTo?): JsonObject {
        val j = codec.jsonOf(content)
        if (content !is MessageEventContent) return j
        return when {
            rel == null -> if ("m.relates_to" in j) JsonObject(j - "m.relates_to") else j
            rel is RelatesTo.Replace -> j
            else -> JsonObject(j + ("m.relates_to" to codec.json.encodeToJsonElement(RelatesTo.serializer(), rel)))
        }
    }

    private fun sendErrorText(e: RoomOutboxMessage.SendError): String = when (e) {
        RoomOutboxMessage.SendError.NoEventPermission, RoomOutboxMessage.SendError.NoMediaPermission -> errorText("M_FORBIDDEN")!!
        RoomOutboxMessage.SendError.MediaTooLarge -> errorText("M_TOO_LARGE")!!
        is RoomOutboxMessage.SendError.BadRequest -> e.errorResponse.error
        is RoomOutboxMessage.SendError.EncryptionError -> e.reason ?: "رمزنگاری پیام ناموفق بود."
        RoomOutboxMessage.SendError.EncryptionAlgorithmNotSupported -> "این روش رمزنگاری پشتیبانی نمی‌شود."
        is RoomOutboxMessage.SendError.Unknown -> e.message ?: e.errorResponse?.error ?: "ارسال نشد."
    }

    private fun reactionsOf(rs: List<TimelineEvent>): List<ReactionGroup> {
        val byKey = LinkedHashMap<String, MutableList<TimelineEvent>>()
        for (r in rs.sortedBy { it.originTimestamp }) {
            val c = r.content?.getOrNull() as? Reaction ?: continue
            val key = c.key ?: continue
            byKey.getOrPut(key) { mutableListOf() }.add(r)
        }
        return byKey.map { (key, evs) ->
            // one reaction per sender and key counts (the newest)
            val per = evs.groupBy { it.event.sender.full }.map { (_, l) -> l.maxBy { it.originTimestamp } }.sortedBy { it.originTimestamp }
            ReactionGroup(
                key, per.size, per.any { it.event.sender.full == me }, per.map { it.event.sender.full },
                (per.first().content!!.getOrThrow() as Reaction).shortcode,
                per.firstOrNull { it.event.sender.full == me }?.event?.id?.full,
            )
        }.sortedByDescending { it.count }
    }

    /** Event JSON for the call outcome helpers. */
    private fun callEv(e: Entry): CallEv {
        val c = e.te.content?.getOrNull() ?: e.te.event.content
        return CallEv(codec.typeOf(c), e.te.event.sender.full, e.te.originTimestamp, codec.jsonOf(c), e.te.event.id.full)
    }

    private fun albumOf(c: JsonObject, replyTo: String?, local: Boolean): String? {
        val mt = c.string("msgtype")
        if ((mt != "m.image" && mt != "m.video") || isGif(mt, c["info"] as? JsonObject) || c["app.panbeh.video_note"].let { it != null && it != JsonPrimitive(false) } || local) return null
        val tag = (c["app.panbeh.album"] as? JsonObject)?.string("id")
        if (tag != null) return "t:$tag"
        return if (captionOf(c).isNotEmpty() || replyTo != null) null else "h"
    }

    private fun senderOf(ctx: Ctx, id: String) = Triple(id, nameOf(ctx, id) ?: id, ctx.members[UserId(id)]?.event?.content?.avatarUrl)

    private val calls = setOf(Types.RTC_NOTIFICATION, "m.rtc.notification", "m.call.invite")

    /**
     * Items for [entries] (oldest first), followed by local echoes from the outbox that aren't in the timeline yet.
     * [threadRoot]: build the items of that thread (its replies and root) instead of the main timeline (without thread replies).
     */
    suspend fun build(entries: List<Entry>, ctx: Ctx, threadRoot: String?, showRedacted: Boolean, all: Boolean = false, receiptTs: suspend (String) -> Long? = { null }): Built {
        val ids = entries.map { it.te.event.id.full }
        val index = ids.withIndex().associate { it.value to it.index }
        val readPos = readPositions(entries, ctx, index, receiptTs)

        val items = ArrayList<TimelineItem>()
        entries.forEachIndexed { i, e ->
            val te = e.te
            val ev = te.event
            val encrypted = ev.content is EncryptedMessageEventContent
            val content = te.content?.getOrNull() ?: if (encrypted) null else ev.content
            val decryption = when {
                !encrypted -> Decryption.OK
                te.content == null -> Decryption.DECRYPTING
                te.content!!.isFailure -> Decryption.FAILED
                else -> Decryption.OK
            }
            val clearRel = relatesOf(ev.content)
            val rel = relatesOf(content) ?: clearRel
            val type = if (content == null) "m.room.encrypted" else codec.typeOf(content)
            if (type in HIDDEN_TYPES || rel is RelatesTo.Replace || rel is RelatesTo.Annotation) return@forEachIndexed
            val id = ev.id.full
            val sender = ev.sender.full
            val thread = (rel as? RelatesTo.Thread)?.eventId?.full
            if (!all && threadRoot == null && thread != null) return@forEachIndexed
            if (!all && threadRoot != null && thread != threadRoot && id != threadRoot) return@forEachIndexed
            val redacted = content is RedactedEventContent
            val c = if (content == null || redacted) JsonObject(emptyMap()) else contentJson(content, rel)
            val replyTo = rel?.replyTo?.eventId?.full?.takeUnless { rel is RelatesTo.Thread && rel.isFallingBack == true }
            val (_, name, avatar) = senderOf(ctx, sender)
            val state = ev as? RoomEvent.StateEvent<*>
            val prev = ((ev.unsigned as? UnsignedRoomEventData.UnsignedStateEventData)?.previousContent)?.let { codec.jsonOf(it) } ?: JsonObject(emptyMap())
            val mt = c.string("msgtype")
            val message = isMessageType(type) && mt != VERIFY_REQUEST && state == null
            var call: CallLine? = null
            if (!redacted && type in calls) {
                val after = entries.drop(i + 1).map { callEv(it) }
                val cls = c.string("notification_type")
                call = when {
                    type == "m.call.invite" -> CallLine(isVideoOffer(c), false, legacyOutcome(CallEv(type, sender, te.originTimestamp, c, id), after))
                    cls != "ring" -> CallLine(c.string("m.call.intent") == "video", true, null)
                    else -> {
                        val senderTs = c.number("sender_ts")
                        val base = if (senderTs != null && kotlin.math.abs(senderTs - te.originTimestamp) <= 20_000) senderTs.toLong() else te.originTimestamp
                        CallLine(c.string("m.call.intent") == "video", false, rtcOutcome(CallEv(type, sender, te.originTimestamp, c, id), base + (c.number("lifetime")?.toLong() ?: 0L), after))
                    }
                }
            }
            val notice = if (message || redacted) null else noticeText(type, c, prev, state?.stateKey, sender, bdi(name), { nameOf(ctx, it) }, call, sender == me)
            if (!message && notice == null && !redacted) return@forEachIndexed
            if (redacted && !showRedacted) return@forEachIndexed
            val txn = ev.unsigned?.transactionId
            val reads = readPos.filter { (_, p) -> p.pos >= i }.map { (u, p) -> ReadBy(u, nameOf(ctx, u) ?: u, ctx.members[UserId(u)]?.event?.content?.avatarUrl, p.ts) }.sortedByDescending { it.ts }
            val fwd = (c["app.panbeh.forwarded"] as? JsonObject)?.let { Forwarded(it.string("sender") ?: "", it.string("name")) }
            val serverThread = (ev.unsigned?.relations?.relations?.get(RelationType.Thread) as? ServerAggregation.Thread)
            val summary = if (threadRoot == null && (e.threadReplies > 0 || serverThread != null)) ThreadSummary(maxOf(e.threadReplies, serverThread?.count?.toInt() ?: 0), serverThread?.currentUserParticipated ?: false) else null
            items.add(
                TimelineItem(
                    key = txn ?: id, eventId = id, txnId = txn, roomId = roomId, sender = sender, senderName = name, senderAvatar = avatar,
                    ts = te.originTimestamp, type = type, msgtype = mt, content = c, isMine = sender == me,
                    kind = if (message || redacted) ItemKind.MESSAGE else ItemKind.NOTICE, notice = notice, redacted = redacted, edited = e.edited && !redacted,
                    decryption = decryption, replyTo = replyTo, threadRoot = thread, thread = summary, reactions = reactionsOf(e.reactions),
                    readBy = reads, album = if (message) albumOf(c, replyTo, false) else null,
                    preview = if (notice != null) notice else previewText(type, c, name, decryption), forwardedFrom = fwd, pinned = id in ctx.pinned,
                ),
            )
        }

        // local echoes: outbox messages the timeline doesn't have yet
        val have = HashSet<String>().also { s -> items.forEach { it.txnId?.let(s::add); it.eventId?.let(s::add) } }
        entries.forEach { e -> e.te.event.unsigned?.transactionId?.let(have::add) }
        for (o in ctx.outbox.sortedBy { it.createdAt }) {
            if (o.isDraft || o.transactionId in have || o.eventId?.full in have) continue
            val content = o.content
            val type = codec.typeOf(content)
            if (!isMessageType(type)) continue
            val rel = content.relatesTo
            if (rel is RelatesTo.Replace || rel is RelatesTo.Annotation) continue
            val thread = (rel as? RelatesTo.Thread)?.eventId?.full
            if (threadRoot == null && thread != null) continue
            if (threadRoot != null && thread != threadRoot) continue
            val c = contentJson(content, rel)
            val replyTo = rel?.replyTo?.eventId?.full?.takeUnless { rel is RelatesTo.Thread && rel.isFallingBack == true }
            val (_, name, avatar) = senderOf(ctx, me)
            val failed = o.sendError != null
            items.add(
                TimelineItem(
                    key = o.transactionId, eventId = o.eventId?.full, txnId = o.transactionId, roomId = roomId, sender = me, senderName = name, senderAvatar = avatar,
                    ts = o.createdAt.toEpochMilliseconds(), type = type, msgtype = c.string("msgtype"), content = c, isMine = true, kind = ItemKind.MESSAGE,
                    replyTo = replyTo, threadRoot = thread, sendState = if (failed) SendState.FAILED else if (o.sentAt != null) SendState.SENT else SendState.SENDING,
                    sendError = o.sendError?.let(::sendErrorText), album = albumOf(c, replyTo, failed), preview = previewText(type, c, name),
                    forwardedFrom = (c["app.panbeh.forwarded"] as? JsonObject)?.let { Forwarded(it.string("sender") ?: "", it.string("name")) },
                ),
            )
        }
        val byEvent = items.filter { it.eventId != null }.associateBy { it.eventId }
        return Built(items, entries.map { e ->
            val it = byEvent[e.te.event.id.full]
            Slim(e.te.event.id.full, e.te.event.sender.full, it?.key, it != null && it.kind == ItemKind.MESSAGE && !it.redacted)
        })
    }

    private class Pos(val pos: Int, val ts: Long)

    /** For each user (not me): the index in [entries] their newest receipt covers, so everything up to it counts as read by them. */
    private suspend fun readPositions(entries: List<Entry>, ctx: Ctx, index: Map<String, Int>, receiptTs: suspend (String) -> Long?): Map<String, Pos> {
        if (entries.isEmpty()) return emptyMap()
        val out = HashMap<String, Pos>()
        val tss = entries.map { it.te.originTimestamp }
        for ((user, r) in ctx.receipts) {
            if (user.full == me) continue
            var best: Pos? = null
            for (type in listOf(ReceiptType.Read, ReceiptType.PrivateRead)) {
                val rc = r.receipts[type] ?: continue
                val eid = rc.eventId.full
                val pos = index[eid] ?: receiptTs(eid)?.let { ts ->
                    // an event outside the loaded range: it sits after it if it's newer than the newest loaded one
                    if (ts >= tss.last()) entries.size - 1 else if (ts < tss.first()) -1 else tss.indexOfLast { it <= ts }
                } ?: continue
                if (best == null || pos > best.pos) best = Pos(pos, rc.receipt.timestamp)
            }
            if (best != null && best.pos >= 0) out[user.full] = best
        }
        // members that left don't count
        return out.filterKeys { u -> ctx.members[UserId(u)]?.event?.content?.membership.let { it == null || it == Membership.JOIN } }
    }
}
