package ir.panbeh.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import androidx.activity.result.ActivityResult;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.farsitel.bazaar.IAutoUpdateCheckService;
import com.farsitel.bazaar.IUpdateCheckService;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Native side of src/native.ts for the app's own WebView. */
@CapacitorPlugin(name = "Panbeh", permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications"),
    @Permission(strings = { Manifest.permission.CAMERA }, alias = "camera") })
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

    /** A URI another app handed us (share, clipboard) that's safe to read: content:// from someone else's provider only.
     *  file:// paths and our own providers are read with our permissions, so another app could pick Panbeh's private
     *  files (the session token, the crypto store) as the attachment (F-04). */
    private boolean foreignContent(Uri u) {
        String auth = u.getAuthority();
        if (!ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) || auth == null) return false;
        ProviderInfo p = getContext().getPackageManager().resolveContentProvider(auth, 0);
        return p == null || !getContext().getPackageName().equals(p.packageName); // null: another app's, hidden by package visibility
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
            if (u == null || !foreignContent(u)) continue;
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
        return deliver("callAction", a);
    }

    /** An event for the app's page (callAction, notifyAction) if it's running and listening. */
    static boolean deliver(String event, JSObject a) {
        PanbehPlugin p = instance;
        if (p == null || !SyncService.activityAlive || !p.hasListeners(event)) return false;
        p.notifyListeners(event, a);
        return true;
    }

    /** Answer / open from the call notification: stop the native ring, the page takes over. */
    private boolean dispatchCall(Intent intent) {
        if (!Notifier.isTrustedCall(getContext(), intent)) return false; // not from our notification: ignored (F-01)
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

    // ---------- app lock: fingerprint / face ----------

    /** BIOMETRIC_WEAK: face unlock on most phones counts too; this only lifts a UI lock (no key is released). */
    private static final int BIO = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    @PluginMethod
    public void biometricAvailable(PluginCall call) {
        call.resolve(new JSObject().put("available", BiometricManager.from(getContext()).canAuthenticate(BIO) == BiometricManager.BIOMETRIC_SUCCESS));
    }

    /** Mobile data or a metered Wi‑Fi: the page's media auto-download rules for that network apply. */
    @PluginMethod
    public void metered(PluginCall call) {
        ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
        call.resolve(new JSObject().put("metered", cm != null && cm.isActiveNetworkMetered()));
    }

    /** Shows the system prompt; resolves { ok } — false when cancelled, failed out, or "use PIN" was pressed. */
    @PluginMethod
    public void biometricUnlock(PluginCall call) {
        FragmentActivity a = getActivity();
        a.runOnUiThread(() -> {
            BiometricPrompt p = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { call.resolve(new JSObject().put("ok", true)); }
                @Override
                public void onAuthenticationError(int code, CharSequence msg) { call.resolve(new JSObject().put("ok", false)); }
                // onAuthenticationFailed: one wrong finger; the prompt stays up for another try
            });
            p.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(call.getString("title", ""))
                .setNegativeButtonText(call.getString("cancel", ""))
                .setAllowedAuthenticators(BIO)
                .build());
        });
    }

    /** The page's language, for what the app shows itself: notifications and their channels' names. */
    @PluginMethod
    public void setLanguage(PluginCall call) {
        getContext().getSharedPreferences("panbeh", Context.MODE_PRIVATE).edit().putString("lang", call.getString("lang")).apply();
        Notifier.createChannels(getContext()); // renames them
        call.resolve();
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

    /** The gallery, to send photos from: Android 13+'s photo picker (opens the photo grid directly, no permission needed),
     *  else the gallery app via ACTION_PICK. Resolves { files: [{ uri, name, type }] } — empty when backed out. */
    @PluginMethod
    public void pickImages(PluginCall call) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (call.getBoolean("multiple", true)) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            if (call.getBoolean("multiple", true)) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(call, i, "galleryPicked");
        } catch (RuntimeException e) { // no gallery or picker on the device
            call.reject("no picker");
        }
    }

    /** The photos picked from the gallery: their content URIs, names and types for the page, which reads them through
     *  Capacitor's content:// bridge (like shared files) while the picker's read grant still holds. */
    @ActivityCallback
    private void galleryPicked(PluginCall call, ActivityResult result) {
        Intent d = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || d == null) { call.resolve(new JSObject().put("files", new JSArray())); return; }
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = d.getClipData(); // multi-select
        if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) { Uri u = clip.getItemAt(k).getUri(); if (u != null) uris.add(u); }
        else if (d.getData() != null) uris.add(d.getData());
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
            files.put(new JSObject().put("uri", u.toString()).put("name", name == null ? "photo.jpg" : name).put("type", type == null ? "" : type));
        }
        call.resolve(new JSObject().put("files", files));
    }

    // ---------- camera: capture a photo to send ----------

    /** The full-size capture is written into the cache under a fixed name (a later capture overwrites it). */
    private static File cameraFile(Context ctx) { return new File(ctx.getCacheDir(), "camera.jpg"); }

    /** The camera app, to take a photo to send: ACTION_IMAGE_CAPTURE with a cache file as the output, so the full-size
     *  picture comes back (a thumbnail would be all the intent gives otherwise). Resolves { files: [{ uri, name, type }] }
     *  — empty when backed out. */
    @PluginMethod
    public void pickCamera(PluginCall call) {
        // the app declares CAMERA (for calls), so the intent is only allowed once the permission is granted
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraAllowed");
            return;
        }
        startCapture(call);
    }

    @PermissionCallback
    private void cameraAllowed(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) startCapture(call);
        else call.reject("no camera");
    }

    private void startCapture(PluginCall call) {
        Context ctx = getContext();
        File f = cameraFile(ctx);
        f.delete(); // a previous capture that was never sent
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(call, i, "cameraDone");
        } catch (RuntimeException e) { // no camera app on the device
            call.reject("no camera");
        }
    }

    /** The capture: back to the page as a provider URI it reads like a picked photo (the file stays for the send). */
    @ActivityCallback
    private void cameraDone(PluginCall call, ActivityResult result) {
        File f = cameraFile(getContext());
        if (result.getResultCode() != Activity.RESULT_OK || !f.exists() || f.length() == 0) {
            f.delete();
            call.resolve(new JSObject().put("files", new JSArray()));
            return;
        }
        JSArray files = new JSArray();
        files.put(new JSObject().put("uri", FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f).toString())
            .put("name", f.getName()).put("type", "image/jpeg"));
        call.resolve(new JSObject().put("files", files));
    }

    // ---------- clipboard: sending a copied image ----------

    /** The image on the clipboard (a screenshot, "copy image" in another app), as content:// URIs the page reads like
     *  picked photos: the WebView carries no image on the paste event, so this is how a copied image gets sent.
     *  Resolves { files: [{ uri, name, type }] } — empty when the clipboard holds no image. */
    @PluginMethod
    public void clipboardImage(PluginCall call) {
        JSArray files = new JSArray();
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            ContentResolver cr = getContext().getContentResolver();
            if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) {
                ClipData.Item it = clip.getItemAt(k);
                Uri u = it.getUri();
                if (u == null && it.getIntent() != null) u = it.getIntent().getParcelableExtra(Intent.EXTRA_STREAM); // "Copy" that carries a stream
                if (u == null || !foreignContent(u)) continue;
                String type = cr.getType(u);
                if (type == null || !type.startsWith("image/")) continue;
                String name = u.getLastPathSegment();
                try (Cursor c = cr.query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                } catch (RuntimeException e) {
                    // no name column: keep the path segment
                }
                if (name == null || name.indexOf('.') < 0) name = "clipboard." + (type.equals("image/jpeg") ? "jpg" : type.substring(6));
                files.put(new JSObject().put("uri", u.toString()).put("name", name).put("type", type));
            }
        } catch (RuntimeException e) {
            // a URI we can't read (some providers, some OEMs): it's sent as nothing
        }
        call.resolve(new JSObject().put("files", files));
    }

    /** A file being written: opened by saveOpen, filled by saveChunk, ended by saveClose or saveAbort. */
    private static class Save { OutputStream out; Uri uri; File file; boolean picked, pending; }
    private final ConcurrentHashMap<String, Save> saves = new ConcurrentHashMap<>(); // savePicked runs on the main thread, the rest on the plugin thread

    /** Opens a downloaded file for writing: Android's "Save as" screen when `ask`, else Downloads (Android 10+: MediaStore, no permission).
     *  Resolves { id, picked }, or { id: null } when the user backs out of "Save as". */
    @PluginMethod
    public void saveOpen(PluginCall call) {
        String name = call.getString("name", "file"), mime = call.getString("mime", "application/octet-stream");
        // the name's extension decides the type: given another one (a sender's wrong mimetype), Android appends its own (photo.jpg.txt)
        String byName = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        if (name.contains(".") && byName != null) mime = byName;
        if (call.getBoolean("ask", false)) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(call, i, "savePicked");
            return;
        }
        Save sv = new Save();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1); // hidden from other apps until it's complete
                sv.uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (sv.uri == null) throw new IOException("can't create the file");
                sv.pending = true;
                sv.out = getContext().getContentResolver().openOutputStream(sv.uri);
            } else {
                // ponytail: Android 7-9 save in the app's own Downloads (Android/data/...), no storage permission; "ask where to save" reaches shared folders
                sv.file = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                sv.out = new FileOutputStream(sv.file);
            }
            if (sv.out == null) throw new IOException("can't open the file");
        } catch (IOException | RuntimeException e) {
            drop(sv);
            call.reject(e.getMessage());
            return;
        }
        call.resolve(opened(sv));
    }

    @ActivityCallback
    private void savePicked(PluginCall call, ActivityResult result) {
        Uri uri = result.getData() != null ? result.getData().getData() : null;
        if (result.getResultCode() != Activity.RESULT_OK || uri == null) { call.resolve(new JSObject().put("id", JSObject.NULL)); return; }
        Save sv = new Save();
        sv.uri = uri;
        sv.picked = true;
        try { // so tapping the file again can still open it after a restart
            getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) { } // provider didn't offer it: it re-downloads next time
        try {
            sv.out = getContext().getContentResolver().openOutputStream(uri, "wt");
            if (sv.out == null) throw new IOException("can't open the file");
        } catch (IOException | RuntimeException e) {
            drop(sv);
            call.reject(e.getMessage());
            return;
        }
        call.resolve(opened(sv));
    }

    private JSObject opened(Save sv) {
        String id = UUID.randomUUID().toString();
        saves.put(id, sv);
        return new JSObject().put("id", id).put("picked", sv.picked);
    }

    @PluginMethod
    public void saveChunk(PluginCall call) {
        Save sv = saves.get(call.getString("id", ""));
        String data = call.getString("data");
        if (sv == null || data == null) { call.reject("no such file"); return; }
        try {
            sv.out.write(Base64.decode(data, Base64.DEFAULT));
            call.resolve();
        } catch (IOException | RuntimeException e) {
            saves.values().remove(sv);
            drop(sv);
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void saveClose(PluginCall call) {
        Save sv = saves.remove(call.getString("id", ""));
        if (sv == null) { call.reject("no such file"); return; }
        try {
            sv.out.close();
            if (sv.pending) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContext().getContentResolver().update(sv.uri, v, null, null);
            }
            String uri = (sv.uri != null ? sv.uri : FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", sv.file)).toString();
            // ponytail: grows by one short string per saved file, never pruned; trim it if anyone saves thousands
            Set<String> saved = new HashSet<>(savedPrefs().getStringSet("uris", new HashSet<>()));
            saved.add(uri);
            savedPrefs().edit().putStringSet("uris", saved).apply();
            call.resolve(new JSObject().put("uri", uri));
        } catch (IOException | RuntimeException e) {
            drop(sv);
            call.reject(e.getMessage());
        }
    }

    /** Cancelled: close and delete the partial file. */
    @PluginMethod
    public void saveAbort(PluginCall call) {
        Save sv = saves.remove(call.getString("id", ""));
        if (sv != null) drop(sv);
        call.resolve();
    }

    /** Where updateDownload puts the new APK; BootReceiver deletes it once the app is replaced. */
    static File updateApk(Context ctx) { return new File(ctx.getCacheDir(), "update.apk"); }

    /** Downloads a release APK into the cache, sending "updateProgress" { percent }. Our GitHub releases only. */
    @PluginMethod
    public void updateDownload(PluginCall call) {
        String url = call.getString("url", "");
        if (!url.startsWith("https://github.com/mhfmobin/panbeh-matrix-client/releases/download/")) { call.reject("not a release"); return; }
        new Thread(() -> {
            File part = new File(getContext().getCacheDir(), "update.apk.part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); // follows GitHub's redirect to its download host
                long size = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int p = size > 0 ? (int) (got * 100 / size) : 0;
                        if (p != last) notifyListeners("updateProgress", new JSObject().put("percent", last = p));
                    }
                }
                if (!part.renameTo(updateApk(getContext()))) throw new IOException("can't save the update");
                call.resolve();
            } catch (IOException | RuntimeException e) {
                part.delete();
                call.reject(e.getMessage());
            }
        }).start();
    }

    /** Hands the downloaded APK to Android's installer, which asks the user (and, the first time, to allow installs from Panbeh). */
    @PluginMethod
    public void updateInstall(PluginCall call) {
        File f = updateApk(getContext());
        if (!f.exists()) { call.reject("gone"); return; }
        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
        try {
            getContext().startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("no installer");
        }
    }

    // ---------- Cafe Bazaar build: Bazaar's update-check services (src/main/aidl) instead of updateDownload/updateInstall ----------
    private static final String BAZAAR = "com.farsitel.bazaar";

    private long versionOf(String pkg) {
        try { return PackageInfoCompat.getLongVersionCode(getContext().getPackageManager().getPackageInfo(pkg, 0)); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    private interface BazaarCall { JSObject run(IBinder b) throws Exception; }

    /** Binds one of Bazaar's services, makes one call off the main thread and unbinds. Rejects "no bazaar" when it isn't installed. */
    private void bazaarCall(PluginCall call, String action, BazaarCall f) {
        Context ctx = getContext();
        ServiceConnection con = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder b) {
                ServiceConnection self = this;
                new Thread(() -> {
                    try { call.resolve(f.run(b)); }
                    catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
                    finally { try { ctx.unbindService(self); } catch (IllegalArgumentException ignored) { } }
                }).start();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound;
        try { bound = ctx.bindService(new Intent(action).setPackage(BAZAAR), con, Context.BIND_AUTO_CREATE); }
        catch (SecurityException e) { bound = false; }
        if (!bound) {
            try { ctx.unbindService(con); } catch (IllegalArgumentException ignored) { } // released even when the bind failed
            call.reject("no bazaar");
        }
    }

    /** { newer }: Bazaar has a higher versionCode than this install. */
    @PluginMethod
    public void bazaarCheck(PluginCall call) {
        String pkg = getContext().getPackageName();
        boolean remote = versionOf(BAZAAR) >= 2400700; // getRemoteVersionCode: Bazaar 24.7+; older ones only have getVersionCode
        long own = versionOf(pkg);
        bazaarCall(call, "com.farsitel.bazaar.service.UpdateCheckService.BIND", b -> {
            IUpdateCheckService s = IUpdateCheckService.Stub.asInterface(b);
            long code = remote ? s.getRemoteVersionCode(pkg) : s.getVersionCode(pkg); // -1: no update, or not on Bazaar
            return new JSObject().put("newer", code > own);
        });
    }

    /** { on }: Bazaar auto-updates this app. Rejects on Bazaar older than 26.2, which can't. */
    @PluginMethod
    public void bazaarAutoUpdate(PluginCall call) {
        if (versionOf(BAZAAR) < 2600200) { call.reject("old bazaar"); return; }
        bazaarCall(call, "com.farsitel.bazaar.service.AutoUpdateCheckService.BIND",
            b -> new JSObject().put("on", IAutoUpdateCheckService.Stub.asInterface(b).isAutoUpdateEnabled(getContext().getPackageName())));
    }

    /** Opens Bazaar's sheet for this app: "details" (update) or "autoupdate" (turn auto-update on/off). Without Bazaar, details opens the website. */
    @PluginMethod
    public void bazaarOpen(PluginCall call) {
        String pkg = getContext().getPackageName();
        String page = "autoupdate".equals(call.getString("page")) ? "autoupdate" : "details";
        try {
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://" + page + "/modal?id=" + pkg))
                    .setPackage(BAZAAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                if (page.equals("autoupdate")) throw e;
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://cafebazaar.ir/app/?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("no bazaar");
        }
    }

    private SharedPreferences savedPrefs() { return getContext().getSharedPreferences("panbeh.saved", Context.MODE_PRIVATE); }

    /** Opens a file saveClose wrote, in the app the system picks for its type. Rejects "gone" when it was deleted or moved
     *  (the page then downloads it again) and "no app" when nothing can open it. Only URIs this app saved: the page can't
     *  hand other apps arbitrary content (e.g. its own cache through the FileProvider). */
    @PluginMethod
    public void openFile(PluginCall call) {
        String s = call.getString("uri", "");
        if (!savedPrefs().getStringSet("uris", new HashSet<>()).contains(s)) { call.reject("gone"); return; }
        Uri uri = Uri.parse(s);
        ContentResolver cr = getContext().getContentResolver();
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException();
        } catch (Exception e) {
            call.reject("gone");
            return;
        }
        String mime = cr.getType(uri);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime != null ? mime : call.getString("mime", "*/*"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("no app");
        }
    }

    /** A location in a map app: the system chooser every time (not the default app), so the user picks Neshan, Balad,
     *  Google Maps… `q=` makes them drop a pin; parentheses would end the label early. */
    @PluginMethod
    public void openGeo(PluginCall call) {
        Double lat = call.getDouble("lat"), lon = call.getDouble("lon");
        if (lat == null || lon == null) { call.reject("no position"); return; }
        String label = call.getString("label", "").replaceAll("[()]", "").trim();
        String q = lat + "," + lon + (label.isEmpty() ? "" : "(" + Uri.encode(label) + ")");
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + q));
        getContext().startActivity(Intent.createChooser(view, Notifier.localized(getContext()).getString(R.string.open_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        call.resolve();
    }

    private void drop(Save sv) {
        try { if (sv.out != null) sv.out.close(); } catch (IOException ignored) { }
        try {
            if (sv.file != null) sv.file.delete();
            else if (sv.picked) DocumentsContract.deleteDocument(getContext().getContentResolver(), sv.uri);
            else if (sv.uri != null) getContext().getContentResolver().delete(sv.uri, null, null);
        } catch (Exception ignored) { } // some providers can't delete; an empty/partial file stays
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

    /** The emoji panel stands in for the keyboard: see MainActivity.holdIme. */
    @PluginMethod
    public void holdIme(PluginCall call) {
        MainActivity.holdIme = call.getBoolean("on", false);
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
        if (!(MainActivity.visible && roomId.equals(call.getString("openRoom")))) Notifier.show(getContext(), call.getData());
        call.resolve();
    }

    /** A message was deleted: out of its chat's notification. */
    @PluginMethod
    public void unnotify(PluginCall call) {
        String roomId = call.getString("roomId"), eventId = call.getString("eventId");
        if (roomId != null && eventId != null) Notifier.remove(getContext(), roomId, eventId);
        call.resolve();
    }

    /** Android's notification settings for the app: each channel's sound, vibration and lock-screen behaviour. */
    @PluginMethod
    public void openNotifySettings(PluginCall call) {
        Intent i = Build.VERSION.SDK_INT >= 26
            ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName())
            : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        getActivity().startActivity(i);
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
