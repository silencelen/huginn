#!/bin/sh
# huginn bootstrap (Linux, Termux):
#
#   curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh -s -- <host>
#
# 1. installs the huginn client (the same install.sh a clone runs, from the
#    newest cli-v release - never from a moving branch),
# 2. asks: keep huginn running in the background?  -> huginn core install
# 3. asks: install the desktop GUI too?             -> huginn desktop install
# Every answer can be given up front: HUGINN_BACKGROUND=y|n HUGINN_GUI=y|n.
#
# POSIX sh on purpose: it runs before anything else is known about the machine.
# Questions are read from /dev/tty because stdin is this script.
set -eu
REPO=silencelen/huginn
HOST="${1:-}"

say() { printf '%s\n' "$*"; }
ask() {   # $1 = question, $2 = preset answer (env), default no
  if [ -n "$2" ]; then a="$2"; elif [ -r /dev/tty ]; then printf '%s [y/N] ' "$1" > /dev/tty; read -r a < /dev/tty || a=""; else a=""; fi
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
if [ -r /dev/tty ]; then IN=/dev/tty; else IN=/dev/null; [ -n "$HOST" ] || { say "huginn: no terminal to ask on - pass the host: sh -s -- <host>"; exit 1; }; fi
if [ -n "$HOST" ]; then bash "$T/install.sh" "$HOST" < "$IN"; else bash "$T/install.sh" < "$IN"; fi

# Everything below needs the host to trust this machine's key.
if ! ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null; then
  if [ -r /dev/tty ]; then printf 'Authorize the key above on the host, then press Enter... ' > /dev/tty; read -r _ < /dev/tty || true; fi
fi
TRUSTED=""
ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null && TRUSTED=1

if ask "Keep huginn running in the background (act mode and local AI with this machine signed out)?" "${HUGINN_BACKGROUND:-}"; then
  if [ -z "$TRUSTED" ]; then
    say "  skipped: the host does not trust this key yet. Later: huginn core install"
  else
    bash -c '. "$HOME/.huginn/huginn.sh"; huginn core install' || say "  huginn core install did not finish - run it again to see why"
  fi
fi
case "$(uname -o 2>/dev/null)" in Android*) ;; *)
  if ask "Install the desktop GUI too?" "${HUGINN_GUI:-}"; then
    bash -c '. "$HOME/.huginn/huginn.sh"; huginn desktop install' || say "  the desktop install did not finish - run: huginn desktop install"
  fi ;;
esac
say "Done. Open a new terminal, then: huginn help"
