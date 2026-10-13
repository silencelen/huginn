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

Still open, in order: what attention means and which accent carries it; the
palette ground; the type direction; the terminal letterform; the one-row
raven's 7-bit twin; the motion set.

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
