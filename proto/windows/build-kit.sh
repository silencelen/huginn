#!/usr/bin/env bash
# Assemble proto/windows/kit (+ huginn-core-proto-win.zip) for probe.ps1:
# the Go server/tray/s4ucheck as .exe, the JVM probe classes + jars, and a
# minimal Windows JRE cross-linked from the Windows JDK's jmods.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; P="$HERE/.."
OUT="${1:-$HERE/kit}"; rm -rf "$OUT"; mkdir -p "$OUT/lib"
WINJDK="${HUGINN_WIN_JDK:-/opt/jdk-win-x64/current}"
M2="$HOME/.gradle/caches/modules-2/files-2.1"
(cd "$P/ipc/server" && GOOS=windows GOARCH=amd64 go build -o "$OUT/ipcserver.exe" .)
(cd "$P/tray" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -ldflags -H=windowsgui -o "$OUT/traytest.exe" .)
(cd "$HERE/s4ucheck" && GOOS=windows GOARCH=amd64 go build -o "$OUT/s4ucheck.exe" .)
cp "$(ls "$M2"/com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar)" "$(ls "$M2"/com.squareup.okio/okio-jvm/3.9.0/*/okio-jvm-3.9.0.jar)" "$(ls "$M2"/org.jetbrains.kotlin/kotlin-stdlib/1.9.24/*/kotlin-stdlib-1.9.24.jar)" "$OUT/lib/"
javac --release 17 -d "$OUT/classes" -cp "$OUT/lib/*" "$P/ipc/client/UnixSocketProbe.java"
jlink --module-path "$WINJDK/jmods" --add-modules java.base,java.logging --no-header-files --no-man-pages --strip-debug --compress=2 --output "$OUT/rt"
cp "$HERE/probe.ps1" "$OUT/"
# CRLF for PowerShell 5.1 friendliness; the file is ASCII (checked).
LC_ALL=C grep -qP '[^\x00-\x7F]' "$OUT/probe.ps1" && { echo "probe.ps1 is not ASCII" >&2; exit 1; }
sed -i 's/$/\r/' "$OUT/probe.ps1"
(cd "$(dirname "$OUT")" && rm -f huginn-core-proto-win.zip && zip -qr huginn-core-proto-win.zip "$(basename "$OUT")")
ls -la "$OUT" "$(dirname "$OUT")/huginn-core-proto-win.zip"
