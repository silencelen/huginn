# anim-02-assemble — terminal glyphs settle into the raven

**Preview:** `preview.png` (six frames, 350 ms apart, 0.25 s to 2.00 s). Live: `anim.html`,
`anim.svg`. Settled ASCII block: `glyphs.txt`. Generator: `build.py`.

## Concept

ASCII-to-vector. 103 monospace glyphs from the terminal-safe set (`█ ▓ ▒ ░ ▀ ▄ : .`) are
scattered across the canvas, each rotated and displaced in a random direction; they fly
in along an ease-out curve and settle on a monospace grid that reproduces the mark's
silhouette (full blocks inside, half blocks and shades at the edges, `:` and `.` as dust
at the bill tip). When the last glyph lands the block crossfades to the solid canonical
path and holds. The order is meaningful: delay grows with distance from the eye, so the
raven gathers itself from the head outward — thought assembling.

The glyph positions are not hand-placed. `build.py` renders `assets/brand/raven.svg`
with `tools/render.mjs`, samples the alpha on a grid of 0.602 em x 1.18 em cells (DejaVu
Sans Mono's advance and block-glyph height), picks the glyph by top/bottom-half coverage,
and bakes every `<text>` with its own `--dx --dy --r` start and `animation-delay`. A new
mark is one swap: edit `raven.svg`, run `python3 build.py`.

## Timing

| | |
|---|---|
| Total | 2.00 s, plays once, holds on the solid mark (`animation-fill-mode: both`) |
| Per glyph | 0.90 s, `cubic-bezier(0.2, 0, 0, 1)`; opacity 0 to 1 over the first 35 % |
| Stagger | delay 0.05 s + 0.60 s x (distance from eye / max) + up to 0.12 s jitter, so 0.05-0.77 s |
| Last glyph lands | 1.67 s |
| Crossfade | 1.67-2.00 s, linear: glyph layer 1 to 0, solid mark 0 to 1 |
| Grid | 27 x 9 cells, 5.5 px font on the 108-unit canvas, glyphs drawn 10 % oversize so neighbouring anti-aliased edges overlap instead of leaving seams |

## Reduced motion

`@media (prefers-reduced-motion: reduce)` hides the glyph layer and shows the solid mark
at full opacity with no animation.

## Where it plays

- **App splash** (phone and desktop): 2 s is a splash's budget; the hold state IS the
  mark, so the splash can hand over to the launcher icon without a cut. In Compose this
  is best re-implemented natively (a `Text` per glyph with `Animatable` offsets is cheap
  at 103 glyphs; or play the SVG in a WebView, which is not recommended).
- **Docs hero** / README: `<img src="anim.svg">` plays once per decode. For a page hero
  that should replay, wrap it in a link or a `<details>` that re-mounts the image.
- The settled `glyphs.txt` block doubles as a CLI banner candidate (passes `asciicheck.py`).

## Strengths

- Says "terminal" and "raven" in one gesture, in the house glyph set, without emoji
  or colour.
- Fully generated from the canonical path; the sampler is 150 lines of Python.
- Degrades to exactly the static mark (reduced motion, no CSS, old renderers).

## Weaknesses

- Font-dependent: the settled block is tuned to DejaVu Sans Mono metrics. Inside an
  `<img>` on another OS (Menlo, Consolas, Cascadia) block heights differ by a few
  percent, so the settled silhouette can show hairline gaps for the 330 ms before the
  crossfade hides it. Baking the blocks as `<rect>`s would remove the dependency at the
  cost of the glyph idea; worth doing for the shipped version if the gaps show.
- 103 animated text nodes: fine on desktop, measurable on a low-end phone in a WebView.
  Native Compose is the right home on mobile.
- Plays once; the README version is easy to miss on a slow decode.
- At 64 px and below the scatter reads as noise; the piece needs 160 px or more.

## Relation to the current mark and palette

Canonical path, ink `#161310`; `anim.html?theme=dark` for bone on `#12100F`. No accent.
