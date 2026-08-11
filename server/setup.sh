#!/usr/bin/env bash
# Huginn server setup — turn a fresh Debian/Ubuntu host into a Huginn node.
# Installs Node + Claude Code + tmux, then the cc launcher, huginn-status, and tmux.conf.
# Run as root (or with sudo) on the host:  sudo bash server/setup.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "[huginn] run as root (or with sudo):  sudo bash server/setup.sh" >&2
  exit 1
fi

echo "[huginn] installing base packages..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q curl git tmux ca-certificates jq locales

# locale (silence the perl warnings)
if ! locale -a 2>/dev/null | grep -qi 'en_US.utf8'; then
  sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen 2>/dev/null || true
  locale-gen en_US.UTF-8 >/dev/null 2>&1 || true
fi

# Node 20+ (NodeSource) if missing
if ! command -v node >/dev/null 2>&1; then
  echo "[huginn] installing Node 20..."
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash - >/dev/null
  apt-get install -y -q nodejs
fi

# Claude Code
if ! command -v claude >/dev/null 2>&1; then
  echo "[huginn] installing Claude Code..."
  npm install -g @anthropic-ai/claude-code
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
install_script() {
  local src="$1" dst="$2"
  if [ -e "$dst" ] && ! cmp -s "$src" "$dst"; then
    if [ "$dst" -nt "$src" ] && [ -z "${HUGINN_FORCE:-}" ]; then
      echo "[huginn] SKIP $dst — the installed copy differs and is NEWER than $src" >&2
      SKIPPED="$SKIPPED $dst"
      return 0
    fi
    echo "[huginn] replacing $dst (differs from $src)"
  fi
  install -m 0755 "$src" "$dst"
}
install_script "$HERE/bin/cc"                  /usr/local/bin/cc
install_script "$HERE/bin/huginn-status"       /usr/local/bin/huginn-status
install_script "$HERE/bin/huginn-claude-title" /usr/local/bin/huginn-claude-title

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
  echo "[huginn] jq not found — skipping terminal-title hooks (install jq + re-run, or merge server/claude-hooks.json by hand)." >&2
fi

echo
echo "[huginn] done."
echo "   node   = $(node -v)"
echo "   claude = $(claude --version 2>/dev/null | head -1)"
if [ -n "$SKIPPED" ]; then
  echo
  echo "[huginn] NOT installed, host copy is newer:$SKIPPED" >&2
  echo "[huginn] diff each against $HERE/bin/ and fold the host's fixes back into the repo," >&2
  echo "[huginn] or re-run with HUGINN_FORCE=1 to overwrite them." >&2
fi
echo
echo "Next steps:"
echo "  1. Run 'claude' once to authenticate (Max/Pro subscription, or set ANTHROPIC_API_KEY)."
echo "  2. Add each device's SSH public key to ~/.ssh/authorized_keys on this host."
echo "  3. (optional) export HUGINN_WORKDIR=/path/to/project in the login shell to set"
echo "     where sessions open (default: \$HOME)."
echo "  4. Install the client command on your devices (see client/)."
