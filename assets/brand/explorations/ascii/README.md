# ascii — the terminal face of the Huginn raven

Terminal art of the canonical mark (`assets/brand/raven.svg`) at every size a
terminal will show it, plus the generator that makes it from any mark, plus the
7-bit ASCII twins the CLI client files need. Everything here is text: single-width
characters only (ASCII, box drawing, block elements, geometric shapes, braille),
equal line widths per block, no emoji, no colour baked in. The owner will re-run
`regen.sh` on the chosen new mark; the hand tuning is kept as editable `#`-grids so
it can be ported.

## Variations

| id | one line | look at |
|---|---|---|
| [ascii-01-portrait-large](ascii-01-portrait-large/) | the raven at 48 columns in half-block and braille (hand-tuned); an 80-column shaded poster in `extras/` | `ascii-01-portrait-large/preview.png` |
| [ascii-02-portrait-medium](ascii-02-portrait-medium/) | the raven at 24×8, the splash / README size; half-block and braille both hand-tuned | `ascii-02-portrait-medium/preview.png`, then `preview-github.png` |
| [ascii-03-compact](ascii-03-compact/) | hand-drawn 1-, 2-, 3- and 5-line ravens plus the ASCII ravens for the client files; `◢▆█▀` is the Unicode glyph, `(o)=-` the ASCII one | `ascii-03-compact/preview.png`, then `preview-zoom.png` |
| [ascii-04-lockups](ascii-04-lockups/) | raven + figlet HUGINN in four faces, the first line of the CLI (Unicode and ASCII), the `--help` header, a tmux status-left segment | `ascii-04-lockups/preview.png`, then `preview-mocks.png` |
| [ascii-05-runes](ascii-05-runes/) | can `ᚨ`/`ᚺ` be a bullet or separator? No. Plus the state glyph table that replaces the emoji tab-title icons | `ascii-05-runes/preview.png` |

## Unicode or ASCII: who prints what

`client/huginn.sh` and `client/huginn.ps1` must stay 7-bit ASCII (CONTRIBUTING.md:
`huginn update` ships the file over scp without a BOM and PowerShell 5.1 then
fails to parse any non-ASCII byte). Everything else may print Unicode. Every
file in this folder therefore belongs to one of two families, and each README
says which:

| family | files | printed by |
|---|---|---|
| **ASCII** (7-bit, `/\_()=~-\|<>.` etc.) | `03/raven-*ascii*.txt`, `04/banner-ascii.txt`, `04/help-header-ascii.txt`, `04/lockup-e-ascii.txt`, the `ascii` column of `05/example-e.txt` | `client/huginn.sh`, `client/huginn.ps1` (help screen, first line, status glyphs); also fine anywhere else |
| **Unicode** (blocks, braille, geometric shapes, box drawing) | everything else | the server after attach (`server/bin/cc`, `huginn-status` already prints Unicode), `huginn-claude-title` (tab title), `server/tmux.conf` (status bar), the installer's console page, README and docs, the apps' terminal view |

`check.py` asserts that every `*ascii*` file is 7-bit, so a Unicode glyph cannot
creep into a client-bound file by accident.

## Recommended set

- **Client files (ASCII):** `03/raven-1line-ascii.txt` `(o)=-` as the glyph;
  `04/help-header-ascii.txt` as the `huginn --help` top (a drop-in for the
  heredoc at `huginn.sh:988`); `04/banner-ascii.txt` if the client prints a line
  before ssh; `* ! o -` from `05/example-e.txt` when the PowerShell client
  prints state.
- **Server side (Unicode):** `03/raven-1line-a.txt` `◢▆█▀` in the tmux
  status-left and as the first line after attach (`04/banner.txt`);
  `04/lockup-a-ansi-shadow.txt` as the splash on an 80-column terminal;
  `02/raven-24-half.txt` on the installer's console; `● ▲ ○ ·` from
  `05/example-e.txt` for the tab title.
- **README / docs:** `04/lockup-d-readme-hero.txt` in a code block;
  `02/raven-24-braille.txt` or `01/raven-48-braille.txt` where the line-height
  is 1.45 (GitHub), because block art stripes there (`02/preview-github.png`).

## Which style for which surface

