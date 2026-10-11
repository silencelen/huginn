# Huginn-Play FRAMES.txt [-Loops N] [-Fps N]
#
# PowerShell twin of bash-player.sh: plays a huginn-frames file (format: anim-ascii/README.md).
# ASCII-only, so it can be pasted into client/huginn.ps1 (CONTRIBUTING.md "Dev notes"); the
# frames file it reads may be UTF-8 (the Unicode variations) or 7-bit (the -ascii twins).
# Windows PowerShell 5.1 and PowerShell 7. Static fallback (no cursor movement) when output is
# redirected, HUGINN_NO_ANIM is set, or the window is narrower than the frame.
#
# NOT RUN HERE: this environment has no pwsh. Written against the documented [Console] API;
# the critic / owner should run it once on Windows Terminal and on conhost before trusting it.
function Huginn-Play {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Loops = 0,
        [int]$Fps = 0
    )
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $lines = [System.IO.File]::ReadAllLines($Path, $utf8)
    $cols = 0; $rows = 0; $fps = 8; $loop = 'no'; $rest = 'first'; $holds = @(); $pad = [string][char]0x2800
    $start = 0
    if ($lines.Length -gt 0 -and $lines[0].StartsWith('# huginn-frames')) {
        foreach ($kv in $lines[0].Substring(15).Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $k, $v = $kv.Split('=', 2)
            switch ($k) {
                'cols'  { $cols = [int]$v }
                'rows'  { $rows = [int]$v }
                'fps'   { $fps = [int]$v }
                'loop'  { $loop = $v }
                'rest'  { $rest = $v }
                'holds' { $holds = @($v.Split(',') | ForEach-Object { [int]$_ }) }
                'pad'   { $pad = $v }
            }
        }
        $start = 1
    }
    $frames = New-Object System.Collections.ArrayList
    $cur = New-Object System.Collections.ArrayList
    for ($i = $start; $i -lt $lines.Length; $i++) {
        $l = $lines[$i]
        if ($l -eq '') { continue }
        if ($l -eq '---') { [void]$frames.Add(($cur -join "`n")); $cur.Clear(); continue }
        [void]$cur.Add($l.Replace($pad, ' '))
    }
    if ($cur.Count -gt 0) { [void]$frames.Add(($cur -join "`n")) }
    if ($frames.Count -eq 0) { Write-Error "Huginn-Play: no frames in $Path"; return }
    if ($Fps -gt 0) { $fps = $Fps }
    if ($rows -le 0) { $rows = ($frames[0] -split "`n").Length }
    $restIdx = switch ($rest) { 'first' { 0 } 'last' { $frames.Count - 1 } 'none' { -1 } default { [int]$rest } }
    $static = [Console]::IsOutputRedirected -or $env:HUGINN_NO_ANIM -or ($cols -gt 0 -and [Console]::WindowWidth -lt $cols)
    if ($static) {
        if ($restIdx -ge 0) { foreach ($l in ($frames[$restIdx] -split "`n")) { [Console]::Out.WriteLine($l.TrimEnd()) } }
        return
    }
    $delays = @()
    for ($i = 0; $i -lt $frames.Count; $i++) {
        if ($i -lt $holds.Count -and $holds[$i] -gt 0) { $delays += $holds[$i] } else { $delays += [int](1000 / $fps) }
    }
    $passes = 0; $n = 0; $top = [Console]::CursorTop
    try {
        [Console]::CursorVisible = $false
        while ($true) {
            for ($i = 0; $i -lt $frames.Count; $i++) {
                if ($n -gt 0) { [Console]::SetCursorPosition(0, $top) }
                [Console]::Out.Write($frames[$i] + "`n")
                $top = [Console]::CursorTop - $rows    # the buffer may have scrolled on the first frame
                $n++
                Start-Sleep -Milliseconds $delays[$i]
            }
            $passes++
            if ($Loops -gt 0) { if ($passes -ge $Loops) { break } }
            elseif ($loop -ne 'yes') { break }
        }
    } finally {
        [Console]::CursorVisible = $true
    }
}
if ($MyInvocation.InvocationName -ne '.') { Huginn-Play @args }
