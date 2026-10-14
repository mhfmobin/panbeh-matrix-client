package ir.panbeh.client.timeline

import de.connect2x.trixnity.core.model.RoomId
import ir.panbeh.client.events.rawMessage
import ir.panbeh.client.testServer
import ir.panbeh.core.util.string
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PinsAndSearchTest {
    @Test
    fun `pin and unpin, who may, pin notice and flag`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "pn")
        try {
            val ta = d.timelineA().also { it.open() }
            val tb = d.timelineB().also { it.open() }
            d.sb.sendText(d.roomId, "pin me")
            val msg = ta.item("msg") { it.content.string("body") == "pin me" }
            val pa = Pins(d.a, d.roomId)
            val pb = Pins(d.b, d.roomId)
            assertTrue(pa.canPin.first())
            assertFalse(pb.canPin.first()) // a plain member can't in a private chat
            assertTrue(pa.toggle(msg.eventId!!))
            assertEquals(listOf(msg.eventId), eventually("pinned") { pb.ids.first().takeIf { it.isNotEmpty() } })
            val pinned = tb.item("flag") { it.eventId == msg.eventId && it.pinned }
            assertTrue(pinned.pinned)
            val notice = tb.item("notice") { it.kind == ItemKind.NOTICE && it.notice?.endsWith("پیامی را سنجاق کرد") == true }
            assertNotNull(notice)
            assertEquals("pin me", pb.load(msg.eventId!!)!!.content.string("body"))
            assertFalse(pa.toggle(msg.eventId!!))
            eventually("unpinned") { if (pb.ids.first().isEmpty()) true else null }
            tb.item("unpin notice") { it.kind == ItemKind.NOTICE && it.notice?.endsWith("سنجاق پیامی را برداشت") == true }
        } finally { d.close() }
    }

    @Test
    fun `link preview is null when the server has no previews`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "lp")
        try {
            val content = buildJsonObject { put("msgtype", "m.text"); put("body", "see https://example.com/page") }
            val lp = LinkPreviews(d.b)
            assertEquals("https://example.com/page", firstPreviewUrl(content))
            assertEquals(null, lp.of(content, System.currentTimeMillis())) // Synapse has url previews off: no card, no exception
            assertEquals(null, lp.of(buildJsonObject { put("msgtype", "m.text"); put("body", "no link") }, 0))
        } finally { d.close() }
    }

    @Test
    fun `search with the server, the local index and backfill`() = runBlocking {
        testServer ?: return@runBlocking
        // unencrypted: the server finds it
        val plain = Duo.create(encrypted = false, prefix = "sp")
        try {
            val tb = plain.timelineB().also { it.open() }
            plain.a.api.room.sendMessageEvent(RoomId(plain.roomId), rawMessage(buildJsonObject { put("msgtype", "m.text"); put("body", "the quick brown zebra") })).getOrThrow()
            tb.item("zebra") { it.content.string("body")?.contains("zebra") == true }
            val search = MessageSearch(plain.b)
            val hit = eventually("server search") { search.inRoom(plain.roomId, "zebra", tb.state.value.items).items.firstOrNull() }
            assertEquals("the quick brown zebra", hit.content.string("body"))
            assertEquals(plain.a.userId.full, hit.sender)
            assertEquals(0, search.inRoom(plain.roomId, "z", tb.state.value.items).items.size) // too short
            assertEquals("the quick brown zebra", eventually("global") { search.everywhere("zebra").items.firstOrNull() }.content.string("body"))
        } finally { plain.close() }

        // encrypted: the index fills from live decrypted messages, edits and redactions apply, backfill pages history
        val d = Duo.create(prefix = "se")
        try {
            val index = InMemorySearchIndex()
            d.sa.sendText(d.roomId, "old needle one")   // before the indexer starts: found by backfill
            d.sa.sendText(d.roomId, "old haystack")
            val tb = d.timelineB().also { it.open() }
            tb.item("old") { it.content.string("body") == "old haystack" }
            val indexer = SearchIndexer(d.b, index, d.scope).also { it.start() }
            d.sa.sendText(d.roomId, "live needle two")
            val live = tb.item("live") { it.content.string("body") == "live needle two" }
            eventually("indexed live") { if (index.search("needle", d.roomId, 10).size == 1) true else null }
            val search = MessageSearch(d.b, index)
            val res = search.inRoom(d.roomId, "NEEDLE", tb.state.value.items)
            assertTrue(res.items.any { it.content.string("body") == "live needle two" })
            assertFalse(res.serverFailed)
            // an edit by the author changes the indexed text; a redaction removes it
            val mine = d.timelineA().also { it.open() }.item("own") { it.content.string("body") == "live needle two" }
            d.sa.edit(d.roomId, mine, "live pin two")
            eventually("edit indexed") { if (index.search("pin", d.roomId, 10).size == 1 && index.search("needle", d.roomId, 10).isEmpty()) true else null }
            d.sa.redact(d.roomId, mine)
            eventually("redaction indexed") { if (index.search("pin", d.roomId, 10).isEmpty()) true else null }
            // backfill finds the older ones and reports when history is exhausted
            assertFalse(indexer.backfillDone(d.roomId))
            val more = indexer.backfill(d.roomId, pages = 5)
            assertFalse(more)
            assertTrue(indexer.backfillDone(d.roomId))
            val old = search.inRoom(d.roomId, "needle", emptyList()).items
            assertEquals(listOf("old needle one"), old.map { it.content.string("body") })
            // loaded-only search for encrypted rooms without an index
            val noIndex = MessageSearch(d.b)
            assertEquals(1, noIndex.inRoom(d.roomId, "haystack", tb.state.value.items).items.size)
            assertEquals(0, noIndex.inRoom(d.roomId, "needle", emptyList()).items.size)
            assertEquals(live.roomId, d.roomId)
        } finally { d.close() }
    }
}
