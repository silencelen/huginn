#!/usr/bin/env bash
# Release the Compose Multiplatform desktop client into huginn-appd's
# /v1/desktop-kt channel.
#
# Everything happens on THIS box. No Windows machine is involved and none is
# needed: the Windows installer is built by running the WINDOWS jpackage.exe
# under wine against a Windows jlink runtime, then wrapped by Linux makensis.
# The Linux .deb comes from Compose's own packageDeb. Both are staged into
# DATA_DIR/desktop-kt by local moves and verified back through the wire with the
# real Bearer token.
#
# ---------------------------------------------------------------------------
# THE OTHER CHANNEL. /v1/desktop belongs to the ELECTRON client, which the owner
# is running (0.4.0) and which self-updates from it. This script must never write
# there: an Electron client that "updates" into a Compose build has been replaced
# by a different application, silently, from its own update prompt. The two
# directories, the two manifests, the two installers' registry keys and install
# paths are all disjoint (see lib/desktop.js and packaging/huginn-desktop-kt.nsi).
#
# CUTOVER, when parity arrives, is deliberately NOT a directory rename — a rename
# is exactly the accident this separation exists to prevent. It is: ship one final
# Electron release whose only change is a notice; then have the Compose installer
# take over the Electron install path and uninstall key in the same release that
# starts publishing to /v1/desktop; then retire /v1/desktop-kt. Nothing about that
# sequence should be improvised on the day.
# ---------------------------------------------------------------------------
#
# Usage: scripts/release-desktop.sh [--linux-only] [--skip-tests] [--skip-wine-install]
set -euo pipefail
cd "$(dirname "$0")/.."          # -> mobile/

CHANNEL_DIR=/var/lib/huginn-appd/desktop-kt
TOKEN_FILE=/etc/huginn-appd/token
BASE_URL=${HUGINN_APPD_URL:-http://100.97.198.90:8787}
FEED=/v1/desktop-kt
KEEP=2

# The Windows JDK whose jmods the cross-jlink links against and whose jpackage.exe
# runs under wine. Cached OUTSIDE the build tree on purpose: the spike kept it in
# scratch, scratch was cleared, and the chain had to be re-derived from scratch.
# Named ONCE, here.
WIN_JDK=${HUGINN_WIN_JDK:-/opt/jdk-win-x64/current}
WIN_JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
# A wine prefix of this build's own, so a release never depends on — or disturbs —
# whatever electron-builder left in ~/.wine.
export WINEPREFIX=${HUGINN_WINE_PREFIX:-/root/.wine-huginn-kt}
export WINEDEBUG=${WINEDEBUG:--all}

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
# Concurrent Claude sessions share this working tree and a second gradle
# invocation on the same project dir corrupts the build cache. Same lock as
# scripts/build.sh.
LOCK=/tmp/huginn-app-gradle.lock
GRADLE="flock $LOCK ./gradlew"

LINUX_ONLY=0
SKIP_TESTS=0
SKIP_WINE_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --linux-only) LINUX_ONLY=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --skip-wine-install) SKIP_WINE_INSTALL=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

VERSION=$(cat app-desktop/version.txt | tr -d '[:space:]')
[ -n "$VERSION" ] || { echo "REFUSING: app-desktop/version.txt is empty" >&2; exit 1; }
# x.y.z exactly. Step 6's prune finds a version inside a FILENAME with a
# three-component regex, so a two-component version would produce artifacts the
# prune cannot see and therefore never deletes — and the gate below cannot order
# two versions it cannot parse.
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || { echo "REFUSING: app-desktop/version.txt is '$VERSION', not x.y.z" >&2; exit 1; }
BUILD=app-desktop/build
WIN=$BUILD/windows
DIST=$BUILD/release
EXE="Huginn-Desktop-Setup-$VERSION.exe"
DEB="huginn-desktop-kt_${VERSION}-1_amd64.deb"
# The installer, the plugin that stamps the notification identity, and the file
# that holds the identity itself. Named once: two gates and the makensis
# invocation all reach for them, and the plugin path is DERIVED from the .nsi
# path so it cannot end up pointing at a different tree.
NSI=app-desktop/packaging/huginn-desktop-kt.nsi
PLUGIN_DIR="$PWD/$(dirname "$NSI")/plugins/x86-unicode"
NOTIFIER=app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/notify/WindowsToastNotifier.kt
LOG=${TMPDIR:-/tmp}/huginn-desktop-kt-release.log
: > "$LOG"

