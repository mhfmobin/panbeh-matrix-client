package ir.panbeh.core.calls

import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import ir.panbeh.core.util.truthy
import kotlinx.serialization.json.*

/** True if an m.mentions object (content["m.mentions"]) names the room or `me`. */
private fun aimedAt(c: JsonObject, me: String): Boolean {
    val m = c["m.mentions"] as? JsonObject ?: return false
    return m["room"].truthy() || (m["user_ids"] as? JsonArray)?.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == me } == true
}

/** An m.rtc.notification (MSC4075) that should ring `me` now: a "ring" (not a group "notification"), aimed at us or the room, before `until`. NaN until = no. */
fun isRing(c: JsonObject, until: Double, me: String, now: Long = System.currentTimeMillis()) =
    c.string("notification_type") == "ring" && aimedAt(c, me) && until > now

/** A group call that just started (a "notification", not a ring): worth a quiet notification. */
fun isGroupCallAlert(c: JsonObject, until: Double, me: String, now: Long = System.currentTimeMillis()) =
    c.string("notification_type") == "notification" && aimedAt(c, me) && until > now

/** A legacy 1:1 m.call.invite (old Element, FluffyChat, Nheko…) that should ring `me` now: unexpired and not aimed at someone else. `c` is its content. */
fun isLegacyRing(c: JsonObject, ts: Long, me: String, now: Long = System.currentTimeMillis()): Boolean {
    val invitee = c.string("invitee")
    return ts + (c.number("lifetime") ?: 0.0) > now && (invitee.isNullOrEmpty() || invitee == me)
}

/** Whether the invite's SDP offer (content.offer.sdp) has a video line. */
fun isVideoOffer(c: JsonObject): Boolean {
    val sdp = ((c["offer"] as? JsonObject)?.string("sdp")) ?: ""
    return sdp.split('\n', '\r', ' ', ' ').any { it.startsWith("m=video") }
}

// ---------- how a call went (for its line in the timeline) ----------

/** A room event as the call helpers read it. */
data class CallEv(val type: String, val sender: String, val ts: Long, val content: JsonObject, val id: String? = null)

enum class CallState { RINGING, ONGOING, MISSED, DECLINED, ENDED }

/** ringing/ongoing: nothing to add yet. ended: answered, for `duration` ms. */
data class CallOutcome(val state: CallState, val duration: Long? = null)

private const val MEMBER = "org.matrix.msc3401.call.member"
private fun isEmpty(c: JsonObject) = c.isEmpty()

/** A legacy m.call.invite, from the events after it in the room (oldest first). */
fun legacyOutcome(invite: CallEv, after: List<CallEv>, now: Long = System.currentTimeMillis()): CallOutcome {
    val same = after.filter { it.content["call_id"] == invite.content["call_id"] }
    val answer = same.firstOrNull { it.type == "m.call.answer" }
    val end = same.firstOrNull { it.type == "m.call.hangup" || it.type == "m.call.reject" }
    if (answer != null) return if (end != null) CallOutcome(CallState.ENDED, end.ts - answer.ts) else CallOutcome(CallState.ONGOING)
    if (end?.type == "m.call.reject") return CallOutcome(CallState.DECLINED)
    return if (end != null || invite.ts + (invite.content.number("lifetime") ?: 0.0) <= now) CallOutcome(CallState.MISSED) else CallOutcome(CallState.RINGING)
}

/** A MatrixRTC ring (m.rtc.notification) ringing until `until`: answered once someone else's call membership shows up, over when one side leaves. */
fun rtcOutcome(ring: CallEv, until: Long, after: List<CallEv>, now: Long = System.currentTimeMillis()): CallOutcome {
    val members = after.filter { it.type == MEMBER }
    val joined = members.firstOrNull { it.sender != ring.sender && !isEmpty(it.content) && it.ts <= until }
    if (joined != null) {
        val left = members.firstOrNull { it.ts >= joined.ts && isEmpty(it.content) } // 1:1: whoever leaves first ends it
        return if (left != null) CallOutcome(CallState.ENDED, left.ts - joined.ts) else CallOutcome(CallState.ONGOING)
    }
    // (as in the TS source, a decline with no relation matches a ring with no id)
    if (after.any { it.type.endsWith("rtc.decline") && (it.content["m.relates_to"] as? JsonObject)?.string("event_id") == ring.id }) return CallOutcome(CallState.DECLINED)
    val gaveUp = members.any { it.sender == ring.sender && isEmpty(it.content) }
    return if (gaveUp || until <= now) CallOutcome(CallState.MISSED) else CallOutcome(CallState.RINGING)
}
