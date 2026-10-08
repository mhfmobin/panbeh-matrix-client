package ir.panbeh.flutter

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "ir.panbeh.flutter/native").setMethodCallHandler { call, result ->
            when (call.method) {
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
