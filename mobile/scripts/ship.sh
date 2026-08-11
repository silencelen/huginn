#!/usr/bin/env bash
# Build and publish Huginn to the self-hosted devstore on devserv:
#   1. scripts/build.sh (tests + assemble + signature check)
#   2. scp the APK + latest.json (+ CHANGELOG.md, icon.png) to the app's dir
#   3. run the server's update-index.sh
#   4. verify the served index.json really carries this versionCode
#
# Debug ships are possible but must be asked for; release is the channel.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"
VARIANT="${1:-release}"

# Where to publish — operator-specific, never hardcoded here. Comes from the
# environment or the gitignored mobile/scripts/.shiprc (two lines:
#   DEVSERV=user@host  and  DEVSERV_DIR=/path/to/store/huginn ).
[ -f "$REPO_DIR/mobile/scripts/.shiprc" ] && . "$REPO_DIR/mobile/scripts/.shiprc"
: "${DEVSERV:?set DEVSERV (user@host of your app store) or create mobile/scripts/.shiprc}"
: "${DEVSERV_DIR:?set DEVSERV_DIR (the app directory on the store host) or create mobile/scripts/.shiprc}"
DEVSTORE_ROOT="$(dirname "$DEVSERV_DIR")"
PKG="com.silencelen.huginn"

case "$VARIANT" in
  release) MANIFEST="dist/latest.json" ;;
  debug)   MANIFEST="dist/latest-debug.json" ;;
  *) echo "usage: ship.sh [release|debug]" >&2; exit 2 ;;
esac

if [ "$VARIANT" = "release" ] && [ ! -f "${HUGINN_KEYSTORE_PROPERTIES:-$HOME/.huginn-app/keystore.properties}" ]; then
  echo "[ship] no keystore.properties — a release build here would be unsigned; refusing." >&2
  exit 1
fi

echo "[ship 1/4] building $VARIANT"
scripts/build.sh "$VARIANT"

APK_NAME="$(grep -oE '"apk":"[^"]+"' "$MANIFEST" | head -1 | sed 's/"apk":"//; s/"$//')"
APK="dist/$APK_NAME"
# Guard against shipping the other variant's stale manifest.
case "$APK_NAME" in
  Huginn-"$VARIANT"-*.apk) ;;
  *) echo "[ship] manifest APK '$APK_NAME' is not Huginn-$VARIANT-*.apk; aborting." >&2; exit 1 ;;
esac

UPLOADS=("$APK")
[ -f CHANGELOG.md ] && { cp CHANGELOG.md dist/CHANGELOG.md; UPLOADS+=(dist/CHANGELOG.md); }
[ -f assets/icon.png ] && UPLOADS+=(assets/icon.png)

echo "[ship 2/4] uploading $(basename "$APK") to $DEVSERV:$DEVSERV_DIR/"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$DEVSERV" "mkdir -p '$DEVSERV_DIR'"
scp -o BatchMode=yes -o ConnectTimeout=10 "${UPLOADS[@]}" "$DEVSERV:$DEVSERV_DIR/"
# app.json holds the constants update-index.sh merges with latest.json.
scp -o BatchMode=yes -o ConnectTimeout=10 dist/app.json "$DEVSERV:$DEVSERV_DIR/app.json"
# The served manifest is always latest.json regardless of variant.
scp -o BatchMode=yes -o ConnectTimeout=10 "$MANIFEST" "$DEVSERV:$DEVSERV_DIR/latest.json"

echo "[ship 3/4] refreshing the devstore index"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$DEVSERV" "cd '$DEVSTORE_ROOT' && ./update-index.sh" | sed 's/^/  /'

echo "[ship 4/4] verifying the live index"
EXPECTED_VC="$(grep -oE '"versionCode":[0-9]+' "$MANIFEST" | head -1 | cut -d: -f2)"
DEVSTORE_BASE="http://${DEVSERV#*@}:8083"
LIVE_VC="$(curl -sS --max-time 8 "$DEVSTORE_BASE/index.json" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['apps'].get('$PKG',{}).get('versionCode',''))")"
if [ -n "$EXPECTED_VC" ] && [ "$EXPECTED_VC" = "$LIVE_VC" ]; then
  echo "[ship] OK  $PKG @ versionCode $EXPECTED_VC is live"
  echo "[ship]     $DEVSTORE_BASE/$(basename "$DEVSERV_DIR")/$APK_NAME"
else
  echo "[ship] FAIL index versionCode '$LIVE_VC' != expected '$EXPECTED_VC'" >&2
  exit 1
fi