echo "== releasing huginn-desktop-kt $VERSION =="
echo "   (log: $LOG)"

# ---------------------------------------------------------------- 1. gates
echo "[1/7] gates"

# A human wrote a note about this version, or it is not a release.
grep -q "^## $VERSION\$" app-desktop/CHANGELOG.md || {
  echo "REFUSING: app-desktop/CHANGELOG.md has no '## $VERSION' section" >&2; exit 1; }

# The toast identity, in the two files that must agree about it. A desktop app
# has no notification identity of its own — it borrows the AUMID stamped on its
# Start Menu shortcut — so the string the app hands to CreateToastNotifier and
# the string the installer stamps are ONE FACT STORED TWICE. When they disagree,
# or when nothing stamps at all, Windows accepts every toast and displays none of
# them, with no error and a zero exit code, so nothing downstream can notice.
# 0.3.1 shipped stamping nothing and reported itself healthy the whole time.
KT_AUMID=$(sed -n 's/^ *const val AUMID: String = "\(.*\)"$/\1/p' "$NOTIFIER")
NSI_AUMID=$(sed -n 's/^!define AUMID  *"\(.*\)"$/\1/p' "$NSI")
[ -n "$KT_AUMID" ] || { echo "REFUSING: no AUMID constant found in $NOTIFIER" >&2; exit 1; }
[ "$KT_AUMID" = "$NSI_AUMID" ] || {
  echo "REFUSING: AUMID drift — the app posts as '$KT_AUMID', the installer stamps '$NSI_AUMID'" >&2
  exit 1; }
# Agreeing strings prove nothing if the stamp lands on a DIFFERENT shortcut than
# the one the installer creates — that reintroduces the silent drop while every
# other check here still passes.
LNK=$(sed -n 's/^ *CreateShortCut \("\$SMPROGRAMS[^"]*\.lnk"\) .*/\1/p' "$NSI" | head -1)
grep -qF "WinShell::SetLnkAUMI $LNK" "$NSI" || {
  echo "REFUSING: $NSI does not stamp the AUMID on $LNK, the shortcut it creates" >&2; exit 1; }
[ -f "$PLUGIN_DIR/WinShell.dll" ] || {
  echo "REFUSING: $PLUGIN_DIR/WinShell.dll is missing — nothing can stamp the AUMID" >&2; exit 1; }
echo "  toast identity: $KT_AUMID (app and installer agree)"

