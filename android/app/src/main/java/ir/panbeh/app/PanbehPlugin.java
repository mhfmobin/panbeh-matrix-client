package ir.panbeh.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
    /** Something shared to us (SEND intent) before the page was listening. */
    private JSObject launchShare;

    static boolean isShare(Intent i) {
        return i != null && (Intent.ACTION_SEND.equals(i.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()));
    }

    /** "Share with Panbeh": text and content:// files for the page, which picks a chat and reads the files via convertFileSrc. */
    private boolean dispatchShare(Intent intent) {
        if (!isShare(intent)) return false;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        }
        ContentResolver cr = getContext().getContentResolver();
        JSArray files = new JSArray();
        for (Uri u : uris) {
            String name = u.getLastPathSegment();
            try (Cursor c = cr.query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } catch (RuntimeException e) {
                // no name column: keep the path segment
            }
            String type = cr.getType(u);
            files.put(new JSObject().put("uri", u.toString()).put("name", name == null ? "file" : name).put("type", type == null ? "" : type));
        }
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        JSObject s = new JSObject().put("files", files);
        if (text != null) s.put("text", text.toString());
        if (hasListeners("share")) notifyListeners("share", s);
        else launchShare = s;
        return true;
    }

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

    /** The app's WebView, for SyncService to nudge its sync loop. */
    static WebView webView() {
        PanbehPlugin p = instance;
        return p == null || p.getBridge() == null ? null : p.getBridge().getWebView();
    }

    @Override
    public void load() {
        instance = this;
        // cold start: the launch intent never goes through handleOnNewIntent
        Intent launch = getActivity().getIntent();
        dispatchLink(launch);
        boolean call = dispatchCall(launch);
        boolean share = dispatchShare(launch);
        if (call || share || (launch != null && launch.getData() != null && isMatrixLink(launch.getData()))) // don't replay it if recreated
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
        dispatchShare(intent);
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
    public void takeLaunchShare(PluginCall call) {
        JSObject r = launchShare != null ? launchShare : new JSObject();
        launchShare = null;
        call.resolve(r);
    }

    /** A downloaded file into Downloads (Android 10+: MediaStore, no permission). */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("name", "file"), mime = call.getString("mime", "application/octet-stream"), data = call.getString("data");
        if (data == null) { call.reject("data required"); return; }
        byte[] bytes = Base64.decode(data, Base64.DEFAULT);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("can't create the file");
                try (OutputStream out = cr.openOutputStream(uri)) { out.write(bytes); }
            } else {
                // ponytail: Android 7-9 save in the app's own Downloads (Android/data/...), no storage permission; ask for WRITE_EXTERNAL_STORAGE if users want the shared folder
                File f = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                try (OutputStream out = new FileOutputStream(f)) { out.write(bytes); }
            }
            call.resolve();
        } catch (IOException | RuntimeException e) {
            call.reject(e.getMessage());
        }
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
        String roomId = call.getString("roomId");
        SyncService.setCall(getContext(), on, video);
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        am.setMode(on ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
        if (!on && Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        getActivity().runOnUiThread(() -> MainActivity.showOverLockScreen(getActivity(), on));
        boolean starting = on && !callOn, ending = !on && callOn; // off without a call = a ring that stopped
        callOn = on;
        callVideo = video;
        if (starting) {
            startRoute(video);
            watchRoutes(true);
            if (roomId != null && Build.VERSION.SDK_INT >= 26) CallConnectionService.started(getContext(), roomId, call.getString("name"), video);
        }
        if (!on) {
            speakerOn = false;
            watchRoutes(false);
        }
        if (ending && Build.VERSION.SDK_INT >= 26) CallConnectionService.ended();
        updateProximity();
        call.resolve();
    }

    /** The page has a call under way. */
    static boolean inCall() {
        return instance != null && instance.callOn;
    }

    private AudioManager audio() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    /** Where a call starts: a headset if one is connected (Bluetooth first), else the speaker for video and the earpiece for voice. */
    private void startRoute(boolean video) {
        AudioManager am = audio();
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo best = null;
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                String kind = routeKind(d.getType());
                if ("bluetooth".equals(kind)) { best = d; break; }
                if ("wired".equals(kind)) best = d;
                else if ("speaker".equals(kind) && video && best == null) best = d;
            }
            if (best != null) am.setCommunicationDevice(best);
            else am.clearCommunicationDevice();
            speakerOn = best != null && best.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } else {
            speakerOn = video && !am.isWiredHeadsetOn();
            am.setSpeakerphoneOn(speakerOn);
        }
    }

    private AudioDeviceCallback devicesWatch;
    private AudioManager.OnCommunicationDeviceChangedListener routeWatch;
    /** Devices seen so far in this call: the callback also reports the ones already there when it's registered. */
    private final Set<Integer> knownDevices = new HashSet<>();

    /** Android 12+, during a call: a headset that connects takes the call over, and the page hears whenever the routes change. */
    private void watchRoutes(boolean on) {
        if (Build.VERSION.SDK_INT < 31) return;
        AudioManager am = audio();
        if (on && devicesWatch == null) {
            knownDevices.clear();
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) knownDevices.add(d.getId());
            devicesWatch = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    for (AudioDeviceInfo d : added) {
                        if (!knownDevices.add(d.getId())) continue;
                        String kind = routeKind(d.getType());
                        if (!"wired".equals(kind) && !"bluetooth".equals(kind)) continue;
                        for (AudioDeviceInfo c : am.getAvailableCommunicationDevices()) {
                            if (c.getId() == d.getId() || (kind.equals(routeKind(c.getType())) && c.getAddress().equals(d.getAddress()))) {
                                routeTo(c);
                                break;
                            }
                        }
                    }
                    routesChanged();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    for (AudioDeviceInfo d : removed) knownDevices.remove(d.getId());
                    routesChanged();
                }
            };
            am.registerAudioDeviceCallback(devicesWatch, null);
            routeWatch = d -> routesChanged();
            am.addOnCommunicationDeviceChangedListener(getContext().getMainExecutor(), routeWatch);
        } else if (!on && devicesWatch != null) {
            am.unregisterAudioDeviceCallback(devicesWatch);
            am.removeOnCommunicationDeviceChangedListener(routeWatch);
            devicesWatch = null;
            routeWatch = null;
        }
    }

    private void routesChanged() {
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo now = audio().getCommunicationDevice();
            speakerOn = now != null && now.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
        updateProximity();
        notifyListeners("audioRoutes", routesNow());
    }

    /** Through Telecom while it has our call (it owns routing then), else straight to the audio manager. */
    private void routeTo(AudioDeviceInfo d) {
        if (Build.VERSION.SDK_INT < 31) return;
        if (!CallConnectionService.route(d)) audio().setCommunicationDevice(d);
        speakerOn = d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    /** A video call is on screen: picture-in-picture when the app is left. */
    @PluginMethod
    public void setPip(PluginCall call) {
        MainActivity.pipAllowed = call.getBoolean("on", false);
        getActivity().runOnUiThread(() -> MainActivity.updatePip(getActivity()));
        call.resolve();
    }

    /** A call's video fills the screen: status and navigation bars hidden (a swipe shows them for a moment). */
    @PluginMethod
    public void setImmersive(PluginCall call) {
        boolean on = call.getBoolean("on", false);
        getActivity().runOnUiThread(() -> {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getActivity().getWindow(), getActivity().getWindow().getDecorView());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (on) c.hide(WindowInsetsCompat.Type.systemBars());
            else c.show(WindowInsetsCompat.Type.systemBars());
        });
        call.resolve();
    }

    /** Loudspeaker vs earpiece (or whatever headset is plugged in). */
    @PluginMethod
    public void setSpeaker(PluginCall call) {
        boolean on = call.getBoolean("on", false);
        AudioManager am = audio();
        if (Build.VERSION.SDK_INT >= 26 && CallConnectionService.speaker(on)) {
            // Telecom routes it
        } else if (Build.VERSION.SDK_INT >= 31) {
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
        call.resolve(routesNow());
    }

    private JSObject routesNow() {
        JSArray routes = new JSArray();
        int current = -1;
        if (Build.VERSION.SDK_INT >= 31) {
            AudioManager am = audio();
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                String kind = routeKind(d.getType());
                if (kind != null) routes.put(new JSObject().put("id", d.getId()).put("kind", kind).put("name", String.valueOf(d.getProductName())));
            }
            AudioDeviceInfo now = am.getCommunicationDevice();
            if (now != null) current = now.getId();
        }
        return new JSObject().put("routes", routes).put("current", current);
    }

    @PluginMethod
    public void setAudioRoute(PluginCall call) {
        int id = call.getInt("id", -1);
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : audio().getAvailableCommunicationDevices()) {
                if (d.getId() == id) { routeTo(d); break; }
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
        Integer interval = call.getInt("interval");
        if (interval != null) SyncService.setInterval(getContext(), interval);
        SyncService.start(getContext());
        call.resolve();
    }

    /** The page's sync state, for the background notification. */
    @PluginMethod
    public void syncState(PluginCall call) {
        String state = call.getString("state");
        if (state != null) SyncService.reportSync(state);
        call.resolve();
    }

    /** Developer-option diagnostics (the [net] line) into logcat: release builds don't forward the console. */
    @PluginMethod
    public void log(PluginCall call) {
        android.util.Log.i("PanbehSync", call.getString("msg", ""));
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
        r.put("interval", SyncService.interval(getContext()));
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
