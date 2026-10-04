package ir.panbeh.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
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
    /** The app's WebView, for call actions from CallReceiver. */
    static PanbehPlugin instance;
    /** Call notification button that arrived before the page was listening (cold start). */
    private JSObject launchCall;
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

    static JSObject callAction(Intent i) {
        return new JSObject()
            .put("action", i.getStringExtra(Notifier.EXTRA_CALL))
            .put("roomId", i.getStringExtra(Notifier.EXTRA_ROOM))
            .put("eventId", i.getStringExtra(Notifier.EXTRA_EVENT))
            .put("video", i.getBooleanExtra(Notifier.EXTRA_VIDEO, false));
    }

    /** Hands a call action to the app's page if it's running and listening. */
    static boolean deliverCall(JSObject a) {
        PanbehPlugin p = instance;
        if (p == null || !SyncService.activityAlive || !p.hasListeners("callAction")) return false;
        p.notifyListeners("callAction", a);
        return true;
    }

    /** Answer / open from the call notification: stop the native ring, the page takes over. */
    private boolean dispatchCall(Intent intent) {
        if (intent == null || intent.getStringExtra(Notifier.EXTRA_CALL) == null) return false;
        Notifier.cancelCall(getContext(), intent.getStringExtra(Notifier.EXTRA_ROOM));
        JSObject a = callAction(intent);
        if (!deliverCall(a)) launchCall = a;
        return true;
    }

    @Override
    public void load() {
        instance = this;
        // cold start: the launch intent never goes through handleOnNewIntent
        Intent launch = getActivity().getIntent();
        dispatchLink(launch);
        boolean call = dispatchCall(launch);
        if (call || (launch != null && launch.getData() != null && isMatrixLink(launch.getData()))) // don't replay it if recreated
            getActivity().setIntent(new Intent(getContext(), MainActivity.class));
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String room = intent.getStringExtra(Notifier.EXTRA_ROOM);
        if (dispatchCall(intent)) room = null; // the call opens its own screen
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
    public void takeLaunchCall(PluginCall call) {
        JSObject r = launchCall != null ? launchCall : new JSObject();
        launchCall = null;
        call.resolve(r);
    }

    @PluginMethod
    public void showCall(PluginCall call) {
        String roomId = call.getString("roomId"), eventId = call.getString("eventId");
        if (roomId == null || eventId == null) { call.reject("roomId and eventId required"); return; }
        Notifier.showCall(getContext(), roomId, eventId, call.getString("caller", ""), call.getBoolean("video", false),
            call.getString("icon"), call.getDouble("timeout", 60000.0).longValue());
        call.resolve();
    }

    @PluginMethod
    public void cancelCall(PluginCall call) {
        String roomId = call.getString("roomId");
        if (roomId != null) Notifier.cancelCall(getContext(), roomId);
        call.resolve();
    }

    /** Call state for the proximity sensor: a voice call held to the ear turns the screen off. */
    private boolean callOn, callVideo, speakerOn;
    private PowerManager.WakeLock proximity;

    private void updateProximity() {
        boolean want = callOn && !callVideo && !speakerOn;
        if (proximity == null) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "panbeh:call");
            proximity.setReferenceCounted(false);
        }
        if (want && !proximity.isHeld()) proximity.acquire(4 * 60 * 60 * 1000L); // a call that outlives this is forgotten
        else if (!want && proximity.isHeld()) proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
    }

    /** In a call: mic/camera foreground service, call audio mode, and staying over the lock screen until it ends. Called again when the camera toggles. */
    @PluginMethod
    public void callActive(PluginCall call) {
        boolean on = call.getBoolean("on", false), video = call.getBoolean("video", false);
        SyncService.setCall(getContext(), on, video);
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        am.setMode(on ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
        if (!on && Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        getActivity().runOnUiThread(() -> MainActivity.showOverLockScreen(getActivity(), on));
        callOn = on;
        callVideo = video;
        if (!on) speakerOn = false;
        updateProximity();
        call.resolve();
    }

    /** A video call is on screen: picture-in-picture when the app is left. */
    @PluginMethod
    public void setPip(PluginCall call) {
        MainActivity.pipAllowed = call.getBoolean("on", false);
        getActivity().runOnUiThread(() -> MainActivity.updatePip(getActivity()));
        call.resolve();
    }

    /** Loudspeaker vs earpiece (or whatever headset is plugged in). */
    @PluginMethod
    public void setSpeaker(PluginCall call) {
        boolean on = call.getBoolean("on", false);
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) {
            if (!on) am.clearCommunicationDevice();
            else for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); break; }
            }
        } else {
            am.setSpeakerphoneOn(on);
        }
        speakerOn = on;
        updateProximity();
        call.resolve();
    }

    private static String routeKind(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "wired";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "bluetooth";
            default: return null;
        }
    }

    /** Android 12+: where call audio can go, and where it goes now. Empty before 12 (the page keeps the speaker toggle). */
    @PluginMethod
    public void audioRoutes(PluginCall call) {
        JSArray routes = new JSArray();
        int current = -1;
        if (Build.VERSION.SDK_INT >= 31) {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                String kind = routeKind(d.getType());
                if (kind != null) routes.put(new JSObject().put("id", d.getId()).put("kind", kind).put("name", String.valueOf(d.getProductName())));
            }
            AudioDeviceInfo now = am.getCommunicationDevice();
            if (now != null) current = now.getId();
        }
        call.resolve(new JSObject().put("routes", routes).put("current", current));
    }

    @PluginMethod
    public void setAudioRoute(PluginCall call) {
        int id = call.getInt("id", -1);
        if (Build.VERSION.SDK_INT >= 31) {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getId() != id) continue;
                am.setCommunicationDevice(d);
                speakerOn = d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
                break;
            }
        }
        updateProximity();
        call.resolve();
    }

    /** Android 14+: full-screen ringing needs the user's OK unless the store granted it. */
    @PluginMethod
    public void requestFullScreen(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName())));
            } catch (RuntimeException e) {
                getActivity().startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName()));
            }
        }
        call.resolve();
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
        r.put("fullScreen", Build.VERSION.SDK_INT < 34 || getContext().getSystemService(NotificationManager.class).canUseFullScreenIntent());
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
