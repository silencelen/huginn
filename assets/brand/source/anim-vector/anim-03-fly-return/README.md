# anim-03-fly-return — out and back

**Preview:** `preview.png` (row 1: eight frames across the loop, ink on bone, 224 px:
rest, wings unfurled, climb, exit, entry, flare, fold, rest; row 2: the same on the app's
dark ground; row 3: the same at 64 px, pixel-upscaled, so the small read is on record).
Live: `anim.html`, `anim.svg` (`anim-inverse.svg` for dark). Raster fallback: `poster.png`
(resting state). Generator: `build.py`; `capture.mjs` also runs an edge scan.

## Concept

The brand story literally: Huginn flies out over the world and comes back to tell what
he saw. The canonical perched mark crouches, opens its wings, lifts off as a flight
silhouette that beats out of frame to the right, and a moment later returns from the
left, flares, lands and folds its wings. A 5 s loop: 2 s perched, 3 s of flight. The
flight silhouette is three shapes: a body with a heavy round head, the mark's evenodd eye
hole, the mark's bill proportion and a wedge tail, and one broad four-fingered wing used
twice (near and far, the far one 10 % smaller and 20 degrees ahead), rotated about the
shoulder.

The wings are the continuous element across both hand-overs. They unfurl on the perched
mark (the perched raven opens its wings) and the body swap from perched to flight
silhouette is a single-frame cut that happens under the first downstroke; on landing the
body cuts back during the flare and the wings fold away on the perched mark. No frame
shows two bodies (measured: mid-grey pixel counts are flat, edge anti-aliasing only,
through every sampled instant of both cut windows).

## Timing (one 5.00 s loop, all tracks on one `linear` master so `seek(t)` is exact)

| t (s) | perched mark | flyer (translate · pose) | flight body | wings |
|---|---|---|---|---|
| 0.00-2.00 | at rest | hidden at the shoulder position | hidden | folded (scale 0.25, -120°) |
| 2.00-2.30 | crouch `scaleY(0.94)` from the feet, ease-in-out | - | hidden | - |
| 2.26-2.40 | rises back to 1 | wings become visible | hidden | unfurl: -120° → -45°, scale 0.25 → 1, ease-out |
| 2.40-2.52 | push-off: `translate(0,-3)` | lifts 3 with it | hidden | first downstroke -45° → +55° |
| **2.53** | **cut: hidden** | - | **cut: shown** | under the downstroke |
| 2.50-3.40 | hidden | out to `translate(135,-22)`, `cubic-bezier(0.5, 0, 1, 1)`; nose up to -14° by 2.80 | shown | beat |
| 3.40-3.60 | hidden | off-canvas; jumps to `(-128,-12)` at 3.55 | shown | beat |
| 3.60-4.40 | hidden | in to `(-8,-2)`, `cubic-bezier(0.2, 0, 0, 1)`; +6° settling to +2° | shown | beat until 4.00, then glide at +5° |
| 4.40-4.60 | hidden | to the shoulder position, nose up -16° (flare) | shown | sweep to -70° (4.58), -95° (4.66) |
| **4.63** | **cut: shown** at `translate(0,-3)` | - | **cut: hidden** | up and back |
| 4.63-4.80 | settles down 3, ease-out | moves down with it | hidden | fold: → -130°, scale 0.25, then hidden |
| 4.75-5.00 | `scaleY(0.965)` at 4.85, back to 1 | hidden | hidden | - |

Wing beat: 0.28 s per cycle (3.6 Hz, raven-like), -45° (top) to +55° (bottom),
downstroke 42 % of the cycle, upstroke 58 %, `cubic-bezier(0.45, 0, 0.55, 1)` each way.
Six beats out, then glide in. Every departure is ease-in and every arrival ease-out.
There is no overshoot or stretch anywhere (the earlier 1.04 stretch is gone). Cuts are
`step-end` keyframes 30 ms wide: the value holds, then jumps, so a paused frame can only
ever show one body.

## Rest frame = the canonical placement

