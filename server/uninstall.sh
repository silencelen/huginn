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
SETTINGS="${HUGINN_HOME:-/root}/.claude/settings.json"

if systemctl list-unit-files huginn-appd.service >/dev/null 2>&1; then
  systemctl disable --now huginn-appd >/dev/null 2>&1 && say "stopped and disabled huginn-appd"
fi

# The daemon's hooks, one script at a time (install-hooks.js manages entries by
# the script they run), then the terminal-title hooks setup.sh merged in.
if [ -f /opt/huginn-appd/install-hooks.js ] && [ -f "$SETTINGS" ] && command -v node >/dev/null 2>&1; then
  for h in huginn-headroom-gate huginn-permission-hook huginn-modelswitch-hook; do
    node /opt/huginn-appd/install-hooks.js --settings "$SETTINGS" --script "/opt/huginn-appd/hooks/$h" --uninstall >/dev/null
  done
  say "removed the daemon's Claude Code hooks from $SETTINGS"
fi
if [ -f "$SETTINGS" ] && command -v jq >/dev/null 2>&1; then
  jq '
    if .hooks then
      .hooks |= ( with_entries( .value |= ( map( .hooks |= map(select((.command // "") | test("huginn-claude-title") | not)) )
                                            | map(select((.hooks | length) > 0)) ) )
                  | with_entries(select((.value | length) > 0)) )
    else . end' "$SETTINGS" > "$SETTINGS.huginn-new" && mv "$SETTINGS.huginn-new" "$SETTINGS"
  say "removed the terminal-title hooks from $SETTINGS"
fi

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
