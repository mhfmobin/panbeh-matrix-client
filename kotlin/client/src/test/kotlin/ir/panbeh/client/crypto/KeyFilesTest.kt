package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import ir.panbeh.client.events.json
import ir.panbeh.client.testServer
import ir.panbeh.core.keyfile.KeyFileException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeyFilesTest {
    @Test
    fun `export from one device and import into a fresh one to read an old message`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (u, p, c1) = newUser(hs)
        val room = c1.api.room.createRoom(initialState = listOf(InitialStateEvent(EncryptionEventContent(), ""))).getOrThrow()
        withTimeout(30_000) { c1.room.getById(room).first { it?.encrypted == true } } // Trixnity encrypts only once it has seen the encryption state
        val tx = c1.room.sendMessage(room) { text("old secret") }
        val eventId = withTimeout(30_000) { c1.room.getOutbox(room, tx).first { it?.eventId != null }!!.eventId!! }

        val file = KeyFiles(c1).export("file pass", rounds = 1000)
        assertTrue(file.startsWith("-----BEGIN MEGOLM SESSION DATA-----"))

        val c2 = device(hs, u, p)
        withTimeout(30_000) { c2.room.getById(room).first { it != null } }
        assertFailsWith<KeyFileException.WrongPassphrase> { KeyFiles(c2).import(file, "bad") }
        val progress = mutableListOf<Pair<Int, Int>>()
        assertEquals(1, KeyFiles(c2).import(file, "file pass") { d, t -> progress += d to t })
        assertEquals(listOf(1 to 1), progress)
        val ev = withTimeout(60_000) {
            c2.room.getTimelineEvent(room, eventId) { decryptionTimeout = 30.seconds }.first { it?.content?.isSuccess == true }
        }!!
        assertEquals("old secret", (ev.content!!.getOrThrow() as de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent).json["body"]!!.toString().trim('"'))
    }
}
