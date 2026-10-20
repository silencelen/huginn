#!/usr/bin/env bash
# test-cc-args.sh - server/bin/cc reads its arguments by position: the session
# name first, and `solo` as the flag only in second place.
#
# WHY THIS EXISTS: cc read the word `solo` as the flag wherever it stood. `solo`
# is also a session name: the daemon creates one, and every client opens it as
# `cc solo` (the desktop's "Open in terminal" sends `huginn attach 'solo'`). So
# opening that session attached to MAIN instead, with `attach -d`, which
# detaches every other device attached to main, and the session called solo
# could not be opened from any client.
#
# Driven with a stub tmux that only writes down what it was asked, so no
# session is created, attached or detached.
#
# Usage: scripts/test-cc-args.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1
CC="$TREE/server/bin/cc"

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
# has-session answers yes for any name, so cc takes its solo path whenever it
# means to.
cat > "$T/tmux" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CC_LOG"
exit 0
STUB
chmod +x "$T/tmux"

cc_asks() {   # "$@" = cc's arguments -> every tmux call it made, one per line
  : > "$T/log"
  CC_LOG="$T/log" PATH="$T:$PATH" HUGINN_WORKDIR="$T" bash "$CC" "$@" >/dev/null 2>&1
  cat "$T/log"
}
opens() {     # $1 = the session cc must open as a mirror (attach-or-create); $2.. = cc's arguments
  local want="$1" got; shift
  got="$(cc_asks "$@")"
  if grep -qF -- "-u new-session -A -s $want -c $T " <<<"$got" && ! grep -q -- 'attach -d' <<<"$got"; then
    ok "cc ${*:-(no arguments)}: opens '$want', detaching nobody"
  else
    bad "cc ${*:-(no arguments)}: should open '$want' - tmux was asked: $(tr '\n' '|' <<<"$got")"
  fi
}
takes_solo() { # $1 = the session cc must take solo; $2.. = cc's arguments
  local want="$1" got; shift
  got="$(cc_asks "$@")"
  if grep -qxF -- "-u attach -d -t =$want" <<<"$got" && [ "$(grep -c -- 'attach -d' <<<"$got")" = 1 ]; then
    ok "cc $*: takes '$want' solo"
  else
    bad "cc $*: should take '$want' solo - tmux was asked: $(tr '\n' '|' <<<"$got")"
  fi
}

echo "[1/2] a session named solo is that session, not main"
opens solo solo
takes_solo solo solo solo
got="$(cc_asks solo; cc_asks solo solo)"
grep -q -- '=main\|-s main' <<<"$got" \
  && bad "a call for the session solo still names main: $(tr '\n' '|' <<<"$got")" \
  || ok "and neither call names main at all"

echo "[2/2] what every client sends today, and the first clients' order"
opens main
opens work work
takes_solo main main solo
takes_solo work work solo
# `cc solo <name>`: the order clients sent until 2026-06-16.
takes_solo work solo work
# Case still folds before tmux sees the name.
opens solo Solo

echo
[ "$FAIL" = 0 ] && echo "cc-args gates: PASS" || { echo "cc-args gates: FAIL" >&2; exit 1; }
