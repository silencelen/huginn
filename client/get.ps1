# huginn bootstrap (Windows PowerShell 5.1 / 7):
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1))) -HuginnHost <host>
#
# 1. installs the huginn client (install.ps1, from the newest cli-v release -
#    never from a moving branch),
# 2. installs huginn core (huginn core install): the product itself, which
#    carries the CLI and runs at boot (one UAC prompt: the boot task needs an
#    administrator token),
# 3. asks: install the desktop app too?            -> the desktop installer
# 4. says what it installed, what runs at boot, and the next command.
# The question can be answered up front: -Gui y|n. (-Background is accepted
# and ignored: the core is always installed since 2.3.0.) ASCII only.
param([string]$HuginnHost, [string]$Background, [string]$Gui)
$ErrorActionPreference = 'Stop'
$repo = 'silencelen/huginn'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
function Ask([string]$q, [string]$preset) {
  $a = if ($preset) { $preset } else { Read-Host "$q [y/N]" }
  return ($a -match '^(y|yes)$')
}
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
  Write-Host "Installing the huginn client from $tag"
  if ($HuginnHost) { & (Join-Path $t 'install.ps1') -HuginnHost $HuginnHost } else { & (Join-Path $t 'install.ps1') }
} finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue }

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
if (-not $trusted -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
  Read-Host 'Authorize the key above on the host, then press Enter' | Out-Null
  $trusted = Test-HuginnTrust
}

# huginn core IS the product (owner rule 2026-10-15): the CLI rides inside it,
# and the console and the desktop app need it running. Installed, not offered.
Write-Host ''
Write-Host 'Installing huginn core (it runs at boot, as you, and carries the CLI). Windows asks for administrator approval once.'
$core = $false
try { huginn core install; $core = ($LASTEXITCODE -eq 0) } catch { Write-Host "  huginn core install did not finish: $($_.Exception.Message)" }
if (-not $core) { Write-Host '  run it again to see why: huginn core install' }
$gui = $false
if ($core -and (Ask 'Install the desktop app too (a window and tray on top of huginn core)?' $Gui)) {
  $url = huginn desktop windows
  if ($url) {
    $exe = Join-Path ([IO.Path]::GetTempPath()) (Split-Path $url -Leaf)
    Invoke-WebRequest $url -OutFile $exe -UseBasicParsing
    Start-Process $exe -Wait
    $gui = $true
  } else { Write-Host '  could not find the desktop installer - later: huginn desktop' }
}
# What happened, in one place (the install's answer to uninstall's Removed / Left behind).
Write-Host ''
Write-Host 'huginn is installed:'
Write-Host '  the CLI         ~\.huginn (loaded by your PowerShell profile; new windows have huginn)'
if ($core) { Write-Host '  huginn core     %LOCALAPPDATA%\huginn\bin\huginn.exe, started at boot by the task "Huginn Core"' }
if ($gui)  { Write-Host '  the desktop app Huginn Desktop (Start menu)' }
if ($trusted) { Write-Host "  SSH             'Host huginn' in ~\.ssh\config (the host trusts this key)" }
else {
  Write-Host "  SSH             'Host huginn' in ~\.ssh\config - NOT trusted yet"
  Write-Host ''
  Write-Host 'To finish: authorize the key shown above on the host, then run: huginn core install'
  Write-Host '(it pairs huginn core with the host; nothing else needs doing again)'
}
Write-Host ''
Write-Host 'Open a new terminal and run: huginn    (the console; huginn help lists every command)'

