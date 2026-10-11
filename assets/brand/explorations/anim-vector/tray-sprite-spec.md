# Tray sprite spec — playing anim-04-attention-pulse from Compose / Java2D

`TrayIcons.kt` draws the tray icon with Java2D at 64 px and hands Compose a `Painter`.
This spec turns `anim-04-attention-pulse/anim.svg` into frames that code can draw with
the same `RavenMark.draw` call, so nothing is shipped as a file.

**Revision 2.** The sequence now starts from the icon the tray is actually showing:
`TrayIcons.kt` draws IDLE and WORKING with `RavenMark.draw(g, 2.0, 2.0, 60.0, 60.0)`
and ATTENTION with `(0.0, 22.0, 50.0, 42.0)`, so the raven shrinks 17 % and drops 14 px
on entering ATTENTION. That reposition is the first thing the owner sees and the old spec
started after it. The first 300 ms now slide the raven box from one to the other with
the same easing; the badge and the ring follow. The badge pop lost its overshoot (one
100 ms frame of overshoot at 10 fps read as a flicker). The frame tables are generated
by `anim-04-attention-pulse/build.py --table` from the same numbers that make the SVG.

## Geometry (64-unit canvas, 1 unit = 1 px of the 64 px sprite)

| element | value | today |
|---|---|---|
| raven, IDLE / WORKING | `RavenMark.draw(g, 2.0, 2.0, 60.0, 60.0, color)` | unchanged |
| raven, ATTENTION | `RavenMark.draw(g, 0.0, 22.0, 50.0, 42.0, color)` | unchanged |
| raven, slide | `RavenMark.draw(g, x(t), y(t), w(t), h(t), color)`: the box interpolated | new |
| badge | `Ellipse2D.Double(44.0, 2.0, 18.0, 18.0)` (centre 53,11, r 9), scaled about its centre | unchanged |
| ring | circle centre (29, 36), radius r(t), stroke w(t), alpha a(t), drawn BEFORE the raven | new |

## Frame maths

`E(u)` is CSS `cubic-bezier(0.2, 0, 0, 1)` evaluated at progress `u` (solve the x-bezier
for the parameter, read y; `build.py` has a 40-step bisection), clamped to [0, 1].

```
t  = seconds since entering ATTENTION

slide, t < 0.30:   p = E(t / 0.30)
                   box = (2, 2, 60, 60) + p * ((0, 22, 50, 42) - (2, 2, 60, 60))
                   no badge, no ring
after the slide:   tt = t - 0.30, box = (0, 22, 50, 42)

badge, tt < 0.24:  scale = E(tt / 0.24), alpha = min(1, scale / 0.15)    (no overshoot)
                   else scale = 1, alpha = 1

ring, two cycles:  u = (tt mod 1.2) / 1.2, drawn only while tt < 2.4 and u < 0.75
                   p = E(u / 0.75)
                   r = 17 + 10.2 * p
                   w = 4 - 2.5 * p
                   a = 0.85 * E(u / 0.08)                if u < 0.08
                     = 0.85 * (1 - (u - 0.08) / 0.67)    otherwise
```

Because `RavenMark.draw` fits the bbox into the box by `min(w/84.6, h/52)` and the width
is the limiting axis in both boxes, interpolating the box linearly is the same as
interpolating the raven's scale and offset linearly, which is what the SVG does.

### 10 fps (recommended for the tray)

One frame per 100 ms tick, evaluated exactly at the tick. Rows are from
`python3 anim-04-attention-pulse/build.py --table`.

