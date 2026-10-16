# shellcheck shell=bash
# huginn (bash) - talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 2.4.1

HUGINN_VERSION='2.4.1'
HUGINN_REPO='silencelen/huginn'
# Where `huginn update` may fetch a replacement for THIS FILE, which it then
# sources into the live shell. Pinned, and deliberately NOT $HUGINN_HOST:
# that variable answers "which box do I drive", and letting it also answer
# "whose code do I run" means a typo, a second host or a test alias silently
# becomes a code source. Override needs HUGINN_UPDATE_HOST set on purpose.
HUGINN_UPDATE_HOST_DEFAULT='huginn'

# --- this machine, as a device --------------------------------------------
# `huginn devices` (plural) lists the machines the host knows about. `huginn
# device` (singular) is about the one you are typing on: offering it to Huginn as
# a place to run work, the way the desktop app's "Give Huginn access to this PC"
# toggle does - for machines that have no desktop app and nobody sitting at them.
#
# The runner itself is a separate small Node program (client/huginn-device; that
# file says why Node and not more shell). It is fetched on demand rather than
# carried inside this one, because most devices are clients and never offer
# themselves, and a client that never enrols should not be shipping a daemon.
# THE ONE COLOUR GUARD for every coloured line this file prints, by the brand's
# 16-colour slot pins (assets/brand/BRAND.md section 4): 91 brightRed = error,
# 93 brightYellow = attention, 96 brightCyan = ok, 95 brightMagenta = the accent,
# 90 brightBlack = muted, 0 = reset. Slot numbers only, never hex, so the user's
# terminal owns the hues; only when the stream it is written to is a TTY, and
# never under NO_COLOR (set to anything at all - no-color.org). 7-bit like the
# rest of this file.
#   $1 = the SGR slot   $2 = the fd the line goes to (default 2: most coloured
#   lines are messages on stderr; `update` reports on stdout and passes 1)
_huginn_color() { if [ -t "${2:-2}" ] && [ -z "${NO_COLOR:-}" ]; then printf '\033[%sm' "$1"; fi; }

# --- installer-style progress -----------------------------------------------
# What `huginn update` and the core/desktop installs look like (owner, 2026-10-16:
# "professional cli installer looking, with animations where needed"):
#
#     (o> huginn update
#         ■  client    2.3.6 -> 2.3.7   from GitHub
#        ╲▄╱ core      downloading   ██████████░░░░░░░░   56%   5.7/10.1 MB   4.2 MB/s
#         ■  core      2.3.6 -> 2.3.7   installed, restarted
#         ◦  local AI  not installed
#         done in 38 s
#
# A busy step turns the brand's wingbeat (BRAND.md §6: 4 frames at 8 fps, with
# a 7-bit twin) and a download draws a bar with its size and speed; both are
# redrawn in place on stderr and settle into one final line. Not a terminal
# (piped, logged, a script): only the final lines. NO_COLOR, HUGINN_ASCII=1,
# HUGINN_REDUCED_MOTION=1 (a still frame) are honoured.
_huginn_ui_tty() { [ -t 2 ] && [ "${TERM:-}" != dumb ]; }
_huginn_ui_uni() {
  case "${HUGINN_ASCII:-}" in 1) return 1 ;; 0) return 0 ;; esac
  case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) return 0 ;; esac
  return 1
}
_huginn_ui_c() { _huginn_ui_tty && [ -z "${NO_COLOR:-}" ] && printf '\033[%sm' "$1"; return 0; }
# The final line for one component. $1 = ok|fail|skip|warn  $2 = component  $3 = text
_huginn_ui_line() {
  local g c
  if _huginn_ui_uni; then
    # The brand's state marks, built from bytes so this file stays 7-bit
    # (the rule in test-client.sh [5f]): ■ ok, × error, ▲ needs a look, ◦ skipped.
    case "$1" in ok) g=$'\xe2\x96\xa0' ;; fail) g=$'\xc3\x97' ;; warn) g=$'\xe2\x96\xb2' ;; *) g=$'\xe2\x97\xa6' ;; esac
  else
    case "$1" in ok) g='+' ;; fail) g='x' ;; warn) g='!' ;; *) g='-' ;; esac
  fi
  case "$1" in ok) c=96 ;; fail) c=91 ;; warn) c=93 ;; *) c=90 ;; esac
  _huginn_ui_tty && printf '\r\033[K' >&2
  # Glyph at column 5, the component at 8: the spinner's three cells are
  # centred over the glyph's one, so the line does not move when it settles.
  printf '     %s%s%s  %-9s %s\n' "$(_huginn_ui_c "$c")" "$g" "$(_huginn_ui_c 0)" "$2" "$3" >&2
}
_huginn_ui_head() { printf '%s(o>%s %s\n' "$(_huginn_ui_c 95)" "$(_huginn_ui_c 0)" "$1" >&2; }
_huginn_ui_frames() {
  if [ -n "${HUGINN_REDUCED_MOTION:-}" ]; then
    _huginn_ui_uni && echo $'\xe2\x94\x80\xe2\x96\x84\xe2\x94\x80' || echo '-_-'
  elif _huginn_ui_uni; then printf '%s\n' $'\xe2\x94\x80\xe2\x96\x84\xe2\x94\x80' $'\xe2\x95\xb2\xe2\x96\x84\xe2\x95\xb1' $'\xe2\x94\x80\xe2\x96\x84\xe2\x94\x80' $'\xe2\x95\xb1\xe2\x96\x80\xe2\x95\xb2'
  else printf '%s\n' '-_-' '\_/' '-_-' "/'\\"; fi
}
# Runs "$@" with its output in $3 (a log), turning the wingbeat on stderr while
# it runs. $1 = component  $2 = what it is doing  $3 = log file. Returns its rc.
_huginn_ui_spin() {
  local comp="$1" what="$2" log="$3" pid i=0 f
  shift 3
  "$@" >"$log" 2>&1 &
  pid=$!
  if _huginn_ui_tty; then
    local frames=(); mapfile -t frames < <(_huginn_ui_frames)
    while kill -0 "$pid" 2>/dev/null; do
      f="${frames[$((i % ${#frames[@]}))]}"; i=$((i + 1))
      printf '\r\033[K    %s%s%s %-9s %s' "$(_huginn_ui_c 92)" "$f" "$(_huginn_ui_c 0)" "$comp" "$what" >&2
      sleep 0.125
    done
  fi
  wait "$pid"
}
# Downloads $1 to $2 (expected size $3 bytes, 0 = unknown) with a bar.
# $4 = component. Returns curl's rc; prints nothing on success.
_huginn_ui_download() {
  local url="$1" out="$2" size="${3:-0}" comp="$4" pid i=0 f cur t0 now el sp pct bar n w=24
  if ! command -v curl >/dev/null 2>&1; then
    wget -qO "$out" --timeout=60 "$url"; return
  fi
  curl -fsL --max-time 900 -o "$out" "$url" &
  pid=$!
  if _huginn_ui_tty; then
    local frames=(); mapfile -t frames < <(_huginn_ui_frames)
    local on=$'\xe2\x96\x88' off=$'\xe2\x96\x91'; _huginn_ui_uni || { on='#'; off='-'; }
    t0=$SECONDS
    while kill -0 "$pid" 2>/dev/null; do
      # The redirection fails until curl has created the file, and bash
      # reports a failed redirection BEFORE a `2>/dev/null` after it applies:
      # the group's own stderr is what silences it.
      cur=$( { wc -c <"$out"; } 2>/dev/null || echo 0); cur=${cur// /}
      el=$(( SECONDS - t0 )); [ "$el" -lt 1 ] && el=1
      sp=$(awk -v b="$cur" -v s="$el" 'BEGIN{printf "%.1f MB/s", b/s/1048576}')
      f="${frames[$((i % ${#frames[@]}))]}"; i=$((i + 1))
      if [ "$size" -gt 0 ] 2>/dev/null; then
        pct=$(( cur * 100 / size )); [ "$pct" -gt 100 ] && pct=100
        n=$(( pct * w / 100 )); bar="$(printf "%${n}s" '' | sed "s/ /$on/g")$(printf "%$((w - n))s" '' | sed "s/ /$off/g")"
        printf '\r\033[K    %s%s%s %-9s downloading  %s%s%s %3d%%  %s  %s' "$(_huginn_ui_c 92)" "$f" "$(_huginn_ui_c 0)" "$comp" \
          "$(_huginn_ui_c 92)" "$bar" "$(_huginn_ui_c 0)" "$pct" \
          "$(awk -v a="$cur" -v b="$size" 'BEGIN{printf "%.1f/%.1f MB", a/1048576, b/1048576}')" "$sp" >&2
      else
        printf '\r\033[K    %s%s%s %-9s downloading  %s  %s' "$(_huginn_ui_c 92)" "$f" "$(_huginn_ui_c 0)" "$comp" \
          "$(awk -v a="$cur" 'BEGIN{printf "%.1f MB", a/1048576}')" "$sp" >&2
      fi
      sleep 0.125
    done
    printf '\r\033[K' >&2
  fi
  wait "$pid"
}

_huginn_device_runner() { printf '%s' "$HOME/.huginn/huginn-device"; }

# The daemon URL this machine should be enrolled with, worked out from the ssh
# link it has ALREADY been trusted on. $SSH_CONNECTION's third field is the
# address THIS machine just reached the host at, which is better than choosing on
# the device's behalf between a LAN address, a tailnet name and whatever
# `hostname` happens to say. $1 = the ssh alias; prints https://<authority>:8787
# (http:// only for the host's own loopback).
#
# ⚠ HTTPS, BECAUSE THE RUNNER CARRIES THE TOKEN (appd 3.11, docs/TLS.md). The
# runner sends /etc/huginn-appd/token - root-equivalent on the host - on every
# request, and a device enrolled from here is usually on a flat LAN with no VPN
# under it. The runner would upgrade a remote http:// on its own anyway (rule 6:
# no setting re-enables cleartext to a remote host); building https:// here is
# so what the person reads in `huginn device status` is what is actually dialled.
# Loopback stays http://: the daemon answers plain HTTP from 127.0.0.0/8 only.
#
# ⚠ IT IS A BARE ADDRESS, AND A BARE IPv6 LITERAL IS NOT A URL. `http://fd00::1:8787`
# has no valid port, so `huginn device on` / `huginn local on` from a machine
# whose ssh landed on IPv6 died with nothing but "huginn-device: Invalid URL" -
# after saveConf() had already PERSISTED it, so a later flagless `on` repeated it
# and `serve` logged "not reaching huginn: Invalid URL - retrying in 15s" forever.
#
# ⚠ AND BRACKETING ALONE WOULD ONLY CHANGE THE ERROR. appd's resolveBind() takes
# `tailscale ip -4` and this deployment overrides it with 0.0.0.0, so nothing is
# listening on v6: a bracketed v6 url is a persisted ECONNREFUSED. So on a v6 ssh
# path the host is asked for an IPv4 it actually holds, and the bracketed literal
# is kept only as the last answer - correct syntax, and an honest failure.
#
# ⚠ AND AN EMPTY $SSH_CONNECTION IS NOT A DEAD END ANY MORE. It used to be:
# `[ -n "$a" ] || return 1`, and the caller turned that into "could not work out
# how to reach huginn's daemon" with nothing to do about it. It is empty in two
# ordinary situations - a shell running ON the host itself (the alias points at
# this machine, so there is no ssh session to have exported it) and an sshd whose
# ForceCommand/Match wrapper does not pass the variable on. Both of those can be
# answered: the host is asked for an address it actually holds, and when the host
# IS this machine the daemon is on our own loopback, which is the one address
# that cannot change under us. Only when nothing can be learned at all is it a
# refusal - and the caller then names `--url`, which every runner accepts.
#
# NOTE ON ROUTES: the apps pin a ROUTE BOOK (several addresses per daemon, tried
# in order, with a witness). The CLI deliberately has none - it is an ssh front
# end that runs host-side, so it has exactly one route, the ssh alias, and the
# address below is only ever handed to the local RUNNER, which is the piece that
# does have to dial the daemon directly. A device enrolled from here and one
# enrolled from an app can therefore hold different addresses for the same
# daemon; fix that in the app's route book, or with `--url` here.
_huginn_srv_url() {
  local H="$1" a v4 there here
  a="$(ssh -T "$H" 'echo $SSH_CONNECTION' 2>/dev/null | awk '{print $3}' | tr -d '[:space:]')"
  case "$a" in
    '')
      # Nothing to learn from the link. Is the host this very machine?
      there="$(ssh -T "$H" 'cat /etc/machine-id 2>/dev/null' 2>/dev/null | tr -d '[:space:]')"
      here="$(cat /etc/machine-id 2>/dev/null | tr -d '[:space:]')"
      if [ -n "$there" ] && [ "$there" = "$here" ]; then
        printf 'http://127.0.0.1:8787'; return 0
      fi ;;
    # The host's own loopback (an alias pointing at this machine): plain HTTP
    # is the one thing that stays, because it never leaves the box. ::1 maps to
    # 127.0.0.1 because appd binds IPv4 (see the bracketing note above).
    127.*) printf 'http://%s:8787' "$a"; return 0 ;;
    ::1) printf 'http://127.0.0.1:8787'; return 0 ;;
    *:*) ;;
    *) printf 'https://%s:8787' "$a"; return 0 ;;
  esac
  v4="$(ssh -T "$H" "ip -4 -o addr show scope global 2>/dev/null | awk '{print \$4}' | cut -d/ -f1 | head -1" \
        2>/dev/null | tr -d '[:space:]')"
  case "$v4" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*) printf 'https://%s:8787' "$v4"; return 0 ;;
  esac
  [ -n "$a" ] || return 1
  printf 'https://[%s]:8787' "$a"
}

# Did the caller already say where the daemon is? `huginn device on --url ...`
# (and --url=...) is the out when neither the ssh link nor the host can answer,
# and re-deriving one on top of it would put two --url flags on the runner's
# argv - the kind of thing that works until the day the parser stops taking the
# last one.
_huginn_has_url() {
  local a
  for a in "$@"; do case "$a" in --url|--url=*) return 0 ;; esac; done
  return 1
}

_huginn_device_fetch() {
  local dest tmp got= uh why=
  dest="$(_huginn_device_runner)"
  [ "${1:-}" = force ] || [ ! -s "$dest" ] || return 0
  mkdir -p "$HOME/.huginn"; tmp="$dest.tmp.js"
  # ⚠ EACH SOURCE IS VALIDATED INSIDE ITS OWN BRANCH, so a bad answer from one
  # really does fall through to the other. This side always checked gh's EXIT
  # status (the ps1 twin did not, which is the whole of #112 there); what
  # neither checked is whether a 200 is the FILE - a proxy error page is a
  # perfectly successful fetch of something that is not JavaScript, and the
  # syntax check sat AFTER the scp block, so it cleared `got` with the mirror
  # already skipped and told the caller to fix a download it never made.
  if command -v gh >/dev/null 2>&1; then
    if gh api "repos/$HUGINN_REPO/contents/client/huginn-device" \
         -H "Accept: application/vnd.github.raw" >"$tmp" 2>/dev/null \
       && [ -s "$tmp" ] && node --check "$tmp" 2>/dev/null; then got=1
    else why="gh did not return a usable runner"; fi
  else
    why="gh is not installed"
  fi
  if [ -z "$got" ]; then
    # PINNED, exactly like `huginn update` and for the same reason: this
    # downloads code that a systemd unit will then run in a loop, so the host it
    # comes from is a trust root and not a convenience. Never $HUGINN_HOST.
    uh="${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT}"
    if scp -o BatchMode=yes "$uh:/usr/local/share/huginn-cli/huginn-device" "$tmp" >/dev/null 2>&1 \
       && [ -s "$tmp" ] && node --check "$tmp" 2>/dev/null; then got=1
    else why="$why; the $uh mirror did not either"; fi
  fi
  # Validated BEFORE installing, same as the client's own update. A truncated
  # download that systemd then restarts every ten seconds is worse than none.
  [ -n "$got" ] || {
    echo "huginn device: could not fetch the runner ($why)" >&2
    rm -f "$tmp"; return 1; }
  mv -f "$tmp" "$dest"; chmod 0755 "$dest"
}

