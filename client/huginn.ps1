# huginn (PowerShell) - talk to your remote Claude Code node.
# Install: source from your $PROFILE:
#     if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }
# Targets the `huginn` SSH alias by default; override per-device with:  $env:HUGINN_HOST = 'my-host'
# Self-update with:  huginn update   (pulls this file from the repo; gh -> scp fallback)
# Version: 2.6.2

$script:HUGINN_VERSION = '2.6.2'
$script:HUGINN_REPO    = 'silencelen/huginn'
# Client code - this file, and the runners it installs - comes from the newest
# cli-v release on GitHub: a tagged build, never a moving branch. Only
# HUGINN_UPDATE_HOST, set on purpose, names a different source: an SSH host whose
# /usr/local/share/huginn-cli holds the files. Never $HUGINN_HOST: "which box do
# I drive" must not also answer "whose code do I run".

# ONE session-name rule for the whole product: lowercase letters, digits, '_'
# and '-', starting with a letter, digit or '_', at most 50 characters. Compared
# case-folded, because names are case-insensitive here (see _Huginn-CanonName).
# It still keeps a typo'd flag (e.g. 'huginn --hlp') from falling through to the
# attach path and spawning a junk tmux session - a leading '-' is not a name -
# and it still keeps names safe to pass through the remote shell. Enforced again
# server-side in cc.
#
# The dash used to be REFUSED here, and the daemon has always accepted it: a
# session named build-box was listed by `huginn ls`, openable from both GUI
# clients, and refused by every verb of this one before any network. The dot is
# banned everywhere on purpose - tmux silently rewrites '.' to '_', so a dotted
# name is a name that comes back different from the one that was asked for.
function _Huginn-ValidName {
  param([string]$Name)
  if (-not $Name) { return $false }
  return ($Name.ToLower() -match '^[a-z0-9_][a-z0-9_-]{0,49}$')
}
# WHY A NAME WAS REFUSED, as the daemon would say it (nameProblem, server/appd/
# huginn-appd.js), or '' when the shape is fine.
#
# ⚠ TWO OF THE THREE RULES WERE NEVER MENTIONED. Every refusal in this file said
# "use lowercase letters, digits, _ and -; no dots, spaces or *" - so `huginn
# kill -box` and a 51-character name were both told a sentence that describes a
# name which is, by that text, perfectly valid, and the refusal read as a bug in
# the client. The rule is ^[a-z0-9_][a-z0-9_-]{0,49}$: at most 50 characters, and
# '-' may not lead.
function _Huginn-NameProblem {
  param([string]$Name)
  if (-not $Name)          { return 'a session needs a name' }
  if ($Name.Contains('.')) { return 'a "." is rewritten to "_" by tmux, so the name you asked for would not be the name you got' }
  if ($Name -notmatch '^[A-Za-z0-9_]') { return "it starts with '$($Name.Substring(0,1))' - a name starts with a letter, digit or _" }
  if ($Name.Length -gt 50) { return "it is $($Name.Length) characters long - a name is at most 50" }
  return ''
}
# ONE refusal for a bad name, in both clients' words. Terminating, like every
# other refusal here - see _Huginn-Fail.
function _Huginn-BadName {
  param([string]$Name, [string]$Noun = 'session name', [string]$Tail = '')
  $lines = @("huginn: invalid $Noun '$Name'")
  $why = _Huginn-NameProblem $Name
  if ($why) { $lines += "        $why" }
  $lines += "        (letters, digits, _ and -, starting with a letter, digit or _, up to 50"
  $lines += "         characters; no dots, spaces or *. Case-insensitive.)"
  if ($Tail) { $lines += "        $Tail" }
  _Huginn-Fail ($lines -join "`n")
}

# Session names are case-INSENSITIVE: lowercase before touching tmux so 'Test' and
# 'test' resolve to the same session (tmux itself is case-sensitive). Canonicalized
# here for every tmux-facing path AND again server-side in cc as the backstop.
function _Huginn-CanonName { param([string]$Name) return $Name.ToLower() }
# tmux resolves -t targets by EXACT match, then PREFIX, then glob. A unique prefix
# resolves silently, so 'huginn kill andvari' would destroy a session actually named
# 'andvariautofill', and 'huginn solo jt' would evict the real client of 'jotter'.
# Anchoring with '=' forces exact match (tmux(1) "exact-match").
function _Huginn-TmuxTarget { param([string]$Name) return "=$Name" }

# Reach huginn-appd, which listens on the HOST's loopback. The bearer token is
# root-only on the host, so the call runs THERE (over the ssh alias) and only the
# result comes back - the token never touches a client device.
# Base64 for the same reason as the -p/-y path below: PS 5.1 mangles embedded
# double quotes when marshalling to a native exe, and this command carries both
# quotes and a $(...) that must be evaluated on the host.
#
# NOT `curl -sf`, WHICH THREW THE ANSWER AWAY. -f discards the response BODY on
# every HTTP >= 400, collapsing four different refusals into one failure: `huginn
# end` printed "is huginn-appd running? is the session a live Claude pane?" -
# naming two causes that are both fine - for the daemon's 409 "answer the waiting
# question first", for the 409 "no Claude state recorded ... pass force", for a
# 404, and for a 500 "tmux: ...". The Kotlin client sets expectSuccess = false
# for exactly this reason.
#
# The status rides home on its own last line, so one ssh still answers both
# questions. Returns @{ Reached; Code; Body; Ssh; Err }: Reached is $false only
# when curl never CONNECTED (code 000) or ssh itself failed, and that is the
# ONLY case in which a caller may fall back to raw tmux.
#
# ⚠ AND "NOT REACHED" IS TWO DIFFERENT FACTS. An unreachable HOST is a network
# problem and a stopped daemon is a systemctl problem, and `huginn end` answered
# the first with "could not reach huginn-appd on <host>" - blaming a daemon that
# was never asked - while 2>$null threw away the sentence that named the real
# fault. ssh's OWN failures exit 255 (ssh(1) "exit status"); any other non-zero
# code is the REMOTE command's, i.e. the connection was made and curl is what
# failed. `Ssh` is that discriminator and `Err` is whatever was said on stderr,
# verbatim - captured by merging stream 2 and then separating the ErrorRecords
# from the output, which is the one way that works on 5.1 and 7 alike.
function _Huginn-Appd {
  param([string]$H, [string]$Method, [string]$Path, [string]$Body)
  $data = if ($Body) { " -H 'Content-Type: application/json' --data '" + $Body + "'" } else { '' }
  # The token is read ON THE HOST and handed to curl as a config line on stdin
  # (`-K -`): printf is a shell builtin, so it never appears in any process's
  # command line, where every other account on the host could read it.
  # And only to the daemon, proved: HTTPS pinned to the public key in appd's own
  # certificate, which root on the host can read and no other account can (8787
  # is not a privileged port, so while appd is down anybody there can hold it).
  # The same text as _huginn_appd in huginn.sh, with the reasons there: no
  # certificate to read means no call (exit 3), another key means curl's 90,
  # said in words. $pin holds no single quote.
  $pin = 'u=$(systemctl show huginn-appd -p Environment --value 2>/dev/null | tr " " "\n"); c=${HUGINN_APPD_TLS_CERT:-$(printf "%s\n" "$u" | sed -n "s/^HUGINN_APPD_TLS_CERT=//p" | tail -n 1)}; d=${HUGINN_APPD_DATA:-$(printf "%s\n" "$u" | sed -n "s/^HUGINN_APPD_DATA=//p" | tail -n 1)}; c=${c:-${d:-/var/lib/huginn-appd}/tls/cert.pem}; k=$(openssl x509 -in "$c" -noout -pubkey 2>/dev/null); [ -n "$k" ] || { echo "huginn: cannot read the huginn-appd certificate $c - the token goes only to a daemon that proves it holds that key" >&2; exit 3; }; p=$(printf "%s\n" "$k" | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -binary | openssl base64 -A); '
  $remote = $pin + 'printf ''header = "Authorization: Bearer %s"\n'' "$(cat /etc/huginn-appd/token 2>/dev/null)" | curl -K - -k --pinnedpubkey "sha256//$p" -s -w "\n%{http_code}" -X ' + $Method + $data + ' "https://127.0.0.1:8787' + $Path + '"' +
            '; r=$?; [ "$r" != 90 ] || echo "huginn: whatever holds 127.0.0.1:8787 is not huginn-appd (wrong key); the token was not sent" >&2; exit $r'
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
  $raw = ssh -T -o BatchMode=yes -o ConnectTimeout=10 $H "echo $b64 | base64 -d | bash -s" 2>&1
  $rc = $LASTEXITCODE
  $errLines = @($raw | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } |
                 ForEach-Object { $_.ToString() })
  $out = @($raw | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } |
             ForEach-Object { [string]$_ })
  if ($rc -ne 0) {
    return [pscustomobject]@{ Reached = $false; Code = ''; Body = '';
                              Ssh = ($rc -eq 255); Err = ($errLines -join "`n") }
  }
  $lines = @($out)
  $code = if ($lines.Count) { ([string]$lines[-1]).Trim() } else { '' }
  $body = if ($lines.Count -gt 1) { ($lines[0..($lines.Count - 2)] -join '') } else { '' }
  return [pscustomobject]@{ Reached = ($code -ne '000' -and $code -ne ''); Code = $code; Body = $body;
                            Ssh = $false; Err = ($errLines -join "`n") }
}
# Why the last _Huginn-Appd call could not be made, on the error stream, indented
# under the caller's own line. Nothing is invented: if ssh said something that is
# what appears, and when only curl failed, the fact that the connection WAS made
# is itself the answer - the daemon is the suspect and systemctl is the next
# command.
function _Huginn-AppdWhy {
  param([string]$H, $R, [string]$Tail)
  $head = if ($R.Ssh) { "huginn: could not reach the host '$H' over ssh - huginn-appd was never asked" }
          else        { "huginn: $H answered, but huginn-appd did not" }
  $lines = @($head)
  if ($R.Err) { $lines += (($R.Err -split "`n") | ForEach-Object { '        ' + $_.TrimEnd() }) }
  if (-not $R.Ssh) { $lines += "        (ssh $H systemctl status huginn-appd)" }
  if ($Tail) { $lines += "        $Tail" }
  _Huginn-Fail ($lines -join "`n")
}
# One line of shell for the host, over ssh -T, whatever characters it holds.
#
# A DOUBLE QUOTE DOES NOT SURVIVE A NATIVE ARGUMENT ON WINDOWS POWERSHELL 5.1,
# nor on 7 before 7.3 (and huginn core hands the verbs it does not own to
# powershell.exe, which is 5.1). An argument with a space in it is passed
# wrapped in "..." with the double quotes inside left unescaped, and ssh.exe's
# argument parser eats them: a projects brief `Fix the "Sign in" button` reached
# the host as `Fix the Sign in button`. So a line that holds one goes base64'd,
# the way _Huginn-Appd and -p/-y send theirs. A line with none goes as it always
# did, which keeps ssh's stdin for the command (`--brief -` reads it there).
function _Huginn-SshLine {
  param([string]$H, [string]$Line)
  if ($Line.Contains('"')) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Line))
    ssh -T $H "echo $b64 | base64 -d | bash -s"
  } else {
    ssh -T $H $Line
  }
}
# THE ONE COLOUR GUARD, the twin of huginn.sh's _huginn_color: colour only when
# output is not redirected and NO_COLOR is unset (set to anything at all -
# no-color.org). Every coloured line in this file goes through _Huginn-Say, which
# reads it; nothing here passes -ForegroundColor on its own. The colours are the
# brand's 16-colour slot pins (assets/brand/BRAND.md section 4) as console colour
# NAMES, never hex, so the user's palette owns the hues: Red = error, Yellow =
# attention, Cyan = ok, Magenta = the accent, DarkGray = muted. Re-read at the top
# of every `huginn` call, so NO_COLOR set later in the session is honoured.
$script:HuginnColorOn = -not $env:NO_COLOR -and -not [Console]::IsOutputRedirected
function _Huginn-Say {
  param([string]$Text, [string]$Color, [switch]$NoNewline)
  if ($Color -and $script:HuginnColorOn) { Write-Host $Text -ForegroundColor $Color -NoNewline:$NoNewline }
  else { Write-Host $Text -NoNewline:$NoNewline }
}

