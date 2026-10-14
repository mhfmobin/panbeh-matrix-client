package ir.panbeh.client.timeline

import ir.panbeh.core.text.Mention
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun JsonObject.ids(of: String = "m.mentions") = ((this[of] as JsonObject)["user_ids"] as JsonArray).map { (it as JsonPrimitive).content }

class PureTest {
    @Test
    fun `text content with html and mentions`() {
        val members = listOf(Compose.Candidate("Ali", "@ali:hs"), Compose.Candidate("Reza", "@reza:hs"))
        val c = Compose.text("hi @Ali and **x**", members = members, me = "@me:hs", replySender = "@reza:hs")
        assertEquals("m.text", c.string("msgtype"))
        assertTrue("<a href=\"https://matrix.to/#/@ali:hs\">Ali</a>" in c.string("formatted_body")!!)
        assertEquals(listOf("@ali:hs", "@reza:hs"), c.ids())
        // plain text: no html; replying to myself pings nobody
        val p = Compose.text("plain", me = "@me:hs", replySender = "@me:hs")
        assertNull(p["formatted_body"])
        assertEquals(emptyList(), p.ids())
        // @room only counts when allowed
        assertNull(Compose.text("@room hi", canPingRoom = false)["m.mentions"]!!.jsonObject["room"])
        assertEquals(JsonPrimitive(true), Compose.text("@room hi", canPingRoom = true)["m.mentions"]!!.jsonObject["room"])
        // picked mention + custom emoji
        val e = Compose.text("@Zed :cat:", picked = listOf(Mention("Zed", "@zed:hs")), emotes = mapOf("cat" to "mxc://hs/cat"))
        assertTrue("data-mx-emoticon" in e.string("formatted_body")!!)
        assertEquals(listOf("@zed:hs"), e.ids())
        assertEquals("m.emote", Compose.text("waves", "m.emote").string("msgtype"))
    }

    @Test
    fun `relations for replies and threads`() {
        assertNull(Compose.relation(null, null, null))
        assertEquals("\$a", Compose.relation("\$a", null, null)!!["m.in_reply_to"]!!.jsonObject.string("event_id"))
        val t = Compose.relation(null, "\$root", "\$last")!!
        assertEquals("m.thread", t.string("rel_type"))
        assertEquals("\$last", t["m.in_reply_to"]!!.jsonObject.string("event_id"))
        assertEquals(JsonPrimitive(true), t["is_falling_back"])
        val r = Compose.relation("\$reply", "\$root", "\$last")!!
        assertEquals("\$reply", r["m.in_reply_to"]!!.jsonObject.string("event_id"))
        assertEquals(JsonPrimitive(false), r["is_falling_back"])
    }

    @Test
    fun `edit content`() {
        val old = buildJsonObject {
            put("msgtype", "m.text"); put("body", "hello @Ali")
            putJsonObject("m.mentions") { put("user_ids", JsonArray(listOf(JsonPrimitive("@ali:hs")))) }
        }
        assertNull(Compose.edit("\$o", old, Compose.text("hello @Ali"), "hello @Ali"))
        val next = Compose.text("hello @Ali @Reza", picked = listOf(Mention("Ali", "@ali:hs"), Mention("Reza", "@reza:hs")))
        val e = Compose.edit("\$o", old, next, "hello @Ali @Reza")!!
        assertEquals("* hello @Ali @Reza", e.string("body"))
        assertEquals("hello @Ali @Reza", e["m.new_content"]!!.jsonObject.string("body"))
        assertEquals(listOf("@reza:hs"), e.ids())
        assertEquals(listOf("@ali:hs", "@reza:hs"), e["m.new_content"]!!.jsonObject.ids())
        assertEquals("m.replace", e["m.relates_to"]!!.jsonObject.string("rel_type"))
        assertEquals("\$o", e["m.relates_to"]!!.jsonObject.string("event_id"))
    }

