#!/usr/bin/env python3
"""build.py — r2-02-heavy: the vertex list -> mark.svg, mark-inverse.svg, mark-tile.svg, mark-16.svg, glyph*.svg,
grid.txt and the text renderings. Run it from this folder: python3 build.py

Grid (path coordinates, inside the translate(2,11) group): column c -> x = 4.5*c (24 columns, x 0..108),
half-row r -> y = 17 + 3.4*r (16 half-rows, y 17..71.4). A cell is ink when the evenodd path covers
>= 50% of it (exact polygon clipping; the eye circle is subtracted by sampling).
"""
import sys, os, math, json

CW, CH, Y0 = 4.5, 3.4, 17.0
INK, BONE = '#161310', '#E8E2DA'

def P(c, r): return (CW*c, Y0+CH*r)

def clip(poly, xmin, xmax, ymin, ymax):
    def inside(p, edge):
        k, v, s = edge
        return (p[k] >= v) if s else (p[k] <= v)
    def inter(a, b, edge):
        k, v, _ = edge
        t = (v - a[k]) / (b[k] - a[k])
        return (a[0] + t*(b[0]-a[0]), a[1] + t*(b[1]-a[1]))
    out = list(poly)
    for edge in [(0,xmin,True),(0,xmax,False),(1,ymin,True),(1,ymax,False)]:
        inp, out = out, []
        if not inp: break
        s = inp[-1]
        for e in inp:
            if inside(e, edge):
                if not inside(s, edge): out.append(inter(s, e, edge))
                out.append(e)
            elif inside(s, edge):
                out.append(inter(s, e, edge))
            s = e
    return out

def area(poly):
    a = 0
    for i in range(len(poly)):
        x1,y1 = poly[i]; x2,y2 = poly[(i+1)%len(poly)]
        a += x1*y2 - x2*y1
    return abs(a)/2

def circle_in_cell(cx, cy, r, xmin, xmax, ymin, ymax, n=48):
    cnt = 0
    for i in range(n):
        for j in range(n):
            x = xmin + (xmax-xmin)*(i+0.5)/n; y = ymin + (ymax-ymin)*(j+0.5)/n
            if (x-cx)**2 + (y-cy)**2 <= r*r: cnt += 1
    return cnt/(n*n)*(xmax-xmin)*(ymax-ymin)

def rasterise(pts, eye, r):
    rows = []
    for rr in range(16):
        row = ''
        for c in range(24):
            xmin, xmax, ymin, ymax = CW*c, CW*(c+1), Y0+CH*rr, Y0+CH*(rr+1)
            cov = area(clip(pts, xmin, xmax, ymin, ymax)) - circle_in_cell(eye[0], eye[1], r, xmin, xmax, ymin, ymax)
            row += '#' if cov >= 0.5*CW*CH - 1e-9 else '.'
        rows.append(row)
    return rows

def path_d(pts, eye, r):
    d = 'M%g,%g ' % pts[0] + ' '.join('L%g,%g' % p for p in pts[1:]) + ' Z'
    d += ' M%g,%g m-%g,0 a%g,%g 0 1,0 %g,0 a%g,%g 0 1,0 -%g,0 Z' % (eye[0], eye[1], r, r, r, 2*r, r, r, 2*r)
    return d

DEST = os.path.dirname(os.path.abspath(__file__)) or '.'
G2T = os.path.join(DEST, '..', '..', 'marks-b', 'mark-04-pixel', 'grid2text.py')
import subprocess

# ---- the mark: 10 vertices on the 24x16 grid, clockwise from the bill tip --------------------
CELLS = [(21,6),(19,2),(12,0),(9,3),(3,12),(4,15),(9,12),(13,13),(17,10),(15,6)]
NAMES = ['bill tip','hook (culmen knee)','crown','nape','tail tip','tail end','under-tail notch',
         'belly','breast','chin']
EYE_CELL, EYE_R = (14,3), 3.5
pts = [P(*c) for c in CELLS]
eye = (CW*(EYE_CELL[0]+0.5), Y0+CH*(EYE_CELL[1]+0.5))
d = path_d(pts, eye, EYE_R)

