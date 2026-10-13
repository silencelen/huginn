#!/usr/bin/env python3
"""build.py - regenerates every file in round3/fold-system from the geometry below.

    python3 build.py            # SVGs, grids, text renderings, the alignment proof
    python3 build.py --preview  # ... and preview.png (needs node + playwright, PIL)
    python3 build.py --look DIR # quick iteration sheets into DIR (scratchpad)

Lattice: Snapped's. 24 columns x 16 half-rows over the 108 box, cell 4.5 x 3.4 units:
column i is x = 4.5 i, half-row j is y = 14.4 + 3.4 j (path coordinates, before the
translate(2,11)). Every silhouette vertex of both birds is a lattice point P(i, j) and every
edge is horizontal, vertical or a p:q diagonal with p or q even (never through a cell centre),
so rasterising a silhouette onto its own lattice at >= 50 % coverage is unambiguous.

Two birds share the drawing language (twelve-edge faceted body, round eye hole, one hairline
fold along the folded wing):
  PERCHED  the r2-05 fold raven re-snapped to Snapped's twelve vertices (mark-perched.svg);
           its solid silhouette IS r2-01-snapped's mark, so it rasterises to Snapped's grid.
  UPRIGHT  the same bird standing up (owner's amendment): chest forward, head raised, bill
           slightly up, 1.24:1, fold running steeper. mark.svg and the masters.
No dependencies beyond Python 3 for the files; the preview uses ../../tools and PIL.
"""
import math, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
EXPL = os.path.normpath(os.path.join(HERE, '..', '..'))
SNAPPED = os.path.join(EXPL, 'round2', 'r2-01-snapped')
INK, BONE, BG = '#161310', '#E8E2DA', '#12100F'
IDLE, WORKING, ATTENTION = '#7B8794', '#C8A45C', '#F3D58F'

# ---------------------------------------------------------------- lattice
CW, CH, Y0 = 4.5, 3.4, 14.4
COLS, ROWS = 24, 16


def P(i, j):
    return (round(4.5 * i, 3), round(Y0 + CH * j, 3))


# ---------------------------------------------------------------- the perched bird (= Snapped)
PERCHED = [P(21, 6),   # bill tip
           P(19, 3),   # culmen
           P(16, 1),   # forehead
           P(13, 1),   # crown
           P(10, 3),   # back of head
           P(9, 5),    # nape / shoulder (the fold opens here)
           P(3, 13),   # tail tip
           P(3, 15),   # tail bottom
           P(8, 13),   # under-tail notch
           P(12, 14),  # belly
           P(15, 10),  # breast
           P(16, 6)]   # throat
PERCHED_EYE = ((65.25, 26.3), 3.2)           # centre of cell (14,3): one half-cell hole
PERCHED_SHOULDER = 5
PERCHED_FOLD = [PERCHED[5], P(10, 10), P(5, 13)]   # shoulder -> flap corner -> wing tip on the tail

# ---------------------------------------------------------------- the upright bird (the mark)
UPRIGHT = [P(19, 4),   # bill tip
           P(16, 2),   # culmen
           P(12, 1),   # forehead
           P(10, 1),   # crown
           P(8, 2),    # back of head
           P(7, 4),    # nape / shoulder (the fold opens here)
           P(5, 16),   # tail tip
           P(8, 16),   # tail bottom (the tail stands on the ground)
           P(12, 13),  # under-tail notch
           P(16, 14),  # belly
           P(17, 10),  # chest
           P(15, 6)]   # throat
UPRIGHT_EYE = ((56.25, 26.3), 3.2)           # centre of cell (12,3)
UPRIGHT_SHOULDER = 5
UPRIGHT_FOLD = [UPRIGHT[5], P(10, 11), P(7, 15)]   # shoulder -> flap corner -> wing tip on the tail
FOLD_W = 1.0                                  # half-width: the slit is 2 units

NAMES = ['bill tip', 'culmen', 'forehead', 'crown', 'back of head', 'nape', 'tail tip', 'tail bottom',
         'under-tail notch', 'belly', 'chest', 'throat']


def cell_of(p):
    return (round(p[0] / CW, 3), round((p[1] - Y0) / CH, 3))


def check_parity(sil, label):
    for k in range(len(sil)):
        a, b = cell_of(sil[k]), cell_of(sil[(k + 1) % len(sil)])
        di, dj = int(round(abs(b[0] - a[0]))), int(round(abs(b[1] - a[1])))
        g = math.gcd(di, dj)
        pi, pj = di // g, dj // g
        assert not (pi % 2 == 1 and pj % 2 == 1), f'{label}: edge {NAMES[k]}->{NAMES[(k+1)%12]} is {pi}:{pj} (odd:odd, through cell centres)'


check_parity(PERCHED, 'perched')
check_parity(UPRIGHT, 'upright')


# ---------------------------------------------------------------- path helpers
def fmt(p):
    return f'{round(p[0], 3):g},{round(p[1], 3):g}'


def poly_d(pts):
    return 'M' + ' L'.join(fmt(p) for p in pts) + ' Z'


def circle_d(c, r):
    return f'M{c[0]-r:g},{c[1]:g} a{r},{r} 0 1,0 {2*r:g},0 a{r},{r} 0 1,0 {-2*r:g},0 Z'


def unit(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy)
    return dx / L, dy / L


def offset_polyline(pts, ws):
    """closed polygon around an open polyline, half-width ws[k] at vertex k, mitred bends, square ends"""
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
        w = ws[k]
        left.append((pts[k][0] + nx * w, pts[k][1] + ny * w))
        right.append((pts[k][0] - nx * w, pts[k][1] - ny * w))
    return left, right


def line_x(p, d, a, b):
    """intersection of the line p + t d with the segment a-b, or None"""
    ex, ey = b[0] - a[0], b[1] - a[1]
    den = d[0] * ey - d[1] * ex
    if abs(den) < 1e-9:
        return None
    t = ((a[0] - p[0]) * ey - (a[1] - p[1]) * ex) / den
    s = ((a[0] - p[0]) * d[1] - (a[1] - p[1]) * d[0]) / den
    return (p[0] + t * d[0], p[1] + t * d[1]) if -1e-9 <= s <= 1 + 1e-9 else None