    @Test
    fun `media caption edit keeps the file`() {
        val old = buildJsonObject {
            put("msgtype", "m.image"); put("body", "cap"); put("filename", "a.jpg"); put("url", "mxc://hs/x")
            putJsonObject("info") { put("w", 3) }
            put("format", "org.matrix.custom.html"); put("formatted_body", "<b>cap</b>")
        }
        val e = Compose.edit("\$o", old, Compose.text("new cap", "m.image"), "new cap")!!
        val nc = e["m.new_content"]!!.jsonObject
        assertEquals("m.image", nc.string("msgtype"))
        assertEquals("mxc://hs/x", nc.string("url"))
        assertEquals("a.jpg", nc.string("filename"))
        assertEquals("new cap", nc.string("body"))
        assertEquals("mxc://hs/x", e.string("url"))
        // removing the caption: the file name is the body again
        val rm = Compose.edit("\$o", old, Compose.text("", "m.image"), "")!!
        assertEquals("a.jpg", rm["m.new_content"]!!.jsonObject.string("body"))
        assertEquals("* a.jpg", rm.string("body"))
    }

    @Test
    fun `forward strips relations and fallback, keeps the first author`() {
        val c = buildJsonObject {
            put("msgtype", "m.text"); put("body", "> <@a:hs> quoted\n\nreal text")
            put("formatted_body", "<mx-reply><blockquote>q</blockquote></mx-reply>real")
            putJsonObject("m.relates_to") { putJsonObject("m.in_reply_to") { put("event_id", "\$q") } }
            putJsonObject("m.mentions") { put("user_ids", JsonArray(listOf(JsonPrimitive("@a:hs")))) }
        }
        val f = Compose.forward(c, "@b:hs", "Bob")
        assertEquals("real text", f.string("body"))
        assertEquals("real", f.string("formatted_body"))
        assertNull(f["m.relates_to"])
        assertEquals(0, f["m.mentions"]!!.jsonObject.size)
        assertEquals("@b:hs", f["app.panbeh.forwarded"]!!.jsonObject.string("sender"))
        // forwarding a forward keeps the original
        val again = Compose.forward(f, "@c:hs", "Carol")
        assertEquals("@b:hs", again["app.panbeh.forwarded"]!!.jsonObject.string("sender"))
    }

    @Test
    fun `location content like makeLocationContent`() {
        val l = Compose.location(35.6892123456, 51.389, 12, 1000)
        assertEquals("m.location", l.string("msgtype"))
        assertEquals("geo:35.689212,51.389;u=12", l.string("geo_uri"))
        assertTrue(l.string("body")!!.startsWith("موقعیت مکانی https://www.openstreetmap.org/"))
        assertEquals("m.self", l["org.matrix.msc3488.asset"]!!.jsonObject.string("type"))
        assertEquals("geo:35.689212,51.389;u=12", l["org.matrix.msc3488.location"]!!.jsonObject.string("uri"))
    }

    @Test
    fun `reaction content with a custom emoji shortcode`() {
        val r = Compose.reaction("\$e", "mxc://hs/cat", ":cat:")
        assertEquals("m.annotation", r["m.relates_to"]!!.jsonObject.string("rel_type"))
        assertEquals(":cat:", r.string("com.beeper.reaction.shortcode"))
        assertNull(Compose.reaction("\$e", "👍")["com.beeper.reaction.shortcode"])
    }

    @Test
    fun `poll content parses back and follows the unstable shape`() {
        val c = pollStartContent("Q?", listOf("a", "b", "c"), multi = true, undisclosed = true)
        val p = parsePoll(c)!!
        assertEquals("Q?", p.question)
        assertEquals(listOf("a", "b", "c"), p.answers.map { it.text })
        assertEquals(3, p.maxSelections)
        assertTrue(p.undisclosed)
        assertEquals("Q?\n1. a\n2. b\n3. c", c.string("org.matrix.msc1767.text"))
        assertEquals(1, parsePoll(pollStartContent("Q", listOf("a", "b"), false, false))!!.maxSelections)
        assertTrue(runCatching { pollStartContent("Q", listOf("a"), false, false) }.isFailure)
        assertTrue(runCatching { pollStartContent("Q", List(21) { "x$it" }, false, false) }.isFailure)
        // the stable names are read too
        val stable = buildJsonObject {
            putJsonObject("m.poll.start") {
                putJsonObject("question") { put("m.text", "S?") }
                put("kind", "m.poll.disclosed"); put("max_selections", 2)
                put("answers", JsonArray(listOf(buildJsonObject { put("id", "1"); put("m.text", "x") }, buildJsonObject { put("id", "2"); put("m.text", "y") })))
            }
        }
        assertEquals(listOf("x", "y"), parsePoll(stable)!!.answers.map { it.text })
    }

