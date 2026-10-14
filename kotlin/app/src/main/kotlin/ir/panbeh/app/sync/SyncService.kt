package ir.panbeh.app.sync

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.clientserverapi.client.SyncState
import ir.panbeh.app.MainActivity
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.R
import ir.panbeh.app.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive so the client can go on syncing and notifying while the app is in the background.
 * Real-time mode holds a wake lock "panbeh:sync" and a long-poll; interval mode wakes by alarm, holds "panbeh:check" for
 * up to 45 s, syncs once and sleeps again. A network change or a stalled request restarts the long-poll. The client itself
 * is the app's single [PanbehApp.accounts] client (there is no second copy to start).
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var realtimeLock: PowerManager.WakeLock? = null
    private var checkLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var network: ConnectivityManager.NetworkCallback? = null
    private var watching: Job? = null

    private val online = MutableStateFlow(true)
    private var current: Network? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
        val app = PanbehApp.instance
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                val changed = !online.value || n != current
                online.value = true
                current = n
                if (changed) scope.launch { app.accounts.client.value?.let { SyncControl.kick(it) } }
            }

            override fun onLost(n: Network) {
                if (n != current) return // an old default network going away after the switch
                online.value = false
            }
        }
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            current = cm.activeNetwork
            online.value = current != null
            cm.registerDefaultNetworkCallback(cb)
            network = cb
        } catch (e: RuntimeException) { // too many callbacks, or no permission
            Log.w(TAG, "no network callback", e)
        }
        watching = scope.launch {
            if (app.accounts.client.value == null) runCatching { app.accounts.restore() } // started with no UI: restore the account first
            launch { watchdog(app) }
            app.accounts.client.collectLatest { c -> if (c != null) follow(c) }
        }
    }

    /** Status text and progress of the running client. */
    private suspend fun follow(c: MatrixClient): Nothing = coroutineScope {
        val unsubscribe = c.api.sync.subscribe { SyncControl.lastProgress = SystemClock.elapsedRealtime() }
        launch { combine(c.syncState, online) { s, o -> s to o }.collect { refresh() } }
        try { awaitCancellation() } finally { unsubscribe() }
    }

    /** Every minute: a long-poll that hasn't answered in 50 s (it should within ~30) is stalled; start it over. */
    private suspend fun watchdog(app: PanbehApp) {
        while (true) {
            delay(WATCHDOG_MS)
            val c = app.accounts.client.value ?: continue
            if (SyncControl.polling && SystemClock.elapsedRealtime() - SyncControl.lastProgress > STALLED_MS) {
                Log.i(TAG, "sync stalled, restarting")
                runCatching { SyncControl.kick(c) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val check = intent?.action == ACTION_CHECK
        // an alarm may not start a foreground service from the background; we already are one unless the system restarted us
        if (!check || !foreground) goForeground()
        if (!SyncControl.isEnabled(this) && !SyncControl.inCall) { // restarted by the system after being switched off
            stopSelf()
            return START_NOT_STICKY
        }
        applyMode()
        if (check) {
            Log.i(TAG, "check")
            checkLock().acquire(CHECK_MAX_MS)
            scope.launch {
                val c = PanbehApp.instance.accounts.client.value ?: runCatching { PanbehApp.instance.accounts.restore() }.getOrNull()
                if (c != null) runCatching { SyncControl.check(c) }
                checkLock?.let { if (it.isHeld) it.release() }
            }
        }
        return START_STICKY
    }

    private fun checkLock(): PowerManager.WakeLock {
        var l = checkLock
        if (l == null) {
            l = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panbeh:check")
            l.setReferenceCounted(false)
            checkLock = l
        }
        return l
    }

    /** Real-time: hold the CPU. Every N minutes: no lock, an alarm instead (Doze stretches it to ~9-15 min at most). */
    @SuppressLint("WakelockTimeout") // held for as long as the user wants real-time messages
    private fun applyMode() {
        val enabled = SyncControl.isEnabled(this)
        val minutes = if (enabled) SyncControl.interval(this) else 0
        val l = realtimeLock
        if (enabled && minutes == 0) {
            val lock = l ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panbeh:sync").also {
                it.setReferenceCounted(false)
                realtimeLock = it
            }
            if (!lock.isHeld) lock.acquire()
        } else if (l != null && l.isHeld) l.release()
        val am = getSystemService(AlarmManager::class.java)
        if (minutes > 0) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, checkIntent())
        else am.cancel(checkIntent())
    }

    private fun checkIntent(): PendingIntent =
        PendingIntent.getService(this, 1, Intent(this, SyncService::class.java).setAction(ACTION_CHECK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** «پنبه متصل است» / «در حال اتصال…» / «اتصال قطع است؛ تلاش دوباره…» in the permanent notification. */
    private fun statusText(): Int {
        val state = PanbehApp.instance.accounts.client.value?.syncState?.value
        return when {
            !online.value || state == SyncState.ERROR -> R.string.service_offline
            state == SyncState.RUNNING || (state == SyncState.STOPPED && !SyncControl.polling) -> R.string.service_title
            else -> R.string.service_connecting
        }
    }

    /** Rebuilds the permanent notification (the sync state changed). */
    @SuppressLint("MissingPermission") // without it the notification just doesn't update
    fun refresh() {
        if (!foreground) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // notifications refused
        }
    }

    /** A call started or ended: new notification text and foreground types (mic, camera). */
    fun onCallChanged() = goForeground()

    private fun buildNotification(): Notification {
        val inCall = SyncControl.inCall
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(getString(if (inCall) R.string.call_ongoing else statusText()))
            .setContentText(if (inCall) null else getString(R.string.service_text))
            .setPriority(if (inCall) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        val call = if (!SyncControl.inCall || Build.VERSION.SDK_INT < 30) 0
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (SyncControl.inVideoCall) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type or call)
            foreground = true
        } catch (e: RuntimeException) { // e.g. camera permission refused: keep the mic at least
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type or (call and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA.inv()))
                foreground = true
            } catch (e2: RuntimeException) {
                Log.w(TAG, "startForeground failed", e2)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        network?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        realtimeLock?.let { if (it.isHeld) it.release() }
        checkLock?.let { if (it.isHeld) it.release() }
        getSystemService(AlarmManager::class.java).cancel(checkIntent())
        foreground = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 7
        private const val TAG = "PanbehSync"
        /** Alarm in interval mode: wake up, sync once, sleep again. */
        private const val ACTION_CHECK = "ir.panbeh.app.kotlin.CHECK"
        private const val CHECK_MAX_MS = 45_000L
        private const val WATCHDOG_MS = 60_000L
        private const val STALLED_MS = 50_000L

        @Volatile var instance: SyncService? = null
            private set
    }
}