def splice_slit(ring, k, pts, ws):
    """splice a slit (polyline pts from ring[k], half-widths ws) into the closed polygon ring at vertex k.
    The slit's two sides are extended back along -d until they hit the ring's edges either side of
    vertex k, so the mouth is cut cleanly into the outline. Returns the new ring."""
    left, right = offset_polyline(pts, ws)
    d = (pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
    prev, nxt = ring[k - 1], ring[(k + 1) % len(ring)]
    hits = {}
    for side, p0 in (('L', left[0]), ('R', right[0])):
        for name, a, b in (('prev', prev, ring[k]), ('next', ring[k], nxt)):
            h = line_x(p0, d, a, b)
            if h is not None and all(v[0] != name for v in hits.values()):
                hits[side] = (name, h)
                break
    assert len(hits) == 2, 'slit mouth does not meet both edges'
    (sideA, (_, hA)), (sideB, (_, hB)) = sorted(hits.items(), key=lambda kv: kv[1][0] != 'prev')
    A = left if sideA == 'L' else right
    B = right if sideA == 'L' else left
    inner = A[1:] + B[1:][::-1]
    out = []
    for p in ring[:k] + [hA] + inner + [hB] + ring[k + 1:]:
        if not out or math.hypot(p[0] - out[-1][0], p[1] - out[-1][1]) > 1e-6:
            out.append(p)
    return out


PERCHED_RING = splice_slit(PERCHED, PERCHED_SHOULDER, PERCHED_FOLD, [FOLD_W] * 3)
UPRIGHT_RING = splice_slit(UPRIGHT, UPRIGHT_SHOULDER, UPRIGHT_FOLD, [FOLD_W] * 3)


def svg(paths, fill=INK, bg=None, size=512, vb='0 0 108 108', tr='translate(2,11)', comment='', bg_rx=None):
    c = f'  <!-- {comment} -->\n' if comment else ''
    w = vb.split()[2]
    rx = f' rx="{bg_rx}"' if bg_rx else ''
    bgr = f'  <rect width="{w}" height="{w}"{rx} fill="{bg}"/>\n' if bg else ''
    g = f'<g transform="{tr}">' if tr else '<g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{vb}">\n{c}{bgr}'
            f'  {g}\n    <path fill="{fill}" fill-rule="evenodd" d="{" ".join(paths)}"/>\n  </g>\n</svg>\n')


# ---------------------------------------------------------------- the head drawing (16-22 px)
# the upright bird's head, bill and neck on a 7-unit cell (plain 108 viewBox), 10 edges + eye hole r 7
HEAD = [(105, 40), (83.5, 29), (54.5, 24), (40, 24), (26, 29), (18.5, 40), (14, 90), (84, 90), (90.5, 73), (76, 51)]
HEAD_EYE = ((58, 37.5), 6.5)

# ---------------------------------------------------------------- glyph tiles (64 box)
GLYPH_RX = 14
EYE_CELL = 4


def T(p, s, ox, oy):
    return (round(ox + s * p[0], 3), round(oy + s * p[1], 3))


# the rounded-square boundary, walked clockwise from (rx,0): 8 pieces, position = piece index + fraction
def _pieces():
    rx, n = GLYPH_RX, 64
    return [('line', (rx, 0), (n - rx, 0), None),
            ('arc', (n - rx, 0), (n, rx), (n - rx, rx)),
            ('line', (n, rx), (n, n - rx), None),
            ('arc', (n, n - rx), (n - rx, n), (n - rx, n - rx)),
            ('line', (n - rx, n), (rx, n), None),
            ('arc', (rx, n), (0, n - rx), (rx, n - rx)),
            ('line', (0, n - rx), (0, rx), None),
            ('arc', (0, rx), (rx, 0), (rx, rx))]


def inside_tile(p):
    rx = GLYPH_RX
    x, y = p
    if not (0 <= x <= 64 and 0 <= y <= 64):
        return False
    for cx, cy in ((rx, rx), (64 - rx, rx), (rx, 64 - rx), (64 - rx, 64 - rx)):
        if (x < cx if cx == rx else x > cx) and (y < cy if cy == rx else y > cy):
            if math.hypot(x - cx, y - cy) > rx:
                return False
    return True


def _arc_frac(piece, p):
    """fraction along a clockwise arc piece for a point on it"""
    _, a, b, c = piece
    a0 = math.atan2(a[1] - c[1], a[0] - c[0])
    a1 = math.atan2(b[1] - c[1], b[0] - c[0])
    ap = math.atan2(p[1] - c[1], p[0] - c[0])
    def norm(t):
        while t < 0: t += 2 * math.pi
        while t >= 2 * math.pi: t -= 2 * math.pi
        return t
    span = norm(a1 - a0)
    return norm(ap - a0) / span


def boundary_crossings(a, b):
    """all crossings of the segment a->b with the tile boundary, as (t, point, walk position)"""
    out = []
    for idx, piece in enumerate(_pieces()):
        kind, p0, p1, c = piece
        if kind == 'line':
            h = line_x(a, (b[0] - a[0], b[1] - a[1]), p0, p1)
            if h is None:
                continue
            t = ((h[0] - a[0]) * (b[0] - a[0]) + (h[1] - a[1]) * (b[1] - a[1])) / ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2)
            if 0 <= t <= 1:
                L = math.hypot(p1[0] - p0[0], p1[1] - p0[1])
                f = math.hypot(h[0] - p0[0], h[1] - p0[1]) / L
                out.append((t, h, idx + f))
        else:
            rx = GLYPH_RX
            dx, dy = b[0] - a[0], b[1] - a[1]
            fx, fy = a[0] - c[0], a[1] - c[1]
            A, B, C = dx * dx + dy * dy, 2 * (fx * dx + fy * dy), fx * fx + fy * fy - rx * rx
            disc = B * B - 4 * A * C
            if disc < 0:
                continue
            for sgn in (-1, 1):
                t = (-B + sgn * math.sqrt(disc)) / (2 * A)
                if 0 <= t <= 1:
                    h = (a[0] + t * dx, a[1] + t * dy)
                    f = _arc_frac(piece, h)
                    if 0 <= f <= 1:
                        out.append((t, h, idx + f))
    return sorted(out)


def _walk(pos_from, pos_to, start_pt):
    """SVG commands along the boundary clockwise from pos_from to pos_to (pos_to may be past 8 = the start)"""
    pieces = _pieces()
    d = ''
    pos = pos_from
    while pos < pos_to - 1e-9:
        idx = int(math.floor(pos + 1e-9)) % 8
        kind, p0, p1, c = pieces[idx]
        end_pos = min(idx + 1 + (8 if pos >= 8 else 0), pos_to)
        if end_pos >= idx + 1 - 1e-9 + (8 if pos >= 8 else 0):
            target = p1
        else:
            f = end_pos - int(math.floor(end_pos))
            if kind == 'line':
                target = (p0[0] + f * (p1[0] - p0[0]), p0[1] + f * (p1[1] - p0[1]))
            else:
                a0 = math.atan2(p0[1] - c[1], p0[0] - c[0])
                a1 = math.atan2(p1[1] - c[1], p1[0] - c[0])
                span = (a1 - a0) % (2 * math.pi)
                ang = a0 + f * span
                target = (c[0] + GLYPH_RX * math.cos(ang), c[1] + GLYPH_RX * math.sin(ang))
        if kind == 'line':
            d += f'L{fmt(target)} '
        else:
            d += f'A{GLYPH_RX},{GLYPH_RX} 0 0 1 {fmt(target)} '
        pos = end_pos
    return d


