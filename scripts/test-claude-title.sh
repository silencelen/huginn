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
# Cases 1-10 need a tmux pane ($TMUX_PANE): the hook resolves the session name
# through tmux, and without one they SKIP LOUDLY. The background-job cases (B1-B6)
# need no pane, which is their point, and always run. Writes only under a private
# temp dir, never /run. The one visible side effect is the tab title of the pane it
# runs in.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="${HUGINN_CLAUDE_TITLE:-$HERE/server/bin/huginn-claude-title}"
command -v jq >/dev/null 2>&1 || { echo "SKIP test-claude-title: jq missing"; exit 0; }
[ -x "$HOOK" ] || { echo "FAIL test-claude-title: $HOOK is not executable"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# A bash whose comm is `claude`, like the real binary's: comm is the basename of
# the path exec'd, so a symlink is enough and no claude is started.
ln -s /bin/bash "$TMP/claude"
PAYLOAD='{"session_id":"11111111-2222-test","transcript_path":"/nonexistent/t.jsonl","cwd":"/tmp"}'

pass=0; fail=0
check() { if "$@"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "  FAIL: $*"; fi; }

# B1-B7. A Claude Code BACKGROUND JOB (its hooks get CLAUDE_JOB_DIR) runs under a
#    bg-pty-host with no pane anywhere above it, so the ancestor walk finds
#    nothing. It finds its tmux session through the state file that names its
#    session id instead. Run detached (a double fork), so that even inside a pane
#    no ancestor holds $TMUX/$TMUX_PANE, as for the real thing.
# $1 = state dir, $2 = event, $3 = payload, then env assignments for the job.
bg_hook() {
  local dir="$1" ev="$2" pl="$3"; shift 3
  local done="$TMP/done.$RANDOM$RANDOM"
  ( env -u TMUX -u TMUX_PANE -u CLAUDECODE -u CLAUDE_CODE_SESSION_KIND -u CLAUDE_JOB_DIR "$@" \
      HUGINN_CLAUDE_STATE_DIR="$dir" PAYLOAD="$pl" HOOK="$HOOK" EV="$ev" DONE="$done" \
      "$TMP/claude" -c 'printf "%s" "$PAYLOAD" | "$HOOK" "$EV"; : > "$DONE"' & )
  for _ in $(seq 200); do [ -e "$done" ] && return 0; sleep 0.05; done
  echo "  (bg_hook: the hook did not finish)"; return 1
}
seed() { mkdir -p "$1"; printf '%s\n' "$3" > "$1/$2"; }   # $1 dir, $2 session, $3 json
BGP='{"session_id":"bbbbbbbb-bg-test","transcript_path":"/nonexistent/bg.jsonl","cwd":"/tmp"}'
OLD='{"state":"running","sessionId":"bbbbbbbb-bg-test","transcript":"/nonexistent/bg.jsonl","cwd":"/tmp","ts":1}'
OTHER_JSON='{"state":"idle","sessionId":"cccccccc-other","transcript":null,"cwd":"/tmp","ts":1}'
# What a real job's hooks are handed: CLAUDE_JOB_DIR, and NOT
# CLAUDE_CODE_SESSION_KIND (that one stays on the claude process).
JOB=(CLAUDE_JOB_DIR=/nonexistent/jobs/job123/)

# B1. The one file naming its session id is the job's: the state moves on, the id
#     stays, the job is recorded; another session's file is not touched.
seed "$TMP/b1" bgsess "$OLD"; seed "$TMP/b1" other "$OTHER_JSON"
bg_hook "$TMP/b1" Stop "$BGP" "${JOB[@]}"
check test "$(jq -r .state "$TMP/b1/bgsess" 2>/dev/null)" = idle
check test "$(jq -r .sessionId "$TMP/b1/bgsess" 2>/dev/null)" = bbbbbbbb-bg-test
check test "$(jq -r .job "$TMP/b1/bgsess" 2>/dev/null)" = job123
check test "$(cat "$TMP/b1/other")" = "$OTHER_JSON"

# B2. No file names it: nothing is written, nothing is created.
seed "$TMP/b2" other "$OTHER_JSON"
bg_hook "$TMP/b2" Stop "$BGP" "${JOB[@]}"
check test "$(ls -A "$TMP/b2")" = other
check test "$(cat "$TMP/b2/other")" = "$OTHER_JSON"

# B3. Two files name it: not the hook's to choose, so neither moves.
seed "$TMP/b3" one "$OLD"; seed "$TMP/b3" two "$OLD"
bg_hook "$TMP/b3" Stop "$BGP" "${JOB[@]}"
check test "$(jq -r .state "$TMP/b3/one" 2>/dev/null)" = running
check test "$(jq -r .state "$TMP/b3/two" 2>/dev/null)" = running

# B4. Not a background job (a headless `claude -p`): ignored, as before.
seed "$TMP/b4" bgsess "$OLD"
bg_hook "$TMP/b4" Stop "$BGP"
check test "$(jq -r .state "$TMP/b4/bgsess" 2>/dev/null)" = running

# B5. A fork changed the session id; the job recorded in B1 still finds the file.
bg_hook "$TMP/b1" PreToolUse '{"session_id":"dddddddd-forked","transcript_path":"/nonexistent/f.jsonl","cwd":"/tmp","tool_name":"Bash"}' "${JOB[@]}"
check test "$(jq -r .sessionId "$TMP/b1/bgsess" 2>/dev/null)" = dddddddd-forked
check test "$(jq -r .state "$TMP/b1/bgsess" 2>/dev/null)" = running

# B6. A question from a background job leaves its sidecar, so it can alert.
bg_hook "$TMP/b1" PreToolUse '{"session_id":"dddddddd-forked","transcript_path":"/nonexistent/f.jsonl","cwd":"/tmp","tool_name":"AskUserQuestion","tool_input":{"questions":[]}}' "${JOB[@]}"
check test -f "$TMP/b1/.ask/bgsess"

# B7. The process-side mark alone is a job too (should a version hand it on).
seed "$TMP/b7" bgsess "$OLD"
bg_hook "$TMP/b7" Stop "$BGP" CLAUDE_CODE_SESSION_KIND=bg
check test "$(jq -r .state "$TMP/b7/bgsess" 2>/dev/null)" = idle

# B8. The shared temp name taken (here by a directory standing in for another
#     writer's temp, renamed or half-written): the record must survive. The old
#     fallback wrote the bare state word over it, after which no event of the
#     job could find its file again.
seed "$TMP/b8" bgsess "$OLD"; mkdir -p "$TMP/b8/bgsess.tmp"
bg_hook "$TMP/b8" Stop "$BGP" "${JOB[@]}"
check test "$(jq -r .sessionId "$TMP/b8/bgsess" 2>/dev/null)" = bbbbbbbb-bg-test
check test "$(jq -r .state "$TMP/b8/bgsess" 2>/dev/null)" = idle
bg_hook "$TMP/b8" PreToolUse '{"session_id":"bbbbbbbb-bg-test","transcript_path":"/nonexistent/bg.jsonl","cwd":"/tmp","tool_name":"Bash"}' "${JOB[@]}"
check test "$(jq -r .state "$TMP/b8/bgsess" 2>/dev/null)" = running

# B9. Sixteen of one job's hooks at once, three times over (a turn that starts
#     background agents): the record is the job's JSON after every burst, and no
#     temp is left behind.
bg_burst() {   # $1 = n, $2 = dir, $3 = event, $4 = payload, then env for the job
  local n="$1" dir="$2" ev="$3" pl="$4" i; shift 4
  local tag="$TMP/burst.$RANDOM$RANDOM"
  for i in $(seq "$n"); do
    ( env -u TMUX -u TMUX_PANE -u CLAUDECODE -u CLAUDE_CODE_SESSION_KIND -u CLAUDE_JOB_DIR "$@" \
        HUGINN_CLAUDE_STATE_DIR="$dir" PAYLOAD="$pl" HOOK="$HOOK" EV="$ev" DONE="$tag.$i" \
        "$TMP/claude" -c 'printf "%s" "$PAYLOAD" | "$HOOK" "$EV"; : > "$DONE"' & )
  done
  for _ in $(seq 400); do [ "$(ls "$tag".* 2>/dev/null | wc -l)" -ge "$n" ] && return 0; sleep 0.05; done
  echo "  (bg_burst: the hooks did not all finish)"; return 1
}
seed "$TMP/b9" bgsess "$OLD"
for _ in 1 2 3; do
  bg_burst 16 "$TMP/b9" PreToolUse '{"session_id":"bbbbbbbb-bg-test","transcript_path":"/nonexistent/bg.jsonl","cwd":"/tmp","tool_name":"Bash"}' "${JOB[@]}"
  check test "$(jq -r .sessionId "$TMP/b9/bgsess" 2>/dev/null)" = bbbbbbbb-bg-test
done
check test -z "$(ls -A "$TMP/b9" | grep '\.tmp$')"

if [ -z "${TMUX:-}" ] || [ -z "${TMUX_PANE:-}" ]; then
  echo "SKIP test-claude-title cases 1-10: not inside a tmux pane (the hook resolves the session name via tmux)"
  echo "test-claude-title: $pass passed, $fail failed"
  [ "$fail" -eq 0 ]
  exit
fi
SESS="$(tmux display-message -p -t "$TMUX_PANE" '#{session_name}')"
[ -n "$SESS" ] || { echo "FAIL test-claude-title: tmux gave no session name"; exit 1; }
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

# 5. Claude Code 2.1.300's bg-pty-host hands the hook NO $TMUX/$TMUX_PANE. The
#    hook recovers them from the nearest ancestor that has them (this script's
#    shell) because that pane's own process is one of its ancestors.
run_hook "$TMP/s5" -u CLAUDECODE -u TMUX -u TMUX_PANE
check test "$(jq -r .sessionId "$TMP/s5/$SESS" 2>/dev/null)" = "11111111-2222-test"

# 6. ...but never from a pane that is NOT its own: an ancestor whose TMUX_PANE
#    names another pane (a daemon that served someone else) writes nothing.
OTHER="$(tmux new-window -d -P -F '#{pane_id}' 'sleep 30')"
if [ -n "$OTHER" ]; then
  env TMUX_PANE="$OTHER" HUGINN_CLAUDE_STATE_DIR="$TMP/s6" PAYLOAD="$PAYLOAD" HOOK="$HOOK" bash -c \
    'env -u TMUX -u TMUX_PANE -u CLAUDECODE "$0/claude" -c "printf %s \"\$PAYLOAD\" | \"\$HOOK\" Stop"; true' "$TMP"
  # (`; true` keeps that bash alive as the ancestor holding the other pane:
  # a lone command would be exec'd in its place and the walk would never see it.)
  check test ! -e "$TMP/s6"
  tmux kill-pane -t "$OTHER" 2>/dev/null
else
  fail=$((fail + 1)); echo "  FAIL: could not open a second pane for case 6"
fi

# 7. The native binary runs as claude.exe: a nested one is still recognised.
ln -s /bin/bash "$TMP/claude.exe"
env CLAUDECODE=1 HUGINN_CLAUDE_STATE_DIR="$TMP/s7" PAYLOAD="$PAYLOAD" HOOK="$HOOK" \
  "$TMP/claude.exe" -c 'printf "%s" "$PAYLOAD" | "$HOOK" Stop'
check test ! -e "$TMP/s7"

# 8-10. A compaction puts back the state it found. $1 = state dir, then the
#    events in order (an event may carry a trigger as EVENT:trigger).
events() {
  local dir="$1"; shift
  local ev trig
  for ev in "$@"; do
    trig="${ev#*:}"; [ "$trig" = "$ev" ] && trig=auto
    env -u CLAUDECODE HUGINN_CLAUDE_STATE_DIR="$dir" HOOK="$HOOK" EV="${ev%%:*}" \
      PAYLOAD="{\"session_id\":\"11111111-2222-test\",\"transcript_path\":\"/nonexistent/t.jsonl\",\"cwd\":\"/tmp\",\"trigger\":\"$trig\"}" \
      "$TMP/claude" -c 'printf "%s" "$PAYLOAD" | "$HOOK" "$EV"'
  done
}
# 8. Compacted while idle: no turn before or after, so it ends idle.
events "$TMP/s8" Stop PreCompact SessionStart PostCompact
check test "$(jq -r .state "$TMP/s8/$SESS" 2>/dev/null)" = idle
check test ! -e "$TMP/s8/.compacting/$SESS"
# 9. Mid-turn: the turn is still running after it.
events "$TMP/s9" UserPromptSubmit PreToolUse PreCompact SessionStart PostCompact
check test "$(jq -r .state "$TMP/s9/$SESS" 2>/dev/null)" = running
# 10. A manual /compact runs between turns, even if the prompt hook fired for it.
events "$TMP/s10" Stop UserPromptSubmit PreCompact:manual SessionStart PostCompact
check test "$(jq -r .state "$TMP/s10/$SESS" 2>/dev/null)" = idle

echo "test-claude-title: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
