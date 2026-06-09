#!/usr/bin/env bash
# Huginn client installer (bash / Termux).
# Run from the cloned repo:   ./client/install.sh my-host-or-ip
set -e
HHOST="${1:-}"
[ -z "$HHOST" ] && read -rp "Huginn host (IP or DNS name reachable over SSH): " HHOST
HERE="$(cd "$(dirname "$0")" && pwd)"
KEY="$HOME/.ssh/id_ed25519"
mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"

# 1. SSH key
[ -f "$KEY" ] || ssh-keygen -t ed25519 -f "$KEY" -N "" >/dev/null
echo
echo ">>> Authorize THIS key on the Huginn host (append to its ~/.ssh/authorized_keys):"
echo "    $(cat "$KEY.pub")"
echo

# 2. `Host huginn` SSH alias (idempotent)
CFG="$HOME/.ssh/config"; touch "$CFG"; chmod 600 "$CFG"
if ! grep -qE '^[[:space:]]*Host[[:space:]]+huginn[[:space:]]*$' "$CFG"; then
  printf '\nHost huginn\n  HostName %s\n  User root\n  IdentityFile %s\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n' "$HHOST" "$KEY" >> "$CFG"
  echo "Added 'Host huginn' -> $HHOST to $CFG"
fi

# 3. install the command + wire the profile
mkdir -p "$HOME/.huginn"
cp "$HERE/huginn.sh" "$HOME/.huginn/huginn.sh"
RC="$HOME/.bashrc"; touch "$RC"
grep -q '.huginn/huginn.sh' "$RC" || echo '[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh' >> "$RC"
# shellcheck disable=SC1090
source "$HOME/.huginn/huginn.sh"
echo
echo "Installed. Authorize the key above on the host, then:  huginn help  |  huginn status"
