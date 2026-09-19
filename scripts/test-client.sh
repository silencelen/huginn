#!/usr/bin/env bash
# test-client.sh — gates for the CLI core (client/huginn.sh + client/huginn.ps1).
#
# WHY THIS EXISTS: the core was the only component with no release script and no
# gates, which is exactly how it drifted — huginn.sh reached 0.8.0 while huginn.ps1
# sat at 0.7.1 with the `end` verb missing, and a deny-list bug shipped to every
# device that ran `huginn update` because nothing ever asserted what the client
# actually sends. These checks are cheap; run them before cutting a core release.
#
# The PowerShell checks need `pwsh` (installed at /opt/microsoft/powershell/7 on
# huginn, 2026-08-14). Without it they SKIP LOUDLY rather than passing silently —
# a skipped check must never look like a green one.
#
# Usage: scripts/test-client.sh
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

# ⚠ ONE EXIT TRAP, AND ONE ONLY. `trap ... EXIT` REPLACES the previous handler,
# so a lane that sets its own silently un-registers everybody else's — which is
# how this script used to leave its stub daemons running after a Ctrl-C and
# poison the NEXT run (see the port note below). Lanes register here instead.
STUB_PIDS=()
STUB_DIRS=()
# Private tmux SOCKETS (tmux -L <name>), never the operator's default one: the
# projects-lifecycle lane stands a real daemon up and it makes real sessions.
STUB_TMUX=()
_cleanup() {
  local p d t
  for p in ${STUB_PIDS[@]+"${STUB_PIDS[@]}"}; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  # kill-server, then the socket FILE: an exited server leaves a 0-byte socket
  # behind, and /tmp/tmux-<uid>/ is already a graveyard of them from every suite
  # in this repo. A gate that adds one per run is a gate nobody wants to run.
  for t in ${STUB_TMUX[@]+"${STUB_TMUX[@]}"}; do
    [ -n "$t" ] || continue
    tmux -L "$t" kill-server 2>/dev/null
    rm -f "${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/$t" 2>/dev/null
  done
  for d in ${STUB_DIRS[@]+"${STUB_DIRS[@]}"}; do [ -n "$d" ] && rm -rf "$d"; done
  return 0
}
trap _cleanup EXIT

# ⚠ EVERY STUB DAEMON BINDS PORT 0, AND READS THE PORT BACK. A stub pinned to a
# hardcoded port does not fail when something else already holds it: python dies
# EADDRINUSE into /dev/null and the lane then runs against the SQUATTER. What
# that costs depends on who is squatting — a token-guarded listener turns the
# headroom lane into the false `headroom on a 404 said: … (HTTP 401)` and fails
# the whole gate; a 404-ing listener leaves it GREEN with our own stub never
# bound, which is worse. And the commonest squatter is this script: an
# interrupted run used to orphan its own stub (no EXIT trap, above).
#
# So the kernel picks the port, the stub writes it to a file, and the lane
# reads it — a port nobody else can be holding, and a file that PROVES our stub
# is the thing being talked to. An explicit port may still be requested (the
# HUGINN_TEST_* overrides below) for anyone debugging against a fixed address.
STUB_DIR=$(mktemp -d); STUB_DIRS+=("$STUB_DIR")
stub_port () {    # $1 = the port file a stub was told to write; prints the port
  local pf="$1" _
  for _ in $(seq 1 60); do
    [ -s "$pf" ] && { tr -d '[:space:]' < "$pf"; return 0; }
    sleep 0.1
  done
  return 1
}

