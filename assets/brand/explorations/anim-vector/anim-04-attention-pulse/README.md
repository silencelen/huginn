# anim-04-attention-pulse — the tray wants you

**Preview:** `preview.png` (three rows, six frames each, 240 ms apart across one 1.2 s
ring cycle: 128 px on bone; 24 px and 16 px on the dark ground, pixel-upscaled). Sprite
sheets: `sprite-ring.png` (12 frames at 64 px), `sprite-pop.png` (3 frames). Live:
`anim.html`, `anim.svg`. Generator: `build.py`. Tray playback: `../tray-sprite-spec.md`.

## Concept

The ATTENTION tray state, animated once. The layout is exactly what `TrayIcons.kt`
already draws (raven in a 50 x 42 box at (0,22), badge dot at (53,11) r 9 on the
64-unit canvas), so the resting frame IS the current icon. On entering the state the
badge pops in (260 ms) and a ring emerges from behind the raven, expands and fades,
twice, then everything rests. No loop: an attention cue that never stops asking is one
the owner learns to ignore.

## Timing

| element | | |
|---|---|---|
| badge | `scale(0) -> 1.08 at 65 % -> 1`, opacity 0 -> 1 over the first 15 % | 260 ms, `cubic-bezier(0.2, 0, 0, 1)`, once |
| ring | drawn behind the raven, centre (29,36), r 17 -> 27.2 (`scale(1.6)`), stroke 4 -> 1.5 px (`vector-effect: non-scaling-stroke`), opacity 0 -> 0.85 at 8 % -> 0 at 75 % | 1.2 s per cycle, `cubic-bezier(0.2, 0, 0, 1)`, 2 cycles, then rest |
| total | | 2.4 s, then the static ATTENTION icon |

The ring starts inside the raven's silhouette and is painted beneath it, so it is first
seen leaving the body: the pulse comes from the raven, not from the icon's bounding
box. Its outer radius stops at 27.2 so it never clips the 64 px canvas edge
(left 1.8, bottom 63.2).

## Reduced motion

`@media (prefers-reduced-motion: reduce)` hides the ring and shows the badge static. For
the tray, which cannot read that media query, the spec recommends a settings toggle
and, with it off, the pulse plays only on the transition into ATTENTION.

## Where it plays

Desktop tray (Compose/Java2D, see the spec), Android notification shade is out of scope
(status icons cannot animate), and the same 64 px frames serve a "needs you" badge on
the desktop window's session list.

## Strengths

- Reads at 24 px (ring and badge both clear) and at 16 px the badge pop still reads
  while the ring reads as a brief glow around the raven: good enough for a cue.
- Resting frame equals today's icon; the Kotlin change is additive (15 cached frames,
  a 100 ms ticker, 240 KB).
- Monochrome by construction: the tray tints everything one colour.

## Weaknesses

- At 16 px the raven itself is the usual 12 x 7 px lump; this animation does not fix
  the mark's small-size problem, it works around it with the badge.
- A ring is a generic notification idiom; nothing raven-specific is in the motion
  itself. The raven-specific version would be a tiny wing lift, which does not read at
  16 px, so the generic one won.
- The sprite route costs 100 ms steps (10 fps); the ring's growth is visibly stepped
  at 64 px on a HiDPI tray. The spec gives the 24 fps variant for trays that can take it.

## Relation to the current mark and palette

Canonical path at the current tray geometry. The tray tint stays the tray's concern:
the brief lists the grey/blue/amber state colours as a known incoherence for the colour
dimension to resolve; this animation is colour-agnostic.
