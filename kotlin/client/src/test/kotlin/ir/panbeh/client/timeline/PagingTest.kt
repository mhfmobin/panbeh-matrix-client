package ir.panbeh.client.timeline

import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.client.events.rawMessage
import ir.panbeh.client.testServer
import ir.panbeh.core.timeline.Row
import ir.panbeh.core.util.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagingTest {
    /** n messages "msg i" straight through the API (the room is unencrypted); returns their event ids. */
    private suspend fun fill(d: Duo, n: Int, from: Int = 0) = (from until from + n).map { i ->
        d.a.api.room.sendMessageEvent(RoomId(d.roomId), rawMessage(buildJsonObject { put("msgtype", "m.text"); put("body", "msg $i") })).getOrThrow().full
    }

    private fun bodies(t: RoomTimeline) = t.state.value.items.mapNotNull { it.content.string("body") }.filter { it.startsWith("msg ") }.map { it.removePrefix("msg ").toInt() }

    @Test
    fun `older pages load, in order and without duplicates, until the start of the room`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "pg")
        try {
            fill(d, 120)
            val tb = d.timelineB().also { it.open() }
            eventually("newest") { if (bodies(tb).lastOrNull() == 119) true else null }
            val first = bodies(tb).size
            assertTrue(first in 20..120, "first window: $first")
            assertTrue(tb.state.value.canLoadOlder)
            assertTrue(tb.state.value.atLive)
            var guard = 0
            while (tb.state.value.canLoadOlder && guard++ < 30) tb.loadOlder()
            val all = bodies(tb)
            assertEquals((0 until 120).toList(), all)
            assertFalse(tb.state.value.canLoadOlder)
            assertTrue(tb.state.value.items.first().kind == ItemKind.NOTICE) // the room's creation
            // day separators / bursts are in the rows
            assertTrue(tb.state.value.rows.first() is Row.Day)
            // a message sent now follows at the end
            fill(d, 1, 120)
            eventually("follows live") { if (bodies(tb).lastOrNull() == 120) true else null }
        } finally { d.close() }
    }

    @Test
    fun `jumping around an old event opens a window that extends both ways and meets the live end`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "jp")
        try {
            val ids = fill(d, 150)
            val tb = d.timelineB().also { it.open() }
            eventually("newest") { if (bodies(tb).lastOrNull() == 149) true else null }
            assertFalse(bodies(tb).contains(10))
            val seq0 = tb.state.value.windowSeq
            assertEquals(JumpResult.Found, tb.jump(ids[10]))
            val st = tb.state.value
            assertEquals(ids[10], st.focus)
            assertTrue(st.windowSeq > seq0)
            assertTrue(bodies(tb).contains(10))
            assertTrue(st.canLoadNewer && !st.atLive)
            assertFalse(bodies(tb).contains(149))
            // extend older and newer
            tb.loadOlder()
            assertTrue(bodies(tb).min() < 10 || tb.state.value.canLoadOlder.not())
            var guard = 0
            while (tb.state.value.canLoadNewer && guard++ < 30) tb.loadNewer()
            assertTrue(tb.state.value.atLive)
            assertEquals(149, bodies(tb).last())
            val b = bodies(tb)
            assertEquals(b.sorted(), b)
            assertEquals(b.size, b.toSet().size)
            // jumping to something already loaded only focuses it
            val seq = tb.state.value.windowSeq
            assertEquals(JumpResult.Found, tb.jump(ids[100]))
            assertEquals(seq, tb.state.value.windowSeq)
            assertEquals(ids[100], tb.state.value.focus)
            // back to the live end from a window
            assertEquals(JumpResult.Found, tb.jump(ids[3]))
            tb.jumpToLive()
            assertTrue(tb.state.value.atLive)
            assertEquals(149, bodies(tb).last())
            // an unknown event
            assertEquals(JumpResult.NotFound, tb.jump("\$nonexistent:localhost"))
            // loadEvent: a reply target that isn't loaded
            val lone = RoomTimeline(d.b, d.roomId, d.scope).also { it.open() }
            val far = lone.loadEvent(ids[2])
            assertNotNull(far)
            assertEquals("msg 2", far.content.string("body"))
        } finally { d.close() }
    }

    @Test
    fun `an old read point opens the history around it with the divider`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "uw")
        try {
            val ids = fill(d, 130)
            d.sb.markRead(d.roomId, ids[20])
            // b has read up to msg 20 only; the newest 110 are unread: the timeline opens around the read point
            kotlinx.coroutines.delay(1500)
            val tb = d.timelineB().also { it.open() }
            eventually("window", 90_000) { if (tb.state.value.unreadAfter != null) true else null }
            val st = tb.state.value
            val after = st.byKey[st.unreadAfter]!!
            assertEquals("msg 20", after.content.string("body"))
            assertTrue(st.canLoadNewer)
            assertTrue(st.rows.any { it is Row.Unread })
            assertNull(null)
        } finally { d.close() }
    }

    @Test
    fun `an encrypted room pages too`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "ep")
        try {
            repeat(25) { d.sa.sendText(d.roomId, "msg $it") }
            val tb = d.timelineB(opts = TimelineOptions(pageSize = 10)).also { it.open() }
            eventually("all decrypted", 120_000) { if (bodies(tb).size == 25) true else null }
            assertTrue(tb.state.value.items.all { it.decryption == Decryption.OK })
            assertEquals((0 until 25).toList(), bodies(tb))
        } finally { d.close() }
    }
}