echo "[1/8] syntax"
bash -n client/huginn.sh && ok "huginn.sh parses" || bad "huginn.sh does not parse"
if command -v pwsh >/dev/null 2>&1; then
  if pwsh -NoProfile -Command '
      $e=$null; $t=$null
      [System.Management.Automation.Language.Parser]::ParseFile("client/huginn.ps1",[ref]$t,[ref]$e) | Out-Null
      if ($e.Count) { $e | ForEach-Object { Write-Host "    line $($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }
      exit 0'; then ok "huginn.ps1 parses"; else bad "huginn.ps1 does not parse"; fi
else
  skip "huginn.ps1 parse (no pwsh)"
fi

echo "[2/8] the two version constants agree with each other and the changelog"
SH_V=$(grep -m1 "^HUGINN_VERSION=" client/huginn.sh | sed "s/.*'\(.*\)'.*/\1/")
PS_V=$(grep -m1 "HUGINN_VERSION = " client/huginn.ps1 | sed "s/.*'\(.*\)'.*/\1/")
CL_V=$(grep -m1 -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' CHANGELOG.md | tr -d '#[] ')
[ -n "$SH_V" ] && [ "$SH_V" = "$PS_V" ] \
  && ok "huginn.sh = huginn.ps1 = $SH_V" \
  || bad "version drift: huginn.sh=$SH_V huginn.ps1=$PS_V"
[ "$SH_V" = "$CL_V" ] \
  && ok "changelog head matches ($CL_V)" \
  || bad "changelog head is $CL_V but the clients say $SH_V — write the notes, or bump"
# The header COMMENT is what `huginn-sync` prints as the mirror's version, so a stale
# one misreports what devices just received. It sat at 0.7.1 through the 0.8.0 cut.
for f in client/huginn.sh client/huginn.ps1; do
  HDR=$(grep -m1 -oE '^# Version: [0-9]+\.[0-9]+\.[0-9]+' "$f" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  [ "$HDR" = "$SH_V" ] && ok "$f header comment says $HDR" \
    || bad "$f header comment says ${HDR:-none}, constant says $SH_V"
done

# `update` overwrites the file that is then loaded into the shell, so its download
# host is a trust root. huginn.sh pinned it in 0.6.1; huginn.ps1 kept using
# $HUGINN_HOST until 0.8.2 — nothing noticed for two minor versions.
echo "[2b/8] both clients pin the update trust root"
for f in client/huginn.sh client/huginn.ps1; do
  grep -q 'HUGINN_UPDATE_HOST' "$f" && ok "$f pins HUGINN_UPDATE_HOST" \
    || bad "$f fetches update code from an unpinned host"
done
grep -q 'scp .*\${H}:' client/huginn.ps1 && bad "huginn.ps1 still scps from \$HUGINN_HOST" \
  || ok "huginn.ps1 does not scp from \$HUGINN_HOST"

echo "[3/8] both clients expose the same verbs (parity by verb)"
# huginn.sh writes cases as alternations (`list|ls)`, `status|st)`), so match the
# verb as a case ALTERNATIVE, not as a bare `verb)`.
for v in end kill archive revive solo rename list status rounds headroom devices device local projects desktop usage update uninstall version help; do
  # Match the DISPATCH, not a mention: huginn.ps1 lists every verb in its
  # completion array too, so grepping "'$v'" passes even with the branch deleted
  # (verified by removing the `end` branch: still 2 matches, still green).
  # Scope to the huginn() DISPATCHER. Unscoped, this also matched _huginn_complete's
  # `case "$prev"` labels (kill|end|solo|rename|mv), so a whole verb branch could be
  # deleted and the gate stayed green — the same decoy already fixed for the ps1 side.
  a=$(awk '/^huginn\(\) \{/,/^\}/' client/huginn.sh \
        | grep -cE "^[[:space:]]+([a-z'?/*-]+\|)*$v(\||\))")
  b=$(grep -cE "\\\$args\[0\] -(eq|in) [^;]*'$v'" client/huginn.ps1)
  [ "$a" -gt 0 ] && [ "$b" -gt 0 ] && ok "verb $v" || bad "verb $v missing (sh=$a ps1=$b)"
done

echo "[4/8] what the PowerShell client actually SENDS"
if ! command -v pwsh >/dev/null 2>&1; then
  skip "ps1 behaviour (no pwsh)"
else
  T=$(mktemp -d); STUB_DIRS+=("$T")
  cat > "$T/ssh" <<'STUB'
#!/usr/bin/env bash
dec=""
for a in "$@"; do
  if [[ "$a" == *"base64 -d"* ]]; then
    dec=$(sed -E 's/^echo ([A-Za-z0-9+/=]+).*/\1/' <<<"$a" | base64 -d)
  fi
done
printf '%s' "$dec" >> "$SSH_LOG"
grep -q 'soft-end' <<<"$dec" && echo '{"ok":true,"phrase":"WRAPUP","auto":true}'
exit 0
STUB
  chmod +x "$T/ssh"; export PATH="$T:$PATH"

  emit () { export SSH_LOG="$T/log"; : > "$SSH_LOG"
            pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; $1" >"$T/out" 2>&1
            cat "$SSH_LOG"; }

  # The deny-list must reach claude as ONE argument. Assembled in a remote variable
  # and expanded unquoted it word-splits into `'Bash Edit Write NotebookEdit'` with
  # literal quotes — no valid tool name, nothing denied. That shipped once.
  P=$(emit 'huginn -p "q"')
  grep -q -- "--disallowedTools 'Bash Edit Write NotebookEdit'" <<<"$P" \
    && ok "-p carries a correctly quoted deny-list" \
    || bad "-p deny-list is malformed: $(grep -o -- '--disallowedTools.*' <<<"$P" | head -1)"
  grep -q -- "--allowedTools 'Skill mcp__mempalace WebFetch WebSearch'" <<<"$P" \
    && ok "-p allow-list is read-only + web + memory + Skill" || bad "-p allow-list wrong"

  Y=$(emit 'huginn -y "q"')
  grep -q -- "--disallowedTools" <<<"$Y" \
    && bad "-y must NOT carry a deny-list" || ok "-y carries no deny-list"
  grep -q -- "--allowedTools 'Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace'" <<<"$Y" \
    && ok "-y allow-list may mutate (+ Skill)" || bad "-y allow-list wrong"

  E=$(emit 'huginn end testsess')
  grep -q "POST .*/v1/sessions/testsess/soft-end" <<<"$E" \
    && ok "end POSTs soft-end for the canonical name" || bad "end sent: $E"
  grep -q 'Bearer \$(cat /etc/huginn-appd/token' <<<"$E" \
    && ok "token is read ON THE HOST, never marshalled from the client" || bad "token handling changed"
  grep -q "WRAPUP" "$T/out" \
    && ok "end reports the phrase the daemon returned" || bad "end did not parse the JSON reply"

  K=$(emit 'huginn kill testsess')
  grep -q "DELETE .*/v1/sessions/testsess" <<<"$K" \
    && ok "kill prefers the daemon's DELETE" || bad "kill sent: $K"

  # ⚠ THE INVALID EXAMPLE IS A DOT, NOT A DASH. A dash is legal everywhere in
  # the product (contract 1, and see [5b/8]); it was this gate pinning
  # 'bad-name' as the invalid example that kept the narrow rule alive.
  B=$(pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; huginn end 'bad.name'" 2>&1)
  grep -q "invalid session name" <<<"$B" \
    && ok "ps1: end rejects a dotted name" || bad "ps1: end accepted 'bad.name'"
  D=$(emit 'huginn end build-box')
  grep -q "/v1/sessions/build-box/soft-end" <<<"$D" \
    && ok "ps1: a dashed name reaches the daemon" || bad "ps1: 'build-box' sent: $D"
  DU=$(emit 'huginn end Build_Box')
  grep -q "/v1/sessions/build_box/soft-end" <<<"$DU" \
    && ok "ps1: and it is still case-folded on the way" || bad "ps1: 'Build_Box' sent: $DU"
fi

echo "[5/8] what the POSIX client actually SENDS"
# huginn.sh is the client the 0.8.0 deny-list bug actually shipped to, and nothing
# here exercised it — [4/6] drives only huginn.ps1. Same stub-ssh technique; huginn.sh
# passes a plain argv string rather than a base64 payload, so the stub logs argv.
T2=$(mktemp -d)
cat > "$T2/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SSH_LOG"
grep -q 'soft-end' <<<"$*" && echo '{"ok":true,"phrase":"WRAPUP","auto":true}'
exit 0
STUB
chmod +x "$T2/ssh"

semit () { export SSH_LOG="$T2/log"; : > "$SSH_LOG"
           ( export PATH="$T2:$PATH"
             . "$PWD/client/huginn.sh" >/dev/null 2>&1
             eval "$1" ) >"$T2/out" 2>&1
           cat "$SSH_LOG"; }

SP=$(semit 'huginn -p "q"')
grep -q -- "--disallowedTools 'Bash Edit Write NotebookEdit'" <<<"$SP" \
  && ok "sh: -p carries a correctly quoted deny-list" \
  || bad "sh: -p deny-list malformed/missing"
grep -q -- "--allowedTools 'Skill mcp__mempalace WebFetch WebSearch'" <<<"$SP" \
  && ok "sh: -p allow-list matches the ps1 client" || bad "sh: -p allow-list differs from ps1"

SY=$(semit 'huginn -y "q"')
if grep -q -- "--disallowedTools" <<<"$SY"; then bad "sh: -y must NOT carry a deny-list"; else ok "sh: -y carries no deny-list"; fi

SE=$(semit 'huginn end testsess')
grep -q "soft-end" <<<"$SE" && ok "sh: end reaches the soft-end route" || bad "sh: end sent nothing matching soft-end"
SK=$(semit 'huginn kill testsess')
grep -q "DELETE" <<<"$SK" && ok "sh: kill prefers the daemon DELETE" || bad "sh: kill did not use DELETE"

echo "[5b/8] ONE session-name rule, in all three enforcers"
# ⚠ WHY: the product enforced FOUR different rules, and two of them could mint a
# name this client can never address. The daemon accepts a dash and honours it
# end to end; the desktop dialogs offer one; keyboard-made sessions routinely
# carry one (dev-phonefarm). The CLI and `cc` allowed `^[A-Za-z0-9_]+$`, so
# `huginn build-box` — and solo/kill/end/archive/rename — refused LOCALLY, before
# any network, for a session `huginn ls` had just listed and tab-completion had
# just offered. `huginn revive build-box` was accepted while `huginn archive
# build-box` was not.
#
# The one rule, contract 1 of the edge-hunt: ^[a-z0-9_][a-z0-9_-]{0,49}$,
# case-folded, dots banned everywhere (tmux silently rewrites '.' to '_', so a
# dotted name is a name that comes back different).
SN_OK=$(semit 'huginn end build-box')
grep -q "/v1/sessions/build-box/soft-end" <<<"$SN_OK" \
  && ok "sh: a dashed name reaches the daemon" || bad "sh: 'build-box' sent: $SN_OK"
SN_UP=$(semit 'huginn end Build_Box')
grep -q "/v1/sessions/build_box/soft-end" <<<"$SN_UP" \
  && ok "sh: and it is still case-folded on the way" || bad "sh: 'Build_Box' sent: $SN_UP"
SN_DOT=$(semit 'huginn end build.box')
[ -z "$SN_DOT" ] && grep -q "invalid session name" "$T2/out" \
  && ok "sh: a dotted name is refused before it is sent anywhere" \
  || bad "sh: 'build.box' sent: $SN_DOT / said: $(cat "$T2/out")"
SN_FLAG=$(semit 'huginn --hlp')
[ -z "$SN_FLAG" ] && ok "sh: a typo'd flag still cannot spawn a junk session" \
  || bad "sh: '--hlp' sent: $SN_FLAG"
# ⚠ AND THE REFUSAL TELLS THE TWO CAUSES APART. A typo is one thing; a session
# that EXISTS on the host and this client cannot address is another, and saying
# "invalid session name" about a row `huginn ls` just printed sends somebody
# looking for their own mistake. The completion cache is already the live
# `tmux ls` output, so this costs no round trip.
semit '_HUGINN_SESS_CACHE="my box"; huginn end "my box"' >/dev/null
grep -q "exists on the host but this client cannot address it" "$T2/out" \
  && ok "sh: a live-but-unaddressable name says so, not 'invalid'" \
  || bad "sh: an uncompletable live name said: $(cat "$T2/out")"

# `cc` is the server-side backstop and the one the ssh path actually runs.
# Driven with a stub tmux so the check is exercised without creating a session.
CCT=$(mktemp -d); STUB_DIRS+=("$CCT")
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> "$CCT_LOG"\nexit 0\n' > "$CCT/tmux"
chmod +x "$CCT/tmux"
cc_try () { CCT_LOG="$CCT/log" PATH="$CCT:$PATH" server/bin/cc "$1" 2>&1; }
: > "$CCT/log"
CC_OK=$(cc_try build-box); CC_RC=$?
[ "$CC_RC" != 2 ] && grep -q 'new-session -A -s build-box' "$CCT/log" \
  && ok "cc: a dashed name is created, not refused with exit 2" \
  || bad "cc: 'build-box' exited $CC_RC, tmux saw: $(cat "$CCT/log")"
: > "$CCT/log"
CC_UP=$(cc_try Build_Box)
grep -q 'new-session -A -s build_box' "$CCT/log" \
  && ok "cc: and it still folds case before tmux sees it" || bad "cc: tmux saw: $(cat "$CCT/log")"
: > "$CCT/log"
cc_try build.box >/dev/null 2>&1; CC_RC=$?
[ "$CC_RC" = 2 ] && [ ! -s "$CCT/log" ] \
  && ok "cc: a dotted name is refused with exit 2 and never reaches tmux" \
  || bad "cc: 'build.box' exited $CC_RC, tmux saw: $(cat "$CCT/log")"

echo "[5c/8] the daemon's own refusal survives the trip home"
# ⚠ WHY: `curl -sf` discards the response body on every HTTP >= 400 and exits 22,
# so four different refusals arrived as ONE exit code and `huginn end` answered
# all of them with "is huginn-appd running? is the session a live Claude pane?" -
# two causes that are both fine when the daemon is saying "answer the waiting
# question first". `huginn kill` was worse: DELETE has no 409 guard, so the
# realistic case is a GLOBAL 401 (rotated or unreadable token) with the daemon
# up, where the bare tmux fallback skipped clearSessionState/registryRemove and
# the killed session came back on the next reboot restore.
#
# Driven against a REAL curl: the stub ssh runs the command the client actually
# composed, with the daemon address and the token PATH rewritten to this lane's
# own stub and a throwaway token file. So this asserts the composed request and
# the reply handling together, not a re-implementation of either.
APT=$(mktemp -d); STUB_DIRS+=("$APT")
printf 'gate-token\n' > "$APT/token"
AP_PF="$APT/appd.port"
python3 - "$AP_PF" "${HUGINN_TEST_APPD_PORT:-0}" <<'APSTUB' >/dev/null 2>&1 &
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        # Compact, exactly like the daemon's JSON.stringify: the sh client reads
        # `phrase` with sed, and a stub that pretty-printed would test a shape
        # appd never sends.
        b = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(code); self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n = int(self.headers.get("content-length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}") if n else {}
        if self.path.startswith("/v1/sessions/attention/"):
            self._send(409, {"error": "answer the waiting question first, then end the session"})
        elif self.path.startswith("/v1/sessions/shell/"):
            if body.get("force"):
                self._send(200, {"ok": True, "phrase": "WRAPUP", "auto": True})
            else:
                self._send(409, {"error": "no Claude state recorded for this session - it may be a plain shell; pass force to send anyway"})
        else:
            self._send(200, {"ok": True, "phrase": "WRAPUP", "auto": True})
    def do_DELETE(self):
        if self.path.startswith("/v1/sessions/locked"):
            self._send(401, {"error": "unauthorized"})
        else:
            self._send(200, {"ok": True})
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
APSTUB
AP_STUB=$!; STUB_PIDS+=("$AP_STUB")
AP_PORT=$(stub_port "$AP_PF")
if [ -z "${AP_PORT:-}" ]; then
  skip "daemon-refusal checks (the stub never bound a port)"
else
  cat > "$APT/ssh" <<'APSSH'
#!/usr/bin/env bash
# What ssh would run on the host: the LAST argument. A base64 payload (the ps1
# client marshals that way) is decoded first.
cmd="${!#}"
case "$cmd" in
  *"base64 -d"*) cmd="$(sed -E 's/^echo ([A-Za-z0-9+/=]+).*/\1/' <<<"$cmd" | base64 -d)" ;;
esac
cmd="${cmd//127.0.0.1:8787/127.0.0.1:$APPD_PORT}"
cmd="${cmd//\/etc\/huginn-appd\/token/$APPD_TOKEN}"
printf '%s\n' "$cmd" >> "$SSH_LOG"
eval "$cmd"
APSSH
  chmod +x "$APT/ssh"
  # `kill` may legitimately fall back to raw tmux, and eval would then run the
  # REAL one. Stubbed, and its invocation is itself an assertion below.
  printf '#!/usr/bin/env bash\nprintf "tmux %%s\\n" "$*" >> "$SSH_LOG"\nexit 0\n' > "$APT/tmux"
  chmod +x "$APT/tmux"
  aemit () {   # $1 = the huginn command; $2 = the daemon port to aim ssh at
    export SSH_LOG="$APT/log" APPD_TOKEN="$APT/token" APPD_PORT="${2:-$AP_PORT}"
    : > "$SSH_LOG"
    ( export PATH="$APT:$PATH"
      . "$PWD/client/huginn.sh" >/dev/null 2>&1
      eval "$1" ) >"$APT/out" 2>&1
    cat "$APT/out"
  }
  AE=$(aemit 'huginn end attention')
  grep -q "answer the waiting question first" <<<"$AE" \
    && ok "sh: end repeats the daemon's 409, not a guess about the daemon being down" \
    || bad "sh: end on a 409 said: $AE"
  grep -q "is huginn-appd running" <<<"$AE" \
    && bad "sh: end still blames the daemon for a refusal it issued itself" \
    || ok "sh: and it no longer names two causes that are both fine"
  AF=$(aemit 'huginn end shell')
  grep -q "plain shell" <<<"$AF" && grep -q -- "--force" <<<"$AF" \
    && ok "sh: the 'pass force' refusal names the flag that answers it" \
    || bad "sh: end on the no-state 409 said: $AF"
  AG=$(aemit 'huginn end shell --force')
  grep -q "WRAPUP" <<<"$AG" \
    && ok "sh: end --force is reachable and carries force to the daemon" \
    || bad "sh: end --force said: $AG"
  AK=$(aemit 'huginn kill locked')
  grep -q "unauthorized" <<<"$AK" \
    && ok "sh: kill on a 401 says what the daemon said" || bad "sh: kill on a 401 said: $AK"
  grep -q '^tmux kill-session' "$APT/log" \
    && bad "sh: kill fell back to raw tmux on an HTTP status - the session returns at reboot" \
    || ok "sh: and it does NOT silently fall back to raw tmux"
  AD=$(aemit 'huginn kill stranded' 1)
  grep -q '^tmux kill-session' "$APT/log" \
    && ok "sh: kill DOES fall back to tmux when nothing answered at all" \
    || bad "sh: kill with no daemon sent: $(cat "$APT/log") / said: $AD"
  if command -v pwsh >/dev/null 2>&1; then
    pemit () { export SSH_LOG="$APT/log" APPD_TOKEN="$APT/token" APPD_PORT="${2:-$AP_PORT}"
               : > "$SSH_LOG"
               PATH="$APT:$PATH" pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; $1" 2>&1 | tr -d '\r'; }
    PE=$(pemit 'huginn end attention')
    grep -q "answer the waiting question first" <<<"$PE" \
      && ok "ps1: end repeats the daemon's 409 too" || bad "ps1: end on a 409 said: $PE"
    PK=$(pemit 'huginn kill locked')
    grep -q "unauthorized" <<<"$PK" && ! grep -q '^tmux kill-session' "$APT/log" \
      && ok "ps1: kill on a 401 says so and keeps its hands off tmux" \
      || bad "ps1: kill on a 401 said: $PK / tmux saw: $(cat "$APT/log")"
  else
    skip "ps1 daemon-refusal checks (no pwsh)"
  fi
fi

echo "[5d/8] the runner fetch really tries the mirror"
# ⚠ WHY: both runner fetches gated on `Length -gt 0` alone - never on
# $LASTEXITCODE, never on validity - BEFORE the `if (-not $got) { scp ... }`
# mirror block. `gh api` prints its error JSON to STDOUT (401 with a bad token =
# 112 bytes, 404 for a renamed path = 127 bytes, both exit 1), so a FAILED fetch
# still set $got, the mirror was SKIPPED, the later `node --check` cleared $got,
# and the user was told "gh and the mirror both failed" about a mirror that was
# never contacted. On stock Windows PowerShell 5.1 the second half compounds it:
# `>` is Out-File, default -Encoding unicode, i.e. UTF-16LE + BOM, which node
# cannot parse - so `huginn device on` and `huginn local on|update|plan` failed
# 100% of the time on a 5.1 box with gh installed and authenticated.
#
# The 5.1 encoding half is settled by Microsoft's documentation plus the
# mechanism (a UTF-16LE+BOM file passes a length gate and fails `node --check`,
# verified). What is driven here is the ORDERING half, which is platform
# independent, plus the source property that no fetch uses `>` any more.
FT=$(mktemp -d); STUB_DIRS+=("$FT")
cat > "$FT/gh" <<'GHSTUB'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >> "$FETCH_LOG"
case "${GH_MODE:-errbody}" in
  # What the real gh does on a bad token / renamed path: the error JSON goes to
  # STDOUT and the exit code is 1.
  errbody) echo '{"message":"Bad credentials","documentation_url":"https://docs.github.com/rest"}'; exit 1 ;;
  # HTTP 200 carrying something that is not the file (a proxy page, a truncation).
  junk)    echo 'this is ) not javascript'; exit 0 ;;
  ok)      echo 'if (process.argv[2] === "version") console.log("1.0.0-gh");'; exit 0 ;;
esac
GHSTUB
cat > "$FT/scp" <<'SCPSTUB'
#!/usr/bin/env bash
printf 'scp %s\n' "$*" >> "$FETCH_LOG"
[ "${SCP_MODE:-ok}" = fail ] && exit 1
dest="${!#}"
printf 'if (process.argv[2] === "version") console.log("9.9.9-scp");\n' > "$dest"
exit 0
SCPSTUB
chmod +x "$FT/gh" "$FT/scp"
# Set out here, not inside the runners: they execute in a command substitution,
# whose exports never reach this shell - and the assertions read the log.
export FETCH_LOG="$FT/log"

# The POSIX twin already gated on gh's EXIT status, so it never skipped the
# mirror for an error body. What it also never checked was whether a 200 is the
# FILE: a proxy error page is a successful fetch of something that is not
# JavaScript, and that skipped the mirror on both sides.
sh_fetch_run () {   # $1 = huginn command, $2 = GH_MODE, $3 = SCP_MODE
  rm -rf "$FT/shhome"; mkdir -p "$FT/shhome"
  : > "$FETCH_LOG"
  ( export PATH="$FT:$PATH" HOME="$FT/shhome" GH_MODE="$2" SCP_MODE="${3:-ok}"
    . "$PWD/client/huginn.sh" >/dev/null 2>&1
    eval "$1" ) 2>&1
}
SHJ=$(sh_fetch_run 'huginn device update' junk ok)
grep -q '^scp ' "$FETCH_LOG" && grep -q '9.9.9-scp' <<<"$SHJ" \
  && ok "sh: a 200 that is not JavaScript falls through to the mirror" \
  || bad "sh: device update on a junk body said: $SHJ / log: $(cat "$FETCH_LOG")"
SHB=$(sh_fetch_run 'huginn device update' errbody fail)
grep -q 'gh' <<<"$SHB" && grep -q 'mirror' <<<"$SHB" \
  && ok "sh: when both fail, the message names which source failed" \
  || bad "sh: both-failed message was: $SHB"

if ! command -v pwsh >/dev/null 2>&1; then
  skip "ps1 runner-fetch checks (no pwsh)"
else
  fetch_run () {   # $1 = huginn command, $2 = GH_MODE, $3 = SCP_MODE
    rm -rf "$FT/home"; mkdir -p "$FT/home"
    : > "$FETCH_LOG"
    HOME="$FT/home" GH_MODE="$2" SCP_MODE="${3:-ok}" PATH="$FT:$PATH" \
      pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; $1" 2>&1 | tr -d '\r'
  }
  FR=$(fetch_run 'huginn device update' errbody ok)
  grep -q '^scp ' "$FETCH_LOG" \
    && ok "ps1: a gh error BODY does not count as a fetch - the mirror is contacted" \
    || bad "ps1: device update with a failing gh never ran scp; log: $(cat "$FETCH_LOG") / said: $FR"
  grep -q '9.9.9-scp' <<<"$FR" \
    && ok "ps1: and the runner installed is the mirror's" || bad "ps1: device update said: $FR"
  FJ=$(fetch_run 'huginn device update' junk ok)
  grep -q '^scp ' "$FETCH_LOG" && grep -q '9.9.9-scp' <<<"$FJ" \
    && ok "ps1: a 200 that is not JavaScript also falls through to the mirror" \
    || bad "ps1: device update on a junk body said: $FJ / log: $(cat "$FETCH_LOG")"
  FB=$(fetch_run 'huginn device update' errbody fail)
  grep -q 'gh exited 1' <<<"$FB" && grep -qE 'scp from [^ ]+ exited' <<<"$FB" \
    && ok "ps1: when both fail, the message names which source failed and how" \
    || bad "ps1: both-failed message was: $FB"
  FL=$(fetch_run 'huginn local plan' errbody ok)
  grep -q '^scp ' "$FETCH_LOG" \
    && ok "ps1: the local-tier fetch has the same ordering" \
    || bad "ps1: local plan never ran scp; log: $(cat "$FETCH_LOG") / said: $FL"
  # ⚠ AND THE SOURCE PROPERTY, because the encoding half of this cannot be run
  # from Linux: `>` into the temp file is Out-File, and its PS 5.1 default is
  # UTF-16LE + BOM. Nothing may redirect into a fetch temp again.
  grep -qE 'gh api [^|]*> *\$tmp' client/huginn.ps1 \
    && bad "ps1: a fetch still redirects with '>' - PS 5.1 writes UTF-16 there" \
    || ok "ps1: no fetch redirects into its temp file with '>'"
fi

echo "[5e/8] the address a device is told to dial is a URL"
# ⚠ WHY: $SSH_CONNECTION's third field is a BARE address, and all four wrapper
# call sites built `--url "http://$srv:8787"` from it. On a machine whose ssh to
# the host landed on IPv6 that is `http://fd00::1:8787` - not a URL - and
# `huginn device on` / `huginn local on` died with the bare "huginn-device:
# Invalid URL" after saveConf() had already PERSISTED it, so a later flagless
# `on` repeated it and `serve` logged "not reaching huginn: Invalid URL -
# retrying in 15s" forever. `huginn local on` reached it only after installing
# the whole model tier.
#
# Bracketing alone is not the fix: appd's resolveBind() takes `tailscale ip -4`
# and this deployment overrides it with 0.0.0.0, so nothing listens on v6 and a
# bracketed URL would only turn a cryptic error into a persisted ECONNREFUSED.
# An IPv4 the host actually holds is preferred, and the bracketed v6 is the last
# answer - correct syntax, honest failure.
UT=$(mktemp -d); STUB_DIRS+=("$UT")
cat > "$UT/ssh" <<'USSH'
#!/usr/bin/env bash
cmd="${!#}"
case "$cmd" in
  *'/etc/huginn-appd/token'*) echo 'gate-token' ;;
  *SSH_CONNECTION*)           echo "$SSHCONN" ;;
  *'ip -4'*)                  [ -n "${HOSTV4:-}" ] && echo "$HOSTV4" ;;
esac
exit 0
USSH
chmod +x "$UT/ssh"
url_home () {   # a home whose ~/.huginn already holds runners that print argv
  rm -rf "$UT/home"; mkdir -p "$UT/home/.huginn"
  local f
  for f in huginn-device huginn-local huginn-llm-shim; do
    printf 'console.log(process.argv.slice(2).join(" "));\n' > "$UT/home/.huginn/$f"
  done
}
url_sh () {   # $1 = the huginn command, $2 = SSH_CONNECTION, $3 = the host's IPv4
  url_home
  ( export PATH="$UT:$PATH" HOME="$UT/home" SSHCONN="$2" HOSTV4="${3:-}"
    export HUGINN_LOCAL_DIR="$UT/home/localdir"
    . "$PWD/client/huginn.sh" >/dev/null 2>&1
    eval "$1" ) 2>&1
}
U6=$(url_sh 'huginn device on' 'fd00::2 5000 fd00::1 22' '10.0.0.5')
grep -q -- '--url http://10.0.0.5:8787' <<<"$U6" \
  && ok "sh: an IPv6 ssh path dials an IPv4 the host actually holds" \
  || bad "sh: device on over IPv6 passed: $U6"
U6B=$(url_sh 'huginn device on' 'fd00::2 5000 fd00::1 22' '')
grep -q -- '--url http://\[fd00::1\]:8787' <<<"$U6B" \
  && ok "sh: and with no IPv4 to be had, the v6 literal is BRACKETED" \
  || bad "sh: device on over IPv6 with no v4 passed: $U6B"
