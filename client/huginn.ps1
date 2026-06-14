# huginn (PowerShell) — talk to your remote Claude Code node.
# Install: source from your $PROFILE:
#     if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
# Targets the `huginn` SSH alias by default; override per-device with:  $env:HUGINN_HOST = 'my-host'
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 2026-06-14

$script:HUGINN_VERSION = '2026-06-14'
$script:HUGINN_REPO    = 'silencelen/huginn'

function huginn {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  if ($args.Count -eq 0) {
    ssh -tt $H cc
  } elseif ($args[0] -in '?','help','/help','-h','--help') {
    Write-Host @"

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
  huginn update               self-update this client from the repo ($script:HUGINN_REPO)
  huginn version              show client version
  huginn help | ? | /help     this help

  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.  Reattach from any device.
  Host via the 'huginn' SSH alias; override with `$env:HUGINN_HOST.

"@
  } elseif ($args[0] -eq 'version' -or $args[0] -eq '--version' -or $args[0] -eq '-v') {
    Write-Host "huginn-cli $script:HUGINN_VERSION  (host: $H)"
  } elseif ($args[0] -eq 'update') {
    $dest = if ($PSCommandPath) { $PSCommandPath } else { "$HOME\.huginn\huginn.ps1" }
    $tmp  = "$dest.tmp"
    $got  = $false
    Write-Host "huginn: updating client -> $dest"
    if (Get-Command gh -ErrorAction SilentlyContinue) {
      try {
        gh api "repos/$script:HUGINN_REPO/contents/client/huginn.ps1" -H "Accept: application/vnd.github.raw" 2>$null | Out-File -FilePath $tmp -Encoding utf8
        if ((Test-Path $tmp) -and (Get-Item $tmp).Length -gt 0) {
          Move-Item -Force $tmp $dest; $got = $true; Write-Host "  pulled from GitHub ($script:HUGINN_REPO) via gh" -ForegroundColor Green
        }
      } catch {}
    }
    if (-not $got) {
      scp "${H}:/usr/local/share/huginn-cli/huginn.ps1" $dest
      if ($LASTEXITCODE -eq 0) { $got = $true; Write-Host "  pulled from $H mirror via scp" -ForegroundColor Green }
    }
    if (Test-Path $tmp) { Remove-Item -Force $tmp -ErrorAction SilentlyContinue }
    # NOTE: dot-sourcing here would only update this function's local scope, not the global
    # session — so we don't pretend to hot-reload. Tell the user to reload.
    if ($got) {
      Write-Host "  client file updated. Open a new PowerShell (or run '. `$PROFILE') to load it into this session." -ForegroundColor Yellow
    } else { Write-Host "huginn: update failed (no gh, scp failed)" -ForegroundColor Red }
  } elseif ($args[0] -eq 'list' -or $args[0] -eq 'ls') {
    ssh -T $H "tmux ls 2>/dev/null || echo '(no sessions running)'"
  } elseif ($args[0] -eq 'status' -or $args[0] -eq 'st') {
    ssh -T $H huginn-status
  } elseif ($args[0] -eq 'usage' -or $args[0] -eq 'cost' -or $args[0] -eq 'ccusage') {
    $sub = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] -join ' ' } else { 'daily' }
    # Full history is layered server-side by the /usr/local/bin/ccusage wrapper on huginn — keep this bare.
    ssh -tt $H "ccusage $sub"   # default 'daily'. -tt for tables + --live.
  } elseif ($args[0] -eq 'solo') {
    $name = if ($args.Count -gt 1) { $args[1] } else { 'main' }
    ssh -tt $H "cc solo $name"
  } elseif ($args[0] -eq 'rename' -or $args[0] -eq 'mv') {
    if ($args.Count -lt 3) { Write-Host "usage: huginn rename <old> <new>"; return }
    ssh -T $H "tmux rename-session -t '$($args[1])' '$($args[2])' && echo 'renamed: $($args[1]) -> $($args[2])'"
  } elseif ($args[0] -eq 'kill') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn kill <name>"; return }
    ssh -T $H "tmux kill-session -t '$($args[1])' && echo 'killed: $($args[1])'"
  } elseif ($args[0] -eq '-p' -or $args[0] -eq '-y') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn $($args[0]) ""your prompt"""; return }
    $q = ($args[1..($args.Count - 1)] -join ' '); $esc = $q -replace "'", "'\''"  # POSIX single-quote escape
    $tools = if ($args[0] -eq '-y') { "Bash Read Edit Write Glob Grep WebFetch mcp__mempalace" } else { "mcp__mempalace" }
    # Persona-aware: if the host carries persona.md, inject it + memory tools; else plain headless query.
    ssh -T $H "cd ~/netplan 2>/dev/null || cd `"`$HOME`"; P=`"`$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)`"; if [ -n `"`$P`" ]; then echo '$esc' | claude -p --append-system-prompt `"`$P`" --allowedTools '$tools'; else echo '$esc' | claude -p; fi"
  } else {
    ssh -tt $H "cc $($args[0])"
  }
}
Set-Alias rclaude huginn
Set-Alias rcc huginn

Register-ArgumentCompleter -CommandName huginn, rclaude, rcc -ScriptBlock {
  param($word, $ast, $pos)
  'list', 'status', 'solo', 'rename', 'kill', '-p', '-y', 'usage', 'cost', 'update', 'version', 'help' |
    Where-Object { $_ -like "$word*" } |
    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
}