# --- installer-style progress (huginn.sh has the same block, same look) --------
# One line per component, a turning wingbeat (BRAND.md section 6) while a step
# runs and a bar with size and speed while something downloads, both redrawn in
# place and settled into one final line. Redirected: only the final lines.
# Glyphs are built from [char] codes so this file stays 7-bit (Windows
# PowerShell 5.1 reads it in the ANSI code page); they are drawn only where the
# console can show them (Windows Terminal, or a UTF-8 console), else 7-bit twins.
function _Huginn-UiTty { (-not [Console]::IsOutputRedirected) -and ($env:TERM -ne 'dumb') }
function _Huginn-UiUni {
  if ($env:HUGINN_ASCII -eq '1') { return $false }
  if ($env:HUGINN_ASCII -eq '0') { return $true }
  return ([bool]$env:WT_SESSION -or ([Console]::OutputEncoding.CodePage -eq 65001))
}
function _Huginn-UiWidth { try { [Math]::Max(40, [Console]::WindowWidth - 1) } catch { 79 } }
# Redrawn lines go through Write-Host, never [Console]::Write: PowerShell 7
# encodes [Console]::Write in the console's OEM page, which has no wing
# diagonals, so the wingbeat read "?_?" (run 4); Write-Host draws them.
function _Huginn-UiClear { if (_Huginn-UiTty) { $w = _Huginn-UiWidth; Write-Host ("`r" + (' ' * $w) + "`r") -NoNewline } }
# Breaks $Text at spaces into lines that fit the terminal from column $Col, so
# a long line carries on under its own text instead of at the margin (run 4:
# "so no password is stor" / "ed)"). Redirected, or short: one line, as given.
function _Huginn-UiWrap {
  param([string]$Text, [int]$Col)
  $w = (_Huginn-UiWidth) - $Col
  if (-not (_Huginn-UiTty) -or $w -lt 20 -or $Text.Length -le $w) { return $Text }
  $out = @(); $rest = $Text
  while ($rest.Length -gt $w) {
    $i = $rest.LastIndexOf(' ', $w)
    if ($i -lt 1) { $i = $w }
    $out += $rest.Substring(0, $i).TrimEnd()
    $rest = $rest.Substring($i).TrimStart()
  }
  if ($rest) { $out += $rest }
  return $out   # callers wrap it in @()
}
function _Huginn-UiLine {
  param([string]$State, [string]$Comp, [string]$Text)
  $u = _Huginn-UiUni
  $g = switch ($State) {
    'ok'    { if ($u) { [string][char]0x25A0 } else { '+' } }
    'fail'  { if ($u) { [string][char]0x00D7 } else { 'x' } }
    'warn'  { if ($u) { [string][char]0x25B2 } else { '!' } }
    default { if ($u) { [string][char]0x25E6 } else { '-' } }
  }
  $c = switch ($State) { 'ok' { 'Cyan' } 'fail' { 'Red' } 'warn' { 'Yellow' } default { 'DarkGray' } }
  _Huginn-UiClear
  $ls = @(_Huginn-UiWrap $Text 18)
  Write-Host '     ' -NoNewline
  _Huginn-Say $g $c -NoNewline
  Write-Host ('  ' + $Comp.PadRight(9) + ' ' + $ls[0])
  foreach ($l in @($ls | Select-Object -Skip 1)) { Write-Host ((' ' * 18) + $l) }
}
function _Huginn-UiHead { param([string]$Text) _Huginn-Say '(o>' Magenta -NoNewline; Write-Host " $Text" }
function _Huginn-UiFrames {
  if (-not (_Huginn-UiUni)) { if ($env:HUGINN_REDUCED_MOTION) { return @('-_-') }; return @('-_-', '\_/', '-_-', "/'\") }
  $a = "$([char]0x2500)$([char]0x2584)$([char]0x2500)"
  if ($env:HUGINN_REDUCED_MOTION) { return @($a) }
  return @($a, "$([char]0x2572)$([char]0x2584)$([char]0x2571)", $a, "$([char]0x2571)$([char]0x2580)$([char]0x2572)")
}
function _Huginn-UiLive {
  param([string]$Frame, [string]$Comp, [string]$Text)
  $w = _Huginn-UiWidth
  $line = '    ' + $Frame + ' ' + $Comp.PadRight(9) + ' ' + $Text
  if ($line.Length -gt $w) { $line = $line.Substring(0, $w) }
  Write-Host ("`r" + $line.PadRight($w)) -NoNewline
}
function _Huginn-UiNote { param([string]$Text) foreach ($l in @(_Huginn-UiWrap $Text 18)) { _Huginn-Say ((' ' * 18) + $l) DarkGray } }
# Waits for $P (a process started elsewhere, e.g. elevated) with the wingbeat.
function _Huginn-UiWait {
  param([System.Diagnostics.Process]$P, [string]$Comp, [string]$What)
  $fr = @(_Huginn-UiFrames); $i = 0
  while (-not $P.HasExited) {
    if (_Huginn-UiTty) { _Huginn-UiLive $fr[$i % $fr.Count] $Comp $What }
    $i++; Start-Sleep -Milliseconds 125
  }
  $P.WaitForExit()
  _Huginn-UiClear
}
# Draws the lines `huginn core install --report` wrote (state, component, text,
# tab separated): its elevated step runs in a window of its own, so it hands
# its lines back to be drawn here. $Tray is appended to the tray's ok line.
function _Huginn-UiRecords {
  param([string]$Path, [string]$Tray)
  foreach ($r in @(Get-Content $Path -Encoding UTF8 -ErrorAction SilentlyContinue)) {
    $f = $r -split "`t", 3
    if ($f.Count -lt 3) { continue }
    switch ($f[0]) {
      'note' { _Huginn-UiNote $f[2] }
      'say'  { foreach ($l in @(_Huginn-UiWrap $f[2] 4)) { Write-Host ('    ' + $l) } }
      default {
        $t = $f[2]
        if ($f[0] -eq 'ok' -and $f[1] -eq 'tray' -and $Tray) { $t = "$t; $Tray" }
        _Huginn-UiLine $f[0] $f[1] $t
      }
    }
  }
}
# Runs $Exe with $Arguments, its output to $Log, turning the wingbeat while it
# runs. Returns its exit code.
function _Huginn-UiSpin {
  param([string]$Comp, [string]$What, [string]$Exe, [string[]]$Arguments, [string]$Log)
  $o = "$Log.out"; $e = "$Log.err"
  $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -NoNewWindow -PassThru -RedirectStandardOutput $o -RedirectStandardError $e
  $null = $p.Handle   # without it a redirected process reports no ExitCode
  $fr = @(_Huginn-UiFrames); $i = 0
  while (-not $p.HasExited) {
    if (_Huginn-UiTty) { _Huginn-UiLive $fr[$i % $fr.Count] $Comp $What }
    $i++; Start-Sleep -Milliseconds 125
  }
  $p.WaitForExit()
  # The core writes UTF-8; Windows PowerShell 5.1 would read it as the ANSI page.
  (@(Get-Content $o -Encoding UTF8 -ErrorAction SilentlyContinue) + @(Get-Content $e -Encoding UTF8 -ErrorAction SilentlyContinue)) | Set-Content -Encoding UTF8 $Log
  Remove-Item $o, $e -Force -ErrorAction SilentlyContinue
  _Huginn-UiClear
  return $p.ExitCode
}
# Downloads $Url to $Out with a bar ($Size bytes; 0 = take the server's).
function _Huginn-UiDownload {
  param([string]$Url, [string]$Out, [long]$Size, [string]$Comp)
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
  $req = [Net.HttpWebRequest]::Create($Url); $req.Timeout = 60000; $req.ReadWriteTimeout = 300000
  $resp = $req.GetResponse()
  if ($Size -le 0) { $Size = $resp.ContentLength }
  $in = $resp.GetResponseStream(); $fs = [IO.File]::Create($Out)
  $buf = New-Object byte[] 65536; $got = [long]0; $last = -1000
  $sw = [Diagnostics.Stopwatch]::StartNew(); $fr = @(_Huginn-UiFrames); $i = 0
  $on = if (_Huginn-UiUni) { [string][char]0x2588 } else { '#' }; $off = if (_Huginn-UiUni) { [string][char]0x2591 } else { '-' }
  $tty = _Huginn-UiTty
  try {
    while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
      $fs.Write($buf, 0, $n); $got += $n
      if ($tty -and ($sw.ElapsedMilliseconds - $last -ge 125)) {
        $last = $sw.ElapsedMilliseconds
        $mbps = ($got / 1MB) / [Math]::Max(0.1, $sw.Elapsed.TotalSeconds)
        if ($Size -gt 0) {
          $pct = [Math]::Min(100, [int][Math]::Floor($got * 100 / $Size)); $fill = [int][Math]::Floor($pct * 24 / 100)
          $t = 'downloading  {0}{1} {2,3}%  {3:N1}/{4:N1} MB  {5:N1} MB/s' -f ($on * $fill), ($off * (24 - $fill)), $pct, ($got / 1MB), ($Size / 1MB), $mbps
        } else {
          $t = 'downloading  {0:N1} MB  {1:N1} MB/s' -f ($got / 1MB), $mbps
        }
        _Huginn-UiLive $fr[$i % $fr.Count] $Comp $t; $i++
      }
    }
  } finally { $fs.Close(); $in.Close(); $resp.Close() }
  _Huginn-UiClear
}

# ONE refusal path for the whole client, and the only thing here that a script
# can read.
#
# ⚠⚠ Write-Host IS NOT A FAILURE. Every refusal in this file used to be a
# Write-Host and a bare `return`: `huginn kill 'bad name!'` and `huginn end
# nosuchsession` both left $? True and $LASTEXITCODE 0, so a Windows script, a
# CI step or a `&&` chain read every failure as a success - the gate-pipe
# exit-code trap wearing the other client's clothes - while the bash twin
# returned 1 for both. And Write-Host writes to the HOST stream, so `huginn end
# x 2>$null` could not suppress the text and `$out = huginn end x` could not
# capture it.
#
# `throw` is what a simple function has. $? is NOT set by Write-Error here: for
# a non-advanced function PowerShell attributes the error to the function's
# BODY, not to the call, and [CmdletBinding()] is not available to us - a
# declared parameter set would make `huginn -p "q"` a parameter-binding failure
# instead of a verb. So a refusal is a TERMINATING error: $? goes False, `&&`
# short-circuits, try/catch can take it, and the sentence lands on the error
# stream and in $Error. $LASTEXITCODE is set too, so a script that watches the
# number a native command would have left behind still sees one.
#
# The sentence goes to stderr FIRST, plain, exactly as bash's twin prints it, so a
# refusal reads the same in both clients; the throw that follows is what a script
# branches on.
function _Huginn-Fail {
  param([string]$Message, [int]$Code = 1)
  $global:LASTEXITCODE = $Code
  [Console]::Error.WriteLine($Message)
  throw $Message
}
# The daemon's own sentence out of an error body ({"error":"..."}), or ''.
function _Huginn-AppdError {
  param([string]$Body)
  if (-not $Body) { return '' }
  try { $j = $Body | ConvertFrom-Json; if ($j.error) { return [string]$j.error } } catch {}
  $m = [regex]::Match($Body, '"error"\s*:\s*"([^"]*)"')
  if ($m.Success) { return $m.Groups[1].Value }
  return ''
}

# Fetch ONE client file: GitHub via gh, else the pinned mirror via scp, validated
# before it is installed. $true when $Dest now holds the file.
#
# ⚠ THREE THINGS THIS GETS RIGHT THAT THE TWO INLINE COPIES DID NOT.
#
# (1) ORDER. `node --check` used to run AFTER the scp block, so a FAILED gh
#     fetch whose error body happened to be non-empty set $got and SKIPPED the
#     mirror entirely - `gh api` prints its error JSON to STDOUT (bad token =
#     112 bytes, renamed path = 127 bytes, both exit 1). The check then cleared
#     $got and the user was told "gh and the mirror both failed" about a mirror
#     that was never contacted. Each source is now validated inside its own
#     branch, so a bad body from one really does fall through to the other.
#
# (2) EXIT STATUS. try/catch cannot catch a native command's failure, so gh's
#     $LASTEXITCODE is what decides. A non-empty ERROR BODY is still non-empty;
#     length alone was never a gate. `huginn update` already knew this - these
#     two branches regressed against their own sibling in this file.
#
# (3) ENCODING. `>` is Out-File, and its WINDOWS POWERSHELL 5.1 default is
#     -Encoding unicode: UTF-16LE with a BOM, which node cannot parse. Combined
#     with (1) that made `huginn device on|enrol|update` and `huginn local
#     on|update|plan` fail 100% of the time on stock 5.1 with gh installed and
#     authenticated, mirror never tried. Set-Content writes the bytes instead.
#     UTF-8 and not -Encoding ascii (which `huginn update` may use, because
#     huginn.ps1 IS pure ASCII): these runners are not - they carry ⚠ and → in
#     strings a person reads - so the console is also asked to hand us UTF-8
#     rather than the OEM code page. Node strips the BOM a 5.1 Set-Content adds.
# The newest cli-v release tag, by version number, or $null.
function _Huginn-ReleaseTag {
  $body = _Huginn-Get "https://api.github.com/repos/$script:HUGINN_REPO/releases?per_page=60"
  if (-not $body) { return $null }
  try { $rels = $body | ConvertFrom-Json } catch { return $null }
  $best = $null; $bestV = $null
  foreach ($r in @($rels)) {
    if ([string]$r.tag_name -match '^cli-v(\d+\.\d+\.\d+)$') {
      $v = [version]$Matches[1]
      if (-not $bestV -or $v -gt $bestV) { $bestV = $v; $best = [string]$r.tag_name }
    }
  }
  return $best
}

# client/<file> into $Dest from the newest release, or from the HUGINN_UPDATE_HOST
# mirror when that is set. Returns where it came from, or $null (with $why set
# in the caller's scope via the -Why reference).
function _Huginn-FetchClient {
  param([string]$File, [string]$Dest, [ref]$Why)
  Remove-Item -Force -ErrorAction SilentlyContinue $Dest
  if ($env:HUGINN_UPDATE_HOST) {
    scp -o BatchMode=yes "$($env:HUGINN_UPDATE_HOST):/usr/local/share/huginn-cli/$File" $Dest 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0 -and (Test-Path $Dest) -and (Get-Item $Dest).Length -gt 0) { return "the $($env:HUGINN_UPDATE_HOST) mirror" }
    $Why.Value = "the $($env:HUGINN_UPDATE_HOST) mirror did not return $File"; return $null
  }
  $tag = _Huginn-ReleaseTag
  if (-not $tag) { $Why.Value = 'could not read the newest release from GitHub'; return $null }
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
  try {
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$script:HUGINN_REPO/$tag/client/$File" `
      -OutFile $Dest -UseBasicParsing -TimeoutSec 60 -ErrorAction Stop
  } catch { $Why.Value = "could not download $File from $tag"; return $null }
  if (-not (Test-Path $Dest) -or (Get-Item $Dest).Length -eq 0) { $Why.Value = "$tag returned an empty $File"; return $null }
  return $tag
}

function _Huginn-FetchFile {
  param([string]$RepoPath, [string]$Dest, [string]$Label)
  $tmp = "$Dest.tmp.js"      # .js: modern node refuses to PARSE an unknown extension
  $why = ''
  $file = Split-Path -Leaf $RepoPath
  if (_Huginn-FetchClient -File $file -Dest $tmp -Why ([ref]$why)) {
    # Validated BEFORE installing: a truncated download (or a proxy's error page)
    # that a service then restarts every ten seconds is worse than no runner.
    node --check $tmp 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Move-Item -Force $tmp $Dest; return $true }
    $why = "what came back is not valid JavaScript"
  }
  Remove-Item -Force -ErrorAction SilentlyContinue $tmp
  # A fetch that failed is a failure, never a quiet return: callers and scripts
  # must see it (`&&` chains, $?).
  _Huginn-Fail ("{0}: could not fetch {1} ({2})" -f $Label, $file, $why)
}

# The daemon URL this machine should be enrolled with, worked out from the ssh
# link it has ALREADY been trusted on. $SSH_CONNECTION's third field is the
# address THIS machine just reached the host at, which is better than choosing
# on the device's behalf between a LAN address, a tailnet name and whatever
# `hostname` happens to say. Returns https://<authority>:8787, and never a
# loopback url (see the last note).
#
# ⚠ HTTPS, BECAUSE THE RUNNER CARRIES THE TOKEN (appd 3.11, docs/dev/protocol-tls.md). The
# runner sends /etc/huginn-appd/token - root-equivalent on the host - on every
# request, and a device enrolled from here is usually on a flat LAN with no VPN
# under it. The runner would upgrade a remote http:// on its own anyway (rule 6:
# no setting re-enables cleartext to a remote host); building https:// here is
# so what the person reads in `huginn device status` is what is actually dialled.
#
# ⚠ IT IS A BARE ADDRESS, AND A BARE IPv6 LITERAL IS NOT A URL.
# `http://fd00::1:8787` has no valid port, so `huginn device on` / `huginn local
# on` from a machine whose ssh landed on IPv6 died with nothing but
# "huginn-device: Invalid URL" - after the url had already been PERSISTED, so a
# later flagless `on` repeated it and `serve` retried it every 15 seconds
# forever.
#
# ⚠ AND BRACKETING ALONE WOULD ONLY CHANGE THE ERROR. appd binds an IPv4 address,
# so a bracketed v6 url is a persisted ECONNREFUSED. On a v6 ssh path the host is
# asked for an IPv4 it actually holds, and the bracketed literal is kept only as
# the last answer - correct syntax, and an honest failure.
#
# ⚠ AND AN EMPTY $SSH_CONNECTION IS NOT A DEAD END ANY MORE. It used to be
# `if (-not $a) { return $null }`, which the caller turned into "could not work
# out how to reach huginn's daemon" with nothing to do about it - and the index
# above threw outright when the probe came back with fewer than three fields.
# It is empty in two ordinary situations: a shell running ON the host itself,
# and an sshd whose ForceCommand/Match wrapper does not pass the variable on.
# Both can be answered by asking the host for an address it actually holds, and
# only when it names none is it a refusal, with `--url` named.
#
# ⚠ AND NEVER LOOPBACK. This is the Windows client and the huginn host is a
# Linux box, so the host is never this machine (and Windows has no
# /etc/machine-id that could prove otherwise). A 127.* or ::1 answer means a
# tunnel or proxy that ends on the HOST's loopback (a reverse ssh tunnel,
# cloudflared, sslh), and it used to become http://127.x:8787 anyway:
# the device was enrolled at ITS OWN loopback, and the runner sent the
# root-equivalent token in cleartext, every 15 seconds, to whatever local
# account or app bound that port. A loopback answer is now no answer at all.
#
# NOTE ON ROUTES: the apps pin a ROUTE BOOK (several addresses per daemon, tried
# in order, with a witness). This client deliberately has none - it is an ssh
# front end, so it has exactly one route, the alias - and the address below is
# only ever handed to the local RUNNER, which is the piece that dials the daemon
# directly.
function _Huginn-SrvUrl {
  param([string]$H)
  $conn = @(((ssh -T $H 'echo $SSH_CONNECTION') -join ' ') -split '\s+' | Where-Object { $_ })
  $a = if ($conn.Count -ge 3) { [string]$conn[2] } else { '' }
  if ($a -like '127.*' -or $a -eq '::1' -or $a -like '::ffff:127.*') { $a = '' }
  if ($a -and $a -notmatch ':') { return "https://${a}:8787" }
  $v4 = ((ssh -T $H "ip -4 -o addr show scope global 2>/dev/null | awk '{print `$4}' | cut -d/ -f1 | head -1") -join '').Trim()
  if ($v4 -match '^\d+\.\d+\.\d+\.\d+$' -and $v4 -notlike '127.*') { return "https://${v4}:8787" }
  if (-not $a) { return $null }
  return "https://[$a]:8787"
}

