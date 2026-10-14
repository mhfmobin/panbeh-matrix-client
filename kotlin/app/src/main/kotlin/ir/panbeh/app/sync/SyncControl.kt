package ir.panbeh.app.sync

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.events.m.Presence
import ir.panbeh.app.PanbehApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Decides how the single running client syncs: a long-poll (real-time) while the UI is visible or in real-time mode, nothing
 * between the alarm-driven checks of interval mode. Presence follows visibility (online / unavailable; offline when the
 * "last seen" sharing is off). The settings live in SharedPreferences "panbeh": `background` (bool) and `interval`
 * (minutes, 0 = real-time).
 */
object SyncControl {
    private const val TAG = "PanbehSync"
    private const val PREFS = "panbeh"
    private const val KEY_ENABLED = "background"
    private const val KEY_INTERVAL = "interval"

    /** The activity is in the foreground; set from MainActivity.onResume / onPause. */
    val visible = MutableStateFlow(false)

    private data class Mode(val enabled: Boolean, val interval: Int)

    private val mode = MutableStateFlow(Mode(false, 0))
    private val lock = Mutex()

    /** elapsedRealtime of the last sync response; the service's watchdog restarts a long-poll that stalls. */
    @Volatile var lastProgress = SystemClock.elapsedRealtime()

    /** In a call: the service also holds the mic (and camera) so they keep working in the background. */
    @Volatile var inCall = false
        private set
    @Volatile var inVideoCall = false
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Background sync (and its notification) is on. */
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)

    /** Minutes between background checks; 0 = real-time. */
    fun interval(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 0)

    private fun load(ctx: Context) { mode.value = Mode(isEnabled(ctx), interval(ctx)) }

    /** Turns background sync on and starts the service. */
    fun start(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, true).apply()
        load(ctx)
        startService(ctx)
    }

    /** Real-time (0) or a check every N minutes; restarts the service in that mode. */
    fun setInterval(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_INTERVAL, maxOf(0, minutes)).apply()
        load(ctx)
        if (isEnabled(ctx)) startService(ctx)
    }

    /** Turns background sync off and stops the service. */
    fun stop(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
        load(ctx)
        ctx.stopService(Intent(ctx, SyncService::class.java))
    }

    internal fun startService(ctx: Context, action: String? = null) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java).also { if (action != null) it.action = action })
        } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowedException from the background
            Log.w(TAG, "could not start the service", e)
        }
    }

    /**
     * A call started or ended in the app. Start it while the app is in the foreground (Android requires that for
     * mic/camera services); the service then also holds the mic (and camera) and shows «تماس در جریان است».
     */
    fun setCall(ctx: Context, on: Boolean, video: Boolean = false) {
        inCall = on
        inVideoCall = on && video
        if (on) {
            startService(ctx)
            SyncService.instance?.onCallChanged()
        } else if (isEnabled(ctx)) SyncService.instance?.onCallChanged()
        else ctx.stopService(Intent(ctx, SyncService::class.java))
    }

    /** Hooks the sync decision to the client, the visibility and the settings. Call once from Application.onCreate. */
    fun attach(app: PanbehApp) {
        load(app)
        app.scope.launch {
            combine(
                app.accounts.client,
                app.state.prefs.map { it.shareLastSeen }.distinctUntilChanged(),
                visible,
                mode,
            ) { c, _, _, _ -> c }.collectLatest { c ->
                delay(200) // visible flips twice when the activity starts; settle first
                if (c != null) apply(c)
            }
        }
    }

    /** Real-time mode, or the UI is looking: a long-poll runs. Otherwise (interval mode in the background) only the alarm checks. */
    val polling: Boolean
        get() = visible.value || !(mode.value.enabled && mode.value.interval > 0)

    /** online while the UI is visible, else unavailable ("away"); offline when sharing the last-seen time is off. */
    fun presence(): Presence = when {
        !PanbehApp.instance.state.prefs.value.shareLastSeen -> Presence.OFFLINE
        visible.value -> Presence.ONLINE
        else -> Presence.UNAVAILABLE
    }

    /** Starts or stops the long-poll to match the mode. Starting again also restarts a stalled request. */
    suspend fun apply(c: MatrixClient) = lock.withLock {
        lastProgress = SystemClock.elapsedRealtime()
        if (polling) c.startSync(presence()) else c.stopSync()
    }

    /** Drops the pending /sync and asks again now (a new network, or a stalled request). No-op in interval mode. */
    suspend fun kick(c: MatrixClient) {
        if (!polling) return
        lock.withLock {
            lastProgress = SystemClock.elapsedRealtime()
            c.startSync(presence())
        }
    }

    /** Interval mode: one quick sync, then a moment for the batch's notifications to be posted. Returns when done. */
    suspend fun check(c: MatrixClient) {
        if (polling) return // already syncing
        lock.withLock {
            withTimeoutOrNull(45_000) {
                c.syncOnce(presence = null, timeout = Duration.ZERO)
                delay(5_000)
            }
        }
    }
}
