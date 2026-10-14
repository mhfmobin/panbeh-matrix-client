package ir.panbeh.client.timeline

import ir.panbeh.client.testServer
import ir.panbeh.core.text.Mention
import ir.panbeh.core.util.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun `text with formatting and mentions, reply, edit, reactions, redact`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create()
        try {
            val tb = d.timelineB().also { it.open() }
            val ta = d.timelineA().also { it.open() }
            val bLocal = d.b.userId.localpart

            // formatting + a typed mention
            d.sa.sendText(d.roomId, "hello @$bLocal **bold** and ~~gone~~")
            val got = tb.item("text") { it.content.string("body")?.startsWith("hello") == true }
            val html = got.content.string("formatted_body")!!
            assertTrue("<strong>bold</strong>" in html && "<del>gone</del>" in html, html)
            assertTrue("https://matrix.to/#/${d.b.userId.full}" in html, html)
            assertEquals(listOf(d.b.userId.full), got.content["m.mentions"]!!.jsonObject["user_ids"]!!.jsonArray.map { (it as JsonPrimitive).content })
            assertEquals(d.a.userId.full, got.sender)
            assertFalse(got.isMine)
            assertEquals(Decryption.OK, got.decryption)

            // reply (mentions the replied-to sender) from b
            val reply = d.sb.sendText(d.roomId, "re: you", TextOptions(replyTo = got.replyTarget()))
            val r = ta.item("reply") { it.content.string("body") == "re: you" }
            assertEquals(got.eventId, r.replyTo)
            assertTrue(d.a.userId.full in r.content["m.mentions"]!!.jsonObject["user_ids"]!!.jsonArray.map { (it as JsonPrimitive).content })
            assertTrue(reply.isNotEmpty())

            // edit by the author: the edited content shows with the edited flag, on both sides
            val mine = ta.item("own") { it.eventId == got.eventId }
            assertTrue(mine.isMine)
            d.sa.edit(d.roomId, mine, "hello edited")
            val edited = tb.item("edit") { it.eventId == got.eventId && it.content.string("body") == "hello edited" }
            assertTrue(edited.edited)

            // reactions aggregate, mine can be taken back; a custom emoji keeps its shortcode
            d.sb.toggleReaction(d.roomId, edited, "👍")
            d.sa.toggleReaction(d.roomId, mine, "👍")
            d.sb.toggleReaction(d.roomId, edited, "mxc://localhost/cat", ":cat:")
            val agg = tb.item("reactions") { it.eventId == got.eventId && it.reactions.firstOrNull { g -> g.key == "👍" }?.count == 2 && it.reactions.size == 2 }
            val thumbs = agg.reactions.first { it.key == "👍" }
            assertTrue(thumbs.mine)
            assertEquals(setOf(d.a.userId.full, d.b.userId.full), thumbs.senders.toSet())
            assertEquals(":cat:", agg.reactions.first { it.key == "mxc://localhost/cat" }.shortcode)
            assertFalse(d.sb.toggleReaction(d.roomId, agg, "👍"))
            tb.item("unreact") { it.eventId == got.eventId && it.reactions.firstOrNull { g -> g.key == "👍" }?.count == 1 }

            // delete
            d.sa.redact(d.roomId, mine)
            eventually("redacted") { if (tb.state.value.items.none { it.eventId == got.eventId }) true else null }
        } finally { d.close() }
    }
}
