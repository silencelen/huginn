#!/usr/bin/env python3
"""build.py — regenerates every file in r2-05-fold from the geometry below.

    python3 build.py            # SVGs, grid.txt, the three text renderings
    python3 build.py --preview  # ... and preview.png (needs node + playwright, ImageMagick, PIL)

The mark lives on a 24 x 16 half-block lattice over the 108 box: column i is x = 4.5*i,
half-row j is y = 17 + 3.25*j, so the 16 half-rows cover exactly the canon box height
(y 17..69) and the bird uses columns 3..21 (x 13.5..94.5). Every silhouette vertex is a
lattice point P(i, j). grid.txt is the silhouette rasterised onto that lattice (cell ink if
>= 50 % covered, 8x8 supersampled) and onto its 2x refinement (48 x 32, for braille); the
text renderings are read straight off those grids with ../../marks-b/mark-04-pixel/grid2text.py.
The fold is a 2-unit slit (hole) that opens at the shoulder vertex and ends mid-tail; it is
dropped below 32 px, so mark-32.svg, mark-16.svg, glyph*.svg and the grids are solid.
No dependencies beyond Python 3 for the files; the preview uses the tools in ../../tools.
"""
import math, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
EXPL = os.path.normpath(os.path.join(HERE, '..', '..'))
INK, BONE, BG = '#161310', '#E8E2DA', '#12100F'
IDLE, WORKING, ATTENTION = '#7B8794', '#C8A45C', '#F3D58F'

# ---------------------------------------------------------------- lattice and geometry
CW, CH, Y0 = 4.5, 3.25, 17.0
COLS, ROWS = 24, 16


def P(i, j):
    return (4.5 * i, round(Y0 + CH * j, 3))


# silhouette: 12 vertices clockwise from the bill tip (the canon's order), all lattice points
SIL = [P(21, 5),   # bill tip
       P(19, 3),   # upper bill
       P(16, 1),   # crown front
       P(12, 0),   # crown back
       P(10, 3),   # nape
       P(9, 4),    # shoulder  (the fold opens here)
       P(3, 13),   # tail tip
       P(4, 16),   # tail bottom
       P(8, 13),   # under-tail
       P(12, 14),  # belly
       P(15, 11),  # breast
       P(16, 6)]   # chin
SHOULDER = 5
EYE_C, EYE_R = (65.25, 25.125), 3.0          # centre of lattice cell (14, 2): one half-cell hole
FOLD = [SIL[SHOULDER], P(10, 9), P(5, 13)]   # slit centreline: shoulder -> flap corner -> wing tip
FOLD_W = 1.0                                  # half-width: the gap is 2 units


def fmt(p):
    return f'{p[0]:g},{p[1]:g}'


def poly_d(pts):
    return 'M' + ' L'.join(fmt(p) for p in pts) + ' Z'


def circle_d(c, r):
    return f'M{c[0]-r:g},{c[1]:g} a{r},{r} 0 1,0 {2*r:g},0 a{r},{r} 0 1,0 {-2*r:g},0 Z'


def offset_polyline(pts, w):
    """closed polygon of half-width w around an open polyline, mitred bends, square ends"""
    def unit(a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy)
        return dx / L, dy / L
    left, right, n = [], [], len(pts)
    for k in range(n):
        if k == 0 or k == n - 1:
            u = unit(pts[max(k - 1, 0)], pts[min(k + 1, n - 1)])
            nx, ny = -u[1], u[0]
        else:
            u1, u2 = unit(pts[k - 1], pts[k]), unit(pts[k], pts[k + 1])
            n1, n2 = (-u1[1], u1[0]), (-u2[1], u2[0])
            mx, my = n1[0] + n2[0], n1[1] + n2[1]
            L = math.hypot(mx, my)
            mx, my = mx / L, my / L
            c = mx * n1[0] + my * n1[1]
            nx, ny = mx / c, my / c
        left.append((pts[k][0] + nx * w, pts[k][1] + ny * w))
        right.append((pts[k][0] - nx * w, pts[k][1] - ny * w))
    return left + right[::-1]


def line_x(p, d, a, b):
    """intersection of the line p + t*d with the segment a-b, or None"""
    ex, ey = b[0] - a[0], b[1] - a[1]
    den = d[0] * ey - d[1] * ex
    if abs(den) < 1e-9:
        return None
    t = ((a[0] - p[0]) * ey - (a[1] - p[1]) * ex) / den
    s = ((a[0] - p[0]) * d[1] - (a[1] - p[1]) * d[0]) / den
    return (p[0] + t * d[0], p[1] + t * d[1]) if -1e-9 <= s <= 1 + 1e-9 else None


