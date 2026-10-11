# Wordmark and type system — explorations

How "Huginn" is set: in terminals (Part A, ASCII wordmarks) and on screens
(Part B, typographic wordmark + a type system for the apps, docs and
installer). Every variation is a folder with its files, a `README.md` and a
`preview.png`. Open the preview first; the README says what it is bad at.

The recommendation lives in [`type-system.md`](type-system.md).

## Part A — ASCII wordmarks (README, CLI splash, installer)

Surveyed every installed pyfiglet font (571 fonts × `HUGINN`/`huginn`,
991 renders that fit 60 columns with terminal-safe glyphs, 798 unique)
on contact sheets, then kept eight and drew two by hand. All pass
`tools/asciicheck.py`; widths are in each README. Previews show each mark
on the dark (`#12100F`/`#E8E2DA`) and light (`#FAF7F2`/`#1B1817`) grounds in
two monospace faces: DejaVu Sans Mono (the stated fallback) and the desktop
build of JetBrains Mono at line-height 1.0 (how a terminal cell looks).

| Variation | One line | Cols × rows | Preview |
|---|---|---|---|
| [`word-01-ansi-shadow`](word-01-ansi-shadow/) | the baseline: today's block capitals with a box-drawing shadow | 49 × 6 | [preview](word-01-ansi-shadow/preview.png) |
| [`word-02-mono9-caps`](word-02-mono9-caps/) | lighter block capitals, flat, a real diagonal `N` | 42 × 5 | [preview](word-02-mono9-caps/preview.png) |
| [`word-03-mono9-lower`](word-03-mono9-lower/) | lowercase block `huginn` with a full-height block cursor | 45 × 7 | [preview](word-03-mono9-lower/preview.png) |
| [`word-04-future`](word-04-future/) | 3-row heavy box-drawing narrow mark for Termux | 16 × 3 | [preview](word-04-future/preview.png) |
| [`word-05-calvin`](word-05-calvin/) | 3-row double-line (engraved) narrow mark | 16 × 3 | [preview](word-05-calvin/preview.png) |
| [`word-06-pagga`](word-06-pagga/) | 3-row half-block narrow mark, font-independent | 24 × 3 | [preview](word-06-pagga/preview.png) |
| [`word-07-cyberlarge`](word-07-cyberlarge/) | slim pure-ASCII capitals, seam-free on GitHub | 44 × 3 | [preview](word-07-cyberlarge/preview.png) |
| [`word-08-slant-lower`](word-08-slant-lower/) | the italic: slanted lowercase with an upright cursor | 36 × 6 | [preview](word-08-slant-lower/preview.png) |
| [`word-09-hairline`](word-09-hairline/) | **hand-drawn** thin box-drawing capitals with a true diagonal `N` | 33 × 5 | [preview](word-09-hairline/preview.png) |
| [`word-10-perch`](word-10-perch/) | **hand-drawn**: the pixel raven stands in for the `I` of the hairline mark | 41 × 5 | [preview](word-10-perch/preview.png) |

Two facts that apply to all of them:

- **The flush-right rule.** `asciicheck.py` requires equal line lengths and
  no trailing spaces, so every row must end in ink at the same column. Caps
  ending in `N` do this naturally; lowercase (`h` ascender, `g` descender)
  and italics cannot, which is why `word-03` and `word-08` carry a
  full-height block cursor. It became the brand device for lowercase marks.
- **Where box and block art renders tight.** Terminals draw U+2500–259F
  glyphs to the cell edge (most desktop terminals draw them natively);
  GitHub's `<pre>` uses line-height 1.45 and shows horizontal seams in block
  art and gaps in hairlines. For the README, either use the pure-ASCII marks
  (`word-07`, `word-08` minus its cursor) or render the chosen wordmark to
  SVG/PNG. Google Fonts' web subset of JetBrains Mono omits U+2500–25FF
  altogether; the desktop build has box drawing and block elements in full,
  43 of 96 geometric shapes (`▶ ▸ ◆ ■ ● ◇` yes; the quadrant triangles `◢ ◣ ◤ ◥` no), no braille and no runes.

Regenerating the stock marks: `pyfiglet.figlet_format(word, font=<font>)`,
rows right-stripped; `word-06` has `░` replaced by spaces; `word-03`/`word-08`
append the cursor column. The two hand-drawn marks are the `.txt` files.

## Part B — typographic wordmark and type system (apps, docs, social, installer)

Three directions, each a single self-contained `specimen.html` (Google Fonts
`<link>`, no other dependencies) rendered to `preview.png` at 1200 px:
wordmark at 144/64/24 px, three lockups with the canonical `raven.svg`
inlined (left, above, and the raven as the tittle of the `i`), the tagline,
and the UI scale (display 44, title 28, headline 22, body 16, label 13, mono
13) as a settings-screen mock on the dark and the light palette. All
families are OFL and on Google Fonts, so they bundle in Compose as TTF.

| Variation | One line | Families | Preview |
|---|---|---|---|
| [`type-01-schibsted`](type-01-schibsted/) | neo-grotesk with a Nordic passport; one family for everything | Schibsted Grotesk · JetBrains Mono | [preview](type-01-schibsted/preview.png) |
| [`type-02-plex-mono`](type-02-plex-mono/) | mono-led: `huginn` typed, the raven as the cursor | IBM Plex Mono · IBM Plex Sans | [preview](type-02-plex-mono/preview.png) |
| [`type-03-instrument`](type-03-instrument/) | display serif with a carved edge; sans UI, mono code | Instrument Serif · Instrument Sans · JetBrains Mono | [preview](type-03-instrument/preview.png) |

Every text colour in the specimens is a Theme.kt token and passes WCAG AA
(`tools/contrast.py`): the tightest pairs are muted `#8A8177` on `#12100F`
(4.96:1) and light primary `#7A5A16` on `#FAF7F2` (5.95:1).

## Verdict

Direction 1 (Schibsted Grotesk + JetBrains Mono) for the product, with
`word-10-perch` as the terminal signature and `word-04-future` for narrow
phones; the reasoning is in [`type-system.md`](type-system.md).
