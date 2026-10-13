# fold-system — the fold raven, upright, with a glyph that carries the fold

**Look at:** `preview.png` (A: the upright mark at 512 on bone and on `#12100F`, at 64 with the
fold and at 32 / 16 solid, the head drawing at 22 / 16, the tile and launcher; B: perched beside
upright at 512 and 64; C: the three glyph tiles, each beside the mark at the same height, ink on
bone at 128 / 48 / 24 / 16 and tinted idle / working / attention at 128 / 48 / 24 / 16; D: the
terminal art in a `<pre>`; E: each vector over its own grid).

Everything in this folder regenerates from `build.py` (`python3 build.py`, `--preview` for the
sheet). Edit the vertex lists there, not the files.

## What changed from round 2

1. **Posture (owner's amendment).** The fold raven now stands upright: chest forward, head
   raised, bill slightly up, the way `r2-03-watching` stands, but drawn with the fold raven's
   own head (flat crown, forehead step, the decurved culmen, the round eye hole) and body
   language (twelve straight edges, one hairline fold). It is the perched bird's head rotated up
   about fifteen degrees and a body turned under it. Box 63 × 51 units, **1.24 : 1** (perched:
   81 × 47.6, 1.7 : 1), so it fills a tile and the launcher circle instead of lying along their
   bottom. The perched drawing stays in the folder as `mark-perched.svg` for comparison.
2. **Grid alignment, not a redraw.** Round 2's fold bird lived on its own lattice (cells
   4.5 × 3.25, rows from y 17) and so could not reproduce Snapped's 24 × 8 text art. Both birds
   here sit on **Snapped's lattice**: 24 columns × 16 half-rows, cell **4.5 × 3.4** units, column
   *i* at x = 4.5 *i*, half-row *j* at y = 14.4 + 3.4 *j* (path coordinates, before the
   `translate(2,11)`). The perched solid master (`mark-perched-32.svg`) is now **Snapped's twelve
   vertices exactly**, so it rasterises to Snapped's `grid.txt` and the owner's chosen CLI art is
   this system's own raster, not an import. Which vertices moved to get there (r2-05 → here):

   | vertex | r2-05 | re-snapped | vertex | r2-05 | re-snapped |
   |---|---|---|---|---|---|
   | bill tip | 94.5, 33.25 | 94.5, **34.8** | tail tip | 13.5, 59.25 | 13.5, **58.6** |
   | culmen | 85.5, 26.75 | 85.5, **24.6** | tail bottom | 18, 69 | **13.5, 65.4** |
   | forehead | 72, 20.25 | 72, **17.8** | under-tail notch | 36, 59.25 | 36, **58.6** |
   | crown | 54, 17 | **58.5, 17.8** | belly | 54, 62.5 | 54, **62** |
   | back of head | 45, 26.75 | 45, **24.6** | breast | 67.5, 52.75 | 67.5, **48.4** |
   | shoulder | 40.5, 30 | 40.5, **31.4** | throat | 72, 36.5 | 72, **34.8** |

   Every vertex moved in y (the row pitch changed from 3.25 to 3.4 and the origin from 17 to
   14.4); the crown corner and the tail bottom also moved one column (54 → 58.5, 18 → 13.5),
   which is Snapped's flat crown and vertical tail edge. The eye moved from (65.25, 25.125) r 3.0
   to Snapped's (65.25, 26.3) r 3.2. The fold centreline is re-snapped to the same lattice:
   shoulder (9,5) → flap corner (10,10) → wing tip (5,13), i.e. (40.5, 31.4) → (45, 48.4) →
   (22.5, 58.6), still a 2-unit slit spliced into the ring at the shoulder.
3. **The glyph carries the fold.** Round 2's tile was a head cut from a rounded square, with no
   fold. Three constructions below put the fold in; all are cut from the upright bird.
4. **A 48-column rendering** with the fold as a one-cell gap, for the README and the host splash,
   plus the upright bird's own 24 × 8 set beside Snapped's.

## The upright bird

Twelve lattice vertices, clockwise from the bill tip, (column, row) → units:

