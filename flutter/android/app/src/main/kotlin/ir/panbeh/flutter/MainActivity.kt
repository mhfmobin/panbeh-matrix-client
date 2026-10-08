package ir.panbeh.flutter

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    // Share target: the shared streams are copied to cache files off the main thread, then handed to Dart once.
    private var channel: MethodChannel? = null
    private var share: Map<String, Any?>? = null
    private var waiting: MethodChannel.Result? = null
    private var copying = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun readShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND) {
            (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        } else {
            i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        i.action = Intent.ACTION_MAIN // a recreated activity must not share again
        copying++
        Thread {
            val files = ArrayList<Map<String, String>>()
            for (u in uris) {
                try { files.add(copyShared(u)) } catch (e: Exception) { /* unreadable: skipped */ }
            }
            runOnUiThread {
                copying--
                val map = mapOf<String, Any?>("text" to text, "files" to files)
                val w = waiting
                if (w != null) {
                    waiting = null
                    w.success(map)
                } else {
                    share = map
                    channel?.invokeMethod("onShare", null)
                }
            }
        }.start()
    }

    // ponytail: copies stay in the cache dir (the OS evicts it); a send may still be reading an earlier share, so nothing is cleared here
    private fun copyShared(u: Uri): Map<String, String> {
        var name = "file"
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val dest = File(dir, "${System.nanoTime()}_${name.replace('/', '_')}")
        (contentResolver.openInputStream(u) ?: throw java.io.IOException("can't open the file")).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return mapOf("path" to dest.absolutePath, "name" to name, "mime" to (contentResolver.getType(u) ?: "application/octet-stream"))
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "ir.panbeh.flutter/native")
        channel = ch
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "getShare" -> {
                    val s = share
                    if (s != null) {
                        share = null
                        result.success(s)
                    } else if (copying > 0) {
                        waiting = result
                    } else {
                        result.success(null)
                    }
                }
                "saveToDownloads" -> try {
                    val bytes = call.argument<ByteArray>("bytes")
                    if (bytes == null) {
                        result.error("args", "bytes required", null)
                    } else {
                        saveToDownloads(call.argument<String>("name") ?: "file", call.argument<String>("mime") ?: "application/octet-stream", bytes)
                        result.success(null)
                    }
                } catch (e: Exception) {
                    result.error("save", e.message, null)
                }
                else -> result.notImplemented()
            }
        }
    }

    /** Android 10+: MediaStore Downloads, no permission. Below: the app's own Downloads folder (no storage permission needed). */
    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("can't create the file")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("can't open the file")
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw java.io.IOException("no storage")
            File(dir, name).writeBytes(bytes)
        }
    }
}
