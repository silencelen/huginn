# type-01-schibsted — Schibsted Grotesk + JetBrains Mono

**Files:** `specimen.html` (self-contained, Google Fonts `<link>` only), `preview.png` (full-page render at 1200 px).

## Concept

A neo-grotesk with a Nordic passport. Schibsted Grotesk was drawn for the
Norwegian media group Schibsted; it is a working newspaper sans with just
enough ink-trap character (the double-storey `g`, the slightly narrow `n`,
the flat-topped `t`) to not be generic. Set at 800 with -3.5 % tracking,
`Huginn` becomes a compact, confident slab of a word that the raven can
perch on. The whole product then speaks one voice: UI, display, tagline
(italic 400) and wordmark are all the same family, and JetBrains Mono does
the terminal, code and key/value rows. This is the quiet, professional option: the
raven carries the distinctiveness, the type carries the quality.

## Fonts

| Role | Family | Weights used | Licence |
|---|---|---|---|
| Wordmark, display, title, headline, body, label | Schibsted Grotesk (variable 400–900 + italics) | 400, 600, 700, 800 | SIL OFL 1.1 |
| Terminal, code, key/value rows | JetBrains Mono | 400, 500, 700 | SIL OFL 1.1 |

Both are on Google Fonts and on GitHub as static TTFs, so the same files
can be bundled in Compose Multiplatform, inlined into the README SVG and
loaded on the web.

## Intended uses

- The wordmark everywhere a vector wordmark is needed: README hero (as SVG
  with the raven), docs site header, installer wizard bitmap, About screen,
  social cards.
- The UI type system for the Android and Compose desktop apps.
- Not for terminal splashes (those stay ASCII, see the `word-*` variations).

## Material 3 mapping (see `../type-system.md` for the full table)

displaySmall 44/52 · 800 → headlineMedium 28/36 · 700 → titleLarge 22/28 · 600 →
bodyLarge 16/24 · 400 → labelMedium 13/16 · 600, +6 % tracking → mono 13/18
(desktop) / 11/15 (phone) in JetBrains Mono 400.

In the terminal the sans does not exist: the CLI uses the ASCII wordmarks
and the user's own monospace; JetBrains Mono is merely the recommended one.

## Strengths

- Holds up at 24 px (notification, tray tooltip): the 800 weight keeps the
  word solid where the serif direction thins out.
- One family for everything means one set of TTFs (about 5 static files)
  and no pairing decisions in the UI.
- The "tittle" lockup (raven as the dot of the i, dotless `ı` underneath) works
  at 56 px on both grounds without special-casing; the bird sits on a post.
- Reads as a modern developer product without quoting any other brand's
  typeface (Geist is Vercel's, Inter is everyone's).

## Weaknesses

- A grotesk is the safe choice; nothing about the letterforms says "raven".
  The story lives in the lockups and the mark, not in the type.
- The Nordic provenance is a story the owner tells, not something a user
  sees.
- At 144 px the tight tracking closes the `g`–`i` gap; if the wordmark goes
  larger than 160 px, open tracking to -2.5 %.
- Variable-font axes are not reliable on Compose desktop (Skia); bundle the
  static instances (Regular, SemiBold, Bold, ExtraBold, Italic) instead.

## Relation to the current identity

Keeps the canonical `raven.svg` untouched and uses it in all three lockups.
Palette is Theme.kt verbatim (dark primary, light styled); every text colour
on the page passes AA (muted `#8A8177` on `#12100F` is 4.96:1, the tightest
pair). Replaces the stock figlet wordmark in the README with a drawn one and
replaces Material's default Roboto with a bundled family.
