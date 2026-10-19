#!/usr/bin/env bash
# test-appd-pin.sh — nothing on the host hands the appd token to a listener that
# has not proved it is the daemon.
#
# WHY THIS EXISTS: 8787 is an unprivileged port. The host tools (huginn-rounds,
# -devices, -archive, -llm, -headroom, -projects), the desktop release's wire
# checks and the command both CLI clients run on the host over ssh all called
# http://127.0.0.1:8787 with the root-equivalent bearer token - so while
# huginn-appd was stopped or restarting, any account on the host that bound the
# port first was handed the token by the first call. They now speak HTTPS pinned
# to the key in appd's own certificate, which only root can read, and make no
# call at all when it cannot be read. This holds them there, against a stand-in
# that answers plain HTTP or TLS on one port, the way appd does:
#   [1/3] the daemon's key: every call works, over TLS, carrying the token;
#   [2/3] another key: every caller refuses and says so, and the impostor sees
#         a handshake and nothing after it - no request, so no token;
#   [3/3] no readable certificate: every caller refuses and says so, and
#         nothing reaches the daemon's port at all.
# Every key and token here is a fresh scratch one; the real ones are never read.
#
# Usage: scripts/test-appd-pin.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server client mobile/scripts | tar -x -C DIR) to see the
#   gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

for c in openssl python3 curl; do
  command -v "$c" >/dev/null 2>&1 || { skip "every check (no $c here)"; exit 0; }
done
T="$(mktemp -d)"
PIDS=()
_cleanup() {
  local p
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill "$p" 2>/dev/null; done
  rm -rf "$T"
}
trap _cleanup EXIT

# ------------------------------------------------------------------ the parts
# Two key pairs: the daemon's, and an impostor's. The callers are only ever told
# where the DAEMON's certificate is.
for k in daemon impostor; do
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 \
    -subj /CN=huginn-appd -keyout "$T/$k.key" -out "$T/$k.crt" >/dev/null 2>&1 \
    || { skip "every check (openssl could not make a key pair)"; exit 0; }
done
od -An -tx1 -N24 /dev/urandom | tr -d ' \n' > "$T/token"; echo >> "$T/token"; chmod 600 "$T/token"

cat > "$T/routes" <<'ROUTES'
GET /v1/rounds 200 {"rounds":[]}
GET /v1/devices 200 {"devices":[{"id":"d1","name":"box","online":true,"scope":"own","platform":"linux"}]}
PATCH /v1/devices/d1 200 {"id":"d1","name":"box","watch":true}
GET /v1/archive 200 {"max":64,"archives":[{"id":"a1","tmuxName":"sess","title":"Old work","transcriptPresent":true,"archivedAt":1}]}
POST /v1/sessions/sess/archive?mode=graceful 202 {"ok":true,"archived":true}
POST /v1/archive/a1/revive 201 {"ok":true,"name":"sess2","resumed":true,"restoredTranscript":false}
GET /v1/models?local=1 200 {"models":[{"id":"local:box:m","family":"local","available":true}]}
POST /v1/chats 200 {"id":"c1"}
POST /v1/chats/c1/messages 200 {"ok":true}
GET /v1/chats/c1 200 {"running":false,"messages":[{"type":"assistant","text":"forty-two"}]}
DELETE /v1/chats/c1 200 {"ok":true}
GET /v1/headroom 200 {"marker":"headroom-answered"}
GET /v1/projects 200 {"projects":[]}
POST /v1/sessions/sess/soft-end 200 {"ok":true,"phrase":"WRAPUP","auto":true}
GET /v1/desktop-kt/manifest 200 {"version":"1.2.3"}
GET /v1/desktop-kt/a.deb 200 abc
GET /v1/desktop-kt/a.tar.gz 200 abc
GET /v1/desktop-kt/a.pkg.tar.zst 200 abc
GET /v1/desktop-kt/a.exe 200 abc
ROUTES

