package ir.panbeh.client.timeline

import ir.panbeh.client.testServer
import ir.panbeh.core.timeline.Row
import ir.panbeh.core.util.string
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptsTest {
    @Test
    fun `read receipts show read-by and the unread divider is placed from the read point`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "rc")
        try {
            val ta = d.timelineA().also { it.open() }
            val tb = d.timelineB().also { it.open() }
            d.sa.sendText(d.roomId, "m1")
            val m1 = tb.item("m1") { it.content.string("body") == "m1" }
            val mine = ta.item("m1 at a") { it.eventId == m1.eventId }
            assertEquals(emptyList(), mine.readBy)
            tb.markRead(m1.eventId!!)
            val read = ta.item("read-by") { it.eventId == m1.eventId && it.readBy.isNotEmpty() }
            assertEquals(d.b.userId.full, read.readBy.single().userId)
            assertTrue(read.readBy.single().ts > 0)

            // b answers (its own message is its read point), then a writes two more
            d.sb.sendText(d.roomId, "b1")
            val b1 = tb.item("b1") { it.content.string("body") == "b1" }
            d.sa.sendText(d.roomId, "m2")
            d.sa.sendText(d.roomId, "m3")
            tb.item("m3 at b") { it.content.string("body") == "m3" }
            // a new timeline for b: the divider follows b1, and the rows show it
            val again = d.timelineB().also { it.open() }
            eventually("unread divider") { if (again.state.value.unreadAfter != null) true else null }
            val st = again.state.value
            assertEquals(b1.key, st.unreadAfter)
            val rows = st.rows
            val i = rows.indexOfFirst { it is Row.Unread }
            assertTrue(i > 0)
            assertEquals(b1.key, (rows[i - 1] as Row.Message).id)
            // all caught up: no divider on a's side (its own messages are last)
            assertNull(ta.state.value.unreadAfter)

            // typing
            d.sa.typing(d.roomId, true)
            val names = eventually("typing") { d.b.typingIn(d.roomId).first().takeIf { it.isNotEmpty() } }
            assertEquals(d.a.userId.full, names.single().userId)
            assertTrue(typingText(names.map { it.name })!!.endsWith("در حال نوشتن"))
            d.sa.typing(d.roomId, false)
            eventually("stopped typing") { if (d.b.typingIn(d.roomId).first().isEmpty()) true else null }
            // throttled: a second "on" within 3 s sends nothing new (no exception either way)
            d.sa.typing(d.roomId, true); d.sa.typing(d.roomId, true)
            withTimeout(5000) { d.sa.typing(d.roomId, false) }
        } finally { d.close() }
    }
}
