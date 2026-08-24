# shellcheck shell=bash
# huginn (bash) - talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 0.9.0

HUGINN_VERSION='0.9.0'
HUGINN_REPO='silencelen/huginn'
# Where `huginn update` may fetch a replacement for THIS FILE, which it then
# sources into the live shell. Pinned, and deliberately NOT $HUGINN_HOST:
# that variable answers "which box do I drive", and letting it also answer
# "whose code do I run" means a typo, a second host or a test alias silently
# becomes a code source. Override needs HUGINN_UPDATE_HOST set on purpose.
HUGINN_UPDATE_HOST_DEFAULT='huginn'

# A session name is letters, digits, and underscore only - no '-', '*', spaces or
# other shell-special characters. This keeps a typo'd flag (e.g. 'huginn --hlp')
# from falling through to the attach path and spawning a junk tmux session, and
# keeps names safe to pass through the remote shell. Enforced again server-side in cc.
_huginn_valid_name() { [[ "$1" =~ ^[A-Za-z0-9_]+$ ]]; }
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
# result comes back — the token never touches a client device. $1=method $2=path.
# Prints the raw JSON body; non-zero exit on any HTTP error or an unreachable
# daemon, which the callers use to fall back.
_huginn_appd() {
  local H="${HUGINN_HOST:-huginn}"
  ssh -T "$H" "curl -sf -X $1 -H \"Authorization: Bearer \$(cat /etc/huginn-appd/token 2>/dev/null)\" \"http://127.0.0.1:8787$2\"" 2>/dev/null
}

