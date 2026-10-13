# Huginn brand book — v1, 2026-10-13

The identity for every Huginn surface: the CLI in bash, PowerShell and Termux,
the Android app, the Compose desktop app, the README, the installer, the tray
and the notifications. Decided in six rounds of explorations under
[`explorations/`](explorations/README.md); every file named here exists there
and every number comes from its proofs. The rendered twin of this document, set
in the brand, is the "Huginn Brand Book" page.

Contents: 1 The raven · 2 The small form · 3 Terminal art · 4 Colour · 5 Type ·
6 Motion · 7 Surfaces · 8 Voice · 9 To build.

## 1. The raven

A raven standing forward with its head raised: twelve straight edges, a round
eye cut as a hole, and one hairline fold along the folded wing. Every vertex
sits on a 24 × 16 lattice over the 108 box (cell 4.5 × 3.4 units; column *i* at
x = 4.5 i, half-row *j* at y = 14.4 + 3.4 j), so the terminal renderings are
exact rasters of the mark, not samplings. Box 54 × 51 units, path frame
x 22.5–76.5, y 17.8–68.8, inside the canon `translate(2,11)`.

| vertex | (col,row) | units | vertex | (col,row) | units |
|---|---|---|---|---|---|
| bill tip | 17,4 | 76.5, 28 | tail tip | 5,16 | 22.5, 68.8 |
| culmen | 15,3 | 67.5, 24.6 | tail bottom | 9,16 | 40.5, 68.8 |
| forehead | 12,1 | 54, 17.8 | under-tail notch | 12,14 | 54, 62 |
| crown | 10,1 | 45, 17.8 | belly | 13,14 | 58.5, 62 |
| back of head | 8,2 | 36, 21.2 | chest | 14,10 | 63, 48.4 |
| nape / shoulder | 7,4 | 31.5, 28 | throat | 14,6 | 63, 34.8 |

Eye: r 3.2 at the centre of cell (11,3) = (51.75, 26.3). Fold: a 2-unit slit
from the nape to (9,11) to (7,15), spliced into the ring at the shoulder, so the
mark is one evenodd path (a 17-vertex ring plus the eye hole).

**Sizes.** 64 px and up: `mark.svg` with the fold. 32 and below: `mark-32.svg`,
the twelve-vertex solid (the slit antialiases away). 16–22 px on a bone tile
(tray, favicon-16, title bar): `mark-16.svg`, the head drawing derived from the
same vertices (scaled 1.8 about the bill tip, eye r 6.5). The eye survives every
size.

**Do.** Use the files; never redraw. Ink `#141615` on bone `#E2E6E1`, bone on
the ground, or one state tint. Keep the bird upright and facing right. The
launcher pivots at (52,43) and scales 0.72 so the farthest vertex stays inside
the 33 dp safe ring; `mark-tile.svg` scales the bird 1.4 about its box centre on
the bone tile (x 16.2–91.8).

**Don't.** No outlines, gradients, shadows or second colours on the mark. No
mirroring. No fold below 64. No raven inside type below 44 px except the cursor
lockup. No emoji anywhere a raven could stand.

| file | what |
|---|---|
| `explorations/round3/fold-system/mark.svg`, `mark-inverse.svg` | the mark, ink and bone, one evenodd path, 108 viewBox |
| `mark-tile.svg` | the app-icon master |
| `mark-32.svg`, `mark-16.svg` | the solid for 32 and below; the head drawing for 16–22 |
| `mark-perched.svg` | the perched round-2 bird, for the record |
| `build.py` | regenerates every file from the vertex list; edit the vertices, not the SVGs |

## 2. The small form

For the tray, the notification icon and favicons the raven is a hole in a
rounded square (64 box, rx 14) at 0.84 of the mark, the eye a 4 × 4 ink cell on
whole pixels so it is exactly one pixel at 16. The tile takes the state colour;
needs-you adds the badge dot (`error`, r 9 at (53,11) of 64). Below 24 the fold
antialiases away and the tile is "a tile with a bird"; from 24 up it is the fold
raven.