U4=$(url_sh 'huginn device on' '192.168.2.50 5000 192.168.2.117 22' '10.0.0.5')
grep -q -- '--url http://192.168.2.117:8787' <<<"$U4" \
  && ok "sh: an IPv4 ssh path is untouched" || bad "sh: device on over IPv4 passed: $U4"
UL=$(url_sh 'huginn local on' 'fd00::2 5000 fd00::1 22' '10.0.0.5')
grep -q -- '--url http://10.0.0.5:8787' <<<"$UL" \
  && ok "sh: and huginn local on builds the same url" || bad "sh: local on passed: $UL"
if command -v pwsh >/dev/null 2>&1; then
  url_ps () {
    url_home
    HOME="$UT/home" SSHCONN="$2" HOSTV4="${3:-}" HUGINN_LOCAL_DIR="$UT/home/localdir" \
      PATH="$UT:$PATH" pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; $1" 2>&1 | tr -d '\r'
  }
  P6=$(url_ps 'huginn device on' 'fd00::2 5000 fd00::1 22' '10.0.0.5')
  grep -q -- '--url http://10.0.0.5:8787' <<<"$P6" \
    && ok "ps1: an IPv6 ssh path dials an IPv4 the host actually holds" \
    || bad "ps1: device on over IPv6 passed: $P6"
  P6B=$(url_ps 'huginn device on' 'fd00::2 5000 fd00::1 22' '')
  grep -q -- '--url http://\[fd00::1\]:8787' <<<"$P6B" \
    && ok "ps1: and with no IPv4 to be had, the v6 literal is BRACKETED" \
    || bad "ps1: device on over IPv6 with no v4 passed: $P6B"
  P4=$(url_ps 'huginn device on' '192.168.2.50 5000 192.168.2.117 22' '')
  grep -q -- '--url http://192.168.2.117:8787' <<<"$P4" \
    && ok "ps1: an IPv4 ssh path is untouched" || bad "ps1: device on over IPv4 passed: $P4"
  PL=$(url_ps 'huginn local on' 'fd00::2 5000 fd00::1 22' '10.0.0.5')
  grep -q -- '--url http://10.0.0.5:8787' <<<"$PL" \
    && ok "ps1: and huginn local on builds the same url" || bad "ps1: local on passed: $PL"
else
  skip "ps1 device-url checks (no pwsh)"
fi
# ⚠ AND THE RUNNER NAMES THE ADDRESS. A bare "Invalid URL" on a machine with
# nobody at it is a message that cannot be acted on - and this one is reached
# AFTER the url has been written to disk.
UD=$(mktemp -d); STUB_DIRS+=("$UD")
UE=$(HUGINN_DEVICE_DIR="$UD" node client/huginn-device on --url 'http://fd00::1:8787' 2>&1)
grep -q 'fd00::1' <<<"$UE" && grep -qi 'bracket' <<<"$UE" \
  && ok "huginn-device names the address it cannot dial, and how to spell it" \
  || bad "huginn-device on a bad url said: $UE"
[ ! -e "$UD/device.json" ] \
  && ok "and it does not persist a url it has just refused" \
  || bad "huginn-device saved an unusable url: $(cat "$UD/device.json")"

echo "[6/8] desktop links come from GitHub, and reach it WITHOUT the host"
# The whole point of the verb is that it works on a machine that cannot ssh here
# (that is why it does not use /v1/desktop-kt, whose every route needs the token).
# Both halves are asserted: the url is right, AND the stub ssh log stayed empty.
# Live network — SKIP LOUDLY when GitHub is unreachable rather than reporting a
# green run that tested nothing.
if ! curl -sfL --max-time 15 -o /dev/null "https://api.github.com/repos/silencelen/huginn/releases?per_page=1"; then
  skip "desktop link checks (GitHub unreachable from here)"