# --- desktop download links ---
# The Compose desktop client ships as a PUBLIC GitHub release (tag desktop-v<ver>),
# and that is also where the installed app's own self-updater fetches from — so the
# link printed here is the real distribution source, not a mirror that can drift.
# Deliberately NOT the daemon's /v1/desktop-kt: it serves the same bytes, but every
# route on it needs the host's bearer token and a browser has no way to send one.
# That also makes `desktop` the one verb that works from a device which cannot reach
# the host at all — it is a GitHub fetch, not an ssh.

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
# simply whichever shipped last — usually not the desktop.
# Unauthenticated API, so 60 requests/hour per IP; this is one call per invocation.
_huginn_desktop_release() {
  local tag json
  tag="$(_huginn_get "https://api.github.com/repos/$HUGINN_REPO/releases?per_page=60" | tr -d '\n' \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"desktop-v[^"]*"' | head -1 \
        | sed 's/.*"\(desktop-v[^"]*\)".*/\1/')"
  [ -n "$tag" ] || return 1
  # manifest.json is a release ASSET (the same one the updater verifies sha256
  # against), so the filenames come from the release itself — nothing here has to
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
# is to print both links so they can be sent to a laptop — not to fail.
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
    # Duration is the honest signal — a session that lived 40 minutes dropped; one
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
  huginn end <name>           soft end: ask Claude to wrap up + commit, then
                              (if auto-end is on) end it once it goes idle
  huginn rounds               what this host does on a schedule, and what it found
  huginn devices              machines that can run a chat in their own context
  huginn kill <name>          hard end: stop the session now
  huginn -p "question"        one-shot headless query (reasoning + memory, read-only)
  huginn -y "task"            one-shot that may use tools (bash/files/web + memory)
  huginn usage [args]         Claude Code token/cost report (ccusage; default: daily)
                                e.g. huginn usage monthly | session | blocks | blocks --live
  huginn usage <when>         shortcut date range: today | yesterday | week | month
                                e.g. huginn usage today | huginn usage week session
  huginn desktop              download links for the latest Huginn Desktop build
  huginn desktop win|linux    just that platform's url, bare, for scripting
  huginn update               self-update this client from the repo ($HUGINN_REPO);
                              without gh, from the PINNED $HUGINN_UPDATE_HOST mirror
                              (never $HUGINN_HOST - that would make whichever box you
                              point at a source of code this shell then runs)
  huginn version              show client version
  huginn help | ? | /help     this help

  Session names are letters/digits/underscore only (no - or *) and case-insensitive
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
      echo "huginn: updating client -> $dest"
      if command -v gh >/dev/null 2>&1; then
        # Leave it at $tmp — the syntax check + backup below is the only install path.
        if gh api "repos/$HUGINN_REPO/contents/client/huginn.sh" -H "Accept: application/vnd.github.raw" >"$tmp" 2>/dev/null && [ -s "$tmp" ]; then
          got=1; echo "  pulled from GitHub ($HUGINN_REPO) via gh"
        else
          echo "  (gh fetch failed - falling back to the $H mirror)"
        fi
      fi
      if [ -z "$got" ]; then
        command -v gh >/dev/null 2>&1 || echo "  (gh not installed - using the scp fallback)"
        # The mirror host is PINNED, not $H. This path downloads a shell script
        # and the block below sources it, so the host it comes from is a trust
        # root, not a convenience — and $HUGINN_HOST is routinely repointed at a
        # test box or mistyped. Say which host is being trusted, every time.
        local uh="${HUGINN_UPDATE_HOST:-$HUGINN_UPDATE_HOST_DEFAULT}"
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
        echo "huginn: update failed (gh unavailable or errored, and scp fallback failed)" >&2; return 1
      fi
      ;;
    list|ls)   ssh -T "$H" "tmux ls 2>/dev/null || echo '(no sessions running)'" ;;
    status|st) ssh -T "$H" huginn-status ;;
    # Rendered ON THE HOST, exactly like huginn-status above. Both clients run the
    # same renderer, so there is one implementation of "what a round looks like"
    # rather than one per client. These two files have already drifted over a
    # single version constant; this has far more fields to drift over.
    rounds|round) ssh -T "$H" huginn-rounds ;;
    devices|device) ssh -T "$H" huginn-devices ;;
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
      _huginn_valid_name "$s" || { echo "huginn: invalid session name '$s' (use letters, digits, underscore; no - or *)" >&2; return 1; }
      _huginn_attach "$H" "$s" solo ;;
    rename|mv)
      [ -n "$2" ] && [ -n "$3" ] || { echo "usage: huginn rename <old> <new>" >&2; return 1; }
      # Validate BOTH names: the old one is interpolated into a remote root shell.
      _huginn_valid_name "$2" || { echo "huginn: invalid session name '$2' (use letters, digits, underscore; no - or *)" >&2; return 1; }
      _huginn_valid_name "$3" || { echo "huginn: invalid new name '$3' (use letters, digits, underscore; no - or *)" >&2; return 1; }
      local ro rn; ro="$(_huginn_canon_name "$2")"; rn="$(_huginn_canon_name "$3")"
      ssh -T "$H" "tmux rename-session -t '$(_huginn_tmux_target "$ro")' '$rn' && echo 'renamed: $ro -> $rn'" ;;
    kill)
      [ -n "$2" ] || { echo "usage: huginn kill <name>" >&2; return 1; }
      _huginn_valid_name "$2" || { echo "huginn: invalid session name '$2' (use letters, digits, underscore; no - or *)" >&2; return 1; }
      local kn; kn="$(_huginn_canon_name "$2")"
      # Prefer the daemon's DELETE: it also removes the orphaned /run state file
      # and releases the pane lease, which a bare tmux kill-session leaves behind
      # (Claude's SessionEnd hook never fires on a kill). Fall back to tmux if the
      # daemon is unreachable — kill must work even when appd is down.
      # '=' anchor on the fallback: without it 'huginn kill andvari' kills 'andvariautofill'.
      if _huginn_appd DELETE "/v1/sessions/$kn" >/dev/null 2>&1; then
        echo "killed: $kn"
      else
        ssh -T "$H" "tmux kill-session -t '$(_huginn_tmux_target "$kn")' && echo 'killed: $kn'"
      fi ;;
    end)
      [ -n "$2" ] || { echo "usage: huginn end <name>" >&2; return 1; }
      _huginn_valid_name "$2" || { echo "huginn: invalid session name '$2' (use letters, digits, underscore; no - or *)" >&2; return 1; }
      local en; en="$(_huginn_canon_name "$2")"
      # Soft end: ask Claude to wrap up (finish, commit, prepare to end) and — when
      # auto-end is on for the host — end the session once it settles. This is a
      # DAEMON feature (it types into the pane and watches state), so there is no
      # tmux fallback; the phrase is whatever the host is configured to send.
      local r; r="$(_huginn_appd POST "/v1/sessions/$en/soft-end")" || {
        echo "huginn: soft-end failed for '$en' (is huginn-appd running? is the session a live Claude pane?)" >&2; return 1; }
      local phrase auto; phrase="$(printf '%s' "$r" | sed -n 's/.*"phrase":"\([^"]*\)".*/\1/p')"
      printf '%s' "$r" | grep -q '"auto":true' && auto=' (auto-ends when it goes idle)' || auto=''
      echo "soft-ended '$en': sent \"${phrase:-wrap-up phrase}\"${auto}" ;;
    -p|-y)
      local mode="$1"; shift
      [ "$#" -gt 0 ] || { echo "usage: huginn $mode \"your prompt\"" >&2; return 1; }
      local q="$*"; q=${q//\'/\'\\\'\'}            # POSIX single-quote escape
      # Kept in step with huginn-appd's ask/act tool sets (server/appd TOOLS/
      # DISALLOWED): -p is read-only reasoning + web + memory, -y may also mutate.
      # The DISALLOWED deny-list is the real fence — --allowedTools only
      # auto-approves, so without it a -p query could still be granted Bash.
      # The flag is assembled HERE and interpolated into the remote command, so the
      # quoting is bash SYNTAX on the host. Building it into a remote variable and
      # expanding it unquoted (the 0.8.0 pre-release form) word-split it into
      # `'Bash` `Edit` `Write` `NotebookEdit'` — literal quotes, no valid tool name,
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
      _huginn_valid_name "$1" || { echo "huginn: invalid session name '$1' (use letters, digits, underscore; no - or *). Did you mean a subcommand? Try 'huginn help'." >&2; return 1; }
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
  cmds="list ls status st rounds devices solo rename mv kill end -p -y usage cost desktop update version help"
  if [ "$COMP_CWORD" -eq 1 ]; then
    # first word: subcommands + live session names (bare name attaches to it)
    mapfile -t COMPREPLY < <(compgen -W "$cmds $(_huginn_sessions)" -- "$cur")
  else
    case "$prev" in
      kill|end|solo|rename|mv)   # these take an existing session name
        mapfile -t COMPREPLY < <(compgen -W "$(_huginn_sessions)" -- "$cur") ;;
      usage|cost|ccusage)    # date shortcuts + raw report names
        mapfile -t COMPREPLY < <(compgen -W "today yesterday week month daily monthly weekly session blocks statusline" -- "$cur") ;;
      desktop)
        mapfile -t COMPREPLY < <(compgen -W "windows linux both" -- "$cur") ;;
      today|yesterday|week|month)   # optional report-type override after a date shortcut
        mapfile -t COMPREPLY < <(compgen -W "daily monthly weekly session blocks statusline" -- "$cur") ;;
      *) COMPREPLY=() ;;
    esac
  fi
}
complete -F _huginn_complete huginn rclaude rcc 2>/dev/null
