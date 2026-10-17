# huginn bootstrap (Windows PowerShell 5.1 / 7). One line:
#
#   irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1 | iex
#
# It asks for the huginn host. To answer up front, set $env:HUGINN_HOST first
# (and $env:HUGINN_GUI = 'y' or 'n' for the desktop question); the older form
#   & ([scriptblock]::Create((irm <the same url>))) -HuginnHost <host> [-Gui y|n]
# still works.
#
# 1. installs the huginn client (install.ps1, from the newest cli-v release -
#    never from a moving branch): key, `Host huginn`, the CLI, and the host's
#    trust in the key,
# 2. installs huginn core (huginn core install): the product itself, which
#    carries the CLI, runs at boot (one UAC prompt: the boot task needs an
#    administrator token) and pairs with the host,
# 3. asks: install the desktop app too?            -> the desktop installer
# 4. says what it set up and the next command.
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
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
  throw ("huginn: needs Windows' OpenSSH client, which is not installed. Add it from Settings > System > Optional features > " +
         "'OpenSSH Client', or in an administrator PowerShell: Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0 " +
         "- then run this again.")
}
$rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases?per_page=60" -UseBasicParsing
$tag = ($rel | Where-Object { $_.tag_name -match '^cli-v[0-9]+\.[0-9]+\.[0-9]+$' } |
          Sort-Object { [version]($_.tag_name -replace '^cli-v','') } | Select-Object -Last 1).tag_name
if (-not $tag) { throw "huginn: could not read the release list (offline, or GitHub rate-limited this IP)" }
$t = Join-Path ([IO.Path]::GetTempPath()) ("huginn-get-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $t | Out-Null
try {
  foreach ($f in 'install.ps1', 'huginn.ps1') {
    # ASCII on purpose: these files are 7-bit, and PS 5.1 reads a BOM-less file
    # as the ANSI code page (see docs/ARCHITECTURE.md).
    $body = Invoke-WebRequest "https://raw.githubusercontent.com/$repo/$tag/client/$f" -UseBasicParsing
    $text = if ($body.Content -is [byte[]]) { [Text.Encoding]::ASCII.GetString($body.Content) } else { $body.Content }
    Set-Content -Path (Join-Path $t $f) -Value $text -Encoding ascii
  }
  $null = [ScriptBlock]::Create((Get-Content -Raw (Join-Path $t 'huginn.ps1')))   # parse check before anything runs
  # From here on, the client's own installer-style lines (and `huginn`).
  . (Join-Path $t 'huginn.ps1')
  _Huginn-UiLine 'ok' 'release' "$tag, the newest release"
  $env:HUGINN_SETUP = '1'
  try {
    if ($HuginnHost) { & (Join-Path $t 'install.ps1') -HuginnHost $HuginnHost } else { & (Join-Path $t 'install.ps1') }
  } finally { Remove-Item Env:HUGINN_SETUP -ErrorAction SilentlyContinue }
} finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue }
# The installed client, in THIS scope: install.ps1 loaded it into its own,
# which ended with it - so `huginn core install` below would not resolve.
. "$HOME\.huginn\huginn.ps1"

# Does the host trust this key yet? Judged by ssh's EXIT CODE only.
# NOTE: under $ErrorActionPreference='Stop', Windows PowerShell 5.1 turns any native
# stderr line into a terminating error - and `Host huginn` asks for a TTY, so
# ssh says "Pseudo-terminal will not be allocated" even on success. -T, plus
# 'Continue' for this one call, keeps a probe from ending the install.
function Test-HuginnTrust {
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { & ssh -T -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>&1 | Out-Null; return ($LASTEXITCODE -eq 0) }
  finally { $ErrorActionPreference = $eap }
}
$trusted = Test-HuginnTrust

# huginn core IS the product (owner rule 2026-10-15): the CLI rides inside it,
# and the console and the desktop app need it running. Installed, not offered.
# Its lines join this block: download, binary, service, tray, and pairing -
# which waits for the host's trust when there is none yet.
$core = $false; $paired = $false
$env:HUGINN_SETUP = '1'
if (-not $trusted) { $env:HUGINN_CORE_NOPAIR = '1' }
# A refusal in the client throws (_Huginn-Fail), so getting past the call is
# success. A refusal AFTER the core is in place (pairing) has already said
# why in its own line.
try { huginn core install; $core = $true; $paired = $trusted }
catch {
  if (Test-Path "$env:LOCALAPPDATA\huginn\bin\huginn.exe") { $core = $true }
  else { _Huginn-UiLine 'fail' 'core' "did not finish: $($_.Exception.Message)"; _Huginn-UiNote 'run it again to see why: huginn core install' }
}
finally { Remove-Item Env:HUGINN_SETUP, Env:HUGINN_CORE_NOPAIR -ErrorAction SilentlyContinue }

