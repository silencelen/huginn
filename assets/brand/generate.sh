#!/usr/bin/env bash
# Regenerate every raster brand asset from the SVG masters in this directory.
# Run after editing raven*.svg. Needs rsvg-convert + ImageMagick (both on huginn).
#
# What consumes what:
#   mobile/assets/icon.svg + icon.png        devstore listing (ship.sh uploads icon.png)
#   mobile/app-desktop/packaging/huginn.ico  jpackage --icon (exe/taskbar) + NSIS installer
#   mobile/app-desktop/packaging/huginn.png  Compose nativeDistributions Linux iconFile (.deb)
#
# NOT generated here (drawn in code on purpose, so they cannot go missing from a
# package): the desktop tray + window icons (app-desktop tray/RavenMark.kt) and the
# Android vectors (ic_launcher_foreground.xml, ic_stat_huginn.xml) — those carry
# the same path data by hand; if the path in raven.svg changes, change them too.
set -euo pipefail
cd "$(dirname "$0")"

command -v rsvg-convert >/dev/null || { echo "need rsvg-convert" >&2; exit 1; }
command -v convert      >/dev/null || { echo "need ImageMagick"  >&2; exit 1; }

# Devstore listing icon (the svg alongside is the tile master, copied verbatim).
cp raven-tile.svg ../../mobile/assets/icon.svg
rsvg-convert -w 512 -h 512 raven-tile.svg -o ../../mobile/assets/icon.png

# Windows .ico: every size Windows actually uses, each rendered from the vector
# (never scaled from one raster — 16px needs its own crisp rasterization).
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for s in 16 24 32 48 64 128 256; do
  rsvg-convert -w "$s" -h "$s" raven-tile.svg -o "$tmp/$s.png"
done
convert "$tmp"/16.png "$tmp"/24.png "$tmp"/32.png "$tmp"/48.png \
        "$tmp"/64.png "$tmp"/128.png "$tmp"/256.png \
        ../../mobile/app-desktop/packaging/huginn.ico

# Linux .deb icon.
rsvg-convert -w 512 -h 512 raven-tile.svg -o ../../mobile/app-desktop/packaging/huginn.png

echo "brand assets regenerated:"
ls -la ../../mobile/assets/icon.png ../../mobile/app-desktop/packaging/huginn.ico \
       ../../mobile/app-desktop/packaging/huginn.png
