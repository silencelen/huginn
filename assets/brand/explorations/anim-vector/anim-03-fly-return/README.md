# anim-03-fly-return — out and back

**Preview:** `preview.png` (six frames, 600 ms apart, across the 3 s flight: crouch,
out, exit, re-entry, glide, perched). Live: `anim.html`, `anim.svg`. Generator: `build.py`.

## Concept

The brand story literally: Huginn flies out over the world and comes back to tell what
he saw. The canonical perched mark crouches, lifts off into a flight silhouette that beats
out of frame to the right, and a moment later returns from the left, flares, folds its
wings and settles back on the perch. A 5 s loop: 2 s perched, 3 s of flight. The flight
silhouette is three shapes: a body with the perched mark's heavy head, bill and a wedge
tail, and one broad four-fingered wing used twice (near and far, the far one 10 % smaller
and 20 degrees ahead), rotated about the shoulder.

## Timing (one 5.00 s loop, all tracks on one `linear` master so `seek(t)` is exact)

| t (s) | perched mark | flyer | wings |
|---|---|---|---|
| 0.00-2.00 | at rest | hidden | - |
| 2.00-2.30 | crouch `scaleY(0.94)` from the feet, ease-in-out | hidden | - |
| 2.30-2.50 | lifts `translate(8,-14)`, stretches to 1.04, fades out (ease-in) | fades in on the same path, nose up 14 deg | first upstroke |
| 2.50-3.40 | hidden | out to `translate(135,-50)`, `cubic-bezier(0.5, 0, 1, 1)` | beat |
| 3.40-3.60 | hidden | off-canvas; jumps to `(-128,-34)` at 3.55 | beat |
| 3.60-4.40 | hidden | in to `(-8,-8)`, `cubic-bezier(0.2, 0, 0, 1)`; beats until 4.00 then glides at +5 deg | beat, glide |
| 4.40-4.72 | fades in from `(4,-6)` (ease-out) | flare: nose up 16-18 deg, wings sweep to -70 then -95 deg, fades out | fold |
| 4.75-5.00 | settle `scaleY(0.965)` at 4.85, back to 1 | hidden | - |

Wing beat: 0.28 s per cycle (3.6 Hz, raven-like), -45 deg (top) to +55 deg (bottom),
downstroke 42 % of the cycle, upstroke 58 %, `cubic-bezier(0.45, 0, 0.55, 1)` each way.
Six beats out, then glide in. Every departure is ease-in and every arrival ease-out; the
only overshoot is the 2 % stretch at lift-off.

## Reduced motion

`@media (prefers-reduced-motion: reduce)` stops all four tracks and hides the flyer: the
file becomes the static perched mark.

## Where it plays

- **Desktop "reconnecting"**: the raven is out; when the session re-attaches the loop is
  cut at the next landing (4.75 s) and the state card shows the perched mark. Compose:
  drive one `Animatable` 0..5 s and read the keyframe tables above, or play the SVG in
  a WebView on desktop only.
- **Installer finish page** (`wizard.bmp` panel is a static BMP; this would be the
  in-app first-run page instead): one pass, then hold perched.
- README hero in dark mode via `anim-inverse.svg` if the owner wants motion there; the
  two-second perched hold keeps it from being busy.

## Strengths

- The one animation that tells the brand story rather than decorating the mark.
- Flight silhouette reads as a corvid (broad fingered wing, wedge tail, heavy bill) at
  64 px and still reads as "a bird flying" at 32 px (checked on the wing-pose sheets).
- 5 s loop with a 2 s rest: never at full energy for long.

## Weaknesses

- The flight silhouette is NEW geometry (`#body`, `#wing` in `build.py`). Swapping the
  canonical path updates the perched state only; a redrawn mark needs its flyer
  redrawn to match head and bill.
- The two hand-overs (2.30-2.50 s and 4.56-4.72 s) are crossfades, not morphs; freeze
  a frame there and the two silhouettes are visibly doubled. At speed it is a blur, but
  a true morph (path interpolation) would be better and is out of scope for CSS in
  `<img>`.
- The side-view wing is one shape rotated through 100 degrees; the foreshortening is
  faked. Fine at icon sizes, a little puppet-like at 512 px.
- Needs a canvas with room on both sides; cropped to the mark's bbox the exit and
  entry are cuts.

## Relation to the current mark and palette

Perched state is `assets/brand/raven.svg` verbatim; ink `#161310` / bone `#E8E2DA` only.
