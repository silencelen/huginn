# anim-01-blink — the raven blinks

**Preview:** `preview.png` (row 1: six frames, 60 ms apart, across one blink, ink on bone;
row 2: the same on the app's dark ground; row 3: the closed-eye frame zoomed 4x on both
grounds, and the resting state with animations disabled at 256 / 64 / 16 px). Live:
`anim.html`, `anim.svg` (`anim-inverse.svg` for dark). Raster fallbacks: `poster.png`
(resting state), `fallback.gif` (one 12 s loop, 11 KB). Android sketch: `avd-blink.xml`.

## Concept

The mark stays exactly the canonical raven and does one thing a living thing does: it
blinks. Nothing else moves. The eye in `raven.svg` is an evenodd hole, so the lid is a
small ink circle (`<circle class="lid">`, r 2.6, larger than the r 2 hole) that scales
down from the top edge of the eye, like an upper eyelid, and back. Because the lid is
ink on ink it is invisible while open, and the hole keeps showing whatever ground the
mark sits on.

## Timing

| | |
|---|---|
| Loop | 12 s, `linear` master timeline; each segment carries its own easing |
| Blink | close 100 ms `cubic-bezier(0.4, 0, 1, 1)` · hold 60 ms · open 160 ms `cubic-bezier(0.2, 0, 0, 1)` = 320 ms |
| Events | 0.00 s single · 3.90 s single · 8.20 s + 8.58 s double (380 ms start to start, a 60 ms gap between the first opening and the second closing) |
| Average cadence | three blink events per 12 s, i.e. about one every four seconds, one of them a double |

The uneven spacing is deliberate: an evenly spaced blink reads as a metronome. The
timeline is `animation: huginn-blink 12s linear infinite` on the lid only; the keyframe
percentages are t / 12.

## Why the lid is oversize and not clipped

Review suggested clipping the lid to the eye hole so no anti-aliased edge of the lid
lands on ink. Measured at 512 px on the closed frame (eye crop, red channel, ink = 22):
the oversize lid gives min/max 20/22 on bone and 230/232 on the dark ground (bone = 232),
i.e. nothing beyond rounding. A lid clipped to the hole's own circle gives 21/114: the
hole's anti-aliased rim and the clip's rim are both partial, and their product leaves a
ring of up to 45 % ground. A clip at r 2.35 with the r 2.6 lid measures 21/22, the same
as no clip, so the simpler file stays. A Compose port should draw the lid as a filled
oval in the same ink on the same canvas, not as a separate layer.

## Reduced motion and where the file actually honours it

`@media (prefers-reduced-motion: reduce) { .lid { animation: none } }`: the lid rests at
`scaleY(0)`, the file is the static mark. Observed under Chromium emulation:

- inline SVG or the file opened directly: honoured (0 running animations);
- inside `<img>`: NOT honoured. A control copy whose lid holds closed for 3 s differs
  from its open frame by 127 px at every sample with the preference on. Treat the README
  embed as always-animating; a reduced-motion-respecting embed is inline SVG on the docs
  site, or `poster.png` in the README with the animated file behind a link.
- Firefox, Safari and GitHub's camo proxy were not tested (only Chromium is installed here).

## Where it plays

- **App idle state** (phone and desktop): the raven on the empty-session screen or the
  connection card. Compose: a `rememberInfiniteTransition` driving `scaleY` on a clipped
  circle over the mark, same key times. Android drawable surfaces (widget, notification
  big view): `avd-blink.xml` is a sketch of the same timeline as an AnimatedVectorDrawable
  (not compiled here; the two CSS curves become `pathInterpolator` resources).
- **README hero**: `<img src="anim.svg">`. CSS animation inside an SVG `<style>` runs in
  `<img>` in Chromium: screenshots of a file-hosted `<img>` embed differ from the open
  frame by 103 / 127 / 124 / 48 / 25 / 9 / 0 px at 90 / 121 / 170 / 203 / 255 / 287 / 321 ms,
  which is the 100 / 60 / 160 ms blink. Scripts are stripped by GitHub; CSS is not.
- Anything the mark already appears in at 48 px or more.

## Renderers without animation

Thumbnailers, librsvg, Batik, social-card and Slack unfurl renderers apply the CSS but
run no animations. For this file that state is the resting value `scaleY(0)`: the static
mark (row 3 of the preview). `poster.png` and `fallback.gif` exist for surfaces that
cannot take SVG at all.

## Strengths

- Zero new geometry: the mark is `<path id="raven">` referenced once; swapping the path
  is one edit (plus the lid's `cx cy r` if the new mark's eye moves).
- Reads as life, not as "an animation": a sober micro-motion in the 150-400 ms band.
- Works on any ground (hole stays a hole) and in a single colour; resting state is the
  mark at every size.

## Weaknesses

- Below about 48 px the eye is a 1-2 px hole and the blink is invisible; the tray and
  the notification icon get nothing from it. That is acceptable (nothing should blink
  in a tray) but it means this is a hero/idle animation only.
- The lid's geometry is tied to the eye position; a redrawn mark needs the three numbers
  updated by hand.
- Inside `<img>` the loop restarts whenever the image is re-decoded, and the reduced-motion
  preference is ignored there (see above).
- A faint ring of 2/255 at the lid's edge is measurable at 512 px on the closed frame.
  It is not visible; it is noted because a reviewer went looking for it.

## Relation to the current mark and palette

Uses `assets/brand/raven.svg` verbatim and the ink `#161310`; `anim-inverse.svg` and
`anim.html?theme=dark` show bone `#E8E2DA` on `#12100F`. No accent colour is involved.

## Revision

After review: timing arithmetic corrected (380 ms, one event per four seconds); the
reduced-motion claim narrowed to what was observed (honoured inline, not in `<img>`);
`--ink` scoped on the `svg` element instead of `:root` so an inlined copy cannot override
a host page's variable; the proposed clip-to-hole lid was measured and rejected (numbers
above); dark-ground row, closed-eye zooms and resting-state tiles added to the preview;
`poster.png`, `fallback.gif` and `avd-blink.xml` added.