def tile_ring(bird=None):
    """the rounded-square ring as one SVG subpath; with bird (a closed polygon in tile coordinates that
    leaves the tile somewhere), the ring is spliced so the part of the bird inside the tile is a notch:
    one simple loop, no coincident edges. The container is walked clockwise from (rx,0); every run of
    bird vertices inside the tile is entered at its boundary crossing and left at the other."""
    rx = GLYPH_RX
    if bird is None:
        return (f'M{rx},0 H{64-rx} A{rx},{rx} 0 0 1 64,{rx} V{64-rx} A{rx},{rx} 0 0 1 {64-rx},64 H{rx} '
                f'A{rx},{rx} 0 0 1 0,{64-rx} V{rx} A{rx},{rx} 0 0 1 {rx},0 Z')
    n = len(bird)
    ins = [inside_tile(p) for p in bird]
    assert any(ins) and not all(ins), 'bird must cross the tile boundary'
    runs = []
    for k0 in [k for k in range(n) if ins[k] and not ins[k - 1]]:
        pts, k = [], k0
        while ins[k % n]:
            pts.append(bird[k % n]); k += 1
        ce = boundary_crossings(bird[k0], bird[(k0 - 1) % n])
        cx = boundary_crossings(bird[(k - 1) % n], bird[k % n])
        assert ce and cx, 'edge leaves the tile without a boundary crossing'
        enter, pos_e = ce[0][1], ce[0][2]
        exit_, pos_x = cx[0][1], cx[0][2]
        if pos_e < pos_x:
            runs.append((pos_e, enter, pts, exit_, pos_x))
        else:
            runs.append((pos_x, exit_, pts[::-1], enter, pos_e))
    runs.sort()
    for r1, r2 in zip(runs, runs[1:]):
        assert r1[4] < r2[0], 'bird runs overlap along the boundary'
    d = f'M{rx},0 '
    pos = 0.0
    for p_a, pt_a, pts, pt_b, p_b in runs:
        d += _walk(pos, p_a, None)
        d += ' '.join('L' + fmt(p) for p in pts) + f' L{fmt(pt_b)} '
        pos = p_b
    d += _walk(pos, 8.0, None)
    return d.rstrip() + ' Z'


def extend(a, b, y):
    """the point on the line a->b at height y"""
    t = (y - a[1]) / (b[1] - a[1])
    return (a[0] + t * (b[0] - a[0]), y)


GLYPH = {  # the construction parameters: scale, eye cell (x, y of its top-left, multiples of 4), slit half-width (mark units)
    'a': dict(s=0.95, cell=(28, 24), w=1.0),
    'b': dict(s=0.78, cell=(32, 24), mouth=5.0, length=18.0, kind='hole'),
    'c': dict(s=0.78, cell=(32, 16), w=1.4),
}


def glyph_paths(construction, **over):
    """returns (paths, s, (ox, oy)) for glyph a / b / c, one evenodd path each"""
    bird, eye, shoulder, fold = UPRIGHT, UPRIGHT_EYE, UPRIGHT_SHOULDER, UPRIGHT_FOLD
    prm = dict(GLYPH[construction]); prm.update(over)
    s = prm['s']
    ecx, ecy = prm['cell'][0] + 2, prm['cell'][1] + 2
    ox, oy = ecx - s * eye[0][0], ecy - s * eye[0][1]
    eye_d = f'M{ecx-2},{ecy-2} h4 v4 h-4 Z'
    if construction == 'c':
        # the whole upright mark with its fold, smaller, as a hole; the eye is a 4x4 ink cell on whole pixels
        ring = [T(p, s, ox, oy) for p in splice_slit(bird, shoulder, fold, [prm['w']] * 3)]
        assert all(inside_tile(p) for p in ring)
        return [tile_ring(), poly_d(ring), eye_d], s, (ox, oy)
    if construction == 'a':
        # the head, chest and body near the mark's scale; the tail (and wing tip) run off the bottom-left; the full fold
        ring = [T(p, s, ox, oy) for p in splice_slit(bird, shoulder, fold, [prm['w']] * 3)]
        return [tile_ring(ring), eye_d], s, (ox, oy)
    if construction == 'b':
        # head, throat, chest and the wing's upper edge as the hole, breast open onto the bottom edge; the fold is a
        # short ink wedge at the shoulder only (mouth and length in tile units), tapering to nothing
        tip, culmen, forehead, crown, boh, nape, tailtip, _, _, belly, chest, throat = bird
        far = (70 - oy) / s                                  # well below the tile's bottom edge, in mark units
        L, hw = prm['length'] / s, prm['mouth'] / 2 / s
        if prm.get('kind') == 'ink':
            # the fold as a short INK wedge along the fold line, cut into a hole whose back follows the back edge
            u = unit(nape, fold[1])
            wend = (nape[0] + u[0] * L, nape[1] + u[1] * L)
            hole = [tip, culmen, forehead, crown, boh, nape, extend(nape, tailtip, far), extend(chest, belly, far), chest, throat]
            hole = splice_slit(hole, 5, [nape, wend], [hw, 0.0])
        else:
            # the hole's back follows the FOLD line (the ink behind it is the folded wing); at the shoulder a short
            # tapering sliver of hole peels off along the back edge: the wing's upper edge, the fold's mouth
            u = unit(nape, tailtip)
            wend = (nape[0] + u[0] * L, nape[1] + u[1] * L)
            nrm = (-u[1], u[0])
            m1 = (nape[0] + nrm[0] * hw, nape[1] + nrm[1] * hw)     # mouth corner on the neck side
            m2 = (nape[0] - nrm[0] * hw, nape[1] - nrm[1] * hw)     # mouth corner on the body side
            if (m1[0] - nape[0]) * (boh[0] - nape[0]) + (m1[1] - nape[1]) * (boh[1] - nape[1]) < 0:
                m1, m2 = m2, m1
            hole = [tip, culmen, forehead, crown, boh, m1, wend, m2, extend(nape, fold[1], far), extend(chest, belly, far), chest, throat]
        ring = [T(p, s, ox, oy) for p in hole]
        return [tile_ring(ring), eye_d], s, (ox, oy)
    raise ValueError(construction)


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


def coverage(cols, rows, cw, ch, x0, y0, polys, circles=(), ss=16):
    out = []
    for j in range(rows):
        row = []
        for i in range(cols):
            hit = sum(inked((x0 + cw * (i + (sx + .5) / ss), y0 + ch * (j + (sy + .5) / ss)), polys, circles)
                      for sy in range(ss) for sx in range(ss))
            row.append(hit / (ss * ss))
        out.append(row)
    return out


def raster(cov, thr=0.5):
    return [''.join('#' if v >= thr else '.' for v in row) for row in cov]


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


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def load_grid(path, name):
    sys.path.insert(0, os.path.join(EXPL, 'marks-b', 'mark-04-pixel'))
    import grid2text as gt
    return gt.load(path)[name]