def fold_outline(sil, k, pts, w):
    """splice a slit that opens at vertex k of the clockwise polygon sil into its ring"""
    off = offset_polyline(pts, w)
    n = len(pts)
    left, right = off[:n], off[n:][::-1]
    d = (pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
    prev, nxt = sil[k - 1], sil[(k + 1) % len(sil)]
    hits = {}
    for side, p0 in (('L', left[0]), ('R', right[0])):
        for name, a, b in (('prev', prev, sil[k]), ('next', sil[k], nxt)):
            h = line_x(p0, d, a, b)
            if h is not None and all(v[0] != name for v in hits.values()):
                hits[side] = (name, h)
                break
    (sideA, (_, hA)), (sideB, (_, hB)) = sorted(hits.items(), key=lambda kv: kv[1][0] != 'prev')
    A = left if sideA == 'L' else right
    B = right if sideA == 'L' else left
    return sil[:k] + [hA] + A[1:] + B[1:][::-1] + [hB] + sil[k + 1:]


RING = fold_outline(SIL, SHOULDER, FOLD, FOLD_W)     # the mark's outer ring, 17 vertices

# the compact head drawing (16-22 px): head, bill and neck in 10 edges, near-square box of a
# plain 108 viewBox (x 14-105, y 24-90), same facets as the mark's head, eye hole r 6.5
HEAD = [(105, 51.5), (67, 57), (52, 90), (15, 78), (14, 46), (21.5, 40.5), (36.5, 24), (67, 29.5), (90, 40.5)]
HEAD_EYE = ((56, 42), 6.5)

# the glyph tile (64 box): rounded square minus this head and breast, plus a 4x4 eye cell
GLYPH_RX = 14
GLYPH_HOLE = [(21, 18), (36.5, 20.5), (47.5, 26), (55, 31.5), (36.5, 34), (43, 47), (41, 64), (9, 64), (10, 29), (13.5, 26)]
GLYPH_EYE = (28, 24, 4)      # x, y, size: whole pixels at 16, 32, 64


def svg(paths, fill=INK, bg=None, size=512, vb='0 0 108 108', tr='translate(2,11)', comment='', bg_rx=None):
    c = f'  <!-- {comment} -->\n' if comment else ''
    w = vb.split()[2]
    rx = f' rx="{bg_rx}"' if bg_rx else ''
    bgr = f'  <rect width="{w}" height="{w}"{rx} fill="{bg}"/>\n' if bg else ''
    g = f'<g transform="{tr}">' if tr else '<g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{vb}">\n{c}{bgr}'
            f'  {g}\n    <path fill="{fill}" fill-rule="evenodd" d="{" ".join(paths)}"/>\n  </g>\n</svg>\n')


# ---------------------------------------------------------------- rasterising
def inside_poly(pt, poly):
    x, y = pt
    inside = False
    for k in range(len(poly)):
        x1, y1 = poly[k]
        x2, y2 = poly[(k + 1) % len(poly)]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def inked(pt, polys, circles):
    if sum(inside_poly(pt, p) for p in polys) % 2 == 0:
        return False
    return not any(math.hypot(pt[0] - c[0], pt[1] - c[1]) < r for c, r in circles)


def raster(cols, rows, cw, ch, x0, y0, polys, circles=(), ss=8, thr=0.5):
    out = []
    for j in range(rows):
        row = ''
        for i in range(cols):
            hit = sum(inked((x0 + cw * (i + (sx + .5) / ss), y0 + ch * (j + (sy + .5) / ss)), polys, circles)
                      for sy in range(ss) for sx in range(ss))
            row += '#' if hit / (ss * ss) >= thr else '.'
        out.append(row)
    return out


def dist_seg(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)


def carve(grid, cw, ch, y0, line, w):
    """clear every ink cell whose centre lies within w of the polyline: the fold as a one-cell gap"""
    out = []
    for j, row in enumerate(grid):
        r = ''
        for i, c in enumerate(row):
            centre = (cw * (i + .5), y0 + ch * (j + .5))
            near = min(dist_seg(centre, line[k], line[k + 1]) for k in range(len(line) - 1)) < w
            r += '.' if (c == '#' and near) else c
        out.append(r)
    return out


# ---------------------------------------------------------------- files
def write(name, text):
    with open(os.path.join(HERE, name), 'w', encoding='utf-8') as f:
        f.write(text)


