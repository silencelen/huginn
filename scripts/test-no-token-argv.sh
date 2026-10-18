#!/usr/bin/env bash
# test-no-token-argv.sh — the appd bearer token never rides a command line.
#
# WHY THIS EXISTS: /etc/huginn-appd/token is root-only and root-equivalent, and
# the host tools used to expand it into `curl -H "Authorization: Bearer ..."`.
# /proc/<pid>/cmdline is readable by every account on the host (no hidepid is
# the Linux default) and curl does not scrub -H, so any local account that
# polled the process table while huginn-llm, -devices, -rounds or -archive ran,
# or while install.sh or release-desktop.sh did, got the token (CWE-214). The
# header now reaches curl through a file descriptor:
#
#     curl -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") ...
#
# (printf is a bash builtin, so no process carries the token in its argv), and
# this holds every script to that, three ways:
#   [1/3] static: no script under the scanned dirs (nor a command a README there
#         tells somebody to run) passes an Authorization header or a Bearer
#         value as an argument;
#   [2/3] a recording curl on PATH: the renderers, the installer's health check
#         and the desktop release's wire checks run against it; the token must
#         reach it, through a file and never through argv;
#   [3/3] the real curl: the renderers against a stub daemon on loopback, while
#         every /proc/<pid>/cmdline on the machine is watched for the token.
# Every token here is a fresh random one; the real one is never read.
#
# Usage: scripts/test-no-token-argv.sh [tree [dir...]]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server mobile/scripts scripts | tar -x -C DIR) to see the
#   gate fail there. dirs (relative to tree) replace the static lane's default
#   list: server mobile/scripts scripts.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1
shift $(( $# > 0 ? 1 : 0 ))
SCAN_DIRS=("$@"); [ "${#SCAN_DIRS[@]}" -gt 0 ] || SCAN_DIRS=(server mobile/scripts scripts)

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

T="$(mktemp -d)"
PIDS=()
_cleanup() {
  local p
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill "$p" 2>/dev/null; done
  rm -rf "$T"
}
trap _cleanup EXIT

# The scripts that talk to appd with the token, and what each is called here.
RENDERERS=(huginn-llm huginn-devices huginn-rounds huginn-archive)
TARGETS=(server/bin/huginn-llm server/bin/huginn-devices server/bin/huginn-rounds
         server/bin/huginn-archive server/appd/install.sh mobile/scripts/release-desktop.sh)

# ------------------------------------------------------------------- static
echo "[1/3] static: no script passes an Authorization value as an argument"
# A shell surface is what CI's syntax lane calls one: a bash/sh shebang, or the
# "shell=bash" directive for shellcheck that a sourced file carries instead; and
# any *.sh.
is_shell() {
  case "$1" in *.sh) return 0 ;; esac
  head -c 200 -- "$1" 2>/dev/null | head -n1 | tr -d '\0' \
    | grep -qE '^(#!.*[ /](bash|sh)( |$)|# shellcheck shell=(ba)?sh)'
}
Q="[\"']?"
# -H/--header (or -sH and the like) whose value is an Authorization header -
# in a bash array too, and inside a command string run somewhere else (\").
P_HDR="(^|[[:space:](])(-[A-Za-z]*H|--header)([[:space:]]+|=)\\\\?${Q}[Aa]uthorization"
# A variable or command substitution expanded straight after "Bearer ".
P_VAL="Bearer[[:space:]]+${Q}(\\\$|\`)"
# curl blanks this one only after it has started, which is too late.
P_OAUTH="--oauth2""-bearer"
# Shell scripts, and prose whose code blocks are shell, are held to the three
# patterns above. Any other program (node, python) builds its header in-process,
# so in one of those a line that names curl AND the header is the leak - unless
# it is the fd form itself. Comment lines are not commands; a changelog is history.
APPROVED="@<(printf 'Authorization: Bearer %s"
scan() {   # $1 = file -> offending lines, as file:line: text
  local f="$1" pats="$P_HDR|$P_VAL|$P_OAUTH"
  case "$f" in */CHANGELOG.md) return 0 ;; esac
  if ! is_shell "$f"; then
    case "$f" in
      *.md|*.txt) ;;
      *) pats="$P_OAUTH|curl.*([Aa]uthorization|Bearer)|([Aa]uthorization|Bearer).*curl" ;;
    esac
  fi
  grep -nE -- "$pats" "$f" 2>/dev/null | grep -vE '^[0-9]+:[[:space:]]*(#|//|\*)' \
    | grep -vF -- "$APPROVED" | sed "s#^#${f#"$TREE"/}:#"
}
# The scanner is not allowed to be blind: it must catch the shape it hunts.
printf '#!/usr/bin/env bash\ncurl -sf -%s "%s: Bearer %s(cat /etc/huginn-appd/token)" x\n' H Authorization '$' > "$T/canary.sh"
printf '#!/usr/bin/env bash\nauth=(-%s "%s: Bearer %sTOKEN")\n' H Authorization '$' > "$T/canary2.sh"
if [ -n "$(scan "$T/canary.sh")" ] && [ -n "$(scan "$T/canary2.sh")" ]; then
  ok "the scanner catches the shape it is looking for"
