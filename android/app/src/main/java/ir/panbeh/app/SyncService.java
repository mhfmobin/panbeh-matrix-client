package ir.panbeh.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.app.NotificationCompat;
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
    private static final String PREFS = "panbeh", PREF_ENABLED = "background";
    /** Same origin as Capacitor's local server, so both WebViews share IndexedDB and localStorage. */
    private static final String ORIGIN = "https://localhost";

    static SyncService instance;
    /** True between MainActivity.onCreate and onDestroy: its WebView is the one syncing. */
    static boolean activityAlive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView headless;
    private final Runnable startHeadless = this::createHeadless;

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

    static void stop(Context ctx) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        ctx.stopService(new Intent(ctx, SyncService.class));
    }

    /** The activity is about to load its own WebView: the headless one must be gone first. */
    static void onActivityCreated() {
        activityAlive = true;
        if (instance != null) instance.destroyHeadless();
    }

    static void onActivityDestroyed() {
        activityAlive = false;
        // a moment for the activity's page to unload before the next client opens the same stores
        if (instance != null) instance.scheduleHeadless();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notifier.createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!isEnabled(this)) { // restarted by the system after being switched off
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!activityAlive) scheduleHeadless();
        return START_STICKY;
    }

    private void goForeground() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_panbeh)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground failed", e);
            stopSelf();
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