# The questions (owner rule 2026-10-16: setup asks, it does not leave them to
# later commands). Each can be answered up front - $env:HUGINN_DEVICE,
# $env:HUGINN_GUI, $env:HUGINN_LOCAL = 'y' or 'n' - and with no console to ask
# on, an unanswered question is a no.
# NOTE: not `$gui`: PowerShell names are case-insensitive, and `$gui = $false`
# here once overwrote the [string]$Gui answer with "False", so the desktop
# question was never asked.
$canAsk = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
function Ask-Huginn([string]$q, [string]$preset, [bool]$yesDefault) {
  if ($preset) { return ($preset -match '^(y|yes)$') }
  if (-not $canAsk) { return $false }
  $hint = if ($yesDefault) { '[Y/n]' } else { '[y/N]' }
  $a = (Read-Host "    $q $hint").Trim()
  if (-not $a) { return $yesDefault }
  return ($a -match '^(y|yes)$')
}
$didDesktop = $false; $didDevice = $false; $didLocal = $false
if ($core) {
  Write-Host ''
  # 1. This machine as a device: huginn can run sessions here. Read-only
  # (scope look) until widened with huginn device on --scope work.
  if ($paired) {
    if (Ask-Huginn 'Offer this machine to huginn as a device (read-only sessions until you widen it)?' $env:HUGINN_DEVICE $true) {
      $out = @(huginn device on 2>&1 | ForEach-Object { "$_" })
      if ($LASTEXITCODE -eq 0) { _Huginn-UiLine 'ok' 'device' 'on: huginn can run read-only sessions here (huginn device on --scope work widens it)'; $didDevice = $true }
      else { _Huginn-UiLine 'fail' 'device' 'could not turn it on:'; $out | ForEach-Object { _Huginn-UiNote $_ } }
    } else { _Huginn-UiLine 'skip' 'device' 'not offered (huginn device on, any time)' }
  }
  # 2. The desktop app: a window and a tray on top of huginn core.
  if (Ask-Huginn 'Install the desktop app (a window and a tray on top of huginn core)?' $Gui $true) {
    $env:HUGINN_SETUP = '1'
    try {
      huginn desktop install; $didDesktop = $true
      # Until the desktop uses the core's own link to huginn (it is next), it
      # asks for the address and token once, on its first screen.
      _Huginn-UiNote "on its first screen it asks once for huginn's address and token"
    }
    catch { _Huginn-UiLine 'fail' 'desktop' "did not finish: $($_.Exception.Message)" }
    finally { Remove-Item Env:HUGINN_SETUP -ErrorAction SilentlyContinue }
  } else { _Huginn-UiLine 'skip' 'desktop' 'not installed (huginn desktop install, any time)' }
  # 3. The local AI tier, only where it would run on a GPU: models served
  # from this machine. Windows runs it as services, so it needs one
  # administrator prompt; its own window shows the download.
  $plan = $null
  if ($paired -and (Get-Command node -ErrorAction SilentlyContinue)) {
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
        if ($p.ExitCode -eq 0) { _Huginn-UiLine 'ok' 'local AI' "serving from the $($plan.gpu.name) (huginn local status)"; $didLocal = $true }
        else { _Huginn-UiLine 'fail' 'local AI' "did not finish (exit $($p.ExitCode)) - from an administrator terminal: huginn local on" }
      } catch { _Huginn-UiLine 'skip' 'local AI' 'administrator approval was declined (huginn local on, from an administrator terminal, any time)' }
    } else { _Huginn-UiLine 'skip' 'local AI' 'not set up (huginn local on, any time)' }
  }
}

Write-Host ''
if (-not $trusted) {
  Write-Host '    huginn is installed, but the host does not trust this computer yet.'
  Write-Host '    Authorize the key shown above on the host, then run:  huginn core install'
  Write-Host '    (it pairs huginn core with the host), and to offer this machine:  huginn device on'
} elseif ($core -and -not $paired) {
  Write-Host '    huginn is installed, but huginn core is not paired with the host yet (above says why).'
  Write-Host '    Once that is fixed, run:  huginn core install   (everything else is already in place)'
} elseif (-not $core) {
  Write-Host '    The CLI is installed, but huginn core is not (above says why): huginn core install'
} else {
  Write-Host '    huginn is set up. Run:  huginn'
  Write-Host '    That opens the console; huginn help lists every command.'
}
_Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray
} $HuginnHost $Gui

# `huginn` in THIS window too, not only in new ones: under `irm | iex` this
# line runs in your session's own scope, as your profile's line would. (Run
# the other way, as a script block, it is that block's scope and goes with it.)
if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
