package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import ir.panbeh.core.text.LINK_SRC
import ir.panbeh.core.text.linkHref
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** A link card: [image] is an mxc:// URI (the homeserver's copy of og:image). */
data class LinkPreview(val url: String, val siteName: String, val title: String, val description: String, val image: String?)

private val URL_RE = Regex(LINK_SRC, RegexOption.IGNORE_CASE)
private val CODE_RE = Regex("```[\\s\\S]*?```|`[^`\\n]*`")
private val TEXT = listOf("m.text", "m.notice", "m.emote")

/** The first web link worth previewing in a message's [content]: not a matrix.to pill, not inside code; null if none. */
fun firstPreviewUrl(content: JsonObject): String? {
    if (content.string("msgtype") !in TEXT) return null
    val body = stripReplyFallback(content.string("body") ?: "").replace(CODE_RE, "")
    return URL_RE.findAll(body).map { linkHref(it.value) }.firstOrNull { !it.startsWith("https://matrix.to/") }
}

/** Link previews from the homeserver (preview_url) for the first web link of a text message. */
class LinkPreviews(private val client: MatrixClient) {
    private val cache = ConcurrentHashMap<String, java.util.Optional<LinkPreview>>()

    /**
     * The card for a message ([ts] = its time, so the server shows the page as it was). Null when there's no link, the
     * server has no previews, or the page has no title or description. Cached per link. Respect the user's `previews` preference before calling.
     */
    suspend fun of(content: JsonObject, ts: Long): LinkPreview? {
        val url = firstPreviewUrl(content) ?: return null
        cache[url]?.let { return it.orElse(null) }
        val p = runCatching {
            val r = client.api.media.getUrlPreview(url, ts).getOrThrow()
            fun str(k: String) = ((r.others?.get(k)) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: ""
            val title = str("og:title")
            val desc = str("og:description")
            if (title.isEmpty() && desc.isEmpty()) null
            else LinkPreview(url, str("og:site_name").ifEmpty { url.split("/").getOrElse(2) { url } }, title, desc, r.imageUrl?.takeIf { it.startsWith("mxc://") })
        }.getOrNull() // no previews on this server: nothing
        cache[url] = java.util.Optional.ofNullable(p)
        return p
    }
}
