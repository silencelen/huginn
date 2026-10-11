#!/usr/bin/env python3
"""svg2ascii.py — turn a mark (SVG or PNG) into terminal art.

Styles (one cell = one terminal character, cells are ~1:2 wide:tall):
  half     ▀ ▄ █ and space, 1 x 2 pixels per cell (the workhorse; every font has these)
  braille  ⠁..⣿, 2 x 4 dots per cell (highest resolution; depends on the font, see README)
  quarter  ▘ ▝ ▖ ▗ ▚ ▞ ▌ ▐ ▀ ▄ █ .., 2 x 2 per cell (quadrants; less universal than half)
  shaded   █ ▓ ▒ ░ ramp from coverage, 1 per cell (soft, "painted"; no hard edge)

Pipeline: SVG --(tools/render.mjs, Chromium, transparent)--> PNG --(PIL)--> coverage map
--> trim to the ink --> resample to the style's sub-pixel grid at the requested column
width (with terminal-cell aspect correction) --> threshold --> pack into glyphs.

Hand-tuning: `--grid` writes the thresholded sub-pixel grid as '#'/'.' text instead of
glyphs. Edit that (it is much easier to fix a bill or an eye in '#'s than in ▞s), then
feed the .grid file back in with the same --style to pack it into glyphs. That keeps the
touched-up art reproducible: the grid is the source, the glyph text is generated.

Lines are padded to equal width. Block styles pad with plain spaces, so the file needs
nothing from the font beyond the block glyphs themselves (asciicheck.py reports those
trailing spaces; see the README). Braille pads with U+2800 BRAILLE PATTERN BLANK, which
braille art needs anyway. --pad overrides either.

Examples
  svg2ascii.py ../../raven.svg --style half --width 48
  svg2ascii.py ../../raven.svg --style braille --width 24 --threshold 0.42 --grid > r.grid
  svg2ascii.py r.grid --style braille
"""
import argparse
import math
import os
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RENDER_TOOL = os.path.normpath(os.path.join(HERE, '..', 'tools', 'render.mjs'))

# sub-pixels per cell (across, down)
STYLES = {
    'half': (1, 2),
    'braille': (2, 4),
    'quarter': (2, 2),
    'shaded': (1, 1),
}
HALF = {(0, 0): ' ', (1, 0): '▀', (0, 1): '▄', (1, 1): '█'}
# quadrant bits: 1=top-left 2=top-right 4=bottom-left 8=bottom-right
QUARTER = ' ▘▝▀▖▌▞▛▗▚▐▜▄▙▟█'
BRAILLE_BLANK = '⠀'
SHADE_RAMP = ' ░▒▓█'


def log(*a):
    print(*a, file=sys.stderr)


# ----------------------------------------------------------------------------- input
def render_svg(path, size, tool):
    """Rasterise an SVG with the shared Chromium renderer, transparent background."""
    tmp = tempfile.mkdtemp(prefix='svg2ascii-')
    prefix = os.path.join(tmp, 'r')
    cmd = ['node', tool, os.path.abspath(path), prefix, str(size), 'transparent']
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, cwd=os.path.dirname(tool))
    return os.path.join(tmp, f'r-{size}.png')


def coverage_image(path, invert=False):
    """Return an 'L' image where 255 = full ink."""
    im = Image.open(path)
    if im.mode in ('RGBA', 'LA') and im.getchannel('A').getextrema()[0] < 255:
        cov = im.getchannel('A')          # transparent mark: alpha is coverage
        if invert:
            cov = cov.point(lambda v: 255 - v)
        return cov
    lum = im.convert('L')                  # opaque image: dark is ink
    if invert:
        return lum
    return lum.point(lambda v: 255 - v)


def trim(cov, margin=0, floor=12):
    mask = cov.point(lambda v: 255 if v > floor else 0)
    box = mask.getbbox()
    if not box:
        raise SystemExit('svg2ascii: the image is empty')
    l, t, r, b = box
    if margin:
        l, t = max(0, l - margin), max(0, t - margin)
        r, b = min(cov.width, r + margin), min(cov.height, b + margin)
    return cov.crop((l, t, r, b))


