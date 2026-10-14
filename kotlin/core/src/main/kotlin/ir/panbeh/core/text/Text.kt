package ir.panbeh.core.text

/** JS `\s` as regex class contents (Java's `\s` is ASCII-only). */
const val JS_WS = """\t\n\u000B\u000C\r    -     　﻿"""

private fun isJsSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '

/** JS `String.prototype.trim`. */
fun jsTrim(s: String) = s.trim(::isJsSpace)

private val ESC_RE = Regex("[.*+?^\${}()|\\[\\]\\\\]")

/** Escape regex metacharacters (JS escRe). */
fun escRe(s: String) = ESC_RE.replace(s) { "\\" + it.value }

fun normalizeServer(input: String): String {
    val s = jsTrim(input).replace(Regex("/+\\z"), "")
    return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "https://$s"
}

private val USER_ID = Regex("@[^$JS_WS:]+:[^$JS_WS:]+(:\\d+)?")

/** Full Matrix user ID, e.g. "@alice:example.org" (worth a direct profile lookup). */
fun isUserId(s: String) = USER_ID.matches(jsTrim(s))

/** Suggested #address localpart from a room name; Latin letters/digits only (Farsi names give ""). */
fun aliasLocalpart(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-+|-+\\z"), "")

/** Search-term folding: case, Arabic/Persian ي ك, and ZWNJ don't matter. */
fun normalize(s: String) = s.lowercase().replace('ي', 'ی').replace('ك', 'ک').replace("‌", "")

/** Where `term` matches `text` (both normalized), as [start, end) in the original text: normalizing drops ZWNJs, which shifts indices. */
fun matchRange(text: String, term: String): Pair<Int, Int>? {
    val t = normalize(term)
    if (t.isEmpty()) return null
    val norm = StringBuilder()
    val at = ArrayList<Int>() // index in `text` of each char of `norm`
    for (i in text.indices) {
        val n = normalize(text[i].toString())
        norm.append(n)
        repeat(n.length) { at.add(i) }
    }
    val i = norm.indexOf(t)
    return if (i < 0) null else at[i] to at[i + t.length - 1] + 1
}

private val LETTER = Regex("\\p{L}")
private val RTL_LETTER = Regex("[\\p{IsArabic}\\p{IsHebrew}\\p{IsSyriac}\\p{IsThaana}\\p{IsNko}]")

/** Direction of a message's text the way dir=auto picks it (first letter), but the same everywhere: WebViews disagree on text
 *  with no letters (numbers, emoji), and the time/ticks have to sit on the side the text leaves free. */
fun textDir(s: String): String {
    val letter = LETTER.find(s)?.value
    return if (letter != null && RTL_LETTER.matches(letter)) "rtl" else "ltr"
}
