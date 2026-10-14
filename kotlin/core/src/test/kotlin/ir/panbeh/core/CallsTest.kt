package ir.panbeh.core

import ir.panbeh.core.calls.*
import ir.panbeh.core.util.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CallsTest {
    private fun ring(type: String, room: Boolean? = null, users: List<String>? = null) = buildJsonObject {
        put("notification_type", type)
        if (room != null || users != null) putJsonObject("m.mentions") {
            if (room != null) put("room", room)
            if (users != null) putJsonArray("user_ids") { users.forEach { add(it) } }
        }
    }

    @Test fun `isRing - rings only for fresh rings aimed at us or the room`() {
        val r = ring("ring", users = listOf("@me:x"))
        assertTrue(isRing(r, 2000.0, "@me:x", 1000))
        assertFalse(isRing(r, 1000.0, "@me:x", 1000)) // expired
        assertFalse(isRing(r, Double.NaN, "@me:x", 1000)) // bad lifetime
        assertFalse(isRing(r, 2000.0, "@you:x", 1000)) // someone else
        assertTrue(isRing(ring("ring", room = true), 2000.0, "@me:x", 1000))
        assertFalse(isRing(ring("notification", room = true), 2000.0, "@me:x", 1000))
        assertFalse(isRing(ring("ring"), 2000.0, "@me:x", 1000))
    }

    @Test fun `isLegacyRing - fresh invites for us or anyone in the room`() {
        fun inv(lifetime: Int? = null, invitee: String? = null) = buildJsonObject { lifetime?.let { put("lifetime", it) }; invitee?.let { put("invitee", it) } }
        assertTrue(isLegacyRing(inv(60000), 1000, "@me:x", 2000))
        assertFalse(isLegacyRing(inv(60000), 1000, "@me:x", 62000)) // expired
        assertFalse(isLegacyRing(inv(), 1000, "@me:x", 1000)) // no lifetime
        assertTrue(isLegacyRing(inv(60000, "@me:x"), 1000, "@me:x", 2000))
        assertFalse(isLegacyRing(inv(60000, "@you:x"), 1000, "@me:x", 2000))
        fun offer(sdp: String) = buildJsonObject { putJsonObject("offer") { put("sdp", sdp) } }
        assertTrue(isVideoOffer(offer("v=0\r\nm=audio 9 UDP\r\nm=video 9 UDP\r\n")))
        assertFalse(isVideoOffer(offer("v=0\r\nm=audio 9 UDP\r\n")))
    }

    @Test fun `isGroupCallAlert - group call starts, not rings`() {
        assertTrue(isGroupCallAlert(ring("notification", room = true), 2000.0, "@me:x", 1000))
        assertFalse(isGroupCallAlert(ring("ring", room = true), 2000.0, "@me:x", 1000))
        assertFalse(isGroupCallAlert(ring("notification", room = true), 1000.0, "@me:x", 1000))
    }

    @Test fun `legacyOutcome - answered with duration, declined, missed, still ringing`() {
        val inv = CallEv("m.call.invite", "@a:x", 1000, buildJsonObject { put("call_id", "c1"); put("lifetime", 60000) })
        fun ev(type: String, ts: Long, callId: String = "c1") = CallEv(type, "@b:x", ts, buildJsonObject { put("call_id", callId) })
        assertEquals(CallOutcome(CallState.ENDED, 192000), legacyOutcome(inv, listOf(ev("m.call.answer", 5000), ev("m.call.hangup", 197000)), 300000))
        assertEquals(CallOutcome(CallState.ONGOING), legacyOutcome(inv, listOf(ev("m.call.answer", 5000)), 300000))
        assertEquals(CallOutcome(CallState.DECLINED), legacyOutcome(inv, listOf(ev("m.call.reject", 5000)), 300000))
        assertEquals(CallOutcome(CallState.MISSED), legacyOutcome(inv, listOf(ev("m.call.hangup", 5000)), 6000)) // caller gave up
        assertEquals(CallOutcome(CallState.MISSED), legacyOutcome(inv, emptyList(), 300000)) // expired
        assertEquals(CallOutcome(CallState.RINGING), legacyOutcome(inv, listOf(ev("m.call.answer", 5000, "other")), 2000)) // another call's events
    }

    @Test fun `rtcOutcome - from call memberships after the ring`() {
        val ring = CallEv("org.matrix.msc4075.rtc.notification", "@a:x", 1000, JsonObject(emptyMap()), id = "\$r")
        fun m(sender: String, ts: Long, on: Boolean) =
            CallEv("org.matrix.msc3401.call.member", sender, ts, if (on) buildJsonObject { put("application", "m.call") } else JsonObject(emptyMap()))
        assertEquals(CallOutcome(CallState.ENDED, 60000), rtcOutcome(ring, 31000, listOf(m("@b:x", 5000, true), m("@a:x", 65000, false)), 100000))
        assertEquals(CallOutcome(CallState.ONGOING), rtcOutcome(ring, 31000, listOf(m("@b:x", 5000, true)), 100000))
        val decline = CallEv("org.matrix.msc4310.rtc.decline", "@b:x", 3000, buildJsonObject { putJsonObject("m.relates_to") { put("event_id", "\$r") } })
        assertEquals(CallOutcome(CallState.DECLINED), rtcOutcome(ring, 31000, listOf(decline), 100000))
        assertEquals(CallOutcome(CallState.MISSED), rtcOutcome(ring, 31000, listOf(m("@a:x", 9000, false)), 10000)) // caller gave up
        assertEquals(CallOutcome(CallState.MISSED), rtcOutcome(ring, 31000, emptyList(), 100000))
        assertEquals(CallOutcome(CallState.RINGING), rtcOutcome(ring, 31000, emptyList(), 2000))
        assertEquals(CallOutcome(CallState.MISSED), rtcOutcome(ring, 31000, listOf(m("@b:x", 40000, true)), 100000)) // joined after it stopped ringing
    }

    @Test fun `parseStats - video over audio, simulcast adds up, bitrate from the last sample`() {
        fun report(bytes: Int, ts: Int): List<Map<String, Any?>> = listOf(
            mapOf("type" to "outbound-rtp", "kind" to "audio", "bytesSent" to 999999, "timestamp" to ts),
            mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to bytes / 2.0, "frameWidth" to 640, "frameHeight" to 360, "framesPerSecond" to 15, "codecId" to "c1", "timestamp" to ts),
            mapOf("type" to "outbound-rtp", "kind" to "video", "bytesSent" to bytes / 2.0, "frameWidth" to 1280, "frameHeight" to 720, "framesPerSecond" to 30, "codecId" to "c1", "timestamp" to ts),
            mapOf("type" to "remote-inbound-rtp", "kind" to "video", "fractionLost" to 0.0125),
            mapOf("type" to "candidate-pair", "nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.042),
            mapOf("type" to "codec", "id" to "c1", "mimeType" to "video/VP8"),
        )
        val a = parseStats(report(100_000, 1000), StatsDir.OUT)
        assertNull(a.kbps)
        val b = parseStats(report(250_000, 2000), StatsDir.OUT, a)
        assertEquals(listOf<Any?>(1280.0, 720.0, 30.0, 1200L, 1.3, 42L, "VP8"), listOf(b.w, b.h, b.fps, b.kbps, b.loss, b.rtt, b.codec))
        assertEquals("1280×720 · 30fps · 1200kbps · 1.3% · 42ms · VP8", fmtStats(b))
        val audio = parseStats(listOf(mapOf("type" to "inbound-rtp", "kind" to "audio", "bytesReceived" to 10, "packetsLost" to 1, "packetsReceived" to 199, "timestamp" to 5)), StatsDir.IN)
        assertEquals(listOf<Any?>(null, 0.5), listOf(audio.w, audio.loss))
    }
}
