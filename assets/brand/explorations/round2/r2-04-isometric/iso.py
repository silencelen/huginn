#!/usr/bin/env python3
"""iso.py — r2-04-isometric: one polygon on a 30/60/90 grid, and everything that falls out of it.

    python3 iso.py build            # writes every mark/glyph SVG, grid.txt and the text renderings
    python3 iso.py pack grid.txt    # re-pack grid.txt into the text renderings (what `build` does last)
    python3 iso.py check            # re-pack grid.txt and diff against the shipped text files

The grid. A terminal cell is one column wide and one text row (two half-rows) tall. On this
variant a column is 2*sqrt(3) = 3.464 units of the 108 box and a half-row is 3 units, so a cell
is 3.464 x 6 (aspect 1 : 1.732, the sqrt(3) cell; DejaVu Sans Mono at line-height 1 is 1 : 1.66,
a 10x18 px terminal 1 : 1.8). That makes

    one column by one text row     a 60 degree edge  ->  exactly a cell diagonal  ->  one of ◢ ◣ ◤ ◥
    three columns by one text row  a 30 degree edge  ->  three half-row steps (a stair), no glyph
    vertical / horizontal                             ->  exact on the cell grid

Every vertex of the mark sits on an integer (column, half-row) of a 24 x 16 grid at x0 = 10.5,
y0 = 18.5 (local coordinates of the translate(2,11) group), so the box is x 10.5-93.6, y 18.5-66.5.
Every 60 degree edge starts and ends on an even half-row, so it is whole cell diagonals.

Rasterisation is exact coverage: each half-row cell (and each triangle half of a text cell) gets
the area of the polygon inside it, minus the eye circle; a cell is a triangle glyph only when its
coverage IS that triangle (error < 2 %), else it is whatever half-block has the least error.
grid.txt is the result; the text files are packed from grid.txt and nothing else.
No dependencies beyond Python 3.
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
INK, BONE, BG = '#161310', '#E8E2DA', '#12100F'

# ----------------------------------------------------------------------------- the grid
COLS, ROWS = 24, 16                 # columns x half-rows
CW, HR = 2 * math.sqrt(3), 3.0      # column width, half-row height (units of the 108 box)
X0, Y0 = 10.5, 18.5                 # local coordinates (inside the translate(2,11) group)

def gx(c): return X0 + c * CW
def gy(r): return Y0 + r * HR
def P(c, r): return (gx(c), gy(r))

# ----------------------------------------------------------------------------- the mark
# (column, half-row), anticlockwise on screen from the bill tip: over the crown, down the back,
# under the tail and belly, up the breast, back along the gonys to the tip.
MARK = [
    (24, 4),   # bill tip
    (18, 0),   # culmen: 30 over six columns, straight to the tip (the dagger)
    (12, 0),   # crown: flat, six columns
    (10, 4),   # back of the head: 60
    (4, 8),    # back and folded wing: 30 over six columns
    (0, 16),   # tail: 60 down to the tip
    (1, 16),   # tail end: one column flat
    (7, 12),   # under-tail: 30 up
    (10, 14),  # belly: 30 down
    (13, 12),  # belly to breast: 30 up
    (16, 6),   # breast: 60 up to the chin
    (23, 6),   # gonys: flat
]              # undercut: 60 up to the tip
EYE = ((15, 3), 3.5)   # centre in grid coordinates (a corner of four half-row cells), radius in units

ANGLES = {0, 30, 60, 90}

def check_angles(poly, name):
    bad = []
    n = len(poly)
    for i in range(n):
        (c0, r0), (c1, r1) = poly[i], poly[(i + 1) % n]
        dx, dy = (c1 - c0) * CW, (r1 - r0) * HR
        a = round(math.degrees(math.atan2(abs(dy), abs(dx))), 3)
        if a not in ANGLES: bad.append(((c0, r0), (c1, r1), a))
    if bad: raise SystemExit(f'{name}: edges off the 30/60/90 system: {bad}')

# ----------------------------------------------------------------------------- geometry
def area(poly):
    s = 0.0
    for i in range(len(poly)):
        x0, y0 = poly[i]; x1, y1 = poly[(i + 1) % len(poly)]
        s += x0 * y1 - x1 * y0
    return abs(s) / 2

def clip(poly, window):
    """Sutherland-Hodgman: clip poly (any simple polygon) to a convex window (anticlockwise or
    clockwise, any orientation handled by the sign test)."""
    def inside(p, a, b, sgn):
        return sgn * ((b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])) >= -1e-9
    def intersect(p, q, a, b):
        x1, y1 = p; x2, y2 = q; x3, y3 = a; x4, y4 = b
        den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
        return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    # orientation of the window
    sgn = 1 if sum((window[i][0] * window[(i + 1) % len(window)][1] - window[(i + 1) % len(window)][0] * window[i][1]) for i in range(len(window))) > 0 else -1
    out = list(poly)
    for i in range(len(window)):
        a, b = window[i], window[(i + 1) % len(window)]
        inp, out = out, []
        if not inp: break
        s = inp[-1]
        for e in inp:
            if inside(e, a, b, sgn):
                if not inside(s, a, b, sgn): out.append(intersect(s, e, a, b))
                out.append(e)
            elif inside(s, a, b, sgn):
                out.append(intersect(s, e, a, b))
            s = e
    return out

def circle_poly(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]

def coverage(shape, hole, window):
    """fraction of `window` covered by shape minus hole (hole lies inside shape)."""
    w = area(window)
    return (area(clip(shape, window)) - area(clip(hole, window))) / w

# ----------------------------------------------------------------------------- rasterise
TRI = {'r': '◢', 'l': '◣', 'L': '◤', 'R': '◥'}        # lower-case sits low: r = lower-right ink
TRI_HALF = {'r': ('.', '#'), 'l': ('.', '#'), 'L': ('#', '.'), 'R': ('#', '.')}   # plain fallback (top, bottom)
TRI_ASCII = {'r': '/', 'l': '\\', 'L': '/', 'R': '\\'}

def rasterise(shape, hole, cols, rows, cw, hr, x0, y0, fine=False):
    """Returns rows of chars: '#', '.', or a triangle letter (both half-rows of the text cell)."""
    out = [['.'] * cols for _ in range(rows)]
    for r in range(rows):
        for c in range(cols):
            x, y = x0 + c * cw, y0 + r * hr
            cell = [(x, y), (x + cw, y), (x + cw, y + hr), (x, y + hr)]
            out[r][c] = '#' if coverage(shape, hole, cell) >= 0.5 else '.'
    if fine: return [''.join(r) for r in out]
    # triangle test per text cell
    for t in range(0, rows, 2):
        for c in range(cols):
            x, y = x0 + c * cw, y0 + t * hr
            x1, y1 = x + cw, y + 2 * hr
            tris = {'r': [(x1, y), (x1, y1), (x, y1)], 'l': [(x, y), (x1, y1), (x, y1)],
                    'L': [(x, y), (x1, y), (x, y1)], 'R': [(x, y), (x1, y), (x1, y1)]}
            comp = {'r': 'L', 'l': 'R', 'L': 'r', 'R': 'l'}
            for k, tri in tris.items():
                if coverage(shape, hole, tri) > 0.98 and coverage(shape, hole, tris[comp[k]]) < 0.02:
                    out[t][c] = out[t + 1][c] = k
                    break
    return [''.join(r) for r in out]

# ----------------------------------------------------------------------------- pack grid.txt
def load_grids(path):
    grids, name, rows = {}, None, []
    for raw in open(path, encoding='utf-8'):
        line = raw.rstrip('\n')
        if line.startswith('@'):
            if name: grids[name] = rows
            name, rows = line[1:].split()[0], []
        elif name and line and not line.startswith('//'):
            rows.append(line)
    if name: grids[name] = rows
    return grids

def pack_half(rows, plain=False):
    out = []
    for t in range(0, len(rows), 2):
        s = ''
        for c in range(len(rows[0])):
            a, b = rows[t][c], rows[t + 1][c]
            if a in TRI:
                if not plain: s += TRI[a]; continue
                a, b = TRI_HALF[a]
            s += '█' if a == '#' and b == '#' else '▀' if a == '#' else '▄' if b == '#' else ' '
        out.append(s)
    return out

def pack_ascii(rows):
    out = []
    for t in range(0, len(rows), 2):
        s = ''
        for c in range(len(rows[0])):
            a, b = rows[t][c], rows[t + 1][c]
            if a in TRI: s += TRI_ASCII[a]; continue
            s += '#' if a == '#' and b == '#' else '"' if a == '#' else '_' if b == '#' else ' '
        out.append(s)
    return out

_BITS = {(0, 0): 0x01, (0, 1): 0x02, (0, 2): 0x04, (1, 0): 0x08, (1, 1): 0x10, (1, 2): 0x20, (0, 3): 0x40, (1, 3): 0x80}
def pack_braille(rows):
    out = []
    for y in range(0, len(rows), 4):
        s = ''
        for x in range(0, len(rows[0]), 2):
            v = 0
            for (dx, dy), bit in _BITS.items():
                if y + dy < len(rows) and x + dx < len(rows[0]) and rows[y + dy][x + dx] == '#': v |= bit
            s += chr(0x2800 + v)
        out.append(s)
    return out

def frame(lines, plain=False):
    w = len(lines[0])
    if plain: return ['+' + '-' * w + '+'] + ['|' + l + '|' for l in lines] + ['+' + '-' * w + '+']
    return ['┌' + '─' * w + '┐'] + ['│' + l + '│' for l in lines] + ['└' + '─' * w + '┘']

def pack_all(grids):
    g, f = grids['half'], grids['fine']
    return {
        'ascii-halfblock.txt': frame(pack_half(g)),
        'ascii-halfblock-plain.txt': frame(pack_half(g, plain=True)),
        'ascii-7bit.txt': frame(pack_ascii(g), plain=True),
        'ascii-braille.txt': pack_braille(f),
    }

# ----------------------------------------------------------------------------- SVG writers
def fmt(v):
    s = f'{v:.3f}'.rstrip('0').rstrip('.')
    return '0' if s == '-0' else s

def path_d(points, hole=None):
    d = 'M' + ' L'.join(f'{fmt(x)},{fmt(y)}' for x, y in points) + ' Z'
    if hole:
        kind, a = hole
        if kind == 'circle':
            cx, cy, r = a
            d += f' M{fmt(cx - r)},{fmt(cy)} a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0 a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0 Z'
        elif kind == 'square':
            x, y, s = a
            d += f' M{fmt(x)},{fmt(y)} h{fmt(s)} v{fmt(s)} h{fmt(-s)} Z'
    return d

def svg(body, comment, vb=108, w=512):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{w}" viewBox="0 0 {vb} {vb}">\n'
            f'  <!-- {comment} -->\n{body}</svg>\n')

def mark_svgs(points, eye):
    d = path_d(points, ('circle', eye))
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    box = f'box x {fmt(min(xs))}-{fmt(max(xs))}, y {fmt(min(ys))}-{fmt(max(ys))}'
    base = ('Huginn mark, r2-04-isometric. One evenodd polygon on a 30/60/90 angle system: every edge is '
            'flat, vertical, 30 or 60 degrees; every vertex is on a 24 x 16 half-row terminal grid (column 2*sqrt(3), '
            f'half-row 3 units, origin 10.5,18.5) so the half-block rendering is exact. {box}, translate(2,11). '
            'Eye: circular hole r 3.5 centred on a grid corner. Generated by iso.py; edit MARK there, not this file.')
    g = lambda fill: f'  <g transform="translate(2,11)">\n    <path fill="{fill}" fill-rule="evenodd" d="{d}"/>\n  </g>\n'
    return {
        'mark.svg': svg(g(INK), base + f' Ink {INK} on transparent.'),
        'mark-inverse.svg': svg(g(BONE), f'Inverse: bone {BONE} for dark grounds ({BG}). Same polygon as mark.svg.'),
        'mark-tile.svg': svg(f'  <rect width="108" height="108" fill="{BONE}"/>\n' + g(INK),
                             'App-icon master: ink mark on the bone tile, ~10% inset (the mark box is 83 x 48 in a 108 square). Same polygon as mark.svg.'),
        'mark-status.svg': svg(f'  <g transform="translate(2,11)">\n    <path fill="{INK}" d="{path_d(points)}"/>\n  </g>\n',
                               'Status-bar silhouette (ic_stat_huginn.xml): the polygon without the eye hole; the platform tints it.'),
    }

# ----------------------------------------------------------------------------- the head (16-22 px)
# Built from angles so the facet language is the same: column 6, half-row 3*sqrt(3) in a plain 108 viewBox.
HW, HH = 6.0, 3 * math.sqrt(3)
def H(c, r, ox, oy): return (ox + c * HW, oy + r * HH)
HEAD = [
    (15, 5),   # bill tip
    (9, 1),    # culmen: 30, straight to the tip (the dagger)
    (3, 1),    # crown: flat
    (1, 5),    # nape: 60
    (1, 13),   # back of the neck: vertical
    (4, 15),   # neck cut: 30 (the perch line)
    (8, 7),    # throat: 60 up to the chin
    (14, 7),   # gonys: flat
]              # undercut: 60 up to the tip
HEAD_EYE = ((6, 4), 6.5)

def head_svg():
    ox, oy = 12, 20                                     # box x 18-102, y 25.2-98
    pts = [H(c, r, ox, oy) for c, r in HEAD]
    (ec, er), rad = HEAD_EYE
    eye = H(ec, er, ox, oy) + (rad,)
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    d = path_d(pts, ('circle', eye))
    return svg(f'  <path fill="{INK}" fill-rule="evenodd" d="{d}"/>\n',
               'r2-04-isometric-16: the compact drawing for 16-22 px (tray, favicon-16): head, bill and neck in 8 edges (30 culmen to a dagger tip, 60 undercut) on the same '
               f'30/60/90 system (column 6, half-row 5.196) plus a circular eye hole r {rad}; near-square box x {fmt(min(xs))}-{fmt(max(xs))}, '
               f'y {fmt(min(ys))}-{fmt(max(ys))} of a plain 108 viewBox, no translate. Generated by iso.py.')

# ----------------------------------------------------------------------------- the glyph tile (64 box)
# Rounded square of ink, this head and breast cut out, opening onto the bottom edge; eye a 4 x 4 cell on whole
# pixels at 16/32/64. Column 4, half-row 2*sqrt(3) = 3.464.
GW, GH = 4.0, 2 * math.sqrt(3)
GX = 2.0   # the head sits two units right of the tile edge: left wall x 10, tip x 54
GLYPH_EYE = (24, 24, 4)   # x, y, side: whole pixels at 16, 32 and 64

def glyph_points():
    """The hole, anticlockwise on screen from the bill tip. Angles are exact by construction; the hole
    opens onto the bottom edge and its bottom-left corner follows the container's own arc (rx 14), so
    evenodd leaves no sliver outside the tile."""
    def g(c, r): return (GX + c * GW, r * GH)
    tip = g(13, 9)      # (54, 31.2)
    cul = g(7, 5)       # culmen: 30 over six columns
    crown = g(3, 5)     # crown: flat   (14, 17.3)
    nape = g(2, 7)      # nape: 60      (10, 24.2)
    chin = g(9, 11)     # (38, 38.1)
    gonys_end = g(12, 11)
    breast = g(11, 15)  # breast: 60 down and out from the chin  (46, 52)
    foot = (breast[0], 64.0)             # then vertical to the bottom edge
    wall_bottom = (nape[0], 50 + math.sqrt(14 ** 2 - (14 - nape[0]) ** 2))   # the wall meets the corner arc
    arc_end = (14.0, 64.0)
    return [tip, cul, crown, nape, wall_bottom, 'ARC', arc_end, foot, breast, chin, gonys_end]

def glyph_d():
    pts = glyph_points()
    d = ''
    for i, p in enumerate(pts):
        if p == 'ARC': continue
        prev = pts[i - 1] if i else None
        if i == 0: d += f'M{fmt(p[0])},{fmt(p[1])}'
        elif prev == 'ARC': d += f' A14,14 0 0 0 {fmt(p[0])},{fmt(p[1])}'
        else: d += f' L{fmt(p[0])},{fmt(p[1])}'
    x, y, s = GLYPH_EYE
    return d + f' Z M{x},{y} h{s} v{s} h{-s} Z'

def glyph_svgs():
    x, y, s = GLYPH_EYE
    container = 'M14,0 H50 A14,14 0 0 1 64,14 V50 A14,14 0 0 1 50,64 H14 A14,14 0 0 1 0,50 V14 A14,14 0 0 1 14,0 Z'
    d = container + ' ' + glyph_d()
    c = ('r2-04-isometric glyph: the container is the ink, the raven head and breast are the hole, the eye a 4 x 4 cell of ink '
         '(evenodd: container - raven + eye), ONE path. The head is this variant\'s: flat crown, 30 culmen straight to the tip, 60 undercut, flat gonys, '
         '60 breast, 60 nape, vertical back; the breast opens onto the bottom edge, the corner follows the tile arc. Column 4, half-row 3.464 of the 64 box, left wall x 10, tip x 54; '
         f'eye cell x {x}-{x+s}, y {y}-{y+s} (whole pixels at 16, 32, 64). Generated by iso.py.')
    return {
        'glyph.svg': svg(f'  <path fill="{INK}" fill-rule="evenodd" d="{d}"/>\n', c, vb=64),
        'glyph-inverse.svg': svg(f'  <rect width="64" height="64" fill="{INK}"/>\n  <path fill="{BONE}" fill-rule="evenodd" d="{d}"/>\n',
                                 'Inverse: the same path in bone on an ink ground; the raven is the dark ground showing through.', vb=64),
    }

# ----------------------------------------------------------------------------- build
def write(name, text):
    open(os.path.join(HERE, name), 'w', encoding='utf-8').write(text)
    print(name)

def build():
    check_angles(MARK, 'MARK')
    pts = [P(c, r) for c, r in MARK]
    (ec, er), rad = EYE
    eye = (gx(ec), gy(er), rad)
    for n, t in mark_svgs(pts, eye).items(): write(n, t)
    write('mark-16.svg', head_svg())
    for n, t in glyph_svgs().items(): write(n, t)
    hole = circle_poly(*eye)
    half = rasterise(pts, hole, COLS, ROWS, CW, HR, X0, Y0)
    fine = rasterise(pts, hole, COLS * 2, ROWS * 2, CW / 2, HR / 2, X0, Y0, fine=True)
    head = ['// r2-04-isometric: the mark rasterised onto its own grid. Generated by iso.py from MARK; do not edit.',
            '// @half: 24 columns x 16 half-rows (one text row = two half-rows). # ink, . ground,',
            '//   r l L R = a text cell that is exactly one 60-degree diagonal: r = ◢ (lower-right ink), l = ◣, L = ◤, R = ◥;',
            '//   the letter is written in both half-rows of its cell. Packed into ascii-halfblock.txt (◢◣◤◥ ▀▄█),',
            '//   ascii-halfblock-plain.txt (triangles replaced by their nearest half-block) and ascii-7bit.txt (#"_ and / \\).',
            '// @fine: the same polygon at 2 x 2 per half-row cell (48 x 32), # and . only: the braille source (2 x 4 dots per cell).',
            '//   python3 iso.py pack grid.txt   re-packs; python3 iso.py check   verifies the shipped files.',
            '', '@half 24x16'] + half + ['', '@fine 48x32'] + fine
    write('grid.txt', '\n'.join(head) + '\n')
    pack(os.path.join(HERE, 'grid.txt'))

def pack(path, verify=False):
    grids = load_grids(path)
    ok = True
    for n, lines in pack_all(grids).items():
        text = '\n'.join(lines) + '\n'
        if verify:
            cur = open(os.path.join(HERE, n), encoding='utf-8').read()
            print(n, 'ok' if cur == text else 'DIFFERS'); ok &= cur == text
        else:
            write(n, text)
    if verify and not ok: sys.exit(1)

if __name__ == '__main__':
    a = sys.argv[1:]
    if not a or a[0] == 'build': build()
    elif a[0] == 'pack': pack(a[1])
    elif a[0] == 'check': pack(os.path.join(HERE, 'grid.txt'), verify=True)
    else: print(__doc__)
