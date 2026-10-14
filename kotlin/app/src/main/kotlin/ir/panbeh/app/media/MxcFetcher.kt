package ir.panbeh.app.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.core.model.events.m.room.EncryptedFile
import ir.panbeh.app.PanbehApp
import okio.Buffer

/**
 * An image Coil loads through Trixnity's media service (authenticated media, decryption, its cache).
 * [w]/[h] > 0 asks the server for a thumbnail; [file] is the encrypted attachment, if any.
 */
data class Mxc(val uri: String, val w: Int = 0, val h: Int = 0, val file: EncryptedFile? = null, val maxBytes: Long = 50L shl 20)

class MxcFetcher(private val data: Mxc, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val client = PanbehApp.instance.accounts.client.value ?: return null
        val m = client.media
        val media = when {
            data.file != null -> m.getEncryptedMedia(data.file, maxSize = data.maxBytes)
            data.w > 0 -> m.getThumbnail(data.uri, data.w.toLong(), data.h.toLong(), maxSize = data.maxBytes)
            else -> m.getMedia(data.uri, maxSize = data.maxBytes)
        }.getOrThrow()
        val bytes = media.toByteArray(maxSize = data.maxBytes) ?: error("media too large")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<Mxc> {
        override fun create(data: Mxc, options: Options, imageLoader: ImageLoader): Fetcher = MxcFetcher(data, options)
    }
}

class MxcKeyer : Keyer<Mxc> {
    override fun key(data: Mxc, options: Options) = "${data.uri}#${data.w}x${data.h}"
}
