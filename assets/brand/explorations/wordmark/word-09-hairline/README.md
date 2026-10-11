# word-09-hairline — hand-drawn thin box-drawing `HUGINN`

**Width:** 33 columns × 5 rows. **Files:** `wordmark.txt`, `preview.png`.

## Concept

Custom-drawn, not a figlet font. Light box-drawing lines (`│ ─ ├ ┤ ╭ ╮ ╰ ╯`)
form tall, narrow capitals on a 5-row cap height; the `N` uses the light
diagonal `╲` (U+2572) for a true diagonal, the `I` carries small bars so it
does not read as a pipe, and the `G` and `U` have rounded corners. The result
is a hairline wordmark, closer to a wire sculpture than a banner, and the
terminal counterpart of the thin, tall type in type-03.

```
│  │ │  │ ╭───╮ ─┬─ │╲   │ │╲   │
│  │ │  │ │      │  │ ╲  │ │ ╲  │
├──┤ │  │ │ ╶─┐  │  │  ╲ │ │  ╲ │
│  │ │  │ │   │  │  │   ╲│ │   ╲│
│  │ ╰──╯ ╰───╯ ─┴─ │    │ │    │
```

## Intended uses

CLI splash on desktop terminals (where box drawing is drawn natively and
tight), installer banner, the dark-ground README hero if the README is
rendered to SVG rather than left as `<pre>`.

## Strengths

- Elegant and unmistakably designed; nothing else in the figlet corpus looks
  like this.
- 33 columns, 5 rows: fits a phone with room to spare.
- Passes `asciicheck.py` (every row ends on the last `N`'s stem).
- Works on dark and light grounds identically; one weight, no shading.

## Weaknesses

- Fragile: hairlines need a font that draws box glyphs to the cell edge.
  Desktop terminals (Windows Terminal, VTE, Kitty, WezTerm, Ghostty, iTerm2)
  draw them natively and tight; GitHub's `<pre>` does not, so the verticals
  show gaps at line-height 1.45 and the diagonal breaks into dashes.
- The `╲` diagonal is drawn corner-to-corner by DejaVu Sans Mono but not by
  every font (JetBrains Mono's leaves small notches at the cell joins, see
  the second preview row).
- Thin strokes disappear on a phone at 11 px.
- The `G`'s inner bar (`╶─┐`) is a compromise; a 5-row hairline `G` cannot
  have a proper spur.

## Relation to the current identity

The opposite weight of today's banner. Shares nothing with ANSI Shadow but
everything with the "quiet, warm, not-a-terminal-emulator" intent in
Theme.kt. Base for `word-10-perch`.