    private fun file(name: String, mime: String) = FileToSend(name, MediaInfo(mime, 10)) { kotlinx.coroutines.flow.emptyFlow() }

    @Test
    fun `albums are planned like the composer does`() {
        var n = 0
        val files = List(12) { file("p$it.jpg", "image/jpeg") } + file("a.gif", "image/gif") + file("doc.pdf", "application/pdf")
        val plan = planFiles(files, "caption", asFile = false, asGif = false) { "id${n++}" }
        val albums = plan.filter { it.extra?.get("app.panbeh.album") != null }
        assertEquals(12, albums.size)
        val first = albums.first().extra!!["app.panbeh.album"]!!.jsonObject
        assertEquals("id0", first.string("id"))
        assertEquals(JsonPrimitive(10), first["n"])
        assertEquals(JsonPrimitive(2), albums.last().extra!!["app.panbeh.album"]!!.jsonObject["n"])
        // the reply is on the first item of the first album, the caption on its last
        assertEquals(listOf(true) + List(11) { false } + listOf(false, false), plan.map { it.replyTo }.take(12) + listOf(false, false))
        assertEquals("caption", albums[9].caption)
        assertEquals(1, plan.count { it.caption != null })
        // the rest go on their own without the reply or caption
        val rest = plan.drop(12)
        assertEquals(setOf("a.gif", "doc.pdf"), rest.map { it.file.name }.toSet())
        assertTrue(rest.none { it.replyTo || it.caption != null })
        // a single photo: reply and caption on it
        val one = planFiles(listOf(file("x.jpg", "image/jpeg")), "c", false, false)
        assertTrue(one[0].replyTo && one[0].caption == "c" && one[0].extra == null)
        // "send as GIF" flags a video
        val gif = planFiles(listOf(file("v.mp4", "video/mp4")), null, asFile = false, asGif = true)
        assertEquals(JsonPrimitive(true), gif[0].extra!!["info"]!!.jsonObject["fi.mau.gif"])
        // "as file": no albums
        assertTrue(planFiles(List(3) { file("p$it.jpg", "image/jpeg") }, null, asFile = true, asGif = false).all { it.extra == null && it.asFile })
    }

