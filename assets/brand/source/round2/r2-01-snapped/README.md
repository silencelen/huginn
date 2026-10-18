# r2-01-snapped — the faceted raven, snapped to the terminal grid (the control)

**Look at:** `preview.png`

## The idea

The round-1 `mark-02-faceted` raven kept as close as possible to its silhouette,
with every one of its 12 vertices moved to the nearest node of a 24 × 16
half-block grid laid over the 108 box, and the eye circle centred on one grid
cell. Nothing else is redesigned: same perched profile, same 12 facets (two for
the arched bill, a flat crown, the concave nape, one long edge from nape to tail
tip, a wedge tail, a notched belly, a tall breast facet), same ink/bone pair,
same `translate(2,11)` and launcher transform. Because the vertices are grid
nodes and every edge is horizontal, vertical or a diagonal that never passes
through a cell centre, rasterising the vector onto its own grid is unambiguous:
`grid.txt` *is* `mark.svg` on the terminal grid, and the half-block, braille and
7-bit renderings are generated from it by `grid2text.py` with no hand tuning.
The glyph tile and the 16 px head drawing are built from the same head nodes
(bill tip, culmen, forehead, crown, back of head, nape, throat, breast), scaled.
This is the variant the other four are measured against.

## The grid and how the facets snap to it

- **24 columns × 16 half-rows** = 24 × 8 terminal cells. One half-row cell is
  **4.5 × 3.4 units** of the 108 box: column *c* starts at x = 4.5·c, half-row
  *r* at y = 14.4 + 3.4·r (path coordinates of `mark.svg`, before the
  `translate(2,11)`); the rows span y 14.4–68.8, inside the canon box y 17–69.
- **Vertices (col,row), clockwise from the bill tip:** bill tip (21,6), culmen
  (19,3), forehead (16,1), crown (13,1), back of head (10,3), nape (9,5), tail tip
  (3,13), tail bottom (3,15), under-tail notch (8,13), belly bottom (12,14),
  breast (15,10), throat (16,6). In units: (94.5,34.8) (85.5,24.6) (72,17.8)
  (58.5,17.8) (45,24.6) (40.5,31.4) (13.5,58.6) (13.5,65.4) (36,58.6) (54,62)
  (67.5,48.4) (72,34.8). The notch and the belly bottom are round-1's points
  exactly (36,59 → 36,58.6; 54,62 → 54,62).
- **Eye:** a circle of r 3.2 at the centre of cell (14,3) = (65.25, 26.3)
  (round-1: 66.5,27 r 3.5). That cell is the one ground cell inside the head;
  its four neighbours stay ≥ 64 % ink, so the eye is stable in the raster.
- **The parity rule.** An edge whose (Δcol, Δrow) in lowest terms is odd:odd
  (1:1, 3:1, 5:3 …) passes through cell centres and splits those cells exactly
  in half, which makes a 50 % threshold a coin toss. So every edge here is
  horizontal, vertical, or p:q with p or q even (2:3, 3:2, 1:2, 3:4, 2:1, 1:4,
  5:2, 4:1 …). The build asserts it. The crown and the bill underside lie on
  half-row boundaries, the tail edge on a column boundary. After snapping, no
  cell's coverage falls within 0.42–0.58.
- **Box** x 13.5–94.5, y 17.8–65.4 (round-1: 10.5–94.6, 18.5–67.5), inside the
  canon box x 10–95, y 17–69, so `RavenMark.kt`, the adaptive-icon group
  (pivot 52,43, scale 0.72: farthest vertex 32.1 dp, inside the 33 dp safe
  radius) and the tray composition take the new path data unchanged.
- **What moved, honestly:** the mark is one column (4.5 units) narrower than
  round-1 on the left, because 19 columns = 85.5 units do not fit the 85-unit
  canon box, so the tail tip is at x 13.5 instead of 10.5; the crown's back
  corner moved from 55 to 58.5 (a 13.5-unit flat crown instead of 15) because
  (12,1)→(10,3) is a 1:1 diagonal; the bill underside is exactly horizontal
  (round-1 rose 2.5 units to the tip); the tail's left edge is vertical and
  the tail bottom is at y 65.4 instead of 67.5 (a row-16 corner put the tail
  33.4 dp from the launcher pivot); the breast vertex is (67.5,48.4) instead
  of (68,51). At 64 px the two marks are hard to tell apart (the sheet shows
  round-1 beside it).

## Files

