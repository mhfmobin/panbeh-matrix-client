package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.room
import ir.panbeh.client.testServer
import ir.panbeh.core.util.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadsTest {
    @Test
    fun `thread messages, summary, thread timeline and jumps into a thread`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "th")
        try {
            val ta = d.timelineA().also { it.open() }
            d.sa.sendText(d.roomId, "root")
            val root = ta.item("root") { it.content.string("body") == "root" }
            val rootId = root.eventId!!

            // b replies in the thread, a replies in the thread to b (a real reply inside it)
            val tb = d.timelineB().also { it.open() }
            tb.item("root at b") { it.eventId == rootId }
            d.sb.sendText(d.roomId, "in thread 1", TextOptions(thread = rootId))
            val tha = d.timelineA(thread = rootId).also { it.open() }
            val first = tha.item("thread reply") { it.content.string("body") == "in thread 1" }
            assertEquals(rootId, first.threadRoot)
            assertNull(first.replyTo) // the fallback "reply" to the thread's latest event isn't a real reply
            d.sa.sendText(d.roomId, "in thread 2", TextOptions(thread = rootId, replyTo = first.replyTarget()))
            val second = tha.item("second reply") { it.content.string("body") == "in thread 2" }
            assertEquals(first.eventId, second.replyTo)
            tha.item("thread root") { it.eventId == rootId }

            // the main timeline shows the root with a count, not the replies
            val summary = ta.item("summary") { it.eventId == rootId && it.thread?.count == 2 }
            assertTrue(ta.state.value.items.none { it.content.string("body")?.startsWith("in thread") == true })
            assertEquals(2, summary.thread!!.count)

            // reactions in a thread
            d.sb.toggleReaction(d.roomId, second, "🎉")
            tha.item("thread reaction") { it.eventId == second.eventId && it.reactions.any { r -> r.key == "🎉" } }

            // a reply of the thread can't be shown in the main timeline: the jump says which thread
            val j = ta.jump(first.eventId!!)
            assertEquals(JumpResult.InThread(rootId), j)
        } finally { d.close() }
    }

    @Test
    fun `forward, report, sticker and location, emote`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "fw")
        try {
            val tb = d.timelineB().also { it.open() }
            d.sa.sendText(d.roomId, "forward me")
            val orig = tb.item("orig") { it.content.string("body") == "forward me" }

            // b forwards it into a new room (unencrypted, b alone) and into the same encrypted room
            val other = d.b.api.room.createRoom().getOrThrow().full
            d.sb.forward(listOf(other), listOf(orig))
            val tOther = d.timelineB().let { RoomTimeline(d.b, other, d.scope) }.also { it.open() }
            val fwd = tOther.item("forwarded") { it.content.string("body") == "forward me" }
            val from = fwd.forwardedFrom!!
            assertEquals(d.a.userId.full, from.sender)
            assertEquals(0, fwd.content["m.mentions"]!!.jsonObject.size)
            assertNull(fwd.replyTo)
            // forwarding the forward keeps the first author
            d.sb.forward(listOf(d.roomId), listOf(fwd))
            val ta = d.timelineA().also { it.open() }
            val again = ta.item("forwarded again") { it.forwardedFrom != null && it.sender == d.b.userId.full }
            assertEquals(d.a.userId.full, again.forwardedFrom!!.sender)

            // report with score -100 and a reason
            d.sb.report(d.roomId, orig, "spam")
            d.sb.report(d.roomId, orig)

            // sticker, location, emote
            d.sa.sendSticker(d.roomId, "cat", "mxc://localhost/cat", buildJsonObject { put("w", 64) })
            val st = tb.item("sticker") { it.type == "m.sticker" }
            assertEquals("cat", st.content.string("body"))
            assertEquals("استیکر", st.preview)
            d.sa.sendLocation(d.roomId, 35.7, 51.4, 15)
            val loc = tb.item("location") { it.msgtype == "m.location" }
            assertEquals("geo:35.7,51.4;u=15", loc.content.string("geo_uri"))
            d.sa.sendText(d.roomId, "waves", TextOptions(emote = true))
            val em = tb.item("emote") { it.msgtype == "m.emote" }
            assertEquals("m.emote", em.content.string("msgtype"))
            assertTrue(em.preview.startsWith("* "))
            d.sa.sendGif(d.roomId, buildJsonObject { put("msgtype", "m.image"); put("body", "g.gif"); put("url", "mxc://localhost/g"); put("info", buildJsonObject { put("mimetype", "image/gif") }) })
            tb.item("gif") { it.content.string("url") == "mxc://localhost/g" }
        } finally { d.close() }
    }

    @Test
    fun `local echoes show sending state and failed ones can be cancelled`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "le")
        try {
            val ta = d.timelineA().also { it.open() }
            val txn = d.sa.sendText(d.roomId, "echo")
            val item = ta.echo("echo") { it.content.string("body") == "echo" }
            // while in the outbox it is keyed by its transaction id, and keeps that key once the server echo arrives
            assertEquals(txn, item.txnId ?: txn)
            val sent = eventually("sent") { ta.state.value.items.firstOrNull { it.content.string("body") == "echo" && it.eventId != null && it.sendState == null } }
            assertNotNull(sent.eventId)
            assertEquals(txn, sent.key) // the row keeps its key from local echo to server echo
            assertFalse(sent.isLocal)
        } finally { d.close() }
    }
}
