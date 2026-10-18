#!/usr/bin/env bash
# Redeploy huginn-appd from this checkout: the test suite as a gate, then
# install.sh (the same install a new server gets from server/setup.sh), then the
# GitHub release mirror. For a first install use server/setup.sh.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST=/opt/huginn-appd

# The suite is the gate, not `node --check`. Syntax alone says nothing about a lost
# import or a broken detectPrompt/transcript reader — and those ping healthy while
# silently blinding both clients, which is the exact bug this repo shipped twice.
# It runs in ~2s and both CLIENT release scripts already gate on it; the script that
# puts the DAEMON into production had no reason to be the weakest of the three.
# The COUNT is asserted, not just the exit code: `node --test` with a glob matching
# nothing exits 0 having run zero tests, so a moved test/ would turn this green.
# ---- leaked test daemons ----------------------------------------------------
# A huginn-appd.js running from anywhere but $DEST is a TEST daemon some earlier
# run left behind. Every route suite binds a port out of a pid-derived range, and
# a leaked daemon holding one refuses the suite's own token — /v1/ping is
# authenticated like every other route, so the suite's start loop never sees its
# 200 and then every call 401s, and the file that collides with it fails
# wholesale with `401 unauthorized`. Twenty-five routes-headroom tests went that
# way on 2026-09-15, and it reads like a code bug for as long as it takes someone
# to think of running `ss`. Named and REFUSED, never killed: this script does not
# get to decide that another session's daemon is disposable.
leaked_appd() {
  # `pgrep -x node`, not `pgrep -f huginn-appd.js`: the latter also matches any
  # shell whose own command line happens to contain the string — including, on a
  # bad day, this script's.
  local pid cmd where
  for pid in $(pgrep -x node 2>/dev/null || true); do
    cmd="$({ tr '\0' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
    case " $cmd " in *"huginn-appd.js "*) ;; *) continue ;; esac
    case " $cmd " in *" $DEST/huginn-appd.js "*) continue ;; esac
    where="$(ss -ltnp 2>/dev/null | grep "pid=$pid," | awk '{print $4}' | tr '\n' ' ')"
    echo "  pid $pid  listening on ${where:-<nothing>}  $cmd"
  done
}
LEAKED="$(leaked_appd)"
if [ -n "$LEAKED" ]; then
  echo "[deploy] REFUSING: a leaked test daemon is running and will fail whichever suite collides with it:" >&2
  echo "$LEAKED" >&2
  echo "[deploy]          kill it yourself (it may be another session's), then re-run." >&2
  exit 1
fi

# Which FILE failed, from node's own TAP: a failing test carries the absolute
# path in its `location:` (node emits that line for failures only), and a file
# whose before/after hook blew up is named by the `not ok` line itself.
failed_files() {
  {
    sed -nE "s|^not ok [0-9]+ - (/.*\.test\.js)$|\1|p" "$1"
    sed -nE "s|^[[:space:]]*location: '(/[^']*\.test\.js):[0-9]+:[0-9]+'.*|\1|p" "$1"
  } | sort -u
}

