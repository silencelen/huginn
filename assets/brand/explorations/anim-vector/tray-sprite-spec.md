# Tray sprite spec — playing anim-04-attention-pulse from Compose / Java2D

`TrayIcons.kt` draws the tray icon with Java2D at 64 px and hands Compose a `Painter`.
This spec turns `anim-04-attention-pulse/anim.svg` into frames that code can draw with
the same `RavenMark.draw` call, so nothing is shipped as a file.

## Geometry (64-unit canvas, 1 unit = 1 px of the 64 px sprite)

| element | value | today |
|---|---|---|
| raven | `RavenMark.draw(g, 0.0, 22.0, 50.0, 42.0, color)` | unchanged |
| badge | `Ellipse2D.Double(44.0, 2.0, 18.0, 18.0)` (centre 53,11, r 9) | unchanged |
| ring | circle centre (29, 36), radius r(t), stroke w(t), alpha a(t), drawn BEFORE the raven | new |

## Frame math

Ring, per 1.2 s cycle, with `E(u)` = CSS `cubic-bezier(0.2, 0, 0, 1)` evaluated at
progress `u` (solve the x-bezier for u, read y; or use the table):

```
u  = (t mod 1.2) / 1.2
if u < 0.75:  p = E(u / 0.75)
              r = 17 + 10.2 * p
              w = 4 - 2.5 * p
              a = 0.85 * E(u / 0.08)             if u < 0.08
                = 0.85 * (1 - (u - 0.08) / 0.67)  otherwise
else:         ring not drawn (a = 0)
```

Badge, first 0.26 s only, `p = E(min(t / 0.26, 1))`:

```
scale = 1.08 * p / 0.65                      if p < 0.65
      = 1.08 - 0.08 * (p - 0.65) / 0.35      otherwise
alpha = min(1, p / 0.15)
```

### 12 frames at 10 fps (recommended for the tray)

The ring cycle is 1.2 s; at 100 ms ticks that is 12 frames per cycle. Values below are
the keyframes evaluated at each tick (all px on the 64 px sprite):

| frame | t (s) | ring r | stroke | ring alpha |
|---|---|---|---|---|
| 0 | 0.0 | 17.0 | 4.00 | 0.00 |
| 1 | 0.1 | 19.0 | 3.50 | 0.85 |
| 2 | 0.2 | 22.6 | 2.62 | 0.74 |
| 3 | 0.3 | 24.5 | 2.17 | 0.63 |
| 4 | 0.4 | 25.6 | 1.90 | 0.53 |
| 5 | 0.5 | 26.3 | 1.73 | 0.42 |
| 6 | 0.6 | 26.7 | 1.62 | 0.32 |
| 7 | 0.7 | 27.0 | 1.55 | 0.21 |
| 8 | 0.8 | 27.2 | 1.51 | 0.11 |
| 9-11 | 0.9-1.1 | - | - | 0 (static icon) |

Badge pop frames (first cycle only): frame 0 scale 0 / alpha 0, frame 1 scale 1.05 /
alpha 1, frame 2 scale 1.00 / alpha 1.

Playback sequence on entering ATTENTION, one frame per 100 ms tick:

```
pop0 pop1 pop2 ring3 ring4 ... ring11      (cycle 1: 12 ticks, badge pops in)
ring0 ring1 ... ring11                     (cycle 2: 12 ticks)
hold ring11 (= today's static ATTENTION icon)
```

24 ticks = 2.4 s, then nothing moves until the state changes.

### Why not 12 frames at 24 fps

Twelve frames at 24 fps is a 0.5 s cycle, which is a flicker, not a pulse; the brief's
1.2 s is right. If the tray can repaint at 24 fps, keep the maths and change the tick:
29 frames per cycle (`t = i / 24`), 58 ticks for the two cycles, ~460 KB cached. Windows
and GNOME trays repaint `TrayIcon.setImage` reliably at 10 Hz; at 24 Hz some shells
coalesce updates and the ring visibly stutters anyway, which is why 10 fps is the
recommendation. Test on the target shell before choosing 24.

## Memory and CPU

- Cached frames: 12 ring + 3 pop = 15 `BufferedImage`s of 64 x 64 ARGB = 15 x 16 KB
  = 240 KB. Build them once per tint colour (ATTENTION only), lazily, on first use.
- Ticker: one coroutine, `delay(100)`, 24 iterations; set `Tray(icon = painter)` state
  each tick. Swapping the painter calls `TrayIcon.setImage`; 24 calls over 2.4 s is
  negligible. Cancel the job if the state leaves ATTENTION mid-sequence and show that
  state's static icon.
- Drawing a frame (Java2D, antialiasing on): draw the ring first with
  `g.stroke = BasicStroke(w)`, `g.composite = AlphaComposite.SrcOver.derive(a)`, then
  reset the composite and draw the raven and the badge as today. The ring is drawn
  before the raven so it appears to leave the body.

## Behaviour rules

- Play once per transition INTO ATTENTION; never loop while the state persists.
- If the user has a "reduce motion" preference in the app settings (Java cannot read
  the OS one portably), skip straight to `ring11`.
- Do not animate WORKING or IDLE in the tray; the chat UI owns the "thinking"
  indicator (`anim-05-thinking`).
- Frames are monochrome: the tint comes from the state colour exactly as today.

## Verifying

`node anim-04-attention-pulse/capture.mjs` regenerates `sprite-ring.png` and
`sprite-pop.png` from the SVG at 64 px (dark ground, bone ink) so a Kotlin
implementation can be diffed against them frame by frame.
