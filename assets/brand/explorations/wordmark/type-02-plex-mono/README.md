# type-02-plex-mono — IBM Plex Mono-led identity

**Files:** `specimen.html` (self-contained, Google Fonts `<link>` only),
`preview.png` (full-page render at 1200 px, then DPR-3 crops of the phone mock
and the lockup cards at 1:1 device pixels).

## Concept

The wordmark is typed, not drawn: `huginn`, lowercase, in IBM Plex Mono
SemiBold with -6 % tracking so the cells close up into a word. Plex Mono is
the warmest of the serious monospaces (slab-ish `i`, `l` and `r`, a real
italic), which suits the parchment-on-near-black palette far better than a
cold coding face would. The lead lockup is the one idea in this folder that
belongs to Huginn alone: the raven stands where the cursor would be.
`huginn [raven]` is "thought, waiting at the prompt".

Roles: the display size (44 px `Settings`), labels, key/value rows, terminal
and code are Plex Mono; title (28), headline (22) and body (16) are IBM Plex
Sans, its designed sibling, so chat transcripts and row text stay readable.
(The first draft of this README said titles were mono; the specimen never
did, and the specimen is right.)

## Fonts

| Role | Family | Weights used | Licence |
|---|---|---|---|
| Wordmark, display, label, terminal, code | IBM Plex Mono | 400, 500, 600 + italic 400 | SIL OFL 1.1 |
| Title, headline, body | IBM Plex Sans | 400, 500, 600 | SIL OFL 1.1 |

Measured on IBM Plex Mono 2.x (Google Fonts static TTF, Regular and
SemiBold): box drawing U+2500–257F **128/128**, block elements U+2580–259F
**32/32**, geometric shapes U+25A0–25FF **1/96** (only `◊` U+25CA), braille
0/256, runes 0/96. Consequence: every `word-*` mark renders inside the app
with this font, *including* the rounded hairline (`╭ ╮ ╰ ╯ ╲ ╶` all present)
and the perch, now that the perch no longer uses `▶` (which Plex lacks).
`●`, `▶`, `◆` and the other geometric glyphs must not appear in terminal art
if this direction is chosen.

## Intended uses

- CLI-adjacent surfaces first: installer, README, docs code headers, the
  desktop About box, the tray tooltip (mono at 13 px is at home there).
- App UI if the owner wants the phone to feel like the terminal it mirrors.
- The "raven as cursor" lockup is usable in any direction; it is drawn here
  because mono makes it literal.

## Material 3 mapping

displaySmall 44/52 · Plex Mono 600 → headlineMedium 28/36 · Plex Sans 600 →
titleLarge 22/28 · Plex Sans 500 → bodyLarge 16/24 · Plex Sans 400 →
labelMedium 13/16 · Plex Mono 500, +6 % tracking → mono 13/18 · Plex Mono 400.
One mono for the whole product: `LocalMonoStyle` and the code card use the
same file as the wordmark.

In the terminal, Plex Mono is also the recommended user font; the ASCII
wordmarks `word-03-mono9-lower` and `word-08-slant-lower` (lowercase with the
cursor column) are this direction's terminal twins.

## Strengths

- Only direction where wordmark, UI and terminal are literally one face; the
  CLI splash and the app title bar look like the same product.
- The cursor lockup is memorable, cheap to animate (blink the raven, or let
  it fly off and return: the brief's "flight out and return" motif) and
  survives monochrome at 44 px on both grounds (DPR-3 card crops).
- Plex covers Latin, Greek and Cyrillic in one family, and its italic is a
  real italic, not a slant. Box and block coverage is complete (above).

## Weaknesses

- Theme.kt's stated intent is "a terminal that does not look like a terminal
  emulator"; a mono-led UI pulls the other way. Titles in mono at 28 px are
  wide, which is why they are in the Sans.
- Mono wordmarks are common among developer tools (Warp, Zed, Fly, Railway
  all did a version of this); the raven has to do the differentiating.
- **The tittle lockup does not work in this direction at any size tested.**
  Rendered at 44, 56, 72 and 96 px (and with a smaller raven at 56/72): a
  mono `ı` cell is 0.6 em wide and the raven is 1.65:1, so the bill touches
  the `n` and the tail the `g`'s ear every time. The specimen keeps one card
  at 72 px marked REJECTED as evidence; the direction leads with the cursor
  lockup instead.
- Tagline in mono italic is charming at one line and clumsy if it wraps.
- Two families to bundle (Mono + Sans), about 7 static TTFs.

## Relation to the current identity

Uses `raven.svg` unchanged. Palette is Theme.kt verbatim. Replaces
`FontFamily.Monospace` (platform default) with a bundled Plex Mono so phone
and desktop finally show the same glyphs; sizes stay 11/15 and 13/17 as
today.

## Revision

Tittle lockup measured at 44/56/72/96 px, found to fail at all of them, and
replaced in the specimen by a labelled rejected card; the cursor lockup is
now the lead and says so. IBM Plex Mono coverage measured (numbers above and
in `type-system.md` section 2). Role table corrected to match the specimen
(title and headline in Sans). Preview carries the DPR-3 phone crop.
