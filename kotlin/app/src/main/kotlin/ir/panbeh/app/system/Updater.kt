package ir.panbeh.app.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ir.panbeh.app.BuildConfig
import ir.panbeh.app.PanbehApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the self-updater is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object None : UpdateState
    data object Error : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
}

/**
 * Self-update from GitHub: the newest release's `Panbeh-kotlin*.apk`, handed to Android's installer (which asks the user;
 * there is no silent install). Only release builds (`x.y.z`) take part: a rolling main build has a newer versionCode than
 * any release, so Android would refuse the downgrade.
 */
object Updater {
    private const val RELEASE = "https://api.github.com/repos/mhfmobin/panbeh-matrix-client/releases/latest" // "latest" skips the rolling pre-release
    private const val DOWNLOADS = "https://github.com/mhfmobin/panbeh-matrix-client/releases/download/"
    private val APK = Regex("^Panbeh-kotlin.*\\.apk$")
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private const val PREFS = "panbeh.updates"
    private const val KEY_AUTO = "auto"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var apkUrl = ""

    /** Release builds only. */
    val enabled: Boolean get() = Regex("^\\d+\\.\\d+\\.\\d+$").matches(BuildConfig.VERSION_NAME)

    /** Where the new APK is put; BootReceiver deletes it once the app is replaced. */
    fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** Download a found update on its own (not over mobile data). */
    fun autoUpdate(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
        if (on) download()
    }

    /** Checks now and every 6 hours. Call once from Application.onCreate. */
    fun start(app: PanbehApp) {
        if (!enabled) return
        app.scope.launch(Dispatchers.IO) {
            while (true) {
                check(app)
                delay(EVERY_MS)
            }
        }
    }

    /** Numeric version compare like `localeCompare(…, {numeric: true})`: 0.10.0 > 0.9.0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        val y = b.removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(y.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    /** Asks GitHub for a newer release; downloads it right away when auto-update is on and the network isn't metered. */
    suspend fun check(ctx: Context = PanbehApp.instance) {
        val cur = _state.value
        if (cur is UpdateState.Checking || cur is UpdateState.Downloading || cur is UpdateState.Ready) return
        if (cur !is UpdateState.Available) _state.value = UpdateState.Checking // a version already found stays on show
        try {
            val rel = withContext(Dispatchers.IO) {
                val c = URL(RELEASE).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (c.responseCode != 200) throw IOException("${c.responseCode}")
                Json.parseToJsonElement(c.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            }
            val v = rel["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
            val asset = (rel["assets"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { APK.matches(it["name"]?.jsonPrimitive?.content ?: "") }
            apkUrl = asset?.get("browser_download_url")?.jsonPrimitive?.content ?: ""
            if (apkUrl.isEmpty() || compareVersions(v, BuildConfig.VERSION_NAME) <= 0) { _state.value = UpdateState.None; return }
            _state.value = UpdateState.Available(v)
            if (autoUpdate(ctx) && !Device.isMetered(ctx)) download() // on mobile data it only says so
        } catch (e: Exception) {
            val prev = cur as? UpdateState.Available
            _state.value = prev ?: UpdateState.Error
        }
    }

    /** Downloads the found release into the cache; progress in [state]. */
    fun download() {
        val cur = _state.value as? UpdateState.Available ?: return
        val ctx = PanbehApp.instance
        _state.value = UpdateState.Downloading(cur.version, 0)
        ctx.scope.launch(Dispatchers.IO) {
            val part = File(ctx.cacheDir, "update.apk.part")
            try {
                if (!apkUrl.startsWith(DOWNLOADS)) throw IOException("not a release")
                val c = URL(apkUrl).openConnection() as HttpURLConnection // follows GitHub's redirect to its download host
                val size = c.contentLengthLong
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            got += n
                            val p = if (size > 0) (got * 100 / size).toInt() else 0
                            if (p != last) { last = p; _state.value = UpdateState.Downloading(cur.version, p) }
                        }
                    }
                }
                if (!part.renameTo(apkFile(ctx))) throw IOException("can't save the update")
                _state.value = UpdateState.Ready(cur.version)
            } catch (e: Exception) {
                part.delete()
                _state.value = cur // the UI says the download failed and offers it again
            }
        }
    }

    /** Hands the downloaded APK to Android's installer, which asks the user (and, the first time, to allow installs from Panbeh). */
    fun install(ctx: Context): Boolean {
        val f = apkFile(ctx)
        val ready = _state.value as? UpdateState.Ready
        if (!f.exists()) {
            if (ready != null) _state.value = UpdateState.Available(ready.version)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
