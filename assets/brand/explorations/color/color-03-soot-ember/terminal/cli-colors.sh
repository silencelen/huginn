#!/usr/bin/env bash
# Huginn CLI colours - Soot & Ember (color-03-soot-ember)
# For client/huginn.sh (inline it: huginn.sh is one shipped file) and server/bin/huginn-status.
# PURE 7-BIT ASCII, no BOM: huginn update ships huginn.sh over scp and Windows PowerShell
# 5.1 mis-decodes anything else (CONTRIBUTING.md, Dev notes). The generator refuses to
# write this file if it contains a non-ASCII byte; the raven below is built from escapes.
# Same rules as huginn-status today: colour only on a TTY and only while NO_COLOR is unset.
#
# The 16-colour branch inherits the user's palette, so it uses the BRIGHT slots: on every
# legacy palette the dark red is under 2:1 on its own ground (conhost #800000 on #000000
# is 1.9:1), while Red / Yellow / Green / DarkGray are what huginn.ps1 already prints.
# Ratios of each slot on the legacy palettes it must survive (tools/contrast.py numbers;
# the minimum is 3:1, text in the apps and the Huginn scheme is 4.5:1):
#  semantic slot          SGR  with the Huginn scheme                 conhost  powershell-5.1        campbell           xterm
#  ok       brightGreen   92   #B5C27A 9.7:1 on #141212        #00FF00 15.3:1  #00FF00 11.0:1   #16C60C 8.5:1  #00FF00 15.3:1
#  warn     brightYellow  93   #F2CF96 12.6:1 on #141212       #FFFF00 19.6:1  #FFFF00 14.1:1  #F9F1A5 16.9:1  #FFFF00 19.6:1
#  error    brightRed     91   #EE6E7C 6.4:1 on #141212         #FF0000 5.3:1   #FF0000 3.8:1   #E74856 5.1:1   #FF0000 5.3:1
#  muted    brightBlack   90   #7C736E 4.0:1 on #141212         #808080 5.3:1   #808080 3.8:1   #767676 4.3:1   #7F7F7F 5.2:1
#  accent   yellow        33   #D9A45A 8.4:1 on #141212         #808000 5.0:1  #EEEDF0 13.0:1   #C19C00 7.5:1  #CDCD00 12.3:1
#                              (ground of each legacy palette)         #000000         #012456         #0C0C0C         #000000
#   (PowerShell 5.1's own console remaps DarkYellow to #EEEDF0 and uses #012456 as the
#   ground, so an accent in slot 33 shows there as near-white: legible, not gold.)
# The truecolor branch pins the brand hex: H_ACCENT is the brand accent itself (#E58A4C).
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  case "${COLORTERM:-}" in
    truecolor|24bit)
      H_OK=$(printf '\033[38;2;181;194;122m')   # #B5C27A
      H_WARN=$(printf '\033[38;2;242;207;150m')   # #F2CF96
      H_ERROR=$(printf '\033[38;2;238;110;124m')   # #EE6E7C
      H_MUTED=$(printf '\033[38;2;124;115;110m')   # #7C736E
      H_ACCENT=$(printf '\033[38;2;229;138;76m')   # #E58A4C
      ;;
    *)
      H_OK=$(printf '\033[92m')
      H_WARN=$(printf '\033[93m')
      H_ERROR=$(printf '\033[91m')
      H_MUTED=$(printf '\033[90m')
      H_ACCENT=$(printf '\033[33m')
      ;;
  esac
  H_BOLD=$(printf '\033[1m'); H_RESET=$(printf '\033[0m')
else
  H_OK=; H_WARN=; H_ERROR=; H_MUTED=; H_ACCENT=; H_BOLD=; H_RESET=
fi
# The one-row raven, both forms (the brief: CLI-facing terminal art ships Unicode and 7-bit
# ASCII). H_GLYPH_ASCII is the head row of the help-screen raven huginn.sh already prints.
# H_GLYPH is the ascii dimension's pick U+25E2 U+2586 U+2588 U+2580 (tail wedge, body, raised
# head, bill) when the locale is UTF-8, else the ASCII form; H_GLYPH_BLOCK swaps the wedge
# for U+2597 for fonts without Geometric Shapes.
H_GLYPH_ASCII='(o)==-'
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) H_GLYPH=$(printf '\342\227\242\342\226\206\342\226\210\342\226\200') ;;
  *) H_GLYPH=$H_GLYPH_ASCII ;;
esac
H_GLYPH_BLOCK=$(printf '\342\226\227\342\226\206\342\226\210\342\226\200')
# usage:  printf '%s%s%s %s\n' "$H_ACCENT" "$H_GLYPH" "$H_RESET" "huginn - thought, on call from anywhere."
#         printf '%sok%s link up\n' "$H_OK" "$H_RESET"
#         printf '%shuginn: link dropped - reconnecting%s\n' "$H_WARN" "$H_RESET"
