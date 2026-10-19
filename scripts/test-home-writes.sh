#!/usr/bin/env bash
# test-home-writes.sh — server/setup.sh and server/uninstall.sh never write
# through a path in HUGINN_HOME that the home's own account controls, and the
# desktop package's purge never deletes through one in anybody's home.
#
# WHY THIS EXISTS: both scripts run as root and write into HUGINN_HOME, which is
# root's home by default but may be a non-root account's. That account controls
# every name under it, and a symlink it planted at .tmux.conf.pre-huginn or
# .claude/settings.json.huginn-new turned the root-run step into a root write
# wherever the link pointed; a settings.json linked to a root-only JSON file came
# back out as a world-readable copy inside the home. The scripts now do those
# reads and writes AS the home's owner and refuse links and other accounts'
# files. This holds them there, and holds root's own home to what it always got.
#
# The desktop .deb's postrm (built by mobile/scripts/release-desktop.sh) is the
# same class with every account at once: on purge it walks each home as root and
# removes the app's files, and a link planted at .config or .cache turned that
# into root deleting wherever the link pointed. [6/6] runs it against scratch
# homes owned by scratch uids.
#
# It runs ONLY the marked `huginn-home-writes` block of each script, sourced in a
# subshell against scratch homes under one mktemp -d, never the scripts
# themselves: nothing here touches /root/.claude, /etc, /usr or /opt. The
# cross-account cases need root (a home that belongs to `nobody`) and SKIP
# loudly without it.
#
# Usage: scripts/test-home-writes.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server | tar -x -C DIR) to see the gate fail there; a
#   tree without the block markers is cut at the old anchors instead.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
# Traversable, so a home inside it can be reached by the account that owns it.
chmod 0711 "$T"

# ---------------------------------------------------------------- the blocks
mark_block() {  # $1 = file -> its huginn-home-writes block
  awk '/^# BEGIN huginn-home-writes/{on=1} on{print} /^# END huginn-home-writes/{if(on)exit}' "$1"
}
SETUP="$TREE/server/setup.sh"
UNINSTALL="$TREE/server/uninstall.sh"
if grep -q '^# BEGIN huginn-home-writes' "$SETUP"; then
  mark_block "$SETUP" > "$T/setup-block.sh"
else
  awk '/^# tmux config/{on=1} /^# huginn-appd: the daemon/{exit} on{print}' "$SETUP" > "$T/setup-block.sh"
fi
# The daemon's install-hooks.js, and the hooks it is pointed at, from a scratch
# copy - never the deployed /opt/huginn-appd.
OPT="$T/opt/huginn-appd"
mkdir -p "$OPT/hooks"; chmod 0755 "$T/opt" "$OPT" "$OPT/hooks"
install -m 0644 "$TREE/server/appd/install-hooks.js" "$OPT/install-hooks.js"
for h in huginn-headroom-gate huginn-permission-hook huginn-modelswitch-hook; do
  printf '#!/bin/sh\nexit 0\n' > "$OPT/hooks/$h"; chmod 0755 "$OPT/hooks/$h"
done
{
  grep -E '^(TARGET_HOME|SETTINGS)=' "$UNINSTALL"
  if grep -q '^# BEGIN huginn-home-writes' "$UNINSTALL"; then
    mark_block "$UNINSTALL"
  else
    awk "/^# The daemon's hooks/{on=1} /^rm -rf \\/opt\\/huginn-appd/{exit} on{print}" "$UNINSTALL"
  fi
} | sed "s#/opt/huginn-appd#$OPT#g" > "$T/uninstall-block.sh"

echo "[1/6] the blocks are what this gate thinks they are"
for b in setup-block.sh uninstall-block.sh; do
  # A block that reached past its own step would run apt-get or systemctl from
  # here; refuse to source anything that looks like it.
  if [ "$(wc -l < "$T/$b")" -lt 10 ]; then
    bad "$b: could not cut the block out of the script (markers moved?)"
  elif grep -nE 'apt-get|npm |systemctl|install_script|rm -rf|/etc/|/opt/' "$T/$b" | grep -v "$T" >&2; then
    bad "$b reaches past HUGINN_HOME - refusing to run it"
  else
    ok "$b: $(wc -l < "$T/$b") lines, nothing outside HUGINN_HOME"
  fi
