#!/usr/bin/env bash
# Regenerate every raster brand asset from the SVG masters in this directory.
# Run after editing raven*.svg or the lockup. Needs rsvg-convert (or node + playwright) + ImageMagick.
#
# What consumes what:
#   mobile/assets/icon.svg + icon.png        devstore listing (ship.sh uploads icon.png)
#   mobile/app-desktop/packaging/huginn.ico  jpackage --icon (exe/taskbar) + NSIS installer
#   mobile/app-desktop/packaging/huginn.png  Compose nativeDistributions Linux iconFile (.deb)
#   mobile/app-desktop/packaging/wizard.bmp  NSIS finish page's left panel
#
# NOT generated here (drawn in code on purpose, so they cannot go missing from a
# package): the desktop tray + window icons (app-desktop tray/RavenMark.kt) and the
# Android vectors (ic_launcher_foreground.xml, ic_stat_huginn.xml) — those carry
# the same path data by hand; if the path in raven.svg changes, change them too.
set -euo pipefail
cd "$(dirname "$0")"

# rsvg-convert on the host; where it is missing (a container with Chromium), the
# brand explorations' renderer draws the same files with Playwright.
if command -v rsvg-convert >/dev/null; then
  render() { rsvg-convert -w "$2" -h "$2" "$1" -o "$3"; }
else
  command -v node >/dev/null || { echo "need rsvg-convert (or node + playwright)" >&2; exit 1; }
  render() { node explorations/tools/render.mjs "$1" "${3%.png}" "$2" transparent >/dev/null && mv "${3%.png}-$2.png" "$3"; }
fi
command -v convert      >/dev/null || { echo "need ImageMagick"  >&2; exit 1; }

# Devstore listing icon (the svg alongside is the tile master, copied verbatim).
cp raven-tile.svg ../../mobile/assets/icon.svg
render raven-tile.svg 512 ../../mobile/assets/icon.png

# Windows .ico: every size Windows actually uses, each rendered from the vector
# (never scaled from one raster — 16px needs its own crisp rasterization).
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for s in 16 24 32 48 64 128 256; do
  render raven-tile.svg "$s" "$tmp/$s.png"
done
convert "$tmp"/16.png "$tmp"/24.png "$tmp"/32.png "$tmp"/48.png \
        "$tmp"/64.png "$tmp"/128.png "$tmp"/256.png \
        ../../mobile/app-desktop/packaging/huginn.ico

# Linux .deb icon.
render raven-tile.svg 512 ../../mobile/app-desktop/packaging/huginn.png

# The NSIS finish page's left panel (MUI_WELCOMEFINISHPAGE_BITMAP): the lockup,
# bone on the app ground (BRAND.md section 7), "Huginn Desktop" below it. The
# size and the format are both dictated: MUI draws the panel at 164x314, and
# NSIS loads classic 24-bit uncompressed BMPs only - hence BMP3 with the alpha
# dropped onto the ground rather than left in the file. Rendered at 1024 wide
# and scaled down because 132 px of a small render is visibly soft. The caption
# is IBM Plex Sans where it is installed (the brand face), Liberation Sans
# otherwise.
if convert -list font | grep -q 'Font: IBM-Plex-Sans-Medium$'; then capfont=IBM-Plex-Sans-Medium
elif convert -list font | grep -q 'Font: Liberation-Sans$'; then capfont=Liberation-Sans
else echo "need IBM Plex Sans or the Liberation fonts for the wizard caption" >&2; exit 1; fi
render lockup-dark.svg 1024 "$tmp/lockup-bone.png"
convert "$tmp/lockup-bone.png" -trim +repage -resize 132x "$tmp/lockup.png"
convert -size 164x314 xc:'#141615' \
        "$tmp/lockup.png" -gravity North -geometry +0+64 -composite \
        -font "$capfont" -pointsize 12 -fill '#A2ABA5' \
        -gravity North -annotate +0+130 'Huginn Desktop' \
        -alpha off -type TrueColor \
        BMP3:../../mobile/app-desktop/packaging/wizard.bmp

echo "brand assets regenerated:"
ls -la ../../mobile/assets/icon.png ../../mobile/app-desktop/packaging/huginn.ico \
       ../../mobile/app-desktop/packaging/huginn.png
