#!/usr/bin/env bash
# Huginn Server setup: turn a fresh Debian or Ubuntu machine into a Huginn server.
#
#   sudo bash server/setup.sh          (or: curl -fsSL .../server/get.sh | sudo bash)
#
# Installs Node.js, Claude Code, tmux and the host tools, the tuned tmux config,
# the terminal-title hooks, and huginn-appd (the daemon the clients pair with),
# then prints what to do next. Safe to re-run: it is also how a server is updated.
#
#   HUGINN_WORKDIR=/path   where sessions and chats start (default: root's home)
#   HUGINN_NO_DAEMON=1     terminal-only server: skip huginn-appd
#   HUGINN_FORCE=1         overwrite host tools that were edited in place
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "[huginn] run as root (or with sudo):  sudo bash server/setup.sh" >&2
  exit 1
fi

echo "[huginn] installing base packages..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
# python3: the host tools that render reports (headroom, rounds, archive,
# devices) are python. openssl: the daemon's token and TLS certificate.
# iproute2/procps: ss and pgrep, used by the install checks.
apt-get install -y -q curl git tmux ca-certificates jq locales python3 openssl iproute2 procps

# locale (silence the perl warnings)
if ! locale -a 2>/dev/null | grep -qi 'en_US.utf8'; then
  sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen 2>/dev/null || true
  locale-gen en_US.UTF-8 >/dev/null 2>&1 || true
fi

# Node.js 22 (NodeSource) when there is none, or an older one than the daemon
# is tested on.
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if [ "$NODE_MAJOR" -lt 22 ]; then
  echo "[huginn] installing Node.js 22..."
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -q nodejs
fi

# Claude Code
if ! command -v claude >/dev/null 2>&1; then
  echo "[huginn] installing Claude Code..."
  npm install -g @anthropic-ai/claude-code
fi
# ccusage: the token and cost report behind `huginn usage` and the apps' usage view.
if ! command -v ccusage >/dev/null 2>&1; then
  echo "[huginn] installing ccusage..."
  npm install -g ccusage >/dev/null 2>&1 || echo "[huginn] ccusage did not install - 'huginn usage' will be unavailable" >&2
fi

# server scripts — never a SILENT downgrade. server/bin has drifted behind
# /usr/local/bin before: for five weeks the repo's cc was the older file, so this
# step (the one provision/generic-host.md tells a new host to run) would have
# reverted three fixes that were live and working: exact tmux targeting, lowercased
# session names, and claude-as-the-window's-first-program. A setup script that
# quietly makes a working host worse is worse than one that stops and asks.
# Content is compared first, so a re-run after a normal install is a no-op, not a
# warning: `install` stamps the destination mtime, which would otherwise make
# every installed file look "newer" forever.
SKIPPED=
install_script() {   # $1 = src  $2 = dst  [$3 = mode, default 0755]
  local src="$1" dst="$2"
  if [ -e "$dst" ] && ! cmp -s "$src" "$dst"; then
    if [ "$dst" -nt "$src" ] && [ -z "${HUGINN_FORCE:-}" ]; then
      echo "[huginn] SKIP $dst - the installed copy differs and is NEWER than $src" >&2
      SKIPPED="$SKIPPED $dst"
      return 0
    fi
    echo "[huginn] replacing $dst (differs from $src)"
  fi
  install -m "${3:-0755}" "$src" "$dst"
}
install_script "$HERE/bin/cc"                  /usr/local/bin/cc
install_script "$HERE/bin/huginn-status"       /usr/local/bin/huginn-status
install_script "$HERE/bin/huginn-rounds"       /usr/local/bin/huginn-rounds
install_script "$HERE/bin/huginn-devices"      /usr/local/bin/huginn-devices
# `huginn headroom` in both clients is only an ssh to THIS file, so a host that
# skips it answers the verb with "command not found" from a shell the operator
# never asked for. It is installed as a COPY like its siblings (huginn-sync does
# not carry /usr/local/bin), which is the known drift [8/8] in test-client.sh
# exists to catch.
install_script "$HERE/bin/huginn-headroom"     /usr/local/bin/huginn-headroom
install_script "$HERE/bin/huginn-archive"      /usr/local/bin/huginn-archive
# `huginn projects` in both clients is only an ssh to THIS file, the same as
# headroom above, so a host that skips it answers the verb with "command not
# found" from a shell the operator never asked for. It is node rather than the
# bash+python of its siblings because it POSTs JSON built out of argv.
install_script "$HERE/bin/huginn-projects"     /usr/local/bin/huginn-projects
install_script "$HERE/bin/huginn-claude-title" /usr/local/bin/huginn-claude-title
install_script "$HERE/bin/huginn-llm"          /usr/local/bin/huginn-llm
install_script "$HERE/bin/huginn-ask"          /usr/local/bin/huginn-ask
# The state table every renderer above reads its marks, words and colour slots
# from: server/lib/state-table.json and the readers generated from it (bash,
# python, node). Beside the renderers in the tree; here on a host, where they
# look for it second. Data, not programs: 0644.
install -d /usr/local/lib/huginn
for f in state-table.json state-table.sh state-table.py state-table.js; do
  install_script "$HERE/lib/$f" "/usr/local/lib/huginn/$f" 0644
