package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.clientserverapi.client.getStateEventContent
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import ir.panbeh.client.testServer
import ir.panbeh.core.calls.CallState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** The facade end to end: start, ring, answer, hang-up rules, power, history. */
class CallsFlowTest {
    private fun calls(p: Peer, peers: FakePeers? = null, rtc: RtcConfig = RtcConfig(), legacy: Boolean = false, dev: Boolean = false) =
        Calls(p.client, p.scope, mockSfu, peers, { legacy }, { dev }, rtc, LegacyConfig(candidateDelayMs = 20),
            keyTransports = { emptyList() }).also { it.start() }

    @Test
    fun `start, ring, answer, then the other side leaves and the DM call ends`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("fa")); val b = peer(login("fb"))
        val room = roomOf(a.client, b.client)
        val ca = calls(a); val cb = calls(b)
        delay(300)
        assertTrue(ca.availability(room).rtc)
        val started = ca.startCall(room, video = true) as ActiveCall.Rtc
        assertEquals(ca.active.value, started)
        assertEquals("wss://lk.example/livekit", started.connection.url)
        val i = withTimeout(30_000) { cb.incoming.first { it != null } }!!
        assertTrue(i.video)
        val answered = cb.answer(video = true) as ActiveCall.Rtc
        assertNull(cb.incoming.value)
        assertEquals(room, answered.roomId)
        // both are in; the call "started" for the caller
        withTimeout(30_000) { started.session.since.first { it != null } }
        withTimeout(30_000) { started.session.memberships.first { it.size == 2 } }
        val ended = async { withTimeout(30_000) { ca.ended.first() } }
        delay(100)
        cb.hangup()
        assertNull(cb.active.value)
        assertEquals(CallEndReason.OTHER_SIDE_LEFT, ended.await())
        withTimeout(30_000) { ca.active.first { it == null } }

        // the ring and the call show up in the calls list: answered, with a duration
        val rows = withTimeout(30_000) { var r: List<CallRow>; do { r = a.client.callHistory(listOf(room)); delay(300) } while (r.isEmpty() || r.first().outcome.state != CallState.ENDED); r }
        val row = rows.first()
        assertTrue(row.outgoing && row.video && !row.missed && !row.legacy)
        assertNotNull(row.outcome.duration)
        assertTrue(row.text.startsWith("خروجی تصویری"))
        a.close(); b.close()
    }

    @Test
    fun `an unanswered DM call gives up and a declined one ends`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("na")); val b = peer(login("nb"))
        val room = roomOf(a.client, b.client)
        val ca = calls(a, rtc = RtcConfig(noAnswerMs = 1500)); val cb = calls(b)
        delay(300)
        ca.startCall(room, video = false)
        assertEquals(CallEndReason.NO_ANSWER, withTimeout(30_000) { ca.ended.first() })
        assertNull(ca.active.value)
        withTimeout(30_000) { cb.incoming.first { it == null || true } }

        delay(500)
        ca.startCall(room, video = false)
        withTimeout(30_000) { cb.incoming.first { it != null } }
        val ended = async { withTimeout(30_000) { ca.ended.first() } }
        delay(100)
        cb.decline()
        assertEquals(CallEndReason.DECLINED, ended.await())
        // missed/declined rows
        val rows = withTimeout(30_000) { var r: List<CallRow>; do { r = b.client.callHistory(listOf(room)); delay(300) } while (r.size < 2); r }
        assertTrue(rows.all { !it.outgoing })
        assertTrue(rows.any { it.outcome.state == CallState.DECLINED && it.text.startsWith("رد شده") })
        assertTrue(rows.any { it.outcome.state == CallState.MISSED && it.missed })
        a.close(); b.close()
    }

    @Test
    fun `joining needs power, and an admin can enable calls`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("pa")); val b = peer(login("pb"))
        val room = roomOf(a.client, b.client, direct = false)
        val pa = CallPower(a.client); val pb = CallPower(b.client)
        // ordinary members start with state_default 50: not allowed. The creator is.
        withTimeout(30_000) { while (pb.access(room) != CallAccess.DENIED) delay(100) }
        assertEquals(CallAccess.ALLOWED, pa.access(room))
        // b as a moderator that can change power levels but not join calls
        setPl(a.client, room) { it.copy(users = it.users + (b.client.userId to 50L)) }
        setPl(a.client, room) { it.copy(events = it.events.filterKeys { k -> k.name != "m.room.power_levels" } + (EventType(null, "m.room.power_levels") to 50L)) }
        setPl(a.client, room) { it.copy(events = it.events.filterKeys { k -> k.name != CallTypes.MEMBER } + (EventType(null, CallTypes.MEMBER) to 100L)) }
        withTimeout(30_000) { while (pb.access(room) != CallAccess.ASK_ENABLE) delay(100) }
        val cb = calls(b)
        val e = assertFailsWith<CallPermissionException> { cb.startCall(room, false) }
        assertEquals(CallAccess.ASK_ENABLE, e.access)
        assertEquals("مدیر گروه هنوز تماس را برای اعضا فعال نکرده است", e.message)
        pb.allowCalls(room)
        withTimeout(30_000) { while (pb.access(room) != CallAccess.ALLOWED) delay(100) }
        assertTrue(pb.canJoin(room).first())
        cb.startCall(room, false)
        cb.hangup()
        a.close(); b.close()
    }

    private suspend fun setPl(c: MatrixClient, room: RoomId, f: (PowerLevelsEventContent) -> PowerLevelsEventContent) {
        val pl = c.api.room.getStateEventContent<PowerLevelsEventContent>(room).getOrThrow()
        c.api.room.sendStateEvent(room, f(pl)).getOrThrow()
    }

    @Test
    fun `legacy call through the facade and availability`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("xa")); val b = peer(login("xb"))
        val room = roomOf(a.client, b.client)
        val pa = FakePeers(); val pb = FakePeers()
        val ca = calls(a, pa, dev = true); val cb = calls(b, pb, legacy = true)
        delay(300)
        until { ca.availability(room).legacy }
        assertTrue(!cb.availability(room).legacy)
        val out = ca.startCall(room, video = false, legacy = true) as ActiveCall.Legacy
        val i = withTimeout(30_000) { cb.incoming.first { it != null } }!!
        assertNotNull(i.legacy)
        val inc = cb.answer(false) as ActiveCall.Legacy
        assertEquals(inc.call, cb.active.value?.let { (it as ActiveCall.Legacy).call })
        pa.peers.single().events.onConnectionState(PeerConnectionState.CONNECTED)
        until { out.call.state.value.phase == LegacyPhase.CONNECTED }
        val ended = async { withTimeout(30_000) { cb.ended.first() } }
        delay(100)
        ca.hangup()
        assertEquals(CallEndReason.LEGACY_ENDED, ended.await())
        a.close(); b.close()
    }
}