- States: idle `#848C87` · working `#7CB98E` · needs you `#E4E2A8` + badge
  `#EE757D`. Adjacent steps are ≥ 12 L* apart (L* 57 / 70 / 89), so a template
  tray that strips colour still tells them apart at onSurface 45 / 75 / 100 %.
- Where: the desktop tray (`TrayIcons.kt` draws the tile and the dot), the
  Android status icon (white; the OS tints it), favicon-32 and -16, the title
  bar. The launcher uses the full mark on the bone tile, not the glyph.
- Files: `explorations/round3/fold-system/glyph-c.svg`, `glyph-c-inverse.svg`.

## 3. Terminal art

The logo is the terminal art: the text renderings are the mark's own raster at
≥ 50 % cell coverage, with no cell near the threshold. Half-block where
line-height is 1, braille where it is not, 7-bit in the client scripts.
Single-width characters only; no emoji, no runes, no geometric shapes in
terminal art.

| rendering | file | surface |
|---|---|---|
| 24 × 8 half-block | `round3/fold-system/ascii-halfblock.txt` | `huginn status`, the tmux pane, the apps' terminal view |
| 24 × 8 in 7 bits | `ascii-7bit.txt` | `client/huginn.sh`, `huginn.ps1` where a banner is wanted |
| braille 12 × 4 | `ascii-braille.txt` | docs and anywhere blocks stripe |
| 48 × 16 with the fold as a one-cell gap | `ascii-halfblock-48.txt`, `ascii-7bit-48.txt` | the host splash after attach, in moss |
| the hairline perch | `round5/perch-upright.txt` (rounded), `-square.txt` (fonts without the rounded corners), `-ascii.txt` (the client) | server banners, the README's text hero, the client |
| the five-row raven alone | `round5/raven-5row.txt`, `-ascii.txt` | the perch's bird; the mark at 9 × 10 cells |
| hairline without the bird | `wordmark/word-09-hairline/` | where no bird fits |
| narrow mark | `wordmark/word-06-pagga/` | terminals under 34 columns |
| one-row raven | `(o>` · `(!>` needs you · `(->` waiting (`wordmark/word-11-oneline/`) | tmux status-left, tab titles, the version line, the first line of banners |

**Exception, by decision.** The client's help screen keeps today's four-row bird
(`(o)==-`, `client/huginn.sh:988`), and the 7-bit landing, takeoff and boot
that were grown from it stay as they are. It is the one place the old drawing
survives, and it is deliberate.

All files pass `explorations/tools/asciicheck.py`.

## 4. Colour — Moss & Heather

A dark grey ground with a breath of green in it, cool bone ink, moss as the one
accent, heather as the second voice for code, links and CLI headings. Needs-you
is the moss lit up; success a lime; error a coral; the third voice a dusty rose.
The light scheme deepens the moss to evergreen and the heather to a royal
purple. Generated and proved by `explorations/color/build/`: every text pair
≥ 4.5:1, UI ≥ 3:1, the ladders ≥ 12 L* apart, every terminal slot readable on
conhost, PowerShell 5.1, Campbell and xterm. Source:
`explorations/color/color-04-moss-heather/tokens.json`; proof: `contrast.txt`.

