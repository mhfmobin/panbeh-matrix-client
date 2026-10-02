package ir.panbeh.app;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** Resumed and on screen: no notification for the chat being looked at. */
    static boolean visible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // before super: it loads our WebView, and the service's headless client must be gone by then
        SyncService.onActivityCreated();
        registerPlugin(PanbehPlugin.class);
        super.onCreate(savedInstanceState);
        WebView w = bridge.getWebView();
        // keep the renderer at full priority while hidden, so the sync loop isn't starved in the background
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (SyncService.isEnabled(this)) SyncService.start(this);
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
