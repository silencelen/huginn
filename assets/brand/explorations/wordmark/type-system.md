# Huginn type system — recommendation

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

## 2. The mono face

**JetBrains Mono** for terminals, code cards and every key/value row, in all
three directions except type-02 (which uses IBM Plex Mono for everything
and gets the same sizes). Reasons: it is hinted for 11–13 px, has a clear
`0`/`O`, `1`/`l`/`I`, full box drawing (U+2500–257F, 128/128) and block
elements (U+2580–259F, 32/32) in the desktop build, so the ASCII wordmarks
and tmux borders render tight inside the app. Disable ligatures
(`FontFeature "calt" off`) in the terminal view: a terminal must show what
was typed.

Coverage limits to design around (measured on JetBrains Mono 2.304):
geometric shapes 43/96 (`▶ ▸ ◆ ■ ● ◇` present; the quadrant triangles `◢ ◣ ◤ ◥` absent, so draw ravens with half blocks, not triangles), braille 0/256, runes 0/96.
DejaVu Sans Mono has box drawing and blocks but no braille either (DejaVu
Sans does). So: block/box art is safe in the bundled mono; braille art and
runic accents will come from the system's fallback font and will not match
the mono's cell. Keep runes out of terminal art; the brief wanted them
subtle anyway.

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
  `FontVariation`. Static files come from each family's GitHub repo
  (`fonts/ttf/` or `static/`). Budget: about 90–110 KB per sans instance,
  ~110 KB per mono instance; seven files ≈ 0.8 MB. Subset with
  `pyftsubset --unicodes="U+0000-00FF,U+0100-017F,U+2000-206F,U+20AC,U+2500-259F,U+25A0-25FF"`
  if the APK size matters; keep the box/block ranges in the mono.
- Android launcher, notification and tray icons never contain type; the
  wordmark appears as text only inside the apps and as SVG outlines in
  README/installer, so no font needs to be installed on the user's system.
- Installer (NSIS `wizard.bmp`) and README hero: render the wordmark to SVG
  with the glyphs converted to outlines (any browser → "print to SVG", or
  Inkscape "object to path") so the artwork does not depend on the font
  being present.

## 4. Fallbacks

- Apps: bundled fonts cannot fail to load, but `FontFamily(...)` should list
  `FontFamily.SansSerif` last for the odd Unicode block the family lacks
  (CJK in a chat transcript, for example).
- Web/docs: `font-family: "Schibsted Grotesk", system-ui, -apple-system,
  "Segoe UI", Roboto, sans-serif` and `"JetBrains Mono", ui-monospace,
  "Cascadia Mono", Menlo, "DejaVu Sans Mono", monospace`. Use
  `font-display: swap`; the wordmark in a README must be SVG outlines, not
  live text.
- Terminals: the CLI cannot choose the font. The ASCII marks are designed
  for DejaVu Sans Mono (Termux default lineage, Linux default) and any font
  with U+2500–259F; `word-06` and `word-07` are the safety marks for fonts
  without box drawing. Detect `$COLUMNS` and pick `word-04`/`word-05` below
  48 columns.

## 5. Verdict

Choose **Direction 1, Schibsted Grotesk + JetBrains Mono**, and pair it with
`word-10-perch` as the terminal signature (`word-04-future` below 48 columns,
`word-07-cyberlarge` in plain-text contexts). The product's own stated intent
is "a terminal that does not look like a terminal emulator", which rules out
leading with mono (Direction 2's best idea, the raven as cursor, survives as
a lockup in any direction and should be kept); and Direction 3's serif is
the most beautiful of the three but fails exactly where this product lives:
at 13–24 px on a phone, in a tray tooltip, in a notification, where a
regular-only display serif thins out and its sans companion is anonymous.
Schibsted Grotesk holds at every size on the table above, gives the
wordmark a solid 800 that the raven can perch on, is one family for the
whole UI (fewer files, no pairing decisions, one italic voice for the
tagline), and is not yet the default face of every developer tool. The raven
stays the distinctive element, which is what the brief asked for: the type's
job is to be quiet, well made and the same on every platform.
