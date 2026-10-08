# Panbeh (Flutter)

Panbeh is a Persian Matrix client. This folder is the native Flutter rewrite, Android first. It is self-contained so it can move to its own repository. The React app in `../src` is the reference for behaviour and wording.

License: AGPL-3.0, inherited from the [Famedly Matrix SDK](https://pub.dev/packages/matrix) and vodozemac.

## Build

```sh
flutter pub get
flutter analyze && flutter test
flutter build apk --release --split-per-abi   # needs the Android SDK and a Rust toolchain (vodozemac)
```

CI (`.github/workflows/flutter-android.yml`) builds every push that touches `flutter/`. The APKs are in the run's artifacts.
- A tag `flutter-v1.2.3` attaches them to a release.
- `main` keeps a rolling `flutter-latest` pre-release.

## App id and signing

The app id is `ir.panbeh.flutter`, so this app installs next to the Capacitor app (`ir.panbeh.app`). It is signed with the same keystore secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).

To make it replace the Capacitor app instead, change these together:
- `applicationId` in `android/app/build.gradle.kts` to `ir.panbeh.app`.
- The OAuth redirect scheme in `AndroidManifest.xml` and in `lib/matrix.dart` (`_redirect`, `_clientUri`).
- The app label.