# Did the caller already say where the daemon is? `huginn device on --url ...`
# (and --url=...) is the out when neither the ssh link nor the host can answer,
# and deriving one on top of it would put two --url flags on the runner's argv.
function _Huginn-HasUrl {
  param([object[]]$Rest)
  foreach ($a in @($Rest)) { if (([string]$a) -eq '--url' -or ([string]$a).StartsWith('--url=')) { return $true } }
  return $false
}

# pwsh on Linux or macOS keeps the appd token the way the bash client does:
# owner-only. New-Item and Set-Content create at the umask (directories 0755,
# files 0644), so on a shared machine the token - root-equivalent on the host -
# was readable by every account. Windows keeps what it had: there the
# profile's ACL is what guards it. A no-op on Windows (5.1 has no $IsWindows).
function _Huginn-OwnerOnly {
  param([string]$Path, [switch]$Directory)
  if ($null -eq $IsWindows -or $IsWindows) { return }
  $mode = if ($Directory) { 'UserRead, UserWrite, UserExecute' } else { 'UserRead, UserWrite' }
  $t = 'System.IO.UnixFileMode' -as [type]
  if ($t) { [IO.File]::SetUnixFileMode($Path, [Enum]::Parse($t, $mode)); return }
  chmod $(if ($Directory) { '700' } else { '600' }) -- $Path
  if ($LASTEXITCODE -ne 0) { throw "huginn: could not make $Path owner-only" }
}

# Write the token to $Path. Off Windows: its directory is made 0700 first, so
# not even an empty temp is reachable by another account; the temp has a
# random name and is created new (never a file somebody planted), 0600 before
# a byte of the token goes in, and is then renamed over $Path.
function _Huginn-WriteSecret {
  param([string]$Path, [string]$Value)
  if ($null -eq $IsWindows -or $IsWindows) { Set-Content -NoNewline -Path $Path -Value $Value; return }
  $d = Split-Path -Parent $Path
  New-Item -ItemType Directory -Force -Path $d | Out-Null
  _Huginn-OwnerOnly $d -Directory
  $tmp = Join-Path $d ('.' + (Split-Path -Leaf $Path) + '.' + [IO.Path]::GetRandomFileName())
  $t = 'System.IO.UnixFileMode' -as [type]
  if ($t) {
    $o = New-Object IO.FileStreamOptions
    $o.Mode = 'CreateNew'; $o.Access = 'Write'; $o.UnixCreateMode = [Enum]::Parse($t, 'UserRead, UserWrite')
    $fs = [IO.FileStream]::new($tmp, $o)
  } else {
    $fs = [IO.File]::Open($tmp, 'CreateNew', 'Write', 'None')
  }
  try {
    _Huginn-OwnerOnly $tmp
    $b = (New-Object Text.UTF8Encoding $false).GetBytes($Value)
    $fs.Write($b, 0, $b.Length)
  } catch {
    $fs.Dispose(); Remove-Item -Force -LiteralPath $tmp -ErrorAction SilentlyContinue; throw
  }
  $fs.Dispose()
  Move-Item -Force -LiteralPath $tmp -Destination $Path
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
  # NOTE: Highest SEMVER, not first in the feed. GitHub does not return releases
  # newest-first - verified 2026-08-25 with desktop-v0.8.9 ahead of
  # desktop-v0.8.13 in the same page - so this handed out an installer four
  # versions stale while looking perfectly healthy, because the url was
  # well-formed and did exist. The Kotlin updater already picked by semver.
  $tag = ($releases | Where-Object { $_.tag_name -like 'desktop-v*' } |
            Sort-Object { try { [version]($_.tag_name -replace '^desktop-v','') } catch { [version]'0.0.0' } } |
            Select-Object -Last 1).tag_name
  if (-not $tag) { return $null }
  # manifest.json is a release ASSET (the same one the updater verifies sha256
  # against), so the filenames come from the release itself - nothing here has to
  # guess how jpackage or the NSIS step named an artifact.
  $man = _Huginn-Get "https://github.com/$script:HUGINN_REPO/releases/download/$tag/manifest.json"
  if (-not $man) { return $null }
  try { return @{ tag = $tag; manifest = ($man | ConvertFrom-Json) } } catch { return $null }
}

# Asks a running Huginn Desktop to quit through its single-instance channel
# (port and secret in instance.lock, readable by this user only), and waits for
# it to go. True when it went. An app from before 2.3.6 does not know the
# request; the installer then closes it as before.
function _Huginn-DesktopAskQuit {
  $base = if ($env:XDG_CONFIG_HOME) { $env:XDG_CONFIG_HOME } else { Join-Path $HOME '.config' }
  $lock = Join-Path $base 'huginn-desktop-kt\instance.lock'
  if (-not (Test-Path $lock)) { return $false }
  try {
    $l = @(Get-Content $lock)
    $c = New-Object Net.Sockets.TcpClient('127.0.0.1', [int]$l[0])
    $st = $c.GetStream(); $st.ReadTimeout = 3000
    $w = New-Object IO.StreamWriter($st); $w.NewLine = "`n"
    $w.WriteLine($l[1]); $w.WriteLine('!quit'); $w.Flush()
    $null = (New-Object IO.StreamReader($st)).ReadLine()
    $c.Close()
  } catch { return $false }
  for ($i = 0; $i -lt 60; $i++) {
    if (-not (Get-Process -Name 'huginn-desktop-kt' -ErrorAction SilentlyContinue)) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}

# `huginn desktop update` (and the desktop step of `huginn update`): bring the
# installed Huginn Desktop to the newest desktop-v release. The installer comes
# from the release, is checked against the sha256 in that release's manifest,
# and runs silently (it is per-user: no administrator). An app that was open is
# opened again afterwards. Not installed: nothing to do, silently.
# -Install: `huginn desktop install` - the same verified, silent install for a
# machine that has no desktop app yet (it used to point at the .exe instead).
# Opens an app as the signed-in user. From an elevated terminal (run 3: the
# one-liner pasted into an administrator PowerShell) Start-Process hands the
# app this terminal's administrator token, and the desktop app then ran
# elevated. Explorer starts it with the user's own token.
function _Huginn-OpenAsUser([string]$Path) {
  $admin = $false
  try { $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) } catch { }
  if ($admin) { Start-Process -FilePath (Join-Path $env:WINDIR 'explorer.exe') -ArgumentList ('"' + $Path + '"') }
  else { Start-Process -FilePath $Path }
}

function _Huginn-DesktopUpdate {
  param([switch]$Install)
  $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\huginn-desktop-kt'
  $reg = Get-ItemProperty -Path $key -ErrorAction SilentlyContinue
  if (-not $reg -and -not $Install) { _Huginn-UiLine 'skip' 'desktop' 'not installed (huginn desktop install)'; return }
  $have = if ($reg) { "$($reg.DisplayVersion)" } else { '' }
  $rel = _Huginn-DesktopRelease
  if (-not $rel) { _Huginn-UiLine 'fail' 'desktop' 'could not read the release feed - not updated'; return }
  $ver = "$($rel.manifest.version)"
  if ($ver -notmatch '^\d+\.\d+\.\d+$') { _Huginn-UiLine 'fail' 'desktop' "$($rel.tag) carries an unreadable version - not updated"; return }
  if ($have -and -not (_Huginn-CoreNewer $have $ver)) { _Huginn-UiLine 'ok' 'desktop' "$have is current"; return }
  $a = $rel.manifest.artifacts.'windows-x64'
  $file = "$($a.file)"; $sha = "$($a.sha256)".ToLower()
  if ($file -ne "Huginn-Desktop-Setup-$ver.exe" -or $sha -notmatch '^[0-9a-f]{64}$') {
    _Huginn-UiLine 'fail' 'desktop' "$($rel.tag) has no usable Windows installer in its manifest - not updated"; return
  }
  $from = if ($have) { $have } else { '?' }
  $exe = Join-Path ([IO.Path]::GetTempPath()) $file
  try {
    _Huginn-UiDownload "https://github.com/$script:HUGINN_REPO/releases/download/$($rel.tag)/$file" $exe ([long]$a.size) 'desktop'
    $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash.ToLower()
    if ($got -ne $sha) { _Huginn-UiLine 'fail' 'desktop' "$file did not match its sha256 - discarded, nothing installed"; return }
    $app = if ($reg) { Join-Path (Split-Path -Parent "$($reg.UninstallString)".Trim('"')) 'huginn-desktop-kt.exe' } else { '' }
    $wasOpen = [bool](Get-Process -Name 'huginn-desktop-kt' -ErrorAction SilentlyContinue)
    # The installer installs the core it carries. Desktop 2.3.6 and older do
    # that without comparing versions, which put an OLDER core over the one
    # this machine runs (PC01, 2026-10-16) - so the current core is kept
    # aside and put back if the installer replaced it with an older one.
    $coreBin = _Huginn-CoreBin; $coreWas = ''; $coreKeep = ''
    if ($coreBin) {
      if (((& $coreBin version 2>$null) -join '') -match '^\(o> huginn (\S+) ') { $coreWas = $Matches[1] }
      $coreKeep = Join-Path ([IO.Path]::GetTempPath()) ('huginn-core-keep-' + [guid]::NewGuid().ToString('N') + '.exe')
      Copy-Item -LiteralPath $coreBin -Destination $coreKeep -Force -ErrorAction SilentlyContinue
    }
    # Ask it to quit the way its own tray menu does, before the installer has
    # to end it by force (a forced end left the launcher showing error dialogs).
    if ($wasOpen) {
      if (_Huginn-UiTty) { _Huginn-UiLive (@(_Huginn-UiFrames)[0]) 'desktop' 'closing the app' }
      $null = _Huginn-DesktopAskQuit
    }
    $p = Start-Process -FilePath $exe -ArgumentList '/S' -PassThru
    $null = $p.Handle
    $fr = @(_Huginn-UiFrames); $i = 0
    while (-not $p.HasExited) { if (_Huginn-UiTty) { _Huginn-UiLive $fr[$i % $fr.Count] 'desktop' "installing $ver" }; $i++; Start-Sleep -Milliseconds 125 }
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { _Huginn-UiLine 'fail' 'desktop' "the installer exited $($p.ExitCode) - see Settings > Apps"; return }
    $now = "$((Get-ItemProperty -Path $key -ErrorAction SilentlyContinue).DisplayVersion)"
    $tail = ''
    if (-not $app) { $app = Join-Path $env:LOCALAPPDATA 'Programs\huginn-desktop-kt\huginn-desktop-kt.exe' }
    # A silent first install answers its components page with the section
    # DEFAULTS (local AI off, autostart off) into first-run.json, and the app
    # then showed those steps as "skipped" - answers nobody gave (PC01,
    # 2026-10-16). Nobody saw that page, so its file goes before the app opens.
    if ($Install -and -not $have) {
      $cfgRoot = if ($env:XDG_CONFIG_HOME) { $env:XDG_CONFIG_HOME } else { Join-Path $HOME '.config' }
      Remove-Item -Force (Join-Path $cfgRoot 'huginn-desktop-kt\first-run.json') -ErrorAction SilentlyContinue
    }
    if (($wasOpen -or $Install) -and (Test-Path $app)) { _Huginn-OpenAsUser $app; $tail = if ($Install) { ', opened' } else { ', reopened' } }
    if ($Install -and -not $have) { _Huginn-UiLine 'ok' 'desktop' "$now installed (Start menu)$tail" }
    else { _Huginn-UiLine 'ok' 'desktop' "$from -> $now   installed$tail" }
    if ($coreWas -and $coreKeep -and (Test-Path $coreKeep)) {
      $coreNow = ''
      if (((& $coreBin version 2>$null) -join '') -match '^\(o> huginn (\S+) ') { $coreNow = $Matches[1] }
      if ($coreNow -and (_Huginn-CoreNewer $coreNow $coreWas)) {
        $log = "$coreKeep.log"
        $null = _Huginn-UiSpin 'core' "putting $coreWas back" $coreKeep @('core', 'self-install') $log
        Remove-Item $log -Force -ErrorAction SilentlyContinue
        _Huginn-UiLine 'ok' 'core' "kept $coreWas (the desktop installer carries $coreNow)"
      }
    }
  } catch {
    _Huginn-UiLine 'fail' 'desktop' "install failed: $($_.Exception.Message)"
  } finally {
    Remove-Item -Force $exe -ErrorAction SilentlyContinue
    if ($coreKeep) { Remove-Item -Force $coreKeep -ErrorAction SilentlyContinue }
  }
}

# --- huginn core ---
# The Go core (docs/design/core.md): one binary per user that runs act
# mode at boot, with nobody signed in. While it exists, `huginn device` is a
# door to it and the Node runner is not used. The binary is NAMED huginn too, so
# it is always run by its full path - a bare `huginn` here is this function.
function _Huginn-CoreBin {
  # No LOCALAPPDATA = not Windows (pwsh on Linux): no Windows core to find.
  if (-not $env:LOCALAPPDATA) { return $null }
  $p = Join-Path $env:LOCALAPPDATA 'huginn\bin\huginn.exe'
  if (Test-Path $p) { return $p }
  return $null
}

# Newest cli-v* (2.x+) release that carries core-manifest.json, semver-picked.
function _Huginn-CoreRelease {
  $body = _Huginn-Get "https://api.github.com/repos/$script:HUGINN_REPO/releases?per_page=60"
  if (-not $body) { return $null }
  try { $releases = $body | ConvertFrom-Json } catch { return $null }
  $tag = ($releases | Where-Object { $_.tag_name -match '^cli-v[0-9]+\.[0-9]+\.[0-9]+$' -and [int](($_.tag_name -replace '^cli-v','').Split('.')[0]) -ge 2 } |
            Sort-Object { [version]($_.tag_name -replace '^cli-v','') } | Select-Object -Last 1).tag_name
  if (-not $tag) { return $null }
  $man = _Huginn-Get "https://github.com/$script:HUGINN_REPO/releases/download/$tag/core-manifest.json"
  if (-not $man) { return $null }
  try { return @{ tag = $tag; manifest = ($man | ConvertFrom-Json) } } catch { return $null }
}

