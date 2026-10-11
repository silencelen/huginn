# anim-05-thinking — "Claude is working"

**Preview:** `preview.png` (six frames of the three-option page, 500 ms apart, dark
theme, each option shown large and at 24 px). Live: `anim.html` (all three),
`anim.svg` (option c, the recommendation), `feather.svg` (a), `eye-scan.svg` (b).
Generator: `build.py`.

## The three options

**(a) feather** - a single feather drops beside the raven, tumbling: 2.8 s loop,
`translate` + `rotate` on four keyframes with `cubic-bezier(0.45, 0, 0.55, 1)`, opacity
in over the first 10 %, out over the last 18 %. Quiet and slightly melancholy; the
feather is a new 16-unit shape.

**(b) eye scan** - an ink pupil inside the eye hole slides left, holds, slides right,
holds: 2.4 s loop, `cubic-bezier(0.45, 0, 0.55, 1)`, 18 % holds at each end. The raven
visibly "looks". The pupil is a 1-unit circle inside the 2-unit hole, so it is a sub-pixel
at any size below about 120 px.

**(c) cursor** - the raven glyph followed by a block cursor that blinks at the terminal's
cadence: 1.06 s period, `steps(2, jump-none)` (530 ms on, 530 ms off, no fade). The
raven is still; the cursor is a 10 x 21 unit rect at the bill's height.

## Recommendation: (c) cursor

It is the only one that survives 24 px (see the small row in the preview: the feather is
a speck, the pupil invisible), it says "terminal" in the house idiom, it costs one
`Box` with an alpha toggle in Compose, and a hard-stepped blink is honest about what the
app is doing: waiting on a process. Use (a) as the empty-state illustration at 96 px or
more if a softer idle is wanted; keep (b) for hero sizes only.

## Timing summary

| option | period | easing | micro-motion |
|---|---|---|---|
| a feather | 2.8 s, infinite | symmetric ease-in-out per segment | 4 sway segments of ~0.7 s |
| b eye scan | 2.4 s, infinite | symmetric ease-in-out | 0.77 s slides, 0.43 s holds |
| c cursor | 1.06 s, infinite | `steps(2, jump-none)` | 530 ms on/off |

## Reduced motion

(a) hides the feather; (b) centres the pupil and stops; (c) shows a steady cursor.
All three therefore degrade to a static "working" indicator rather than to nothing.

## Where it plays

Chat UI (phone and desktop) next to the streaming message while Claude works; the
desktop WORKING tray state could use (c) too, but a blinking tray icon is a known
annoyance, so the tray should keep its static tint and leave the blink to the chat.

## Strengths / weaknesses

- (c) + : reads at 16 px, zero new geometry, terminal-native. - : a blinking block is a
  cliche; at 1.06 s it is slower than most terminals (0.5-0.6 s), chosen to be calmer.
- (a) + : charming at 96 px+. - : invisible small; a feather falling can read as "lost".
- (b) + : the most "alive". - : hero-only; the pupil must be re-placed if the mark changes.

## Relation to the current mark and palette

All three use the canonical `<path id="raven">`; `--ink` only, no accent colour.
