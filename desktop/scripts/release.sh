#!/usr/bin/env bash
# Release Huginn Desktop into huginn-appd's /v1/desktop channel.
#
# The whole channel is local: build here, stage into /var/lib/huginn-appd/
# desktop with the feed ymls written LAST via atomic rename, verify through
# the wire with the real Bearer token. No daemon restart — it reads per
# request. Windows NSIS builds on this box (wine64 + wine32:i386 under xvfb).
#
# Usage: scripts/release.sh [--linux-only] [--skip-tests]
set -euo pipefail
cd "$(dirname "$0")/.."

DESKTOP_DIR=/var/lib/huginn-appd/desktop
TOKEN_FILE=/etc/huginn-appd/token
BASE_URL=${HUGINN_APPD_URL:-http://100.97.198.90:8787}
KEEP=2

LINUX_ONLY=0
SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --linux-only) LINUX_ONLY=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

VERSION=$(node -p "require('./package.json').version")
echo "== releasing huginn-desktop $VERSION =="

echo "[1/6] gates"
# The version must have a changelog section a human wrote.
grep -q "^## $VERSION\$" CHANGELOG.md || {
  echo "REFUSING: CHANGELOG.md has no '## $VERSION' section" >&2; exit 1; }
# Refuse to overwrite a live version.
if [ -f "$TOKEN_FILE" ]; then
  LIVE=$(curl -sf -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
    "$BASE_URL/v1/desktop/manifest" 2>/dev/null | node -p \
    "try{JSON.parse(require('fs').readFileSync(0,'utf8')).version}catch{''}" || true)
  if [ "$LIVE" = "$VERSION" ]; then
    echo "REFUSING: $VERSION is already the live version" >&2; exit 1
  fi
fi
if [ "$SKIP_TESTS" = 0 ]; then
  # A glob matching nothing exits 0 — assert the count, not just the exit.
  TEST_OUT=$(npx vitest run 2>&1 | tail -20)
  TEST_COUNT=$(echo "$TEST_OUT" | sed -n 's/.*Tests  \([0-9]*\) passed.*/\1/p')
  if [ -z "$TEST_COUNT" ] || [ "$TEST_COUNT" -lt 100 ]; then
    echo "$TEST_OUT"; echo "REFUSING: test run did not report >=100 passes" >&2; exit 1
  fi
  echo "  $TEST_COUNT tests green"
  npx tsc --noEmit
  echo "  typecheck clean"
fi

echo "[2/6] build"
# Builder output goes to a log, never /dev/null — a silenced failing gate is
# the house's oldest trap (green-over-red).
BUILD_LOG=${TMPDIR:-/tmp}/huginn-desktop-build.log
npx electron-vite build
npx electron-builder --linux AppImage deb >> "$BUILD_LOG" 2>&1 || {
  tail -30 "$BUILD_LOG"; echo "REFUSING: linux build failed (full log: $BUILD_LOG)" >&2; exit 1; }
if [ "$LINUX_ONLY" = 0 ]; then
  xvfb-run -a npx electron-builder --win nsis >> "$BUILD_LOG" 2>&1 || {
    tail -30 "$BUILD_LOG"; echo "REFUSING: windows build failed (full log: $BUILD_LOG)" >&2; exit 1; }