else
  bad "the scanner missed a planted token-on-argv line - it would pass anything"
fi
FILES=()
for d in "${SCAN_DIRS[@]}"; do
  [ -d "$TREE/$d" ] || { bad "no such dir to scan: $d"; continue; }
  while IFS= read -r f; do FILES+=("$f"); done < <(
    find "$TREE/$d" -type f ! -path '*/node_modules/*' ! -path '*/__pycache__/*' ! -path '*/.git/*' \
      ! -name '*.png' ! -name '*.jar' ! -name '*.zip' ! -name '*.gz' | sort)
done
SHELLS=0; HITS=""
for f in "${FILES[@]}"; do
  is_shell "$f" && SHELLS=$((SHELLS + 1))
  h="$(scan "$f")"; [ -z "$h" ] || HITS="$HITS$h"$'\n'
done
for t in "${TARGETS[@]}"; do
  case " ${SCAN_DIRS[*]} " in *" ${t%%/*} "*|*" $(dirname "$t") "*) ;; *) continue ;; esac
  [ -f "$TREE/$t" ] && is_shell "$TREE/$t" || bad "$t is not a shell script the scan can see"
done
if [ -n "$HITS" ]; then
  bad "the token (or a Bearer value) is put on a command line:"
  printf '%s' "$HITS" | sed 's/^/          /' >&2
else
  ok "${#FILES[@]} files under ${SCAN_DIRS[*]} ($SHELLS shell scripts): no Authorization value on argv"
fi

# --------------------------------------------------------- shared scaffolding
# The token, the routes a stub daemon answers, and copies of the renderers that
# read the scratch token instead of /etc/huginn-appd/token.
od -An -tx1 -N24 /dev/urandom | tr -d ' \n' > "$T/token"; echo >> "$T/token"; chmod 600 "$T/token"
has_token() { grep -qFf "$T/token" "$@"; }    # never the token itself on argv
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
GET /v1/ping 200 {"ok":true,"version":"9.9.9","secure":true}
GET /v1/desktop-kt/manifest 200 {"version":"1.2.3"}
GET /v1/desktop-kt/a.deb 200 abc
GET /v1/desktop-kt/a.tar.gz 200 abc
GET /v1/desktop-kt/a.pkg.tar.zst 200 abc
GET /v1/desktop-kt/a.exe 200 abc
ROUTES
mkdir -p "$T/bin" "$T/lib"
cp "$TREE/server/lib/state-table.sh" "$T/lib/"
copy_renderer() {   # $1 = name, $2 = base URL -> $T/bin/$1
  sed -e "s#^TOKEN_FILE=/etc/huginn-appd/token\$#TOKEN_FILE=$T/token#" \
      -e "s#http://127\\.0\\.0\\.1:8787#$2#g" "$TREE/server/bin/$1" > "$T/bin/$1"
  chmod 755 "$T/bin/$1"
  grep -q "^TOKEN_FILE=$T/token\$" "$T/bin/$1" \
    || { bad "$1: no 'TOKEN_FILE=/etc/huginn-appd/token' line to point at the scratch token (update this gate)"; return 1; }
}
# name|args|the requests it must make|a line its output must hold
CASES=(
  "huginn-rounds||GET /v1/rounds|No rounds yet"
  "huginn-devices||GET /v1/devices|box"
  "huginn-devices|watch box on|GET /v1/devices;PATCH /v1/devices/d1|box is now watched"
  "huginn-archive||GET /v1/archive|1 archived"
  "huginn-archive|sess|POST /v1/sessions/sess/archive?mode=graceful|archived sess"
  "huginn-archive|revive sess|GET /v1/archive;POST /v1/archive/a1/revive|revived as sess2"
  "huginn-llm|what is six times seven|GET /v1/models?local=1;POST /v1/chats;POST /v1/chats/c1/messages;GET /v1/chats/c1;DELETE /v1/chats/c1|forty-two"
)