# Never overwrite what is already live, and never publish BACKWARDS. Equality was
# the original hazard — a client that has downloaded and verified 0.2.0 would
# find different bytes under the same version and the same hash claim — but a
# downgrade is that hazard plus a second one: step 6 prunes to the newest $KEEP
# versions, so publishing 0.2.9 over a live 0.3.1 deletes 0.3.1's artifacts out
# from under whoever is mid-download, and then offers every running client an
# "update" that walks it backwards. version.txt is a one-line file edited by
# hand and releases get cut from old checkouts; neither mistake is exotic.
if [ -f "$TOKEN_FILE" ]; then
  # `|| ''` because a manifest WITHOUT a version field makes `node -p` print the
  # four-letter word "undefined", which is neither empty nor a version, and every
  # test downstream of here would then be reasoning about a string that means
  # nothing.
  LIVE=$(curl -sf -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
    "$BASE_URL$FEED/manifest" 2>/dev/null | node -p \
    "try{JSON.parse(require('fs').readFileSync(0,'utf8')).version||''}catch{''}" || true)
  # An empty LIVE is the first release into a fresh channel — nothing to compare.
  if [ -n "$LIVE" ]; then
    # An unreadable live version means the channel is not in the state this
    # script believes it is in, and guessing is how you overwrite something.
    echo "$LIVE" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || {
      echo "REFUSING: $BASE_URL$FEED serves version '$LIVE', which is not x.y.z" >&2; exit 1; }
    NEWEST=$(printf '%s\n%s\n' "$LIVE" "$VERSION" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
    if [ "$VERSION" = "$LIVE" ] || [ "$NEWEST" != "$VERSION" ]; then
      echo "REFUSING: $VERSION is not newer than $LIVE, already live on $BASE_URL$FEED" >&2; exit 1
    fi
  fi
fi

if [ "$SKIP_TESTS" = 0 ]; then
  # The FULL shared suite, not just this module's. :core and :ui are what the
  # desktop client is made of, and a release that only ran :app-desktop:test
  # would be 26 tests over an untested application — the exact failure
  # scripts/build.sh was hardened against twice.
  $GRADLE :core:jvmTest :core:testDebugUnitTest :app:testDebugUnitTest :ui:jvmTest \
          :app-desktop:test >> "$LOG" 2>&1 || {
    tail -40 "$LOG"; echo "REFUSING: kotlin tests failed (full log: $LOG)" >&2; exit 1; }

  # The COUNT, not the exit code. A task that silently has no sources — a moved
  # source set, a renamed module — exits 0 having run nothing.
  # Raised for the desktop-native pass: the right-click menus, the tooltip
  # sentences, the multi-select model and the window/splitter restore are all
  # pure decisions that fail SILENTLY when wrong (a menu that deletes four rows
  # while reading "Delete", a window restored onto a monitor that is gone), so
  # they are asserted rather than eyeballed. +28 in :app-desktop.
  KOTLIN_MIN=436   # 382 (scripts/build.sh floor) + 54 (:app-desktop), 2026-07-31
  KOTLIN_COUNT=0
  for D in core/build/test-results/jvmTest \
           core/build/test-results/testDebugUnitTest \
           app/build/test-results/testDebugUnitTest \
           ui/build/test-results/jvmTest \
           app-desktop/build/test-results/test; do
    N="$(grep -ho 'tests="[0-9]*"' "$D"/*.xml 2>/dev/null \
         | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}')"
    [ "${N:-0}" -gt 0 ] || { echo "REFUSING: $D ran ZERO tests" >&2; exit 1; }
    KOTLIN_COUNT=$((KOTLIN_COUNT + N))
  done
  [ "$KOTLIN_COUNT" -ge "$KOTLIN_MIN" ] \
    || { echo "REFUSING: kotlin tests ran $KOTLIN_COUNT, expected >= $KOTLIN_MIN" >&2; exit 1; }
  echo "  kotlin tests: $KOTLIN_COUNT passed"

  # The daemon serves this channel; a broken daemon is a broken release.
  APPD_DIR="$(cd ../server/appd && pwd)"
  NODE_LOG="$(mktemp)"
  node --test "$APPD_DIR"/test/*.test.js > "$NODE_LOG" 2>&1 || {
    tail -30 "$NODE_LOG"; echo "REFUSING: server tests failed" >&2; exit 1; }
  NODE_COUNT="$(grep -oE '^# pass [0-9]+' "$NODE_LOG" | grep -oE '[0-9]+' || echo 0)"
  rm -f "$NODE_LOG"
  [ "${NODE_COUNT:-0}" -ge 300 ] \
    || { echo "REFUSING: server tests reported $NODE_COUNT passes, expected >= 300" >&2; exit 1; }
  echo "  server tests: $NODE_COUNT passed"

  # The phone is built from the same :core and :ui. A desktop release that broke
  # the daily driver's build would not be noticed until the next APK.
  $GRADLE :app:assembleDebug >> "$LOG" 2>&1 || {
    tail -40 "$LOG"; echo "REFUSING: :app:assembleDebug failed (full log: $LOG)" >&2; exit 1; }
  echo "  :app:assembleDebug still builds"
fi

# ------------------------------------------------------------- 2. linux build
echo "[2/7] linux .deb"
$GRADLE :app-desktop:packageDeb :app-desktop:createDistributable >> "$LOG" 2>&1 || {
  tail -40 "$LOG"; echo "REFUSING: linux package failed (full log: $LOG)" >&2; exit 1; }
DEB_SRC="$BUILD/compose/binaries/main/deb/$DEB"
# ARTIFACTS, never exit codes: `packageMsi` on Linux exits 0 having produced
# nothing at all, and that is the shape of every packaging failure here.
[ -f "$DEB_SRC" ] || { echo "REFUSING: $DEB_SRC was not produced" >&2; exit 1; }
echo "  $(basename "$DEB_SRC") $(stat -c %s "$DEB_SRC") bytes"

# ----------------------------------------------------------- 3. windows build
if [ "$LINUX_ONLY" = 0 ]; then
  echo "[3/7] windows installer (jpackage.exe under wine -> linux makensis)"

  command -v wine >/dev/null || { echo "REFUSING: wine is not installed" >&2; exit 1; }
  command -v xvfb-run >/dev/null || { echo "REFUSING: xvfb-run is not installed" >&2; exit 1; }
  MAKENSIS="$(command -v makensis || true)"
  # The apt package first, the electron-builder cache only as a fallback: that
  # cache is a download directory some other tool owns and is free to prune.
  [ -n "$MAKENSIS" ] || MAKENSIS="$(ls -1 /root/.cache/electron-builder/nsis-*/nsis-*/linux/makensis 2>/dev/null | head -1 || true)"
  [ -n "$MAKENSIS" ] || { echo "REFUSING: no makensis (apt install nsis)" >&2; exit 1; }

  # Fetch the Windows JDK if the cache is cold. It is only the jmods (for the
  # cross-jlink) and jpackage.exe itself that are needed, but Temurin ships them
  # in one archive.
  if [ ! -x "$WIN_JDK/bin/jpackage.exe" ]; then
    echo "  fetching the Windows JDK into $(dirname "$WIN_JDK")"
    PARENT="$(dirname "$WIN_JDK")"
    install -d "$PARENT"
    curl -fsSL -o "$PARENT/temurin17-win-x64.zip" "$WIN_JDK_URL"
    (cd "$PARENT" && unzip -q -o temurin17-win-x64.zip)
    UNPACKED="$(ls -1d "$PARENT"/jdk-17* | head -1)"
    ln -sfn "$(basename "$UNPACKED")" "$WIN_JDK"
    [ -x "$WIN_JDK/bin/jpackage.exe" ] || { echo "REFUSING: no jpackage.exe after fetch" >&2; exit 1; }
  fi

  # The Windows-x64 runtime classpath. Its whole job is to differ from the Linux
  # one in exactly one artifact (skiko), and the task asserts that itself.
  $GRADLE :app-desktop:windowsAppLibs >> "$LOG" 2>&1 || {
    tail -40 "$LOG"; echo "REFUSING: windowsAppLibs failed (full log: $LOG)" >&2; exit 1; }

  # skiko does not load its native from the jar in a packaged app — Compose
  # extracts it beside the jars and points `skiko.library.path` at APPDIR. The
  # Linux path gets that for free from createDistributable; the Windows one has
  # to do it here, and without it the app installs perfectly and dies at first paint.
  (cd "$WIN/lib" && unzip -o -q skiko-awt-runtime-windows-x64-*.jar \
      'skiko-windows-x64.dll*' 'icudtl.dat' -d . && mkdir -p resources)
  [ -f "$WIN/lib/skiko-windows-x64.dll" ] || {
    echo "REFUSING: skiko-windows-x64.dll was not extracted" >&2; exit 1; }

  # The jlink module list is READ OUT of the Linux runtime image rather than
  # written here. It is the same application, so it is the same module set, and a
  # second hand-maintained copy would drift the moment a dependency reached for
  # java.sql. Compose computes it; this just reuses the answer.
  MODULES=$(grep -o 'MODULES="[^"]*"' "$BUILD/compose/tmp/main/runtime/release" \
            | sed 's/MODULES="//; s/"$//' | tr ' \n' ',,' | sed 's/,$//')
  [ -n "$MODULES" ] || { echo "REFUSING: could not read MODULES from the linux runtime image" >&2; exit 1; }
  echo "  jlink modules: $MODULES"

  rm -rf "$WIN/runtime" "$WIN/image" "$WIN/out"
  mkdir -p "$WIN/out"
  xvfb-run -a wine "$WIN_JDK/bin/jlink.exe" \
    --module-path "$(winepath -w "$WIN_JDK/jmods")" \
    --add-modules "$MODULES" \
    --strip-debug --no-header-files --no-man-pages \
    --output "$(winepath -w "$PWD/$WIN")\\runtime" >> "$LOG" 2>&1 || true
  [ -f "$WIN/runtime/release" ] || {
    tail -40 "$LOG"; echo "REFUSING: cross-jlink produced no runtime image" >&2; exit 1; }

  # The java-options are Compose's own, copied from the launcher .cfg that
  # createDistributable generated for Linux. jpackage puts every jar in --input
  # on the classpath itself, so there is nothing to post-process.
  xvfb-run -a wine "$WIN_JDK/bin/jpackage.exe" \
    --type app-image \
    --name huginn-desktop-kt \
    --app-version "$VERSION" \
    --description "Huginn desktop client" \
    --vendor silencelen \
    --input "$(winepath -w "$PWD/$WIN/lib")" \
    --main-jar "app-desktop-$VERSION.jar" \
    --main-class com.silencelen.huginn.desktop.MainKt \
    --runtime-image "$(winepath -w "$PWD/$WIN/runtime")" \
    --dest "$(winepath -w "$PWD/$WIN/image")" \
    --java-options '-Dcompose.application.resources.dir=$APPDIR\resources' \
    --java-options '-Dcompose.application.configure.swing.globals=true' \
    --java-options '-Dskiko.library.path=$APPDIR' >> "$LOG" 2>&1 || true
  APP_IMAGE="$WIN/image/huginn-desktop-kt"
  [ -f "$APP_IMAGE/huginn-desktop-kt.exe" ] || {
    tail -40 "$LOG"; echo "REFUSING: jpackage under wine produced no launcher" >&2; exit 1; }
  file "$APP_IMAGE/huginn-desktop-kt.exe" | grep -q 'PE32+ executable' || {
    echo "REFUSING: the app-image launcher is not a Windows PE binary" >&2; exit 1; }

  # -V4 into a log of its own, not -V2 appended to the shared one. That is the
  # only verbosity that prints the RESOLVED plugin calls, and the assertion below
  # reads the stamped identity off the compiler instead of trusting the source it
  # was supposed to come from. A relative -DPLUGIN_DIR would be accepted, add
  # nothing, and fail as "Plugin not found" — hence the absolute path.
  "$MAKENSIS" -V4 \
    -DAPP_VERSION="$VERSION" \
    -DSRC_DIR="$PWD/$APP_IMAGE" \
    -DOUT_FILE="$PWD/$WIN/out/$EXE" \
    -DPLUGIN_DIR="$PLUGIN_DIR" \
    "$NSI" > "$LOG.nsis" 2>&1 || {
    cat "$LOG.nsis" >> "$LOG"; tail -40 "$LOG.nsis"
    echo "REFUSING: makensis failed (full log: $LOG)" >&2; exit 1; }
  cat "$LOG.nsis" >> "$LOG"
  [ -f "$WIN/out/$EXE" ] || { echo "REFUSING: $EXE was not produced" >&2; exit 1; }
  # PROVE it is what it claims to be, rather than trusting that makensis exited 0.
  file "$WIN/out/$EXE" | grep -q 'Nullsoft Installer self-extracting archive' || {
    file "$WIN/out/$EXE"; echo "REFUSING: $EXE is not an NSIS installer" >&2; exit 1; }

  # The identity this installer will actually stamp, taken from the compiled
  # instruction rather than the .nsi. This is the check that would have caught
  # 0.3.1: an installer that stamps nothing builds, installs, launches and passes
  # every other gate here, and then Windows drops each toast in silence. Empty
  # means no SetLnkAUMI survived into the installer at all.
  STAMPED=$(sed -n 's/^Plugin command: SetLnkAUMI .*\.lnk //p' "$LOG.nsis" | head -1)
  [ "$STAMPED" = "$KT_AUMID" ] || {
    echo "REFUSING: the installer stamps '$STAMPED', the app posts as '$KT_AUMID'" >&2; exit 1; }
  echo "  $EXE $(stat -c %s "$WIN/out/$EXE") bytes"
  echo "  $(file -b "$WIN/out/$EXE")"
  echo "  stamps AUMID $STAMPED on the Start Menu shortcut"

  # ------------------------------------------------- 3b. run it, under wine
  #
  # The only check that covers the whole chain. An installer can build, install
  # and leave an app that dies on its first frame — a wrong skiko native does
  # exactly that, and every step before this one passes.
  if [ "$SKIP_WINE_INSTALL" = 0 ]; then
    echo "  installing under wine and launching the result"
    [ -d "$WINEPREFIX" ] || xvfb-run -a wineboot -u >> "$LOG" 2>&1
    xvfb-run -a wine "$WIN/out/$EXE" /S >> "$LOG" 2>&1 || true
    INSTALLED="$WINEPREFIX/drive_c/users/$(id -un)/AppData/Local/Programs/huginn-desktop-kt"
    [ -f "$INSTALLED/huginn-desktop-kt.exe" ] || {
      echo "REFUSING: the installer did not put a launcher in $INSTALLED" >&2; exit 1; }

    # A settings file it wrote itself is the proof. "The process is still alive"
    # is not: a JVM that failed to find its main class is alive too. The client
    # generates a clientId on first construction and writes it through, so this
    # file existing means the app's own code ran.
    PROBE_HOME="$WINEPREFIX/drive_c/users/$(id -un)/.config/huginn-desktop-kt"
    rm -f "$PROBE_HOME/settings.json"
    ( cd "$INSTALLED" && timeout 120 xvfb-run -a -s "-screen 0 1400x900x24" \
        wine ./huginn-desktop-kt.exe >> "$LOG" 2>&1 & )
    for _ in $(seq 1 24); do
      [ -f "$PROBE_HOME/settings.json" ] && break
      sleep 5
    done
    # Bracketed so the pattern cannot match the shell that is running it — a
    # `pkill -f` of a literal string reliably kills its own invoking command line.
    pkill -f '[h]uginn-desktop-kt\.exe' >/dev/null 2>&1 || true
    [ -f "$PROBE_HOME/settings.json" ] || {
      tail -40 "$LOG"
      echo "REFUSING: the installed app never got as far as writing its settings" >&2; exit 1; }
    # skiko cannot make a GL/DX context under wine+Xvfb and falls back to
    # software. Expected, and not a failure — it does mean the GPU path is never
    # exercised here.
    echo "  installed app launched and initialised (software renderer under wine)"
  fi
