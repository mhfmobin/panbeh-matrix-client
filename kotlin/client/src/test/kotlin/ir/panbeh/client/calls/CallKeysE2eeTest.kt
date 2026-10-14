package ir.panbeh.client.calls

import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Frame-encryption keys travel between two real clients in an encrypted room. */
class CallKeysE2eeTest {
    private fun roundTrip(name: String, transports: (de.connect2x.trixnity.client.MatrixClient) -> List<KeyTransport>) = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("k${name}a")).let { Peer(it.client, it.scope, MatrixRtc(it.client, it.scope, it.events, SfuClient(mockSfu), RtcConfig(), transports)) }
        val b = peer(login("k${name}b")).let { Peer(it.client, it.scope, MatrixRtc(it.client, it.scope, it.events, SfuClient(mockSfu), RtcConfig(), transports)) }
        val room = roomOf(a.client, b.client, encrypted = true)
        val ca = a.rtc.session(room).join(CallIntent.AUDIO, ring = false)
        val ka = ca.keys!!
        val cb = b.rtc.session(room).join(CallIntent.AUDIO, ring = false)
        val kb = cb.keys!!
        // each gets the other's key, filed under the LiveKit identity "@user:server:DEVICE"
        val fromA = withTimeout(60_000) { kb.remote.first { it.identity == ca.identity } }
        val fromB = withTimeout(60_000) { ka.remote.first { it.identity == cb.identity } }
        assertContentEquals(ka.local.value!!.key, fromA.key); assertEquals(ka.local.value!!.index, fromA.index)
        assertContentEquals(kb.local.value!!.key, fromB.key); assertEquals(kb.local.value!!.index, fromB.index)
        assertEquals(16, fromA.key.size)
        a.rtc.session(room).leave(); b.rtc.session(room).leave()
        a.close(); b.close()
    }

    @Test fun `room event transport`() = roundTrip("r") { listOf(RoomKeyTransport(it)) }
    @Test fun `to-device transport`() = roundTrip("t") { listOf(ToDeviceKeyTransport(it)) }

    @Test
    fun `unencrypted rooms have no keys`() = runBlocking {
        testServer ?: return@runBlocking
        val a = peer(login("kua")); val b = peer(login("kub"))
        val room = roomOf(a.client, b.client)
        assertNull(a.rtc.session(room).join(CallIntent.AUDIO, ring = false).keys)
        a.rtc.session(room).leave()
        a.close(); b.close()
    }
}
