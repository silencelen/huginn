# huginn (PowerShell) — talk to your remote Claude Code node.
# Install: source from your $PROFILE:
#     if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
# Targets the `huginn` SSH alias by default; override per-device with:  $env:HUGINN_HOST = 'my-host'

function huginn {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  if ($args.Count -eq 0) {
    ssh -t $H cc
  } elseif ($args[0] -in '?','help','/help','-h','--help') {
    Write-Host @"

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
  Host via the 'huginn' SSH alias; override with `$env:HUGINN_HOST.

"@
  } elseif ($args[0] -eq 'list' -or $args[0] -eq 'ls') {
    ssh -T $H "tmux ls 2>/dev/null || echo '(no sessions running)'"
  } elseif ($args[0] -eq 'status' -or $args[0] -eq 'st') {
    ssh -T $H huginn-status
  } elseif ($args[0] -eq 'solo') {
    $name = if ($args.Count -gt 1) { $args[1] } else { 'main' }
    ssh -t $H "cc solo $name"
  } elseif ($args[0] -eq 'rename' -or $args[0] -eq 'mv') {
    if ($args.Count -lt 3) { Write-Host "usage: huginn rename <old> <new>"; return }
    ssh -T $H "tmux rename-session -t '$($args[1])' '$($args[2])' && echo 'renamed: $($args[1]) -> $($args[2])'"
  } elseif ($args[0] -eq 'kill') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn kill <name>"; return }
    ssh -T $H "tmux kill-session -t '$($args[1])' && echo 'killed: $($args[1])'"
  } elseif ($args[0] -eq '-p' -or $args[0] -eq '-y') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn $($args[0]) ""your prompt"""; return }
    $q     = ($args[1..($args.Count - 1)] -join ' '); $esc = $q -replace "'", "'\''"  # POSIX single-quote escape
    $tools = if ($args[0] -eq '-y') { " --allowedTools 'Bash Read Edit Write Glob Grep WebFetch'" } else { "" }
    ssh -T $H "echo '$esc' | claude -p$tools"
  } else {
    ssh -t $H "cc $($args[0])"
  }
}
Set-Alias rclaude huginn

Register-ArgumentCompleter -CommandName huginn, rclaude -ScriptBlock {
  param($word, $ast, $pos)
  'list', 'status', 'solo', 'rename', 'kill', '-p', '-y', 'help' |
    Where-Object { $_ -like "$word*" } |
    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
}
