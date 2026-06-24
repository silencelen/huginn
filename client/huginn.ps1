# huginn (PowerShell) - talk to your remote Claude Code node.
# Install: source from your $PROFILE:
#     if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
# Targets the `huginn` SSH alias by default; override per-device with:  $env:HUGINN_HOST = 'my-host'
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 0.4.1

$script:HUGINN_VERSION = '0.4.1'
$script:HUGINN_REPO    = 'silencelen/huginn'

# A session name is letters, digits, and underscore only - no '-', '*', spaces or
# other shell-special characters. This keeps a typo'd flag (e.g. 'huginn --hlp')
# from falling through to the attach path and spawning a junk tmux session, and
# keeps names safe to pass through the remote shell. Enforced again server-side in cc.
function _Huginn-ValidName { param([string]$Name) return ($Name -match '^[A-Za-z0-9_]+$') }
# Session names are case-INSENSITIVE: lowercase before touching tmux so 'Test' and
# 'test' resolve to the same session (tmux itself is case-sensitive). Canonicalized
# here for every tmux-facing path AND again server-side in cc as the backstop.
function _Huginn-CanonName { param([string]$Name) return $Name.ToLower() }

# --- auto-reconnecting attach ---
# The session lives in tmux ON the host, so a dropped link (laptop sleep, wifi
# flap) only severs the ssh client - the work keeps running. We re-run the attach
# whenever ssh exits non-zero (dropped link / transport failure - code varies by
# OS, e.g. 255); a clean tmux detach (Alt-d / Ctrl-b d) or normal shell exit
# returns 0 and ends the loop.
# ServerAlive* makes a half-open socket (post-sleep) die in ~45s instead of
# hanging. Reconnect is dynamic: mirror if another device is still attached, else
# take it solo (full screen). Our own dead client (the ghost the dropped link
# left attached) still counts server-side, so the test is >=2 clients (ghost + a
# real other) -> mirror, just the ghost (or none) -> solo; the count + attach run
# in ONE remote command. Opt out: $env:HUGINN_NO_RECONNECT = '1'
#
# Tab naming: the terminal tab/window is renamed to the session name, so
# 'huginn costtracking' gives you a 'costtracking' tab in Windows
# Terminal. tmux's set-titles defaults OFF, so the inner Claude TUI's own title
# sequences are absorbed by tmux and never reach this terminal -> the title we set
# here survives the whole session. Previous title restored on exit. Opt out:
# $env:HUGINN_NO_TITLE = '1'
function _Huginn-Attach {
  param([string]$H, [string]$Session = 'main', [switch]$Solo)
  $Session = _Huginn-CanonName $Session   # case-insensitive: 'Test' -> 'test'
  $prevTitle = $null
  try { $prevTitle = $Host.UI.RawUI.WindowTitle } catch {}
  try {
    if (-not $env:HUGINN_NO_TITLE) { try { $Host.UI.RawUI.WindowTitle = "$Session" } catch {} }
    $delay  = 2
    $remote = "cc $Session" + $(if ($Solo) { ' solo' } else { '' })
    while ($true) {
      ssh -tt -o ServerAliveInterval=15 -o ServerAliveCountMax=3 $H $remote
      $rc = $LASTEXITCODE
      if ($rc -eq 0 -or $env:HUGINN_NO_RECONNECT) { return }
      Write-Host "`nhuginn: link to $H dropped (ssh exit $rc) - reconnecting in ${delay}s (Ctrl-C to stop)..." -ForegroundColor Yellow
      Start-Sleep -Seconds $delay
      # mirror if another client is still attached, else solo (evicts the ghost).
      # Single-quoted bash so $(...) is evaluated on the host, not by PowerShell.
      $remote = 'if [ "$(tmux list-clients -t ' + $Session + ' 2>/dev/null | wc -l)" -ge 2 ]; then cc ' + $Session + '; else cc ' + $Session + ' solo; fi'
      if ($delay -lt 15) { $delay = [Math]::Min($delay * 2, 15) }
    }
  } finally {
    if (($null -ne $prevTitle) -and (-not $env:HUGINN_NO_TITLE)) { try { $Host.UI.RawUI.WindowTitle = $prevTitle } catch {} }
  }
}