TEST_LOG="$(mktemp)"
if ! node --test "$SRC"/test/*.test.js > "$TEST_LOG" 2>&1; then
  # ONE flaky FILE gets ONE more chance, and only when it is the only one that
  # failed. Four suites have each failed exactly once on this host while parallel
  # gradle builds held it at load 11-23 on eight cores, each passing alone
  # straight afterwards — and every refusal costs a ten-minute rerun of a tree
  # that was green. Two failing files, a file we cannot name, or a second failure
  # of the same one is refused exactly as before. The retry is never repeated and
  # the pass COUNT below still comes from the FULL run.
  RETRY=""; RETRY_N=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    RETRY_N=$((RETRY_N + 1)); RETRY="$f"
  done <<EOF
$(failed_files "$TEST_LOG")
EOF
  if [ "$RETRY_N" = 1 ] && [ -f "$RETRY" ]; then
    echo "[deploy] one test file failed — re-running $RETRY alone, once."
    RETRY_LOG="$(mktemp)"
    if node --test "$RETRY" > "$RETRY_LOG" 2>&1; then
      echo "[gate] retried once: $RETRY"
      rm -f "$RETRY_LOG"
    else
      tail -40 "$RETRY_LOG"
      echo "[deploy] REFUSING: appd tests failed twice on $RETRY (retry log: $RETRY_LOG)" >&2; exit 1
    fi
  else
    tail -40 "$TEST_LOG"
    echo "[deploy] REFUSING: appd tests failed (${RETRY_N} file(s); full log: $TEST_LOG)" >&2; exit 1
  fi
fi
# The FULL run's count, never the retry's: a floor met by one file re-run alone
# would be no floor at all.
PASSED="$(grep -oE '^# pass [0-9]+' "$TEST_LOG" | grep -oE '[0-9]+' || echo 0)"
# ...except that after a successful single-file retry every '# fail' in the full
# run belonged to that file and has just been proved passing, so it is credited
# back — from the full run's own numbers, never the retry's. Without this a
# flake in any file bigger than the floor's margin refused a green tree
# (build.sh had the same gap; 1733 vs a floor of 1740 on 2026-09-19).
if [ -n "${RETRY:-}" ]; then
  PASSED=$((PASSED + $(grep -oE '^# fail [0-9]+' "$TEST_LOG" | grep -oE '[0-9]+' || echo 0)))
fi
rm -f "$TEST_LOG"
. "$(cd "$SRC/../.." && pwd)/scripts/test-floors.env"
[ "${PASSED:-0}" -ge "$APPD_MIN" ] || { echo "[deploy] REFUSING: appd tests ran $PASSED, expected >= $APPD_MIN" >&2; exit 1; }
echo "[deploy] appd tests: $PASSED passed"

# lib/*.js is covered by the suite above; the entry point is only spawned by it, so
# a syntax error there would surface as a readiness-loop timeout rather than a line
# number. Keep the cheap, precise check.
node --check "$SRC/huginn-appd.js"

# Everything from here to a healthy daemon is install.sh: the same steps a new
# server runs from server/setup.sh (files to /opt, token, unit + drop-ins, the
# Claude Code hooks, restart, loopback HTTP + HTTPS ping). One copy of the steps,
# so a fresh install and a redeploy cannot drift apart.
bash "$SRC/install.sh"

# Mirror the release to GitHub, like ship.sh and release-desktop.sh already do.
# Without this appd was the only component whose public Releases page did not track
# what is actually deployed -- it silently reached 2.59.1 while appd-v2.55.0 was the
# newest published. Best-effort by design: the daemon is already live and healthy
# above, so a GitHub hiccup must not fail a good deploy. Skip: HUGINN_NO_GH_RELEASE=1.
if [ "${HUGINN_NO_GH_RELEASE:-}" != 1 ]; then
  VER="$(grep -m1 "^const VERSION" "$SRC/huginn-appd.js" | sed "s/.*'\(.*\)'.*/\1/")"
  # We install from the working tree, but github-release.sh tags HEAD. Publishing
  # from a dirty appd tree would point appd-vX.Y.Z at bits that were never deployed
  # — the exact drift this publish step exists to remove.
  # `git diff HEAD` sees modifications but NOT untracked files, so a brand-new
  # lib/*.js would be installed and then tagged as if it were in the commit.
  # --porcelain covers both.
  if [ -n "$(git -C "$SRC" status --porcelain -- "$SRC" 2>/dev/null)" ]; then
    echo "[deploy] appd tree is dirty — deployed, but NOT publishing a release for $VER (commit first)." >&2
    VER=""
  fi
  REPO_ROOT="$(cd "$SRC/../.." && pwd)"
  if [ -n "$VER" ] && "$REPO_ROOT/scripts/github-release.sh" appd "$VER"; then
    echo "[deploy] GitHub release appd-v$VER updated"
  else
    echo "[deploy] WARNING: GitHub release failed for appd ${VER:-?} — the deploy above is unaffected." >&2
    echo "[deploy]          Most likely server/appd/CHANGELOG.md has no '## $VER' section: write the notes, then re-run." >&2
  fi
fi