fi

# ------------------------------------------------ 4. checksums + manifest
echo "[4/7] checksums + manifest"
rm -rf "$DIST"; mkdir -p "$DIST"
cp "$DEB_SRC" "$DIST/$DEB"
[ "$LINUX_ONLY" = 1 ] || cp "$WIN/out/$EXE" "$DIST/$EXE"

node - "$VERSION" "$DIST" "$LINUX_ONLY" "$EXE" "$DEB" << 'EOF' > "$DIST/manifest.json"
const fs = require('fs'), crypto = require('crypto'), path = require('path')
const [version, dist, linuxOnly, exe, deb] = process.argv.slice(2)
const entry = (name) => {
  const p = path.join(dist, name)
  return {
    file: name,
    sha256: crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex'),
    size: fs.statSync(p).size,
  }
}
const man = { version, releasedAt: new Date().toISOString(), artifacts: { 'linux-x64': entry(deb) } }
if (linuxOnly !== '1') man.artifacts['windows-x64'] = entry(exe)
// The changelog section for THIS version, so the client can show what changed
// without a second fetch.
const clog = fs.readFileSync('app-desktop/CHANGELOG.md', 'utf8')
const m = clog.split(`## ${version}`)[1]
if (m) man.notes = m.split(/\n## /)[0].trim().slice(0, 2000)
console.log(JSON.stringify(man, null, 1))
EOF
node -e "const m=require('$PWD/$DIST/manifest.json');
  if (m.version!=='$VERSION') { console.error('manifest version mismatch'); process.exit(1) }
  for (const [k,a] of Object.entries(m.artifacts)) {
    if (!/^[0-9a-f]{64}\$/.test(a.sha256)) { console.error('bad sha256 for '+k); process.exit(1) }
  }
  console.log('  manifest:', Object.keys(m.artifacts).join(', '))"

# ------------------------------------------------------------------ 5. stage
echo "[5/7] stage (artifacts first, manifest last, atomic)"
# ORDER MATTERS. A manifest visible before the file it names is a client that
# downloads a 404; every move is a rename within the same filesystem so a reader
# sees the old file or the new one and never a partial.
install -d -m 755 "$CHANNEL_DIR"
for f in "$DIST/$DEB" "$DIST/$EXE"; do
  [ -f "$f" ] || continue
  b=$(basename "$f")
  install -m 644 "$f" "$CHANNEL_DIR/$b.tmp"
  mv "$CHANNEL_DIR/$b.tmp" "$CHANNEL_DIR/$b"
done
install -m 644 app-desktop/CHANGELOG.md "$CHANNEL_DIR/CHANGELOG.md.tmp"
mv "$CHANNEL_DIR/CHANGELOG.md.tmp" "$CHANNEL_DIR/CHANGELOG.md"
install -m 644 "$DIST/manifest.json" "$CHANNEL_DIR/manifest.json.tmp"
mv "$CHANNEL_DIR/manifest.json.tmp" "$CHANNEL_DIR/manifest.json"
echo "  staged into $CHANNEL_DIR"

# ------------------------------------------------------------------ 6. prune
echo "[6/7] prune (keep $KEEP versions)"
node - "$CHANNEL_DIR" "$KEEP" << 'EOF'
const fs = require('fs'), path = require('path')
const [dir, keepStr] = process.argv.slice(2)
const keep = Number(keepStr)
// Both shapes: Huginn-Desktop-Setup-0.1.0.exe and huginn-desktop-kt_0.1.0-1_amd64.deb
const VER = /[-_](\d+\.\d+\.\d+)(?:-\d+)?[_.]/
// The version a file BELONGS TO, or null. Deciding by `name.includes(version)`
// instead is a substring bug waiting for the first double-digit major: "1.0.0"
// is a substring of "11.0.0", so pruning 1.0.0 would delete 11.0.0's installer
// out from under a client that is mid-download.
const versionOf = (f) => (f.match(VER) || [])[1] || null
const vers = new Set(fs.readdirSync(dir).map(versionOf).filter(Boolean))
const cmp = (a, b) => {
  const A = a.split('.').map(Number), B = b.split('.').map(Number)
  return (B[0] - A[0]) || (B[1] - A[1]) || (B[2] - A[2])
}
const doomed = new Set([...vers].sort(cmp).slice(keep))
for (const f of fs.readdirSync(dir)) {
  if (doomed.has(versionOf(f))) { fs.unlinkSync(path.join(dir, f)); console.log('  pruned', f) }
}
EOF

# --------------------------------------------------- 7. verify through the wire
echo "[7/7] verify through the wire"
TOKEN=$(cat "$TOKEN_FILE")
SERVED=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL$FEED/manifest" \
  | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).version")
