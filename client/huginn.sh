# shellcheck shell=bash
# huginn (bash) - talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 0.8.1

HUGINN_VERSION='0.8.1'
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
  huginn kill <name>          hard end: stop the session now
  huginn -p "question"        one-shot headless query (reasoning + memory, read-only)
  huginn -y "task"            one-shot that may use tools (bash/files/web + memory)
  huginn usage [args]         Claude Code token/cost report (ccusage; default: daily)
                                e.g. huginn usage monthly | session | blocks | blocks --live
  huginn usage <when>         shortcut date range: today | yesterday | week | month
                                e.g. huginn usage today | huginn usage week session
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
  cmds="list ls status st solo rename mv kill end -p -y usage cost update version help"
  if [ "$COMP_CWORD" -eq 1 ]; then
    # first word: subcommands + live session names (bare name attaches to it)
    mapfile -t COMPREPLY < <(compgen -W "$cmds $(_huginn_sessions)" -- "$cur")
  else
    case "$prev" in
      kill|end|solo|rename|mv)   # these take an existing session name
        mapfile -t COMPREPLY < <(compgen -W "$(_huginn_sessions)" -- "$cur") ;;
      usage|cost|ccusage)    # date shortcuts + raw report names
        mapfile -t COMPREPLY < <(compgen -W "today yesterday week month daily monthly weekly session blocks statusline" -- "$cur") ;;
      today|yesterday|week|month)   # optional report-type override after a date shortcut
        mapfile -t COMPREPLY < <(compgen -W "daily monthly weekly session blocks statusline" -- "$cur") ;;
      *) COMPREPLY=() ;;
    esac
  fi
}
complete -F _huginn_complete huginn rclaude rcc 2>/dev/null