def build_files():
    mark_paths = [poly_d(RING), circle_d(EYE_C, EYE_R)]
    solid_paths = [poly_d(SIL), circle_d(EYE_C, EYE_R)]
    note = ('Huginn mark, r2-05-fold: the faceted raven on a 24x16 half-block lattice (x = 4.5i, y = 17 + 3.25j) '
            'plus ONE interior cut, a 2-unit slit that opens at the shoulder and separates the folded wing from the '
            'body. ONE evenodd path: the outer ring (12 lattice vertices + the slit) and a circular eye hole. '
            'Box x 13.5-94.5, y 17-69; translate(2,11). Ink #161310. Below 32 px use mark-32.svg (no fold).')
    write('mark.svg', svg(mark_paths, comment=note))
    write('mark-inverse.svg', svg(mark_paths, fill=BONE, comment='Inverse: bone mark for dark grounds (#12100F). Same path as mark.svg.'))
    write('mark-tile.svg', svg(mark_paths, bg=BONE, tr='translate(0,11)',
                               comment='App-icon master: ink mark centred on the bone tile (12.5 % inset left/right, 26 % top/bottom). Same path as mark.svg.'))
    write('mark-32.svg', svg(solid_paths, comment='The silhouette without the fold: for 32 px and below (favicon-32, tray at 200 %), and the source of grid.txt. Same 12 vertices.'))
    write('mark-16.svg', svg([poly_d(HEAD), circle_d(*HEAD_EYE)], tr=None, comment=(
        'mark-16: the compact drawing for 16-22 px (tray, favicon-16): head, bill and neck in 9 straight edges '
        'with the mark\'s crown/bill facets, plus a circular eye hole r 6.5; near-square box x 14-105, y 24-90 of a plain 108 viewBox, no fold.')))
    # glyph: container minus head hole plus eye cell, one evenodd path
    rx = GLYPH_RX
    cont = (f'M{rx},0 H{64-rx} A{rx},{rx} 0 0 1 64,{rx} V{64-rx} A{rx},{rx} 0 0 1 {64-rx},64 H{rx} '
            f'A{rx},{rx} 0 0 1 0,{64-rx} V{rx} A{rx},{rx} 0 0 1 {rx},0 Z')
    ex, ey, es = GLYPH_EYE
    eye = f'M{ex},{ey} h{es} v{es} h-{es} Z'
    gpaths = [cont, poly_d(GLYPH_HOLE), eye]
    gnote = ('Glyph tile, r2-05-fold: a rounded square (rx 14) with THIS variant\'s head and breast cut out in 10 straight edges '
             '(the mark\'s crown, bill and chin facets scaled 0.83), breast opening onto the bottom edge; eye a 4x4 cell at '
             'x 28-32, y 24-28 (whole pixels at 16/32/64). ONE evenodd path: container - head + eye. No fold. Ink #161310.')
    write('glyph.svg', svg(gpaths, vb='0 0 64 64', tr=None, comment=gnote))
    write('glyph-inverse.svg', svg(gpaths, fill=BONE, bg=INK, vb='0 0 64 64', tr=None,
                                   comment='Inverse: the same path in bone on an ink ground; the raven is the dark ground showing through.'))

    # grids: the solid silhouette (no fold) on the 24x16 lattice and its 2x refinement
    g24 = raster(COLS, ROWS, CW, CH, 0, Y0, [SIL], [(EYE_C, EYE_R)])
    g48 = carve(raster(COLS * 2, ROWS * 2, CW / 2, CH / 2, 0, Y0, [SIL], [(EYE_C, EYE_R)]), CW / 2, CH / 2, Y0, FOLD, 1.0)
    header = [
        '// r2-05-fold: the mark rasterised onto its own lattice. Generated by build.py; edit build.py, not this.',
        '// Lattice: 24 columns x 16 half-rows over the 108 box, cell 4.5 x 3.25 units (x = 4.5i, y = 17 + 3.25j).',
        '// Every silhouette vertex is a lattice point; a cell is ink when >= 50 % of it is inside the path.',
        '// fold24: the half-block / 7-bit source (one row of text = two half-rows), solid: the fold drops at',
        '// <= 32 px and a one-cell gap here read as a gash. fold48: the same path on the 2x lattice (2.25 x 1.625),',
        '// the braille source (one braille cell = 2 x 4 fine cells), WITH the fold as a one-dot gap (every fine',
        '// cell whose centre is within 1 unit of the slit centreline). Both are 24 text columns x 8 text rows.',
        '// The eye is one cell in fold24 and a 3 x 4 hole in fold48.',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid.txt fold24 --frame > ascii-halfblock.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py braille   grid.txt fold48         > ascii-braille.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py ascii     grid.txt fold24 --frame > ascii-7bit.txt',
        '']
    write('grid.txt', '\n'.join(header) + f'@fold24 {COLS}x{ROWS}\n' + '\n'.join(g24) + f'\n\n@fold48 {COLS*2}x{ROWS*2}\n' + '\n'.join(g48) + '\n')

    # text renderings straight from grid.txt
    sys.path.insert(0, os.path.join(EXPL, 'marks-b', 'mark-04-pixel'))
    import grid2text as gt
    grids = gt.load(os.path.join(HERE, 'grid.txt'))
    write('ascii-halfblock.txt', '\n'.join(gt.frame(gt.halfblock(grids['fold24']))) + '\n')
    write('ascii-braille.txt', '\n'.join(gt.braille(grids['fold48'])) + '\n')
    write('ascii-7bit.txt', '\n'.join(gt.frame(gt.ascii(grids['fold24']), plain=True)) + '\n')
    return g24, g48


