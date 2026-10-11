# word-09-hairline — hand-drawn thin box-drawing `HUGINN`, in three charsets

**Width:** 33 columns × 5 rows, all three files. **Charsets:**
`wordmark.txt` Unicode light box drawing with rounded corners and the light
diagonal (`│ ─ ├ ┤ ┬ ┴ ╭ ╮ ╰ ╯ ╶ ╲`, U+2500–2572); `square.txt` Unicode light
box drawing with square corners and `\` (`│ ─ ├ ┤ ┬ ┴ ┌ ┐ └ ┘`, the subset
Liberation Mono has); `ascii.txt` **7-bit ASCII, eligible for the CLI client
files**. **Files:** `wordmark.txt`, `square.txt`, `ascii.txt`, `preview.png`.

```
│  │ │  │ ╭───╮ ─┬─ │╲   │ │╲   │      │  │ │  │ ┌───┐ ─┬─ │\   │ │\   │      |  | |  | .---. -+- |\   | |\   |
│  │ │  │ │      │  │ ╲  │ │ ╲  │      │  │ │  │ │      │  │ \  │ │ \  │      |  | |  | |      |  | \  | | \  |
├──┤ │  │ │ ╶─┐  │  │  ╲ │ │  ╲ │      ├──┤ │  │ │ ──┐  │  │  \ │ │  \ │      |--| |  | | --.  |  |  \ | |  \ |
│  │ │  │ │   │  │  │   ╲│ │   ╲│      │  │ │  │ │   │  │  │   \│ │   \│      |  | |  | |   |  |  |   \| |   \|
│  │ ╰──╯ ╰───╯ ─┴─ │    │ │    │      │  │ └──┘ └───┘ ─┴─ │    │ │    │      |  | '--' '---' -+- |    | |    |
```

## Concept

Custom-drawn, not a figlet font. Light lines form tall, narrow capitals on a
5-row cap height; the `N` has a true diagonal, the `I` carries small bars so
it does not read as a pipe, and the `G` and `U` have rounded corners. The
result is a hairline wordmark, closer to a wire sculpture than a banner. The
same drawing exists three times so that one letterform family can appear on
every surface: rounded (desktop terminals with DejaVu Sans Mono, JetBrains
Mono, Cascadia, Menlo), square (any font with the basic light box set,
Liberation Mono included), and ASCII (the 7-bit client scripts, plain text,
GitHub `<pre>`). Base for `word-10-perch`.

## Intended uses

Server-side splash (`huginn-status`, the attach banner) in desktop terminals;
the installer console; the CLI client's help masthead via `ascii.txt`; the
README via `word-10-perch`'s geometry SVG rather than as `<pre>`.

## Strengths

- Designed letterforms: consistent 1-cell letter gap, serifed `I`, rounded
  `G`/`U`, true diagonal; nothing in the figlet corpus looks like this.
- Readable at 12 px DejaVu Sans Mono and 13 px JetBrains Mono on both grounds
  at DPR 1 (preview, 1:1 rows); 33 columns fits any phone.
- Passes `asciicheck.py` without a cursor device; identical on both grounds.
- The three charsets degrade gracefully: the square twin renders completely
  in Liberation Mono (preview), the ASCII twin everywhere.

## Weaknesses

- `wordmark.txt` depends on `╭ ╮ ╰ ╯` (U+256D–2570), `╲` (U+2572) and `╶`
  (U+2576), which **Liberation Mono does not have** (the preview's Liberation
  row shows tofu at every corner and diagonal); it is a mark for fonts that
  have them, which is why `square.txt` exists.
- JetBrains Mono draws `╲` with small notches at the cell joins (font-glyph
  row of the preview); geometry renderers (kitty, VTE, Windows Terminal,
  xterm.js, and the apps if they draw box glyphs themselves) draw it clean.
  The square twin's `\` is shorter than its cell in every font, so its
  diagonal is always dashed.
- GitHub `<pre>` (line-height 1.45) opens gaps in every vertical and breaks
  the diagonal (preview); use the ASCII twin or an SVG there.
- Hairlines are the lightest thing on a dark phone screen; at 11 px they are
  one device pixel wide at DPR 1 and still read, but they do not carry weight.
- The `G`'s inner bar (`╶─┐` / `──┐` / `--.`) is a compromise; a 5-row
  hairline `G` cannot have a proper spur.

## Relation to the current identity

The opposite weight of today's banner. Shares nothing with ANSI Shadow but
everything with the "quiet, warm, not-a-terminal-emulator" intent in
Theme.kt. The ASCII twin replaces nothing in the client yet; it is what the
`huginn --help` masthead would become.

## Revision

Added `square.txt` (Liberation-Mono-safe) and `ascii.txt` (7-bit), both the
same 33 × 5 drawing. The weaknesses now state the measured facts (readable at
11–13 px DPR 1; fails in fonts lacking U+256D–2576, Liberation Mono named)
instead of "thin strokes disappear on a phone". Charset lines added; preview
rebuilt with all three files, including Liberation Mono and GitHub rows.