| file | what |
|---|---|
| `mark.svg` | ink `#161310`, ONE evenodd path: the 12-vertex polygon + circular eye hole; viewBox 0 0 108 108, `translate(2,11)` |
| `mark-inverse.svg` | the same in bone `#E8E2DA` |
| `mark-tile.svg` | app-icon master: ink mark on a square bone tile, the mark's box scaled to 86.4 (10 % inset) and centred |
| `mark-16.svg` | the head for 16–22 px: the mark's head nodes scaled 1.7× about the bill tip, closed by a straight neck and a belly, 10 edges + eye hole r 6.5; box x 13–105 / y 23–92 of a plain 108 viewBox |
| `glyph.svg`, `glyph-inverse.svg` | the glyph tile (64 box, rounded square rx 14): THIS head cut out, scaled 0.85, 18 units of ink above the crown and 9 right of the tip, nape dropping straight to the bottom edge, breast flaring to it; eye a 4×4 ink cell at x 28–32, y 24–28; one evenodd path |
| `grid.txt` | `mark.svg` rasterised onto the 24 × 16 grid (≥ 50 % coverage = ink), `#`/`.`, with the grid definition and the regeneration commands in its header |
| `ascii-halfblock.txt` | 24 cols × 8 lines of `▀▄█` in a box-drawing frame (26 × 10). Unicode; line-height-1.0 terminals (tmux, `huginn-status`, the apps' terminal view) |
| `ascii-braille.txt` | 12 × 4 braille cells (2 × 4 dots each), U+2800-padded, no frame. Unicode; README, docs |
| `ascii-7bit.txt` | 24 × 8 of `#` (full cell), `"` (top half), `_` (bottom half) in a `+-|` frame. Pure 7-bit ASCII: the CLI client files |
| `preview.png` | the contact sheet |

All three text files pass `tools/asciicheck.py` and regenerate byte-for-byte
from `grid.txt` with `../../marks-b/mark-04-pixel/grid2text.py` (commands in
the grid header).

## What the sheet shows

Row 1: the mark at 512 on bone and on `#12100F`; the tile master at 200; the
launcher (adaptive canvas, the Android group transform, 72 dp circle mask,
dashed 66 dp safe ring); round-1 at 64/32 for comparison. Row 2: the mark and
the inverse at 64/32/16 at 1× and magnified; the head at 22/16 on bone and as
the tray draws it (bone on the app ground). Row 3: the head and the glyph pair
at 150; the glyph and its inverse at 48/24/16. Row 4: the glyph at 48/24/16 on
the app ground tinted idle `#7B8794`, working `#C8A45C`, attention `#F3D58F`;
the braille and 7-bit files in a `<pre>`. Row 5: the half-block file in a
`<pre>` (DejaVu Sans Mono, line-height 1) at 10 px and 16 px cells. Row 6: the
vector mark overlaid on its own `grid.txt` cells, once on the exact 4.5 × 3.4
grid and once at the 10 × 18 px cell aspect a terminal actually draws.

## Strengths

- **It is round-1.** The owner's chosen silhouette survives the snap: at 512
  the same bird, at 64/32 hard to distinguish from the original. Every
  hand-carried copy (Compose, Java2D, VectorDrawable, `.ico`) takes the new
  path data unchanged.
- **The terminal art is exact by construction, not by tracing.** `grid.txt`
  is a deterministic function of `mark.svg` (every vertex a node, no edge
  through a cell centre), so a change to the mark regenerates all three text
  forms with no hand tuning and no ambiguity; the overlay shows every edge of
  the half-block art within half a cell of the vector.
- **Three forms from one set of nodes.** The glyph's hole and the 16 px head
  are the mark's own head vertices under a scale, so the tray tile, the
  favicon head and the hero mark share one bill, one crown, one eye position.
- Clean, stable eye in all three forms: a circle on a cell centre in the
  mark, a whole-pixel 4 × 4 cell in the glyph, a one-cell hole in the raster.
- The glyph reads as "a tile with a bird" at 16 in all three state tints, and
  the inverse (dark raven rising through a bone tile) is a good image at 150.

## Weaknesses (honest)

- **24 × 16 is coarse for a perched bird.** In the half-block art the bill is
  a two-half-row bar, the tail tip is a single `▀` at the bottom-left, the
  belly notch is a three-cell dent and the eye is one cell. It reads as a
  bird with a bill and a tail; it is not a good drawing at this size, and it
  is smaller than the 24 × 8 hand-tuned raven in `ascii-02` only in finish,
  not in cell count.
- **The braille file is too small to be useful** (12 × 4 cells: a blob with
  a bill). "Exact from grid.txt" ties braille to the same 24 × 16 grid; a
  README-grade braille raven needs a 48 × 32 grid of the same polygon, which
  this variant does not ship.
- **Exact in cells, not in proportion.** The grid cell is 4.5 × 3.4 (1.32 : 1)
  but a terminal's half-cell is about 10 × 9 (1.11 : 1) or 8 × 8, so the
  half-block art shows the bird about 19 % taller than the vector (the sheet's
  second overlay). The match is on the cell grid; the aspect belongs to the
  font.
- The grid cost the silhouette a little: one column of width on the tail
  side, a slightly shorter crown, a dead-horizontal bill underside and a
  vertical tail edge. The bill reads marginally heavier and flatter than
  round-1's.
- The glyph's head is wide and low because the mark's head is (17 units tall
  on 54 wide): in the 64 tile the hole is a long flat head with a heavy bill,
  and at 16 the species is gone (corvid, or any heavy-billed bird), the same
  ceiling `mark-05` reported.
- The full mark at 16 is the usual smudge; the head drawing is the fix, as in
  round-1, and it is nearly the same drawing.
- Chromium's font-drawn `▀▄█` show hairline seams in the `<pre>` renders
  (known from round 1); terminals that draw blocks as cells do not.

## Weakest of the three forms

**The terminal rendering**, and within it the braille file. The mark and the
glyph are each as strong as their round-1 parents; the text art is exact but
coarse, and 24 × 16 half-blocks are the limit of what "exact" buys at this
grid. A variant that wants better terminal art needs a finer grid or a
simpler silhouette, which is what the other four are for.
