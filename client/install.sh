#!/usr/bin/env bash
# Huginn client installer (bash / Termux).
# Run from the cloned repo:   ./client/install.sh my-host-or-ip
set -e
HHOST="${1:-}"
[ -z "$HHOST" ] && read -rp "Huginn host (IP or DNS name reachable over SSH): " HHOST
HERE="$(cd "$(dirname "$0")" && pwd)"
KEY="$HOME/.ssh/id_ed25519"
HUSER="${HUGINN_USER:-root}"   # SSH user on the host; override: HUGINN_USER=huginn ./install.sh <host>
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
  printf '\nHost huginn\n  HostName %s\n  User %s\n  IdentityFile %s\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n' "$HHOST" "$HUSER" "$KEY" >> "$CFG"
  echo "Added 'Host huginn' -> $HHOST to $CFG"
fi

# 3. install the command + wire the profile
mkdir -p "$HOME/.huginn"
cp "$HERE/huginn.sh" "$HOME/.huginn/huginn.sh"
# The device runner rides along when installing from a clone, so `huginn device
# on` has nothing to fetch. It is inert until this machine is actually enrolled —
# most devices are clients and never offer themselves.
[ -f "$HERE/huginn-device" ] && install -m 0755 "$HERE/huginn-device" "$HOME/.huginn/huginn-device"
RC="$HOME/.bashrc"; touch "$RC"
grep -q '.huginn/huginn.sh' "$RC" || echo '[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh' >> "$RC"
# shellcheck disable=SC1090
source "$HOME/.huginn/huginn.sh"
echo
echo "Installed. Authorize the key above on the host, then:  huginn help  |  huginn status"
echo "This machine may also be able to serve local AI models to huginn (optional, ~5 GB):  huginn local on"
# The base client is bash+ssh and needs no node; only the optional features do.
# Said HERE because the native claude build ships without node, so its absence
# is normal now, not a sign something else is missing.
command -v node >/dev/null 2>&1 || \
  echo "Note: the optional device/local-AI features need Node.js LTS (nodejs.org) — the base client does not."