[ "$SERVED" = "$VERSION" ] || { echo "FAIL: served manifest says '$SERVED'" >&2; exit 1; }
for f in "$DIST/$DEB" "$DIST/$EXE"; do
  [ -f "$f" ] || continue
  b=$(basename "$f")
  LEN=$(curl -sf -o /dev/null -w '%{size_download}' \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL$FEED/$b")
  WANT=$(stat -c %s "$f")
  [ "$LEN" = "$WANT" ] || { echo "FAIL: $b served $LEN bytes, built $WANT" >&2; exit 1; }
  echo "  $b OK ($LEN bytes)"
done

# The ELECTRON channel is untouched, and this asserts it rather than assuming it.
# It is the one failure in this script that would reach the owner's running
# desktop app, so it is checked on every release.
ELECTRON=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/desktop/manifest" \
  | node -p "try{JSON.parse(require('fs').readFileSync(0,'utf8')).version||''}catch{''}" || true)
[ -n "$ELECTRON" ] || { echo "FAIL: /v1/desktop stopped serving a manifest" >&2; exit 1; }
echo "  /v1/desktop still serves the Electron client: $ELECTRON (untouched)"

# And finally through the CLIENT, not through curl — the updater itself fetches
# the manifest from its PINNED feed, downloads the artifact and verifies the
# sha256 the manifest carries. Nothing else proves those four programs agree.
#
# NEVER `gradle | tail` on a gate: the pipe's exit status is the LAST command's,
# so a failing build reads as green. Redirect, then read the code.
set +e
$GRADLE -q :app-desktop:updaterProbe \
  --args="--token-file $TOKEN_FILE --current 0.0.0 --expect $VERSION --cache-dir $BUILD/probe-cache" \
  > "$LOG.probe" 2>&1
PROBE_RC=$?
set -e
sed 's/^/  /' "$LOG.probe"; cat "$LOG.probe" >> "$LOG"
[ "$PROBE_RC" = 0 ] || { echo "FAIL: the updater could not fetch and verify $VERSION" >&2; exit 1; }

echo "== $VERSION live on $BASE_URL$FEED =="
