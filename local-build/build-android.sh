#!/usr/bin/env bash
# Runs inside the android container. /src is the repo (read-only), /out is where panbeh.apk lands.
set -euo pipefail

rsync -a --delete --exclude node_modules --exclude dist --exclude release --exclude .git \
  --exclude android/.gradle --exclude android/build --exclude android/app/build /src/ /work/
cd /work

npm ci
[ "${SKIP_TESTS:-0}" = 1 ] || npm test
npm run build
npx cap sync android

# optional release signing: mount a keystore and set these (see local-build/README.md)
if [ -n "${KEYSTORE_PATH:-}" ]; then
  export ANDROID_KEYSTORE_FILE="$KEYSTORE_PATH"
else
  echo ">> No keystore given: the APK is signed with the debug key (fine for sideloading, can't update a release-signed install)."
fi

cd android
chmod +x gradlew
./gradlew --no-daemon assembleRelease
cp app/build/outputs/apk/release/app-release.apk /out/panbeh.apk
[ -z "${HOST_UID:-}" ] || chown "$HOST_UID:${HOST_GID:-$HOST_UID}" /out/panbeh.apk
echo ">> Done: panbeh.apk"
