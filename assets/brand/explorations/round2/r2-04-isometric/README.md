# r2-04-isometric — the raven on a 30/60/90 grid

**Look at:** `preview.png`. **The riskiest of the five**, on purpose: it bets
that an angle system can stand in for drawing, and that the terminal's
triangle glyphs are safe to use. The second bet does not fully hold (below).

## The idea

Every edge of the raven is flat, vertical, 30° or 60°. Nothing else. The
bill is a 30° blade with a 60° undercut, the crown is flat, the back of the
head drops at 60°, the back and folded wing run at 30°, the tail hangs at
60°, the belly is flat and the breast rises at 60° to the chin. Twelve
vertices, the same count as the chosen round-1 mark, but no facet was
placed by eye: each was placed by the system, so the bill, the crown, the
back and the tail rhyme. That is what "engineered" means here, and it is
also why the terminal gets diagonals for free: on a terminal cell a 60° line
is the cell's own diagonal, so the shoulder, the tail, the breast and the
undercut come out as `◢` and `◤`, not as stairs. The head drawing and the
glyph tile are cut on the same angles (column and half-row scaled to their
boxes), so the three forms share one geometry rather than one style.

## The grid, and how the facets snap to it

24 columns × 16 half-rows (8 text rows). A column is 2·√3 = 3.464 units of
the 108 box and a half-row is 3 units, origin (10.5, 18.5) in the
`translate(2,11)` group, so the box is x 10.5–93.6, y 18.5–66.5 (inside the
canon's 10–95 / 17–69). The cell is therefore 3.464 × 6: aspect 1 : √3 =
1 : 1.732. DejaVu Sans Mono at line-height 1 is 1 : 1.66, a 10×18 px
terminal 1 : 1.8, so a real terminal stretches the drawing by between −4 %
and +4 %, less than the control's 4.5 × 3.4 cell does.

On that cell:

| edge | in cells | in the text |
|---|---|---|
| 60° | one column by one text row | exactly a cell diagonal → `◢ ◣ ◤ ◥` |
| 30° | three columns by one text row | a three-column stair of half-blocks (no glyph exists) |
| flat / vertical | on cell edges | exact |

Every vertex of `mark.svg` is on an integer (column, half-row), and every
60° edge starts and ends on an *even* half-row, so each one is whole cell
diagonals and never half of one. `iso.py` holds the polygon as grid
coordinates, refuses any edge off the system, writes the SVGs, rasterises
the polygon by exact area coverage into `grid.txt` (a cell becomes a
triangle only when its coverage *is* that triangle to within 2 %, otherwise
the nearest half-block), and packs `grid.txt` into the text files.
`python3 iso.py check` re-packs and diffs the shipped files; every
`ascii-*.txt` passes `tools/asciicheck.py`.

`grid.txt` carries two grids: `half` (24 × 16, `#` `.` and `r l L R` for the
four triangles, written in both half-rows of their cell) and `fine` (48 × 32,
the same polygon at 2 × 2 per half-row cell), which is the braille source
(2 × 4 dots per cell → 24 × 8 braille cells). The eye is a circle of r 3.5
centred on a grid corner, so it rasterises to a 2 × 2 half-row hole without
special-casing.

## Files

| file | what |
|---|---|
| `mark.svg` | the mark: ink `#161310`, one evenodd path (12-edge polygon + circular eye hole), 108 viewBox, `translate(2,11)` |
| `mark-inverse.svg`, `mark-tile.svg` | bone version; ink mark on the bone 108 square (the box is 83 × 48, so the inset is ~11 % on the long side) |
| `mark-status.svg` | the polygon without the eye hole (notification icon; extra, as in round 1) |
| `mark-16.svg` | the head drawing for 16–22 px: head, bill and neck in 8 edges on the same angles (column 6, half-row 5.196), eye hole r 6.5, box x 18–102 / y 25–98 |
| `glyph.svg`, `glyph-inverse.svg` | the glyph tile: rounded square rx 14 of the 64 box, this head and breast cut out (column 4, half-row 3.464; left wall x 10, tip x 54, breast opening x 10–46), the hole's corner follows the tile's own arc, eye a 4 × 4 cell at x 24–28 / y 24–28 (whole pixels at 16, 32, 64); one evenodd path |
| `grid.txt` | the two grids above; the source of every text file |
| `ascii-halfblock.txt` | 24 × 8 in `▀▄█` plus `◢◣◤◥`, framed. Unicode; tmux, `huginn-status`, the apps' terminal view |
| `ascii-halfblock-plain.txt` | the same with the triangles replaced by their nearest half-block: for any font or terminal where `◢` is not a full-cell glyph (see weaknesses) |
| `ascii-braille.txt` | 24 × 8 braille from the fine grid, padded with U+2800. README, docs |
| `ascii-7bit.txt` | `#` full, `"` top, `_` bottom, and `/` `\` for the diagonals, framed with `+-|`. Pure ASCII, for `huginn.sh` / `huginn.ps1` |
| `iso.py` | the generator: `build`, `pack grid.txt`, `check` |
| `preview.png` | the contact sheet |

## What the sheet shows

Top: the mark at 512 on bone and on `#12100F`; the glyph at 256 in ink and
inverse; the tile; the launcher mock (mark scaled 0.68 about the canvas
centre inside the 72 dp mask with the 66 dp safe ring: farthest point
32.4 dp). Then the mark at 64 / 32 / 16 on both grounds, at 1× and at 4×
pixels; the head at 22 / 16 on both grounds (1× and 6×); the glyph at
48 / 24 / 16 on the app ground tinted idle `#7B8794`, working `#C8A45C`,
attention `#F3D58F`. Then the three text renderings (and the plain
fallback) in a `<pre>`, DejaVu Sans Mono 20 px, line-height 1, on dark,
plus `grid.txt` drawn as cell boxes, which is what a terminal that draws
block glyphs itself shows. Last, the exactness check: the vector mark in
45 % gold over the cell-drawn grid (column 20 px, √3 cell), and over the
font-drawn `<pre>` at line-height 1.043 (= √3 × the advance; at 1.0 every
row is 4 % shorter).

In the overlay the 60° edges, the flat edges and the eye coincide with the
vector; the 30° edges (culmen, back, under-tail, belly) are stairs whose
error is at most one third of a half-row (1 unit). The half-block, the
braille and the 7-bit file are the same cells and the same stairs.

## Strengths

- The three forms are one geometry: the mark, the head and the glyph's hole
  are built from the same four angles, so they match when set side by side
  in a way that "same style" marks only approximate.
- The 7-bit raven is the best of the family's ASCII ravens: real `/`
  diagonals on the shoulder, tail, breast and bill, which is what hand-drawn
  ASCII art has always done, so it looks intended rather than sampled.
- The mark reads as a bird with a heavy, straight, corvid bill at 64 and 32
  on both grounds, and the head reads at 22 and 16 with the bill as the key
  feature. Twelve vertices; ports anywhere; morphs vertex for vertex.
- The glyph reads as a tile with a bird in it at 48 and 24 and tints in one
  colour; its eye cell is on whole pixels like mark-05's.
- Exactness is structural (`iso.py check`), not asserted.

## Weaknesses (honest)

- **The triangle glyphs are font-dependent.** In DejaVu Sans Mono (and
  Menlo, Hack, and most mono fonts) `◢◣◤◥` are drawn as a 45° triangle
  filling the cell's width but only ~60 % of its height (DejaVu: y −79 to
  1143 of a 2048 em), so font-drawn they leave a gap above and below each
  diagonal cell and the hypotenuse is 45°, not 60°. The sheet shows exactly
  that. Terminals that draw the geometric-shape block themselves (kitty and
  WezTerm, from their sources; not verified on a device here) fill the cell
  and the diagonal is true. Everything else should use
  `ascii-halfblock-plain.txt`, which is a stair like the other variants.
  **The headline feature of this variant is therefore a conditional one**,
  and the plain fallback is what most people will see.
- Forcing every edge to 30/60/90 costs the raven some raven. The bill is a
  blade rather than a hooked culmen, the breast is a plane, and at 512 the
  bird is "engineered" first and a raven second; crow and jackdaw readings
  are open. It earns its species at 64 and below, where the bill and the
  wedge tail do the work. The flat crown over six columns is the one edge
  that drifts towards duck at hero size.
- The 30° edges are not exact in any text form; only 60°, 0° and 90° are.
  That is four of the twelve edges stepping, which the overlay shows.
- The full mark at 16 is a smudge, like every full-pose drawing; the head
  is the fix, as in round 1.
- The glyph at 16 is a tile with a light notch; the species is gone, which
  is the ceiling of every 16 px tile. Its bill at 24 is two pixels.
- Hard 30/60 geometry next to Material 3's soft corners dates faster than
  the free-angle facets; the surrounding system (type, icons) would need
  the same angles to look intended.

## Weakest of the three forms

The **terminal rendering**, which is unusual for this family: the half-block
file is the one place the variant promises something (true 60° diagonals)
that it can only deliver in some terminals. The mark and the glyph are
honest everywhere. If the owner's terminals are kitty / WezTerm the ranking
flips and the text becomes the strongest form.
