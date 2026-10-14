# Panbeh for Android (Kotlin)

A native rewrite of Panbeh, the Persian Matrix client. It uses Jetpack Compose and the
[Trixnity](https://gitlab.com/connect2x/trixnity) Matrix SDK. Everything lives in this folder: it is a self-contained
Gradle project and depends on nothing outside it.

- Android 8.0+ (minSdk 26), app id `ir.panbeh.app.kotlin`. It installs next to the WebView app (`ir.panbeh.app`).
- Same protocol strings as the web app (custom event types, account data, MSCs), so both can use the same account.
- See [ARCHITECTURE.md](ARCHITECTURE.md) for the modules and [PARITY.md](PARITY.md) for the feature checklist.

## Building

You need JDK 21 and the Android SDK (platform 37).

```sh
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests of all modules
```

Without the Android SDK or Google's Maven (e.g. in a restricted sandbox), the JVM modules still build and test:

```sh
PANBEH_NO_ANDROID=1 ./gradlew :core:test :client:test
```

The `client` tests include integration tests against a real homeserver. They run when `PANBEH_TEST_HS` points to a
server with open registration, e.g. a local Synapse with `enable_registration: true`,
`enable_registration_without_verification: true` and generous rate limits:

```sh
PANBEH_TEST_HS=http://127.0.0.1:8008 PANBEH_NO_ANDROID=1 ./gradlew :client:test
```

## CI and signing

`.github/workflows/kotlin-android.yml` and the `kotlin-apk` job in `.gitlab-ci.yml` build `Panbeh-kotlin.apk` on every
push. They use the same signing secrets as the WebView app (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; see `../ANDROID.md`). Without them, the release build is signed with the
debug key.

## OAuth login (MAS)

Login opens in the browser and comes back through `ir.panbeh.app.kotlin:/oauth`. The app registers itself as a native
client with `client_uri` `https://kotlin.app.panbeh.ir/`; MAS wants the redirect scheme to be that host reversed. To
use another domain, change `OAUTH_SCHEME` in `MainActivity.kt`, `oauthClientUri` in `PanbehApp.kt` and `oauthScheme` in
`app/build.gradle.kts`.

## Emoji data

`app/src/main/assets/emoji.json` is generated from emojibase and the CLDR Persian annotations, the same sources the
web app uses: see `tools/gen-emoji.mjs`.
