# r2-03-watching — the alert raven

**Look at:** `preview.png`

## The idea

A raven that has seen something stands up: neck stretched, head raised, bill
lifted a few degrees, chest out, tail splayed down and back. This variant is
the round-1 faceted raven in that posture. It is cut from **15 straight edges**
and a circular eye hole, ONE evenodd path, and it is taller than the canon
mark: box 61.75 × 52 units (**1.19:1**, against the canon's 1.7:1), so it fills
a square tile and a launcher circle instead of lying along the bottom of
them. The facets are the same language as mark-02 (flat crown, a forehead
step, a flat culmen that droops only at the tip, a straight gonys, one
concave nape, a wedge tail) but the body has a vertical chest facet and the
back is steeper than the tail's upper edge, which is what makes it stand
rather than perch. Every vertex sits on a half-block grid, so the terminal
art is the mark sampled, not a tracing of it.

## The grid and how the facets snap to it

- **Grid:** 20 columns × 16 half-rows (8 terminal lines), cell **3.25 units**
  of the 108 box, origin (20, 17) in the `translate(2,11)` frame. 16 rows
  × 3.25 = 52 = the canon box height exactly; 20 columns = 65 units, of which
  the mark uses columns 1–20 (x 23.25–85).
- **Vertices (cells):** tip (20,2), chin (15,4), throat (15,6), chest (17,8),
  chest (17,10), belly (14,13), vent (11,13), tail (1,16), rump (7,9),
  nape (9,5), occiput (8,2), crown (10,0), crown (13,0), forehead (14,1),
  culmen (16,1). Unit coordinates are `20 + 3.25·i`, `17 + 3.25·j`.
- **Eye:** centre on the cell corner (12,2), radius 1 cell (3.25 units), so
  it renders as a 2 × 2 hole in the half-block grid, a 4-dot hole in braille
  and a 2 px dot at 32 px.
- **Rule:** a cell is ink when the vector covers at least half of it
  (16 × 16 supersampling; ties would go to ink, but no cell in either grid
  is an exact tie). Horizontal and vertical edges fall on cell boundaries;
  the sloped edges (1:2, 2:1, 1:3, 2:7 …) stair-step symmetrically about the
  line. `grid.txt` holds `@half` (20 × 16) and `@fine` (40 × 32, the same
  vector at 2×); the half-block and 7-bit files come from `@half`, the
  braille from `@fine`, all via `../../marks-b/mark-04-pixel/grid2text.py`
  (commands in the header of `grid.txt`). All three pass
  `tools/asciicheck.py`; the 7-bit one is pure ASCII.
- **Head drawing (`mark-16.svg`):** the mark's head cells re-cut on a 7-unit
  cell, 11 edges, box x 14–105 / y 24–87, eye r 7 at (49,38).
- **Glyph (`glyph.svg`):** rounded square rx 14 in a 64 box; the same head
  cells as a hole on a 3.5-unit cell from (9,16), breast open onto the
  bottom edge (x 9–45.75); walls 16 above the crown, 9.5 right of the tip,
  9 behind the head; eye a 4 × 4 cell at x 24–28 / y 20–24 (whole pixels at
  16, 32 and 64).
- **Tile (`mark-tile.svg`):** the mark scaled 1.3 about the box centre so it
  spans x 12.3–96.8 of the bone tile (~10 % side inset). **Launcher:** the
  canon composition (pivot (52.5,43), scale 0.72) keeps every vertex within
  28.2 dp of centre (safe radius 33).

## What the sheet shows

Row 1: `mark.svg` at 512 on bone and `mark-inverse.svg` at 512 on `#12100F`;
the mark at 64 / 32 / 16 on both grounds (small sizes upscaled with hard
pixels). Row 2: the head drawing at 22 / 16 in ink on bone and in bone on the
app ground as the tray composes it; the tile at 512 / 48 / 32; the launcher
circle mask with the dashed 66 dp safe ring at 256 and 48. Row 3: the glyph
at 512 / 64, its inverse at 64 / 16, and the glyph tinted idle `#7B8794`,
working `#C8A45C`, attention `#F3D58F` on the app ground at 48 / 24 / 16.
Row 4: the three text files in a `<pre>` (DejaVu Sans Mono 20 px,
line-height 1) on dark; the hairline seams in the half-block one are
Chromium drawing DejaVu's block glyphs, the same artefact the ascii set
documents, not gaps in the art. Row 5: the vector (gold) over its own
half-block and braille grids (grey cells).

## Strengths

- The posture is the distinctive thing: "watching" is the product's job
  (a session that reports back), and the raised head reads at 64 and 32
  where a perched silhouette is just a bird-shaped blob.
- Near-square: the tile, the launcher circle and the glyph all carry it
  without the empty top third the 1.7:1 canon leaves; the launcher margin
  (28.2 of 33 dp) has room to spare.
- The grid is honest: 8 lines × 20 columns is the half-block banner, the
  same 8 × 20 characters carry the braille at double resolution, the 7-bit
  file is the same cells in `#"_`, and the overlay shows the vector and the
  cells agree with no hand tuning. One edit to the vertex list regenerates
  everything.
- The eye is on a corner so it survives as a 2 × 2 hole in text and a dot at
  32 px; the glyph's eye cell is on whole pixels at 16 / 32 / 64.
- Same viewBox, transform, winding, ink/bone pair and eye-as-hole rule as the
  canon; the path drops into the hand-carried copies unchanged.

## Weaknesses (honest)

- At 512 the head is a hooded, flat-crowned shape with a heavy hooked bill:
  it reads crow or raven, but a first glance can also say jackdaw or (because
  of the hook) a small parrot. The flat crown + forehead step is the price of
  keeping the crown on a grid row.
- The 15 edges are more than the brief's "fewer, bolder" heavy variant; the
  chest facet and the two crown points are there for the posture, not for
  economy. At 32 px the chest step and the nape notch merge anyway.
- The 20 × 16 grid is coarse: the bill is 1–2 cells thick and the tail tip
  is a single cell, so the half-block and 7-bit banners read "bird with a
  big bill" rather than "raven"; the braille (2×) is the one text form that
  shows the hook.
- The full mark at 16 px is a smudge, as every full-pose mark is; the head
  drawing is the tray and favicon answer, and at 16 its eye is a one-pixel
  grey dot.
- The eye sits one cell under the crown (3.25 units of ink above it at
  hero size): legible, but thinner than mark-02's ring.
- Half-block art still needs a terminal that draws `▀▄█` edge to edge at
  line-height 1.0; GitHub code blocks stripe it (use the braille there).

## Weakest of the three forms

The **terminal rendering**. The glyph and the mark each read as a bird in a
tile / a raven standing up at every size they are meant for; the 20 × 16
half-block raven reads as a bird, but it is the form that loses the species
first, and the 7-bit twin loses the eye to the `"` row. It is exact, which is
the point of this round, but exact at 8 lines is a coarse bird.