def rasterise_n(pts, eye, r, cols, rows, cw, ch):
    out = []
    for rr in range(rows):
        row = ''
        for c in range(cols):
            xmin, xmax, ymin, ymax = cw*c, cw*(c+1), Y0+ch*rr, Y0+ch*(rr+1)
            cov = area(clip(pts, xmin, xmax, ymin, ymax)) - circle_in_cell(eye[0], eye[1], r, xmin, xmax, ymin, ymax)
            row += '#' if cov >= 0.5*cw*ch - 1e-9 else '.'
        out.append(row)
    return out

grid24 = rasterise_n(pts, eye, EYE_R, 24, 16, CW, CH)
grid48 = rasterise_n(pts, eye, EYE_R, 48, 32, CW/2, CH/2)

vert_comment = ', '.join('%s (%g,%g)=cell(%d,%d)' % (n, p[0], p[1], c[0], c[1]) for n, p, c in zip(NAMES, pts, CELLS))
HEAD = ('Huginn mark, r2-02-heavy: the perched raven in 10 straight edges and a circular eye hole. '
        'Every vertex sits on a 24 x 16 half-block grid over the path box: column c -> x = 4.5c, half-row r -> y = 17 + 3.4r, '
        'so the half-block / braille / 7-bit renderings in grid.txt are exact cell rasterisations of this polygon. '
        'ONE evenodd path, clockwise from the bill tip; 108 viewBox, translate(2,11), box x 13.5-94.5, y 17-68. '
        'Eye: circle r 3.5 at the centre of cell (14,3) = (65.25,28.9). Ink #161310, bone #E8E2DA. '
        'Vertices: ' + vert_comment)

def svg108(d, fill, bg=None, comment=HEAD, extra=''):
    s = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n'
    s += '  <!-- %s -->\n' % comment
    if bg: s += '  <rect width="108" height="108" fill="%s"/>\n' % bg
    s += extra
    s += '  <g transform="translate(2,11)">\n    <path fill="%s" fill-rule="evenodd" d="%s"/>\n  </g>\n</svg>\n' % (fill, d)
    return s

open(f'{DEST}/mark.svg','w').write(svg108(d, INK))
open(f'{DEST}/mark-inverse.svg','w').write(svg108(d, BONE, None, 'Inverse: bone mark for dark grounds (#12100F). Same polygon as mark.svg.'))
open(f'{DEST}/mark-tile.svg','w').write(svg108(d, INK, BONE, 'App-icon master: ink mark on the square bone tile; the box x 15.5-96.5, y 28-79 of the 108 tile is a 10-14 % inset. Same polygon as mark.svg.'))

# ---- mark-16: the head drawing, same facet language (hook, culmen, crown, nape; chin, flat gonys) -----------
HEAD16 = [(105,57),(92,36),(42,24),(14,41),(14,76),(52,90),(70,76),(64,59)]
EYE16 = ((64,42), 6.5)
d16 = path_d(HEAD16, *EYE16)
open(f'{DEST}/mark-16.svg','w').write(
 '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n'
 '  <!-- mark-16: the compact drawing for 16-22 px (tray, favicon-16). Head, hooked bill and neck in 8 straight edges plus a '
 'circular eye hole r 6.5, near-square box (x 14-105, y 24-90 of a plain 108 viewBox, no translate). Same facets as mark.svg: '
 'the hook knee (92,36), the long culmen to the crown (42,24), the nape (14,41); near-flat gonys from the chin (64,59) to the tip (105,57). -->\n'
 '  <path fill="#161310" fill-rule="evenodd" d="%s"/>\n</svg>\n' % d16)

# ---- glyph: 64 rounded square, the head + breast cut out, eye a 4x4 cell ---------------------------
HOLE = [(57,32),(52,24),(30,16),(16,22),(12,64),(44,64),(47,50),(38,32)]
hole_d = 'M%g,%g ' % HOLE[0] + ' '.join('L%g,%g' % p for p in HOLE[1:]) + ' Z'
glyph_d = ('M14,0 H50 A14,14 0 0 1 64,14 V50 A14,14 0 0 1 50,64 H14 A14,14 0 0 1 0,50 V14 A14,14 0 0 1 14,0 Z '
           + hole_d + ' M28,24 h4 v4 h-4 Z')
