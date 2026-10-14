package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

class BackupRestoreTest {
    @Test
    fun `a new device restores old message keys from the key backup`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        val key = Recovery(c1).setup(password = p)
        val room = c1.api.room.createRoom(initialState = listOf(de.connect2x.trixnity.core.model.events.InitialStateEvent(de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent(), ""))).getOrThrow()
        val tx = c1.room.sendMessage(room) { text("from the backup") }
        val eventId = withTimeout(30_000) { c1.room.getOutbox(room, tx).first { it?.eventId != null }!!.eventId!! }
        // the new session is uploaded to the backup in the background (after a second)
        withTimeout(30_000) {
            while (c1.api.key.getRoomKeys(c1.api.key.getRoomKeysVersion().getOrThrow().let { (it as de.connect2x.trixnity.clientserverapi.model.key.GetRoomKeysBackupVersionResponse.V1).version }).getOrThrow().rooms.isEmpty()) kotlinx.coroutines.delay(500)
        }

        val c2 = device(hs, u, p)
        withTimeout(30_000) { c2.room.getById(room).first { it != null } }
        val r2 = Recovery(c2)
        r2.unlock(key)
        val seen = mutableListOf<Pair<Int, Int>>()
        assertEquals(1, r2.restoreKeyBackup { d, t -> seen += d to t })
        assertEquals(listOf(1 to 1), seen)
        val ev = withTimeout(60_000) { c2.room.getTimelineEvent(room, eventId) { decryptionTimeout = 30.seconds }.first { it?.content?.isSuccess == true } }!!
        assertEquals("from the backup", (ev.content!!.getOrThrow() as de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent).body)
    }
}
