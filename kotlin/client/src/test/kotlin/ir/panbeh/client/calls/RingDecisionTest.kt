package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.RoomId
import kotlinx.serialization.json.*
import kotlin.test.*

class RingDecisionTest {
    private val me = "@me:localhost"
    private val room = RoomId("!r:localhost")
    private val now = 10_000L

    private fun rtc(type: String = "ring", sender: String = "@a:localhost", user: String? = me, roomMention: Boolean = false, ts: Long = now - 1000, lifetime: Int = 90_000, intent: String = "audio") =
        RoomCallEvent(room, "\$ring", CallTypes.RTC_NOTIFICATION, sender, ts, buildJsonObject {
            put("notification_type", type); put("lifetime", lifetime); put("sender_ts", ts); put("m.call.intent", intent)
            putJsonObject("m.mentions") { putJsonArray("user_ids") { user?.let { add(it) } }; put("room", roomMention) }
        }, null, null)

    private fun invite(sender: String = "@a:localhost", lifetime: Int = 60_000, ts: Long = now - 1000, invitee: String? = null, video: Boolean = false) =
        RoomCallEvent(room, "\$inv", "m.call.invite", sender, ts, buildJsonObject {
            put("call_id", "c1"); put("lifetime", lifetime); invitee?.let { put("invitee", it) }
            putJsonObject("offer") { put("type", "offer"); put("sdp", if (video) "v=0\r\nm=audio 1\r\nm=video 9" else "v=0\r\nm=audio 1") }
        }, null, null)

    @Test
    fun `a fresh ring aimed at me rings`() {
        val d = decideRing(rtc(), me, RingContext(), now)
        assertEquals(RingDecision.Ring(now - 1000 + 90_000, false, false), d)
        assertEquals(true, (decideRing(rtc(intent = "video"), me, RingContext(), now) as RingDecision.Ring).video)
        assertIs<RingDecision.Ring>(decideRing(rtc(user = null, roomMention = true), me, RingContext(), now))
    }

    @Test
    fun `no ring when mine, muted, ignored, in that call, expired, for someone else or already ringing`() {
        assertEquals(RingDecision.None, decideRing(rtc(sender = me), me, RingContext(), now))
        assertEquals(RingDecision.None, decideRing(rtc(), me, RingContext(muted = true), now))
        assertEquals(RingDecision.None, decideRing(rtc(), me, RingContext(ignored = true), now))
        assertEquals(RingDecision.None, decideRing(rtc(), me, RingContext(inCall = true), now))
        assertEquals(RingDecision.None, decideRing(rtc(ts = 0, lifetime = 5000), me, RingContext(), now))
        assertEquals(RingDecision.None, decideRing(rtc(user = "@other:localhost"), me, RingContext(), now))
        assertEquals(RingDecision.None, decideRing(rtc(), me, RingContext(alreadyRinging = true), now))
        assertEquals(RingDecision.None, decideRing(rtc(), me, RingContext(activeRoom = room), now))
    }

    @Test
    fun `a ring while in another call is call waiting`() {
        val d = decideRing(rtc(), me, RingContext(activeRoom = RoomId("!other:localhost")), now) as RingDecision.Ring
        assertTrue(d.waiting)
    }

    @Test
    fun `a group call start is a quiet notice, unless muted`() {
        assertEquals(RingDecision.GroupAlert(true), decideRing(rtc(type = "notification", roomMention = true, intent = "video"), me, RingContext(), now))
        assertEquals(RingDecision.None, decideRing(rtc(type = "notification", roomMention = true), me, RingContext(muted = true), now))
        // not even when already ringing: the notice is independent of the ring
        assertEquals(RingDecision.GroupAlert(false), decideRing(rtc(type = "notification", roomMention = true), me, RingContext(alreadyRinging = true), now))
    }

    @Test
    fun `legacy invites ring only when enabled and unexpired`() {
        assertEquals(RingDecision.LegacyUnsupported, decideRing(invite(), me, RingContext(legacyEnabled = false), now))
        val d = decideRing(invite(video = true), me, RingContext(legacyEnabled = true), now) as RingDecision.Ring
        assertTrue(d.video); assertEquals(now - 1000 + 60_000, d.until)
        assertEquals(RingDecision.None, decideRing(invite(ts = 0, lifetime = 1000), me, RingContext(legacyEnabled = true), now))
        assertEquals(RingDecision.None, decideRing(invite(invitee = "@x:localhost"), me, RingContext(legacyEnabled = true), now))
        assertIs<RingDecision.Ring>(decideRing(invite(invitee = me), me, RingContext(legacyEnabled = true), now))
        assertEquals(RingDecision.None, decideRing(invite(sender = me), me, RingContext(legacyEnabled = true), now))
        assertEquals(RingDecision.None, decideRing(invite(), me, RingContext(legacyEnabled = true, muted = true), now))
    }

    @Test
    fun `texts`() {
        assertEquals("تماس صوتی از دست رفته", missedCallText(false))
        assertEquals("تماس تصویری از دست رفته", missedCallText(true))
        assertEquals("علی تماس صوتی گروهی را شروع کرد", groupCallText("علی", false))
    }
}