_huginn_device() {
  local H="${HUGINN_HOST:-huginn}" runner sub dir srv core
  runner="$(_huginn_device_runner)"
  sub="${1:-status}"; [ $# -gt 0 ] && shift
  # With huginn core installed, THIS machine's device is the core's: the Node
  # runner below is the pre-2.0 path and must not enrol a second row.
  if core="$(_huginn_core_bin)"; then
    case "$sub" in
      on|enrol|enroll) _huginn_core_pair "$core" || return 1; "$core" device on "$@"; return ;;
      off|status) "$core" device "$sub" "$@"; return ;;
      *) echo "huginn device $sub: not needed with huginn core (see: huginn core status)" >&2; return 1 ;;
    esac
  fi
  command -v node >/dev/null 2>&1 || {
    echo "huginn device: needs node - the NATIVE claude build ships without it; install" >&2
    echo "               Node.js LTS: nodejs.org (Windows: winget install OpenJS.NodeJS.LTS)" >&2
    return 1; }
  case "$sub" in
    on|enrol|enroll)
      _huginn_device_fetch "${HUGINN_DEVICE_REFRESH:+force}" || return 1
      dir="${HUGINN_DEVICE_DIR:-$HOME/.config/huginn}"
      mkdir -p "$dir"; chmod 700 "$dir" 2>/dev/null
      # The token and the address, both taken over the ssh link this machine has
      # ALREADY been trusted on. Nothing is widened by this: anyone who can ssh to
      # the host can read that file anyway. What it removes is a bearer token
      # pasted by hand between two terminals, which is how tokens end up in
      # scrollback and in pastes.
      if [ ! -s "$dir/appd-token" ]; then
        if ssh -T "$H" 'cat /etc/huginn-appd/token' >"$dir/appd-token.tmp" 2>/dev/null \
           && [ -n "$(tr -d '[:space:]' <"$dir/appd-token.tmp" 2>/dev/null)" ]; then
          mv -f "$dir/appd-token.tmp" "$dir/appd-token"; chmod 600 "$dir/appd-token"
        else
          rm -f "$dir/appd-token.tmp"
          echo "huginn device: could not read the appd token from $H" >&2; return 1
        fi
      fi
      # See _huginn_srv_url: the address is the one this machine just reached the
      # host at, bracketed when it is an IPv6 literal. An explicit --url wins
      # outright - it is the answer to this question, not an extra opinion.
      if _huginn_has_url "$@"; then
        node "$runner" on "$@"
      else
        srv="$(_huginn_srv_url "$H")"
        [ -n "$srv" ] || {
          echo "huginn device: could not work out how to reach $H's daemon" >&2
          echo "               (no ssh address to go on, and $H named no global IPv4)" >&2
          echo "               say it yourself: huginn device on --url https://<host>:8787" >&2
          return 1; }
        node "$runner" on --url "$srv" "$@"
      fi
      ;;
    update)
      _huginn_device_fetch force && echo "huginn device: runner is now $(node "$runner" version)" ;;
    *)
      [ -s "$runner" ] || {
        echo "huginn device: this machine is not set up as a device - run: huginn device on" >&2
        return 1; }
      node "$runner" "$sub" "$@" ;;
  esac
}

# `huginn local` - THIS machine serves local AI models to huginn (the optional
# local tier). Same grammar as `huginn device`: consent, fetch pinned, validate,
# install, enrol - and the same trust roots for the fetch. The manager carries
# its own pinned runtime/model manifest, so what it may install is decided by
# the release you are running, never by whatever an endpoint serves today.
# --------------------------------------------------------------- huginn core
# The Go core (docs/CORE-ARCHITECTURE.md): one binary per user that runs act
# mode at boot, with nobody signed in. While it exists, `huginn device` is a
# door to it and the Node runner is not used. The binary is NAMED huginn too, so
# it is always run by PATH, never by bare name - inside this function a bare
# `huginn` would be this function.

# The installed core binary, or nothing.
_huginn_core_bin() {
  local p
  for p in "$HOME/.local/bin/huginn" /usr/bin/huginn; do
    [ -f "$p" ] && [ -x "$p" ] && { printf '%s' "$p"; return 0; }
  done
  return 1
}

# Which core build THIS machine runs: linux-x64 | linux-arm64 | android-arm64.
_huginn_core_platform() {
  case "$(uname -o 2>/dev/null)" in Android*) echo 'android-arm64'; return ;; esac
  case "$(uname -s 2>/dev/null)/$(uname -m 2>/dev/null)" in
    Linux*/x86_64|Linux*/amd64) echo 'linux-x64' ;;
    Linux*/aarch64|Linux*/arm64) echo 'linux-arm64' ;;
  esac
}

# Newest cli-v* release that carries core-manifest.json: <tag>\n<manifest>.
# Semver, not feed order (see _huginn_desktop_release for why).
_huginn_core_release() {
  local ver json
  ver="$(_huginn_get "https://api.github.com/repos/$HUGINN_REPO/releases?per_page=60" | tr -d '\n' \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"cli-v[0-9][^"]*"' \
        | sed 's/.*"cli-v\([^"]*\)".*/\1/' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
        | sort -t. -k1,1n -k2,2n -k3,3n | awk -F. '$1>=2' | tail -1)"
  [ -n "$ver" ] || return 1
  json="$(_huginn_get "https://github.com/$HUGINN_REPO/releases/download/cli-v$ver/core-manifest.json" | tr -d '\n')"
  [ -n "$json" ] || return 1
  printf 'cli-v%s\n%s\n' "$ver" "$json"
}

# Download, verify and self-install the core. Prints the installed path.
# HUGINN_CORE_FILE=<a local binary> skips the download (tests, offline installs)
# - it is still run through `self-install`, never copied into place by hand.
# `_huginn_core_fetch install [flags]`: the verified download runs `core
# install` itself instead (the first install: it lays out the binary and the
# CLI, so its lines say what really happened on this machine).
_huginn_core_fetch() (
  set -u
  fail() { _huginn_ui_line fail core "$*"; exit 1; }
  local key rel tag man obj file sha tmp got mode="${1:-}"
  [ $# -gt 0 ] && shift
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/huginn-core.XXXXXX")" || fail "could not make a temp dir"
  trap 'rm -rf "$tmp"' EXIT
  if [ -n "${HUGINN_CORE_FILE:-}" ]; then
    cp "$HUGINN_CORE_FILE" "$tmp/huginn" && chmod 755 "$tmp/huginn" || fail "could not read $HUGINN_CORE_FILE"
  else
    key="$(_huginn_core_platform)"; [ -n "$key" ] || fail "there is no huginn core build for $(uname -s)/$(uname -m)"
    rel="$(_huginn_core_release)" || fail "could not read the core release feed (offline, rate-limited, or no 2.x release yet)"
    tag="${rel%%$'\n'*}"; man="${rel#*$'\n'}"
    obj="$(_huginn_json_obj "$man" "$key")"
    file="$(_huginn_json_str "$obj" file)"; sha="$(_huginn_json_str "$obj" sha256)"
    case "$file" in huginn-*-"$key") ;; *) fail "$tag names an unexpected core file '$file' - refused" ;; esac
    printf '%s' "$sha" | grep -qE '^[0-9a-f]{64}$' || fail "$tag carries no usable sha256 for $file - refused"
    _huginn_ui_download "https://github.com/$HUGINN_REPO/releases/download/$tag/$file" "$tmp/huginn" \
      "$(_huginn_json_num "$obj" size)" core || fail "download failed: $file ($tag)"
    # THE GATE: the manifest's sha256 is the only integrity check between
    # GitHub and an executable in your home directory.
    got="$(_huginn_sha256 "$tmp/huginn")"
    [ "$got" = "$sha" ] || fail "$file did not match its sha256 - discarded, nothing installed"
    chmod 755 "$tmp/huginn"
  fi
  if [ "$mode" = install ]; then
    local v; v="$("$tmp/huginn" version 2>/dev/null | sed -n 's/^(o> huginn \([^ ]*\) .*/\1/p')"
    if [ -n "${HUGINN_CORE_FILE:-}" ]; then _huginn_ui_line ok download "huginn ${v:-?} from $HUGINN_CORE_FILE"
    else _huginn_ui_line ok download "huginn ${v:-?} for $key, sha256 verified"; fi
    HUGINN_UI_COMPACT=1 "$tmp/huginn" core install "$@" >&2 || exit 1
    _huginn_core_bin
    exit 0
  fi
  if ! _huginn_ui_spin core "installing" "$tmp/log" "$tmp/huginn" core self-install; then
    _huginn_ui_line fail core "self-install failed:"; sed 's/^/            /' "$tmp/log" >&2
    exit 1
  fi
  # One line from the core's own report; the whole report when asked for it
  # (`huginn core install`), and never lost: the log is what a failure shows.
  local now restart
  now="$("$tmp/huginn" version 2>/dev/null | sed -n 's/^(o> huginn \([^ ]*\) .*/\1/p')"
  restart="installed"
  grep -q 'restarted huginn core' "$tmp/log" && restart="installed, restarted"
  grep -q 'has not come back yet' "$tmp/log" && restart="installed; the running core restarts onto it shortly"
  _huginn_ui_line ok core "${HUGINN_UI_FROM:+$HUGINN_UI_FROM -> }${now:-?}   $restart"
  [ -n "${HUGINN_UI_COMPACT:-}" ] || sed 's/^/            /' "$tmp/log" >&2
  _huginn_core_bin
)

# Pair the core with the host this shell already reaches over ssh, if it is not
# paired yet. The token travels ssh -> stdin, never argv or a file in /tmp.
# $2 = say: report an existing pairing too (`huginn core install`'s line).
_huginn_core_pair() {
  local H="${HUGINN_HOST:-huginn}" bin="$1" srv st out
  st="$("$bin" core status 2>/dev/null)"
  if ! grep -q 'not paired' <<< "$st"; then
    [ "${2:-}" = say ] && _huginn_ui_line ok paired "$(sed -n 's/^  huginn: *//p' <<< "$st" | head -1)"
    return 0
  fi
  srv="$(_huginn_srv_url "$H")"
  if [ -z "$srv" ]; then
    _huginn_ui_line fail paired "could not work out $H's daemon address"
    printf '                  pair by hand: huginn core pair --url https://<host>:8787\n' >&2
    return 1
  fi
  local log; log="$(mktemp)"
  if _huginn_ui_spin paired "fetching the token over ssh and pairing with $srv" "$log" \
       bash -c 'ssh -T "$1" "cat /etc/huginn-appd/token" 2>/dev/null | "$2" core pair --url "$3"' _ "$H" "$bin" "$srv"; then
    out="$(head -1 "$log")"; _huginn_ui_line ok paired "${out#paired with }"
  else
    _huginn_ui_line fail paired "could not pair with $srv"; sed 's/^/                  /' "$log" >&2
    rm -f "$log"; return 1
  fi
  rm -f "$log"
}

# Is release $2 newer than the installed core $1? A prerelease (2.1.0-rc.1)
# is older than its release (2.1.0); a newer local build is left alone.
_huginn_core_newer() {
  local cur="$1" rel="$2" base="${1%%-*}"
  [ -n "$cur" ] || return 0
  [ "$cur" = "$rel" ] && return 1
  [ "$base" = "$rel" ] && [ "$base" != "$cur" ] && return 0
  [ "$(printf '%s\n%s\n' "$base" "$rel" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)" = "$rel" ] && [ "$base" != "$rel" ]
}

# Bring the installed core up to the newest release. Its self-install restarts
# the running core onto it and adds the tray to a pre-tray (2.0.0) install.
_huginn_core_update() {
  local bin cur rel latest run
  bin="$(_huginn_core_bin)" || bin=""
  [ -n "$bin" ] || { echo "huginn core: not installed here - huginn core install" >&2; return 1; }
  cur="$("$bin" version 2>/dev/null | sed -n 's/^(o> huginn \([^ ]*\) .*/\1/p')"
  if [ -z "${HUGINN_CORE_FILE:-}" ]; then
    rel="$(_huginn_core_release)" || { _huginn_ui_line warn core "could not read the release feed (offline or rate-limited); ${cur:-the installed core} kept"; return 1; }
    latest="${rel%%$'\n'*}"; latest="${latest#cli-v}"
    if ! _huginn_core_newer "$cur" "$latest"; then
      run="$("$bin" core status 2>/dev/null | sed -n 's/^(o> huginn core \([^ ]*\) .*/\1/p')"
      if [ -n "$run" ] && [ "$run" != "$cur" ]; then
        # Installed but not running yet (an earlier restart could not be made).
        local log; log="$(mktemp)"
        if _huginn_ui_spin core "restarting $run onto $cur" "$log" "$bin" core self-install; then
          _huginn_ui_line ok core "$cur   restarted (it was still running $run)"
        else
          _huginn_ui_line fail core "the restart failed:"; sed 's/^/            /' "$log" >&2
        fi
        rm -f "$log"; return
      fi
      _huginn_ui_line ok core "$cur is current"; return 0
    fi
  fi
  HUGINN_UI_FROM="$cur" _huginn_core_fetch >/dev/null
}

_huginn_core() {
  local bin sub="${1:-status}" i
  [ $# -gt 0 ] && shift
  bin="$(_huginn_core_bin)" || bin=""
  case "$sub" in
    update)
      _huginn_core_update
      ;;
    install)
      # One block, as `huginn update` draws it: the download (or the update
      # of the core already here), then the core's own lines, then pairing.
      local t0=$SECONDS
      [ -n "${HUGINN_UI_COMPACT:-}${HUGINN_SETUP:-}" ] || _huginn_ui_head "huginn core install"
      if [ -z "$bin" ] || [ -n "${HUGINN_CORE_FILE:-}" ]; then
        bin="$(_huginn_core_fetch install "$@")" || return 1
      else
        # An installed core is brought up to date first (get.sh re-run on a
        # machine from an older release); offline, the installed one is used.
        HUGINN_UI_COMPACT=1 _huginn_core_update || true
        HUGINN_UI_COMPACT=1 "$bin" core install "$@" || return 1
      fi
      # The service starts the core; wait for it to answer, then pair it.
      for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        "$bin" core status >/dev/null 2>&1 && break; sleep 1
      done
      if [ -n "${HUGINN_CORE_NOPAIR:-}" ]; then
        # huginn setup: the host does not trust this computer yet, so there is
        # no ssh to fetch the token over; the person re-runs this after.
        _huginn_ui_line skip paired "not yet: the host does not trust this computer's key - huginn core install pairs it once it does"
      else
        _huginn_core_pair "$bin" say || return 1
      fi
      [ -n "${HUGINN_UI_COMPACT:-}${HUGINN_SETUP:-}" ] || printf '    %sdone in %s s%s\n' "$(_huginn_ui_c 90)" "$((SECONDS - t0))" "$(_huginn_ui_c 0)" >&2
      ;;
    *)
      [ -n "$bin" ] || { echo "huginn core: not installed here - huginn core install" >&2; return 1; }
      "$bin" core "$sub" "$@"
      ;;
  esac
}

_huginn_local_manager() { printf '%s' "$HOME/.huginn/huginn-local"; }

