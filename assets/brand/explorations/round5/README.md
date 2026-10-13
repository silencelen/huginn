# Round 5 — the raven in type

Mark (`../round3/fold-system/`) and colour (`../color/color-04-moss-heather/`)
are decided. Round 5 asked the three questions left on the decision checklist
that the brand book cannot be written without: the type direction, the terminal
letterform, and the one-row raven. The chooser "The Raven in Type" set each
option live on the Moss & Heather ground with the decided bird.

| question | options | recommendation |
|---|---|---|
| Type direction | `type-01-schibsted` (Schibsted Grotesk + JetBrains Mono) · `type-02-plex-mono` (Plex Mono-led) · `type-03-instrument` (Instrument Serif + Sans) | 01, the type study's verdict: holds from 11 to 144, one family for the UI, outlined SVGs exist; keep 02's raven-as-cursor as a lockup in any direction |
| Terminal letterform | the hairline (`word-10-perch` with the raven re-cut, `word-09` where no bird fits) · the block family (`word-02` / `word-03` / `word-06`) · keep `word-01-ansi-shadow` | the hairline: the only drawn letterform, the only one with the raven in it, three charsets without changing its drawing |
| One-row raven | `▗▆█▀` (ascii-03's drawn wedge, block fallback form) · `▄███▀` (the decided mark's own grid at 6 by 2) · `(o>` (word-11, 7-bit everywhere) | the grid row: it is the logo, block elements only, and it has a 7-bit twin from the same cells |

## New artefacts in this folder

The hairline's bird and the one-row glyph are re-cut from the decided mark so
"the logo is the terminal art" holds on these surfaces too (both rasterised by
`../round3/fold-system/build.py`'s `coverage()` on the mark's own box, >= 50 %
cell coverage, no cell between 42 and 58 %):

| file | what |
|---|---|
| `raven-5row.txt`, `raven-5row-ascii.txt` | the upright fold raven at 9 columns by 10 half-rows (five text rows), half-block and 7-bit, framed |
| `perch-upright.txt`, `-square.txt`, `-ascii.txt` | `word-10-perch` with that bird as the `I`: rounded hairline, square twin, 7-bit twin |
| `raven-1row.txt`, `raven-1row-ascii.txt` | the mark at 6 columns by 2 half-rows: `▄███▀`, 7-bit `_###"` (not chosen; kept for the record) |

All pass `../tools/asciicheck.py`.

## Decided (round-5 chooser, 2026-10-13)

| question | pick |
|---|---|
| Type direction | `type-02-plex-mono`: IBM Plex Mono (wordmark `huginn` lowercase, display, labels, terminal, code) + IBM Plex Sans (titles, body); the raven stands where the cursor would be |
| Terminal letterform | the hairline: `perch-upright.txt` (rounded), `-square.txt`, `-ascii.txt`; `word-09-hairline` where no bird fits; `word-06-pagga` under 34 columns |
| One-row raven | `(o>` (`word-11-oneline`) with `(!>` needs-you and `(->` waiting, 7-bit everywhere |

The recommendation was 01 / hairline / the grid row; the owner chose the
terminal-native bundle. Consequences to carry into the brand book:

- Plex Mono has one geometric-shapes glyph, so `●` `▲` `○` `■` `▸` must not
  appear in terminal art or in the apps' own terminal view; the apps draw dots
  as shapes (they already do), tab titles and `huginn-claude-title` run in the
  user's terminal font, and the one-row `(o>` carries its state in ASCII, which
  is exactly why it was chosen.
- The hairline's `╭ ╮ ╰ ╯ ╲` are all in Plex Mono, so the perch renders inside
  the app; the square twin stays for Liberation Mono hosts.
- `wordmark.svg` / `lockup.svg` are to be cut from Plex Mono SemiBold with the
  decided raven as the cursor (type-01's fontTools method), and the README,
  installer and About box use those outlines.
