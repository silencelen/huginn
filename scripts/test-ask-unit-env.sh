#!/usr/bin/env bash
# test-ask-unit-env.sh - server/bin/huginn-ask (what `huginn -p` and `-y` run)
# reads the daemon's settings out of its unit exactly, spaces and all.
#
# WHY THIS EXISTS: huginn-ask takes HUGINN_APPD_EXTRA_TOOLS, _PERSONA_FILE and
# _WORKDIR from `systemctl show huginn-appd -p Environment --value`, which
# prints the unit's assignments on one line and puts an assignment with a space
# in it in double quotes. huginn-ask cut that line at every space, so the
# documented Environment="HUGINN_APPD_EXTRA_TOOLS=mcp__memory mcp__github"
# matched nothing: a one-shot was denied the MCP tools the same question in a
# chat gets, and a workdir or persona path with a space in it was lost too.
#
# The line below is what systemctl 255 printed for a unit carrying these
# assignments (captured through a mock systemd on a private bus, not written
# by hand), with this run's scratch directory put where /T was. It also holds
# the cases a parser gets wrong: a variable assigned twice (the last one wins,
# as for the daemon), and another variable whose VALUE contains the text
# "HUGINN_APPD_EXTRA_TOOLS=" after escaped quotes, a $ and a backslash.
#
# A stub systemctl prints it and a stub claude reports what it was given; no
# daemon, unit or Claude Code is involved.
#
# Usage: scripts/test-ask-unit-env.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> server | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1
ASK="$TREE/server/bin/huginn-ask"

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/home" "$T/work dir"
printf 'THE CONFIGURED PERSONA\n' > "$T/my persona.md"

# shellcheck disable=SC2016  # the $ and backslashes are systemd's own output
CAPTURED='HOME=/root NODE_ENV=production "HUGINN_APPD_WORKDIR=/T/work dir" HUGINN_APPD_TELEGRAM_SCRIPT=/root/bin/send-alert.sh HUGINN_APPD_EXTRA_TOOLS=mcp__unit "HUGINN_APPD_EXTRA_TOOLS=mcp__memory mcp__github" "HUGINN_APPD_PERSONA_FILE=/T/my persona.md" "NOTE=say \"hi\" to \$HOME at C:\\x HUGINN_APPD_EXTRA_TOOLS=mcp__decoy"'
printf '%s\n' "${CAPTURED//\/T\//$T/}" > "$T/unit.line"

cat > "$T/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
[ "$*" = "show huginn-appd -p Environment --value" ] && cat "$UNIT_LINE"
exit 0
STUB
# Never the persona text itself: on a real server the fallback files are the
# owner's, and they have no business in a test log.
cat > "$T/bin/claude" <<'STUB'
#!/usr/bin/env bash
echo "cwd=$(pwd)"
while [ $# -gt 0 ]; do
  case "$1" in
    --allowedTools) echo "allowed=$2"; shift ;;
    --append-system-prompt) [ "$2" = "$WANT_PERSONA" ] && echo "persona=configured" || echo "persona=another"; shift ;;
  esac
  shift
done
STUB
chmod +x "$T/bin/systemctl" "$T/bin/claude"

ask() {   # $1 = ask|act, then VAR=value pairs for the login environment -> what claude got
  local mode="$1"; shift
  echo 'a question' | env -u HUGINN_APPD_EXTRA_TOOLS -u HUGINN_APPD_PERSONA_FILE -u HUGINN_WORKDIR \
    UNIT_LINE="$T/unit.line" WANT_PERSONA='THE CONFIGURED PERSONA' HOME="$T/home" \
    PATH="$T/bin:/usr/bin:/bin" "$@" bash "$ASK" "$mode" 2>&1
}

echo "[1/3] the documented two-tool setting reaches -p and -y"
for mode in ask act; do
  OUT="$(ask "$mode")"
  case "$mode" in
    ask) want='allowed=Skill WebFetch WebSearch mcp__memory mcp__github' ;;
    act) want='allowed=Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__memory mcp__github' ;;
  esac
  grep -qxF -- "$want" <<<"$OUT" \
    && ok "$mode: both extra tools, from the last assignment, and nothing from another variable's value" \
    || bad "$mode: claude got $(grep '^allowed=' <<<"$OUT"), want ${want#allowed=}"
done

echo "[2/3] paths with a space in them"
OUT="$(ask ask)"
grep -qxF -- "cwd=$T/work dir" <<<"$OUT" \
  && ok "the turn runs in the unit's HUGINN_APPD_WORKDIR" \
  || bad "the turn ran in $(grep '^cwd=' <<<"$OUT"), want $T/work dir"
grep -qx 'persona=configured' <<<"$OUT" \
  && ok "and its HUGINN_APPD_PERSONA_FILE is the persona" \
  || bad "the persona was $(grep '^persona=' <<<"$OUT" || echo none), want the configured file"

echo "[3/3] what already worked still does"
OUT="$(ask ask HUGINN_APPD_EXTRA_TOOLS='mcp__login')"
grep -qxF -- 'allowed=Skill WebFetch WebSearch mcp__login' <<<"$OUT" \
  && ok "a value in the login environment still comes first" \
  || bad "with HUGINN_APPD_EXTRA_TOOLS in the environment, claude got $(grep '^allowed=' <<<"$OUT")"
printf '%s\n' 'HOME=/root HUGINN_APPD_EXTRA_TOOLS=mcp__memory' > "$T/unit.line"
OUT="$(ask ask)"
grep -qxF -- 'allowed=Skill WebFetch WebSearch mcp__memory' <<<"$OUT" \
  && ok "a one-word value, printed unquoted, is read as before" \
  || bad "an unquoted one-word value gave $(grep '^allowed=' <<<"$OUT")"
: > "$T/unit.line"
OUT="$(ask ask)"
grep -qxF -- 'allowed=Skill WebFetch WebSearch' <<<"$OUT" && grep -qxF -- "cwd=$T/home" <<<"$OUT" \
  && ok "no unit settings: no extra tools, and the turn runs in HOME" \
  || bad "with an empty unit environment: $(tr '\n' '|' <<<"$OUT")"

echo
[ "$FAIL" = 0 ] && echo "ask-unit-env gates: PASS" || { echo "ask-unit-env gates: FAIL" >&2; exit 1; }