| style | cells | needs from the font | use it for | avoid it for |
|---|---|---|---|---|
| half-block `▀▄█` | 1×2 px | the three block glyphs, which every monospace font has; kitty, WezTerm, Alacritty, foot and Windows Terminal draw them themselves. Files are padded with plain spaces, so nothing else is needed | server-side splash, installer console (conhost + Consolas included), tmux, Termux, anything whose font is unknown | GitHub code blocks (1.45 line-height puts stripes between rows); the client files |
| braille `⠁..⣿` | 2×4 dots | the Braille Patterns block, or a good fallback; files are padded with U+2800 | README and docs where the font is known, Termux, Windows Terminal with Cascadia, kitty/WezTerm (they draw braille themselves) | tmux status bars, the installer console, any terminal using Consolas or DejaVu Sans Mono without a configured fallback; the client files |
| quarter-block `▘▝▖▗▚▞▌▐` | 2×2 | the quadrant glyphs (in DejaVu Sans Mono, hence Menlo and Hack; in Cascadia; drawn by the terminals above; check others) | the 2-line compact raven, finer than half-block | large sizes (no gain over half-block; 01 keeps it in `gen/` only) |
| shaded `░▒▓█` | 1×1 | the shade glyphs, whose density differs per font | an 80-column poster, transition frames, the dither source for animation (`01/extras/`) | the mark itself under ~64 columns: the edge turns to noise |
| glyph `◢▆█▀` | 1 line | `◢` U+25E2 plus three block elements; fallback `▗▆█▀` | status-left, the server's first line, TUI headers. A mark-derived monogram, not a picture of a raven | fonts without Geometric Shapes; the client files |
| ASCII `(o)=-` and the 3/4-line ravens | 1–4 lines | nothing | the client files' help screen and first line; anywhere at all | nothing; it is the lowest common denominator by design |