| role | dark | light | use |
|---|---|---|---|
| background / surface | `#141615` | `#F4F6F3` | the app ground |
| surfaceContainerLowest | `#0E100F` | `#FFFFFF` | tier 0: code wells, the terminal block |
| surfaceContainerLow | `#1C1F1D` | `#EDF0EC` | tier 1: list rows, chat bubbles |
| surfaceContainer | `#242826` | `#E6EAE6` | tier 2: cards, sheets |
| surfaceContainerHigh | `#2D3230` | `#DFE4DF` | tier 3: dialogs, menus |
| surfaceContainerHighest | `#383E3B` | `#D6DCD6` | tier 4: chips, inputs on a dialog |
| onSurface | `#E2E6E1` | `#191C1A` | primary text |
| onSurfaceVariant | `#A2ABA5` | `#535B56` | secondary text, labels, timestamps |
| outline / outlineVariant / outlineHairline | `#747D77` / `#3A423E` / `#2A302D` | `#78817B` / `#C6CDC8` / `#DDE2DE` | 3:1 strokes / decorative edges / dividers |
| primary (moss) · onPrimary | `#7CB98E` · `#0B2316` | `#1F5A3A` · `#FFFFFF` | the one accent: links, buttons, the raven eye, the cursor |
| primaryContainer · onPrimaryContainer | `#1F3D2C` · `#C2E6CF` | `#CDEAD6` · `#0A3320` | selected row, accent chip |
| secondary (heather) · onSecondary | `#B49CDB` · `#221640` | `#5B3E8A` · `#FFFFFF` | functions, links in code, CLI headings |
| secondaryContainer · onSecondaryContainer | `#352A52` · `#E2D7F5` | `#E6DCF5` · `#2E1A52` | |
| tertiary | `#E6AEC6` | `#6E3A7A` | subagent markers, graph series |
| error · onError | `#EE757D` · `#2B0C10` | `#B3262E` · `#FFFFFF` | destructive verbs, failures |
| errorContainer · onErrorContainer | `#4C1C22` · `#F8C5CA` | `#F9DBDC` · `#5E1418` | |
| stateIdle / stateWorking / stateAttention | `#848C87` / `#7CB98E` / `#E4E2A8` | `#687069` / `#1F5A3A` / `#0F3320` | the tray ladder: L* 57 / 70 / 89 dark, 46 / 34 / 18 light |
| stateSuccess / stateError / stateInfo | `#C4CF7A` / `#EE757D` / `#86B5E6` | `#4E7A1C` / `#B3262E` / `#2A5FA5` | text semantics by hue |
| inkMark / boneTile | `#141615` / `#E2E6E1` | same | the raven on light grounds; the icon tile |

ANSI-16 (`terminal/moss-heather.ansi.json`; ground `#141615`, foreground
`#E2E6E1`, cursor `#7CB98E`, selection `#2C4035`): black `#3A423E` · red
`#D65A62` · green `#7CB98E` · yellow `#D4B872` · blue `#6C9BD6` · magenta
`#A98BD6` · cyan `#5EB5B0` · white `#C8CDC9` · brightBlack `#747D77` ·
brightRed `#EE757D` · brightGreen `#C4CF7A` · brightYellow `#E4E2A8` ·
brightBlue `#86B5E6` · brightMagenta `#C3A8E8` · brightCyan `#80CFC9` ·
brightWhite `#E2E6E1`. The ladder brightBlack / green / brightYellow is
L* 52 / 70 / 89.

Rules:

- **Slot pins.** brightRed = error, brightGreen = stateSuccess, brightYellow =
  stateAttention, brightBlue = stateInfo, so the CLI, tmux and the app never
  show two reds. The CLI's 16-colour accent is brightMagenta (heather); moss is
  the accent in truecolor and in the apps.
- **Needs-you is the lit accent**, not error. The apps draw dots; the pulse
  breathes stateWorking at the `PulseDot` table; the tray shows the tinted tile
  plus the badge.
- **Known trade-offs** to refine before the tokens are frozen: green means both
  working (moss) and success (lime), 68 degrees and 11 L* apart; the heather
  never reaches the tray; the lit moss reads as a highlighter beside the lime.

State glyph table (titles, tmux, host status, Telegram, the clients; the apps
draw dots, not glyphs):

| state | unicode | 7-bit | one-row |
|---|---|---|---|
| running | `●` | `*` | `(o>` |
| needs you | `▲` | `!` | `(!>` |
| waiting | `○` | `o` | `(->` |
| ok | `■` | `+` | |
| error | `×` | `x` | |
| info | `▸` | `>` | |
| shell | `◦` | `-` | |

## 5. Type — IBM Plex Mono, led by the wordmark; IBM Plex Sans for reading

The wordmark is typed, not drawn: lowercase `huginn` in Plex Mono SemiBold with
the cells closed up to −6 % tracking, and the raven standing where the cursor
would be. Plex Mono sets the display role, labels, terminal, code and key-value
rows; Plex Sans sets titles and body so transcripts stay readable. Source:
`explorations/wordmark/type-02-plex-mono/` and `type-system.md`.

