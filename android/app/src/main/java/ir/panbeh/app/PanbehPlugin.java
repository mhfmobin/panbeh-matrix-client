package ir.panbeh.app;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Native side of src/native.ts for the app's own WebView. */
@CapacitorPlugin(name = "Panbeh", permissions = @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications"))
public class PanbehPlugin extends Plugin {
    /** Room from a notification tap that arrived before the page was listening. */
    private String launchRoom;
    /** matrix.to / matrix: link that arrived before the page was listening. */
    private String launchLink;

    /** A matrix.to or matrix: VIEW intent: pass the URL to the page, which parses and opens it. */
    static boolean isMatrixLink(Uri data) {
        String scheme = data.getScheme();
        return "matrix".equalsIgnoreCase(scheme) || ("https".equalsIgnoreCase(scheme) && "matrix.to".equalsIgnoreCase(data.getHost()));
    }

    /** Reads a link out of an intent; also called for the launch intent (handleOnNewIntent doesn't see it). */
    void dispatchLink(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !isMatrixLink(data)) return;
        String link = data.toString();
        if (hasListeners("openLink")) notifyListeners("openLink", new JSObject().put("link", link));
        else launchLink = link;
    }

    @Override
    public void load() {
        // cold start: the launch intent never goes through handleOnNewIntent
        Intent launch = getActivity().getIntent();
        dispatchLink(launch);
        if (launch != null && launch.getData() != null && isMatrixLink(launch.getData())) // don't replay it if recreated
            getActivity().setIntent(new Intent(getContext(), MainActivity.class));
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String room = intent.getStringExtra(Notifier.EXTRA_ROOM);
        if (room != null) {
            if (hasListeners("openRoom")) notifyListeners("openRoom", new JSObject().put("roomId", room));
            else launchRoom = room;
        }
        dispatchLink(intent);
        Uri data = intent.getData();
        if (data != null && "ir.panbeh.app".equals(data.getScheme())) {
            // OAuth redirect from the browser: hand the code to the page's existing callback handling
            String q = data.getEncodedQuery();
            getBridge().getWebView().loadUrl(getBridge().getLocalUrl() + "/" + (q != null ? "?" + q : ""));
        }
        // don't replay the tap or the single-use code if the activity is recreated
        getActivity().setIntent(new Intent(getContext(), MainActivity.class));
    }

    @PluginMethod
    public void takeLaunchRoom(PluginCall call) {
        JSObject r = new JSObject();
        r.put("roomId", launchRoom);
        launchRoom = null;
        call.resolve(r);
    }

    @PluginMethod
    public void takeLaunchLink(PluginCall call) {
        JSObject r = new JSObject();
        r.put("link", launchLink);
        launchLink = null;
        call.resolve(r);
    }

    @PluginMethod
    public void showNotification(PluginCall call) {
        String roomId = call.getString("roomId");
        if (roomId == null) { call.reject("roomId required"); return; }
        if (!(MainActivity.visible && roomId.equals(call.getString("openRoom")))) {
            Notifier.show(getContext(), roomId, call.getString("title", ""), call.getString("body", ""),
                call.getString("icon"), call.getBoolean("sound", true));
        }
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String roomId = call.getString("roomId");
        if (roomId != null) Notifier.cancel(getContext(), roomId);
        call.resolve();
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        Notifier.cancelAll(getContext());
        call.resolve();
    }

    @PluginMethod
    public void startService(PluginCall call) {
        SyncService.start(getContext());
        call.resolve();
    }

    @PluginMethod
    public void stopService(PluginCall call) {
        SyncService.stop(getContext());
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("permission", permission());
        r.put("service", SyncService.isEnabled(getContext()));
        r.put("batteryOptimized", batteryOptimized());
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotifyPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "permissionDone");
        } else {
            permissionDone(call);
        }
    }

    @PermissionCallback
    private void permissionDone(PluginCall call) {
        call.resolve(new JSObject().put("permission", permission()));
    }

    /** Opens the system dialog that lets us run unrestricted in the background (Doze would cut our network). */
    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 23 && batteryOptimized()) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(i);
            } catch (RuntimeException e) {
                getActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }
        call.resolve();
    }

    private String permission() {
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionState s = getPermissionState("notifications");
            if (s == PermissionState.GRANTED) return "granted";
            return s == PermissionState.DENIED ? "denied" : "default";
        }
        return NotificationManagerCompat.from(getContext()).areNotificationsEnabled() ? "granted" : "denied";
    }

    private boolean batteryOptimized() {
        if (Build.VERSION.SDK_INT < 23) return false;
        PowerManager pm = getContext().getSystemService(PowerManager.class);
        return !pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }
}