fi
ls -l dist/*.exe dist/*.AppImage dist/*.deb 2>/dev/null || true

echo "[3/6] checksums + manifest"
EXE="dist/Huginn-Setup-$VERSION.exe"
APPIMAGE="dist/huginn-desktop-$VERSION.AppImage"
DEB="dist/huginn-desktop-$VERSION.deb"
for f in "$APPIMAGE" "$DEB"; do
  [ -f "$f" ] || { echo "REFUSING: missing artifact $f" >&2; exit 1; }
done
[ "$LINUX_ONLY" = 1 ] || [ -f "$EXE" ] || { echo "REFUSING: missing $EXE" >&2; exit 1; }
node - "$VERSION" "$LINUX_ONLY" << 'EOF' > dist/manifest.json
const fs = require('fs'), crypto = require('crypto')
const [version, linuxOnly] = process.argv.slice(2)
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')
const entry = (p) => ({ file: p.replace('dist/', ''), sha256: sha(p), size: fs.statSync(p).size })
const man = {
  version,
  releasedAt: new Date().toISOString(),
  linux: { appImage: entry(`dist/huginn-desktop-${version}.AppImage`), deb: entry(`dist/huginn-desktop-${version}.deb`) },
}
if (linuxOnly !== '1') man.windows = entry(`dist/Huginn-Setup-${version}.exe`)
const clog = fs.readFileSync('CHANGELOG.md', 'utf8')
const m = clog.split(`## ${version}`)[1]
if (m) man.notes = m.split(/\n## /)[0].trim().slice(0, 2000)
console.log(JSON.stringify(man, null, 1))
EOF
echo "  manifest written"

echo "[4/6] stage (artifacts first, feed ymls last, atomic)"
install -d -m 755 "$DESKTOP_DIR"
for f in "$EXE" "$APPIMAGE" "$DEB"; do
  [ -f "$f" ] || continue
  install -m 644 "$f" "$DESKTOP_DIR/$(basename "$f").tmp"
  mv "$DESKTOP_DIR/$(basename "$f").tmp" "$DESKTOP_DIR/$(basename "$f")"
done
install -m 644 CHANGELOG.md "$DESKTOP_DIR/CHANGELOG.md.tmp"
mv "$DESKTOP_DIR/CHANGELOG.md.tmp" "$DESKTOP_DIR/CHANGELOG.md"
install -m 644 dist/manifest.json "$DESKTOP_DIR/manifest.json.tmp"
# electron-builder's ymls go VERBATIM — never re-serialized.
install -m 644 dist/latest-linux.yml "$DESKTOP_DIR/latest-linux.yml.tmp"
if [ "$LINUX_ONLY" = 0 ]; then
  install -m 644 dist/latest.yml "$DESKTOP_DIR/latest.yml.tmp"
fi
mv "$DESKTOP_DIR/manifest.json.tmp" "$DESKTOP_DIR/manifest.json"
mv "$DESKTOP_DIR/latest-linux.yml.tmp" "$DESKTOP_DIR/latest-linux.yml"
[ "$LINUX_ONLY" = 1 ] || mv "$DESKTOP_DIR/latest.yml.tmp" "$DESKTOP_DIR/latest.yml"
echo "  staged into $DESKTOP_DIR"

echo "[5/6] prune (keep $KEEP versions)"
node - "$DESKTOP_DIR" "$KEEP" << 'EOF'
const fs = require('fs'), path = require('path')
const [dir, keepStr] = process.argv.slice(2)
const keep = Number(keepStr)
const vers = new Set()
for (const f of fs.readdirSync(dir)) {
  const m = f.match(/(\d+\.\d+\.\d+)\.(exe|AppImage|deb)$/)
  if (m) vers.add(m[1])
}
const sorted = [...vers].sort((a, b) =>
  b.split('.').map(Number).reduce((acc, x, i) => acc || x - a.split('.').map(Number)[i], 0))
for (const v of sorted.slice(keep)) {
  for (const f of fs.readdirSync(dir)) {
    if (f.includes(`-${v}.`)) { fs.unlinkSync(path.join(dir, f)); console.log('  pruned', f) }
  }
}
EOF

echo "[6/6] verify through the wire"
TOKEN=$(cat "$TOKEN_FILE")
SERVED=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/desktop/manifest" \
  | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).version")
[ "$SERVED" = "$VERSION" ] || { echo "FAIL: served manifest says '$SERVED'" >&2; exit 1; }
for f in "$EXE" "$APPIMAGE" "$DEB"; do
  [ -f "$f" ] || continue
  b=$(basename "$f")
  LEN=$(curl -sf -o /dev/null -w '%{size_download}' \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/desktop/$b")
  WANT=$(stat -c %s "$f")
  [ "$LEN" = "$WANT" ] || { echo "FAIL: $b served $LEN bytes, built $WANT" >&2; exit 1; }
  echo "  $b OK ($LEN bytes)"
done
YML=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/desktop/latest-linux.yml" | head -1)
echo "  latest-linux.yml: $YML"
echo "== $VERSION live on $BASE_URL/v1/desktop =="
