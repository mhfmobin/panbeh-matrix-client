package ir.panbeh.client.timeline

import ir.panbeh.client.testServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PollsTest {
    @Test
    fun `create, vote, retract, tally, end`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "po")
        try {
            val pa = Polls(d.a, d.sa)
            val pb = Polls(d.b, d.sb)
            val tb = d.timelineB().also { it.open() }
            pa.create(d.roomId, "Tea or coffee?", listOf("tea", "coffee", "water"), multi = false, undisclosed = false)
            val item = tb.item("poll") { isPollStart(it.type) }
            val id = item.eventId!!
            val poll = parsePoll(item.content)!!
            assertEquals("Tea or coffee?", poll.question)
            assertEquals("📊 Tea or coffee?", item.preview)
            val ids = poll.answers.map { it.id }

            suspend fun stateOf(p: Polls, ok: (PollState) -> Boolean) = withTimeout(60_000) { p.state(d.roomId, id).first { ok(it) } }

            val s0 = stateOf(pa) { it.poll != null }
            assertTrue(s0.canEdit && s0.canEnd && !s0.ended)
            assertFalse(stateOf(pb) { it.poll != null }.canEnd) // not the author, not a moderator

            // votes: the latest of each voter counts, a vote for an unknown answer is spoiled
            pb.vote(d.roomId, id, listOf(ids[0]))
            val v1 = stateOf(pa) { it.tally.voters[ids[0]]?.size == 1 }
            assertEquals(listOf(d.b.userId.full), v1.tally.voters[ids[0]])
            assertFalse(v1.canEdit) // somebody voted
            pb.vote(d.roomId, id, listOf(ids[1]))
            pa.vote(d.roomId, id, listOf(ids[1]))
            val v2 = stateOf(pa) { it.tally.voters[ids[1]]?.size == 2 }
            assertEquals(0, v2.tally.voters[ids[0]]!!.size)
            assertEquals(2, v2.total)
            assertEquals(listOf(ids[1]), v2.mine)
            assertTrue(v2.showResults)
            // retracting = a spoiled vote
            pb.vote(d.roomId, id, emptyList())
            val v3 = stateOf(pa) { it.tally.voters[ids[1]]?.size == 1 }
            assertEquals(1, v3.total)

            // ending: the author may; votes after the end don't count
            pa.end(d.roomId, id)
            val ended = stateOf(pb) { it.ended }
            assertTrue(ended.showResults)
            pb.vote(d.roomId, id, listOf(ids[2]))
            kotlinx.coroutines.delay(3000)
            assertEquals(0, stateOf(pa) { it.ended }.tally.voters[ids[2]]!!.size)
        } finally { d.close() }
    }

    @Test
    fun `an undisclosed poll hides results until the end, and an unvoted poll can be edited`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "pu")
        try {
            val pa = Polls(d.a, d.sa)
            val tb = d.timelineB().also { it.open() }
            pa.create(d.roomId, "Q1", listOf("x", "y"), multi = true, undisclosed = true)
            val item = tb.item("poll") { isPollStart(it.type) }
            val id = item.eventId!!
            val answers = parsePoll(item.content)!!
            assertTrue(answers.undisclosed && answers.multi)
            val pb = Polls(d.b, d.sb)
            pb.vote(d.roomId, id, listOf(answers.answers[0].id, answers.answers[1].id))
            val s = withTimeout(60_000) { pb.state(d.roomId, id).first { it.mine.size == 2 } }
            assertFalse(s.showResults) // undisclosed: nothing until it ends
            // a second poll, edited before anybody votes
            pa.create(d.roomId, "Q2", listOf("a", "b"))
            val p2 = tb.item("poll2") { isPollStart(it.type) && parsePoll(it.content)?.question == "Q2" }
            pa.edit(d.roomId, p2.eventId!!, "Q2 edited", listOf("a", "b", "c"))
            val edited = tb.item("edited") { it.eventId == p2.eventId && parsePoll(it.content)?.question == "Q2 edited" }
            assertTrue(edited.edited)
            assertEquals(3, parsePoll(edited.content)!!.answers.size)
            assertNotNull(withTimeout(60_000) { pa.state(d.roomId, p2.eventId!!).first { it.poll?.answers?.size == 3 && it.canEdit } })
        } finally { d.close() }
    }
}