def build_files(verbose=True):
    sys.path.insert(0, os.path.join(EXPL, 'marks-b', 'mark-04-pixel'))
    import grid2text as gt

    up_paths = [poly_d(UPRIGHT_RING), circle_d(*UPRIGHT_EYE)]
    up_solid = [poly_d(UPRIGHT), circle_d(*UPRIGHT_EYE)]
    pe_paths = [poly_d(PERCHED_RING), circle_d(*PERCHED_EYE)]
    pe_solid = [poly_d(PERCHED), circle_d(*PERCHED_EYE)]
    cells = lambda sil: ' '.join(f'{NAMES[k]} ({int(cell_of(p)[0])},{int(cell_of(p)[1])})' for k, p in enumerate(sil))
    note = ('Huginn mark, round 3 fold-system: the fold raven standing upright (chest forward, head raised, bill '
            'slightly up) on Snapped\'s 24x16 half-block lattice (x = 4.5i, y = 14.4 + 3.4j), twelve lattice vertices '
            'plus ONE interior cut, a 2-unit slit that opens at the shoulder and separates the folded wing from the body. '
            'ONE evenodd path: the outer ring and a circular eye hole. Box x 18-81, y 17.8-68.8 (63 x 51, 1.24:1); '
            'translate(2,11). Ink #161310. Vertices (col,row): ' + cells(UPRIGHT) + '. Below 32 px use mark-32.svg (no fold).')
    write('mark.svg', svg(up_paths, comment=note))
    write('mark-inverse.svg', svg(up_paths, fill=BONE, comment='Inverse: bone mark for dark grounds (#12100F). Same path as mark.svg.'))
    # tile master: the 63 x 51 box scaled 1.3 and centred on the bone tile (x 13.05-94.95, 12 % inset)
    s = 1.3
    tx, ty = 54 - s * 49.5, 54 - s * 43.3
    write('mark-tile.svg', svg(up_paths, bg=BONE, tr=f'translate({tx:.2f},{ty:.2f}) scale({s})',
                               comment='App-icon master: ink mark scaled 1.3 about the box centre on the bone tile (x 13-95, y 23.2-89.5; 12 % side inset). Same path as mark.svg.'))
    write('mark-32.svg', svg(up_solid, comment='The upright silhouette without the fold: 32 px and below (favicon-32, tray at 200 %), and the source of grid-upright.txt. Same twelve vertices.'))
    write('mark-16.svg', svg([poly_d(HEAD), circle_d(*HEAD_EYE)], tr=None, comment=(
        'mark-16: the compact drawing for 16-22 px (tray, favicon-16): the upright bird\'s head, bill, throat and chest '
        'on a 7-unit cell, 10 straight edges plus a circular eye hole r 7; box x 21-105, y 17-90 of a plain 108 viewBox, no fold.')))
    write('mark-perched.svg', svg(pe_paths, comment=(
        'For comparison: the perched r2-05 fold raven re-snapped to r2-01-snapped\'s twelve vertices on its lattice '
        '(x = 4.5i, y = 14.4 + 3.4j), with the 2-unit fold slit spliced at the nape. Its solid silhouette is byte-for-byte '
        'Snapped\'s mark.svg polygon, so it rasterises to Snapped\'s grid.txt (the CLI art). Vertices (col,row): ' + cells(PERCHED) + '.')))
    write('mark-perched-32.svg', svg(pe_solid, comment='The perched silhouette without the fold = r2-01-snapped mark.svg, the solid master that the 24x8 CLI art is the raster of.'))

    for g in 'abc':
        paths, s, _ = glyph_paths(g)
        gnote = {
            'a': ('Glyph A "full fold": the upright bird\'s head, chest and body at the mark\'s own scale (1.0 units per unit) as a hole in the rounded '
                  'square (rx 14), running off the bottom-left; the fold hairline runs through the hole exactly as in the mark (2 units) and '
                  'is tile ink; the eye is a 4x4 ink cell at x 32-36, y 24-28 (whole pixels at 16/32/64). ONE evenodd path.'),
            'b': ('Glyph B "shoulder wedge": the head, throat, chest and the wing\'s upper edge as a hole (the bird at 0.83), the breast open '
                  'onto the bottom edge; the fold is a short ink wedge (3 units at the mouth, 11 long, tapering to nothing) opening at the '
                  'shoulder only; eye a 4x4 ink cell at x 32-36, y 24-28. ONE evenodd path.'),
            'c': ('Glyph C "negative mark": the whole upright mark with its fold at 0.72 as a hole in the rounded square, so the tile is the mark '
                  'in negative; the fold is a 2.2-unit ink hairline, the eye a 4x4 ink cell at x 36-40, y 20-24. ONE evenodd path.')}[g]
        write(f'glyph-{g}.svg', svg(paths, vb='0 0 64 64', tr=None, comment=gnote))
        write(f'glyph-{g}-inverse.svg', svg(paths, fill=BONE, bg=INK, vb='0 0 64 64', tr=None,
                                            comment='Inverse: the same path in bone on an ink ground; the raven is the dark ground showing through.'))

    # ---- Snapped's files: copied byte for byte (the CLI art), and the proof that the perched solid master rasterises to them
    for f in ('grid.txt', 'ascii-halfblock.txt', 'ascii-7bit.txt', 'ascii-braille.txt'):
        write(f, read(os.path.join(SNAPPED, f)))
    snapped_grid = load_grid(os.path.join(SNAPPED, 'grid.txt'), 'r2-01')
    cov = coverage(COLS, ROWS, CW, CH, 0, Y0, [PERCHED], [PERCHED_EYE])
    perched24 = raster(cov)
    ties = [(i, j, v) for j, row in enumerate(cov) for i, v in enumerate(row) if 0.42 < v < 0.58]
    assert perched24 == snapped_grid, 'perched solid master does not rasterise to Snapped\'s grid'
    assert not ties, f'ambiguous cells: {ties}'
    hb = gt.frame(gt.halfblock(perched24))
    a7 = gt.frame(gt.ascii(perched24), plain=True)
    assert '\n'.join(hb) + '\n' == read(os.path.join(SNAPPED, 'ascii-halfblock.txt'))
    assert '\n'.join(a7) + '\n' == read(os.path.join(SNAPPED, 'ascii-7bit.txt'))
    br = gt.braille(perched24)
    assert '\n'.join(br) + '\n' == read(os.path.join(SNAPPED, 'ascii-braille.txt'))
    if verbose:
        print('proof: mark-perched-32.svg rasterised on the 24x16 lattice == r2-01-snapped grid.txt; half-block, 7-bit and braille regenerate byte-identical; no cell within 0.42-0.58')

    # ---- the upright bird's own grids and text
    cov_u = coverage(COLS, ROWS, CW, CH, 0, Y0, [UPRIGHT], [UPRIGHT_EYE])
    up24 = raster(cov_u)
    ties = [(i, j, round(v, 2)) for j, row in enumerate(cov_u) for i, v in enumerate(row) if 0.42 < v < 0.58]
    assert not ties, f'upright: ambiguous cells {ties}'
    up48 = carve(raster(coverage(COLS * 2, ROWS * 2, CW / 2, CH / 2, 0, Y0, [UPRIGHT], [UPRIGHT_EYE])), CW / 2, CH / 2, Y0, UPRIGHT_FOLD, 1.0)
    pe48 = carve(raster(coverage(COLS * 2, ROWS * 2, CW / 2, CH / 2, 0, Y0, [PERCHED], [PERCHED_EYE])), CW / 2, CH / 2, Y0, PERCHED_FOLD, 1.0)
    header = [
        '// round3/fold-system: the UPRIGHT fold raven rasterised onto Snapped\'s lattice. Generated by build.py; edit build.py, not this.',
        '// Lattice: 24 columns x 16 half-rows over the 108 box, cell 4.5 x 3.4 units (x = 4.5i, y = 14.4 + 3.4j), the same lattice',
        '// as ../../round2/r2-01-snapped/grid.txt. A cell is ink when >= 50 % of it is inside the solid silhouette (mark-32.svg);',
        '// every vertex is a lattice point and no edge passes through a cell centre, so no cell is near the threshold.',
        '// upright24: 24 x 16, the half-block / 7-bit source (solid: the fold drops at <= 32 px).',
        '// upright48: 48 x 32, the same silhouette on the 2x lattice (2.25 x 1.7) WITH the fold carved as a one-cell gap (every fine cell',
        '//   whose centre is within 1 unit of the slit centreline): the braille source (24 x 8) and the 48 x 16 half-block / 7-bit source.',
        '// perched48: the perched bird (mark-perched-32.svg = Snapped\'s silhouette) on the 2x lattice with its fold carved, for comparison.',
        '// perched24: the perched solid master on the 24 x 16 lattice: identical to @r2-01 in grid.txt (the alignment proof).',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid-upright.txt upright24 --frame > ascii-halfblock-upright.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py ascii     grid-upright.txt upright24 --frame > ascii-7bit-upright.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py braille   grid-upright.txt upright48         > ascii-braille-upright.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid-upright.txt upright48 --frame > ascii-halfblock-48.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py ascii     grid-upright.txt upright48 --frame > ascii-7bit-48.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid-upright.txt perched48 --frame > ascii-halfblock-48-perched.txt',
        '//   python3 ../../marks-b/mark-04-pixel/grid2text.py ascii     grid-upright.txt perched48 --frame > ascii-7bit-48-perched.txt',
        '']
    body = (f'@upright24 {COLS}x{ROWS}\n' + '\n'.join(up24) + f'\n\n@upright48 {COLS*2}x{ROWS*2}\n' + '\n'.join(up48) +
            f'\n\n@perched48 {COLS*2}x{ROWS*2}\n' + '\n'.join(pe48) + f'\n\n@perched24 {COLS}x{ROWS}\n' + '\n'.join(perched24) + '\n')
    write('grid-upright.txt', '\n'.join(header) + body)
    write('ascii-halfblock-upright.txt', '\n'.join(gt.frame(gt.halfblock(up24))) + '\n')
    write('ascii-7bit-upright.txt', '\n'.join(gt.frame(gt.ascii(up24), plain=True)) + '\n')
    write('ascii-braille-upright.txt', '\n'.join(gt.braille(up48)) + '\n')
    write('ascii-halfblock-48.txt', '\n'.join(gt.frame(gt.halfblock(up48))) + '\n')
    write('ascii-7bit-48.txt', '\n'.join(gt.frame(gt.ascii(up48), plain=True)) + '\n')
    write('ascii-halfblock-48-perched.txt', '\n'.join(gt.frame(gt.halfblock(pe48))) + '\n')
    write('ascii-7bit-48-perched.txt', '\n'.join(gt.frame(gt.ascii(pe48), plain=True)) + '\n')
    return dict(perched24=perched24, up24=up24, up48=up48, pe48=pe48)


