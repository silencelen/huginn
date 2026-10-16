#!/bin/sh
# huginn bootstrap (Linux, Termux):
#
#   curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh -s -- <host>
#
# 1. installs the huginn client (the same install.sh a clone runs, from the
#    newest cli-v release - never from a moving branch),
# 2. installs huginn core (huginn core install): the product itself, which
#    carries the CLI and runs at boot (not on Termux or macOS, where it cannot),
# 3. asks: install the desktop app too?            -> huginn desktop install
# 4. says what it installed, what runs at boot, and the next command.
# The question can be answered up front: HUGINN_GUI=y|n.
#
# POSIX sh on purpose: it runs before anything else is known about the machine.
# Questions are read from /dev/tty because stdin is this script.
set -eu
REPO=silencelen/huginn
HOST="${1:-}"

say() { printf '%s\n' "$*"; }
# A terminal we can actually OPEN. `[ -r /dev/tty ]` is true with no controlling
# terminal at all (automation, CI): the file is readable, opening it fails.
# ⚠ In a SUBSHELL: a failed redirection on a special built-in (`:`) makes a
# non-interactive POSIX sh EXIT, which killed this whole script silently.
has_tty() { ( : < /dev/tty ) 2>/dev/null; }
ask() {   # $1 = question, $2 = preset answer (env), default no
  if [ -n "$2" ]; then a="$2"; elif has_tty; then printf '%s [y/N] ' "$1" > /dev/tty; read -r a < /dev/tty || a=""; else a=""; fi
  case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}
get() { if command -v curl >/dev/null 2>&1; then curl -fsSL --max-time 60 "$1"; else wget -qO- --timeout=60 "$1"; fi; }

command -v ssh >/dev/null 2>&1 || { say "huginn: needs an ssh client (Debian: apt install openssh-client; Termux: pkg install openssh)"; exit 1; }
command -v bash >/dev/null 2>&1 || { say "huginn: needs bash (Termux: pkg install bash)"; exit 1; }

# The newest cli-v tag, by semver - the release a reviewed client is pinned to.
TAG="$(get "https://api.github.com/repos/$REPO/releases?per_page=60" | tr -d '\n' \
  | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"cli-v[0-9][0-9.]*"' | sed 's/.*"cli-v\([0-9.]*\)".*/\1/' \
  | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
[ -n "$TAG" ] || { say "huginn: could not read the release list (offline, or GitHub rate-limited this IP)"; exit 1; }
TAG="cli-v$TAG"
T="$(mktemp -d "${TMPDIR:-/tmp}/huginn-get.XXXXXX")"
trap 'rm -rf "$T"' EXIT
for f in install.sh huginn.sh huginn-device; do
  get "https://raw.githubusercontent.com/$REPO/$TAG/client/$f" > "$T/$f" || { say "huginn: could not fetch $f from $TAG"; exit 1; }
done
bash -n "$T/huginn.sh" || { say "huginn: $TAG's huginn.sh does not parse - refused"; exit 1; }
chmod 755 "$T/install.sh"
say "Installing the huginn client from $TAG"
# install.sh asks for the host when none was given; with no terminal at all
# (automation) the host must be the argument.
if has_tty; then IN=/dev/tty; else IN=/dev/null; [ -n "$HOST" ] || { say "huginn: no terminal to ask on - pass the host: sh -s -- <host>"; exit 1; }; fi
if [ -n "$HOST" ]; then bash "$T/install.sh" "$HOST" < "$IN"; else bash "$T/install.sh" < "$IN"; fi

# Everything below needs the host to trust this machine's key.
if ! ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null; then
  if has_tty; then printf 'Authorize the key above on the host, then press Enter... ' > /dev/tty; read -r _ < /dev/tty || true; fi
fi
TRUSTED=""
ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null && TRUSTED=1
# huginn core IS the product (owner rule 2026-10-15): the CLI rides inside it,
# the console and the desktop app need it running. So it is installed, not
# offered — except where it cannot run: Termux (the phone app is the device
# there) and macOS (no core build yet).
CORE=""
case "$(uname -s 2>/dev/null)/$(uname -o 2>/dev/null)" in
  */Android*) say "  huginn core does not run on Termux: this phone uses the CLI only (the huginn app is the device)" ;;
  Darwin/*)   say "  huginn core has no macOS build yet: this Mac uses the CLI only" ;;
  *)
    say ""
    say "Installing huginn core (it runs at boot, as you, and carries the CLI)"
    if bash -c '. "$HOME/.huginn/huginn.sh"; huginn core install'; then CORE=1
    else say "  huginn core install did not finish - run it again to see why: huginn core install"; fi ;;
esac
GUI=""
case "$(uname -o 2>/dev/null)" in Android*) ;; *)
  if [ -n "$CORE" ] && ask "Install the desktop app too (a window and tray on top of huginn core)?" "${HUGINN_GUI:-}"; then
    if bash -c '. "$HOME/.huginn/huginn.sh"; huginn desktop install'; then GUI=1
    else say "  the desktop install did not finish - run: huginn desktop install"; fi
  fi ;;
esac

# What happened, in one place (the install's answer to uninstall's Removed / Left behind).
say ""
say "huginn is installed:"
say "  the CLI         ~/.huginn (loaded by your shell; new terminals have 'huginn')"
[ -n "$CORE" ] && say "  huginn core     ~/.local/bin/huginn, started at boot by systemd (huginn-core.service)"
[ -n "$GUI" ]  && say "  the desktop app ~/.local/share/huginn-desktop (in your app menu)"
say "  SSH             'Host huginn' in ~/.ssh/config$( [ -n "$TRUSTED" ] && echo ' (the host trusts this key)' || echo ' - NOT trusted yet')"
if [ -z "$TRUSTED" ]; then
  say ""
  say "To finish: authorize the key shown above on the host, then run: huginn core install"
  say "(it pairs huginn core with the host; nothing else needs doing again)"
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *)
  [ -n "$CORE" ] && say "" && say "Note: ~/.local/bin is not on your PATH in this shell; a new login shell adds it on most systems." ;;
esac
if command -v fish >/dev/null 2>&1; then
  say ""
  say "fish: 'huginn' works there too (through huginn core at ~/.local/bin/huginn)."
fi
say ""
say "Open a new terminal and run: huginn    (the console; huginn help lists every command)"
