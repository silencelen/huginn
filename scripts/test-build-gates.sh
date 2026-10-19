#!/usr/bin/env bash
# test-build-gates.sh — the build and release scripts keep their scratch files
# private, and every wait in them has an end.
#
# WHY THIS EXISTS: mobile/scripts/build.sh and release-desktop.sh run as root,
# and they locked on and logged to fixed names in shared /tmp: any account on the
# host could create /tmp/huginn-app-gradle.lock first and hold it (the lock had
# no timeout, so every build then waited for ever), or plant the release log's
# name as a link to a file the release would truncate and write into. And the
# daemon's test suite, which gates deploy.sh, build.sh and release-desktop.sh,
# ran with no timeout at all - one hung test held a gate for fourteen hours.
# This holds the scripts to:
#   [1/4] no fixed name in shared /tmp (a mktemp template is not a fixed name);
#   [2/4] the gradle lock lives in the tree, 0700, and a wait on it ends with a
#         sentence that names it - run, against a held lock;
#   [3/4] every `node --test` gate carries --test-timeout;
#   [4/4] a hang fails fast and the retry-once path takes it: run, each gate cut
#         out of its script, against a suite whose one test hangs once. The
#         retried file's tests must all be counted (the full run never saw the
#         ones after the hang), and a file that hangs twice is refused.
#
# Usage: scripts/test-build-gates.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server mobile/scripts scripts | tar -x -C DIR) to see the
#   gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

T="$(mktemp -d)"
HOLDER=""
trap '[ -n "$HOLDER" ] && kill "$HOLDER" 2>/dev/null; rm -rf "$T"' EXIT

BUILD="$TREE/mobile/scripts/build.sh"
REL="$TREE/mobile/scripts/release-desktop.sh"
DEPLOY="$TREE/server/appd/deploy.sh"
GATES=("$DEPLOY" "$BUILD" "$REL")
code_lines() { grep -nv '^[[:space:]]*#' "$1"; }

