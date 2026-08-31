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
#
# :ui joined the list when it was extracted: it now owns the terminal grid walk
# and the transcript rows for BOTH clients, so a break there breaks the phone.
# Only its jvm target runs — the suite needs a real DrawScope, and on the Android
# target ImageBitmap is a stubbed android.graphics.Bitmap in a unit test.
# --rerun-tasks --no-build-cache so the gate cannot pass on FROM-CACHE / UP-TO-DATE
# task outputs: an unchanged tree leaves the test tasks up-to-date, gradle skips
# them, and the COUNT below is then read from the PREVIOUS run's XML — a green gate
# over tests that never executed. Forcing a real run is the whole point of a gate.
flock "$LOCK" ./gradlew --rerun-tasks --no-build-cache :core:jvmTest :core:testDebugUnitTest :app:testDebugUnitTest :ui:jvmTest

# The COUNT is asserted, not just the exit code — same reason as the server suite
# below, and the same failure the line above describes: a suite that stops being
# DISCOVERED (a module split, a renamed source set, a task that silently has no
# sources) exits 0 having run nothing. A floor catches that; it only ever needs
# raising, never lowering, unless tests are deliberately deleted.
# Raised when the HTTP layer moved into :core: SseTest could finally leave :app
# (MockWebServer, JVM-only -> Ktor's multiplatform MockEngine) and brought
# SseLinesTest, HuginnClientTest and SettingsCodecTest with it, so 42 more tests
# now run TWICE. :app keeps the 49 whose subject genuinely needs Android or its
# own classes.
# Raised again when :ui was extracted (phase 3b): the shared terminal grid walk
# arrived with 7 tests of its own, asserted against a recording CellPainter plus
# one real skia render.
# Raised again for phase 3c (the desktop session view): the transcript merge left
# the Android view model for :core, and the pane-size LEASE rule plus the two poll
# backoff ladders arrived as pure, shared code. All three are safety properties —
# a row identity that collides, a lease that is never released, a 409 retried
# forever — so they are tested where both clients read them, which means the 25
# new tests run TWICE.
. "$REPO_DIR/../scripts/test-floors.env"   # floors live in ONE place
KOTLIN_MIN="$KOTLIN_SHARED_MIN"
KOTLIN_COUNT=0
for D in core/build/test-results/jvmTest \
         core/build/test-results/testDebugUnitTest \
         app/build/test-results/testDebugUnitTest \
         ui/build/test-results/jvmTest; do
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
  # '# pass', not '# tests': the latter counts failures too. Matches deploy.sh and
  # release-desktop.sh so all three gates measure the same thing.
  NODE_COUNT="$(grep -oE '^# pass [0-9]+' "$NODE_LOG" | grep -oE '[0-9]+' || echo 0)"
  rm -f "$NODE_LOG"
  [ "$NODE_RC" = 0 ] || { echo "[build] server tests failed" >&2; exit 1; }
  # Honour the shared floor. This said `-gt 0` while test-floors.env — which this
  # script already sources — sets APPD_MIN=300 and says "never edit a copy in a
  # script". A >0 gate accepts a suite that lost 99% of its tests.
  [ "${NODE_COUNT:-0}" -ge "$APPD_MIN" ] || { echo "[build] server tests ran $NODE_COUNT, expected >= $APPD_MIN — refusing." >&2; exit 1; }
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
