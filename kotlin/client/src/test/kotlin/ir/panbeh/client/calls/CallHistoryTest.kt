package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.core.calls.CallState
import kotlinx.serialization.json.*
import kotlin.test.*

class CallHistoryTest {
    private val me = "@me:l"; private val they = "@they:l"; private val room = RoomId("!r:l")

    private fun ev(id: String, type: String, sender: String, ts: Long, c: JsonObject = JsonObject(emptyMap()), typed: Boolean = false) = RoomCallEvent(room, id, type, sender, ts, c, null, null)
    private fun ring(id: String, sender: String, ts: Long, intent: String = "audio", lifetime: Int = 30_000) = ev(id, CallTypes.RTC_NOTIFICATION, sender, ts, buildJsonObject { put("notification_type", "ring"); put("lifetime", lifetime); put("m.call.intent", intent) })
    private fun member(id: String, sender: String, ts: Long, joined: Boolean) = ev(id, CallTypes.MEMBER, sender, ts, if (joined) buildJsonObject { put("device_id", "D") } else JsonObject(emptyMap()))
    private fun decline(id: String, sender: String, ts: Long, of: String) = ev(id, CallTypes.RTC_DECLINE, sender, ts, buildJsonObject { putJsonObject("m.relates_to") { put("event_id", of) } })
    private fun invite(id: String, sender: String, ts: Long, callId: String, video: Boolean = false) = ev(id, "m.call.invite", sender, ts, buildJsonObject {
        put("call_id", callId); put("lifetime", 60_000); putJsonObject("offer") { put("sdp", if (video) "m=video 9" else "m=audio 9") } })
    private fun legacy(id: String, type: String, sender: String, ts: Long, callId: String) = ev(id, type, sender, ts, buildJsonObject { put("call_id", callId) })

    @Test
    fun `rtc ring outcomes`() {
        val now = 1_000_000L
        val events = listOf(
            ring("\$a", they, 1000), member("\$m1", me, 2000, true), member("\$m2", me, 62_000, false), // answered, 60 s
            ring("\$b", me, 100_000, "video"), decline("\$d", they, 101_000, "\$b"),                    // we called, declined
            ring("\$c", they, 200_000),                                                              // they rang, never answered
            ring("\$e", they, now - 1000),                                                           // still ringing: not listed
        )
        val rows = callRows(room, events, me, now)
        assertEquals(listOf("\$c", "\$b", "\$a"), rows.map { it.eventId })
        val a = rows.single { it.eventId == "\$a" }
        assertEquals(CallState.ENDED, a.outcome.state); assertEquals(60_000L, a.outcome.duration); assertTrue(!a.outgoing); assertTrue(!a.missed)
        assertTrue(a.text.startsWith("ورودی صوتی ·"), a.text)
        val b = rows.single { it.eventId == "\$b" }
        assertTrue(b.outgoing && b.video && !b.missed); assertEquals("بی‌پاسخ تصویری", b.text) // declined by them, our side shows unanswered
        val c = rows.single { it.eventId == "\$c" }
        assertTrue(c.missed); assertEquals("از دست رفته صوتی", c.text)
        assertTrue(rows.none { it.eventId == "\$e" })
    }

    @Test
    fun `legacy outcomes and group alerts`() {
        val now = 1_000_000L
        val events = listOf(
            invite("\$i1", they, 1000, "c1", video = true), legacy("\$a1", "m.call.answer", me, 2000, "c1"), legacy("\$h1", "m.call.hangup", they, 12_000, "c1"),
            invite("\$i2", they, 20_000, "c2"), legacy("\$r2", "m.call.reject", me, 21_000, "c2"),
            ev("\$g", CallTypes.RTC_NOTIFICATION, they, 30_000, buildJsonObject { put("notification_type", "notification"); put("lifetime", 1000) }),
        )
        val rows = callRows(room, events, me, now)
        assertEquals(2, rows.size)
        val r1 = rows.single { it.eventId == "\$i1" }
        assertTrue(r1.legacy && r1.video); assertEquals(10_000L, r1.outcome.duration); assertTrue(r1.text.startsWith("ورودی تصویری"))
        val r2 = rows.single { it.eventId == "\$i2" }
        assertEquals(CallState.DECLINED, r2.outcome.state); assertEquals("رد شده صوتی", r2.text); assertTrue(r2.missed)
    }
}
