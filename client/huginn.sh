# shellcheck shell=bash
# huginn (bash) - talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 1.4.0

HUGINN_VERSION='1.4.0'
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
_huginn_device_runner() { printf '%s' "$HOME/.huginn/huginn-device"; }

# The daemon URL this machine should be enrolled with, worked out from the ssh
# link it has ALREADY been trusted on. $SSH_CONNECTION's third field is the
# address THIS machine just reached the host at, which is better than choosing on
# the device's behalf between a LAN address, a tailnet name and whatever
# `hostname` happens to say. $1 = the ssh alias; prints http://<authority>:8787.
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
_huginn_srv_url() {
  local H="$1" a v4
  a="$(ssh -T "$H" 'echo $SSH_CONNECTION' 2>/dev/null | awk '{print $3}' | tr -d '[:space:]')"
  [ -n "$a" ] || return 1
  case "$a" in
    *:*) ;;
    *) printf 'http://%s:8787' "$a"; return 0 ;;
  esac
  v4="$(ssh -T "$H" "ip -4 -o addr show scope global 2>/dev/null | awk '{print \$4}' | cut -d/ -f1 | head -1" \
        2>/dev/null | tr -d '[:space:]')"
  case "$v4" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*) printf 'http://%s:8787' "$v4"; return 0 ;;
  esac
  printf 'http://[%s]:8787' "$a"
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
  local H="${HUGINN_HOST:-huginn}" runner sub dir srv
  runner="$(_huginn_device_runner)"
  sub="${1:-status}"; [ $# -gt 0 ] && shift
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
      # host at, bracketed when it is an IPv6 literal.
      srv="$(_huginn_srv_url "$H")"
      [ -n "$srv" ] || { echo "huginn device: could not work out how to reach $H's daemon" >&2; return 1; }
      node "$runner" on --url "$srv" "$@"
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
      srv="$(_huginn_srv_url "$H")"
      [ -n "$srv" ] || { echo "huginn local: could not work out how to reach $H's daemon" >&2; return 1; }
      HUGINN_LOCAL_DIR="$dir" node "$mgr" on --url "$srv" "$@"
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

  local hdir="$HOME/.huginn"
  local ddir="${HUGINN_DEVICE_DIR:-$HOME/.config/huginn}"
  local ldir="${HUGINN_LOCAL_DIR:-$HOME/.config/huginn-local}"
  local rc="$HOME/.bashrc"
  local cfg="$HOME/.ssh/config"
  # The EXACT line install.sh appends. Matched whole-line and fixed-string, so a
  # profile somebody hand-wrote differently is reported rather than rewritten.
  local rcline='[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh'
  local havenode=; command -v node >/dev/null 2>&1 && havenode=1
  local -a removed=() left=()

  echo "huginn uninstall removes, from THIS machine:"
  echo "  $hdir"
  echo "      the client, the device runner, the local-AI manager"
  echo "  $ddir"
  echo "      this machine's device enrolment and its copy of the appd token"
  echo "  $ldir"
  echo "      the local-AI tier: models, sessions, runtime (can be several GB)"
  echo "  the 'source ~/.huginn/huginn.sh' line in $rc"
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

  # 4. The profile line.
  if [ -f "$rc" ] && grep -qxF "$rcline" "$rc"; then
    local grc
    grep -vxF "$rcline" "$rc" > "$rc.huginn-uninstall"; grc=$?
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
  elif [ -f "$rc" ] && grep -q '\.huginn/huginn\.sh' "$rc"; then
    left+=("a hand-edited '.huginn/huginn.sh' line in $rc - it is not the one the installer wrote, so it was left")
  fi

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
_huginn_bad_name() {   # $1 = the name, $2 = the noun for the message
  local H="${HUGINN_HOST:-huginn}"
  if [ -n "$_HUGINN_SESS_CACHE" ] && grep -qxF -- "$1" <<<"$_HUGINN_SESS_CACHE"; then
    echo "huginn: '$1' exists on the host but this client cannot address it" >&2
    echo "        (names here are lowercase letters, digits, _ and -). Rename it from the" >&2
    echo "        desktop app, or: ssh $H -t \"tmux attach -t '=$1'\"" >&2
  else
    echo "huginn: invalid ${2:-session name} '$1' (use lowercase letters, digits, _ and -; no dots, spaces or *)" >&2
  fi
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
# for the whole session; reset on exit. Opt out: export HUGINN_NO_TITLE=1
_huginn_attach() {
  # $1=host  $2=session (default main)  $3=non-empty => start in solo
  local H="$1" session="${2:-main}" solo="$3" delay=2 rc remote t0 elapsed quick=0 tgt
  session="$(_huginn_canon_name "$session")"   # case-insensitive: 'Test' -> 'test'
  tgt="$(_huginn_tmux_target "$session")"
  [ -z "$HUGINN_NO_TITLE" ] && printf '\033]0;%s\007' "$session"
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
        printf '\nhuginn: %s is failing immediately (%s attempts, last exit %s) - giving up.\n  The remote error is printed above; fix it or run "huginn %s" again.\n' \
          "$H" "$quick" "$rc" "$session" >&2
        break
      fi
    else
      quick=0
    fi
    printf '\nhuginn: link to %s dropped (ssh exit %s) - reconnecting in %ss (Ctrl-C to stop)...\n' "$H" "$rc" "$delay" >&2
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
  [ -z "$HUGINN_NO_TITLE" ] && printf '\033]0;%s\007' "${HOSTNAME:-shell}"   # reset tab on leaving
  return "$rc"
}

huginn() {
  local H="${HUGINN_HOST:-huginn}"
  case "$1" in
    "")
      _huginn_attach "$H"
      ;;
    '?'|help|/help|-h|--help)
      # The banner rides its own QUOTED heredoc: the art's backslashes and
      # punctuation must reach the terminal verbatim, while the body heredoc
      # below stays unquoted so $HUGINN_REPO/$HUGINN_UPDATE_HOST expand.
      cat <<'EOF'

        _
       (o)==-   huginn - remote Claude Code node.  aliases: rclaude, rcc
       //\
    =~/_/
EOF
      cat <<EOF

  huginn                      attach/create the live 'main' session (run claude inside)
  huginn <name>               a separate named session
  huginn solo [name]          attach + detach all OTHER clients (resume solo / full screen)
  huginn list | ls            list sessions + attach status
  huginn status | st          health: uptime, auth, sessions, disk
  huginn rename <old> <new>   rename a session (alias: mv)
  huginn end <name> [--force] soft end: ask Claude to wrap up + commit, then
                              (if auto-end is on) end it once it goes idle
                              (--force: send it into a pane with no Claude state)
  huginn rounds               what this host does on a schedule, and what it found
  huginn headroom             usage left per account, what huginn moved or is holding, and why
  huginn devices              machines that can run a chat in their own context
  huginn projects             clusters of sessions with roles, and who is waiting
  huginn projects show <name> a project's members, one line each
  huginn projects new <name>  start one (launches its lead session)
                              --brief "<the lead's whole first message>"|-
                              [--kind software|infra|hardware|docs|research|other] [--cwd DIR]
  huginn projects spawn <project>         approve the lead's proposal: create its members
  huginn projects msg <project> <from> <to> <text>
  huginn projects end <project>           end it AND wind its sessions down
                              [--now: end them outright | --keep-sessions: leave them running]
  huginn device [status]      what THIS machine offers huginn, and what huginn sees
  huginn device on            offer this machine  [--scope look|work|own] [--root DIR]
  huginn device off           stop offering it
  huginn llm "prompt"         one question to the local tier (a serving machine answers, not Claude)
  huginn local [status]       what THIS machine serves as local AI, if anything
  huginn local on             serve local models from this machine (optional, ~5 GB)
  huginn local plan           what 'on' would install here, without installing anything
  huginn local off            stop serving  [--purge-models] [--purge]
  huginn device unit          print a systemd unit that keeps the runner up
  huginn kill <name>          hard end: stop the session now
  huginn archive <name>       end it for good and keep the way back: the title,
                              the cwd, a copy of the transcript and the exact
                              'claude --resume' command   [--now to skip wrap-up]
  huginn archive              what has been archived, and how to bring it back
  huginn revive <id|name>     bring an archived session back to life
  huginn -p "question"        one-shot headless query: reasoning + memory + web, and
                              nothing that can change anything (Bash/Edit/Write are DENIED)
  huginn -y "task"            one-shot that may use tools (bash/files/web + memory)
  huginn usage [args]         Claude Code token/cost report (ccusage; default: daily)
                                e.g. huginn usage monthly | session | blocks | blocks --live
  huginn usage <when>         shortcut date range: today | yesterday | week | month
                                e.g. huginn usage today | huginn usage week session
  huginn desktop              download links for the latest Huginn Desktop build
  huginn desktop win|linux    just that platform's url, bare, for scripting
  huginn update               self-update this client from the repo ($HUGINN_REPO);
                              without gh, from the PINNED ${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT} mirror
                              (never \$HUGINN_HOST${HUGINN_HOST:+, which is $HUGINN_HOST here} - the box
                              you point at must not become a source of code this shell runs)
  huginn uninstall            unenrol this machine, then remove the client, the
                              tokens, the local-AI tier and the profile line
                              [--all also takes the SSH stanza + huginn's own key]
  huginn version              show client version
  huginn help | ? | /help     this help

  Session names are lowercase letters, digits, '_' and '-' (no dots, spaces or *),
  up to 50 characters, and must start with a letter, digit or '_'. Case-insensitive
  ('Test' and 'test' are the same session).
  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.  Reattach from any device.
  Host via the 'huginn' SSH alias; override with HUGINN_HOST.
  Attach auto-reconnects after a dropped link (laptop sleep); Ctrl-C during the
  wait to stop. Disable with HUGINN_NO_RECONNECT=1.
  The terminal tab is named after the session (<name>); HUGINN_NO_TITLE=1 off.
  A state icon leads the tab title while Claude runs: working / needs-you / waiting
  (set host-side by the claude hooks; needs the server's title hook installed).

EOF
      ;;
    version|--version|-v)
      echo "huginn-cli $HUGINN_VERSION  (host: $H)" ;;
    update)
      local dest="${BASH_SOURCE[0]:-$HOME/.huginn/huginn.sh}" tmp got=
      tmp="$dest.tmp"
      # ⚠ NAMED BEFORE THE FIRST MESSAGE THAT MENTIONS IT. The mirror host is
      # PINNED and is never $HUGINN_HOST - this path downloads a shell script the
      # block below SOURCES, so the host it comes from is a trust root and not a
      # convenience - and the fallback line said "falling back to the $H mirror",
      # i.e. it named the one host this deliberately does not use, in the one
      # message whose job is naming the one it does. A run could print "falling
      # back to the rvhost mirror" and then "pulled from huginn mirror" in the
      # same four lines.
      local uh="${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT}"
      echo "huginn: updating client -> $dest"
      if command -v gh >/dev/null 2>&1; then
        # Leave it at $tmp - the syntax check + backup below is the only install path.
        if gh api "repos/$HUGINN_REPO/contents/client/huginn.sh" -H "Accept: application/vnd.github.raw" >"$tmp" 2>/dev/null && [ -s "$tmp" ]; then
          got=1; echo "  pulled from GitHub ($HUGINN_REPO) via gh"
        else
          echo "  (gh fetch failed - falling back to the pinned $uh mirror)"
        fi
      fi
      if [ -z "$got" ]; then
        command -v gh >/dev/null 2>&1 || echo "  (gh not installed - using the scp fallback)"
        # $HUGINN_HOST is routinely repointed at a test box or mistyped, which is
        # why it answers "which box do I drive" and never "whose code do I run".
        # Say which host is being trusted, every time.
        [ "$uh" = "$HUGINN_UPDATE_HOST_DEFAULT" ] \
          || echo "  (HUGINN_UPDATE_HOST is set - trusting $uh for this client's code)"
        # BatchMode: never drop into an interactive password prompt in the middle
        # of what reads as a non-interactive update.
        if scp -o BatchMode=yes "$uh:/usr/local/share/huginn-cli/huginn.sh" "$tmp"; then
          got=1; echo "  pulled from $uh mirror via scp"
        fi
      fi
      # Validate BEFORE installing. Sourcing a truncated download leaves the live
      # shell with a half-defined huginn function AND overwrites the good copy on
      # disk, so the next shell is broken too.
      if [ -n "$got" ]; then
        if ! bash -n "$tmp" 2>/dev/null; then
          echo "huginn: downloaded client failed its syntax check - keeping the current version" >&2
          rm -f "$tmp"; return 1
        fi
        cp -f "$dest" "$dest.bak" 2>/dev/null
        mv -f "$tmp" "$dest"
        # shellcheck disable=SC1090
        source "$dest"; huginn version
        echo "  (previous version saved as $(basename "$dest").bak)"
      else
        rm -f "$tmp" 2>/dev/null
        echo "huginn: update failed (gh unavailable or errored, and the $uh mirror did not answer either)" >&2; return 1
      fi
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
    devices) ssh -T "$H" huginn-devices ;;
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
    local) _huginn_local "${@:2}" ;;
    uninstall) _huginn_uninstall "${@:2}" ;;
    desktop)
      local want=''
      case "${2:-}" in
        ''|both|all)             want='' ;;
        win|windows|exe)         want='windows-x64' ;;
        linux|deb|debian|ubuntu) want='linux-x64' ;;
        *) echo "usage: huginn desktop [windows|linux]" >&2; return 1 ;;
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
      local p label sz sha mark linux_file=''
      printf '\n  Huginn Desktop %s   (%s)\n\n' "${ver:-?}" "$tag"
      for p in windows-x64 linux-x64; do
        obj="$(_huginn_json_obj "$man" "$p")"
        file="$(_huginn_json_str "$obj" file)"
        [ -n "$file" ] || continue
        [ "$p" = 'linux-x64' ] && linux_file="$file"
        case "$p" in windows-x64) label='Windows' ;; *) label='Linux  ' ;; esac
        [ "$p" = "$here" ] && mark='   <- this machine' || mark=''
        sz="$(_huginn_json_num "$obj" size)"; sha="$(_huginn_json_str "$obj" sha256)"
        printf '  %s  %s%s\n' "$label" "$base/$file" "$mark"
        printf '           %s   sha256 %s\n' \
          "$(awk -v b="${sz:-0}" 'BEGIN{printf "%6.1f MB", b/1048576}')" "${sha:0:16}..."
      done
      case "$here" in
        linux-x64)   printf '\n  install:  curl -fLO %s/%s && sudo dpkg -i %s\n' "$base" "$linux_file" "$linux_file" ;;
        windows-x64) printf '\n  install:  run the .exe (per-user NSIS installer, no admin needed)\n' ;;
        *)           printf '\n  (no desktop build for this machine - these links are for your laptop)\n' ;;
      esac
      printf '  An installed client self-updates from this same feed.\n\n' ;;
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
_huginn_complete() {
  local cur prev cmds
  cur="${COMP_WORDS[COMP_CWORD]}"
  prev="${COMP_WORDS[COMP_CWORD-1]}"
  cmds="list ls status st rounds headroom devices device local llm projects solo rename mv kill end archive revive -p -y usage cost desktop update uninstall version help"
  if [ "$COMP_CWORD" -eq 1 ]; then
    # first word: subcommands + live session names (bare name attaches to it)
    mapfile -t COMPREPLY < <(compgen -W "$cmds $(_huginn_sessions)" -- "$cur")
  else
    case "$prev" in
      kill|end|archive|solo|rename|mv)   # these take an existing session name
        mapfile -t COMPREPLY < <(compgen -W "$(_huginn_sessions)" -- "$cur") ;;
      usage|cost|ccusage)    # date shortcuts + raw report names
        mapfile -t COMPREPLY < <(compgen -W "today yesterday week month daily monthly weekly session blocks statusline" -- "$cur") ;;
      desktop)
        mapfile -t COMPREPLY < <(compgen -W "windows linux both" -- "$cur") ;;
      device)
        mapfile -t COMPREPLY < <(compgen -W "status on off unit update serve" -- "$cur") ;;
      local)
        mapfile -t COMPREPLY < <(compgen -W "status on plan off unit update doctor" -- "$cur") ;;
      uninstall)
        mapfile -t COMPREPLY < <(compgen -W "--all --yes" -- "$cur") ;;
      --scope)
        mapfile -t COMPREPLY < <(compgen -W "look work own" -- "$cur") ;;
      today|yesterday|week|month)   # optional report-type override after a date shortcut
        mapfile -t COMPREPLY < <(compgen -W "daily monthly weekly session blocks statusline" -- "$cur") ;;
      *) COMPREPLY=() ;;
    esac
  fi
}
complete -F _huginn_complete huginn rclaude rcc 2>/dev/null
