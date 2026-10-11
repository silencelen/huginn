# word-04-future — figlet `future`, `HUGINN`, hand-fixed `N`, the 3-row narrow mark

**Width:** 16 columns × 3 rows. **Charset:** Unicode heavy box drawing
(`┏ ┓ ┗ ┛ ━ ┃ ┣ ┫`, half strokes `╻ ╹ ╸ ╺`, U+250F–257B) plus the light diagonal
`╲` U+2572; **not eligible for the CLI client files**, and no 7-bit twin
exists at this width (below 34 columns the client prints plain `huginn`).
**Files:** `wordmark.txt`, `preview.png`.

```
╻ ╻╻ ╻┏━╸╻╻ ╻╻ ╻
┣━┫┃ ┃┃╺┓┃┃╲┃┃╲┃
╹ ╹┗━┛┗━┛╹╹ ╹╹ ╹
```

## Concept

Heavy box-drawing strokes in a 3-row cap height. At 16 columns it is the
narrowest mark in the set, for places where 40 columns is all there is. The
heavy line weight keeps it visible at phone font sizes where thin box drawing
turns to dust. The two `N`s are no longer figlet's: they are heavy stems with
a light diagonal between them (`╻ ╻ / ┃╲┃ / ╹ ╹`).

## Intended uses

Server-side output viewed on a narrow phone terminal (Termux after attach),
`huginn-status` on a phone, log headers. (The tmux status bar is one line;
see `word-11-oneline`.)

## Strengths

- 16 columns: fits anywhere, including a 40-column session with room for a
  prompt beside it.
- Reads `HUGINN` at 12 px DejaVu Sans Mono and 13 px JetBrains Mono, dark
  and light (preview); the previous `N` read as `4`.
- Passes `asciicheck.py`.

## Weaknesses

- Heavy box glyphs and `╲` are absent from **Liberation Mono** (the preview's
  Liberation row is all tofu), so the mark cannot be shown on a Linux desktop
  that falls through JetBrains Mono → DejaVu to Liberation, and GitHub's
  `<pre>` only shows it because the browser substitutes another font.
- The diagonal is thin between heavy stems: correct, but the lightest stroke
  in the mark. In JetBrains Mono the `╲` leaves small notches at the cell
  joins (font-glyph row of the preview).
- No lowercase; no 7-bit twin.

## Relation to the current identity

New: the brand has never had a narrow mark. `word-06-pagga` is the narrow
mark that works in every font; this one is the better-drawn of the two where
heavy box glyphs exist.

## Revision

Blocking defect fixed: the `N` was `┏┓╻ / ┃┗┫ / ╹ ╹` and the word read
`HUGI44` at terminal sizes; it is now `╻ ╻ / ┃╲┃ / ╹ ╹` (`\` was also tried
and reads, but is shorter than the cell in every font). Removed the tmux
status-left use; named Liberation Mono as a known failure; charset line
added; preview rebuilt. The verdict in `type-system.md` now names
`word-06-pagga` as the sub-34-column mark and this one as the alternative
where heavy glyphs exist.
