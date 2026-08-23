#!/usr/bin/env bash
# Build and publish Huginn. Releases go to GitHub; devstore pulls them from there.
#   1. scripts/build.sh (tests + assemble + signature check)
#   2. cut the GitHub Release (APK + latest.json) — THIS is the publish
#   3. nudge devserv to pull it now, and confirm the served index caught up
#
# Devstore pulls from GitHub Releases; nothing is scp'd on the release path.
#
# Debug ships are possible but must be asked for; release is the channel.
set -euo pipefail

# The REPO ROOT (this script is at mobile/scripts/ship.sh → up TWO). It was `/..`
# (one too shallow → mobile/), which made the .shiprc source below double to
# mobile/mobile/scripts/.shiprc and silently skip (DEVSERV then unset), and the
# github-release.sh call below miss the repo-root scripts/. Both read REPO_DIR as
# the repo root, so that is what it must be; the mobile-relative work cd's in.
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR/mobile"
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

# ---------------------------------------------------------------------------
# RELEASE PATH: publish to GitHub. That is the whole publish.
#
# Devstore PULLS from GitHub Releases now (devserv: devstore-sync.timer, every
# 15 min) instead of being pushed to. This inverts what used to happen here —
# the scp to devserv was the release and GitHub was a best-effort mirror — which
# is exactly how andvari 0.25.0 came to be released on every channel while the
# owner's phone sat on 0.24.0: one scp nobody ran. One publish now, and this is
# it. Do not re-add an scp; two publish paths is how the two sources drift.
# ---------------------------------------------------------------------------
VN="$(grep -oE '"versionName":"[^"]+"' "$MANIFEST" | head -1 | cut -d'"' -f4)"

if [ "$VARIANT" = "release" ]; then
  echo "[ship 2/3] publishing GitHub release app-v$VN"
  TMPD="$(mktemp -d)"
  # The updater matches the APK asset by extension and verifies by sha256, so
  # the nice asset name and latest.json's internal apk name need not agree —
  # devstore-sync renames the asset to whatever the manifest calls it.
  cp "$APK" "$TMPD/Huginn-$VN.apk"
  # latest.json is load-bearing twice: the in-app PhoneUpdater verifies the APK
  # against its sha256, AND devstore-sync mirrors it verbatim as the store
  # manifest. A release without it is skipped by the store, loudly. It is not
  # optional and this step is no longer best-effort.
  if ! "$REPO_DIR/scripts/github-release.sh" app "$VN" \
         "$TMPD/Huginn-$VN.apk#Huginn $VN (signed APK, arm64-v8a)" \
         "$REPO_DIR/mobile/$MANIFEST#Release manifest (sha256, for in-app + devstore update)"; then
    rm -rf "$TMPD"
    echo "[ship] FAIL GitHub release app-v$VN failed — NOTHING is published. Fix and re-run." >&2
    exit 1
  fi
  rm -rf "$TMPD"
  echo "[ship] GitHub release app-v$VN published (APK + latest.json)"

  # A nudge, not a publish path: the timer would pick this up within 15 minutes
  # anyway. If devserv is unreachable the release still stands and the store
  # self-corrects on its own — that is the entire point of going pull-based.
  echo "[ship 3/3] nudging devstore to pull now"
  if ssh -o BatchMode=yes -o ConnectTimeout=10 "$DEVSERV" \
       "'$DEVSTORE_ROOT/devstore-sync.sh' --app huginn" 2>&1 | sed 's/^/  /'; then
    EXPECTED_VC="$(grep -oE '"versionCode":[0-9]+' "$MANIFEST" | head -1 | cut -d: -f2)"
    DEVSTORE_BASE="http://${DEVSERV#*@}:8083"
    LIVE_VC="$(curl -sS --max-time 8 "$DEVSTORE_BASE/index.json" \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['apps'].get('$PKG',{}).get('versionCode',''))" || true)"
    if [ -n "$EXPECTED_VC" ] && [ "$EXPECTED_VC" = "$LIVE_VC" ]; then
      echo "[ship] OK  $PKG @ versionCode $EXPECTED_VC is live on devstore"
    else
      echo "[ship] NOTE devstore shows '$LIVE_VC', expected '$EXPECTED_VC' — the timer will reconcile; check devstore-sync logs on devserv if it does not." >&2
    fi
  else
    echo "[ship] NOTE could not reach devserv to nudge it; the quarter-hourly timer will pull this release on its own." >&2
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
# DEBUG PATH: still a direct push, because a debug build is not a release and
# never gets a GitHub Release cut for it. app.json is deliberately NOT pushed —
# it carries the devstore-sync `source` block, and scp'ing a build-time copy
# over it would silently revert huginn to push-only.
# ---------------------------------------------------------------------------
echo "[ship 2/3] (debug) uploading $(basename "$APK") to $DEVSERV:$DEVSERV_DIR/"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$DEVSERV" "mkdir -p '$DEVSERV_DIR'"
scp -o BatchMode=yes -o ConnectTimeout=10 "${UPLOADS[@]}" "$DEVSERV:$DEVSERV_DIR/"
scp -o BatchMode=yes -o ConnectTimeout=10 "$MANIFEST" "$DEVSERV:$DEVSERV_DIR/latest.json"

echo "[ship 3/3] (debug) refreshing the devstore index"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$DEVSERV" "cd '$DEVSTORE_ROOT' && ./update-index.sh" | sed 's/^/  /'
