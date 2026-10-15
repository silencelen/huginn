# huginn bootstrap (Windows PowerShell 5.1 / 7):
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1))) -HuginnHost <host>
#
# 1. installs the huginn client (install.ps1, from the newest cli-v release -
#    never from a moving branch),
# 2. asks: keep huginn running in the background?  -> huginn core install
#    (one UAC prompt: the boot task needs an administrator token),
# 3. asks: install the desktop GUI too?             -> the desktop installer
# Answers can be given up front: -Background y|n -Gui y|n. ASCII only.
param([string]$HuginnHost, [string]$Background, [string]$Gui)
$ErrorActionPreference = 'Stop'
$repo = 'silencelen/huginn'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
function Ask([string]$q, [string]$preset) {
  $a = if ($preset) { $preset } else { Read-Host "$q [y/N]" }
  return ($a -match '^(y|yes)$')
}
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "huginn: needs the OpenSSH client (Settings > Optional features > OpenSSH Client)" }
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

if (Ask 'Keep huginn running in the background (act mode and local AI with this PC signed out)?' $Background) {
  if (-not $trusted) { Write-Host '  skipped: the host does not trust this key yet. Later: huginn core install' }
  else { huginn core install }
}
if (Ask 'Install the desktop GUI too?' $Gui) {
  $url = huginn desktop windows
  if ($url) {
    $exe = Join-Path ([IO.Path]::GetTempPath()) (Split-Path $url -Leaf)
    Invoke-WebRequest $url -OutFile $exe -UseBasicParsing
    Start-Process $exe -Wait
  } else { Write-Host '  could not find the desktop installer - later: huginn desktop' }
}
Write-Host 'Done. Open a new terminal, then: huginn help'