_huginn_local_fetch() {
  local f dest tmp got uh why
  # huginn-device rides along: managed mode installs a runner SERVICE, and a
  # machine that never enrolled as a claude device has no runner otherwise
  # (found wiring the desktop door - enrolment died on a bare spawn error).
  for f in huginn-local huginn-llm-shim huginn-device; do
    dest="$HOME/.huginn/$f"; got=; why=
    [ "${1:-}" = force ] || [ ! -s "$dest" ] || continue
    mkdir -p "$HOME/.huginn"; tmp="$dest.tmp.js"
    # Each source validated inside its own branch - see _huginn_device_fetch.
    if command -v gh >/dev/null 2>&1; then
      if gh api "repos/$HUGINN_REPO/contents/client/$f" \
           -H "Accept: application/vnd.github.raw" >"$tmp" 2>/dev/null \
         && [ -s "$tmp" ] && node --check "$tmp" 2>/dev/null; then got=1
      else why="gh did not return a usable $f"; fi
    else
      why="gh is not installed"
    fi
    if [ -z "$got" ]; then
      # PINNED, like the device runner and `huginn update`: this downloads code
      # a service will run in a loop, so the source is a trust root. Never
      # $HUGINN_HOST.
      uh="${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT}"
      if scp -o BatchMode=yes "$uh:/usr/local/share/huginn-cli/$f" "$tmp" >/dev/null 2>&1 \
         && [ -s "$tmp" ] && node --check "$tmp" 2>/dev/null; then got=1
      else why="$why; the $uh mirror did not either"; fi
    fi
    [ -n "$got" ] || {
      echo "huginn local: could not fetch $f ($why)" >&2
      rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$dest"; chmod 0755 "$dest"
  done
}

_huginn_local() {
  local H="${HUGINN_HOST:-huginn}" mgr sub dir srv
  mgr="$(_huginn_local_manager)"
  sub="${1:-status}"; [ $# -gt 0 ] && shift
  command -v node >/dev/null 2>&1 || {
    echo "huginn local: needs node - install Node.js LTS: nodejs.org (Windows: winget install OpenJS.NodeJS.LTS)" >&2
    return 1; }
  case "$sub" in
    on)
      _huginn_local_fetch "${HUGINN_LOCAL_REFRESH:+force}" || return 1
      dir="${HUGINN_LOCAL_DIR:-$HOME/.config/huginn-local}"
      mkdir -p "$dir/device"; chmod 700 "$dir" "$dir/device" 2>/dev/null
      # Token and address over the ssh link this machine is already trusted on,
      # exactly like device-on. An existing device enrolment's token is reused
      # rather than fetched twice.
      if [ ! -s "$dir/device/appd-token" ]; then
        if [ -s "$HOME/.config/huginn/appd-token" ]; then
          cp "$HOME/.config/huginn/appd-token" "$dir/device/appd-token"; chmod 600 "$dir/device/appd-token"
        elif ssh -T "$H" 'cat /etc/huginn-appd/token' >"$dir/device/appd-token.tmp" 2>/dev/null \
           && [ -n "$(tr -d '[:space:]' <"$dir/device/appd-token.tmp" 2>/dev/null)" ]; then
          mv -f "$dir/device/appd-token.tmp" "$dir/device/appd-token"; chmod 600 "$dir/device/appd-token"
        else
          rm -f "$dir/device/appd-token.tmp"
          echo "huginn local: could not read the appd token from $H" >&2; return 1
        fi
      fi
      if _huginn_has_url "$@"; then
        HUGINN_LOCAL_DIR="$dir" node "$mgr" on "$@"
      else
        srv="$(_huginn_srv_url "$H")"
        [ -n "$srv" ] || {
          echo "huginn local: could not work out how to reach $H's daemon" >&2
          echo "              (no ssh address to go on, and $H named no global IPv4)" >&2
          echo "              say it yourself: huginn local on --url https://<host>:8787" >&2
          return 1; }
        HUGINN_LOCAL_DIR="$dir" node "$mgr" on --url "$srv" "$@"
      fi
      ;;
    update)
      _huginn_local_fetch force || return 1
      [ -s "$mgr" ] || { echo "huginn local: not set up - run: huginn local on" >&2; return 1; }
      node "$mgr" update "$@"
      ;;
    plan)
      # Read-only: what `on` would install here, without installing anything.
      _huginn_local_fetch || return 1
      node "$mgr" plan "$@"
      ;;
    *)
      [ -s "$mgr" ] || {
        echo "huginn local: this machine does not serve local models - run: huginn local on" >&2
        return 1; }
      node "$mgr" "$sub" "$@" ;;
  esac
}

# --- uninstall -------------------------------------------------------------
# `huginn uninstall` - put this machine back the way install.sh found it.
#
# THE ORDER IS THE POINT, and it is huginn-device's: THE SERVER FIRST, THE DISK
# SECOND. Every enrolment this machine holds can only be retired with a token
# that is about to be deleted, so each unenrol is attempted while its own
# credentials still exist. Wipe first and those rows are unremovable from here
# forever - they sit in `huginn devices` reading "not reachable" and go on being
# offered work by a machine that no longer exists.
#
# AND AN UNINSTALLER DOES NOT GET A SECOND RUN, so a failed unenrol does not
# stop it: the local files go anyway (`off --force`), and the row that was
# stranded is named - by the runner, and again in the summary. That is the one
# place the refuse-to-destroy-the-handle rule is deliberately inverted, because
# "run it again tomorrow" is advice to somebody who will not be here tomorrow.
#
# WHAT IT LEAVES ON PURPOSE: the SSH key and the `Host huginn` stanza.
# install.sh only CREATES a key when there is not one already, and afterwards
# nothing can tell "the key install.sh generated" from "the key you have used
# for five years" - ~/.ssh/id_ed25519 is the default name for both, and the
# wrong guess locks somebody out of every host they have. So they stay, with a
# note. `--all` takes them, and only then, when the key is huginn-specific by
# FILENAME or by the comment in its .pub. Never by guess.
_huginn_uninstall() {
  local all= yes= a
  for a in "$@"; do
    case "$a" in
      --all) all=1 ;;
      --yes) yes=1 ;;
      *) echo "usage: huginn uninstall [--all] [--yes]" >&2; return 1 ;;
    esac
  done
  # Everything below is built out of $HOME. An empty one turns every path here
  # into an absolute path at the filesystem root, so it is a refusal and not a
  # default.
  [ -n "$HOME" ] || { echo "huginn uninstall: HOME is not set - refusing to guess where anything lives" >&2; return 1; }

  # With a core installed, the CORE owns the uninstall: the whole stack, top
  # down - the desktop app, the local tier, the device, the core, this CLI
  # (owner rule 2026-10-15: the core includes the CLI; the desktop sits on it).
  # Everything below remains for platforms with no core (macOS, Termux).
  local core
  if [ "$(uname -o 2>/dev/null)" != Android ] && core="$(_huginn_core_bin)"; then
    "$core" uninstall "$@"
    return
  fi

  local hdir="$HOME/.huginn"
  local ddir="${HUGINN_DEVICE_DIR:-$HOME/.config/huginn}"
  local ldir="${HUGINN_LOCAL_DIR:-$HOME/.config/huginn-local}"
  local cfg="$HOME/.ssh/config"
  # ⚠ THREE FILES, BECAUSE THE INSTALLER WIRES THREE. This knew only ~/.bashrc,
  # so on a machine whose login shell is zsh (every recent macOS, and plenty of
  # Linux) the uninstaller left the source line behind in ~/.zshrc and every new
  # shell went on defining a `huginn` function whose file had just been deleted.
  # The list and the two spellings are install.sh's own - see the RCLINE/PROFILE
  # comments there - and they are matched WHOLE-LINE and fixed-string, so a line
  # somebody hand-wrote differently is reported rather than rewritten.
  local -a rcfiles=("$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile")
  local -a rclines=(
    '[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh'
    '[ -n "${BASH_VERSION-}${ZSH_VERSION-}" ] && [ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh'
  )
  local havenode=; command -v node >/dev/null 2>&1 && havenode=1
  local -a removed=() left=()

  echo "huginn uninstall removes, from THIS machine:"
  echo "  $hdir"
  echo "      the client, the device runner, the local-AI manager"
  echo "  $ddir"
  echo "      this machine's device enrolment and its copy of the appd token"
  echo "  $ldir"
  echo "      the local-AI tier: models, sessions, runtime (can be several GB)"
  echo "  the 'source ~/.huginn/huginn.sh' line in ~/.bashrc, ~/.zshrc or ~/.profile"
  echo
  echo "It unenrols this machine from huginn FIRST, while the tokens still exist."
  [ -n "$all" ] && echo "--all: the 'Host huginn' SSH stanza goes too, and its key IF it is huginn's own."
  echo
  if [ -z "$yes" ]; then
    local answer=
    read -rp 'Type "uninstall" to continue: ' answer
    [ "$answer" = uninstall ] || { echo "Nothing was removed."; return 1; }
    echo
  fi

  # 1. The local tier first: it owns a device row of its own (<host>-llm), two
  #    services and the heaviest files, and its manager lives in $hdir - which
  #    step 3 is about to delete, so this cannot be reordered after it.
  if [ -d "$ldir" ]; then
    echo "==> local AI tier"
    if [ -n "$havenode" ] && [ -s "$hdir/huginn-local" ]; then
      if HUGINN_LOCAL_DIR="$ldir" node "$hdir/huginn-local" off --purge --yes; then
        removed+=("$ldir")
      else
        # Deliberately NOT forced. A failed unenrol here keeps the id that can
        # still retire the row, and what is left behind is models - which the
        # summary names, with the command that finishes the job.
        left+=("$ldir - 'huginn local off --purge' did not finish; run it again when huginn is reachable")
      fi
    else
      left+=("$ldir - no manager or no node here, so nothing could unenrol or remove it")
    fi
    echo
  fi

  # 2. This machine as a device.
  if [ -d "$ddir" ]; then
    echo "==> device enrolment"
    if [ -n "$havenode" ] && [ -s "$hdir/huginn-device" ]; then
      if ! HUGINN_DEVICE_DIR="$ddir" node "$hdir/huginn-device" off; then
        HUGINN_DEVICE_DIR="$ddir" node "$hdir/huginn-device" off --force
        left+=("a device row on huginn (named above) is still enrolled - retire it from the host")
      fi
    else
      left+=("this machine may still be enrolled - no runner or no node here to unenrol it")
    fi
    # Named files only, and after the attempt above: a dir that was never
    # enrolled can still hold the token `huginn device on` fetched into it.
    rm -f "$ddir/device.json" "$ddir/appd-token"
    # rmdir REFUSING a non-empty directory is the safeguard, not a failure - a
    # leftover .tmp, a .bak, or a file somebody put there themselves all stop
    # it, and none of them are ours to delete. But it was reported as removed
    # either way, so the one summary that tells a person what is still on their
    # disk named a directory that is still on their disk.
    if rmdir "$ddir" 2>/dev/null; then
      removed+=("$ddir")
    else
      left+=("$ddir - left (not empty): its device.json and appd-token are gone, but something else is in there")
    fi
    echo
  fi

  # 3. The client itself. The whole directory: install.sh created it and
  #    everything in it is huginn's - unlike the desktop app's uninstaller,
  #    which shares this directory and therefore names its files one by one.
  if [ -d "$hdir" ]; then
    rm -rf "$hdir"
    removed+=("$hdir")
  fi

  # 4. The profile line, in every file the installer may have written it to.
  local rc rcline hit grc
  for rc in "${rcfiles[@]}"; do
    [ -f "$rc" ] || continue
    hit=
    for rcline in "${rclines[@]}"; do
      grep -qxF -- "$rcline" "$rc" || continue
      hit=1
      grep -vxF -- "$rcline" "$rc" > "$rc.huginn-uninstall"; grc=$?
      # 0 = lines kept, 1 = the file was only that line. Anything else is a read
      # error, and truncating somebody's .bashrc over one is not a trade worth
      # making.
      if [ "$grc" -le 1 ]; then
        mv -f "$rc.huginn-uninstall" "$rc"
        removed+=("the source line in $rc")
      else
        rm -f "$rc.huginn-uninstall"
        left+=("the source line in $rc - could not rewrite it (read error)")
      fi
    done
    if [ -z "$hit" ] && grep -q '\.huginn/huginn\.sh' "$rc"; then
      left+=("a hand-edited '.huginn/huginn.sh' line in $rc - it is not the one the installer wrote, so it was left")
    fi
  done

  # 5. SSH. Left by default; see the note on this function.
  local key='' ours=
  if [ -f "$cfg" ]; then
    key="$(awk 'tolower($1)=="host"{h=(NF==2 && $2=="huginn")} h && tolower($1)=="identityfile"{print $2; exit}' "$cfg")"
  fi
  if [ -n "$key" ]; then
    case "${key##*/}" in *huginn*) ours=1 ;; esac
    [ -z "$ours" ] && [ -f "$key.pub" ] && grep -qi huginn "$key.pub" && ours=1
  fi
  if [ -n "$all" ] && [ -f "$cfg" ]; then
    # Only a stanza that is EXACTLY `Host huginn`. `Host huginn build01` serves
    # another alias too, and taking it out would break a host this never
    # installed.
    awk 'tolower($1)=="host"{drop=(NF==2 && $2=="huginn")} !drop' "$cfg" > "$cfg.huginn-uninstall" \
      && mv -f "$cfg.huginn-uninstall" "$cfg" && chmod 600 "$cfg" \
      && removed+=("the 'Host huginn' stanza in $cfg")
    rm -f "$cfg.huginn-uninstall"
    if [ -n "$ours" ] && [ -f "$key" ]; then
      rm -f "$key" "$key.pub"
      removed+=("$key and $key.pub (huginn's own key)")
    elif [ -n "$key" ]; then
      left+=("$key - NOT removed: nothing marks it as huginn's (no 'huginn' in the filename or the .pub comment), and it is very likely your general SSH key")
    fi
  elif [ -n "$key" ]; then
    left+=("$key and the 'Host huginn' stanza in $cfg - kept (use 'huginn uninstall --all' to remove them)")
  fi

  echo "Removed:"
  if [ "${#removed[@]}" -eq 0 ]; then echo "  (nothing - was huginn installed here?)"; fi
  for a in "${removed[@]}"; do echo "  $a"; done
  if [ "${#left[@]}" -gt 0 ]; then
    echo
    echo "Left behind, on purpose or because it could not be done:"
    for a in "${left[@]}"; do echo "  $a"; done
  fi
  echo
  # The function is still defined in THIS shell - the file it came from is gone,
  # but bash does not forget what it has already sourced.
  echo "The 'huginn' command is still loaded in this shell. Open a new one, or: unset -f huginn rclaude rcc"
}

