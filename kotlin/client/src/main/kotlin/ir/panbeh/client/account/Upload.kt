package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.utils.toByteArrayFlow
import io.ktor.http.ContentType

/** Uploads already-processed bytes unencrypted and returns the mxc:// URI (avatars, emoji, stickers). */
suspend fun MatrixClient.uploadPlain(bytes: ByteArray, contentType: String?): String {
    val cache = media.prepareUploadMedia(bytes.toByteArrayFlow(), contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() })
    return media.uploadMedia(cache, keepMediaInCache = false).getOrThrow()
}
