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
[ "$(grep -Fc '.tmp.js"' client/huginn.ps1)" -ge 2 ] \
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
HR_PORT=18787
python3 - "$HR_PORT" <<'STUB' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404); self.end_headers(); self.wfile.write(b'{"error":"not found"}')
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
STUB
HR_STUB=$!
HR_UP=
for _ in $(seq 1 40); do
  curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$HR_PORT/" && { HR_UP=1; break; }
done
if [ -z "$HR_UP" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below read "connection refused" and the 404 check would fail for the wrong
  # reason, which is worse than not running it.
  skip "headroom daemon-too-old checks (nothing bound 127.0.0.1:$HR_PORT)"
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
AR_PORT=18811
python3 - "$AR_PORT" <<'ARSTUB' &
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
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
ARSTUB
AR_STUB=$!
AR_UP=
for _ in $(seq 1 40); do
  curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$AR_PORT/" && { AR_UP=1; break; }
done
if [ -z "$AR_UP" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below fail for the wrong reason.
  skip "archive renderer checks (nothing bound 127.0.0.1:$AR_PORT)"
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

# End to end against a stub daemon. The port is overridable because these gates
# run beside a live appd and beside each other -- 18787 and 18811 are already
# taken by the headroom and archive stubs above.
PJ_PORT="${HUGINN_TEST_PROJECTS_PORT:-18822}"
PJ_404_PORT="${HUGINN_TEST_PROJECTS_404_PORT:-18823}"
PJ_REQ=$(mktemp)
python3 - "$PJ_PORT" "$PJ_REQ" <<'PJSTUB' &
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
LOG = sys.argv[2]
P1 = {"id": "aaaaaaaa-0000-4000-8000-00000000aaaa", "name": "LoRa sensor stick",
      "cwd": "/root/netplan/dev-ledger/lora-stick", "createdAt": 1789459900,
      "lead": {"name": "lora-stick/lead", "state": "busy"},
      "members": [{"name": "lora-stick/docs", "role": "docs", "state": "idle"},
                  {"name": "lora-stick/repo", "role": "repo", "state": "attention",
                   "needsYou": True}]}
P2 = {"id": "bbbbbbbb-0000-4000-8000-00000000bbbb", "name": "status page",
      "cwd": "/root/netplan/status-page", "createdAt": 1789000000, "endedAt": 1789400000,
      "lead": {"name": "status-page/lead"}, "members": []}
DASH = {"project": P1, "updatedAt": 1789460500,
        "members": [{"name": "lora-stick/lead", "role": "lead", "state": "busy", "turns": 12},
                    {"name": "lora-stick/docs", "role": "docs", "state": "idle", "turns": 3},
                    {"name": "lora-stick/repo", "role": "repo", "state": "attention",
                     "needsYou": True, "turns": 7}]}
class H(BaseHTTPRequestHandler):
    def _log(self):
        with open(LOG, "a") as fh:
            fh.write("%s %s\n" % (self.command, self.path))
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        self._log()
        if self.path == "/v1/projects":
            self._send(200, {"projects": [P1, P2]})
        elif self.path.endswith("/dashboard"):
            self._send(200, DASH)
        else:
            self._send(404, {"error": "no"})
    def do_POST(self):
        self._log()
        n = int(self.headers.get("content-length") or 0)
        sent = self.rfile.read(n) if n else b""
        if self.path.endswith("/spawn") and b"flood" in sent:
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
        self._send(200, {"ok": True, "ended": ["lora-stick-docs"]})
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PJSTUB
PJ_STUB=$!
python3 - "$PJ_404_PORT" <<'PJ404' >/dev/null 2>&1 &
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404); self.end_headers(); self.wfile.write(b'{"error":"not found"}')
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PJ404
PJ_404_STUB=$!
PJ_UP=
for _ in $(seq 1 40); do
  curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$PJ_PORT/" && { PJ_UP=1; break; }
done
if [ -z "$PJ_UP" ]; then
  # LOUDLY, never silently: a stub that never bound would make every assertion
  # below fail for the wrong reason (set HUGINN_TEST_PROJECTS_PORT to move it).
  skip "projects renderer checks (nothing bound 127.0.0.1:$PJ_PORT)"
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
  # A NAME is resolved host-side, exactly like `huginn-archive revive <name>`,
  # so neither client has to parse the list in bash AND in PowerShell.
  PJ_MISS2=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" server/bin/huginn-projects show nosuchproject 2>&1)
  grep -q "no project" <<<"$PJ_MISS2" \
    && ok "show of an unknown project says so instead of 404-ing" || bad "show unknown: $PJ_MISS2"
  # ⚠ A MALFORMED member:role MUST NOT BECOME A REQUEST. `spawn` starts real
  # Claude sessions; a half-parsed pair ("docs" with no role, a role with a
  # slash in it) that reaches the daemon either spawns the wrong thing or leaves
  # a project half-born, and the caller cannot tell which. Refused in the
  # renderer, before anything is sent -- asserted by the request log staying
  # empty, not just by the exit code.
  : > "$PJ_REQ"
  PJ_BAD=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
           server/bin/huginn-projects spawn "LoRa sensor stick" 'docs' --prompt 'x' 2>&1); PJ_RC=$?
  PJ_BAD2=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
            server/bin/huginn-projects spawn "LoRa sensor stick" 'docs:a;rm -rf /' --prompt 'x' 2>&1)
  [ "$PJ_RC" != 0 ] && grep -q "member:role" <<<"$PJ_BAD" && ! grep -q "POST" "$PJ_REQ" \
    && ok "spawn refuses a malformed member:role and sends nothing at all" \
    || bad "spawn accepted a malformed pair (exit $PJ_RC): $PJ_BAD / $PJ_BAD2 / $(cat "$PJ_REQ")"
  PJ_SPAWN=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_PORT" \
             server/bin/huginn-projects spawn "LoRa sensor stick" docs:docs repo:repo \
             --prompt 'write the README' 2>&1); PJ_RC=$?
  grep -q "docs" <<<"$PJ_SPAWN" && grep -q "POST /v1/projects/aaaaaaaa-0000-4000-8000-00000000aaaa/spawn" "$PJ_REQ" \
    && ok "a well-formed spawn reaches the resolved project's spawn route" \
    || bad "spawn sent: $(cat "$PJ_REQ") / printed: $PJ_SPAWN"
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
             server/bin/huginn-projects spawn "LoRa sensor stick" flood:flood 2>&1 | cat | tail -3)
  grep -q "THE-LAST-LINE" <<<"$PJ_FLOOD" \
    && ok "a long reply on the non-zero-exit path survives the pipe" \
    || bad "output was truncated by the exit; tail was: $PJ_FLOOD"