function huginn {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  if ($args.Count -eq 0) {
    _Huginn-Attach -H $H
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

  Session names are letters/digits/underscore only (no - or *) and case-insensitive
  ('Test' and 'test' are the same session).
  In a session: run claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.  Reattach from any device.
  Host via the 'huginn' SSH alias; override with `$env:HUGINN_HOST.
  Attach auto-reconnects after a dropped link (laptop sleep); Ctrl-C during the
  wait to stop. Disable with `$env:HUGINN_NO_RECONNECT = '1'.
  The terminal tab is named after the session (<name>); `$env:HUGINN_NO_TITLE='1' off.
  A state icon leads the tab title while Claude runs: working / needs-you / waiting
  (set host-side by the claude hooks; needs the server's title hook installed).

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
    # session - so we don't pretend to hot-reload. Tell the user to reload.
    if ($got) {
      Write-Host "  client file updated. Open a new PowerShell (or run '. `$PROFILE') to load it into this session." -ForegroundColor Yellow
    } else { Write-Host "huginn: update failed (no gh, scp failed)" -ForegroundColor Red }
  } elseif ($args[0] -eq 'list' -or $args[0] -eq 'ls') {
    ssh -T $H "tmux ls 2>/dev/null || echo '(no sessions running)'"
  } elseif ($args[0] -eq 'status' -or $args[0] -eq 'st') {
    ssh -T $H huginn-status
  } elseif ($args[0] -eq 'usage' -or $args[0] -eq 'cost' -or $args[0] -eq 'ccusage') {
    $sub = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] -join ' ' } else { 'daily' }
    # Full history is layered server-side by the /usr/local/bin/ccusage wrapper on huginn - keep this bare.
    ssh -tt $H "ccusage $sub"   # default 'daily'. -tt for tables + --live.
  } elseif ($args[0] -eq 'solo') {
    $name = if ($args.Count -gt 1) { $args[1] } else { 'main' }
    if (-not (_Huginn-ValidName $name)) { Write-Host "huginn: invalid session name '$name' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    _Huginn-Attach -H $H -Session $name -Solo
  } elseif ($args[0] -eq 'rename' -or $args[0] -eq 'mv') {
    if ($args.Count -lt 3) { Write-Host "usage: huginn rename <old> <new>"; return }
    if (-not (_Huginn-ValidName $args[2])) { Write-Host "huginn: invalid new name '$($args[2])' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    $ro = _Huginn-CanonName $args[1]; $rn = _Huginn-CanonName $args[2]
    ssh -T $H "tmux rename-session -t '$ro' '$rn' && echo 'renamed: $ro -> $rn'"
  } elseif ($args[0] -eq 'kill') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn kill <name>"; return }
    $kn = _Huginn-CanonName $args[1]
    ssh -T $H "tmux kill-session -t '$kn' && echo 'killed: $kn'"
  } elseif ($args[0] -eq '-p' -or $args[0] -eq '-y') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn $($args[0]) ""your prompt"""; return }
    $q = ($args[1..($args.Count - 1)] -join ' '); $esc = $q -replace "'", "'\''"  # POSIX single-quote escape
    $tools = if ($args[0] -eq '-y') { "Bash Read Edit Write Glob Grep WebFetch mcp__mempalace" } else { "mcp__mempalace" }
    # Persona-aware: if the host carries persona.md, inject it + memory tools; else plain headless query.
    ssh -T $H "cd ~/netplan 2>/dev/null || cd `"`$HOME`"; P=`"`$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)`"; if [ -n `"`$P`" ]; then echo '$esc' | claude -p --append-system-prompt `"`$P`" --allowedTools '$tools'; else echo '$esc' | claude -p; fi"
  } else {
    if (-not (_Huginn-ValidName $args[0])) { Write-Host "huginn: invalid session name '$($args[0])' (use letters, digits, underscore; no - or *). Did you mean a subcommand? Try 'huginn help'." -ForegroundColor Red; return }
    _Huginn-Attach -H $H -Session $args[0]
  }
}
Set-Alias rclaude huginn
Set-Alias rcc huginn

# Live session names from the host (tmux ls), cached in-memory for a few seconds so
# repeated Tab doesn't ssh every keystroke. BatchMode stops a missing key from hanging
# the prompt; ConnectTimeout bounds a slow link.
$script:HUGINN_SESS_CACHE = @()
$script:HUGINN_SESS_TS    = [datetime]::MinValue
function _Huginn-Sessions {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  if (((Get-Date) - $script:HUGINN_SESS_TS).TotalSeconds -ge 5) {
    $script:HUGINN_SESS_CACHE = @(ssh -T -o BatchMode=yes -o ConnectTimeout=2 $H "tmux ls -F '#S' 2>/dev/null" 2>$null)
    $script:HUGINN_SESS_TS    = Get-Date
  }
  return $script:HUGINN_SESS_CACHE
}
Register-ArgumentCompleter -CommandName huginn, rclaude, rcc -ScriptBlock {
  param($word, $ast, $pos)
  $cmds = 'list', 'status', 'solo', 'rename', 'kill', '-p', '-y', 'usage', 'cost', 'update', 'version', 'help'
  # tokens already typed after the command name, excluding the partial word being completed
  $typed = @($ast.CommandElements | Select-Object -Skip 1 | ForEach-Object { $_.ToString() })
  if ($word -and $typed.Count -ge 1) { $typed = @($typed | Select-Object -SkipLast 1) }
  $prev = if ($typed.Count -ge 1) { $typed[-1] } else { '' }
  if ($typed.Count -eq 0) {
    $candidates = $cmds + @(_Huginn-Sessions)          # first word: subcommands + sessions
  } elseif ($prev -in 'kill', 'solo', 'rename', 'mv') {
    $candidates = @(_Huginn-Sessions)                  # these take an existing session name
  } else {
    $candidates = @()
  }
  $candidates | Where-Object { $_ -like "$word*" } |
    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
}
