#!/usr/bin/env bash
# test-tmux-backup.sh - server/setup.sh never writes over a backup of somebody's
# tmux config, on the first install or on any update after it.
#
# WHY THIS EXISTS: setup.sh saves an existing ~/.tmux.conf that differs from the
# release's as ~/.tmux.conf.pre-huginn, and re-running it is how a server is
# updated. Every update after a release that changed server/tmux.conf (or after
# the `set -g mouse on` edit that file invites) found the file different again
# and copied it over the backup: the config somebody had carried for years was
# then in neither file, and setup still said it was saved.
#
# Like scripts/test-home-writes.sh, it runs ONLY the marked `huginn-home-writes`
# block of setup.sh, sourced in a subshell against scratch homes under one
# mktemp -d, with two scratch "releases" (the tree's tmux.conf, and the same
# with one line more) standing in for an update. Nothing outside the scratch
# directory is read or written.
#
# Usage: scripts/test-tmux-backup.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

SETUP="$TREE/server/setup.sh"
awk '/^# BEGIN huginn-home-writes/{on=1} on{print} /^# END huginn-home-writes/{if(on)exit}' "$SETUP" > "$T/block.sh"
# The same refusal test-home-writes.sh makes: a block that reached past its own
# step would run apt-get or systemctl from here.
if [ "$(wc -l < "$T/block.sh")" -lt 10 ]; then
  echo "  FAIL  could not cut the huginn-home-writes block out of $SETUP" >&2; exit 1
elif grep -nE 'apt-get|npm |systemctl|install_script|rm -rf|/etc/|/opt/' "$T/block.sh" >&2; then
  echo "  FAIL  the block reaches past HUGINN_HOME - refusing to run it" >&2; exit 1
fi

# Release B is the tree's own tmux.conf; release A is the same with one line
# more, as two releases that changed the file would be.
mkdir -p "$T/relA" "$T/relB"
cp "$TREE/server/claude-hooks.json" "$T/relA/"; cp "$TREE/server/claude-hooks.json" "$T/relB/"
cp "$TREE/server/tmux.conf" "$T/relB/tmux.conf"
{ cat "$TREE/server/tmux.conf"; echo 'set -g status-interval 5   # release A'; } > "$T/relA/tmux.conf"

