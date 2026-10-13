#!/usr/bin/env bash
# Regenerate every file and preview of the colour explorations.
#   bash build/render.sh            (from assets/brand/explorations/color)
# Scratch renders go to $RENDERS (defaults to the session scratchpad); the
# committed preview.png files are written beside each variation.
set -euo pipefail
cd "$(dirname "$0")/.."
RENDERS=${RENDERS:-/tmp/claude-0/-home-user-huginn/dc9f86f2-f60d-5445-8434-59f9b7f51183/scratchpad/renders/color}
DIRS="color-01-ink-bone-gold color-02-slate-verdigris color-03-soot-ember color-04-moss-heather"
mkdir -p "$RENDERS" "$RENDERS/tray"

# 1. tokens, sheets, terminal files (refuses if a rule fails), then the READMEs
python3 build/palette.py
python3 build/readmes.py

# 2. the CLI tables are inlined into client/huginn.sh and huginn.ps1, which must stay 7-bit
if grep -lP '[^\x00-\x7F]' color-*/terminal/cli-colors.sh color-*/terminal/cli-colors.ps1; then
  echo "refusing: non-ASCII bytes in a CLI colour table" >&2; exit 1
fi
for f in color-*/terminal/cli-colors.sh; do bash -n "$f"; done
python3 ../tools/asciicheck.py color-*/terminal/glyph.txt

# 3. full-page renders of the sheets and terminal mocks, quantised with pinned token hex
for d in $DIRS; do
  node build/shot.mjs "$d/swatches.html" "$RENDERS/$d-swatches.png" 1200 &
  node build/shot.mjs "$d/terminal/mock.html" "$RENDERS/$d-terminal.png" 1200 &
done
wait
for d in $DIRS; do
  python3 build/quant.py "$RENDERS/$d-swatches.png" "$d/preview.png" "$d"
  python3 build/quant.py "$RENDERS/$d-terminal.png" "$d/terminal/preview.png" "$d"
done
# the three terminal mocks stacked (exact hex is proven per direction, so plain 256 colours here)
convert color-01-ink-bone-gold/terminal/preview.png color-02-slate-verdigris/terminal/preview.png \
        color-03-soot-ember/terminal/preview.png -append +dither -colors 256 terminal/preview.png

# 4. the tray ravens at the real tray sizes with tools/render.mjs, tiled with hard pixels
#    (verification renders; the committed sheets carry the same icons as an HTML strip)
python3 build/tray.py "$RENDERS/tray" > /dev/null
for svg in "$RENDERS"/tray/*.svg; do node ../tools/render.mjs "$svg" "${svg%.svg}" 22,16 > /dev/null; done
for d in $DIRS; do for s in dark light; do
  files=()
  for z in 22 16; do for v in idle working attention mono-idle mono-working mono-attention; do files+=("$RENDERS/tray/$d-$s-$v-$z.png"); done; done
  bash ../tools/sheet.sh "$RENDERS/tray/$d-$s-sheet.png" "${files[@]}" > /dev/null
done; done
# committed: dark bar over light bar, 22 px then 16 px, hard-pixel upscaled (a few KB each)
for d in $DIRS; do convert "$RENDERS/tray/$d-dark-sheet.png" "$RENDERS/tray/$d-light-sheet.png" -append "$d/tray-sizes.png"; done

ls -la color-*/preview.png color-*/terminal/preview.png color-*/tray-sizes.png terminal/preview.png
