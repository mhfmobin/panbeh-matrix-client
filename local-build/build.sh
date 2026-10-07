#!/usr/bin/env bash
# Local build helper: APK, Linux (AppImage + deb) and Windows (NSIS installer) builds, all inside Docker.
# Only Docker (with the compose plugin) is needed on your machine. Output lands in local-build/out/.
set -euo pipefail
cd "$(dirname "$0")"

usage() {
  cat <<USAGE
Usage: ./build.sh <target> [options]

Targets:
  apk        Android APK                    -> out/panbeh.apk
  linux      AppImage + deb                 -> out/Panbeh-linux-*
  windows    NSIS installer (via wine)      -> out/Panbeh-windows-setup.exe
  desktop    linux + windows
  all        apk + linux + windows
  clean      remove out/ and the cache volumes
  shell      (apk|desktop) open a shell in a build container for debugging

Options:
  --skip-tests          don't run npm test first
  --version X.Y.Z       desktop version (default: from package.json)
  --version-name/--version-code   Android versionName / versionCode
  --keystore FILE       sign the APK with this keystore (needs --store-pass, --alias, --key-pass;
                        or export ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD)
  --store-pass P  --alias A  --key-pass P

Without --keystore the APK uses the debug key. macOS builds aren't possible in Docker (need a Mac).
USAGE
}

[ $# -ge 1 ] || { usage; exit 1; }
target=$1; shift
extra=${1:-}; [[ $extra == -* ]] && extra=""; [ -z "$extra" ] || shift

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose plugin not found" >&2; exit 1; }

export HOST_UID=$(id -u) HOST_GID=$(id -g)
while [ $# -gt 0 ]; do
  case $1 in
    --skip-tests) export SKIP_TESTS=1 ;;
    --version) export VERSION=$2; shift ;;
    --version-name) export VERSION_NAME=$2; shift ;;
    --version-code) export VERSION_CODE=$2; shift ;;
    --keystore)
      ks=$(realpath "$2"); [ -f "$ks" ] || { echo "no such file: $ks" >&2; exit 1; }
      export KEYSTORE_HOST_PATH=$ks KEYSTORE_PATH=/keystore/release.jks; shift ;;
    --store-pass) export ANDROID_KEYSTORE_PASSWORD=$2; shift ;;
    --alias) export ANDROID_KEY_ALIAS=$2; shift ;;
    --key-pass) export ANDROID_KEY_PASSWORD=$2; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option $1" >&2; usage; exit 1 ;;
  esac
  shift
done

mkdir -p out
[ -e .no-keystore ] || : > .no-keystore # placeholder mount when no keystore is given
run() { docker compose run --rm --build "$@"; }

case $target in
  apk)     run android ;;
  linux)   run desktop linux ;;
  windows) run desktop windows ;;
  desktop) run desktop all ;;
  all)     run android; run desktop all ;;
  clean)   docker compose down -v --remove-orphans; rm -rf out ;;
  shell)   run --entrypoint bash "${extra:-desktop}" ;;
  -h|--help|help) usage ;;
  *) usage; exit 1 ;;
esac

echo; echo "Output in $(pwd)/out:"; ls -1 out 2>/dev/null || true
