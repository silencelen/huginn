# anim-vector — motion for the mark on screens

Five explorations of how the Huginn raven moves, built on the current canonical path
(`assets/brand/raven.svg`). Every file keeps the mark as one `<path id="raven" d="...">`
and references it, so when the mark dimension picks a new raven the swap is one edit
(the generated ones re-read `raven.svg`; run `bash build.sh`).

| id | one line | look at |
|---|---|---|
| `anim-01-blink` | the eye blinks: an ink lid closes over the evenodd hole, 320 ms, irregular cadence; otherwise still | `anim-01-blink/preview.png` |
| `anim-02-assemble` | 103 terminal glyphs (`█▓▒░▀▄:.`) fly in and settle into the silhouette, then crossfade to the solid mark, 2.0 s | `anim-02-assemble/preview.png` |
| `anim-03-fly-return` | the raven crouches, lifts into a flight silhouette, exits right, returns from the left and perches: 5 s loop with a 2 s hold | `anim-03-fly-return/preview.png` |
| `anim-04-attention-pulse` | tray ATTENTION: badge pops, a ring emerges from the raven twice (1.2 s each), then rest; 64 px sprite, 10 fps | `anim-04-attention-pulse/preview.png` (rows: 128 / 24 / 16 px) |
| `anim-05-thinking` | three "Claude is working" indicators: (a) falling feather, (b) eye scan, (c) blinking block cursor. Recommendation: (c) | `anim-05-thinking/preview.png` |

Plus `tray-sprite-spec.md`: how `TrayIcons.kt` (Java2D) plays anim-04 - geometry, frame
maths, a 12-frame table at 10 fps, the 24 fps variant, memory and behaviour rules.

## What each folder holds

- `anim.svg` - the animation as pure SVG + CSS, usable in the README via `<img>`.
  GitHub strips scripts from SVG but CSS animations inside the SVG's `<style>` do play
  inside `<img>`; this was verified here by screenshotting an `<img>` embed early and
  late against a copy whose lid holds closed (127 px differed). `anim-inverse.svg`
  (bone on dark) exists for 01-03 for a `<picture>` dark source.
- `anim.html` - self-contained preview page: the SVG inlined, no dependencies, with
  `window.seek(t)` (pauses every animation and sets it to t seconds) and `?t=<s>` /
  `?theme=dark` query parameters, so any instant can be screenshotted.
- `capture.mjs` - rebuilds `preview.png` with Playwright through the shared
  `../capture.mjs` (seek, wait two frames, screenshot, label, tile with ImageMagick).
- `build.py` (02-05) - generates the SVG(s) from `raven.svg`; 02 also samples the
  silhouette into glyphs and writes `glyphs.txt`.
- `README.md` - concept, timing table, reduced-motion fallback, where it plays,
  strengths, weaknesses, relation to the mark and palette.

## House motion rules applied throughout

- Micro-motion in the 150-400 ms band (blink 320 ms, badge pop 260 ms); arrivals
  `cubic-bezier(0.2, 0, 0, 1)`, departures ease-in, symmetric `(0.45, 0, 0.55, 1)` for
  oscillation. The only overshoots are a 2 % stretch at lift-off and an 8 % badge pop.
- Nothing loops at full energy: the blink is a 12 s cycle with three events, the flight
  rests 2 s in 5, the pulse plays twice and stops, the cursor is a step function.
- Monochrome: every file uses one `--ink` variable (`#161310`, or `#E8E2DA` on
  `#12100F`); no accent colour anywhere. No emoji; the terminal block passes
  `tools/asciicheck.py`.
- `@media (prefers-reduced-motion: reduce)` in every SVG degrades to the static mark
  or a static indicator, never to nothing.

## Honest limits

- 01 and 05(b) only read at hero sizes; 02 needs 160 px or more; 04 fixes nothing about
  the mark at 16 px (the badge carries the cue there). 03 introduces new geometry (the
  flight silhouette) that must be redrawn if the mark changes. 02's settled block is
  tuned to DejaVu Sans Mono metrics and can show hairline gaps on other monospace
  fonts for the 330 ms before the crossfade.
- Capturing: a paused animation seeked to exactly `currentTime = 0` is treated by
  Chromium as not started, so the compositor keeps the pre-seek frame (a half-popped
  badge in 04). `window.seek(t)` therefore clamps to 1 ms; frames labelled `t=0.00s`
  are 1 ms in, which is visually identical.

## Rebuild

```
bash assets/brand/explorations/anim-vector/build.sh
```
