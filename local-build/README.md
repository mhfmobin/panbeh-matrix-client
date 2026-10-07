# Local builds

Builds the APK, Linux and Windows apps on your own machine, inside Docker, so you don't install
a JDK, the Android SDK, wine, etc. Only **Docker with the compose plugin** is required.

```sh
cd local-build
./build.sh apk        # out/panbeh.apk
./build.sh linux      # out/Panbeh-linux-x86_64.AppImage, out/Panbeh-linux-amd64.deb
./build.sh windows    # out/Panbeh-windows-setup.exe  (cross-built with wine)
./build.sh all        # everything
./build.sh --help
```

The first run downloads the images and SDKs (several GB, 10+ minutes); npm, gradle, electron and wine
caches are kept in Docker volumes so later runs are fast. `./build.sh clean` removes them and `out/`.

Useful options: `--skip-tests`, `--version 1.2.3` (desktop), `--version-name` / `--version-code` (APK).

## Signing the APK

Without a keystore the APK is signed with the debug key: fine for sideloading, but it can't update an app
signed with your release key. To use your release key (see `ANDROID.md`):

```sh
./build.sh apk --keystore ~/release.jks --store-pass ... --alias ... --key-pass ...
```

## Notes

- Windows/Linux installers are unsigned, same as CI without secrets.
- macOS can't be built in Docker (Apple requires macOS); use the CI workflow or run `npm run desktop:dist` on a Mac.
- The repo is mounted read-only and copied into the container, so your `node_modules` and the build never touch each other.
- Windows-on-Linux builds use `electronuserland/builder:wine` (amd64); on Apple Silicon/ARM hosts Docker runs it under emulation, which is slow.
