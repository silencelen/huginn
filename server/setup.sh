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

# server scripts
install -m 0755 "$HERE/bin/cc"            /usr/local/bin/cc
install -m 0755 "$HERE/bin/huginn-status" /usr/local/bin/huginn-status

# tmux config — installed for root by default; for a non-root login user, copy
# server/tmux.conf to that user's ~/.tmux.conf instead.
TARGET_HOME="${HUGINN_HOME:-/root}"
cp "$HERE/tmux.conf" "$TARGET_HOME/.tmux.conf"

echo
echo "[huginn] done."
echo "   node   = $(node -v)"
echo "   claude = $(claude --version 2>/dev/null | head -1)"
echo
echo "Next steps:"
echo "  1. Run 'claude' once to authenticate (Max/Pro subscription, or set ANTHROPIC_API_KEY)."
echo "  2. Add each device's SSH public key to ~/.ssh/authorized_keys on this host."
echo "  3. (optional) export HUGINN_WORKDIR=/path/to/project in the login shell to set"
echo "     where sessions open (default: \$HOME)."
echo "  4. Install the client command on your devices (see client/)."