else
  SD=$(semit 'huginn desktop linux')      # SD = what went over ssh; $T2/out = stdout
  URL=$(cat "$T2/out")
  grep -qE '^https://github\.com/silencelen/huginn/releases/download/desktop-v[0-9.]+/huginn-desktop-kt_.*\.deb$' <<<"$URL" \
    && ok "sh: desktop linux prints one bare release url ($URL)" \
    || bad "sh: desktop linux printed: $URL"
  # ⚠ AND THAT IT IS THE NEWEST ONE. The check above asserts the SHAPE of the url
  # and shipped green for months while the client handed out a build four
  # versions stale: GitHub does not return releases newest-first, and the client
  # took the first desktop-v* it saw. A well-formed url to a real file is exactly
  # what that bug looks like. Compared against every desktop tag in the feed, so
  # this needs no knowledge of what the tree happens to be building.
  GOT_V=$(grep -oE 'desktop-v[0-9]+\.[0-9]+\.[0-9]+' <<<"$URL" | head -1 | sed 's/desktop-v//')
  TOP_V=$(curl -sfL --max-time 15 "https://api.github.com/repos/silencelen/huginn/releases?per_page=60" \
            | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"desktop-v[^"]*"' \
            | sed 's/.*"desktop-v\([^"]*\)".*/\1/' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
  [ -n "$GOT_V" ] && [ "$GOT_V" = "$TOP_V" ] \
    && ok "sh: and it is the NEWEST desktop release ($GOT_V)" \
    || bad "sh: desktop points at $GOT_V but the newest published is $TOP_V"
  [ -z "$SD" ] && ok "sh: desktop reached GitHub without touching the host" \
    || bad "sh: desktop sent something over ssh: $SD"
  if command -v pwsh >/dev/null 2>&1; then
    PD=$(emit 'huginn desktop windows')
    PURL=$(tr -d '\r' < "$T/out")
    grep -qE '^https://github\.com/silencelen/huginn/releases/download/desktop-v[0-9.]+/Huginn-Desktop-Setup-.*\.exe$' <<<"$PURL" \
      && ok "ps1: desktop windows prints one bare release url ($PURL)" \
      || bad "ps1: desktop windows printed: $PURL"
    [ -z "$PD" ] && ok "ps1: desktop reached GitHub without touching the host" \
      || bad "ps1: desktop sent something over ssh: $PD"
  else
    skip "ps1 desktop checks (no pwsh)"
  fi
fi
rm -rf "$T2"

echo "[7/8] the headless runner (client/huginn-device)"
# WHY THIS SECTION EXISTS: the runner had no gate at all, and every defect it
# shipped had the same shape — it reported success, or the wrong reason, on a
# machine with NOBODY SITTING AT IT. That is the one place a misleading message
# costs the most, because there is no human to notice the advice is useless.
node --check client/huginn-device && ok "huginn-device parses" || bad "huginn-device does not parse"

DEV_V=$(grep -m1 "^const VERSION = " client/huginn-device | sed "s/.*'\(.*\)'.*/\1/")
[ "$DEV_V" = "$SH_V" ] && ok "huginn-device says $DEV_V, same as the core" \
  || bad "huginn-device says $DEV_V but the core says $SH_V — they ship as one release"

# HUGINN_DEVICE_DIR relocates BOTH device.json and appd-token. The generated unit
# pinned HOME with a six-line comment about why a wrong one is fatal, and then
# dropped this — so the very next line the tool prints installed a service reading
# ~/.config/huginn: no config, no token. serve() treats that as transient and
# loops at 15s forever, so the process never exits, Restart=always never fires,
# and systemd reports the unit perfectly healthy while it does nothing.
for FLAVOUR in "--system" ""; do
  U=$(HUGINN_DEVICE_DIR=/etc/huginn node client/huginn-device unit $FLAVOUR 2>/dev/null)
  grep -q "^Environment=HUGINN_DEVICE_DIR=/etc/huginn$" <<<"$U" \
    && ok "unit ${FLAVOUR:-（user）} carries HUGINN_DEVICE_DIR" \
    || bad "unit ${FLAVOUR:-（user）} drops HUGINN_DEVICE_DIR — the service reads a different config"
  grep -q "^Environment=HOME=" <<<"$U" \
    && ok "unit ${FLAVOUR:-（user）} carries HOME" || bad "unit ${FLAVOUR:-（user）} drops HOME"
done

# `--scope=own --root=/srv/build` enrolled at the DEFAULT scope with no root and
# printed `Enrolled flagbox as "work"`. The word root never appeared in the
# output, so nothing said the build directory had been dropped.
TD=$(mktemp -d)
HUGINN_DEVICE_DIR="$TD" node client/huginn-device on --scpoe=own >/dev/null 2>&1
[ $? -eq 2 ] && ok "an unknown flag is refused, not ignored" || bad "an unknown flag was swallowed"
HUGINN_DEVICE_DIR="$TD" node client/huginn-device on --scope >/dev/null 2>&1
[ $? -eq 2 ] && ok "a flag with no value is refused" || bad "a valueless flag was swallowed"

# The token reason. EACCES, EISDIR, a dangling symlink, an absent file and a file
# holding one captured newline all produced the same "put one in <path>" — advice
# that is wrong in four of those five cases, repeated every 15 seconds forever.
reason () { HUGINN_APPD_TOKEN= HUGINN_DEVICE_DIR="$TD" node client/huginn-device status 2>&1 \
              | grep -m1 "^    token"; }
rm -f "$TD/appd-token"
grep -q "no token file at" <<<"$(reason)" && ok "an absent token says so" || bad "absent token: $(reason)"
printf '\n' > "$TD/appd-token"
grep -q "whitespace" <<<"$(reason)" \
  && ok "a token file holding one newline says SO, not MISSING" \
  || bad "newline-only token: $(reason)"
: > "$TD/appd-token"
grep -q "is empty" <<<"$(reason)" && ok "an empty token file says so" || bad "empty token: $(reason)"
printf 'realtoken\n' > "$TD/appd-token"
grep -qv "MISSING" <<<"$(reason)" && ok "a good token is not reported missing" || bad "good token: $(reason)"

# `off` printed "Removed from huginn." and exited 0 when the DELETE failed, having
# already thrown away conf.id — the only handle that could ever remove the row.
# So exactly when a machine is decommissioned (host asleep, VPN down, wrong url)
# the row was orphaned on the host and unremovable from the machine, and a restart
# enrolled a second one.
printf '{"id":"11111111-1111-1111-1111-111111111111","url":"http://127.0.0.1:1","scope":"work"}\n' > "$TD/device.json"
printf 'sometoken\n' > "$TD/appd-token"
OFF=$(HUGINN_DEVICE_DIR="$TD" node client/huginn-device off 2>&1); OFF_RC=$?
[ "$OFF_RC" -ne 0 ] && ok "off fails loudly when huginn is unreachable" \
  || bad "off exited 0 with the host unreachable"
grep -q "Removed from huginn" <<<"$OFF" && bad "off claimed success while failing" \
  || ok "off does not claim to have removed anything"
grep -q '11111111-1111-1111-1111-111111111111' "$TD/device.json" \
  && ok "off keeps the id, so it can be run again" \
  || bad "off destroyed the only handle that can remove the row"
# ...AND the token with it. A failed off that swept the credential away would
# leave a machine that cannot retry the very thing it was told to retry.
[ -s "$TD/appd-token" ] && ok "a refused off keeps the token too" \
  || bad "a refused off destroyed the token, so it can never be retried"

# --force is the ONE exit from that refusal, for the machine that is going away
# regardless (an uninstall, a wipe). It must clear BOTH files, exit 0, and NAME
# the row it is stranding - a silent force would be the original bug with a flag
# on it.
FOFF=$(HUGINN_DEVICE_DIR="$TD" node client/huginn-device off --force 2>&1); FOFF_RC=$?
[ "$FOFF_RC" -eq 0 ] && ok "off --force exits 0: the local half is what it promised" \
  || bad "off --force exited $FOFF_RC"
grep -q '11111111-1111-1111-1111-111111111111' <<<"$FOFF" \
  && ok "off --force names the row it stranded" \
  || bad "off --force cleared the machine without naming the row left on the host"
grep -q "Removed from huginn" <<<"$FOFF" && bad "off --force claimed the row was removed" \
  || ok "off --force does not claim the row went with it"
[ ! -e "$TD/device.json" ] && [ ! -e "$TD/appd-token" ] \
  && ok "off --force leaves neither the config nor the token" \
  || bad "off --force left $(ls "$TD" 2>/dev/null | tr '\n' ' ')behind"
rm -rf "$TD"

# ⚠ AND `--force=false` IS A REFUSAL, NOT A SETTING. A value-less flag written
# with an `=` used to fall through to the string branch, and a non-empty string
# is truthy - so the one spelling somebody reaches for to turn a destructive
# flag OFF was the spelling that turned it on.
TD3=$(mktemp -d)
printf '{"id":"44444444-4444-4444-4444-444444444444","url":"http://127.0.0.1:1"}\n' > "$TD3/device.json"
printf 'sometoken\n' > "$TD3/appd-token"
HUGINN_DEVICE_DIR="$TD3" node client/huginn-device off --force=false >/dev/null 2>&1
[ $? -eq 2 ] && ok "off --force=false is refused, not read as a boolean" \
  || bad "off --force=false was accepted"
[ -s "$TD3/appd-token" ] && ok "and it changed nothing on the way out" \
  || bad "a refused flag still destroyed the token"
rm -rf "$TD3"

# ⚠ AND THE SUCCESS PATH TAKES THE TOKEN WITH IT. `off` used to delete the id
# out of device.json and leave BOTH files sitting there, so a machine somebody
# had just decommissioned kept a working bearer token for the daemon - the same
# one /etc/huginn-appd/token holds, root-equivalent on the host - in a file
# nobody would ever look at again. Needs a listener, so it SKIPS LOUDLY rather
# than reporting green on a box where the port is taken.
TD2=$(mktemp -d)
node -e '
const http = require("http");
const s = http.createServer((q, r) => { r.writeHead(200, {"Content-Type":"application/json"}); r.end("{}"); });
s.on("error", () => process.exit(1));
s.listen(8791, "127.0.0.1", () => { console.log("up"); });
setTimeout(() => process.exit(0), 20000);
' > "$TD2/srv.log" 2>&1 &
SRV_PID=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do grep -q up "$TD2/srv.log" 2>/dev/null && break; sleep 0.3; done
if ! grep -q up "$TD2/srv.log" 2>/dev/null; then
  skip "off success path (could not listen on 127.0.0.1:8791)"
else
  printf '{"id":"22222222-2222-2222-2222-222222222222","url":"http://127.0.0.1:8791","scope":"work"}\n' > "$TD2/device.json"
  printf 'sometoken\n' > "$TD2/appd-token"
  SOFF=$(HUGINN_DEVICE_DIR="$TD2" node client/huginn-device off 2>&1)
  grep -q "Removed from huginn" <<<"$SOFF" \
    && ok "off says so when the row really went" || bad "off on a live daemon said: $SOFF"
  [ ! -e "$TD2/device.json" ] && [ ! -e "$TD2/appd-token" ] \
    && ok "a successful off takes the config AND the token" \
    || bad "off left a live bearer token on a decommissioned machine"
fi
kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null
rm -rf "$TD2"

# ⚠ THE SIZE RULES, which decide whether a run's whole answer survives. A device
# streams stream-json with --include-partial-messages, so ONE line can carry a
# whole tool_result. Over the daemon's body cap the POST came back 413 and the
# WHOLE BATCH was lost — and if the terminal frame was in it, its retry was
# identically 413, so the ending could never be delivered: the chat sat running
# forever and the machine was blocked from every other job. Silent, permanent,
# and reserved for the runs with the most to say.
node -e '
const assert = require("assert");
// ⚠ THE TREE'S runner, not /opt/huginn's. This line named the live clone by
// absolute path, so a worktree or a branch ran its gate against whatever the
// deployment happened to hold - a green that says nothing about the code in
// front of you, which is the same lesson [8/8] exists for.
const r = require(process.cwd() + "/client/huginn-device");
const big = JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", content: "x".repeat(400000) }] } });
const small = r.shrinkLine(big);
assert.ok(Buffer.byteLength(small) <= r.MAX_LINE_BYTES, "an oversized line was not shrunk");
const ev = JSON.parse(small);                       // still a valid event, not a fragment
assert.equal(ev.type, "user", "shrinking lost the event type");
assert.ok(JSON.stringify(ev).includes("were dropped"), "the truncation is not admitted");
const lines = Array.from({ length: 12 }, (_, i) => JSON.stringify({ n: i, pad: "y".repeat(30000) }));
const batches = r.batchLines(lines);
assert.ok(batches.length > 1, "12 x 30KB should not be one batch");
for (const b of batches) assert.ok(Buffer.byteLength(JSON.stringify(b)) <= r.MAX_BATCH_BYTES, "a batch exceeds the budget");
const order = batches.flat().map((l) => JSON.parse(l).n);
assert.deepEqual(order, [...Array(12).keys()], "batching reordered the output");
assert.deepEqual(r.batchLines([]), [], "an empty tail should post nothing");
' && ok "output is batched, shrunk in place, and kept in order" \
   || bad "the size rules do not hold — a large answer can still be lost"

# ⚠ WHICH ENGINE FAILED. Both terminal handlers hardcoded "claude", so a
# generate device's local-engine failure was reported as a claude failure -
# naming a program the machine does not run, and telling somebody to set a
# config key ("claude") this install does not have. The key is `llm`. And the
# stderr kept was the TAIL, which on the realistic failure (a missing shim) is
# node's stack, with the "Cannot find module '<path>'" headline thrown away.
node -e '
const assert = require("assert");
const r = require(process.cwd() + "/client/huginn-device");
const stack = "Error: Cannot find module \x27/srv/hl/bin/huginn-llm-shim.js\x27\n"
  + "    at Module._resolveFilename (node:internal/modules/cjs/loader:1234:15)\n"
  + "    at Module._load (node:internal/modules/cjs/loader:1056:27)\n"
  + "    at wrapModuleLoad (node:internal/modules/cjs/loader:220:24)";
const nf = r.notFoundText("generate", "kratos");
assert.ok(/local engine/.test(nf), "a generate failure must not be about claude: " + nf);
assert.ok(/"llm"/.test(nf), "it must name the config key this install has: " + nf);
assert.ok(!/claude/.test(nf), "claude is not on this machine: " + nf);
assert.ok(/claude/.test(r.notFoundText("ask", "kratos")), "an ask failure IS about claude");
const ex = r.exitedText("generate", "kratos", 1, stack);
assert.ok(!/claude/.test(ex), "a generate exit must not be about claude: " + ex);
assert.ok(/Cannot find module/.test(ex) && /huginn-llm-shim/.test(ex),
  "the HEAD of stderr names the file; the tail is stack: " + ex);
assert.ok(/claude exited 1/.test(r.exitedText("ask", "kratos", 1, "")), "ask keeps its wording");
' && ok "a local-engine failure is reported as a local-engine failure" \
   || bad "the runner still blames claude for the local engine, or keeps the wrong end of stderr"

# ⚠ A 401 IS NOT A BLIP, AND IT IS NOT A REASON TO KILL THE CHILD. permanent()
# matched only 400/403/404/413, so a token rotated or deleted mid-run fell into
# the retry branch and was re-POSTed every 500 ms for the life of the run
# (measured: 52 POSTs in 24.6 s, both finish() frames failing, `pending` growing
# the whole time) - and after `huginn device off --force` or `huginn uninstall`
# the child could no longer be cancelled either, because cancel rides on a
# successful POST. Widening permanent() to 401 is the WRONG fix: that branch
# kills the child, possibly mid-edit.
node -e '
const assert = require("assert");
const r = require(process.cwd() + "/client/huginn-device");
const four01 = new Error("POST /v1/devices/x/work/y/events → 401 unauthorized");
assert.equal(r.permanent(four01), false,
  "a 401 must not reach the branch that kills a live child");
assert.equal(r.authFailed(four01), true, "a 401 is a delivery failure");
assert.equal(r.authFailed(r.noTokenError("the token file is empty")), true,
  "the pre-flight no-token rejection is a delivery failure too");
assert.equal(r.authFailed(new Error("no appd token — the token file is empty")), false,
  "recognised by the FLAG on the Error, never by matching its prose");
assert.equal(r.authFailed(new Error("POST /x → 502 bad gateway")), false, "a 502 is a blip");
assert.equal(r.permanent(new Error("POST /x → 404 gone")), true, "404 still tears down");
assert.ok(r.retryDelayMs(1) >= 1000 && r.retryDelayMs(1) <= 2000, "the first retry backs off");
assert.ok(r.retryDelayMs(3) > r.retryDelayMs(1), "and it grows");
assert.equal(r.retryDelayMs(99), 30000, "capped, so a long outage is not a busy loop");
' && ok "a 401 stops delivery without stopping the child, and blips back off" \
   || bad "a 401 is still retried twice a second, or kills the run"

# ⚠ "KEEP ACT MODE WHILE LOCKED" — the one setting that decides whether a lock
# screen withdraws authority. Two facts wear the word `locked`: what the machine
# REPORTS (honest, rides every frame home) and what the POLICY may see (the
# owner's standing answer applied to it). gateLocked is the whole of the second,
# and the case matrix in shared/device-policy-cases.json is deliberately NOT
# involved: the lattice did not change, the signal feeding it did.
node -e '
const assert = require("assert");
const r = require(process.cwd() + "/client/huginn-device");
const REF = "this machine is locked, so it is read-only until someone unlocks it";
const work = (mode) => ({ id: "w", chatId: "c", prompt: "p", mode });

assert.equal(r.gateLocked(true,  false), true,  "locked + setting off must still gate");
assert.equal(r.gateLocked(true,  true),  false, "locked + setting on must NOT gate");
assert.equal(r.gateLocked(false, false), false, "unlocked is unlocked");
assert.equal(r.gateLocked(false, true),  false, "unlocked + setting on is still unlocked");
// ⚠ ONLY A REAL `true`. An absent key is every device.json written before this
// existed, and a truthy string is what a hand-edited "actWhileLocked": "no"
// would be - a machine running Bash unattended because it read a denial as
// consent.
assert.equal(r.gateLocked(true, undefined), true, "an absent setting is OFF");
assert.equal(r.gateLocked(true, "no"), true, "a string is not a yes");
assert.equal(r.gateLocked(true, 1), true, "nor is a 1");

assert.equal(r.refusal("own", r.gateLocked(true, false), "act"), REF,
  "the refusal a person already knows must not change wording");
assert.equal(r.refusal("own", r.gateLocked(true, true), "act"), null,
  "with the setting on, act runs while locked");
// It widens the LOCK and nothing else: a look machine is still a look machine.
assert.equal(r.refusal("look", r.gateLocked(true, true), "act"),
  "this machine is set to look, which cannot run act",
  "the setting must not sideways-widen the scope");
const granted = r.argvFor(work("act"), "own", r.gateLocked(true, true)).join(" ");
assert.ok(/--allowedTools [^-]*Bash/.test(granted), "act while locked must carry the act grant: " + granted);
const refused = r.argvFor(work("act"), "own", r.gateLocked(true, false)).join(" ");
assert.ok(/--disallowedTools [^-]*Bash/.test(refused), "act refused must keep the read-only argv: " + refused);
assert.equal(r.cwdFor("work", r.gateLocked(true, true), "/root-dir"), "/root-dir",
  "a work run keeps its root when the setting holds the scope up");
// And this runner still reports NO lock state of its own - a headless box has
// no session to lock, and inventing one is the fake signal the file refuses.
assert.equal(r.locked(), false, "the headless runner must not start claiming a lock");
' && ok "the act-while-locked gate is the only thing it changes" \
   || bad "the act-while-locked gate is wrong, or it moved the scope lattice"

# ⚠ AND IT SURVIVES THE ROUND TRIP, IN BOTH DIRECTIONS. A setting that can only
# be turned ON is a trapdoor, and one whose OFF never reaches the daemon leaves
# the pre-check granting act on a machine whose owner revoked it — the narrowing
# that does not travel. Needs a listener, so it SKIPS LOUDLY rather than
# reporting green on a box where it could not bind.
AW=$(mktemp -d)
cat > "$AW/stub.js" <<'STUB'
const fs = require("fs"); const http = require("http");
const s = http.createServer((q, r) => {
  let b = ""; q.on("data", (c) => { b += c; });
  q.on("end", () => {
    fs.appendFileSync(process.argv[3], `${q.method} ${q.url} ${b}\n`);
    r.writeHead(q.method === "POST" ? 201 : 200, { "Content-Type": "application/json" });
    r.end(JSON.stringify({ id: "33333333-3333-3333-3333-333333333333", scope: "work" }));
  });
});
s.on("error", () => process.exit(1));
s.listen(0, "127.0.0.1", () => fs.writeFileSync(process.argv[2], String(s.address().port)));
setTimeout(() => process.exit(0), 30000);
STUB
node "$AW/stub.js" "$AW/port" "$AW/reqs" >/dev/null 2>&1 &
AW_PID=$!; STUB_PIDS+=("$AW_PID"); STUB_DIRS+=("$AW")
AW_PORT=$(stub_port "$AW/port")
if [ -z "$AW_PORT" ]; then
  skip "act-while-locked round trip (the stub could not bind)"
else
  printf 'tok\n' > "$AW/appd-token"
  HUGINN_DEVICE_DIR="$AW" node client/huginn-device on \
    --url "http://127.0.0.1:$AW_PORT" --scope own --act-while-locked >/dev/null 2>&1
  node -e '
    const fs = require("fs");
    const c = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (c.actWhileLocked !== true) { console.error("device.json: " + JSON.stringify(c.actWhileLocked)); process.exit(1); }
    const body = JSON.parse(fs.readFileSync(process.argv[2], "utf8").trim().split("\n").pop().replace(/^\S+ \S+ /, ""));
    if (body.actWhileLocked !== true) { console.error("enrol body: " + JSON.stringify(body)); process.exit(1); }
    if (body.locked !== false) { console.error("the reported lock state must stay honest: " + JSON.stringify(body)); process.exit(1); }
  ' "$AW/device.json" "$AW/reqs" \
    && ok "--act-while-locked is stored and enrolled with, and the reported lock stays honest" \
    || bad "--act-while-locked did not survive to the config or the wire"
  HUGINN_DEVICE_DIR="$AW" node client/huginn-device on --no-act-while-locked >/dev/null 2>&1
  node -e '
    const fs = require("fs");
    const c = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (c.actWhileLocked !== false) { console.error("device.json: " + JSON.stringify(c.actWhileLocked)); process.exit(1); }
    const body = JSON.parse(fs.readFileSync(process.argv[2], "utf8").trim().split("\n").pop().replace(/^\S+ \S+ /, ""));
    if (body.actWhileLocked !== false) { console.error("enrol body: " + JSON.stringify(body)); process.exit(1); }
  ' "$AW/device.json" "$AW/reqs" \
    && ok "--no-act-while-locked travels too: turning it off is not a local-only fact" \
    || bad "turning act-while-locked off did not reach the config or the daemon"
  HUGINN_DEVICE_DIR="$AW" node client/huginn-device on --act-while-locked=true >/dev/null 2>&1
  [ $? -eq 2 ] && ok "the =value spelling is refused, not read as a boolean" \
    || bad "--act-while-locked=true was accepted"
  HUGINN_DEVICE_DIR="$AW" node client/huginn-device on --act-while-locked --no-act-while-locked >/dev/null 2>&1
  [ $? -eq 2 ] && ok "asking for both at once is refused rather than resolved by order" \
    || bad "both spellings at once were silently resolved"
fi

echo "[local/8] the local tier: manager, shim, manifest, units"
node --check client/huginn-local && ok "huginn-local parses" || bad "huginn-local does not parse"
node --check client/huginn-llm-shim && ok "huginn-llm-shim parses" || bad "huginn-llm-shim does not parse"

LOCAL_V=$(grep -m1 "^const VERSION = " client/huginn-local | sed "s/.*'\(.*\)'.*/\1/")
SHIM_V=$(grep -m1 "^const VERSION = " client/huginn-llm-shim | sed "s/.*'\(.*\)'.*/\1/")
[ "$LOCAL_V" = "$SH_V" ] && ok "huginn-local says $LOCAL_V, same as the core" \
  || bad "huginn-local says $LOCAL_V but the core says $SH_V — they ship as one release"
[ "$SHIM_V" = "$SH_V" ] && ok "huginn-llm-shim says $SHIM_V, same as the core" \
  || bad "huginn-llm-shim says $SHIM_V but the core says $SH_V — they ship as one release"

# The embedded pins ARE the gated-bump mechanism; drift here means somebody
# edited one side without the generator, which is the road to an unreviewed
# runtime landing on a serving machine.
node scripts/gen-local-manifest.js --check >/dev/null \
  && ok "the embedded manifest matches shared/local-runtime.json" \
  || bad "manifest drift — run: node scripts/gen-local-manifest.js and READ the diff"

# The PERSISTENCE half: whether this install still serves after its owner logs
# out, and whether every surface says so. Driven through an injectable command
# runner rather than the real systemd, because every fact it decides is a fact
# about a machine nobody is sitting at — see the file's own header. Captured,
# never piped: this script runs under `set -o pipefail`.
LT_OUT=$(node --test scripts/test-local-tier.js 2>&1); LT_RC=$?
LT_N=$(grep -oE '^# pass [0-9]+' <<<"$LT_OUT" | grep -oE '[0-9]+')
if [ "$LT_RC" -eq 0 ] && [ "${LT_N:-0}" -ge 20 ]; then
  ok "the local tier's persistence gate (${LT_N} tests)"
else
  bad "local-tier gate FAILED (exit $LT_RC, ${LT_N:-0} passed):"
  grep -E '^not ok|error:' <<<"$LT_OUT" | head -12 >&2
fi

# The device-unit lesson, re-applied: a unit that drops the env var that moves
# its own files is a service that loops forever while systemd calls it healthy.
UNIT_LLM=$(HUGINN_LOCAL_DIR=/tmp/hl-gate node client/huginn-local unit --system --which llm)
echo "$UNIT_LLM" | grep -q 'Environment=HUGINN_LOCAL_DIR=/tmp/hl-gate' \
  && ok "the llm unit pins HUGINN_LOCAL_DIR" || bad "the llm unit drops HUGINN_LOCAL_DIR"
echo "$UNIT_LLM" | grep -q 'Environment=HOME=' \
  && ok "the llm unit pins HOME" || bad "the llm unit drops HOME"
UNIT_RUN=$(HUGINN_LOCAL_DIR=/tmp/hl-gate node client/huginn-local unit --system --which runner)
echo "$UNIT_RUN" | grep -q 'Environment=HUGINN_DEVICE_DIR=/tmp/hl-gate/device' \
  && ok "the runner unit pins HUGINN_DEVICE_DIR" || bad "the runner unit drops HUGINN_DEVICE_DIR"

HUGINN_LOCAL_DIR=/tmp/hl-gate node client/huginn-local on --clsas=G8 >/dev/null 2>&1
[ $? -eq 2 ] && ok "an unknown flag is refused, not ignored" || bad "an unknown flag was swallowed"

# `--purge` is the uninstall-hygiene door: the whole tier, not just the models.
# Asserted as a KNOWN flag - exit 1 "not set up", never exit 2 "unknown flag".
# A purge silently rejected as a typo looks exactly like a purge that ran and
# found nothing, which is the --scpoe lesson pointed at a delete.
PURGE_DIR=$(mktemp -d); rmdir "$PURGE_DIR"
HUGINN_LOCAL_DIR="$PURGE_DIR" node client/huginn-local off --purge --yes >/dev/null 2>&1
[ $? -eq 1 ] && ok "--purge is a known flag, refused only for want of an install" \
  || bad "--purge was rejected as an unknown flag"

# ⚠ AND IT REFUSES A DIRECTORY IT DOES NOT OWN. HUGINN_LOCAL_DIR is an argument
# somebody supplies and --purge is a recursive delete of whatever it names, so
# pointed at a home by a typo it would take the home.
GUARD=$(mktemp -d); mkdir -p "$GUARD/home/bin"
cp client/huginn-device "$GUARD/home/bin/huginn-device.js"
printf '{"mode":"managed","systemUnits":false}\n' > "$GUARD/home/local.json"
printf 'keepme\n' > "$GUARD/home/precious"
# Captured, never piped: this file runs under `set -o pipefail`, and the command
# under test EXITS NONZERO on purpose (a refused step is a failed step), so
# `node ... | grep -q` reports the node exit and the gate fails on a pass.
GOUT=$(HOME="$GUARD/home" HUGINN_LOCAL_DIR="$GUARD/home" \
  node client/huginn-local off --purge --yes 2>&1)
grep -q "does not own" <<<"$GOUT" \
  && ok "--purge refuses a HUGINN_LOCAL_DIR that is a home directory" \
  || bad "--purge did not refuse a home directory: $GOUT"
[ -f "$GUARD/home/precious" ] && ok "and the home survived it" \
  || bad "--purge deleted a home directory"
rm -rf "$GUARD"

# The same lesson on this side: a value-less flag written with an `=` fell
# through to the string branch, and every `if (flags.x)` here reads a non-empty
# string as yes - so `--purge-models=false` deleted the models.
HUGINN_LOCAL_DIR=/tmp/hl-gate node client/huginn-local off --purge-models=false >/dev/null 2>&1
[ $? -eq 2 ] && ok "--purge-models=false is refused, not read as a boolean" \
  || bad "--purge-models=false was accepted as a value"

# `plan` is the desktop's consent card: it must DECIDE everything and DO
# nothing. Both halves are asserted — the answer's shape, and the empty dir.
PLAN_DIR=$(mktemp -d)
HUGINN_LOCAL_DIR="$PLAN_DIR" node client/huginn-local plan --json | python3 -c '
import json, sys
p = json.load(sys.stdin)
assert ("refuse" in p) != ("downloads" in p), "exactly one of refuse/downloads"
assert p["deviceName"].endswith("-llm"), p["deviceName"]
assert p["services"] == ["huginn-local-llm", "huginn-local-runner"], p["services"]
' && ok "plan --json answers with a decision (refuse XOR downloads)" \
  || bad "plan --json is not a decision the desktop can render"
[ -z "$(ls -A "$PLAN_DIR")" ] && ok "plan wrote nothing — read-only as promised" \
  || bad "plan MUTATED its dir: $(ls -A "$PLAN_DIR" | head -3 | tr '\n' ' ')"
rm -rf "$PLAN_DIR"

# Modern node refuses to PARSE an unknown extension — `node --check x.tmp`
# dies with ERR_UNKNOWN_FILE_EXTENSION (esm/get_format; reproduced on this
# host's node 22.23.1, field-hit by the first Node-24 machine). Every fetch
# that syntax-checks its download must therefore download under a .js name.
[ "$(grep -c 'tmp="\$dest\.tmp\.js"' client/huginn.sh)" = 2 ] \
  && ok "sh fetches syntax-check under a .js temp name" \
  || bad "sh fetch temp name regressed — .tmp is unparseable on modern node"
# The ps1 side now has ONE fetch helper (see [5d/8]) instead of two inline
# copies, so this is asserted as a property rather than counted: every
# `node --check` in the file targets the helper's temp, and that temp is .js.
PS_CHK=$(grep -c '^[[:space:]]*node --check' client/huginn.ps1)
PS_JS=$(grep -c '^[[:space:]]*node --check \$tmp ' client/huginn.ps1)
[ "$PS_CHK" -gt 0 ] && [ "$PS_CHK" = "$PS_JS" ] \
  && grep -q '\$tmp = "\$Dest\.tmp\.js"' client/huginn.ps1 \
  && ok "ps1 fetches syntax-check under a .js temp name" \
  || bad "ps1 fetch temp name regressed — .tmp is unparseable on modern node"

# The delegation lane: parse + parity here; the LIVE path is the daily
# smoke's job. A tool promised in two shells must exist in both.
bash -n server/bin/huginn-llm && ok "huginn-llm parses" || bad "huginn-llm does not parse"
grep -q '^    llm) ssh -T' client/huginn.sh && grep -q "eq 'llm'" client/huginn.ps1 \
  && ok "both shells carry: llm" || bad "the llm verb is missing from a shell client"

# The headroom lane, same rule: a verb promised in two shells must have a
# renderer on the host, and setup.sh must actually install it — `huginn headroom`
# is nothing but an ssh to that file, so a missing install line answers the verb
# with "command not found" on a freshly set-up host. The renderer is pure python
# (its siblings are `python3 -c` inside single quotes, where one apostrophe ends
# the program), so its syntax check is a real one.
# compile(), not `python3 -m py_compile`: the module form writes a .pyc into a
# __pycache__ beside the file, and an untracked directory that appears every time
# the gates run is exactly the stray the nightly snapshot trips over.
python3 -c 'import sys; compile(open(sys.argv[1]).read(), sys.argv[1], "exec")' \
  server/bin/huginn-headroom 2>/dev/null \
  && ok "huginn-headroom compiles" || bad "huginn-headroom does not compile"
[ -x server/bin/huginn-headroom ] && ok "huginn-headroom is executable" \
  || bad "huginn-headroom is not executable — ssh would refuse to run it"
grep -q '^    headroom)' client/huginn.sh && grep -q "eq 'headroom'" client/huginn.ps1 \
  && ok "both shells carry: headroom" || bad "the headroom verb is missing from a shell client"
grep -q 'install_script .*bin/huginn-headroom' server/setup.sh \
  && ok "setup.sh installs huginn-headroom" \
  || bad "setup.sh does not install huginn-headroom — the verb would 'command not found'"
# What the two clients actually SEND. The whole verb is one ssh to the renderer,
# so a client that sends the wrong command line is the whole feature broken -- and
# a `--json` swallowed on the way (an empty argv element, a lost array, a flag
# eaten by PowerShell's parameter binder) looks exactly like a working verb.
# Its own stub ssh, so neither the [4/8] nor the [5/8] harness has to change.
HRT=$(mktemp -d)
cat > "$HRT/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SSH_LOG"
exit 0
STUB
chmod +x "$HRT/ssh"
HRSH=$( export SSH_LOG="$HRT/log"; : > "$SSH_LOG"
        ( export PATH="$HRT:$PATH"
          . "$PWD/client/huginn.sh" >/dev/null 2>&1
          huginn headroom; huginn headroom --json ) >/dev/null 2>&1
        cat "$SSH_LOG" )
grep -q -- '-T .* huginn-headroom$' <<<"$HRSH" && grep -q -- 'huginn-headroom --json' <<<"$HRSH" \
  && ok "sh: headroom ssh -T's the renderer, with and without --json" \
  || bad "sh: headroom sent: $HRSH"
if command -v pwsh >/dev/null 2>&1; then
  HRPS=$( export SSH_LOG="$HRT/log2"; : > "$SSH_LOG"
          PATH="$HRT:$PATH" pwsh -NoProfile -Command \
            ". $PWD/client/huginn.ps1; huginn headroom; huginn headroom --json" >/dev/null 2>&1
          cat "$SSH_LOG" )
  grep -q -- '-T .* huginn-headroom$' <<<"$HRPS" && grep -q -- "huginn-headroom '--json'" <<<"$HRPS" \
    && ok "ps1: headroom sends the same, and --json survives the binder" \
    || bad "ps1: headroom sent: $HRPS"
else
  skip "ps1 headroom send check (no pwsh)"
fi
rm -rf "$HRT"

# It must degrade, not explode, against the daemon this host is running today:
# every appd before 3.0.0 404s /v1/headroom, and the renderer has to say WHICH
# version is missing rather than "not found" — the person reading it is the
# person who can deploy the daemon. Driven against a stub, so this holds on a
# host where appd is stopped, and on one already running 3.0.0.
HR_PF="$STUB_DIR/headroom.port"
python3 - "$HR_PF" "${HUGINN_TEST_HEADROOM_PORT:-0}" <<'STUB' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404); self.end_headers(); self.wfile.write(b'{"error":"not found"}')
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
# The port file is the lane's proof that the listener it is about to talk to is
# OURS. Written after bind, so it never appears for a stub that lost the port.
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
STUB
HR_STUB=$!; STUB_PIDS+=("$HR_STUB")
HR_PORT=$(stub_port "$HR_PF")
if [ -z "${HR_PORT:-}" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below read "connection refused" and the 404 check would fail for the wrong
  # reason, which is worse than not running it.
  skip "headroom daemon-too-old checks (the stub never bound a port)"
else
  HR_OUT=$(HUGINN_APPD_URL="http://127.0.0.1:$HR_PORT" server/bin/huginn-headroom 2>&1); HR_RC=$?
  grep -q 'needs appd 3.0.0' <<<"$HR_OUT" && [ "$HR_RC" = 1 ] \
    && ok "headroom on an older daemon names the version it needs (exit 1)" \
    || bad "headroom on a 404 said: $HR_OUT (exit $HR_RC)"
fi
HR_DEAD=$(HUGINN_APPD_URL="http://127.0.0.1:1" server/bin/huginn-headroom 2>&1); HR_RC=$?
[ "$HR_RC" = 2 ] \
  && ok "headroom exits 2 when nothing is answering (1 = appd is there but cannot serve it)" \
  || bad "headroom with no daemon exited $HR_RC: $HR_DEAD"
# ⚠ AND IT NEVER PRINTS THE TOKEN. Its failure paths were written by hand and the
# bearer token is one variable away from every string they emit — urllib carries
# the whole request, headers included, inside some of its exceptions, which is
# why this renderer prints its own message instead of the caught one.
if [ -r /etc/huginn-appd/token ]; then
  HR_TOK=$(tr -d '[:space:]' < /etc/huginn-appd/token)
  if [ -n "$HR_TOK" ] && grep -qF "$HR_TOK" <<<"$HR_OUT$HR_DEAD"; then
    bad "headroom printed the bearer token on a failure path"
  else
    ok "headroom failure paths print no credential"
  fi
else
  skip "headroom token-leak check (no readable /etc/huginn-appd/token here)"
fi
kill "$HR_STUB" 2>/dev/null

# What the two clients SEND for archive/revive. The verb is one ssh to the host
# renderer, so a client that sends the wrong command line is the whole feature
# broken — and a flag eaten on the way (an empty argv element, a lost array, a
# `--now` swallowed by PowerShell's parameter binder) looks exactly like a
# working verb from the caller's side.
ART=$(mktemp -d)
cat > "$ART/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SSH_LOG"
exit 0
STUB
chmod +x "$ART/ssh"
ARSH=$( export SSH_LOG="$ART/log"; : > "$SSH_LOG"
        ( export PATH="$ART:$PATH"
          . "$PWD/client/huginn.sh" >/dev/null 2>&1
          huginn archive; huginn archive testsess; huginn archive testsess --now
          huginn revive testsess ) >/dev/null 2>&1
        cat "$SSH_LOG" )
grep -q -- '-T .* huginn-archive$' <<<"$ARSH" \
  && ok "sh: bare archive ssh -T's the renderer with NO empty argument" \
  || bad "sh: bare archive sent: $ARSH"
grep -q -- "huginn-archive testsess" <<<"$ARSH" && grep -q -- "huginn-archive testsess --now" <<<"$ARSH" \
  && ok "sh: archive carries the session name, and --now survives" || bad "sh: archive sent: $ARSH"
grep -q -- "huginn-archive revive testsess" <<<"$ARSH" \
  && ok "sh: revive reaches the renderer" || bad "sh: revive sent: $ARSH"
# ⚠ A NAME REACHES A REMOTE SHELL. Both verbs refuse anything outside their
# allow-list BEFORE it is interpolated, and this is the assertion that says so:
# nothing at all may be sent.
ARBAD=$( export SSH_LOG="$ART/logbad"; : > "$SSH_LOG"
         ( export PATH="$ART:$PATH"
           . "$PWD/client/huginn.sh" >/dev/null 2>&1
           huginn archive 'a;rm -rf /'; huginn revive 'b$(whoami)' ) >/dev/null 2>&1
         cat "$SSH_LOG" )
[ -z "$ARBAD" ] && ok "sh: a shell-shaped name is refused before it is sent anywhere" \
  || bad "sh: sent a refused name: $ARBAD"
if command -v pwsh >/dev/null 2>&1; then
  ARPS=$( export SSH_LOG="$ART/log2"; : > "$SSH_LOG"
          PATH="$ART:$PATH" pwsh -NoProfile -Command \
            ". $PWD/client/huginn.ps1; huginn archive; huginn archive testsess; huginn archive testsess --now; huginn revive testsess" >/dev/null 2>&1
          cat "$SSH_LOG" )
  grep -q -- '-T .* huginn-archive$' <<<"$ARPS" \
    && ok "ps1: bare archive sends the same bare renderer call" || bad "ps1: bare archive sent: $ARPS"
  grep -q -- "huginn-archive 'testsess' '--now'" <<<"$ARPS" \
    && ok "ps1: --now survives the parameter binder" || bad "ps1: archive --now sent: $ARPS"
  grep -q -- "huginn-archive revive 'testsess'" <<<"$ARPS" \
    && ok "ps1: revive reaches the renderer" || bad "ps1: revive sent: $ARPS"
else
  skip "ps1 archive send check (no pwsh)"
fi
rm -rf "$ART"

# The archive lane. Same rule as headroom — a verb promised in two shells must
# have a renderer on the host and an install line for it — plus one this verb has
# that no other does: the daemon REFUSES an archive in prose ("answer the waiting
# question first, then archive the session"), and the whole reason the call is
# made host-side is so that sentence survives. A client that printed its own
# guess instead would look identical from the outside.
bash -n server/bin/huginn-archive && ok "huginn-archive parses" || bad "huginn-archive does not parse"
[ -x server/bin/huginn-archive ] && ok "huginn-archive is executable" \
  || bad "huginn-archive is not executable — ssh would refuse to run it"
grep -q 'install_script .*bin/huginn-archive' server/setup.sh \
  && ok "setup.sh installs huginn-archive" \
  || bad "setup.sh does not install huginn-archive — the verb would 'command not found'"
# ⚠ ITS PYTHON LIVES INSIDE `python3 -c ' ... '`, so ONE apostrophe anywhere in it
# closes the program and the shell starts parsing python. Nothing but reading the
# real file can catch that — extracting the block to a .py to test it cannot.
AP_BAD=$(awk "/python3 -c '/{inpy=1; next} /^' /{inpy=0} inpy" server/bin/huginn-archive | grep -c "'")
[ "$AP_BAD" = 0 ] \
  && ok "huginn-archive's embedded python carries no apostrophe" \
  || bad "huginn-archive has $AP_BAD apostrophe line(s) inside python3 -c '...' — the program ends there"

# End to end against a stub daemon, the way the headroom lane does it: this
# renderer is the ONLY implementation of what an archive looks like, in either
# client, so a parse check would be most of it untested.
AR_PF="$STUB_DIR/archive.port"
python3 - "$AR_PF" "${HUGINN_TEST_ARCHIVE_PORT:-0}" <<'ARSTUB' &
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
ROWS = {"max": 64, "archives": [
    {"id": "0123abcd-0000-4000-8000-00000000abcd", "tmuxName": "jtyper",
     "title": "Archive session feature", "cwd": "/root/netplan", "model": "claude-opus-4-5",
     "resumeCommand": "cd '/root/netplan' && claude --resume 0123abcd-0000-4000-8000-00000000abcd",
     "archivedAt": 1, "endedAt": 2, "lastMessage": "suite green",
     "transcriptBytes": 4096, "transcriptTruncated": False,
     "revivedAt": None, "revivedAs": None, "live": False, "transcriptPresent": True},
    {"id": "2222abcd-0000-4000-8000-00000000abcd", "tmuxName": "swept",
     "title": "Old conversation", "cwd": "/root/netplan",
     "resumeCommand": "cd '/root/netplan' && claude --resume 2222abcd-0000-4000-8000-00000000abcd",
     "archivedAt": 1, "endedAt": 2, "lastMessage": "",
     "transcriptBytes": 0, "transcriptTruncated": False,
     "revivedAt": None, "revivedAs": None, "live": False, "transcriptPresent": False}]}
class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        self._send(200, ROWS) if self.path == "/v1/archive" else self._send(404, {"error": "no"})
    def do_POST(self):
        if self.path.endswith("/revive"):
            self._send(201, {"ok": True, "name": "jtyper2", "resumed": True, "restoredTranscript": True})
        elif "attention" in self.path:
            self._send(409, {"error": "answer the waiting question first, then archive the session"})
        else:
            self._send(202, {"ok": True, "id": "x", "archived": False, "pending": True, "queued": True})
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
ARSTUB
AR_STUB=$!; STUB_PIDS+=("$AR_STUB")
AR_PORT=$(stub_port "$AR_PF")
if [ -z "${AR_PORT:-}" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below fail for the wrong reason.
  skip "archive renderer checks (the stub never bound a port)"
else
  AR_LIST=$(HUGINN_APPD_URL="http://127.0.0.1:$AR_PORT" server/bin/huginn-archive 2>&1)
  grep -q "claude --resume 0123abcd" <<<"$AR_LIST" \
    && ok "the list prints the resume command, which is the whole point of the row" \
    || bad "archive list had no resume command: $AR_LIST"
  # ⚠ THE ONE THING A ROW MUST NOT BE QUIET ABOUT. Claude Code deletes its own
  # transcripts after cleanupPeriodDays, and a revive past that comes back with
  # amnesia — so a row with nothing kept has to say so before somebody tries.
  grep -q "TRANSCRIPT GONE" <<<"$AR_LIST" \
    && ok "a row whose transcript is gone says so instead of offering a hollow revive" \
    || bad "archive list was silent about a missing transcript: $AR_LIST"
  # THE REFUSAL, VERBATIM. This is why the call is made on the host at all.
  AR_409=$(HUGINN_APPD_URL="http://127.0.0.1:$AR_PORT" server/bin/huginn-archive attention 2>&1)
  grep -q "answer the waiting question first" <<<"$AR_409" \
    && ok "a refused archive repeats the daemon's sentence, not a client guess" \
    || bad "archive refusal was rewritten client-side: $AR_409"
  AR_REV=$(HUGINN_APPD_URL="http://127.0.0.1:$AR_PORT" server/bin/huginn-archive revive jtyper 2>&1)
  grep -q "revived as jtyper2" <<<"$AR_REV" \
    && ok "revive resolves a NAME to an id host-side and reports the name it got" \
    || bad "revive by name did not work: $AR_REV"
  # A name nobody archived must not become a POST at all.
  AR_MISS=$(HUGINN_APPD_URL="http://127.0.0.1:$AR_PORT" server/bin/huginn-archive revive nosuchname 2>&1)
  grep -q "nothing archived under" <<<"$AR_MISS" \
    && ok "reviving a name that was never archived says so" || bad "revive of an unknown name: $AR_MISS"
fi
AR_DEAD=$(HUGINN_APPD_URL="http://127.0.0.1:1" server/bin/huginn-archive 2>&1); AR_RC=$?
[ "$AR_RC" = 2 ] \
  && ok "archive exits 2 when nothing is answering (1 = appd is there but cannot serve it)" \
  || bad "archive with no daemon exited $AR_RC: $AR_DEAD"
# ⚠ AND IT NEVER PRINTS THE TOKEN, the same failure headroom was audited for: the
# bearer is one variable away from every string these paths emit.
if [ -r /etc/huginn-appd/token ]; then
  AR_TOK=$(tr -d '[:space:]' < /etc/huginn-appd/token)
  if [ -n "$AR_TOK" ] && grep -qF "$AR_TOK" <<<"${AR_LIST:-}${AR_DEAD}${AR_409:-}"; then
    bad "huginn-archive printed the bearer token on a failure path"
  else
    ok "huginn-archive failure paths print no credential"
  fi
else
  skip "archive token-leak check (no readable /etc/huginn-appd/token here)"
fi
kill "$AR_STUB" 2>/dev/null

# The projects lane. Same rule as headroom/archive above -- a verb promised in
# two shells must have a renderer on the host and an install line for it -- plus
# the one thing that makes this renderer different from all of them: it is the
# ONLY implementation of what a project looks like in either shell, and it also
# WRITES (new / spawn / msg / end). A parse check would leave every write path
# untested, so it is driven end to end against a stub daemon like the archive
# lane below it.
#
# It is node, not bash-around-python3: the bodies it POSTs are JSON objects built
# from argv, and building JSON in a single-quoted `python3 -c` inside bash is the
# quoting trap huginn-rounds already carries a warning about. `node --check` is
# therefore the real syntax gate here.
node --check server/bin/huginn-projects 2>/dev/null \
  && ok "huginn-projects parses" || bad "huginn-projects does not parse"
[ -x server/bin/huginn-projects ] && ok "huginn-projects is executable" \
  || bad "huginn-projects is not executable — ssh would refuse to run it"
# The sh side writes its case as an alternation (`projects|project)`), so match
# the verb as a case ALTERNATIVE the way [3/8] does -- a bare `^    projects)`
# here would go red for a spelling that is correct.
grep -qE '^[[:space:]]+([a-z]+\|)*projects(\||\))' client/huginn.sh && grep -q "eq 'projects'" client/huginn.ps1 \
  && ok "both shells carry: projects" || bad "the projects verb is missing from a shell client"
grep -q 'install_script .*bin/huginn-projects' server/setup.sh \
  && ok "setup.sh installs huginn-projects" \
  || bad "setup.sh does not install huginn-projects — the verb would 'command not found'"
# `--help` is the one path that must work with no daemon, no token and no
# network: it is what somebody types when the verb did something they did not
# expect. It must exit 0 (a help screen is not an error) and name every verb,
# because the grammar lives nowhere else -- the clients forward argv untouched.
PJ_HELP=$(server/bin/huginn-projects --help 2>&1); PJ_RC=$?
PJ_MISS=
for v in list show new spawn msg end; do
  grep -qE "^  huginn-projects $v" <<<"$PJ_HELP" || PJ_MISS="$PJ_MISS $v"
done
[ "$PJ_RC" = 0 ] && [ -z "$PJ_MISS" ] \
  && ok "huginn-projects --help exits 0 and documents every verb" \
  || bad "huginn-projects --help exited $PJ_RC, missing verbs:${PJ_MISS:- none}"

# What the two clients actually SEND. The whole verb is one ssh to the renderer,
# so a client that drops an argument (an empty argv element from a bare verb, a
# flag eaten by PowerShell's parameter binder, a lost array) looks exactly like a
# working verb from the caller's side.
PJT=$(mktemp -d)
cat > "$PJT/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SSH_LOG"
exit 0
STUB
chmod +x "$PJT/ssh"
PJSH=$( export SSH_LOG="$PJT/log"; : > "$SSH_LOG"
        ( export PATH="$PJT:$PATH"
          . "$PWD/client/huginn.sh" >/dev/null 2>&1
          huginn projects; huginn projects show lora; huginn projects --json ) >/dev/null 2>&1
        cat "$SSH_LOG" )
grep -q -- '-T .* huginn-projects$' <<<"$PJSH" \
  && ok "sh: bare projects ssh -T's the renderer with NO empty argument" \
  || bad "sh: bare projects sent: $PJSH"
grep -q -- 'huginn-projects show lora' <<<"$PJSH" && grep -q -- 'huginn-projects --json' <<<"$PJSH" \
  && ok "sh: projects carries its sub-verb and --json" || bad "sh: projects sent: $PJSH"
if command -v pwsh >/dev/null 2>&1; then
  PJPS=$( export SSH_LOG="$PJT/log2"; : > "$SSH_LOG"
          PATH="$PJT:$PATH" pwsh -NoProfile -Command \
            ". $PWD/client/huginn.ps1; huginn projects; huginn projects show lora; huginn projects --json" >/dev/null 2>&1
          cat "$SSH_LOG" )
  grep -q -- '-T .* huginn-projects$' <<<"$PJPS" \
    && ok "ps1: bare projects sends the same bare renderer call" || bad "ps1: bare projects sent: $PJPS"
  grep -q -- "huginn-projects 'show' 'lora'" <<<"$PJPS" && grep -q -- "huginn-projects '--json'" <<<"$PJPS" \
    && ok "ps1: the sub-verb and --json survive the parameter binder" || bad "ps1: projects sent: $PJPS"
else
  skip "ps1 projects send check (no pwsh)"
fi
rm -rf "$PJT"

# End to end against a stub daemon. Port 0 like every other stub here (see the
# note by stub_port): these gates run beside a live appd and beside each other,
# so no fixed number is ever provably free.
PJ_PF="$STUB_DIR/projects.port"
PJ_404_PF="$STUB_DIR/projects404.port"
PJ_REQ=$(mktemp)
python3 - "$PJ_PF" "${HUGINN_TEST_PROJECTS_PORT:-0}" "$PJ_REQ" <<'PJSTUB' &
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
LOG = sys.argv[3]
# ⚠ THE MANIFEST IS PART OF THE FIXTURE NOW, because `spawn` is an APPROVAL:
# the renderer reads the project, takes the rev the proposal is at, and posts
# {approve:true, manifestRev}. A fixture with no manifest could only test the
# refusal path.
P1 = {"id": "aaaaaaaa-0000-4000-8000-00000000aaaa", "name": "LoRa sensor stick",
      "cwd": "/root/netplan/dev-ledger/lora-stick", "createdAt": 1789459900,
      "status": "proposed",
      "manifest": {"rev": 2, "spawnedRev": 0, "summary": "docs and repo",
                   "sessions": [{"role": "docs", "firstPrompt": "write the README"},
                                {"role": "repo", "firstPrompt": "tidy the tree"}]},
      "lead": {"name": "lora-stick/lead", "state": "busy"},
      "members": [{"name": "lora-stick/docs", "role": "docs", "state": "idle"},
                  {"name": "lora-stick/repo", "role": "repo", "state": "attention",
                   "needsYou": True}]}
# Not proposed: the project somebody types `spawn` at before the lead has
# written a plan, which is the commonest way that verb is reached too early.
P2 = {"id": "bbbbbbbb-0000-4000-8000-00000000bbbb", "name": "status page",
      "cwd": "/root/netplan/status-page", "createdAt": 1789000000, "endedAt": 1789400000,
      "status": "drafting", "manifest": {"rev": 0, "sessions": []},
      "lead": {"name": "status-page/lead"}, "members": []}
# The one whose spawn refuses HUNDREDS of members — see the flood assertion.
P3 = {"id": "cccccccc-0000-4000-8000-00000000cccc", "name": "flood cluster",
      "cwd": "/root/netplan", "createdAt": 1789459000, "status": "proposed",
      "manifest": {"rev": 1, "spawnedRev": 0, "summary": "many",
                   "sessions": [{"role": "flood", "firstPrompt": "x"}]},
      "lead": {"name": "flood-cluster/lead"}, "members": []}
BY_ID = {P1["id"]: P1, P2["id"]: P2, P3["id"]: P3}
DASH = {"project": P1, "updatedAt": 1789460500,
        "members": [{"name": "lora-stick/lead", "role": "lead", "state": "busy", "turns": 12},
                    {"name": "lora-stick/docs", "role": "docs", "state": "idle", "turns": 3},
                    {"name": "lora-stick/repo", "role": "repo", "state": "attention",
                     "needsYou": True, "turns": 7}]}
class H(BaseHTTPRequestHandler):
    def _log(self, body=b""):
        # The BODY is logged too: what the client SENDS is the whole of H1/H2,
        # and a request log that only carried the path could not tell a spawn
        # that approves from one that posts a member list.
        with open(LOG, "a") as fh:
            fh.write("%s %s %s\n" % (self.command, self.path, body.decode("utf-8", "replace")))
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        self._log()
        if self.path == "/v1/projects":
            self._send(200, {"projects": [P1, P2, P3]})
        elif self.path.endswith("/dashboard"):
            self._send(200, DASH)
        elif self.path.startswith("/v1/projects/"):
            # The detail route is an ENVELOPE — {project, row, live} — and the
            # record inside it is what carries the manifest.
            rec = BY_ID.get(self.path.rsplit("/", 1)[-1])
            self._send(200, {"project": rec, "row": rec, "live": []}) if rec \
                else self._send(404, {"error": "no such project"})
        else:
            self._send(404, {"error": "no"})
    def do_POST(self):
        n = int(self.headers.get("content-length") or 0)
        sent = self.rfile.read(n) if n else b""
        self._log(sent)
        if self.path.startswith("/v1/projects/" + P3["id"]) and self.path.endswith("/spawn"):
            # A spawn that refused HUNDREDS of members: >64 KB of rendered output
            # on a code path that ends in a non-zero exit. See the assertion.
            self._send(200, {"ok": False, "spawned": [],
                             "failed": [{"role": "r%03d" % i,
                                         "reason": "refused because " + ("x" * 100)}
                                        for i in range(800)] +
                                       [{"role": "last", "reason": "THE-LAST-LINE"}]})
            return
        if self.path.endswith("/spawn"):
            # A PARTIAL spawn on purpose: HTTP 200 with one member started and
            # one refused is the daemon's documented normal failure, and the
            # renderer has to survive it in both directions (say which is
            # missing, and not report success).
            self._send(200, {"ok": False, "spawned": [{"role": "docs", "name": "lora-stick/docs"}],
                             "failed": [{"role": "repo",
                                         "reason": "a tmux session called lora-stick-repo already exists"}]})
        elif self.path.endswith("/message"):
            self._send(202, {"ok": True})
        else:
            self._send(201, P1)
    def do_DELETE(self):
        self._log()
        # `refused` is what the daemon added for a member whose dialog was in
        # the way: it is still running, with no project behind it.
        self._send(200, {"ok": True, "mode": "graceful", "ended": ["lora-stick-lead", "lora-stick-docs"],
                         "refused": [{"name": "lora-stick-repo", "claudeName": "lora-stick/repo",
                                      "why": "it is sitting on a permission dialog"}]})
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
PJSTUB
PJ_STUB=$!; STUB_PIDS+=("$PJ_STUB")
python3 - "$PJ_404_PF" "${HUGINN_TEST_PROJECTS_404_PORT:-0}" <<'PJ404' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404); self.end_headers(); self.wfile.write(b'{"error":"not found"}')
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
PJ404
PJ_404_STUB=$!; STUB_PIDS+=("$PJ_404_STUB")
# A third stub: HTTP 200 with a body that is not JSON. See the assertion below.
PJ_JUNK_PF="$STUB_DIR/projectsjunk.port"
python3 - "$PJ_JUNK_PF" "${HUGINN_TEST_PROJECTS_JUNK_PORT:-0}" <<'PJJUNK' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers()
        self.wfile.write(b'<html>502 Bad Gateway</html>')
    def log_message(self, *a): pass
srv = HTTPServer(("127.0.0.1", int(sys.argv[2])), H)
with open(sys.argv[1], "w") as fh: fh.write(str(srv.server_port))
srv.serve_forever()
PJJUNK
PJ_JUNK_STUB=$!; STUB_PIDS+=("$PJ_JUNK_STUB")
PJ_PORT=$(stub_port "$PJ_PF")
PJ_404_PORT=$(stub_port "$PJ_404_PF")
PJ_JUNK_PORT=$(stub_port "$PJ_JUNK_PF")
if [ -z "${PJ_PORT:-}" ] || [ -z "${PJ_404_PORT:-}" ] || [ -z "${PJ_JUNK_PORT:-}" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below fail for the wrong reason.
  skip "projects renderer checks (a stub never bound a port)"
else
  PJ_LIST=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" server/bin/huginn-projects 2>&1)
  grep -q "LoRa sensor stick" <<<"$PJ_LIST" && grep -qE "2 members" <<<"$PJ_LIST" \
    && ok "the list names each project and counts its members" \
    || bad "projects list did not render the fixture: $PJ_LIST"
  # ⚠ THE ONE FACT THE LIST EXISTS FOR. A cluster of twelve sessions is unreadable
  # unless the line says which of them is stopped waiting for a person; a count
  # that silently drops `needsYou` looks identical to a cluster that is fine.
  # ON THE PROJECT'S OWN LINE, not just in the footer total: a renderer that
  # dropped the row's count still printed "1 needs you" at the bottom, and the
  # assertion passed while the line somebody actually reads had lost it.
  grep -q "LoRa sensor stick.*1 needs you" <<<"$PJ_LIST" \
    && ok "a member waiting on a person is counted on the project's line" \
    || bad "projects list hid a waiting member: $PJ_LIST"
  # The lead's own state, on the same line: a lead that died is why nothing is
  # being proposed, and it is not a member so the member counts never show it.
  grep -qE "lead (busy|working)" <<<"$PJ_LIST" \
    && ok "the lead's state rides the project line" || bad "no lead state in: $PJ_LIST"
  PJ_SHOW=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" server/bin/huginn-projects show "LoRa sensor stick" 2>&1)
  grep -q "docs" <<<"$PJ_SHOW" && grep -q "repo" <<<"$PJ_SHOW" && grep -q "NEEDS YOU" <<<"$PJ_SHOW" \
    && ok "show renders the member table with the waiting member marked" \
    || bad "projects show: $PJ_SHOW"
  # ⚠ THE ATTACH NAME IS NOT THE PEER NAME. A member is `<slug>/<role>` to its
  # peers and `<slug>-<role>` to tmux, because a slash is not a tmux name
  # character. A hint that printed the peer name would not attach anything --
  # `huginn lora-stick/repo` takes the first path segment for a session name and
  # CREATES a new session beside the project, which is the worst possible answer
  # to "how do I look at this one".
  grep -q "huginn lora-stick-lead" <<<"$PJ_SHOW" && ! grep -q "huginn lora-stick/" <<<"$PJ_SHOW" \
    && ok "show suggests the tmux session name, not the peer name, for attaching" \
    || bad "show's attach hint: $(grep -F 'to attach' <<<"$PJ_SHOW")"
  # A NAME is resolved host-side, exactly like `huginn-archive revive <name>`,
  # so neither client has to parse the list in bash AND in PowerShell.
  PJ_MISS2=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" server/bin/huginn-projects show nosuchproject 2>&1)
  grep -q "no project" <<<"$PJ_MISS2" \
    && ok "show of an unknown project says so instead of 404-ing" || bad "show unknown: $PJ_MISS2"
  # ⚠⚠ WHAT `new` SENDS IS THE WHOLE VERB. The daemon requires `kind` (one of
  # projectsLib.KINDS) and a non-empty `brief`, and this renderer sent NEITHER —
  # so every create from a terminal came back "kind is one of software, infra,
  # …" and the only way to make a project was a phone or a desktop, while the
  # gate stayed green because nothing here ever created one.
  : > "$PJ_REQ"
  PJ_NEW=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
           server/bin/huginn-projects new "Gate cluster" --brief 'size the gate work' 2>&1); PJ_RC=$?
  PJ_NEW_BODY=$(grep -F 'POST /v1/projects ' "$PJ_REQ" | head -1)
  [ "$PJ_RC" = 0 ] && grep -q '"kind":"other"' <<<"$PJ_NEW_BODY" \
    && grep -q '"brief":"size the gate work"' <<<"$PJ_NEW_BODY" \
    && ok "new sends the kind (default other) and the brief the daemon requires" \
    || bad "new sent: ${PJ_NEW_BODY:-nothing} (exit $PJ_RC) / printed: $PJ_NEW"
  : > "$PJ_REQ"
  PJ_KIND=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
            server/bin/huginn-projects new "Gate cluster" --kind hardware --brief - <<<'from stdin' 2>&1)
  grep -q '"kind":"hardware"' "$PJ_REQ" && grep -q 'from stdin' "$PJ_REQ" \
    && ok "--kind travels, and --brief - reads the brief from stdin" \
    || bad "new --kind/--brief - sent: $(cat "$PJ_REQ") / printed: $PJ_KIND"
  # ⚠ AND A BRIEF IS NOT OPTIONAL, because it is the whole first message the
  # lead gets. Refused HERE so the message can name the flag that answers it —
  # and nothing may reach the wire.
  : > "$PJ_REQ"
  PJ_NOBRIEF=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
               server/bin/huginn-projects new "No brief" 2>&1); PJ_RC=$?
  [ "$PJ_RC" != 0 ] && grep -q -- "--brief" <<<"$PJ_NOBRIEF" && ! grep -q "POST" "$PJ_REQ" \
    && ok "new without a brief is refused before anything is sent" \
    || bad "new with no brief exited $PJ_RC: $PJ_NOBRIEF / sent: $(cat "$PJ_REQ")"
  # ⚠⚠ AND SPAWN IS AN APPROVAL. The daemon reads `{approve:true, manifestRev}`
  # and NOTHING else — the members are the lead's plan, at the rev the owner
  # saw. This renderer used to post `{members}` with no approval, so the verb
  # answered "approve must be true — spawning is the owner's decision" 100% of
  # the time; the old gate asserted the member:role GRAMMAR and never the body.
  : > "$PJ_REQ"
  PJ_SPAWN=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
             server/bin/huginn-projects spawn "LoRa sensor stick" 2>&1); PJ_RC=$?
  PJ_SPAWN_BODY=$(grep -F "POST /v1/projects/aaaaaaaa-0000-4000-8000-00000000aaaa/spawn" "$PJ_REQ" | head -1)
  grep -q '"approve":true' <<<"$PJ_SPAWN_BODY" && grep -q '"manifestRev":2' <<<"$PJ_SPAWN_BODY" \
    && ok "spawn approves the proposal at the rev the project is actually on" \
    || bad "spawn sent: ${PJ_SPAWN_BODY:-nothing} / printed: $PJ_SPAWN"
  grep -q '"members"' <<<"$PJ_SPAWN_BODY" \
    && bad "spawn still sends a member list the daemon does not read" \
    || ok "and it sends no member list of its own"
  # A member list typed at it is refused in the renderer's own words, because
  # it was documented for two releases and it is what a finger types.
  : > "$PJ_REQ"
  PJ_PAIR=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
            server/bin/huginn-projects spawn "LoRa sensor stick" docs:docs --prompt 'x' 2>&1); PJ_RC=$?
  [ "$PJ_RC" != 0 ] && grep -qi "no members" <<<"$PJ_PAIR" && ! grep -q "POST" "$PJ_REQ" \
    && ok "the old member:role spelling is refused, and sends nothing at all" \
    || bad "spawn with a pair exited $PJ_RC: $PJ_PAIR / sent: $(cat "$PJ_REQ")"
  # ⚠ AND A PROJECT WITH NO PROPOSAL IS NOT A SPAWN. "this project is drafting,
  # not proposed" is true and has no next step in it; the next step is the lead.
  : > "$PJ_REQ"
  PJ_NOTPROP=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
               server/bin/huginn-projects spawn "status page" 2>&1); PJ_RC=$?
  [ "$PJ_RC" != 0 ] && grep -q "no proposal to approve" <<<"$PJ_NOTPROP" && ! grep -q "POST" "$PJ_REQ" \
    && ok "spawning a project the lead has not proposed yet says so, and posts nothing" \
    || bad "spawn of a drafting project exited $PJ_RC: $PJ_NOTPROP / sent: $(cat "$PJ_REQ")"
  PJ_SPAWN=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
             server/bin/huginn-projects spawn "LoRa sensor stick" 2>&1); PJ_RC=$?
  # ⚠ HTTP 200 IS NOT "IT WORKED". The daemon reports a member that could not
  # start inside a 200 body; a renderer that passed that through as success
  # hands a script a half-born cluster and tells it the cluster is up.
  grep -q "lora-stick-repo already exists" <<<"$PJ_SPAWN" && [ "$PJ_RC" != 0 ] \
    && ok "a partial spawn names the member that did not start, and exits non-zero" \
    || bad "partial spawn exited $PJ_RC: $PJ_SPAWN"
  # ⚠ A NON-ZERO EXIT MUST NOT EAT THE OUTPUT. Node's stdout is ASYNCHRONOUS
  # when it is a pipe on POSIX -- and a pipe is the normal case here, because both
  # clients run this renderer as `ssh -T <host> huginn-projects` and capture or
  # page what comes back -- so a buffered write followed by process.exit() is
  # DISCARDED. A short reply hides it completely; this drives the exiting path
  # with >64 KB (a spawn that refused 800 members) through a pipe and asserts the
  # LAST line survived. Only the tail is kept, so a failure does not dump 90 KB.
  PJ_FLOOD=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
             server/bin/huginn-projects spawn "flood cluster" 2>&1 | cat | tail -3)
  grep -q "THE-LAST-LINE" <<<"$PJ_FLOOD" \
    && ok "a long reply on the non-zero-exit path survives the pipe" \
    || bad "output was truncated by the exit; tail was: $PJ_FLOOD"
  # ⚠⚠ A REMEDY THAT CANNOT BE RUN IS WORSE THAN NONE. `end` used to delete the
  # record and print "end them with: huginn projects end <name> --now" — and
  # `resolve()` reads the LIST, which no longer holds the project it has just
  # deleted, so that command answered "no project called …" every single time,
  # for the name AND for the id. Meanwhile the lead, a real claude, was left
  # running with no project behind it. The default now winds the sessions down
  # through the daemon's own `?end=1`, and anything still running is named with
  # a command that resolves: a tmux name.
  : > "$PJ_REQ"
  PJ_END=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
           server/bin/huginn-projects end "LoRa sensor stick" 2>&1); PJ_RC=$?
  grep -q "DELETE /v1/projects/aaaaaaaa-0000-4000-8000-00000000aaaa?end=1" "$PJ_REQ" \
    && ok "end winds the sessions down by default (the daemon's ?end=1)" \
    || bad "end sent: $(cat "$PJ_REQ")"
  grep -q "huginn projects end" <<<"$PJ_END" \
    && bad "end still prints a remedy that cannot resolve the project it deleted" \
    || ok "and it no longer names a command that cannot resolve"
  grep -q "lora-stick-repo" <<<"$PJ_END" && grep -q "permission dialog" <<<"$PJ_END" \
    && grep -q "huginn kill lora-stick-repo" <<<"$PJ_END" && [ "$PJ_RC" != 0 ] \
    && ok "a member that could not be wound down is named, with a command that works, and exits non-zero" \
    || bad "end (exit $PJ_RC) printed: $PJ_END"
  : > "$PJ_REQ"
  PJ_ENDNOW=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
              server/bin/huginn-projects end "LoRa sensor stick" --now 2>&1)
  grep -q "?end=now" "$PJ_REQ" \
    && ok "--now asks for the outright end, not the graceful one it used to send" \
    || bad "end --now sent: $(cat "$PJ_REQ") / printed: $PJ_ENDNOW"
  : > "$PJ_REQ"
  PJ_KEEP=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
            server/bin/huginn-projects end "LoRa sensor stick" --keep-sessions 2>&1)
  grep -qE "DELETE /v1/projects/[0-9a-f-]+ " "$PJ_REQ" && ! grep -q "end=" "$PJ_REQ" \
    && grep -q "lora-stick-lead" <<<"$PJ_KEEP" \
    && ok "--keep-sessions ends nothing and NAMES the sessions it left running" \
    || bad "end --keep-sessions sent: $(cat "$PJ_REQ") / printed: $PJ_KEEP"
