# type-03-instrument — Instrument Serif + Instrument Sans + JetBrains Mono

**Files:** `specimen.html` (self-contained, Google Fonts `<link>` only),
`preview.png` (full-page render at 1200 px, then DPR-3 crops of the phone mock
and the lockup cards at 1:1 device pixels).

## Concept

A display serif with a carved edge, for the Norse story without the costume.
Instrument Serif is condensed, high-contrast and sharp: its wedge serifs read
as cut rather than drawn, and at 144 px `Huginn` looks like it belongs on a
title page or a stone. It is deliberately restricted to the name, the display
role and the italic tagline; everything a user touches is set in Instrument
Sans, its sibling, with JetBrains Mono for terminal and code. The raven as
the tittle of the `i` is the strongest lockup here: a bird on a tall serif
post, bill pointing on into the word.

## Fonts

| Role | Family | Weights used | Licence |
|---|---|---|---|
| Wordmark, display, tagline | Instrument Serif | Regular, Italic (the family has no other weights) | SIL OFL 1.1 |
| Title, headline, body, label | Instrument Sans (variable 400–700 + italics) | 400, 500, 600 | SIL OFL 1.1 |
| Terminal, code | JetBrains Mono | 400, 500 | SIL OFL 1.1 |

## Intended uses

- Hero surfaces: README header (as SVG), docs site masthead, social cards,
  the installer wizard, the desktop About box, release notes.
- The display role inside the app (`Settings`, empty-state titles) and
  nothing smaller.

## Material 3 mapping

displaySmall 44/52 · Instrument Serif 400 → headlineMedium 28/36 · Instrument
Sans 500 → titleLarge 22/28 · Instrument Sans 600 → bodyLarge 16/24 ·
Instrument Sans 400 → labelMedium 13/16 · Instrument Sans 600, +6 % tracking →
mono 13/18 · JetBrains Mono 400. The serif never appears below 28 px; the
specimen's 24 px line is labelled "not for use" and shows the Instrument Sans
600 fallback and the 13 px label beside it.

In the terminal this direction has no presence beyond the ASCII wordmarks;
`word-09-hairline` (thin box drawing) is the closest terminal cousin in
spirit (fine lines, tall proportions).

## Strengths

- The most distinctive and the most "premium" of the three; the only one
  where the letterforms themselves carry a hint of the carved/Nordic motif.
- Serif wordmark + sans UI + mono code is a classic three-part system that
  maps cleanly onto Material roles.
- Italic tagline is genuinely beautiful; `Thought, on call from anywhere.`
  reads like a line of verse.
- At DPR 3 (preview crop) the Instrument Sans 13 px label, 16 px body and
  the JetBrains Mono rows are crisp; the risk is confined to the serif.

## Weaknesses

- Weak at small sizes: the 24 px serif wordmark is thin and loses the serifs
  on a phone (shown for comparison in the specimen, marked not for use);
  notifications and tray tooltips must use the sans or the mark alone.
- Only Regular and Italic exist: no bold for emphasis, no way to make the
  wordmark heavier if it is ever needed small.
- Instrument Serif + Instrument Sans is a fashionable pairing on indie SaaS
  landing pages in 2024–2025; it risks reading as "trend" rather than "us".
- Instrument Sans is competent but characterless; the UI will look like any
  well-made app.
- Condensed serif capitals and the raven's horizontal silhouette fight a
  little in the side-by-side lockup; the stacked and tittle lockups are
  better.
- No outlined SVG was produced for this direction (only the recommended one
  got that work); it would be the same fontTools step with
  `InstrumentSerif-Regular.ttf`.

## Relation to the current identity

Uses `raven.svg` unchanged; palette is Theme.kt verbatim. Of the three, this
is the biggest departure from the current all-system-font product, and the
only one that changes the *mood* (from tool to object). It keeps the raven
primary by giving the type a supporting, ceremonial role.

## Revision

The 24 px line is relabelled "not for use; shown for comparison" and shows
the Instrument Sans 600 fallback and the 13 px label beside it. The preview
now includes a DPR-3 crop of the phone mock (label, mono, body at 1:1 device
pixels) and the tittle cards at DPR 3.
