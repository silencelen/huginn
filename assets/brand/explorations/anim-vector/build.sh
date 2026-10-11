#!/usr/bin/env bash
# Rebuild every anim-vector variation from the canonical mark, then re-capture every
# preview, poster, sprite sheet and GIF fallback. Run from anywhere:
#   bash assets/brand/explorations/anim-vector/build.sh
# Needs node + global playwright (tools/render.mjs has the same need), python3 + PIL,
# ImageMagick, ffmpeg (GIF fallbacks). Writes only inside this folder (and temp dirs).
set -euo pipefail
cd "$(dirname "$0")"

# 1. generated SVGs (each build.py reads ../../../raven.svg: a new mark is one swap)
python3 anim-02-assemble/build.py >/dev/null
python3 anim-03-fly-return/build.py >/dev/null
python3 anim-04-attention-pulse/build.py >/dev/null
python3 anim-05-thinking/build.py >/dev/null

# 2. hand-written SVGs get their preview page from the same wrapper
node wrap.mjs anim-01-blink/anim.svg anim-01-blink/anim.html "Huginn — anim-01-blink" >/dev/null

# 3. bone-on-dark twins (the app's primary scheme) for every file that is used as an image
for d in anim-01-blink anim-02-assemble anim-03-fly-return anim-05-thinking; do
  sed 's/--ink: #161310/--ink: #E8E2DA/' "$d/anim.svg" > "$d/anim-inverse.svg"
done

# 4. the terminal block must still be a clean block; the AVD sketches must be well-formed
python3 ../tools/asciicheck.py anim-02-assemble/glyphs.txt
python3 -c "import xml.dom.minidom as m, sys; [m.parse(p) for p in sys.argv[1:]]" anim-01-blink/avd-blink.xml anim-05-thinking/avd-cursor.xml

# 5. previews, posters, sprite sheets, GIF fallbacks (each capture.mjs says what it writes)
for d in anim-0*/; do (cd "$d" && node capture.mjs 2>/dev/null | tail -1 | sed "s|^|$d |"); done
ls -la anim-0*/preview.png anim-0*/poster.png anim-0*/fallback.gif anim-04-attention-pulse/sprite-*.png 2>/dev/null | awk '{print $5, $9}'