# ------------------------------------------------------------ recording curl
echo "[2/3] a recording curl: the token reaches it, and never through argv"
SC="$T/stub-curl"; SS="$T/stub-sys"; L="$T/log"; mkdir -p "$SC" "$SS" "$L"
cat > "$SC/curl" <<'STUB'
#!/usr/bin/env bash
# Stands in for curl: records its argv, which way the bearer header came (a
# -H @file, or a plain -H argument), and answers from the routes file.
printf '%s\n' "$@" >> "$STUB_LOG/argv"
want="Bearer "; want="$want$(cat "$STUB_TOKEN_FILE")"
method="" url="" out="" wfmt="" fail="" via=""
while [ $# -gt 0 ]; do
  a="$1"; shift
  case "$a" in
    -X|--request) method="$1"; shift ;;
    -H|--header)
      h="$1"; shift
      if [ "${h#@}" != "$h" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
          case "$line" in [Aa]uthorization:*) [ "${line#*: }" = "$want" ] && via=file ;; esac
        done < "${h#@}"
      else
        case "$h" in [Aa]uthorization:*) [ "${h#*: }" = "$want" ] && via=argv ;; esac
      fi ;;
    -d|--data|--data-binary) shift; [ -n "$method" ] || method=POST ;;
    -o|--output) out="$1"; shift ;;
    -w|--write-out) wfmt="$1"; shift ;;
    -m|--max-time) shift ;;
    --*) ;;
    -*) case "$a" in *f*) fail=1 ;; esac ;;
    *) url="$a" ;;
  esac
done
method="${method:-GET}"; path="/${url#*://*/}"
status=404 body='{"error":"no route"}'
if [ -z "$via" ]; then status=401 body='{"error":"no token"}'
else
  while IFS=' ' read -r m p s b; do
    if [ "$m" = "$method" ] && [ "$p" = "$path" ]; then status="$s" body="$b"; break; fi
  done < "$STUB_ROUTES"
fi
printf '%s %s %s\n' "$method" "$path" "${via:-none}" >> "$STUB_LOG/requests"
if [ -n "$fail" ] && [ "$status" -ge 400 ]; then exit 22; fi
if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
if [ -n "$wfmt" ]; then
  w="${wfmt//'%{http_code}'/$status}"; w="${w//'%{size_download}'/${#body}}"; printf '%s' "$w"