# Download, verify (sha256 from the release's own manifest) and self-install.
# $env:HUGINN_CORE_FILE = a local huginn.exe skips the download (offline/tests);
# it is still installed by `self-install`, never copied into place by hand.
function _Huginn-CoreFetch {
  $tmp = Join-Path ([IO.Path]::GetTempPath()) ("huginn-core-" + [guid]::NewGuid().ToString('N') + '.exe')
  try {
    if ($env:HUGINN_CORE_FILE) {
      Copy-Item $env:HUGINN_CORE_FILE $tmp -Force
    } else {
      $rel = _Huginn-CoreRelease
      if (-not $rel) { _Huginn-Fail "huginn core: could not read the core release feed (offline, rate-limited, or no 2.x release yet)" }
      $a = $rel.manifest.artifacts.'windows-x64'
      if (-not $a -or ($a.file -notmatch '^huginn-[0-9][^\\/]*-windows-x64\.exe$')) { _Huginn-Fail "huginn core: $($rel.tag) names no usable Windows build - refused" }
      if ($a.sha256 -notmatch '^[0-9a-f]{64}$') { _Huginn-Fail "huginn core: $($rel.tag) carries no usable sha256 - refused" }
      _Huginn-UiDownload "https://github.com/$script:HUGINN_REPO/releases/download/$($rel.tag)/$($a.file)" $tmp ([long]$a.size) 'core'
      # THE GATE: the only integrity check between GitHub and an executable
      # in your profile.
      $got = (Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLower()
      if ($got -ne $a.sha256) { _Huginn-Fail "huginn core: $($a.file) did not match its sha256 - discarded, nothing installed" }
    }
    # Under the wingbeat, its report to a log: one summary line here, the whole
    # report on failure or when not compact (`huginn core install`). (Never as
    # this function's output: that is its RETURN value, and the caller's
    # `$null = _Huginn-CoreFetch` once threw the report away with it.)
    $log = "$tmp.log"
    $rc = _Huginn-UiSpin 'core' 'installing' $tmp @('core', 'self-install') $log
    $report = @(Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue); Remove-Item $log -Force -ErrorAction SilentlyContinue
    if ($rc -ne 0) {
      _Huginn-UiLine 'fail' 'core' 'self-install failed:'; $report | ForEach-Object { Write-Host "            $_" }
      _Huginn-Fail "huginn core: self-install failed"
    }
    $now = ''; if (((& $tmp version 2>$null) -join '') -match '^\(o> huginn (\S+) ') { $now = $Matches[1] }
    $how = 'installed'
    if ($report -match 'restarted huginn core') { $how = 'installed, restarted' }
    if ($report -match 'starting huginn core directly') { $how = 'installed, started directly (Windows held its task back)' }
    if ($report -match 'has not come back yet') { $how = 'installed; the running core restarts onto it shortly' }
    $from = if ($env:HUGINN_UI_FROM) { "$env:HUGINN_UI_FROM -> " } else { '' }
    _Huginn-UiLine 'ok' 'core' "$from$now   $how"
    if (-not $env:HUGINN_UI_COMPACT) { $report | ForEach-Object { Write-Host "            $_" } }
  } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
  return (_Huginn-CoreBin)
}

# Is release $Rel newer than the installed core $Cur? A prerelease (2.1.0-rc.1)
# is older than its release (2.1.0); a newer local build is left alone.
function _Huginn-CoreNewer {
  param([string]$Cur, [string]$Rel)
  if (-not $Cur) { return $true }
  if ($Cur -eq $Rel) { return $false }
  $base = ($Cur -split '-', 2)[0]
  try { $b = [version]$base; $r = [version]$Rel } catch { return $true }
  if ($b -eq $r) { return ($base -ne $Cur) }
  return ($r -gt $b)
}

# Bring the installed core up to the newest release. Its self-install restarts
# the running core onto it and adds the tray to a pre-tray (2.0.0) install.
function _Huginn-CoreUpdate {
  $bin = _Huginn-CoreBin
  if (-not $bin) { _Huginn-Fail "huginn core: not installed here - huginn core install" }
  $cur = ''
  if (((& $bin version 2>$null) -join '') -match '^\(o> huginn (\S+) ') { $cur = $Matches[1] }
  if (-not $env:HUGINN_CORE_FILE) {
    $rel = _Huginn-CoreRelease
    if (-not $rel) { _Huginn-UiLine 'warn' 'core' "could not read the release feed (offline or rate-limited); $cur kept"; return }
    $latest = $rel.tag -replace '^cli-v', ''
    if (-not (_Huginn-CoreNewer $cur $latest)) {
      $run = ''
      if (((& $bin core status 2>$null) -join "`n") -match '\(o> huginn core (\S+) ') { $run = $Matches[1] }
      if ($run -and $run -ne $cur) {
        # Installed but not running yet (an earlier restart could not be made).
        $log = Join-Path ([IO.Path]::GetTempPath()) ("huginn-restart-" + [guid]::NewGuid().ToString('N') + '.log')
        $rc = _Huginn-UiSpin 'core' "restarting $run onto $cur" $bin @('core', 'self-install') $log
        if ($rc -eq 0) { _Huginn-UiLine 'ok' 'core' "$cur   restarted (it was still running $run)" }
        else { _Huginn-UiLine 'fail' 'core' 'the restart failed:'; Get-Content $log -Encoding UTF8 | ForEach-Object { Write-Host "            $_" } }
        Remove-Item $log -Force -ErrorAction SilentlyContinue; return
      }
      _Huginn-UiLine 'ok' 'core' "$cur is current"; return
    }
  }
  $env:HUGINN_UI_FROM = $cur
  try { $null = _Huginn-CoreFetch } finally { Remove-Item Env:HUGINN_UI_FROM -ErrorAction SilentlyContinue }
}

# Pair the core with the host this shell already reaches over ssh, if it is not
# paired yet. The token travels ssh -> the pipeline -> stdin, never argv.
# -Say: report an existing pairing too (`huginn core install`'s line).
function _Huginn-CorePair {
  param([string]$Bin, [switch]$Say)
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  $st = (& $Bin core status 2>$null) -join "`n"
  if ($st -notmatch 'not paired') {
    if ($Say -and $st -match '(?m)^  huginn:\s+(\S+)') { _Huginn-UiLine 'ok' 'paired' $Matches[1] }
    return
  }
  # Can the token be fetched over ssh? When ssh does not answer (no port 22
  # from here, a key not trusted yet), the person pastes it instead: pairing
  # itself goes over huginn's own port and needs no ssh (run 7).
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { & ssh -T -o BatchMode=yes -o ConnectTimeout=8 $H true 2>&1 | Out-Null; $sshOk = ($LASTEXITCODE -eq 0) }
  finally { $ErrorActionPreference = $eap }
  if (-not $sshOk) { _Huginn-CorePairByToken $Bin $H; return }
  $srv = _Huginn-SrvUrl $H
  if (-not $srv) {
    _Huginn-UiLine 'fail' 'paired' "could not work out $H's daemon address"
    _Huginn-UiNote 'pair by hand: huginn core pair --url https://<host>:8787'
    _Huginn-Fail "huginn core: could not work out $H's daemon address"
  }
  if (_Huginn-UiTty) { _Huginn-UiLive (@(_Huginn-UiFrames)[0]) 'paired' "fetching the token over ssh and pairing with $srv" }
  # What the core prints is UTF-8; read as the console's OEM page, its ellipsis
  # came out as three box-drawing letters on PC01.
  $enc = [Console]::OutputEncoding
  try {
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    $out = @(((ssh -T $H 'cat /etc/huginn-appd/token') -join '') | & $Bin core pair --url $srv 2>&1)
    $rc = $LASTEXITCODE
  } finally { [Console]::OutputEncoding = $enc }
  _Huginn-UiClear
  if ($rc -eq 0) { _Huginn-UiLine 'ok' 'paired' (([string]$out[0]) -replace '^paired with ', '') }
  else {
    _Huginn-UiLine 'fail' 'paired' "could not pair with $srv"; $out | ForEach-Object { _Huginn-UiNote ([string]$_) }
    _Huginn-Fail "huginn core: pairing with $srv failed"
  }
}

# Pairs huginn core with a token the person pastes, at the address the ssh
# alias names (ssh -G reads the config without connecting) or the one the
# installer was given ($env:HUGINN_APPD_URL). Not echoed. Without a terminal
# to ask in, says the one command that does it.
function _Huginn-CorePairByToken([string]$Bin, [string]$H) {
  $srv = $env:HUGINN_APPD_URL
  if (-not $srv) {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $hn = @(& ssh -G $H 2>$null | Where-Object { $_ -match '^hostname ' }) | Select-Object -First 1 } finally { $ErrorActionPreference = $eap }
    $hn = ([string]$hn -replace '^hostname ', '').Trim()
    if ($hn -and $hn -ne $H) { $srv = "https://${hn}:8787" }
  }
  if (-not $srv) {
    _Huginn-UiLine 'warn' 'paired' "not yet: ssh to $H did not answer, and its address is not known here"
    _Huginn-UiNote 'pair with the token: huginn core pair --url https://<host>:8787'
    return
  }
  if (-not ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected)) {
    _Huginn-UiLine 'warn' 'paired' "not yet: ssh to $H did not answer - pair with the token: huginn core pair --url $srv"
    return
  }
  _Huginn-UiLine 'warn' 'paired' "ssh to $H did not answer, so the token cannot be fetched from the host"
  _Huginn-UiNote 'huginn core can pair with it pasted here instead (on the host: sudo cat /etc/huginn-appd/token).'
  $sec = Read-Host "    huginn's token for $srv (not shown; Enter to skip)" -AsSecureString
  $tok = ([System.Net.NetworkCredential]::new('', $sec).Password).Trim()
  if (-not $tok) { _Huginn-UiLine 'skip' 'paired' "not yet - huginn core pair --url $srv, any time"; return }
  $enc = [Console]::OutputEncoding
  try {
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    $out = @($tok | & $Bin core pair --url $srv 2>&1)
    $rc = $LASTEXITCODE
  } finally { [Console]::OutputEncoding = $enc; $tok = $null }
  if ($rc -eq 0) { _Huginn-UiLine 'ok' 'paired' (([string]$out[0]) -replace '^paired with ', '') }
  else {
    _Huginn-UiLine 'fail' 'paired' "could not pair with $srv"; $out | ForEach-Object { _Huginn-UiNote ([string]$_) }
    _Huginn-UiNote "try again: huginn core pair --url $srv"
  }
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
          _Huginn-Say "`nhuginn: $H is failing immediately ($quick attempts, last exit $rc) - giving up.`n  The remote error is printed above; fix it or run 'huginn $Session' again." Red
          return
        }
      } else { $quick = 0 }
      _Huginn-Say "`nhuginn: link to $H dropped (ssh exit $rc) - reconnecting in ${delay}s (Ctrl-C to stop)..." Yellow
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

# --- uninstall -------------------------------------------------------------
# `huginn uninstall` - put this machine back the way install.ps1 found it.
#
# THE ORDER IS THE POINT, and it is huginn-device's: THE SERVER FIRST, THE DISK
# SECOND. Every enrolment this machine holds can only be retired with a token
# that is about to be deleted, so each unenrol is attempted while its own
# credentials still exist. Wipe first and those rows are unremovable from here
# forever - they sit in `huginn devices` reading "not reachable" and go on being
# offered work by a machine that no longer exists.
#
# AND AN UNINSTALLER DOES NOT GET A SECOND RUN, so a failed unenrol does not
# stop it: the local files go anyway (`off --force`), and the row that was
# stranded is named - by the runner, and again in the summary. That is the one
# place the refuse-to-destroy-the-handle rule is deliberately inverted, because
# "run it again tomorrow" is advice to somebody who will not be here tomorrow.
#
# WHAT IT LEAVES ON PURPOSE: the SSH key and the `Host huginn` stanza.
# install.ps1 only CREATES a key when there is not one already, and afterwards
# nothing can tell "the key install.ps1 generated" from "the key you have used
# for five years" - id_ed25519 is the default name for both, and the wrong guess
# locks somebody out of every host they have. So they stay, with a note. `--all`
# takes them, and only then, when the key is huginn-specific by FILENAME or by
# the comment in its .pub. Never by guess.
#
# Kept behaviourally identical to huginn.sh's _huginn_uninstall, verb for verb
# and message for message: the two clients have already drifted over a single
# version constant, and this one deletes things.
function _Huginn-Uninstall {
  param([string[]]$Rest = @())
  $all = $false; $yes = $false
  foreach ($a in $Rest) {
    if     ($a -eq '--all') { $all = $true }
    elseif ($a -eq '--yes') { $yes = $true }
    else { _Huginn-Fail "usage: huginn uninstall [--all] [--yes]" }
  }
  # With a core installed, the CORE owns the uninstall: the whole stack, top
  # down - the desktop app, the local tier, the device, the core, this CLI
  # (owner rule 2026-10-15: the core includes the CLI; the desktop sits on it).
  # Everything below remains for a machine with no core.
  $core = _Huginn-CoreBin
  if ($core) { & $core uninstall @Rest; return }
  $hdir = Join-Path $HOME '.huginn'
  # Both managers derive these from the same variables, so there is nothing to
  # pass down - and nothing is written into $env: here, which would outlive the
  # command and quietly repoint the next `huginn local`.
  $ddir = if ($env:HUGINN_DEVICE_DIR) { $env:HUGINN_DEVICE_DIR } else { Join-Path $HOME '.config\huginn' }
  # The literal fallback is huginn-local's own (localDir()), copied rather than
  # improved on: with %ProgramData% unset, Join-Path is handed a null and THROWS,
  # which took out the whole local-tier step in testing. The two must agree about
  # where the tier lives or this deletes the wrong nothing.
  $ldir = if ($env:HUGINN_LOCAL_DIR) { $env:HUGINN_LOCAL_DIR }
          elseif ($env:ProgramData)  { Join-Path $env:ProgramData 'huginn-local' }
          else                       { 'C:\ProgramData\huginn-local' }
  $cfg  = Join-Path $HOME '.ssh\config'
  # The EXACT line install.ps1 appends, single-quoted so $HOME stays literal the
  # way the installer wrote it. Matched whole-line, so a profile somebody
  # hand-wrote differently is reported rather than rewritten.
  $line = 'if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }'
  $havenode = [bool](Get-Command node -ErrorAction SilentlyContinue)
  $removed = @(); $left = @()

  Write-Host "huginn uninstall removes, from THIS machine:"
  Write-Host "  $hdir"
  Write-Host "      the client, the device runner, the local-AI manager"
  Write-Host "  $ddir"
  Write-Host "      this machine's device enrolment and its copy of the appd token"
  Write-Host "  $ldir"
  Write-Host "      the local-AI tier: models, sessions, runtime (can be several GB)"
  Write-Host "  the huginn line in $PROFILE"
  Write-Host ""
  Write-Host "It unenrols this machine from huginn FIRST, while the tokens still exist."
  if ($all) { Write-Host "--all: the 'Host huginn' SSH stanza goes too, and its key IF it is huginn's own." }
  Write-Host ""
  if (-not $yes) {
    $answer = Read-Host 'Type "uninstall" to continue'
    if ($answer -ne 'uninstall') { Write-Host "Nothing was removed."; return }
    Write-Host ""
  }

  # 1. The local tier first: it owns a device row of its own (<host>-llm), two
  #    services and the heaviest files, and its manager lives in $hdir - which
  #    step 3 is about to delete, so this cannot be reordered after it.
  if (Test-Path $ldir) {
    Write-Host "==> local AI tier"
    $mgr = Join-Path $hdir 'huginn-local'
    if ($havenode -and (Test-Path $mgr)) {
      node $mgr off --purge --yes
      # Deliberately NOT forced. A failed unenrol here keeps the id that can
      # still retire the row, and what is left behind is models - which the
      # summary names, with the command that finishes the job.
      if ($LASTEXITCODE -eq 0) { $removed += $ldir }
      else { $left += "$ldir - 'huginn local off --purge' did not finish; run it again when huginn is reachable" }
    } else {
      $left += "$ldir - no manager or no node here, so nothing could unenrol or remove it"
    }
    Write-Host ""
  }

  # 2. This machine as a device.
  if (Test-Path $ddir) {
    Write-Host "==> device enrolment"
    $runner = Join-Path $hdir 'huginn-device'
    if ($havenode -and (Test-Path $runner)) {
      node $runner off
      if ($LASTEXITCODE -ne 0) {
        node $runner off --force
        $left += "a device row on huginn (named above) is still enrolled - retire it from the host"
      }
    } else {
      $left += "this machine may still be enrolled - no runner or no node here to unenrol it"
    }
    # Named files only, and after the attempt above: a dir that was never
    # enrolled can still hold the token `huginn device on` fetched into it.
    foreach ($f in 'device.json', 'appd-token') {
      Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $ddir $f)
    }
    if ((Test-Path $ddir) -and -not (Get-ChildItem -Force $ddir -ErrorAction SilentlyContinue)) {
      Remove-Item -Force -ErrorAction SilentlyContinue $ddir
    }
    # Only report what actually went: the dir may still hold files this uninstall
    # does not own, or the Remove-Item may have failed silently, and claiming a
    # removal that did not happen sends the user looking in the wrong place.
    if (-not (Test-Path $ddir)) { $removed += $ddir }
    Write-Host ""
  }

  # 3. The client itself. The whole directory: install.ps1 created it and
  #    everything in it is huginn's - unlike the desktop app's uninstaller,
  #    which shares this directory and therefore names its files one by one.
  if (Test-Path $hdir) {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $hdir
    $removed += $hdir
  }

  # 4. The profile line.
  if ($PROFILE -and (Test-Path $PROFILE)) {
    $plines = @(Get-Content $PROFILE)
    if ($plines -contains $line) {
      # UTF8 out, not Set-Content's 5.1 default (the system ANSI code page):
      # $PROFILE is not one of the two files the pure-ASCII rule covers - nothing
      # ever fetches it over scp - and re-encoding somebody's profile is how a
      # non-English string already in it turns to mojibake.
      Set-Content -Path $PROFILE -Value ($plines | Where-Object { $_ -ne $line }) -Encoding UTF8
      $removed += "the huginn line in $PROFILE"
    } elseif ($plines -match '\.huginn\\huginn\.ps1') {
      $left += "a hand-edited '.huginn\huginn.ps1' line in $PROFILE - it is not the one the installer wrote, so it was left"
    }
  }

  # 5. SSH. Left by default; see the note on this function.
  $key = ''; $slines = @()
  if (Test-Path $cfg) {
    $slines = @(Get-Content $cfg)
    $inH = $false
    foreach ($l in $slines) {
      $t = $l.Trim()
      # -match is case-insensitive in PowerShell, which is also how ssh reads
      # these keywords.
      # A stanza ends where ssh ends it: at the next Host OR Match line (a value
      # follows spaces or an '=').
      if ($t -match '^(host|match)(\s*=\s*|\s+)(.*)$') { $inH = (($matches[1] -eq 'host') -and ($matches[3].Trim() -eq 'huginn')); continue }
      # The installers write the key path in double quotes (a profile path with a
      # space in it is otherwise a fatal line to ssh): a quoted value is read whole.
      if ($inH -and ($t -match '^identityfile\s+(.+)$')) { $key = $matches[1].Trim() -replace '^"([^"]*)".*$', '$1'; break }
    }
  }
  $ours = $false
  if ($key) {
    if ((Split-Path -Leaf $key) -like '*huginn*') { $ours = $true }
    elseif ((Test-Path "$key.pub") -and ((Get-Content "$key.pub" -Raw) -match 'huginn')) { $ours = $true }
  }
  if ($all -and (Test-Path $cfg)) {
    # Only a stanza that is EXACTLY `Host huginn`. `Host huginn build01` serves
    # another alias too, and taking it out would break a host this never
    # installed.
    # It ends where ssh ends it, at the next Host OR Match line: the installers
    # append it at the end of the file, so whatever the person adds later
    # follows it, and ending it at Host alone took a Match block (for other
    # hosts entirely) with it. A comment after its last option is kept for the
    # same reason: it heads what follows.
    $keep = @(); $held = @(); $drop = $false
    foreach ($l in $slines) {
      $t = $l.Trim()
      if ($t -match '^(host|match)(\s*=\s*|\s+)(.*)$') {
        $keep += $held; $held = @()
        $drop = (($matches[1] -eq 'host') -and ($matches[3].Trim() -eq 'huginn'))
      }
      if (-not $drop) { $keep += $l; continue }
      if ($t.StartsWith('#')) { $held += $l; continue }
      if ($t -ne '') { $held = @() }
    }
    $keep += $held
    # ascii, and this one IS the encoding that matters: a BOM on the first line
    # of ~/.ssh/config is a keyword OpenSSH does not recognise.
    Set-Content -Path $cfg -Value $keep -Encoding ascii
    $removed += "the 'Host huginn' stanza in $cfg"
    if ($ours -and (Test-Path $key)) {
      Remove-Item -Force -ErrorAction SilentlyContinue $key, "$key.pub"
      $removed += "$key and $key.pub (huginn's own key)"
    } elseif ($key) {
      $left += "$key - NOT removed: nothing marks it as huginn's (no 'huginn' in the filename or the .pub comment), and it is very likely your general SSH key"
    }
  } elseif ($key) {
    $left += "$key and the 'Host huginn' stanza in $cfg - kept (use 'huginn uninstall --all' to remove them)"
  }

  Write-Host "Removed:"
  if ($removed.Count -eq 0) { Write-Host "  (nothing - was huginn installed here?)" }
  foreach ($r in $removed) { Write-Host "  $r" }
  if ($left.Count -gt 0) {
    Write-Host ""
    Write-Host "Left behind, on purpose or because it could not be done:"
    foreach ($r in $left) { Write-Host "  $r" }
  }
  Write-Host ""
  # The function is still defined in THIS session - the file it came from is
  # gone, but PowerShell does not forget what it has already dot-sourced.
  _Huginn-Say "The 'huginn' command is still loaded in this session. Open a new PowerShell, or: Remove-Item Function:huginn" Yellow
}

