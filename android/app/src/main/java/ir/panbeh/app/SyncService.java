package ir.panbeh.app;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps the app process alive so its Matrix client can go on syncing and notifying while the app is in the
 * background. While the activity's WebView lives it does the syncing; once the activity is gone (swiped
 * away, killed, after a reboot) this service runs the same web bundle in an off-screen WebView instead.
 * Never both at once: two clients on one device would race over the same crypto store.
 */
public class SyncService extends Service {
    static final int NOTIFICATION_ID = 7;
    private static final String TAG = "PanbehSync";
    private static final String PREFS = "panbeh", PREF_ENABLED = "background", PREF_INTERVAL = "interval";
    /** Alarm in "every N minutes" mode: wake up, let the page catch up, sleep again. */
    private static final String ACTION_CHECK = "ir.panbeh.app.CHECK";
    private static final long CHECK_MAX_MS = 45_000, WATCHDOG_MS = 60_000;
    /** Same origin as Capacitor's local server, so both WebViews share IndexedDB and localStorage. */
    private static final String ORIGIN = "https://localhost";

    static SyncService instance;
    /** True between MainActivity.onCreate and onDestroy: its WebView is the one syncing. */
    static boolean activityAlive;
    /** In a call: the service also holds the mic (and camera) so they keep working in the background. */
    private static boolean inCall, inVideoCall;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView headless;
    private final Runnable startHeadless = this::createHeadless;
    /** Real-time mode keeps the CPU awake so the page's long-poll and timers never freeze; interval mode holds one per check. */
    private PowerManager.WakeLock realtimeLock, checkLock;
    private final Runnable endCheck = () -> { if (checkLock != null && checkLock.isHeld()) checkLock.release(); };
    /** While the CPU is awake: nudge the page every minute; it reconnects if its /sync stalled (see kick in src/matrix.ts). */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            kick();
            main.postDelayed(this, WATCHDOG_MS);
        }
    };
    /** Last sync state the page reported (SyncState in matrix-js-sdk), and whether Android sees a network. */
    private String syncState;
    private boolean online = true;
    /** The default network: a different one means the page's pending /sync went out on a network that's gone. */
    private Network current;
    private boolean foreground;
    private ConnectivityManager.NetworkCallback network;

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    static void start(Context ctx) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply();
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, SyncService.class));
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException from the background
            Log.w(TAG, "could not start", e);
        }
    }

    /** Minutes between background checks; 0 = real-time. */
    static int interval(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_INTERVAL, 0);
    }

    static void setInterval(Context ctx, int minutes) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_INTERVAL, Math.max(0, minutes)).apply();
    }

    static void stop(Context ctx) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        ctx.stopService(new Intent(ctx, SyncService.class));
    }

    /** Call started or ended in the app. Started from the foreground, as Android requires for mic/camera services. */
    static void setCall(Context ctx, boolean on, boolean video) {
        inCall = on;
        inVideoCall = on && video;
        if (on) {
            try {
                ContextCompat.startForegroundService(ctx, new Intent(ctx, SyncService.class));
            } catch (RuntimeException e) {
                Log.w(TAG, "could not start for call", e);
            }
        } else if (instance != null) {
            if (isEnabled(instance)) instance.goForeground(); // back to plain syncing
            else instance.stopSelf();
        }
    }

    /** A call notification button for the headless page. */
    void deliverCall(org.json.JSONObject a) {
        main.post(() -> {
            if (headless != null) headless.evaluateJavascript("window.panbehCallAction && panbehCallAction(" + a + ")", null);
        });
    }

    /** The activity is about to load its own WebView: the headless one must be gone first. */
    static void onActivityCreated() {
        activityAlive = true;
        if (instance != null) instance.destroyHeadless();
    }

    static void onActivityDestroyed() {
        activityAlive = false;
        if (inCall) setCall(instance, false, false); // the call died with the page
        // a moment for the activity's page to unload before the next client opens the same stores
        if (instance != null) instance.scheduleHeadless();
    }

    /** The page's sync state changed (every ClientEvent.Sync). Updates the notification and ends an interval check once caught up. */
    static void reportSync(String state) {
        SyncService s = instance;
        if (s != null) s.main.post(() -> s.onSync(state));
    }

    private void onSync(String state) {
        if ("SYNCING".equals(state) && checkLock != null && checkLock.isHeld()) {
            main.removeCallbacks(endCheck);
            main.postDelayed(endCheck, 5000); // a moment for the batch's notifications to be posted
        }
        if (state.equals(syncState)) return;
        syncState = state;
        refreshNotification();
    }

    /** Asks whichever page is syncing to reconnect now, instead of waiting on JS timers that froze while the phone slept. */
    void kick() {
        kick(false);
    }

    /** newNetwork: drop the pending /sync even if it's young; it was sent on the old network and will never answer. */
    void kick(boolean newNetwork) {
        main.post(() -> {
            WebView w = headless != null ? headless : activityAlive ? PanbehPlugin.webView() : null;
            if (w != null) w.evaluateJavascript("window.panbehKick && panbehKick(" + newNetwork + ")", null);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notifier.createChannels(this);
        network = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                main.post(() -> {
                    boolean changed = !online || !n.equals(current);
                    online = true;
                    current = n;
                    refreshNotification();
                    kick(changed);
                });
            }

            @Override
            public void onLost(Network n) {
                main.post(() -> {
                    if (!n.equals(current)) return; // an old default network going away after the switch
                    online = false;
                    refreshNotification();
                });
            }
        };
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            current = cm.getActiveNetwork();
            online = current != null;
            cm.registerDefaultNetworkCallback(network);
        } catch (RuntimeException e) { // too many callbacks, or no permission
            Log.w(TAG, "no network callback", e);
            network = null;
        }
        main.postDelayed(watchdog, WATCHDOG_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean check = intent != null && ACTION_CHECK.equals(intent.getAction());
        // an alarm may not start a foreground service from the background; we already are one unless the system restarted us
        if (!check || !foreground) goForeground();
        if (!isEnabled(this) && !inCall) { // restarted by the system after being switched off
            stopSelf();
            return START_NOT_STICKY;
        }
        applyMode();
        if (check) {
            Log.i(TAG, "check");
            checkLock().acquire(CHECK_MAX_MS);
            main.removeCallbacks(endCheck);
            kick();
        }
        if (!activityAlive) scheduleHeadless();
        return START_STICKY;
    }

    private PowerManager.WakeLock checkLock() {
        if (checkLock == null) {
            checkLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panbeh:check");
            checkLock.setReferenceCounted(false);
        }
        return checkLock;
    }

    /** Real-time: hold the CPU. Every N minutes: no lock, an alarm instead (Doze stretches it to ~9-15 min at most). */
    @SuppressLint("WakelockTimeout") // held for as long as the user wants real-time messages
    private void applyMode() {
        int minutes = isEnabled(this) ? interval(this) : 0;
        boolean realtime = isEnabled(this) && minutes == 0;
        if (realtime) {
            if (realtimeLock == null) {
                realtimeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panbeh:sync");
                realtimeLock.setReferenceCounted(false);
            }
            if (!realtimeLock.isHeld()) realtimeLock.acquire();
        } else if (realtimeLock != null && realtimeLock.isHeld()) realtimeLock.release();
        AlarmManager am = getSystemService(AlarmManager.class);
        if (minutes > 0) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, checkIntent());
        else am.cancel(checkIntent());
    }

    private PendingIntent checkIntent() {
        Intent i = new Intent(this, SyncService.class).setAction(ACTION_CHECK);
        return PendingIntent.getService(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** «متصل» / «در حال اتصال…» / «قطع؛ تلاش دوباره…» in the permanent notification. */
    private int statusText() {
        if (!online || "ERROR".equals(syncState)) return R.string.service_offline;
        if ("SYNCING".equals(syncState) || "PREPARED".equals(syncState)) return R.string.service_title;
        return R.string.service_connecting;
    }

    @SuppressLint("MissingPermission") // without it the notification just doesn't update
    private void refreshNotification() {
        if (!foreground) return;
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException e) {
            // notifications refused
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(getString(inCall ? R.string.call_ongoing : statusText()))
            .setContentText(inCall ? null : getString(R.string.service_text))
            .setPriority(inCall ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build();
    }

    private void goForeground() {
        Notification n = buildNotification();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING : 0;
        int call = !inCall || Build.VERSION.SDK_INT < 30 ? 0
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | (inVideoCall ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0);
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type | call);
            foreground = true;
        } catch (RuntimeException e) { // e.g. camera permission refused: keep the mic at least
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type | (call & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
                foreground = true;
            } catch (RuntimeException e2) {
                Log.w(TAG, "startForeground failed", e2);
                stopSelf();
            }
        }
    }

    private void scheduleHeadless() {
        main.removeCallbacks(startHeadless);
        main.postDelayed(startHeadless, 1500);
    }

    @SuppressLint({ "SetJavaScriptEnabled", "JavascriptInterface" })
    private void createHeadless() {
        if (headless != null || activityAlive) return;
        Log.i(TAG, "starting headless sync");
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain("localhost")
            .addPathHandler("/", new PublicAssets(this))
            .build();
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !ORIGIN.equals(request.getUrl().getScheme() + "://" + request.getUrl().getHost());
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { // adb logcat -s PanbehSync; the SDK's debug chatter stays out
                if (m.messageLevel() != ConsoleMessage.MessageLevel.DEBUG) Log.i(TAG, m.message());
                return true;
            }
        });
        w.addJavascriptInterface(new HeadlessBridge(), "PanbehAndroid");
        w.loadUrl(ORIGIN + "/?headless=1");
        headless = w;
    }

    private void destroyHeadless() {
        main.removeCallbacks(startHeadless);
        if (headless == null) return;
        Log.i(TAG, "stopping headless sync");
        headless.stopLoading();
        headless.loadUrl("about:blank");
        headless.destroy();
        headless = null;
    }

    @Override
    public void onDestroy() {
        destroyHeadless();
        main.removeCallbacks(watchdog);
        main.removeCallbacks(endCheck);
        if (network != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
        if (realtimeLock != null && realtimeLock.isHeld()) realtimeLock.release();
        if (checkLock != null && checkLock.isHeld()) checkLock.release();
        getSystemService(AlarmManager.class).cancel(checkIntent());
        foreground = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** window.PanbehAndroid in the headless page; same calls as the Capacitor plugin (see src/native.ts). */
    public class HeadlessBridge {
        @JavascriptInterface
        public void showNotification(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Notifier.show(SyncService.this, o.getString("roomId"), o.getString("title"), o.getString("body"),
                    o.optString("icon", null), o.optBoolean("sound", true));
            } catch (JSONException e) {
                Log.w(TAG, "bad notification", e);
            }
        }

        @JavascriptInterface
        public void cancel(String roomId) {
            Notifier.cancel(SyncService.this, roomId);
        }

        @JavascriptInterface
        public void showCall(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Notifier.showCall(SyncService.this, o.getString("roomId"), o.getString("eventId"), o.optString("caller"),
                    o.optBoolean("video"), o.optString("icon", null), o.optLong("timeout", 60000));
            } catch (JSONException e) {
                Log.w(TAG, "bad call", e);
            }
        }

        @JavascriptInterface
        public void cancelCall(String roomId) {
            Notifier.cancelCall(SyncService.this, roomId);
        }

        @JavascriptInterface
        public void syncState(String state) {
            reportSync(state);
        }

        @JavascriptInterface
        public void stopService() {
            main.post(() -> SyncService.stop(SyncService.this));
        }
    }

    /** Serves the bundled web app (assets/public) at the site root, like Capacitor's local server. */
    private static class PublicAssets implements WebViewAssetLoader.PathHandler {
        private final Context ctx;

        PublicAssets(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public WebResourceResponse handle(String path) {
            if (path.isEmpty() || path.endsWith("/")) path += "index.html";
            try {
                InputStream in = ctx.getAssets().open("public/" + path);
                return new WebResourceResponse(mime(path), null, in);
            } catch (IOException e) {
                return null;
            }
        }

        private static String mime(String p) {
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".wasm")) return "application/wasm";
            if (p.endsWith(".json")) return "application/json";
            if (p.endsWith(".woff2")) return "font/woff2";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            return "application/octet-stream";
        }
    }
}
