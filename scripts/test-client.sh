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

echo "[1/7] syntax"
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

echo "[2/7] the two version constants agree with each other and the changelog"
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
echo "[2b/7] both clients pin the update trust root"
for f in client/huginn.sh client/huginn.ps1; do
  grep -q 'HUGINN_UPDATE_HOST' "$f" && ok "$f pins HUGINN_UPDATE_HOST" \
    || bad "$f fetches update code from an unpinned host"
done
grep -q 'scp .*\${H}:' client/huginn.ps1 && bad "huginn.ps1 still scps from \$HUGINN_HOST" \
  || ok "huginn.ps1 does not scp from \$HUGINN_HOST"

echo "[3/7] both clients expose the same verbs (parity by verb)"
# huginn.sh writes cases as alternations (`list|ls)`, `status|st)`), so match the
# verb as a case ALTERNATIVE, not as a bare `verb)`.
for v in end kill solo rename list status rounds devices device desktop usage update version help; do
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

echo "[4/7] what the PowerShell client actually SENDS"
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

echo "[5/7] what the POSIX client actually SENDS"
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

echo "[6/7] desktop links come from GitHub, and reach it WITHOUT the host"
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

echo "[7/7] the headless runner (client/huginn-device)"
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
OFF=$(HUGINN_DEVICE_DIR="$TD" node client/huginn-device off 2>&1); OFF_RC=$?
[ "$OFF_RC" -ne 0 ] && ok "off fails loudly when huginn is unreachable" \
  || bad "off exited 0 with the host unreachable"
grep -q "Removed from huginn" <<<"$OFF" && bad "off claimed success while failing" \
  || ok "off does not claim to have removed anything"
grep -q '11111111-1111-1111-1111-111111111111' "$TD/device.json" \
  && ok "off keeps the id, so it can be run again" \
  || bad "off destroyed the only handle that can remove the row"
rm -rf "$TD"

echo
[ "$FAIL" = 0 ] && echo "client gates: PASS" || { echo "client gates: FAIL" >&2; exit 1; }