update() {   # $1 = release dir, $2 = scratch home -> the block's output, as root runs it
  case "$2" in "$T"/*) ;; *) echo "refusing a home outside the scratch dir: $2" >&2; return 99 ;; esac
  ( set -euo pipefail
    umask 022
    say() { echo "[huginn] $*"; }
    # shellcheck disable=SC2034  # both are read by the sourced block
    HERE="$1"
    # shellcheck disable=SC2034
    HUGINN_HOME="$2"
    # shellcheck source=/dev/null
    . "$T/block.sh" ) 2>&1
}
backups() { (cd "$1" && find . -maxdepth 1 -name '.tmux.conf.pre-huginn*' | sort | tr '\n' ' '); }
MINE='set -g prefix C-a   # mine, for years'

echo "[1/4] somebody's own config survives every update after the first"
H="$T/homes/own"; mkdir -p "$H"
printf '%s\n' "$MINE" > "$H/.tmux.conf"
update "$T/relA" "$H" >/dev/null
OUT="$(update "$T/relB" "$H")"; RC=$?
grep -qxF -- "$MINE" "$H/.tmux.conf.pre-huginn" 2>/dev/null \
  && ok "the update leaves .pre-huginn holding the config from before Huginn" \
  || bad "the update (exit $RC) lost it - .pre-huginn now holds: $(head -c 80 "$H/.tmux.conf.pre-huginn" 2>/dev/null) / $OUT"
cmp -s "$T/relA/tmux.conf" "$H/.tmux.conf.pre-huginn.1" && cmp -s "$T/relB/tmux.conf" "$H/.tmux.conf" \
  && grep -qF "was saved as $H/.tmux.conf.pre-huginn.1" <<<"$OUT" \
  && ok "the config it replaced goes to .pre-huginn.1, and it says so" \
  || bad "after the update: backups $(backups "$H")/ said: $OUT"
update "$T/relB" "$H" >/dev/null
[ "$(backups "$H")" = "./.tmux.conf.pre-huginn ./.tmux.conf.pre-huginn.1 " ] \
  && ok "re-running the same release backs up nothing more" \
  || bad "a re-run of the same release left: $(backups "$H")"

echo "[2/4] an edit to Huginn's own config is kept too"
H="$T/homes/edited"; mkdir -p "$H"
update "$T/relB" "$H" >/dev/null
sed -i 's/^# set -g mouse on /set -g mouse on  /' "$H/.tmux.conf"
grep -q '^set -g mouse on' "$H/.tmux.conf" || bad "the scratch edit did not apply (did tmux.conf lose its mouse line?)"
cp "$H/.tmux.conf" "$T/edited-B"
update "$T/relA" "$H" >/dev/null
sed -i 's/^# set -g mouse on /set -g mouse on  /' "$H/.tmux.conf"
cp "$H/.tmux.conf" "$T/edited-A"
update "$T/relB" "$H" >/dev/null
cmp -s "$T/edited-B" "$H/.tmux.conf.pre-huginn" && cmp -s "$T/edited-A" "$H/.tmux.conf.pre-huginn.1" \
  && ok "both edited copies are kept, the first one unchanged by the second update" \
  || bad "after two edited updates: backups $(backups "$H")"

echo "[3/4] a config that is already kept is not kept twice"
H="$T/homes/own"
cp "$H/.tmux.conf.pre-huginn" "$H/.tmux.conf"   # put the old config back by hand
OUT="$(update "$T/relB" "$H")"
[ "$(backups "$H")" = "./.tmux.conf.pre-huginn ./.tmux.conf.pre-huginn.1 " ] \
  && grep -qF "already saved as $H/.tmux.conf.pre-huginn" <<<"$OUT" && cmp -s "$T/relB/tmux.conf" "$H/.tmux.conf" \
  && ok "it says where that copy already is, and installs the release" \
  || bad "the restored config: backups $(backups "$H")/ said: $OUT"

echo "[4/4] a link in the series is refused, not followed"
H="$T/homes/linked"; mkdir -p "$H" "$T/victim"
printf 'VICTIM\n' > "$T/victim/file"
printf '%s\n' "$MINE" > "$H/.tmux.conf"
printf 'an older backup\n' > "$H/.tmux.conf.pre-huginn"
ln -s "$T/victim/file" "$H/.tmux.conf.pre-huginn.1"
OUT="$(update "$T/relB" "$H")"
[ "$(cat "$T/victim/file")" = VICTIM ] && grep -qF "NOT writing $H/.tmux.conf.pre-huginn.1" <<<"$OUT" \
  && ok "nothing is written through the link, and the refusal names it" \
  || bad "the link at .pre-huginn.1: victim now $(cat "$T/victim/file") / said: $OUT"
grep -qxF -- "$MINE" "$H/.tmux.conf" && grep -qx 'an older backup' "$H/.tmux.conf.pre-huginn" \
  && ok "and a config that could not be backed up is not replaced" \
  || bad "the config or the older backup changed without a backup: $(head -c 80 "$H/.tmux.conf")"
# The same for a name in the series that another account owns.
if [ "$(id -u)" = 0 ]; then
  H="$T/homes/foreign"; mkdir -p "$H"
  printf '%s\n' "$MINE" > "$H/.tmux.conf"
  printf 'an older backup\n' > "$H/.tmux.conf.pre-huginn"
  printf 'NOT MINE\n' > "$H/.tmux.conf.pre-huginn.1"; chown 65534:65534 "$H/.tmux.conf.pre-huginn.1"
  OUT="$(update "$T/relB" "$H")"
  [ "$(cat "$H/.tmux.conf.pre-huginn.1")" = 'NOT MINE' ] && grep -qxF -- "$MINE" "$H/.tmux.conf" \
    && grep -qF "NOT writing $H/.tmux.conf.pre-huginn.1: it belongs to uid 65534" <<<"$OUT" \
    && ok "a backup name another account owns is refused the same way" \
    || bad "a foreign .pre-huginn.1: it now says $(cat "$H/.tmux.conf.pre-huginn.1") / said: $OUT"
else
  echo "  SKIP  a backup name another account owns (needs root to chown one)  <-- not a pass" >&2
fi

echo
[ "$FAIL" = 0 ] && echo "tmux-backup gates: PASS" || { echo "tmux-backup gates: FAIL" >&2; exit 1; }
