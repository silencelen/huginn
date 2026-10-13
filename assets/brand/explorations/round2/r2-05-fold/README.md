# r2-05-fold — the folded raven

**Look at:** `preview.png` (A: the mark at 512 on bone and on `#12100F`, at 64/32/16 and the
head at 22/16, all small sizes shown 4x with hard pixels; B: the glyph at 48/24/16 tinted idle,
working, attention and bone on the app ground, the 128s, the launcher mask, the tile; C: the three
terminal renderings in a `<pre>`; D: the vector mark over its own grid).

## The idea

The round-1 faceted raven, re-cut so that every vertex sits on a terminal half-block lattice,
plus **one interior cut**: a 2-unit hairline that opens at the shoulder, runs down the front of
the folded wing to a corner at mid-body and back along its lower edge to the wing tip resting on
the tail. The wing is a flap of paper hinged along the back; the gap is the light under its free
edge. Nothing else changes: still monochrome, still one evenodd path, still a round eye hole, and
the silhouette without the cut is exactly the round-1 pose snapped to the grid. The cut is dropped
at 32 px and below (`mark-32.svg`, the head drawing and the glyph are solid) and it reappears in
the braille rendering, where a one-dot gap is what a hairline is at 240 px.

## The grid and how the facets snap

**24 columns × 16 half-rows, cells 4.5 × 3.25 units of the 108 box:** column *i* is
x = 4.5·*i*, half-row *j* is y = 17 + 3.25·*j*, so the sixteen half-rows cover the canon box
height (y 17–69) exactly and the bird uses columns 3–21 (x 13.5–94.5). Every silhouette vertex is
a lattice point P(*i*, *j*):

| vertex | lattice | units | canon (round 1) |
|---|---|---|---|
| bill tip | P(21,5) | 94.5, 33.25 | 94.6, 34 |
| upper bill | P(19,3) | 85.5, 26.75 | 84, 25.5 |
| crown front | P(16,1) | 72, 20.25 | 70, 19.5 |
| crown back | P(12,0) | 54, 17 | 55, 18.5 |
| nape | P(10,3) | 45, 26.75 | 46, 26 |
| shoulder | P(9,4) | 40.5, 30 | 42, 30.5 |
| tail tip | P(3,13) | 13.5, 59.25 | 10.5, 60.5 |
| tail bottom | P(4,16) | 18, 69 | 14.5, 67.5 |
| under-tail | P(8,13) | 36, 59.25 | 36, 59 |
| belly | P(12,14) | 54, 62.5 | 54, 62 |
| breast | P(15,11) | 67.5, 52.75 | 68, 51 |
| chin | P(16,6) | 72, 36.5 | 71.5, 36.5 |

The back edge is 6 columns over 9 half-rows, so it passes through a lattice point every two
columns and its stair-step is regular; the belly-to-breast edge is 3 over 3, the under-tail 4 over
3, the lower bill 5 over 1. The eye is centred on lattice cell (14,2) — (65.25, 25.125), r 3.0 —
so it rasterises as exactly one half-cell. The fold centreline is P(9,4) → P(10,9) → P(5,13),
half-width 1; its mouth is spliced into the outline at the shoulder, so the mark is one 17-vertex
ring plus the eye hole (`mark.svg`), and the solid 12-vertex ring plus the eye hole is
`mark-32.svg`.

`grid.txt` holds two rasters of the solid silhouette, a cell being ink when at least half of it
is inside the path: `fold24` (24 × 16, the half-block and 7-bit source) and `fold48` (48 × 32, the
same path on the 2× lattice, the braille source, with the fold carved as a one-fine-cell gap).
Both give 24 text columns × 8 text rows, so the three renderings have the same footprint. They are
read straight off the grids with `../../marks-b/mark-04-pixel/grid2text.py`; the half-block and
7-bit files are framed because block art pads with spaces (the documented exception in
`../../ascii/README.md`), the braille pads with U+2800 and needs no frame. All three pass
`../../tools/asciicheck.py` and the 7-bit file is pure ASCII. (The brief's `../tools/` path does
not exist under `round2/`; the tools are one level up.)

## Files