echo "[1/4] no fixed names in shared /tmp"
for f in "$TREE"/mobile/scripts/*.sh "$DEPLOY"; do
  # An absolute /tmp path or a TMPDIR default, outside a mktemp template ("a/tmp/"
  # inside a build tree is not shared /tmp).
  HITS="$(code_lines "$f" | grep -E "(^|[[:space:]\"'=:(])/tmp([/\"'}[:space:]]|\$)|TMPDIR" | grep -v 'mktemp')"
  [ -z "$HITS" ] && ok "${f#"$TREE"/}: every scratch path comes from mktemp" \
    || bad "${f#"$TREE"/} names a fixed path in shared /tmp: $(head -2 <<<"$HITS" | tr '\n' ' ')"
done

echo "[2/4] the gradle lock: in the tree, private, and never waited on for ever"
for f in "$BUILD" "$REL"; do
  n="${f#"$TREE"/}"
  UNBOUNDED="$(code_lines "$f" | grep -E '(^|[^_[:alnum:]])flock ' | grep -vE 'flock .*-w ')"
  [ -z "$UNBOUNDED" ] && ok "$n: every flock has a -w timeout" \
    || bad "$n: a flock with no timeout: $(head -1 <<<"$UNBOUNDED")"
  # The lock setup and the gradle wrapper, cut out and run in a scratch tree
  # with a stand-in ./gradlew, against a lock somebody else holds.
  W="$T/lock-${n//\//-}"; mkdir -p "$W"
  printf '#!/bin/sh\necho "gradlew ran: $*"\n' > "$W/gradlew"; chmod 755 "$W/gradlew"
  awk '/^LOCK_DIR=/{on=1} on{print} on && /^\}/{exit}' "$f" > "$W/cut.sh"
  if ! grep -q 'gradle_locked()' "$W/cut.sh"; then
    bad "$n: no 'LOCK_DIR=' ... gradle_locked() block to cut out (the lock is not a private, bounded one)"
    continue
  fi
  { echo 'set -euo pipefail'; printf 'REPO_DIR=%q\ncd "$REPO_DIR"\n' "$W"; cat "$W/cut.sh"; } > "$W/run.sh"
  OUT="$(cd "$W" && bash -c '. ./run.sh; gradle_locked :x:test' 2>&1)"; RC=$?
  LOCK="$(find "$W" -name 'gradle.lock' 2>/dev/null | head -1)"
  MODE="$(stat -c %a "$(dirname "${LOCK:-$W/none/x}")" 2>/dev/null)"
  if [ "$RC" != 0 ] || ! grep -q 'gradlew ran: :x:test' <<<"$OUT"; then
    bad "$n: a free lock did not let gradle run (exit $RC): $OUT"
  elif [ -z "$LOCK" ] || [ "${LOCK#"$W"/}" = "$LOCK" ]; then
    bad "$n: the lock is not inside the tree it serialises"
  elif [ "$MODE" != 700 ]; then
    bad "$n: the lock's directory is $MODE, not 0700"
  else
    ok "$n: a free lock runs gradle; the lock is in the tree, in a 0700 directory"
  fi
  [ -n "$LOCK" ] || continue
  # -o: the lock is flock's own, so it is gone the moment flock is.
  flock -o "$LOCK" sleep 15 & HOLDER=$!
  sleep 0.3
  START=$(date +%s)
  OUT="$(cd "$W" && HUGINN_GRADLE_LOCK_WAIT=1 timeout 20 bash -c '. ./run.sh; gradle_locked :x:test' 2>&1)"; RC=$?
  TOOK=$(( $(date +%s) - START ))
  kill "$HOLDER" 2>/dev/null; wait "$HOLDER" 2>/dev/null; HOLDER=""
  if [ "$RC" = 0 ] || grep -q 'gradlew ran' <<<"$OUT"; then
    bad "$n: gradle ran while another build held the lock: $OUT"
  elif [ "$TOOK" -gt 10 ]; then
    bad "$n: the wait on a held lock did not end (${TOOK}s, exit $RC)"
  elif ! grep -qF "$LOCK" <<<"$OUT"; then
    bad "$n: gave up on a held lock (exit $RC) without naming it: $OUT"
  else
    ok "$n: a held lock is given up on after HUGINN_GRADLE_LOCK_WAIT, naming the lock (exit $RC)"
  fi
done

echo "[3/4] every node --test gate is bounded"
for f in "${GATES[@]}" "$TREE/mobile/scripts/ship.sh"; do
  n="${f#"$TREE"/}"
  RUNS="$(code_lines "$f" | grep -E 'node --test( |$)')"
  [ -n "$RUNS" ] || { ok "$n: runs no node --test of its own"; continue; }
  UNB="$(grep -v -- '--test-timeout=' <<<"$RUNS")"
  [ -z "$UNB" ] && ok "$n: $(grep -c . <<<"$RUNS") node --test run(s), each with --test-timeout" \
    || bad "$n: node --test with no timeout: $(head -1 <<<"$UNB")"
done

echo "[4/4] a hang fails fast, the retry takes it, and the count is whole"
if ! command -v node >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
  skip "the gate runs (needs node and timeout)"
else
  # A suite of two files and five tests. b's second test hangs the first time it
  # runs (a flake that hangs), so the full run times out inside b, after one of
  # its three tests, and the retry of b alone passes all three.
  S="$T/suite"; mkdir -p "$S/server/appd/test" "$S/scripts"
  printf 'APPD_MIN=5\n' > "$S/scripts/test-floors.env"
  cat > "$S/server/appd/test/a.test.js" <<'JS'
const test = require('node:test');
test('a one', () => {});
test('a two', () => {});
JS
  cat > "$S/server/appd/test/b.test.js" <<'JS'
const test = require('node:test');
const fs = require('node:fs');
test('b one', () => {});
test('b two', async () => {
  if (process.env.HANG_ALWAYS !== '1' && fs.existsSync(process.env.HANG_ONCE)) return;
  fs.writeFileSync(process.env.HANG_ONCE, '');
  await new Promise(() => { setInterval(() => {}, 1000); });
});
test('b three', () => {});
JS
  # Each gate, cut out of its script: from its failed-file finder to the line
  # that reports the count. Everything else in the scripts needs root, gradle
  # or a deploy target.
  cut_gate() {   # $1 = script -> a runnable gate on stdout
    echo 'set -euo pipefail'
    printf 'SRC=%q APPD_DIR=%q APPD_MIN=5\n' "$S/server/appd" "$S/server/appd"
    echo 'leaked_appd() { :; }'
    case "$1" in
      "$DEPLOY") awk '/^failed_files\(\) \{/{on=1} on{print} on && /appd tests: \$PASSED passed/{exit}' "$1" ;;
      "$BUILD")  awk '/^appd_failed_files\(\) \{/{on=1} on{print} on && /^fi$/ && seen{exit} /server tests: \$NODE_COUNT passed/{seen=1}' "$1" ;;
      "$REL")    awk '/^  appd_failed_files\(\) \{/{on=1} on{print} on && /server tests: \$NODE_COUNT passed/{exit}' "$1" ;;
    esac
  }
  for f in "${GATES[@]}"; do
    n="${f#"$TREE"/}"
    cut_gate "$f" > "$T/gate.sh"
    if ! grep -q 'node --test' "$T/gate.sh"; then bad "$n: could not cut its test gate out (update this check)"; continue; fi
    rm -f "$T/hang-once"
    START=$(date +%s)
    OUT="$(HANG_ONCE="$T/hang-once" HUGINN_APPD_TEST_TIMEOUT_MS=3000 timeout 30 bash "$T/gate.sh" 2>&1)"; RC=$?
    TOOK=$(( $(date +%s) - START ))
    if [ "$RC" = 124 ]; then
      bad "$n: a hung test held the gate until this check killed it (${TOOK}s)"
    elif [ "$RC" != 0 ]; then
      bad "$n: a test that hung once refused the tree (exit $RC): $(tail -3 <<<"$OUT" | tr '\n' ' ')"
    elif ! grep -q 'retried once' <<<"$OUT"; then
      bad "$n: passed without the retry it should have needed: $(tail -3 <<<"$OUT" | tr '\n' ' ')"
    elif ! grep -q ': 5 passed' <<<"$OUT"; then
      bad "$n: the count after the retry is not the five tests the suite holds: $(grep -E 'passed' <<<"$OUT" | tail -2 | tr '\n' ' ')"
    else
      ok "$n: the hang failed in seconds (${TOOK}s in all), the retry passed it, and all 5 tests count"
    fi
    # The same hang every time is not a flake: refused, and quickly.
    START=$(date +%s)
    OUT="$(HANG_ALWAYS=1 HANG_ONCE="$T/hang-once" HUGINN_APPD_TEST_TIMEOUT_MS=3000 timeout 30 bash "$T/gate.sh" 2>&1)"; RC=$?
    TOOK=$(( $(date +%s) - START ))
    if [ "$RC" = 0 ] || [ "$RC" = 124 ]; then
      bad "$n: a test that hangs every time was not refused in time (exit $RC, ${TOOK}s)"
    else
      ok "$n: a test that hangs every time is refused (exit $RC, ${TOOK}s)"
    fi
  done
fi

echo
[ "$FAIL" = 0 ] && echo "build-gates gates: PASS" || { echo "build-gates gates: FAIL" >&2; exit 1; }
