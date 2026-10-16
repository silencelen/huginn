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

$gui = $false
if ($core) {
  Write-Host ''
  $a = if ($Gui) { $Gui } else { Read-Host '    Install the desktop app too (a window and a tray on top of huginn core)? [y/N]' }
  if ($a -match '^(y|yes)$') {
    $url = huginn desktop windows
    if ($url) {
      $exe = Join-Path ([IO.Path]::GetTempPath()) (Split-Path $url -Leaf)
      _Huginn-UiDownload $url $exe 0 'desktop'
      $p = Start-Process $exe -PassThru
      $null = $p.Handle
      _Huginn-UiWait $p 'desktop' 'the installer is open: finish it there'
      if ($p.ExitCode -eq 0) { _Huginn-UiLine 'ok' 'desktop' 'Huginn Desktop installed (Start menu)'; $gui = $true }
      else { _Huginn-UiLine 'warn' 'desktop' "the installer ended with exit $($p.ExitCode) - run it again any time: huginn desktop" }
      Remove-Item $exe -Force -ErrorAction SilentlyContinue
    } else { _Huginn-UiLine 'fail' 'desktop' 'could not find the desktop installer - later: huginn desktop' }
  } else {
    _Huginn-UiLine 'skip' 'desktop' 'not installed (huginn desktop shows the installer any time)'
  }
}

Write-Host ''
if (-not $trusted) {
  Write-Host '    huginn is installed, but the host does not trust this computer yet.'
  Write-Host '    Authorize the key shown above on the host, then run:  huginn core install'
  Write-Host '    (it pairs huginn core with the host; nothing else needs doing again)'
} elseif ($core -and -not $paired) {
  Write-Host '    huginn is installed, but huginn core is not paired with the host yet (above says why).'
  Write-Host '    Once that is fixed, run:  huginn core install   (everything else is already in place)'
} elseif (-not $core) {
  Write-Host '    The CLI is installed, but huginn core is not (above says why): huginn core install'
} else {
  Write-Host '    huginn is set up. Open a new terminal and run:  huginn'
  Write-Host '    That opens the console; huginn help lists every command.'
}
_Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray
} $HuginnHost $Gui
