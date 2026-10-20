#!/usr/bin/env bash
# test-rename-route.sh - `huginn rename` goes through the daemon's rename route
# in both shell clients, and falls back to raw tmux only when nothing answered.
#
# WHY THIS EXISTS: both clients renamed with a bare `tmux rename-session`, which
# left everything huginn-appd keeps by session name under the OLD name: the
# state file (the apps showed no state or conversation for the session), the
# send queue (a message queued for the old name was never delivered), the
# restore-registry row (a reboot restored a fresh claude instead of resuming),
# and the project record (a member renamed this way dropped out of its project;
# a renamed lead's project was archived). POST /v1/sessions/<name>/rename moves
# all of it and refuses a project member with a sentence saying what to do; the
# Go console and both apps already use it. Same rule as `huginn kill`: raw tmux
# only when the daemon was never reached (a terminal-only server has none).
#
# The stub ssh never runs what it is handed. It reads the daemon call out of
# the command (the PowerShell client sends it base64'd), writes down the method,
# path and body, and answers as the scenario says: renamed, refused, or no
# daemon at all. A raw tmux command is only written down.
#
# Usage: scripts/test-rename-route.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> client | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/home"
cat > "$T/bin/ssh" <<'STUB'
#!/bin/bash
while [ $# -gt 0 ]; do case "$1" in -o) shift 2 ;; -*) shift ;; *) break ;; esac; done
shift
cmd="$*"
case "$cmd" in
  "echo "*" | base64 -d | bash -s") b="${cmd#echo }"; cmd="$(printf '%s' "${b%% *}" | base64 -d)" ;;
esac
case "$cmd" in
  *curl*https://127.0.0.1:8787*)
    method="$(sed -n 's/.* -X \([A-Z][A-Z]*\) .*/\1/p' <<<"$cmd")"
    path="$(grep -o 'https://127\.0\.0\.1:8787[^"]*' <<<"$cmd" | sed 's#^https://127.0.0.1:8787##')"
    data="$(sed -n "s/.*--data '\([^']*\)'.*/\1/p" <<<"$cmd")"
    printf 'APPD %s %s %s\n' "$method" "$path" "$data" >> "$RN_LOG"
    case "$RN_SCENARIO" in
      renamed) printf '{"ok":true,"name":"payments"}\n200' ;;
      refused) printf '%s\n409' "$RN_REFUSAL" ;;
      nodaemon)
        echo "huginn: cannot read the huginn-appd certificate /var/lib/huginn-appd/tls/cert.pem - the token goes only to a daemon that proves it holds that key" >&2
        exit 3 ;;
    esac ;;
  tmux\ *)
    printf 'TMUX %s\n' "$cmd" >> "$RN_LOG"
    echo 'renamed: build-box -> payments' ;;
  *)
    printf 'OTHER %s\n' "$cmd" >> "$RN_LOG" ;;
esac
exit 0
STUB
chmod +x "$T/bin/ssh"
# What the daemon says for a project member, as sendErr encodes it.
REFUSAL='{"error":"'"'"'build-box'"'"' is the worker session of the project \"Sensor board\" — its name is what the project and the peer registry know it by. Drop it from the project first (DELETE /v1/projects/p1/members/worker), then rename it"}'

PWSH="$(command -v pwsh || true)"
rename_with() {   # $1 = sh|ps1, $2 = scenario -> the client's output; the log holds what ssh was asked
  : > "$T/log"
  case "$1" in
    sh)  env -i HOME="$T/home" PATH="$T/bin:/usr/bin:/bin" RN_LOG="$T/log" RN_SCENARIO="$2" RN_REFUSAL="$REFUSAL" \
           bash -c '. "$1" >/dev/null 2>&1; huginn rename build-box payments' sh "$TREE/client/huginn.sh" 2>&1
         echo "exit=$?" ;;
    ps1) env -i HOME="$T/home" PATH="$T/bin:/usr/bin:/bin" RN_LOG="$T/log" RN_SCENARIO="$2" RN_REFUSAL="$REFUSAL" \
           XDG_CONFIG_HOME="$T/home/.config" XDG_CACHE_HOME="$T/home/.cache" XDG_DATA_HOME="$T/home/.local/share" \
           "$PWSH" -NoProfile -NonInteractive -Command ". '$TREE/client/huginn.ps1'; huginn rename build-box payments" 2>&1 \
           | tr -d '\r'
         echo "exit=${PIPESTATUS[0]}" ;;
  esac
}

for c in sh ps1; do
  if [ "$c" = ps1 ] && [ -z "$PWSH" ]; then skip "ps1: every check (no pwsh)"; continue; fi
  echo "[$c] huginn rename build-box payments"
  OUT="$(rename_with "$c" renamed)"
  if grep -qxF 'APPD POST /v1/sessions/build-box/rename {"name":"payments"}' "$T/log" && ! grep -q '^TMUX' "$T/log" \
     && grep -qx 'renamed: build-box -> payments' <<<"$OUT" && grep -qx 'exit=0' <<<"$OUT"; then
    ok "$c: renamed through POST /v1/sessions/build-box/rename, and no raw tmux"
  else
    bad "$c: ssh was asked: $(tr '\n' '|' < "$T/log") / said: $(tr '\n' '|' <<<"$OUT")"
  fi
  OUT="$(rename_with "$c" refused)"
  if ! grep -q '^TMUX' "$T/log" && grep -qF 'the project "Sensor board"' <<<"$OUT" \
     && grep -qF 'Drop it from the project first' <<<"$OUT" && ! grep -qx 'exit=0' <<<"$OUT"; then
    ok "$c: a project member is refused in the daemon's whole sentence, and not renamed behind its back"
  else
    bad "$c: on the refusal ssh was asked: $(tr '\n' '|' < "$T/log") / said: $(tr '\n' '|' <<<"$OUT")"
  fi
  OUT="$(rename_with "$c" nodaemon)"
  grep -qF "TMUX tmux rename-session -t '=build-box' 'payments'" "$T/log" && grep -qx 'renamed: build-box -> payments' <<<"$OUT" \
    && ok "$c: with no daemon to answer, raw tmux still renames it" \
    || bad "$c: with no daemon ssh was asked: $(tr '\n' '|' < "$T/log") / said: $(tr '\n' '|' <<<"$OUT")"
done

echo
[ "$FAIL" = 0 ] && echo "rename-route gates: PASS" || { echo "rename-route gates: FAIL" >&2; exit 1; }