# The stand-in: plain HTTP or TLS on one port, told apart by the first byte as
# appd tells them apart, so a caller that still spoke plain HTTP is answered too
# - and caught. It logs every connection, and every request with whether it came
# over TLS and whether it carried the token.
cat > "$T/stub.py" <<'STUB'
import socket, ssl, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
routes_file, port_file, log_file, token_file, cert, key = sys.argv[1:7]
want = "Bearer " + open(token_file).read().strip()
routes = {}
for line in open(routes_file):
    if line.strip():
        m, p, s, b = line.rstrip("\n").split(" ", 3)
        routes.setdefault((m, p), (int(s), b))
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(cert, key)
lock = threading.Lock()
def log(s):
    with lock, open(log_file, "a") as fh:
        fh.write(s + "\n")
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def answer(self, m):
        n = int(self.headers.get("content-length") or 0)
        if n:
            self.rfile.read(n)
        tls = isinstance(self.connection, ssl.SSLSocket)
        log("REQ %s %s %s %s" % (m, self.path, "tls" if tls else "plain",
                                 "token" if self.headers.get("authorization") == want else "none"))
        s, b = routes.get((m, self.path), (404, '{"error":"no route"}'))
        b = b.encode()
        self.send_response(s)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    def do_GET(self): self.answer("GET")
    def do_POST(self): self.answer("POST")
    def do_PATCH(self): self.answer("PATCH")
    def do_DELETE(self): self.answer("DELETE")
    def log_message(self, *a): pass
class S(ThreadingHTTPServer):
    daemon_threads = True
    def finish_request(self, request, client_address):
        try:
            request.settimeout(10)
            first = request.recv(1, socket.MSG_PEEK)
        except OSError:
            return
        if first[:1] == b"\x16":
            log("CONN tls")
            try:
                request = ctx.wrap_socket(request, server_side=True)
            except (ssl.SSLError, OSError):
                return
        else:
            log("CONN plain")
        try:
            self.RequestHandlerClass(request, client_address, self)
        except (ssl.SSLError, OSError):
            pass
srv = S(("127.0.0.1", 0), H)
with open(port_file, "w") as fh:
    fh.write(str(srv.server_port))
srv.serve_forever()
STUB
for k in daemon impostor; do
  python3 "$T/stub.py" "$T/routes" "$T/$k.port" "$T/$k.log" "$T/token" "$T/$k.crt" "$T/$k.key" 2>/dev/null &
  PIDS+=("$!")
done
for k in daemon impostor; do
  for _ in $(seq 1 50); do [ -s "$T/$k.port" ] && break; sleep 0.1; done
  [ -s "$T/$k.port" ] || { skip "every check (the $k stand-in never bound a port)"; exit 0; }
done

# Copies of the host tools, one set per stand-in: the scratch token for the real
# path, and the stand-in's port for 8787 - in any scheme, so an older tree that
# still spoke plain HTTP is aimed at the same listener and caught there.
mkdir -p "$T/lib" "$T/sbin"
cp "$TREE"/server/lib/state-table.* "$T/lib/"
# Nothing here may read this host's own unit: a systemctl that knows nothing.
printf '#!/bin/sh\nexit 0\n' > "$T/sbin/systemctl"; chmod 755 "$T/sbin/systemctl"
TOOLS=(huginn-rounds huginn-devices huginn-archive huginn-llm huginn-headroom huginn-projects)
for k in daemon impostor; do
  mkdir -p "$T/bin-$k"
  for t in "${TOOLS[@]}"; do
    sed -e "s#/etc/huginn-appd/token#$T/token#g" -e "s#127\\.0\\.0\\.1:8787#127.0.0.1:$(cat "$T/$k.port")#g" \
      "$TREE/server/bin/$t" > "$T/bin-$k/$t"
    chmod 755 "$T/bin-$k/$t"
  done
done

# The desktop release's steps 1 (the downgrade gate) and 7 (verify through the
# wire), cut out of the script: the whole of it needs root, gradle and wine.
REL="$TREE/mobile/scripts/release-desktop.sh"
mkdir -p "$T/dist"; for b in a.deb a.tar.gz a.pkg.tar.zst a.exe; do printf 'abc' > "$T/dist/$b"; done
rel_step() {   # $1 = 1|7, $2 = port -> a runnable script on stdout
  echo 'set -euo pipefail'
  printf 'TOKEN_FILE=%q BASE_URL=%q FEED=/v1/desktop-kt DIST=%q VERSION=%q\n' \
    "$T/token" "https://127.0.0.1:$2" "$T/dist" "$([ "$1" = 1 ] && echo 1.2.4 || echo 1.2.3)"
  echo 'DEB=a.deb TGZ=a.tar.gz ARCHPKG=a.pkg.tar.zst EXE=a.exe'
  awk '/^appd_pin\(\) \{/{on=1} on{print} on && /^\}/{exit}' "$REL"
  grep -E '^appd_curl\(\) \{' "$REL" || true
  if [ "$1" = 1 ]; then
    awk '/^if \[ -f "\$TOKEN_FILE" \]; then$/{on=1} on{print} on && /^fi$/{exit}' "$REL"
    echo 'echo "downgrade gate passed: $LIVE -> $VERSION"'
  else
    awk '/^TOKEN=\$\(cat "\$TOKEN_FILE"\)$/{on=1} on{print} on && /^done$/{exit}' "$REL"
  fi
}
for k in daemon impostor; do
  rel_step 1 "$(cat "$T/$k.port")" > "$T/release-1-$k.sh"
  rel_step 7 "$(cat "$T/$k.port")" > "$T/release-7-$k.sh"
