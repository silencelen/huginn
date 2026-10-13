# Round 3 — the fold system, finished

The owner chose on the round-2 chooser, with one combination:

- **Mark:** `r2-05-fold` everywhere (hero, README, launcher, installer,
  window icon, favicon-32 and up).
- **Terminal art:** `r2-01-snapped`'s text renderings for the CLI and the
  host (the owner likes that art specifically). Fold and Snapped share a
  silhouette, so this is a grid alignment, not a redraw: the fold mark's
  grid must produce Snapped's 24 × 8 half-block and 7-bit files exactly, or
  the fold mark's solid master (`mark-32.svg`) must be re-snapped to
  Snapped's vertex set so it does.
- **Glyph tile:** add a **wing fold** to the glyph so the tile more closely
  resembles the mark. Today the glyph is a head and breast cut out of a
  rounded square; the mark's identity is the folded-wing hairline. The tile
  needs that hairline (or an equivalent cue) in a way that reads at 48 and
  24 and degrades cleanly at 16.

Read first: `../round2/BRIEF.md`, `../round2/r2-05-fold/README.md` and its
files, `../round2/r2-01-snapped/README.md` and its files, `../BRIEF.md`.

## Deliverables (folder `round3/fold-system/`)

| file | what |
|---|---|
| `mark.svg`, `mark-inverse.svg`, `mark-tile.svg`, `mark-32.svg`, `mark-16.svg` | the fold mark and its masters, carried over from r2-05 unless a snap to Snapped's vertex set is needed (say so) |
| `glyph-a.svg`, `glyph-b.svg`, `glyph-c.svg` (+ `-inverse`) | THREE ways to put the fold in the tile, each one evenodd path, each honest at 48 / 24 / 16: (a) the breast cut-out continues down into the folded wing and the fold hairline runs through the ink exactly as in the mark, scaled; (b) the hole shows head, breast and the wing's upper edge, with the fold as a short hairline wedge opening at the shoulder only; (c) the whole perched mark (with fold) sits as a hole in the tile, smaller, so the tile is literally the mark in negative. Pick the construction for each; iterate until each reads. State which drops the fold at 16 and how. |
| `grid.txt`, `ascii-halfblock.txt`, `ascii-7bit.txt`, `ascii-braille.txt` | Snapped's 24 × 8 files, byte-identical to `../round2/r2-01-snapped/` (copy them and prove the fold mark's solid master rasterises to the same grid; if it does not, re-snap the master and say which vertices moved) |
| `ascii-halfblock-48.txt`, `ascii-7bit-48.txt` | a 48-column, 16-line rendering of the fold mark where the fold appears as a one-cell gap, for the README and the host splash; must pass `../../tools/asciicheck.py` |
| `preview.png` | one sheet: the mark at 512 on bone and dark; the three glyph options each at 128 / 48 / 24 / 16 in ink on bone AND tinted idle `#7B8794`, working `#C8A45C`, attention `#F3D58F` on `#12100F`; each glyph beside the mark at the same height so resemblance is judged; the 24 × 8 and 48 × 16 text renderings in a `<pre>` (DejaVu Sans Mono, line-height 1) on dark |
| `README.md` | what changed from round 2; how the three glyph options differ and which you would ship and why; the 16 px rule; the terminal mapping (which file goes to which surface) |

## Rules

- Monochrome first; one evenodd path per glyph; no emoji; single-width
  characters; 7-bit files pure ASCII.
- Render and LOOK (`node ../../tools/render.mjs`, `../../tools/sheet.sh`, your
  image tool). A glyph that does not read as a tile with a bird at 16 is
  revised; a fold that reads as a crack or a scar rather than a wing is
  revised.
- Write only in `round3/fold-system/` and the scratchpad. Never run git.
- Return JSON: files, summary, glyphOptions (id, construction, readsAt,
  foldAt16, recommendation), terminalNote, scores 1–5 per glyph option
  (resemblanceToMark, legibility16, legibility24, craft).

## Amendment (owner, mid-round): "same bird, but in a forward or upright position"

The fold raven keeps its drawing language (the twelve-edge faceted body, the
round eye hole, the one hairline fold along the wing, the grid-snapped
vertices) but its **posture changes**: the bird stands more upright, chest
forward, head raised and the bill slightly up, the way `r2-03-watching`
stands, without adopting that variant's head shape. Think of it as the fold
raven lifting its head to look ahead. Aim for a proportion near 1.2:1 so it
fills a tile; keep the wedge tail dropping down and back; keep the fold
hairline on the folded wing, now running steeper.

Consequences:
- `mark.svg` and its masters are the UPRIGHT fold raven; keep the perched
  r2-05 drawing in the folder as `mark-perched.svg` for comparison.
- The glyph options are cut from the upright bird's head and shoulder.
- The 24 × 8 terminal art: the owner likes Snapped's; ship it unchanged as
  the CLI art, AND ship the upright bird's own 24 × 8 and 48 × 16 renderings
  beside it so the owner can see whether the upright bird's text art is
  worth switching to. Label which is which.
- The sheet gets a row with perched and upright side by side at 512 and 64.
