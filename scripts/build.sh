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
flock "$LOCK" ./gradlew :app:testDebugUnitTest

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
