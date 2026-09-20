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
# ⚠ NOT JUST ~/.bashrc. This wired that one file and nothing else, so on a
# machine whose login shell is zsh - every recent macOS, and plenty of Linux -
# the client installed perfectly and then did not exist: no `huginn` command in
# any shell the person actually opens, with an installer that had just said
# "Installed". `huginn uninstall` knew the same one file, so the leftover line
# in ~/.zshrc outlived the client it sourced.
#
# WHICH FILES: ~/.bashrc always (this installer runs under bash, and Termux's
# login shell is bash). ~/.zshrc when it exists, or when zsh is the login shell -
# creating one otherwise would add a file to a machine that has no zsh. ~/.profile
# only when it ALREADY exists: creating it can change which file a login shell
# reads, and that is not an installer's decision to make.
#
# ⚠ AND ~/.profile GETS A DIFFERENT LINE. It is read by /bin/sh logins too, and
# huginn.sh is bash/zsh source (`[[ ]]`, `${1,,}`, `mapfile`) - sourcing it from
# dash would spray syntax errors over every login. So that copy is guarded on the
# shell, and uses `.` rather than the non-POSIX `source`. Both spellings are
# known to `huginn uninstall`.
RCLINE='[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh'
PROFILELINE='[ -n "${BASH_VERSION-}${ZSH_VERSION-}" ] && [ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh'
WIRED=""
wire_rc() {   # $1 = the file, $2 = the line to append
  touch "$1" || return 0
  grep -q '\.huginn/huginn\.sh' "$1" || printf '%s\n' "$2" >> "$1"
  WIRED="$WIRED $1"
}
wire_rc "$HOME/.bashrc" "$RCLINE"
# `if`, not `[ ... ] && ...`: this script runs under `set -e`, and a trailing
# test that is simply FALSE is not a failure worth aborting an install over.
ZSH_WANTED=""
case "${SHELL:-}" in */zsh) ZSH_WANTED=1 ;; esac
if [ -f "$HOME/.zshrc" ] || [ -n "$ZSH_WANTED" ]; then wire_rc "$HOME/.zshrc" "$RCLINE"; fi
if [ -f "$HOME/.profile" ]; then wire_rc "$HOME/.profile" "$PROFILELINE"; fi
# shellcheck disable=SC1090
source "$HOME/.huginn/huginn.sh"
echo
echo "Installed, and sourced from:$WIRED"
echo "Authorize the key above on the host, then:  huginn help  |  huginn status"
echo "This machine may also be able to serve local AI models to huginn (optional, ~5 GB):  huginn local on"
# The base client is bash+ssh and needs no node; only the optional features do.
# Said HERE because the native claude build ships without node, so its absence
# is normal now, not a sign something else is missing.
command -v node >/dev/null 2>&1 || \
  echo "Note: the optional device/local-AI features need Node.js LTS (nodejs.org) — the base client does not."
