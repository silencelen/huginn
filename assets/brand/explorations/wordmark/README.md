# Wordmark and type system — explorations (revised)

How "Huginn" is set: in terminals (Part A, ASCII wordmarks) and on screens
(Part B, typographic wordmark + a type system for the apps, docs and
installer). Every variation is a folder with its files, a `README.md` and a
`preview.png`. Open the preview first; the README says what it is bad at.

The recommendation lives in [`type-system.md`](type-system.md).

## Part A — ASCII wordmarks (README, server-side banners, CLI client, installer)

Surveyed every installed pyfiglet font (571 fonts × `HUGINN`/`huginn`),
kept seven stock marks (two of them hand-fixed) and drew the hairline family
and the one-row glyph by hand. All text files pass `tools/asciicheck.py`.

**Charset, because it decides where a mark may live.** `client/huginn.sh`
and `client/huginn.ps1` are 7-bit only (CONTRIBUTING.md): a mark that goes
in the client needs a pure-ASCII file. Unicode marks are fine server-side
(`huginn-status`, the attach banner), in the apps, in the installer console
and in docs. Every README states its charset; CLI-facing marks ship an
`ascii.txt` twin.

| Variation | One line | Cols × rows | Charset | Preview |
|---|---|---|---|---|
| [`word-01-ansi-shadow`](word-01-ansi-shadow/) | the baseline: today's block capitals with a box-drawing shadow | 49 × 6 | Unicode | [preview](word-01-ansi-shadow/preview.png) |
| [`word-02-mono9-caps`](word-02-mono9-caps/) | lighter block capitals, flat, a real diagonal `N` | 40 × 5 | Unicode (`█▀▄`) | [preview](word-02-mono9-caps/preview.png) |
| [`word-03-mono9-lower`](word-03-mono9-lower/) | lowercase block `huginn` with the cursor | 43 × 7 | Unicode (`█▀▄`) | [preview](word-03-mono9-lower/preview.png) |
| [`word-04-future`](word-04-future/) | 3-row heavy box-drawing narrow mark, `N` hand-fixed | 16 × 3 | Unicode heavy box | [preview](word-04-future/preview.png) |
| [`word-06-pagga`](word-06-pagga/) | 3-row half-block narrow mark, works in every font | 23 × 3 | Unicode (`█▀`) | [preview](word-06-pagga/preview.png) |
| [`word-07-cyberlarge`](word-07-cyberlarge/) | slim ASCII capitals, hand-fixed, seam-free everywhere | 40 × 3 | **7-bit** | [preview](word-07-cyberlarge/preview.png) |
| [`word-08-slant-lower`](word-08-slant-lower/) | the italic: slanted lowercase with the cursor | 36 × 6 | Unicode + `ascii.txt` | [preview](word-08-slant-lower/preview.png) |
| [`word-09-hairline`](word-09-hairline/) | **hand-drawn** thin box-drawing capitals, true diagonal `N`; rounded, square and ASCII files | 33 × 5 | Unicode + `square.txt` + `ascii.txt` | [preview](word-09-hairline/preview.png) |
| [`word-10-perch`](word-10-perch/) | **hand-drawn**: the raven (rasterised from `raven.svg`) stands in for the `I`; three charsets plus a geometry SVG | 44 × 5 | Unicode + `square.txt` + `ascii.txt` + SVG | [preview](word-10-perch/preview.png) |
| [`word-11-oneline`](word-11-oneline/) | `(o>`: the one-row glyph for the tmux bar and tab titles, with state variants | 3 (10 with the name) | **7-bit** | [preview](word-11-oneline/preview.png) |

Dropped in this revision: `word-05-calvin` (double-line 3-row mark). It was
the third narrow mark for one slot, read worst of the three at 12 px, and
used the double-line glyph set the baseline's shadow is being retired for.

### What the previews show, and how they were made

Each `word-*/preview.png` has the same rows, for every file in the folder:

1. **Specimen**: a FreeType cell-grid render at 24 px DejaVu Sans Mono on the
   dark and the light ground. Every glyph is drawn into an integer cell
   (`round(advance) × (ascent+descent)`), the way a terminal lays text out,
   so there are no fractional-advance seams from a browser.
2. **At the size it ships**: captures from **xterm.js 5.5** (the terminal
   engine inside VS Code) with its canvas renderer, which draws box and
   block characters as geometry the way kitty, VTE and Windows Terminal do:
   DejaVu Sans Mono 12 px (Termux-class cell 7 × 14) and JetBrains Mono 13 px
   (desktop cell 7 × 17), at 1:1 and blown up 3× with hard pixels.