| file | what |
|---|---|
| `mark.svg` | the mark: ink `#161310`, one evenodd path (ring + eye hole), 108 viewBox, `translate(2,11)`, box x 13.5–94.5, y 17–69 |
| `mark-inverse.svg` | the same path in bone `#E8E2DA` |
| `mark-tile.svg` | app-icon master: ink mark on the bone tile (`translate(0,11)` so it is centred; 12.5 % inset left/right) |
| `mark-32.svg` | the silhouette without the fold: 32 px and below, and the source of `grid.txt` (an extra file; the fold needs a second master) |
| `mark-16.svg` | the head drawing for 16–22 px: head, bill and neck in 9 edges with the mark's crown and bill facets, eye hole r 6.5, box x 14–105 / y 24–90 of a plain 108 viewBox |
| `glyph.svg`, `glyph-inverse.svg` | the glyph tile: rounded square (64 box, rx 14) minus this head and breast in 10 straight edges, plus a 4 × 4 eye cell at x 28–32 / y 24–28 (whole pixels at 16/32/64); one evenodd path |
| `grid.txt` | `fold24` and `fold48`, `#`/`.` rows, with the generation commands in the header |
| `ascii-halfblock.txt` | 24 × 8 `▀▄█`, framed (26 × 10). Unicode; line-height-1 terminals |
| `ascii-braille.txt` | 24 × 8 braille, carries the fold. Unicode; README, docs, tmux |
| `ascii-7bit.txt` | 24 × 8 `#"_`, framed with `+-\|`. Pure ASCII; the CLI client scripts |
| `preview.png` | the contact sheet |
| `build.py` | regenerates everything above from the lattice and the vertex lists (`--preview` for the sheet) |

## What the sheet shows

- **A.** At 512 the fold reads as a wing flap on both grounds; at 64 it is a 1.2 px grey chevron,
  still legible; the 32 and 16 renders are the solid master and read as a bird at 32 and a smudge
  with a bill at 16, which the head drawing covers (22 and 16, both grounds).
- **B.** The glyph is a tile with a bird in it at 16 in all four tints, the eye cell survives at 24
  and up, and the inverse (dark raven through a bone tile) is the best single image again. The
  launcher mask keeps the whole mark inside the safe ring at the canon's 0.72 scale.
- **C.** The three renderings share one footprint; the half-block is the mark's own cells, the
  braille has the fold and the eye hole, the 7-bit is the half-block with `#"_`.
- **D.** The vector over its grid: every vertex on a lattice line, the diagonals stair-stepping
  across it, the eye one cell.

## Strengths

- One idea, legible at a glance: a paper-cut raven with a folded wing. It is the only variant with
  an interior, so it is the most distinctive of the five at hero size without adding a colour.
- The cut is additive: strip it and you have the control variant's bird. Two masters, one family.
- Terminal exactness is structural: the half-block is the silhouette's own cells, the braille is
  the same path at 2× and carries the fold, the 7-bit is CLI-safe, and all three are 24 × 8.
- Regular stair-steps (the back is 2:3, the breast 1:1) because the slopes were chosen on the
  lattice, not snapped after the fact.
- The eye is a single half-cell in the terminal and a crisp hole at 64.

## Weaknesses (honest)

- **Cell aspect.** The lattice cell is 4.5 × 3.25 units but a terminal half-cell is roughly 10 × 9
  px, so the terminal bird is about 1.3× taller than the vector (1.15× in the `<pre>` at
  line-height 1). The 24 × 16 grid is what all five variants share; a 24 × 12 square-cell grid
  would fix the proportion at the cost of resolution. Stated, not solved.
- **The fold needs a second master.** Below 64 it is a grey line and below 32 it is gone, so the
  tray, favicon-32 and the half-block carry a mark without the variant's idea. Only the hero, the
  README and the braille show what makes this variant this variant.
- At 24 columns the bill tip vertex sits on a cell corner, so the half-block bill is one column
  short of the vector's, and the tail is three rows of stair.
- The brow above the eye is 3.1 units (the canon's is 4.2) because the eye must centre on a cell;
  at 64 it is a 1.8 px line.
- The braille fold is one dot wide and depends on the terminal's braille font; a heavy fallback
  can close it, a light one can make it read as noise (see `../../ascii/README.md` on braille
  coverage).
- The bent cut is one more thing to read than the control; at 512 some will see a chevron badge
  before a wing, and it leans further into the paper-craft trope than the plain facets do.

**Weakest form:** the glyph tile. It cannot carry the fold, so it is the generic negative-space
head of mark-05 with this variant's facets, and at 16 it is "a tile with a bird" exactly as
mark-05 was, no more. The half-block is a close second for the aspect reason above.