done

# tmux config — installed for root by default; for a non-root login user, copy
# server/tmux.conf to that user's ~/.tmux.conf instead. (Ships allow-passthrough on,
# which the tab-title hook needs to push state out through tmux.)
# Someone who has carried the same tmux bindings for years should not lose them to
# a documented setup step, so an existing config that differs is kept alongside.
# Same idempotency rule as the hook merge below: identical content backs up nothing.
TARGET_HOME="${HUGINN_HOME:-/root}"
TMUXRC="$TARGET_HOME/.tmux.conf"
if [ -e "$TMUXRC" ] && ! cmp -s "$HERE/tmux.conf" "$TMUXRC"; then
  cp -p "$TMUXRC" "$TMUXRC.pre-huginn"
  echo "[huginn] your existing $TMUXRC was saved as $TMUXRC.pre-huginn"
fi
cp "$HERE/tmux.conf" "$TMUXRC"

# Terminal-title hooks — merge server/claude-hooks.json into the target user's
# ~/.claude/settings.json so the tab title reflects Claude's state (running / needs
# you / waiting). Idempotent: strips any prior huginn-claude-title hooks first, and
# preserves every other hook already configured. Skipped if jq is unavailable.
if command -v jq >/dev/null 2>&1; then
  echo "[huginn] wiring terminal-title hooks into $TARGET_HOME/.claude/settings.json ..."
  mkdir -p "$TARGET_HOME/.claude"
  SETTINGS="$TARGET_HOME/.claude/settings.json"
  [ -s "$SETTINGS" ] || echo '{}' > "$SETTINGS"
  jq 'del(._comment) | with_entries(.value |= map(.hooks |= map(.command |= ("/usr/local/bin/" + .))))' \
     "$HERE/claude-hooks.json" > "$SETTINGS.huginn-add"
  jq --slurpfile add "$SETTINGS.huginn-add" '
    .hooks = ( (.hooks // {})
      | with_entries( .value |= ( map( .hooks |= map(select((.command // "") | test("huginn-claude-title") | not)) )
                                  | map(select((.hooks | length) > 0)) ) )
      | with_entries(select((.value | length) > 0)) )
    | reduce ($add[0] | to_entries[]) as $e (.;
        .hooks[$e.key] = ((.hooks[$e.key] // []) + $e.value) )
  ' "$SETTINGS" > "$SETTINGS.huginn-new" && mv "$SETTINGS.huginn-new" "$SETTINGS"
  rm -f "$SETTINGS.huginn-add"
else
  echo "[huginn] jq not found - skipping terminal-title hooks (install jq + re-run, or merge server/claude-hooks.json by hand)." >&2
fi

# huginn-appd: the daemon huginn core, the console and the desktop and Android
# apps pair with. Skipped for a terminal-only server.
if [ "${HUGINN_NO_DAEMON:-}" != 1 ]; then
  echo "[huginn] installing huginn-appd..."
  HUGINN_WORKDIR="${HUGINN_WORKDIR:-}" bash "$HERE/appd/install.sh"
fi

echo
echo "[huginn] done."
echo "   node   = $(node -v)"
echo "   claude = $(claude --version 2>/dev/null | head -1)"
if [ -n "$SKIPPED" ]; then
  echo
  echo "[huginn] NOT installed, the copy on this machine was edited and is newer:$SKIPPED" >&2
  echo "[huginn] compare each with $HERE/bin/, or re-run with HUGINN_FORCE=1 to overwrite them." >&2
fi

ADDRS="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -v -e '^$' -e '^127\.' -e '^fe80' | head -3 | paste -sd' ')"
echo
echo "Next steps:"
echo "  1. Sign Claude Code in, once:   claude"
echo "     (a Claude Pro or Max subscription, or set ANTHROPIC_API_KEY)"
echo "  2. On each computer you will use, install the Huginn client:"
echo "       Linux, Termux:  curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh"
echo "       Windows:        irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1 | iex"
echo "     When it asks for the server, give this machine's address: ${ADDRS:-<the IP address or name of this machine>}"
if [ "${HUGINN_NO_DAEMON:-}" != 1 ]; then
  echo "  3. Pairing asks for the access token. Print it here with:   cat /etc/huginn-appd/token"
  echo "     It grants full control of this machine: paste it only into your own devices."
  echo
  echo "Clients reach this server on port 22 (SSH) and 8787 (huginn-appd, HTTPS)."
  echo "Keep both on your own network or VPN; do not expose them to the internet."
else
  echo
  echo "Terminal-only server (HUGINN_NO_DAEMON=1): clients reach it over SSH, port 22."
fi
