# Brand explorations — round 1

Variations for the Huginn identity overhaul, made to decide between before the
brand book is written. The raven stays the primary mark; everything else is on
the table. Every variation is self-contained (files, a README that states what it
is good and bad at, a `preview.png` rendered at the sizes that matter), and every
set was reviewed by a second pass that tried to refute it; the revisions are in
each README.

`BRIEF.md` is the brief all of this was designed against. `catalog.json` is the
machine-readable index: every variation, the gaps, the conflicts between
dimensions, top picks, three coherent bundles and the decision checklist.

| dimension | folder | what is in it | start with |
|---|---|---|---|
| 01 Marks, set A | [`marks-a/`](marks-a/) | the current raven refined; a twelve-facet cut; a Lucide-style stroke icon | [`compare.png`](marks-a/compare.png) |
| 01 Marks, set B | [`marks-b/`](marks-b/) | a pixel-grid raven that is also its own terminal art; a negative-space "terminal raven"; the raven in flight | [`system.png`](marks-b/system.png) |
| 02 ASCII | [`ascii/`](ascii/) | an SVG-to-terminal pipeline; portraits at 48 and 24 columns; compact ravens down to one line in Unicode and 7-bit; lockups; a state glyph table; runes rejected with evidence | [`ascii/README.md`](ascii/README.md) |
| 03 Wordmark and type | [`wordmark/`](wordmark/) | ten terminal wordmarks (figlet survey plus hand-drawn hairline, perch and one-line); three typographic directions with full specimens | [`type-system.md`](wordmark/type-system.md) |
| 04 Motion, terminal | [`anim-ascii/`](anim-ascii/) | spinners, flap, landing and takeoff, idle, boot, tmux status; players for Python, bash and PowerShell | [`anim-ascii/README.md`](anim-ascii/README.md) |
| 04 Motion, screen | [`anim-vector/`](anim-vector/) | blink, assemble from glyphs, fly and return, the tray attention pulse with its sprite spec, thinking indicators | [`anim-vector/README.md`](anim-vector/README.md) |
| 05 Colour | [`color/`](color/) | three palette directions with dark and light schemes, one state system mapped onto every surface, a terminal theme each, the migration list | [`color/README.md`](color/README.md) |
| tools | [`tools/`](tools/) | the Chromium renderer, contact-sheet tiler, terminal-art checker and contrast calculator the sets were verified with | |

## Decisions so far

