#!/usr/bin/env bash
# Huginn client installer (bash / Termux).
# Run from the cloned repo:   ./client/install.sh my-host-or-ip
# (get.sh, the one-line installer, runs this same file with HUGINN_SETUP=1:
# it draws the block's title and its end itself.)
set -e
HHOST="${1:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KEY="$HOME/.ssh/id_ed25519"
HUSER="${HUGINN_USER:-root}"   # SSH user on the host; override: HUGINN_USER=huginn ./install.sh <host>
# The client's own installer-style helpers (_huginn_ui_line and friends):
# sourcing huginn.sh only defines functions.
# shellcheck disable=SC1091
. "$HERE/huginn.sh"
t0=$SECONDS
[ -n "${HUGINN_SETUP:-}" ] || _huginn_ui_head "huginn client install"
if [ -z "$HHOST" ]; then
  printf '    Huginn host (IP or DNS name reachable over SSH): ' >&2
  read -r HHOST
fi
[ -n "$HHOST" ] || { _huginn_ui_line fail host "no host given - run it again with the host: ./install.sh <host>"; exit 1; }
mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"
tilde() { case "$1" in "$HOME"/*) printf '~%s' "${1#"$HOME"}" ;; *) printf '%s' "$1" ;; esac; }

# 1. SSH key: yours when there is one (it is very likely the one your other
# hosts already trust), a new one otherwise.
if [ -f "$KEY" ]; then
  _huginn_ui_line ok key "$(tilde "$KEY") (yours, reused)"
else
  ssh-keygen -q -t ed25519 -f "$KEY" -N "" -C "huginn@$(hostname 2>/dev/null || echo client)" >/dev/null
  _huginn_ui_line ok key "$(tilde "$KEY") (made now)"
fi

# 2. `Host huginn` SSH alias (idempotent)
CFG="$HOME/.ssh/config"; touch "$CFG"; chmod 600 "$CFG"
if ! grep -qE '^[[:space:]]*Host[[:space:]]+huginn[[:space:]]*$' "$CFG"; then
  printf '\nHost huginn\n  HostName %s\n  User %s\n  IdentityFile %s\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n' "$HHOST" "$HUSER" "$KEY" >> "$CFG"
  _huginn_ui_line ok ssh "'Host huginn' -> $HHOST, as $HUSER ($(tilde "$CFG"))"
else
  _huginn_ui_line ok ssh "'Host huginn' is already in $(tilde "$CFG") - kept as it is"
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
  WIRED="$WIRED${WIRED:+, }$(basename "$1")"
}
wire_rc "$HOME/.bashrc" "$RCLINE"
# `if`, not `[ ... ] && ...`: this script runs under `set -e`, and a trailing
# test that is simply FALSE is not a failure worth aborting an install over.
ZSH_WANTED=""
case "${SHELL:-}" in */zsh) ZSH_WANTED=1 ;; esac
if [ -f "$HOME/.zshrc" ] || [ -n "$ZSH_WANTED" ]; then wire_rc "$HOME/.zshrc" "$RCLINE"; fi
if [ -f "$HOME/.profile" ]; then wire_rc "$HOME/.profile" "$PROFILELINE"; fi
_huginn_ui_line ok CLI "$HUGINN_VERSION at ~/.huginn/huginn.sh, loaded by $WIRED"

# 4. Does the host trust this key? Judged by ssh's exit code, with the reason
# read from what it says. Public-key only, so a host that does not know the key
# answers "Permission denied" instead of asking for a password. A host key this
# computer has never seen is ssh's own question, with its fingerprint, on the
# terminal: an installer does not accept one on the person's behalf.
WHY=""
trusted() {
  local err
  if ( : < /dev/tty ) 2>/dev/null; then
    err="$(ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o ConnectTimeout=8 huginn true 2>&1 >/dev/null < /dev/tty)" && return 0
  else
    err="$(ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>&1 >/dev/null)" && return 0
  fi
  case "$err" in
    *"IDENTIFICATION HAS CHANGED"*) WHY=changed ;;
    *"Permission denied"*) WHY=denied ;;
    *"Host key verification failed"*) WHY=hostkey ;;
    *) WHY="unreachable: $(printf '%s' "$err" | grep -v '^Warning: Permanently added' | tail -1)" ;;
  esac
  return 1
}
say_trust() {
  case "$WHY" in
    denied)  _huginn_ui_line warn trust "the huginn host does not know this key yet" ;;
    hostkey) _huginn_ui_line warn trust "the huginn host's own key was not accepted, so nothing was sent" ;;
    changed) _huginn_ui_line fail trust "the huginn host's key has CHANGED since this computer last saw it - nothing was sent"
             printf '%18s%s\n' '' "Check with whoever runs $HHOST. If it was reinstalled: ssh-keygen -R $HHOST, then run this again." >&2 ;;
    *)       _huginn_ui_line warn trust "could not reach $HHOST over ssh (${WHY#unreachable: })" ;;
  esac
}
if trusted; then
  _huginn_ui_line ok trust "the huginn host accepts this key"
else
  say_trust
  if [ "$WHY" = denied ] || [ "$WHY" = hostkey ]; then
    printf '\n    Authorize this computer on the huginn host. On %s, as %s, run:\n\n' "$HHOST" "$HUSER" >&2
    printf "      echo '%s' >> ~/.ssh/authorized_keys\n\n" "$(cat "$KEY.pub")" >&2
  fi
  if [ "$WHY" != changed ] && ( : < /dev/tty ) 2>/dev/null; then
    printf '    Press Enter when that is done (Ctrl-C to finish later) ' >&2
    read -r _ < /dev/tty || true
    if trusted; then _huginn_ui_line ok trust "the huginn host accepts this key"
    else say_trust; fi
  fi
fi

if [ -z "${HUGINN_SETUP:-}" ]; then
  printf '\n    Next: huginn core install   (runs huginn core at boot and pairs it with the host)\n' >&2
  printf '    %sThis machine may also serve local AI models to huginn (optional, ~5 GB): huginn local on%s\n' "$(_huginn_ui_c 90)" "$(_huginn_ui_c 0)" >&2
  # The base client is bash+ssh and needs no node; only the optional features
  # do. Said HERE because the native claude build ships without node, so its
  # absence is normal now, not a sign something else is missing.
  command -v node >/dev/null 2>&1 || \
    printf '    %sThe optional device and local-AI features need Node.js LTS (nodejs.org); the base client does not.%s\n' "$(_huginn_ui_c 90)" "$(_huginn_ui_c 0)" >&2
  printf '    %sdone in %s s%s\n' "$(_huginn_ui_c 90)" "$((SECONDS - t0))" "$(_huginn_ui_c 0)" >&2
fi
