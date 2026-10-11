# type-02-plex-mono — IBM Plex Mono-led identity

**Files:** `specimen.html` (self-contained, Google Fonts `<link>` only), `preview.png` (full-page render at 1200 px).

## Concept

The wordmark is typed, not drawn: `huginn`, lowercase, in IBM Plex Mono
SemiBold with -6 % tracking so the cells close up into a word. Plex Mono is
the warmest of the serious monospaces (slab-ish `i`, `l` and `r`, a real
italic), which suits the parchment-on-near-black palette far better than a
cold coding face would. Display, titles, labels and all key/value rows are
mono; only body copy drops into IBM Plex Sans, its designed sibling, so chat
transcripts stay readable. The bonus lockup is the one idea in this folder
that belongs to Huginn alone: the raven stands where the cursor would be.
`huginn ▶raven` is "thought, waiting at the prompt".

## Fonts

| Role | Family | Weights used | Licence |
|---|---|---|---|
| Wordmark, display, title, headline, label, terminal, code | IBM Plex Mono | 400, 500, 600, 700 + italic 400/600 | SIL OFL 1.1 |
| Body copy only | IBM Plex Sans | 400, 500, 600 | SIL OFL 1.1 |

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
  survives monochrome.
- Plex covers Latin, Greek and Cyrillic in one family, and its italic is a
  real italic, not a slant. (Box-drawing coverage was not measured here;
  check before relying on it for the ASCII marks inside the app.)

## Weaknesses

- Theme.kt's stated intent is "a terminal that does not look like a terminal
  emulator"; a mono-led UI pulls the other way. Titles in mono at 28 px are
  wide, and `Answer permission prompts from the lock screen` needs the sans.
- Mono wordmarks are common among developer tools (Warp, Zed, Fly, Railway
  all did a version of this); the raven has to do the differentiating.
- The tittle lockup is cramped: a mono `ı` is narrow and the raven overhangs
  both neighbours; it needs 44 px or more.
- Tagline in mono italic is charming at one line and clumsy if it wraps.
- Two families to bundle (Mono + Sans), about 9 static TTFs.

## Relation to the current identity

Uses `raven.svg` unchanged. Palette is Theme.kt verbatim. Replaces
`FontFamily.Monospace` (platform default) with a bundled Plex Mono so phone
and desktop finally show the same glyphs; sizes stay 11/15 and 13/17 as
today.