# ---------------------------------------------------------------- rendering helpers
def render(path, prefix, sizes, bg):
    subprocess.run(['node', os.path.join(EXPL, 'tools', 'render.mjs'), path, prefix, ','.join(map(str, sizes)), bg],
                   check=True, stdout=subprocess.DEVNULL)


def pre_html(files, px=16.61, gap=40):
    """text renderings in a <pre>, DejaVu Sans Mono, line-height 1, on the dark ground; 16.61 px gives a 10 px advance"""
    import html
    blocks = ''.join(f'<div><div class=l>{lbl}</div><pre>{html.escape(read(os.path.join(HERE, f)).rstrip())}</pre></div>'
                     for f, lbl in files)
    return (f'<!doctype html><html><head><meta charset="utf-8"><style>body{{margin:0;background:{BG};color:{BONE};'
            f'font-family:"DejaVu Sans Mono",monospace}}#w{{display:flex;gap:{gap}px;padding:24px;align-items:flex-start;flex-wrap:wrap}}'
            f'pre{{margin:0;font-family:"DejaVu Sans Mono",monospace;font-size:{px}px;line-height:1;white-space:pre}}'
            f'.l{{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#8A8177;margin-bottom:10px;font-family:"DejaVu Sans",sans-serif}}</style></head>'
            f'<body><div id=w>{blocks}</div></body></html>')


def grid_overlay_svg(grid, ring, eye, cw=CW, ch=CH, cols=COLS, rows=ROWS):
    cells = ''.join(f'<rect x="{cw*i:g}" y="{Y0+ch*j:g}" width="{cw}" height="{ch}" fill="#9A8F84"/>'
                    for j, row in enumerate(grid) for i, c in enumerate(row) if c == '#')
    lines = ''.join(f'<line x1="{cw*i:g}" y1="{Y0}" x2="{cw*i:g}" y2="{Y0+ch*rows:g}" />' for i in range(cols + 1))
    lines += ''.join(f'<line x1="0" y1="{Y0+ch*j:g}" x2="108" y2="{Y0+ch*j:g}" />' for j in range(rows + 1))
    d = f'{poly_d(ring)} {circle_d(*eye)}'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">'
            f'<rect width="108" height="108" fill="{BONE}"/><g transform="translate(2,11)">{cells}'
            f'<g stroke="#C9BFB4" stroke-width="0.25">{lines}</g>'
            f'<path fill="{INK}" fill-opacity="0.55" fill-rule="evenodd" d="{d}"/>'
            f'<path fill="none" stroke="{INK}" stroke-width="0.4" fill-rule="evenodd" d="{d}"/></g></svg>')


def launcher_svg(ring, eye):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">'
            f'<defs><clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
            f'<g clip-path="url(#m)"><rect width="108" height="108" fill="{BONE}"/>'
            f'<g transform="translate(2,11) translate(52,43) scale(0.72) translate(-52,-43)">'
            f'<path fill="{INK}" fill-rule="evenodd" d="{poly_d(ring)} {circle_d(*eye)}"/></g></g>'
            f'<circle cx="54" cy="54" r="33" fill="none" stroke="#C8A45C" stroke-width="0.6" stroke-dasharray="1.5 1.5"/></svg>')


def tmpdir(prefix):
    base = os.environ.get('R3_TMP')
    if base:
        os.makedirs(base, exist_ok=True)
        return tempfile.mkdtemp(prefix=prefix, dir=base)
    return tempfile.mkdtemp(prefix=prefix)