3. **Font-glyph path**: FreeType cells at the same sizes, which is what
   xterm, Termux and PuTTY draw (the font's own box glyphs: DejaVu's faint
   block seams and JetBrains Mono's `╲` notches are visible here and nowhere
   else).
4. **Where it breaks**: Liberation Mono 13 px cells (hollow box = glyph the
   font lacks; this is the last font in the desktop fallback chain), and
   GitHub's `<pre>` as Chromium renders it (Liberation Mono 13.6 px,
   line-height 1.45, light and dark).

No real GPU terminal was captured (no kitty/xterm binary in this
environment); xterm.js is a real terminal emulator and is labelled as such.

### Three facts that apply across the set

- **The cursor device.** `asciicheck.py` refuses trailing spaces and ragged
  blocks, so a lowercase figlet mark cannot ship without something in the
  last column of every row. The cursor exists to satisfy that linter; it also
  happens to be the product in one glyph. One spec: one column, full block
  height, `█` in Unicode files and `|` in ASCII files, after the mark's own
  letter gap (word-03, word-08).
- **Font coverage decides the Unicode marks.** Measured with fontTools:
  DejaVu Sans Mono has all box/block/geometric glyphs (no braille);
  JetBrains Mono 2.304 has box 128/128, blocks 32/32, geometric 43/96
  (`▶ ▸ ◆ ■ ● ◇` yes, quadrant triangles no), no braille, no runes; IBM Plex
  Mono has box 128/128, blocks 32/32, geometric 1/96 (`◊` only), no braille;
  Liberation Mono has box 40/128 (no `╭ ╮ ╰ ╯ ╲ ╶`, no heavy strokes),
  blocks 8/32 (`▀ ▄ █` yes), geometric 24/96. Google Fonts' web subset of
  JetBrains Mono omits U+2500–25FF altogether. Hence the `square.txt` twins
  (basic light box only) and the rule that terminal art uses `█ ▀ ▄` and the
  light box set, never `▶`.
- **GitHub `<pre>` is not a terminal.** Line-height 1.45 opens gaps in every
  box/block mark. For the README use the ASCII files or the geometry SVG in
  `word-10-perch`.

Regenerating the stock marks: `pyfiglet.figlet_format(word, font=<font>)`,
rows right-stripped and the common leading blank column removed; `word-02`
also drops figlet's extra column before `N`; `word-04`'s two `N`s and all of
`word-07` are hand-edited; `word-06` has `░` replaced by spaces; `word-03` /
`word-08` append the cursor column. The hand-drawn marks are the `.txt`
files; `word-10`'s bird comes from rasterising `raven.svg` onto a 14 × 10
half-block grid (the pipeline is described in its README).

## Part B — typographic wordmark and type system (apps, docs, social, installer)

Three directions, each a single self-contained `specimen.html` (Google Fonts
`<link>`, no other dependencies) rendered to `preview.png`: full page at
1200 px (wordmark at 144/64/24 px, lockups with the canonical `raven.svg`
inlined, the tagline, the M3 scale as a settings-screen mock on the dark and
the light palette), then DPR-3 crops of the phone mock and the lockup cards
at 1:1 device pixels. All families are OFL and on Google Fonts, so they
bundle in Compose as TTF.

| Variation | One line | Families | Preview |
|---|---|---|---|
| [`type-01-schibsted`](type-01-schibsted/) | neo-grotesk with a Nordic passport; one family for everything; **outlined SVG wordmark and lockup included** | Schibsted Grotesk · JetBrains Mono | [preview](type-01-schibsted/preview.png) |
| [`type-02-plex-mono`](type-02-plex-mono/) | mono-led: `huginn` typed, the raven as the cursor (tittle lockup measured and rejected) | IBM Plex Mono · IBM Plex Sans | [preview](type-02-plex-mono/preview.png) |
| [`type-03-instrument`](type-03-instrument/) | display serif with a carved edge; sans UI, mono code | Instrument Serif · Instrument Sans · JetBrains Mono | [preview](type-03-instrument/preview.png) |

Every text colour in the specimens is a Theme.kt token and passes WCAG AA
(`tools/contrast.py`): the tightest pairs are muted `#8A8177` on `#12100F`
(4.96:1) and light primary `#7A5A16` on `#FAF7F2` (5.95:1).

## Verdict

Direction 1 (Schibsted Grotesk + JetBrains Mono) for the product, with
**one** terminal letterform family, the hairline, in three charsets
(`word-09`/`word-10` rounded, square and ASCII), `word-11-oneline` on the
one-row surfaces, and `word-06-pagga` only where fewer than 34 columns
exist; the reasoning is in [`type-system.md`](type-system.md).
