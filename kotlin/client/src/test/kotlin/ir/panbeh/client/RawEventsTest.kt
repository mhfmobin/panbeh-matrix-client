package ir.panbeh.client

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.MessageBuilder
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import ir.panbeh.client.events.Reaction
import ir.panbeh.client.events.Sticker
import ir.panbeh.client.events.json
import ir.panbeh.client.events.rawMessage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two users in an encrypted room: Panbeh's extra fields and custom event types survive the round trip. */
class RawEventsTest {
    private suspend fun login(prefix: String): MatrixClient {
        val (u, p) = register(prefix)
        return Accounts(testPlatform()).loginPassword(testServer!!, u, p).also { it.startSync() ; withTimeout(30_000) { it.initialSyncDone.first { d -> d } } }
    }

    @Test
    fun `custom fields and types survive encryption`() = runBlocking {
        testServer ?: return@runBlocking
        val a = login("ra")
        val b = login("rb")
        val roomId = a.api.room.createRoom(
            invite = setOf(b.userId),
            initialState = listOf(InitialStateEvent(EncryptionEventContent(), "")),
        ).getOrThrow()
        withTimeout(30_000) { b.room.getById(roomId).filterNotNull().first { it.membership == Membership.INVITE } }
        b.api.room.joinRoom(roomId).getOrThrow()
        withTimeout(30_000) { a.room.getById(roomId).filterNotNull().first { true } }

        val msg = rawMessage(buildJsonObject {
            put("msgtype", "m.text"); put("body", "سلام")
            putJsonObject("app.panbeh.album") { put("id", "x"); put("n", 2); put("i", 0) }
        })
        a.room.sendMessage(roomId) { content(msg) }
        val got = withTimeout(60_000) { awaitEvent(b, roomId) { (it as? RoomMessageEventContent)?.json?.get("app.panbeh.album") != null } }
        val content = got.second as RoomMessageEventContent
        assertEquals("سلام", content.body)
        assertEquals("x", (content.json["app.panbeh.album"]!!.jsonObject["id"] as JsonPrimitive).content)

        // reaction with a custom-emoji shortcode, and a sticker (types Trixnity doesn't know)
        b.room.sendMessage(roomId) {
            content(Reaction(buildJsonObject { put("com.beeper.reaction.shortcode", ":cat:") }, RelatesTo.Annotation(got.first, "mxc://localhost/cat")))
        }
        b.room.sendMessage(roomId) { content(Sticker(buildJsonObject { put("body", "cat"); put("url", "mxc://localhost/cat") }, null)) }
        val r = withTimeout(60_000) { awaitEvent(a, roomId) { it is Reaction } }.second as Reaction
        assertEquals(":cat:", r.shortcode)
        assertEquals("mxc://localhost/cat", r.key)
        val s = withTimeout(60_000) { awaitEvent(a, roomId) { it is Sticker } }.second as Sticker
        assertEquals("cat", (s.raw["body"] as JsonPrimitive).content)
        a.closeSuspending(); b.closeSuspending()
    }

    /** Waits for a decrypted timeline event in [roomId] matching [pred]. */
    private suspend fun awaitEvent(c: MatrixClient, roomId: RoomId, pred: (Any?) -> Boolean): Pair<EventId, Any?> {
        while (true) {
            val last = c.room.getLastTimelineEvents(roomId).filterNotNull().first()
            var e: de.connect2x.trixnity.client.store.TimelineEvent? = last.first().first()
            repeat(20) {
                val cur = e ?: return@repeat
                val content = cur.content?.getOrNull()
                if (pred(content)) return cur.event.id to content
                e = c.room.getPreviousTimelineEvent(cur)?.first()
            }
            kotlinx.coroutines.delay(500)
        }
    }
}
