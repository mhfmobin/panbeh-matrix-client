package ir.panbeh.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import ir.panbeh.core.media.blurhashEncode
import ir.panbeh.core.media.fitSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A file picked or shared, read into memory with what the sender needs to describe it (matrix.ts sendFile). */
class PreparedMedia(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val w: Int? = null,
    val h: Int? = null,
    /** ms, for video/audio */
    val duration: Long? = null,
    /** xyz.amorgan.blurhash */
    val blurhash: String? = null,
    /** JPEG poster for videos */
    val thumb: Thumb? = null,
) {
    class Thumb(val bytes: ByteArray, val w: Int, val h: Int, val mime: String = "image/jpeg")
    val size: Int get() = bytes.size
    val msgtype: String get() = when {
        mime.startsWith("image/") -> "m.image"
        mime.startsWith("video/") -> "m.video"
        mime.startsWith("audio/") -> "m.audio"
        else -> "m.file"
    }
}

object MediaPrep {
    fun nameOf(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    fun mimeOf(ctx: Context, uri: Uri, name: String): String =
        ctx.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /**
     * Reads [uri]; photos are downscaled to 1280 px JPEG (q 0.82) unless [asFile] or the result isn't smaller, and get
     * w/h + blurhash; videos get w/h/duration, a poster frame (800 px) and its blurhash.
     */
    suspend fun prepare(ctx: Context, uri: Uri, asFile: Boolean = false): PreparedMedia = withContext(Dispatchers.IO) {
        val name = nameOf(ctx, uri)
        val mime = mimeOf(ctx, uri, name)
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        when {
            asFile -> PreparedMedia(name, mime, bytes)
            mime.startsWith("image/") -> image(name, mime, bytes)
            mime.startsWith("video/") -> video(ctx, uri, name, mime, bytes)
            mime.startsWith("audio/") -> PreparedMedia(name, mime, bytes, duration = durationOf(ctx, uri))
            else -> PreparedMedia(name, mime, bytes)
        }
    }

    fun image(name: String, mime: String, bytes: ByteArray): PreparedMedia {
        val bmp = decode(bytes) ?: return PreparedMedia(name, mime, bytes)
        val hash = blurhashOf(bmp)
        if (!Regex("^image/(jpeg|png|webp|bmp)$").matches(mime)) return PreparedMedia(name, mime, bytes, bmp.width, bmp.height, blurhash = hash)
        val (w, h) = fitSize(bmp.width, bmp.height).let { it.w to it.h }
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply { drawColor(Color.WHITE) /* JPEG has no alpha */; drawBitmap(Bitmap.createScaledBitmap(bmp, w, h, true), 0f, 0f, null) }
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        return if (jpeg.size >= bytes.size) PreparedMedia(name, mime, bytes, bmp.width, bmp.height, blurhash = hash)
        else PreparedMedia(name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg, w, h, blurhash = hash)
    }

    private fun video(ctx: Context, uri: Uri, name: String, mime: String, bytes: ByteArray): PreparedMedia {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val w0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = if (rot % 180 != 0) h0 to w0 else w0 to h0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            // frame 0 is often black
            val frame = r.getFrameAtTime(minOf(100_000L, (dur ?: 0) * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumb = frame?.let { f ->
                val s = fitSize(f.width, f.height, 800)
                val b = Bitmap.createScaledBitmap(f, s.w, s.h, true)
                PreparedMedia.Thumb(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray(), s.w, s.h)
            }
            PreparedMedia(name, mime, bytes, w, h, dur, frame?.let(::blurhashOf), thumb)
        } catch (_: Exception) {
            PreparedMedia(name, mime, bytes)
        } finally { r.release() }
    }

    fun durationOf(ctx: Context, uri: Uri): Long? = runCatching {
        MediaMetadataRetriever().run { try { setDataSource(ctx, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() } }
    }.getOrNull()

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** MSC2448 blurhash of a ≤32 px copy. */
    fun blurhashOf(src: Bitmap): String {
        val s = fitSize(src.width, src.height, 32)
        val small = Bitmap.createScaledBitmap(src.copy(Bitmap.Config.ARGB_8888, false), maxOf(1, s.w), maxOf(1, s.h), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return blurhashEncode(px, small.width, small.height)
    }
}
