# anim-05-thinking — "Claude is working"

**Preview:** `preview.png` (six frames of the three-option page, dark theme, at instants
aligned to the PulseDot steps, each option shown large and at 24 px; then the resting
state with animations disabled). Live: `anim.html` (all three), `anim.svg` (option c,
the recommendation; `anim-inverse.svg` for dark), `feather.svg` (a), `eye-scan.svg` (b).
Android sketch: `avd-cursor.xml` (option c). Generator: `build.py`.

## The three options

**(a) feather** - a single feather drops beside the raven, tumbling: 2.8 s loop,
`translate` + `rotate` on four keyframes with `cubic-bezier(0.45, 0, 0.55, 1)`, opacity
in over the first 10 %, out over the last 18 %. The feather is a new 26-unit shape with
a barb notch and a 1-unit rachis gap, so it reads as a feather rather than a petal. Its
resting transform is the 30 % keyframe (beside the raven), not the origin.

**(b) eye scan** - an ink pupil inside the eye hole slides left, holds, slides right,
holds: 2.4 s loop, `cubic-bezier(0.45, 0, 0.55, 1)`, 18 % holds at each end. The pupil
is r 1.35 travelling +/-0.95 inside the r 2 hole (at the ends it touches the rim, which
reads as looking to the corner). Visible at hero size; sub-pixel below about 100 px.

**(c) cursor** - the raven glyph followed by a block cursor that breathes on the apps'
own clock. The cursor's opacity steps through the PulseDot table (`0.35 0.5 0.68 0.85 1
1 0.85 0.68 0.5 0.35`, 160 ms per step, 1.6 s, `step-end` between keyframes), which is
exactly the live marker in the session list and the chat screen. The raven is still.
Cursor geometry: height = the raven's head top to body bottom (26.3 units, one "line"),
width 0.55 x height (a terminal cell, 14.4), on the body's baseline, half a cell after
the bill tip.

## Recommendation: (c) cursor, on the PulseDot clock

It is the only one that survives 24 px (see the small row in the preview: the feather is
a speck, the pupil invisible), it says "terminal" in the house idiom, and it costs one
`Animatable` stepping through a ten-entry table in Compose, which the apps already have
as `PulseDot`. The earlier version blinked 530 ms on / 530 ms off on its own clock, which
put a second "working" rhythm next to the live marker on the same screen; the owner asked
for cohesion, so the cursor now shares the marker's breath (and its ~6 fps budget on the
phone). If the owner would rather retire PulseDot for a hard blink, that is one table
swap in both places; the recommendation is to keep the breath, because a 6 fps stepped
breath is what the phone can afford and a hard blink is louder than "working" needs.

Use (a) as the empty-state illustration at 96 px or more if a softer idle is wanted;
keep (b) for hero sizes only.

## Timing summary

| option | period | easing | micro-motion |
|---|---|---|---|
| a feather | 2.8 s, infinite | symmetric ease-in-out per segment | 4 sway segments of ~0.7 s |
| b eye scan | 2.4 s, infinite | symmetric ease-in-out | 0.77 s slides, 0.43 s holds |
| c cursor | 1.6 s, infinite | `step-end`, ten 160 ms steps | PulseDot alphas 0.35 → 1 → 0.35 |

## Resting state and reduced motion

Resting values: (a) feather beside the raven at the 30 % pose; (b) pupil centred;
(c) cursor at opacity 1. A renderer without animations therefore shows a static
"working" indicator for each (preview, last row). `@media (prefers-reduced-motion:
reduce)` gives the same static states. In `<img>` the preference is not honoured by
Chromium (observed on the other files in this folder); these are in-app indicators, so
the app's own reduce-motion setting is the switch that matters.

## Where it plays

Chat UI (phone and desktop) next to the streaming message while Claude works, on the
same clock as the live marker; the desktop WORKING tray state keeps its static tint (a
blinking tray icon is a known annoyance). `avd-cursor.xml` sketches (c) as an
AnimatedVectorDrawable for a widget or notification surface that takes a drawable
(stepped alphas as paired keyframes; not compiled here).

## Strengths / weaknesses

- (c) + : reads at 16 px, zero new geometry, terminal-native, one rhythm across the app.
  - : a block cursor is a cliche; a stepped breath at 6 fps is visibly stepped at hero
  size (by design, it is the apps' budget); the cursor height must follow the mark's
  body if the mark changes.
- (a) + : charming at 96 px+, and now reads as a feather. - : invisible small; a feather
  falling can read as "lost".
- (b) + : the most "alive" at hero size. - : hero-only; the pupil must be re-placed if
  the mark's eye moves.

## Relation to the current mark and palette

All three use the canonical `<path id="raven">`; `--ink` only, no accent colour. The
PulseDot table and its 160 ms step are the apps' (`BRIEF.md`, motion house rule).

## Revision

After review: (c) re-timed from a 530 / 530 ms blink to the PulseDot breath so the chat
indicator and the live marker share one rhythm; cursor proportion set to the raven's
line (26.3 x 14.4 on the body baseline) instead of a 10 x 21 door; feather redrawn at
26 units with a notch and a rachis gap, resting beside the raven instead of clipped at
the origin; pupil enlarged to r 1.35 and travel +/-0.95 so the scan is visible at hero
size; `--ink` scoped on `svg`; `anim-inverse.svg` generated by `build.sh`; resting-state
tile added to the preview; `avd-cursor.xml` added.