# --- help -----------------------------------------------------------------
# The ONE list of verbs, and the ONE place each one is described. `huginn help`
# prints it whole; `huginn <verb> --help` prints the entry (and its continuation
# lines) for that verb alone - see _Huginn-VerbHelp, which reads this rather than
# carrying a second copy that would drift from it.
#
# Expandable here-string, for $script:HUGINN_REPO and the pinned mirror name;
# every literal $ is backtick-escaped.
function _Huginn-HelpBody {
  # ---8<--- BEGIN GENERATED by scripts/gen-help.js from client/help-table.txt - DO NOT EDIT
  return @"

  huginn                      the console: sessions, devices, usage and the
                              core, with menus (in a terminal; set
                              HUGINN_CONSOLE=0, or pipe it, for 'main')
  huginn console              the console, by name
  huginn setup ssh            SSH to the huginn host (key + 'Host huginn'),
                              for attaching and the session verbs, on a
                              machine set up from the desktop or a package
  huginn main                 attach/create the live 'main' session
                              (Claude Code starts in a new one)
  huginn <name>               a separate named session
  huginn attach <name>        that session even when its name is a verb
                              (update, solo...)
  huginn solo [name]          attach + detach all OTHER clients
                              (resume solo / full screen)
  huginn list | ls            list sessions + attach status
  huginn status | st          health: uptime, auth, sessions, disk
  huginn rename <old> <new>   rename a session (alias: mv)
  huginn end <name> [--force] soft end: ask Claude to wrap up + commit, then
                              (if auto-end is on) end it once it goes idle
                              (--force: send it into a pane with no Claude
                              state)
  huginn rounds               what this host does on a schedule, and what it
                              found
  huginn headroom [--json]    usage left per account, what huginn moved or is
                              holding, and why
  huginn devices              machines that can run a chat in their own context
  huginn devices watch <name> on|off
                              tell the host this machine is always on: it
                              messages you when the machine stops checking in,
                              and when it is back
  huginn projects [--json]    clusters of sessions with roles, and who is
                              waiting
  huginn projects show <name> a project's members, one line each
  huginn projects new <name>  start one (launches its lead session)
                              --brief "<the lead's whole first message>"|-
                              [--kind software|infra|hardware|docs|research|
                              other] [--cwd DIR]
  huginn projects spawn <project>
                              approve the lead's proposal: create its members
  huginn projects msg <project> <from> <to> <text>
                              one line from one member to another
  huginn projects end <project>
                              end it AND wind its sessions down
                              [--now: end them outright | --keep-sessions:
                              leave them running]
  huginn core install         run huginn core at boot on this machine - as you,
                              with nobody signed in - and pair it with the host
                              [--no-tray | --tray: even from an ssh shell
                              (Linux) | --logon s4u|password: Windows only]
  huginn core status          is the core running and paired, what it offers
  huginn core start           start it now if it is set up but not running
  huginn core pair --url URL  pair it with the server at URL
                              (https://<server>:8787); asks for the token
  huginn core update          take the newest core release (restarts the core)
  huginn core route           the addresses huginn core reaches huginn on, and
                              their health [add <url> | remove <url> |
                              pin <url>|none | auto on|off]
  huginn core uninstall       stop starting it at boot
  huginn device [status]      what THIS machine offers huginn, and what huginn
                              sees
  huginn device on            offer this machine
                              [--scope look|work|own] [--root DIR]
                              [--name NAME: how it is listed]
                              [--act-while-locked | --no-act-while-locked:
                              whether a lock screen withdraws 'act' on this
                              machine (default: it does)]
  huginn device off           stop offering it
  huginn device update        fetch a newer runner from the newest release
  huginn device serve         the runner itself - what the service starts
  huginn device unit          print what keeps the runner up: a systemd unit
                              [--system], or on Windows a scheduled task that
                              runs it as you with nobody signed in
  huginn llm "prompt"         one question to the local tier (a serving
                              machine answers, not Claude)
                              [--model ROW] [--timeout S];
                              'huginn llm -' reads stdin
  huginn local [status]       what THIS machine serves as local AI, if anything
  huginn local on             serve local models from this machine
                              (optional, ~5 GB)
  huginn local plan           what 'on' would install here, without installing
                              anything
  huginn local persist        make an existing install survive logout
                              (system services, or linger)
  huginn local doctor         exits nonzero when this install is not doing
                              what it says
  huginn local update         re-fetch the manager and the pinned runtime
  huginn local unit           print the service definitions this tier installs
                              [--system]
  huginn local off            stop serving  [--purge-models] [--purge]
  huginn kill <name>          hard end: stop the session now
  huginn archive <name>       end it for good and keep the way back: the title,
                              the cwd, a copy of the transcript and the exact
                              'claude --resume' command  [--now: skip wrap-up]
  huginn archive              what has been archived, and how to bring it back
  huginn revive <id|name>     bring an archived session back to life
  huginn -p "question"        one-shot headless query: reasoning, reading and
                              the web, and nothing that can change anything
                              (Bash/Edit/Write are denied)
  huginn -y "task"            one-shot that may use tools (bash, files, web)
  huginn usage [args]         Claude Code token/cost report (ccusage; default:
                              daily), e.g. huginn usage monthly | session |
                              blocks | blocks --live
  huginn usage <when>         shortcut date range: today | yesterday | week |
                              month, e.g. huginn usage today |
                              huginn usage week session
  huginn desktop              download links for the latest Huginn Desktop
                              build (Windows, Debian, Arch, portable)
  huginn desktop win|linux|arch|portable
                              just that build's url, bare, for scripting
  huginn desktop install      install or repair the desktop app: the
                              Windows installer, or on Linux the per-user,
                              self-updating build (no root; any distro)
  huginn desktop uninstall    Linux: remove that install (keeps settings)
  huginn desktop update       update the installed desktop app to the newest
                              release (Windows, the per-user Linux app, or
                              the Debian/Arch package with sudo)
  huginn update               update everything huginn on this machine: this
                              client, huginn core, the desktop app and the
                              local-AI tier (whichever are installed), from
                              the newest release of $script:HUGINN_REPO.
                              Not from `$env:HUGINN_HOST$(if ($env:HUGINN_HOST) { ", which is $env:HUGINN_HOST here" }):
                              the box you drive is not a source of code this
                              shell runs (`$env:HUGINN_UPDATE_HOST='host'
                              names a mirror on purpose)
  huginn uninstall            remove the whole stack, top down: the desktop
                              app, the local-AI tier, this machine's device
                              row, huginn core and this CLI [--all also takes
                              the SSH stanza + huginn's own key; --yes: do
                              not ask first]
  huginn version              show client version
  huginn help | ? | /help     this help

  Any verb takes --help for its own usage, answered here without touching the
  host: 'huginn archive --help', 'huginn device --help'.
  Session names are lowercase letters, digits, '_' and '-' (no dots, spaces
  or *), up to 50 characters, and must start with a letter, digit or '_'.
  Case-insensitive ('Test' and 'test' are the same session).
  In a session: claude / claude --resume.  Detach: Alt-d (or Ctrl-b d).
  Alt-o = detach all OTHER clients (full screen).  Ctrl-b [ = scroll.
  Reattach from any device.
  Host via the 'huginn' SSH alias; override with `$env:HUGINN_HOST.
  Attach auto-reconnects after a dropped link (laptop sleep); Ctrl-C during
  the wait to stop. Disable with `$env:HUGINN_NO_RECONNECT='1'.
  The terminal tab is named after the session (<name>) and the title that was
  there before is put back when you leave; `$env:HUGINN_NO_TITLE='1' off.
  A state mark leads the tab title while Claude runs: working / needs you /
  waiting (set host-side by the claude hooks; needs the server's title hook
  installed).
"@
  # ---8<--- END GENERATED help body
}

# `huginn attach <name>`: attach to a session BY NAME, never by verb.
#
# ⚠ WHY IT EXISTS (desktop breaker, 2026-10-02): `huginn <word>` reads the word
# as a verb first and a session only when it is not one, and the daemon makes
# sessions called update, solo, list, uninstall. The desktop's "Open in
# PowerShell" ran `huginn 'update'` for a session named update - replacing this
# very file - and `huginn 'solo'` threw every other device off main. This is the
# form that cannot dispatch. The desktop checks for THIS FUNCTION'S NAME
# (`Get-Command _Huginn-AttachNamed`) to know a CLI has `attach`, so it is not
# to be renamed.
function _Huginn-AttachNamed {
  param([string]$H, [string]$Name)
  if (-not (_Huginn-ValidName $Name)) { _Huginn-BadName $Name }
  _Huginn-Attach -H $H -Session $Name
}

# `huginn <verb> --help` - ONE convention, and it used to be four. `projects`
# and `llm` printed their full help and exited 0; `headroom` printed a one-line
# usage and exited 1; `archive` answered "invalid session name '--help'". A
# person types --help when something has already surprised them, and two of
# those made it look like a second mistake.
#
# ANSWERED HERE, NEVER OVER SSH: help that needs the network is help you cannot
# read on a train, and it is not an error, so nothing is thrown and
# $LASTEXITCODE stays 0.
#
# $true when the verb had an entry; $false when it had none, so the caller can
# fall through to its normal dispatch.
function _Huginn-VerbHelp {
  param([string]$Verb)
  # Aliases resolve to the spelling the help list uses, so `huginn st --help`
  # and `huginn status --help` are the same answer.
  $v = switch ($Verb) {
    'ls'        { 'list' }
    'st'        { 'status' }
    'mv'        { 'rename' }
    'round'     { 'rounds' }
    'project'   { 'projects' }
    'unarchive' { 'revive' }
    'cost'      { 'usage' }
    'ccusage'   { 'usage' }
    'enrol'     { 'device' }
    'enroll'    { 'device' }
    default     { $Verb }
  }
  $keep = $false
  $hit  = @()
  foreach ($line in ((_Huginn-HelpBody) -split "`n")) {
    $l = $line.TrimEnd()
    if     ($l -match '^\s*$')        { $keep = $false }
    elseif ($l -match '^  huginn(\s|$)') { $keep = (($l -split '\s+')[2] -ceq $v) }
    elseif ($l -match '^  \S')         { $keep = $false }
    if ($keep) { $hit += $l }
  }
  if (-not $hit.Count) { return $false }
  Write-Host ($hit -join "`n")
  Write-Host ""
  Write-Host "  (huginn help for everything else)"
  return $true
}

