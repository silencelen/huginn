# type-01-schibsted — Schibsted Grotesk + JetBrains Mono

**Files:** `specimen.html` (self-contained, Google Fonts `<link>` plus the four
SVGs beside it), `wordmark.svg` / `wordmark-dark.svg` (the word as outlines, ink
and bone), `lockup.svg` / `lockup-dark.svg` (canonical raven at cap height left
of the word), `preview.png` (full-page render at 1200 px, then DPR-3 crops of
the phone mock and the lockup cards at 1:1 device pixels).

## Concept

A neo-grotesk with a Nordic passport. Schibsted Grotesk was drawn for the
Norwegian media group Schibsted; it is a working newspaper sans with just
enough ink-trap character (the double-storey `g`, the slightly narrow `n`,
the flat-topped `t`) to not be generic. Set at 800 with -3.5 % tracking,
`Huginn` becomes a compact, confident slab of a word that the raven can
perch on. The whole product then speaks one voice: UI, display, tagline
(italic 400) and wordmark are all the same family, and JetBrains Mono does
the terminal, code and key/value rows. This is the quiet, professional
option: the raven carries the distinctiveness, the type carries the quality.

## Fonts

| Role | Family | Weights used | Static TTFs to bundle | Licence |
|---|---|---|---|---|
| Wordmark, display, title, headline, body, label | Schibsted Grotesk | 400, 600, 700, 800 + italic 400 | 5 | SIL OFL 1.1 |
| Terminal, code, key/value rows | JetBrains Mono | 400, 500 (never 700 inline) | 2 | SIL OFL 1.1 |

Seven files, about 0.7–0.8 MB, as `type-system.md` section 3 says. Both are
on Google Fonts and on GitHub as static TTFs, so the same files can be
bundled in Compose Multiplatform and loaded on the web; the README and
installer use the outlined SVGs and need no font at all.

## The outlined wordmark

`wordmark.svg` is `Huginn` in Schibsted Grotesk ExtraBold converted to a
path with fontTools (HarfBuzz shaping, kerning on, tracking -0.035 em), and
`lockup.svg` adds the untouched `raven.svg` path at cap height, baseline
aligned, 0.22 em before the word. Rendered with `tools/render.mjs` at 512, 64
and 32 px: the lockup's raven still reads at 32 px (eye, bill, tail). The
`-dark` twins are the same outlines in bone for the dark README; use
`<picture>` with `prefers-color-scheme` to switch.

## Intended uses

- The wordmark everywhere a vector wordmark is needed: README hero (the
  lockup SVG), docs site header, installer wizard bitmap, About screen,
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
  word solid where the serif direction thins out. The DPR-3 phone crop in
  the preview shows label 13 / mono 13 / body 16 crisp.
- One family for the UI means no pairing decisions and one italic voice.
- The tittle lockup (raven as the dot of the `i`, dotless `ı` underneath)
  works at 56 px and 44 px on both grounds with the raven at 0.42 em,
  0.62 em up, 0.05 em right: the tail clears the `g`'s ear (DPR-3 card crops
  in the preview).
- Reads as a modern developer product without quoting any other brand's
  typeface (Geist is Vercel's, Inter is everyone's).
- The README hero no longer needs a font or a `<pre>`: the SVGs exist.

## Weaknesses

- A grotesk is the safe choice; nothing about the letterforms says "raven".
  The story lives in the lockups and the mark, not in the type.
- The Nordic provenance is a story the owner tells, not something a user
  sees.
- At 144 px the tight tracking closes the `g`–`i` gap; if the wordmark goes
  larger than 160 px, open tracking to -2.5 % (the SVG is cut at -3.5 %).
- Variable-font axes are not reliable on Compose desktop (Skia); bundle the
  static instances.
- The tittle lockup below 44 px was not tested and should not be assumed.

## Relation to the current identity

Keeps the canonical `raven.svg` untouched and uses it in all three lockups
and in `lockup.svg`. Palette is Theme.kt verbatim (dark primary, light
styled); every text colour on the page passes AA (muted `#8A8177` on
`#12100F` is 4.96:1, the tightest pair). Replaces the stock figlet wordmark
in the README with a drawn one and Material's default Roboto with a bundled
family.

## Revision

Tittle lockup re-tuned (raven 0.46 → 0.42 em, offset +0.03 em) after the
review found the tail touching the `g`; a 44 px card was added and both are
shown at DPR 3 in the preview. Weights table aligned with `type-system.md`
(mono 400/500; seven TTFs). The outlined `wordmark.svg` / `lockup.svg` and
their dark twins were produced with fontTools + uharfbuzz, rendered at
512/64/32 and added to the specimen. Preview now also carries the DPR-3
phone crop.