fi
# ⚠ AN OLDER DAEMON IS NOT A BROKEN ONE. /v1/projects does not exist before the
# Wave 3 appd, and "404" on its own sends somebody looking for a bug in the
# renderer. One line, naming the daemon, and its own exit code so a script can
# branch on it.
PJ_OLD=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_404_PORT" server/bin/huginn-projects 2>&1); PJ_RC=$?
[ "$PJ_RC" = 3 ] && grep -qi "huginn-appd" <<<"$PJ_OLD" \
  && ok "projects on a daemon without the feature exits 3 and says which daemon" \
  || bad "projects on a 404 exited $PJ_RC: $PJ_OLD"
# ⚠ AND A 200 THAT IS NOT JSON MUST NOT DRAW THE EMPTY STATE. A truncated body
# or a proxy error page arrives as a SUCCESSFUL status with junk in it, and the
# renderer would otherwise print "No projects." — a sentence somebody acts on,
# and a lie. Worse than an error, because it looks like an answer.
PJ_JUNK=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_JUNK_PORT" server/bin/huginn-projects 2>&1); PJ_RC=$?
[ "$PJ_RC" != 0 ] && ! grep -q "No projects" <<<"$PJ_JUNK" \
  && ok "a 200 that is not JSON is an error, not an empty list" \
  || bad "projects on a non-JSON 200 exited $PJ_RC: $PJ_JUNK"