# ---------------------------------------------------------------- preview
def grid_overlay_svg(g24):
    """the vector mark (ink, 55 %) over its own 24x16 rendering (cells in muted grey) on bone"""
    cells = ''.join(f'<rect x="{CW*i:g}" y="{Y0+CH*j:g}" width="{CW}" height="{CH}" fill="#9A8F84"/>'
                    for j, row in enumerate(g24) for i, c in enumerate(row) if c == '#')
    lines = ''.join(f'<line x1="{CW*i:g}" y1="{Y0}" x2="{CW*i:g}" y2="{Y0+CH*ROWS}" />' for i in range(COLS + 1))
    lines += ''.join(f'<line x1="0" y1="{Y0+CH*j:g}" x2="108" y2="{Y0+CH*j:g}" />' for j in range(ROWS + 1))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">'
            f'<rect width="108" height="108" fill="{BONE}"/><g transform="translate(2,11)">{cells}'
            f'<g stroke="#C9BFB4" stroke-width="0.25">{lines}</g>'
            f'<path fill="{INK}" fill-opacity="0.6" fill-rule="evenodd" d="{poly_d(RING)} {circle_d(EYE_C, EYE_R)}"/>'
            f'<path fill="none" stroke="{INK}" stroke-width="0.4" fill-rule="evenodd" d="{poly_d(RING)} {circle_d(EYE_C, EYE_R)}"/>'
            f'</g></svg>')


def launcher_svg():
    """adaptive-icon look: bone tile, mark at scale 0.72 about (52,43) (the canon launcher group), circle mask d 72"""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">'
            f'<defs><clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
            f'<g clip-path="url(#m)"><rect width="108" height="108" fill="{BONE}"/>'
            f'<g transform="translate(2,11) translate(52,43) scale(0.72) translate(-52,-43)">'
            f'<path fill="{INK}" fill-rule="evenodd" d="{poly_d(RING)} {circle_d(EYE_C, EYE_R)}"/></g></g>'
            f'<circle cx="54" cy="54" r="33" fill="none" stroke="#C8A45C" stroke-width="0.6" stroke-dasharray="1.5 1.5"/></svg>')


def pre_html(files, px=16.61):
    """the three text renderings in a <pre>, DejaVu Sans Mono, line-height 1, on the dark ground.
    16.61 px makes the advance exactly 10 px (1233/2048 em), so the font-drawn blocks meet without seams."""
    import html
    blocks = ''.join(f'<div><div class=l>{lbl}</div><pre>{html.escape(open(os.path.join(HERE, f), encoding="utf-8").read().rstrip())}</pre></div>'
                     for f, lbl in files)
    return (f'<!doctype html><html><head><meta charset="utf-8"><style>body{{margin:0;background:{BG};color:{BONE};'
            f'font-family:"DejaVu Sans Mono",monospace}}#w{{display:flex;gap:40px;padding:24px;align-items:flex-start}}'
            f'pre{{margin:0;font-family:"DejaVu Sans Mono",monospace;font-size:{px}px;line-height:1;white-space:pre}}'
            f'.l{{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#8A8177;margin-bottom:10px}}</style></head>'
            f'<body><div id=w>{blocks}</div></body></html>')


