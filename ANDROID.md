# Panbeh for Android

The Android app is the same web app inside a WebView, built with [Capacitor](https://capacitorjs.com).
The code in `src/` serves both; `src/native.ts` is the only Android-specific part on the web side,
and `android/app/src/main/java/ir/panbeh/app/` holds the native code.

## Notifications

There is no Firebase and no push server. While notifications are on, a foreground service
(`SyncService`) keeps the app running so its Matrix client keeps syncing, and new messages become
Android notifications. Since our own client sees the messages, encrypted ones show their real text.

- While the app is open or in the background, its own WebView syncs.
- After the app is swiped away, killed, or the phone restarts, the service runs the same web bundle in
  an invisible WebView (`/?headless=1`) that only syncs and notifies. When you open the app again,
  that one is stopped first, so there are never two clients on the same device.
- Android requires a permanent "Panbeh متصل است" notification for this. You can hide it under
  App info → Notifications → «اتصال پس‌زمینه».
- In Settings → اعلان‌ها, tap «اجرای بدون محدودیت در پس‌زمینه» so Doze doesn't delay messages.
  Some phones (Xiaomi, Huawei, Samsung, …) have their own extra battery killers. If notifications stop
  after a while, allow Panbeh to autostart / run unrestricted in the phone's battery settings
  (see https://dontkillmyapp.com).
- Background syncing shows you as "away", not "online".

## Getting the APK

Every push builds `panbeh.apk`. Download it from the workflow run's artifacts (Actions tab → run →
*panbeh-apk*). Pushing a tag `v1.0.0` also attaches it to a GitHub release.

### Signing key (do this once)

Without a key, CI signs with a throwaway debug key, and each build then has to be uninstalled before
the next one will install. To get updates that install over each other, create a key once and keep it safe:

```sh
keytool -genkeypair -v -keystore panbeh.jks -alias panbeh -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 panbeh.jks   # macOS: base64 -i panbeh.jks
```

Then go to GitHub → repo Settings → Secrets and variables → Actions and add:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 output |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `panbeh` |
| `ANDROID_KEY_PASSWORD` | the key password (same as the keystore password, unless you set another) |

If you lose the key, existing installs can't be updated: you'll have to uninstall and reinstall.

## Building locally

You need Node 22, JDK 21 and the Android SDK (Android Studio).

```sh
npm ci && npm run build && npx cap sync android
cd android && ./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` folder in Android Studio and press Run. Run `npm run build && npx cap sync android`
again after every change to the web code.

## OAuth login (MAS)

On Android, login opens in the browser and comes back to the app through `ir.panbeh.app:/oauth`.
The app registers itself as a native client with `client_uri` `https://app.panbeh.ir/` (MAS wants the
redirect scheme to be that host reversed). If you'd rather use a domain of your own, change
`NATIVE_REDIRECT` / `NATIVE_CLIENT_URI` in `src/matrix.ts`, the `data android:scheme` in
`AndroidManifest.xml`, and the scheme check in `PanbehPlugin.java` to match.

## Links

`matrix:` URIs and `https://matrix.to/…` links open in Panbeh (VIEW intent filters on `MainActivity`). Android can't verify `matrix.to` for us, so there is no `autoVerify`: the first time, the system offers a chooser. Test with `adb shell am start -a android.intent.action.VIEW -d "matrix:u/user:server"`, both with the app closed and running.
