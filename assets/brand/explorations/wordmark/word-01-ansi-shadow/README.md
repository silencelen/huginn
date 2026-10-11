# word-01-ansi-shadow — the baseline (figlet `ansi_shadow`, `HUGINN`)

**Width:** 49 columns × 6 rows. **Charset:** Unicode (block elements U+2588 and
double-line box drawing U+2550–255D); **not eligible for `client/huginn.sh` /
`huginn.ps1`**, which are 7-bit only. No 7-bit twin: the ASCII marks are
`word-07-cyberlarge` and `word-09-hairline/ascii.txt`. **Files:** `wordmark.txt`, `preview.png`.

## Concept

Today's README banner, kept as the control: block capitals (`█`) with a
double-line box-drawing drop shadow (`╗ ║ ╝`). It is a stock figlet font,
which is exactly the brief's complaint, but it is also heavy, legible and
the one every developer has seen, so every other variation here is judged
against it.

## Intended uses

README hero (today); server-side banners (`huginn-status`, the attach banner
printed by the host), the installer's console. Not the CLI client files.

## Strengths

- Heaviest and most legible mark in the set; reads at 12 px on both grounds
  in every face in `preview.png`, including Liberation Mono (every glyph it
  uses exists there).
- Passes `asciicheck.py` natively (the final `N` closes every row).
- Fits Termux at 50 columns with one to spare.

## Weaknesses

- Generic: this is the default banner of ten thousand projects.
- The shadow is decoration the brand does not need; it fights the flat,
  monochrome-first rule.
- Block art shows horizontal seams in GitHub's `<pre>` (line-height 1.45;
  see the "where it breaks" row of the preview). Terminals that draw the
  font's own block glyphs (xterm, Termux) show faint vertical seams in
  DejaVu Sans Mono at 12 px; geometry renderers (kitty, VTE, Windows
  Terminal, xterm.js) draw it tight.
- No lowercase, no raven, no relationship to the drawn mark.

## Relation to the current identity

It *is* the current identity's wordmark. Kept so the owner can see what the
others gain and lose.

## Revision

Added the charset line and dropped the "CLI splash on a 50-column phone" use
(the client is 7-bit; a 50-column *server-side* banner still fits). The
preview is new: FreeType cell-grid renders (integer cells, no fractional
advance seams), real terminal-scale rows at 12 px DejaVu Sans Mono and 13 px
JetBrains Mono captured in xterm.js 5.5 (the VS Code terminal engine, canvas
renderer) at 1:1 and 3×, the font-glyph path (what xterm/Termux draw), a
Liberation Mono row and a GitHub `<pre>` row. The old 21 px Chromium
`<pre>` panels, with their fractional-advance seams and the mislabelled
"line-height 1.0" row, are gone.
