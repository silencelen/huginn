#!/usr/bin/env bash
# End-to-end: a real appd from this tree + the Go core under an isolated HOME.
#   pair → device on (own) → appd dispatches a chat to the device → the core runs
#   a fake claude → the answer lands in appd's transcript → device off retires
#   the row → a restarted core keeps no stale id.
# Exit status: number of failed checks. Run from anywhere; needs go, node, curl.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
T="$(mktemp -d)"; FAILS=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
cleanup() { kill "${CORE:-}" "${APPD:-}" 2>/dev/null; wait 2>/dev/null; tmux -L "huginn-core-e2e-$$" kill-server 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT

(cd "$REPO/core" && go build -o "$T/huginn" ./cmd/huginn) || { echo "build failed"; exit 99; }

# --- appd ------------------------------------------------------------------
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
TOK=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
mkdir -p "$T/appd/data" "$T/appd/state" "$T/appd/claude"; printf '%s\n' "$TOK" > "$T/appd/token"; chmod 600 "$T/appd/token"
env $(env | grep -o '^HUGINN_APPD_[A-Z_]*' | sed 's/^/-u /') \
  HUGINN_APPD_PORT=$PORT HUGINN_APPD_BIND=127.0.0.1 HUGINN_APPD_DATA="$T/appd/data" \
  HUGINN_APPD_TOKEN_FILE="$T/appd/token" HUGINN_APPD_STATE_DIR="$T/appd/state" \
  HUGINN_APPD_CLAUDE_DIR="$T/appd/claude" HUGINN_APPD_TMUX_SOCKET="huginn-core-e2e-$$" HUGINN_APPD_WORKDIR="$T" \
  node "$REPO/server/appd/huginn-appd.js" > "$T/appd.log" 2>&1 &
APPD=$!
for _ in $(seq 100); do curl -s "http://127.0.0.1:$PORT/v1/ping" >/dev/null && break; sleep 0.1; done
api() { curl -s -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' "$@"; }
A="http://127.0.0.1:$PORT"

# --- the core, isolated -------------------------------------------------------
export HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" XDG_STATE_HOME="$T/home/.local/state" XDG_RUNTIME_DIR="$T/run"
mkdir -p "$HOME" "$XDG_RUNTIME_DIR" "$T/bin"; chmod 700 "$XDG_RUNTIME_DIR"
cat > "$T/bin/claude" <<'FAKE'
#!/bin/sh
printf '%s\n' "$@" > "$HOME/claude-argv"
cat > "$HOME/claude-prompt"
echo '{"type":"system","subtype":"init","session_id":"11111111-2222-3333-4444-555555555555"}'
echo '{"type":"assistant","message":{"content":[{"type":"text","text":"Hello from the Go core."}]}}'
echo '{"type":"result","subtype":"success","is_error":false,"result":"Hello from the Go core.","session_id":"11111111-2222-3333-4444-555555555555"}'
FAKE
chmod 755 "$T/bin/claude"; export PATH="$T/bin:$PATH"
start_core() { "$T/huginn" core run >> "$T/core.log" 2>&1 & CORE=$!; for _ in $(seq 50); do [ -S "$XDG_RUNTIME_DIR/huginn/core.sock" ] && return; sleep 0.1; done; }
start_core
[ -S "$XDG_RUNTIME_DIR/huginn/core.sock" ] && ok "core listens on its socket" || bad "no core socket"
[ "$(stat -c %a "$XDG_RUNTIME_DIR/huginn")" = 700 ] && ok "runtime dir 0700" || bad "runtime dir mode"

grep -q "not paired" <<< "$("$T/huginn" core status)" && ok "status says not paired" || bad "status before pairing"
HUGINN_APPD_TOKEN=wrong "$T/huginn" core pair --url "$A" >/dev/null 2>&1 && bad "a wrong token paired" || ok "wrong token refused, nothing saved"
HUGINN_APPD_TOKEN="$TOK" "$T/huginn" core pair --url "$A" && ok "paired" || bad "pair failed"
[ "$(stat -c %a "$XDG_CONFIG_HOME/huginn/secrets/appd-token" 2>/dev/null)" = 600 ] && ok "token file 0600" || bad "token file mode"
grep -q "$TOK" "$XDG_CONFIG_HOME/huginn/core.json" && bad "token leaked into core.json" || ok "core.json holds no token"

"$T/huginn" device on --scope own --name e2e-box >/dev/null || bad "device on"
ID=""
for _ in $(seq 100); do
  ID=$("$T/huginn" device status 2>/dev/null | sed -n 's/.*id \([^ ]*\) · enrolled true.*/\1/p'); [ -n "$ID" ] && break; sleep 0.1
done
[ -n "$ID" ] && ok "enrolled as $ID" || { bad "never enrolled"; tail -20 "$T/core.log"; }
api "$A/v1/devices" | python3 -c "import json,sys; d=[x for x in json.load(sys.stdin)['devices'] if x['id']=='$ID'][0]; assert d['scope']=='own' and d['name']=='e2e-box' and d['online'], d" \
  && ok "appd sees the row: own, e2e-box, online" || bad "appd's view of the row"

CHAT=$(api -X POST "$A/v1/chats" -d "{\"mode\":\"act\",\"host\":\"$ID\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
api -X POST "$A/v1/chats/$CHAT/messages" -d '{"text":"say hello"}' >/dev/null
GOT=""
for _ in $(seq 150); do
  GOT=$(api "$A/v1/chats/$CHAT" | python3 -c 'import json,sys;c=json.load(sys.stdin);print("done" if (not c.get("running")) and any("Hello from the Go core" in (m.get("text") or "") for m in c.get("messages",[])) else "")')
  [ "$GOT" = done ] && break; sleep 0.2
done
[ "$GOT" = done ] && ok "act chat ran on the device and the answer is in appd's transcript" || { bad "the answer never arrived"; tail -20 "$T/core.log"; }
grep -qx -- "--allowedTools" "$HOME/claude-argv" && grep -q "Bash" "$HOME/claude-argv" && ok "act argv carries the act tool list" || bad "argv: $(tr '\n' ' ' < "$HOME/claude-argv")"
grep -q "say hello" "$HOME/claude-prompt" && ok "prompt arrived on stdin" || bad "prompt not on stdin"

"$T/huginn" device on --scope look >/dev/null
sleep 0.3
for _ in $(seq 70); do
  S=$(api "$A/v1/devices" | python3 -c "import json,sys; print([x for x in json.load(sys.stdin)['devices'] if x['id']=='$ID'][0]['scope'])"); [ "$S" = look ] && break; sleep 0.1
done
[ "$S" = look ] && ok "a scope change reached appd without a restart (E1)" || bad "scope still $S"

# --- P2: the tray role's feed. A fake tray attaches to /core/events?role=tray;
# a session that starts waiting must produce a "needs you" notice, and its end a
# withdrawal — decided by the core from appd's watch stream, no GUI involved.
SOCK="$XDG_RUNTIME_DIR/huginn/core.sock"
curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events?role=tray' > "$T/events.log" 2>/dev/null &
TRAY=$!
for _ in $(seq 50); do grep -q '^event: tray' "$T/events.log" 2>/dev/null && break; sleep 0.1; done
grep -q '"tray":true' <<< "$(curl -s --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' http://core/core/status)" \
  && ok "core sees the tray role attached" || bad "core status does not show the tray"
GUIVIEW="$(timeout 2 curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events' 2>/dev/null || true)"
grep -A1 '^event: coretray' <<< "$GUIVIEW" | grep -q '"attached":true' \
  && ok "a GUI subscriber is told the core tray is attached (it stands its own down)" || bad "no coretray event for the GUI"
tmux -L "huginn-core-e2e-$$" new-session -d -s notifye2e 'sleep 600'
sleep 2   # let a digest with the session in it seed the baseline first
printf '{"state":"attention","ts":%s}' "$(date +%s%3N)" > "$T/appd/state/notifye2e"
for _ in $(seq 100); do grep -q '"key":"sess:notifye2e"' "$T/events.log" && break; sleep 0.2; done
grep '^data: .*"action":"post"' "$T/events.log" | grep -q '"title":"notifye2e needs you".*"urgent":true' \
  && ok "a waiting session becomes an urgent 'needs you' notice for the tray" || { bad "no attention notice"; tail -5 "$T/events.log"; }
grep '^data: ' "$T/events.log" | grep -q '"state":"attention".*"attention":\["notifye2e"\]' \
  && ok "the tray summary turns to attention with the session in the menu" || bad "no attention summary"
tmux -L "huginn-core-e2e-$$" kill-session -t =notifye2e
for _ in $(seq 100); do grep -q '"action":"withdraw","key":"sess:notifye2e"' "$T/events.log" && break; sleep 0.2; done
grep -q '"action":"withdraw","key":"sess:notifye2e"' "$T/events.log" \
  && ok "the session ending withdraws the notice" || bad "no withdrawal"
kill $TRAY 2>/dev/null

kill "$CORE"; wait "$CORE" 2>/dev/null; start_core
grep -q "id $ID" <<< "$("$T/huginn" device status)" && ok "a restarted core keeps its id (no second row)" || bad "id changed across restart"

"$T/huginn" device off >/dev/null
GONE=""
for _ in $(seq 100); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" "$A/v1/devices/$ID"); [ "$code" = 404 ] && GONE=1 && break; sleep 0.1
done
[ -n "$GONE" ] && ok "device off retired the row on appd" || bad "row still present after off"
python3 -c "import json;c=json.load(open('$XDG_CONFIG_HOME/huginn/core.json'));d=c['device'];assert not d.get('id') and not d.get('unenrolOwed') and not d['enabled'],d" \
  && ok "core.json: off, no id, no debt" || bad "core.json after off"

echo; [ "$FAILS" = 0 ] && echo "ALL PASS" || { echo "$FAILS FAILED"; echo "--- core.log"; tail -30 "$T/core.log"; }
exit "$FAILS"
