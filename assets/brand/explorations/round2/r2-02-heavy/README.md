# r2-02-heavy — the raven in ten edges

**Look at:** `preview.png`

## The idea

The round-1 faceted raven cut again with fewer, bolder decisions: **ten straight
edges** instead of twelve, every one of them a grid corner. Two facets make the
bill — a long culmen that runs in one line from the crown to a knee, then a
short steep facet down to the tip, which is what a hooked bill is when you may
only bend once — and the gonys is dead flat from the chin to the tip, so the
tip is the lowest point of the bill and the whole head hangs forward of the
breast. The breast is deeper than round 1 (it bulges 9 units forward of the chin
where mark-02's receded 3.5, and the body is about 27 units deep from back
line to belly against mark-02's 23), the back is one long edge from the nape to the tail
tip, and the tail is a blunt wedge whose end is a 4.5 × 10 edge rather than a
stick. The eye is a round hole r 3.5. The aim was a mark that reads at 32 px
as a raven without needing the head drawing: at 32 it does (see the sheet),
at 16 it does not, so the head drawing ships anyway.

## The grid and how the facets snap to it

The terminal grid is **24 columns × 16 half-rows** over the path box of
`mark.svg` (inside the `translate(2,11)` group): column *c* → x = 4.5 *c*,
half-row *r* → y = 17 + 3.4 *r*. The ten vertices, as (column, half-row):

| vertex | cell | path coords |
|---|---|---|
| bill tip | (21, 6) | 94.5, 37.4 |
| hook knee | (19, 2) | 85.5, 23.8 |
| crown | (12, 0) | 54, 17 |
| nape | (9, 3) | 40.5, 27.2 |
| tail tip | (3, 12) | 13.5, 57.8 |
| tail end | (4, 15) | 18, 68 |
| under-tail notch | (9, 12) | 40.5, 57.8 |
| belly | (13, 13) | 58.5, 61.2 |
| breast | (17, 10) | 76.5, 51 |
| chin | (15, 6) | 67.5, 37.4 |

The eye circle is centred on cell (14, 3) — (65.25, 28.9) — and r 3.5 is the
largest radius that leaves the neighbouring cells more than half ink, so the eye
is exactly one ground cell in the grid and one `▀`/`"` notch in the text. The
gonys (chin → tip) is a grid row, so it is a clean cell edge; the tail end and
the hook facet are 1:2 cell diagonals; the crown-to-knee line is 7:2 and the
back 6:9, which rasterise as regular staircases. Box x 13.5–94.5, y 17–68 (the
canon's x 10–95, y 17–69), bbox centre in the viewBox (56, 53.5); at the
launcher's scale 0.72 the farthest vertex is 31.8 dp from centre, inside the
33 dp safe radius.

`grid.txt` (written by `build.py`) is the cell rasterisation of the polygon (a cell is ink when the
evenodd path covers ≥ 50 % of it, exact clipping, no hand retouching), in two
sizes: `heavy` 24 × 16 and `heavy-2x` 48 × 32, the same vertices on the 2×
subdivision. The three shipped renderings are generated from it with
`../../marks-b/mark-04-pixel/grid2text.py`: `ascii-halfblock.txt` and
`ascii-7bit.txt` from the 24 × 16 grid (24 columns, 8 lines), `ascii-braille.txt`
from the 48 × 32 grid (braille is 2 × 4 dots per cell, so it comes out the same
24 columns × 8 lines). Two extras: `ascii-halfblock-48.txt` (48 columns, 16
lines, the banner that actually looks like the mark) and `ascii-braille-12.txt`
(the 24 × 16 grid as 12 braille columns, a status-line size that does not read
as a bird and is there to show that). All five pass `tools/asciicheck.py`.
The 7-bit file is the one for `client/huginn.sh` / `huginn.ps1`.

## Files

| file | what |
|---|---|
| `mark.svg` | ink `#161310`, one evenodd path (10-gon + circular eye hole), 108 viewBox, `translate(2,11)` |
| `mark-inverse.svg` | bone `#E8E2DA` on `#12100F`, same path |
| `mark-tile.svg` | app-icon master: ink mark on the square bone tile (box x 15.5–96.5, y 28–79 of the 108 tile) |
| `mark-16.svg` | the head drawing for 16–22 px: head, hooked bill and neck in 8 edges, eye hole r 6.5, box x 14–105 / y 24–90 of a plain 108 viewBox |
| `glyph.svg`, `glyph-inverse.svg` | the glyph tile: rounded square (64 viewBox, rx 14), this head and breast cut out in the same 8 facets, breast open to the bottom edge, eye a 4 × 4 cell at x 28–32, y 24–28 (whole pixels at 16/32/64); one evenodd path |
| `grid.txt` | the two grids (24 × 16 and 48 × 32), with the generation commands in the header |
| `ascii-halfblock.txt`, `ascii-7bit.txt`, `ascii-braille.txt` | the terminal renderings, exact from `grid.txt`; the 7-bit one is pure ASCII |
| `ascii-halfblock-48.txt`, `ascii-braille-12.txt` | extras, see above |
| `preview.png` | the contact sheet |
| `build.py` | the generator: the vertex list in, every SVG, `grid.txt` and the text files out (Python 3, no dependencies beyond `grid2text.py`); edit the vertices, not the files |

## What the sheet shows

Row 1: the mark at 512 on bone and inverse on `#12100F`; the tile at 200 and
the launcher circle mask with the 66 dp safe ring. Row 2: the mark at 64 / 32 /
16 on both grounds, the head drawing at 22 / 16 on both grounds and tinted tray
idle at 16. Row 3: the glyph at 48 / 24 / 16 on the app ground tinted idle
`#7B8794`, working `#C8A45C`, attention `#F3D58F`, plus bone, inverse, on bone,
and both at 160. Row 4: the 24-column half-block, the 7-bit, the braille and the
48-column half-block in a `<pre>` (DejaVu Sans Mono, line-height 1) on dark —
Chromium draws block glyphs from the font here, so the hairline seams and the
1:1.66 cell are the GitHub/xterm look, not a terminal's. Row 5: the vector in
gold over its own 24 × 16 and 48 × 32 cell renderings in grey, so the match
can be judged: every vertex lands on a cell corner, every ink cell is at least
half under the vector, and the only disagreement is the stair-steps along the
diagonals.

## Strengths

- Reads as a raven at 32 px without the head drawing; the heavy bill and the
  wedge tail survive where mark-02's slimmer body turned into a smudge.
- Ten edges, four of them on a grid row or a 1:1 / 1:2 cell diagonal: the simplest
  polygon in the set to port, morph, trace and animate, and the 48-column
  banner and the 24-column braille are honest pictures of the same shape.
- The glyph is the strongest of the three forms: the heavy hooked head gives
  the hole a silhouette that still reads as a bird at 24 and as "a tile with
  something in it" at 16; the inverse (dark raven rising through a bone tile)
  is the best single image on the sheet.
- Same box, transform, winding, eye-as-hole rule and palette as the canon; the
  hand-carried copies swap path data and nothing else.

## Weaknesses (honest)

- The hook is a hawk's as much as a raven's at 512: with one knee the culmen
  cannot arch, it bends, and the steep final facet reads raptor on first look;
  the flat gonys and the round eye bring it back, but a second look is needed.
  The single-peak crown behind the knee has the same "cap" quality.
- The 24-column half-block and 7-bit renderings are coarse: 18 × 15 cells for
  the whole bird, the tail is one `▀` and the under-tail notch splits the
  bottom row into tail and belly fragments. They are exact, and they are a bird
  with a bill, but they are not good terminal art; the 48-column file is.
- The grid cell (4.5 × 3.4) is squarer than half a terminal cell (1 × ~1), so
  every text rendering is about 16 % taller, relative to its width, than the
  vector. Every variant on this grid shares that; it is the grid's cost.
- "Exact" means every vertex is a cell corner and the cell rule is
  deterministic, not that the half-block equals the vector pixel for pixel:
  the back (6:9) and the crown line (7:2) are irregular staircases.
- At 16 the full mark is a smudge with a bill, like every full-pose mark; the
  head drawing is the fix, and at 16 the glyph's bill is two pixels.

## Weakest form

The terminal renderings at the brief's 24 × 16 — specifically the 8-line
half-block and its 7-bit twin. The mark and the glyph hold together; the
8-line text is a faithful but small bird.