done

# The clients' own composed command, run the way ssh would run it on the host:
# huginn.sh's through `sh -c` (the host account's POSIX shell), huginn.ps1's
# base64 payload through `bash -s`. Only 127.0.0.1:8787 and the token path are
# rewritten, to this lane's stand-in and scratch token.
cat > "$T/sbin/ssh" <<'SSH'
#!/usr/bin/env bash
cmd="${!#}" run=sh
case "$cmd" in
  *"base64 -d"*) cmd="$(sed -E 's/^echo ([A-Za-z0-9+/=]+).*/\1/' <<<"$cmd" | base64 -d)"; run=bash ;;
esac
cmd="${cmd//127.0.0.1:8787/127.0.0.1:$PIN_PORT}"
cmd="${cmd//\/etc\/huginn-appd\/token/$PIN_TOKEN}"
if [ "$run" = sh ]; then exec sh -c "$cmd"; fi
printf '%s\n' "$cmd" | exec bash -s
SSH
chmod 755 "$T/sbin/ssh"
# kill may fall back to raw tmux when the daemon cannot be reached; never the real one.
printf '#!/bin/sh\necho "tmux $*"\nexit 0\n' > "$T/sbin/tmux"; chmod 755 "$T/sbin/tmux"

# name|args|the requests it must make|a line its output must hold
CASES=(
  "huginn-rounds||GET /v1/rounds|No rounds yet"
  "huginn-devices||GET /v1/devices|box"
  "huginn-devices|watch box on|GET /v1/devices;PATCH /v1/devices/d1|box is now watched"
  "huginn-archive||GET /v1/archive|1 archived"
  "huginn-archive|sess|POST /v1/sessions/sess/archive?mode=graceful|archived sess"
  "huginn-archive|revive sess|GET /v1/archive;POST /v1/archive/a1/revive|revived as sess2"
  "huginn-llm|what is six times seven|GET /v1/models?local=1;POST /v1/chats;POST /v1/chats/c1/messages;GET /v1/chats/c1;DELETE /v1/chats/c1|forty-two"
  "huginn-headroom|--json|GET /v1/headroom|headroom-answered"
  "huginn-projects||GET /v1/projects|No projects"
  "release-desktop.sh step 1||GET /v1/desktop-kt/manifest|downgrade gate passed: 1.2.3 -> 1.2.4"
  "release-desktop.sh step 7||GET /v1/desktop-kt/manifest;GET /v1/desktop-kt/a.deb;GET /v1/desktop-kt/a.tar.gz;GET /v1/desktop-kt/a.pkg.tar.zst;GET /v1/desktop-kt/a.exe|a.exe OK (3 bytes)"
  "huginn.sh: huginn end|sess|POST /v1/sessions/sess/soft-end|WRAPUP"
  "huginn.ps1: huginn end|sess|POST /v1/sessions/sess/soft-end|WRAPUP"
)

run_case() {   # $1 = name, $2 = args, $3 = daemon|impostor, $4 = the cert the caller is told about
  local name="$1" args="$2" k="$3" cert="$4"
  local env=(env PATH="$T/sbin:$PATH" HUGINN_APPD_TLS_CERT="$cert" HUGINN_APPD_DATA="$T/no-such-data"
             PIN_PORT="$(cat "$T/$k.port")" PIN_TOKEN="$T/token" NO_COLOR=1)
  case "$name" in
    "release-desktop.sh step 1") "${env[@]}" bash "$T/release-1-$k.sh" ;;
    "release-desktop.sh step 7") "${env[@]}" bash "$T/release-7-$k.sh" ;;
    "huginn.sh: huginn end")
      "${env[@]}" bash -c '. "$1/client/huginn.sh" >/dev/null 2>&1; huginn end "$2"' sh "$TREE" "$args" ;;
    "huginn.ps1: huginn end")
      "${env[@]}" pwsh -NoProfile -Command ". '$TREE/client/huginn.ps1'; huginn end '$args'" 2>&1 | tr -d '\r' ;;
    *)
      # shellcheck disable=SC2086  # args are words on purpose
      "${env[@]}" "$T/bin-$k/$name" $args ;;
  esac
}
have() {   # $1 = case name -> 0 when this host can run it, else says why not
  case "$1" in
    huginn-projects|release-desktop.sh*) command -v node >/dev/null 2>&1 || { skip "$1 (no node here)"; return 1; } ;;
    huginn.ps1*) command -v pwsh >/dev/null 2>&1 || { skip "$1 (no pwsh here)"; return 1; } ;;
  esac
  return 0
}
reqs_of() { grep '^REQ ' "$1" 2>/dev/null | cut -d' ' -f2-; }

