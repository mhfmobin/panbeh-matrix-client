package ir.panbeh.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import java.util.List;

public class MainActivity extends BridgeActivity {
    /** Resumed and on screen: no notification for the chat being looked at. */
    static boolean visible;
    /** A video call is on screen: leaving the app shrinks it to picture-in-picture. Set by PanbehPlugin.setPip. */
    static boolean pipAllowed;
    /** The emoji panel is standing in for the keyboard (Composer.tsx). Set by PanbehPlugin.holdIme. */
    static volatile boolean holdIme;

    private static PictureInPictureParams.Builder pipParams() {
        return new PictureInPictureParams.Builder().setAspectRatio(new Rational(9, 16));
    }

    /** Android 12+ enters PiP by itself when we're left (smoother with gesture navigation); older ones go in onUserLeaveHint. */
    static void updatePip(Activity a) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            a.setPictureInPictureParams(pipParams().setAutoEnterEnabled(pipAllowed).build());
        } catch (RuntimeException e) {
            // no PiP on this device
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!pipAllowed || Build.VERSION.SDK_INT < 26 || Build.VERSION.SDK_INT >= 31) return;
        try {
            enterPictureInPictureMode(pipParams().build());
        } catch (RuntimeException e) {
            // no PiP on this device, or turned off for the app
        }
    }

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
        // no grey ▶ placeholder on a <video> that has no frame yet (camera starting, upload previews): a transparent one
        w.setWebChromeClient(new BridgeWebChromeClient(bridge) {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        // keep the renderer at full priority while hidden, so the sync loop isn't starved in the background
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (SyncService.isEnabled(this)) SyncService.start(this);
        if (call) showOverLockScreen(this, true);
        holdPageForKeyboard();
    }

    /** Capacitor pads the decor view by the keyboard as soon as it starts to rise, so the page shrinks at once and a bare
     *  strip shows under it until the keyboard arrives. While the emoji panel is open in the keyboard's place, that
     *  padding waits for the end of the animation: the keyboard slides up over the panel, then the panel goes. */
    private void holdPageForKeyboard() {
        View decor = getWindow().getDecorView();
        ViewCompat.setWindowInsetsAnimationCallback(decor, new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            int before, after = -1;

            @Override
            public void onPrepare(@NonNull WindowInsetsAnimationCompat a) {
                before = decor.getPaddingBottom(); // the new insets (and padding) come after this, before onStart
            }

            @NonNull
            @Override
            public WindowInsetsAnimationCompat.BoundsCompat onStart(@NonNull WindowInsetsAnimationCompat a, @NonNull WindowInsetsAnimationCompat.BoundsCompat b) {
                int now = decor.getPaddingBottom();
                if (holdIme && (a.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0 && now > before) {
                    after = now;
                    decor.setPadding(decor.getPaddingLeft(), decor.getPaddingTop(), decor.getPaddingRight(), before);
                }
                return b;
            }

            @NonNull
            @Override
            public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets, @NonNull List<WindowInsetsAnimationCompat> running) {
                return insets;
            }

            @Override
            public void onEnd(@NonNull WindowInsetsAnimationCompat a) {
                if (after < 0) return;
                decor.setPadding(decor.getPaddingLeft(), decor.getPaddingTop(), decor.getPaddingRight(), after);
                after = -1;
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (isCall(intent)) showOverLockScreen(this, true);
        super.onNewIntent(intent);
    }

    private boolean isCall(Intent i) {
        return Notifier.isTrustedCall(this, i);
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
