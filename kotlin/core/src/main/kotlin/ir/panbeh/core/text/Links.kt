package ir.panbeh.core.text

import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val TLDS = "com|org|net|edu|gov|int|io|ir|co|me|app|dev|info|biz|ai|tv|xyz|ly|gl|us|uk|de|fr|ru|tech|online|site|chat|link|page"

/** What counts as a web link in plain text: http(s)://…, www.…, or a bare domain with a well-known TLD (not in emails). Use with IGNORE_CASE. */
const val LINK_SRC =
    """(?:https?://|www\.)[^$JS_WS<]+[^$JS_WS<.,;:!?)"'`]""" +
        """|(?<![\w@./-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:$TLDS)(?![\w@-])(?::\d+)?(?:/[^$JS_WS<]*[^$JS_WS<.,;:!?)"'`])?"""

private val LINK_RE = Regex(LINK_SRC, RegexOption.IGNORE_CASE)
private val SCHEME = Regex("^(?:https?:|matrix:)", RegexOption.IGNORE_CASE)

/** href for a link found by LINK_SRC: scheme-less ones get https://. */
fun linkHref(u: String) = if (SCHEME.containsMatchIn(u)) u else "https://$u"

/** Distinct web links in text (same pattern as the bubble's linkify), as hrefs. */
fun extractLinks(text: String): List<String> = LINK_RE.findAll(text).map { linkHref(it.value) }.distinct().toList()

private val REPLY_FALLBACK = Regex("^(> [^\\n\\r\\u2028\\u2029]*\\n)+\\n?")

/** Links in a message's own text, not in the quoted reply fallback. */
fun contentLinks(c: JsonObject): List<String> {
    val body = when (val b = c["body"]) { null, JsonNull -> ""; is JsonPrimitive -> b.content; else -> b.toString() }
    return extractLinks(REPLY_FALLBACK.replaceFirst(body, ""))
}
