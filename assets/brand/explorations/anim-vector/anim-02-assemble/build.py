#!/usr/bin/env python3
"""build.py — bake anim-02-assemble from the canonical mark.

Reads the <path> from assets/brand/raven.svg, renders it with tools/render.mjs,
samples the silhouette on a monospace grid (DejaVu Sans Mono metrics: advance
0.602 em, block ink from -0.86 em to +0.32 em, half-block split at -0.27 em),
picks a glyph per cell from the brief's terminal-safe set, gives every glyph a
scattered start position and a delay that grows with its distance from the eye,
and writes anim.svg (+ glyphs.txt, the settled Unicode block). Then wrap.mjs
derives anim.html. A new mark is one swap: edit raven.svg, re-run this.

Revision 2: the full and half blocks (█ ▀ ▄) are baked as <rect>s, which is what
those characters are, so the settled silhouette no longer depends on the viewer's
monospace font; only the shades (▓ ▒ ░) remain <text>. The dust tier (: .) is gone.
The resting state (no animations) is the solid mark, and the hand-over is sequenced
(mark fades in OVER the opaque glyphs, then only the glyph fringe fades) so the
silhouette never passes through grey.

    python3 build.py [--font-size 5.5] [--seed 7]
"""
import argparse, math, os, random, re, subprocess, sys, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
EXPL = os.path.abspath(os.path.join(HERE, '..', '..'))          # assets/brand/explorations
BRAND = os.path.abspath(os.path.join(EXPL, '..'))                # assets/brand
RAVEN = os.path.join(BRAND, 'raven.svg')

ap = argparse.ArgumentParser()
ap.add_argument('--font-size', type=float, default=5.5)
ap.add_argument('--seed', type=int, default=7)
ap.add_argument('--eye', default='66.5,36.5', help='eye centre in viewBox units (assembly radiates from it)')
ap.add_argument('--out', default=HERE, help='output directory (default: beside this script)')
args = ap.parse_args()
random.seed(args.seed)
OUT = args.out

src = open(RAVEN, encoding='utf-8').read()
m = re.search(r'<path[^>]*\sd="([^"]+)"', src, re.S)
D = ' '.join(m.group(1).split())
tm = re.search(r'<g transform="([^"]+)"', src)
GROUP_TRANSFORM = tm.group(1) if tm else ''
vb = re.search(r'viewBox="([^"]+)"', src).group(1)
VW = float(vb.split()[2])

# 1. render the mark (transparent) and read alpha
tmp = tempfile.mkdtemp()
RES = 512
subprocess.run(['node', os.path.join(EXPL, 'tools', 'render.mjs'), RAVEN, os.path.join(tmp, 'r'), str(RES), 'transparent'],
               check=True, capture_output=True)
im = Image.open(os.path.join(tmp, 'r-%d.png' % RES)).convert('RGBA')
alpha = im.split()[3].load()
S = RES / VW  # px per viewBox unit

def coverage(x0, y0, x1, y1):
    """Mean alpha over a viewBox-space rect, sampled on the pixel grid."""
    px0, py0, px1, py1 = (int(round(v * S)) for v in (x0, y0, x1, y1))
    px0, py0 = max(px0, 0), max(py0, 0); px1, py1 = min(px1, RES), min(py1, RES)
    if px1 <= px0 or py1 <= py0: return 0.0
    tot = 0
    for y in range(py0, py1):
        for x in range(px0, px1):
            tot += alpha[x, y]
    return tot / (255.0 * (px1 - px0) * (py1 - py0))

# 2. the grid. Cell = one monospace glyph: advance 0.602 em, block height 1.18 em.
F = args.font_size
CW, CH = 0.602 * F, 1.18 * F
# find the ink bbox in viewBox units to centre the grid on it
xs = [x for y in range(RES) for x in range(RES) if alpha[x, y] > 127]
ys = [y for y in range(RES) for x in range(0, RES, 4) if alpha[x, y] > 127]
bx0, bx1, by0, by1 = min(xs) / S, max(xs) / S, min(ys) / S, max(ys) / S
cols = math.ceil((bx1 - bx0) / CW) + 1
rows = math.ceil((by1 - by0) / CH) + 1
gx0 = (bx0 + bx1) / 2 - cols * CW / 2
gy0 = (by0 + by1) / 2 - rows * CH / 2

