# anim-05-status — tmux status-bar segment

**Encoding:** Unicode. Lives in `server/tmux.conf` on the host, where Unicode
is allowed. `(o>` is the *font* fallback for a terminal without block
elements, not an encoding one; no 7-bit twin is needed.

**Preview:** `preview.png` (every frame of every state at 33 px and 13 px),
`preview.gif` (the attention cycle).

| state | file | cols | frames | rate |
|---|---|---|---|---|
| working: a thought drifts | `frames.*` | 7 | 4 | 1 fps (4 s cycle) |
| working (quiet): breathe | `frames-breathe.*` | 4 | 4 | 1 fps (4 s cycle) |
| needs you: turn + marker | `frames-attention.*` | 6 | 3 | 1 fps (3 s cycle) |
| waiting / no Claude: still | `frames-waiting.*` | 4 | 1 | static |

## Concept

The 1-row glyph raven `◢▆█▀` (the same glyph as anim-01: tail wedge, body,
head, beak on the head's top edge) lives in the tmux status bar of a Huginn
session and carries the product's three states without demanding a look.

- **working** — a thought `·` drifts out of the beak one column per second,
  then the line is clear for a second. At one frame per second this is the
  only kind of motion that reads as *motion* rather than as a glitch.
- **working (quiet)** — the body swells over four seconds: `◢▄█▀ ◢▆█▀ ◢██▀ ◢▆█▀`,
  a quarter of a cell per step (3 px at a 12 px font). For people who find
  the drift busy; the two-frame quadrant flicker of round 1 is gone.
- **needs you** — the raven turns to face the user (`▀█▆◣`) with a marker `◆`;
  the marker blinks hollow (`◇`), then the raven turns back with the marker
  still lit. For "a permission prompt is waiting" / "Claude asked something".
- **waiting**, and **no Claude** — the glyph, still. The colour tells the
  two apart (table below); no motion, because nothing is happening.

## State → frame → colour

One mapping for the status bar, the apps, the tray and the tab title. Colour
tokens are the colour dimension's (`color/`); today's hex values are from
`Theme.kt` and `TrayIcons.kt`; the ANSI slots are the colour dimension's
`ansi16` map, for a 16-colour tmux.

| state (word) | frames | colour token (dark value proposed by `color-01`) | today: app / tray | ANSI | tab-title glyph (`HUGINN_ICON_*`) |
|---|---|---|---|---|---|
| working | `frames` (or `frames-breathe`) | `stateWorking` = rune-gold `#C8A45C` (8.06:1) | `primary` gold / `#7AA2F7` blue | yellow | `●` (ASCII `*`) |
| needs you | `frames-attention` | `stateAttention` = gold lit `#F3D58F` (13.3:1), plus the `◆` marker | `error` `#E8736D` (6.42:1) / `#E0AF68` amber | brightYellow | `◆` (ASCII `!`) |
| waiting | `frames-waiting` | `stateIdle` `#8A8177` (4.96:1) for the glyph | `onSurfaceVariant` / `#7B8794` grey | brightBlack | `○` (ASCII `-`) |
| no Claude | `frames-waiting` | `outline` tier (dimmer than idle) | `outline` / tray grey | brightBlack, dim | none |

Round 1 put rune-gold on the *attention* marker; that was the opposite of the
apps (running → gold). Gold is now *working*, and the attention marker takes
whichever "needs you" colour the colour work settles on: today's `error`
`#E8736D`, or `stateAttention` if the lit-gold ladder is adopted. The
segment itself needs no colour at all; the table is for clients that have it.
The tab-title glyphs are candidates for re-glyphing `huginn-claude-title`
(today 🔄 ✋ ✅), which the brief lists as a surface the overhaul may change;
they are rendered in a UI font, so block elements are avoided there.

## tmux floor

tmux redraws the status line no faster than `status-interval` and the floor is
**1 second**. So: 1 fps, and frames that are meaningful on their own. Wire it
as:

```
set -g status-interval 1
set -g status-right '#(huginn-status-frame) %H:%M'
```

where `huginn-status-frame` prints one line: the frame at index
`$(date +%s) % N` from the file for the current state (the state comes from
the same hook that drives `huginn-claude-title`). A sketch (bash, host side):

```
f=~/.huginn/status/$state.txt          # frames.txt format, see the root README
n=$(grep -c '^---$' "$f"); n=$((n+1)); i=$(( $(date +%s) % n ))
awk -v i="$i" 'NR>1 && !/^#/ {if ($0=="---") {k++; next} if (k==i && NF) {print; exit}}' "$f" | sed 's/\xe2\xa0\x80/ /g'
```

(`tmux status 2`, two status rows, would allow a 2-row raven with an eye; not
tested here.)

## Fallbacks

The status bar is always a tty; the only fallback is a font without block
elements, where `(o>` stands in for the raven and the thought dot still
drifts. `NO_COLOR` is irrelevant. Under `status-interval 5` the drift is
simply slow. `◣` (U+25E3) and `◢` are missing from a few console fonts;
`▗▆█▀` / `▀█▆▖` are the block-only stand-ins.

## Strengths

- 1-fps-safe: every frame is a complete, sensible status on its own.
- Carries all three product states (and no-Claude) in 7 columns or fewer.
- The attention nudge (the bird turns to look at you) is unmistakable at
  33 px and still distinct at 13 px, without flashing a block.
- One glyph, shared with anim-01, so the brand has one 1-row raven.

## Weaknesses (honest)

- The glyph raven is a convention, not a likeness: at 13 px it is a wedge, a
  block and a bar. The turned form `▀█▆◣` reads as "turned" once the user
  has seen it face right.
- A segment that changes every second can be distracting in a bar the user
  reads constantly; "breathe" exists for that reason, and the drift should be
  switchable off.
- `stateAttention` as lit gold is the colour dimension's proposal; if the
  owner keeps `error` red for attention, the marker is red and the ladder
  argument in `color/` does not apply here.
- tmux's 1 s floor means the nudge cycle is 3 s long.

## Relation to the current mark / palette

Monochrome by default. Colours only per the table above; the one rule is
that gold means *working*, as it does in the apps.

## Revision (round 2)

Glyph changed to `◢▆█▀` / `▀█▆◣` (shared with anim-01; beak on the head);
attention colour corrected to the apps' attention semantics, gold reserved
for working; working / waiting / no-Claude frames added; the state → frame →
colour → ANSI → title-glyph table added and cross-referenced to `color/`;
breathe rebuilt as a four-step swell; encoding and surface stated; the
`frames.txt` sketch updated for the header line.