| role (M3) | size / leading | family, weight |
|---|---|---|
| displaySmall | 44 / 52 | Plex Mono 600, −3 % |
| headlineMedium | 28 / 36 | Plex Sans 600 |
| titleLarge | 22 / 28 | Plex Sans 500 |
| bodyLarge | 16 / 24 | Plex Sans 400 |
| labelMedium | 13 / 16 | Plex Mono 500, +6 %, caps optional |
| mono (`LocalMonoStyle`) | 11 / 15 phone, 13 / 17 desktop, 13 / 18 web | Plex Mono 400 (500 for emphasis, never 700 inline) |
| wordmark | 24 to 144 | Plex Mono 600, −6 % |

- Negative tracking only above 22; never letter-space the mono. Tabular
  numerals in the mono and in timestamps. The tagline is Plex Mono italic at
  one line, never wrapped.
- The tittle lockup (raven as the dot of the i) is rejected: in a mono cell the
  bill touches the n at every size. The cursor lockup leads everywhere.
- Bundling: static TTFs in `mobile/ui/src/commonMain/composeResources/font/`
  (Plex Mono 400 / 500 / 600 + italic 400, Plex Sans 400 / 500 / 600, about
  0.9 MB); replace `FontFamily.Monospace` with the bundled mono so phone and
  desktop show the same glyphs; disable ligatures in the terminal view. Web:
  `font-display: swap` with `ui-monospace` and `system-ui` fallbacks.
- Coverage: Plex Mono has the full box and block sets, so the hairline perch
  and the half-block art render inside the app. It has one geometric-shapes
  glyph, so the state glyphs above come from the user's terminal font in tab
  titles; the apps draw dots as shapes.

## 6. Motion

A slow stepped pulse, not a spinner. Micro-motion in the 150–400 ms band;
arrivals `cubic-bezier(0.2, 0, 0, 1)`, departures ease-in; no overshoots;
nothing loops at full energy; monochrome; the resting state is always the mark.
Reduced motion stops every animation at its resting frame.

| piece | where | timing | file |
|---|---|---|---|
| blink | the app's idle state (in-app; not the README hero) | 12 s cycle, three blinks, one a double; close 100 ms, hold 60, open 160 | `anim-vector/anim-01-blink` (lid to be moved to the decided eye: cx 51.75, cy 26.3, r 3.9) |
| cursor breath | "Claude is working" in chat and the session list | the `PulseDot` table 0.35 → 1 → 0.35, 160 ms per step, 1.6 s | `anim-05-thinking` option (c); cursor one line tall, 0.55 × that wide, half a cell after the bill |
| tray attention pulse | the tray entering needs-you | tint 300 ms, badge pop 240 ms, ring twice at 1.2 s, then rest | `anim-04-attention-pulse` (sprite to be regenerated on the tile) |
| wingbeat spinner | the client while connecting | 4 frames at 8 fps; 7-bit twin | `anim-ascii/anim-01-spinner-1line` |
| landing and takeoff | attach and detach, host and client | 10 frames at 10 fps, once | `anim-02-flap-3line` |
| boot | first run in the client (7-bit); the Unicode boot as a data file | 19 frames at 12 fps, 1.6 s, once | `anim-04-boot` |
| tmux drift and attention | status-left while working and when needs-you | 1 fps, four frames | `anim-05-status` |

Not shipped: the glyph assemble, fly-and-return, the idle head-tuck, the feather
and eye-scan indicators. They stay in the folder.

## 7. Surfaces