done
[ "$FAIL" = 0 ] || { echo "home-writes gates: FAIL" >&2; exit 1; }

run_block() {   # $1 = block, $2 = scratch home -> the block's output, as root runs it
  case "$2" in "$T"/*) ;; *) echo "refusing a home outside the scratch dir: $2" >&2; return 99 ;; esac
  ( set -euo pipefail
    umask 022
    say() { echo "[huginn] $*"; }
    # shellcheck disable=SC2034  # both are read by the sourced block
    HERE="$TREE/server"
    # shellcheck disable=SC2034
    HUGINN_HOME="$2"
    # shellcheck source=/dev/null
    . "$T/$1" ) 2>&1
}
command -v jq >/dev/null 2>&1 || { skip "every check (no jq here; both scripts skip the hook merge without it)"; exit 0; }
hooks_of() { jq -r '[.hooks[]?[]?.hooks[]?.command] | sort | .[]' "$1" 2>/dev/null; }
WANT_TITLE="$(jq -r '[del(._comment)[][].hooks[].command | "/usr/local/bin/" + .] | sort | .[]' "$TREE/server/claude-hooks.json")"

echo "[2/6] a home this account owns: what it always got"
H="$T/homes/own"; mkdir -p "$H"
OUT="$(run_block setup-block.sh "$H")"; RC=$?
if [ "$RC" = 0 ] && cmp -s "$TREE/server/tmux.conf" "$H/.tmux.conf" \
   && [ "$(hooks_of "$H/.claude/settings.json")" = "$WANT_TITLE" ]; then
  ok "a fresh home gets server/tmux.conf and exactly the title hooks"
else
  bad "a fresh home (exit $RC): $OUT"
fi
[ "$(stat -c %a "$H/.tmux.conf" 2>/dev/null)" = 644 ] && [ "$(stat -c %a "$H/.claude/settings.json" 2>/dev/null)" = 644 ] \
  && ok "both written 0644, as a plain copy under umask 022 always was" \
  || bad "modes: .tmux.conf $(stat -c %a "$H/.tmux.conf" 2>/dev/null), settings.json $(stat -c %a "$H/.claude/settings.json" 2>/dev/null)"
cp "$H/.claude/settings.json" "$T/first.json"
OUT="$(run_block setup-block.sh "$H")"; RC=$?
cmp -s "$T/first.json" "$H/.claude/settings.json" && [ ! -e "$H/.tmux.conf.pre-huginn" ] \
  && ok "a second run changes nothing and backs nothing up" \
  || bad "the second run (exit $RC) changed something: $OUT"
LEFT="$(cd "$H" && find . -name '*.huginn-*' -o -name '*.huginn.tmp' | head -3)"
[ -z "$LEFT" ] && ok "no temp file is left behind" || bad "left behind: $LEFT"
# Somebody's own config and hooks: kept alongside, and kept in place.
printf 'set -g mouse off  # mine\n' > "$H/.tmux.conf"
printf '{"theme":"dark","hooks":{"Stop":[{"hooks":[{"type":"command","command":"/usr/local/bin/huginn-claude-title stale"},{"type":"command","command":"/home/me/mine.sh"}]}]}}\n' > "$H/.claude/settings.json"
chmod 0600 "$H/.claude/settings.json"
OUT="$(run_block setup-block.sh "$H")"; RC=$?
grep -q 'mouse off  # mine' "$H/.tmux.conf.pre-huginn" 2>/dev/null && cmp -s "$TREE/server/tmux.conf" "$H/.tmux.conf" \
  && ok "a different tmux config is saved as .tmux.conf.pre-huginn, then replaced" \
  || bad "the existing tmux config (exit $RC): $OUT"
S="$H/.claude/settings.json"
if [ "$(jq -r .theme "$S" 2>/dev/null)" = dark ] && hooks_of "$S" | grep -qx /home/me/mine.sh \
   && [ "$(hooks_of "$S" | grep -v /home/me/mine.sh)" = "$WANT_TITLE" ]; then
  ok "the merge keeps their settings and hooks and replaces the stale title hook"
else
  bad "the merge: $(tr -d '\n' < "$S" | head -c 300)"
fi
[ "$(stat -c %a "$S")" = 600 ] \
  && ok "and a settings.json the owner made 0600 stays 0600" \
  || bad "settings.json went from 0600 to $(stat -c %a "$S")"
printf 'not json {\n' > "$S"
OUT="$(run_block setup-block.sh "$H")"; RC=$?
[ "$RC" = 0 ] && [ "$(cat "$S")" = 'not json {' ] && grep -q 'did not merge' <<<"$OUT" \
  && ok "a settings.json jq cannot read is left exactly as it was, and said so" \
  || bad "an unreadable settings.json (exit $RC): $(cat "$S") / $OUT"

echo "[3/6] links planted in the home are refused, not followed"
H="$T/homes/linked"; V="$T/victims"; mkdir -p "$H/.claude" "$V"; chmod 0755 "$V"
for f in tmux new add settings; do printf 'VICTIM %s\n' "$f" > "$V/$f"; done
printf 'set -g mouse off  # mine\n' > "$H/.tmux.conf"
ln -s "$V/tmux" "$H/.tmux.conf.pre-huginn"
ln -s "$V/settings" "$H/.claude/settings.json"
ln -s "$V/new" "$H/.claude/settings.json.huginn-new"
ln -s "$V/add" "$H/.claude/settings.json.huginn-add"
OUT="$(run_block setup-block.sh "$H")"; RC=$?
CHANGED=""
for f in tmux new add settings; do [ "$(cat "$V/$f")" = "VICTIM $f" ] || CHANGED="$CHANGED $f"; done
[ -z "$CHANGED" ] && ok "setup.sh wrote through none of them" || bad "setup.sh wrote through the links to:$CHANGED"
grep -q "NOT writing $H/.tmux.conf.pre-huginn" <<<"$OUT" && grep -q "NOT writing $H/.claude/settings.json" <<<"$OUT" \
  && ok "and named each one it refused" || bad "no refusal named (exit $RC): $OUT"
grep -q 'mouse off  # mine' "$H/.tmux.conf" \
  && ok "a tmux config that could not be backed up is not replaced either" \
  || bad "the tmux config was replaced without its backup"
[ -L "$H/.claude/settings.json" ] && ok "the settings link is still a link (not replaced by a copy)" \
  || bad "settings.json was replaced: $(ls -l "$H/.claude/settings.json")"
# uninstall.sh, on a home of its own: the settings file and the temp name both
# linked out, and the target holding hooks for it to want to take out.
H="$T/homes/linked-un"; mkdir -p "$H/.claude"
printf '{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"/usr/local/bin/huginn-claude-title stop"}]}]}}\n' > "$V/un-settings"
printf 'VICTIM un-new\n' > "$V/un-new"
cp "$V/un-settings" "$T/un-settings.before"
ln -s "$V/un-settings" "$H/.claude/settings.json"
ln -s "$V/un-new" "$H/.claude/settings.json.huginn-new"
OUT="$(run_block uninstall-block.sh "$H")"; RC=$?
cmp -s "$T/un-settings.before" "$V/un-settings" && [ "$(cat "$V/un-new")" = 'VICTIM un-new' ] \
  && [ "$(readlink "$H/.claude/settings.json")" = "$V/un-settings" ] \
  && grep -q "NOT writing $H/.claude/settings.json" <<<"$OUT" \
  && ok "uninstall.sh follows neither link, and says so" \
  || bad "uninstall.sh wrote through a link in the home (exit $RC): $OUT"

echo "[4/6] a home that belongs to another account"
NOBODY="$(getent passwd nobody | cut -d: -f1)"
if [ "$(id -u)" != 0 ] || [ -z "$NOBODY" ] || ! { command -v runuser || command -v setpriv; } >/dev/null 2>&1; then
  skip "cross-account cases (need root, a 'nobody' account and runuser or setpriv)"
else
  mkdir -p "$T/homes"; chmod 0711 "$T/homes"
  newhome() { mkdir -p "$T/homes/$1"; chown "$NOBODY": "$T/homes/$1"; chmod 0755 "$T/homes/$1"; echo "$T/homes/$1"; }
  as_nobody() { if command -v runuser >/dev/null 2>&1; then runuser -u "$NOBODY" -- "$@"; else setpriv --reuid="$(id -u "$NOBODY")" --regid="$(id -g "$NOBODY")" --init-groups -- "$@"; fi; }
  # A root-only secret and a root file the account can read but not write.
  SECRET="ROOT-ONLY-$$-$RANDOM"
  R="$T/rootfiles"; mkdir -p "$R"; chmod 0755 "$R"
  printf '{"secret":"%s"}\n' "$SECRET" > "$R/secret.json"; chmod 0600 "$R/secret.json"
  printf 'ROOT FILE\n' > "$R/file"; chmod 0644 "$R/file"

  H="$(newhome fresh)"
  OUT="$(run_block setup-block.sh "$H")"; RC=$?
  OWNERS="$(stat -c '%U %n' "$H/.tmux.conf" "$H/.claude" "$H/.claude/settings.json" 2>&1 | grep -v "^$NOBODY " || true)"
  [ "$RC" = 0 ] && [ -z "$OWNERS" ] && [ "$(hooks_of "$H/.claude/settings.json")" = "$WANT_TITLE" ] \
    && ok "everything setup.sh makes in the home belongs to the home's owner" \
    || bad "files in $NOBODY's home (exit $RC) not $NOBODY's: ${OWNERS:-none} / $OUT"

  H="$(newhome attack)"
  as_nobody mkdir "$H/.claude"
  as_nobody sh -c 'printf "attacker\n" > "$1/.tmux.conf"' sh "$H"
  as_nobody ln -s "$R/file" "$H/.tmux.conf.pre-huginn"
  as_nobody ln -s "$R/secret.json" "$H/.claude/settings.json"
  OUT="$(run_block setup-block.sh "$H")"; RC=$?
  [ "$(cat "$R/file")" = "ROOT FILE" ] && [ "$(stat -c %a "$R/secret.json")" = 600 ] \
    && grep -q "$SECRET" "$R/secret.json" \
    && ok "setup.sh: the account's links reach no root file" \
    || bad "setup.sh wrote a root file through the account's links (exit $RC): $OUT"
  if grep -rqs -- "$SECRET" "$H"; then bad "setup.sh copied a root-only file into the account's home"
  else ok "and nothing root-only is copied into the home"; fi

  H="$(newhome leftover)"
  as_nobody mkdir "$H/.claude"
  printf '{"left":"by an older setup.sh, as root"}\n' > "$H/.claude/settings.json"
  cp "$H/.claude/settings.json" "$T/leftover.json"
  OUT="$(run_block setup-block.sh "$H")"; RC=$?
  cmp -s "$T/leftover.json" "$H/.claude/settings.json" && grep -q "NOT writing $H/.claude/settings.json: it belongs to uid 0" <<<"$OUT" \
    && ok "a root-owned file in the account's home is refused, with the chown that fixes it" \
    || bad "a root-owned settings.json in $NOBODY's home (exit $RC): $OUT"

  H="$(newhome uninstall)"
  as_nobody mkdir "$H/.claude"
  as_nobody ln -s "$R/secret.json" "$H/.claude/settings.json"
  OUT="$(run_block uninstall-block.sh "$H")"; RC=$?
  if grep -rqs -- "$SECRET" "$H"; then bad "uninstall.sh copied a root-only file into the account's home"
  else ok "uninstall.sh copies nothing root-only into the home"; fi
  H="$(newhome uninstall-plain)"
  as_nobody mkdir "$H/.claude"
  as_nobody sh -c 'printf "%s\n" "$2" > "$1/.claude/settings.json"' sh "$H" \
    '{"keep":1,"hooks":{"Stop":[{"hooks":[{"type":"command","command":"/usr/local/bin/huginn-claude-title stop"}]}]}}'
  OUT="$(run_block uninstall-block.sh "$H")"; RC=$?
  S="$H/.claude/settings.json"
  [ "$(jq -r .keep "$S" 2>/dev/null)" = 1 ] && [ -z "$(hooks_of "$S")" ] && [ "$(stat -c %U "$S")" = "$NOBODY" ] \
    && ok "uninstall.sh still takes the hooks out, writing as the home's owner" \
    || bad "uninstall in $NOBODY's home (exit $RC): $(stat -c '%U' "$S") $(tr -d '\n' < "$S") / $OUT"
fi

echo "[5/5] one rule, one copy of it"
fn() { awk -v f="$1() {" 'index($0, f) == 1 {on=1} on{print} on && /^}$/{exit}' "$2"; }
for f in as_home_owner home_path_ok home_replace; do
  A="$(fn "$f" "$SETUP")"; B="$(fn "$f" "$UNINSTALL")"
  if [ -z "$A" ] || [ -z "$B" ]; then bad "$f is missing from $([ -z "$A" ] && echo setup.sh || echo uninstall.sh)"
  elif [ "$A" != "$B" ]; then bad "$f differs between setup.sh and uninstall.sh"
  else ok "$f is the same in setup.sh and uninstall.sh"; fi
done
# The uninstall checks above ran the daemon's install-hooks.js only when node is here.
command -v node >/dev/null 2>&1 || skip "install-hooks.js under uninstall.sh (no node here)"

echo "[6/6] the desktop package's purge: each home as its owner, through no link"
REL="$TREE/mobile/scripts/release-desktop.sh"
awk "/^cat > \"\\\$DEB_TMP\/hygiene.sh\" <<'HYGIENE_EOF'\$/{on=1; next} /^HYGIENE_EOF\$/{on=0} on" "$REL" > "$T/hygiene.sh"
if [ "$(wc -l < "$T/hygiene.sh")" -lt 10 ] || ! grep -q 'getent passwd' "$T/hygiene.sh"; then
  bad "release-desktop.sh: could not cut the purge block out of it (the HYGIENE_EOF heredoc moved?)"
elif [ "$(id -u)" != 0 ] || ! command -v setpriv >/dev/null 2>&1; then
  skip "the purge (needs root, to own scratch homes as other uids, and setpriv)"
else
  { printf '#!/bin/sh\n'; cat "$T/hygiene.sh"; printf 'exit 0\n'; } > "$T/postrm"
  P="$T/purge"; mkdir -p "$P/bin"; chmod 0711 "$P"
  # Scratch uids with no account here: the passwd lines are this check's own.
  home() {   # $1 = name, $2 = uid -> a home owned by that uid
    mkdir -p "$P/$1"; chown "$2:$2" "$P/$1"; chmod 0755 "$P/$1"; echo "$P/$1"
  }
  plant() {  # $1 = a file to create, with its parents; $2 = what it says (default: kept)
    mkdir -p "$(dirname "$1")"; printf '%s\n' "${2:-kept}" > "$1"
  }
  # 1. an account's own files, in its own home: removed, and nothing else.
  H1="$(home own 61001)"
  for f in .config/huginn-desktop-kt/settings.json .cache/huginn-desktop-kt/x \
           .local/share/applications/huginn-desktop-kt.desktop .config/keep-me/x; do plant "$H1/$f"; done
  plant "$H1/.config/autostart/huginn-desktop-kt.desktop" 'Exec=/opt/huginn-desktop-kt/bin/huginn-desktop-kt'
  chown -R 61001:61001 "$H1"
  # 2. links planted by the account, pointing at root's files.
  H2="$(home linked 61002)"
  plant "$P/victim-config/huginn-desktop-kt/settings.json"
  plant "$P/victim-config/autostart/huginn-desktop-kt.desktop" 'Exec=/opt/huginn-desktop-kt/bin/x'
  plant "$P/victim-local/share/applications/huginn-desktop-kt.desktop"
  plant "$P/victim-cache/x"
  ln -s "$P/victim-config" "$H2/.config"; ln -s "$P/victim-local" "$H2/.local"
  mkdir -p "$H2/.cache"; ln -s "$P/victim-cache" "$H2/.cache/huginn-desktop-kt"
  chown -h 61002:61002 "$H2/.config" "$H2/.local" "$H2/.cache" "$H2/.cache/huginn-desktop-kt"
  # 3. a "home" the account does not own.
  H3="$(home notmine 0)"; plant "$H3/.config/huginn-desktop-kt/settings.json"
  # 4. root's files left in an account's own home: not the account's to delete,
  #    so not deleted (as root, they were).
  H4="$(home rootleft 61004)"; plant "$H4/.config/huginn-desktop-kt/settings.json"
  # 5. root's own home: cleaned as root, still through no link.
  H5="$(home roothome 0)"; plant "$H5/.config/huginn-desktop-kt/settings.json"
  plant "$P/victim-root/huginn-desktop-kt/x"; ln -s "$P/victim-root" "$H5/.cache"
  printf '%s\n' "alex:x:61001:61001::$H1:/bin/sh" "blake:x:61002:61002::$H2:/bin/sh" \
    "casey:x:61003:61003::$H3:/bin/sh" "drew:x:61004:61004::$H4:/bin/sh" "root:x:0:0::$H5:/bin/sh" \
    > "$P/passwd"
  printf '#!/bin/sh\n[ "$1" = passwd ] && cat %q\n' "$P/passwd" > "$P/bin/getent"; chmod 0755 "$P/bin/getent"
  OUT="$(PATH="$P/bin:$PATH" sh "$T/postrm" purge 2>&1)"; RC=$?
  GONE=""; for f in .config/huginn-desktop-kt .cache/huginn-desktop-kt .local/share/applications/huginn-desktop-kt.desktop \
                    .config/autostart/huginn-desktop-kt.desktop; do [ -e "$H1/$f" ] && GONE="$GONE $f"; done
  [ "$RC" = 0 ] && [ -z "$GONE" ] && [ -e "$H1/.config/keep-me/x" ] \
    && ok "an account's own files go, as that account, and nothing beside them" \
    || bad "the purge (exit $RC) left in an account's own home:${GONE:- -} (keep-me $( [ -e "$H1/.config/keep-me/x" ] && echo kept || echo GONE)) / $OUT"
  LOST=""
  for f in victim-config/huginn-desktop-kt/settings.json victim-config/autostart/huginn-desktop-kt.desktop \
           victim-local/share/applications/huginn-desktop-kt.desktop victim-cache/x victim-root/huginn-desktop-kt/x; do
    [ -e "$P/$f" ] || LOST="$LOST $f"
  done
  [ -z "$LOST" ] && ok "a link planted at .config, .local or .cache/huginn-desktop-kt reaches nothing of root's" \
    || bad "the purge deleted root's files through a link in somebody's home:$LOST"
  [ -e "$H3/.config/huginn-desktop-kt/settings.json" ] && ok "a home the account does not own is left alone" \
    || bad "the purge cleaned a home its account does not own"
  [ -e "$H4/.config/huginn-desktop-kt/settings.json" ] \
    && ok "root's own files in an account's home are not deleted for it (the purge acts as the account)" \
    || bad "the purge deleted root's files in an account's home - it acted as root"
  [ ! -e "$H5/.config/huginn-desktop-kt" ] && ok "root's own home is still cleaned" \
    || bad "the purge no longer cleans root's own home"
fi

echo
[ "$FAIL" = 0 ] && echo "home-writes gates: PASS" || { echo "home-writes gates: FAIL" >&2; exit 1; }
