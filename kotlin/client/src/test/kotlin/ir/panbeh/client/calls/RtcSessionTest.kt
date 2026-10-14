package ir.panbeh.client.calls

import ir.panbeh.client.testServer
import ir.panbeh.core.calls.isRing
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two users against the local Synapse; the SFU is mocked (no LiveKit here). */
class RtcSessionTest {
    @Test
    fun `join and leave are visible to the other user`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ca")); val b = peer(login("cb"))
        val room = roomOf(a.client, b.client)
        val sa = a.rtc.session(room); val sb = b.rtc.session(room)

        val conn = sa.join(CallIntent.VIDEO, ring = false)
        assertEquals("wss://lk.example/livekit", conn.url)
        assertEquals("jwt-for-test", conn.jwt)
        assertEquals("${a.client.userId.full}:${a.client.deviceId}", conn.identity)
        val m = withTimeout(30_000) { sb.memberships.first { it.isNotEmpty() } }.single()
        assertEquals(a.client.userId.full, m.userId)
        assertEquals(a.client.deviceId, m.deviceId)
        assertEquals(CallIntent.VIDEO, m.intent)
        assertEquals("https://jwt.example", m.focus?.serviceUrl)
        assertEquals(room.full, m.focus?.alias)
        assertEquals("_${a.client.userId.full}_${a.client.deviceId}", m.stateKey)
        assertTrue(sa.joined.value)
        // mine resolves on the joiner's own side
        assertNotNull(withTimeout(30_000) { sa.mine.first { it != null } })

        sa.leave()
        withTimeout(30_000) { sb.memberships.first { it.isEmpty() } }
        assertTrue(!sa.joined.value)
        a.close(); b.close()
    }

    @Test
    fun `ring reaches the other user and a decline is seen`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ra")); val b = peer(login("rb"))
        val room = roomOf(a.client, b.client)
        val sa = a.rtc.session(room); val sb = b.rtc.session(room)
        val ringWait = async { withTimeout(30_000) { b.events.first { it.type == CallTypes.RTC_NOTIFICATION } } }
        val declineWait = async { withTimeout(30_000) { a.events.first { it.type == CallTypes.RTC_DECLINE } } }
        kotlinx.coroutines.delay(500)
        sa.join(CallIntent.AUDIO, ring = true)
        val ring = ringWait.await()
        assertEquals(a.client.userId.full, ring.sender)
        assertEquals("ring", (ring.content["notification_type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("audio", (ring.content["m.call.intent"] as kotlinx.serialization.json.JsonPrimitive).content)
        val until = notificationExpiry(ring.content, ring.ts)
        assertTrue(isRing(ring.content, until, b.client.userId.full))
        // it points at a's membership event
        assertEquals(sa.mine.first { it != null }!!.eventId, ring.relatesTo)

        sb.decline(ring.eventId!!)
        val d = declineWait.await()
        assertEquals(b.client.userId.full, d.sender)
        assertEquals(ring.eventId, d.relatesTo)
        sa.leave()
        a.close(); b.close()
    }

    @Test
    fun `raised hand and call reaction`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ha")); val b = peer(login("hb"))
        val room = roomOf(a.client, b.client, direct = false)
        val sa = a.rtc.session(room); val sb = b.rtc.session(room)
        sa.join(CallIntent.AUDIO, ring = false)
        val mid = withTimeout(30_000) { sa.mine.first { it?.eventId != null } }!!.eventId!!
        withTimeout(30_000) { sb.memberships.first { it.isNotEmpty() } }

        sa.toggleHand()
        val up = withTimeout(30_000) { sb.raisedHands.first { it.containsKey(mid) } }
        assertNotNull(up[mid])
        assertTrue(sa.raisedHands.first().containsKey(mid))

        val rx = async { withTimeout(30_000) { sb.reactionEvents.first() } }
        kotlinx.coroutines.delay(300)
        sa.react("🎉")
        val r = rx.await()
        assertEquals(mid, r.membershipEventId)
        assertEquals("🎉", r.emoji)
        assertEquals("party", r.name)
        assertEquals("🎉", withTimeout(30_000) { sb.reactions.first { it.isNotEmpty() } }[mid])

        sa.toggleHand()
        withTimeout(30_000) { sb.raisedHands.first { !it.containsKey(mid) } }
        sa.leave()
        a.close(); b.close()
    }
}
