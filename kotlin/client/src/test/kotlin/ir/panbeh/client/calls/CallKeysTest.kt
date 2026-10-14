package ir.panbeh.client.calls

import de.connect2x.trixnity.core.model.RoomId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CallKeysTest {
    private val room = RoomId("!r:localhost")

    private class FakeTransport : KeyTransport {
        val sent = mutableListOf<Triple<Set<Pair<String, String>>, Int, ByteArray>>()
        val incoming = MutableSharedFlow<IncomingKeys>(extraBufferCapacity = 16)
        override val received: Flow<IncomingKeys> = incoming
        override suspend fun send(roomId: RoomId, myDevice: String, to: Collection<Pair<String, String>>, keys: List<Pair<Int, ByteArray>>) {
            keys.forEach { (i, k) -> sent += Triple(to.toSet(), i, k) }
        }
    }

    private fun mem(u: String, d: String) = CallMembership(u, d, "k", null, 0, null, null, emptyList())

    @Test
    fun `wire format round trips and accepts Element's variants`() {
        val k = ByteArray(16) { it.toByte() }
        assertEquals(22, encodeKey(k).length) // unpadded
        assertContentEquals(k, decodeKey(encodeKey(k)))
        assertContentEquals(k, decodeKey(encodeKey(k) + "=="))
        assertContentEquals(byteArrayOf(-5, -1), decodeKey("-_8"))
        val td = keysToDeviceContent("!r:localhost", "DEV", 3, k, 1)
        assertEquals("DEV", td["member"]!!.jsonObject["claimed_device_id"]!!.jsonPrimitive.content)
        assertEquals("", td["session"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
        val (dev, keys) = parseKeys(td, "!r:localhost")!!
        assertEquals("DEV", dev); assertEquals(3, keys.single().first); assertContentEquals(k, keys.single().second)
        val room = keysRoomContent("DEV2", listOf(0 to k, 1 to k), 1)
        assertEquals(2, parseKeys(room, "!r:localhost")!!.second.size)
        assertEquals("DEV2", parseKeys(room, null)!!.first)
        assertNull(parseKeys(td, "!other:localhost"))
        assertNull(parseKeys(JsonObject(td + ("session" to buildJsonObject { put("call_id", "x") })), "!r:localhost"))
    }

    @Test
    fun `makes a key, hands it to members as they join, rotates when one leaves`() = runBlocking {
        val tr = FakeTransport()
        val members = MutableStateFlow(listOf(mem("@me:l", "D")))
        val m = CallKeyManager(this, room, "@me:l", "D", members, listOf(tr), rotateDelayMs = 50)
        m.start()
        val k0 = m.local.value!!
        assertEquals(0, k0.index); assertEquals(16, k0.key.size); assertEquals("@me:l:D", k0.identity)
        assertTrue(tr.sent.isEmpty())

        members.value = listOf(mem("@me:l", "D"), mem("@b:l", "B1"))
        withTimeout(5000) { while (tr.sent.isEmpty()) delay(10) }
        assertEquals(setOf("@b:l" to "B1"), tr.sent.single().first)
        assertContentEquals(k0.key, tr.sent.single().third)

        members.value = listOf(mem("@me:l", "D"), mem("@b:l", "B1"), mem("@c:l", "C1"))
        withTimeout(5000) { while (tr.sent.size < 2) delay(10) }
        assertEquals(setOf("@c:l" to "C1"), tr.sent[1].first) // only the new one

        members.value = listOf(mem("@me:l", "D"), mem("@c:l", "C1")) // B left
        withTimeout(5000) { while (m.local.value!!.index == 0) delay(10) }
        assertEquals(1, m.local.value!!.index)
        assertNotEquals(k0.key.toList(), m.local.value!!.key.toList())
        withTimeout(5000) { while (tr.sent.size < 3) delay(10) }
        assertEquals(setOf("@c:l" to "C1"), tr.sent[2].first)
        assertEquals(1, tr.sent[2].second)
        m.stop()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `reports received keys once and ignores our own`() = runBlocking {
        val tr = FakeTransport()
        val m = CallKeyManager(this, room, "@me:l", "D", MutableStateFlow(emptyList()), listOf(tr))
        m.start()
        val got = mutableListOf<ParticipantKey>()
        val job = launch { m.remote.collect { got += it } }
        delay(50)
        val k = ByteArray(16) { 7 }
        val c = keysToDeviceContent(room.full, "B1", 2, k, 1)
        tr.incoming.emit(IncomingKeys(room.full, "@b:l", c))
        tr.incoming.emit(IncomingKeys(room.full, "@b:l", c))
        tr.incoming.emit(IncomingKeys(room.full, "@me:l", keysToDeviceContent(room.full, "D", 0, k, 1)))
        tr.incoming.emit(IncomingKeys("!other:l", "@b:l", keysToDeviceContent("!other:l", "B1", 5, k, 1)))
        withTimeout(5000) { while (got.isEmpty()) delay(10) }
        delay(100)
        assertEquals(1, got.size)
        assertEquals("@b:l:B1", got[0].identity); assertEquals(2, got[0].index); assertContentEquals(k, got[0].key)
        job.cancel(); m.stop(); coroutineContext.cancelChildren()
    }
}
