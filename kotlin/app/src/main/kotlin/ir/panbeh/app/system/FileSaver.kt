package ir.panbeh.app.system

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** What [FileSaver.openFile] did. */
enum class OpenResult { OPENED, GONE, NO_APP }

/** A failure with a Persian message for the user. */
class SaveException(message: String) : Exception(message)

/**
 * Saving downloaded files: into the phone's Downloads folder (Android 10+: MediaStore, no permission) or to a place the user
 * picked in Android's "Save as" screen (the UI launches `ActivityResultContracts.CreateDocument(mime)` and gives the Uri to
 * [saveToPicked]), and opening them again later. Saved copies are remembered by mxc URL in SharedPreferences "panbeh.saved".
 */
object FileSaver {
    const val NO_APP_TEXT = "برنامه‌ای برای باز کردن این فایل پیدا نشد."
    const val GONE_TEXT = "این فایل دیگر در دسترس نیست؛ دوباره دریافت می‌شود."

    private const val PREFS = "panbeh.saved"
    private const val URIS = "uris"
    private const val KEY = "k:"
    private const val CHUNK = 64 * 1024

    private fun saved(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private suspend fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(CHUNK)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    /** Writes [input] to Downloads as [name]; the returned Uri is remembered as ours (so [openFile] accepts it). Cancelling drops the partial file. */
    suspend fun saveToDownloads(ctx: Context, name: String, mime: String, input: InputStream, mxc: String? = null): Uri = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until it's complete
            }
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("can't create the file")
            try {
                (cr.openOutputStream(uri) ?: throw IOException("can't open the file")).use { copy(input, it) }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
        } else {
            // Android 7-9 save in the app's own Downloads (Android/data/…), no storage permission; "Save as" reaches shared folders
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("no storage")
            val file = File(dir, name.replace('/', '_'))
            try {
                file.outputStream().use { copy(input, it) }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
            uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }
        remember(ctx, uri, mxc)
        uri
    }

    /** Writes [input] to a document the user picked in "Save as". Keeps read access to it across restarts where the provider allows. */
    suspend fun saveToPicked(ctx: Context, uri: Uri, input: InputStream, mxc: String? = null): Uri = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        try {
            (cr.openOutputStream(uri, "wt") ?: throw IOException("can't open the file")).use { copy(input, it) }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, uri) } // some providers can't delete; a partial file stays
            throw e
        }
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } // else it re-downloads next time
        remember(ctx, uri, mxc)
        uri
    }

    private fun remember(ctx: Context, uri: Uri, mxc: String?) {
        // ponytail: grows by one short string per saved file, never pruned
        val sp = saved(ctx)
        val uris = HashSet(sp.getStringSet(URIS, emptySet()) ?: emptySet())
        uris.add(uri.toString())
        sp.edit().apply {
            putStringSet(URIS, uris)
            if (mxc != null) putString(KEY + mxc, uri.toString())
        }.apply()
    }

    /** Where the file for this mxc URL was saved, if it was. */
    fun savedUri(ctx: Context, mxc: String): String? = saved(ctx).getString(KEY + mxc, null)

    private fun forget(ctx: Context, mxc: String) { saved(ctx).edit().remove(KEY + mxc).apply() }

    /**
     * Opens a saved file in the app the system picks for its type. Only URIs this app saved: other apps' (or our own private)
     * content can't be handed on through here. GONE = deleted or moved since.
     */
    fun openFile(ctx: Context, uri: String, mime: String): OpenResult {
        if (uri !in (saved(ctx).getStringSet(URIS, emptySet()) ?: emptySet())) return OpenResult.GONE
        val u = Uri.parse(uri)
        val cr = ctx.contentResolver
        try {
            cr.openInputStream(u).use { if (it == null) throw IOException() }
        } catch (e: Exception) {
            return OpenResult.GONE
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, cr.getType(u) ?: mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            OpenResult.OPENED
        } catch (e: ActivityNotFoundException) {
            OpenResult.NO_APP
        }
    }

    /**
     * Opens the saved copy of [mxc]. false: not saved here, or deleted/moved since (then forgotten, so it downloads again).
     * Throws [SaveException] ([NO_APP_TEXT]) when nothing can open it.
     */
    fun openSaved(ctx: Context, mxc: String, mime: String): Boolean {
        val uri = savedUri(ctx, mxc) ?: return false
        return when (openFile(ctx, uri, mime)) {
            OpenResult.OPENED -> true
            OpenResult.NO_APP -> throw SaveException(NO_APP_TEXT)
            OpenResult.GONE -> { forget(ctx, mxc); false }
        }
    }
}
