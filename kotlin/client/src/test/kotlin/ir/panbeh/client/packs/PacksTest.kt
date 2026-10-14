package ir.panbeh.client.packs

import ir.panbeh.client.MemoryStore
import ir.panbeh.client.crypto.newUser
import ir.panbeh.client.testServer
import ir.panbeh.core.packs.PACK_CAP
import ir.panbeh.core.packs.PackImage
import ir.panbeh.core.packs.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun <T> Flow<T>.until(ms: Long = 30_000, p: (T) -> Boolean): T = withTimeout(ms) { first(p) }

class PacksTest {
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)

    @Test
    fun `personal pack add, rename, delete`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, c) = newUser(hs)
        val packs = Packs(c)
        val me = PackRef()
        val img = packs.upload(png, "image/png", "smile.png", 64, 64)
        assertTrue(img.url.startsWith("mxc://"))
        assertEquals(64, (img.info!!["w"] as JsonPrimitive).content.toInt())
        assertEquals(AddResult(2, 0), packs.add(me, listOf("smile.png" to img, "smile.png" to img.copy(url = img.url + "2")), Usage.EMOTICON))
        val c1 = packs.personal.until { it.images?.size == 2 }
        assertEquals(setOf("smile", "smile_2"), c1.images!!.keys)
        assertEquals(listOf(Usage.EMOTICON), c1.images!!.getValue("smile").usage)

        packs.rename(me, "smile", "هورا")
        packs.personal.until { it.images?.containsKey("هورا") == true }
        assertFailsWith<IllegalArgumentException> { packs.rename(me, "هورا", "smile_2") }

        // as a sticker too: it keeps the emoji side when deleted as a sticker
        packs.add(me, listOf("both.png" to img.copy(url = img.url + "3")), null)
        packs.delete(me, "هورا", Usage.EMOTICON)
        assertEquals(setOf("smile_2", "both"), packs.personal.until { it.images?.containsKey("هورا") == false }.images!!.keys)
        packs.delete(me, "both", Usage.STICKER) // no usage of its own: stays as emoji only
        val after = packs.personal.until { it.images?.get("both")?.usage == listOf(Usage.EMOTICON) }
        assertEquals(setOf("smile_2", "both"), after.images!!.keys)
        packs.delete(me, "both")
        assertEquals(setOf("smile_2"), packs.personal.until { it.images?.containsKey("both") == false }.images!!.keys)

        val emotes = packs.emotesFor(null, Usage.EMOTICON).until { it.isNotEmpty() }
        assertEquals(listOf("smile_2"), emotes.map { it.code })
        assertEquals("بسته‌ی من", emotes[0].pack.name)
        assertTrue(packs.emotesFor(null, Usage.STICKER).first().isEmpty())
    }

    @Test
    fun `pack cap skips the overflow`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, c) = newUser(hs)
        val packs = Packs(c)
        val cap = PACK_CAP.getValue(Usage.STICKER)
        val many = (1..cap + 3).map { "s$it.png" to PackImage("mxc://localhost/s$it") }
        assertEquals(AddResult(cap, 3), packs.add(PackRef(), many, Usage.STICKER))
        assertEquals(AddResult(0, 1), packs.add(PackRef(), listOf("x.png" to PackImage("mxc://localhost/x")), Usage.STICKER))
        assertEquals(cap, packs.personal.until { (it.images?.size ?: 0) == cap }.images!!.size)
    }

    @Test
    fun `room pack, copy to mine and global switch`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, ca) = newUser(hs, "a")
        val (b, _, cb) = newUser(hs, "b")
        val room = ca.api.room.createRoom(name = "Emoji room", invite = setOf(de.connect2x.trixnity.core.model.UserId("@$b:localhost"))).getOrThrow()
        cb.api.room.joinRoom(room).getOrThrow()
        val pa = Packs(ca)
        assertTrue(pa.canEdit(room.full).until { it })
        val ref = pa.createRoomPack(room.full, Usage.STICKER, null)
        assertEquals("stickers", ref.stateKey)
        val img = pa.upload(png, "image/png", "wave.png", 256, 256)
        pa.add(ref, listOf("wave.png" to img))
        val shown = pa.roomPacks(room.full).until { l -> l.any { it.content.images?.size == 1 } }.single()
        assertEquals("Emoji room", shown.name)
        assertEquals(listOf(Usage.STICKER), shown.content.pack!!.usage)
        pa.renamePack(ref, "Waves")
        assertEquals("Waves", pa.roomPacks(room.full).until { l -> l.firstOrNull()?.name == "Waves" }.single().name)
        val second = pa.createRoomPack(room.full, Usage.STICKER, "Other")
        assertEquals("stickers_2", second.stateKey)

        // b can't edit but can take the sticker into their own pack, once
        val pb = Packs(cb)
        assertFalse(pb.canEdit(room.full).until { true })
        val seen = pb.emotesFor(room.full, Usage.STICKER).until { it.isNotEmpty() }.single()
        assertEquals("wave", seen.code)
        assertEquals("Waves", seen.pack.name)
        pb.copyToMine(seen.code, seen.img, Usage.STICKER)
        pb.copyToMine(seen.code, seen.img, Usage.STICKER)
        assertEquals(setOf("wave"), pb.personal.until { it.images != null }.images!!.keys)
        assertTrue(pb.inMyPack(img.url, Usage.STICKER))
        assertFalse(pb.inMyPack(img.url, Usage.EMOTICON))
        pb.copyToMine("wave", PackImage(img.url + "x"), Usage.STICKER) // a different image with the same name
        assertEquals(setOf("wave", "wave_2"), pb.personal.until { it.images?.size == 2 }.images!!.keys)

        // switched on everywhere: usable outside the room, and gone again when switched off
        assertTrue(pb.emotesFor(null, Usage.STICKER).first().none { it.code == "wave" && it.pack.roomId == room.full })
        pb.setGlobal(ref, true)
        assertTrue(pb.isGlobal(ref).until { it })
        pb.emotesFor(null, Usage.STICKER).until { l -> l.any { it.pack.roomId == room.full } }
        pb.setGlobal(ref, false)
        pb.emotesFor(null, Usage.STICKER).until { l -> l.none { it.pack.roomId == room.full } }

        pa.deleteRoomPack(ref)
        pa.deleteRoomPack(second)
        assertTrue(pa.roomPacks(room.full).until { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `saved gifs are newest first, deduplicated and capped`() = runBlocking {
        val hs = testServer ?: return@runBlocking
        val (_, _, c) = newUser(hs)
        val gifs = Gifs(c)
        fun gif(n: Int) = buildJsonObject { put("msgtype", "m.video"); put("body", "caption $n"); put("url", "mxc://localhost/g$n"); put("info", buildJsonObject { put("fi.mau.gif", true) }) }
        gifs.save(gif(1))
        gifs.save(gif(2))
        gifs.save(gif(1).let { JsonObject(it + ("body" to JsonPrimitive("other caption"))) }) // same url: moves up
        val l = gifs.saved.until { it.size == 2 && gifKey(it[0]) == "mxc://localhost/g1" }
        assertEquals(listOf("mxc://localhost/g1", "mxc://localhost/g2"), l.map(::gifKey))
        assertEquals("gif", (l[0]["body"] as JsonPrimitive).content)
        assertTrue(gifs.isSaved(gif(2)))
        assertFalse(gifs.toggle(gif(2)))
        assertEquals(listOf("mxc://localhost/g1"), gifs.saved.until { it.size == 1 }.map(::gifKey))
        assertTrue(gifs.toggle(gif(2)))
        // encrypted ones are keyed by their file url
        val enc = buildJsonObject { put("msgtype", "m.video"); put("file", buildJsonObject { put("url", "mxc://localhost/enc"); put("v", "v2") }) }
        gifs.save(enc)
        assertEquals("mxc://localhost/enc", gifKey(gifs.saved.until { it.size == 3 }.first()))

        for (n in 10..120) gifs.save(gif(n))
        val full = gifs.saved.until { it.size == 100 && gifKey(it[0]) == "mxc://localhost/g120" }
        assertEquals(100, full.size)
        assertEquals("mxc://localhost/g21", gifKey(full.last()))
    }

    @Test
    fun `recent emoji keep 24 newest first`() {
        val store = MemoryStore()
        val r = RecentEmoji(store)
        assertEquals(emptyList(), r.list())
        r.add("😀"); r.add("👍"); r.add("😀")
        assertEquals(listOf("😀", "👍"), r.list())
        for (i in 1..30) r.add("mxc://x/$i")
        assertEquals(24, r.list().size)
        assertEquals("mxc://x/30", r.list()[0])
        assertEquals(24, RecentEmoji(store).list().size)
        store["panbeh.recentEmoji"] = "not json"
        assertEquals(emptyList(), r.list())
    }

    @Test
    fun `pack json keeps unknown fields`() {
        val raw = buildJsonObject {
            put("pack", buildJsonObject { put("display_name", "X"); put("extra", 1) })
            put("images", buildJsonObject { put("a", buildJsonObject { put("url", "mxc://h/a"); put("usage", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("sticker")))) }); put("bad", buildJsonObject { put("body", "no url") }) })
            put("future", true)
        }
        val c = parsePack(raw)
        assertEquals(setOf("a"), c.images!!.keys)
        assertEquals(listOf(Usage.STICKER), c.images!!.getValue("a").usage)
        assertEquals("X", c.pack!!.displayName)
        assertEquals(JsonPrimitive(true), raw.withImages(emptyMap())["future"])
        assertEquals(JsonPrimitive(1), ((raw.withPack(mapOf("display_name" to JsonPrimitive("Y")))["pack"]) as JsonObject)["extra"])
    }
}