# ONE session-name rule for the whole product: lowercase letters, digits, '_'
# and '-', starting with a letter, digit or '_', at most 50 characters. Compared
# case-folded, because names are case-insensitive here (see _huginn_canon_name).
# It still keeps a typo'd flag (e.g. 'huginn --hlp') from falling through to the
# attach path and spawning a junk tmux session - a leading '-' is not a name -
# and it still keeps names safe to pass through the remote shell. Enforced again
# server-side in cc.
#
# ⚠ '-' IS LEGAL, AND USED TO BE REFUSED HERE. The daemon accepts a dash and
# honours it end to end, the desktop dialogs offer one, and keyboard-made
# sessions routinely carry one (dev-phonefarm) - so `huginn build-box`, and
# solo/kill/end/archive/rename of it, refused LOCALLY, before any network, for a
# session `huginn ls` had just listed and tab-completion had just offered. The
# same session was openable from both GUI clients, and `huginn revive build-box`
# was accepted while `huginn archive build-box` was not.
#
# ⚠ '.' IS BANNED, everywhere, on purpose. tmux silently rewrites '.' to '_' in
# a session name, so a dotted name is a name that comes back different from the
# one that was asked for - the daemon, the desktop and the phone ban it too.
_huginn_valid_name() { [[ "${1,,}" =~ ^[a-z0-9_][a-z0-9_-]{0,49}$ ]]; }
# The refusal has TWO causes and they are not the same message. A typo is the
# caller's mistake. A name the GUI clients can mint but this one cannot address
# is a session sitting on the host, and "invalid session name" about a row
# `huginn ls` has just printed sends somebody looking for an error they did not
# make. The completion cache is already live `tmux ls` output, so telling them
# apart costs no round trip and no ssh.
#
# ⚠ AND IT SAYS WHICH RULE, because two of the three were never mentioned. Both
# `huginn kill -box` and a 51-character name used to produce "use lowercase
# letters, digits, _ and -; no dots, spaces or *" - a sentence that describes a
# name which is, by that text, perfectly valid, so the refusal read as a bug in
# the client. The rule is the daemon's own (nameProblem, server/appd/
# huginn-appd.js): ^[a-z0-9_][a-z0-9_-]{0,49}$ - at most 50 characters, and '-'
# may not lead. Named one at a time, in the daemon's order, so the sentence a
# person gets here is the sentence they would have got from the host.
_huginn_bad_name() {   # $1 = the name, $2 = the noun for the message
  local H="${HUGINN_HOST:-huginn}" n="$1" why=
  if [ -n "$_HUGINN_SESS_CACHE" ] && grep -qxF -- "$n" <<<"$_HUGINN_SESS_CACHE"; then
    echo "huginn: '$n' exists on the host but this client cannot address it" >&2
    echo "        (names here are lowercase letters, digits, _ and -). Rename it from the" >&2
    echo "        desktop app, or: ssh $H -t \"tmux attach -t '=$n'\"" >&2
    return 1
  fi
  case "$n" in
    '')   why='a session needs a name' ;;
    *.*)  why='a "." is rewritten to "_" by tmux, so the name you asked for would not be the name you got' ;;
    [!a-zA-Z0-9_]*)
          why="it starts with '${n:0:1}' - a name starts with a letter, digit or _" ;;
  esac
  if [ -z "$why" ] && [ "${#n}" -gt 50 ]; then
    why="it is ${#n} characters long - a name is at most 50"
  fi
  echo "huginn: invalid ${2:-session name} '$n'" >&2
  [ -z "$why" ] || echo "        $why" >&2
  echo "        (letters, digits, _ and -, starting with a letter, digit or _, up to 50" >&2
  echo "         characters; no dots, spaces or *. Case-insensitive.)" >&2
  return 1
}
# tmux resolves -t targets by EXACT match, then PREFIX, then glob. A unique prefix
# resolves silently, so 'huginn kill andvari' would destroy a session actually named
# 'andvariautofill', and 'huginn solo jt' would evict the real client of 'jtyper'.
# Anchoring with '=' forces exact match (tmux(1) "exact-match"), so a typo now fails
# loudly with "can't find session" instead of hitting the wrong session.
_huginn_tmux_target() { printf '=%s' "$1"; }
# Session names are case-INSENSITIVE: we lowercase before touching tmux so 'Test'
# and 'test' resolve to the same session (tmux itself is case-sensitive). Canonicalized
# here for every tmux-facing path AND again server-side in cc as the backstop.
_huginn_canon_name() { printf '%s' "${1,,}"; }

# Reach huginn-appd, which listens on the HOST's loopback. The bearer token is
# root-only on the host, so the call runs THERE (over the ssh alias) and only the
# result comes back - the token never touches a client device.
# $1=method $2=path $3=optional JSON request body.
#
# ⚠ NOT `curl -sf`, WHICH THREW THE ANSWER AWAY. -f discards the response BODY on
# every HTTP >= 400 and exits 22, collapsing four different refusals into one
# exit code: `huginn end` printed "is huginn-appd running? is the session a live
# Claude pane?" - naming two causes that are both fine - for the daemon's 409
# "answer the waiting question first", for the 409 "no Claude state recorded ...
# pass force", for a 404, and for a 500 "tmux: ...". The Kotlin client sets
# expectSuccess=false for exactly this reason, and this file's own `archive`
# comment already named `curl -sf` as the reason archive is rendered host-side.
#
# The status rides home on its own LAST line (-w '\n%{http_code}'), so one ssh
# still answers both questions. Prints the body and sets _HUGINN_APPD_CODE.
# Exit: 0 = 2xx  ·  1 = an HTTP error, body holds the daemon's own `error`
#       7 = curl never CONNECTED (code 000) or ssh itself failed - and that is
#           the ONLY case in which a caller may fall back to raw tmux.
#
# ⚠ AND RC 7 IS TWO DIFFERENT FACTS, so the caller is told WHICH. "the host
# refused the connection" and "the host is fine, the daemon is not" are a network
# problem and a systemctl problem, and `huginn end` answered the first with "could
# not reach huginn-appd on <host>" - blaming a daemon that was never asked, on a
# box that was never reached, and throwing ssh's own sentence away with
# 2>/dev/null. ssh's OWN failures exit 255 (ssh(1) "exit status"); anything else
# non-zero is the REMOTE command's code, i.e. the connection WAS made and curl (or
# a missing curl) is what failed.
#
# ⚠⚠ AND THE DIAGNOSIS RIDES THE STDOUT, NOT A VARIABLE. Every caller reads this
# function through `$( )`, which is a SUBSHELL: a global set in here is gone by
# the time the caller reads it (that is also why `${_HUGINN_APPD_CODE}` in the
# `end` and `kill` fallbacks has always printed empty). So on the rc-7 path -
# where there is no body to collide with - the first line is a marker naming
# which half failed and the rest is whatever ssh or curl said, verbatim, for
# _huginn_appd_why to render. The stderr is captured through a temp file for the
# same subshell reason; with no mktemp (a stripped Termux, a busybox) the marker
# still travels and only the quoted sentence is lost.
_HUGINN_APPD_CODE=
_huginn_appd() {
  local H="${HUGINN_HOST:-huginn}" raw body data='' rc=0 ef= cmd err=
  _HUGINN_APPD_CODE=
  [ -z "${3:-}" ] || data=" -H 'Content-Type: application/json' --data '$3'"
  cmd="curl -s -w '\n%{http_code}' -X $1$data -H \"Authorization: Bearer \$(cat /etc/huginn-appd/token 2>/dev/null)\" \"http://127.0.0.1:8787$2\""
  ef="$(mktemp "${TMPDIR:-/tmp}/huginn-ssh.XXXXXX" 2>/dev/null)" || ef=
  if [ -n "$ef" ]; then
    raw="$(ssh -T "$H" "$cmd" 2>"$ef")" || rc=$?
    err="$(cat "$ef" 2>/dev/null)"
    rm -f "$ef"
  else
    raw="$(ssh -T "$H" "$cmd" 2>/dev/null)" || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then
    if [ "$rc" -eq 255 ]; then printf '%s\n' "$_HUGINN_TRANSPORT ssh"
    else printf '%s\n' "$_HUGINN_TRANSPORT remote"; fi
    [ -z "$err" ] || printf '%s\n' "$err"
    return 7
  fi
  _HUGINN_APPD_CODE="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  # No newline at all means curl printed nothing but the status line.
  [ "$body" != "$raw" ] || body=''
  printf '%s' "$body"
  # ⚠ AND THE STATUS RIDES HOME THE SAME WAY, for the same subshell reason: the
  # `huginn-appd answered HTTP $_HUGINN_APPD_CODE` fallback in `end` and `kill`
  # reads a global this function set inside a `$( )`, so it has always printed
  # the sentence with no number in it - on exactly the refusals where the daemon
  # sent no sentence of its own, i.e. the ones with nothing else to go on.
  # Appended, never substituted: the body is still the body, and the marker is on
  # its own last line where `_huginn_appd_error`'s regex cannot see it.
  case "$_HUGINN_APPD_CODE" in
    2*) return 0 ;;
    *)  printf '\n%s http %s\n' "$_HUGINN_TRANSPORT" "$_HUGINN_APPD_CODE" ;;
  esac
  [ "$_HUGINN_APPD_CODE" != 000 ] || return 7
  return 1
}
# The HTTP status out of what _huginn_appd printed, or empty.
_huginn_appd_http() {
  printf '%s' "$1" | sed -n "s/^$_HUGINN_TRANSPORT http \([0-9][0-9]*\)$/\1/p" | tail -1
}
# The marker _huginn_appd prints on its rc-7 path. Unlikely enough in a JSON body
# that a caller cannot confuse the two, and defined once so the writer and the
# reader cannot drift.
_HUGINN_TRANSPORT='huginn-transport:'
# Why the last _huginn_appd call could not be made, on stderr, indented under the
# caller's own line. Nothing is invented: if ssh said something that is what
# appears, and when nothing but curl failed, the fact that the connection WAS
# made is itself the answer - the daemon is the suspect and systemctl is the
# next command.
_huginn_appd_why() {   # $1 = the host, $2 = what _huginn_appd printed
  local first rest
  first="${2%%$'\n'*}"
  rest="${2#"$first"}"; rest="${rest#$'\n'}"
  case "$first" in
    "$_HUGINN_TRANSPORT ssh")
      echo "huginn: could not reach the host '$1' over ssh - huginn-appd was never asked" >&2 ;;
    *)
      echo "huginn: $1 answered, but huginn-appd did not" >&2 ;;
  esac
  case "$first" in "$_HUGINN_TRANSPORT"*) ;; *) rest='' ;; esac
  [ -z "$rest" ] || printf '%s\n' "$rest" | sed 's/^/        /' >&2
  case "$first" in
    "$_HUGINN_TRANSPORT ssh") ;;
    *) echo "        (ssh $1 systemctl status huginn-appd)" >&2 ;;
  esac
}
# The daemon's own sentence out of an error body ({"error":"..."}), or empty.
_huginn_appd_error() {
  printf '%s' "$1" | sed -n 's/.*"error"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# --- desktop download links ---
# The Compose desktop client ships as a PUBLIC GitHub release (tag desktop-v<ver>),
# and that is also where the installed app's own self-updater fetches from - so the
# link printed here is the real distribution source, not a mirror that can drift.
# Deliberately NOT the daemon's /v1/desktop-kt: it serves the same bytes, but every
# route on it needs the host's bearer token and a browser has no way to send one.
# That also makes `desktop` the one verb that works from a device which cannot reach
# the host at all - it is a GitHub fetch, not an ssh.

# GET a URL as text. curl -> wget -> the host (which always has both), so a stock
# Windows/Termux shell without curl still resolves the link instead of erroring.
_huginn_get() {
  if command -v curl >/dev/null 2>&1; then
    curl -sfL --max-time 20 "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO- --timeout=20 "$1"
  else
    ssh -T -o BatchMode=yes -o ConnectTimeout=10 "${HUGINN_HOST:-huginn}" "curl -sfL --max-time 20 '$1'" 2>/dev/null
  fi
}

# Newest desktop-v* release, printed as: <tag>\n<manifest json>.
# Filtered by TAG rather than read from /releases/latest, because four components
# publish into this one feed (v*, app-v*, appd-v*, desktop-v*) and "latest" is
# simply whichever shipped last - usually not the desktop.
# Unauthenticated API, so 60 requests/hour per IP; this is one call per invocation.
#
# NOTE: THE WINNER IS DECIDED ON SEMVER, NOT ON FEED POSITION. This took the FIRST
# desktop-v* tag in the response, on the assumption that GitHub returns releases
# newest-first. It does not - verified 2026-08-25, with desktop-v0.8.9 (created
# 04:10) ahead of desktop-v0.8.13 (created 09:39) in the same page. So
# `huginn desktop linux` handed out an installer FOUR versions stale and looked
# entirely healthy doing it, because the url was well-formed and did exist.
#
# The Kotlin updater already got this right (GithubReleaseIndex.newest picks
# maxWith Semver.compare, and its comment says the winner is decided on semver of
# the tail); the shell clients simply never learned the same lesson.
_huginn_desktop_release() {
  local tag ver json
  ver="$(_huginn_get "https://api.github.com/repos/$HUGINN_REPO/releases?per_page=60" | tr -d '\n' \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"desktop-v[^"]*"' \
        | sed 's/.*"desktop-v\([^"]*\)".*/\1/' \
        | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
  [ -n "$ver" ] || return 1
  tag="desktop-v$ver"
  # manifest.json is a release ASSET (the same one the updater verifies sha256
  # against), so the filenames come from the release itself - nothing here has to
  # guess how electron-builder or jpackage named an artifact.
  json="$(_huginn_get "https://github.com/$HUGINN_REPO/releases/download/$tag/manifest.json" | tr -d '\n')"
  [ -n "$json" ] || return 1
  printf '%s\n%s\n' "$tag" "$json"
}

# The {...} value of one platform key, and scalar reads within it. Scoped in two
# steps on purpose: a single regex over the whole manifest would match the LAST
# "file" in it, i.e. the wrong platform's.
_huginn_json_obj() { printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*{\([^{}]*\)}.*/\1/p"; }
_huginn_json_str() { printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"; }
_huginn_json_num() { printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p"; }

# Which artifact THIS machine could actually run. Empty is a normal answer: on a
# phone (Termux) or a Mac there is no desktop build, and the useful behaviour there
# is to print both links so they can be sent to a laptop - not to fail.
_huginn_desktop_platform() {
  case "$(uname -o 2>/dev/null)" in Android*) return ;; esac
  case "$(uname -s 2>/dev/null)" in
    Linux*)                        echo 'linux-x64' ;;
    MINGW*|MSYS*|CYGWIN*|Windows*) echo 'windows-x64' ;;
  esac
}

# Which Linux family this is, from os-release ID + ID_LIKE: arch | debian | other.
# Omarchy, EndeavourOS, Manjaro and CachyOS all say ID_LIKE=arch; Ubuntu and Mint
# say debian. HUGINN_OS_RELEASE only exists so scripts/test-client.sh can play
# every distro on one box - it names a file to READ, nothing more.
_huginn_linux_distro() {
  local f="${HUGINN_OS_RELEASE:-/etc/os-release}" ids
  [ -r "$f" ] || f=/usr/lib/os-release
  ids="$( { sed -n 's/^ID=//p; s/^ID_LIKE=//p' "$f" 2>/dev/null || true; } | tr -d "\"'" | tr 'A-Z' 'a-z' | tr '\n' ' ')"
  case " $ids " in
    *' arch '*|*' archlinux '*) echo arch ;;
    *' debian '*|*' ubuntu '*)  echo debian ;;
    *)                          echo other ;;
  esac
}

# --- the portable, self-updating Linux install ---
# Layout (the desktop app's PortableInstall.kt reads and rewrites exactly this, so
# the two must agree - change one, change both):
#   R=${XDG_DATA_HOME:-~/.local/share}/huginn-desktop
#   R/.huginn-portable          marker: "huginn laid this out" (the app refuses to
#                               self-update a tree without it)
#   R/versions/<ver>/           one unpacked app-image per version
#   R/current -> versions/<ver> RELATIVE link; the only thing an update changes
#   ~/.local/bin/huginn-desktop -> R/current/bin/huginn-desktop-kt
#   $XDG_DATA_HOME/applications/huginn-desktop-kt.desktop  (Exec = R/current/...)
# Everything outside R names `current`, never a version, so an update - from here
# or from the app's own "Install and restart" - never has to find and rewrite a
# menu entry, the huginn:// handler or the autostart entry.
#
# ⚠ THE DESKTOP FILE IS huginn-desktop-kt.desktop, NOT A NEW NAME. The app's
# SchemeRegistrar writes that same per-user file on every launch; a second name
# would give the menu two Huginns and huginn:// two competing handlers. Its text
# here matches SchemeRegistrar.linuxDesktopEntry byte for byte, so the app's
# rewrite is a no-op.
#
# ⚠ NEVER sudo. A distro package of the old shape is NAMED with its removal
# command and left alone: this client does not ask for, or run as, root.
_huginn_desktop_root() { echo "${XDG_DATA_HOME:-$HOME/.local/share}/huginn-desktop"; }