PJ_DEAD=$(HUGINN_APPD_URL="http://127.0.0.1:1" server/bin/huginn-projects 2>&1); PJ_RC=$?
[ "$PJ_RC" = 2 ] \
  && ok "projects exits 2 when nothing is answering (3 = appd is there but too old)" \
  || bad "projects with no daemon exited $PJ_RC: $PJ_DEAD"
# ⚠ AND IT NEVER PRINTS THE TOKEN, the same failure headroom and archive were
# audited for: the bearer is one variable away from every string these paths emit.
if [ -r /etc/huginn-appd/token ]; then
  PJ_TOK=$(tr -d '[:space:]' < /etc/huginn-appd/token)
  if [ -n "$PJ_TOK" ] && grep -qF "$PJ_TOK" <<<"${PJ_LIST:-}${PJ_DEAD}${PJ_OLD}${PJ_JUNK}${PJ_BAD:-}"; then
    bad "huginn-projects printed the bearer token on a failure path"
  else
    ok "huginn-projects failure paths print no credential"
  fi
else
  skip "projects token-leak check (no readable /etc/huginn-appd/token here)"
fi
kill "$PJ_STUB" "$PJ_404_STUB" "$PJ_JUNK_STUB" 2>/dev/null
rm -f "$PJ_REQ"