echo "[1/3] the daemon's own key: every caller gets through, over TLS, with the token"
for c in "${CASES[@]}"; do
  IFS='|' read -r name args reqs expect <<<"$c"
  have "$name" || continue
  : > "$T/daemon.log"
  out="$(run_case "$name" "$args" daemon "$T/daemon.crt" 2>&1)"; rc=$?
  want="$(tr ';' '\n' <<<"$reqs" | sed 's/$/ tls token/')"
  got="$(reqs_of "$T/daemon.log")"
  if [ "$got" != "$want" ]; then
    bad "$name${args:+ $args}: the daemon saw [$(tr '\n' ';' <<<"$got")], wanted [$(tr '\n' ';' <<<"$want")] (exit $rc: $(head -c 300 <<<"$out"))"
  elif ! grep -qF -- "$expect" <<<"$out"; then
    bad "$name${args:+ $args}: answered, but the output lost '$expect' (exit $rc): $(head -c 300 <<<"$out")"
  else
    ok "$name${args:+ $args}: $(grep -c . <<<"$got") request(s), all over TLS to the pinned key"
  fi
done

echo "[2/3] another key on the port: nothing is sent to it, and the caller says why"
for c in "${CASES[@]}"; do
  IFS='|' read -r name args reqs expect <<<"$c"
  have "$name" || continue
  : > "$T/impostor.log"
  out="$(run_case "$name" "$args" impostor "$T/daemon.crt" 2>&1)"; rc=$?
  got="$(reqs_of "$T/impostor.log")"
  conns="$(grep -c '^CONN' "$T/impostor.log" 2>/dev/null)"
  if [ -n "$got" ]; then
    bad "$name${args:+ $args}: the impostor was sent [$(tr '\n' ';' <<<"$got")]"
  elif [ "${conns:-0}" -lt 1 ]; then
    bad "$name${args:+ $args}: never reached the impostor at all, so this proves nothing (exit $rc: $(head -c 300 <<<"$out"))"
  elif [ "$name" = "release-desktop.sh step 1" ]; then
    # The downgrade gate treats a channel it cannot read as a fresh one (step 7
    # is what refuses); the point here is only that nothing was sent.
    ok "$name: the impostor saw a handshake and no request"
  elif [ "$rc" = 0 ]; then
    bad "$name${args:+ $args}: exited 0 against a listener that is not the daemon: $(head -c 300 <<<"$out")"
  elif ! grep -qE 'not huginn-appd|pinned public key' <<<"$out"; then
    bad "$name${args:+ $args}: refused (exit $rc), but did not say the listener is not the daemon: $(head -c 300 <<<"$out")"
  else
    ok "$name${args:+ $args}: refused (exit $rc); the impostor saw a handshake and no request"
  fi
done

echo "[3/3] no readable certificate: no call at all, and the caller says why"
for c in "${CASES[@]}"; do
  IFS='|' read -r name args reqs expect <<<"$c"
  have "$name" || continue
  : > "$T/daemon.log"
  out="$(run_case "$name" "$args" daemon "$T/no-such-cert.pem" 2>&1)"; rc=$?
  if [ -s "$T/daemon.log" ]; then
    bad "$name${args:+ $args}: with no certificate to pin, it still called the port: [$(tr '\n' ';' < "$T/daemon.log")]"
  elif [ "$rc" = 0 ]; then
    bad "$name${args:+ $args}: exited 0 with no certificate to check the daemon against: $(head -c 300 <<<"$out")"
  elif ! grep -q "certificate" <<<"$out"; then
    bad "$name${args:+ $args}: refused (exit $rc), but did not name the certificate: $(head -c 300 <<<"$out")"
  else
    ok "$name${args:+ $args}: refused (exit $rc) before any connection"
  fi
done

echo
[ "$FAIL" = 0 ] && echo "appd-pin gates: PASS" || { echo "appd-pin gates: FAIL" >&2; exit 1; }
