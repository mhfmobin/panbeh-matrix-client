package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.EventContent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.m.call.CallEventContent
import ir.panbeh.client.events.CallMember
import ir.panbeh.client.events.CallReaction
import ir.panbeh.client.events.Reaction
import ir.panbeh.client.events.RtcDecline
import ir.panbeh.client.events.RtcDeclineStable
import ir.panbeh.client.events.RtcNotification
import ir.panbeh.client.events.RtcNotificationStable
import ir.panbeh.core.calls.CallEv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.*

/** A call-related room event, reduced to what the signalling code reads: its type, sender and content as JSON (plus the typed content). */
data class RoomCallEvent(
    val roomId: RoomId,
    val eventId: String?,
    val type: String,
    val sender: String,
    val ts: Long,
    val content: JsonObject,
    val stateKey: String?,
    val typed: EventContent?,
) {
    fun toCallEv() = CallEv(type, sender, ts, content, eventId)
    /** the event this one points at via m.relates_to */
    val relatesTo: String? get() = ((content["m.relates_to"] as? JsonObject)?.get("event_id") as? JsonPrimitive)?.contentOrNull
}

private fun JsonObjectBuilder.callBase(c: CallEventContent) {
    put("call_id", c.callId); put("version", c.version); c.partyId?.let { put("party_id", it) }
}

/** The JSON the pure helpers in core read, for the legacy m.call.* contents. */
private fun legacyJson(c: CallEventContent): Pair<String, JsonObject> = when (c) {
    is CallEventContent.Invite -> "m.call.invite" to buildJsonObject {
        callBase(c); put("lifetime", c.lifetime); c.invitee?.let { put("invitee", it) }
        putJsonObject("offer") { put("type", "offer"); put("sdp", c.offer.sdp) }
    }
    is CallEventContent.Candidates -> "m.call.candidates" to buildJsonObject { callBase(c) }
    is CallEventContent.Answer -> "m.call.answer" to buildJsonObject { callBase(c) }
    is CallEventContent.Hangup -> "m.call.hangup" to buildJsonObject { callBase(c) }
    is CallEventContent.Negotiate -> "m.call.negotiate" to buildJsonObject { callBase(c) }
    is CallEventContent.Reject -> "m.call.reject" to buildJsonObject { callBase(c) }
    is CallEventContent.SelectAnswer -> "m.call.select_answer" to buildJsonObject { callBase(c); put("selected_party_id", c.selectedPartyId) }
    is CallEventContent.SdpStreamMetadataChanged -> "m.call.sdp_stream_metadata_changed" to buildJsonObject { callBase(c) }
}

/** [content] as a call event, or null when it is not call-related. */
fun callEventOf(roomId: RoomId, eventId: String?, sender: String, ts: Long, stateKey: String?, content: EventContent): RoomCallEvent? {
    val (type, json) = when (content) {
        is CallMember -> CallTypes.MEMBER to content.raw
        is RtcNotification -> CallTypes.RTC_NOTIFICATION to content.raw
        is RtcNotificationStable -> CallTypes.RTC_NOTIFICATION_STABLE to content.raw
        is RtcDecline -> CallTypes.RTC_DECLINE to content.raw
        is RtcDeclineStable -> CallTypes.RTC_DECLINE_STABLE to content.raw
        is CallReaction -> CallTypes.CALL_REACTION to content.raw
        is Reaction -> "m.reaction" to content.raw
        is CallEventContent -> legacyJson(content)
        is UnknownEventContent -> content.eventType to content.raw
        else -> return null
    }
    return RoomCallEvent(roomId, eventId, type, sender, ts, json, stateKey, content)
}

/** A timeline event as a call event (null while undecrypted or for other types). The relation of typed contents is merged into the JSON. */
fun TimelineEvent.toCallEvent(): RoomCallEvent? {
    val c = content?.getOrNull() ?: return null
    val ev = event
    val e = callEventOf(ev.roomId, ev.id.full, ev.sender.full, ev.originTimestamp, (ev as? ClientEvent.RoomEvent.StateEvent<*>)?.stateKey, c) ?: return null
    val rel = (c as? de.connect2x.trixnity.core.model.events.MessageEventContent)?.relatesTo
    if (rel == null || e.content["m.relates_to"] != null) return e
    val eid = when (rel) {
        is de.connect2x.trixnity.core.model.events.m.RelatesTo.Reference -> rel.eventId.full
        is de.connect2x.trixnity.core.model.events.m.RelatesTo.Annotation -> rel.eventId.full
        else -> null
    } ?: return e
    return e.copy(content = JsonObject(e.content + ("m.relates_to" to buildJsonObject { put("event_id", eid) })))
}

/** Live call-related events in all rooms (needs a running sync). */
fun MatrixClient.callEvents(): Flow<RoomCallEvent> = room.getTimelineEventsFromNowOn().mapNotNull { it.toCallEvent() }

/** Sends [c] through the outbox (encrypted in encrypted rooms) and returns its event id once the server has it (null on timeout). */
suspend fun MatrixClient.sendRoomEvent(roomId: RoomId, c: de.connect2x.trixnity.core.model.events.MessageEventContent): de.connect2x.trixnity.core.model.EventId? {
    val txn = room.sendMessage(roomId) { content(c) }
    return kotlinx.coroutines.withTimeoutOrNull(30_000) {
        room.getOutbox(roomId, txn).filterNotNull().mapNotNull { m ->
            m.sendError?.let { throw IllegalStateException("send failed: $it") }
            m.eventId
        }.first()
    }
}

/** The call-related events among the last [limit] stored timeline events of a room (oldest first). Nothing is fetched from the server. */
suspend fun MatrixClient.recentCallEvents(roomId: RoomId, limit: Int = 200): List<RoomCallEvent> =
    room.getLastTimelineEvents(roomId) { fetchTimeout = kotlin.time.Duration.ZERO; decryptionTimeout = 5.seconds }
        .filterNotNull().first().take(limit).toList().mapNotNull { it.first().toCallEvent() }.sortedBy { it.ts }
