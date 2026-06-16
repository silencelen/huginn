# shellcheck shell=bash
# huginn (bash) - talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 2026-06-16

HUGINN_VERSION='2026-06-16'
HUGINN_REPO='silencelen/huginn'

# --- auto-reconnecting attach ---
# The session lives in tmux ON the host, so a dropped link (laptop sleep, wifi
# flap) only severs the ssh client - the work keeps running. We re-run the attach
# whenever ssh dies with a TRANSPORT failure (exit 255); a clean tmux detach
# (Alt-d / Ctrl-b d) or shell exit passes its own code straight through and ends
# the loop. ServerAlive* makes a half-open socket (post-sleep) die in ~45s
# instead of hanging. Reconnect is dynamic: mirror if another device is still
# attached, else take it solo (full screen). Our own dead client (the ghost the
# dropped link left attached) still counts server-side, so the test is >=2
# clients (ghost + a real other) -> mirror, just the ghost (or none) -> solo.
# The count + attach run in ONE remote command, so the decision is atomic.
# Opt out: export HUGINN_NO_RECONNECT=1
_huginn_attach() {
  # $1=host  $2=session (default main)  $3=non-empty => start in solo
  local H="$1" session="${2:-main}" solo="$3" delay=2 rc remote
  remote="cc $session${solo:+ solo}"
  while :; do
    ssh -tt -o ServerAliveInterval=15 -o ServerAliveCountMax=3 "$H" "$remote"
    rc=$?
    { [ "$rc" -ne 255 ] || [ -n "$HUGINN_NO_RECONNECT" ]; } && return "$rc"
    printf '\nhuginn: link to %s dropped - reconnecting in %ss (Ctrl-C to stop)...\n' "$H" "$delay" >&2
    sleep "$delay" || { echo 'huginn: reconnect cancelled.' >&2; return 130; }
    # mirror if another client is still attached, else solo (evicts the ghost)
    remote="if [ \"\$(tmux list-clients -t $session 2>/dev/null | wc -l)\" -ge 2 ]; then cc $session; else cc $session solo; fi"
    delay=$(( delay < 15 ? delay * 2 : 15 ))
  done
}

huginn() {
  local H="${HUGINN_HOST:-huginn}"
  case "$1" in
    "")
      _huginn_attach "$H"
      ;;
    '?'|help|/help|-h|--help)
      cat <<EOF

huginn - remote Claude Code node.  aliases: rclaude, rcc

  huginn                      attach/create the live 'main' session (run claude inside)
  huginn <name>               a separate named session
  huginn solo [name]          attach + detach all OTHER clients (resume solo / full screen)
  huginn list | ls            list sessions + attach status
  huginn status | st          health: uptime, auth, sessions, disk
  huginn rename <old> <new>   rename a session (alias: mv)
  huginn kill <name>          end a session
  huginn -p "question"        one-shot headless query (reasoning + memory, read-only)
  huginn -y "task"            one-shot that may use tools (bash/files/web + memory)
  huginn usage [args]         Claude Code token/cost report (ccusage; default: daily)
                                e.g. huginn usage monthly | session | blocks | blocks --live
  huginn update               self-update this client from the repo ($HUGINN_REPO)
  huginn version              show client version
  huginn help | ? | /help     this help

  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.  Reattach from any device.
  Host via the 'huginn' SSH alias; override with HUGINN_HOST.
  Attach auto-reconnects after a dropped link (laptop sleep); Ctrl-C during the
  wait to stop. Disable with HUGINN_NO_RECONNECT=1.

EOF
      ;;
    version|--version|-v)
      echo "huginn-cli $HUGINN_VERSION  (host: $H)" ;;
    update)
      local dest="${BASH_SOURCE[0]:-$HOME/.huginn/huginn.sh}" tmp got=
      tmp="$dest.tmp"
      echo "huginn: updating client -> $dest"
      if command -v gh >/dev/null 2>&1; then
        if gh api "repos/$HUGINN_REPO/contents/client/huginn.sh" -H "Accept: application/vnd.github.raw" >"$tmp" 2>/dev/null && [ -s "$tmp" ]; then
          mv -f "$tmp" "$dest"; got=1; echo "  pulled from GitHub ($HUGINN_REPO) via gh"
        fi
      fi
      if [ -z "$got" ]; then
        if scp "$H:/usr/local/share/huginn-cli/huginn.sh" "$dest"; then got=1; echo "  pulled from $H mirror via scp"; fi
      fi
      rm -f "$tmp" 2>/dev/null
      if [ -n "$got" ]; then
        # shellcheck disable=SC1090
        source "$dest"; huginn version
      else
        echo "huginn: update failed (no gh, scp failed)" >&2; return 1
      fi
      ;;
    list|ls)   ssh -T "$H" "tmux ls 2>/dev/null || echo '(no sessions running)'" ;;
    status|st) ssh -T "$H" huginn-status ;;
    usage|cost|ccusage)
      shift                                         # ccusage report; default 'daily'. -tt for tables + --live.
      # Full history (back to 2026-01) is layered server-side by the /usr/local/bin/ccusage
      # wrapper on huginn - keep this call bare so client-side quoting can't break it.
      ssh -tt "$H" "ccusage ${*:-daily}" ;;
    solo)      _huginn_attach "$H" "${2:-main}" solo ;;
    rename|mv)
      [ -n "$2" ] && [ -n "$3" ] || { echo "usage: huginn rename <old> <new>" >&2; return 1; }
      ssh -T "$H" "tmux rename-session -t '$2' '$3' && echo 'renamed: $2 -> $3'" ;;
    kill)
      [ -n "$2" ] || { echo "usage: huginn kill <name>" >&2; return 1; }
      ssh -T "$H" "tmux kill-session -t '$2' && echo 'killed: $2'" ;;
    -p|-y)
      local mode="$1"; shift
      [ "$#" -gt 0 ] || { echo "usage: huginn $mode \"your prompt\"" >&2; return 1; }
      local q="$*"; q=${q//\'/\'\\\'\'}            # POSIX single-quote escape
      local tools="mcp__mempalace"
      [ "$mode" = "-y" ] && tools="Bash Read Edit Write Glob Grep WebFetch mcp__mempalace"
      # Persona-aware: if the host carries persona.md, inject it + memory tools; else plain headless query.
      ssh -T "$H" "cd ~/netplan 2>/dev/null || cd \"\$HOME\"; P=\"\$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)\"; if [ -n \"\$P\" ]; then echo '$q' | claude -p --append-system-prompt \"\$P\" --allowedTools '$tools'; else echo '$q' | claude -p; fi" ;;
    *)         _huginn_attach "$H" "$1" ;;
  esac
}

rclaude() { huginn "$@"; }
rcc()     { huginn "$@"; }

# tab completion
_huginn_complete() {
  mapfile -t COMPREPLY < <(compgen -W "list ls status st solo rename mv kill -p -y usage cost update version help" -- "${COMP_WORDS[COMP_CWORD]}")
}
complete -F _huginn_complete huginn rclaude rcc 2>/dev/null
