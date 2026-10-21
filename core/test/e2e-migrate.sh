#!/usr/bin/env bash
# End-to-end: first start ADOPTS an existing Node-runner device row instead of
# minting a second one (§11 Q7). Real appd from this tree; isolated HOME.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
T="$(mktemp -d)"; FAILS=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
cleanup() { kill "${CORE:-}" "${APPD:-}" 2>/dev/null; wait 2>/dev/null; tmux -L "huginn-core-mig-$$" kill-server 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
(cd "$REPO/core" && go build -o "$T/huginn" ./cmd/huginn) || exit 99

PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
TOK=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
mkdir -p "$T/appd/data" "$T/appd/state" "$T/appd/claude"; printf '%s\n' "$TOK" > "$T/appd/token"
# The push key is not HUGINN_APPD_-prefixed, so the unset list above misses it,
# and unset it falls back to the live key on a huginn host. Point it at nothing.
env $(env | grep -o '^HUGINN_APPD_[A-Z_]*' | sed 's/^/-u /') \
  HUGINN_FCM_KEY=/nonexistent/huginn-test-fcm-service-account.json \
  HUGINN_APPD_PORT=$PORT HUGINN_APPD_BIND=127.0.0.1 HUGINN_APPD_DATA="$T/appd/data" \
  HUGINN_APPD_TOKEN_FILE="$T/appd/token" HUGINN_APPD_STATE_DIR="$T/appd/state" \
  HUGINN_APPD_CLAUDE_DIR="$T/appd/claude" HUGINN_APPD_TMUX_SOCKET="huginn-core-mig-$$" HUGINN_APPD_WORKDIR="$T" \
  node "$REPO/server/appd/huginn-appd.js" > "$T/appd.log" 2>&1 &
APPD=$!
for _ in $(seq 100); do curl -s "http://127.0.0.1:$PORT/v1/ping" >/dev/null && break; sleep 0.1; done
A="http://127.0.0.1:$PORT"
api() { curl -s -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' "$@"; }
count() { api "$A/v1/devices" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["devices"]))'; }

# The machine as the Node runner left it: an enrolled row + its device.json/token.
OLD=$(api -X POST "$A/v1/devices" -d '{"name":"oldbox","platform":"linux","scope":"work","root":null,"version":"1.9.0","locked":false,"actWhileLocked":false}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
BEFORE=$(count)
export HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" XDG_STATE_HOME="$T/home/.local/state" XDG_RUNTIME_DIR="$T/run"
mkdir -p "$XDG_CONFIG_HOME/huginn" "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
printf '{"url":"%s","name":"oldbox","scope":"work","id":"%s","claude":"claude","actWhileLocked":true}\n' "$A" "$OLD" > "$XDG_CONFIG_HOME/huginn/device.json"
printf '%s\n' "$TOK" > "$XDG_CONFIG_HOME/huginn/appd-token"; chmod 600 "$XDG_CONFIG_HOME/huginn/"*

"$T/huginn" core run > "$T/core.log" 2>&1 & CORE=$!
for _ in $(seq 50); do [ -S "$XDG_RUNTIME_DIR/huginn/core.sock" ] && break; sleep 0.1; done
ST=""
for _ in $(seq 100); do ST="$("$T/huginn" device status 2>/dev/null)"; grep -q "enrolled true" <<< "$ST" && break; sleep 0.1; done
grep -q "id $OLD" <<< "$ST" && ok "adopted the Node runner's id $OLD" || { bad "did not adopt: $ST"; cat "$T/core.log"; }
grep -q "scope work" <<< "$ST" && grep -q "act while locked true" <<< "$ST" && ok "kept its scope and act-while-locked" || bad "settings not carried: $ST"
[ "$(count)" = "$BEFORE" ] && ok "appd still has $BEFORE row(s): no second row" || bad "rows $BEFORE -> $(count)"
grep -q "pair" <<< "$("$T/huginn" core status)" && bad "core thinks it is unpaired" || ok "paired from the imported token"
[ -f "$XDG_CONFIG_HOME/huginn/device.json" ] && ok "source device.json left untouched" || bad "source file deleted"
grep -q "adopting $OLD from the runner" "$T/core.log" && ok "import logged" || bad "import not logged"

kill "$CORE"; wait "$CORE" 2>/dev/null
"$T/huginn" core run >> "$T/core.log" 2>&1 & CORE=$!
sleep 1
[ "$(grep -c 'first start' "$T/core.log")" = 1 ] && ok "import ran once, not on every start" || bad "import re-ran"

echo; [ "$FAILS" = 0 ] && echo "ALL PASS" || echo "$FAILS FAILED"
exit "$FAILS"