function huginn {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  # ⚠ THE NUMBER IS RESET AT THE DOOR. A refusal sets $LASTEXITCODE = 1 (see
  # _Huginn-Fail) and nothing ever cleared it, so the NEXT verb - a perfectly
  # successful one - still left a 1 behind for a script to read as its own
  # failure. Native commands this function runs (ssh, scp, node) overwrite it
  # with their own code, which is exactly what a caller wants.
  $global:LASTEXITCODE = 0
  $script:HuginnColorOn = -not $env:NO_COLOR -and -not [Console]::IsOutputRedirected
  # --help on a VERB, before anything is dispatched or sent: one convention for
  # all of them, answered locally, no throw and no exit code. See
  # _Huginn-VerbHelp. A verb with no entry is told so rather than falling
  # through to the attach path, which would ssh to the host and open a session
  # named after the typo while the person is standing there asking to be told
  # something.
  if ($args.Count -ge 2 -and ($args[1] -in '-h','--help')) {
    if (_Huginn-VerbHelp ([string]$args[0])) { return }
    _Huginn-Fail "huginn: no help for '$($args[0])' - run 'huginn help' for the list of verbs"
  }
  if ($args.Count -eq 0) {
    # In a terminal a person is at, plain `huginn` is the console (the core
    # draws it). Redirected, HUGINN_CONSOLE=0, or a core too old to answer
    # --probe: attach to main, as it always did.
    $core = _Huginn-CoreBin
    $tty = -not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected
    if ($tty -and $env:HUGINN_CONSOLE -ne '0' -and $core) { & $core console --probe *> $null; $probe = $LASTEXITCODE } else { $probe = 1 }
    if ($probe -eq 0) { & $core console } else { _Huginn-Attach -H $H }
  } elseif ($args[0] -eq 'setup') {
    # The SSH side of the install, for a machine that got huginn from the
    # desktop installer: the core does it (huginn setup ssh).
    $core = _Huginn-CoreBin
    if ($core) { & $core setup @($args | Select-Object -Skip 1) }
    else { _Huginn-Fail "huginn setup needs huginn core: install it with huginn core install (or the one-liner in the README)" }
  } elseif ($args[0] -eq 'console') {
    $core = _Huginn-CoreBin
    if ($core) { & $core console --probe *> $null }
    if ($core -and $LASTEXITCODE -eq 0) { & $core console @($args | Select-Object -Skip 1) }
    else { _Huginn-Fail "huginn console needs huginn core 2.3.0 or newer: huginn core install (or huginn core update)" }
  } elseif ($args[0] -in '?','help','/help','-h','--help') {
    # Banner in a SINGLE-quoted here-string: the art must reach the terminal
    # verbatim, and in an expandable one PowerShell would treat its punctuation
    # as escapes. The body (see _Huginn-HelpBody) stays expandable.
    Write-Host @'

        _
       (o)==-   huginn - remote Claude Code node.  aliases: rclaude, rcc
       //\
    =~/_/
'@
    Write-Host (_Huginn-HelpBody)
  } elseif ($args[0] -eq 'version' -or $args[0] -eq '--version' -or $args[0] -eq '-v') {
    Write-Host "(o> huginn-cli $script:HUGINN_VERSION  (host: $H)"
  } elseif ($args[0] -eq 'update') {
    $dest = if ($PSCommandPath) { $PSCommandPath } else { "$HOME\.huginn\huginn.ps1" }
    $tmp  = "$dest.tmp"
    $got  = $false; $src = ''
    $t0 = [Diagnostics.Stopwatch]::StartNew()
    _Huginn-UiHead 'huginn update'
    if ($env:HUGINN_UPDATE_HOST) { _Huginn-UiLine 'warn' 'client' "HUGINN_UPDATE_HOST is set: trusting $($env:HUGINN_UPDATE_HOST) for this client's code" }
    $why = ''
    if (_Huginn-UiTty) { _Huginn-UiLive (@(_Huginn-UiFrames))[0] 'client' 'fetching' }
    $from = _Huginn-FetchClient -File 'huginn.ps1' -Dest $tmp -Why ([ref]$why)
    _Huginn-UiClear
    if ($from -and (Get-Item $tmp).Length -gt 2000) { $got = $true; $src = "from $from" }
    if ($got) {
      try {
        $raw = Get-Content $tmp -Raw
        $null = [ScriptBlock]::Create($raw)
        $newv = ''; if ($raw -match "script:HUGINN_VERSION = '([^']+)'") { $newv = $Matches[1] }
        if (Test-Path $dest) { Copy-Item -Force $dest "$dest.bak" -ErrorAction SilentlyContinue }
        Move-Item -Force $tmp $dest
        if ($newv -eq $script:HUGINN_VERSION) { _Huginn-UiLine 'ok' 'client' "$($script:HUGINN_VERSION) is current" }
        else { _Huginn-UiLine 'ok' 'client' "$($script:HUGINN_VERSION) -> $newv   $src (new windows load it; this one: . `$PROFILE)" }
      } catch {
        _Huginn-UiLine 'fail' 'client' "the downloaded client failed its syntax check - kept $($script:HUGINN_VERSION)"
        $got = $false
      }
    }
    if (Test-Path $tmp) { Remove-Item -Force $tmp -ErrorAction SilentlyContinue }
    if (-not $got) { _Huginn-Fail "huginn: update failed ($(if ($why) { $why } else { 'the download was empty' }))" }
    # Every huginn component on this machine follows, not only the CLI (owner,
    # 2026-10-16): run by the NEW client, in a child shell, since this session
    # still holds the old one.
    $chain = @("`$env:HUGINN_UI_COMPACT = '1'")
    if (_Huginn-CoreBin) { $chain += 'huginn core update' } else { $chain += "_Huginn-UiLine 'skip' 'core' 'not installed (huginn core install)'" }
    $chain += 'huginn desktop update'
    & (Get-Process -Id $PID).Path -NoProfile -ExecutionPolicy Bypass -Command (". '$($dest -replace "'", "''")'; " + ($chain -join '; '))
    $ld = if ($env:HUGINN_LOCAL_DIR) { $env:HUGINN_LOCAL_DIR } elseif ($env:ProgramData) { Join-Path $env:ProgramData 'huginn-local' } else { 'C:\ProgramData\huginn-local' }
    if (Test-Path $ld) { _Huginn-UiLine 'warn' 'local AI' "update it with 'huginn local update' (it asks for administrator approval)" }
    else { _Huginn-UiLine 'skip' 'local AI' 'not installed' }
    _Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray
  } elseif ($args[0] -eq 'list' -or $args[0] -eq 'ls') {
    ssh -T $H "tmux ls 2>/dev/null || echo '(no sessions running)'"
  } elseif ($args[0] -eq 'status' -or $args[0] -eq 'st') {
    ssh -T $H huginn-status
  } elseif ($args[0] -eq 'rounds' -or $args[0] -eq 'round') {
    # Same host-side renderer the bash client calls; see huginn.sh.
    ssh -T $H huginn-rounds
  } elseif ($args[0] -eq 'headroom') {
    # Host-side like rounds/devices, and for a third reason on top of theirs:
    # headroom is read out of the daemon with the bearer token, so rendering it
    # anywhere but on the host would mean teaching this machine that credential.
    # Flags are single-quote marshalled like the llm branch below - what follows
    # the host name is parsed by a shell on the far side, so an argument typed
    # here is remote shell input. -T, not -tt: the attach paths force a PTY
    # because tmux needs one, but a tty on the far end would turn this
    # renderer's newlines into CRLF on their way into a pipe or a variable.
    $hrArgs = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }
    if ($hrArgs.Count) {
      $hrStr = ($hrArgs | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
      ssh -T $H "huginn-headroom $hrStr"
    } else {
      ssh -T $H huginn-headroom
    }
  } elseif ($args[0] -eq 'devices') {
    # Arguments go through like headroom's: 'huginn devices watch <name> on|off'
    # is parsed by the renderer on the host, so each one is remote shell input.
    $dvArgs = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }
    if ($dvArgs.Count) {
      $dvStr = ($dvArgs | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
      ssh -T $H "huginn-devices $dvStr"
    } else {
      ssh -T $H huginn-devices
    }
  } elseif ($args[0] -eq 'projects' -or $args[0] -eq 'project') {
    # A PROJECT is a cluster of sessions with roles and a lead that sizes the
    # work. Same host-side renderer the bash client calls (see huginn.sh): one
    # implementation of the whole grammar - list/show/new/spawn/msg/end - rather
    # than the same grammar written twice, in two languages, and drifting.
    #
    # Single-quote marshalled like the headroom/archive branches: what follows
    # the host name is parsed by a shell on the far side, so an argument typed
    # here is remote shell input, and a first prompt is prose full of quotes and
    # $. Guarded on the count so a bare `huginn projects` sends a BARE renderer
    # call and not one empty argument. -T, not -tt: this output is piped and
    # captured, and a tty on the far end would turn its newlines into CRLF.
    $pjArgs = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }
    if ($pjArgs.Count) {
      $pjStr = ($pjArgs | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
      # A brief or a message is prose, double quotes and all: see _Huginn-SshLine.
      _Huginn-SshLine -H $H -Line "huginn-projects $pjStr"
    } else {
      ssh -T $H huginn-projects
    }
  } elseif ($args[0] -eq 'core') {
    $sub = if ($args.Count -ge 2) { [string]$args[1] } else { 'status' }
    $rest = if ($args.Count -ge 3) { $args[2..($args.Count-1)] } else { @() }
    $bin = _Huginn-CoreBin
    if ($sub -eq 'update') {
      _Huginn-CoreUpdate
    } elseif ($sub -eq 'install') {
      # One block, as `huginn update` draws it: the download (or the update of
      # the core already here), the core's own lines, then pairing.
      $t0 = [Diagnostics.Stopwatch]::StartNew()
      $inBlock = [bool]($env:HUGINN_UI_COMPACT -or $env:HUGINN_SETUP)   # huginn setup draws the title and the end
      if (-not $inBlock) { _Huginn-UiHead 'huginn core install' }
      $env:HUGINN_UI_COMPACT = '1'
      try {
        if (-not $bin -or $env:HUGINN_CORE_FILE) { $bin = _Huginn-CoreFetch }
        else { _Huginn-CoreUpdate }  # an installed core is brought up to date first
      } finally { Remove-Item Env:HUGINN_UI_COMPACT -ErrorAction SilentlyContinue }
      # Registering a boot task needs an administrator token: re-run just that
      # step elevated, behind ONE UAC prompt, as this same user. Its window
      # closes when it ends, so it writes its lines to a report drawn here.
      $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
      $rep = $null
      if ($admin) {
        $env:HUGINN_UI_COMPACT = '1'
        try { & $bin core install @rest } finally { Remove-Item Env:HUGINN_UI_COMPACT -ErrorAction SilentlyContinue }
        if ($LASTEXITCODE -ne 0) { _Huginn-Fail 'huginn core install did not finish (the lines above say where it stopped)' }
      } else {
        $rep = Join-Path ([IO.Path]::GetTempPath()) ('huginn-install-' + [guid]::NewGuid().ToString('N') + '.txt')
        # Start-Process joins its arguments with spaces: the path is quoted.
        $argv = @('core', 'install', '--report', ('"' + $rep + '"')) + $rest
        # --logon password asks for the password in that window, so it shows.
        $style = if ($rest -contains 'password') { 'Normal' } else { 'Hidden' }
        try { $p = Start-Process -FilePath $bin -ArgumentList $argv -Verb RunAs -PassThru -WindowStyle $style }
        catch {
          _Huginn-UiLine 'fail' 'service' 'administrator approval was declined: no boot task was registered'
          _Huginn-Fail 'huginn core install: the boot task needs administrator approval - run it again and approve the prompt'
        }
        $null = $p.Handle
        _Huginn-UiWait $p 'service' 'registering the boot task (approve the administrator prompt)'
        $elevRc = $p.ExitCode
      }
      for ($i = 0; $i -lt 15; $i++) { & $bin core status *> $null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 1 }
      if ($LASTEXITCODE -ne 0) {
        # Windows queues every start of the boot task on battery (Energy Saver;
        # PC01, 2026-10-16: the desktop's setup then found no core).
        # `core start` - unelevated, so the core never runs as administrator -
        # starts it directly when the task is held back.
        $slog = Join-Path ([IO.Path]::GetTempPath()) ('huginn-core-start-' + [guid]::NewGuid().ToString('N') + '.log')
        $src = _Huginn-UiSpin 'core' 'starting huginn core (Windows held the boot task back)' $bin @('core', 'start') $slog
        if ($src -eq 0) { _Huginn-UiLine 'ok' 'core' 'running (started directly; the boot task takes over at the next boot)' }
        else { _Huginn-UiLine 'warn' 'core' 'not running yet - huginn core start'; @(Get-Content $slog -Encoding UTF8 -ErrorAction SilentlyContinue) | ForEach-Object { _Huginn-UiNote $_ } }
        Remove-Item $slog -Force -ErrorAction SilentlyContinue
      }
      if ($rep) {
        # The elevated step does not start the tray (it would run as
        # administrator); this unelevated self-install does, when none is up.
        $log = "$rep.log"; $started = ''
        $null = _Huginn-UiSpin 'tray' 'starting the tray' $bin @('core', 'self-install') $log
        if (@(Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue) -match 'started the tray') { $started = 'started now' }
        Remove-Item $log -Force -ErrorAction SilentlyContinue
        _Huginn-UiRecords $rep $started
        Remove-Item $rep -Force -ErrorAction SilentlyContinue
        if ($elevRc -ne 0) { _Huginn-Fail "huginn core install: the elevated step failed (exit $elevRc)" }
      }
      if ($env:HUGINN_CORE_NOPAIR) {
        # huginn setup: the host does not trust this computer yet, so there is
        # no ssh to fetch the token over; the person re-runs this after.
        if ($env:HUGINN_CORE_NOPAIR -eq 'window') { _Huginn-UiLine 'skip' 'paired' 'not yet: the Huginn window connects it (the address, then the token)' }
        else { _Huginn-UiLine 'skip' 'paired' "not yet: the host does not trust this computer's key - huginn core install pairs it once it does" }
      } else { _Huginn-CorePair $bin -Say }
      if (-not $inBlock) { _Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray }
    } elseif (-not $bin) {
      _Huginn-Fail "huginn core: not installed here - huginn core install"
    } else {
      & $bin core $sub @rest
    }
  } elseif ($args[0] -eq 'device' -and (_Huginn-CoreBin)) {
    # With huginn core installed, THIS machine's device is the core's: the Node
    # runner below is the pre-2.0 path and must not enrol a second row.
    $bin = _Huginn-CoreBin
    $sub = if ($args.Count -ge 2) { [string]$args[1] } else { 'status' }
    $rest = if ($args.Count -ge 3) { $args[2..($args.Count-1)] } else { @() }
    if ($sub -eq 'on' -or $sub -eq 'enrol' -or $sub -eq 'enroll') { _Huginn-CorePair $bin; & $bin device on @rest }
    elseif ($sub -eq 'off' -or $sub -eq 'status') { & $bin device $sub @rest }
    else { _Huginn-Fail "huginn device ${sub}: not needed with huginn core (see: huginn core status)" }
  } elseif ($args[0] -eq 'device') {
    # Plural is the host's list of machines; SINGULAR is the one you are typing
    # on - offering it to Huginn as a place to run work, the way the desktop
    # app's "Give Huginn access to this PC" toggle does. Different question, and
    # answered in a different place: `devices` renders on the host, this never
    # leaves the machine.
    #
    # The runner is a small Node program fetched on demand (client/huginn-device;
    # that file says why Node and not more shell). Node is free here: claude is
    # itself a Node program, so any machine that can do the work already has it.
    $sub = if ($args.Count -ge 2) { $args[1] } else { 'status' }
    $rest = if ($args.Count -ge 3) { $args[2..($args.Count-1)] } else { @() }
    $runner = Join-Path $HOME '.huginn/huginn-device'
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
      _Huginn-Fail "huginn device: needs node - install Node.js LTS: winget install OpenJS.NodeJS.LTS"
    } elseif ($sub -eq 'on' -or $sub -eq 'enrol' -or $sub -eq 'enroll' -or $sub -eq 'update') {
      $dir = Join-Path $HOME '.config/huginn'
      New-Item -ItemType Directory -Force -Path (Split-Path $runner) | Out-Null
      New-Item -ItemType Directory -Force -Path $dir | Out-Null
      _Huginn-OwnerOnly $dir -Directory   # off Windows: 0700, as the bash client makes it
      if ($sub -eq 'update' -or -not (Test-Path $runner)) {
        _Huginn-FetchFile -RepoPath 'client/huginn-device' -Dest $runner -Label 'huginn device' | Out-Null
      }
      if ($sub -eq 'update') {
        if (Test-Path $runner) { Write-Host "huginn device: runner is now $(node $runner version)" }
      } elseif (Test-Path $runner) {
        # The token and the address, both over the ssh link this machine has
        # ALREADY been trusted on. Nothing is widened: anyone who can ssh to the
        # host can read that file anyway. What it removes is a bearer token
        # pasted by hand between two terminals.
        $tokfile = Join-Path $dir 'appd-token'
        if (-not (Test-Path $tokfile)) {
          $tok = (ssh -T $H 'cat /etc/huginn-appd/token') -join ''
          if ($tok.Trim()) { _Huginn-WriteSecret $tokfile $tok.Trim() }
          else { _Huginn-Fail "huginn device: could not read the appd token from $H" }
        } else { _Huginn-OwnerOnly $tokfile }   # one an older run left at the umask
        # See _Huginn-SrvUrl: the address this machine just reached the host at,
        # bracketed when it is an IPv6 literal.
        # An explicit --url wins outright - it is the answer to this question,
        # not an extra opinion.
        if (_Huginn-HasUrl $rest) { node $runner on @rest }
        else {
          $srv = _Huginn-SrvUrl $H
          if ($srv) { node $runner on --url $srv @rest }
          else {
            _Huginn-Fail ("huginn device: could not work out how to reach $H's daemon`n" +
                          "               (no ssh address to go on, and $H named no global IPv4)`n" +
                          "               say it yourself: huginn device on --url https://<host>:8787")
          }
        }
      }
    } elseif (Test-Path $runner) {
      node $runner $sub @rest
    } else {
      _Huginn-Fail "huginn device: this machine is not set up as a device - run: huginn device on"
    }
  } elseif ($args[0] -eq 'llm') {
    # One question to the LOCAL TIER - a serving machine's model answers, never
    # Claude. Leading --flags pass as argv; the PROMPT rides base64 -> stdin
    # ("huginn-llm -"), the same hardening the exec path uses, so double quotes,
    # newlines, $ and backticks in the prompt survive both PowerShell and the
    # remote shell. Single-quote marshalling mangled double-quoted prompts on
    # Windows PowerShell 5.1.
    $rest = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }
    $flags = @(); $i = 0
    while ($i -lt $rest.Count -and ([string]$rest[$i]).StartsWith('--')) {
      $flags += $rest[$i]
      if (($rest[$i] -eq '--model' -or $rest[$i] -eq '--timeout') -and ($i + 1) -lt $rest.Count) {
        $i++; $flags += $rest[$i]
      }
      $i++
    }
    $prompt = if ($i -lt $rest.Count) { ($rest[$i..($rest.Count - 1)]) -join ' ' } else { '' }
    $flagStr = ($flags | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($prompt))
    ssh -T $H "echo $b64 | base64 -d | huginn-llm $flagStr -"
  } elseif ($args[0] -eq 'local') {
    # THIS machine serves local AI models to huginn - the optional local tier.
    # Same grammar as `huginn device`: consent, fetch pinned, validate, install,
    # enrol - and the same trust roots for the fetch. The manager carries its
    # own pinned runtime/model manifest; what it may install is decided by the
    # release you run, never by whatever an endpoint serves today.
    $sub = if ($args.Count -ge 2) { $args[1] } else { 'status' }
    $rest = if ($args.Count -ge 3) { $args[2..($args.Count-1)] } else { @() }
    $mgr = Join-Path $HOME '.huginn/huginn-local'
    # On Windows the tier is LocalSystem services, so these need an
    # administrator. From a normal terminal they asked for one to be opened by
    # hand ("re-run from an Administrator terminal"); now one approval prompt
    # runs the same command in an administrator window, which shows its
    # progress (downloads included) and stays open if it fails.
    $winAdminVerb = (@('on', 'off', 'update', 'persist') -contains $sub) -and
      ($env:OS -eq 'Windows_NT') -and -not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($winAdminVerb) {
      $q = @($rest | ForEach-Object { "'" + ([string]$_ -replace "'", "''") + "'" }) -join ' '
      $cmd = ". `"$HOME\.huginn\huginn.ps1`"; huginn local $sub $q; if (`$LASTEXITCODE) { Read-Host 'It did not finish (above says why). Press Enter to close' }; exit `$LASTEXITCODE"
      $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
      $psExe = (Get-Process -Id $PID).Path
      try { $p = Start-Process -FilePath $psExe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $enc -Verb RunAs -PassThru }
      catch { _Huginn-Fail "huginn local ${sub}: administrator approval was declined - nothing changed" }
      $null = $p.Handle
      _Huginn-UiWait $p 'local AI' "huginn local $sub, in its own administrator window"
      if ($p.ExitCode -eq 0) { _Huginn-UiLine 'ok' 'local AI' "huginn local $sub finished (huginn local status)" }
      else { _Huginn-UiLine 'fail' 'local AI' "huginn local $sub did not finish (exit $($p.ExitCode)); its window said why" }
      $global:LASTEXITCODE = $p.ExitCode
      return
    }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
      _Huginn-Fail "huginn local: needs node - install Node.js LTS: winget install OpenJS.NodeJS.LTS"
    } elseif ($sub -eq 'on' -or $sub -eq 'update' -or $sub -eq 'plan') {
      New-Item -ItemType Directory -Force -Path (Split-Path $mgr) | Out-Null
      # huginn-device rides along: managed mode installs a runner SERVICE, and
      # a machine that never enrolled as a claude device has no runner otherwise.
      foreach ($f in 'huginn-local', 'huginn-llm-shim', 'huginn-device') {
        $dest = Join-Path $HOME ".huginn/$f"
        if ($sub -eq 'update' -or -not (Test-Path $dest)) {
          # Throws when it could not fetch (see _Huginn-FetchFile).
          _Huginn-FetchFile -RepoPath "client/$f" -Dest $dest -Label 'huginn local' | Out-Null
        }
      }
      if ($sub -eq 'plan') { node $mgr plan @rest; return }
      if ($sub -eq 'update') { if (Test-Path $mgr) { node $mgr update @rest }; return }
      # Off Windows the tier lives where the manager and the bash client keep
      # it; 'C:\ProgramData\...' there was a relative path in the current directory.
      $posix = ($null -ne $IsWindows) -and -not $IsWindows
      $dir = if ($env:HUGINN_LOCAL_DIR) { $env:HUGINN_LOCAL_DIR }
             elseif ($posix)            { Join-Path $HOME '.config/huginn-local' }
             elseif ($env:ProgramData)  { Join-Path $env:ProgramData 'huginn-local' }
             else                       { 'C:\ProgramData\huginn-local' }
      # The appd token is root-equivalent on the host, so it is NOT written into
      # ProgramData here: that tree inherits BUILTIN\Users:read until
      # huginn-local's lockTree runs, and a token placed before it is readable by
      # every account on the PC for the whole run - downloads included - and
      # indefinitely if the run aborts before lockTree (2026-10-18, F2). Instead
      # it is staged owner-only in THIS user's profile and handed to the elevated
      # manager through HUGINN_SEED_TOKEN_FILE; the manager writes it into
      # device\ only AFTER lockTree, via a temp that already carries the locked
      # DACL, and shreds the stage. An install that already holds a token is left
      # for lockTree to relock.
      $tokfile = Join-Path $dir 'device/appd-token'
      $seed = $null
      if ($posix) {
        # pwsh on Linux or macOS does what the bash client does. There is no
        # ProgramData ACL to wait for, so the token goes straight into device/,
        # owner-only (0700 directories, a 0600 file); a stage in the profile was
        # 0644 there, and an elevated --system run never got to read it.
        New-Item -ItemType Directory -Force -Path (Join-Path $dir 'device') | Out-Null
        _Huginn-OwnerOnly $dir -Directory
        _Huginn-OwnerOnly (Join-Path $dir 'device') -Directory
        if (Test-Path $tokfile) { _Huginn-OwnerOnly $tokfile }
        else {
          $tok = ''
          $old = Join-Path $HOME '.config/huginn/appd-token'
          if (Test-Path $old) { $tok = Get-Content -Raw -Path $old }
          if (-not "$tok".Trim()) { $tok = (ssh -T $H 'cat /etc/huginn-appd/token') -join '' }
          if (-not "$tok".Trim()) { _Huginn-Fail "huginn local: could not read the appd token from $H" }
          _Huginn-WriteSecret $tokfile "$tok".Trim()
        }
      } elseif (-not (Test-Path $tokfile)) {
        $tok = ''
        $old = Join-Path $HOME '.config/huginn/appd-token'
        if (Test-Path $old) { $tok = Get-Content -Raw -Path $old }
        if (-not "$tok".Trim()) { $tok = (ssh -T $H 'cat /etc/huginn-appd/token') -join '' }
        if (-not "$tok".Trim()) { _Huginn-Fail "huginn local: could not read the appd token from $H" }
        $hd = Join-Path $HOME '.huginn'
        New-Item -ItemType Directory -Force -Path $hd | Out-Null
        $seed = Join-Path $hd ('.seed-' + [IO.Path]::GetRandomFileName())
        Set-Content -NoNewline -Path $seed -Value "$tok".Trim()
        # Owner-only. The profile already denies other standard users; pin it.
        $me = ([Security.Principal.WindowsIdentity]::GetCurrent()).User.Value
        icacls $seed /inheritance:r /grant:r "*${me}:F" /c /q | Out-Null
      }
      $env:HUGINN_LOCAL_DIR = $dir
      try {
        if ($seed) { $env:HUGINN_SEED_TOKEN_FILE = $seed }
        if (_Huginn-HasUrl $rest) { node $mgr on @rest; return }
        $srv = _Huginn-SrvUrl $H
        if (-not $srv) {
          _Huginn-Fail ("huginn local: could not work out how to reach $H's daemon`n" +
                        "              (no ssh address to go on, and $H named no global IPv4)`n" +
                        "              say it yourself: huginn local on --url https://<host>:8787")
        }
        node $mgr on --url $srv @rest
      } finally {
        if ($seed) { Remove-Item Env:\HUGINN_SEED_TOKEN_FILE -ErrorAction SilentlyContinue }
      }
    } elseif (Test-Path $mgr) {
      node $mgr $sub @rest
    } else {
      _Huginn-Fail "huginn local: this machine does not serve local models - run: huginn local on"
    }
  } elseif ($args[0] -eq 'uninstall') {
    $rest = if ($args.Count -ge 2) { $args[1..($args.Count-1)] } else { @() }
    _Huginn-Uninstall -Rest $rest
  } elseif ($args[0] -eq 'desktop') {
    $arg = if ($args.Count -gt 1) { "$($args[1])".ToLower() } else { '' }
    # install/uninstall are the bash client's per-user Linux install (huginn.sh,
    # _huginn_desktop_install). Named here so the verb set matches, and so the
    # answer on Windows is a pointer rather than "usage".
    if ($arg -eq 'update') { _Huginn-DesktopUpdate; return }
    if ($arg -eq 'install') {
      # ($IsWindows is unset on Windows PowerShell 5.1, which is Windows.)
      if ($null -ne $IsWindows -and -not $IsWindows) {
        _Huginn-Fail "huginn: 'desktop install' here is the bash client's (huginn.sh): the per-user Linux install"
      }
      # The release's installer, sha256-checked against its manifest, run
      # silently (per-user: no admin), then opened.
      $t0 = [Diagnostics.Stopwatch]::StartNew()
      if (-not $env:HUGINN_SETUP) { _Huginn-UiHead 'huginn desktop install' }
      _Huginn-DesktopUpdate -Install
      if (-not $env:HUGINN_SETUP) { _Huginn-Say ("    done in {0} s" -f [int]$t0.Elapsed.TotalSeconds) DarkGray }
      return
    }
    if ($arg -in 'uninstall', 'remove') {
      _Huginn-Fail ("huginn: 'desktop $arg': on Windows, remove Huginn Desktop from Settings > Apps`n" +
                    "  (huginn uninstall takes the whole stack, the desktop included)")
    }
    $want = switch ($arg) {
      { $_ -in '', 'both', 'all' }              { ''; break }
      { $_ -in 'win', 'windows', 'exe' }        { 'windows-x64'; break }
      { $_ -in 'linux', 'deb', 'debian', 'ubuntu' } { 'linux-x64'; break }
      { $_ -in 'arch', 'pacman', 'pkg' }        { 'linux-x64-arch'; break }
      { $_ -in 'portable', 'tar', 'tarball' }   { 'linux-x64-portable'; break }
      default { 'BAD' }
    }
    if ($want -eq 'BAD') { _Huginn-Fail "usage: huginn desktop [windows|linux|arch|portable|install|uninstall]" }
    $rel = _Huginn-DesktopRelease
    if (-not $rel) {
      _Huginn-Fail ("huginn: could not read the desktop release feed (offline, or GitHub rate-limited this IP).`n" +
                    "  Browse it: https://github.com/$script:HUGINN_REPO/releases")
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
      if (-not $a) { _Huginn-Fail "huginn: $($rel.tag) has no $want build" }
      return "$base/$($a.file)"
    }
    Write-Host ""
    Write-Host "  Huginn Desktop $($rel.manifest.version)   ($($rel.tag))"
    Write-Host ""
    $labels = @{ 'windows-x64' = 'Windows '; 'linux-x64' = 'Debian  '; 'linux-x64-arch' = 'Arch    '; 'linux-x64-portable' = 'Portable' }
    foreach ($p in @('windows-x64', 'linux-x64', 'linux-x64-arch', 'linux-x64-portable')) {
      $a = $arts.$p
      if (-not $a) { continue }
      $mark  = if ($p -eq $here) { '   <- this machine' } else { '' }
      Write-Host "  $($labels[$p])  $base/$($a.file)$mark"
      _Huginn-Say ("            {0,6:N1} MB   sha256 {1}..." -f ($a.size / 1MB), $a.sha256.Substring(0, 16)) DarkGray
    }
    Write-Host ""
    if ($here -eq 'windows-x64') {
      Write-Host "  install:  run the .exe (per-user NSIS installer, no admin needed)"
    } elseif ($here -eq 'linux-x64') {
      # pwsh on Linux: the per-user install lives in the bash client.
      Write-Host "  install:  huginn desktop install    (bash client; per-user, no root, updates itself)"
    } else {
      Write-Host "  (no desktop build for this machine - these links are for your laptop)"
    }
    _Huginn-Say "  The Windows and portable installs update themselves from this same feed." DarkGray
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
        # Single quotes inside, never double: the whole line rides ssh as one
        # native argument, and 5.1 strips the double quotes out of that (see
        # _Huginn-SshLine), which handed the host `date -d 7 days ago`, so
        # ccusage got an empty -s.
        'week'      { 'since=$(date -d ''7 days ago'' +%Y%m%d); until=$(date +%Y%m%d)' }
        'month'     { 'since=$(date +%Y%m01); until=$(date +%Y%m%d)' }
      }
      ssh -tt $H "$dates; ccusage $report -s `$since -u `$until $rest"
    } else {
      $sub = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] -join ' ' } else { 'daily' }
      ssh -tt $H "ccusage $sub"   # default 'daily'. -tt for tables + --live.
    }
  } elseif ($args[0] -eq 'attach') {
    # By name, never by verb - see _Huginn-AttachNamed. No default: a bare
    # `attach` is a mistake, not a request for main.
    if ($args.Count -ne 2) { _Huginn-Fail "usage: huginn attach <name>" }
    _Huginn-AttachNamed -H $H -Name ([string]$args[1])
  } elseif ($args[0] -eq 'solo') {
    $name = if ($args.Count -gt 1) { $args[1] } else { 'main' }
    if (-not (_Huginn-ValidName $name)) { _Huginn-BadName $name }
    _Huginn-Attach -H $H -Session $name -Solo
  } elseif ($args[0] -eq 'rename' -or $args[0] -eq 'mv') {
    if ($args.Count -lt 3) { _Huginn-Fail "usage: huginn rename <old> <new>" }
    if (-not (_Huginn-ValidName $args[2])) { _Huginn-BadName ([string]$args[2]) 'new name' }
    if (-not (_Huginn-ValidName $args[1])) { _Huginn-BadName ([string]$args[1]) }
    $ro = _Huginn-CanonName $args[1]; $rn = _Huginn-CanonName $args[2]
    # THROUGH THE DAEMON'S RENAME, LIKE KILL. A bare tmux rename-session left
    # everything the daemon keeps by name under the OLD one: the state file, the
    # send queue (a message queued for the old name was never delivered), the
    # restore-registry row, and the project record (a member renamed this way
    # dropped out of its project, and a lead's project was archived). The route
    # moves all of it, and refuses a project member or lead in a sentence that
    # says what to do instead. Raw tmux only when nothing answered at all (a
    # terminal-only server has no daemon); never on an HTTP refusal.
    $rr = _Huginn-Appd -H $H -Method 'POST' -Path "/v1/sessions/$ro/rename" -Body ('{"name":"' + $rn + '"}')
    if (-not $rr.Reached) {
      ssh -T $H "tmux rename-session -t '$(_Huginn-TmuxTarget $ro)' '$rn' && echo 'renamed: $ro -> $rn'"
    } elseif ($rr.Code -match '^2') {
      Write-Host "renamed: $ro -> $rn"
    } else {
      $m = _Huginn-AppdError $rr.Body
      if (-not $m) { $m = "huginn-appd answered HTTP $($rr.Code)" }
      _Huginn-Fail "huginn: could not rename '$ro': $m"
    }
  } elseif ($args[0] -eq 'kill') {
    if ($args.Count -lt 2) { _Huginn-Fail "usage: huginn kill <name>" }
    if (-not (_Huginn-ValidName $args[1])) { _Huginn-BadName ([string]$args[1]) }
    $kn = _Huginn-CanonName $args[1]
    # Prefer the daemon's DELETE: it also removes the orphaned /run state file and
    # releases the pane lease, which a bare tmux kill-session leaves behind (Claude's
    # SessionEnd hook never fires on a kill). Fall back to tmux when the daemon is
    # unreachable - kill must work even when appd is down.
    # '=' anchor on the fallback: without it 'huginn kill andvari' kills 'andvariautofill'.
    #
    # THE FALLBACK IS FOR AN UNREACHABLE DAEMON, NOT AN HTTP STATUS. DELETE has no
    # 409 guard, so the realistic failure is a GLOBAL 401 - an unreadable or rotated
    # token - with the daemon perfectly healthy. Falling back there killed the
    # session with raw tmux, skipping clearSessionState / registryRemove /
    # releaseSize, so a deliberately killed session came back on the next reboot
    # restore and nothing ever said why.
    $kr = _Huginn-Appd -H $H -Method 'DELETE' -Path "/v1/sessions/$kn"
    if (-not $kr.Reached) {
      ssh -T $H "tmux kill-session -t '$(_Huginn-TmuxTarget $kn)' && echo 'killed: $kn'"
    } elseif ($kr.Code -match '^2') {
      Write-Host "killed: $kn"
    } else {
      $m = _Huginn-AppdError $kr.Body
      if (-not $m) { $m = "huginn-appd answered HTTP $($kr.Code)" }
      _Huginn-Fail "huginn: could not kill '$kn': $m"
    }
  } elseif ($args[0] -eq 'end') {
    if ($args.Count -lt 2) { _Huginn-Fail "usage: huginn end <name> [--force]" }
    if (-not (_Huginn-ValidName $args[1])) { _Huginn-BadName ([string]$args[1]) }
    $en = _Huginn-CanonName $args[1]
    # --force is the answer to one specific refusal, and it used to be UNREACHABLE:
    # neither client sent a request body and `end` parsed no flags, so the daemon's
    # "pass force to send anyway" named something nobody could do.
    $force = ''
    if ($args.Count -ge 3) {
      if ($args[2] -eq '--force') { $force = '{"force":true}' }
      else { _Huginn-Fail "usage: huginn end <name> [--force]" }
    }
    # Soft end: ask Claude to wrap up (finish, commit, prepare to end) and - when
    # auto-end is on for the host - end the session once it settles. This is a DAEMON
    # feature (it types into the pane and watches state), so there is no tmux fallback;
    # the phrase is whatever the host is configured to send.
    $r = _Huginn-Appd -H $H -Method 'POST' -Path "/v1/sessions/$en/soft-end" -Body $force
    if (-not $r.Reached) {
      # ⚠ WHICH HALF FAILED. An unreachable HOST is not a stopped daemon, and
      # this line used to say the second about the first - on a box ssh had just
      # refused - with the transport error thrown away by 2>$null.
      _Huginn-AppdWhy -H $H -R $r -Tail 'a soft-end is a daemon feature, so there is no tmux fallback'
    }
    if ($r.Code -notmatch '^2') {
      $m = _Huginn-AppdError $r.Body
      if (-not $m) { $m = "huginn-appd answered HTTP $($r.Code)" }
      $msg = "huginn: could not end '$en': $m"
      if ($m -match 'force') { $msg += "`n        send it anyway with: huginn end $en --force" }
      _Huginn-Fail $msg
    }
    $phrase = 'wrap-up phrase'; $auto = ''
    try {
      $j = $r.Body | ConvertFrom-Json
      if ($j.phrase) { $phrase = $j.phrase }
      if ($j.auto)   { $auto = ' (auto-ends when it goes idle)' }
    } catch {}
    Write-Host "soft-ended '$en': sent `"$phrase`"$auto"
  } elseif ($args[0] -eq 'archive') {
    # Archive: end the session for good AND keep the way back into it — the
    # title, the cwd, the last thing said, a COPY of the transcript, and the
    # exact `claude --resume <uuid>`. Graceful by default like `end`; --now for a
    # session with nothing left to wrap up. Bare `huginn archive` is the list.
    #
    # CALLED on the host, not just rendered there. The daemon refuses an archive
    # in prose ("answer the waiting question first, then archive the session")
    # and that sentence is the most useful thing this verb ever says, while
    # _Huginn-Appd uses curl -sf and throws a 4xx body away.
    if ($args.Count -lt 2) { ssh -T $H huginn-archive; return }
    if (-not (_Huginn-ValidName $args[1])) { _Huginn-BadName ([string]$args[1]) }
    $an = _Huginn-CanonName $args[1]
    # Single-quote marshalled like the headroom/llm branches: what follows the
    # host name is parsed by a shell on the far side, so an argument typed here
    # is remote shell input.
    $arArgs = @($an)
    if ($args.Count -gt 2) { $arArgs += $args[2..($args.Count - 1)] }
    $arStr = ($arArgs | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
    ssh -T $H "huginn-archive $arStr"
  } elseif ($args[0] -eq 'revive' -or $args[0] -eq 'unarchive') {
    # Takes the archive id OR the name it had; the name is resolved host-side,
    # because resolving it here would mean parsing the list in two languages.
    if ($args.Count -lt 2) { _Huginn-Fail "usage: huginn revive <id|name>" }
    # Wider than _Huginn-ValidName deliberately: an archive id is a uuid, and
    # uuids have dashes. Still a strict allow-list — this reaches a remote shell.
    if ($args[1] -notmatch '^[A-Za-z0-9_.-]{1,64}$') { _Huginn-Fail "huginn: '$($args[1])' is not an archive id or a session name" }
    $rv = "'" + ($args[1] -replace "'", "'\''") + "'"
    ssh -T $H "huginn-archive revive $rv"
  } elseif ($args[0] -eq '-p' -or $args[0] -eq '-y') {
    if ($args.Count -lt 2) { _Huginn-Fail "usage: huginn $($args[0]) ""your prompt""" }
    $q = ($args[1..($args.Count - 1)] -join ' '); $esc = $q -replace "'", "'\''"  # POSIX single-quote escape
    # The server's huginn-ask runs the turn with the same fence as a chat in the
    # apps (-p = ask: read, search, web, Bash/Edit/Write denied; -y = act), plus
    # the server's own extra tools, persona and working directory. A server too
    # old to carry it gets the same fence inline, with the persona from where
    # older servers kept it. The deny-list is the real fence: --allowedTools only
    # auto-approves. It is assembled HERE, so its quoting is bash SYNTAX on the
    # host; expanding it from a remote variable word-splits it into
    # `'Bash` `Edit` `Write` `NotebookEdit'`, which denies nothing.
    $sub = if ($args[0] -eq '-y') { 'act' } else { 'ask' }
    $tools = if ($args[0] -eq '-y') { "Skill Bash Read Edit Write Glob Grep WebFetch WebSearch" } else { "Skill WebFetch WebSearch" }
    $dflag = if ($args[0] -eq '-y') { '' } else { "--disallowedTools 'Bash Edit Write NotebookEdit'" }
    #
    # The remote script is base64'd rather than passed as a quoted argument.
    # Windows PowerShell 5.1 mangles embedded double quotes when marshalling
    # arguments to a native executable, which silently corrupted P="$(cat ...)"
    # into an unquoted assignment: the persona word-split, `[ -n $P ]` errored
    # with "too many arguments", the else branch ran, and `huginn -y` degraded to
    # a bare `claude -p` with NO persona and NO --allowedTools. Base64 puts only
    # [A-Za-z0-9+/=] on the command line, so 5.1 and 7.x behave identically.
    $remoteScript = @"
if command -v huginn-ask >/dev/null 2>&1; then
  echo '$esc' | huginn-ask $sub
  exit
fi
cd "`${HUGINN_WORKDIR:-`$HOME}" 2>/dev/null || cd "`$HOME"
P="`$(cat /usr/local/share/huginn-cli/persona.md 2>/dev/null)"
if [ -n "`$P" ]; then
  echo '$esc' | claude -p --append-system-prompt "`$P" --allowedTools '$tools' $dflag
else
  echo '$esc' | claude -p --allowedTools '$tools' $dflag
fi
"@
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remoteScript -replace "`r`n", "`n")))
    ssh -T $H "echo $b64 | base64 -d | bash -s"
  } else {
    if (-not (_Huginn-ValidName $args[0])) { _Huginn-BadName ([string]$args[0]) 'session name' "Did you mean a subcommand? Try 'huginn help'." }
    _Huginn-Attach -H $H -Session $args[0]
  }
}
Set-Alias rclaude huginn
Set-Alias rcc huginn