# ---------------------------------------------------------------- quick look (iteration)
def build_look(outdir):
    """mark and glyphs beside each other at the judging sizes, hard-pixel magnified, into outdir"""
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(outdir, exist_ok=True)
    tmp = tmpdir('r3look-')
    Tp = lambda n: os.path.join(tmp, n)
    F = lambda n: os.path.join(HERE, n)
    wt = lambda n, s: (open(Tp(n), 'w').write(s), Tp(n))[1]
    render(F('mark.svg'), Tp('up'), [512, 64, 32], BONE)
    render(F('mark-perched.svg'), Tp('pe'), [512, 64], BONE)
    render(F('mark-32.svg'), Tp('up32'), [32, 16], BONE)
    render(F('mark-16.svg'), Tp('h'), [22, 16], BONE)
    for g in 'abc':
        render(F(f'glyph-{g}.svg'), Tp(f'g{g}'), [128, 48, 24, 16], BONE)
        for name, col in (('idle', IDLE), ('working', WORKING), ('attention', ATTENTION)):
            render(wt(f'g{g}-{name}.svg', read(F(f'glyph-{g}.svg')).replace(INK, col)), Tp(f'g{g}-{name}'), [48, 24, 16], BG)
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 12)
    sheet = Image.new('RGB', (1700, 1500), '#6F6A64')
    d = ImageDraw.Draw(sheet)

    def put(img, x, y, scale=1, label=None):
        if isinstance(img, str):
            img = Image.open(img).convert('RGBA')
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        sheet.paste(img, (x, y), img)
        if label:
            d.text((x, y + img.height + 4), label, fill='#F2ECE4', font=font)
        return img.width, img.height
    put(Tp('up-512.png'), 20, 20, label='upright 512')
    put(Tp('pe-512.png'), 550, 20, label='perched 512')
    put(Tp('up-64.png'), 1090, 20, 4, label='upright 64 (4x)')
    put(Tp('pe-64.png'), 1360, 20, 4, label='perched 64 (4x)')
    put(Tp('up-32.png'), 1090, 300, 4, label='32 with fold')
    put(Tp('up32-32.png'), 1230, 300, 4, label='32 solid')
    put(Tp('up32-16.png'), 1370, 300, 4, label='16 solid')
    put(Tp('h-22.png'), 1090, 460, 4, label='head 22')
    put(Tp('h-16.png'), 1200, 460, 4, label='head 16')
    y = 560
    for g in 'abc':
        x = 20
        put(Tp(f'g{g}-128.png'), x, y, label=f'glyph {g} 128'); x += 150
        put(Tp('up-64.png'), x, y, 2, label='mark 128'); x += 150
        for s, sc in ((48, 3), (24, 3), (16, 3)):
            w, h = put(Tp(f'g{g}-{s}.png'), x, y, sc, label=f'{s} x{sc}'); x += w + 14
        for name in ('idle', 'working', 'attention'):
            for s, sc in ((48, 2), (24, 2), (16, 2)):
                w, h = put(Tp(f'g{g}-{name}-{s}.png'), x, y, sc, label=f'{name[:3]} {s}'); x += w + 8
            x += 10
        xx = x
        for s in (48, 24, 16):
            w, h = put(Tp(f'g{g}-{s}.png'), xx, y + 20); xx += w + 6
        y += 300
    out = os.path.join(outdir, 'look.png')
    sheet.save(out)
    print(out)
    return out


def build_variants(outdir, variants):
    """variants: list of (label, construction, overrides); renders each at 128/48/24/16 + idle 24/16, hard pixels"""
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(outdir, exist_ok=True)
    tmp = tmpdir('r3var-')
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 12)
    sheet = Image.new('RGB', (1500, 230 * len(variants) + 20), '#6F6A64')
    d = ImageDraw.Draw(sheet)
    y = 10
    for label, g, over in variants:
        paths, _, _ = glyph_paths(g, **over)
        fn = os.path.join(tmp, f'{label}.svg')
        open(fn, 'w').write(svg(paths, vb='0 0 64 64', tr=None))
        open(fn + '.idle.svg', 'w').write(svg(paths, fill=IDLE, vb='0 0 64 64', tr=None))
        render(fn, os.path.join(tmp, label), [128, 48, 24, 16], BONE)
        render(fn + '.idle.svg', os.path.join(tmp, label + '-idle'), [48, 24, 16], BG)
        x = 10
        d.text((x, y), label, fill='#F2ECE4', font=font)
        y += 16
        for nm, sc in ((f'{label}-128', 1), (f'{label}-48', 3), (f'{label}-24', 3), (f'{label}-16', 3),
                       (f'{label}-idle-48', 3), (f'{label}-idle-24', 3), (f'{label}-idle-16', 3),
                       (f'{label}-48', 1), (f'{label}-24', 1), (f'{label}-16', 1), (f'{label}-idle-24', 1), (f'{label}-idle-16', 1)):
            img = Image.open(os.path.join(tmp, nm + '.png')).convert('RGBA')
            if sc != 1:
                img = img.resize((img.width * sc, img.height * sc), Image.NEAREST)
            sheet.paste(img, (x, y), img)
            x += img.width + 10
        y += 214
    out = os.path.join(outdir, 'variants.png')
    sheet.save(out)
    print(out)


def build_poses(outdir, poses):
    """poses: list of (label, vertex list, eye, fold): the mark with its fold at 256 and 64 (3x), side by side"""
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(outdir, exist_ok=True)
    tmp = tmpdir('r3pose-')
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 12)
    sheet = Image.new('RGB', (470 * len(poses) + 20, 300), '#6F6A64')
    d = ImageDraw.Draw(sheet)
    x = 10
    for label, sil, eye, fold in poses:
        check_parity(sil, label)
        ring = splice_slit(sil, 5, fold, [FOLD_W] * 3)
        fn = os.path.join(tmp, label.replace(' ', '_') + '.svg')
        open(fn, 'w').write(svg([poly_d(ring), circle_d(*eye)]))
        render(fn, os.path.join(tmp, label.replace(' ', '_')), [256, 64], BONE)
        cov = coverage(COLS, ROWS, CW, CH, 0, Y0, [sil], [eye])
        ties = [(i, j, round(v, 2)) for j, row in enumerate(cov) for i, v in enumerate(row) if 0.42 < v < 0.58]
        d.text((x, 4), f'{label}  ties:{ties}', fill='#F2ECE4', font=font)
        img = Image.open(os.path.join(tmp, label.replace(' ', '_') + '-256.png')).convert('RGBA')
        sheet.paste(img, (x, 20), img)
        img = Image.open(os.path.join(tmp, label.replace(' ', '_') + '-64.png')).convert('RGBA').resize((192, 192), Image.NEAREST)
        sheet.paste(img, (x + 266, 20), img)
        x += 470
    out = os.path.join(outdir, 'poses.png')
    sheet.save(out)
    print(out)


