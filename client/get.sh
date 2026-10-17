#!/bin/sh
# huginn bootstrap (Linux, Termux):
#
#   curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh
#
# (or `| sh -s -- <host>` to name the huginn host up front; it asks otherwise)
#
# FIRST it asks whether to install the desktop app (owner rule 2026-10-16;
# asked only where there is a graphical session):
#  - yes: installs huginn core (it carries the CLI and runs at boot) and the
#    desktop app, and opens it. The app's setup window walks through the rest
#    - the address, the token, this machine as a device - and configures huginn
#    core with it.
#  - no: onboards here in the terminal: the client (the same install.sh a clone
#    runs, from the newest cli-v release - never from a moving branch: key,
#    `Host huginn`, the host's trust in the key), huginn core, paired with the
#    host (not on Termux or macOS, where it cannot run), then the device and
#    local-AI questions.
# The questions can be answered up front: HUGINN_GUI, HUGINN_DEVICE,
# HUGINN_LOCAL = y|n; with no terminal to ask on, an unanswered one is a no.
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

if has_tty; then IN=/dev/tty; else IN=/dev/null; fi
yes_no() {   # $1 = question, $2 = preset, $3 = default (y|n)
  if [ -n "$2" ]; then a="$2"
  elif has_tty; then
    if [ "$3" = y ]; then printf '    %s [Y/n] ' "$1" > /dev/tty; else printf '    %s [y/N] ' "$1" > /dev/tty; fi
    read -r a < /dev/tty || a=""; [ -n "$a" ] || a="$3"
  else a=n; fi
  case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}
in_huginn() { bash -c '. "$HOME/.huginn/huginn.sh"; huginn "$@"' _ "$@"; }
# Where huginn core can run: not Termux (the phone app is the device there),
# not macOS (no core build yet).
CAN_CORE=1
case "$(uname -s 2>/dev/null)/$(uname -o 2>/dev/null)" in */Android*|Darwin/*) CAN_CORE="" ;; esac

# THE FIRST QUESTION, before any configuration (owner rule 2026-10-16): with the
# desktop app, its window walks the person through the rest and configures
# huginn core; without it, this terminal does.
GUI=""
if [ -n "$CAN_CORE" ] && { [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] || [ -n "${HUGINN_GUI:-}" ]; }; then
  yes_no "Install the desktop app? Its window then walks you through the rest" "${HUGINN_GUI:-}" y && GUI=1
fi
printf '\n' >&2
CORE="" PAIRED="" TRUSTED=""
if [ -n "$GUI" ]; then
  # The core (which carries the CLI) and the app; the app's setup connects the
  # core to huginn - the address, then the token - so nothing is paired here.
  if HUGINN_SETUP=1 HUGINN_CORE_NOPAIR=window bash -c '. "$1"; huginn core install' _ "$T/huginn.sh" < "$IN"; then CORE=1
  elif [ -x "$HOME/.local/bin/huginn" ]; then CORE=1
  else line fail core "did not finish - run it again to see why: huginn core install"; fi
  if [ -n "$CORE" ]; then
    if HUGINN_SETUP=1 in_huginn desktop install < "$IN"; then
      # Opened in its own session, so it outlives this script and its terminal.
      if command -v setsid >/dev/null 2>&1; then setsid "$HOME/.local/bin/huginn-desktop" >/dev/null 2>&1 < /dev/null &
      else nohup "$HOME/.local/bin/huginn-desktop" >/dev/null 2>&1 < /dev/null & fi
      line ok desktop "opened"
    else GUI=""; line fail desktop "the install did not finish - run: huginn desktop install"; fi
  fi
else
  command -v ssh >/dev/null 2>&1 || die needs "an ssh client (Debian: apt install openssh-client; Termux: pkg install openssh)"
  # install.sh asks for the host when none was given; with no terminal at all
  # (automation) the host must be the argument.
  [ -n "$HOST" ] || has_tty || die host "no terminal to ask on - pass the host: sh -s -- <host>"
  if [ -n "$HOST" ]; then HUGINN_SETUP=1 HUGINN_APPD_URL_FILE="$T/appd-url" bash "$T/install.sh" "$HOST" < "$IN"
  else HUGINN_SETUP=1 HUGINN_APPD_URL_FILE="$T/appd-url" bash "$T/install.sh" < "$IN"; fi
  # huginn's address, as install.sh worked it out from what was typed: pairing
  # uses it when ssh cannot fetch the token (run 7).
  [ -s "$T/appd-url" ] && { HUGINN_APPD_URL="$(cat "$T/appd-url")"; export HUGINN_APPD_URL; }
  ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>/dev/null && TRUSTED=1
  # huginn core IS the product (owner rule 2026-10-15): the CLI rides inside it.
  if [ -z "$CAN_CORE" ]; then
    case "$(uname -s 2>/dev/null)" in Darwin) line skip core "has no macOS build yet: this Mac uses the CLI" ;;
      *) line skip core "does not run on Termux: this phone uses the CLI (the huginn app is the device)" ;; esac
  else
    # Its own lines join this block: download, binary, service, linger, tray,
    # and pairing - which, without ssh to the host, asks for the token (run 7).
    if HUGINN_SETUP=1 bash -c '. "$HOME/.huginn/huginn.sh"; huginn core install' < "$IN"; then CORE=1
      "$HOME/.local/bin/huginn" core status 2>/dev/null | grep -q 'not paired' || PAIRED=1
    elif [ -x "$HOME/.local/bin/huginn" ]; then CORE=1   # in place; a later step (pairing) failed, and said why above
    else line fail core "did not finish - run it again to see why: huginn core install"; fi
  fi
  if [ -n "$CORE" ] && [ -n "$PAIRED" ]; then
    printf '\n' >&2
    # This machine as a device: huginn can run sessions here. Read-only
    # (scope look) until widened with huginn device on --scope work.
    if yes_no "Offer this machine to huginn as a device (read-only sessions until you widen it)?" "${HUGINN_DEVICE:-}" y; then
      if OUT="$(in_huginn device on 2>&1)"; then line ok device "on: huginn can run read-only sessions here (huginn device on --scope work widens it)"
      else line fail device "could not turn it on:"; printf '%s\n' "$OUT" | sed 's/^/                  /' >&2; fi
    else line skip device "not offered (huginn device on, any time)"; fi
    # The local AI tier, only where it would run on a GPU.
    if command -v node >/dev/null 2>&1; then
      PLAN="$(in_huginn local plan --json 2>/dev/null | tr -d '\n')"
      case "$PLAN" in *'"gpu":{'*'"setup":false'*|*'"setup":false'*'"gpu":{'*)
        GPU="$(printf '%s' "$PLAN" | sed -n 's/.*"gpu":{"name":"\([^"]*\)".*/\1/p')"
        NEED="$(printf '%s' "$PLAN" | sed -n 's/.*"needBytes":\([0-9]*\).*/\1/p')"
        GB="$(awk -v b="${NEED:-0}" 'BEGIN{printf "%.1f GB", b/1073741824}')"
        if yes_no "Serve local AI models from this machine's ${GPU:-GPU} ($GB download)?" "${HUGINN_LOCAL:-}" n; then
          line ok "local AI" "setting up - its own steps follow"
          if in_huginn local on --yes < "$IN"; then line ok "local AI" "serving from the ${GPU:-GPU} (huginn local status)"
          else line fail "local AI" "did not finish (above says why) - run: huginn local on"; fi
        else line skip "local AI" "not set up (huginn local on, any time)"; fi ;;
      esac
    fi
  fi
