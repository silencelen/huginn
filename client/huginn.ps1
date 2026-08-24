# huginn (PowerShell) - talk to your remote Claude Code node.
# Install: source from your $PROFILE:
#     if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
# Targets the `huginn` SSH alias by default; override per-device with:  $env:HUGINN_HOST = 'my-host'
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 0.9.0

$script:HUGINN_VERSION = '0.9.0'
$script:HUGINN_REPO    = 'silencelen/huginn'
# Where `huginn update` may fetch a replacement for THIS FILE, which is then loaded
# into the shell. Pinned, and deliberately NOT $HUGINN_HOST: that variable answers
# "which box do I drive", and letting it also answer "whose code do I run" means a
# typo, a second host or a test alias silently becomes a code source. Override needs
# HUGINN_UPDATE_HOST set on purpose. (Ported from huginn.sh, where this shipped in
# 0.6.1 -- the PowerShell client kept fetching from $HUGINN_HOST until 0.8.2.)
$script:HUGINN_UPDATE_HOST_DEFAULT = 'huginn'

# A session name is letters, digits, and underscore only - no '-', '*', spaces or
# other shell-special characters. This keeps a typo'd flag (e.g. 'huginn --hlp')
# from falling through to the attach path and spawning a junk tmux session, and
# keeps names safe to pass through the remote shell. Enforced again server-side in cc.
function _Huginn-ValidName { param([string]$Name) return ($Name -match '^[A-Za-z0-9_]+$') }
# Session names are case-INSENSITIVE: lowercase before touching tmux so 'Test' and
# 'test' resolve to the same session (tmux itself is case-sensitive). Canonicalized
# here for every tmux-facing path AND again server-side in cc as the backstop.
function _Huginn-CanonName { param([string]$Name) return $Name.ToLower() }
# tmux resolves -t targets by EXACT match, then PREFIX, then glob. A unique prefix
# resolves silently, so 'huginn kill andvari' would destroy a session actually named
# 'andvariautofill', and 'huginn solo jt' would evict the real client of 'jtyper'.
# Anchoring with '=' forces exact match (tmux(1) "exact-match").
function _Huginn-TmuxTarget { param([string]$Name) return "=$Name" }

# Reach huginn-appd, which listens on the HOST's loopback. The bearer token is
# root-only on the host, so the call runs THERE (over the ssh alias) and only the
# result comes back - the token never touches a client device.
# Base64 for the same reason as the -p/-y path below: PS 5.1 mangles embedded
# double quotes when marshalling to a native exe, and this command carries both
# quotes and a $(...) that must be evaluated on the host.
# Returns the raw body on success (possibly empty) or $null on any HTTP error /
# unreachable daemon, which callers use to fall back.
function _Huginn-Appd {
  param([string]$H, [string]$Method, [string]$Path)
  $remote = 'curl -sf -X ' + $Method + ' -H "Authorization: Bearer $(cat /etc/huginn-appd/token 2>/dev/null)" "http://127.0.0.1:8787' + $Path + '"'
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
  $out = ssh -T -o BatchMode=yes -o ConnectTimeout=10 $H "echo $b64 | base64 -d | bash -s" 2>$null
  if ($LASTEXITCODE -ne 0) { return $null }
  return ($out -join '')
}

# --- desktop download links ---
# The Compose desktop client ships as a PUBLIC GitHub release (tag desktop-v<ver>),
# and that is also where the installed app's own self-updater fetches from - so the
# link printed here is the real distribution source, not a mirror that can drift.
# Deliberately NOT the daemon's /v1/desktop-kt: it serves the same bytes, but every
# route on it needs the host's bearer token and a browser has no way to send one.
# That also makes `desktop` the one verb that works from a machine which cannot
# reach the host at all - it is a GitHub fetch, not an ssh.
function _Huginn-Get {
  param([string]$Url)
  # PS 5.1 still negotiates TLS 1.0 by default on some Windows builds; GitHub
  # requires 1.2, so the request fails with a bare "could not create SSL/TLS
  # secure channel" that reads like an outage. Set it per call, not globally.
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
  try {
    # -UseBasicParsing: without it PS 5.1 hands the body to the IE engine, which
    # throws on a machine where IE was never first-run.
    $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop
  } catch { return $null }
  $c = $r.Content
  # A release asset is served as application/octet-stream, and PS 5.1 hands back
  # byte[] rather than a string for a non-text type - ConvertFrom-Json chokes on it.
  if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
  return $c
}

