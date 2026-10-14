package ir.panbeh.core.media

import ir.panbeh.core.text.contentLinks
import ir.panbeh.core.util.jsNum
import ir.panbeh.core.util.string
import ir.panbeh.core.util.truthy
import kotlinx.serialization.json.JsonObject

/** Shared-media tab an event belongs to. */
enum class MediaKind { MEDIA, FILE, LINK, AUDIO }

/** Shared-media tab for an event (`c` is its content); null = not listed (stickers, polls, plain text…). */
fun mediaKind(type: String, c: JsonObject): MediaKind? {
    if (type != "m.room.message") return null
    return when (c.string("msgtype")) {
        "m.image", "m.video" -> MediaKind.MEDIA
        "m.file" -> MediaKind.FILE
        "m.audio" -> MediaKind.AUDIO
        "m.text", "m.notice", "m.emote" -> if (contentLinks(c).isNotEmpty()) MediaKind.LINK else null
        else -> null
    }
}

/** A looping, silent video (mautrix's flags, also set by the Telegram bridge) or an animated gif image; `info` is the content's info object. */
fun isGif(msgtype: String?, info: JsonObject?) =
    info?.get("fi.mau.gif").truthy() || (msgtype == "m.image" && info?.string("mimetype") == "image/gif")

/** A saved gif: message content, keyed by its mxc url (plain or encrypted file). */
data class Gif(val msgtype: String, val body: String, val info: JsonObject? = null, val url: String? = null, val file: GifFile? = null)
/** The encrypted-file part of a [Gif]; only its url matters here. */
data class GifFile(val url: String)

fun isGif(g: Gif) = isGif(g.msgtype, g.info)
private fun gifKey(g: Gif) = g.url ?: g.file?.url

// ponytail: capped at 100, account data tops out around 64KB
/** Saved gifs with `g` first: re-saving moves it up instead of duplicating. */
fun withGif(list: List<Gif>, g: Gif) = (listOf(g) + list.filter { gifKey(it) != gifKey(g) }).take(100)
fun withoutGif(list: List<Gif>, g: Gif) = list.filter { gifKey(it) != gifKey(g) }
fun hasGif(list: List<Gif>, g: Gif) = list.any { gifKey(it) == gifKey(g) }

/** `n` bars (peak of each slice), scaled 0..1024 like MSC1767 waveforms. Also rescales a received waveform for display. */
fun downsample(xs: List<Double>, n: Int = 40): List<Int> {
    if (xs.isEmpty()) return emptyList()
    val max = xs.max().let { if (it == 0.0 || it.isNaN()) 1.0 else it }
    return List(n) { i ->
        val a = (i * xs.size) / n
        val part = xs.subList(minOf(a, xs.size), minOf(xs.size, maxOf(a + 1, ((i + 1) * xs.size) / n)))
        Math.round(part.max() / max * 1024).toInt()
    }
}

/** A parsed geo: URI; `acc` = the uncertainty in meters, if given. */
data class Geo(val lat: Double, val lon: Double, val acc: Double?)

private val GEO = Regex("^geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,-?[\\d.]+)?(?:;[^\\n\\r\\u2028\\u2029]*?\\bu=(\\d+(?:\\.\\d+)?))?", RegexOption.IGNORE_CASE)

/** geo:lat,lon[,alt][;u=accuracy] → numbers; null if malformed. */
fun parseGeoUri(uri: String?): Geo? {
    val m = GEO.find(uri ?: "") ?: return null
    return Geo(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groups[3]?.value?.toDouble())
}

fun osmUrl(lat: Double, lon: Double) = "https://www.openstreetmap.org/?mlat=${jsNum(lat)}&mlon=${jsNum(lon)}#map=16/${jsNum(lat)}/${jsNum(lon)}"

/** A width × height in pixels. */
data class Dim(val w: Int, val h: Int)

/** Scale (w, h) so the longer side is at most `max`; never upscales. */
fun fitSize(w: Int, h: Int, max: Int = 1280): Dim {
    val k = minOf(1.0, max.toDouble() / maxOf(w, h))
    return Dim(Math.round(w * k).toInt(), Math.round(h * k).toInt())
}

/** Emoji and stickers may be at most 2:1 either way: the size after padding the short side to fit. */
fun padToRatio(w: Int, h: Int, max: Int = 2): Dim =
    if (w > h * max) Dim(w, Math.ceil(w.toDouble() / max).toInt()) else if (h > w * max) Dim(Math.ceil(h.toDouble() / max).toInt(), h) else Dim(w, h)
