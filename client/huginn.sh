# huginn (bash) — talk to your remote Claude Code node.
# Install: source from your ~/.bashrc:
#     [ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh
# Targets the `huginn` SSH alias by default; override per-device with:  export HUGINN_HOST=my-host

huginn() {
  local H="${HUGINN_HOST:-huginn}"
  case "$1" in
    "")
      ssh -t "$H" cc
      ;;
    '?'|help|/help|-h|--help)
      cat <<'EOF'

huginn - remote Claude Code node.  alias: rclaude

  huginn                      attach/create the live 'main' session (run claude inside)
  huginn <name>               a separate named session
  huginn solo [name]          attach + detach all OTHER clients (resume solo / full screen)
  huginn list | ls            list sessions
  huginn status | st          health: uptime, auth, sessions, disk
  huginn rename <old> <new>   rename a session (alias: mv)
  huginn kill <name>          end a session
  huginn -p "question"        one-shot headless query (reasoning only)
  huginn -y "task"            one-shot that may use tools (bash/files/web)
  huginn help | ? | /help     this help

  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.  Reattach from any device.
  Host via the 'huginn' SSH alias; override with HUGINN_HOST.

EOF
      ;;
    list|ls)   ssh -T "$H" "tmux ls 2>/dev/null || echo '(no sessions running)'" ;;
    status|st) ssh -T "$H" huginn-status ;;
    solo)      ssh -t "$H" "cc solo ${2:-main}" ;;
    rename|mv) ssh -T "$H" "tmux rename-session -t '$2' '$3' && echo 'renamed: $2 -> $3'" ;;
    kill)      ssh -T "$H" "tmux kill-session -t '$2' && echo 'killed: $2'" ;;
    -p)        shift; ssh -T "$H" "echo '$*' | claude -p" ;;
    -y)        shift; ssh -T "$H" "echo '$*' | claude -p --allowedTools 'Bash Read Edit Write Glob Grep WebFetch'" ;;
    *)         ssh -t "$H" "cc $1" ;;
  esac
}

rclaude() { huginn "$@"; }

# tab completion
_huginn_complete() {
  COMPREPLY=($(compgen -W "list ls status st solo rename mv kill -p -y help" -- "${COMP_WORDS[COMP_CWORD]}"))
}
complete -F _huginn_complete huginn rclaude 2>/dev/null
