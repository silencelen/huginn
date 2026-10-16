#!/bin/sh
# huginn bootstrap (Linux, Termux):
#
#   curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh
#
# (or `| sh -s -- <host>` to name the huginn host up front; it asks otherwise)
#
# 1. installs the huginn client (the same install.sh a clone runs, from the
#    newest cli-v release - never from a moving branch): key, `Host huginn`,
#    the CLI, and the host's trust in the key,
# 2. installs huginn core (huginn core install): the product itself, which
#    carries the CLI, runs at boot and pairs with the host (not on Termux or
#    macOS, where it cannot),
# 3. asks: install the desktop app too?            -> huginn desktop install
# 4. says what it set up and the next command.
# The question can be answered up front: HUGINN_GUI=y|n.
#
# One installer block, drawn like `huginn update` (BRAND.md section 6): the
# client's own helpers once it is downloaded, these few lines before that.
# POSIX sh on purpose: it runs before anything else is known about the machine.
# Questions are read from /dev/tty because stdin is this script.
set -eu
REPO=silencelen/huginn
HOST="${1:-}"
T0=$(date +%s)

# A terminal we can actually OPEN. `[ -r /dev/tty ]` is true with no controlling
# terminal at all (automation, CI): the file is readable, opening it fails.
# ⚠ In a SUBSHELL: a failed redirection on a special built-in (`:`) makes a
# non-interactive POSIX sh EXIT, which killed this whole script silently.
has_tty() { ( : < /dev/tty ) 2>/dev/null; }
# The same marks and columns as the client's _huginn_ui_line (glyph at 5,
# component at 8, text at 18), in octal so this file stays 7-bit.
color() { [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != dumb ] && printf '\033[%sm' "$1"; return 0; }
uni() { case "${HUGINN_ASCII:-}" in 1) return 1 ;; 0) return 0 ;; esac
        case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) return 0 ;; esac; return 1; }
line() {   # $1 = ok|fail|warn|skip  $2 = component  $3 = text
  if uni; then case "$1" in ok) g='\342\226\240' ;; fail) g='\303\227' ;; warn) g='\342\226\262' ;; *) g='\342\227\246' ;; esac
  else case "$1" in ok) g='+' ;; fail) g='x' ;; warn) g='!' ;; *) g='-' ;; esac; fi
  case "$1" in ok) c=96 ;; fail) c=91 ;; warn) c=93 ;; *) c=90 ;; esac
  printf "     %s$g%s  %-9s %s\n" "$(color $c)" "$(color 0)" "$2" "$3" >&2
}
say() { printf '    %s\n' "$*" >&2; }
die() { line fail "$1" "$2"; exit 1; }
ask() {   # $1 = question, $2 = preset answer (env), default no
  if [ -n "$2" ]; then a="$2"; elif has_tty; then printf '    %s [y/N] ' "$1" > /dev/tty; read -r a < /dev/tty || a=""; else a=""; fi
  case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}
get() { if command -v curl >/dev/null 2>&1; then curl -fsSL --max-time 60 "$1"; else wget -qO- --timeout=60 "$1"; fi; }

printf '%s(o>%s huginn setup\n' "$(color 95)" "$(color 0)" >&2
command -v ssh >/dev/null 2>&1 || die needs "an ssh client (Debian: apt install openssh-client; Termux: pkg install openssh)"
command -v bash >/dev/null 2>&1 || die needs "bash (Termux: pkg install bash)"

# The newest cli-v tag, by semver - the release a reviewed client is pinned to.
TAG="$(get "https://api.github.com/repos/$REPO/releases?per_page=60" | tr -d '\n' \
  | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"cli-v[0-9][0-9.]*"' | sed 's/.*"cli-v\([0-9.]*\)".*/\1/' \
  | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)" || true
[ -n "$TAG" ] || die release "could not read the release list (offline, or GitHub rate-limited this IP)"
TAG="cli-v$TAG"
T="$(mktemp -d "${TMPDIR:-/tmp}/huginn-get.XXXXXX")"
trap 'rm -rf "$T"' EXIT
for f in install.sh huginn.sh huginn-device; do
  get "https://raw.githubusercontent.com/$REPO/$TAG/client/$f" > "$T/$f" || die release "could not fetch $f from $TAG"
