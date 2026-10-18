#!/usr/bin/env bash
# Remove Huginn Server from this machine.
#
#   sudo bash /usr/local/src/huginn/server/uninstall.sh            keep data and token
#   sudo bash /usr/local/src/huginn/server/uninstall.sh --purge    remove those too
#
# Removes the huginn-appd service, its files and drop-ins, the Claude Code hooks
# setup.sh added, the host tools in /usr/local/bin and the tmux server service
# when no session is running. It leaves alone: Claude Code, Node.js, tmux and the
# other packages, root's tmux config (the one before Huginn is in
# ~/.tmux.conf.pre-huginn), and your sessions' files. --purge also deletes
# /var/lib/huginn-appd (chats, uploads, settings, certificate) and
# /etc/huginn-appd (the access token), which disconnects every paired client.
set -uo pipefail
PURGE=""
case "${1:-}" in
  --purge) PURGE=1 ;;
  "") ;;
  -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "usage: $0 [--purge]" >&2; exit 2 ;;
esac
[ "$(id -u)" -eq 0 ] || { echo "[huginn] run as root: sudo bash $0" >&2; exit 1; }
say() { echo "[huginn] $*"; }
TARGET_HOME="${HUGINN_HOME:-/root}"
SETTINGS="$TARGET_HOME/.claude/settings.json"

if systemctl list-unit-files huginn-appd.service >/dev/null 2>&1; then
  systemctl disable --now huginn-appd >/dev/null 2>&1 && say "stopped and disabled huginn-appd"
fi

# BEGIN huginn-home-writes - the hooks this takes back out of HUGINN_HOME, under
# server/setup.sh's rule and with its helpers, which scripts/test-home-writes.sh
# holds identical to setup.sh's: every read and write there runs as the home's
# owner, so a link that account planted cannot turn this root-run script into a
# root write, and a path that is a symlink or belongs to anybody else is left
# alone, with why. For root's own home, the normal case, nothing changes.
HOME_UID="$(stat -L -c %u -- "$TARGET_HOME" 2>/dev/null)" || HOME_UID=
HOME_USER="$(getent passwd "$HOME_UID" | cut -d: -f1)" || HOME_USER=
HOME_AS=""
HOME_AS_WHY=""
if [ "$HOME_UID" = "$(id -u)" ]; then HOME_AS=self
elif [ -z "$HOME_USER" ]; then HOME_AS_WHY="the home belongs to uid $HOME_UID, which has no account here to write as"
elif command -v runuser >/dev/null 2>&1; then HOME_AS=runuser
elif command -v setpriv >/dev/null 2>&1; then HOME_AS=setpriv
else HOME_AS_WHY="the home belongs to $HOME_USER, and there is neither runuser nor setpriv here to write as $HOME_USER"
fi
HOME_REFUSED=

as_home_owner() {   # "$@", as the account that owns TARGET_HOME
  case "$HOME_AS" in
    self)    "$@" ;;
    runuser) runuser -u "$HOME_USER" -- "$@" ;;
    setpriv) setpriv --reuid="$HOME_UID" --regid="$(id -g "$HOME_USER")" --init-groups -- "$@" ;;
    *)       return 1 ;;
  esac
}

home_path_ok() {    # 0 when $1, a path in TARGET_HOME, may be written; else why not
  local why
  if [ -z "$HOME_AS" ]; then
    why="$HOME_AS_WHY"
  elif [ -L "$1" ]; then
    why="it is a symlink, and nothing in HUGINN_HOME is written through one"
  elif [ -e "$1" ] && [ "$(stat -c %u -- "$1")" != "$HOME_UID" ]; then
    why="it belongs to uid $(stat -c %u -- "$1"), not to ${HOME_USER:-uid $HOME_UID}, the home's owner - chown it, then re-run"
  else
    return 0
  fi
  echo "[huginn] NOT writing $1: $why" >&2
  HOME_REFUSED="$HOME_REFUSED $1"
  return 1
}

home_replace() {    # stdin -> $1, as the owner: a mktemp file beside it, its mode, a rename
  as_home_owner sh -c '
    t="$(mktemp "$1.huginn-new.XXXXXX")" || exit 1
    if cat > "$t" && chmod "$(stat -c %a -- "$1")" "$t" && mv -f -- "$t" "$1"; then exit 0; fi
    rm -f -- "$t"; exit 1' sh "$1"
}

# The daemon's hooks, one script at a time (install-hooks.js manages entries by
# the script they run), then the terminal-title hooks setup.sh merged in.
if { [ -e "$SETTINGS" ] || [ -L "$SETTINGS" ]; } \
   && home_path_ok "$TARGET_HOME/.claude" && home_path_ok "$SETTINGS"; then
  if [ -f /opt/huginn-appd/install-hooks.js ] && [ -f "$SETTINGS" ] && command -v node >/dev/null 2>&1; then
    NODE="$(command -v node)"
    for h in huginn-headroom-gate huginn-permission-hook huginn-modelswitch-hook; do
      as_home_owner "$NODE" /opt/huginn-appd/install-hooks.js --settings "$SETTINGS" --script "/opt/huginn-appd/hooks/$h" --uninstall >/dev/null
    done
    say "removed the daemon's Claude Code hooks from $SETTINGS"
  fi
  if [ -f "$SETTINGS" ] && command -v jq >/dev/null 2>&1; then
    if NEW="$(as_home_owner cat -- "$SETTINGS" | jq '
        if .hooks then
          .hooks |= ( with_entries( .value |= ( map( .hooks |= map(select((.command // "") | test("huginn-claude-title") | not)) )
                                                | map(select((.hooks | length) > 0)) ) )
                      | with_entries(select((.value | length) > 0)) )
        else . end')" \
       && printf '%s\n' "$NEW" | home_replace "$SETTINGS"; then
      say "removed the terminal-title hooks from $SETTINGS"
    else
      say "could not rewrite $SETTINGS - take the huginn-claude-title hooks out of it by hand"
    fi
  fi
fi
# END huginn-home-writes

rm -rf /opt/huginn-appd /etc/systemd/system/huginn-appd.service /etc/systemd/system/huginn-appd.service.d
systemctl daemon-reload
say "removed huginn-appd (/opt/huginn-appd, the unit and its drop-ins)"

# The tmux server the daemon started, only when nothing is running in it.
if systemctl is-active --quiet huginn-tmux.service; then
  if tmux ls >/dev/null 2>&1; then
    say "kept huginn-tmux.service: sessions are still running in it (end them, then: systemctl stop huginn-tmux)"
  else
    systemctl stop huginn-tmux.service && say "stopped huginn-tmux.service"
  fi
fi

for t in cc huginn-status huginn-rounds huginn-devices huginn-headroom huginn-archive \
         huginn-projects huginn-claude-title huginn-llm huginn-ask; do
  rm -f "/usr/local/bin/$t"
done
rm -rf /usr/local/lib/huginn
say "removed the host tools from /usr/local/bin and /usr/local/lib/huginn"

if [ -n "$PURGE" ]; then
  rm -rf /var/lib/huginn-appd /etc/huginn-appd /run/huginn-claude-state
  say "purged /var/lib/huginn-appd and /etc/huginn-appd (data, certificate, token)"
else
  say "kept /var/lib/huginn-appd and /etc/huginn-appd (data and token); --purge removes them"
fi
say "Huginn Server is uninstalled. The source stays in /usr/local/src/huginn; delete it when done."