| decision | chosen | where |
|---|---|---|
| Primary mark | `mark-02-faceted`, the perched raven cut from straight edges | round-1 chooser, 2026-10-13; `compare/in-context.png` shows it on every surface |
| Small form (tray, favicon, notification) | a glyph tile re-cut from the primary's head, tinted by state | same |
| Terminal art | the logo is the terminal art: the facets are drawn to a grid so the half-block, braille and 7-bit renderings are exact | same |
| Round 2 | five refinements of that direction, each a system of mark, glyph and terminal art | [`round2/BRIEF.md`](round2/BRIEF.md) |
| Round 2 pick | `r2-05-fold` for the mark; `r2-01-snapped`'s text art for the CLI; the glyph tile gains the wing fold | round-2 chooser, 2026-10-13 |
| Round 3 | the fold raven redrawn upright on Snapped's lattice, three glyph constructions with the fold, Snapped's text art proved to be this bird's own raster | [`round3/fold-system/`](round3/fold-system/); the round-3 chooser records the pick |
| **The mark, decided** | the upright fold raven, slimmed (`round3/fold-system/mark.svg`, 54 × 51); the glyph tile is `glyph-c.svg`, the whole mark in negative; the CLI art is Snapped's 24 × 8 (`ascii-7bit.txt` / `ascii-halfblock.txt`), the 48 × 16 fold art for the README and host splash | round-3 chooser, 2026-10-13: "approved, i like this version better" |
| **Colour, decided** | `color-04-moss-heather` (evergreen accent on dark grey, heather second voice; added at the owner's request and proved by the same rules); needs-you = the accent lit up (`stateAttention`, not error); tray needs-you = tinted tile + badge dot; needs-you glyph = `▲` / `!`, so error takes `×` / `x` | round-4 chooser, 2026-10-13 |
| **Type, decided** | `type-02-plex-mono`: IBM Plex Mono for the wordmark (lowercase `huginn`), display, labels, terminal and code, IBM Plex Sans for titles and body; the raven-as-cursor lockup. Terminal letterform: the hairline, `word-10-perch` with the raven re-cut from the decided mark (`round5/perch-upright*.txt`), `word-09` where no bird fits, `word-06-pagga` under 34 columns. One-row raven: `(o>` with its state variants, 7-bit everywhere | round-5 chooser, 2026-10-13 |

Still open: the hero form for README, splash and installer; the client's help
header; the motion set. Then the brand book. Everything builds on
`round3/fold-system/`, `color/color-04-moss-heather/`, `wordmark/type-02-plex-mono/`
and `round5/`.

## How to review

1. Pick a mark first. Everything terminal and motion was built on today's path and
   regenerates from the chosen one (`ascii/regen.sh`, `anim-vector/build.sh`,
   `marks-b/mark-04-pixel/grid.txt`); the colour and type decisions do not depend
   on it.
2. Then colour, because the state system and the terminal theme carry over
   whichever ground wins, and the type direction, because the terminal wordmark
   should match its weight.
3. Then the terminal pieces (ASCII style per surface, the one-row glyph, the
   state glyphs) and the motion set.

Two dimensions disagree in one place and the owner has to pick: the ASCII set's
state glyph for *needs you* is a triangle, the colour set's is a diamond. Both
are single-width; one has to win.

## Hard constraints every variation respects

- `client/huginn.sh` and `client/huginn.ps1` are 7-bit ASCII only; every
  CLI-facing artefact ships an ASCII twin and says which surface prints which.
- No emoji in brand assets; terminal art uses single-width characters only.
- Monochrome first: every mark works as one flat colour before colour is added.
- The apps' motion rule, a slow stepped pulse rather than a spinner, bounds
  every in-app animation.

## Round 5

Type, on the decided mark and ground: [`round5/README.md`](round5/README.md).
**Decided:** Plex Mono-led, the hairline perch with the re-cut raven, `(o>` as
the one-row raven.

## Round 4

Colour, on the decided mark: [`round4/README.md`](round4/README.md) lists the
four questions (palette ground, what needs-you means, the tray cue, the state
glyph) with the recommendation; the round-4 chooser "The Raven in Colour"
showed the raven on each direction's real surfaces. **Decided:** Moss & Heather,
the lit accent for needs-you, tint plus badge in the tray, the triangle glyph.

## Round 3

The chosen combination finished as one system: [`round3/fold-system/`](round3/fold-system/)
holds the upright fold raven and its masters, the perched round-2 bird for comparison,
three glyph tiles that carry the wing fold (`glyph-a` full fold, `glyph-b` shoulder wedge,
`glyph-c` the whole mark in negative; the README recommends c), Snapped's 24 by 8 text art
byte-identical to round 2 plus the upright bird's own 24 by 8 and 48 by 16, and a
`preview.png`. **Decided:** the slimmer upright mark (revised once after the owner found the first upright draft too wide), `glyph-c`, Snapped's CLI art. The mark is settled; the next rounds build on these files.

## Round 2

Five systems built on the chosen direction, each a faceted raven, the glyph
tile cut from the same head, and terminal art exact from the mark's grid:
[`round2/`](round2/) holds `r2-01-snapped` (the control), `r2-02-heavy`,
`r2-03-watching`, `r2-04-isometric` and `r2-05-fold`, each with a
`preview.png` and an honest README. The round-2 chooser records the pick.
