package ir.panbeh.client

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountsTest {
    @Test
    fun `password login, second account, switch, logout`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val accounts = Accounts(testPlatform())
        assertEquals(LoginMode.PASSWORD, accounts.loginMode(hs))
        val (a, pa) = register("a")
        val (b, pb) = register("b")
        val ca = accounts.loginPassword(hs, a, pa)
        assertEquals("@$a:localhost", ca.userId.full)
        ca.startSync()
        withTimeout(30_000) { ca.initialSyncDone.first { it } }

        val cb = accounts.loginPassword(hs, b, pb)
        assertEquals("@$b:localhost", cb.userId.full)
        assertEquals(listOf("@$a:localhost", "@$b:localhost"), accounts.accounts.map { it.userId })

        val back = accounts.switchTo("@$a:localhost")
        assertEquals(ca.deviceId, back.deviceId)

        val next = accounts.logout()
        assertEquals("@$b:localhost", next?.userId?.full)
        accounts.logout()
        assertNull(accounts.client.value)
        assertEquals(emptyList(), accounts.accounts)
    }
}
