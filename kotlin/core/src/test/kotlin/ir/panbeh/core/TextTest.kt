package ir.panbeh.core

import ir.panbeh.core.text.*
import ir.panbeh.core.media.*
import kotlinx.serialization.json.*
import kotlin.test.*

class TextTest {
    @Test fun normalizeServer() {
        assertEquals("https://chat.example.org:8448", normalizeServer(" chat.example.org:8448/ "))
        assertEquals("http://localhost:8008", normalizeServer("http://localhost:8008"))
    }

    @Test fun `user IDs and alias suggestions`() {
        assertTrue(isUserId("@b:localhost"))
        assertTrue(isUserId(" @alice:matrix.org:8448 "))
        assertFalse(isUserId("alice"))
        assertFalse(isUserId("@alice"))
        assertFalse(isUserId("#room:server"))
        assertEquals("my-cool-group", aliasLocalpart("My Cool Group!"))
        assertEquals("", aliasLocalpart("گروه دوستان"))
    }

    @Test fun formatMessage() {
        val ali = Mention("Ali", "@ali:hs")
        val aliR = Mention("Ali Reza", "@ar:hs")
        assertNull(formatMessage("hi\nthere", listOf(ali)))
        assertEquals(
            FormattedMessage("""<a href="https://matrix.to/#/@ar:hs">Ali Reza</a> &#38; <a href="https://matrix.to/#/@ali:hs">Ali</a><br>&#60;b&#62;""", listOf("@ar:hs", "@ali:hs")),
            formatMessage("@Ali Reza & @Ali\n<b>", listOf(ali, aliR)),
        )
    }

    @Test fun `formatMessage markdown`() {
        fun h(s: String, m: List<Mention> = emptyList()) = formatMessage(s, m)?.html
        assertEquals("<strong>b</strong> <em>i</em> <em>j</em> <del>s</del> <span data-mx-spoiler>x</span>", h("**b** *i* _j_ ~~s~~ ||x||"))
        assertNull(h("snake_case_names and 2*3*4"))
        assertEquals("""hi <a href="https://matrix.to/#/@ali_b:server">ali_b:server</a> <em>x</em>""", h("hi @ali_b:server _x_", listOf(Mention("ali_b:server", "@ali_b:server"))))
        assertEquals("""<strong>hey <a href="https://matrix.to/#/@ali:hs">Ali</a></strong>""", h("**hey @Ali**", listOf(Mention("Ali", "@ali:hs"))))
        assertEquals("<code>*a* _b_</code> <em>c</em>", h("`*a* _b_` *c*"))
        assertEquals("<strong>&#60;b&#62;</strong>", h("**<b>**"))
        assertEquals("<blockquote>a<br><em>b</em></blockquote>c", h("> a\n> *b*\nc"))
        assertEquals("x<pre><code>let _a_ = 1;\n*z*</code></pre>y", h("x\n```js\nlet _a_ = 1;\n*z*\n```\ny"))
        assertEquals("""<a href="https://x.io/a_b_c?q=1&#38;r=2">a_b</a>""", h("[a_b](https://x.io/a_b_c?q=1&r=2)"))
        assertNull(h("[a](javascript:alert(1))"))
        assertEquals("<pre><code>@Ali</code></pre>", h("```@Ali```", listOf(Mention("Ali", "@ali:hs"))))
    }

    @Test fun normalize() {
        assertEquals("hello کتاب ی میروم", normalize("Hello كتاب ي می‌روم"))
        assertEquals(normalize("ميروم"), normalize("می‌روم"))
    }

    @Test fun `shared media - classify content and pull links out of the text, not the reply quote`() {
        fun msg(vararg kv: Pair<String, String>) = mediaKind("m.room.message", JsonObject(kv.associate { it.first to JsonPrimitive(it.second) }))
        assertEquals(listOf(MediaKind.MEDIA, MediaKind.MEDIA, MediaKind.FILE, MediaKind.AUDIO),
            listOf(msg("msgtype" to "m.image"), msg("msgtype" to "m.video"), msg("msgtype" to "m.file"), msg("msgtype" to "m.audio")))
        assertNull(mediaKind("m.sticker", buildJsonObject { put("url", "mxc://a/b") }))
        assertNull(msg("msgtype" to "m.text", "body" to "سلام"))
        assertNull(msg("msgtype" to "m.text", "body" to "> <@a:x> see https://old.example\n\nthanks"))
        assertEquals(MediaKind.LINK, msg("msgtype" to "m.notice", "body" to "see https://x.io"))
        assertEquals(listOf("https://x.org/p?q=1", "http://y.io"), contentLinks(buildJsonObject { put("body", "a https://x.org/p?q=1, and (http://y.io) again https://x.org/p?q=1.") }))
    }

    @Test fun `links without a scheme - www and bare domains are found, emails and file names are not`() {
        assertEquals(
            listOf("https://www.example.com/a", "https://panbeh.ir", "https://example.org:8080/x?y=1", "https://x.io"),
            contentLinks(buildJsonObject { put("body", "سلام www.example.com/a, و panbeh.ir. یا (example.org:8080/x?y=1) https://x.io") }),
        )
        assertEquals(emptyList(), contentLinks(buildJsonObject { put("body", "mail me@example.com, see notes.txt and v1.2.3") }))
    }

    @Test fun `matchRange - normalized match mapped back to the original text`() {
        assertEquals(5 to 8, matchRange("سلام علي", "علی")) // Arabic yeh in the text
        assertEquals(0 to 6, matchRange("می‌روم خانه", "میروم")) // ZWNJ inside the match
        assertEquals(6 to 11, matchRange("Hello World", "world"))
        assertNull(matchRange("abc", "x"))
        assertNull(matchRange("abc", ""))
    }

    @Test fun `textDir - first letter decides, no letters is ltr`() {
        assertEquals("rtl", textDir("سلام hello"))
        assertEquals("ltr", textDir("hello سلام"))
        assertEquals("rtl", textDir("123 سلام"))
        assertEquals("ltr", textDir("۱۲۳"))
        assertEquals("ltr", textDir("12:30 👍"))
        assertEquals("ltr", textDir(""))
    }

    @Test fun `custom emoji - code becomes an emoticon img, not in code, unknown codes stay text`() {
        val emotes = mapOf("cat" to "mxc://s/cat", "cat_big" to "mxc://s/big")
        fun img(code: String, url: String) = """<img data-mx-emoticon src="$url" alt=":$code:" title=":$code:" height="32">"""
        assertEquals(img("cat", "mxc://s/cat"), formatMessage(":cat:", emptyList(), emotes)?.html)
        assertEquals("hi ${img("cat_big", "mxc://s/big")} :dog:", formatMessage("hi :cat_big: :dog:", emptyList(), emotes)?.html)
        assertEquals("<code>:cat:</code>", formatMessage("`:cat:`", emptyList(), emotes)?.html)
        assertNull(formatMessage(":dog: 10:30", emptyList(), emotes))
    }
}