_huginn_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# The listing check, BEFORE anything is unpacked: one top-level dir named $2, no
# absolute names, no `..` component, symlinks only to places inside $2, hardlinks
# only to entries inside $2, nothing but files/dirs/links. The same rules as the
# app's PortableInstall.checkTar. Reads GNU tar's verbose listing; prints the
# offending entry and returns 1 on refusal.
_huginn_desktop_tar_ok() {
  local listing
  listing="$(LC_ALL=C tar --numeric-owner -tzvf "$1" 2>/dev/null)" || { echo "not a readable .tar.gz" >&2; return 1; }
  [ -n "$listing" ] || { echo "the archive is empty" >&2; return 1; }
  printf '%s\n' "$listing" | LC_ALL=C awk -v top="$2" '
    function bad(why) { print why > "/dev/stderr"; failed = 1; exit 1 }
    # Split a path into seg[], dropping "" and "."; returns -1 on any "..".
    function split_path(p, seg,   n, i, raw, k) {
      n = split(p, raw, "/"); k = 0
      for (i = 1; i <= n; i++) {
        if (raw[i] == "" || raw[i] == ".") continue
        if (raw[i] == "..") return -1
        seg[++k] = raw[i]
      }
      return k
    }
    {
      type = substr($0, 1, 1)
      # perms owner/group size date time NAME... - strip the five fixed columns.
      rest = $0
      if (!match(rest, /^[^ ]+ +[^ ]+ +[0-9,]+ +[0-9-]+ +[0-9:]+ /)) bad("unreadable listing line: " $0)
      rest = substr(rest, RLENGTH + 1)
      name = rest; target = ""
      if (type == "l") { i = index(rest, " -> ");     if (!i) bad("unreadable symlink: " rest); name = substr(rest, 1, i - 1); target = substr(rest, i + 4) }
      if (type == "h") { i = index(rest, " link to "); if (!i) bad("unreadable hardlink: " rest); name = substr(rest, 1, i - 1); target = substr(rest, i + 9) }
      if (type != "-" && type != "d" && type != "l" && type != "h") bad("unsupported entry type " type ": " name)
      if (substr(name, 1, 1) == "/") bad("absolute path: " name)
      delete seg; k = split_path(name, seg)
      if (k < 0) bad("path climbs out with ..: " name)
      if (k == 0) bad("an entry names the archive root itself: " name)
      if (seg[1] != top) bad("entry outside " top "/: " name)
      if (k == 1 && type != "d") bad("the top-level entry is not a directory")
      if (type == "l") {
        if (target == "" || substr(target, 1, 1) == "/") bad("symlink " name " -> " target " is absolute or empty")
        # Resolve from the link'"'"'s own directory, the way the kernel will.
        depth = k - 1; n = split(target, raw, "/")
        for (j = 1; j <= n; j++) {
          if (raw[j] == "" || raw[j] == ".") continue
          if (raw[j] == "..") { depth--; if (depth < 1) bad("symlink " name " -> " target " escapes " top "/") }
          else depth++
        }
      }
      if (type == "h") {
        delete tseg; t = split_path(target, tseg)
        if (t < 1 || tseg[1] != top || substr(target, 1, 1) == "/") bad("hardlink " name " -> " target " escapes " top "/")
      }
    }
  '
}

_huginn_desktop_entry() {   # $1 = stable launcher; the same text as SchemeRegistrar.linuxDesktopEntry
  local exe="$1" app
  app="${exe%/bin/huginn-desktop-kt}"
  printf '[Desktop Entry]\nType=Application\nName=Huginn\nComment=huginn desktop client\n'
  printf 'Exec="%s" %%u\n' "$(printf '%s' "$exe" | sed 's/%/%%/g')"
  printf 'Icon=%s/lib/huginn-desktop-kt.png\n' "$app"
  printf 'Terminal=false\nCategories=Development;Utility;\nMimeType=x-scheme-handler/huginn;\n'
}

# A SUBSHELL body - ( ), not { } - so `exit` and the temp-dir trap stay inside it
# and never touch the shell that sourced this file.
_huginn_desktop_install() (
  set -u
  # Three ways to be read: a person's `huginn desktop install` (a titled
  # block of lines), a step of `huginn setup` (HUGINN_SETUP: lines, no title),
  # and `huginn update`'s log (HUGINN_UI_COMPACT: plain text on stdout, which
  # the update turns into its own line).
  local t0=$SECONDS
  fail() { _huginn_ui_line fail desktop "$*"; exit 1; }
  msg() { if [ -n "${HUGINN_UI_COMPACT:-}" ]; then echo "  $*"; else printf '%18s%s%s%s\n' '' "$(_huginn_ui_c 90)" "$*" "$(_huginn_ui_c 0)" >&2; fi; }
  [ -n "${HUGINN_UI_COMPACT:-}${HUGINN_SETUP:-}" ] || _huginn_ui_head "huginn desktop install"
  case "$(uname -s 2>/dev/null)" in Linux*) ;; *) fail "desktop install is for Linux; on Windows run the installer from: huginn desktop windows" ;; esac
  case "$(uname -o 2>/dev/null)" in Android*) fail "there is no desktop build for a phone" ;; esac
  case "$(uname -m 2>/dev/null)" in x86_64|amd64) ;; *) fail "the desktop is only built for x86-64 (this is $(uname -m))" ;; esac
  command -v tar >/dev/null 2>&1 || fail "tar is needed to unpack the app"

  local rel tag man ver obj file sha base R L cur prev
  rel="$(_huginn_desktop_release)" || fail "could not read the desktop release feed (offline, or GitHub rate-limited this IP)."
  tag="${rel%%$'\n'*}"; man="${rel#*$'\n'}"
  ver="$(_huginn_json_str "$man" version)"
  printf '%s' "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || fail "$tag carries an unreadable version '$ver'"
  obj="$(_huginn_json_obj "$man" linux-x64-portable)"
  file="$(_huginn_json_str "$obj" file)"; sha="$(_huginn_json_str "$obj" sha256)"
  [ -n "$file" ] || fail "$tag has no portable Linux build (it predates them) - use: huginn desktop"
  # A NAME, never a path: it is joined onto a temp dir below.
  [ "$file" = "huginn-desktop-kt-$ver-linux-x64.tar.gz" ] || fail "$tag names an unexpected portable file '$file' - refused"
  printf '%s' "$sha" | grep -qE '^[0-9a-f]{64}$' || fail "$tag's manifest carries no usable sha256 for $file - refused"
  base="https://github.com/$HUGINN_REPO/releases/download/$tag"

  R="$(_huginn_desktop_root)"
  L="$R/current/bin/huginn-desktop-kt"
  cur="$(readlink "$R/current" 2>/dev/null || true)"

  if [ "$cur" = "versions/$ver" ] && [ -x "$R/versions/$ver/bin/huginn-desktop-kt" ]; then
    if [ -n "${HUGINN_UI_COMPACT:-}" ]; then echo "  Huginn Desktop $ver is already installed in $R - repairing the links."
    else _huginn_ui_line ok desktop "$ver is already installed in ${R/#$HOME/\~} - links repaired"; fi
  else
    local tmp
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/huginn-desktop.XXXXXX")" || fail "could not make a temp dir"
    trap 'rm -rf "$tmp"' EXIT
    command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || fail "needs curl or wget to download $file"
    _huginn_ui_download "$base/$file" "$tmp/$file" "$(_huginn_json_num "$obj" size)" desktop || fail "download failed: $base/$file"
    # THE GATE. These builds are unsigned; the manifest's sha256 is the only
    # integrity check between GitHub and an executable in your home directory.
    local got
    got="$(_huginn_sha256 "$tmp/$file")"
    [ "$got" = "$sha" ] || fail "$file did not match its sha256 (got ${got:0:16}..., want ${sha:0:16}...) - discarded, nothing installed"
    _huginn_desktop_tar_ok "$tmp/$file" "huginn-desktop-kt-$ver" || fail "refused $file: its listing would write outside huginn-desktop-kt-$ver/"

    mkdir -p "$R/versions" || fail "could not create $R"
    : > "$R/.huginn-portable"
    local stage="$R/versions/.tmp-$ver"
    rm -rf "$stage"; mkdir -p "$stage"
    tar -xzf "$tmp/$file" -C "$stage" --no-same-owner || { rm -rf "$stage"; fail "unpacking $file failed"; }
    # What actually LANDED, checked again: two readers of one archive can disagree.
    local top="$stage/huginn-desktop-kt-$ver" lnk real topreal
    topreal="$(cd "$top" 2>/dev/null && pwd -P)" || { rm -rf "$stage"; fail "$file did not unpack its directory"; }
    while IFS= read -r lnk; do
      real="$(readlink -f "$lnk" 2>/dev/null || true)"
      case "$real" in "$topreal"/*) ;; *) rm -rf "$stage"; fail "unpacked link $lnk escapes the app dir" ;; esac
    done < <(find "$top" -type l 2>/dev/null)
    [ -x "$top/bin/huginn-desktop-kt" ] || { rm -rf "$stage"; fail "$file has no executable bin/huginn-desktop-kt"; }
    rm -rf "$R/versions/$ver"
    mv "$top" "$R/versions/$ver" || { rm -rf "$stage"; fail "could not move $ver into place"; }
    rmdir "$stage" 2>/dev/null || rm -rf "$stage"

    # THE SWAP: a new relative link renamed over the old one - atomic, so the
    # menu entry never points at nothing, even mid-update.
    ln -sfn "versions/$ver" "$R/.current.tmp.$$" && mv -Tf "$R/.current.tmp.$$" "$R/current" \
      || { rm -f "$R/.current.tmp.$$"; fail "could not switch $R/current to $ver"; }
    prev="${cur#versions/}"
    # Keep the new version and the one it replaced (the rollback, and possibly
    # still running). Anything else goes - except a version some process is still
    # running from, which is pruned by a later install, never from under it.
    local d v
    for d in "$R"/versions/* "$R"/versions/.tmp-*; do
      [ -e "$d" ] || continue
      v="${d##*/}"
      [ "$v" = "$ver" ] && continue
      [ -n "$prev" ] && [ "$v" = "$prev" ] && continue
      if command -v pgrep >/dev/null 2>&1 && pgrep -f "$R/versions/$v/" >/dev/null 2>&1; then continue; fi
      rm -rf "$d"
    done
    if [ -n "${HUGINN_UI_COMPACT:-}" ]; then echo "  installed $ver into $R"
    else _huginn_ui_line ok desktop "$ver installed in ${R/#$HOME/\~}"; fi
  fi

  # Links and menu entry - written on every run, which is what makes a re-run a repair.
  mkdir -p "$HOME/.local/bin"
  ln -sfn "$L" "$HOME/.local/bin/huginn-desktop"
  local apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  mkdir -p "$apps"
  _huginn_desktop_entry "$L" > "$apps/huginn-desktop-kt.desktop"
  command -v xdg-mime >/dev/null 2>&1 && xdg-mime default huginn-desktop-kt.desktop x-scheme-handler/huginn >/dev/null 2>&1
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$apps" >/dev/null 2>&1
  # An autostart entry from a /opt install would keep starting the package after
  # it is removed. Repointed only if one exists - turning it ON is the app's
  # setting, not this verb's.
  local auto="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/huginn-desktop-kt.desktop"
  if [ -f "$auto" ] && ! grep -qF "Exec=\"$L\"" "$auto"; then
    sed -i "s|^Exec=.*|Exec=\"$L\"|" "$auto" && msg "autostart entry now starts $L"
  fi

  msg "in your app menu, and from a shell: huginn-desktop (~/.local/bin)"
  case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) msg "(add ~/.local/bin to PATH to run 'huginn-desktop' from a shell)" ;; esac
  msg "settings in ${XDG_CONFIG_HOME:-$HOME/.config}/huginn-desktop-kt are kept; updates install from inside the app"
  if command -v pacman >/dev/null 2>&1 && pacman -Q huginn-desktop-kt >/dev/null 2>&1; then
    _huginn_ui_line warn desktop "a pacman package of Huginn Desktop is also installed - remove it so the menu has one Huginn:"
    msg "sudo pacman -R huginn-desktop-kt"
  fi
  if command -v dpkg >/dev/null 2>&1 && dpkg -s huginn-desktop-kt >/dev/null 2>&1; then
    _huginn_ui_line warn desktop "the .deb of Huginn Desktop is also installed - remove it so the menu has one Huginn:"
    msg "sudo apt remove huginn-desktop-kt"
  fi
  [ -n "${HUGINN_UI_COMPACT:-}${HUGINN_SETUP:-}" ] || printf '    %sdone in %s s%s\n' "$(_huginn_ui_c 90)" "$((SECONDS - t0))" "$(_huginn_ui_c 0)" >&2
)

# `huginn desktop update` (and the desktop step of `huginn update`): bring the
# installed desktop app to the newest desktop-v release - the per-user app
# through the same verified path as `huginn desktop install`, a Debian or Arch
# package by downloading it, checking its sha256 against the release manifest
# and handing it to the package manager with sudo (in a terminal; otherwise the
# exact command is printed). Nothing installed: nothing to do, silently.
_huginn_desktop_update() {
  local R portable='' packaged='' rel tag man ver cur
  R="$(_huginn_desktop_root)"
  [ -f "$R/.huginn-portable" ] && portable=1
  [ -d /opt/huginn-desktop-kt ] && packaged=1
  if [ -z "$portable$packaged" ]; then
    # In `huginn update` every component gets its line; alone, nothing to say.
    [ -n "${HUGINN_UI_COMPACT:-}" ] && _huginn_ui_line skip desktop "not installed (huginn desktop install)"
    return 0
  fi
  rel="$(_huginn_desktop_release)" || { _huginn_ui_line fail desktop "could not read the release feed - not updated"; return 1; }
  tag="${rel%%$'\n'*}"; man="${rel#*$'\n'}"
  ver="$(_huginn_json_str "$man" version)"
  printf '%s' "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || { _huginn_ui_line fail desktop "$tag carries an unreadable version - not updated"; return 1; }
  if [ -n "$portable" ]; then
    cur="$(readlink "$R/current" 2>/dev/null)"; cur="${cur#versions/}"
    if [ -n "$cur" ] && ! _huginn_core_newer "$cur" "$ver"; then
      _huginn_ui_line ok desktop "$cur is current"
    else
      local dlog; dlog="$(mktemp)"
      # Its own report to the log (the bar and any refusal still reach the
      # terminal on stderr); `huginn desktop install` prints it in full.
      if HUGINN_UI_COMPACT=1 _huginn_desktop_install >"$dlog"; then
        _huginn_ui_line ok desktop "${cur:-?} -> $ver   installed"
        if command -v pgrep >/dev/null 2>&1 && pgrep -f "$R/versions/" >/dev/null 2>&1; then
          _huginn_ui_line warn desktop "the open app runs ${cur:-the old version} until you quit it from its tray"
        fi
        rm -f "$dlog"
      else
        _huginn_ui_line fail desktop "the update did not finish:"; sed 's/^/            /' "$dlog" >&2; rm -f "$dlog"; return 1
      fi
    fi
  fi
  if [ -n "$packaged" ]; then
    local mgr='' have='' key='' obj file sha base tmp got
    if command -v pacman >/dev/null 2>&1 && pacman -Qq huginn-desktop-kt >/dev/null 2>&1; then
      mgr=pacman; key=linux-x64-arch; have="$(pacman -Q huginn-desktop-kt 2>/dev/null | awk '{print $2}')"
    elif command -v dpkg-query >/dev/null 2>&1 && dpkg-query -W -f='${Status}' huginn-desktop-kt 2>/dev/null | grep -q 'install ok installed'; then
      mgr=apt; key=linux-x64; have="$(dpkg-query -W -f='${Version}' huginn-desktop-kt 2>/dev/null)"
    fi
    have="${have%-*}"
    if [ -z "$mgr" ]; then
      _huginn_ui_line skip desktop "/opt/huginn-desktop-kt belongs to no package this can update"
    elif [ -n "$have" ] && ! _huginn_core_newer "$have" "$ver"; then
      _huginn_ui_line ok desktop "$have is current (the $mgr package)"
    else
      obj="$(_huginn_json_obj "$man" "$key")"
      file="$(_huginn_json_str "$obj" file)"; sha="$(_huginn_json_str "$obj" sha256)"
      base="https://github.com/$HUGINN_REPO/releases/download/$tag"
      if [ -z "$file" ] || ! printf '%s' "$sha" | grep -qE '^[0-9a-f]{64}$'; then
        _huginn_ui_line skip desktop "$tag has no usable $key package"
      elif ! { [ -t 0 ] && [ -t 1 ]; }; then
        _huginn_ui_line warn desktop "the $mgr package is ${have:-?}, $ver is out; to update it:"
        if [ "$mgr" = pacman ]; then echo "            curl -fLO $base/$file && sudo pacman -U ./$file" >&2; else echo "            curl -fLO $base/$file && sudo apt install ./$file" >&2; fi
      else
        tmp="$(mktemp -d "${TMPDIR:-/tmp}/huginn-desktop-pkg.XXXXXX")" || return 1
        if ! _huginn_ui_download "$base/$file" "$tmp/$file" "$(_huginn_json_num "$obj" size)" desktop; then
          rm -rf "$tmp"; _huginn_ui_line fail desktop "download failed: $base/$file"; return 1
        fi
        got="$(_huginn_sha256 "$tmp/$file")"
        if [ "$got" != "$sha" ]; then
          rm -rf "$tmp"; _huginn_ui_line fail desktop "$file did not match its sha256 - discarded, nothing installed"; return 1
        fi
        _huginn_ui_line ok desktop "${have:-?} -> $ver   downloaded and verified; installing the $mgr package (sudo asks for your password)"
        # A local FILE, never a URL: pacman -U <url> also fetches a .sig that
        # these releases do not publish, and aborts.
        if [ "$mgr" = pacman ]; then sudo pacman -U --noconfirm "$tmp/$file"; else sudo apt-get install -y "$tmp/$file"; fi
        local rc=$?; rm -rf "$tmp"
        [ "$rc" = 0 ] || { _huginn_ui_line fail desktop "the $mgr update did not finish"; return 1; }
        _huginn_ui_line ok desktop "$ver installed (the $mgr package)"
      fi
    fi
  fi
  return 0
}

