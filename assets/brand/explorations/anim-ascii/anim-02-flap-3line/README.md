# anim-02-flap-3line — perched raven: flap, hop, land, take off

**Encoding:** Unicode files (`frames*.txt/.json` without `ascii` in the name)
for host-side `huginn status` and the README GIF; **7-bit twins**
(`frames-ascii-flap.*`, `frames-ascii-landing.*`, `frames-ascii-takeoff.*`) for
`client/huginn.sh` / `client/huginn.ps1`, which are ASCII-only. The 7-bit twins
are grown from the raven the client already prints on its help screen
(`huginn.sh:988-994`), so *connected* ends on exactly that raven.

**Preview:** `preview.png` (every frame of every file at 20-30 px, then the
landing and both ravens at 13 px), `preview.gif` (the landing).

| | file | canvas (cols x rows) | frames | fps | plays |
|---|---|---|---|---|---|
| flap-hop, 3-row raven | `frames.*` | 9 x 4 (the hop needs the 4th row) | 6 | 10 | loop, 0.6 s |
| flap-hop, 5-row raven | `frames-5line.*` | 13 x 6 (hop) | 8 | 10 | loop, 0.8 s |
| landing — *connected* | `frames-landing.*` | 13 x 6 | 10 | 10 | once, 1.0 s, ends on the 5-row pose (`rest=last`) |
| takeoff — *detached* | `frames-takeoff.*` | 13 x 6 | 10 | 10 | once, 1.0 s, ends empty (`rest=none`) |
| landing, narrow | `frames-landing-3line.*` | 12 x 5 | 10 | 10 | once; for terminals under 60 columns only |
| takeoff, narrow | `frames-takeoff-3line.*` | 12 x 5 | 9 | 10 | once; same |
| 7-bit flap | `frames-ascii-flap.*` | 22 x 5 | 4 | 8 | loop, 0.5 s |
| 7-bit landing | `frames-ascii-landing.*` | 22 x 5 | 9 | 10 | once, ends on the help-screen raven |
| 7-bit takeoff | `frames-ascii-takeoff.*` | 22 x 5 | 9 | 10 | once, ends empty |

(The id keeps its round-1 name; "3-line" means the 3-row *raven*; the table
gives the rows a player repaints.)

The Unicode sprites are 2x pixel maps (16x6 and 24x10 pixels) rendered with
quadrant block elements, so every cell is one of `▘▝▖▗▀▄▌▐▚▞▛▜▙▟█`. The 5-row
raven has a true 2x2 eye hole, as the SVG mark does; the 3-row raven now has a
2x1 px eye hole on its second pixel row (a half-cell notch, `▟▀▙` across the
head), which survives the wing frames.

## Concept and timing

**Flap-hop.** Folded → wing half-raised → wing up → wing up *and* the body
lifts one row → half → folded. The wing is a tapered wedge rooted at the neck.
The hop is what sells it at 3 rows; at 5 rows the wing alone already reads.

**Landing** (play once on *connected*): the raven comes in from the right,
facing left, alternating wings-up / wings-down flying poses while descending,
brakes with the wings raised, folds them, holds one frame facing left, then
cuts to the canonical right-facing pose with 7 free columns to its right for
`connected · main`. The flying poses are drawn at the same 24x10 scale as the
perched 5-row raven, so the bird does not change size when it lands. The
wings-down pose is a wedge *under* the body, centred on the shoulder (round 1
hung a block at the tail, which read as a pistol).

**Takeoff** (play once on *detached*): the reverse, to the right: half, up,
hop, then flying frames climbing to the top-right and out. The last frame is
blank so the line can be reused.

**7-bit twins.** The help-screen raven `(o)==-` with its folded wing `//\`
raises the wing as a stroke rotating up and back (`\/\`, then `|\` with `\ \`
above); in flight it is `~~=(o)==-` with the wing `\ \` above or `/ /` below.
The landing mirrors it (`-==(o)=~~`, `\_\~=` tail) and ends by cutting to the
right-facing help raven, exactly as the Unicode one does.

## Where it plays

- CLI / Termux (7-bit twins): landing when `huginn <session>` attaches
  (printed as the last thing before `exec`), takeoff on a clean detach, the
  flap while the client polls.
- Host (Unicode): `huginn status` while the host is being polled (5-row
  flap-hop); the 3-row set only when the terminal is under 60 columns.
- README (as a GIF): the 5-row flap or the landing.

## Fallbacks

Not a tty / `TERM=dumb` / `HUGINN_NO_ANIM` / terminal narrower than the frame:
both players print the rest frame from the header (`rest=last` = the perched
raven for a landing, `rest=none` = nothing for a takeoff, `rest=first` for the
loops). Fonts without block elements: the 7-bit twins.

## Strengths

- The 5-row raven is a faithful small version of the mark; the eye hole and
  the beak survive at 13 px cells, and *connected* now ends on it.
- Landing and takeoff give *connected* and *detached* a visual verb that is
  the brand story (out and back), at one scale, with no pistol frame.
- The 7-bit twins mean the CLI identity has a successor today: the help raven
  is no longer a dead end but the resting pose of an animation.
- Sprites are generated from pixel maps, so a change to the pose is a 10-line
  edit, not a redraw.

## Weaknesses (honest)

- The wings-down flying pose (`DN5`) is a downward arrowhead in a still; only
  the alternation with the wings-up pose makes it a bird.
- The turn at the end of the landing is still a hard cut (a drawn front view
  read as a ghost in round 1 and was not retried).
- The 3-row raven's eye is a half-cell notch, not a hole; at 13 px it is a
  dark speck. It is the right size for its head but it is not the 5-row eye.
- The 7-bit flying sprite is two strokes and a body; it is a bird by
  convention, like every ASCII bird. The 7-bit flap has no hop (4 rows only).
- The 7-bit `.txt` files pad with `@`, which looks noisy in a text editor; the
  `.json` twins are the clean form.

## Relation to the current mark / palette

Derived by hand from `assets/brand/raven.svg` (the 5-row map started as a
quadrant sampling of the SVG and was then cleaned up); the 7-bit set derives
from the client's existing ASCII raven. Monochrome; no palette dependency.

## Revision (round 2)

Eye added to the 3-row raven; wings-down pose redrawn as a wedge under the
body; landing and takeoff rebuilt on the 13x6 canvas with 24x10 flying poses,
ending on / starting from the 5-row raven, with the 12x5 pair kept for narrow
terminals; 7-bit flap, landing and takeoff added, built on the help-screen
raven, asciicheck'd and rendered; rows stated honestly in the table; the
header's `rest` field makes the non-tty output the perched raven (round 1
printed a 2-cell speck); Unicode vs ASCII stated at the top.
