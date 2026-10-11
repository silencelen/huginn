# Huginn CLI colours - Slate & Verdigris (color-02-slate-verdigris)
# For client/huginn.ps1 (inline it). PURE 7-BIT ASCII, no BOM: the file goes over scp and
# Windows PowerShell 5.1 mis-decodes anything else; the generator refuses non-ASCII here.
# $HuginnColor: ConsoleColor names for Write-Host -ForegroundColor, the BRIGHT slots, which
# are what huginn.ps1 prints today (Red / Yellow / Green / DarkGray): the dark ones are
# unreadable on the default consoles (DarkRed #800000 on #000000 is 1.9:1). $HuginnSgr:
# escape sequences, 16-colour or truecolor. Ratios on the palettes they must survive
# (tools/contrast.py; minimum 3:1, the Huginn scheme itself is >= 4.5:1):
#  semantic slot          SGR  with the Huginn scheme                 conhost  powershell-5.1        campbell           xterm
#  ok       brightGreen   92   #A9C97E 9.7:1 on #14171C        #00FF00 15.3:1  #00FF00 11.0:1   #16C60C 8.5:1  #00FF00 15.3:1
#  warn     brightYellow  93   #EFD3A0 12.4:1 on #14171C       #FFFF00 19.6:1  #FFFF00 14.1:1  #F9F1A5 16.9:1  #FFFF00 19.6:1
#  error    brightRed     91   #E8767A 6.2:1 on #14171C         #FF0000 5.3:1   #FF0000 3.8:1   #E74856 5.1:1   #FF0000 5.3:1
#  muted    brightBlack   90   #6F7983 4.1:1 on #14171C         #808080 5.3:1   #808080 3.8:1   #767676 4.3:1   #7F7F7F 5.2:1
#  accent   brightCyan    96   #7FD0BE 10.0:1 on #14171C       #00FFFF 16.7:1  #00FFFF 12.1:1  #61D6D6 11.3:1  #00FFFF 16.7:1
#                              (ground of each legacy palette)         #000000         #012456         #0C0C0C         #000000
#   PowerShell 5.1's console remaps DarkYellow to #EEEDF0 on its #012456 ground, so an
#   accent in slot 33 / DarkYellow shows there as near-white: legible, not gold. Install
#   the Windows Terminal scheme beside this file for the brand hex.
$e = [char]27
$HuginnColor = @{
  Ok     = 'Green'
  Warn   = 'Yellow'
  Error  = 'Red'
  Muted  = 'DarkGray'
  Accent = 'Cyan'
}
$HuginnSgr = if (-not $Host.UI.SupportsVirtualTerminal -or $env:NO_COLOR) {
  @{ Ok=''; Warn=''; Error=''; Muted=''; Accent=''; Bold=''; Reset='' }
} elseif ($env:COLORTERM -in 'truecolor','24bit' -or $env:WT_SESSION) {
  @{
    Ok     = "$e[38;2;169;201;126m"   # #A9C97E
    Warn   = "$e[38;2;239;211;160m"   # #EFD3A0
    Error  = "$e[38;2;232;118;122m"   # #E8767A
    Muted  = "$e[38;2;111;121;131m"   # #6F7983
    Accent = "$e[38;2;93;187;166m"   # #5DBBA6
    Bold   = "$e[1m"; Reset = "$e[0m"
  }
} else {
  @{
    Ok     = "$e[92m"
    Warn   = "$e[93m"
    Error  = "$e[91m"
    Muted  = "$e[90m"
    Accent = "$e[96m"
    Bold   = "$e[1m"; Reset = "$e[0m"
  }
}
# The one-row raven, both forms. $HuginnGlyphAscii is the head row of the help-screen raven
# huginn.ps1 already prints; $HuginnGlyph is the ascii dimension's pick (U+25E2 U+2586 U+2588
# U+2580) when the console writes UTF-8 (code page 65001, or Windows Terminal), else ASCII;
# $HuginnGlyphBlock swaps the wedge for U+2597 for fonts without Geometric Shapes.
$HuginnGlyphAscii = '(o)==-'
$HuginnGlyphBlock = -join [char[]](0x2597, 0x2586, 0x2588, 0x2580)
$HuginnGlyph = if ([Console]::OutputEncoding.CodePage -eq 65001 -or $env:WT_SESSION) {
  -join [char[]](0x25E2, 0x2586, 0x2588, 0x2580)
} else { $HuginnGlyphAscii }
# usage:  Write-Host "huginn: link dropped - reconnecting" -ForegroundColor $HuginnColor.Warn
#         "$($HuginnSgr.Accent)$HuginnGlyph$($HuginnSgr.Reset) huginn - thought, on call from anywhere."