Both birds sit inside one `<g transform="translate(2,11)">`, the mark's own group
transform from `raven.svg`, and the perched group carries no transform attribute of its
own (its CSS animation used to override one, which displaced the rest frame by (-2,-11)).
Measured at 256 px: the ink bounding box at t = 1.0 s is (29, 70, 227, 188), identical
to anim-01's; 1,303 pixels differ at anti-aliasing level (mean 19/255, max 67, one pixel
above 64) because the animated group rasterises on its own layer. With animations
disabled the file is pixel-identical to anim-01's rest frame (0 px differ).

## Canvas edges

`capture.mjs` scans every 50 ms of the flight at 256 px: no frame has ink on the top or
bottom row. Left and right contact is the exit and the entry, by design.

## Reduced motion, renderers, `<img>`

`@media (prefers-reduced-motion: reduce)` stops all six tracks; the resting values hide
the flyer and the flight body, so the file is the static perched mark, and that is also
what a renderer without animations shows (`poster.png` is that frame). Observed under
Chromium emulation: the query is honoured inline and when the file is opened directly,
and ignored inside `<img>` (the embed keeps flying with the preference on: 10,115 /
18,647 / 9,962 / 3,419 px differ from the rest frame at 2.6 / 3.0 / 3.9 / 4.5 s). A 5 s
infinite flight is the worst case in the folder for motion-sensitive readers, so this
file is **not** offered as a README hero until `<img>` reduced-motion is verified on
real browsers; Firefox and Safari were not tested here.

## Where it plays

- **Desktop "reconnecting"**: the raven is out; when the session re-attaches the loop is
  cut at the next landing (4.63 s) and the state card shows the perched mark. Compose:
  drive one `Animatable` 0..5 s and read the keyframe tables above.
- **In-app first-run / installer finish page**: one pass, then hold perched.
- In-app only; the app can honour its own reduce-motion setting.

## Strengths

- The one animation that tells the brand story rather than decorating the mark.
- Flight silhouette carries the mark's identity: the same eye hole, a heavy head, the
  bill proportion; reads as a corvid at 224 px and as a flying bird at 64 px (row 3).
- Wings continuous across the hand-overs; 2 s rest in every 5; departures ease-in,
  arrivals ease-out.
- Every track is generated as keyframes from one table in `build.py`; the numbers in
  this README are the numbers in the file.

## Weaknesses

- The flight silhouette is NEW geometry (`BODY`, `WING` in `build.py`). Swapping the
  canonical path updates the perched state only; a redrawn mark needs its flyer redrawn
  to match head and bill.
- The body swap is a cut. At launch it hides under the near wing's downstroke; at
  landing the wings are swept up, so the cut from flight body to perched body is visible
  as one frame of change under the flare. Honest alternative: a path morph, which is not
  portable inside `<img>`, so it was not attempted.
- At 32 px the flight phase is a moving lump; the 64 px row is the smallest size claimed.
- The side-view wing is one shape rotated through 100 degrees; the foreshortening is
  faked. Fine at icon sizes, a little puppet-like at 512 px.
- Needs a canvas with room on both sides; cropped to the mark's bbox the exit and
  entry are cuts.

## Relation to the current mark and palette

Perched state is `assets/brand/raven.svg` at its own placement; ink `#161310` / bone
`#E8E2DA` only.

## Revision

After review: the rest frame is the canonical placement (one outer group; bbox verified
against anim-01); the flight path is lower (exit to y -22, entry from y -12, glide to
y -2) and the edge scan shows no top/bottom clipping; the resting values hide the flyer
and the flight body (no doubled bird in non-animating renderers); the dissolves are
replaced by wings that unfurl on the perched mark and a step-end body cut under the
first downstroke, with the fold on landing; translate and rotate split into separate
groups so each segment has one easing; the duplicate 100 % keyframe is gone; the flyer
has the mark's eye hole and a heavier head; the "2 % stretch" and 32 px claims are
withdrawn; README-hero use is withdrawn until `<img>` reduced motion is verified;
`--ink` scoped on `svg`; dark and 64 px rows added to the preview; `poster.png` added.
