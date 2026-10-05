#!/usr/bin/env bash
# test-claude-title.sh — the nested-claude guard in server/bin/huginn-claude-title.
#
# The hook writes /run/huginn-claude-state/<tmux session> from Claude Code hook
# events, and that file is how huginn-appd learns which Claude transcript belongs
# to which tmux session. A claude started by a Bash tool INSIDE a tmux Claude
# session inherits $TMUX/$TMUX_PANE and resolves the SAME session name, so without
# the guard two claudes write one file: on 2026-10-04 the state flipped between two
# transcripts every few seconds and every client watching that session flickered,
# lost its history, and read the daemon's "no such agent" as "needs appd 3.0".
#
# The guard reads the nearest `claude` ancestor's environ for CLAUDECODE=1 — the
# mark a parent Claude Code puts on everything its Bash tool starts, and one a
# top-level claude never carries. (The hook's OWN environment is useless for this:
# a top-level claude hands its hooks CLAUDECODE=1 too.)
#
# Needs a tmux pane ($TMUX_PANE): the hook resolves the session name through tmux.
# Without one it SKIPS LOUDLY. Writes only under a private temp dir, never /run.
# The one visible side effect is the tab title of the pane it runs in.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="${HUGINN_CLAUDE_TITLE:-$HERE/server/bin/huginn-claude-title}"
if [ -z "${TMUX:-}" ] || [ -z "${TMUX_PANE:-}" ]; then
  echo "SKIP test-claude-title: not inside a tmux pane (the hook resolves the session name via tmux)"
  exit 0
fi
command -v jq >/dev/null 2>&1 || { echo "SKIP test-claude-title: jq missing"; exit 0; }
[ -x "$HOOK" ] || { echo "FAIL test-claude-title: $HOOK is not executable"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# A bash whose comm is `claude`, like the real binary's: comm is the basename of
# the path exec'd, so a symlink is enough and no claude is started.
ln -s /bin/bash "$TMP/claude"
SESS="$(tmux display-message -p -t "$TMUX_PANE" '#{session_name}')"
[ -n "$SESS" ] || { echo "FAIL test-claude-title: tmux gave no session name"; exit 1; }
PAYLOAD='{"session_id":"11111111-2222-test","transcript_path":"/nonexistent/t.jsonl","cwd":"/tmp"}'

pass=0; fail=0
check() { if "$@"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "  FAIL: $*"; fi; }
# $1 = state dir, then env assignments / -u flags for the fake claude.
run_hook() {
  local dir="$1"; shift
  env "$@" HUGINN_CLAUDE_STATE_DIR="$dir" PAYLOAD="$PAYLOAD" HOOK="$HOOK" \
    "$TMP/claude" -c 'printf "%s" "$PAYLOAD" | "$HOOK" Stop'
}

# 1. Top-level: the claude ancestor has no CLAUDECODE → the state file is written
#    with the payload's session id.
run_hook "$TMP/s1" -u CLAUDECODE -u CLAUDE_CODE_SESSION_ID
check test -f "$TMP/s1/$SESS"
check test "$(jq -r .sessionId "$TMP/s1/$SESS" 2>/dev/null)" = "11111111-2222-test"

# 2. Nested: the claude ancestor carries CLAUDECODE=1 → nothing is written at all.
run_hook "$TMP/s2" CLAUDECODE=1
check test ! -e "$TMP/s2/$SESS"
check test ! -e "$TMP/s2"

# 3. The override, both ways, so a test elsewhere can force the verdict.
run_hook "$TMP/s3" -u CLAUDECODE HUGINN_CLAUDE_TITLE_NESTED=1
check test ! -e "$TMP/s3/$SESS"
run_hook "$TMP/s4" CLAUDECODE=1 HUGINN_CLAUDE_TITLE_NESTED=0
check test -f "$TMP/s4/$SESS"

echo "test-claude-title: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
