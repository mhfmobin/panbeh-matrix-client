package ir.panbeh.core.packs

import ir.panbeh.core.text.escRe
import kotlinx.serialization.json.JsonObject

// ---------- custom emoji & stickers (MSC2545 image packs) ----------
enum class Usage { EMOTICON, STICKER }

/** One image of a pack; `usage` is its own override (null or empty = the pack's). */
data class PackImage(val url: String, val body: String? = null, val info: JsonObject? = null, val usage: List<Usage>? = null)

/** The pack's own header. */
data class PackMeta(val displayName: String? = null, val avatarUrl: String? = null, val usage: List<Usage>? = null)

/** An im.ponies.room_emotes / user emote-pack content. */
data class PackContent(val pack: PackMeta? = null, val images: Map<String, PackImage>? = null)

/** A pack's images usable as `use`: by the image's usage, else the pack's, else both (absent or empty = both, per MSC2545). */
fun packImages(p: PackContent, use: Usage): List<Pair<String, PackImage>> =
    (p.images ?: emptyMap()).entries.filter { (_, i) ->
        if (!i.url.startsWith("mxc://")) return@filter false
        val u = i.usage?.takeIf { it.isNotEmpty() } ?: p.pack?.usage?.takeIf { it.isNotEmpty() }
        u == null || use in u
    }.map { it.key to it.value }

// shortcode characters: Latin or Persian/Arabic letters, digits, _ - and the Persian half-space (ZWNJ)
private val CODE_CHAR = Regex("(?=\\p{L})[\\p{IsLatin}\\p{IsArabic}]|\\p{Nd}|[_\\u200c-]")

/** A shortcode as typed: characters outside CODE_CHAR dropped (`sep` ""), or runs of them made one `sep`. */
fun cleanCode(s: String, sep: String = ""): String {
    val out = s.codePoints().toArray().joinToString("") { cp ->
        val c = String(Character.toChars(cp))
        if (CODE_CHAR.matches(c)) c else sep
    }
    return (if (sep.isNotEmpty()) out.replace(Regex("${escRe(sep)}{2,}"), sep) else out).take(32)
}

/** A shortcode from a file name: no extension, other characters as _. */
fun codeFrom(name: String) = cleanCode(name.replace(Regex("\\.[^.]*\\z"), ""), "_").replace(Regex("^_+|_+\\z"), "").ifEmpty { "emoji" }

/** How many images a pack may hold (Telegram's numbers). */
val PACK_CAP = mapOf(Usage.EMOTICON to 200, Usage.STICKER to 120)

/** `code`, or code_2, code_3… when the pack already has it. */
fun freeCode(code: String, taken: Map<String, *>): String {
    var c = code
    var n = 1
    while (taken.containsKey(c)) c = "${code}_${++n}"
    return c
}