| vertex | lattice | units | vertex | lattice | units |
|---|---|---|---|---|---|
| bill tip | (19, 4) | 85.5, 28 | tail tip | (5, 16) | 22.5, 68.8 |
| culmen | (16, 2) | 72, 21.2 | tail bottom | (8, 16) | 36, 68.8 |
| forehead | (12, 1) | 54, 17.8 | under-tail notch | (12, 13) | 54, 58.6 |
| crown | (10, 1) | 45, 17.8 | belly | (16, 14) | 72, 62 |
| back of head | (8, 2) | 36, 21.2 | chest | (17, 10) | 76.5, 48.4 |
| nape / shoulder | (7, 4) | 31.5, 28 | throat | (15, 6) | 67.5, 34.8 |

- **Slopes.** Every edge is horizontal, vertical or p : q with p or q even (bill 3:2 and 4:1,
  crown flat, back of head 2:1, neck 1:2, back 1:6, tail base flat, under-tail 4:3, belly 4:1,
  chest 1:4 and 1:2, gonys 2:1), so no edge passes through a cell centre and the 24 × 16 raster
  has no cell between 42 % and 58 % coverage (the build asserts it). The back is the steep
  edge: two columns over twelve rows.
- **Eye.** A circle r 3.2 at the centre of cell (12, 3) = (56.25, 26.3): 29 units behind the bill
  tip, as in the perched bird, 5.3 units of brow under the forehead. One ground cell in the
  raster, a 3 × 4 hole in the 2× grid.
- **Fold.** Centreline shoulder (7, 4) → flap corner (10, 11) → wing tip (7, 15), i.e.
  (31.5, 28) → (45, 51.8) → (31.5, 65.4), half-width 1 (a 2-unit slit), spliced into the ring at
  the shoulder so the mark is one 17-vertex ring plus the eye hole. The front edge of the flap
  now runs at 60° from horizontal (the perched one at 75° on the other side of vertical, i.e.
  the flap hangs rather than lies), the wing tip rests inside the tail wedge 3.4 units above the
  ground line. The fold is dropped at 32 px and below: `mark-32.svg` is the twelve-vertex solid.
- **Bill slightly up.** The gonys rises 6.8 units over 18 (21°) from throat to tip, the culmen
  drops 27°; the bill's axis is about 10° above horizontal. The chest sits 9 units behind the
  bill tip, the belly 10 units below the chest.
- **Box** x 22.5–85.5, y 17.8–68.8 of the path frame, inside the canon box x 10–95 / y 17–69.
  Launcher (pivot 52,43, scale 0.72): farthest vertex 28.2 dp from centre, inside the
  33 dp safe radius. `mark-tile.svg` scales the bird 1.3 about its box centre (x 13–95, 12 %
  side inset).
- **Head drawing** (`mark-16.svg`, 16–22 px): the upright head's own nodes (tip, culmen,
  forehead, crown, back of head, nape) scaled 1.6 about the bill tip, closed by a straight back
  and the chest facet, eye hole r 6.5 at (58, 37.5); box x 14–105 / y 24–90. No fold.

## The three glyph options

All three: a 64-unit rounded square (rx 14), the bird as the hole, the eye a 4 × 4 cell of ink on
whole pixels (so it is exactly one pixel at 16, 2 × 2 at 32), **one evenodd path**. The container
ring and the hole are spliced into a single loop wherever the hole runs off the tile, so there are
no coincident edges and no slivers in the corners.