| surface | asset | colour |
|---|---|---|
| README header | the cursor lockup as SVG outlines with a dark twin in a `<picture>`; the 48 × 16 art further down in a fenced block; the figlet banner retired | bone on the ground; ink on the light ground |
| host splash after attach, `huginn status` | the 48 × 16 fold art, then the hairline perch (square twin when the font is unknown), `(o>` in the first line | moss for the bird; ANSI slots per the pins |
| client help screen (bash, PowerShell, Termux) | today's four-row bird; the 7-bit perch where a banner is wanted; `(o>` in the first line | none, or the 16-colour table from `cli-colors.sh` / `.ps1` |
| tmux status bar | `(o>` and its state variants; `tmux.conf.snippet` with the `Tc` override and a 16-colour fallback | status on tier 1, segment on tier 3, accent on the glyph |
| Android launcher | `mark-tile.svg` as the adaptive foreground on the bone tile | ink on bone |
| Android status icon and notification | the tile glyph in white; the notification accent is primary; the brand raven as the libnotify icon on Linux | OS-tinted |
| desktop tray | the tile tinted by state, badge on needs-you, the attention pulse once | the state tokens |
| favicon, title bar, About box | tile at 32 and 16; the mark-16 head on the bone tile at 20; the cursor lockup in About | per scheme |
| installer (`wizard.bmp`) | the cursor lockup outlines, bone on the ground, "Huginn Desktop" in Plex Sans below | background, onSurfaceVariant |
| apps | `Theme.kt` from `tokens.json`; Plex bundled; dots for states; the cursor breath; the blink at idle | the scheme |

## 8. Voice

- **The name.** Huginn in prose, `huginn` as the command and the wordmark.
  Never HUGINN. The tagline is "thought, on call from anywhere", lowercase,
  one line.
- **State words.** working · needs you · waiting · shell · ok · error · info,
  the same seven in the apps, the tray tooltip, the terminal title and
  Telegram. "Needs you" is a question, never a failure; a failure is an error.
- **No emoji** in brand assets, READMEs, titles and notifications: the raven,
  the state glyphs and the words do the work. The README's section headers
  lose their emoji.
- **Quiet.** The raven carries the character; the type and the colour carry
  the quality. One accent, one second voice, nothing loud but needs-you.

## 9. To build

1. Cut `wordmark.svg`, `lockup.svg` and their dark twins from Plex Mono
   SemiBold outlines with the decided raven as the cursor (the fontTools method
   in `explorations/wordmark/type-01-schibsted/`), then the README header,
   `wizard.bmp` and the About box from them.
2. Replace `assets/brand/raven.svg`, `raven-tile.svg` and `raven-inverse.svg`
   with the fold-system files, update `generate.sh` for the new ground and run
   it: `icon.png`, `huginn.ico`, `wizard.bmp`, the Android foreground and the
   status icon.
3. `Theme.kt` from `color-04-moss-heather/tokens.json`, `Ansi.kt` from
   `moss-heather.ansi.json`, `TrayIcons.kt` on the tile and the three state
   tokens with the badge, `RavenMark.kt` on the new path; the Glance widgets on
   the same scheme; bundle Plex. The full file-by-file list is the migration
   table in `explorations/color/README.md`.
4. Terminal: `huginn-status` and the attach banner print the 48 × 16 art, the
   perch and the one-row bird; `server/tmux.conf` takes the snippet;
   `cli-colors` into the clients; `huginn-claude-title` takes the glyph table;
   the Telegram marks optional.
5. Regenerate the tray sprite from the tile, move the blink lid to the decided
   eye in the shipped SVG, keep the 7-bit terminal frames as they are.
6. Refine the three colour trade-offs before freezing the tokens.

## Record of decisions

| round | decided |
|---|---|
| 1 | the faceted raven as the primary mark; a glyph tile as the small form; the logo is the terminal art |
| 2 | `r2-05-fold` for the mark, `r2-01-snapped`'s text art for the CLI, a wing fold in the tile |
| 3 | the fold raven upright, slimmed once ("too wide"); `glyph-c`; Snapped's 24 × 8 proved to be this bird's own raster |
| 4 | Moss & Heather (added at the owner's request); needs-you = the lit accent; tint + badge; the triangle glyph (error takes the cross) |
| 5 | Plex Mono-led type; the hairline perch with the raven re-cut; `(o>` as the one-row raven |
| 6 | the still cursor lockup as the hero; today's help bird kept; the recommended motion set |
