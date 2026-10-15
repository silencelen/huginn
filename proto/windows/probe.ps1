# huginn core prototypes on Windows: Q1 (AF_UNIX, same session AND across
# sessions), Q2 (what an S4U boot-style task gets), Q9 (pure-Go tray).
# See docs/CORE-ARCHITECTURE.md section 11. Not product code. ASCII only.
#
# Run from the unzipped kit, in an ELEVATED PowerShell (task registration needs
# admin), while signed in to the desktop:
#     Set-ExecutionPolicy -Scope Process Bypass; .\probe.ps1
# Optional: -WithPassword also tests a stored-password task (asks for it once).
# -NoTray skips the tray check (e.g. when run over ssh with no desktop).
# Leaves nothing behind: the test task is unregistered and the run dir removed.
# Results: results.txt beside this script.
param([switch]$WithPassword, [switch]$NoTray)
$ErrorActionPreference = 'Stop'
$K = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path $K 'results.txt'
$Run = Join-Path $env:LOCALAPPDATA 'huginn-proto\run'
$Java = Join-Path $K 'rt\bin\java.exe'
$CP = "$K\classes;$K\lib\*"
$TaskName = 'huginn-proto-s4u'
Set-Content -Path $Out -Value "huginn core prototypes  $(Get-Date -Format s)  $env:USERDOMAIN\$env:USERNAME  $([Environment]::OSVersion.VersionString)" -Encoding ascii
function Note([string]$t) { Write-Host $t; Add-Content -Path $Out -Value $t -Encoding ascii }

# User-only run dir: the directory ACL is the Windows equivalent of 0700.
if (Test-Path $Run) {
  try { Remove-Item -Recurse -Force $Run -ErrorAction Stop } catch {
    # An earlier run's ACL can shut even its owner out: take it back first.
    & takeown /f (Split-Path -Parent $Run) /r /d y | Out-Null
    & icacls (Split-Path -Parent $Run) /reset /t /q | Out-Null
    Remove-Item -Recurse -Force $Run
  }
}
Unregister-ScheduledTask -TaskName 'huginn-proto-s4u' -Confirm:$false -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Run | Out-Null
# BY SID, NEVER BY $env:USERNAME: on a domain-joined box with a leftover local
# profile of the same name (PRESTIGE: local 'silence' vs JNET\silence) the bare
# name resolves to the LOCAL account, and the run dir was locked against the
# person running this. *S-1-... is icacls' SID syntax.
$mySid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls $Run /inheritance:r /grant:r "*$($mySid):(OI)(CI)F" /grant:r "*S-1-5-18:(OI)(CI)F" | Out-Null
Note "run dir ACL: $((& icacls $Run) -join ' | ')"

function Wait-Sock([string]$p, [int]$secs) {
  for ($i = 0; $i -lt ($secs * 10); $i++) { if (Test-Path $p) { return $true }; Start-Sleep -Milliseconds 100 }
  return $false
}
function Probe([string]$label, [string]$sock) {
  Note "== $label"
  $log = Join-Path $K "probe-$label.log"
  $p = Start-Process -FilePath $Java -ArgumentList @('-cp', "`"$CP`"", 'UnixSocketProbe', "`"$sock`"") -NoNewWindow -Wait -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  Get-Content $log | ForEach-Object { Note "   $_" }
  if ((Get-Item "$log.err").Length -gt 0) { Get-Content "$log.err" | Select-Object -First 15 | ForEach-Object { Note "   ERR $_" } }
  Note "   exit=$($p.ExitCode)"
}

# --- Q1a: same session --------------------------------------------------------
$sa = Join-Path $Run 'a.sock'
$srv = Start-Process -FilePath "$K\ipcserver.exe" -ArgumentList "`"$sa`"" -NoNewWindow -PassThru -RedirectStandardError "$K\server-a.log"
if (Wait-Sock $sa 5) { Probe 'Q1a-same-session' $sa } else { Note '== Q1a: server never created the socket'; Get-Content "$K\server-a.log" | ForEach-Object { Note "   $_" } }
Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue

# --- Q1b + Q2: server in an S4U task (session 0), client here (this session) ---
function Run-Task([string]$label, $principalArgs) {
  $sb = Join-Path $Run "$label.sock"
  $facts = Join-Path $K "facts-$label.json"
  Remove-Item $facts -ErrorAction SilentlyContinue
  $cmd = "`"$K\s4ucheck.exe`" `"$facts`" & `"$K\ipcserver.exe`" `"$sb`" 2> `"$K\server-$label.log`""
  $action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c $cmd"
  $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
  Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
  try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Settings $settings @principalArgs | Out-Null
  } catch { Note "== $label : register FAILED: $($_.Exception.Message)"; return }
  Start-ScheduledTask -TaskName $TaskName
  for ($i = 0; $i -lt 100 -and -not (Test-Path $facts); $i++) { Start-Sleep -Milliseconds 200 }
  if (Test-Path $facts) { Note "== $label facts: $(Get-Content $facts -Raw)" } else { Note "== $label : no facts file (task state $((Get-ScheduledTask -TaskName $TaskName).State))" }
  if (Wait-Sock $sb 10) { Probe "Q1b-cross-session-$label" $sb } else { Note "== $label : task server never created the socket"; if (Test-Path "$K\server-$label.log") { Get-Content "$K\server-$label.log" | ForEach-Object { Note "   $_" } } }
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Get-Process ipcserver -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
}
$me = "$env:USERDOMAIN\$env:USERNAME"
Run-Task 's4u' @{ Principal = (New-ScheduledTaskPrincipal -UserId $me -LogonType S4U -RunLevel Limited) }
if ($WithPassword) {
  $cred = Get-Credential -UserName $me -Message 'Password for the stored-password task test (used once, not saved)'
  Run-Task 'password' @{ User = $me; Password = $cred.GetNetworkCredential().Password; RunLevel = 'Limited' }
}

# --- reference: the same facts from THIS interactive session --------------------
& "$K\s4ucheck.exe" "$K\facts-interactive.json"
Note "== interactive facts: $(Get-Content "$K\facts-interactive.json" -Raw)"

# --- Q9: tray ---------------------------------------------------------------
if (-not $NoTray) {
  Note '== Q9 tray: a heather (lilac) disc should appear in the notification area for 20 s'
  $t = Start-Process -FilePath "$K\traytest.exe" -ArgumentList '20' -PassThru
  $seen = Read-Host 'Did you see the lilac disc in the tray (check the ^ overflow too)? [y/n]'
  Note "   seen=$seen"
  $t.WaitForExit(25000) | Out-Null
}

Remove-Item -Recurse -Force (Split-Path -Parent $Run) -ErrorAction SilentlyContinue
Note "done. Send back: $Out"