| | construction | the fold | 48 | 24 | 16 |
|---|---|---|---|---|---|
| **a** `glyph-a.svg` **full fold** | the bird at 0.95 (head, chest, belly), eye cell x 28–32 / y 24–28; the tail leaves the tile through the bottom-left corner, so the breast cut-out continues down into the body and the folded wing | the mark's own 2-unit slit, scaled (1.9 units): an ink hairline inside the hole from the shoulder down the flap's front edge and back to the wing tip | the flap reads as a wing | a faint grey hairline; the bird reads, the wing is a hint | **fold gone** (0.5 px, antialiased away); a tile with a big bird-head hole |
| **b** `glyph-b.svg` **shoulder wedge** | head, throat and chest at 0.78, eye cell x 32–36 / y 24–28, the breast open onto the bottom edge; the hole's back runs down the **fold line**, so the tile ink behind it is the folded wing; at the shoulder a 5-unit-wide, 18-unit-long sliver of hole peels off along the **back edge** and tapers to nothing: the wing's upper edge, the fold's mouth, nothing else | the sliver only | a small feather lifting off the shoulder; the fold is implied by the kinked back | a one-pixel tick | **fold gone**; the cleanest 16 of the three (head, bill, eye, chest) |
| **c** `glyph-c.svg` **negative mark** | the whole upright mark with its fold at 0.78, centred (margins left 8, right 7, top 11, bottom 13), eye cell x 32–36 / y 16–20 | the slit widened to 2.8 units in the mark = 2.2 in the tile, so it survives 24 | the mark in negative, fold and all | a light seam along the wing: still a fold | **fold gone** (0.55 px); a tile with a standing bird, bill and tail legible |

**How they differ.** (a) is the biggest bird and the most literal fold, bought with no margin: the
tail is cropped and the bird nearly touches the right wall, so at 16 it is a hole with a bill. (b)
is round 2's tile with one cue added, and the only one whose fold cue is a *hole* feature, not a
hairline of ink: it is the most legible tile at 16 and the least convincing fold at 48 (a lifted
feather rather than a folded wing; with the mark beside it the kinked back reads, alone it is
a notch). (c) is literally the mark and reads as the mark at 128 and 48, keeps a visible fold at
24, and at 16 is the smallest bird of the three but still a bird in a tile.

**I would ship (c).** It is the only one where the tray, the favicon-32 and the hero are the same
drawing in negative, which is the whole point of adding the fold to the tile; its 24 is the best
24 (the fold is still there), and its 16 passes the "tile with a bird" test on all three tints.
If the tray at 16 turns out to matter more than the fold, (b) is the fallback: same family, one
cue, strongest 16.

**The 16 px rule.** One tile unit is a quarter pixel at 16 px. Any hairline under 4 units is
antialiased to grey, so **no glyph carries the fold at 16**; nothing is swapped, the same file is
used and the fold degrades by itself: (a)'s 1.9-unit and (c)'s 2.2-unit ink hairlines go to a
faint streak at 24 (a) or a visible seam at 24 (c) and vanish at 16; (b)'s 5-unit sliver is one
pixel at 24 and nothing at 16. What must survive 16 is the eye (one pixel exactly, hence the 4 × 4
cell on multiples of 4) and the bill wedge, and in all three it does. Below 24 the tile is
"a tile with a bird"; from 24 up it is "a tile with the fold raven".

## Terminal art and the alignment

**Proof.** `mark-perched-32.svg` (the perched solid master) rasterised onto the 24 × 16 lattice at
≥ 50 % coverage (16 × 16 supersampling, `build.py`) is identical to `../../round2/r2-01-snapped/grid.txt`
`@r2-01`, and `grid2text.py` regenerates `ascii-halfblock.txt`, `ascii-7bit.txt` and
`ascii-braille.txt` from it byte for byte; the build asserts all four. An independent check with
Chromium (the SVG stretched so one lattice cell is 20 × 20 px, each cell's mean coverage
thresholded at 50 %) gives the same grid for both masters; the nearest cell to the threshold is
at 55 % (perched) and 54 % (upright). So the four files in this folder are Snapped's, copied, and
also this system's own raster; the perched bird did have to be re-snapped (table above) to get
there, as the brief allowed.

