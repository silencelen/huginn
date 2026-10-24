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
cleanup() { kill "${SUP:-}" 2>/dev/null; kill "${CORE:-}" "${APPD:-}" "${SQ:-}" 2>/dev/null; pkill -f "$T/home/.local/share/huginn/versions/" 2>/dev/null; wait 2>/dev/null; tmux -L "huginn-core-e2e-$$" kill-server 2>/dev/null; rm -f "${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/huginn-core-e2e-$$"; rm -rf "$T"; }
trap cleanup EXIT

(cd "$REPO/core" && go build -o "$T/huginn" ./cmd/huginn \
  && go build -ldflags "-X github.com/silencelen/huginn/core/internal/version.Version=1.99.0-e2eold" -o "$T/huginn-old" ./cmd/huginn) \
  || { echo "build failed"; exit 99; }

# --- appd ------------------------------------------------------------------
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
TOK=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
mkdir -p "$T/appd/data" "$T/appd/state" "$T/appd/claude"; printf '%s\n' "$TOK" > "$T/appd/token"; chmod 600 "$T/appd/token"
# The push key is not HUGINN_APPD_-prefixed, so the unset list above misses it,
# and unset it falls back to the live key on a huginn host. Point it at nothing.
env $(env | grep -o '^HUGINN_APPD_[A-Z_]*' | sed 's/^/-u /') \
  HUGINN_FCM_KEY=/nonexistent/huginn-test-fcm-service-account.json \
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

# Security scan F8: a loopback port proves nothing about who holds it. A plain
# http listener on 127.0.0.1 (what any account here could bind while appd
# restarts) answering like a daemon must never be handed the token: the core
# dials it over TLS, the proof fails, and nothing is paired.
python3 - "$T/squatter.log" "$T/squatter.port" <<'PY' &
import http.server, sys
log, portf = sys.argv[1], sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def _any(self):
        with open(log, "a") as f:
            f.write("%s %s %s\n" % (self.command, self.path, self.headers.get("Authorization") or "-"))
        b = b'{"ok":true,"appdVersion":"9.9.9"}'
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    do_GET = do_POST = _any
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(portf, "w").write(str(s.server_address[1]))
s.serve_forever()
PY
SQ=$!
for _ in $(seq 50); do [ -s "$T/squatter.port" ] && break; sleep 0.1; done
: >> "$T/squatter.log"
SQOUT=$(HUGINN_APPD_TOKEN="$TOK" "$T/huginn" core pair --url "http://127.0.0.1:$(cat "$T/squatter.port")" 2>&1) \
  && bad "paired with a loopback listener that never proved itself" || ok "a loopback squatter is refused"
! grep -q "$TOK\|Bearer" "$T/squatter.log" && grep -q "HUGINN_APPD_PLAINTEXT=local" <<<"$SQOUT" \
  && grep -q "not paired" <<< "$("$T/huginn" core status)" \
  && ok "the squatter never saw the token, nothing was saved, and the refusal names the one way round it" \
  || { bad "squatter: $(cat "$T/squatter.log") / $SQOUT"; }
kill "$SQ" 2>/dev/null; wait "$SQ" 2>/dev/null; SQ=

HUGINN_APPD_TOKEN=wrong "$T/huginn" core pair --url "$A" >/dev/null 2>&1 && bad "a wrong token paired" || ok "wrong token refused, nothing saved"
HUGINN_APPD_TOKEN="$TOK" "$T/huginn" core pair --url "$A" && ok "paired" || bad "pair failed"
# The http:// typed for a daemon on this machine is proved over TLS on the same
# port, and saved in the form it is dialled, with the daemon's pin beside it.
python3 -c "import json;c=json.load(open('$XDG_CONFIG_HOME/huginn/core.json'));assert c['appd']['url']=='https://127.0.0.1:$PORT' and any(c.get('pins',{}).values()),c" \
  && ok "pairing on loopback went over TLS: core.json holds https://127.0.0.1:$PORT and the proved pin" || bad "loopback pairing was not proved: $(cat "$XDG_CONFIG_HOME/huginn/core.json")"
