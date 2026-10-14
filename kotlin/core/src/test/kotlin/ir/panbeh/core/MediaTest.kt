package ir.panbeh.core

import ir.panbeh.core.media.*
import ir.panbeh.core.packs.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MediaTest {
    @Test fun `voice helpers`() {
        assertEquals(listOf(512, 1024), downsample(listOf(1.0, 2.0, 3.0, 4.0), 2))
        assertEquals(listOf(0, 0, 1024, 1024), downsample(listOf(0.0, 5.0), 4))
        assertEquals(emptyList(), downsample(emptyList()))
    }

    @Test fun parseGeoUri() {
        assertEquals(Geo(35.6892, 51.389, 20.0), parseGeoUri("geo:35.6892,51.389;u=20"))
        assertEquals(Geo(-1.5, 2.0, 3.5), parseGeoUri("geo:-1.5,2,100;crs=wgs84;u=3.5"))
        assertEquals(Geo(1.0, 2.0, null), parseGeoUri("geo:1,2"))
        assertNull(parseGeoUri("https://x"))
        assertNull(parseGeoUri(null))
        assertEquals("https://www.openstreetmap.org/?mlat=35.5&mlon=51#map=16/35.5/51", osmUrl(35.5, 51.0))
    }

    @Test fun `gifs - mau flag or gif image, saving moves to the front without duplicates`() {
        assertTrue(isGif("m.video", buildJsonObject { put("fi.mau.gif", true) }))
        assertTrue(isGif("m.image", buildJsonObject { put("mimetype", "image/gif") }))
        assertFalse(isGif("m.video", buildJsonObject { put("mimetype", "video/mp4") }))
        val a = Gif("m.video", "a", url = "mxc://hs/a")
        val b = Gif("m.video", "b", file = GifFile("mxc://hs/b"))
        val list = withGif(withGif(withGif(emptyList(), a), b), a)
        assertEquals(listOf("a", "b"), list.map { it.body })
        assertTrue(hasGif(list, b) && !hasGif(withoutGif(list, b), b))
        assertEquals(100, List(120) { a.copy(url = "mxc://hs/$it") }.fold(emptyList<Gif>()) { l, g -> withGif(l, g) }.size)
    }

    @Test fun `fitSize downscales the long side and never upscales`() {
        assertEquals(Dim(1280, 960), fitSize(4000, 3000))
        assertEquals(Dim(960, 1280), fitSize(3000, 4000))
        assertEquals(Dim(800, 600), fitSize(800, 600))
    }

    @Test fun `auto-download rules - chat kind, size cap, no limit, merging stored rules`() {
        val r = mergeAutoDl(buildJsonObject { putJsonObject("mobile") { putJsonObject("files") { put("group", false) } } })
        assertTrue(shouldAutoDl(r.mobile, DlKind.FILES, true, 500_000))
        assertFalse(shouldAutoDl(r.mobile, DlKind.FILES, true, 2_000_000)) // over 1 MB
        assertFalse(shouldAutoDl(r.mobile, DlKind.FILES, false, 10)) // groups turned off
        assertEquals(1, r.mobile.files.maxMB) // the rest of the stored kind keeps its default
        assertTrue(shouldAutoDl(r.mobile, DlKind.VIDEOS, true)) // size unknown
        assertTrue(shouldAutoDl(r.wifi.copy(videos = DlWho(dm = true, group = true, maxMB = 0)), DlKind.VIDEOS, false, 5_000_000_000))
        assertEquals(AUTO_DL_DEFAULT, mergeAutoDl(null))
    }

    /** Pixels are ARGB ints, so the reference hash decodes to 4 * 3 = 12 pixels (the TS RGBA array had 48 bytes). */
    @Test fun `blurhash - average colour and left-right survive a round trip, bad hashes are refused`() {
        val w = 32
        val h = 24
        val red = (255 shl 24) or (220 shl 16) or (40 shl 8) or 40
        val blue = (255 shl 24) or (40 shl 16) or (40 shl 8) or 220
        val px = IntArray(w * h) { if (it % w < w / 2) red else blue }
        val hash = blurhashEncode(px, w, h)
        assertEquals(4 + 2 * 4 * 3, hash.length)
        val out = blurhashDecode(hash, w, h)!!
        fun r(x: Int, y: Int) = (out[x + y * w] shr 16) and 255
        fun b(x: Int, y: Int) = out[x + y * w] and 255
        assertTrue(r(2, 12) > b(2, 12) + 80 && b(29, 12) > r(29, 12) + 80) // red left, blue right
        assertEquals(12, blurhashDecode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 4, 3)?.size) // the reference hash
        assertNull(blurhashDecode("LEHV6nWB2yk8", 4, 3))
        assertNull(blurhashDecode("LEHV6nWB2yk8pyo0adR*.7kCMdn\"", 4, 3))
    }

    @Test fun `image packs - usage falls back image to pack to both, shortcodes from file names, de-duplicated`() {
        val images = linkedMapOf(
            "a" to PackImage("mxc://s/a"), "b" to PackImage("mxc://s/b", usage = listOf(Usage.EMOTICON)),
            "c" to PackImage("https://x/c"), "d" to PackImage("mxc://s/d", usage = emptyList()),
        )
        val p = PackContent(PackMeta(usage = listOf(Usage.STICKER)), images)
        assertEquals(listOf("a", "d"), packImages(p, Usage.STICKER).map { it.first })
        assertEquals(listOf("b"), packImages(p, Usage.EMOTICON).map { it.first })
        assertEquals(listOf("a", "b", "d"), packImages(PackContent(images = images), Usage.EMOTICON).map { it.first })
        assertEquals("happy_cat_1", codeFrom("happy cat:1.png"))
        assertEquals("emoji", codeFrom("  <:)> .png"))
        assertEquals("خنده‌ی_بلند", codeFrom("خنده‌ی بلند!.gif"))
        assertEquals("hahab_x-۲", cleanCode("ha ha:<b>😂_x-۲"))
        assertEquals(32, cleanCode("x".repeat(40)).length)
        assertEquals(Dim(300, 150), padToRatio(300, 100))
        assertEquals(Dim(125, 250), padToRatio(100, 250))
        assertEquals(Dim(200, 100), padToRatio(200, 100))
        assertEquals("cat_3", freeCode("cat", mapOf("cat" to 1, "cat_2" to 1)))
        assertEquals("constructor", freeCode("constructor", emptyMap<String, Any>()))
    }
}
