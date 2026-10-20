#!/usr/bin/env bash
# test-ps51-quotes.sh - what client/huginn.ps1 sends over ssh reaches the host
# intact under Windows PowerShell 5.1's argument passing, double quotes and all.
#
# WHY THIS EXISTS: Windows PowerShell 5.1 (and 7 before 7.3, and every verb
# huginn core forwards to powershell.exe) passes a native argument with a space
# in it wrapped in "..." WITHOUT escaping the double quotes inside, and ssh.exe's
# argument parser eats them. `huginn usage week` put "7 days ago" inside its
# one-argument remote line, so the host ran `date -d 7 days ago` and ccusage got
# an empty -s; a `huginn projects` brief or message lost every double quote it
# had. The gate's other PowerShell checks run pwsh 7 on Linux, whose default
# passing is correct, so none of them could see it.
#
# pwsh 7 still carries the old binder as $PSNativeCommandArgumentPassing =
# 'Legacy', which builds the same command line 5.1 does and splits it back into
# argv the way the C runtime does; a control below proves it still mangles here
# before the 5.1 half is believed. Each verb is run in Legacy and in Standard
# mode against a stub ssh that does what ssh does with the words after the host
# (joins them with spaces and hands them to a shell) and a far side that holds
# nothing but stub ccusage and huginn-projects and the date, base64 and bash
# they need.
#
# Usage: scripts/test-ps51-quotes.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> client | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

PWSH="$(command -v pwsh || true)"
if [ -z "$PWSH" ]; then skip "every check (no pwsh)"; echo; echo "ps51-quotes gates: PASS (nothing ran)"; exit 0; fi

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/far" "$T/home"
for t in date base64 bash; do ln -s "$(command -v "$t")" "$T/far/$t"; done
for t in ccusage huginn-projects; do
  printf '#!/bin/bash\nprintf "%s argv:"; for a in "$@"; do printf " [%%s]" "$a"; done; echo\n' "$t" > "$T/far/$t"
done
# ssh: skip the options and the host, join the rest with single spaces (ssh.c
# builds the remote command that way) and run it in a shell, as sshd would.
cat > "$T/bin/ssh" <<'STUB'
#!/bin/bash
while [ $# -gt 0 ]; do case "$1" in -o) shift 2 ;; -*) shift ;; *) break ;; esac; done
shift
printf 'remote: <%s>\n' "$*"
env -i PATH="$FAR" bash -c "$*" 2>&1
exit 0
STUB
chmod +x "$T/bin/ssh" "$T/far/ccusage" "$T/far/huginn-projects"

# shellcheck disable=SC2016  # PowerShell, not bash
cat > "$T/run.ps1" <<'PS'
param([string]$Mode, [string]$Client, [string]$Verb)
$global:PSNativeCommandArgumentPassing = $Mode
if ($Verb -eq 'control') { ssh -T testhost 'echo "a  b"'; exit }
. $Client
$env:HUGINN_HOST = 'testhost'
switch ($Verb) {
  'week'  { huginn usage week }
  'brief' { huginn projects new demo --brief 'Fix the "Sign in" button, then  run "npm test"' }
  'msg'   { huginn projects msg demo lead 'say "hi" to the "lead"' }
  'show'  { huginn projects show demo }
}
PS
send() {   # $1 = Legacy|Standard, $2 = verb -> what the stub ssh and the far side printed
  env -i HOME="$T/home" PATH="$T/bin:/usr/bin:/bin" FAR="$T/far" XDG_CONFIG_HOME="$T/home/.config" \
    XDG_CACHE_HOME="$T/home/.cache" XDG_DATA_HOME="$T/home/.local/share" \
    "$PWSH" -NoProfile -NonInteractive -File "$T/run.ps1" -Mode "$1" -Client "$TREE/client/huginn.ps1" -Verb "$2" 2>&1 \
    | tr -d '\r'
}

echo "[1/3] the control: Legacy passing still mangles a double quote here"
# `echo "a  b"`: passed intact it prints the two spaces; with the quotes eaten,
# ssh gets two words and joins them with one.
if send Legacy control | grep -qx 'a b' && send Standard control | grep -qx 'a  b'; then
  ok "Legacy passing eats the quotes in 'echo \"a  b\"', as 5.1 does; Standard keeps them"
  MODES="Legacy Standard"
else
  skip "the 5.1 half (this pwsh's Legacy passing no longer mangles: $(send Legacy control | tr '\n' '|'))"
  MODES="Standard"
fi

echo "[2/3] usage week"
for m in $MODES; do
  before="$(date -d '7 days ago' +%Y%m%d)"; OUT="$(send "$m" week)"; after="$(date -d '7 days ago' +%Y%m%d)"
  if grep -qE "^ccusage argv: \[daily\] \[-s\] \[($before|$after)\] \[-u\] \[[0-9]{8}\]$" <<<"$OUT"; then
    ok "$m: ccusage gets -s seven days back and -u today"
  else
    bad "$m: the far side said: $(tr '\n' '|' <<<"$OUT")"
  fi
done

echo "[3/3] projects: a brief and a message keep every double quote"
for m in $MODES; do
  OUT="$(send "$m" brief)"
  grep -qxF 'huginn-projects argv: [new] [demo] [--brief] [Fix the "Sign in" button, then  run "npm test"]' <<<"$OUT" \
    && ok "$m: the brief arrives exactly as typed" \
    || bad "$m: the brief arrived as: $(grep 'argv' <<<"$OUT" || tr '\n' '|' <<<"$OUT")"
  OUT="$(send "$m" msg)"
  grep -qxF 'huginn-projects argv: [msg] [demo] [lead] [say "hi" to the "lead"]' <<<"$OUT" \
    && ok "$m: and so does a message" \
    || bad "$m: the message arrived as: $(grep 'argv' <<<"$OUT" || tr '\n' '|' <<<"$OUT")"
  # A line with no double quote is sent as it always was: the renderer keeps
  # ssh's stdin, which `--brief -` reads.
  OUT="$(send "$m" show)"
  grep -qxF "remote: <huginn-projects 'show' 'demo'>" <<<"$OUT" && grep -qxF 'huginn-projects argv: [show] [demo]' <<<"$OUT" \
    && ok "$m: a line with no double quote goes plain, as before" \
    || bad "$m: 'projects show demo' went as: $(tr '\n' '|' <<<"$OUT")"
done

echo
[ "$FAIL" = 0 ] && echo "ps51-quotes gates: PASS" || { echo "ps51-quotes gates: FAIL" >&2; exit 1; }
