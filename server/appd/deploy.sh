#!/usr/bin/env bash
# Deploy huginn-appd to its runtime home and prove it came back up.
# The daemon RUNS from /opt/huginn-appd (a plain copy, so a mid-pull repo never
# serves half a version); this script is the only sanctioned way bits get there.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST=/opt/huginn-appd

node --check "$SRC/huginn-appd.js"
install -d "$DEST/lib"
install -m 0644 "$SRC/huginn-appd.js" "$DEST/huginn-appd.js"
install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
systemctl restart huginn-appd
sleep 2
TOKEN="$(cat /etc/huginn-appd/token)"
PING="$(curl -sf -H "Authorization: Bearer $TOKEN" http://100.97.198.90:8787/v1/ping)"
echo "[deploy] $PING"
grep -q '"ok":true' <<<"$PING" || { echo "[deploy] daemon did not come back healthy" >&2; exit 1; }
