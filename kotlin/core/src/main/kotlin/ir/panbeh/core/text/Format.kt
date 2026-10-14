package ir.panbeh.core.text

/** A user that "@Name" in the text may refer to. */
data class Mention(val name: String, val id: String)

/** Message HTML plus the user ids it mentions. */
data class FormattedMessage(val html: String, val ids: List<String>)

private fun esc(s: String) = s.replace(Regex("[&<>\"']")) { "&#${it.value[0].code};" }

private val CODE_BLOCK = Regex("\\n?```(?:[\\w+-]*\\n)?([\\s\\S]*?)\\n?```\\n?")
private val CODE_INLINE = Regex("`([^`\\n]+)`")
private val MD_LINK = Regex("\\[([^\\]\\n]+)\\]\\((https?://[^$JS_WS)]+)\\)")
private val BOLD = Regex("\\*\\*([^$JS_WS*](?:[^*\\n]*?[^$JS_WS*])?)\\*\\*")
private val STRIKE = Regex("~~([^$JS_WS~](?:[^~\\n]*?[^$JS_WS~])?)~~")
private val SPOILER = Regex("\\|\\|([^$JS_WS|](?:[^|\\n]*?[^$JS_WS|])?)\\|\\|")
// opening marker not after a word char, closing not before one: snake_case and 2*3*4 stay literal
private val ITALIC = Regex("(?<![\\p{L}\\p{N}_*])([*_])(?=[^$JS_WS])((?:(?!\\1)[^\\n])*?[^$JS_WS])\\1(?![\\p{L}\\p{N}_*])")
private val PLACEHOLDER = Regex("\u0000(\\d+)\u0000")

private class Part(var html: String, val quote: Boolean)

/** Markdown subset + "@Name" mentions as matrix.to links + ":code:" custom emoji (code → mxc).
 *  Everything that must not be re-parsed (code, links, mentions, emoji) is stashed behind \0N\0 placeholders until the end.
 *  null when nothing got formatted, so plain messages send no formatted_body. */
fun formatMessage(text: String, mentions: List<Mention>, emotes: Map<String, String> = emptyMap()): FormattedMessage? {
    val stash = ArrayList<String>()
    fun hold(html: String): String { stash.add(html); return "\u0000${stash.size - 1}\u0000" }
    var t = text.replace("\u0000", "")
    t = CODE_BLOCK.replace(t) { hold("<pre><code>${esc(it.groupValues[1])}</code></pre>") }
    t = CODE_INLINE.replace(t) { hold("<code>${esc(it.groupValues[1])}</code>") }

    val byName = LinkedHashMap<String, String>().also { map -> mentions.forEach { map["@" + it.name] = it.id } }
    val used = LinkedHashSet<String>()
    if (byName.isNotEmpty()) {
        // longest first so "@Ali Reza" isn't cut short by "@Ali"
        val re = Regex(byName.keys.sortedByDescending { it.length }.joinToString("|") { escRe(it) })
        t = re.replace(t) {
            val id = byName[it.value]!!
            used.add(id)
            hold("<a href=\"https://matrix.to/#/${esc(id)}\">${esc(it.value.substring(1))}</a>")
        }
    }
    if (emotes.isNotEmpty()) {
        val re = Regex(":(${emotes.keys.sortedByDescending { it.length }.joinToString("|") { escRe(it) }}):")
        t = re.replace(t) {
            hold("<img data-mx-emoticon src=\"${esc(emotes[it.groupValues[1]]!!)}\" alt=\"${esc(it.value)}\" title=\"${esc(it.value)}\" height=\"32\">")
        }
    }

    t = esc(t)
    t = MD_LINK.replace(t) { hold("<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>") }
    val parts = ArrayList<Part>()
    for (line in t.split("\n")) {
        val q = line.startsWith("&#62; ")
        val last = parts.lastOrNull()
        if (q && last != null && last.quote) last.html += "<br>" + line.substring(6)
        else parts.add(Part(if (q) line.substring(6) else line, q))
    }
    var h = parts.withIndex().joinToString("") { (i, p) ->
        (if (p.quote) "<blockquote>${p.html}</blockquote>" else p.html) + if (i < parts.size - 1 && !p.quote && !parts[i + 1].quote) "\n" else ""
    }
    fun wrap(re: Regex, open: String, close: String) { h = re.replace(h) { "$open${it.groupValues[1]}$close" } }
    wrap(BOLD, "<strong>", "</strong>")
    wrap(STRIKE, "<del>", "</del>")
    wrap(SPOILER, "<span data-mx-spoiler>", "</span>")
    h = ITALIC.replace(h) { "<em>${it.groupValues[2]}</em>" }
    h = h.replace("\n", "<br>") // before restoring, so <pre> keeps its newlines
    while (h.contains('\u0000')) h = PLACEHOLDER.replace(h) { stash[it.groupValues[1].toInt()] }
    val clean = esc(text.replace("\u0000", "")).replace("\n", "<br>")
    return if (used.isNotEmpty() || h != clean) FormattedMessage(h, used.toList()) else null
}
