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
for v in end kill solo rename list status rounds devices device local desktop usage update uninstall version help; do
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
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
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

  B=$(pwsh -NoProfile -Command ". $PWD/client/huginn.ps1; huginn end 'bad-name'" 2>&1)
  grep -q "invalid session name" <<<"$B" \
    && ok "end rejects a non-conforming name" || bad "end accepted 'bad-name'"
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
const r = require("/opt/huginn/client/huginn-device");
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
[ "$(grep -Fc '.tmp.js"' client/huginn.ps1)" -ge 2 ] \
  && ok "ps1 fetches syntax-check under a .js temp name" \
  || bad "ps1 fetch temp name regressed — .tmp is unparseable on modern node"

# The delegation lane: parse + parity here; the LIVE path is the daily
# smoke's job. A tool promised in two shells must exist in both.
bash -n server/bin/huginn-llm && ok "huginn-llm parses" || bad "huginn-llm does not parse"
grep -q '^    llm) ssh -T' client/huginn.sh && grep -q "eq 'llm'" client/huginn.ps1 \
  && ok "both shells carry: llm" || bad "the llm verb is missing from a shell client"

# Verb parity for the new door, and the runner riding along in the fetch —
# a machine that never enrolled as a claude device has no runner otherwise.
grep -q '^    plan)' client/huginn.sh && grep -q "\$sub -eq 'plan'" client/huginn.ps1 \
  && ok "both shells carry: local plan" || bad "local plan is missing from a shell client"
grep -q 'for f in huginn-local huginn-llm-shim huginn-device; do' client/huginn.sh \
  && grep -q "'huginn-local', 'huginn-llm-shim', 'huginn-device'" client/huginn.ps1 \
  && ok "both shells fetch the device runner with the local tier" \
  || bad "a shell client's local fetch list is missing huginn-device"

# The shim's own suite carries the contract verifier — the dialect
# handleClaudeEvent consumes, asserted frame by frame.
SHIM_OUT=$(node --test scripts/test-llm-shim.js 2>&1)
SHIM_PASS=$(echo "$SHIM_OUT" | grep -m1 '^# pass' | grep -oE '[0-9]+')
SHIM_FAIL=$(echo "$SHIM_OUT" | grep -m1 '^# fail' | grep -oE '[0-9]+')
if [ "${SHIM_FAIL:-1}" = 0 ] && [ "${SHIM_PASS:-0}" -ge 10 ]; then
  ok "shim suite: $SHIM_PASS passed (incl. the stream-json contract verifier)"
else
  bad "shim suite: pass=${SHIM_PASS:-?} fail=${SHIM_FAIL:-?}"
  echo "$SHIM_OUT" | grep -A4 'not ok' | head -20 >&2
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
check_deployed () {   # $1 = repo path, $2 = installed path
  if [ ! -f "$2" ]; then skip "not installed here: $2"; return; fi
  if cmp -s "$1" "$2"; then ok "deployed matches tree: $2"
  else bad "DEPLOYED IS STALE: $2 differs from $1"; DRIFT=1; fi
}
check_deployed client/huginn-device      /usr/local/share/huginn-cli/huginn-device
check_deployed client/huginn.sh          /usr/local/share/huginn-cli/huginn.sh
check_deployed client/huginn.ps1         /usr/local/share/huginn-cli/huginn.ps1
check_deployed client/huginn-local       /usr/local/share/huginn-cli/huginn-local
check_deployed client/huginn-llm-shim    /usr/local/share/huginn-cli/huginn-llm-shim
check_deployed server/bin/huginn-rounds  /usr/local/bin/huginn-rounds
check_deployed server/bin/huginn-llm     /usr/local/bin/huginn-llm
check_deployed server/bin/huginn-devices /usr/local/bin/huginn-devices
[ "$DRIFT" -eq 0 ] || echo "       (install the ones above, or devices keep receiving the old file)" >&2

echo
[ "$FAIL" = 0 ] && echo "client gates: PASS" || { echo "client gates: FAIL" >&2; exit 1; }