# The newest desktop-v* release as @{ tag = ...; manifest = <object> }, or $null.
# Filtered by TAG rather than read from /releases/latest, because four components
# publish into this one feed (v*, app-v*, appd-v*, desktop-v*) and "latest" is
# simply whichever shipped last - usually not the desktop.
# Unauthenticated API, so 60 requests/hour per IP; this is one call per invocation.
function _Huginn-DesktopRelease {
  $body = _Huginn-Get "https://api.github.com/repos/$script:HUGINN_REPO/releases?per_page=60"
  if (-not $body) { return $null }
  try { $releases = $body | ConvertFrom-Json } catch { return $null }
  $tag = ($releases | Where-Object { $_.tag_name -like 'desktop-v*' } | Select-Object -First 1).tag_name
  if (-not $tag) { return $null }
  # manifest.json is a release ASSET (the same one the updater verifies sha256
  # against), so the filenames come from the release itself - nothing here has to
  # guess how jpackage or the NSIS step named an artifact.
  $man = _Huginn-Get "https://github.com/$script:HUGINN_REPO/releases/download/$tag/manifest.json"
  if (-not $man) { return $null }
  try { return @{ tag = $tag; manifest = ($man | ConvertFrom-Json) } } catch { return $null }
}

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
    $quick  = 0
    $tgt    = _Huginn-TmuxTarget $Session
    $remote = "cc $Session" + $(if ($Solo) { ' solo' } else { '' })
    while ($true) {
      $t0 = Get-Date
      ssh -tt -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 $H $remote
      $rc = $LASTEXITCODE
      $elapsed = ((Get-Date) - $t0).TotalSeconds
      if ($rc -eq 0 -or $env:HUGINN_NO_RECONNECT) { return }
      # Bound by DURATION, not exit code: real drops on Windows OpenSSH / Termux do
      # not reliably return 255. Three sub-5s exits in a row means the remote is
      # failing instantly (bad workdir, cc error) and retrying only hides the error.
      if ($elapsed -lt 5) {
        $quick++
        if ($quick -ge 3) {
          Write-Host "`nhuginn: $H is failing immediately ($quick attempts, last exit $rc) - giving up.`n  The remote error is printed above; fix it or run 'huginn $Session' again." -ForegroundColor Red
          return
        }
      } else { $quick = 0 }
      Write-Host "`nhuginn: link to $H dropped (ssh exit $rc) - reconnecting in ${delay}s (Ctrl-C to stop)..." -ForegroundColor Yellow
      # Jitter: every tab shares one tunnel, so an unjittered backoff makes them all
      # re-handshake on the identical second after a single relay flap.
      Start-Sleep -Milliseconds ([int]($delay * 1000 * (0.75 + (Get-Random -Minimum 0.0 -Maximum 0.5))))
      # Do NOT resurrect a session killed from another device (cc would fall through
      # to new-session -A and spawn a second claude). Then: mirror if another client
      # is attached, else solo (evicts the ghost).
      # Single-quoted bash so $(...) is evaluated on the host, not by PowerShell.
      $remote = 'tmux has-session -t ' + $tgt + ' 2>/dev/null || { echo "huginn: session ' + $Session + ' no longer exists on ' + $H + '"; exit 0; }; if [ "$(tmux list-clients -t ' + $tgt + ' 2>/dev/null | wc -l)" -ge 2 ]; then cc ' + $Session + '; else cc ' + $Session + ' solo; fi'
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
    # Banner in a SINGLE-quoted here-string: the art must reach the terminal
    # verbatim, and in an expandable one PowerShell would treat its punctuation
    # as escapes. The body below stays expandable for $script:HUGINN_REPO.
    Write-Host @'

        _
       (o)==-   huginn - remote Claude Code node.  aliases: rclaude, rcc
       //\
    =~/_/
'@
    Write-Host @"

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
  huginn desktop              download links for the latest Huginn Desktop build
  huginn desktop win|linux    just that platform's url, bare, for scripting
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
        # Set-Content -Encoding ascii, NOT Out-File -Encoding utf8: on PS 5.1 the
        # latter writes a UTF-8 BOM, which is exactly the parse failure commit
        # 691d602 ("pure ASCII") was written to eliminate. The client is ASCII.
        gh api "repos/$script:HUGINN_REPO/contents/client/huginn.ps1" -H "Accept: application/vnd.github.raw" 2>$null |
          Set-Content -Path $tmp -Encoding ascii
        # try/catch cannot catch a native command's failure, so check the code.
        # A non-empty ERROR BODY is still non-empty - length alone is not a gate.
        if ($LASTEXITCODE -eq 0 -and (Test-Path $tmp) -and (Get-Item $tmp).Length -gt 2000) {
          $got = $true; Write-Host "  pulled from GitHub ($script:HUGINN_REPO) via gh" -ForegroundColor Green
        } else {
          Write-Host "  (gh fetch failed - falling back to the $H mirror)" -ForegroundColor DarkGray
        }
      } catch { Write-Host "  (gh threw - falling back to the $H mirror)" -ForegroundColor DarkGray }
    } else {
      Write-Host "  (gh not installed - using the scp fallback)" -ForegroundColor DarkGray
    }
    if (-not $got) {
      # BatchMode so a device without an authorized key fails instead of silently
      # dropping into a password prompt mid-"update".
      # The downloaded file is loaded into the shell, so the host it comes from is a
      # trust root, not just a transport. Say which host is being trusted when it is
      # not the pinned default.
      $uh = if ($env:HUGINN_UPDATE_HOST) { $env:HUGINN_UPDATE_HOST } else { $script:HUGINN_UPDATE_HOST_DEFAULT }
      if ($uh -ne $script:HUGINN_UPDATE_HOST_DEFAULT) {
        Write-Host "  (HUGINN_UPDATE_HOST is set - trusting $uh for this client's code)" -ForegroundColor Yellow
      }
      scp -o BatchMode=yes "${uh}:/usr/local/share/huginn-cli/huginn.ps1" $tmp
      if ($LASTEXITCODE -eq 0) { $got = $true; Write-Host "  pulled from $uh mirror via scp" -ForegroundColor Green }
    }
    # Validate before installing: a truncated download that overwrites the live
    # client leaves every future shell broken, with no copy to fall back to.
    if ($got) {
      try {
        $null = [ScriptBlock]::Create((Get-Content $tmp -Raw))
        if (Test-Path $dest) { Copy-Item -Force $dest "$dest.bak" -ErrorAction SilentlyContinue }
        Move-Item -Force $tmp $dest
        Write-Host "  (previous version saved as $(Split-Path -Leaf $dest).bak)" -ForegroundColor DarkGray
      } catch {
        Write-Host "huginn: downloaded client failed its syntax check - keeping the current version" -ForegroundColor Red
        $got = $false
      }
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
  } elseif ($args[0] -eq 'rounds' -or $args[0] -eq 'round') {
    # Same host-side renderer the bash client calls; see huginn.sh.
    ssh -T $H huginn-rounds
  } elseif ($args[0] -eq 'devices' -or $args[0] -eq 'device') {
    ssh -T $H huginn-devices
  } elseif ($args[0] -eq 'desktop') {
    $arg = if ($args.Count -gt 1) { "$($args[1])".ToLower() } else { '' }
    $want = switch ($arg) {
      { $_ -in '', 'both', 'all' }              { ''; break }
      { $_ -in 'win', 'windows', 'exe' }        { 'windows-x64'; break }
      { $_ -in 'linux', 'deb', 'debian', 'ubuntu' } { 'linux-x64'; break }
      default { 'BAD' }
    }
    if ($want -eq 'BAD') { Write-Host "usage: huginn desktop [windows|linux]" -ForegroundColor Red; return }
    $rel = _Huginn-DesktopRelease
    if (-not $rel) {
      Write-Host "huginn: could not read the desktop release feed (offline, or GitHub rate-limited this IP)." -ForegroundColor Red
      Write-Host "  Browse it: https://github.com/$script:HUGINN_REPO/releases" -ForegroundColor DarkGray
      return
    }
    $base = "https://github.com/$script:HUGINN_REPO/releases/download/$($rel.tag)"
    $arts = $rel.manifest.artifacts
    # PS 5.1 has no $IsWindows (it is PowerShell Core's), and it only runs on Windows.
    $here = if ($null -eq $IsWindows) { 'windows-x64' }
            elseif ($IsWindows) { 'windows-x64' }
            elseif ($IsLinux)   { 'linux-x64' }
            else { '' }   # macOS: no desktop build
    # With a platform named, emit the BARE url down the pipeline and nothing else,
    # so it composes:  curl -fLO (huginn desktop linux)
    if ($want) {
      $a = $arts.$want
      if (-not $a) { Write-Host "huginn: $($rel.tag) has no $want build" -ForegroundColor Red; return }
      return "$base/$($a.file)"
    }
    Write-Host ""
    Write-Host "  Huginn Desktop $($rel.manifest.version)   ($($rel.tag))"
    Write-Host ""
    foreach ($p in @('windows-x64', 'linux-x64')) {
      $a = $arts.$p
      if (-not $a) { continue }
      $label = if ($p -eq 'windows-x64') { 'Windows' } else { 'Linux  ' }
      $mark  = if ($p -eq $here) { '   <- this machine' } else { '' }
      Write-Host "  $label  $base/$($a.file)$mark"
      Write-Host ("           {0,6:N1} MB   sha256 {1}..." -f ($a.size / 1MB), $a.sha256.Substring(0, 16)) -ForegroundColor DarkGray
    }
    Write-Host ""
    if ($here -eq 'windows-x64') {
      Write-Host "  install:  run the .exe (per-user NSIS installer, no admin needed)"
    } elseif ($here -eq 'linux-x64') {
      $deb = $arts.'linux-x64'.file
      Write-Host "  install:  curl -fLO $base/$deb && sudo dpkg -i $deb"
    } else {
      Write-Host "  (no desktop build for this machine - these links are for your laptop)"
    }
    Write-Host "  An installed client self-updates from this same feed." -ForegroundColor DarkGray
    Write-Host ""
  } elseif ($args[0] -eq 'usage' -or $args[0] -eq 'cost' -or $args[0] -eq 'ccusage') {
    # Full history is layered server-side by the /usr/local/bin/ccusage wrapper on huginn - keep this bare.
    $kw = if ($args.Count -gt 1) { $args[1] } else { $null }
    if ($kw -in @('today', 'yesterday', 'week', 'month')) {
      $idx = 2
      $report = 'daily'
      if ($args.Count -gt 2 -and ($args[2] -in @('daily', 'monthly', 'weekly', 'session', 'blocks', 'statusline'))) {
        $report = $args[2]; $idx = 3
      }
      $rest = if ($args.Count -gt $idx) { $args[$idx..($args.Count - 1)] -join ' ' } else { '' }
      # Date math runs server-side (guaranteed GNU date on the host) so this works
      # the same regardless of client OS.
      $dates = switch ($kw) {
        'today'     { 'since=$(date +%Y%m%d); until=$since' }
        'yesterday' { 'since=$(date -d yesterday +%Y%m%d); until=$since' }
        'week'      { 'since=$(date -d "7 days ago" +%Y%m%d); until=$(date +%Y%m%d)' }
        'month'     { 'since=$(date +%Y%m01); until=$(date +%Y%m%d)' }
      }
      ssh -tt $H "$dates; ccusage $report -s `$since -u `$until $rest"
    } else {
      $sub = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] -join ' ' } else { 'daily' }
      ssh -tt $H "ccusage $sub"   # default 'daily'. -tt for tables + --live.
    }
  } elseif ($args[0] -eq 'solo') {
    $name = if ($args.Count -gt 1) { $args[1] } else { 'main' }
    if (-not (_Huginn-ValidName $name)) { Write-Host "huginn: invalid session name '$name' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    _Huginn-Attach -H $H -Session $name -Solo
  } elseif ($args[0] -eq 'rename' -or $args[0] -eq 'mv') {
    if ($args.Count -lt 3) { Write-Host "usage: huginn rename <old> <new>"; return }
    if (-not (_Huginn-ValidName $args[2])) { Write-Host "huginn: invalid new name '$($args[2])' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    if (-not (_Huginn-ValidName $args[1])) { Write-Host "huginn: invalid session name '$($args[1])' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    $ro = _Huginn-CanonName $args[1]; $rn = _Huginn-CanonName $args[2]
    ssh -T $H "tmux rename-session -t '$(_Huginn-TmuxTarget $ro)' '$rn' && echo 'renamed: $ro -> $rn'"
  } elseif ($args[0] -eq 'kill') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn kill <name>"; return }
    if (-not (_Huginn-ValidName $args[1])) { Write-Host "huginn: invalid session name '$($args[1])' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    $kn = _Huginn-CanonName $args[1]
    # Prefer the daemon's DELETE: it also removes the orphaned /run state file and
    # releases the pane lease, which a bare tmux kill-session leaves behind (Claude's
    # SessionEnd hook never fires on a kill). Fall back to tmux when the daemon is
    # unreachable - kill must work even when appd is down.
    # '=' anchor on the fallback: without it 'huginn kill andvari' kills 'andvariautofill'.
    if ($null -ne (_Huginn-Appd -H $H -Method 'DELETE' -Path "/v1/sessions/$kn")) {
      Write-Host "killed: $kn"
    } else {
      ssh -T $H "tmux kill-session -t '$(_Huginn-TmuxTarget $kn)' && echo 'killed: $kn'"
    }
  } elseif ($args[0] -eq 'end') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn end <name>"; return }
    if (-not (_Huginn-ValidName $args[1])) { Write-Host "huginn: invalid session name '$($args[1])' (use letters, digits, underscore; no - or *)" -ForegroundColor Red; return }
    $en = _Huginn-CanonName $args[1]
    # Soft end: ask Claude to wrap up (finish, commit, prepare to end) and - when
    # auto-end is on for the host - end the session once it settles. This is a DAEMON
    # feature (it types into the pane and watches state), so there is no tmux fallback;
    # the phrase is whatever the host is configured to send.
    $r = _Huginn-Appd -H $H -Method 'POST' -Path "/v1/sessions/$en/soft-end"
    if ($null -eq $r) { Write-Host "huginn: soft-end failed for '$en' (is huginn-appd running? is the session a live Claude pane?)" -ForegroundColor Red; return }
    $phrase = 'wrap-up phrase'; $auto = ''
    try {
      $j = $r | ConvertFrom-Json
      if ($j.phrase) { $phrase = $j.phrase }
      if ($j.auto)   { $auto = ' (auto-ends when it goes idle)' }
    } catch {}
    Write-Host "soft-ended '$en': sent `"$phrase`"$auto"
  } elseif ($args[0] -eq '-p' -or $args[0] -eq '-y') {
    if ($args.Count -lt 2) { Write-Host "usage: huginn $($args[0]) ""your prompt"""; return }
    $q = ($args[1..($args.Count - 1)] -join ' '); $esc = $q -replace "'", "'\''"  # POSIX single-quote escape
    # Kept in step with huginn-appd's ask/act tool sets (server/appd TOOLS/
    # DISALLOWED): -p is read-only reasoning + web + memory, -y may also mutate.
    # The DISALLOWED deny-list is the real fence - --allowedTools only auto-approves,
    # so without it a -p query could still be granted Bash.
    # The flag is assembled HERE and interpolated into the remote script, so its
    # quoting is bash SYNTAX on the host. Assembling it in a remote variable and
    # expanding that unquoted word-splits it into `'Bash` `Edit` `Write`
    # `NotebookEdit'` - literal quotes, no valid tool name, nothing actually denied.
    $tools = if ($args[0] -eq '-y') { "Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace" } else { "Skill mcp__mempalace WebFetch WebSearch" }
    $dflag = if ($args[0] -eq '-y') { '' } else { "--disallowedTools 'Bash Edit Write NotebookEdit'" }
    # Persona-aware: if the host carries persona.md, inject it + memory tools; else plain headless query.
    #
    # The remote script is base64'd rather than passed as a quoted argument.
    # Windows PowerShell 5.1 mangles embedded double quotes when marshalling
    # arguments to a native executable, which silently corrupted P="$(cat ...)"
    # into an unquoted assignment: the persona word-split, `[ -n $P ]` errored
    # with "too many arguments", the else branch ran, and `huginn -y` degraded to
    # a bare `claude -p` with NO persona and NO --allowedTools. Base64 puts only
    # [A-Za-z0-9+/=] on the command line, so 5.1 and 7.x behave identically.
    $remoteScript = @"
cd "`${HUGINN_WORKDIR:-`$HOME}" 2>/dev/null || cd "`$HOME"
P="`$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)"
if [ -n "`$P" ]; then
  echo '$esc' | claude -p --append-system-prompt "`$P" --allowedTools '$tools' $dflag
else
  echo '$esc' | claude -p
fi
"@
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remoteScript -replace "`r`n", "`n")))
    ssh -T $H "echo $b64 | base64 -d | bash -s"
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
  $cmds = 'list', 'status', 'rounds', 'devices', 'solo', 'rename', 'kill', 'end', '-p', '-y', 'usage', 'cost', 'desktop', 'update', 'version', 'help'
  # tokens already typed after the command name, excluding the partial word being completed
  $typed = @($ast.CommandElements | Select-Object -Skip 1 | ForEach-Object { $_.ToString() })
  if ($word -and $typed.Count -ge 1) { $typed = @($typed | Select-Object -SkipLast 1) }
  $prev = if ($typed.Count -ge 1) { $typed[-1] } else { '' }
  if ($typed.Count -eq 0) {
    $candidates = $cmds + @(_Huginn-Sessions)          # first word: subcommands + sessions
  } elseif ($prev -in 'kill', 'end', 'solo', 'rename', 'mv') {
    $candidates = @(_Huginn-Sessions)                  # these take an existing session name
  } elseif ($prev -in 'usage', 'cost', 'ccusage') {
    $candidates = 'today', 'yesterday', 'week', 'month', 'daily', 'monthly', 'weekly', 'session', 'blocks', 'statusline'
  } elseif ($prev -in 'today', 'yesterday', 'week', 'month') {
    $candidates = 'daily', 'monthly', 'weekly', 'session', 'blocks', 'statusline'
  } elseif ($prev -eq 'desktop') {
    $candidates = 'windows', 'linux', 'both'
  } else {
    $candidates = @()
  }
  $candidates | Where-Object { $_ -like "$word*" } |
    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
}
