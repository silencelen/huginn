#!/usr/bin/env bash
# Build a Huginn APK (release by default). The Gradle export task writes
# dist/Huginn-<variant>-<versionCode>-<ts>.apk plus its devstore manifest
# (dist/latest.json for release, dist/latest-debug.json for debug).
#
# Release signing uses huginn:/root/.huginn-app/keystore.properties. If that file
# is absent the release APK builds UNSIGNED on purpose (so CI still exercises the
# release path) and this script refuses to call it shippable.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"
VARIANT="${1:-release}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

case "$VARIANT" in
  release) TASK=":app:assembleRelease"; MANIFEST="dist/latest.json" ;;
  debug)   TASK=":app:assembleDebug";   MANIFEST="dist/latest-debug.json" ;;
  *) echo "usage: build.sh [release|debug]" >&2; exit 2 ;;
esac

# Concurrent sessions share this working tree; a second gradle invocation on the
# same project dir corrupts the build cache, so serialise on a lock.
LOCK=/tmp/huginn-app-gradle.lock

echo "[build 1/3] unit tests"
# BOTH modules. Most of the logic lives in :core now, and `:app:testDebugUnitTest`
# alone would run 58 of the 179 tests and still exit 0 — a green gate over an
# untested app. :core is run for both its targets because a shared-code change
# that only breaks one of them is exactly what a multiplatform module is for.
flock "$LOCK" ./gradlew :core:jvmTest :core:testDebugUnitTest :app:testDebugUnitTest

# The COUNT is asserted, not just the exit code — same reason as the server suite
# below, and the same failure the line above describes: a suite that stops being
# DISCOVERED (a module split, a renamed source set, a task that silently has no
# sources) exits 0 having run nothing. A floor catches that; it only ever needs
# raising, never lowering, unless tests are deliberately deleted.
KOTLIN_MIN=290   # 121 (:core jvm) + 121 (:core android) + 58 (:app), 2026-07-30
KOTLIN_COUNT=0
for D in core/build/test-results/jvmTest \
         core/build/test-results/testDebugUnitTest \
         app/build/test-results/testDebugUnitTest; do
  N="$(grep -ho 'tests="[0-9]*"' "$D"/*.xml 2>/dev/null \
       | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}')"
  [ "${N:-0}" -gt 0 ] || { echo "[build] $D ran ZERO tests — refusing." >&2; exit 1; }
  KOTLIN_COUNT=$((KOTLIN_COUNT + N))
done
[ "$KOTLIN_COUNT" -ge "$KOTLIN_MIN" ] \
  || { echo "[build] kotlin tests ran $KOTLIN_COUNT, expected >= $KOTLIN_MIN — refusing." >&2; exit 1; }
echo "[build] kotlin tests: $KOTLIN_COUNT passed"

# The daemon's pure logic (pane parsing, prompt detection, transcript reading)
# is tested with node's own runner; it gates the APK because the app is useless
# against a broken server.
#
# The test COUNT is asserted, not just the exit code: `node --test` with a glob
# that matches nothing exits 0 having run zero tests, so a moved or renamed test
# directory would turn this gate green while testing nothing.
# The daemon lives beside this tree now (../server/appd), and its absence is an
# ERROR, not a skip: an -d guard here once meant a moved directory would turn
# this gate green while testing nothing.
APPD_DIR="$(cd "$REPO_DIR/../server/appd" 2>/dev/null && pwd || true)"
if [ -z "$APPD_DIR" ] || [ ! -d "$APPD_DIR/test" ]; then
  echo "[build] server/appd/test not found next to mobile/ — refusing." >&2
  exit 1
fi
if command -v node >/dev/null 2>&1; then
  NODE_LOG="$(mktemp)"
  node --test "$APPD_DIR"/test/*.test.js | tee "$NODE_LOG"
  NODE_RC="${PIPESTATUS[0]}"
  NODE_COUNT="$(grep -oE '^# tests [0-9]+' "$NODE_LOG" | grep -oE '[0-9]+' || echo 0)"
  rm -f "$NODE_LOG"
  [ "$NODE_RC" = 0 ] || { echo "[build] server tests failed" >&2; exit 1; }
  [ "${NODE_COUNT:-0}" -gt 0 ] || { echo "[build] server tests ran ZERO tests — refusing." >&2; exit 1; }
  echo "[build] server tests: $NODE_COUNT passed"
fi

echo "[build 2/3] $VARIANT APK"
flock "$LOCK" ./gradlew "$TASK"

[ -f "$MANIFEST" ] || { echo "[build] $MANIFEST missing — export task did not run" >&2; exit 1; }
APK_NAME="$(grep -oE '"apk":"[^"]+"' "$MANIFEST" | head -1 | sed 's/"apk":"//; s/"$//')"
APK="dist/$APK_NAME"
[ -f "$APK" ] || { echo "[build] $APK missing" >&2; exit 1; }

echo "[build 3/3] verifying signature"
if [ "$VARIANT" = "release" ]; then
  if ! "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$APK" >/dev/null 2>&1; then
    echo "[build] ERROR: release APK is UNSIGNED (keystore.properties missing?) — refusing." >&2
    exit 1
  fi
  "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK" | grep -E "Signer #1 certificate DN|SHA-256 digest" | head -2
fi

echo "[build] $APK ($(du -h "$APK" | cut -f1))"
echo "[build] manifest $MANIFEST"