def parse_grid(path):
    """'#' / digits = ink, '.' or ' ' = blank. Returns list of rows of floats 0..1."""
    rows = []
    for line in open(path, encoding='utf-8').read().split('\n'):
        if line.strip() == '' or line.startswith('//'):
            continue
        row = []
        for ch in line:
            if ch == '#':
                row.append(1.0)
            elif ch.isdigit():
                row.append(int(ch) / 9.0)
            else:
                row.append(0.0)
        rows.append(row)
    w = max(len(r) for r in rows)
    return [r + [0.0] * (w - len(r)) for r in rows]


# ----------------------------------------------------------------------------- sampling
def sample(cov, style, width, aspect, rows_max=None, valign='bottom'):
    """Resample coverage to the style's sub-pixel grid; returns rows of floats 0..1."""
    sx, sy = STYLES[style]
    gw = width * sx
    # on screen a sub-pixel is (cell_w/sx) wide and (cell_h/sy) tall, cell_w = aspect*cell_h
    subpx_aspect = aspect * sy / sx                      # width / height of one sub-pixel
    gh = gw * (cov.height / cov.width) * subpx_aspect
    rows = math.ceil(gh / sy)
    if rows_max and rows > rows_max:                     # fit inside rows_max instead
        rows = rows_max
        gh = rows * sy
        gw = gh * (cov.width / cov.height) / subpx_aspect
    gw_i, gh_i = max(1, round(gw)), max(1, round(gh))
    small = cov.resize((gw_i, gh_i), Image.BOX)
    canvas = Image.new('L', (width * sx, rows * sy), 0)
    x = (canvas.width - gw_i) // 2
    y = {'top': 0, 'center': (canvas.height - gh_i) // 2, 'bottom': canvas.height - gh_i}[valign]
    canvas.paste(small, (x, y))
    px = canvas.load()
    return [[px[i, j] / 255.0 for i in range(canvas.width)] for j in range(canvas.height)]


def fit_grid(grid, style):
    """Pad a hand grid so its size is a multiple of the style's cell."""
    sx, sy = STYLES[style]
    w = max(len(r) for r in grid)
    w += (-w) % sx
    rows = [r + [0.0] * (w - len(r)) for r in grid]
    while len(rows) % sy:
        rows.insert(0, [0.0] * w)                        # pad on top: keep the bird perched
    return rows


# ----------------------------------------------------------------------------- cleanup
def neighbours(on, x, y):
    h, w = len(on), len(on[0])
    n = 0
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                yy, xx = y + dy, x + dx
                if 0 <= yy < h and 0 <= xx < w and on[yy][xx]:
                    n += 1
    return n


def clean(on, passes=1, fill=False):
    for _ in range(passes):
        nxt = [row[:] for row in on]
        for y, row in enumerate(on):
            for x, v in enumerate(row):
                n = neighbours(on, x, y)
                if v and n <= 1:
                    nxt[y][x] = False                    # crumb
                elif fill and not v and n >= 7:
                    nxt[y][x] = True                     # pinhole
        on = nxt
    return on


# ----------------------------------------------------------------------------- packing
def pack(levels, style, threshold, ramp, blank, do_clean, do_fill):
    sx, sy = STYLES[style]
    h, w = len(levels), len(levels[0])
    if style == 'shaded':
        steps = len(ramp) - 1
        out = []
        for row in levels:
            out.append(''.join(ramp[min(steps, int(round(v * steps)))] for v in row))
        return out
    on = [[v >= threshold for v in row] for row in levels]
    if do_clean or do_fill:
        on = clean(on, passes=1, fill=do_fill) if do_clean else clean(on, passes=1, fill=True)
    out = []
    for cy in range(0, h, sy):
        line = []
        for cx in range(0, w, sx):
            if style == 'half':
                ch = HALF[(int(on[cy][cx]), int(on[cy + 1][cx]))]
            elif style == 'quarter':
                bits = (on[cy][cx] << 0) | (on[cy][cx + 1] << 1) | (on[cy + 1][cx] << 2) | (on[cy + 1][cx + 1] << 3)
                ch = QUARTER[bits]
            else:  # braille: dot order 1,2,3 (left col rows 0-2), 4,5,6 (right col rows 0-2), 7 (left row 3), 8 (right row 3)
                bits = 0
                for j, mask in ((0, 1), (1, 2), (2, 4)):
                    if on[cy + j][cx]:
                        bits |= mask
                for j, mask in ((0, 8), (1, 16), (2, 32)):
                    if on[cy + j][cx + 1]:
                        bits |= mask
                if on[cy + 3][cx]:
                    bits |= 64
                if on[cy + 3][cx + 1]:
                    bits |= 128
                ch = chr(0x2800 + bits)
            if ch == ' ' or ch == BRAILLE_BLANK:
                ch = blank
            line.append(ch)
        out.append(''.join(line))
    return out


def grid_text(levels, style, threshold):
    if style == 'shaded':
        return [''.join(str(int(round(v * 9))) if v >= 0.06 else '.' for v in row) for row in levels]
    return [''.join('#' if v >= threshold else '.' for v in row) for row in levels]


def finish(lines, pad, strip_empty):
    """Equal width, no trailing whitespace: trailing blanks become `pad`."""
    if strip_empty:
        while lines and lines[0].strip(' ' + BRAILLE_BLANK) == '':
            lines.pop(0)
        while lines and lines[-1].strip(' ' + BRAILLE_BLANK) == '':
            lines.pop()
    width = max(len(l) for l in lines)
    out = []
    for l in lines:
        l = l.ljust(width)
        core = l.rstrip(' ' + BRAILLE_BLANK)
        out.append(core + pad * (width - len(core)))
    return out


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('input', help='mark: .svg, .png, or a .grid text file (hand-tuned sub-pixel grid)')
    ap.add_argument('--style', choices=sorted(STYLES), default='half')
    ap.add_argument('--width', type=int, default=48, help='columns (ignored for .grid input)')
    ap.add_argument('--rows', type=int, help='fit inside this many rows as well')
    ap.add_argument('--threshold', type=float, default=0.5, help='coverage needed to light a sub-pixel (0..1)')
    ap.add_argument('--aspect', type=float, default=0.5, help='terminal cell width/height; 0.5 is typical')
    ap.add_argument('--valign', choices=['top', 'center', 'bottom'], default='bottom')
    ap.add_argument('--margin', type=int, default=0, help='source pixels of margin kept around the ink')
    ap.add_argument('--render-size', type=int, default=1024, help='SVG raster size before sampling')
    ap.add_argument('--render-tool', default=RENDER_TOOL)
    ap.add_argument('--invert', action='store_true', help='opaque PNG: light pixels are the ink')
    ap.add_argument('--clean', action='store_true', help='drop isolated sub-pixels (crumbs)')
    ap.add_argument('--fill', action='store_true', help='fill pinholes (careful: the eye is a hole)')
    ap.add_argument('--ramp', default=SHADE_RAMP, help='shaded style ramp, blank first')
    ap.add_argument('--blank', help='interior blank (default: space, braille: U+2800)')
    ap.add_argument('--pad', help="trailing blank (default: ' ' for block styles, U+2800 for braille)")
    ap.add_argument('--grid', action='store_true', help="emit the sub-pixel grid as '#'/'.' for hand-tuning")
    ap.add_argument('--out', help='write here instead of stdout')
    a = ap.parse_args()

    blank = a.blank if a.blank is not None else (BRAILLE_BLANK if a.style == 'braille' else ' ')
    pad = a.pad if a.pad is not None else (BRAILLE_BLANK if a.style == 'braille' else ' ')

    if a.input.endswith('.grid'):
        levels = fit_grid(parse_grid(a.input), a.style)
    else:
        src = a.input
        if src.lower().endswith('.svg'):
            src = render_svg(src, a.render_size, a.render_tool)
        cov = trim(coverage_image(src, a.invert), a.margin)
        levels = sample(cov, a.style, a.width, a.aspect, a.rows, a.valign)

    if a.grid:
        lines = grid_text(levels, a.style, a.threshold)
    else:
        lines = finish(pack(levels, a.style, a.threshold, a.ramp, blank, a.clean, a.fill), pad, strip_empty=True)
    text = '\n'.join(lines) + '\n'
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, 'w', encoding='utf-8') as f:
            f.write(text)
        log(f'{a.out}: {len(lines)} rows x {len(lines[0])} cols ({a.style})')
    else:
        sys.stdout.write(text)


if __name__ == '__main__':
    main()
