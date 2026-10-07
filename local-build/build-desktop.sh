#!/usr/bin/env bash
# Runs inside the electron-builder (wine) container. $1 = linux | windows | all. Output goes to /out.
set -euo pipefail
target="${1:-all}"

rsync -a --delete --exclude node_modules --exclude dist --exclude release --exclude .git --exclude android /src/ /work/
cd /work

npm ci
[ "${SKIP_TESTS:-0}" = 1 ] || npm test
npm run build

flags=()
case "$target" in
  linux)   flags=(--linux) ;;
  windows) flags=(--win) ;;
  all)     flags=(--linux --win) ;;
  *) echo "unknown target $target" >&2; exit 1 ;;
esac

# unsigned: no certificate in a local build
CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder "${flags[@]}" --publish never \
  ${VERSION:+-c.extraMetadata.version="$VERSION"}

cp release/Panbeh-* release/latest*.yml /out/ 2>/dev/null || true
[ -z "${HOST_UID:-}" ] || chown -R "$HOST_UID:${HOST_GID:-$HOST_UID}" /out
echo ">> Done:"; ls /out