cells = []   # (col,row, glyph, cx, cy)
lines = []
for r in range(rows):
    line = ''
    for c in range(cols):
        x0, y0 = gx0 + c * CW, gy0 + r * CH
        top = coverage(x0, y0, x0 + CW, y0 + CH / 2)
        bot = coverage(x0, y0 + CH / 2, x0 + CW, y0 + CH)
        avg = (top + bot) / 2
        g = None
        if top >= 0.55 and bot >= 0.55:
            g = '█' if avg >= 0.85 else '▓'          # full block / dark shade
        elif top >= 0.55 and bot < 0.3:
            g = '▀'                                        # upper half
        elif bot >= 0.55 and top < 0.3:
            g = '▄'                                        # lower half
        elif avg >= 0.4:
            g = '▒'                                        # medium shade
        elif avg >= 0.22:
            g = '░'                                        # light shade
        # below 0.22: empty. (Revision 2 dropped the ':' '.' dust tier: it read as dirt.)
        line += g or ' '
        if g:
            cells.append((c, r, g, x0 + CW / 2, y0 + CH / 2))
    lines.append(line)
# trim common blank margins of the text block (keep lines equal width)
while lines and all(l[0] == ' ' for l in lines): lines = [l[1:] for l in lines]
while lines and all(l[-1] == ' ' for l in lines): lines = [l[:-1] for l in lines]
lines = [l for l in lines if l.strip()]
w = max(len(l) for l in lines)
boxed = ['┌' + '─' * (w + 2) + '┐'] + ['│ ' + l.ljust(w) + ' │' for l in lines] + ['└' + '─' * (w + 2) + '┘']
open(os.path.join(OUT, 'glyphs.txt'), 'w', encoding='utf-8').write('\n'.join(boxed) + '\n')

# 3. motion: scatter start, delay by distance from the eye
ex, ey = (float(v) for v in args.eye.split(','))
dmax = max(math.hypot(cx - ex, cy - ey) for _, _, _, cx, cy in cells)
OVER_SCALE = 1.10  # shade <text> glyphs drawn 10 % oversize so their AA edges overlap the neighbours
BLEED = 0.30       # viewBox units each side on the block <rect>s: covers the AA seam between abutting cells
FLY = 0.9          # s, per glyph
DELAY_SPAN = 0.6   # s, eye-to-edge stagger
JITTER = 0.12
SETTLE = 0.05 + DELAY_SPAN + JITTER + FLY     # = 1.67 s: last glyph lands
MARK_IN = 0.18                                # mark fades in on top of the opaque glyph layer
FRINGE_OUT = 0.15                             # then the glyph layer (now only a fringe) fades
TOTAL = SETTLE + MARK_IN + FRINGE_OUT         # = 2.0 s
BLOCKS = {'█': (-CH / 2, CH), '▀': (-CH / 2, CH / 2), '▄': (0.0, CH / 2)}   # (y from cell centre, height)

