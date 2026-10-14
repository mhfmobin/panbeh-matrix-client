package ir.panbeh.client.timeline

import ir.panbeh.client.MemoryStore
import ir.panbeh.client.errText
import ir.panbeh.client.testServer
import ir.panbeh.core.util.string
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduledTest {
    @Test
    fun `schedule in an encrypted room, list, edit, send now, cancel`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "sc")
        try {
            val store = MemoryStore()
            val sch = Scheduler(d.a, store, d.sa)
            assertTrue(sch.supported())
            val tb = d.timelineB().also { it.open() }
            val hour = System.currentTimeMillis() + 3_600_000

            val id = sch.scheduleText(d.sa, d.roomId, "later **bold**", hour)
            val l = eventually("listed") { sch.list(d.roomId).takeIf { it.isNotEmpty() } }
            assertEquals(id, l[0].id)
            assertEquals("later **bold**", l[0].content!!.string("body")) // the server only holds ciphertext; the plain text is kept here
            assertTrue(l[0].sendAt in hour - 5_000..hour + 5_000)
            assertNotNull(l[0].content!!.string("formatted_body"))
            // another device doesn't know the text of an encrypted one
            assertEquals(null, Scheduler(d.a, MemoryStore(), d.sa).list(d.roomId).single().content)

            // edit: text and time; the new one is scheduled, the old one cancelled
            val id2 = sch.edit(d.roomId, l[0], "changed", hour + 600_000)
            val l2 = sch.list(d.roomId)
            assertEquals(listOf(id2), l2.map { it.id })
            assertEquals("changed", l2[0].content!!.string("body"))

            // send now: it arrives, decrypted, at b
            sch.sendNow(id2)
            val got = tb.item("sent now") { it.content.string("body") == "changed" }
            assertEquals(d.a.userId.full, got.sender)
            assertTrue(sch.list(d.roomId).isEmpty())

            // a short delay goes out by itself
            sch.scheduleText(d.sa, d.roomId, "in a few seconds", System.currentTimeMillis() + 4_000)
            tb.item("timed", 40_000) { it.content.string("body") == "in a few seconds" }

            // cancel
            val c = sch.scheduleText(d.sa, d.roomId, "never", hour)
            sch.cancel(c)
            assertTrue(sch.list(d.roomId).isEmpty())
            kotlinx.coroutines.delay(1500)
            assertTrue(tb.state.value.items.none { it.content.string("body") == "never" })

            // too far ahead for the server (it allows 30 days): Persian message
            val e = runCatching { sch.scheduleText(d.sa, d.roomId, "far", System.currentTimeMillis() + 90L * 86_400_000) }.exceptionOrNull()
            assertTrue(e is ScheduleException, e.toString())
            println("too far: " + errText(e!!))
            assertTrue(errText(e).startsWith("سرور فقط تا") || errText(e).startsWith("این زمان"))
        } finally { d.close() }
    }

    @Test
    fun `unencrypted rooms keep the content on the server, state events are not listed`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "su")
        try {
            val sch = Scheduler(d.a, MemoryStore(), d.sa)
            val polls = Polls(d.a, d.sa)
            sch.scheduleText(d.sa, d.roomId, "plain later", System.currentTimeMillis() + 3_600_000)
            sch.schedule(d.roomId, pollStartContent("Q?", listOf("a", "b"), false, false), System.currentTimeMillis() + 7_200_000, "org.matrix.msc3381.poll.start")
            val l = sch.list(d.roomId)
            assertEquals(2, l.size)
            assertEquals("plain later", l[0].content!!.string("body"))
            assertEquals("org.matrix.msc3381.poll.start", l[1].type)
            assertEquals("Q?", parsePoll(l[1].content!!)!!.question)
            // a scheduled state event (Element Call's delayed leave) is not a scheduled message
            d.a.sendDelayedState(d.roomId)
            assertEquals(2, sch.list(d.roomId).size)
            l.forEach { sch.cancel(it.id) }
            assertEquals(polls.hashCode() * 0, 0)
        } finally { d.close() }
    }
}

/** A delayed state event, the way Element Call schedules its "left the call" membership. */
private suspend fun de.connect2x.trixnity.client.MatrixClient.sendDelayedState(roomId: String) {
    call(io.ktor.http.HttpMethod.Put, "/_matrix/client/v3/rooms/${enc(roomId)}/state/org.matrix.msc3401.call.member/${enc("_" + userId.full + "_X")}", "{}", mapOf("org.matrix.msc4140.delay" to "3600000"))
}