# Live session names from the host (tmux ls), cached in-memory for a few seconds so
# repeated Tab doesn't ssh every keystroke. BatchMode stops a missing key from hanging
# the prompt; ConnectTimeout bounds a slow link.
#
# ⚠ A SESSION NAME IS THE HOST'S DATA, AND COMPLETION PUT IT ON THE COMMAND LINE.
# Each name went in verbatim and unquoted as CompletionText, so a session renamed
# on the host to `x;<anything>` or `$(...)` became live PowerShell at this prompt,
# run on Enter before huginn ever saw an argument. tmux rewrites only '.' and ':',
# and the daemon's name check never runs for a session made with tmux directly.
# So only names that pass _Huginn-ValidName are returned, and the completer below
# single-quotes what it inserts.
$script:HUGINN_SESS_CACHE = @()
$script:HUGINN_SESS_TS    = [datetime]::MinValue
function _Huginn-Sessions {
  $H = if ($env:HUGINN_HOST) { $env:HUGINN_HOST } else { 'huginn' }
  if (((Get-Date) - $script:HUGINN_SESS_TS).TotalSeconds -ge 5) {
    $script:HUGINN_SESS_CACHE = @(ssh -T -o BatchMode=yes -o ConnectTimeout=2 $H "tmux ls -F '#S' 2>/dev/null" 2>$null)
    $script:HUGINN_SESS_TS    = Get-Date
  }
  return @($script:HUGINN_SESS_CACHE | Where-Object { _Huginn-ValidName ([string]$_) })
}
Register-ArgumentCompleter -CommandName huginn, rclaude, rcc -ScriptBlock {
  param($word, $ast, $pos)
  # ⚠ EVERY VERB THAT TAKES SUB-VERBS HAS TO BE BELOW, and two were missing
  # outright: `huginn projects <TAB>` completed to NOTHING in both clients - for
  # the one verb whose whole grammar is arguments - and this completer had no
  # `device` branch at all while the bash one did, so the PowerShell client
  # could not complete the verb it shares its own name with. `local` was also a
  # release behind (`persist` shipped in 1.1.0), and the aliases the dispatcher
  # accepts (ls, st, mv, round, project, ccusage, unarchive) were offered by
  # neither.
  $cmds = 'console', 'setup', 'list', 'ls', 'status', 'st', 'rounds', 'round', 'headroom', 'devices', 'device', 'core', 'local', 'llm', 'projects', 'project', 'solo', 'attach', 'rename', 'mv', 'kill', 'end', 'archive', 'revive', 'unarchive', '-p', '-y', 'usage', 'cost', 'ccusage', 'desktop', 'update', 'uninstall', 'version', 'help'
  # tokens already typed after the command name, excluding the partial word being completed
  $typed = @($ast.CommandElements | Select-Object -Skip 1 | ForEach-Object { $_.ToString() })
  if ($word -and $typed.Count -ge 1) { $typed = @($typed | Select-Object -SkipLast 1) }
  $prev = if ($typed.Count -ge 1) { $typed[-1] } else { '' }
  $sessions = @()
  if ($typed.Count -eq 0) {
    $candidates = $cmds                                # first word: subcommands + sessions
    $sessions = @(_Huginn-Sessions | Where-Object { $_ -notin $cmds })
  } elseif ($prev -in 'kill', 'end', 'archive', 'solo', 'attach', 'rename', 'mv') {
    $candidates = @()                                  # these take an existing session name
    $sessions = @(_Huginn-Sessions)
  } elseif ($prev -in 'usage', 'cost', 'ccusage') {
    $candidates = 'today', 'yesterday', 'week', 'month', 'daily', 'monthly', 'weekly', 'session', 'blocks', 'statusline'
  } elseif ($prev -in 'today', 'yesterday', 'week', 'month') {
    $candidates = 'daily', 'monthly', 'weekly', 'session', 'blocks', 'statusline'
  } elseif ($prev -eq 'desktop') {
    $candidates = 'windows', 'linux', 'arch', 'portable', 'install', 'uninstall', 'update', 'both'
  } elseif ($prev -eq 'local') {
    $candidates = 'status', 'on', 'plan', 'persist', 'off', 'unit', 'update', 'doctor', '--help'
  } elseif ($prev -eq 'device') {
    $candidates = 'status', 'on', 'off', 'unit', 'update', 'serve', '--help'
  } elseif ($prev -in 'projects', 'project') {
    $candidates = 'list', 'show', 'new', 'spawn', 'msg', 'end', '--json', '--help'
  } elseif ($prev -eq 'headroom') {
    $candidates = '--json', '--help'
  } elseif ($prev -eq '--scope') {
    $candidates = 'look', 'work', 'own'
  } elseif ($prev -eq '--kind') {
    $candidates = 'software', 'infra', 'hardware', 'docs', 'research', 'other'
  } elseif ($prev -eq 'uninstall') {
    $candidates = '--all', '--yes'
  } else {
    $candidates = @()
  }
  $candidates | Where-Object { $_ -like "$word*" } |
    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
  # A session name is the host's, so it goes in single-quoted, every quote
  # character PowerShell honours doubled: inserted text that cannot be evaluated
  # even if a name ever got past _Huginn-ValidName. The list shows it bare.
  $sessions | Where-Object { $_ -like "$word*" } | ForEach-Object {
    $q = "'" + [System.Management.Automation.Language.CodeGeneration]::EscapeSingleQuotedStringContent([string]$_) + "'"
    [System.Management.Automation.CompletionResult]::new($q, $_, 'ParameterValue', $_)
  }
}
