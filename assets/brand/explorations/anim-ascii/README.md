# anim-ascii — terminal animations of the raven

Frame animations for the CLI (`client/huginn.sh`, `client/huginn.ps1`, Termux),
the host's tmux status bar and the README GIF. Every frame is a block of
equal-width lines; a player repaints with ANSI cursor-up. Monochrome,
single-width characters only (ASCII, box drawing, block elements, geometric
shapes, braille), no emoji, no runes.

**Unicode or ASCII.** `client/huginn.sh` and `client/huginn.ps1` are ASCII-only
(CONTRIBUTING.md "Dev notes": `huginn update` ships them over `scp` without a
BOM and PowerShell 5.1 then mis-decodes the whole file). So every variation
that plays in the client ships a **7-bit twin** (`frames-ascii*.txt/.json`,
pure ASCII including the padding) next to the Unicode one; which one is used
where is stated at the top of each README and summarised here:

| variation | Unicode files play in | 7-bit twin plays in |
|---|---|---|
| `anim-01-spinner-1line` | README, host-side tools (`huginn-status`) | `huginn.sh` / `huginn.ps1` while connecting: `frames-ascii`, `frames-b-thought-ascii` |
| `anim-02-flap-3line` | README GIF, host-side `huginn status` | `huginn.sh` / `huginn.ps1` on attach / detach: `frames-ascii-landing`, `frames-ascii-takeoff`, `frames-ascii-flap` |
| `anim-03-idle-medium` | host-side `huginn-status`, README GIF | none; the client shows the 7-bit flap from anim-02 or nothing |
| `anim-04-boot` | README hero GIF; `~/.huginn/boot.frames` data file when present | `huginn.sh` / `huginn.ps1` first run: `frames-ascii` (inline) |
| `anim-05-status` | host, `server/tmux.conf` (Unicode allowed there) | none needed; `(o>` is the *font* fallback |

| variation | one line | look at |
|---|---|---|
| `anim-01-spinner-1line` | two one-line "connecting" spinners: the wingbeat `╲▄╱` and a thought flying out to the host ring and back, each with a 7-bit twin | `anim-01-spinner-1line/preview.png`, `preview.gif` |
| `anim-02-flap-3line` | 3-row and 5-row perched ravens that flap-hop; a landing for *connected* and a takeoff for *detached*; 7-bit twins grown from the client's `(o)==-` raven | `anim-02-flap-3line/preview.png`, `preview.gif` |
| `anim-03-idle-medium` | the 20- and 24-column ravens (sampled from `raven.svg`) idling: blink and head tuck, with holds | `anim-03-idle-medium/preview.png`, `preview.gif` |
| `anim-04-boot` | splash: glyph rain assembles the raven, the tagline types in, 1.5 s (24 fps) or 1.6 s (12 fps); a dissolve for exit; a 7-bit boot for the client | `anim-04-boot/preview.png`, `preview.gif` |
| `anim-05-status` | tmux status segment at 1 fps for working / needs you / waiting, with the state → frame → colour table | `anim-05-status/preview.png`, `preview.gif` |

Recommended set, if one has to be picked now: 01(a) while connecting (7-bit
twin in the client), 02 landing/takeoff for connected/detached (7-bit twins
in the client, Unicode on the host), 03 `frames-20col` for `huginn status`,
04 for the README hero and the first run (7-bit boot inline, Unicode boot as
a data file), 05 drift + attention for tmux.

