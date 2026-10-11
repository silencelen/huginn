# Colour - palette directions, state colours and the terminal theme

Three palette directions for the Huginn overhaul. Each is a folder with
`tokens.json` (every Material 3 role plus the huginn-specific ones, with role,
L* and contrast on its ground), `swatches.html` rendered to `preview.png`,
`contrast.txt` (verbatim `tools/contrast.py` proof for every text/ground pair,
the ANSI slots, the CLI's legacy palettes, plus the L* and hue tables),
`tray-sizes.png` (the tray ravens at 22 and 16 px from `tools/render.mjs`) and a
`terminal/` layer (ANSI-16, Windows Terminal, VHS, tmux, bash/PowerShell colour
tables, the one-row raven, a rendered terminal mock). Everything is generated
from one source, `build/spec.py`: `build/palette.py` emits the files and refuses
if any rule in `build/check.py` fails, `build/readmes.py` emits these READMEs so
that no number in them is typed by hand, and `bash build/render.sh` regenerates
the lot including the previews.

| id | one line | look at |
|---|---|---|
| [`color-01-ink-bone-gold`](color-01-ink-bone-gold/) | the current palette finished: tiers, outlines, a brand-native state set, terminal; nothing the owner likes moves | [preview](color-01-ink-bone-gold/preview.png), [terminal](color-01-ink-bone-gold/terminal/preview.png) |
| [`color-02-slate-verdigris`](color-02-slate-verdigris/) | cool departure: slate ground, bone ink, verdigris accent over a bronze secondary - the raven's sheen and a patinated statue | [preview](color-02-slate-verdigris/preview.png), [terminal](color-02-slate-verdigris/terminal/preview.png) |
| [`color-03-soot-ember`](color-03-soot-ember/) | warm-hot departure: soot ground, one ember accent, oxblood in daylight, olive and ash around it | [preview](color-03-soot-ember/preview.png), [terminal](color-03-soot-ember/terminal/preview.png) |
| [`terminal/`](terminal/) | the terminal layer explained, the one-row raven, and the three terminal mocks on one sheet | [sheet](terminal/preview.png) |

## What is common to all three

- **One semantic state system.** `stateIdle` / `stateWorking` / `stateAttention`
  are one family at three lightness steps: a dim neutral, the accent, the
  accent lit up (plus the badge dot on attention). Adjacent steps are
  >= 12 L* apart in the app tokens of both schemes, in the ANSI-16 ladder
  (brightBlack / the accent slot / brightYellow) and in the monochrome tray
  tints (`onSurface` at 45% / 75% / 100%), so a template tray, a
  16-colour terminal and a reader with any colour-vision deficiency tell them
  apart. All three ladders are generator rules. `stateSuccess`, `stateError`
  (= error) and `stateInfo` (= secondary) are text semantics separated by hue;
  every pairwise dL* and ratio is printed on the sheet and in `contrast.txt`,
  and the cells below 12 are called out as hue-only.
- **Slot pins.** Four ANSI-16 slots *are* app tokens: brightRed = `error`,
  brightGreen = `stateSuccess`, brightYellow = `stateAttention`, brightBlue =
  `stateInfo`. The CLI's error is the app's error, in every direction, by rule.
- **A third voice that cannot be mistaken for a state.** `tertiary` (subagent
  markers, graph series) is >= 12 L* or >= 60 degrees from every
  state colour (neutral states by L* only); the row is on the sheet.
- **Surface tiers 0-4** (`surfaceContainerLowest` .. `Highest`), with the code
  well and terminal block *below* the page, and **three outline tiers**
  (`outline` at 3:1 for strokes, `outlineVariant` decorative, `outlineHairline`
  for dividers).
- **Proof, not promise.** Text >= 4.5:1 on every tier it is used on, UI >= 3:1,
  syntax >= 4.5:1 on the code ground, every ANSI slot but black >= 3:1 on the
  terminal ground, the CLI's 16-colour slots >= 3:1 on conhost (#000000), powershell-5.1 (#012456), campbell (#0C0C0C), xterm (#000000).
  `contrast.txt` is `tools/contrast.py` output line by line.
- **Dark and light** schemes with the same role names, so `Theme.kt` is a
  rename. The terminal block of the light app is the dark tier-0 well with the
  dark ANSI-16 (the grid is dark-only; `Ansi.kt` has one palette), as the light
  "in use" mock shows.
- **One raven in the terminal.** The tmux segment and the CLI banner use the
  ascii dimension's one-row pick `◢▆█▀` (ascii-03-compact), with `▗▆█▀` for
  fonts without Geometric Shapes and `(o)==-` for the 7-bit clients.

## State mapping

Every surface that shows a state, and which token, ANSI slot and glyph it takes.
Glyphs are single-width Unicode / 7-bit ASCII; the apps draw dots, not glyphs,
and brand assets carry no emoji.

| surface | state | token | ANSI slot | glyph | word |
|---|---|---|---|---|---|
| apps: session list, overview, Work views (`SessionOverviewView.kt`, `WorkViews.kt`) | running (today `primary`) | `stateWorking`; `PulseDot` breathes it | accent slot | `●` / `*` | working |
| | attention: permission, question (today `error`) | `stateAttention` + badge | brightYellow | `◆` / `!` | needs you |
| | idle, turn finished (today `onSurfaceVariant`) | `stateIdle`; `SettledDot` | brightBlack | `○` / `o` | waiting |
| | no Claude, shell only (today `outline`) | `outline`, hollow dot | default foreground | `◦` / `-` | shell |
| | tool ok, tests passed | `stateSuccess` | brightGreen | `■` / `+` | ok |
| | tool failed, exit != 0, disconnected | `stateError` (= `error`) | brightRed | `▲` / `x` | error |
| | informational, links | `stateInfo` (= `secondary`) | brightBlue | `▸` / `>` | info |
| desktop tray (`TrayIcons.kt`) | idle / working / attention | `stateIdle` / `stateWorking` / `stateAttention` + badge; template tray: `onSurface` at 45% / 75% / 100% + badge | - | the raven itself | same three words in the tooltip |
| terminal title (`server/bin/huginn-claude-title`, `HUGINN_ICON_RUN` / `_NEEDYOU` / `_IDLE`) | running / needs you / waiting (today the emoji 🔄 ✋ ✅) | none: the title is text | - | `●` / `*`, `◆` / `!`, `○` / `o` | running, needs you, waiting |
| Rounds (`core/.../ui/Rounds.kt` `RoundStatus`, `RoundsView.kt`) | OK "All clear" / ATTENTION "Worth a look" / ACTION "Needs you" | `stateSuccess` / `stateInfo` (it is news, not a demand: `Notifier.kt` keeps "needs you" and "news" apart) / `stateAttention` | brightGreen / brightBlue / brightYellow | `■` / `+` / `▸` / `>` / `◆` / `!` | the verdict words |
| `huginn status` (`server/bin/huginn-status`, today cyan / green / yellow / red) | headings / ok / warn / error / dim | `H_ACCENT` / `H_OK` / `H_WARN` / `H_ERROR` / `H_MUTED` from `cli-colors.sh` | accent slot / brightGreen / brightYellow / brightRed / brightBlack | `H_GLYPH` on the first line | - |
| CLI reconnect and give-up messages (`client/huginn.sh`, `huginn.ps1`) | warn / error | `H_WARN`, `H_ERROR` / `$HuginnColor.Warn`, `.Error` | brightYellow / brightRed | `H_GLYPH_ASCII` (7-bit) | - |
| Telegram (`server/appd/huginn-appd.js` `ROUND_MARK`, today ✅ ⚠️ 🔴 ❓) | ok / attention / action / unknown | text only, no colour | - | `■` / `+` / `▸` / `>` / `◆` / `!` / `◦` / `-` | All clear / Worth a look / Needs you / unknown |
| Linux notifications (`LibnotifyNotifier.kt`, today `dialog-warning` / `dialog-information`) | needs you / news | the brand raven as the icon for both; the kind is carried by the words and `--urgency` | - | - | - |
| Glance widgets (`AskWidget.kt`, `FleetWidget.kt`, today bare `GlanceTheme`) | member states | the same tokens through a `ColorProviders(light, dark)` built from the schemes | - | - | same words |

In the CLI, `warn` and `attention` share brightYellow in every direction: a
stale device and a session that needs you are the same colour, told apart by
bold and the word. Only 02 separates `warn` from the *accent* by hue.

**Motion.** `PulseDot(MaterialTheme.colorScheme.primary)` is the live marker and
`stateWorking` equals `primary` in every direction, so the breath needs no new
token. Its 0.35 floor (`WorkViews.kt` `PULSE_ALPHAS`) is decorative: at the
bottom of the breath the dot is #52442A (2.0:1) in ink-bone-gold, #2E504C (2.0:1) in slate-verdigris, #5D3C26 (1.9:1) in soot-ember
over the ground, below any text rule on purpose; the word and the dot's presence
carry the state, the motion only says "alive". The "Thinking" text fade
(0.35 -> 1 over 900 ms) follows the same rule. No other brand motion is defined
by the colour system.

## Comparison

| | 01 Ink, Bone & Gold | 02 Slate & Verdigris | 03 Soot & Ember |
|---|---|---|---|
| ground / ink | #12100F / #E8E2DA (today's) | #14171C / #E4E2DC | #141212 / #EAE3DC |
| accent (dark / light) | rune-gold #C8A45C / #6B4E10 | verdigris #5DBBA6 / #175B50 | ember #E58A4C / #7A3E14 |
| second voice | sky #7DAFEA | bronze #C9A775 | ash #C7B5A3 |
| third voice (tertiary) | #C9A3D2 (hue 289) | #C4A0D0 (hue 285) | #C29ECF (hue 284) |
| temperature | warm (parchment) | cool with warm ink and bronze | warm-hot |
| tray ladder L* dark (idle / working / attention) | 54 / 69 / 86 | 57 / 70 / 86 | 52 / 66 / 85 |
| tray ladder L* light | 48 / 35 / 18 | 47 / 34 / 18 | 47 / 33 / 17 |
| ANSI-16 ladder L* (brightBlack / accent slot / brightYellow) | 49 / 69 / 86 (steps 20, 17) | 50 / 70 / 86 (steps 20, 16) | 49 / 71 / 85 (steps 22, 14) |
| monochrome tray L* dark (45% / 75% / 100%) | 47 / 71 / 90 | 48 / 71 / 90 | 48 / 72 / 91 |
| accent in ANSI | yellow (exact) | cyan (exact); CLI 16-colour uses brightCyan | yellow is an amber; the ember = cursor, tmux, truecolor CLI |
| warn vs accent in the terminal | same hue, 17 L* | different hues (128 degrees) | same hue, 14 L* |
| success vs working | 67 degrees, 7 L* | 81 degrees, 7 L* | 47 degrees, 10 L* |
| error vs working (dark) | 37 degrees, 7 L* | 169 degrees, 7 L* | 31 degrees, 4 L* |
| distinct from stock dark themes | yes (warm, parchment) | partly (bone + bronze hold it off) | from stock themes yes; from Claude's own terracotta, no |
| raven story in the palette | the gold eye | iridescence, patina, bronze | the eye in firelight |
| shipped assets that change | none (tile, ico, bmp, SVGs keep their hex) | tile ink/bone move 2-4 units (optional) | tile ink/bone move 2-4 units (optional) |
| main risk | least visibly new | the convention it borders on | the ember is the host product's terracotta family; orange reads as warning |
| migration effort | rename + new tokens; 4 edits in Ansi.kt | rename + regenerate rasters; new Ansi.kt | rename + regenerate rasters; new Ansi.kt |

## Recommendation

**Ship `color-01-ink-bone-gold`.** The owner's palette is already the most
distinctive thing about the product's look - a warm near-black with parchment
ink is not what developer tools do - and the overhaul's visible newness should
come from the mark, the wordmark and the motion, not from throwing away the one
colour decision that was already right. 01 fixes every gap the brief lists (the
Electron tray colours, the Catppuccin demo, the mute CLI, the two-tier surfaces,
the undefined M3 roles that leak Material's own pink and purple) with zero
change to shipped assets, and its state ladder and terminal layer are now as
strong as the other two.

If the owner wants the overhaul to be *seen* in colour, take
`color-02-slate-verdigris`: it has the best raven story, the cleanest terminal
semantics (accent and warning are different hues) and is the most modern of the
three, at the cost of regenerating the rasters and of guarding the bone-and-bronze
warmth that keeps it off the convention. `color-03-soot-ember` is the most
dramatic and the least safe: its accent is a warning colour in every OS, it is
the host product's own terracotta family, and its error red can never be pure.

Whichever ground wins, the state system, the tiers, the slot pins, the terminal
layer and the one-row raven carry over unchanged - they are the deliverable; the
hue is the choice.

## Migration list

Every file in the repo that pins a colour or a state glyph today, and the token
it takes. Token names are the same in all three directions; hex values come from
the chosen direction's `tokens.json` / `terminal/*.ansi.json`.

| file | today | takes |
|---|---|---|
| `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/Theme.kt` | `DarkColors`, `LightColors`, `DarkSyntax`, `LightSyntax`, the private `Ink`/`Bg`/`Surface1`/`Surface2`/`Accent`/`AccentInk`/`Muted` | `schemes.dark` / `schemes.light` role for role (`surfaceContainerLowest..Highest`, `outline`/`outlineVariant`, the container roles, `tertiary`), `syntax.dark` / `syntax.light`; add a `HuginnColors` composition local for `stateIdle`, `stateWorking`, `stateAttention`, `stateSuccess`, `outlineHairline`, `inkMark`, `boneTile` |
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/tray/TrayIcons.kt` | `COLORS`: IDLE #7B8794, WORKING #7AA2F7, ATTENTION #E0AF68 | `stateIdle`, `stateWorking`, `stateAttention`; on a monochrome/template tray, `onSurface` at 45% / 75% / 100% plus the badge (`tokens.json` `states.monochromeTray` has the flattened hex); drop the "matches the Electron client" comment |
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/tray/RavenMark.kt` | `INK` #161310, `BONE` #E8E2DA | `inkMark`, `boneTile` |
| `mobile/app/src/main/res/values/colors.xml` | `huginn_bg` #12100F, `huginn_ink` #E8E2DA, `huginn_tile` #E8E2DA | `background`, `onBackground`, `boneTile` |
| `mobile/app/src/main/res/drawable/ic_launcher_foreground.xml` | `fillColor` #161310 | `inkMark` |
| `mobile/app/src/main/res/drawable/ic_stat_huginn.xml` | `fillColor` #FFFFFF | stays white: Android tints status icons itself; where the notification builder sets an accent, it takes `primary` |
| `mobile/app/src/main/kotlin/com/silencelen/huginn/widget/AskWidget.kt`, `FleetWidget.kt` | bare `GlanceTheme { }` (system Material You colours: `widgetBackground`, `onSurfaceVariant`) | `GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors))` from the same scheme as `Theme.kt`; member state dots take the state tokens |
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/notify/LibnotifyNotifier.kt` | `--icon=dialog-warning` (needs you), `--icon=dialog-information` (news) | `--icon=<path to the brand raven PNG>` for both; the kind stays in the words and `--urgency` |
| `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/ui/Ansi.kt` | `palette[0..15]` | `terminal/<slug>.ansi.json` `ansi16` (for 01 this is four edits: yellow, brightYellow, brightBlack, black) |
| `mobile/core/src/commonTest/kotlin/com/silencelen/huginn/TerminalGridTest.kt` | `FG` #E8E2DA, `BG` #12100F | `onBackground`, `background` |
| `mobile/ui/src/jvmTest/kotlin/com/silencelen/huginn/ui/VerbInkTest.kt` | error #E8736D / #B3261E, onSurface #E8E2DA / #1B1817 | `error`, `onSurface` of each scheme |
| `assets/brand/raven.svg`, `raven-tile.svg`, `raven-inverse.svg`, `mobile/assets/icon.svg` | fills #161310 / #E8E2DA | `inkMark`, `boneTile` (unchanged in 01; regenerate `icon.png`, `huginn.ico`, `huginn.png` via `generate.sh` in 02 / 03) |
| `assets/brand/generate.sh` | wizard.bmp `xc:'#12100F'`, `-fill '#8A8177'` | `background`, `onSurfaceVariant` |
| `mobile/app-desktop/packaging/huginn-desktop-kt.nsi` | comment naming Bg #12100F and Ink #E8E2DA | comment follows `background` / `onBackground`; `wizard.bmp` is regenerated |
| `assets/demo.tape` | `Set Theme "Catppuccin Mocha"` | the one line in `terminal/<slug>.tape.snippet` |
| `server/tmux.conf` | `status-left` with `colour238` | `terminal/tmux.conf.snippet` (status-style, window-status, left segment with `◢▆█▀`, the `Tc` override, right segment with the existing keybind hints; a 16-colour fallback is in the same file) |
| `server/bin/huginn-status` | `C`=36 cyan, `G`=32, `R`=31, `Y`=33 | the table in `terminal/cli-colors.sh`: `H_ACCENT` for headings, `H_OK`, `H_ERROR`, `H_WARN`, `H_MUTED`, `H_GLYPH` on the first line |
| `server/bin/huginn-claude-title` lines 73-75 | `ICON_RUN` 🔄, `ICON_NEEDYOU` ✋, `ICON_IDLE` ✅ | `●`, `◆`, `○` (U+25CF, U+25C6, U+25CB); `*`, `!`, `o` when the terminal is not UTF-8; still overridable via `HUGINN_ICON_*` |
| `client/huginn.sh` | no colour; only the terminal title | inline `terminal/cli-colors.sh` (7-bit ASCII): `H_WARN` / `H_ERROR` on the reconnect and give-up messages, `H_GLYPH` / `H_GLYPH_ASCII` on the help screen, same TTY / `NO_COLOR` guard |
| `client/huginn.ps1` | `Write-Host -ForegroundColor Red` / `Yellow` | `$HuginnColor.Error` / `.Warn` from `terminal/cli-colors.ps1` (the same ConsoleColor names it uses today, so this is a rename), `$HuginnGlyph` |
| `server/appd/huginn-appd.js` line 5266 `ROUND_MARK` | ✅ ⚠️ 🔴 ❓ | out of scope for brand assets (chat text), but the glyph table above gives `■` / `▸` / `◆` / `◦` if the owner wants Telegram to match |
| `README.md` | emoji section headers, stock figlet wordmark, shields.io badge colours | the wordmark and README hero are other dimensions; badges may take the accent hex (optional) |

## Not done, and why

- No light-scheme terminal palette: the CLI runs in the user's terminal, the
  demo GIF and tmux are dark, and the app's terminal grid is dark-only today;
  the light app shows the dark tier-0 well (see the light "in use" mock).
- `cli-colors.ps1` is syntax-reviewed by eye and its ConsoleColor names are the
  ones `huginn.ps1` already uses; there is no `pwsh` in the build container to
  execute it. `cli-colors.sh` was exercised on a pseudo-TTY in all three branches
  (truecolor, 16-colour, no TTY) and in a UTF-8 and a C locale.
- The sheets are rendered with Inter and JetBrains Mono from Google Fonts (and
  DejaVu Sans Mono for the one-row raven, which needs U+25E2); the app uses the
  platform's own faces. Colour is unaffected, metrics are not the point.
- `preview.png` files are palette-quantised to 256 colours to stay under
  400 KB; `build/quant.py` pins every token hex into the palette and verifies
  none moved, so a pixel sampled from a swatch is the exact token. Text
  anti-aliasing is slightly coarser than the HTML.

## Revision (after the creative director's review)

Cross-cutting fixes, in every direction: the PowerShell table's dark ConsoleColor
slots (error text 1.92:1 on the default conhost) are replaced by the
bright slots `huginn.ps1` already prints, with the four legacy palettes' ratios
in every CLI file and in `check.py`; both CLI files are pure 7-bit ASCII with
the raven built from escapes and a 7-bit fallback; the three-cell tmux
"staircase" is replaced by the ascii dimension's `◢▆█▀`; the ANSI-16 state
ladder is pinned (brightYellow = `stateAttention`) and is a rule, as are the
monochrome tray tints (45% / 75% / 100%, shown at 22 and 16 px) and the
tertiary-vs-state separation; the tmux header's false "every hex is a slot" is
replaced by the `Tc` override and a real 16-colour fallback; the swatch sheets'
`huginn status` panel uses the CLI slots; the light mock's terminal block is the
dark well; the terminal mocks lost their macOS chrome; and the READMEs are
generated so that their numbers cannot drift. Per direction: 01's tertiary is off
green; 02's success is a leaf green and its "complementary" claim is corrected;
03's attention/brightYellow is a cream-amber, its ember is in the CLI, its light
working/error and both tertiaries are moved apart, and the Claude-terracotta
collision is named as its main risk rather than painted over. Not changed, on
purpose: 03's ember stays orange (an amber would make it a hotter 01), and
`warn` shares brightYellow with `attention` in the CLI (disclosed above).
