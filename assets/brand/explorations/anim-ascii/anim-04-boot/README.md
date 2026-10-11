# anim-04-boot — splash: the raven assembles, the banner types

**Encoding:** the Unicode boot (`frames.*`, `frames-12fps.*`, `frames-dissolve.*`)
is for the README hero GIF and, on the client, for a **data file**
`~/.huginn/boot.frames` that `huginn update` fetches the way it fetches
`huginn-device` (`huginn.sh:200-232`: `gh api … contents/client/boot.frames`,
else `scp` from the pinned update host; validate with "line 1 starts with
`# huginn-frames`" instead of `node --check`). When the file is absent, or the
terminal is under 42 columns or 17 rows, the client plays the **7-bit twin**
`frames-ascii.*`, which is small enough (19 frames, 42x8, 6.7 KB as a quoted
heredoc) to live inside `huginn.sh` / `huginn.ps1` themselves.

**Preview:** `preview.png` (boot at 24 and 12 fps, dissolve, and the 7-bit boot,
all at 13-15 px), `preview.gif` (the 24 fps boot).

| | file | canvas | frames | fps | length |
|---|---|---|---|---|---|
| boot | `frames.*` | 42x17 | 36 | 24 | 1.50 s, once, `rest=last` |
| boot, slow terminals | `frames-12fps.*` | 42x17 | 19 | 12 | 1.58 s, once, `rest=last` |
| dissolve (exit) | `frames-dissolve.*` | 42x17 | 17 | 24 | 0.71 s, once, `rest=none` |
| 7-bit boot | `frames-ascii.*` | 42x8 | 19 | 12 | 1.58 s, once, `rest=last` |

## Concept

**Boot.** A rain of glyphs falls into the medium raven (anim-03's 24-column
bitmap). Every cell of the silhouette is assigned a start frame; it enters at
the top of its column as a flickering braille dot, falls one row per frame,
and *locks* into its final block character when it reaches its row. Frames
0-19 are the rain (0.83 s); by frame 19 every cell is locked. Frames 20-33
type the banner `huginn · Thought, on call from anywhere.` three characters
per frame behind a `▌` cursor; 34-35 hold with the cursor gone. 1.5 s total.

Because cells fall from the top and lock at their own row, the silhouette
takes shape **from the crown down**: the first cell locks at row 0 in frame 1,
the head and beak rows are complete by frame 8, the body fills in across
frames 9-16 and the tail tip is the last thing to land (frames 17-19). Round
1 claimed "bottom up, beak last"; the frame data says the opposite, and
head-first is kept because bottom-up would need the rain to fall two rows a
frame (the bottom row cannot lock before frame 14 otherwise).

The rain is braille (`⠁⠂⠄⡀⠈⠐⠠⢀…`, plus `·`) and the silhouette is quadrant
blocks, deliberately two alphabets: a client that can colour may print
braille in `stateIdle` `#8A8177` (4.96:1 on the ground) and blocks in bone,
with no extra data.

**12 fps file.** Every second rain frame, typing at six characters per
frame, two hold frames: the same 1.6 s for terminals that cannot repaint 17
lines in 40 ms, and the default for the bash player on a phone.

**Dissolve.** The reverse, for exit: cells unlock in random order and fall
*out* as braille dots, two rows per frame, vanishing above the banner row;
the banner wipes from the right. Ends blank; nothing ever lands on the
banner row (checked across all 17 frames).

**7-bit boot.** The same idea in ASCII on a 42x8 canvas: a rain of `. : ' , \``
assembles the client's own help-screen raven `(o)==-` (rows 2-5), then the
banner `huginn - Thought, on call from anywhere.` types in six characters per
frame behind a `_` cursor. 12 fps, 1.6 s.

## Where it plays

- CLI / Termux: the first thing `huginn` prints when run with no arguments
  (`huginn.sh:981` runs `_huginn_attach` locally, so this is client code):
  the Unicode boot from `~/.huginn/boot.frames` when present and the
  terminal is at least 42x17, else the inline 7-bit boot. The dissolve on a
  clean exit, same rule, no 7-bit twin (exit should be quick; the 7-bit path
  just prints a newline).
- README: the hero GIF (`preview.gif` is a first cut; the brand book's
  version should be recorded with VHS on the chosen terminal theme).

## Fallbacks

Not a tty / `TERM=dumb` / `HUGINN_NO_ANIM`: the players print the last frame
(raven + banner) once, from the header's `rest=last`; that is also what a
log should contain. Narrower than 42 columns: the same static frame would
wrap, so the players print it only if it fits; the client should test
`$COLUMNS` and print the banner line alone otherwise.

## Strengths

- Under 2 s, and the rain reads as rain: columns fill at different times,
  the silhouette emerges head-first, the eye hole is visible from frame 8.
- Separate alphabets for rain and silhouette give a two-tone option for free.
- Deterministic (seeded): the same frames every time; another seed, another
  rain.
- Measured: the bash player plays the 36 frames in 1573 ms under a pty on
  this box (1500 nominal), with no per-frame fork.

## Weaknesses (honest)

- Dense: 36 frames of 42x17 is 64 KB as `.txt` (36 KB as JSON); it has to be
  a data file, and the update path for that file is specified, not written.
- 24 fps on Termux is unmeasured; the 12 fps file exists for that reason and
  should be the phone default until someone measures.
- Mid-rain frames look like noise in a still; this one only works in motion.
- The 7-bit boot's raven is four rows of ASCII; it is the help raven, not the
  mark, and its "rain" is short because the canvas is short.
- The raven is the anim-03 bitmap, with the same slab-body caveat.

## Relation to the current mark / palette

Monochrome by construction; optional two-tone with `stateIdle` / Muted
`#8A8177` for the rain. The banner text is the README tagline, spelled
`Thought, on call from anywhere.` (capital T, as in the README and the brief;
round 1 lowercased it; the wordmark dimension should keep this spelling).

## Revision (round 2)

Surface corrected (client-side, ASCII-only file): boot data file path, fetch
mechanism and no-file fallback specified, 7-bit boot shipped and rendered;
lock-order claim corrected to crown-first; 12 fps file shipped; `rest=last`
in the header so the shipped bash player's non-tty output is the raven and
banner, not a row of specks; width guard added to the bash player; tagline
capitalised; preview re-rendered in Cascadia Mono (which has braille; the
round-1 "DejaVu" preview was drawing the rain in FreeMono).