| file | what | surface |
|---|---|---|
| `grid.txt`, `ascii-halfblock.txt`, `ascii-7bit.txt`, `ascii-braille.txt` | **Snapped's 24 × 8, byte-identical to r2-01** (= the perched solid master's raster) | **the CLI art**: `ascii-7bit.txt` (pure ASCII) in `client/huginn.sh` / `huginn.ps1`; `ascii-halfblock.txt` in `huginn-status`, the tmux status and the apps' terminal view (line-height 1); `ascii-braille.txt` (12 × 4, small) in docs where blocks stripe |
| `grid-upright.txt`, `ascii-halfblock-upright.txt`, `ascii-7bit-upright.txt`, `ascii-braille-upright.txt` | the **upright bird's own 24 × 8** (half-block and 7-bit from `@upright24`, braille from the 2× `@upright48` and so carrying the fold) | candidates to replace the three above if the owner prefers the upright text art; labelled on the sheet beside Snapped's |
| `ascii-halfblock-48.txt`, `ascii-7bit-48.txt` | the upright bird at **48 × 16** (half-block `▀▄█`, and `#"_` pure ASCII), the fold carved as a one-cell gap (every 2.25 × 1.7 fine cell whose centre is within 1 unit of the slit), framed 50 × 18 | README hero and the host splash (`huginn-status`, MOTD); the 7-bit one if a 48-column banner is wanted in the clients |
| `ascii-halfblock-48-perched.txt`, `ascii-7bit-48-perched.txt` | the perched bird at 48 × 16 with its fold, same method | comparison only |

All text files pass `../../tools/asciicheck.py`; the `*7bit*` files are pure 7-bit ASCII; the
half-block and 7-bit files are framed because block art pads with spaces (the documented
exception), the braille pads with U+2800 and needs no frame. The 24 × 8 upright art shares
Snapped's footprint (24 columns, 8 lines) so the two can be swapped without re-layout.

**At 48 × 16 the fold is a gap, not a gash:** one fine cell wide, it shows as a staircase of
`▀`/`▄`/space along the flap's front edge and a second along its lower edge, which is what makes
it read as a folded wing rather than a crack (the single-leg version did read as a crack at 24
columns in round 2, which is why the 24 × 8 files stay solid).

## Files

| file | what |
|---|---|
| `mark.svg`, `mark-inverse.svg` | the upright fold raven, ink / bone, ONE evenodd path (17-vertex ring + eye hole), 108 viewBox, `translate(2,11)`, box x 22.5–85.5 / y 17.8–68.8 |
| `mark-tile.svg` | app-icon master: the mark scaled 1.3 on the bone tile |
| `mark-32.svg` | the upright silhouette without the fold (32 px and below); the source of `grid-upright.txt` |
| `mark-16.svg` | the head drawing for 16–22 px |
| `mark-perched.svg`, `mark-perched-32.svg` | the perched r2-05 bird re-snapped to Snapped's vertices, with and without the fold; the solid one is Snapped's polygon and the proof's subject |
| `glyph-a.svg`, `glyph-b.svg`, `glyph-c.svg` (+ `-inverse`) | the three glyph tiles |
| `grid.txt`, `ascii-*.txt` | the text art, see the table above |
| `preview.png` | the sheet |
| `build.py` | regenerates everything from the vertex lists; asserts the parity rule, the coverage margin and the byte-identity with r2-01 |

## Weaknesses (honest)

- The upright bird's head is the perched head on a body half its old length, so it is
  head-heavy; at 32 px it reads as a bird with a strong bill, at 16 it is a smudge (the head
  drawing covers 16–22). The back edge at 1:6 stair-steps every three text rows in the 24 × 8 art.
- (a) has no margin on the right (6 units) and crops the tail; on a busy taskbar it can read as a
  bird's head bursting out of a chip. (b)'s fold cue is honest but small; it needs the mark next to
  it to be read as a wing. (c)'s bird at 16 is 12 × 10 px and its tail is two pixels.
- The upright 24 × 8 text bird is coarser than Snapped's (a taller bird in the same 8 lines means
  fewer cells per feature: the bill is a two-cell wedge, the tail a `▀`); the 48 × 16 is where it
  earns its posture. The braille's fold is one dot wide and depends on the terminal's braille font.
- Cell aspect: the lattice cell is 4.5 × 3.4 but a terminal half-cell is about 10 × 9 px, so the
  text birds are about 19 % taller than the vectors. Shared with round 2, not solved here.
