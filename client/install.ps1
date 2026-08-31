# Huginn client installer (Windows PowerShell).
# Run from the cloned repo:   .\client\install.ps1 -HuginnHost my-host-or-ip
param(
  [string]$HuginnHost,
  [string]$User = "root",
  [string]$Key  = "$HOME\.ssh\id_ed25519"
)
$ErrorActionPreference = "Stop"
if (-not $HuginnHost) { $HuginnHost = Read-Host "Huginn host (IP or DNS name reachable over SSH)" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
New-Item -ItemType Directory -Force "$HOME\.ssh" | Out-Null

# 1. SSH key. An empty passphrase is marshalled DIFFERENTLY across PowerShell
# editions: Windows PowerShell 5.1 collapses '""' into an empty argument, but
# PowerShell 7+ (PSNativeCommandArgumentPassing) forwards '""' LITERALLY as a
# two-character passphrase — which silently produced a passphrase-protected key
# and broke passwordless SSH on every PS7 install. Pick the form each edition
# needs. (Verify once on a real box, both editions: after generating,
# `ssh-keygen -y -P '' -f $Key` must succeed, proving the key has no passphrase.)
$noPass = if ($PSVersionTable.PSVersion.Major -ge 6) { '' } else { '""' }
if (-not (Test-Path $Key)) { ssh-keygen -t ed25519 -f $Key -N $noPass | Out-Null }
Write-Host "`n>>> Authorize THIS key on the Huginn host (append to its ~/.ssh/authorized_keys):" -ForegroundColor Yellow
Write-Host "    $(Get-Content "$Key.pub")`n"

# 2. `Host huginn` SSH alias (idempotent)
$cfg = "$HOME\.ssh\config"
if (-not (Test-Path $cfg)) { New-Item -ItemType File -Force $cfg | Out-Null }
if (-not (Select-String -Path $cfg -Pattern '^\s*Host\s+huginn\s*$' -Quiet)) {
  Add-Content $cfg "`nHost huginn`n  HostName $HuginnHost`n  User $User`n  IdentityFile $Key`n  IdentitiesOnly yes`n  RequestTTY yes`n  ServerAliveInterval 30"
  Write-Host "Added 'Host huginn' -> $HuginnHost to $cfg"
}

# 3. install the command + wire the profile
New-Item -ItemType Directory -Force "$HOME\.huginn" | Out-Null
Copy-Item "$here\huginn.ps1" "$HOME\.huginn\huginn.ps1" -Force
if (-not (Test-Path $PROFILE)) { New-Item -ItemType File -Force $PROFILE | Out-Null }
if (-not (Select-String -Path $PROFILE -Pattern '\.huginn\\huginn\.ps1' -Quiet)) {
  Add-Content $PROFILE 'if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }'
}
. "$HOME\.huginn\huginn.ps1"
Write-Host "`nInstalled. Authorize the key above on the host, then:  huginn help  |  huginn status" -ForegroundColor Green
Write-Host "This machine may also be able to serve local AI models to huginn (optional, ~5 GB):  huginn local on" -ForegroundColor DarkGray
# The base client needs no node; only the optional features do. Named here
# because the native claude build ships without node, so its absence is normal.
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  Write-Host "Note: the optional device/local-AI features need Node.js LTS:  winget install OpenJS.NodeJS.LTS" -ForegroundColor DarkGray
}
