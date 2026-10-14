package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.EventId
import ir.panbeh.client.events.CallReaction
import ir.panbeh.client.events.Reaction
import ir.panbeh.client.events.RtcDecline
import ir.panbeh.client.events.RtcNotification
import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import kotlinx.serialization.json.*

/** Event types and wire constants of MatrixRTC calls, as Element Call and the web app use them. */
object CallTypes {
    const val MEMBER = "org.matrix.msc3401.call.member"
    const val RTC_NOTIFICATION = "org.matrix.msc4075.rtc.notification"
    const val RTC_NOTIFICATION_STABLE = "m.rtc.notification"
    const val RTC_DECLINE = "org.matrix.msc4310.rtc.decline"
    const val RTC_DECLINE_STABLE = "m.rtc.decline"
    const val CALL_REACTION = "io.element.call.reaction"
    const val ENCRYPTION_KEYS = "io.element.call.encryption_keys"
    const val HAND = "🖐️"
    const val ROOM_SCOPE = "m.room"
    const val APPLICATION = "m.call"
}

/** What a call is for; sent as `m.call.intent`. */
enum class CallIntent(val wire: String) {
    AUDIO("audio"), VIDEO("video");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

/** What Element Call offers as reactions: emoji to the name it keys the sound by. Any other emoji goes out as "generic". */
val CALL_REACTIONS: List<Pair<String, String>> = listOf(
    "👍" to "thumbsup", "👏" to "clapping", "🎉" to "party", "😄" to "laugh",
    "🥰" to "heart", "👌" to "ok", "💡" to "lightbulb", "👎" to "thumbsdown",
)

fun reactionName(emoji: String) = CALL_REACTIONS.firstOrNull { it.first == emoji }?.second ?: "generic"

/** A LiveKit SFU, reached through lk-jwt-service at [serviceUrl]; [alias] is the LiveKit room (the Matrix room id). */
data class LivekitFocus(val serviceUrl: String, val alias: String? = null) {
    fun toJson(alias: String? = this.alias) = buildJsonObject {
        put("type", "livekit"); put("livekit_service_url", serviceUrl)
        alias?.let { put("livekit_alias", it) }
    }
    companion object {
        /** A `{type: livekit, livekit_service_url}` entry, also Trixnity-style `{type: m.livekit, url}`. */
        fun parse(o: JsonElement?): LivekitFocus? {
            val j = o as? JsonObject ?: return null
            if (j.string("type") != "livekit" && j.string("type") != "m.livekit") return null
            val url = j.string("livekit_service_url") ?: j.string("url") ?: return null
            return LivekitFocus(url, j.string("livekit_alias"))
        }
    }
}

/**
 * One device in the call, from an org.matrix.msc3401.call.member state event.
 * [createdTs] is when it joined (the oldest membership picks the SFU); [expiresAt] null = never.
 */
data class CallMembership(
    val userId: String,
    val deviceId: String,
    val stateKey: String,
    val eventId: String?,
    val createdTs: Long,
    val expiresAt: Long?,
    val intent: CallIntent?,
    val foci: List<LivekitFocus>,
) {
    /** The LiveKit identity lk-jwt-service gives this device ("@user:server:DEVICE"); E2EE keys are filed under it. */
    val identity get() = "$userId:$deviceId"
    fun expired(now: Long) = expiresAt != null && expiresAt <= now
    /** The SFU this membership publishes on, when it says. */
    val focus get() = foci.firstOrNull()
}

private const val FOUR_HOURS = 4 * 3600_000L

/**
 * The memberships in one call.member state event. Per-device events (state key `_@user:server_DEVICE`, flat content) and
 * the old array form (`memberships: [...]`, state key = user id) are both read; anything but the room call (call_id "",
 * scope m.room, application m.call) and empty content (a leave) gives nothing.
 */
fun parseMemberships(stateKey: String, sender: String, eventId: String?, originTs: Long, content: JsonObject): List<CallMembership> {
    if (content.isEmpty()) return emptyList()
    fun one(m: JsonObject, user: String): CallMembership? {
        if (m.string("application") != CallTypes.APPLICATION || (m.string("call_id") ?: "") != "") return null
        if ((m.string("scope") ?: CallTypes.ROOM_SCOPE) != CallTypes.ROOM_SCOPE) return null
        val device = m.string("device_id") ?: return null
        val created = m.number("created_ts")?.toLong() ?: originTs
        val expires = m.number("expires")?.toLong()
        val foci = ((m["foci_preferred"] as? JsonArray) ?: (m["foci_active"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull(LivekitFocus::parse)
        return CallMembership(
            user, device, stateKey, eventId, created, expires?.let { created + it }, CallIntent.of(m.string("m.call.intent")), foci,
        )
    }
    val list = content["memberships"] as? JsonArray
    if (list != null) return list.mapNotNull { (it as? JsonObject)?.let { m -> one(m, sender) } }
    // the per-device form is only trusted when its state key names its sender and device
    val device = content.string("device_id") ?: return emptyList()
    if (stateKey != sender && stateKey != memberStateKey(sender, device) && stateKey != "${sender}_$device") return emptyList()
    return listOfNotNull(one(content, sender))
}

/** The state key a device files its membership under. */
fun memberStateKey(userId: String, deviceId: String) = "_${userId}_$deviceId"

/** Oldest first: the one whose SFU everyone meets on. */
fun oldestMembership(list: List<CallMembership>) = list.minWithOrNull(compareBy<CallMembership> { it.createdTs }.thenBy { it.identity })

/** The SFU to join: the oldest membership's, when it names a LiveKit one. */
fun sfuOfOldest(list: List<CallMembership>): LivekitFocus? = oldestMembership(list)?.focus

/** Content of our own membership, the shape matrix-js-sdk's MatrixRTC session writes (Element Call and Element X read it). */
fun memberContent(deviceId: String, intent: CallIntent, focus: LivekitFocus, roomId: String, createdTs: Long, expiresMs: Long = FOUR_HOURS) = buildJsonObject {
    put("application", CallTypes.APPLICATION)
    put("call_id", "")
    put("scope", CallTypes.ROOM_SCOPE)
    put("device_id", deviceId)
    put("created_ts", createdTs)
    put("expires", expiresMs)
    putJsonObject("focus_active") { put("type", "livekit"); put("focus_selection", "oldest_membership") }
    putJsonArray("foci_preferred") { add(focus.toJson(alias = roomId)) }
    put("m.call.intent", intent.wire)
}

// ---------- ring, decline, reactions ----------

/** Default lifetime of a ring we send. */
const val RING_LIFETIME_MS = 90_000L

/**
 * When an m.rtc.notification stops ringing (ms since epoch): sender_ts + lifetime when sender_ts is within 20 s of the server's
 * timestamp, else the server's timestamp + lifetime. NaN when there is no valid lifetime (it never rings).
 */
fun notificationExpiry(c: JsonObject, originTs: Long): Double {
    val lifetime = c.number("lifetime") ?: return Double.NaN
    if (lifetime.isNaN() || lifetime < 0) return Double.NaN
    val senderTs = c.number("sender_ts")
    val base = if (senderTs != null && Math.abs(senderTs - originTs) <= 20_000) senderTs else originTs.toDouble()
    return base + lifetime
}

/** The m.rtc.notification for a call we just started: a "ring" in DMs (to the others), a "notification" in groups (@room). */
fun ringContent(membershipEventId: EventId, direct: Boolean, others: List<String>, intent: CallIntent, now: Long, lifetime: Long = RING_LIFETIME_MS): RtcNotification {
    val raw = buildJsonObject {
        putJsonObject("m.mentions") {
            if (direct) { putJsonArray("user_ids") { others.forEach { add(it) } }; put("room", false) }
            else { putJsonArray("user_ids") {}; put("room", true) }
        }
        put("notification_type", if (direct) "ring" else "notification")
        put("sender_ts", now)
        put("lifetime", lifetime)
        put("m.call.intent", intent.wire)
    }
    return RtcNotification(raw, RelatesTo.Reference(membershipEventId))
}

/** The decline of a ring (MSC4310): a reference to the notification event. */
fun declineContent(ringEventId: EventId): RtcDecline = RtcDecline(buildJsonObject {}, RelatesTo.Reference(ringEventId))

/** A raised hand: a 🖐️ annotation on our membership event. */
fun handContent(membershipEventId: EventId): Reaction = Reaction(buildJsonObject {}, RelatesTo.Annotation(membershipEventId, CallTypes.HAND))

/** An Element Call reaction to a membership event. */
fun callReactionContent(membershipEventId: EventId, emoji: String, name: String = reactionName(emoji)): CallReaction =
    CallReaction(buildJsonObject { put("emoji", emoji); put("name", name) }, RelatesTo.Reference(membershipEventId))

/** The first grapheme of [s] (one emoji however long the string); "" when blank. */
fun firstEmoji(s: String): String {
    if (s.isBlank()) return ""
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    return s.substring(0, it.next().let { e -> if (e < 0) s.length else e })
}
