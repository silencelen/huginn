# huginn bootstrap (Windows PowerShell 5.1 / 7). One line:
#
#   irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1 | iex
#
# It asks for the huginn host. To answer up front, set $env:HUGINN_HOST first
# (and $env:HUGINN_GUI = 'y' or 'n' for the desktop question); the older form
#   & ([scriptblock]::Create((irm <the same url>))) -HuginnHost <host> [-Gui y|n]
# still works.
#
# FIRST it asks whether to install the desktop app (owner rule 2026-10-16):
#  - yes: installs huginn core (it carries the CLI and runs at boot; one UAC
#    prompt for the boot task) and the desktop app, and opens it. The app's
#    setup window walks through the rest - the address, the token, this
#    machine as a device - and configures huginn core with it.
#  - no: onboards here in the terminal: the client (install.ps1, from the
#    newest cli-v release - never from a moving branch: key, `Host huginn`, the
#    host's trust in the key), huginn core, paired with the host, then the
#    device and local-AI questions.
# (-Background is accepted and ignored: the core is always installed since
# 2.3.0.) ASCII only.
param([string]$HuginnHost, [string]$Background, [string]$Gui)

# Everything runs in this script block's own scope: `irm | iex` runs a script
# in the CALLER'S scope, and the person's session must not keep this
# installer's variables, functions or $ErrorActionPreference = 'Stop'.
& {
param([string]$HuginnHost, [string]$Gui)
$ErrorActionPreference = 'Stop'
if (-not $HuginnHost) { $HuginnHost = $env:HUGINN_HOST }
if (-not $Gui) { $Gui = $env:HUGINN_GUI }
$repo = 'silencelen/huginn'
$t0 = [Diagnostics.Stopwatch]::StartNew()
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
# Before the client is here to draw its own lines: the title and plain text.
Write-Host '(o> huginn setup'
$rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases?per_page=60" -UseBasicParsing
$tag = ($rel | Where-Object { $_.tag_name -match '^cli-v[0-9]+\.[0-9]+\.[0-9]+$' } |
          Sort-Object { [version]($_.tag_name -replace '^cli-v','') } | Select-Object -Last 1).tag_name
if (-not $tag) { throw "huginn: could not read the release list (offline, or GitHub rate-limited this IP)" }
$t = Join-Path ([IO.Path]::GetTempPath()) ("huginn-get-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $t | Out-Null
foreach ($f in 'install.ps1', 'huginn.ps1') {
  # ASCII on purpose: these files are 7-bit, and PS 5.1 reads a BOM-less file
  # as the ANSI code page (see docs/dev/architecture.md).
  $body = Invoke-WebRequest "https://raw.githubusercontent.com/$repo/$tag/client/$f" -UseBasicParsing
  $text = if ($body.Content -is [byte[]]) { [Text.Encoding]::ASCII.GetString($body.Content) } else { $body.Content }
  Set-Content -Path (Join-Path $t $f) -Value $text -Encoding ascii
}
$null = [ScriptBlock]::Create((Get-Content -Raw (Join-Path $t 'huginn.ps1')))   # parse check before anything runs
# From here on, the client's own installer-style lines (and `huginn`).
. (Join-Path $t 'huginn.ps1')
_Huginn-UiLine 'ok' 'release' "$tag, the newest release"

# Each question can be answered up front - $env:HUGINN_GUI, $env:HUGINN_DEVICE,
# $env:HUGINN_LOCAL = 'y' or 'n' - and with no console to ask on, an
# unanswered question is a no.
# NOTE: no variable here may be named like a parameter: PowerShell names are
# case-insensitive, and `$gui = $false` once overwrote the [string]$Gui
# answer with "False", so the desktop question was never asked.
$canAsk = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
function Ask-Huginn([string]$q, [string]$preset, [bool]$yesDefault) {
  if ($preset) { return ($preset -match '^(y|yes)$') }
  if (-not $canAsk) { return $false }
  $hint = if ($yesDefault) { '[Y/n]' } else { '[y/N]' }
  $a = (Read-Host "    $q $hint").Trim()
  if (-not $a) { return $yesDefault }
  return ($a -match '^(y|yes)$')
}

# THE FIRST QUESTION, before any configuration (owner rule 2026-10-16): with the
# desktop app, its window walks the person through the rest and configures
# huginn core; without it, this terminal does.
$withDesktop = Ask-Huginn 'Install the desktop app? Its window then walks you through the rest' $Gui $true
Write-Host ''
$core = $false; $paired = $false; $trusted = $false
try {
  if ($withDesktop) {
    # The core (which carries the CLI) and the app; the app's setup connects
    # the core to huginn - the address, then the token - so nothing is paired here.
    $env:HUGINN_SETUP = '1'; $env:HUGINN_CORE_NOPAIR = 'window'
    try { huginn core install; $core = $true }
    catch {
      if (Test-Path "$env:LOCALAPPDATA\huginn\bin\huginn.exe") { $core = $true }
      else { _Huginn-UiLine 'fail' 'core' "did not finish: $($_.Exception.Message)"; _Huginn-UiNote 'run it again to see why: huginn core install' }
    }
    finally { Remove-Item Env:HUGINN_CORE_NOPAIR -ErrorAction SilentlyContinue }
    if ($core) {
      . "$HOME\.huginn\huginn.ps1"
      try { huginn desktop install }
      catch { _Huginn-UiLine 'fail' 'desktop' "did not finish: $($_.Exception.Message)" }
    }
    Remove-Item Env:HUGINN_SETUP -ErrorAction SilentlyContinue
  } else {
    if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
      throw ("huginn: needs Windows' OpenSSH client, which is not installed. Add it from Settings > System > Optional features > " +
             "'OpenSSH Client', or in an administrator PowerShell: Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0 " +
             "- then run this again.")
    }
    $env:HUGINN_SETUP = '1'
    try {
      if ($HuginnHost) { & (Join-Path $t 'install.ps1') -HuginnHost $HuginnHost } else { & (Join-Path $t 'install.ps1') }
    } finally { Remove-Item Env:HUGINN_SETUP -ErrorAction SilentlyContinue }
    # The installed client, in THIS scope: install.ps1 loaded it into its own,
    # which ended with it - so `huginn core install` below would not resolve.
    . "$HOME\.huginn\huginn.ps1"
    # Does the host trust this key yet? Judged by ssh's EXIT CODE only.
    # NOTE: under $ErrorActionPreference='Stop', Windows PowerShell 5.1 turns any
    # native stderr line into a terminating error - and `Host huginn` asks for a
    # TTY, so ssh says "Pseudo-terminal will not be allocated" even on success.
    # -T, plus 'Continue' for this one call, keeps a probe from ending the install.
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & ssh -T -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>&1 | Out-Null; $trusted = ($LASTEXITCODE -eq 0) }
    finally { $ErrorActionPreference = $eap }
    # huginn core IS the product (owner rule 2026-10-15): the CLI rides inside
    # it. Its lines join this block, and pairing waits for the host's trust.
    $env:HUGINN_SETUP = '1'
    # Pairing does not wait for ssh: without it, the core install asks for the
    # token instead (run 7: the host refused port 22, and nothing paired).
    # A refusal in the client throws (_Huginn-Fail), so getting past the call is
    # success. A refusal AFTER the core is in place (pairing) has already said
    # why in its own line.
    try {
      huginn core install; $core = $true
      $cb = "$env:LOCALAPPDATA\huginn\bin\huginn.exe"
      $paired = (Test-Path $cb) -and (((& $cb core status 2>$null) -join "`n") -notmatch 'not paired')
      # Paired, but ssh did not answer at the address typed: huginn lists its
      # other addresses to the core, and setup ssh points 'Host huginn' at one
      # that carries ssh from here (PRESTIGE, 2026-10-17).
      if ($paired -and -not $trusted) {
        & $cb setup ssh
        $trusted = ($LASTEXITCODE -eq 0)
      }
    }
    catch {
      if (Test-Path "$env:LOCALAPPDATA\huginn\bin\huginn.exe") { $core = $true }
      else { _Huginn-UiLine 'fail' 'core' "did not finish: $($_.Exception.Message)"; _Huginn-UiNote 'run it again to see why: huginn core install' }
    }
    finally { Remove-Item Env:HUGINN_SETUP, Env:HUGINN_CORE_NOPAIR -ErrorAction SilentlyContinue }
    if ($core -and $paired) {
      Write-Host ''
      # This machine as a device: huginn can run sessions here. Read-only
      # (scope look) until widened with huginn device on --scope work.
      if (Ask-Huginn 'Offer this machine to huginn as a device (read-only sessions until you widen it)?' $env:HUGINN_DEVICE $true) {
        $out = @(huginn device on 2>&1 | ForEach-Object { "$_" })
        if ($LASTEXITCODE -eq 0) { _Huginn-UiLine 'ok' 'device' 'on: huginn can run read-only sessions here (huginn device on --scope work widens it)' }
        else { _Huginn-UiLine 'fail' 'device' 'could not turn it on:'; $out | ForEach-Object { _Huginn-UiNote $_ } }
      } else { _Huginn-UiLine 'skip' 'device' 'not offered (huginn device on, any time)' }
      # The local AI tier, only where it would run on a GPU. Windows runs it as
      # services, so it needs one administrator prompt; its own window shows
      # the download.
      $plan = $null
      if (Get-Command node -ErrorAction SilentlyContinue) {
        try { $plan = ((huginn local plan --json 2>$null) | Out-String) | ConvertFrom-Json } catch { $plan = $null }
      }
      if ($plan -and $plan.gpu -and -not $plan.setup) {
        $gb = '{0:N1} GB' -f ([double]$plan.needBytes / 1GB)
        if (Ask-Huginn "Serve local AI models from this machine's $($plan.gpu.name) ($gb download; Windows asks for administrator approval)?" $env:HUGINN_LOCAL $false) {
          $cmd = ". `"$HOME\.huginn\huginn.ps1`"; huginn local on --yes; if (`$LASTEXITCODE) { Read-Host 'It did not finish (above says why). Press Enter to close' }; exit `$LASTEXITCODE"
          $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
          $psExe = (Get-Process -Id $PID).Path
          try {
            $p = Start-Process -FilePath $psExe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $enc -Verb RunAs -PassThru
            $null = $p.Handle
            _Huginn-UiWait $p 'local AI' 'setting up in its own window (models download there)'
            if ($p.ExitCode -eq 0) { _Huginn-UiLine 'ok' 'local AI' "serving from the $($plan.gpu.name) (huginn local status)" }
            else { _Huginn-UiLine 'fail' 'local AI' "did not finish (exit $($p.ExitCode)) - from an administrator terminal: huginn local on" }
          } catch { _Huginn-UiLine 'skip' 'local AI' 'administrator approval was declined (huginn local on, from an administrator terminal, any time)' }
        } else { _Huginn-UiLine 'skip' 'local AI' 'not set up (huginn local on, any time)' }
      }
    }
  }
} finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue }

Write-Host ''
if ($withDesktop -and $core) {
  Write-Host '    Huginn is open: its setup connects huginn core to huginn (the address, then the token),'
  Write-Host '    and offers this machine as a device. Close this window whenever you like.'
  _Huginn-Say '    Attaching to sessions from a terminal uses SSH; set that up any time with: huginn setup ssh' DarkGray
} elseif ($core -and -not $paired -and -not $withDesktop) {
  $pairUrl = if ($env:HUGINN_APPD_URL) { $env:HUGINN_APPD_URL } else { 'https://<host>:8787' }
  Write-Host '    huginn is installed, but huginn core is not paired with huginn yet (above says why).'
  Write-Host "    Pair it any time:  huginn core pair --url $pairUrl   (asks for the token)"
  Write-Host '    then offer this machine:  huginn device on'
} elseif (-not $core) {
  Write-Host '    huginn core did not install (above says why): huginn core install'
} else {
  Write-Host '    huginn is set up. Run:  huginn'
  Write-Host '    That opens the console; huginn help lists every command.'
  if (-not $withDesktop -and -not $trusted) {
    _Huginn-Say '    Attaching to sessions from a terminal needs ssh to the host; set that up any time with: huginn setup ssh' DarkGray
  }
}
_Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray
} $HuginnHost $Gui

# `huginn` in THIS window too, not only in new ones: under `irm | iex` this
# line runs in your session's own scope, as your profile's line would. (Run
# the other way, as a script block, it is that block's scope and goes with it.)
if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
