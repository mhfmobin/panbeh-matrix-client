package ir.panbeh.client.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** MSC2448: a blurred stand-in other clients show before the media is downloaded. */
const val BLURHASH = "xyz.amorgan.blurhash"

/** A poster frame / thumbnail the platform made (JPEG, at most 800 px for videos). */
class Thumbnail(val bytes: ByteArray, val width: Int, val height: Int, val mimetype: String = "image/jpeg")

/** What the platform computed about a file: size, pixel size, duration, blurhash, poster frame. */
data class MediaInfo(
    val mimetype: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** ms (videos, audio) */
    val durationMs: Long? = null,
    val blurhash: String? = null,
    /** videos: the first frame; images need none (the server thumbnails them) */
    val thumbnail: Thumbnail? = null,
)

/** Extra content for special kinds of media; merged over the generated content like the web app's `extra`. */
object MediaExtras {
    /** mautrix's flags: a video that loops silently without controls. */
    val GIF_INFO: JsonObject = buildJsonObject {
        put("fi.mau.gif", true); put("fi.mau.loop", true); put("fi.mau.autoplay", true); put("fi.mau.hide_controls", true); put("fi.mau.no_audio", true)
    }

    fun gif(): JsonObject = buildJsonObject { put("info", GIF_INFO) }

    /** A voice message: m.audio with MSC1767 audio (duration ms, waveform 0..1024 × 40 bars) and MSC3245 voice. */
    fun voice(durationMs: Long, waveform: List<Int>): JsonObject = buildJsonObject {
        put("msgtype", "m.audio")
        put("body", "پیام صوتی")
        putJsonObject("info") { put("duration", durationMs) }
        putJsonObject("org.matrix.msc1767.audio") { put("duration", durationMs); put("waveform", JsonArray(waveform.map { JsonPrimitive(it) })) }
        putJsonObject("org.matrix.msc3245.voice") {}
    }

    /** A round video message of [px] × [px]. */
    fun videoNote(durationMs: Long, px: Int): JsonObject = buildJsonObject {
        put("body", "پیام ویدیویی")
        put("app.panbeh.video_note", true)
        putJsonObject("info") { put("duration", durationMs); put("w", px); put("h", px) }
    }

    /** Photos and videos sent together are tagged as one album: id, n items, this one is i. */
    fun album(id: String, n: Int, i: Int): JsonObject = buildJsonObject {
        putJsonObject("app.panbeh.album") { put("id", id); put("n", n); put("i", i) }
    }
}

/** Where a file ended up on the media repository: a plain url, or an encrypted file (key and hashes) as the event carries it. */
sealed interface Uploaded {
    data class Plain(val url: String) : Uploaded
    data class Encrypted(val file: JsonObject) : Uploaded
}

/**
 * The m.room.message content for an uploaded file, exactly as the web app builds it: MSC2530 caption (body = caption, `filename` = name,
 * else body = name), info with mimetype/size and for images w/h/blurhash, for videos w/h/duration/blurhash plus the poster as
 * thumbnail_url / thumbnail_file; [extra] is merged over it (its `info` merged into info); the file in `file` (encrypted rooms) or `url`.
 */
fun mediaContent(name: String, caption: String?, m: MediaInfo, asFile: Boolean, extra: JsonObject?, main: Uploaded, thumb: Uploaded?): JsonObject {
    val msgtype = if (asFile) "m.file" else when {
        m.mimetype.startsWith("image/") -> "m.image"
        m.mimetype.startsWith("video/") -> "m.video"
        else -> "m.file"
    }
    val info = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    info["mimetype"] = JsonPrimitive(m.mimetype)
    info["size"] = JsonPrimitive(m.size)
    if (msgtype == "m.image" || msgtype == "m.video") {
        m.width?.let { info["w"] = JsonPrimitive(it) }
        m.height?.let { info["h"] = JsonPrimitive(it) }
        m.blurhash?.let { info[BLURHASH] = JsonPrimitive(it) }
    }
    if (msgtype == "m.video") {
        m.durationMs?.let { info["duration"] = JsonPrimitive(it) }
        val t = m.thumbnail
        if (t != null && thumb != null) {
            when (thumb) { is Uploaded.Encrypted -> info["thumbnail_file"] = thumb.file; is Uploaded.Plain -> info["thumbnail_url"] = JsonPrimitive(thumb.url) }
            info["thumbnail_info"] = buildJsonObject { put("mimetype", t.mimetype); put("size", t.bytes.size); put("w", t.width); put("h", t.height) }
        }
    }
    val c = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    c["msgtype"] = JsonPrimitive(msgtype)
    c["body"] = JsonPrimitive(if (!caption.isNullOrEmpty()) caption else name)
    if (!caption.isNullOrEmpty()) c["filename"] = JsonPrimitive(name)
    extra?.forEach { (k, v) -> if (k != "info") c[k] = v }
    c["info"] = JsonObject(info + ((extra?.get("info") as? JsonObject) ?: emptyMap()))
    when (main) { is Uploaded.Encrypted -> c["file"] = main.file; is Uploaded.Plain -> c["url"] = JsonPrimitive(main.url) }
    return JsonObject(c)
}

/** A file to send. [open] returns its bytes again for each try (retries read it anew). */
class FileToSend(val name: String, val info: MediaInfo, val open: () -> kotlinx.coroutines.flow.Flow<ByteArray>) {
    val isImageOrVideo get() = info.mimetype.startsWith("image/") || info.mimetype.startsWith("video/")
}

/** One planned send of the files in a batch (see [planFiles]). */
class PlannedFile(val file: FileToSend, val extra: JsonObject?, val caption: String?, val replyTo: Boolean, val asFile: Boolean)

/**
 * How a batch of files goes out, like the web composer: photos and videos (not GIFs, not "as file") go as albums of up to 10, each
 * tagged `app.panbeh.album`; the reply is on the first item and the caption on the last of the first album. The rest go one by one
 * (a video "as GIF" gets the GIF flags); with no albums the first has the reply and the caption.
 */
fun planFiles(files: List<FileToSend>, caption: String?, asFile: Boolean, asGif: Boolean, newId: () -> String = { java.util.UUID.randomUUID().toString() }): List<PlannedFile> {
    val cap = caption?.ifEmpty { null }
    val gif = { f: FileToSend -> asGif && !asFile && f.info.mimetype.startsWith("video/") }
    val media = files.filter { !asFile && f(it) && it.info.mimetype != "image/gif" && !gif(it) }
    val albums = if (media.size > 1) media.chunked(10) else emptyList()
    val out = ArrayList<PlannedFile>()
    for ((k, a) in albums.withIndex()) {
        val id = newId()
        a.forEachIndexed { i, f -> out.add(PlannedFile(f, MediaExtras.album(id, a.size, i), if (k == 0 && i == a.size - 1) cap else null, k == 0 && i == 0, false)) }
    }
    val inAlbum = albums.flatten().toSet()
    files.filter { it !in inAlbum }.forEachIndexed { i, f ->
        val first = albums.isEmpty() && i == 0
        out.add(PlannedFile(f, if (gif(f)) MediaExtras.gif() else null, if (first) cap else null, first, asFile))
    }
    return out
}

private fun f(x: FileToSend) = x.isImageOrVideo
