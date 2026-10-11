# Huginn type system — recommendation (revised)

Opinionated, so the brand book can copy it. Sizes are sp (Compose) or px
(web); "leading" is line height. The phone column is what a 390-dp Android
screen needs; desktop is the Compose desktop window; web is the docs site
and README SVGs.

## 1. Roles

Material 3 role names on the left so `HuginnTypography` can be filled in
line by line. Family/weight assume Direction 1; the other directions swap
the family per their READMEs and keep the sizes.

| Role (M3) | Use | Phone | Desktop | Web | Family / weight / tracking |
|---|---|---|---|---|---|
| displaySmall | screen titles, empty states, About | 36 / 44 | 44 / 52 | 44 / 52 | Schibsted Grotesk 800, -2 % |
| headlineMedium ("title") | section titles, chat titles | 28 / 36 | 28 / 36 | 28 / 36 | Schibsted Grotesk 700, -1 % |
| titleLarge ("headline") | card titles, dialog titles | 22 / 28 | 22 / 28 | 22 / 28 | Schibsted Grotesk 600, -0.5 % |
| titleMedium | list-row primary text | 16 / 24 | 16 / 24 | 16 / 24 | Schibsted Grotesk 600 |
| bodyLarge | chat prose, settings copy | 16 / 24 | 16 / 24 | 17 / 26 | Schibsted Grotesk 400 |
| bodyMedium | secondary prose, descriptions | 14 / 20 | 14 / 20 | 15 / 22 | Schibsted Grotesk 400 |
| labelMedium | overlines, key labels, chips | 13 / 16 | 13 / 16 | 13 / 16 | Schibsted Grotesk 600, +6 %, optional caps |
| labelSmall | timestamps, badges | 11 / 16 | 11 / 16 | 12 / 16 | Schibsted Grotesk 500, +4 % |
| mono (`LocalMonoStyle`) | terminal, code card, key/value rows | 11 / 15 | 13 / 17 | 13 / 18 | JetBrains Mono 400 (500 for emphasis, never 700 inline) |
| wordmark | `Huginn` as type | 24 | 24–64 | 64–144 | Schibsted Grotesk 800, -3.5 % (open to -2.5 % above 160 px) |

Rules that go with the table:

- Dark is primary: ink `#E8E2DA` on `#12100F`, muted `#8A8177` for labelSmall
  and secondary prose (4.96:1, AA; do not go lighter on the ground or darker
  on the text). Light scheme uses `#1B1817` / `#5C544B` on `#FAF7F2`.
- Tracking is negative only above 22 sp; body and smaller are set at 0 or
  positive. Never letter-space the mono.
- The tagline is the italic of the UI family at headline size, nowhere
  else; italic is not used in running UI copy.
- Numerals: tabular (`font-feature-settings: "tnum"`) in the mono and in
  labelSmall timestamps; Schibsted Grotesk has tabular figures.
- The tittle lockup (raven as the dot of the `i`) is used at 44 px and above
  only, with the raven at 0.42 em, 0.62 em up and 0.05 em right of the `ı`'s
  centre; below 44 px use the lockup with the raven left of the word.

## 2. The mono face

**JetBrains Mono** for terminals, code cards and every key/value row, in all
three directions except type-02 (which uses IBM Plex Mono for everything
and gets the same sizes). Reasons: it is hinted for 11–13 px, has a clear
`0`/`O`, `1`/`l`/`I`, full box drawing (U+2500–257F, 128/128) and block
elements (U+2580–259F, 32/32) in the desktop build, so the ASCII wordmarks
and tmux borders render inside the app. Disable ligatures
(`FontFeature "calt" off`) in the terminal view: a terminal must show what
was typed.

Coverage, measured with fontTools on the actual files:

| Font | box U+2500–257F | blocks U+2580–259F | geometric U+25A0–25FF | braille | runes | notes |
|---|---|---|---|---|---|---|
| JetBrains Mono 2.304 | 128/128 | 32/32 | 43/96 (`▶ ▸ ◆ ■ ● ◇` yes; `◢ ◣ ◤ ◥` no) | 0 | 0 | draws `╲` with notches at the cell joins |
| IBM Plex Mono (Google Fonts static) | 128/128 | 32/32 | 1/96 (`◊` only) | 0 | 0 | no `▶ ● ◆` |
| DejaVu Sans Mono | 128/128 | 32/32 | 96/96 | 0 | 0 | draws `╲` corner to corner |
| Liberation Mono | 40/128 (no `╭ ╮ ╰ ╯ ╲ ╶`, no heavy) | 8/32 (`▀ ▄ █` yes) | 24/96 | 0 | 0 | the last fallback on Linux desktops |

Design rules that follow: terminal art uses `█ ▀ ▄` and the light box set
(`│ ─ ┌ ┐ └ ┘ ├ ┤ ┬ ┴`) as its safe core; `╭ ╮ ╰ ╯ ╲` only in files that have
a square twin; no `▶`, no braille, no runes in terminal art (they would come
from a system fallback font and not match the cell). The signature's `╲` has
notches in JetBrains Mono's own glyph; Compose draws text with Skia and will
show them, so **inside the apps the mark is drawn as geometry** (the
`word-10-perch/wordmark.svg` approach, or a Compose `Canvas` that paints the
cell grid), not as text. In external terminals the user's emulator decides:
kitty, VTE, Windows Terminal, WezTerm and xterm.js draw box glyphs as
geometry and the diagonal is clean; xterm and Termux use the font's glyph.

## 3. Bundling in Compose Multiplatform

