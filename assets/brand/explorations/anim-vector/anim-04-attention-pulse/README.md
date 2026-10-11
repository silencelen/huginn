# anim-04-attention-pulse — the tray wants you

**Preview:** `preview.png` (four rows, eight frames each, t 0 to 1.26 s: slide, pop,
first ring cycle. Row 1: 128 px on bone, vector. Row 2: the 64 px sprite on the dark
ground, pixel-upscaled 2x. Rows 3 and 4: the 64 px sprite frames Lanczos-downscaled to
24 and 16 px the way an OS tray does, pixel-upscaled for viewing.) Sprite sheets:
`sprite-ring.png` (12 ring frames at 64 px, ticks 15-26), `sprite-pop.png` (6 entry
frames: slide and pop, ticks 0-5). Raster: `poster.png` (the resting ATTENTION icon).
Live: `anim.html`, `anim.svg`. Generator: `build.py` (`--table` prints the frame
tables). Tray playback: `../tray-sprite-spec.md`.

## Concept

The transition INTO the ATTENTION tray state, animated once. It starts from the icon the
tray is showing (IDLE/WORKING: raven in a 60 x 60 box at (2,2)), slides and shrinks the
raven to the ATTENTION layout (50 x 42 box at (0,22)) in 300 ms, pops the badge dot in
at (53,11) r 9, and lets a ring emerge from behind the raven, expand and fade, twice.
Then everything rests on today's static ATTENTION icon. Every number is a pixel of the
64 px sprite and both end frames are exactly what `TrayIcons.kt` already draws. No loop:
an attention cue that never stops asking is one the owner learns to ignore.

## Timing

| element | | |
|---|---|---|
| slide | raven box (2,2,60,60) → (0,22,50,42); in the SVG `translate(-5.09,1.50) scale(0.7092)` → `translate(-5.91,17.59) scale(0.5910)` | 0.00-0.30 s, `cubic-bezier(0.2, 0, 0, 1)` |
| badge | `scale(0) → 1`, opacity 0 → 1 over the first 15 %; no overshoot | 0.30-0.54 s, same curve, once |
| ring | drawn behind the raven, centre (29,36); `r` 17 → 27.2 sprite px and `stroke-width` 4 → 1.5 sprite px animated directly (no transform, no `vector-effect`), opacity 0 → 0.85 at 8 % → 0 at 75 % | 1.2 s per cycle from 0.30 s, 2 cycles, then rest |
| total | | 2.7 s, then the static ATTENTION icon |

The ring starts inside the raven's silhouette and is painted beneath it, so it is first
seen leaving the body: the pulse comes from the raven, not from the icon's bounding
box. Its outer radius stops at 27.2 so it never clips the 64 px canvas edge.

## What it looks like at tray sizes (faithful rows)

The tray draws the 64 px sprite and the OS downscales it. Rows 3 and 4 of the preview
are made that way (Lanczos from the 64 px frames). At 24 px the ring is a thin, clear
circle for four to five ticks and the badge pop is obvious. At 16 px the ring is a brief
halo around the raven for two to three ticks; the badge pop is the cue that reads. The
earlier preview live-rendered 24 and 16 px with a non-scaling stroke, which drew the ring
4 CSS px thick at any size and overstated it 2.7-4x; that is gone.

## Resting state, reduced motion

Resting values are the ATTENTION icon: badge at `scale(1)` opacity 1, ring hidden, raven
in the ATTENTION box. A renderer without animations shows today's icon, not IDLE.
`@media (prefers-reduced-motion: reduce)` stops all three tracks (same result). The tray
cannot read that media query; the spec says what to do with an app setting instead.

## Where it plays

Desktop tray (Compose/Java2D, see the spec) on the transition into ATTENTION; the same
64 px frames serve a "needs you" badge on the desktop window's session list. Android
status icons cannot animate, so the notification shade is out of scope.

## Strengths

- Starts and ends on frames the tray already draws; the Kotlin change is additive
  (slide + ring + a 100 ms ticker, at most 17 cached 64 px frames, 272 KB).
- The slide turns today's hard snap (raven shrinks 17 % and drops 14 px in one tick)
  into a 300 ms move.
- Monochrome by construction: the tray tints everything one colour. Plays once.

## Weaknesses

- At 16 px the raven itself is the usual 12 x 7 px lump; this animation does not fix
  the mark's small-size problem, it works around it with the badge, and the ring there
  is a two-to-three-tick halo rather than a shape.
- A ring is a generic notification idiom; nothing raven-specific is in the motion
  itself. The raven-specific version would be a tiny wing lift, which does not read at
  16 px, so the generic one won.
- 10 fps sprite steps are visible on a HiDPI tray at 64 px; the spec gives the 24 fps
  variant for trays that repaint that fast.
- `r` as an animatable CSS property is current-browser behaviour (Chromium verified
  here; Firefox and Safari per their documentation, untested). This file is a spec preview
  for the Kotlin tray, not a web asset.

## Relation to the current mark and palette

Canonical path at the current tray geometry for both states. The tray tint stays the
tray's concern: the brief lists the grey/blue/amber state colours as a known
incoherence for the colour dimension to resolve; this animation is colour-agnostic.

## Revision

After review: the IDLE → ATTENTION reposition is now part of the sequence (300 ms
slide, two extra sprite frames) instead of a snap before it; the ring animates `r` and
`stroke-width` directly so the stroke is the spec's 4 → 1.5 sprite px at every size; the
24 / 16 px preview rows are Lanczos downscales of the 64 px sprite, as the tray would
show them; the badge pop lost its overshoot; the resting state is the ATTENTION icon
(it rested as IDLE); `--ink` scoped on `svg`; frame tables are generated by
`build.py --table` and the spec was rewritten around them; `poster.png` added.