    @Test
    fun `media content`() {
        val img = mediaContent("a.jpg", "hello", MediaInfo("image/jpeg", 99, 640, 480, blurhash = "LEHV6n"), false, MediaExtras.album("id", 2, 0), Uploaded.Plain("mxc://hs/a"), null)
        assertEquals("m.image", img.string("msgtype"))
        assertEquals("hello", img.string("body"))
        assertEquals("a.jpg", img.string("filename"))
        assertEquals("mxc://hs/a", img.string("url"))
        val info = img["info"]!!.jsonObject
        assertEquals(JsonPrimitive(640), info["w"])
        assertEquals("LEHV6n", info.string(BLURHASH))
        assertEquals("id", img["app.panbeh.album"]!!.jsonObject.string("id"))
        // no caption: body is the name, no filename
        val nocap = mediaContent("a.jpg", null, MediaInfo("image/jpeg", 1), false, null, Uploaded.Plain("mxc://hs/a"), null)
        assertEquals("a.jpg", nocap.string("body"))
        assertNull(nocap["filename"])
        // encrypted video with an encrypted poster
        val file = buildJsonObject { put("url", "mxc://hs/v") }
        val poster = buildJsonObject { put("url", "mxc://hs/t") }
        val v = mediaContent("v.mp4", null, MediaInfo("video/mp4", 5, 320, 240, 4000, "H", Thumbnail(ByteArray(7), 320, 240)), false, MediaExtras.gif(), Uploaded.Encrypted(file), Uploaded.Encrypted(poster))
        assertEquals("m.video", v.string("msgtype"))
        assertNotNull(v["file"])
        assertNull(v["url"])
        val vi = v["info"]!!.jsonObject
        assertEquals(JsonPrimitive(4000), vi["duration"])
        assertNotNull(vi["thumbnail_file"])
        assertEquals(JsonPrimitive(7), vi["thumbnail_info"]!!.jsonObject["size"])
        assertEquals(JsonPrimitive(true), vi["fi.mau.gif"])
        // voice
        val voice = mediaContent("voice.ogg", null, MediaInfo("audio/ogg", 3), false, MediaExtras.voice(2500, listOf(1, 2, 3)), Uploaded.Plain("mxc://hs/v"), null)
        assertEquals("m.audio", voice.string("msgtype"))
        assertEquals("پیام صوتی", voice.string("body"))
        assertEquals(JsonPrimitive(2500), voice["info"]!!.jsonObject["duration"])
        assertEquals(3, voice["org.matrix.msc1767.audio"]!!.jsonObject["waveform"]!!.jsonArray.size)
        assertNotNull(voice["org.matrix.msc3245.voice"])
        // video note and plain file
        val note = mediaContent("n.mp4", null, MediaInfo("video/mp4", 3, 480, 480, 5000), false, MediaExtras.videoNote(5000, 480), Uploaded.Plain("mxc://hs/n"), null)
        assertEquals(JsonPrimitive(true), note["app.panbeh.video_note"])
        assertEquals("پیام ویدیویی", note.string("body"))
        assertEquals("m.file", mediaContent("x.pdf", null, MediaInfo("application/pdf", 3), false, null, Uploaded.Plain("mxc://hs/n"), null).string("msgtype"))
        assertEquals("m.file", mediaContent("x.jpg", null, MediaInfo("image/jpeg", 3), true, null, Uploaded.Plain("mxc://hs/n"), null).string("msgtype"))
    }

    @Test
    fun `notice texts in Persian`() {
        fun n(type: String, c: JsonObject, prev: JsonObject = JsonObject(emptyMap()), key: String? = null, sender: String = "@a:hs") = noticeText(type, c, prev, key, sender, bdi("Ali"), { if (it == "@b:hs") "Bob" else null })
        assertEquals("⁨Ali⁩ گفتگو را ایجاد کرد", n("m.room.create", JsonObject(emptyMap())))
        assertEquals("⁨Ali⁩ نام گفتگو را به «⁨Room⁩» تغییر داد", n("m.room.name", buildJsonObject { put("name", "Room") }))
        assertEquals("پیام‌ها رمزنگاری سرتاسری شده‌اند", n("m.room.encryption", JsonObject(emptyMap())))
        val joined = buildJsonObject { put("membership", "join"); put("displayname", "Bob") }
        assertEquals("⁨Bob⁩ پیوست", n("m.room.member", joined, JsonObject(emptyMap()), "@b:hs", "@b:hs"))
        assertNull(n("m.room.member", joined, joined, "@b:hs", "@b:hs")) // profile-only change
        assertEquals("⁨Ali⁩ ⁨Bob⁩ را دعوت کرد", n("m.room.member", buildJsonObject { put("membership", "invite"); put("displayname", "Bob") }, JsonObject(emptyMap()), "@b:hs"))
        val left = buildJsonObject { put("membership", "leave") }
        assertEquals("⁨Bob⁩ خارج شد", n("m.room.member", left, joined, "@b:hs", "@b:hs"))
        assertEquals("⁨Ali⁩ ⁨Bob⁩ را بیرون کرد (دلیل: ⁨spam⁩)", n("m.room.member", buildJsonObject { put("membership", "leave"); put("reason", "spam") }, joined, "@b:hs"))
        assertEquals("⁨Ali⁩ ⁨Bob⁩ را مسدود کرد", n("m.room.member", buildJsonObject { put("membership", "ban") }, joined, "@b:hs"))
        val pl = buildJsonObject { putJsonObject("users") { put("@b:hs", 100) } }
        val pl0 = buildJsonObject { putJsonObject("users") { put("@a:hs", 100) } }
        assertEquals("\u2068Ali\u2069 نقش \u2068@a:hs\u2069 را به «عضو» تغییر داد (و ۱ نفر دیگر)", n("m.room.power_levels", pl, pl0))
        assertEquals("\u2068Ali\u2069 نقش \u2068Bob\u2069 را به «مدیر» تغییر داد", n("m.room.power_levels", pl, JsonObject(mapOf("users" to JsonObject(emptyMap())))))
        assertNull(n("m.room.power_levels", pl0, JsonObject(emptyMap())))
        assertEquals("⁨Ali⁩ پیامی را سنجاق کرد", n("m.room.pinned_events", buildJsonObject { put("pinned", JsonArray(listOf(JsonPrimitive("\$x")))) }, buildJsonObject { put("pinned", JsonArray(emptyList())) }))
        assertEquals("⁨Ali⁩ این گروه را ارتقا داد", n("m.room.tombstone", JsonObject(emptyMap())))
        assertEquals("⁨Ali⁩ شیوه‌ی پیوستن را به «عمومی؛ هر کسی می‌تواند بپیوندد» تغییر داد", n("m.room.join_rules", buildJsonObject { put("join_rule", "public") }, buildJsonObject { put("join_rule", "invite") }))
        assertNull(n("m.room.topic_unknown", JsonObject(emptyMap())))
        assertEquals("⁨Ali⁩ تماس صوتی گروهی را شروع کرد", n("m.rtc.notification", buildJsonObject { put("notification_type", "notification") }))
    }