| tick | t (s) | phase | raven box (x, y, w, h) | badge scale | ring r | stroke | ring alpha |
|---|---|---|---|---|---|---|---|
| 0 | 0.00 | slide | (2.0, 2.0, 60.0, 60.0) | - | - | - | 0 |
| 1 | 0.10 | slide | (0.5, 16.6, 52.7, 46.8) | - | - | - | 0 |
| 2 | 0.20 | slide | (0.1, 21.1, 50.5, 42.8) | - | - | - | 0 |
| 3 | 0.30 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 0.00 | 17.0 | 4.00 | 0.00 |
| 4 | 0.40 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 0.82 | 19.0 | 3.50 | 0.85 |
| 5 | 0.50 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 0.99 | 22.6 | 2.62 | 0.74 |
| 6 | 0.60 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 24.5 | 2.17 | 0.63 |
| 7 | 0.70 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 25.6 | 1.90 | 0.53 |
| 8 | 0.80 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 26.3 | 1.73 | 0.42 |
| 9 | 0.90 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 26.7 | 1.62 | 0.32 |
| 10 | 1.00 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 27.0 | 1.55 | 0.21 |
| 11 | 1.10 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 27.2 | 1.51 | 0.11 |
| 12 | 1.20 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 13 | 1.30 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 14 | 1.40 | cycle 1 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 15 | 1.50 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 17.0 | 4.00 | 0.00 |
| 16 | 1.60 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 19.0 | 3.50 | 0.85 |
| 17 | 1.70 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 22.6 | 2.62 | 0.74 |
| 18 | 1.80 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 24.5 | 2.17 | 0.63 |
| 19 | 1.90 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 25.6 | 1.90 | 0.53 |
| 20 | 2.00 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 26.3 | 1.73 | 0.42 |
| 21 | 2.10 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 26.7 | 1.62 | 0.32 |
| 22 | 2.20 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 27.0 | 1.55 | 0.21 |
| 23 | 2.30 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | 27.2 | 1.51 | 0.11 |
| 24 | 2.40 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 25 | 2.50 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 26 | 2.60 | cycle 2 | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |
| 27 | 2.70 | rest | (0.0, 22.0, 50.0, 42.0) | 1.00 | - | - | 0 |

Tick 0 is today's IDLE/WORKING icon and tick 27 is today's static ATTENTION icon, so
the transition starts and ends on frames that already exist. Ticks 12-14 and 24-26 are
the ATTENTION icon with no ring (the rest between cycles).

### 24 fps variant

Same maths, `t = i / 24`: 7 slide frames, 6 pop frames, 29 frames per ring cycle, 66
ticks for the whole sequence (`build.py --table` prints it). Windows and GNOME trays
repaint `TrayIcon.setImage` reliably at 10 Hz; at 24 Hz some shells coalesce updates
and the ring stutters anyway, which is why 10 fps is the recommendation. Test on the
target shell before choosing 24.

## Memory and CPU

- Cached frames at 10 fps: 2 slide + 9 distinct ring frames (ticks 3-11; cycle 2 reuses
  them, the badge is static by then) + 3 badge-pop frames with the ring (ticks 3-5 differ
  from the plain ring frames only by the badge) = at most 17 `BufferedImage`s of
  64 x 64 ARGB = 17 x 16 KB = 272 KB. Build them once per tint colour (ATTENTION only),
  lazily, on first use.
- Ticker: one coroutine, `delay(100)`, 27 iterations; set `Tray(icon = painter)` state
  each tick. Swapping the painter calls `TrayIcon.setImage`; 27 calls over 2.7 s is
  negligible. Cancel the job if the state leaves ATTENTION mid-sequence and show that
  state's static icon.
- Drawing a frame (Java2D, antialiasing on): draw the ring first with
  `g.stroke = BasicStroke(w)`, `g.composite = AlphaComposite.SrcOver.derive(a)`, then
  reset the composite and draw the raven in its box and the badge (scaled about
  (53, 11)) as today. The ring is drawn before the raven so it appears to leave the body.

## How it looks at tray sizes

The OS downscales the 64 px image to 16-32 px. `anim-04-attention-pulse/preview.png`
rows 3 and 4 are the 64 px sprite frames Lanczos-downscaled to 24 and 16 px (not live
renders): at 24 px the ring is a thin, clear circle for 4-5 ticks; at 16 px it is a
brief halo around the raven for 2-3 ticks and the badge pop is the cue that reads.

## Behaviour rules

- Play once per transition INTO ATTENTION; never loop while the state persists.
- If the user has a "reduce motion" preference in the app settings (Java cannot read
  the OS one portably), skip the ring and the pop: slide in 300 ms, then the static icon.
- Do not animate WORKING or IDLE in the tray; the chat UI owns the "thinking"
  indicator (`anim-05-thinking`).
- Frames are monochrome: the tint comes from the state colour exactly as today.

## Verifying

`node anim-04-attention-pulse/capture.mjs` regenerates `sprite-ring.png` (12 ring
frames, ticks 15-26) and `sprite-pop.png` (6 entry frames, ticks 0-5) from the SVG at
64 px (dark ground, bone ink) so a Kotlin implementation can be diffed against them
frame by frame.