fi
exit 0
STUB
# The installer's check also asks systemd and openssl; neither is the point here.
printf '#!/bin/sh\nexit 0\n' > "$SS/systemctl"
printf '#!/bin/sh\nexit 0\n' > "$SS/journalctl"
printf '#!/bin/sh\nexit 0\n' > "$SS/openssl"
chmod 755 "$SC/curl" "$SS"/*
export STUB_LOG="$L" STUB_TOKEN_FILE="$T/token" STUB_ROUTES="$T/routes"

recorded() {  # $1 = label, $2 = requests (;-separated), $3 = expected output line, $4.. = command
  local label="$1" want got out rc
  want="$(tr ';' '\n' <<<"$2" | sed 's/$/ file/')"
  local expect="$3"; shift 3
  : > "$L/argv"; : > "$L/requests"
  out="$(PATH="$SC:$SS:$PATH" "$@" 2>&1)"; rc=$?
  got="$(cat "$L/requests")"
  if has_token "$L/argv"; then
    bad "$label: the token was on curl's command line ($(grep -c . "$L/requests") request(s))"
  elif [ "$got" != "$want" ]; then
    bad "$label: requests (method path how-the-token-came) were [$(tr '\n' ';' <<<"$got")], wanted [$(tr '\n' ';' <<<"$want")] (exit $rc: $(head -c 300 <<<"$out"))"
  elif ! grep -qF -- "$expect" <<<"$out"; then
    bad "$label: authorised, but the output lost '$expect' (exit $rc): $(head -c 300 <<<"$out")"
  else
    ok "$label: $(grep -c . <<<"$got") request(s), the header on a file descriptor, never argv"
  fi
}
for r in "${RENDERERS[@]}"; do copy_renderer "$r" "http://127.0.0.1:8787" || true; done
for c in "${CASES[@]}"; do
  IFS='|' read -r name args reqs expect <<<"$c"
  [ -x "$T/bin/$name" ] || continue
  # shellcheck disable=SC2086  # args are words on purpose
  recorded "$name${args:+ $args}" "$reqs" "$expect" "$T/bin/$name" $args
done

# install.sh step 5 and release-desktop.sh steps 1 and 7, cut out of the scripts
# and run against the recording curl: both need root, systemd and a build to run
# whole, and both are where the token sat on argv the longest.
INSTALL="$TREE/server/appd/install.sh"
{
  echo 'set -euo pipefail'
  printf 'TOKEN_FILE=%q\n' "$T/token"
  grep -E '^(say|die)\(\) ' "$INSTALL"
  awk '/^unit_env\(\) \{/{on=1} on{print} on && /^\}/{exit}' "$INSTALL"
  awk '/^TOKEN="\$\(cat "\$TOKEN_FILE"\)"$/{on=1} on{print}' "$INSTALL"
} > "$T/install-check.sh"
if grep -q 'seq 1 60' "$T/install-check.sh" && grep -q 'SPING=' "$T/install-check.sh"; then
  recorded "install.sh health check" "GET /v1/ping;GET /v1/ping" "running: huginn-appd 9.9.9 on port 8787, https ok" \
    bash "$T/install-check.sh"
else
  bad "install.sh: could not cut step 5 out of it (TOKEN= line or the ping loop moved - update this gate)"
fi
REL="$TREE/mobile/scripts/release-desktop.sh"
mkdir -p "$T/dist"; for b in a.deb a.tar.gz a.pkg.tar.zst a.exe; do printf 'abc' > "$T/dist/$b"; done
rel_prelude() {   # $1 = VERSION
  echo 'set -euo pipefail'
  printf 'TOKEN_FILE=%q BASE_URL=%q FEED=/v1/desktop-kt DIST=%q VERSION=%q\n' "$T/token" http://127.0.0.1:8787 "$T/dist" "$1"
  echo 'DEB=a.deb TGZ=a.tar.gz ARCHPKG=a.pkg.tar.zst EXE=a.exe'
  grep -E '^appd_curl\(\) \{' "$REL" || true
}
{ rel_prelude 1.2.4
  awk '/^if \[ -f "\$TOKEN_FILE" \]; then$/{on=1} on{print} on && /^fi$/{exit}' "$REL"
  echo 'echo "downgrade gate passed: $LIVE -> $VERSION"'
} > "$T/release-step1.sh"
{ rel_prelude 1.2.3
  awk '/^TOKEN=\$\(cat "\$TOKEN_FILE"\)$/{on=1} on{print} on && /^done$/{exit}' "$REL"
} > "$T/release-step7.sh"
if ! command -v node >/dev/null 2>&1; then
  skip "release-desktop.sh wire checks (no node here; both steps parse the manifest with it)"
elif grep -q 'NEWEST=' "$T/release-step1.sh" && grep -q 'size_download' "$T/release-step7.sh"; then
  recorded "release-desktop.sh downgrade gate" "GET /v1/desktop-kt/manifest" "downgrade gate passed: 1.2.3 -> 1.2.4" \
    bash "$T/release-step1.sh"
  recorded "release-desktop.sh verify through the wire" \
    "GET /v1/desktop-kt/manifest;GET /v1/desktop-kt/a.deb;GET /v1/desktop-kt/a.tar.gz;GET /v1/desktop-kt/a.pkg.tar.zst;GET /v1/desktop-kt/a.exe" \
    "a.exe OK (3 bytes)" bash "$T/release-step7.sh"
else
  bad "release-desktop.sh: could not cut steps 1 and 7 out of it (update this gate)"
fi

# ------------------------------------------------------------------ real curl
echo "[3/3] the real curl: no process on this machine ever shows the token"
if ! command -v curl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  skip "the real-curl lane (needs curl and python3)"
else
  python3 - "$T/routes" "$T/port" "$T/served" "$T/token" 0.4 <<'SERVER' &
import sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
routes_file, port_file, log_file, token_file, hold = sys.argv[1:6]
want = "Bearer " + open(token_file).read().strip()
routes = {}
for line in open(routes_file):
    m, p, s, b = line.rstrip("\n").split(" ", 3)
    routes.setdefault((m, p), (int(s), b))
class H(BaseHTTPRequestHandler):
    def answer(self, m):
        n = int(self.headers.get("content-length") or 0)
        if n:
            self.rfile.read(n)
        good = self.headers.get("authorization") == want
        with open(log_file, "a") as fh:
            fh.write("%s %s %s\n" % (m, self.path, "file" if good else "none"))
        time.sleep(float(hold))   # long enough for /proc to be read while curl waits
        s, b = routes.get((m, self.path), (404, '{"error":"no route"}')) if good else (401, '{"error":"no token"}')
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
srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
with open(port_file, "w") as fh:
    fh.write(str(srv.server_port))
srv.serve_forever()
SERVER
  PIDS+=("$!")
  PORT=""; for _ in $(seq 1 50); do [ -s "$T/port" ] && { PORT="$(cat "$T/port")"; break; }; sleep 0.1; done
  if [ -z "$PORT" ]; then
    skip "the real-curl lane (the stub daemon never bound a port)"
  else
    for r in "${RENDERERS[@]}"; do copy_renderer "$r" "http://127.0.0.1:$PORT" || true; done
    for c in "${CASES[@]}"; do
      IFS='|' read -r name args reqs expect <<<"$c"
      [ -x "$T/bin/$name" ] || continue
      rm -f "$T/stop" "$T/hits"; : > "$T/served"
      # The watcher reads every /proc/<pid>/cmdline it can, over and over, until
      # told to stop, and reports any that holds the token - and how many curls
      # it saw talking to the stub, so a watcher that saw nothing cannot pass.
      python3 - "$T/token" "$T/stop" "$T/hits" ":$PORT/" <<'WATCH' &
import os, sys, time
token = open(sys.argv[1], "rb").read().strip()
stop, out, port = sys.argv[2], sys.argv[3], sys.argv[4].encode()
hits, curls = set(), set()
while True:
    done = os.path.exists(stop)
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open("/proc/%s/cmdline" % pid, "rb") as fh:
                c = fh.read()
        except OSError:
            continue
        args = c.split(b"\0")
        if token in c:
            hits.add("%s %s" % (pid, os.path.basename(args[0]).decode(errors="replace")))
        if os.path.basename(args[0]) == b"curl" and port in c:
            curls.add(pid)
    if done:
        break
    time.sleep(0.003)
with open(out, "w") as fh:
    fh.write("curls %d\n" % len(curls))
    for h in sorted(hits):
        fh.write("hit %s\n" % h)
WATCH
      W=$!
      # shellcheck disable=SC2086
      out="$("$T/bin/$name" $args 2>&1)"; rc=$?
      touch "$T/stop"; wait "$W" 2>/dev/null
      want="$(tr ';' '\n' <<<"$reqs" | sed 's/$/ file/')"
      label="$name${args:+ $args}"
      CURLS="$(sed -n 's/^curls //p' "$T/hits" 2>/dev/null)"
      if [ -z "$CURLS" ]; then
        bad "$label: the /proc watcher did not report (python3 failed?)"
      elif grep -q '^hit ' "$T/hits"; then
        bad "$label: the token was in a process's command line: $(grep '^hit ' "$T/hits" | tr '\n' ';')"
      elif [ "$(cat "$T/served")" != "$want" ]; then
        bad "$label: the daemon saw [$(tr '\n' ';' < "$T/served")], wanted [$(tr '\n' ';' <<<"$want")] (exit $rc: $(head -c 300 <<<"$out"))"
      elif ! grep -qF -- "$expect" <<<"$out"; then
        bad "$label: authorised, but the output lost '$expect' (exit $rc): $(head -c 300 <<<"$out")"
      elif [ "$CURLS" -lt 1 ]; then
        skip "$label: the watcher never caught curl running, so it proves nothing"
      else
        ok "$label: authorised; $CURLS curl(s) seen running, none showing the token"
      fi
    done
  fi
fi

echo
[ "$FAIL" = 0 ] && echo "no-token-argv gates: PASS" || { echo "no-token-argv gates: FAIL" >&2; exit 1; }