The one 1-row raven used everywhere is `◢▆█▀` (tail wedge, body, head, beak on
the top edge of the head, where the mark's beak is); turned to face the user
it is `▀█▆◣`; its 7-bit stand-in is `(o>`.

## File formats

- `frames.txt` — line 1 is a header:
  `# huginn-frames cols=W rows=H fps=N loop=yes|no rest=first|last|none [holds=ms,ms,…] [pad=X]`.
  Then a blank line, then frames separated by a line containing only `---`
  (blank lines either side; blank lines are ignored by the players). `rest` is
  the frame printed when the animation cannot play (non-tty, narrow terminal):
  `first` for loops, `last` for one-shots that end on a pose, `none` for ones
  that end empty. `holds` are milliseconds per frame and win over `fps`.
  Trailing padding inside a frame line is the **pad character**: U+2800
  BRAILLE BLANK by default, `@` in the 7-bit twins (declared with `pad=@`), so
  every block keeps equal widths under `tools/asciicheck.py`, no line ends in
  whitespace (editors and hooks that strip trailing whitespace would otherwise
  corrupt frames silently), and the 7-bit files stay 7-bit. Players turn the
  pad back into a space.
- `frames.json` — `{"name", "encoding": "unicode"|"ascii", "fps", "loop",
  "rest", "cols", "rows", "frames": ["line\nline", …]}`, optionally `"holds"`
  and a `"note"`. Lines are padded with plain spaces. This is the form for the
  apps and `play.py`.
- Extra candidates in a folder are `frames-<slug>.json/.txt`; 7-bit twins are
  `frames-…-ascii` or `frames-ascii-…`.

Every `frames.txt` here passes `python3 tools/asciicheck.py anim-ascii/*/*.txt`;
every `*ascii*` file is pure 7-bit (`LC_ALL=C grep -P '[^\x00-\x7F]'` finds nothing).

## Players

- `bash-player.sh` — `huginn_play FRAMES.txt|- [LOOPS] [FPS]`. Pure 7-bit
  ASCII and bash builtins only: no `tput` (Termux ships none), no `sleep`
  (the delay is `read -t` on a never-written pipe), no `awk`; the cursor-up
  escape and the per-frame delays are computed once with `printf -v`, so a
  frame costs one `printf`. Reads the header (fps, loop, rest, holds, pad),
  prints the rest frame with trailing blanks trimmed when stdout is not a tty
  / `TERM=dumb` / `HUGINN_NO_ANIM` is set / the terminal (`$COLUMNS`, else
  `stty size`) is narrower than the frame, hides and restores the cursor,
  honours holds, caps passes with `LOOPS`, and puts the caller's INT trap
  back after Ctrl-C (returns 130). `-` reads stdin, so a quoted heredoc inside
  `huginn.sh` is a frames source. Measured under a pty on this box: the 36-frame
  24 fps boot plays in 1573 ms (nominal 1500), the 12 fps 7-bit boot in 1642
  (1583), a 300/100/200 ms holds file in 626 (600), a 2-pass loop cap in 1028
  (1000); Ctrl-C after 300 ms returned in 409 ms with the cursor restored. Not
  measured on Termux.
- `play.py <frames.json|frames.txt> [--once] [--frames N] [--fps F]` — reads
  both formats, validates that every frame has the same dimensions (refuses
  otherwise), same fallbacks (`shutil.get_terminal_size`, so `COLUMNS`
  overrides), honours `holds` and `rest`. `--frames N` forces N frames even
  headless (smoke test: `python3 play.py anim-04-boot/frames.json --once --frames 3`).
- `ps-player.ps1` — `Huginn-Play FRAMES.txt [-Loops N] [-Fps N]`, the
  PowerShell twin for `client/huginn.ps1`: same header, pad and fallbacks,
  `[Console]::SetCursorPosition` repaints, `[Console]::CursorVisible`. **Not run
  here** (no pwsh in this environment); it needs one run on Windows Terminal
  and on conhost before it is trusted.

## Fallback policy (all variations)

1. Not a tty, `TERM=dumb`, or `HUGINN_NO_ANIM` set → the rest frame once, no
   escape codes (nothing at all for `rest=none`).
2. Terminal narrower than the frame → the rest frame (cursor-up arithmetic
   breaks once lines wrap). Both players check the width.
3. `NO_COLOR` changes nothing: every frame is monochrome.
4. Fonts without block elements or braille (raster-font conhost, Consolas
   without a fallback) → the 7-bit twin where one exists, else the ASCII
   stand-ins named in each README. The 7-bit twins are also the *encoding*
   answer for the client files; the two cases are different and both are
   covered.

## State language (shared with the apps)

The product's three states are *working* / *needs you* / *waiting*; the colour
dimension names them `stateWorking` / `stateAttention` / `stateIdle`. The
animations map onto them as: working = 01 (connecting) or 05 drift / breathe;
needs you = 05 attention (the raven turns to you); waiting = 05 still, 03 idle.
The full table (frame file, colour token, today's hex, ANSI slot, tab-title
glyph) is in `anim-05-status/README.md`.

## How they were made

Sprites are 2x pixel maps (`#`/`.`) rendered with quadrant blocks; the medium
ravens were sampled from `assets/brand/raven.svg` (rendered at 512 px,
box-filtered to 48x30 and 40x24, thresholded) and hand-edited for a
cell-aligned eye hole. The previews are `<pre>` blocks in **Cascadia Mono**
(the Windows Terminal default; it has braille, blocks and the geometric shapes
in one font), `#E8E2DA` on `#12100F`, rendered with `tools/render.mjs` and
trimmed; each sheet carries a 13 px strip because that is the size the critic
and a phone will see. Every candidate was also checked in DejaVu Sans Mono at
12/14/16 px (DejaVu Sans Mono has no braille: in round 1 Chromium drew the
rain in FreeMono, which is why that preview was mislabelled). Terminals that
draw blocks from a font may show hairline seams between adjacent cells at
fractional cell widths; terminals that draw them themselves (kitty, WezTerm,
Alacritty, Windows Terminal) do not. The `preview.gif` files are the same
frames assembled with ImageMagick at the file's timing (holds over 1.5 s are
clipped so the GIF keeps moving).

## Revision (round 2)

Changes after the creative director's review: every CLI-facing variation now
ships a 7-bit twin and states Unicode vs ASCII; `frames.txt` gained the
header (fps / loop / rest / holds / pad) and both shipped players read it, so
one-shots print their resting pose, holds play, and narrow terminals get a
static frame; `bash-player.sh` lost `tput`, `sleep` and `awk`. Craft fixes:
the wingbeat's downstroke registers the body to the wing roots (`╱▀╲`); the
1-row glyph is `◢▆█▀` everywhere (beak on the head, not under the chin) and the
host ring is `○`/`●`; the stave orbit (no brand content) was dropped; the
3-row raven has an eye; the flying wings-down pose is a wedge under the body
(the pistol is gone) and the landing/takeoff were rebuilt at the 5-row scale
so *connected* ends on the raven with the eye; the idle tuck moves the whole
head so the beak keeps its length, the ruffle was cut, and a 20-column raven
ships; the boot README tells the truth about lock order (crown first, tail
last), the 12 fps boot and a 7-bit boot ship as files, the tagline is spelled
`Thought, on call from anywhere.`; anim-05 maps attention to the apps'
attention colour (not gold), adds working / waiting frames and the state
table. Previews are re-rendered in Cascadia Mono with 13 px strips and a GIF
per variation. A PowerShell player was written but could not be run here.
