package ir.panbeh.client.crypto

import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoveryTest {
    @Test
    fun `setup then unlock on a second device with the recovery key`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        val r1 = Recovery(c1)
        assertEquals(RecoveryState.SETUP, withTimeout(30_000) { r1.state().first() })
        val key = r1.setup(password = p)
        assertTrue(key.isNotBlank())
        assertEquals(RecoveryState.OK, withTimeout(30_000) { r1.state().first { it == RecoveryState.OK } })

        val c2 = device(hs, u, p)
        val r2 = Recovery(c2)
        assertEquals(RecoveryState.UNLOCK, withTimeout(30_000) { r2.state().first() })
        assertFailsWith<IllegalArgumentException> { r2.unlock("EsTc 1234 not a key") }
        r2.unlock(key)
        assertEquals(RecoveryState.OK, withTimeout(30_000) { r2.state().first { it == RecoveryState.OK } })
        val me = withTimeout(30_000) { Devices(c2).watch().first { l -> l.any { it.current && it.crossSigningVerified } } }
        assertTrue(me.any { it.current && it.crossSigningVerified })
    }

    @Test
    fun `setup with a passphrase and unlock with it`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        Recovery(c1).setup(password = p, passphrase = "correct horse battery")
        val r2 = Recovery(device(hs, u, p))
        assertFailsWith<IllegalArgumentException> { r2.unlock("wrong passphrase") }
        r2.unlock("correct horse battery")
        assertEquals(RecoveryState.OK, withTimeout(30_000) { r2.state().first { it == RecoveryState.OK } })
    }
}
