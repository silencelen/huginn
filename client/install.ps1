# Huginn client installer (Windows PowerShell).
# Run from the cloned repo:   .\client\install.ps1 -HuginnHost my-host-or-ip
# (get.ps1, the one-line installer, runs this same file with $env:HUGINN_SETUP
# set: it draws the block's title and its end itself.)
param(
  [string]$HuginnHost,
  [string]$User = "root",
  [string]$Key  = "$HOME\.ssh\id_ed25519"
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
# The client's own installer-style helpers (_Huginn-UiLine and friends), and
# its one colour guard: dot-sourcing huginn.ps1 only defines functions.
. "$here\huginn.ps1"
$t0 = [Diagnostics.Stopwatch]::StartNew()
function Tilde([string]$p) { if ($p.StartsWith($HOME, [StringComparison]::OrdinalIgnoreCase)) { '~' + $p.Substring($HOME.Length) } else { $p } }
if (-not $env:HUGINN_SETUP) { _Huginn-UiHead 'huginn client install' }
if (-not $HuginnHost) { $HuginnHost = Read-Host '    Huginn host (its IP address or name)' }
if (-not $HuginnHost) { _Huginn-UiLine 'fail' 'host' 'no host given - run it again with the host: -HuginnHost <host>'; throw 'huginn: no host given' }
# People type huginn's address as they know it, https:// and :8787 included
# (run 7: "192.168.1.20:8787" became an ssh host name that resolved nowhere).
# ssh gets the bare host; pairing later uses the address.
$hg = $HuginnHost.Trim()
if ($hg -match '^(?:https?://)?(\[[^\]]+\]|[^/:]+)(?::(\d+))?(?:/.*)?$') {
  $bare = $Matches[1]; $port = $Matches[2]
  if ($hg -match '^https?://' -or ($port -and $port -ne '22')) {
    $scheme = if ($hg -match '^http://') { 'http' } else { 'https' }
    $env:HUGINN_APPD_URL = "${scheme}://${bare}:" + $(if ($port -and $port -ne '22') { $port } else { '8787' })
  }
  $HuginnHost = $bare.Trim('[', ']')   # ssh takes an IPv6 address bare
  # Without a port or scheme, huginn's own port on the same host.
  if (-not $env:HUGINN_APPD_URL) { $env:HUGINN_APPD_URL = "https://${bare}:8787" }
}
New-Item -ItemType Directory -Force "$HOME\.ssh" | Out-Null

# 1. SSH key: yours when there is one (it is very likely the one your other
# hosts already trust), a new one otherwise - with NO passphrase. An empty
# passphrase cannot be passed as a native argument on PowerShell 7.6: `-N ''`
# is dropped entirely (ssh-keygen: "option requires an argument -- N") and
# `-N '""'` is forwarded as a LITERAL two-character passphrase - both verified
# on a real box, and the second is what silently broke passwordless SSH. So do
# not pass -N at all: answer ssh-keygen's two interactive passphrase prompts
# with empty lines over stdin. Pure PowerShell, edition- and
# argument-mode-independent, and -f keeps a spaced path intact.
# (Verify a generated key has no passphrase with: ssh-keygen -y -f $Key - it
# prints the public key with no prompt when the key is unprotected.)
if (Test-Path $Key) { _Huginn-UiLine 'ok' 'key' "$(Tilde $Key) (yours, reused)" }
else {
  "`n`n" | ssh-keygen -q -t ed25519 -f $Key -C "huginn@$env:COMPUTERNAME" | Out-Null
  _Huginn-UiLine 'ok' 'key' "$(Tilde $Key) (made now)"
}

# 2. `Host huginn` SSH alias (idempotent)
$cfg = "$HOME\.ssh\config"
if (-not (Test-Path $cfg)) { New-Item -ItemType File -Force $cfg | Out-Null }
if (-not (Select-String -Path $cfg -Pattern '^\s*Host\s+huginn\s*$' -Quiet)) {
  Add-Content $cfg "`nHost huginn`n  HostName $HuginnHost`n  User $User`n  IdentityFile $Key`n  IdentitiesOnly yes`n  RequestTTY yes`n  ServerAliveInterval 30"
  _Huginn-UiLine 'ok' 'ssh' "'Host huginn' -> $HuginnHost, as $User ($(Tilde $cfg))"
} else {
  _Huginn-UiLine 'ok' 'ssh' "'Host huginn' is already in $(Tilde $cfg) - kept as it is"
}

# 3. install the command + wire the profile
New-Item -ItemType Directory -Force "$HOME\.huginn" | Out-Null
Copy-Item "$here\huginn.ps1" "$HOME\.huginn\huginn.ps1" -Force
if (-not (Test-Path $PROFILE)) { New-Item -ItemType File -Force $PROFILE | Out-Null }
if (-not (Select-String -Path $PROFILE -Pattern '\.huginn\\huginn\.ps1' -Quiet)) {
  Add-Content $PROFILE 'if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }'
}
_Huginn-UiLine 'ok' 'CLI' "$script:HUGINN_VERSION at ~\.huginn\huginn.ps1, loaded by your PowerShell profile"

# 4. Does the host trust this key? Judged by ssh's exit code, with the reason
# read from what it says; BatchMode, so this probe never prompts.
# NOTE: under $ErrorActionPreference='Stop', Windows PowerShell 5.1 turns any
# native stderr line into a terminating error - 'Continue' for these calls.
function Test-HuginnSsh {
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $err = (& ssh -T -o BatchMode=yes -o ConnectTimeout=8 huginn true 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0) { return 'ok' }
  } finally { $ErrorActionPreference = $eap }
  if ($err -match 'IDENTIFICATION HAS CHANGED') { return 'changed' }
  if ($err -match 'Permission denied') { return 'denied' }
  if ($err -match 'Host key verification failed|No .* host key is known') { return 'hostkey' }
  $last = @($err -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^Warning: Permanently added' }) | Select-Object -Last 1
  return "unreachable: $last"
}
$interactive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
$why = Test-HuginnSsh
if ($why -eq 'hostkey' -and $interactive) {
  # A host this computer has never seen: ssh's own question, with the host's
  # fingerprint, answered by the person - an installer does not accept a host
  # key on anyone's behalf. Public-key only, so nothing asks for a password.
  _Huginn-UiLine 'warn' 'trust' 'the huginn host is new to this computer: ssh shows its fingerprint - answer yes if it is your host'
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { & ssh -T -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o ConnectTimeout=15 huginn true 2>$null } finally { $ErrorActionPreference = $eap }
  $why = Test-HuginnSsh
}
function Show-HuginnTrust([string]$w) {
  switch -Wildcard ($w) {
    'ok'      { _Huginn-UiLine 'ok' 'trust' 'the huginn host accepts this key' }
    'denied'  { _Huginn-UiLine 'warn' 'trust' 'the huginn host does not know this key yet' }
    'hostkey' { _Huginn-UiLine 'warn' 'trust' "the huginn host's own key was not accepted, so nothing was sent" }
    'changed' {
      _Huginn-UiLine 'fail' 'trust' "the huginn host's key has CHANGED since this computer last saw it - nothing was sent"
      _Huginn-UiNote "Check with whoever runs $HuginnHost. If it was reinstalled: ssh-keygen -R $HuginnHost, then run this again."
    }
    default   { _Huginn-UiLine 'warn' 'trust' ("could not reach $HuginnHost over ssh (" + ($w -replace '^unreachable: ', '') + ')') }
  }
}
Show-HuginnTrust $why
if ($why -eq 'denied' -or $why -eq 'hostkey') {
  Write-Host ''
  Write-Host "    Authorize this computer on the huginn host. On $HuginnHost, as $User, run:"
  Write-Host ''
  Write-Host "      echo '$((Get-Content "$Key.pub" -Raw).Trim())' >> ~/.ssh/authorized_keys"
  Write-Host ''
}
# Waiting for a person only when there is something for them to do on the
# host. ssh not answering at all (no port 22 from here) has nothing to wait
# for: it said "Press Enter when that is done" to nobody (run 7). Pairing goes
# over huginn's own port, and asks for the token instead.
if ($why -match '^unreachable') {
  _Huginn-UiNote 'Attaching to sessions from a terminal needs ssh to the host; everything else uses huginn''s own port.'
} elseif ($why -ne 'ok' -and $why -ne 'changed' -and $interactive) {
  Read-Host '    Press Enter once the key is added (Ctrl-C to finish later)' | Out-Null
  $why = Test-HuginnSsh
  Show-HuginnTrust $why
}

if (-not $env:HUGINN_SETUP) {
  Write-Host ''
  Write-Host '    Next: huginn core install   (runs huginn core at boot and pairs it with the host)'
  _Huginn-Say '    This machine may also serve local AI models to huginn (optional, ~5 GB): huginn local on' DarkGray
  # The base client needs no node; only the optional features do. Named here
  # because the native claude build ships without node, so its absence is normal.
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    _Huginn-Say '    The optional device and local-AI features need Node.js LTS: winget install OpenJS.NodeJS.LTS' DarkGray
  }
  _Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray
}
