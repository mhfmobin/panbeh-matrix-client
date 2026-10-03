package ir.panbeh.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** Resumed and on screen: no notification for the chat being looked at. */
    static boolean visible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // before super: it loads our WebView, and the service's headless client must be gone by then
        SyncService.onActivityCreated();
        boolean call = isCall(getIntent()); // read first: the plugin consumes the launch intent during super.onCreate
        registerPlugin(PanbehPlugin.class);
        super.onCreate(savedInstanceState);
        WebView w = bridge.getWebView();
        // the app's own sizes are the source of truth; don't let the system font scale inflate the UI
        w.getSettings().setTextZoom(100);
        // keep the renderer at full priority while hidden, so the sync loop isn't starved in the background
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (SyncService.isEnabled(this)) SyncService.start(this);
        if (call) showOverLockScreen(this, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (isCall(intent)) showOverLockScreen(this, true);
        super.onNewIntent(intent);
    }

    private static boolean isCall(Intent i) {
        return i != null && i.getStringExtra(Notifier.EXTRA_CALL) != null;
    }

    /** A ringing or answered call shows over the lock screen and wakes it; cleared when the call ends. */
    static void showOverLockScreen(Activity a, boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            a.setShowWhenLocked(on);
            a.setTurnScreenOn(on);
        } else {
            int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) a.getWindow().addFlags(f);
            else a.getWindow().clearFlags(f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        visible = false;
        // keep JS timers running while we're in the background: they drive the /sync long-poll
        if (bridge != null) bridge.getWebView().resumeTimers();
    }

    @Override
    public void onDestroy() {
        visible = false;
        // stop this page's client before the service starts its own on the same stores
        if (bridge != null) {
            bridge.getWebView().stopLoading();
            bridge.getWebView().loadUrl("about:blank");
        }
        super.onDestroy();
        if (!isChangingConfigurations()) SyncService.onActivityDestroyed();
    }
}
