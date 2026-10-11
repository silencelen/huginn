# mark-02-faceted — the raven as a paper-cut

**Preview:** `preview.png` (tile at 512/64/32/16 and the `-16` head at 32/22/16; inverse row; surfaces row: launcher mask + safe ring, tray at 16/22, status at 48/72; the current mark for comparison; then the unfold build-in frames and the braille tracing).

## Concept

The same perched raven as mark-01, cut from **12 straight edges** and a
circular eye hole. Every facet is a decision: two facets make the arched bill,
one near-flat facet is the crown, one concave vertex is the nape, and **one
long edge carries the back, the folded wing and the tail** from shoulder to
tail tip. Belly and under-tail are two facets each. Because every edge is
straight the mark rasterises crisp at any size, ports to any vector format
(Android `pathData`, Java2D, Compose `Path`) without curve questions, morphs
vertex-for-vertex (the unfold file), and traces into braille/block art for
terminals (the `.braille.txt` file). Engineered, not drawn.

Like mark-01 it ships a compact head drawing for 16–22 px, here in 9 edges.

## Files

| File | What |
|---|---|
| `mark-02-faceted.svg` | ink `#161310`, one evenodd polygon + circular hole, 108×108 viewBox, `translate(2,11)` |
| `mark-02-faceted-inverse.svg` | bone `#E8E2DA`, same polygon |
| `mark-02-faceted-tile.svg` | app-icon master: ink mark on the bone tile |
| `mark-02-faceted-16.svg` | **the compact 16 px drawing**: head, bill and neck in 9 edges + eye hole r 6.5, box x 14–105 / y 24–90 of a plain 108 viewBox |
| `mark-02-faceted-status.svg` | the polygon without the eye hole, for `ic_stat_huginn.xml` (rendered at 48/72 in the preview) |
| `mark-02-faceted-unfold.svg` | one-shot build-in (SMIL): the polygon morphs from its own back edge into the mark over 1.6 s, ease-out; the eye fades in last |
| `mark-02-faceted.braille.txt` | the mark traced into 28×7 braille cells (U+2800 block, equal line lengths; passes `asciicheck.py`) |
| `preview.png` | contact sheet |

## Intended uses and drop-in notes

Same surfaces and the same measured numbers as mark-01: box x 10.5–94.6,
y 18.5–67.5 (inside the current `RavenMark.kt` box); launcher group unchanged
(`pivot (52,43)`, `scale 0.72`), farthest point 32.4 dp from centre inside the
33 dp safe radius; tray uses the `-16` head in `TrayIcons.kt`'s existing
composition (box 14 / 24 / 91 / 66); status icon uses `-status`; favicon-16
uses `-16`. It is the better of 01/02 for anything rasterised by a third party
at unknown sizes (store listing, `.ico`) and for anything re-drawn in code,
ASCII or animation, because 12 vertices carry faithfully. The unfold morph is
the splash / README build-in; on Android it maps to an `AnimatedVectorDrawable`
path morph (same command count in both keyframes, which the polygon guarantees),
on desktop to a 12-vertex lerp in Compose.

## Strengths

- **Different from mark-01 at icon size, not only at hero size:** the straight
  back-to-tail edge and the angular head give a visibly sharper 64/32 px
  silhouette (see the root `compare.png`); the previous draft was
  pixel-identical to 01 there.
- Crisp at every size; the facets cost nothing in legibility.
- 12 vertices: trivial to port, to morph (the unfold file is 2 keyframes), to
  trace (the braille file reads as the bird at 28 cells wide).
- Same 16 px answer as mark-01 (head drawing reads in the tray at 16 on both
  grounds, clean at 22).
- Same box, transform, palette and rules as the current mark; monochrome.

## Weaknesses (honest)

- The **full mark** at 16 px is the same smudge as every full-pose drawing;
  the head drawing is the fix.
- A faceted bird is still adjacent to the low-poly trope; 12 large facets and a
  round eye keep it on the "cut paper" side rather than "2014 polygon fox",
  but hard edges want some geometry in the surrounding type and icon system
  or they date next to soft Material 3 surfaces.
- The head is angular enough that at 512 px some will read it as a stealth
  shape before a bird; the eye and bill make it a raven on a second look.
- The braille tracing is coarse: 28×7 cells, the eye is a one-cell gap, and
  it is Unicode-only (not for the ASCII-only CLI client files).
- The unfold's frame 0 is a hairline (the polygon on its own edge); a build-in
  that starts from the eye or from nothing is a one-line change, not done here.

## Relation to the current mark and palette

Same viewBox, transform, winding and eye-as-hole rule as
`assets/brand/raven.svg`, box inside the current one, same ink/bone pair, no
new colours. Swapping path data in the hand-carried copies is all it takes;
the `-16` drawing is a new path for the tray and favicon-16.

## Revision (r2)

After the creative-director review: rebuilt from 19 small facets to 12
deliberate ones; hexagon eye replaced by a circle (r 3.5, ink ring 4.78); the
fang under the chin and the 2-unit tail nick are gone; the belly is two facets
with a believable curve; the back, wing and tail are one edge so the 64/32 px
silhouette is no longer identical to mark-01; the tail corner raised to keep
the launcher safe circle (32.4 dp, was 33.55). The old `-16` (pixel-identical
to the mark) is replaced by a 9-edge head drawing; the eyeless polygon moved to
`-status` and is rendered at 48/72. The "trivial to animate / to trace" claims
now ship their proof: `-unfold.svg` (frames in the preview) and
`.braille.txt` (passes `asciicheck.py`).
