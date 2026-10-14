package ir.panbeh.client.crypto

import ir.panbeh.client.account.NeedsPassword
import ir.panbeh.client.account.WrongPassword
import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevicesTest {
    @Test
    fun `device list and delete with the password`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        val c2 = device(hs, u, p)
        val d = Devices(c1)
        val list = withTimeout(30_000) { d.watch().first { it.size == 2 } }
        assertTrue(list[0].current)
        assertEquals(c1.deviceId, list[0].deviceId)
        assertEquals("Panbeh Test", list[0].displayName)
        assertFalse(list[1].crossSigningVerified)
        assertEquals(c2.deviceId, list[1].deviceId)

        assertFailsWith<NeedsPassword> { d.delete(listOf(c2.deviceId)) }
        assertFailsWith<WrongPassword> { d.delete(listOf(c2.deviceId), "nope") }
        d.delete(listOf(c2.deviceId), p)
        assertEquals(listOf(c1.deviceId), withTimeout(30_000) { d.watch().first { it.size == 1 } }.map { it.deviceId })
    }

    @Test
    fun `user trust of someone without cross-signing is unknown`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, _, c1) = newUser(hs)
        val (v, _, _) = newUser(hs)
        val t = withTimeout(30_000) { Devices(c1).userTrust("@$v:localhost").first() }
        assertFalse(t.known)
        assertTrue(u.isNotEmpty())
    }
}