def render(path, prefix, sizes, bg):
    subprocess.run(['node', os.path.join(EXPL, 'tools', 'render.mjs'), path, prefix, ','.join(map(str, sizes)), bg],
                   check=True, stdout=subprocess.DEVNULL)


def build_preview(g24):
    from PIL import Image, ImageDraw, ImageFont
    tmp = tempfile.mkdtemp(prefix='r2-05-')
    T = lambda n: os.path.join(tmp, n)
    F = lambda n: os.path.join(HERE, n)
    render(F('mark.svg'), T('m-bone'), [512, 64], BONE)
    render(F('mark-inverse.svg'), T('m-dark'), [512, 64], BG)
    render(F('mark-32.svg'), T('m32-bone'), [32, 16], BONE)
    render(F('mark-inverse.svg'), T('m-dark-fold'), [32], BG)
    # inverse without the fold for the dark 32/16
    write_tmp = lambda n, s: (open(T(n), 'w').write(s), T(n))[1]
    inv32 = write_tmp('inv32.svg', svg([poly_d(SIL), circle_d(EYE_C, EYE_R)], fill=BONE))
    render(inv32, T('m32-dark'), [32, 16], BG)
    render(F('mark-16.svg'), T('h-bone'), [22, 16], BONE)
    inv16 = write_tmp('inv16.svg', svg([poly_d(HEAD), circle_d(*HEAD_EYE)], fill=BONE, tr=None))
    render(inv16, T('h-dark'), [22, 16], BG)
    render(F('mark-tile.svg'), T('tile'), [128], BONE)
    tints = {'idle': IDLE, 'working': WORKING, 'attention': ATTENTION, 'bone': BONE}
    for name, col in tints.items():
        gsrc = open(F('glyph.svg'), encoding='utf-8').read().replace(INK, col)
        render(write_tmp(f'g-{name}.svg', gsrc), T(f'g-{name}'), [48, 24, 16], BG)
    render(F('glyph.svg'), T('g-ink'), [128, 48, 24, 16], BONE)
    render(F('glyph-inverse.svg'), T('g-inv'), [128], BG)
    render(write_tmp('launcher.svg', launcher_svg()), T('launch'), [192, 48], BG)
    render(write_tmp('overlay.svg', grid_overlay_svg(g24)), T('overlay'), [512], BONE)
    render(write_tmp('pre.html', pre_html([('ascii-halfblock.txt', 'half-block 24x8 (fold24)'),
                                           ('ascii-braille.txt', 'braille 24x8 (fold48, carries the fold)'),
                                           ('ascii-7bit.txt', '7-bit ascii 24x8 (fold24)')], 16.61)), T('pre'), [1100], BG)
    render(write_tmp('pre2.html', pre_html([('ascii-halfblock.txt', 'half-block at a 2x cell'),
                                            ('ascii-braille.txt', 'braille at 2x')], 33.22)), T('pre2'), [1400], BG)

    # ------------------------------------------------ compose
    W = 1700
    sheet = Image.new('RGB', (W, 2100), '#6F6A64')
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 13)
        big = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 18)
    except OSError:
        font = big = ImageFont.load_default()

    def put(img, x, y, scale=1, label=None, frame=None):
        if isinstance(img, str):
            img = Image.open(img).convert('RGBA')
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        if frame:
            d.rectangle([x - 1, y - 1, x + img.width, y + img.height], outline=frame)
        sheet.paste(img, (x, y), img)
        if label:
            d.text((x, y + img.height + 6), label, fill='#F2ECE4', font=font)
        return img.width, img.height

    def title(x, y, s):
        d.text((x, y), s, fill='#F2ECE4', font=big)

    def sub(x, y, s):
        d.text((x, y), s, fill='#D9D1C7', font=font)

    title(24, 18, 'r2-05-fold  -  the faceted raven with one fold cut')
    sub(24, 44, 'A: mark 512 on bone and on #12100F; mark 64 / 32 / 16 and head 22 / 16 at 4x (hard pixels).  B: glyph tile tinted on the app ground.  C: the terminal renderings.  D: vector over grid.')

    # A ---------------------------------------------------------------- marks
    yA = 72
    put(T('m-bone-512.png'), 24, yA, label='mark.svg  512 on bone')
    put(T('m-dark-512.png'), 560, yA, label='mark-inverse.svg  512 on #12100F')
    x, y = 1100, yA
    sub(x, y, 'mark at 64 / 32 / 16, 4x')
    y += 22
    for nm, lbl in (('m-bone-64', '64 (mark.svg)'), ('m32-bone-32', '32 (mark-32.svg, no fold)'), ('m32-bone-16', '16 (mark-32.svg, no fold)')):
        w, h = put(T(nm + '.png'), x, y, 4, label=lbl, frame='#4A4541')
        put(T(nm.replace('bone', 'dark') + '.png'), x + w + 16, y, 4, frame='#4A4541')
        y += h + 30
    x, y = 24, yA + 512 + 40
    sub(x, y, 'head drawing mark-16.svg at 22 / 16, 4x')
    y += 22
    xx = x
    for nm, lbl in (('h-bone-22', '22'), ('h-bone-16', '16')):
        w, h = put(T(nm + '.png'), xx, y, 4, label=lbl, frame='#4A4541')
        put(T(nm.replace('bone', 'dark') + '.png'), xx + w + 12, y, 4, frame='#4A4541')
        xx += 2 * w + 44

    # B ---------------------------------------------------------------- glyph
    yB = 790
    title(24, yB, 'B: glyph tile at 48 / 24 / 16 on the app ground, tinted idle #7B8794 / working #C8A45C / attention #F3D58F / bone; 3x, then 1x')
    panel = Image.new('RGB', (W - 48, 430), BG)
    sheet.paste(panel, (24, yB + 30))
    gx = 48
    for name in ('idle', 'working', 'attention', 'bone'):
        xx = gx
        for s in (48, 24, 16):
            w, h = put(T(f'g-{name}-{s}.png'), xx, yB + 60 + (48 - s) * 3 // 2, 3)
            xx += w + 12
        d.text((gx, yB + 215), name, fill='#8A8177', font=font)
        gx += 300
    # 1x row inside the panel
    xx = 48
    for name in ('idle', 'working', 'attention', 'bone'):
        for s in (48, 24, 16):
            w, h = put(T(f'g-{name}-{s}.png'), xx, yB + 250 + (48 - s))
            xx += w + 8
        xx += 40
    d.text((48, yB + 335), '1x: 48 / 24 / 16 per state', fill='#8A8177', font=font)
    # right side of the panel: 128s on their own grounds, launcher, tile
    put(T('g-ink-128.png'), 1250, yB + 46, label='glyph 128')
    put(T('g-inv-128.png'), 1420, yB + 46, label='glyph-inverse 128')
    put(T('launch-192.png'), 1250, yB + 216, label='launcher: mask d72, safe ring r33')
    put(T('tile-128.png'), 1470, yB + 216, label='mark-tile.svg 128')
    put(T('launch-48.png'), 1620, yB + 216, 1, label='48', frame='#4A4541')

    # C ---------------------------------------------------------------- text
    yC = yB + 30 + 430 + 40
    title(24, yC, 'C: terminal renderings, exact from grid.txt: <pre>, DejaVu Sans Mono 16.61 px, line-height 1; 2x below')
    pre = Image.open(T('pre-1100.png')).convert('RGB')
    bbox = Image.eval(pre.convert('L'), lambda v: 255 if v > 24 else 0).getbbox()
    pre = pre.crop((0, 0, min(pre.width, bbox[2] + 30), min(pre.height, bbox[3] + 30)))
    sheet.paste(pre, (24, yC + 30))
    pre2 = Image.open(T('pre2-1400.png')).convert('RGB')
    bbox = Image.eval(pre2.convert('L'), lambda v: 255 if v > 24 else 0).getbbox()
    pre2 = pre2.crop((0, 0, min(pre2.width, bbox[2] + 30), min(pre2.height, bbox[3] + 30)))
    sheet.paste(pre2, (24, yC + 40 + pre.height))

    # D ---------------------------------------------------------------- overlay
    ox = 24 + max(pre.width, pre2.width) + 30
    yD = yC
    title(ox, yD, 'D: vector over its own grid')
    put(T('overlay-512.png'), ox, yD + 30, label='mark.svg (60 % ink + outline) over the fold24')
    sub(ox, yD + 30 + 512 + 24, 'cells (grey); lattice 4.5 x 3.25 units')
    h_total = max(yD + 30 + 512 + 60, yC + 40 + pre.height + pre2.height + 30)
    sheet = sheet.crop((0, 0, W, h_total))
    out = os.path.join(HERE, 'preview.png')
    sheet.save(out, optimize=True)
    print(out, sheet.size, os.path.getsize(out) // 1024, 'KB')


if __name__ == '__main__':
    g24, g48 = build_files()
    print('\n'.join(g24))
    if '--preview' in sys.argv:
        build_preview(g24)
