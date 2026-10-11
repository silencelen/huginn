#!/usr/bin/env bash
# Rebuild every anim-vector variation from the canonical mark, then re-capture every
# preview. Run from anywhere: bash assets/brand/explorations/anim-vector/build.sh
# Needs node + global playwright (tools/render.mjs has the same need), python3 + PIL,
# ImageMagick. Writes only inside this folder (and temp dirs for frames).
set -euo pipefail
cd "$(dirname "$0")"

# 1. generated SVGs (each build.py reads ../../../raven.svg: a new mark is one swap)
python3 anim-02-assemble/build.py >/dev/null
python3 anim-03-fly-return/build.py >/dev/null
python3 anim-04-attention-pulse/build.py >/dev/null
python3 anim-05-thinking/build.py >/dev/null

# 2. hand-written SVGs get their preview page from the same wrapper
node wrap.mjs anim-01-blink/anim.svg anim-01-blink/anim.html "Huginn — anim-01-blink" >/dev/null

# 3. bone-on-dark twins for the README's <picture> dark source
for d in anim-01-blink anim-02-assemble anim-03-fly-return; do
  sed 's/--ink: #161310/--ink: #E8E2DA/' "$d/anim.svg" > "$d/anim-inverse.svg"
done

# 4. the terminal block must still be a clean block
python3 ../tools/asciicheck.py anim-02-assemble/glyphs.txt

# 5. previews (and the anim-04 sprite sheets)
for d in anim-0*/; do (cd "$d" && node capture.mjs >/dev/null && echo "$d preview.png"); done
ls -la anim-0*/preview.png | awk '{print $5, $9}'