Why braille is risky, concretely: braille dots come from the *font*, and most
monospace fonts do not have them. Windows Terminal with Cascadia Code/Mono: fine
(Cascadia has the block). Windows Terminal or conhost with Consolas: Consolas has
no braille, DirectWrite falls back to Segoe UI Symbol with different dot size
and weight, and the installer's console is exactly that case. Termux default:
fine (Android's Noto Sans Symbols 2 fallback). macOS Terminal/iTerm2 with Menlo
or SF Mono: fine via the system's Apple Braille font, slightly heavier dots.
Linux with DejaVu Sans Mono: no braille in the mono face (verified here with
`fc-list ':charset=2800'`: DejaVu Sans, FreeMono and Unifont have it, DejaVu
Sans Mono does not); fontconfig falls back to DejaVu Sans or Noto Sans Symbols 2,
so the art is right but the weight varies per machine. kitty and WezTerm render
braille themselves and are consistent. Fonts that include braille: Cascadia
Code/Mono, Iosevka, Unifont. Fonts that do not: Consolas, DejaVu Sans Mono, and
its derivatives Menlo and Hack. JetBrains Mono, Fira Code and Noto Sans Mono:
coverage has changed between releases; check with `fc-list ':charset=2800'
family` before relying on it.

## Terminal truths the previews encode

- Every `preview.png` is rendered at 10×18 px cells (a 16.6px DejaVu Sans Mono,
  i.e. the 16px terminal cell the legibility criterion is defined at);
  `preview-zoom.png` in 03 is 20×36. The cell width is even on purpose: see the
  next point.
- `preview.py` draws block elements as cell-sized CSS boxes, the way kitty,
  WezTerm, Alacritty, foot and Windows Terminal do. Half blocks are one box
  covering 100%×50% of the cell (an earlier version composed them from two
  quadrants, which met on a half pixel at 9px cells and drew a hairline; fixed,
  and `preview.py` warns on odd widths). Font-drawn blocks (xterm, GitHub, most
  editors) are a different renderer: `02/preview-fontdrawn.png` shows DejaVu's
  own block glyphs at 10×18 (clean here, seams in some fonts) and
  `02/preview-github.png` shows line-height 1.45, where block art stripes and
  braille survives.
- Braille and runes use Noto Sans Symbols 2 / Noto Sans Runic from Google
  Fonts in a one-cell box, the fallback a Linux desktop or Android shows. Your
  terminal's fallback may be heavier or lighter.
- **Padding.** Block-element files and ASCII files are padded to a rectangle
  with plain spaces, so a "font-proof" file really needs nothing but its
  glyphs. Braille files are padded with U+2800 (braille blank), which braille
  art needs for its interior anyway. `tools/asciicheck.py` forbids trailing
  spaces, so it flags every block file as written; `check.py` runs it on a
  copy with the padding mapped to U+2800 so the ragged / wide / odd-character
  rules still apply, and reports the file as "space padded". The tool should
  gain an exemption for block art; until then this is the documented exception.
  `svg2ascii.py --pad '⠀'` produces U+2800-padded twins if a consumer wants
  them.
- `check.py` also prints every file inside a detached tmux (UTF-8, 120 columns)
  and captures the pane back: each row must come out as it went in, which
  catches wide cells, wrapping and anything tmux would swallow. It is a
  terminal-layer check, not a font check: nothing here was looked at in a real
  terminal emulator on a real device.

## Regenerate and check

```
cd assets/brand/explorations/ascii
./regen.sh ../../raven.svg      # 1. sample the mark into */gen/  2. pack src/ grids, pad hand-written files
                                # 3. lockups + banners  4. previews  5. check.py
./regen.sh --pack               # skip the sampling; re-pack, recompose, re-render, check
python3 check.py */*.txt        # just the checks
```

Requirements: node + global playwright (for `../tools/render.mjs`), Python 3 with
PIL and pyfiglet, ImageMagick, `fc-list`, tmux. Google Fonts is fetched for the
braille and rune previews; without network the preview falls back to DejaVu Sans
and Unifont (alignment unchanged, dots heavier).

### The generator

```
./svg2ascii.py <mark.svg|mark.png|grid.grid> --style half|braille|quarter|shaded --width N
      [--rows N] [--threshold 0.5] [--aspect 0.5] [--valign bottom] [--margin PX]
      [--clean] [--fill] [--grid] [--pad CHAR] [--blank CHAR] [--out FILE]
```

SVG → `tools/render.mjs` (Chromium, transparent, 1024 px) → PIL alpha as coverage
→ trim to the ink → resample to the style's sub-pixel grid at `--width` columns,
correcting for the cell aspect → threshold → pack into glyphs. `--grid` writes the
sub-pixel grid as `#`/`.` text instead; a `.grid` file fed back in is packed
without resampling. That is the hand-tuning loop:

```
./svg2ascii.py mark.svg --style half --width 24 --grid --out gen/half.grid
cp gen/half.grid src/half-24.grid        # edit: fix the bill, keep the eye
./svg2ascii.py src/half-24.grid --style half --out raven-24-half.txt
```

`--clean` removes isolated sub-pixels; `--fill` closes pinholes (not by default:
the eye is a pinhole). PNG input works too (alpha, or dark-on-light; `--invert`
for light-on-dark). `lockup.py` composes a raven file with a pyfiglet face
(`--gap` applies to `--stack` too now); `preview.py` renders any `.txt` files to
one PNG on both grounds (`--font-blocks` for the font-drawn look).

### When the mark changes

Run `regen.sh new-mark.svg`, look at `*/gen/*.txt`, diff `gen/*.grid` against
`src/*.grid`, carry over the tuning that still applies (eye hole, bill taper,
crumbs), run `regen.sh --pack`, look at the previews. The compact set in 03 is
hand-drawn from the silhouette, not sampled: `03/gen/` shows what the pipeline
gets unaided at 2/3/5 lines on the new mark as a starting point, and the grids in
`03/src/` will need redrawing if the new mark's posture differs. The ASCII ravens
are typed, not generated; redraw them by eye against the new mark.

## Honest limits

- The 1-line `◢▆█▀` is a monogram derived from the mark's silhouette; it is
  distinctive as a prefix, and it is not a picture of a raven. `(o)=-` is an eye
  and a bill: recognisable as the lineage of today's help raven, no more.
- The 2-line half-block now reads as a bird (eye, bill, tail) but it is the
  smallest thing here that does; below 3 lines the raven is a suggestion.
- Figlet HUGINN is a stock face; the lockups are only as designed as it is.
- Shaded is a poster at 80 columns and noise below; it is not a candidate.
- Runes: no.
- Everything was looked at in the Chromium renders (`preview*.png`) and round-
  tripped through tmux; nothing was looked at in a real terminal emulator on a
  real device, and font-fallback claims above are from coverage tables (DejaVu
  Sans Mono verified locally), not from each device.

## Revision

After the creative director's review: every CLI-facing artefact now has a 7-bit
ASCII twin and every README states Unicode/ASCII and who prints it; block files
are padded with spaces instead of U+2800 (the "needs nothing but `▀▄█`" claim is
now true); `preview.py`'s half-block seam bug is fixed and every preview is
re-rendered at 10×18; the compact 3- and 5-line ravens were redrawn with 6–7 px
tapered bills and the 2-line half-block with an eye; 01 lost its duplicate
(quarter) and weak (shaded) candidates; 02's braille was hand-tuned; the tmux
snippet puts the quiet segment first and gold under a state condition; the
tagline is "Thought, on call from anywhere." everywhere; 05 gained the state
glyph table; `check.py` adds the 7-bit assertion and the tmux round trip.
