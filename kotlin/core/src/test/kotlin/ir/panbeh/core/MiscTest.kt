package ir.panbeh.core

import ir.panbeh.core.util.*
import kotlin.test.*

class MiscTest {
    @Test fun `endpointOf names the endpoint for the net log`() {
        assertEquals("sync", endpointOf("https://hs.x/_matrix/client/v3/sync?since=s1&timeout=30000"))
        assertEquals("versions", endpointOf("https://hs.x/_matrix/client/versions"))
        assertEquals("rooms", endpointOf("https://hs.x/_matrix/client/v3/rooms/!a:x/send/m.room.message/1"))
        assertEquals("thumbnail", endpointOf("https://hs.x/_matrix/client/v1/media/thumbnail/x/y?width=96"))
        assertEquals("org.matrix.msc3575", endpointOf("https://hs.x/_matrix/client/unstable/org.matrix.msc3575/sync"))
    }

    @Test fun `toEvict drops the oldest until the rest fit`() {
        val e = listOf("a" to 40L, "b" to 30L, "c" to 20L, "d" to 10L)
        assertEquals(emptyList(), toEvict(e, 100))
        assertEquals(listOf("a"), toEvict(e, 60))
        assertEquals(listOf("a", "b", "c"), toEvict(e, 25))
        assertEquals(listOf("a", "b", "c", "d"), toEvict(e, 0))
    }

    @Test fun `hashPin - same PIN and salt agree, anything else doesn't`() {
        val a = hashPin("1234", "salt", 1000)
        assertEquals(a, hashPin("1234", "salt", 1000))
        assertNotEquals(a, hashPin("1235", "salt", 1000))
        assertNotEquals(a, hashPin("1234", "other", 1000))
        // vectors from Node's crypto.pbkdf2Sync(..., "sha256").toString("base64")
        assertEquals("/vKS0jH25vXnh7ItsSiw5Q0uddDD1/KYnnf3ICZGcXs=", a)
        assertEquals("Eg+2z/z4syxD5yJSVsT4N6hlSMkszDVICAWYfLcL4Xs=", hashPin("password", "salt", 1))
    }

    @Test fun `lockDelay - free tries, then doubling up to an hour`() {
        assertEquals(0, lockDelay(4))
        assertEquals(30_000, lockDelay(5))
        assertEquals(60_000, lockDelay(6))
        assertEquals(3_600_000, lockDelay(30))
    }

    @Test fun `fitMenu - after the click, flipped before it near the edge, kept on screen`() {
        assertEquals(100.0, fitMenu(100.0, 200.0, 1000.0))
        assertEquals(700.0, fitMenu(900.0, 200.0, 1000.0)) // last message: opens upward
        assertEquals(8.0, fitMenu(150.0, 200.0, 300.0)) // fits neither way: clamped
        assertEquals(8.0, fitMenu(5.0, 200.0, 1000.0))
    }
}