echo "[projects-live/8] create a project, approve a spawn, and end it — against a REAL daemon"
# ⚠⚠ WHY A REAL DAEMON AND NOT ANOTHER STUB. The two HIGHs of the 2026-09-19
# review were both "the renderer sends a body the daemon does not accept":
# `new` sent neither `kind` nor `brief` and `spawn` sent neither `approve` nor
# `manifestRev`, so BOTH verbs failed 100% of the time — and every check above
# stayed green, because a stub answers whatever it is written to answer. A stub
# can only ever assert what this file already believes. So the write half of the
# grammar is driven end to end against `server/appd/huginn-appd.js` out of THIS
# TREE, which is the only thing that knows what the routes really require.
#
# ⚠ AND THE DAEMON IS BARE. Its own scratch HOME, DATA, STATE_DIR and CLAUDE_DIR
# (so there is no .credentials.json to read and no account to poll), no telegram
# script, an FCM key that does not exist, and a PRIVATE tmux socket — never the
# operator's. `claude` is shadowed by a stand-in, exactly like the appd suite's
# own project tests, so nothing spawned here costs a token or touches the real
# ~/.claude. It is killed in the EXIT trap with every other stub.
PJL=$(mktemp -d); STUB_DIRS+=("$PJL")
PJL_SOCK="huginn-cligate-$$"
STUB_TMUX+=("$PJL_SOCK")
if ! command -v tmux >/dev/null 2>&1 || [ ! -f server/appd/huginn-appd.js ]; then
  skip "projects lifecycle (no tmux, or no daemon in this tree)"
elif [ ! -r /etc/huginn-appd/token ]; then
  # `huginn-projects` reads /etc/huginn-appd/token with no override (by design:
  # the renderer runs ON the host, which is where the token lives), so a private
  # daemon has to be given the SAME token to be talked to at all. Unreadable
  # here means this check cannot run — loudly, never silently.
  skip "projects lifecycle (no readable /etc/huginn-appd/token to match)"
else
  mkdir -p "$PJL/data" "$PJL/state" "$PJL/home" "$PJL/claude" "$PJL/work" "$PJL/bin"
  cp /etc/huginn-appd/token "$PJL/token"; chmod 600 "$PJL/token"
  # The stand-in for `claude`. The launcher runs `claude --name … ; exec "$SHELL" -l`,
  # so a stand-in earlier on the daemon's PATH is the only way to intercept it.
  # It answers --version (so nothing that probes the binary hangs) and otherwise
  # just holds the pane open.
  cat > "$PJL/bin/claude" <<'FAKECLAUDE'