- Put static TTFs in `mobile/ui/src/commonMain/composeResources/font/`
  (`schibsted_grotesk_regular.ttf`, `_semibold`, `_bold`, `_extrabold`,
  `_italic`; `jetbrains_mono_regular.ttf`, `_medium`). Load with
  `org.jetbrains.compose.resources.Font(Res.font.schibsted_grotesk_bold,
  FontWeight.Bold)` into one `FontFamily` per family and build
  `HuginnTypography` from it; replace `FontFamily.Monospace` in
  `MonoStylePhone`/`MonoStyleDesktop` with the bundled mono so the phone and
  the laptop finally show identical glyphs (the one comparison Theme.kt says
  the whole migration exists to win).
- Use **static instances, not the variable font**: Compose desktop (Skia)
  does not expose variation axes reliably and Android needs API 26+ for
  `FontVariation`. Static files come from each family's GitHub repo or from
  the Google Fonts CSS API with a non-woff2 user agent (that is how the
  files in this folder's SVG were obtained). Budget: about 95 KB per sans
  instance, ~130 KB per mono instance; seven files ≈ 0.75 MB. Subset with
  `pyftsubset --unicodes="U+0000-00FF,U+0100-017F,U+2000-206F,U+20AC,U+2500-259F"`
  if the APK size matters; keep the box/block ranges in the mono.
- Android launcher, notification and tray icons never contain type; the
  wordmark appears as text only inside the apps and as outlines elsewhere.
- Installer (NSIS `wizard.bmp`), README hero and docs: use
  `type-01-schibsted/wordmark.svg` / `lockup.svg` (and the `-dark` twins).
  They were produced with `fontTools` + `uharfbuzz` (shape with kerning,
  outline each glyph with `SVGPathPen`, place the canonical raven path at cap
  height); regenerate the same way if the tracking or the family changes.
  The artwork does not depend on any font being present.

## 4. Fallbacks

- Apps: bundled fonts cannot fail to load, but `FontFamily(...)` should list
  `FontFamily.SansSerif` last for the odd Unicode block the family lacks
  (CJK in a chat transcript, for example).
- Web/docs: `font-family: "Schibsted Grotesk", system-ui, -apple-system,
  "Segoe UI", Roboto, sans-serif` and `"JetBrains Mono", ui-monospace,
  "Cascadia Mono", Menlo, "DejaVu Sans Mono", monospace`. Use
  `font-display: swap`; the wordmark in a README is the SVG, not live text
  and not `<pre>` art.
- Terminals: the CLI cannot choose the font, and the client scripts cannot
  carry Unicode at all. The ladder, from best to safest:
  1. `word-10-perch/wordmark.txt` (rounded hairline + bird): server-side
     banners in terminals whose font has `╭ ╮ ╰ ╯ ╲` (DejaVu Sans Mono,
     JetBrains Mono, Cascadia, Menlo, Plex Mono).
  2. `word-10-perch/square.txt`: same drawing with `┌ ┐ └ ┘` and `\`; renders
     in every font with the basic light box set, Liberation Mono included.
     Use it when the server cannot know the client's font (it usually
     cannot), which makes it the practical default for `huginn-status`.
  3. `word-10-perch/ascii.txt` / `word-09-hairline/ascii.txt`: 7-bit; the
     only files allowed in `client/huginn.sh` and `huginn.ps1`; also the
     README fallback where an image is not possible.
  4. Below 34 columns (`$COLUMNS`): `word-06-pagga` (two universal glyphs),
     or plain `huginn` in the client.
  5. One row (tmux `status-left`, tab titles, version lines):
     `word-11-oneline` `(o>` and its state variants.

## 5. Verdict

Choose **Direction 1, Schibsted Grotesk + JetBrains Mono**, and pair it with
**one** terminal letterform family: the hand-drawn hairline, as
`word-10-perch` wherever the raven can be shown and `word-09-hairline` where
it cannot, each in the charset the surface allows (rounded, square, ASCII),
with `word-11-oneline` on the one-row surfaces and `word-06-pagga` as the
sole exception for terminals narrower than 34 columns.

Why this and not a heavier terminal mark to match the 800-weight sans: in a
terminal, weight is not free. Heavy marks are block-element fonts, which
means seams in browsers and a figlet idiom the brief wants to leave; the
hairline is the only drawn letterform in the folder, the only one with a
real raven in it, and the only one that degrades through three charsets
without changing its drawing. The raven, not the stroke weight, is what ties
the terminal to the apps; the type's job is to be quiet, well made and the
same on every platform.

The product's own stated intent is "a terminal that does not look like a
terminal emulator", which rules out leading with mono (Direction 2's best
idea, the raven as cursor, survives as a lockup in any direction and should
be kept; its tittle lockup was measured and does not work). Direction 3's
serif is the most beautiful of the three but fails exactly where this
product lives: at 13–24 px on a phone, in a tray tooltip, in a notification,
where a regular-only display serif thins out and its sans companion is
anonymous. Schibsted Grotesk holds at every size on the table above, gives
the wordmark a solid 800 that the raven can perch on, is one family for the
whole UI (fewer files, no pairing decisions, one italic voice for the
tagline), and now exists as outlined SVG for the README and the installer.

What changed from the first verdict: `word-04-future` was the sub-48-column
mark and read `HUGI44`; its `N` is fixed, but `word-06` takes the narrow slot
because it cannot tofu. `word-10` was recommended as the "CLI signature"
while being Unicode; it is now the *server-side* signature with a 7-bit twin
for the client. `word-07` is no longer in the verdict: its 3-row ASCII is a
different drawing from the signature, and the hairline's own ASCII twin keeps
the family to one.
