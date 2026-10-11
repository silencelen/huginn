# anim-02-assemble — terminal glyphs settle into the raven

**Preview:** `preview.png` (row 1: six frames, 350 ms apart, 0.25 s to 2.00 s, ink on bone;
row 2: the same on the app's dark ground; row 3: the resting state with animations
disabled at 256 / 64 / 32 / 16 px, then the live first frame, which is empty by design).
Live: `anim.html`, `anim.svg` (`anim-inverse.svg` for dark). Settled Unicode block:
`glyphs.txt`. Raster fallbacks: `poster.png` (resting state), `fallback.gif` (the 2 s
assembly and a 1.5 s hold, 25 fps, 189 KB). Generator: `build.py`.

## Concept

ASCII-to-vector. 93 terminal glyphs from the brief's terminal-safe set (`█ ▓ ▒ ░ ▀ ▄`)
are scattered across the canvas, each rotated and displaced in a random direction; they
fly in along an ease-out curve and settle on a monospace grid that reproduces the mark's
silhouette (full blocks inside, half blocks and shades at the edges). When the last
glyph lands the solid canonical path fades in over the block and the blocky fringe
fades away. The order is meaningful: delay grows with distance from the eye, so the
raven gathers itself from the head outward: thought assembling.

The glyph positions are not hand-placed. `build.py` renders `assets/brand/raven.svg`
with `tools/render.mjs`, samples the alpha on a grid of 0.602 em x 1.18 em cells (DejaVu
Sans Mono's advance and block-glyph height), picks the glyph by top/bottom-half coverage,
and bakes every glyph with its own `--dx --dy --r` start and `animation-delay`. The full
and half blocks (79 of the 93) are written as `<rect>`s, which is what those characters
are, so the settled silhouette does not depend on the viewer's font; only the 14 shade
glyphs are `<text>`. A new mark is one swap: edit `raven.svg`, run `python3 build.py`.

## Timing

| | |
|---|---|
| Total | 2.00 s, plays once, holds on the solid mark (`animation-fill-mode: both`) |
| Per glyph | 0.90 s, `cubic-bezier(0.2, 0, 0, 1)`; opacity 0 to 1 over the first 35 % |
| Stagger | delay 0.05 s + 0.60 s x (distance from eye / max) + up to 0.12 s jitter, so 0.05-0.77 s |
| Last glyph lands | 1.67 s |
| Hand-over | 1.67-1.85 s the solid mark fades in ON TOP of the opaque glyph block; 1.85-2.00 s the glyph layer (by then only a fringe outside the mark) fades out. The silhouette's interior is ink throughout (measured: body-centre luminance 20-36 of 255 at every frame of the hand-over); only the fringe passes through grey |
| Grid | 27 x 9 cells, 5.5 px font on the 108-unit canvas; rects bleed 0.3 units so abutting cells leave no anti-aliased seam (5 stray pixels in a 1419-pixel interior sample at 256 px; the `crispEdges` alternative left 255) |

## Resting state and renderers

The resting values (what applies when no animation runs) are `.mark { opacity: 1 }` and
`.glyphs { opacity: 0 }`, and the glyph group also carries `opacity="0"` as a
presentation attribute. So:

- a renderer that applies CSS but no animations (thumbnailers, librsvg, Batik, Android,
  social cards) shows the solid mark (preview row 3; identical at 256 / 64 / 32 / 16 px
  to the canonical mark);
- a renderer that ignores `<style>` entirely shows the mark in default black with no glyphs;
- a live renderer screenshotted at load (what `tools/render.mjs` does) shows the first
  frame, which is empty: the assembly starts from nothing by design. Use `poster.png`
  where a first frame matters.

## Reduced motion

`@media (prefers-reduced-motion: reduce)` hides the glyph layer and shows the solid mark
with no animation. Observed under Chromium emulation: honoured inline and when the file
is opened directly; NOT honoured inside `<img>` (the embed differs from its final frame
by 10,266 / 11,813 / 4,728 / 3,977 px at 121 / 638 / 1,119 / 1,719 ms with the preference
on). For a 93-particle scatter this matters: put the animated file inline on the docs
site, where the query works, and keep the README on `poster.png` with the animation
opt-in behind a link. Firefox and Safari were not tested here.

## Where it plays

- **App splash** (phone and desktop): 2 s is a splash's budget; the hold state IS the
  mark, so the splash can hand over to the launcher icon without a cut. In Compose this
  is best re-implemented natively (a `Box` per rect and a `Text` per shade glyph with
  `Animatable` offsets; or play the SVG in a WebView, which is not recommended).
- **Docs hero**: inline SVG; plays once per mount. For a hero that should replay, re-mount it.
- `glyphs.txt` is a README / docs block (Unicode block elements, boxed). It is NOT a CLI
  banner candidate: `client/huginn.sh` and `huginn.ps1` are 7-bit ASCII only, and a 7-bit
  raven is the ascii dimension's job, not this file's.

## Strengths

- The one piece that fuses the owner's ASCII ask with the mark: terminal glyphs become
  the raven, in the house glyph set, no emoji, no colour.
- Fully generated and deterministic from the canonical path; the sampler is 150 lines.
- Resting state is exactly the static mark at every size; the structure survives any
  monospace font (checked in DejaVu Sans Mono, Liberation Mono and FreeMono, which used
  to stripe the old all-text block).

## Weaknesses

- At 9 rows the bill is a two-row slab and the silhouette reads bird, not specifically
  raven; the eye hole does most of the work. A finer grid (33 x 11) was tried and loses
  the eye hole to a shade cell, so 27 x 9 stays.
- The 14 shade glyphs are still font-dependent: a font with other metrics (or none of
  the block characters) misplaces a few fringe cells for the 330 ms before the hand-over.
- 93 animated nodes: fine on desktop, measurable in a low-end WebView; native Compose on
  mobile.
- Plays once, first frame empty, and reduced motion is ignored in `<img>`.
- At 64 px and below the scatter reads as noise; the piece needs 160 px or more.

## Relation to the current mark and palette

Canonical path, ink `#161310`; `anim-inverse.svg` for bone on `#12100F`. No accent.

## Revision

After review: resting state is now the mark (it was blank: both layers rested at
opacity 0); the grey dip is gone (sequenced hand-over instead of a crossfade); full and
half blocks are `<rect>`s so FreeMono-style metrics cannot stripe the block; the `: .`
dust tier is dropped (103 glyphs became 93, no crumbs under the bill or at the tail);
the CLI-banner claim is withdrawn; the reduced-motion limitation in `<img>` is stated;
`--ink` scoped on `svg`; dark row and resting tiles added to the preview; `poster.png`
and `fallback.gif` added.
