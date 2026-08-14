#!/usr/bin/env bash
# Deploy huginn-appd to its runtime home and prove it came back up.
# The daemon RUNS from /opt/huginn-appd (a plain copy, so a mid-pull repo never
# serves half a version); this script is the only sanctioned way bits get there.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST=/opt/huginn-appd
TOKEN_FILE=/etc/huginn-appd/token

# The suite is the gate, not `node --check`. Syntax alone says nothing about a lost
# import or a broken detectPrompt/transcript reader — and those ping healthy while
# silently blinding both clients, which is the exact bug this repo shipped twice.
# It runs in ~2s and both CLIENT release scripts already gate on it; the script that
# puts the DAEMON into production had no reason to be the weakest of the three.
# The COUNT is asserted, not just the exit code: `node --test` with a glob matching
# nothing exits 0 having run zero tests, so a moved test/ would turn this green.
TEST_LOG="$(mktemp)"
node --test "$SRC"/test/*.test.js > "$TEST_LOG" 2>&1 || {
  tail -40 "$TEST_LOG"; echo "[deploy] REFUSING: appd tests failed (full log: $TEST_LOG)" >&2; exit 1; }
PASSED="$(grep -oE '^# pass [0-9]+' "$TEST_LOG" | grep -oE '[0-9]+' || echo 0)"
rm -f "$TEST_LOG"
[ "${PASSED:-0}" -gt 0 ] || { echo "[deploy] REFUSING: appd tests ran ZERO tests" >&2; exit 1; }
echo "[deploy] appd tests: $PASSED passed"

# lib/*.js is covered by the suite above; the entry point is only spawned by it, so
# a syntax error there would surface as a readiness-loop timeout rather than a line
# number. Keep the cheap, precise check.
node --check "$SRC/huginn-appd.js"

# Nothing else in the repo creates this file, and without it the daemon exits with
# "run deploy.sh first" — pointing at the script that had just aborted reading it.
# A fresh host was a closed loop. Minting it here also puts the required shape in
# one place: the daemon rejects anything under 32 chars.
if [ ! -s "$TOKEN_FILE" ]; then
  install -d -m 0700 "$(dirname "$TOKEN_FILE")"
  (umask 077; openssl rand -hex 32 > "$TOKEN_FILE")
  chmod 600 "$TOKEN_FILE"
  echo "[deploy] minted a bearer token at $TOKEN_FILE — copy it into each client."
  echo "[deploy] it is root-equivalent on this host; treat it like an SSH private key."
fi

install -d "$DEST/lib"
install -m 0644 "$SRC/huginn-appd.js" "$DEST/huginn-appd.js"
install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
systemctl restart huginn-appd
sleep 2
TOKEN="$(cat "$TOKEN_FILE")"
APPD_ADDR="${HUGINN_APPD_URL:-http://$(tailscale ip -4 2>/dev/null || echo 127.0.0.1):8787}"
PING="$(curl -sf -H "Authorization: Bearer $TOKEN" "$APPD_ADDR/v1/ping")"
echo "[deploy] $PING"
grep -q '"ok":true' <<<"$PING" || { echo "[deploy] daemon did not come back healthy" >&2; exit 1; }

# Mirror the release to GitHub, like ship.sh and release-desktop.sh already do.
# Without this appd was the only component whose public Releases page did not track
# what is actually deployed -- it silently reached 2.59.1 while appd-v2.55.0 was the
# newest published. Best-effort by design: the daemon is already live and healthy
# above, so a GitHub hiccup must not fail a good deploy. Skip: HUGINN_NO_GH_RELEASE=1.
if [ "${HUGINN_NO_GH_RELEASE:-}" != 1 ]; then
  VER="$(grep -m1 "^const VERSION" "$SRC/huginn-appd.js" | sed "s/.*'\(.*\)'.*/\1/")"
  REPO_ROOT="$(cd "$SRC/../.." && pwd)"
  if [ -n "$VER" ] && "$REPO_ROOT/scripts/github-release.sh" appd "$VER"; then
    echo "[deploy] GitHub release appd-v$VER updated"
  else
    echo "[deploy] WARNING: GitHub release failed for appd ${VER:-?} — the deploy above is unaffected." >&2
    echo "[deploy]          Most likely server/appd/CHANGELOG.md has no '## $VER' section: write the notes, then re-run." >&2
  fi
fi