elems = []
n_rect = n_text = 0
for c, r, g, cx, cy in cells:
    d = math.hypot(cx - ex, cy - ey) / dmax
    delay = 0.05 + d * DELAY_SPAN + random.random() * JITTER
    ang = random.uniform(0, 2 * math.pi)
    dist = random.uniform(28, 62)
    dx, dy = dist * math.cos(ang), dist * math.sin(ang)
    rot = random.uniform(-50, 50)
    style = f'--dx:{dx:.1f}px;--dy:{dy:.1f}px;--r:{rot:.0f}deg;animation-delay:{delay:.2f}s'
    if g in BLOCKS:
        y, h = BLOCKS[g]
        inner = (f'<rect class="g" x="{-CW / 2 - BLEED:.2f}" y="{y - BLEED:.2f}" width="{CW + 2 * BLEED:.2f}" '
                 f'height="{h + 2 * BLEED:.2f}" style="{style}"/>')
        n_rect += 1
    else:
        # text origin: ink starts 0.07 em right of x and 0.86 em above baseline (DejaVu Sans Mono)
        tx = -CW / 2 - 0.07 * F * OVER_SCALE - (OVER_SCALE - 1) * CW / 2
        ty = -CH / 2 + 0.86 * F * OVER_SCALE - (OVER_SCALE - 1) * CH / 2
        inner = f'<text class="g" x="{tx:.2f}" y="{ty:.2f}" style="{style}">{g}</text>'
        n_text += 1
    elems.append(f'<g transform="translate({cx:.2f} {cy:.2f})">{inner}</g>')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 {VW:g} {VW:g}" width="512" height="512">
  <title>Huginn raven, assembling from terminal glyphs</title>
  <!-- anim-02-assemble. GENERATED by build.py from assets/brand/raven.svg; do not hand-edit.
       {len(cells)} terminal glyphs ({cols}x{rows} grid, {F:g} px cells) scatter in from random
       directions and settle into the mark's silhouette, then hand over to the solid path.
       Full and half blocks are <rect>s ({n_rect}); shades are <text> ({n_text}).
       Timeline: glyph fly {FLY:g} s each, cubic-bezier(0.2,0,0,1), delays 0.05-{0.05+DELAY_SPAN+JITTER:.2f} s
       growing with distance from the eye; last glyph lands at {SETTLE:.2f} s; the solid mark
       fades in on top {SETTLE:.2f}-{SETTLE+MARK_IN:.2f} s; the glyph fringe fades {SETTLE+MARK_IN:.2f}-{TOTAL:.2f} s.
       Plays once, holds on the mark. Resting state (no animations) = the solid mark. -->
  <style>
    svg {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    .glyphs {{
      font-family: "DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono", "Cascadia Mono", Menlo, Consolas, monospace;
      font-size: {F*OVER_SCALE:.2f}px;
      fill: var(--ink);
      opacity: 0;
      animation: huginn-fringe-out {FRINGE_OUT:.2f}s linear {SETTLE+MARK_IN:.2f}s both;
    }}
    .glyphs .g {{
      animation: huginn-fly {FLY:g}s cubic-bezier(0.2, 0, 0, 1) both;
    }}
    .mark {{
      opacity: 1;
      animation: huginn-mark-in {MARK_IN:.2f}s linear {SETTLE:.2f}s both;
    }}
    @keyframes huginn-fly {{
      from {{ transform: translate(var(--dx), var(--dy)) rotate(var(--r)); opacity: 0; }}
      35%  {{ opacity: 1; }}
      to   {{ transform: translate(0, 0) rotate(0deg); opacity: 1; }}
    }}
    @keyframes huginn-fringe-out {{ from {{ opacity: 1; }} to {{ opacity: 0; }} }}
    @keyframes huginn-mark-in    {{ from {{ opacity: 0; }} to {{ opacity: 1; }} }}
    @media (prefers-reduced-motion: reduce) {{
      .glyphs {{ display: none; }}
      .mark {{ opacity: 1; animation: none; }}
    }}
  </style>
  <defs>
    <path id="raven" fill-rule="evenodd" d="{D}"/>
  </defs>
  <!-- opacity="0" is the no-CSS fallback: a renderer that ignores <style> shows only the mark -->
  <g class="glyphs" opacity="0" aria-hidden="true">
    {chr(10).join('    ' + e for e in elems)}
  </g>
  <g class="mark" transform="{GROUP_TRANSFORM}">
    <use href="#raven" class="ink"/>
  </g>
</svg>
'''
open(os.path.join(OUT, 'anim.svg'), 'w', encoding='utf-8').write(svg)
subprocess.run(['node', os.path.join(HERE, '..', 'wrap.mjs'), os.path.join(OUT, 'anim.svg'), os.path.join(OUT, 'anim.html'), 'Huginn — anim-02-assemble'], check=True)
print(f'{len(cells)} glyphs ({n_rect} rects, {n_text} text), grid {cols}x{rows}, settle {SETTLE:.2f}s, mark in by {SETTLE+MARK_IN:.2f}s, total {TOTAL:.2f}s')
print('\n'.join(lines))