GLYPH_COMMENT = ('Huginn glyph tile, r2-02-heavy: the container is the ink, the raven is the hole. The hole is THIS variant\'s head cut '
                 'in the same 8 facets as mark-16 (hook knee (52,24), culmen to the crown (30,16), nape (16,22), neck down to the bottom '
                 'edge, breast (47,50), chin (38,32), flat gonys to the tip (57,32)); the breast opens onto the bottom edge so the bird '
                 'rises out of the ground. The eye is a 4x4 cell of ink at x 28-32, y 24-28: whole pixels at 16, 32 and 64. '
                 'ONE evenodd path (container - raven + eye); viewBox 0 0 64 64; rx 14. Tint it by state in the tray.')
open(f'{DEST}/glyph.svg','w').write(
 '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="512" height="512">\n  <!-- %s -->\n'
 '  <path fill="#161310" fill-rule="evenodd" d="%s"/>\n</svg>\n' % (GLYPH_COMMENT, glyph_d))
open(f'{DEST}/glyph-inverse.svg','w').write(
 '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="512" height="512">\n'
 '  <!-- Inverse: the same single path in bone on an ink ground; the raven is then the dark ground showing through. -->\n'
 '  <rect width="64" height="64" fill="#161310"/>\n  <path fill="#E8E2DA" fill-rule="evenodd" d="%s"/>\n</svg>\n' % glyph_d)

# ---- grid.txt --------------------------------------------------------------------------------------
gridtxt = f'''// r2-02-heavy — the mark's vertices on the terminal grid. '#' ink, '.' ground. GENERATED by the vertex
// list in README.md (python3 build.py here); edit the vertices, not these rows.
//
// The grid: 24 columns x 16 half-rows over the path box of mark.svg (inside the translate(2,11) group):
//   column c  -> x = 4.5 * c          (24 columns span x 0..108)
//   half-row r -> y = 17 + 3.4 * r    (16 half-rows span y 17..71.4; the mark uses r 0..14, y 17..68)
// A cell is ink when the evenodd path covers >= 50 % of it (exact polygon clipping; the eye circle subtracted),
// so the 24x16 grid is the cell rasterisation of the vector with no hand retouching. Every vertex of the
// polygon is a grid corner, and the eye circle (r 3.5) is centred on cell (14,3), so it is exactly one ground cell.
//
// heavy      24x16  the terminal grid proper: one character = one column x two half-rows (half-block, 7-bit)
// heavy-2x   48x32  the same vertices on the 2x subdivision (cells 2.25 x 1.7): the braille source (2x4 dots per
//                   character gives the same 24 columns x 8 lines) and a finer half-block banner (48 x 16)
//
//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid.txt heavy    --frame > ascii-halfblock.txt
//   python3 ../../marks-b/mark-04-pixel/grid2text.py ascii     grid.txt heavy    --frame > ascii-7bit.txt
//   python3 ../../marks-b/mark-04-pixel/grid2text.py braille   grid.txt heavy-2x         > ascii-braille.txt
//   python3 ../../marks-b/mark-04-pixel/grid2text.py halfblock grid.txt heavy-2x --frame > ascii-halfblock-48.txt
//   python3 ../../marks-b/mark-04-pixel/grid2text.py braille   grid.txt heavy            > ascii-braille-12.txt
//
// Vertices (cell = (column, half-row)): {', '.join('%s (%d,%d)' % (n, *c) for n, c in zip(NAMES, CELLS))}; eye cell (14,3).

@heavy 24x16
{chr(10).join(grid24)}

@heavy-2x 48x32
{chr(10).join(grid48)}
'''
open(f'{DEST}/grid.txt','w').write(gridtxt)

def g2t(mode, name, frame, out):
    cmd = ['python3', G2T, mode, f'{DEST}/grid.txt', name] + (['--frame'] if frame else [])
    txt = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    open(f'{DEST}/{out}', 'w', encoding='utf-8').write(txt)
    return txt

hb = g2t('halfblock', 'heavy', True, 'ascii-halfblock.txt')
a7 = g2t('ascii', 'heavy', True, 'ascii-7bit.txt')
br = g2t('braille', 'heavy-2x', False, 'ascii-braille.txt')
hb48 = g2t('halfblock', 'heavy-2x', True, 'ascii-halfblock-48.txt')
br12 = g2t('braille', 'heavy', False, 'ascii-braille-12.txt')
print(hb); print(a7); print(br); print(hb48); print(br12)