[ "$(stat -c %a "$XDG_CONFIG_HOME/huginn/secrets/appd-token" 2>/dev/null)" = 600 ] && ok "token file 0600" || bad "token file mode"
grep -q "$TOK" "$XDG_CONFIG_HOME/huginn/core.json" && bad "token leaked into core.json" || ok "core.json holds no token"

# /appd/* — the front ends' way to appd, with the core's token (D12).
SOCK="$XDG_RUNTIME_DIR/huginn/core.sock"
px() { curl -s --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' "$@"; }
for _ in $(seq 50); do px http://huginn-core/appd/v1/status | grep -q '"appdVersion"' && break; sleep 0.1; done
px http://huginn-core/appd/v1/status | grep -q '"appdVersion"' && ok "/appd/v1/status answers through the core" || bad "/appd proxy GET"
[ "$(px -o /dev/null -w '%{http_code}' http://huginn-core/appd/core/status)" = 404 ] && ok "/appd forwards /v1/ only" || bad "/appd forwarded a non-/v1 path"
[ "$(px -o /dev/null -w '%{http_code}' 'http://huginn-core/appd//evil/v1/x')" != 200 ] && ok "/appd refuses an origin-changing path" || bad "/appd //evil"
[ "$(px -o /dev/null -w '%{http_code}' http://huginn-core/appd/v1/chats/00000000-0000-0000-0000-000000000000)" = 404 ] && ok "/appd passes appd's own refusal through" || bad "/appd error passthrough"
timeout 3 curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://huginn-core/appd/v1/watch?stream=1' > "$T/watch.out" 2>/dev/null
grep -q '^event:\|^data:' "$T/watch.out" && ok "/appd streams the watch feed" || { bad "/appd stream"; head -c 300 "$T/watch.out"; }

# The route book (docs/design/desktop-via-core.md): another address of the same daemon,
# kept in core.json, used when the active one does not answer. A dead address
# pinned (no switching) makes requests fail; unpinned, they work again.
# Typed as http://, kept as https:// (a loopback address is proved like any other).
DEADPORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
DEAD="https://127.0.0.1:$DEADPORT"
"$T/huginn" core route add "http://127.0.0.1:$DEADPORT" --name dead --kind lan > "$T/route.out" 2>&1
grep -q "$DEAD" "$T/route.out" && grep -q 'paired on' "$T/route.out" && python3 -c "import json;c=json.load(open('$XDG_CONFIG_HOME/huginn/core.json'));assert c['appd']['routes'][0]['url']=='$DEAD',c['appd']" \
  && ok "core route add: kept in core.json in the form it is dialled, listed with the paired address" || { bad "core route add"; cat "$T/route.out"; }
"$T/huginn" core route pin "$DEAD" >/dev/null 2>&1
for _ in $(seq 50); do [ "$(px -o /dev/null -w '%{http_code}' http://huginn-core/appd/v1/status)" = 502 ] && break; sleep 0.1; done
[ "$(px -o /dev/null -w '%{http_code}' http://huginn-core/appd/v1/status)" = 502 ] && ok "a pinned dead address: requests fail, nothing switches" || bad "pinned dead route still answered"
"$T/huginn" core route pin none >/dev/null 2>&1
for _ in $(seq 50); do px http://huginn-core/appd/v1/status | grep -q '"appdVersion"' && break; sleep 0.1; done
px http://huginn-core/appd/v1/status | grep -q '"appdVersion"' && ok "unpinned: through the paired address again" || bad "after unpin"
"$T/huginn" core route remove "$DEAD" >/dev/null 2>&1
python3 -c "import json;c=json.load(open('$XDG_CONFIG_HOME/huginn/core.json'));assert not c['appd'].get('routes') and not c['appd'].get('pinned'),c['appd']" \
  && ok "core route remove: gone from core.json" || bad "core route remove"
# The local tier's token comes from the core (the desktop never holds one).
# (The core reads the tier's default place under this test's own HOME.)
LT="$HOME/.config/huginn-local/device/appd-token"
SEED=$(px -X POST http://huginn-core/core/local/seed)
[ "$(cat "$LT" 2>/dev/null)" = "$TOK" ] && [ "$(stat -c %a "$LT")" = 600 ] && grep -q '"url":"https://127.0.0.1:' <<<"$SEED" \
  && ok "core/local/seed: the tier's token file from the core's own store, 0600, and the address to use" || bad "local seed: $SEED"
rm -rf "$HOME/.config/huginn-local"   # no tier here for the uninstall check below to find

# huginn console against the real core and appd, in a pty (script(1)): it
# draws Home from live data and quits on q.
if command -v script >/dev/null 2>&1; then
  CON=$( (sleep 3; printf 'q') | timeout 15 script -qec "stty cols 100 rows 30; HUGINN_ASCII=1 NO_COLOR=1 $T/huginn console" /dev/null 2>&1 \
        | sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' )
  grep -q 'needs you' <<<"$CON" && grep -q '1 sessions' <<<"$CON" && grep -q 'live' <<<"$CON" \
    && ok "huginn console draws Home from the real core and appd" || { bad "console home"; printf '%s\n' "$CON" | tail -c 1500; }
fi

# huginn setup ssh: the SSH side for a machine set up some other way. The host
# comes from the core's pairing; ssh itself is a stub that refuses (no key on
# the host yet), so the check says the host does not know the key. A separate HOME keeps ~/.ssh out
# of the uninstall check below.
if command -v ssh-keygen >/dev/null 2>&1; then
  mkdir -p "$T/sshstub" "$T/sshhome"; printf '#!/bin/sh\necho "Permission denied (publickey)." >&2\nexit 255\n' > "$T/sshstub/ssh"; chmod +x "$T/sshstub/ssh"
  SU=$(HOME="$T/sshhome" PATH="$T/sshstub:$PATH" "$T/huginn" setup ssh </dev/null 2>&1)
  grep -qx 'Host huginn' "$T/sshhome/.ssh/config" && grep -q 'HostName 127.0.0.1' "$T/sshhome/.ssh/config" \
    && [ -f "$T/sshhome/.ssh/id_ed25519.pub" ] && grep -q 'authorized_keys' <<<"$SU" \
    && ok "huginn setup ssh: key, 'Host huginn' -> the paired host, and the line to authorize it" || { bad "setup ssh"; printf '%s\n' "$SU"; }
  SU2=$(HOME="$T/sshhome" PATH="$T/sshstub:$PATH" "$T/huginn" setup ssh </dev/null 2>&1); SURC=$?
  grep -q "is already in" <<<"$SU2" && grep -q 'does not know this key yet' <<<"$SU2" && [ "$SURC" = 1 ] && [ "$(grep -c '^Host huginn' "$T/sshhome/.ssh/config")" = 1 ] \
    && ok "a second setup ssh leaves the stanza alone and checks the key" || { bad "setup ssh twice (rc $SURC)"; printf '%s\n' "$SU2"; }
  # The address it is paired on does not carry ssh from here (seen
  # 2026-10-17): appd lists its own addresses (3.12.1), and setup ssh offers
  # each that answers on port 22 to ssh's host-key proof, in huginn's order.
  # huginn's list says where the server is on its own networks, not what
  # answers those addresses from here, so an entry moves only to an address
  # that holds the host key known for the one it names, whether this run has
  # just written it or it was already there. Otherwise it stays as it is and
  # the command that moves it is printed; --host, the person's own word, moves
  # it. The first stub times out for the paired address and refuses the key
  # everywhere else, so ssh never shows that a new address holds the old one's
  # host key; the second finds that key wherever it is asked to look for it.
  mkdir -p "$T/sshstub2" "$T/sshhome2"
  cat > "$T/sshstub2/ssh" <<'STUB'
#!/bin/sh
hn="$(awk '/^Host huginn$/{f=1;next} /^Host /{f=0} f&&$1=="HostName"{print $2}' "$HOME/.ssh/config" 2>/dev/null)"
[ "$hn" = 127.0.0.1 ] && { echo "ssh: connect to host 127.0.0.1 port 22: Connection timed out" >&2; exit 255; }
echo "Permission denied (publickey)." >&2; exit 255
STUB
  chmod +x "$T/sshstub2/ssh"
  ANY22=""
  for a in $(px http://core/appd/v1/status | sed -n 's/.*"addresses":\[\([^]]*\)\].*/\1/p' | tr -d '"' | tr ',' ' '); do
    case "$a" in *:*) continue ;; esac
    timeout 3 bash -c "exec 3<>/dev/tcp/$a/22" 2>/dev/null && { ANY22="$a"; break; }
  done
  if [ -n "$ANY22" ]; then
    SU3=$(HOME="$T/sshhome2" PATH="$T/sshstub2:$PATH" "$T/huginn" setup ssh </dev/null 2>&1)
    grep -q 'does not answer ssh from here' <<<"$SU3" && grep -q 'HostName 127.0.0.1' "$T/sshhome2/.ssh/config" \
      && grep -q 'huginn setup ssh --host ' <<<"$SU3" \
      && ok "a first setup ssh keeps its own new 'Host huginn' when nothing shows another address is the same machine" \
      || { bad "first setup ssh with another address that answers ssh"; printf '%s\n' "$SU3"; }
    sed -i 's/^  HostName .*/  HostName 127.0.0.1/' "$T/sshhome2/.ssh/config"
    SU3=$(HOME="$T/sshhome2" PATH="$T/sshstub2:$PATH" "$T/huginn" setup ssh </dev/null 2>&1)
    ALT=$(sed -n 's/.*huginn setup ssh --host \([^ ]*\)$/\1/p' <<<"$SU3" | head -1)
    grep -q 'does not answer ssh from here' <<<"$SU3" && grep -q 'HostName 127.0.0.1' "$T/sshhome2/.ssh/config" && [ -n "$ALT" ] \
      && ok "setup ssh leaves an existing 'Host huginn' alone and prints the command that points it at $ALT" \
      || { bad "setup ssh with an existing entry and another address that answers ssh"; printf '%s\n' "$SU3"; }
    SU4=$(HOME="$T/sshhome2" PATH="$T/sshstub2:$PATH" "$T/huginn" setup ssh --host "${ALT:-none}" </dev/null 2>&1)
    grep -qx "  HostName ${ALT:-none}" "$T/sshhome2/.ssh/config" && ! grep -q 'HostName 127.0.0.1' "$T/sshhome2/.ssh/config" \
      && grep -q 'does not know this key yet' <<<"$SU4" \
      && ok "setup ssh --host points 'Host huginn' at the address the person names" \
      || { bad "setup ssh --host ${ALT:-none}"; printf '%s\n' "$SU4"; }
    mkdir -p "$T/sshstub3" "$T/sshhome3"
    cat > "$T/sshstub3/ssh" <<'STUB'
#!/bin/sh
case " $* " in *" HostKeyAlias=127.0.0.1 "*) case " $* " in *" StrictHostKeyChecking=yes "*) exit 0 ;; esac ;; esac
hn="$(awk '/^Host huginn$/{f=1;next} /^Host /{f=0} f&&$1=="HostName"{print $2}' "$HOME/.ssh/config" 2>/dev/null)"
[ "$hn" = 127.0.0.1 ] && { echo "ssh: connect to host 127.0.0.1 port 22: Connection timed out" >&2; exit 255; }
echo "Permission denied (publickey)." >&2; exit 255
STUB
    chmod +x "$T/sshstub3/ssh"
    SU5=$(HOME="$T/sshhome3" PATH="$T/sshstub3:$PATH" "$T/huginn" setup ssh </dev/null 2>&1)
    grep -q 'has the host key this computer knows for 127.0.0.1' <<<"$SU5" && grep -qx "  HostName $ANY22" "$T/sshhome3/.ssh/config" \
      && ok "a first setup ssh moves its own new 'Host huginn' to $ANY22, where ssh finds the paired address's host key" \
      || { bad "first setup ssh with the paired address's host key at another address"; printf '%s\n' "$SU5"; }
  else
    echo "SKIP  no address of this host answers on port 22: the ssh-address search is untested here"
  fi
fi

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
curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events?role=tray&deliver=0' > /dev/null 2>&1 &
MUTE=$!; sleep 0.5
ST="$(curl -s --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' http://core/core/status)"
grep -q '"tray":true' <<< "$ST" && grep -q '"notifyClaim":false' <<< "$ST" \
  && ok "a tray that cannot show notifications leaves them with Telegram (no claim)" || bad "claim with a mute tray: $ST"
kill $MUTE 2>/dev/null; wait $MUTE 2>/dev/null; sleep 0.3
curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events?role=tray' > "$T/events.log" 2>/dev/null &
TRAY=$!
for _ in $(seq 50); do grep -q '^event: tray' "$T/events.log" 2>/dev/null && break; sleep 0.1; done
grep -q '"tray":true' <<< "$(curl -s --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' http://core/core/status)" \
  && ok "core sees the tray role attached" || bad "core status does not show the tray"
GUIVIEW="$(timeout 2 curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events' 2>/dev/null || true)"
grep -A1 '^event: coretray' <<< "$GUIVIEW" | grep -q '"attached":true' \
  && ok "a GUI subscriber is told the core tray is attached (it stands its own down)" || bad "no coretray event for the GUI"
# One icon, one menu (owner rule 2026-10-17): the app attaches as role=gui, the
# tray is told so and is given the app's Close to tray, and the tray's Quit
# Huginn reaches the app through the core, once.
curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events?role=gui' > "$T/gui.log" 2>/dev/null &
GUI=$!
for _ in $(seq 50); do grep -q '"attached":true,"closeToTray"' "$T/events.log" 2>/dev/null && break; sleep 0.1; done
curl -s --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' -H 'Content-Type: application/json' -d '{"closeToTray":false}' http://core/core/presence > /dev/null
for _ in $(seq 50); do grep -q '"attached":true,"closeToTray":false' "$T/events.log" && break; sleep 0.1; done
grep -q '"attached":true,"closeToTray":false' "$T/events.log" \
  && ok "the tray is told the app is open, with its Close to tray" || bad "no coregui for the tray: $(grep -A1 coregui "$T/events.log" | tail -2)"
C="$(curl -s -o /dev/null -w '%{http_code}' --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' -H 'Content-Type: application/json' -d '{"cmd":"quit"}' http://core/core/gui)"
for _ in $(seq 50); do grep -q '"cmd":"quit"' "$T/gui.log" && break; sleep 0.1; done
[ "$C" = 202 ] && grep -q '"cmd":"quit"' "$T/gui.log" \
  && ok "the tray's Quit Huginn reaches the app through the core" || bad "gui command: $C $(tail -2 "$T/gui.log")"
kill $GUI 2>/dev/null; wait $GUI 2>/dev/null
LATE="$(timeout 1 curl -sN --unix-socket "$SOCK" -H 'X-Huginn-Core-Proto: 1' 'http://core/core/events?role=gui' 2>/dev/null || true)"
grep -q 'gui.cmd' <<< "$LATE" && bad "a later app was replayed the quit" || ok "a later app is not replayed a command"
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

# --- P2 upgrade path: an older core under a supervisor (a stand-in for
# systemd's Restart=always) is restarted onto the new binary by the new
# binary's self-install, keeping its device id; a pre-tray install gets the
# tray autostart once, and a removed autostart is not put back.
kill "$CORE"; wait "$CORE" 2>/dev/null; CORE=
mkdir -p "$XDG_CONFIG_HOME/systemd/user"; : > "$XDG_CONFIG_HOME/systemd/user/huginn-core.service"
"$T/huginn-old" core self-install > "$T/up-old.log" 2>&1
STABLE="$HOME/.local/bin/huginn"
( while :; do "$STABLE" core run >> "$T/core.log" 2>&1; sleep 1; done ) & SUP=$!
for _ in $(seq 50); do grep -q '1.99.0-e2eold' <<< "$("$T/huginn" core status 2>/dev/null)" && break; sleep 0.2; done
grep -q 'huginn core 1.99.0-e2eold' <<< "$("$T/huginn" core status)" && ok "the old core runs from the stable path" || bad "old core not running"
DISPLAY=:e2e "$T/huginn" core self-install > "$T/up.log" 2>&1
grep -q "restarted huginn core: 1.99.0-e2eold -> " "$T/up.log" && ok "self-install restarted the running core onto the new binary" || { bad "no restart"; cat "$T/up.log"; }
grep -q "huginn core 1.99.0-e2eold" <<< "$("$T/huginn" core status)" && bad "still the old core" || ok "core status reports the new version"
grep -q "id $ID" <<< "$("$T/huginn" device status)" && ok "the upgraded core keeps the device id" || bad "id changed across the upgrade"
[ -f "$XDG_CONFIG_HOME/autostart/huginn-core-tray.desktop" ] && [ -f "$XDG_STATE_HOME/huginn/tray-autostart" ] \
  && ok "a pre-tray install got the tray autostart (once)" || { bad "no tray autostart after the upgrade"; cat "$T/up.log"; }
rm -f "$XDG_CONFIG_HOME/autostart/huginn-core-tray.desktop"
DISPLAY=:e2e "$T/huginn" core self-install > "$T/up2.log" 2>&1
[ -f "$XDG_CONFIG_HOME/autostart/huginn-core-tray.desktop" ] && bad "a removed tray autostart came back" || ok "a removed tray autostart stays removed"
grep -q restarted "$T/up2.log" && bad "a same-version self-install restarted the core" || ok "a same-version self-install leaves the core alone"

"$T/huginn" device off >/dev/null
GONE=""
for _ in $(seq 100); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" "$A/v1/devices/$ID"); [ "$code" = 404 ] && GONE=1 && break; sleep 0.1
done
[ -n "$GONE" ] && ok "device off retired the row on appd" || bad "row still present after off"
python3 -c "import json;c=json.load(open('$XDG_CONFIG_HOME/huginn/core.json'));d=c['device'];assert not d.get('id') and not d.get('unenrolOwed') and not d['enabled'],d" \
  && ok "core.json: off, no id, no debt" || bad "core.json after off"

# huginn uninstall: the whole stack, top down. Enrol again so there is a row
# for it to retire while the core and its token still exist.
"$T/huginn" device on --scope look >/dev/null
ID2=""
for _ in $(seq 100); do
  ID2=$(python3 -c "import json;print(json.load(open('$XDG_CONFIG_HOME/huginn/core.json'))['device'].get('id') or '')" 2>/dev/null)
  [ -n "$ID2" ] && break; sleep 0.1
done
[ -n "$ID2" ] && ok "re-enrolled for the uninstall check" || bad "no device id after device on"
kill "$SUP" 2>/dev/null; SUP=   # the loop would start a removed binary; the core it started keeps running
mkdir -p "$T/stub"; printf '#!/bin/sh\nexit 0\n' > "$T/stub/systemctl"; printf '#!/bin/sh\nexit 1\n' > "$T/stub/pkill"; chmod +x "$T/stub/"*
PATH="$T/stub:$PATH" "$STABLE" uninstall --yes > "$T/un.log" 2>&1; UNRC=$?
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" "$A/v1/devices/$ID2")
[ "$code" = 404 ] && ok "huginn uninstall retired the device row on appd first" || { bad "row $ID2 still on appd after uninstall ($code)"; cat "$T/un.log"; }
[ ! -e "$HOME/.local/share/huginn" ] && [ ! -e "$STABLE" ] && [ ! -e "$XDG_CONFIG_HOME/huginn" ] && [ ! -e "$HOME/.huginn" ] \
  && ok "huginn uninstall removed the core, its config and the CLI" || { bad "files left after uninstall"; cat "$T/un.log"; }
[ "$UNRC" = 0 ] && ok "huginn uninstall left nothing behind" || { bad "uninstall exit $UNRC"; cat "$T/un.log"; }

echo; [ "$FAILS" = 0 ] && echo "ALL PASS" || { echo "$FAILS FAILED"; echo "--- core.log"; tail -30 "$T/core.log"; }
exit "$FAILS"