fi
# ⚠ AN OLDER DAEMON IS NOT A BROKEN ONE. /v1/projects does not exist before the
# Wave 3 appd, and "404" on its own sends somebody looking for a bug in the
# renderer. One line, naming the daemon, and its own exit code so a script can
# branch on it.
PJ_OLD=$(HUGINN_APPD_URL="http://127.0.0.1:$PJ_404_PORT" server/bin/huginn-projects 2>&1); PJ_RC=$?
[ "$PJ_RC" = 3 ] && grep -qi "huginn-appd" <<<"$PJ_OLD" \
  && ok "projects on a daemon without the feature exits 3 and says which daemon" \
  || bad "projects on a 404 exited $PJ_RC: $PJ_OLD"
PJ_DEAD=$(HUGINN_APPD_URL="http://127.0.0.1:1" server/bin/huginn-projects 2>&1); PJ_RC=$?
[ "$PJ_RC" = 2 ] \
  && ok "projects exits 2 when nothing is answering (3 = appd is there but too old)" \
  || bad "projects with no daemon exited $PJ_RC: $PJ_DEAD"
# ⚠ AND IT NEVER PRINTS THE TOKEN, the same failure headroom and archive were
# audited for: the bearer is one variable away from every string these paths emit.
if [ -r /etc/huginn-appd/token ]; then
  PJ_TOK=$(tr -d '[:space:]' < /etc/huginn-appd/token)
  if [ -n "$PJ_TOK" ] && grep -qF "$PJ_TOK" <<<"${PJ_LIST:-}${PJ_DEAD}${PJ_OLD}${PJ_BAD:-}"; then
    bad "huginn-projects printed the bearer token on a failure path"
  else
    ok "huginn-projects failure paths print no credential"
  fi
else
  skip "projects token-leak check (no readable /etc/huginn-appd/token here)"
fi
kill "$PJ_STUB" "$PJ_404_STUB" 2>/dev/null
rm -f "$PJ_REQ"

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
  if cmp -s "$1" "$2"; then ok "deployed matches tree: $2"; return; fi
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