# ---------------------------------------------------------------- preview sheet
def build_preview(grids):
    from PIL import Image, ImageDraw, ImageFont
    tmp = tmpdir('r3prev-')
    Tp = lambda n: os.path.join(tmp, n)
    F = lambda n: os.path.join(HERE, n)
    wt = lambda n, s: (open(Tp(n), 'w').write(s), Tp(n))[1]
    render(F('mark.svg'), Tp('m-bone'), [512, 128, 64], BONE)
    render(F('mark-inverse.svg'), Tp('m-dark'), [512, 64], BG)
    render(F('mark-perched.svg'), Tp('p-bone'), [512, 64], BONE)
    render(F('mark-32.svg'), Tp('m32-bone'), [32, 16], BONE)
    render(wt('inv32.svg', svg([poly_d(UPRIGHT), circle_d(*UPRIGHT_EYE)], fill=BONE)), Tp('m32-dark'), [32, 16], BG)
    render(F('mark-16.svg'), Tp('h-bone'), [22, 16], BONE)
    render(wt('inv16.svg', svg([poly_d(HEAD), circle_d(*HEAD_EYE)], fill=BONE, tr=None)), Tp('h-dark'), [22, 16], BG)
    render(F('mark-tile.svg'), Tp('tile'), [128], BONE)
    render(wt('launcher.svg', launcher_svg(UPRIGHT_RING, UPRIGHT_EYE)), Tp('launch'), [160], BG)
    render(wt('overlay.svg', grid_overlay_svg(grids['up24'], UPRIGHT_RING, UPRIGHT_EYE)), Tp('overlay'), [400], BONE)
    render(wt('overlay-p.svg', grid_overlay_svg(grids['perched24'], PERCHED_RING, PERCHED_EYE)), Tp('overlay-p'), [400], BONE)
    for g in 'abc':
        render(F(f'glyph-{g}.svg'), Tp(f'g{g}-ink'), [128, 48, 24, 16], BONE)
        render(F(f'glyph-{g}-inverse.svg'), Tp(f'g{g}-inv'), [128], BG)
        for name, col in (('idle', IDLE), ('working', WORKING), ('attention', ATTENTION)):
            render(wt(f'g{g}-{name}.svg', read(F(f'glyph-{g}.svg')).replace(INK, col)), Tp(f'g{g}-{name}'), [128, 48, 24, 16], BG)
    render(wt('pre1.html', pre_html([('ascii-halfblock.txt', 'CLI art: Snapped 24x8 half-block'),
                                     ('ascii-7bit.txt', 'CLI art: Snapped 24x8 7-bit'),
                                     ('ascii-braille.txt', 'Snapped braille 12x4'),
                                     ('ascii-halfblock-upright.txt', 'upright 24x8 half-block'),
                                     ('ascii-7bit-upright.txt', 'upright 24x8 7-bit'),
                                     ('ascii-braille-upright.txt', 'upright braille 24x8 (fold)')], 16.61, 28)), Tp('pre1'), [1660], BG)
    render(wt('pre2.html', pre_html([('ascii-halfblock-48.txt', 'upright 48x16 half-block, fold as a one-cell gap (ascii-halfblock-48.txt)'),
                                     ('ascii-7bit-48.txt', 'upright 48x16 7-bit (ascii-7bit-48.txt)'),
                                     ('ascii-halfblock-48-perched.txt', 'perched 48x16 half-block, for comparison (ascii-halfblock-48-perched.txt)')], 16.61, 30)), Tp('pre2'), [1660], BG)

    W = 1700
    sheet = Image.new('RGB', (W, 4600), '#6F6A64')
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 13)
    big = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 18)
    small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 11)

    def put(img, x, y, scale=1, label=None, frame=None, lf=font):
        if isinstance(img, str):
            img = Image.open(img).convert('RGBA')
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        if frame:
            d.rectangle([x - 1, y - 1, x + img.width, y + img.height], outline=frame)
        sheet.paste(img, (x, y), img)
        if label:
            d.text((x, y + img.height + 5), label, fill='#F2ECE4', font=lf)
        return img.width, img.height

    def title(x, y, s):
        d.text((x, y), s, fill='#F2ECE4', font=big)

    def sub(x, y, s, col='#D9D1C7'):
        d.text((x, y), s, fill=col, font=font)

    title(24, 18, 'round 3 - fold-system: the upright fold raven, three glyph tiles with a wing fold, and the terminal art')
    sub(24, 44, 'A: the mark at 512 on bone and on #12100F; 64 / 32 / 16 and the head at 22 / 16 (3x, hard pixels).  B: perched vs upright.  C: glyphs a / b / c beside the mark.  D: text art.  E: vector over grid.')

    # A ---------------------------------------------------------------- the mark
    yA = 72
    put(Tp('m-bone-512.png'), 24, yA, label='mark.svg  512 on bone')
    put(Tp('m-dark-512.png'), 560, yA, label='mark-inverse.svg  512 on #12100F')
    x, y = 1100, yA
    sub(x, y, '64 (mark.svg, with fold), 3x; bone and app ground')
    y += 20
    w, h = put(Tp('m-bone-64.png'), x, y, 3, frame='#4A4541')
    put(Tp('m-dark-64.png'), x + w + 16, y, 3, frame='#4A4541')
    y += h + 14
    sub(x, y, '32 and 16 (mark-32.svg, solid), 3x')
    y += 20
    xx = x
    for nm in ('m32-bone-32', 'm32-dark-32', 'm32-bone-16', 'm32-dark-16'):
        w, h = put(Tp(nm + '.png'), xx, y, 3, frame='#4A4541')
        xx += w + 16
    y += 96 + 14
    sub(x, y, 'head drawing mark-16.svg at 22 and 16, 3x')
    y += 20
    xx = x
    for nm in ('h-bone-22', 'h-dark-22', 'h-bone-16', 'h-dark-16'):
        w, h = put(Tp(nm + '.png'), xx, y, 3, frame='#4A4541')
        xx += w + 16
    y += 66 + 14
    put(Tp('tile-128.png'), x, y, label='mark-tile.svg 128')
    put(Tp('launch-160.png'), x + 160, y - 16, label='launcher: mask d72, safe ring r33')

    # B ---------------------------------------------------------------- perched vs upright
    yB = yA + 660
    title(24, yB, "B: perched (r2-05 re-snapped to Snapped's twelve vertices: mark-perched.svg) and upright (mark.svg) at 512 and 64")
    y = yB + 32
    put(Tp('p-bone-512.png'), 24, y, label='perched 512')
    put(Tp('m-bone-512.png'), 560, y, label='upright 512')
    put(Tp('p-bone-64.png'), 1100, y, 1, label='perched 64', frame='#4A4541')
    put(Tp('m-bone-64.png'), 1200, y, 1, label='upright 64', frame='#4A4541')
    put(Tp('p-bone-64.png'), 1100, y + 100, 4, frame='#4A4541')
    put(Tp('m-bone-64.png'), 1380, y + 100, 4, frame='#4A4541')
    sub(1100, y + 365, 'same lattice, twelve edges, eye and fold; the body turned up to 1.24:1')
    sub(1100, y + 385, 'perched box 81 x 47.6 (1.7:1); upright box 63 x 51 (1.24:1)')

    # C ---------------------------------------------------------------- glyphs
    yC = yB + 32 + 512 + 50
    title(24, yC, 'C: the three glyph tiles, each beside the mark at the same height')
    sub(24, yC + 26, 'Row 1: ink on bone at 128 / 48 / 24 / 16 (3x, then 1x).  Row 2: tinted idle #7B8794 / working #C8A45C / attention #F3D58F on #12100F at 128 / 48 / 24 / 16 (1x, then 48 / 24 / 16 at 2x)')
    y = yC + 52
    desc = {'a': "a  full fold: the bird's head, chest and body at 0.95, hole runs off the bottom-left corner, the fold hairline (1.9 units) as in the mark",
            'b': "b  shoulder wedge: head, throat and chest as the hole, its back along the fold line, breast open to the bottom; a short tapering sliver of hole peels off at the shoulder along the back edge (the wing's upper edge)",
            'c': "c  negative mark: the whole upright mark with its fold at 0.78, centred, so the tile is the mark in negative; fold 2.2 units"}
    for g in 'abc':
        sub(24, y, desc[g], '#F2ECE4')
        y += 20
        x = 24
        put(Tp('m-bone-128.png'), x, y, label='mark 128'); x += 128 + 16
        put(Tp(f'g{g}-ink-128.png'), x, y, label=f'glyph-{g} 128'); x += 128 + 16
        put(Tp(f'g{g}-inv-128.png'), x, y, label=f'glyph-{g}-inverse 128'); x += 128 + 26
        for sz in (48, 24, 16):
            w, h = put(Tp(f'g{g}-ink-{sz}.png'), x, y, 3, label=f'{sz} (3x)', frame='#4A4541'); x += w + 12
        xx = x
        for sz in (48, 24, 16):
            w, h = put(Tp(f'g{g}-ink-{sz}.png'), xx, y + 10, 1); xx += w + 6
        sub(x, y + 64, '1x 48 / 24 / 16')
        y += 144 + 30
        panel = Image.new('RGB', (W - 48, 170), BG)
        sheet.paste(panel, (24, y))
        gx = 36
        for name in ('idle', 'working', 'attention'):
            xx = gx
            for sz in (128, 48, 24, 16):
                w, h = put(Tp(f'g{g}-{name}-{sz}.png'), xx, y + 8 + (128 - sz) // 2 * 0 + (0 if sz == 128 else 128 - sz - 8)); xx += w + 6
            xx += 6
            for sz in (48, 24, 16):
                w, h = put(Tp(f'g{g}-{name}-{sz}.png'), xx, y + 8 + (96 - 2 * sz), 2); xx += w + 6
            d.text((gx, y + 148), f'{name}: 128 / 48 / 24 / 16, then 48 / 24 / 16 at 2x', fill='#8A8177', font=small)
            gx += 548
        y += 170 + 24

    # D ---------------------------------------------------------------- text
    yD = y + 6
    title(24, yD, "D: terminal art in a <pre> (DejaVu Sans Mono 16.61 px = 10 px cells, line-height 1): Snapped's 24x8 = the CLI art; the upright bird's 24x8; the 48x16")
    pre = Image.open(Tp('pre1-1660.png')).convert('RGB')
    bbox = Image.eval(pre.convert('L'), lambda v: 255 if v > 24 else 0).getbbox()
    pre = pre.crop((0, 0, pre.width, min(pre.height, bbox[3] + 24)))
    sheet.paste(pre, (24, yD + 30))
    y = yD + 30 + pre.height + 10
    pre2 = Image.open(Tp('pre2-1660.png')).convert('RGB')
    bbox = Image.eval(pre2.convert('L'), lambda v: 255 if v > 24 else 0).getbbox()
    pre2 = pre2.crop((0, 0, pre2.width, min(pre2.height, bbox[3] + 24)))
    sheet.paste(pre2, (24, y))
    y += pre2.height + 24
    # E ---------------------------------------------------------------- overlays
    title(24, y, 'E: the vectors (55 % ink + outline) over their own 24x16 grids (grey cells, lattice 4.5 x 3.4 units)')
    put(Tp('overlay-400.png'), 24, y + 32, label='mark.svg over upright24')
    put(Tp('overlay-p-400.png'), 460, y + 32, label="mark-perched.svg over Snapped's grid.txt (byte-identical to r2-01)")
    y += 32 + 400 + 40
    sheet = sheet.crop((0, 0, W, y))
    out = F('preview.png')
    sheet.save(out, optimize=True)
    print(out, sheet.size, os.path.getsize(out) // 1024, 'KB')


if __name__ == '__main__':
    grids = build_files()
    print('\n'.join(grids['up24']))
    if '--poses' in sys.argv:
        C = UPRIGHT
        C2 = [P(19, 3), P(17, 2), P(13, 1), P(11, 1), P(9, 2), P(8, 4), P(5, 16), P(8, 16), P(12, 13), P(16, 14), P(17, 10), P(15, 6)]
        C3 = [P(19, 3), P(17, 3), P(13, 1), P(11, 1), P(9, 2), P(8, 4), P(5, 16), P(8, 16), P(12, 13), P(16, 14), P(17, 10), P(15, 6)]
        C4 = [P(19, 4), P(16, 2), P(12, 1), P(10, 1), P(8, 2), P(7, 4), P(4, 16), P(8, 16), P(12, 13), P(16, 14), P(17, 10), P(15, 6)]
        C4n = list(C); C4n[6] = P(5, 16)
        build_poses(sys.argv[sys.argv.index('--poses') + 1],
                    [('C4 eye(12,3)', C, UPRIGHT_EYE, UPRIGHT_FOLD), ('C4 eye(11,3)', C, ((51.75, 26.3), 3.2), UPRIGHT_FOLD),
                     ('C4 narrow tail(5,16) 1.24:1', C4n, UPRIGHT_EYE, UPRIGHT_FOLD), ('C2 upturned bill', C2, ((51.75, 26.3), 3.2), [P(8, 4), P(11, 11), P(8, 15)])])
    if '--variants' in sys.argv:
        V = [('a s.95 cell(28,24)', 'a', dict(s=0.95, cell=(28, 24))),
             ('a s.9 cell(28,24)', 'a', dict(s=0.9, cell=(28, 24))),
             ('b hole-wedge mouth4 len14', 'b', dict(mouth=4.0, length=14.0)),
             ('b hole-wedge mouth5 len18', 'b', dict(mouth=5.0, length=18.0)),
             ('b hole-wedge mouth3 len12', 'b', dict(mouth=3.0, length=12.0)),
             ('b ink-wedge mouth6 len14', 'b', dict(mouth=6.0, length=14.0, kind='ink')),
             ('c s.78 cell(32,16)', 'c', dict(s=0.78, cell=(32, 16))),
             ('c s.74 cell(32,16)', 'c', dict(s=0.74, cell=(32, 16)))]
        build_variants(sys.argv[sys.argv.index('--variants') + 1], V)
    if '--look' in sys.argv:
        build_look(sys.argv[sys.argv.index('--look') + 1])
    if '--preview' in sys.argv:
        build_preview(grids)