_huginn_desktop_uninstall() (
  set -u
  local R apps auto
  R="$(_huginn_desktop_root)"
  apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications/huginn-desktop-kt.desktop"
  auto="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/huginn-desktop-kt.desktop"
  if [ ! -e "$R" ]; then
    echo "  no portable install at $R - nothing to remove."
  elif [ ! -f "$R/.huginn-portable" ]; then
    # Not ours: someone else's directory under the same name is never rm -rf'd.
    echo "huginn: $R has no .huginn-portable marker - not removing a tree this client did not lay out." >&2
    exit 1
  else
    if command -v pgrep >/dev/null 2>&1 && pgrep -f "$R/versions/" >/dev/null 2>&1; then
      echo "  (Huginn Desktop is still running from $R - quit it from the tray; it keeps running until then.)"
    fi
    rm -rf "$R"
    echo "  removed $R"
  fi
  # Only links and entries that point INTO R: a .deb/pacman install's own entry
  # under the same name is left alone.
  if [ -L "$HOME/.local/bin/huginn-desktop" ]; then
    case "$(readlink "$HOME/.local/bin/huginn-desktop")" in "$R"/*) rm -f "$HOME/.local/bin/huginn-desktop"; echo "  removed ~/.local/bin/huginn-desktop" ;; esac
  fi
  local f
  for f in "$apps" "$auto"; do
    [ -f "$f" ] && grep -qF "$R/" "$f" && rm -f "$f" && echo "  removed $f"
  done
  command -v update-desktop-database >/dev/null 2>&1 \
    && update-desktop-database "${XDG_DATA_HOME:-$HOME/.local/share}/applications" >/dev/null 2>&1
  echo "  Settings in ${XDG_CONFIG_HOME:-$HOME/.config}/huginn-desktop-kt were kept."
  exit 0
)

# --- auto-reconnecting attach ---
# The session lives in tmux ON the host, so a dropped link (laptop sleep, wifi
# flap) only severs the ssh client - the work keeps running. We re-run the attach
# whenever ssh exits non-zero (dropped link / transport failure - code varies by
# OS, e.g. 255); a clean tmux detach (Alt-d / Ctrl-b d) or normal shell exit
# returns 0 and ends the loop. ServerAlive* makes a half-open socket die in ~45s
# instead of hanging. Reconnect is dynamic: mirror if another device is still
# attached, else take it solo (full screen). Our own dead client (the ghost the
# dropped link left attached) still counts server-side, so the test is >=2
# clients (ghost + a real other) -> mirror, just the ghost (or none) -> solo.
# The count + attach run in ONE remote command, so the decision is atomic.
# Opt out: export HUGINN_NO_RECONNECT=1
#
# Tab naming: the terminal tab/window is renamed to the session name, so
# 'huginn costtracking' labels the tab 'costtracking' (Windows Terminal /
# iTerm / Termux). tmux set-titles defaults OFF, so the inner Claude TUI's title
# sequences are absorbed by tmux and never reach this terminal -> our title sticks
# for the whole session. Opt out: export HUGINN_NO_TITLE=1
#
# ⚠ AND IT IS PUT BACK, NOT OVERWRITTEN. This used to end with
# `printf '\033]0;%s\007' "${HOSTNAME:-shell}"` - which sets the title to the
# HOSTNAME, a string that was very probably never in that tab. Both docs promise
# restoration ("Restored when you leave"), so a tab that came back reading
# `huginn` instead of the `~/src/thing` it said before was the docs being wrong
# in the one place a person would notice.
#
# There is no portable way to ASK a terminal what its title is - the reply to
# CSI 21 t arrives on stdin, where it would race whatever the shell reads next -
# so the terminal is asked to remember it instead: CSI 22 t pushes the current
# title onto its own title stack and CSI 23 t pops it back (xterm's
# XTWINOPS/title-stack, supported by xterm, Windows Terminal, iTerm2, VTE,
# kitty, alacritty, foot). A terminal without the stack ignores both - unknown
# CSI sequences are swallowed, not printed - and keeps the session name, which
# is still a title about this window rather than a hostname.
#
# Only when stdout is a TTY: piped or captured, those escapes are bytes in
# somebody's file.
_huginn_attach() {
  # $1=host  $2=session (default main)  $3=non-empty => start in solo
  local H="$1" session="${2:-main}" solo="$3" delay=2 rc remote t0 elapsed quick=0 tgt
  session="$(_huginn_canon_name "$session")"   # case-insensitive: 'Test' -> 'test'
  tgt="$(_huginn_tmux_target "$session")"
  local title=
  { [ -z "$HUGINN_NO_TITLE" ] && [ -t 1 ]; } && title=1
  # Push first, THEN set: the stack has to be given the old title while it is
  # still the current one.
  [ -n "$title" ] && printf '\033[22;0t\033]0;%s\007' "$session"
  remote="cc $session${solo:+ solo}"
  while :; do
    t0=$SECONDS
    ssh -tt -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 "$H" "$remote"
    rc=$?
    elapsed=$(( SECONDS - t0 ))
    { [ "$rc" -eq 0 ] || [ -n "$HUGINN_NO_RECONNECT" ]; } && break
    # Distinguish "the link died" from "the remote command fails instantly". We do
    # NOT classify by exit code: real drops on Termux/Windows OpenSSH do not
    # reliably return 255, which is why v2026-06-16b widened this to any non-zero.
    # Duration is the honest signal - a session that lived 40 minutes dropped; one
    # that died in 0.3s twenty times in a row is a server-side error we are hiding.
    if [ "$elapsed" -lt 5 ]; then
      quick=$(( quick + 1 ))
      if [ "$quick" -ge 3 ]; then
        printf '\n%shuginn: %s is failing immediately (%s attempts, last exit %s) - giving up.%s\n  The remote error is printed above; fix it or run "huginn %s" again.\n' \
          "$(_huginn_color 91)" "$H" "$quick" "$rc" "$(_huginn_color 0)" "$session" >&2
        break
      fi
    else
      quick=0
    fi
    printf '\n%shuginn: link to %s dropped (ssh exit %s) - reconnecting in %ss (Ctrl-C to stop)...%s\n' "$(_huginn_color 93)" "$H" "$rc" "$delay" "$(_huginn_color 0)" >&2
    # Jittered sleep: every tab shares one tunnel, so an unjittered backoff makes
    # all of them re-handshake on the identical second after a single relay flap.
    sleep "$(awk -v d="$delay" 'BEGIN{srand();printf "%.1f", d*(0.75+rand()*0.5)}')" || { rc=130; break; }
    # Reconnect must NOT resurrect a session that was deliberately killed from
    # another device: cc falls through to `new-session -A`, which would spawn a
    # brand-new claude (burning quota) for a session you just ended. Check first.
    # Otherwise: mirror if another client is still attached, else solo (evicts the ghost).
    remote="tmux has-session -t $tgt 2>/dev/null || { echo 'huginn: session $session no longer exists on $H'; exit 0; }; if [ \"\$(tmux list-clients -t $tgt 2>/dev/null | wc -l)\" -ge 2 ]; then cc $session; else cc $session solo; fi"
    delay=$(( delay * 2 > 15 ? 15 : delay * 2 ))
  done
  [ -n "$title" ] && printf '\033[23;0t'   # pop: the title that was there before
  return "$rc"
}

# `huginn attach <name>`: attach to a session BY NAME, never by verb.
#
# ⚠ WHY IT EXISTS (desktop breaker, 2026-10-02): `huginn <word>` reads the word
# as a verb first and a session only when it is not one, and the daemon makes
# sessions called update, solo, list, uninstall. The desktop's "Open in
# terminal" ran `huginn 'update'` for a session named update - replacing this
# very file - and `huginn 'solo'` threw every other device off main. This is
# the form that cannot dispatch. The desktop checks for THIS FUNCTION'S NAME
# (`type _huginn_attach_named`) to know a CLI has `attach`, so it is not to be
# renamed.
_huginn_attach_named() {   # $1 = host  $2 = the session name
  _huginn_valid_name "$2" || { _huginn_bad_name "$2"; return 1; }
  _huginn_attach "$1" "$2"
}

# --- help -----------------------------------------------------------------
# The ONE list of verbs, and the ONE place each one is described. `huginn help`
# prints it whole; `huginn <verb> --help` prints the entry (and its continuation
# lines) for that verb alone - see _huginn_verb_help, which reads this rather
# than carrying a second copy that would drift from it.
_huginn_help_body() {
  # ---8<--- BEGIN GENERATED by scripts/gen-help.js from client/help-table.txt - DO NOT EDIT
  cat <<EOF

  huginn                      the console: sessions, devices, usage and the
                              core, with menus (in a terminal; set
                              HUGINN_CONSOLE=0, or pipe it, for 'main')
  huginn console              the console, by name
  huginn setup ssh            SSH to the huginn host (key + 'Host huginn'),
                              for attaching and the session verbs, on a
                              machine set up from the desktop or a package
  huginn main                 attach/create the live 'main' session
                              (run claude inside)
  huginn <name>               a separate named session
  huginn attach <name>        that session even when its name is a verb
                              (update, solo...)
  huginn solo [name]          attach + detach all OTHER clients
                              (resume solo / full screen)
  huginn list | ls            list sessions + attach status
  huginn status | st          health: uptime, auth, sessions, disk
  huginn rename <old> <new>   rename a session (alias: mv)
  huginn end <name> [--force] soft end: ask Claude to wrap up + commit, then
                              (if auto-end is on) end it once it goes idle
                              (--force: send it into a pane with no Claude
                              state)
  huginn rounds               what this host does on a schedule, and what it
                              found
  huginn headroom [--json]    usage left per account, what huginn moved or is
                              holding, and why
  huginn devices              machines that can run a chat in their own context
  huginn devices watch <name> on|off
                              tell the host this machine is always on: it
                              messages you when the machine stops checking in,
                              and when it is back
  huginn projects [--json]    clusters of sessions with roles, and who is
                              waiting
  huginn projects show <name> a project's members, one line each
  huginn projects new <name>  start one (launches its lead session)
                              --brief "<the lead's whole first message>"|-
                              [--kind software|infra|hardware|docs|research|
                              other] [--cwd DIR]
  huginn projects spawn <project>
                              approve the lead's proposal: create its members
  huginn projects msg <project> <from> <to> <text>
                              one line from one member to another
  huginn projects end <project>
                              end it AND wind its sessions down
                              [--now: end them outright | --keep-sessions:
                              leave them running]
  huginn core install         run huginn core at boot on this machine - as you,
                              with nobody signed in - and pair it with the host
                              [--no-tray | --tray: even from an ssh shell
                              (Linux) | --logon s4u|password: Windows only]
  huginn core status          is the core running and paired, what it offers
  huginn core start           start it now if it is set up but not running
  huginn core update          take the newest core release (restarts the core)
  huginn core uninstall       stop starting it at boot
  huginn device [status]      what THIS machine offers huginn, and what huginn
                              sees
  huginn device on            offer this machine
                              [--scope look|work|own] [--root DIR]
                              [--url URL: the daemon's address, when ssh
                              cannot say]
                              [--act-while-locked | --no-act-while-locked:
                              whether a lock screen withdraws 'act' on this
                              machine (default: it does)]
  huginn device off           stop offering it
  huginn device update        fetch a newer runner from the pinned mirror
  huginn device serve         the runner itself - what the service starts
  huginn device unit          print what keeps the runner up: a systemd unit
                              [--system], or on Windows a scheduled task that
                              runs it as you with nobody signed in
  huginn llm "prompt"         one question to the local tier (a serving
                              machine answers, not Claude)
                              [--model ROW] [--timeout S];
                              'huginn llm -' reads stdin
  huginn local [status]       what THIS machine serves as local AI, if anything
  huginn local on             serve local models from this machine
                              (optional, ~5 GB)
  huginn local plan           what 'on' would install here, without installing
                              anything
  huginn local persist        make an existing install survive logout
                              (system services, or linger)
  huginn local doctor         exits nonzero when this install is not doing
                              what it says
  huginn local update         re-fetch the manager and the pinned runtime
  huginn local unit           print the service definitions this tier installs
                              [--system]
  huginn local off            stop serving  [--purge-models] [--purge]
  huginn kill <name>          hard end: stop the session now
  huginn archive <name>       end it for good and keep the way back: the title,
                              the cwd, a copy of the transcript and the exact
                              'claude --resume' command  [--now: skip wrap-up]
  huginn archive              what has been archived, and how to bring it back
  huginn revive <id|name>     bring an archived session back to life
  huginn -p "question"        one-shot headless query: reasoning + memory +
                              web, and nothing that can change anything
                              (Bash/Edit/Write are denied)
  huginn -y "task"            one-shot that may use tools
                              (bash/files/web + memory)
  huginn usage [args]         Claude Code token/cost report (ccusage; default:
                              daily), e.g. huginn usage monthly | session |
                              blocks | blocks --live
  huginn usage <when>         shortcut date range: today | yesterday | week |
                              month, e.g. huginn usage today |
                              huginn usage week session
  huginn desktop              download links for the latest Huginn Desktop
                              build (Windows, Debian, Arch, portable)
  huginn desktop win|linux|arch|portable
                              just that build's url, bare, for scripting
  huginn desktop install      Linux: install or repair the per-user,
                              self-updating build (no root; any distro)
  huginn desktop uninstall    Linux: remove that install (keeps settings)
  huginn desktop update       update the installed desktop app to the newest
                              release (Windows, the per-user Linux app, or
                              the Debian/Arch package with sudo)
  huginn update               update everything huginn on this machine: this
                              client, huginn core, the desktop app and the
                              local-AI tier (whichever are installed). The
                              client comes from the repo
                              ($HUGINN_REPO); without gh, from the
                              PINNED ${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT} mirror, never from
                              \$HUGINN_HOST${HUGINN_HOST:+, which is $HUGINN_HOST here}:
                              the box you point at must not become a source
                              of code this shell runs
  huginn uninstall            remove the whole stack, top down: the desktop
                              app, the local-AI tier, this machine's device
                              row, huginn core and this CLI [--all also takes
                              the SSH stanza + huginn's own key]
  huginn version              show client version
  huginn help | ? | /help     this help

  Any verb takes --help for its own usage, answered here without touching the
  host: 'huginn archive --help', 'huginn device --help'.
  Session names are lowercase letters, digits, '_' and '-' (no dots, spaces
  or *), up to 50 characters, and must start with a letter, digit or '_'.
  Case-insensitive ('Test' and 'test' are the same session).
  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.
  Reattach from any device.
  Host via the 'huginn' SSH alias; override with \$HUGINN_HOST.
  Attach auto-reconnects after a dropped link (laptop sleep); Ctrl-C during
  the wait to stop. Disable with HUGINN_NO_RECONNECT=1.
  The terminal tab is named after the session (<name>) and the title that was
  there before is put back when you leave; HUGINN_NO_TITLE=1 off.
  A state mark leads the tab title while Claude runs: working / needs you /
  waiting (set host-side by the claude hooks; needs the server's title hook
  installed).
EOF
  # ---8<--- END GENERATED help body
}

# `huginn <verb> --help` - ONE convention, and it used to be four. `projects`
# and `llm` printed their full help and exited 0; `headroom` printed a one-line
# usage and exited 1; `archive` answered "invalid session name '--help'". A
# person types --help when something has already surprised them, and two of
# those made it look like a second mistake.
#
# ANSWERED HERE, NEVER OVER SSH. Help that needs the network is help you cannot
# read on the train, and three of the four spellings above cost an ssh round trip
# to say something this file already knows. Exit 0: asking for help is not an
# error.
#
# $1 = the verb as typed. Prints its block out of _huginn_help_body and returns
# 0; returns 1 when the verb has no entry, so the caller falls through to its
# normal dispatch (and a bare `huginn --help` is still the whole help).
_huginn_verb_help() {
  local v="$1"
  # Aliases resolve to the spelling the help list uses, so `huginn st --help`
  # and `huginn status --help` are the same answer.
  case "$v" in
    ls)                v=list ;;
    st)                v=status ;;
    mv)                v=rename ;;
    round)             v=rounds ;;
    project)           v=projects ;;
    unarchive)         v=revive ;;
    cost|ccusage)      v=usage ;;
    enrol|enroll)      v=device ;;
  esac
  _huginn_help_body | awk -v v="$v" '
    {
      if ($0 ~ /^[[:space:]]*$/)            { keep = 0 }
      else if ($0 ~ /^  huginn([ ]|$)/)     { keep = ($2 == v) }
      else if ($0 ~ /^  [^ ]/)              { keep = 0 }
      if (keep) { print; n++ }
    }
    END { exit (n ? 0 : 1) }
  ' || return 1
  echo
  echo "  (huginn help for everything else)"
}

huginn() {
  local H="${HUGINN_HOST:-huginn}"
  # --help on a VERB, before anything is dispatched or sent: one convention for
  # all of them, answered locally, exit 0. See _huginn_verb_help. A verb with no
  # entry falls through untouched, so `huginn --hlp` is still a refused session
  # name and `huginn help` is still the whole help.
  case "${2:-}" in
    -h|--help)
      _huginn_verb_help "$1" && return 0
      # No entry: say so rather than falling through to the attach path, which
      # would ssh to the host and open a session named after the typo while the
      # person is standing there asking to be told something.
      echo "huginn: no help for '$1' - run 'huginn help' for the list of verbs" >&2
      return 1 ;;
  esac
  case "$1" in
    "")
      # In a terminal a person is at, plain `huginn` is the console (the core
      # draws it). Piped, scripted, TERM=dumb, HUGINN_CONSOLE=0, or a core too
      # old to answer --probe: attach to main, as it always did.
      local core
      if [ -t 0 ] && [ -t 1 ] && [ "${TERM:-}" != dumb ] && [ "${HUGINN_CONSOLE:-1}" != 0 ] \
         && [ "$(uname -o 2>/dev/null)" != Android ] && core="$(_huginn_core_bin)" \
         && "$core" console --probe >/dev/null 2>&1; then
        "$core" console
      else
        _huginn_attach "$H"
      fi
      ;;
    setup)
      # The SSH side of the install, for a machine that got huginn from the
      # desktop installer or a package: the core does it (huginn setup ssh).
      local core
      if core="$(_huginn_core_bin)"; then
        "$core" setup "${@:2}"
      else
        echo "huginn setup needs huginn core: install it with huginn core install (or the one-liner in the README)" >&2
        return 1
      fi
      ;;
    console)
      local core
      if core="$(_huginn_core_bin)" && "$core" console --probe >/dev/null 2>&1; then
        "$core" console "${@:2}"
      else
        echo "huginn console needs huginn core 2.3.0 or newer: huginn core install (or huginn core update)" >&2
        return 1
      fi
      ;;
    '?'|help|/help|-h|--help)
      # The banner rides its own QUOTED heredoc: the art's backslashes and
      # punctuation must reach the terminal verbatim, while the body (see
      # _huginn_help_body) stays unquoted so $HUGINN_REPO/$HUGINN_UPDATE_HOST expand.
      cat <<'EOF'

        _
       (o)==-   huginn - remote Claude Code node.  aliases: rclaude, rcc
       //\
    =~/_/
EOF
      _huginn_help_body
      ;;
    version|--version|-v)
      echo "(o> huginn-cli $HUGINN_VERSION  (host: $H)" ;;
    update)
      local dest="${BASH_SOURCE[0]:-$HOME/.huginn/huginn.sh}" tmp got='' src='' t0=$SECONDS log newv
      tmp="$dest.tmp"; log="$(mktemp)"
      local uh="${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT}"
      _huginn_ui_head "huginn update"
      # gh's answer goes to $tmp, its complaints to the log - never mixed.
      if command -v gh >/dev/null 2>&1 && _huginn_ui_spin client "fetching" "$log" \
           bash -c 'gh api "repos/$1/contents/client/huginn.sh" -H "Accept: application/vnd.github.raw" > "$2"' _ "$HUGINN_REPO" "$tmp" \
         && [ -s "$tmp" ]; then
        got=1; src="from GitHub"
      fi
      if [ -z "$got" ]; then
        [ "$uh" = "$HUGINN_UPDATE_HOST_DEFAULT" ] \
          || _huginn_ui_line warn client "HUGINN_UPDATE_HOST is set: trusting $uh for this client's code"
        if _huginn_ui_spin client "fetching from the $uh mirror" "$log" scp -o BatchMode=yes "$uh:/usr/local/share/huginn-cli/huginn.sh" "$tmp"; then
          got=1; src="from the $uh mirror"
        fi
      fi
      if [ -z "$got" ]; then
        rm -f "$tmp" "$log" 2>/dev/null
        _huginn_ui_line fail client "update failed: gh unavailable or errored, and the $uh mirror did not answer either"; return 1
      fi
      if ! bash -n "$tmp" 2>/dev/null; then
        rm -f "$tmp" "$log"; _huginn_ui_line fail client "the downloaded client failed its syntax check - kept $HUGINN_VERSION"; return 1
      fi
      newv="$(sed -n "s/^HUGINN_VERSION='\(.*\)'/\1/p" "$tmp" | head -1)"
      if [ "$newv" = "$HUGINN_VERSION" ]; then
        _huginn_ui_line ok client "$HUGINN_VERSION is current"
      else
        _huginn_ui_line ok client "$HUGINN_VERSION -> ${newv:-?}   $src"
      fi
      cp -f "$dest" "$dest.bak" 2>/dev/null
      mv -f "$tmp" "$dest"
      source "$dest"
      # Every huginn component on this machine follows, not only the CLI
      # (owner, 2026-10-16): the core, the desktop app, the local-AI tier.
      if _huginn_core_bin >/dev/null 2>&1; then HUGINN_UI_COMPACT=1 _huginn_core_update || true
      else _huginn_ui_line skip core "not installed (huginn core install)"; fi
      HUGINN_UI_COMPACT=1 _huginn_desktop_update || true
      if [ -d "${HUGINN_LOCAL_DIR:-$HOME/.config/huginn-local}" ] && [ -s "$HOME/.huginn/huginn-local" ] && command -v node >/dev/null 2>&1; then
        # It asks before it changes anything, so only where it can ask.
        if [ -t 0 ]; then _huginn_ui_line ok "local AI" "checking (it asks before changing anything)"; huginn local update || true
        else _huginn_ui_line warn "local AI" "run 'huginn local update' in a terminal to update it"; fi
      else
        _huginn_ui_line skip "local AI" "not installed"
      fi
      rm -f "$log"
      printf '    %sdone in %s s%s\n' "$(_huginn_ui_c 90)" "$((SECONDS - t0))" "$(_huginn_ui_c 0)" >&2
      ;;
    list|ls)   ssh -T "$H" "tmux ls 2>/dev/null || echo '(no sessions running)'" ;;
    status|st) ssh -T "$H" huginn-status ;;
    # Rendered ON THE HOST, exactly like huginn-status above. Both clients run the
    # same renderer, so there is one implementation of "what a round looks like"
    # rather than one per client. These two files have already drifted over a
    # single version constant; this has far more fields to drift over.
    rounds|round) ssh -T "$H" huginn-rounds ;;
    # Same host-side rule as rounds/devices, and for a third reason on top of
    # theirs: headroom is read from the daemon with the bearer token, so
    # rendering it anywhere but here would mean handing a laptop that token.
    # -T, not -tt: the attach paths force a PTY because tmux needs one, but a
    # renderer piped into `less` or captured into a variable must not have its
    # newlines turned into CRLF by a tty on the far end.
    # Flags go through printf %q like the llm branch: what follows the host name
    # is parsed by a shell on the far side, so an argument is remote shell input.
    # Guarded on $# because `printf '%q ' ` with NO arguments still runs the
    # format once and emits '' - a bare `huginn headroom` would send one empty
    # argument, which the renderer rightly refuses as an unknown flag.
    headroom)
      if [ "$#" -gt 1 ]; then ssh -T "$H" "huginn-headroom $(printf '%q ' "${@:2}")"
      else ssh -T "$H" huginn-headroom; fi ;;
    # Arguments go through like headroom's: `huginn devices watch <name> on|off`
    # is parsed by the renderer on the host, so each one is remote shell input.
    devices)
      if [ "$#" -gt 1 ]; then ssh -T "$H" "huginn-devices $(printf '%q ' "${@:2}")"
      else ssh -T "$H" huginn-devices; fi ;;
    # A PROJECT is a cluster of sessions with roles and a lead that sizes the
    # work. Host-side for the same three reasons as headroom above -- one
    # renderer for both clients, the bearer token never leaves huginn, and the
    # daemon refuses a spawn in prose that a client would otherwise throw away --
    # plus a fourth this verb alone has: it is the only one here that WRITES,
    # and the whole grammar (list/show/new/spawn/msg/end) lives in that one file
    # rather than being spelled out twice, in two languages, and drifting.
    #
    # argv goes through printf %q like the headroom and llm branches: what
    # follows the host name is parsed by a shell on the far side, so an argument
    # typed here is remote shell input -- and a first prompt is prose full of
    # quotes, $ and newlines. Guarded on $# because `printf '%q ' ` with NO
    # arguments still runs the format once and emits '', and a bare
    # `huginn projects` would send one empty argument the renderer rightly
    # refuses as an unknown one.
    projects|project)
      if [ "$#" -gt 1 ]; then ssh -T "$H" "huginn-projects $(printf '%q ' "${@:2}")"
      else ssh -T "$H" huginn-projects; fi ;;
    # One question to the LOCAL TIER - answered by a serving machine's model,
    # never Claude. Renders on the host like devices/rounds above: one
    # implementation, and the token never leaves huginn. `huginn llm -` reads
    # the prompt from stdin, so a file can be piped in for summarising.
    llm) ssh -T "$H" "huginn-llm $(printf '%q ' "${@:2}")" ;;
    # Plural is the host's list of machines; SINGULAR is the one you are typing
    # on. Different question, different place it is answered - `devices` renders
    # on the host, `device` never leaves this machine.
    device) _huginn_device "${@:2}" ;;
    core) _huginn_core "${@:2}" ;;
    local) _huginn_local "${@:2}" ;;
    uninstall) _huginn_uninstall "${@:2}" ;;
    desktop)
      local want=''
      case "${2:-}" in
        ''|both|all)             want='' ;;
        install)                 _huginn_desktop_install; return $? ;;
        uninstall|remove)        _huginn_desktop_uninstall; return $? ;;
        update)                  _huginn_desktop_update; return $? ;;
        win|windows|exe)         want='windows-x64' ;;
        linux|deb|debian|ubuntu) want='linux-x64' ;;
        arch|pacman|pkg)         want='linux-x64-arch' ;;
        portable|tar|tarball)    want='linux-x64-portable' ;;
        *) echo "usage: huginn desktop [windows|linux|arch|portable|install|uninstall]" >&2; return 1 ;;
      esac
      local rel tag man
      rel="$(_huginn_desktop_release)" || {
        echo "huginn: could not read the desktop release feed (offline, or GitHub rate-limited this IP)." >&2
        echo "  Browse it: https://github.com/$HUGINN_REPO/releases" >&2
        return 1; }
      tag="${rel%%$'\n'*}"; man="${rel#*$'\n'}"
      local base ver here obj file
      base="https://github.com/$HUGINN_REPO/releases/download/$tag"
      ver="$(_huginn_json_str "$man" version)"
      here="$(_huginn_desktop_platform)"
      # With a platform named, print the BARE url and nothing else, so it composes:
      #   curl -fLO "$(huginn desktop linux)"
      if [ -n "$want" ]; then
        obj="$(_huginn_json_obj "$man" "$want")"
        file="$(_huginn_json_str "$obj" file)"
        [ -n "$file" ] || { echo "huginn: $tag has no $want build" >&2; return 1; }
        echo "$base/$file"
        return 0
      fi
      # Which LINUX artifact is "this machine's": the distro's own package where
      # there is one, the portable tarball everywhere else.
      local distro='' mine="$here"
      if [ "$here" = 'linux-x64' ]; then
        distro="$(_huginn_linux_distro)"
        case "$distro" in arch) mine='linux-x64-arch' ;; debian) mine='linux-x64' ;; *) mine='linux-x64-portable' ;; esac
      fi
      local p label sz sha mark deb_file='' arch_file=''
      printf '\n  Huginn Desktop %s   (%s)\n\n' "${ver:-?}" "$tag"
      for p in windows-x64 linux-x64 linux-x64-arch linux-x64-portable; do
        obj="$(_huginn_json_obj "$man" "$p")"
        file="$(_huginn_json_str "$obj" file)"
        [ -n "$file" ] || continue
        [ "$p" = 'linux-x64' ] && deb_file="$file"
        [ "$p" = 'linux-x64-arch' ] && arch_file="$file"
        case "$p" in
          windows-x64)        label='Windows ' ;;
          linux-x64)          label='Debian  ' ;;
          linux-x64-arch)     label='Arch    ' ;;
          *)                  label='Portable' ;;
        esac
        [ "$p" = "$mine" ] && mark='   <- this machine' || mark=''
        sz="$(_huginn_json_num "$obj" size)"; sha="$(_huginn_json_str "$obj" sha256)"
        printf '  %s  %s%s\n' "$label" "$base/$file" "$mark"
        printf '            %s   sha256 %s\n' \
          "$(awk -v b="${sz:-0}" 'BEGIN{printf "%6.1f MB", b/1048576}')" "${sha:0:16}..."
      done
      case "$here" in
        linux-x64)
          # The self-updating install first, on EVERY distro: it needs no root
          # and is the only Linux install the app can update by itself.
          printf '\n  install:  huginn desktop install    (per-user, no root, updates itself from the app)\n'
          if [ "$distro" = arch ] && [ -n "$arch_file" ] && command -v pacman >/dev/null 2>&1; then
            # ⚠ NEVER `pacman -U <url>`: for a remote URL pacman also fetches
            # `<file>.sig` and aborts on its 404 (these packages are unsigned; the
            # manifest's sha256 is the integrity check). A LOCAL file is judged by
            # LocalFileSigLevel instead, which installs. Found on stigo 2026-10-14.
            printf '      or:  curl -fLO %s/%s && sudo pacman -U ./%s\n' "$base" "$arch_file" "$arch_file"
          elif [ "$distro" = debian ] && [ -n "$deb_file" ] && command -v dpkg >/dev/null 2>&1; then
            printf '      or:  curl -fLO %s/%s && sudo apt install ./%s\n' "$base" "$deb_file" "$deb_file"
          fi ;;
        windows-x64) printf '\n  install:  run the .exe (per-user NSIS installer, no admin needed)\n' ;;
        *)           printf '\n  (no desktop build for this machine - these links are for your laptop)\n' ;;
      esac
      printf '  The Windows and portable installs update themselves from this same feed.\n\n' ;;
    usage|cost|ccusage)
      shift                                         # ccusage report; default 'daily'. -tt for tables + --live.
      # Full history (back to 2026-01) is layered server-side by the /usr/local/bin/ccusage
      # wrapper on huginn - keep this call bare so client-side quoting can't break it.
      case "$1" in
        today|yesterday|week|month)
          local kw="$1"; shift
          local report="daily"
          case "$1" in
            daily|monthly|weekly|session|blocks|statusline) report="$1"; shift ;;
          esac
          # Date math runs server-side (guaranteed GNU date on the host) so this
          # works the same regardless of the client OS's date flavor (GNU/BSD).
          local dates
          case "$kw" in
            today)     dates='since=$(date +%Y%m%d); until=$since' ;;
            yesterday) dates='since=$(date -d yesterday +%Y%m%d); until=$since' ;;
            week)      dates='since=$(date -d "7 days ago" +%Y%m%d); until=$(date +%Y%m%d)' ;;
            month)     dates='since=$(date +%Y%m01); until=$(date +%Y%m%d)' ;;
          esac
          ssh -tt "$H" "$dates; ccusage $report -s \$since -u \$until $*" ;;
        *)
          ssh -tt "$H" "ccusage ${*:-daily}" ;;
      esac ;;
    attach)
      # By name, never by verb - see _huginn_attach_named. No default: a bare
      # `attach` is a mistake, not a request for main.
      [ $# -eq 2 ] || { echo "usage: huginn attach <name>" >&2; return 1; }
      _huginn_attach_named "$H" "$2" ;;
    solo)
      local s="${2:-main}"
      _huginn_valid_name "$s" || { _huginn_bad_name "$s"; return 1; }
      _huginn_attach "$H" "$s" solo ;;
    rename|mv)
      [ -n "$2" ] && [ -n "$3" ] || { echo "usage: huginn rename <old> <new>" >&2; return 1; }
      # Validate BOTH names: the old one is interpolated into a remote root shell.
      _huginn_valid_name "$2" || { _huginn_bad_name "$2"; return 1; }
      _huginn_valid_name "$3" || { _huginn_bad_name "$3" "new name"; return 1; }
      local ro rn; ro="$(_huginn_canon_name "$2")"; rn="$(_huginn_canon_name "$3")"
      ssh -T "$H" "tmux rename-session -t '$(_huginn_tmux_target "$ro")' '$rn' && echo 'renamed: $ro -> $rn'" ;;
    kill)
      [ -n "$2" ] || { echo "usage: huginn kill <name>" >&2; return 1; }
      _huginn_valid_name "$2" || { _huginn_bad_name "$2"; return 1; }
      local kn; kn="$(_huginn_canon_name "$2")"
      # Prefer the daemon's DELETE: it also removes the orphaned /run state file
      # and releases the pane lease, which a bare tmux kill-session leaves behind
      # (Claude's SessionEnd hook never fires on a kill). Fall back to tmux if the
      # daemon is unreachable - kill must work even when appd is down.
      # '=' anchor on the fallback: without it 'huginn kill andvari' kills 'andvariautofill'.
      #
      # ⚠ THE FALLBACK IS FOR AN UNREACHABLE DAEMON, NOT AN HTTP STATUS. DELETE has
      # no 409 guard, so the realistic failure is a GLOBAL 401 - an unreadable or
      # rotated token - with the daemon perfectly healthy. Falling back there killed
      # the session with raw tmux, skipping clearSessionState / registryRemove /
      # releaseSize, so a deliberately killed session came back on the next reboot
      # restore and nothing ever said why.
      local kr krc; kr="$(_huginn_appd DELETE "/v1/sessions/$kn")"; krc=$?
      if [ "$krc" -eq 0 ]; then
        echo "killed: $kn"
      elif [ "$krc" -eq 7 ]; then
        ssh -T "$H" "tmux kill-session -t '$(_huginn_tmux_target "$kn")' && echo 'killed: $kn'"
      else
        local kmsg; kmsg="$(_huginn_appd_error "$kr")"
        echo "huginn: could not kill '$kn': ${kmsg:-huginn-appd answered HTTP $(_huginn_appd_http "$kr")}" >&2
        return 1
      fi ;;
    end)
      [ -n "$2" ] || { echo "usage: huginn end <name> [--force]" >&2; return 1; }
      _huginn_valid_name "$2" || { _huginn_bad_name "$2"; return 1; }
      local en force=; en="$(_huginn_canon_name "$2")"
      # --force is the answer to one specific refusal, and it used to be
      # UNREACHABLE: neither client sent a request body and `end` parsed no flags,
      # so the daemon's "pass force to send anyway" named something nobody could do.
      case "${3:-}" in
        --force) force='{"force":true}' ;;
        '') ;;
        *) echo "usage: huginn end <name> [--force]" >&2; return 1 ;;
      esac
      # Soft end: ask Claude to wrap up (finish, commit, prepare to end) and - when
      # auto-end is on for the host - end the session once it settles. This is a
      # DAEMON feature (it types into the pane and watches state), so there is no
      # tmux fallback; the phrase is whatever the host is configured to send.
      local r rc; r="$(_huginn_appd POST "/v1/sessions/$en/soft-end" "$force")"; rc=$?
      if [ "$rc" -ne 0 ]; then
        if [ "$rc" -eq 7 ]; then
          # ⚠ WHICH HALF FAILED. An unreachable HOST is not a stopped daemon, and
          # this line used to say the second about the first - on a box ssh had
          # just refused - while 2>/dev/null threw away the "Connection refused"
          # that named the real fault. _huginn_appd_why tells them apart and
          # prints ssh's own sentence.
          _huginn_appd_why "$H" "$r"
          echo "        a soft-end is a daemon feature, so there is no tmux fallback" >&2
        else
          local msg; msg="$(_huginn_appd_error "$r")"
          echo "huginn: could not end '$en': ${msg:-huginn-appd answered HTTP $(_huginn_appd_http "$r")}" >&2
          case "$msg" in *force*) echo "        send it anyway with: huginn end $en --force" >&2 ;; esac
        fi
        return 1
      fi
      local phrase auto
      phrase="$(printf '%s' "$r" | sed -n 's/.*"phrase"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
      printf '%s' "$r" | grep -q '"auto"[[:space:]]*:[[:space:]]*true' && auto=' (auto-ends when it goes idle)' || auto=''
      echo "soft-ended '$en': sent \"${phrase:-wrap-up phrase}\"${auto}" ;;
    # Archive: end the session for good AND keep the way back into it — the
    # title, the cwd, the last thing said, a COPY of the transcript, and the
    # exact `claude --resume <uuid>`. Graceful by default, exactly like `end`
    # (the wrap-up phrase first, a waiting question refused); --now for a
    # session with nothing left to wrap up. Bare `huginn archive` is the list.
    #
    # CALLED on the host, not just rendered there, which is the one way this
    # differs from `end`. The daemon refuses an archive in prose — "answer the
    # waiting question first, then archive the session" — and that sentence is
    # the most useful thing this verb ever says, while `_huginn_appd` uses
    # `curl -sf` and throws a 4xx body away. Flags go through printf %q: what
    # follows the host name is parsed by a shell on the far side.
    archive)
      if [ -z "${2:-}" ]; then ssh -T "$H" huginn-archive; return; fi
      _huginn_valid_name "$2" || { _huginn_bad_name "$2"; return 1; }
      local ar; ar="$(_huginn_canon_name "$2")"
      # Guarded on $#, like the headroom branch: `printf '%q ' ` with no arguments
      # still runs the format once and emits '', which the renderer would rightly
      # refuse as an unknown flag.
      if [ "$#" -gt 2 ]; then ssh -T "$H" "huginn-archive $(printf '%q ' "$ar" "${@:3}")"
      else ssh -T "$H" "huginn-archive $(printf '%q ' "$ar")"; fi ;;
    # Bring one back: recreate the session, restore the kept transcript if Claude
    # Code has swept its own, and resume into it. Takes the archive id OR the
    # name it had — the name is resolved host-side, because resolving it here
    # would mean parsing the list in bash AND in PowerShell.
    revive|unarchive)
      [ -n "${2:-}" ] || { echo "usage: huginn revive <id|name>" >&2; return 1; }
      # Wider than _huginn_valid_name deliberately: an archive id is a uuid, and
      # uuids have dashes. Still a strict allow-list — this reaches a remote shell.
      case "$2" in
        *[!A-Za-z0-9_.-]*|'') echo "huginn: '$2' is not an archive id or a session name" >&2; return 1 ;;
      esac
      ssh -T "$H" "huginn-archive revive $(printf '%q ' "$2")" ;;
    -p|-y)
      local mode="$1"; shift
      [ "$#" -gt 0 ] || { echo "usage: huginn $mode \"your prompt\"" >&2; return 1; }
      local q="$*"; q=${q//\'/\'\\\'\'}            # POSIX single-quote escape
      # Kept in step with huginn-appd's ask/act tool sets (server/appd TOOLS/
      # DISALLOWED): -p is read-only reasoning + web + memory, -y may also mutate.
      # The DISALLOWED deny-list is the real fence - --allowedTools only
      # auto-approves, so without it a -p query could still be granted Bash.
      # The flag is assembled HERE and interpolated into the remote command, so the
      # quoting is bash SYNTAX on the host. Building it into a remote variable and
      # expanding it unquoted (the 0.8.0 pre-release form) word-split it into
      # `'Bash` `Edit` `Write` `NotebookEdit'` - literal quotes, no valid tool name,
      # so nothing was actually denied.
      local tools dflag
      if [ "$mode" = "-y" ]; then
        tools="Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace"; dflag=""
      else
        tools="Skill mcp__mempalace WebFetch WebSearch"; dflag="--disallowedTools 'Bash Edit Write NotebookEdit'"
      fi
      # Persona-aware: if the host carries persona.md, inject it + memory tools; else plain headless query.
      ssh -T "$H" "cd \"\${HUGINN_WORKDIR:-\$HOME}\" 2>/dev/null || cd \"\$HOME\"; P=\"\$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)\"; if [ -n \"\$P\" ]; then echo '$q' | claude -p --append-system-prompt \"\$P\" --allowedTools '$tools' $dflag; else echo '$q' | claude -p; fi" ;;
    *)
      _huginn_valid_name "$1" || { _huginn_bad_name "$1"
        echo "        Did you mean a subcommand? Try 'huginn help'." >&2; return 1; }
      _huginn_attach "$H" "$1" ;;
  esac
}

rclaude() { huginn "$@"; }
rcc()     { huginn "$@"; }

# tab completion
# Live session names come from the host (tmux ls). We cache them in-memory for a
# few seconds so repeated <Tab> doesn't ssh on every keystroke; BatchMode keeps a
# missing key/agent from hanging the prompt, ConnectTimeout bounds a slow link.
_HUGINN_SESS_CACHE=
_HUGINN_SESS_TS=0
_huginn_sessions() {
  local H="${HUGINN_HOST:-huginn}" now
  now=$(date +%s 2>/dev/null || echo 0)
  if [ -z "$_HUGINN_SESS_CACHE" ] || [ "$(( now - _HUGINN_SESS_TS ))" -ge 5 ]; then
    _HUGINN_SESS_CACHE=$(ssh -T -o BatchMode=yes -o ConnectTimeout=2 "$H" "tmux ls -F '#S' 2>/dev/null" 2>/dev/null)
    _HUGINN_SESS_TS=$now
  fi
  printf '%s\n' "$_HUGINN_SESS_CACHE"
}
# ⚠ EVERY VERB THAT TAKES SUB-VERBS HAS TO BE HERE, and `projects` was not:
# `huginn projects <TAB>` completed to NOTHING while `device` and `local` beside
# it completed fine, which reads as "this verb takes no arguments" - for the one
# verb whose whole grammar is arguments. `local` was also a release behind
# (`persist` shipped in 1.1.0), and the aliases the dispatcher accepts - ls, st,
# mv, round, project, ccusage, unarchive - were completable in neither client.
_huginn_complete() {
  local cur prev cmds
  cur="${COMP_WORDS[COMP_CWORD]}"
  prev="${COMP_WORDS[COMP_CWORD-1]}"
  cmds="console setup list ls status st rounds round headroom devices device local llm projects project solo attach rename mv kill end archive revive unarchive -p -y usage cost ccusage desktop update uninstall version help"
  if [ "$COMP_CWORD" -eq 1 ]; then
    # first word: subcommands + live session names (bare name attaches to it)
    mapfile -t COMPREPLY < <(compgen -W "$cmds $(_huginn_sessions)" -- "$cur")
  else
    case "$prev" in
      kill|end|archive|solo|attach|rename|mv)   # these take an existing session name
        mapfile -t COMPREPLY < <(compgen -W "$(_huginn_sessions)" -- "$cur") ;;
      usage|cost|ccusage)    # date shortcuts + raw report names
        mapfile -t COMPREPLY < <(compgen -W "today yesterday week month daily monthly weekly session blocks statusline" -- "$cur") ;;
      desktop)
        mapfile -t COMPREPLY < <(compgen -W "windows linux arch portable install uninstall both" -- "$cur") ;;
      device)
        mapfile -t COMPREPLY < <(compgen -W "status on off unit update serve --help" -- "$cur") ;;
      local)
        mapfile -t COMPREPLY < <(compgen -W "status on plan persist off unit update doctor --help" -- "$cur") ;;
      projects|project)
        mapfile -t COMPREPLY < <(compgen -W "list show new spawn msg end --json --help" -- "$cur") ;;
      headroom)
        mapfile -t COMPREPLY < <(compgen -W "--json --help" -- "$cur") ;;
      uninstall)
        mapfile -t COMPREPLY < <(compgen -W "--all --yes" -- "$cur") ;;
      --scope)
        mapfile -t COMPREPLY < <(compgen -W "look work own" -- "$cur") ;;
      --kind)
        mapfile -t COMPREPLY < <(compgen -W "software infra hardware docs research other" -- "$cur") ;;
      today|yesterday|week|month)   # optional report-type override after a date shortcut
        mapfile -t COMPREPLY < <(compgen -W "daily monthly weekly session blocks statusline" -- "$cur") ;;
      *) COMPREPLY=() ;;
    esac
  fi
}
complete -F _huginn_complete huginn rclaude rcc 2>/dev/null
