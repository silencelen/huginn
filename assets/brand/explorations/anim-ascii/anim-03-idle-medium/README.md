# anim-03-idle-medium — the medium raven, idling

**Encoding:** Unicode. Plays host-side (`huginn-status`, which already prints
Unicode) and in the README GIF. The client's pre-attach screen is ASCII-only
and gets the 7-bit flap from anim-02, or nothing; there is no 7-bit twin of a
20-column raven worth shipping.

**Preview:** `preview.png` (base, blink and tuck frames of both sizes at 20 px,
then at 13 px), `preview.gif` (the 20-column loop, long holds clipped).

| | file | cols x rows | frames | timing |
|---|---|---|---|---|
| medium (default) | `frames-20col.*` | 20 x 12 | 12 | holds, ~11.9 s per loop |
| large (README) | `frames.*` | 24 x 15 | 12 | holds, ~11.9 s per loop |

Both are sampled from `assets/brand/raven.svg` (rendered at 512 px, box-filtered
to 40x24 / 48x30 pixels, thresholded) and rendered with quadrant blocks, then
hand-edited so the eye is a cell-aligned 2x2 hole (20 col: row 1, col 13;
24 col: row 2, col 15). The 20-column raven is the one for the CLI-sized
surfaces; 15 rows was half a phone terminal.

`holds` live in the `frames.txt` header and in `frames.json`; both shipped
players honour them, so the loop is calm everywhere (round 1's bash player
played it at a constant 12 fps, which turned the idle into a twitch).

## The loop (12 frames)

| frame | pose | hold |
|---|---|---|
| 0 | base | 3200 ms |
| 1 | lid half down (eye cell `▀`) | 60 |
| 2 | eye closed (`█`) | 90 |
| 3 | lid half | 60 |
| 4 | base | 2600 |
| 5 | head tuck 1 (whole head, beak included, moved 1 px down-left; eye closed for this transition frame) | 100 |
| 6 | head tuck 2 (2 px down-left) | 1100 |
| 7 | head tuck 1 | 100 |
| 8 | base | 1900 |
| 9 | lid half | 60 |
| 10 | closed | 90 |
| 11 | base | 2400 |

So: a blink about every 4-5 s and one head movement per loop. Nothing else.

## Concept

An idle animation should be *mostly stillness*. The blink is one cell
changing; the tuck translates the whole head region (every pixel above the
shoulder, beak included) along the back's own diagonal, so the back line
stays unbroken and the beak keeps its length (round 1 slid only part of the
head and the beak doubled in length for the 1.1 s hold; frames 0 and 6 now
have the same beak tip, three half-height cells, two columns further left).
The round-1 ruffle, four frames that differed by 3-4 edge cells, was
sub-perceptual at 13 px and was cut rather than kept as a cost with no return.
The eye is a hole, as in the SVG, so the mark keeps working on any ground.

## Where it plays

- Host: `huginn status` while waiting on the host; the raven idles above the
  output, 20 columns.
- README GIF: the 24-column version, record ~12 s so one full loop is shown.
- The apps should not use this; they have the vector mark.

## Fallbacks

Not a tty / `TERM=dumb` / `HUGINN_NO_ANIM` / terminal narrower than the frame:
frame 0 once (`rest=first`). Under 20 columns: the 5-row raven from anim-02.

## Strengths

- Faithful to the mark at 13 px cells: eye, beak, back, tail notch all
  present in both sizes; the silhouette is recognisably the Huginn raven.
- Holds make it calm, and the shipped players play them.
- One bitmap, two pixel operations: easy to re-tune, and the sampler makes a
  new mark a re-run, not a redraw.

## Weaknesses (honest)

- The head "turn" is really a tuck (down-left): in profile, a true turn
  cannot be drawn without a second view of the head.
- The 20-column eye is a full cell in a head five cells wide; it is a little
  large, which is the price of being visible at 13 px (the half-cell notch
  the sampler produced was a speck).
- Like the SVG, the body is a slab at this size; the brief already lists that
  as something the mark overhaul should fix, and a new mark means resampling
  (the sampler is a ten-line script).
- Only two gestures. A tail flick was considered and not drawn.

## Relation to the current mark / palette

Direct derivation of `raven.svg`. Monochrome.

## Revision (round 2)

Tuck fixed (whole head translated, beak length constant); ruffle cut; 20x12
version added and made the default for CLI-sized surfaces; holds carried in
the `frames.txt` header and honoured by `bash-player.sh`; encoding and
surfaces stated; previews re-rendered in Cascadia Mono with a 13 px strip.
