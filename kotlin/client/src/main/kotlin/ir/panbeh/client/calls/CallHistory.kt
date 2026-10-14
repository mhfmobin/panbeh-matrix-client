package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.core.calls.CallEv
import ir.panbeh.core.calls.CallOutcome
import ir.panbeh.core.calls.CallState
import ir.panbeh.core.calls.isVideoOffer
import ir.panbeh.core.calls.legacyOutcome
import ir.panbeh.core.calls.rtcOutcome
import ir.panbeh.core.time.fmtDuration
import ir.panbeh.core.util.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** A call's start event: an m.rtc.notification (either spelling) or a legacy m.call.invite. Calling someone brings the chat up like a message. */
fun isCallStart(type: String) = type == CallTypes.RTC_NOTIFICATION || type == CallTypes.RTC_NOTIFICATION_STABLE || type == "m.call.invite"

/** A 1:1 call: video or not, and how it went. Null for a group call's "started" alert. [after] = the events after it, oldest first. */
fun callInfo(ev: CallEv, after: List<CallEv>, now: Long = System.currentTimeMillis()): Pair<Boolean, CallOutcome>? {
    if (ev.type == "m.call.invite") return isVideoOffer(ev.content) to legacyOutcome(ev, after, now)
    if (ev.content.string("notification_type") != "ring") return null
    val until = notificationExpiry(ev.content, ev.ts)
    return (ev.content.string("m.call.intent") == "video") to rtcOutcome(ev, if (until.isNaN()) Long.MIN_VALUE else until.toLong(), after, now)
}

/** One line of the Calls tab. */
data class CallRow(
    val roomId: RoomId,
    val eventId: String,
    val ts: Long,
    /** we called */
    val outgoing: Boolean,
    val video: Boolean,
    /** a call we missed or declined (shown red, and in the "missed" tab) */
    val missed: Boolean,
    val outcome: CallOutcome,
    val legacy: Boolean,
    /** "خروجی تصویری · ۱:۰۵" */
    val text: String,
)

private fun kind(video: Boolean) = if (video) "تصویری" else "صوتی"

/** The text under the name in the Calls tab: direction/outcome, kind, duration. */
fun callRowText(out: Boolean, video: Boolean, o: CallOutcome): String {
    val missed = o.state == CallState.MISSED || o.state == CallState.DECLINED
    val what = if (out) (if (missed) "بی‌پاسخ" else "خروجی") else if (o.state == CallState.DECLINED) "رد شده" else if (missed) "از دست رفته" else "ورودی"
    return "$what ${kind(video)}" + (o.duration?.takeIf { it != 0L }?.let { " · ${fmtDuration(it)}" } ?: "")
}

/** Every 1:1 call in one room's events (oldest first), newest first, without the ones still ringing. */
fun callRows(roomId: RoomId, events: List<RoomCallEvent>, me: String, now: Long = System.currentTimeMillis()): List<CallRow> =
    events.withIndex().filter { isCallStart(it.value.type) }.mapNotNull { (i, e) ->
        val (video, o) = callInfo(e.toCallEv(), events.subList(i + 1, events.size).map { it.toCallEv() }, now) ?: return@mapNotNull null
        if (o.state == CallState.RINGING) return@mapNotNull null
        val out = e.sender == me
        val missed = o.state == CallState.MISSED || o.state == CallState.DECLINED
        CallRow(roomId, e.eventId ?: "", e.ts, out, video, missed && !out, o, e.type == "m.call.invite", callRowText(out, video, o))
    }.sortedByDescending { it.ts }

/**
 * The Calls tab: 1:1 calls in what is loaded of each of [rooms], newest first. Only stored events count (recent history plus
 * what was scrolled back to).
 */
suspend fun MatrixClient.callHistory(rooms: Collection<RoomId>, limitPerRoom: Int = 300, now: Long = System.currentTimeMillis()): List<CallRow> =
    rooms.flatMap { callRows(it, recentCallEvents(it, limitPerRoom), userId.full, now) }.sortedByDescending { it.ts }

/** [callHistory] again whenever a call event arrives in [events]. */
fun MatrixClient.callHistoryFlow(rooms: Collection<RoomId>, events: Flow<RoomCallEvent>, limitPerRoom: Int = 300): Flow<List<CallRow>> =
    events.filter { isCallStart(it.type) || it.type == "m.call.answer" || it.type == "m.call.hangup" || it.type == "m.call.reject" || it.type == CallTypes.MEMBER || it.type.endsWith("rtc.decline") }
        .map { }.onStart { emit(Unit) }.map { callHistory(rooms, limitPerRoom) }

/** A 1:1 call's line in the timeline (src/ui/common.tsx callLine): who called, and how it went. [kind] is "تماس صوتی" / "تماس تصویری". */
fun callLine(mine: Boolean, who: String, kind: String, o: CallOutcome): String = when (o.state) {
    CallState.ENDED -> "$who $kind گرفت · ${fmtDuration(o.duration ?: 0)}"
    CallState.MISSED -> if (mine) "$kind بی‌پاسخ" else "$kind از دست رفته از $who"
    CallState.DECLINED -> if (mine) "$kind رد شد" else "$kind $who را رد کردید"
    else -> "$who $kind گرفت"
}