#!/usr/bin/env bash
case " $* " in *" --version "*) echo "0.0.0-gate (stand-in)"; exit 0 ;; esac
exec sleep 600
FAKECLAUDE
  chmod +x "$PJL/bin/claude"
  # The kernel picks the port, the same rule every stub here follows — except
  # that appd takes a number rather than binding 0, so it is probed and handed
  # over. A daemon that then fails to bind simply never answers /v1/ping and the
  # lane SKIPS instead of asserting against somebody else's listener.
  PJL_PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
  # ⚠ NOT `env -i`. A tmux with NO ENVIRONMENT sanitises its own format output:
  # `list-sessions -F '#{session_name}\t…'` comes back with every TAB rewritten
  # as `_`, so the daemon parses one field, decides every session name is
  # unaddressable, and then `sessionExists()` is false for sessions that plainly
  # exist — a project whose end ends nothing, with nothing in the log that says
  # why. The environment is inherited and overridden instead, exactly as the
  # daemon's own test suite does it: what makes this daemon BARE is HOME,
  # CLAUDE_DIR, DATA and STATE_DIR pointing at scratch, not an empty environ.
  env PATH="$PJL/bin:$PATH" \
      HOME="$PJL/home" \
      HUGINN_APPD_PORT="$PJL_PORT" \
      HUGINN_APPD_BIND=127.0.0.1 \
      HUGINN_APPD_DATA="$PJL/data" \
      HUGINN_APPD_TOKEN_FILE="$PJL/token" \
      HUGINN_APPD_STATE_DIR="$PJL/state" \
      HUGINN_APPD_CLAUDE_DIR="$PJL/claude" \
      HUGINN_APPD_TMUX_SOCKET="$PJL_SOCK" \
      HUGINN_APPD_WORKDIR="$PJL/work" \
      HUGINN_APPD_TELEGRAM_SCRIPT= \
      HUGINN_FCM_KEY="$PJL/nonexistent-fcm.json" \
      node server/appd/huginn-appd.js > "$PJL/daemon.log" 2>&1 &
  PJL_PID=$!; STUB_PIDS+=("$PJL_PID")
  PJL_URL="http://127.0.0.1:$PJL_PORT"
  PJL_UP=
  # ⚠ PROBED THROUGH THE RENDERER, not with a bare curl: this daemon wants the
  # bearer on every route including /v1/ping, and a curl carrying it would put
  # the token on a command line every user on the box can read in /proc. The
  # renderer reads the file itself, and a `list` that returns 0 also proves the
  # exact path the rest of this lane uses.
  for _ in $(seq 1 100); do
    HUGINN_APPD_URL="$PJL_URL" server/bin/huginn-projects list >/dev/null 2>&1 && { PJL_UP=1; break; }
    kill -0 "$PJL_PID" 2>/dev/null || break
    sleep 0.2
  done
  if [ -z "$PJL_UP" ]; then
    skip "projects lifecycle (the daemon did not come up: $(tail -2 "$PJL/daemon.log" | tr '\n' ' '))"
  else
    pj () { HUGINN_APPD_URL="$PJL_URL" server/bin/huginn-projects "$@" 2>&1; }
    PJL_NAME="Gate cluster $$"
    # ⚠ NO --cwd: the daemon auto-trusts its own WORKDIR, and every other
    # directory has to have been trusted in Claude Code first (the folder-trust
    # dialog blocks session registration entirely). Pointing this at a real
    # project directory would make the check depend on the operator's
    # ~/.claude.json.
    PJL_NEW=$(pj new "$PJL_NAME" --kind software --brief 'the gate made this; it will be ended in a moment'); PJL_RC=$?
    if [ "$PJL_RC" != 0 ]; then
      bad "creating a project against the real daemon failed (exit $PJL_RC): $PJL_NEW"
    else
      ok "new creates a project against the real daemon (kind + brief are accepted)"
    fi
    grep -q "its lead session is" <<<"$PJL_NEW" \
      && ok "and it names the lead session it launched" || bad "new printed: $PJL_NEW"
    tmux -L "$PJL_SOCK" ls -F '#S' 2>/dev/null | grep -q -- "-lead$" \
      && ok "the lead is a real tmux session on the private socket" \
      || bad "no lead session: $(tmux -L "$PJL_SOCK" ls 2>&1 | tr '\n' ' ')"
    # THE PROPOSAL, staged through the daemon's OWN editor route — the same
    # PATCH the desktop's manifest editor uses, re-validated by the same parser
    # the lead's block goes through. That is the setup; the thing under test is
    # the approval below.
    PJL_REV=$(HUGINN_TOKEN="$(cat "$PJL/token")" PJL_URL="$PJL_URL" python3 - "$PJL_NAME" <<'PROPOSE'
import json, os, sys, urllib.request
base, tok, want = os.environ["PJL_URL"], os.environ["HUGINN_TOKEN"], sys.argv[1]
def call(path, method="GET", body=None):
    req = urllib.request.Request(base + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read() or b"{}")
rows = call("/v1/projects")["projects"]
pid = [r for r in rows if r["name"] == want][0]["id"]
rec = call("/v1/projects/" + pid)["project"]
saved = call("/v1/projects/" + pid, "PATCH", {"rev": rec["rev"], "manifest": {
    "type": "software", "scope": "the gate", "summary": "one session: docs",
    "sessions": [{"role": "docs", "firstPrompt": "write one line and stop", "cwd": None}]}})
print("%s %s" % (pid, saved["manifest"]["rev"]))
PROPOSE
)
    PJL_ID=${PJL_REV%% *}; PJL_MREV=${PJL_REV##* }
    if [ -z "$PJL_ID" ] || [ "$PJL_ID" = "$PJL_REV" ]; then
      bad "could not stage a proposal on the real daemon: $PJL_REV"
    else
      ok "a proposal is on offer (manifest rev $PJL_MREV)"
      # ⚠⚠ THE CHECK H1/H2 EXISTED TO CATCH. This is the verb failing in the
      # field: the daemon requires {approve:true, manifestRev} and the renderer
      # sent {members}, so every spawn from a terminal answered "approve must be
      # true — spawning is the owner's decision".
      PJL_SPAWN=$(pj spawn "$PJL_NAME"); PJL_RC=$?
      [ "$PJL_RC" = 0 ] && grep -q "spawned in" <<<"$PJL_SPAWN" && grep -q "docs" <<<"$PJL_SPAWN" \
        && ok "spawn approves the proposal and the daemon creates the member" \
        || bad "spawn against the real daemon exited $PJL_RC: $PJL_SPAWN"
      tmux -L "$PJL_SOCK" ls -F '#S' 2>/dev/null | grep -q -- "-docs$" \
        && ok "and the member is a real tmux session too" \
        || bad "no member session: $(tmux -L "$PJL_SOCK" ls 2>&1 | tr '\n' ' ')"
    fi
    # ⚠ AND IT ENDS WHAT IT MADE. `--now` is the outright end; the record goes
    # and so do the sessions, which is exactly what the old default did NOT do.
    PJL_END=$(pj end "$PJL_NAME" --now); PJL_RC=$?
    [ "$PJL_RC" = 0 ] && grep -q "its sessions were ended" <<<"$PJL_END" \
      && ok "end --now takes the record AND the sessions with it" \
      || bad "end --now against the real daemon exited $PJL_RC: $PJL_END"
    PJL_LEFT=$(tmux -L "$PJL_SOCK" ls -F '#S' 2>/dev/null | tr '\n' ' ')
    [ -z "$PJL_LEFT" ] && ok "nothing of the cluster is left running" \
      || bad "these sessions outlived the project: $PJL_LEFT"
    # The bare-daemon claim, asserted rather than assumed: a gate that quietly
    # read the operator's credentials or spent quota would be a gate nobody
    # should run.
    grep -qiE "oauth|credential|ccusage|anthropic\.com" "$PJL/daemon.log" \
      && bad "the gate's daemon touched credentials or usage: $(grep -iE 'oauth|credential|ccusage|anthropic\.com' "$PJL/daemon.log" | head -2)" \
      || ok "the gate's daemon stayed bare (no credential, account or usage work in its log)"
  fi
  kill "$PJL_PID" 2>/dev/null
  tmux -L "$PJL_SOCK" kill-server 2>/dev/null
fi

echo "[uninstall/8] the server first, and only huginn's own files"
# WHY: `huginn uninstall` is the one verb that deletes a person's files, and the
# two ways it can be wrong are both silent. It can leave the tokens (the whole
# point of it), or it can take something that was never huginn's - a general SSH
# key, somebody else's line in .bashrc, another Host stanza. Driven against an
# UNREACHABLE host on purpose: an uninstaller does not get a second run, so that
# is the path that has to finish and still tell the truth.
UD=$(mktemp -d)
mkdir -p "$UD/.huginn" "$UD/.config/huginn" "$UD/.ssh"
cp client/huginn.sh "$UD/.huginn/huginn.sh"
cp client/huginn-device "$UD/.huginn/huginn-device"
printf '{"id":"33333333-3333-3333-3333-333333333333","url":"http://127.0.0.1:1","scope":"work","name":"gate"}\n' \
  > "$UD/.config/huginn/device.json"
printf 'sometoken\n' > "$UD/.config/huginn/appd-token"
printf '# mine\nexport EDITOR=vim\n[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh\nalias ll="ls -l"\n' > "$UD/.bashrc"
printf 'Host other\n  HostName 10.0.0.9\n\nHost huginn\n  HostName 10.0.0.1\n  IdentityFile %s/.ssh/id_ed25519\n\nHost last\n  HostName 10.0.0.8\n' "$UD" > "$UD/.ssh/config"
printf 'PRIVATE\n' > "$UD/.ssh/id_ed25519"
printf 'ssh-ed25519 AAAA me@laptop\n' > "$UD/.ssh/id_ed25519.pub"
UOUT=$(HOME="$UD" bash -c 'source "$HOME/.huginn/huginn.sh"; huginn uninstall --all --yes' 2>&1)

grep -q '33333333-3333-3333-3333-333333333333' <<<"$UOUT" \
  && ok "uninstall names the row it could not retire" \
  || bad "uninstall was silent about a stranded device row"
[ ! -e "$UD/.huginn" ] && [ ! -e "$UD/.config/huginn/appd-token" ] \
  && ok "uninstall leaves neither the client nor the token" \
  || bad "uninstall left huginn files behind"
if grep -q 'EDITOR=vim' "$UD/.bashrc" && grep -q 'ls -l' "$UD/.bashrc" \
   && ! grep -q 'huginn.sh' "$UD/.bashrc"; then
  ok "uninstall takes its profile line and nothing else"
else
  bad ".bashrc after uninstall: $(tr '\n' '|' < "$UD/.bashrc")"
fi
# ⚠ THE KEY. install.sh REUSES ~/.ssh/id_ed25519 when it is already there, so
# after the fact nothing can tell its key from the one somebody has used for
# five years - and deleting the wrong one locks them out of every host they have.
[ -f "$UD/.ssh/id_ed25519" ] \
  && ok "--all keeps a key that is not provably huginn's" \
  || bad "--all deleted a general-purpose SSH key"
if grep -q 'Host other' "$UD/.ssh/config" && grep -q 'Host last' "$UD/.ssh/config" \
   && ! grep -q 'Host huginn' "$UD/.ssh/config"; then
  ok "--all removes the huginn stanza and leaves the others"
else
  bad "ssh config after --all: $(tr '\n' '|' < "$UD/.ssh/config")"
fi
# The same key, renamed to something that says whose it is, IS removed - the
# other half of the rule, or the flag would just never do anything.
UD2=$(mktemp -d); mkdir -p "$UD2/.huginn" "$UD2/.ssh"
cp client/huginn.sh "$UD2/.huginn/huginn.sh"
printf 'Host huginn\n  HostName 10.0.0.1\n  IdentityFile %s/.ssh/id_ed25519_huginn\n' "$UD2" > "$UD2/.ssh/config"
printf 'PRIVATE\n' > "$UD2/.ssh/id_ed25519_huginn"
printf 'ssh-ed25519 AAAA me@laptop\n' > "$UD2/.ssh/id_ed25519_huginn.pub"
HOME="$UD2" bash -c 'source "$HOME/.huginn/huginn.sh"; huginn uninstall --all --yes' >/dev/null 2>&1
[ ! -e "$UD2/.ssh/id_ed25519_huginn" ] && [ ! -e "$UD2/.ssh/id_ed25519_huginn.pub" ] \
  && ok "--all removes a key whose NAME says it is huginn's" \
  || bad "--all kept a key that is provably huginn's"
rm -rf "$UD" "$UD2"

# The OTHER uninstaller for the same directory. `huginn uninstall` takes
# ~/.huginn whole because install.sh made it; the desktop's NSIS uninstaller
# SHARES that directory with a separately-installed base client, so it names its
# files one by one — and a hand-kept list drifts. huginn-llm-shim joined
# CliSync.candidates() in desktop 0.13.0 and the list, written in 0.14.0, went
# out without it: the app kept a file current and the uninstaller walked past it.
# So the list is asserted against its source, not read for plausibility.
NSI=mobile/app-desktop/packaging/huginn-desktop-kt.nsi
CLISYNC=mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/CliSync.kt
if [ -f "$NSI" ] && [ -f "$CLISYNC" ]; then
  # Only the satellites: the base client (huginn.sh / huginn.ps1) is
  # install.sh's and is deliberately NOT in the uninstaller's list.
  NSI_MISS=
  for f in $(grep -oE '^        add\("huginn-[a-z-]+"\)' "$CLISYNC" | sed 's/.*"\(.*\)".*/\1/'); do
    for s in "" .bak .tmp.js .appsync.tmp.js; do
      grep -qF "Delete \"\$PROFILE\\.huginn\\$f$s\"" "$NSI" || NSI_MISS="$NSI_MISS $f$s"
    done
  done
  [ -z "$NSI_MISS" ] \
    && ok "the desktop uninstaller names every CliSync satellite in ~/.huginn" \
    || bad "the desktop uninstaller would leave behind:$NSI_MISS"
  # The other half of the rule, and the reason the list exists at all: a
  # wildcard or an RMDir /r here would take a base client this never installed.
  grep -q 'RMDir "\$PROFILE\\.huginn"' "$NSI" \
    && ok "and takes ~/.huginn itself only when it is empty" \
    || bad "the desktop uninstaller no longer removes ~/.huginn with a plain RMDir"
else
  skip "desktop uninstaller file list (no $NSI in this tree)"
fi

echo "[8/8] what is actually DEPLOYED on this host, vs what is in the tree"
# ⚠ WHY THIS EXISTS. On 2026-08-25 the live headless device (brokkr) was found
# running the PRE-SECURITY-FIX runner — `allows()` failing open on an unknown
# mode, and `--resume` fed unvalidated into argv — while reporting version
# 0.10.1, the same number as the fixed code. The fixes were committed, released
# and tested; they had simply never been copied to /usr/local/share/huginn-cli,
# which is what devices install FROM. A version string is not evidence of
# content, and nothing compared the two.
#
# Same shape as server/bin: /usr/local/bin/huginn-{rounds,devices} are COPIES,
# not symlinks, and `huginn-sync` does not carry them, so they drift silently.
#
# Skips LOUDLY off this host rather than failing on somebody else's machine.
DRIFT=0
# The version a file CLAIMS, from whichever of the three spellings it uses. Empty
# for the server-side renderers, which carry none.
ver_of () {
  grep -m1 -oE "^(HUGINN_VERSION=|\\\$script:HUGINN_VERSION = |const VERSION = )'[0-9]+\.[0-9]+\.[0-9]+'" "$1" \
    2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+'
}
check_deployed () {   # $1 = repo path, $2 = installed path
  if [ ! -f "$2" ]; then skip "not installed here: $2"; return; fi
  # Compared with line endings stripped: `.gitattributes` keeps `*.ps1` CRLF in
  # the tree while `huginn-sync` lands LF on the host, so a byte compare called
  # huginn.ps1 stale on every gate run with zero content difference (cli 1.3.0).
  if cmp -s <(tr -d '\r' < "$1") <(tr -d '\r' < "$2"); then ok "deployed matches tree: $2"; return; fi
  # ⚠ THE TWO WAYS THESE DIFFER ARE NOT THE SAME BUG. The 2026-08-25 one was same
  # VERSION, different CONTENT: a file that lies about what it holds, and nothing
  # but this comparison can catch it. A tree that is simply AHEAD of the deployed
  # copy is a release that has not shipped yet — the normal state of this
  # directory between a version bump and `huginn-sync`, and failing on it would
  # mean every in-progress branch shows a red gate for being in progress, which
  # is how a gate stops being read. So the version decides which of the two this
  # is, and only the dangerous one is a failure. Files with no version constant
  # keep the plain comparison.
  local tv dv
  tv=$(ver_of "$1"); dv=$(ver_of "$2")
  if [ -n "$tv" ] && [ -n "$dv" ] && [ "$tv" != "$dv" ]; then
    skip "not shipped yet: $2 is $dv, the tree is $tv (huginn-sync after the release)"
    return
  fi
  bad "DEPLOYED IS STALE: $2 differs from $1"; DRIFT=1
}
check_deployed client/huginn-device      /usr/local/share/huginn-cli/huginn-device
check_deployed client/huginn.sh          /usr/local/share/huginn-cli/huginn.sh
check_deployed client/huginn.ps1         /usr/local/share/huginn-cli/huginn.ps1
check_deployed client/huginn-local       /usr/local/share/huginn-cli/huginn-local
check_deployed client/huginn-llm-shim    /usr/local/share/huginn-cli/huginn-llm-shim
check_deployed server/bin/huginn-rounds  /usr/local/bin/huginn-rounds
check_deployed server/bin/huginn-llm     /usr/local/bin/huginn-llm
check_deployed server/bin/huginn-devices /usr/local/bin/huginn-devices
check_deployed server/bin/huginn-headroom /usr/local/bin/huginn-headroom
check_deployed server/bin/huginn-archive  /usr/local/bin/huginn-archive
check_deployed server/bin/huginn-projects /usr/local/bin/huginn-projects
[ "$DRIFT" -eq 0 ] || echo "       (install the ones above, or devices keep receiving the old file)" >&2

echo
[ "$FAIL" = 0 ] && echo "client gates: PASS" || { echo "client gates: FAIL" >&2; exit 1; }