done
bash -n "$T/huginn.sh" || die release "$TAG's huginn.sh does not parse - refused"
chmod 755 "$T/install.sh"
line ok release "$TAG, the newest release"

# install.sh asks for the host when none was given; with no terminal at all
# (automation) the host must be the argument.
if has_tty; then IN=/dev/tty; else IN=/dev/null; [ -n "$HOST" ] || die host "no terminal to ask on - pass the host: sh -s -- <host>"; fi
if [ -n "$HOST" ]; then HUGINN_SETUP=1 bash "$T/install.sh" "$HOST" < "$IN"; else HUGINN_SETUP=1 bash "$T/install.sh" < "$IN"; fi

TRUSTED=""
ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null && TRUSTED=1
# huginn core IS the product (owner rule 2026-10-15): the CLI rides inside it,
# the console and the desktop app need it running. So it is installed, not
# offered - except where it cannot run: Termux (the phone app is the device
# there) and macOS (no core build yet).
CORE="" PAIRED=""
case "$(uname -s 2>/dev/null)/$(uname -o 2>/dev/null)" in
  */Android*) line skip core "does not run on Termux: this phone uses the CLI (the huginn app is the device)" ;;
  Darwin/*)   line skip core "has no macOS build yet: this Mac uses the CLI" ;;
  *)
    # Its own lines join this block: download, binary, service, linger, tray,
    # and pairing - which waits for the host's trust when there is none yet.
    NOPAIR=""; [ -n "$TRUSTED" ] || NOPAIR=1
    if HUGINN_SETUP=1 HUGINN_CORE_NOPAIR="$NOPAIR" \
         bash -c '. "$HOME/.huginn/huginn.sh"; huginn core install' < "$IN"; then CORE=1; PAIRED="$TRUSTED"
    elif [ -x "$HOME/.local/bin/huginn" ]; then CORE=1   # in place; a later step (pairing) failed, and said why above
    else line fail core "did not finish - run it again to see why: huginn core install"; fi ;;
esac
GUI=""
case "$(uname -o 2>/dev/null)" in Android*) ;; *)
  if [ -n "$CORE" ]; then
    [ -n "${HUGINN_GUI:-}" ] || printf '\n' >&2
    if ask "Install the desktop app too (a window and a tray on top of huginn core)?" "${HUGINN_GUI:-}"; then
      if HUGINN_SETUP=1 bash -c '. "$HOME/.huginn/huginn.sh"; huginn desktop install' < "$IN"; then GUI=1
      else line fail desktop "the install did not finish - run: huginn desktop install"; fi
    else
      line skip desktop "not installed (huginn desktop install adds it any time)"
    fi
  fi ;;
esac

printf '\n' >&2
if [ -z "$TRUSTED" ]; then
  say "huginn is installed, but the host does not trust this computer yet."
  say "Authorize the key shown above on the host, then run:  huginn core install"
  say "(it pairs huginn core with the host; nothing else needs doing again)"
elif [ -n "$CORE" ] && [ -z "$PAIRED" ]; then
  say "huginn is installed, but huginn core is not paired with the host yet (above says why)."
  say "Once that is fixed, run:  huginn core install   (everything else is already in place)"
elif [ -z "$CORE" ] && [ -z "$(uname -o 2>/dev/null | grep Android)" ] && [ "$(uname -s)" != Darwin ]; then
  say "The CLI is installed, but huginn core is not (above says why): huginn core install"
else
  say "huginn is set up. Open a new terminal and run:  huginn"
  say "That opens the console; huginn help lists every command."
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *)
  [ -n "$CORE" ] && say "$(color 90)~/.local/bin is not on this shell's PATH; a new login shell adds it on most systems.$(color 0)" ;;
esac
command -v fish >/dev/null 2>&1 && say "$(color 90)fish: 'huginn' works there too (through huginn core at ~/.local/bin/huginn).$(color 0)"
say "$(color 90)done in $(( $(date +%s) - T0 )) s$(color 0)"