fi

printf '\n' >&2
if [ -n "$GUI" ] && [ -n "$CORE" ]; then
  say "Huginn is open: its setup connects huginn core to huginn (the address, then the token),"
  say "and offers this machine as a device. Close this terminal whenever you like."
  say "$(color 90)Attaching to sessions from a terminal uses SSH; set that up any time with: huginn setup ssh$(color 0)"
elif [ -n "$CORE" ] && [ -z "$PAIRED" ]; then
  say "huginn is installed, but huginn core is not paired with huginn yet (above says why)."
  say "Pair it any time:  huginn core pair --url ${HUGINN_APPD_URL:-https://<host>:8787}   (asks for the token)"
  say "then offer this machine:  huginn device on"
elif [ -z "$CORE" ] && [ -z "$(uname -o 2>/dev/null | grep Android)" ] && [ "$(uname -s)" != Darwin ]; then
  say "The CLI is installed, but huginn core is not (above says why): huginn core install"
else
  say "huginn is set up. Open a new terminal and run:  huginn"
  say "That opens the console; huginn help lists every command."
  [ -n "$GUI" ] || [ -n "$TRUSTED" ] || say "$(color 90)Attaching to sessions from a terminal needs ssh to the host; set that up any time with: huginn setup ssh$(color 0)"
fi
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *)
  [ -n "$CORE" ] && say "$(color 90)~/.local/bin is not on this shell's PATH; a new login shell adds it on most systems.$(color 0)" ;;
esac
command -v fish >/dev/null 2>&1 && say "$(color 90)fish: 'huginn' works there too (through huginn core at ~/.local/bin/huginn).$(color 0)"
say "$(color 90)done in $(( $(date +%s) - T0 )) s$(color 0)"
