package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import ir.panbeh.client.Accounts
import ir.panbeh.client.register
import ir.panbeh.client.testPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** A logged-in, synced client of [user] (its own Accounts, so several devices of one user can run side by side). */
suspend fun device(hs: String, user: String, pass: String): MatrixClient {
    val c = Accounts(testPlatform()).loginPassword(hs, user, pass)
    c.startSync()
    withTimeout(60_000) { c.initialSyncDone.first { it } }
    return c
}

suspend fun newUser(hs: String, prefix: String = "c"): Triple<String, String, MatrixClient> {
    val (u, p) = register(prefix)
    return Triple(u, p, device(hs, u, p))
}
