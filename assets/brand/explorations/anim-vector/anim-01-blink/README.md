# anim-01-blink — the raven blinks

**Preview:** `preview.png` (six frames, 60 ms apart, across one blink). Live: `anim.html`, `anim.svg`.

## Concept

The mark stays exactly the canonical raven and does one thing a living thing does: it
blinks. Nothing else moves. The eye in `raven.svg` is an evenodd hole, so the lid is a
small ink circle (`<circle class="lid">`, r 2.6, slightly larger than the r 2 hole so no
anti-aliased halo leaks through when closed) that scales down from the top edge of the
eye, like an upper eyelid, and back. Because the lid is ink on ink it is invisible while
open, and the hole keeps showing whatever ground the mark sits on.

## Timing

| | |
|---|---|
| Loop | 12 s, `linear` master timeline; each segment carries its own easing |
| Blink | close 100 ms `cubic-bezier(0.4, 0, 1, 1)` · hold 60 ms · open 160 ms `cubic-bezier(0.2, 0, 0, 1)` = 320 ms |
| Events | 0.00 s single · 3.90 s single · 8.20 s + 8.58 s double (260 ms apart) |
| Average cadence | three blink events per 12 s, i.e. about two every five seconds, one of them a double |

The uneven spacing is deliberate: an evenly spaced blink reads as a metronome. The
timeline is `animation: huginn-blink 12s linear infinite` on the lid only; the keyframe
percentages are in `anim.svg`'s comment and were generated (12 s loop, t/12).

## Reduced motion

`@media (prefers-reduced-motion: reduce) { .lid { animation: none } }` — the lid stays at
`scaleY(0)` and the file is the static mark. The media query is honoured in the page; inside
an `<img>` the browser evaluates it against the embedding document, which Chromium and
Firefox do for SVG images (verify on the target browser before relying on it).

## Where it plays

- **App idle state** (phone and desktop): the raven on the empty-session screen or the
  connection card. Compose: a `rememberInfiniteTransition` driving `scaleY` on a clipped
  circle over the mark, same key times.
- **README hero**: `<img src="anim.svg">` — CSS animation inside an SVG `<style>` runs
  in `<img>` on GitHub (scripts are stripped; CSS is not). Verified in this folder by
  screenshotting an `<img>` embed at 0.1 s and 1.5 s against a copy whose lid holds closed.
- Anything the mark already appears in at 48 px or more.

## Strengths

- Zero new geometry: the mark is `<path id="raven">` referenced once; swapping the path
  is one edit (plus the lid's `cx cy r` if the new mark's eye moves).
- Reads as life, not as "an animation": a sober micro-motion in the 150-400 ms band.
- Works on any ground (hole stays a hole) and in a single colour.

## Weaknesses

- Below about 48 px the eye is a 1-2 px hole and the blink is invisible; the tray and
  the notification icon get nothing from it. That is acceptable (nothing should blink
  in a tray) but it means this is a hero/idle animation only.
- The lid's geometry is tied to the eye position; a redrawn mark needs the three numbers
  updated by hand.
- Inside `<img>`, the loop restarts whenever the image is re-decoded (tab discard,
  scroll-back on some browsers); harmless, but the "occasional" double blink is then
  less occasional.

## Relation to the current mark and palette

Uses `assets/brand/raven.svg` verbatim and the ink `#161310`; `anim.html?theme=dark`
shows bone `#E8E2DA` on `#12100F`. No accent colour is involved.