    @Test
    fun `previews`() {
        fun p(c: JsonObject, type: String = "m.room.message") = previewText(type, c, "Ali")
        assertEquals("🖼 عکس", p(buildJsonObject { put("msgtype", "m.image"); put("body", "a.jpg") }))
        assertEquals("🖼 caption", p(buildJsonObject { put("msgtype", "m.image"); put("body", "caption"); put("filename", "a.jpg") }))
        assertEquals("🎤 پیام صوتی ۰:۰۲", p(buildJsonObject { put("msgtype", "m.audio"); putJsonObject("org.matrix.msc3245.voice") {}; putJsonObject("info") { put("duration", 2000) } }))
        assertEquals("* Ali waves", p(buildJsonObject { put("msgtype", "m.emote"); put("body", "waves") }))
        assertEquals("استیکر", p(JsonObject(emptyMap()), "m.sticker"))
        assertEquals("quoted", p(buildJsonObject { put("msgtype", "m.text"); put("body", "> <@a:hs> x\n\nquoted") }))
        assertEquals("📊 Q?", p(pollStartContent("Q?", listOf("a", "b"), false, false), "org.matrix.msc3381.poll.start"))
        assertEquals("🔒 پیام رمزنگاری‌شده", previewText("m.room.encrypted", JsonObject(emptyMap()), "Ali", Decryption.FAILED))
        assertEquals("🔒 در حال رمزگشایی…", previewText("m.room.encrypted", JsonObject(emptyMap()), "Ali", Decryption.DECRYPTING))
    }

    @Test
    fun `pin bar cycling`() {
        val ids = listOf("a", "b", "c")
        assertEquals(2, Pins.current(ids, null))
        assertEquals(1, Pins.next(ids, 2))
        assertEquals(2, Pins.next(ids, 0))
        assertEquals(2, Pins.current(ids, 7))
        assertEquals("پیام سنجاق‌شده ۲ از ۳", Pins.label(ids, 1))
        assertEquals("پیام سنجاق‌شده", Pins.label(listOf("a"), 0))
    }

    @Test
    fun `link preview picks the first real link`() {
        fun u(body: String, type: String = "m.text") = firstPreviewUrl(buildJsonObject { put("msgtype", type); put("body", body) })
        assertEquals("https://example.com/a", u("see https://example.com/a and https://b.org"))
        assertEquals("https://example.com", u("go example.com now"))
        assertNull(u("[x](https://matrix.to/#/@a:hs)"))
        assertEquals("https://b.org", u("`https://a.org` https://matrix.to/#/!r:hs https://b.org"))
        assertNull(u("```\nhttps://code.example\n```"))
        assertNull(u("https://example.com", "m.image"))
        assertEquals("https://x.org", u("> <@a:hs> https://q.org\n\nhttps://x.org"))
    }
}
