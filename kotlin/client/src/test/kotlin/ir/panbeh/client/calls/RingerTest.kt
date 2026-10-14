package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.client.register
import ir.panbeh.client.testServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class RingerTest {
    private fun ringer(p: Peer, muted: Boolean = false, ignored: Boolean = false, active: RoomId? = null, legacy: Boolean = false) =
        Ringer(p.client, p.scope, p.rtc, null, p.events, RingPolicy({ muted }, { ignored }, { active }, { legacy })).also { it.start() }

    @Test
    fun `a ring shows up, declining it tells the caller and clears the ring without a missed notice`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ga")); val b = peer(login("gb"))
        val room = roomOf(a.client, b.client)
        val rb = ringer(b)
        val notices = mutableListOf<CallNotice>()
        val nj = b.scope.launch2 { rb.notices.collect { notices += it } }
        delay(300)
        val sa = a.rtc.session(room)
        sa.join(CallIntent.VIDEO, ring = true)
        val i = withTimeout(30_000) { rb.incoming.first { it != null } }!!
        assertEquals(a.client.userId.full, i.callerId); assertTrue(i.video); assertTrue(!i.waiting); assertNull(i.legacy)
        assertEquals(room, i.roomId)
        assertTrue(i.until > System.currentTimeMillis())
        val signal = async { withTimeout(30_000) { sa.signals.first { it is CallSignal.Declined } } }
        rb.decline()
        assertNull(rb.incoming.value)
        assertEquals(CallSignal.Declined(b.client.userId.full), signal.await())
        delay(500)
        assertTrue(notices.none { it is CallNotice.Missed })
        nj.cancel()
        sa.leave()
        a.close(); b.close()
    }

    @Test
    fun `the caller giving up is a missed call`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ma")); val b = peer(login("mb"))
        val room = roomOf(a.client, b.client)
        val rb = ringer(b)
        val missed = async { withTimeout(30_000) { rb.notices.first { it is CallNotice.Missed } } }
        delay(300)
        val sa = a.rtc.session(room)
        sa.join(CallIntent.AUDIO, ring = true)
        withTimeout(30_000) { rb.incoming.first { it != null } }
        withTimeout(30_000) { b.rtc.session(room).memberships.first { it.isNotEmpty() } }
        sa.leave()
        val n = missed.await() as CallNotice.Missed
        assertEquals(a.client.userId.full, n.callerId); assertTrue(!n.video)
        assertNull(rb.incoming.value)
        assertEquals("تماس صوتی از دست رفته", missedCallText(n.video))
        a.close(); b.close()
    }

    @Test
    fun `declining or answering on another device stops the ring here`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("oa"))
        val (u, p) = register("ob")
        val b1 = peer(loginAs(u, p)); val b2 = peer(loginAs(u, p))
        val room = roomOf(a.client, b1.client)
        val r1 = ringer(b1); val r2 = ringer(b2)
        val notices = mutableListOf<CallNotice>()
        val nj = b1.scope.launch2 { r1.notices.collect { notices += it } }
        delay(300)
        val sa = a.rtc.session(room)
        sa.join(CallIntent.AUDIO, ring = true)
        withTimeout(30_000) { r1.incoming.first { it != null }; r2.incoming.first { it != null } }
        r2.decline()
        withTimeout(30_000) { r1.incoming.first { it == null } }
        delay(300)
        assertTrue(notices.none { it is CallNotice.Missed })
        // and a second ring answered on the other device
        sa.leave()
        delay(1000)
        sa.join(CallIntent.AUDIO, ring = true)
        withTimeout(30_000) { r1.incoming.first { it != null }; r2.incoming.first { it != null } }
        b2.rtc.session(room).join(CallIntent.AUDIO, ring = false)
        withTimeout(30_000) { r1.incoming.first { it == null } }
        delay(300)
        assertTrue(notices.none { it is CallNotice.Missed })
        nj.cancel()
        b2.rtc.session(room).leave(); sa.leave()
        a.close(); b1.close(); b2.close()
    }

    @Test
    fun `muted, ignored, call waiting, group alert`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("wa")); val b = peer(login("wb"))
        val room = roomOf(a.client, b.client)
        val muted = ringer(b, muted = true)
        val ignored = ringer(b, ignored = true)
        val busy = ringer(b, active = RoomId("!elsewhere:localhost"))
        val inSame = ringer(b, active = room)
        val sa = a.rtc.session(room)
        sa.join(CallIntent.AUDIO, ring = true)
        val i = withTimeout(30_000) { busy.incoming.first { it != null } }!!
        assertTrue(i.waiting)
        delay(500)
        assertNull(muted.incoming.value); assertNull(ignored.incoming.value); assertNull(inSame.incoming.value)
        sa.leave()
        a.close(); b.close()

        val c = peer(login("wc")); val d = peer(login("wd"))
        val group = roomOf(c.client, d.client, direct = false)
        val rd = ringer(d)
        val alert = async { withTimeout(30_000) { rd.notices.first { it is CallNotice.GroupCallStarted } } }
        delay(300)
        c.rtc.session(group).join(CallIntent.VIDEO, ring = true)
        val n = alert.await() as CallNotice.GroupCallStarted
        assertEquals(c.client.userId.full, n.senderId); assertTrue(n.video)
        assertNull(rd.incoming.value) // a group call doesn't ring
        c.rtc.session(group).leave()
        c.close(); d.close()
    }

    @Test
    fun `a legacy invite with legacy calls off is a notice, not a ring`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ua")); val b = peer(login("ub"))
        val room = roomOf(a.client, b.client)
        val rb = ringer(b, legacy = false)
        val la = legacyOf(a, FakePeers())
        val notice = async { withTimeout(30_000) { rb.notices.first { it is CallNotice.LegacyUnsupported } } }
        delay(300)
        la.place(room, false)
        val n = notice.await() as CallNotice.LegacyUnsupported
        assertEquals(a.client.userId.full, n.senderId)
        assertNull(rb.incoming.value)
        assertTrue(legacyUnsupportedText("علی").contains("دریافت تماس‌های قدیمی"))
        a.close(); b.close()
    }

    @Test
    fun `a legacy invite rings when enabled and answering it stops the ring`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("ea")); val b = peer(login("eb"))
        val room = roomOf(a.client, b.client)
        val pb = FakePeers()
        val lb = legacyOf(b, pb)
        val rb = Ringer(b.client, b.scope, b.rtc, lb, b.events, RingPolicy({ false }, { false }, { null }, { true })).also { it.start() }
        val la = legacyOf(a, FakePeers())
        delay(300)
        val out = la.place(room, true)
        val i = withTimeout(30_000) { rb.incoming.first { it != null } }!!
        assertNotNull(i.legacy); assertEquals(out.callId, i.legacy!!.callId); assertEquals(a.client.userId.full, i.callerId)
        i.legacy!!.answer(true)
        withTimeout(30_000) { rb.incoming.first { it == null } }
        a.close(); b.close()
    }
}

private fun kotlinx.coroutines.CoroutineScope.launch2(f: suspend () -> Unit) = this.launch { f() }
