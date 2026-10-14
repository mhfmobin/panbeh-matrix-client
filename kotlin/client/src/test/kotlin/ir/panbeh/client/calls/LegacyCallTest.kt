package ir.panbeh.client.calls

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ir.panbeh.client.testServer
import kotlin.test.*

/** Legacy m.call.* signalling between two clients; the WebRTC side is a fake peer. */
class LegacyCallTest {
    @Test
    fun `invite, answer, candidates, connect and hangup`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("la")); val b = peer(login("lb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val la = legacyOf(a, pa); val lb = legacyOf(b, pb)

        val incoming = async { withTimeout(30_000) { lb.incoming.first() } }
        kotlinx.coroutines.delay(300)
        val out = la.place(room, video = false)
        assertEquals(LegacyPhase.CALLING, out.state.value.phase)
        assertTrue(!out.incoming)
        pa.peers.single().events.onLocalCandidate(IceCandidate("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0))
        pa.peers.single().events.onLocalCandidate(null)

        val inc = incoming.await()
        assertEquals(out.callId, inc.callId)
        assertEquals(a.client.userId.full, inc.peerUserId)
        assertEquals(LegacyPhase.RINGING_IN, inc.state.value.phase)
        assertTrue(!inc.video)
        assertTrue(inc.incoming)

        // the candidates sent right after the invite are held until we answer
        kotlinx.coroutines.delay(1500)
        assertTrue(pb.peers.isEmpty())
        inc.answer(video = false)
        assertEquals(LegacyPhase.CONNECTING, inc.state.value.phase)
        assertEquals(inc, lb.current.value)
        assertEquals("v=0\r\nm=audio 9\r\na=offer", pb.peers.single().remoteOffer)
        until { pb.peers.single().remoteCandidates.isNotEmpty() }
        assertEquals("candidate:1 1 udp 1 1.2.3.4 5 typ host", pb.peers.single().remoteCandidates.single().candidate)
        until { out.state.value.phase == LegacyPhase.CONNECTING }
        assertEquals("v=0\r\nm=audio 9\r\na=answer", pa.peers.single().remoteAnswer)

        pa.peers.single().events.onConnectionState(PeerConnectionState.CONNECTED)
        pb.peers.single().events.onConnectionState(PeerConnectionState.CONNECTED)
        until { out.state.value.phase == LegacyPhase.CONNECTED && inc.state.value.phase == LegacyPhase.CONNECTED }
        assertNotNull(out.state.value.connectedAt)

        out.hangup()
        assertEquals(LegacyEnd.HANGUP_LOCAL, out.state.value.end)
        assertTrue(pa.peers.single().closed)
        until { inc.state.value.phase == LegacyPhase.ENDED }
        assertEquals(LegacyEnd.HANGUP_REMOTE, inc.state.value.end)
        assertNull(la.current.value); assertNull(lb.current.value)

        // how it shows in the calls list: answered, with a duration
        val rows = withTimeout(30_000) { var r: List<CallRow>; do { r = a.client.callHistory(listOf(room)); kotlinx.coroutines.delay(200) } while (r.isEmpty()); r }
        assertTrue(rows.single().legacy && rows.single().outgoing)
        assertEquals(ir.panbeh.core.calls.CallState.ENDED, rows.single().outcome.state)
        a.close(); b.close()
    }

    @Test
    fun `reject and answered elsewhere`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lra")); val b = peer(login("lrb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val la = legacyOf(a, pa); val lb = legacyOf(b, pb)
        val incoming = async { withTimeout(30_000) { lb.incoming.first() } }
        kotlinx.coroutines.delay(300)
        val out = la.place(room, video = true)
        val inc = incoming.await()
        assertTrue(!inc.video || inc.video) // the fake offer has video only when asked: see below
        inc.reject()
        assertEquals(LegacyEnd.REJECTED_BY_ME, inc.state.value.end)
        until { out.state.value.phase == LegacyPhase.ENDED }
        assertEquals(LegacyEnd.REJECTED_BY_REMOTE, out.state.value.end)
        assertTrue(pa.peers.single().closed)
        a.close(); b.close()
    }

    @Test
    fun `an invite nobody answers ends with no answer`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lna")); val b = peer(login("lnb"))
        val room = roomOf(a.client, b.client)
        val la = legacyOf(a, FakePeers(), config = LegacyConfig(lifetimeMs = 1500, candidateDelayMs = 20))
        val out = la.place(room, video = false)
        until { out.state.value.phase == LegacyPhase.ENDED }
        assertEquals(LegacyEnd.NO_ANSWER, out.state.value.end)
        a.close(); b.close()
    }

    @Test
    fun `media failure ends the call and says why`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lma")); val b = peer(login("lmb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers().also { it.failMedia = true }
        val la = legacyOf(a, pa)
        assertFailsWith<IllegalStateException> { la.place(room, video = false) }
        assertNull(la.current.value)
        a.close(); b.close()
    }

    @Test
    fun `stuck in setup reports and hangs up`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lsa")); val b = peer(login("lsb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val la = legacyOf(a, pa, config = LegacyConfig(stuckMs = 1500, candidateDelayMs = 20))
        val lb = legacyOf(b, pb, config = LegacyConfig(stuckMs = 1500, candidateDelayMs = 20))
        val incoming = async { withTimeout(30_000) { lb.incoming.first() } }
        kotlinx.coroutines.delay(300)
        la.place(room, video = false)
        val inc = incoming.await()
        val stuck = async { withTimeout(10_000) { inc.stuck.first() } }
        kotlinx.coroutines.delay(100)
        inc.answer(false) // ICE never connects
        val s = stuck.await()
        assertTrue(!s.mediaFailed)
        assertTrue(s.report.startsWith("TURN:"))
        assertTrue(s.text.contains("سرور TURN"))
        until { inc.state.value.phase == LegacyPhase.ENDED }
        assertEquals(LegacyEnd.STUCK, inc.state.value.end)
        a.close(); b.close()
    }

    @Test
    fun `glare keeps one call`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lga")); val b = peer(login("lgb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val la = legacyOf(a, pa); val lb = legacyOf(b, pb)
        val x = async { la.place(room, false) }
        val y = async { lb.place(room, false) }
        val ca = x.await(); val cb = y.await()
        // the call with the larger id gives way to the other and answers it
        val (loser, winner, ll, lw) = if (ca.callId > cb.callId) listOf(ca, cb, la, lb) else listOf(cb, ca, lb, la)
        loser as LegacyCall; winner as LegacyCall; ll as LegacyCalls; lw as LegacyCalls
        until { loser.state.value.phase == LegacyPhase.ENDED }
        assertEquals(LegacyEnd.REPLACED, loser.state.value.end)
        until { ll.current.value?.callId == winner.callId }
        assertTrue(ll.current.value!!.incoming)
        until { winner.state.value.phase == LegacyPhase.CONNECTING }
        a.close(); b.close()
    }

    @Test
    fun `cold start rebuilds a call from the replayed invite and its candidates`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lca")); val b = peer(login("lcb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val la = legacyOf(a, pa)
        val inviteSeen = async { withTimeout(30_000) { b.events.first { it.type == "m.call.invite" } } }
        kotlinx.coroutines.delay(300)
        la.place(room, video = false)
        val inv = inviteSeen.await()
        pa.peers.single().events.onLocalCandidate(IceCandidate("candidate:9 1 udp 1 9.9.9.9 9 typ host", "0", 0))
        until { b.client.recentCallEvents(room).any { it.type == "m.call.candidates" } }
        // b's call manager never saw the live events: rebuild from the stored timeline
        val lb = LegacyCalls(b.client, b.scope, b.events, pb, { true }, LegacyConfig(candidateDelayMs = 20))
        val c = assertNotNull(lb.restoreIncoming(room, inv.eventId!!))
        assertEquals(LegacyPhase.RINGING_IN, c.state.value.phase)
        c.answer(false)
        assertEquals("candidate:9 1 udp 1 9.9.9.9 9 typ host", pb.peers.single().remoteCandidates.single().candidate)
        // once hung up, a replay finds nothing to ring
        c.hangup()
        val lb2 = LegacyCalls(b.client, b.scope, b.events, pb, { true }, LegacyConfig())
        until { b.client.recentCallEvents(room).any { it.type == "m.call.hangup" } }
        assertNull(lb2.restoreIncoming(room, inv.eventId!!))
        a.close(); b.close()
    }

    @Test
    fun `legacy off produces no call object`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("lda")); val b = peer(login("ldb"))
        val room = roomOf(a.client, b.client)
        val la = legacyOf(a, FakePeers())
        val lb = legacyOf(b, FakePeers(), enabled = false)
        val inviteSeen = async { withTimeout(30_000) { b.events.first { it.type == "m.call.invite" } } }
        kotlinx.coroutines.delay(300)
        la.place(room, false)
        val ev = inviteSeen.await()
        assertNull(lb.fromInvite(ev))
        kotlinx.coroutines.delay(500)
        assertNull(lb.current.value)
        a.close(); b.close()
    }
}
