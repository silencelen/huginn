# Round 2 — five refinements of the chosen direction

The owner chose, on the round-1 chooser:

- **Primary mark:** `mark-02-faceted` (marks-a) — the perched raven cut from
  straight edges with a round eye hole.
- **Small form:** a **glyph tile** re-cut from the primary's head (the
  negative-space idea of `mark-05-glyph`, marks-b), tinted by state in the
  tray, favicon and notification.
- **Terminal art:** **the logo is the terminal art.** Not "sample the SVG
  into characters" but "the mark is drawn so that its half-block, braille
  and 7-bit renderings are exact". With a faceted mark that means the
  vertices live on a grid that the terminal cell grid can reproduce.
- **Owner's note:** "with our choices as the primary driver of further
  refinement, let's further refine by crafting 5 choices."

So round 2 is five *systems*, not five ravens: each variant is one faceted
raven plus the glyph tile cut from the same head plus the terminal
renderings that fall out of it, so the owner can pick the one whose three
forms hold together best.

Read first: `../BRIEF.md` (the original brief, palette, surfaces, hard
rules incl. the 7-bit CLI constraint), `../marks-a/mark-02-faceted/README.md`
and its SVGs, `../marks-b/mark-05-glyph/README.md` and its SVGs,
`../marks-b/mark-04-pixel/README.md` (the integer-scale rule and the
grid-to-text scripts), `../ascii/README.md` (the svg2ascii pipeline and
`regen.sh`).

## What every variant ships (folder `round2/<id>/`)

| file | what |
|---|---|
| `mark.svg` | the faceted raven, ink `#161310` on transparent, viewBox `0 0 108 108`, `translate(2,11)` group, bounding box inside x 10–95, y 17–69 like the canon so hand-carried copies stay valid; ONE evenodd path (polygon + circular eye hole) |
| `mark-inverse.svg`, `mark-tile.svg` | bone version; app-icon master on a square bone tile with ~10 % inset |
| `mark-16.svg` | the head drawing for 16–22 px (near-square box), same facet language |
| `glyph.svg`, `glyph-inverse.svg` | the glyph tile: a rounded square (64-unit viewBox like mark-05) with THIS variant's head and breast cut out, eye a square cell; one evenodd path; must read at 16 |
| `grid.txt` | the mark's vertices snapped to the terminal grid you chose (state the grid: e.g. 24 columns × 16 half-rows), as `#`/`.` rows, the source the text renderings come from |
| `ascii-halfblock.txt`, `ascii-braille.txt`, `ascii-7bit.txt` | the terminal renderings, EXACT from `grid.txt` (use `../marks-b/mark-04-pixel/grid2text.py` or your own), each passing `python3 ../tools/asciicheck.py`; the 7-bit one is for the CLI client scripts |
| `preview.png` | ONE contact sheet: mark at 512 on bone and on `#12100F`; mark at 64/32/16; head at 22/16; glyph at 48/24/16 on the app ground tinted idle `#7B8794`, working `#C8A45C`, attention `#F3D58F`; launcher circle mask; the three text renderings rendered in a `<pre>` (DejaVu Sans Mono, line-height 1) on dark; and the vector mark overlaid on its own grid rendering so the owner can see how exact the match is |
| `README.md` | name; the idea in one paragraph; the grid and how the facets snap to it; what the sheet shows; strengths; weaknesses (honest); which of the three forms is the weakest in this variant |

## The five variants (one per designer; stay distinct)

1. `r2-01-snapped` — the round-1 faceted raven kept as close as possible to
   its current silhouette, with every vertex snapped to a 24 × 16 half-block
   grid (cells 4.5 × 3.4 units of the 108 box) so the half-block rendering is
   exact; the glyph tile cut from its head. The control.
2. `r2-02-heavy` — fewer, bolder facets (8–10 edges): a heavier hooked bill,
   deeper breast, a stronger wedge tail; designed to read at 32 px without a
   head drawing if it can. Grid-snapped the same way.
3. `r2-03-watching` — the "alert" raven: a more upright, taller perched
   posture with the head raised and the bill slightly up, as a raven stands
   when it has seen something; faceted; proportion closer to 1.2:1 so it fits
   a square tile better. Grid-snapped.
4. `r2-04-isometric` — facets on a strict 30/60/90 angle system (an isometric
   grid), so bill, crown, back and tail all share the same angles and the
   mark reads as engineered; the terminal rendering uses the quarter-block
   diagonals (`◢◣◤◥`) where the 30/60 edges allow. The riskiest; say so.
5. `r2-05-fold` — the faceted silhouette plus ONE interior cut: a hairline
   gap (negative space, 1.5–2 units) that separates the folded wing from the
   body, so the mark reads as folded paper. Monochrome still; the gap drops
   at ≤ 32 px (the head drawing and glyph have none). Grid-snapped.

## Rules

- Monochrome first; no emoji; single-width characters only; the 7-bit
  rendering is pure ASCII.
- Render and LOOK at every artefact (`node ../tools/render.mjs`, `../tools/sheet.sh`,
  your image tool). A mark that does not read as a raven at 32 is revised;
  a glyph that does not read as "a tile with a bird" at 16 is revised.
- Write only inside your `round2/<id>/` folder and the scratchpad. Never run git.
- Return JSON: id, files, summary, grid, strengths, weaknesses, weakestForm,
  scores 1–5 (legibility16, distinctiveness, ravenness, modernity,
  cohesionOfThreeForms, terminalExactness).
