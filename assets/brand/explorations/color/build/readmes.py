#!/usr/bin/env python3
"""Emit the READMEs of the colour explorations (one per direction, the folder root, the
terminal index) so that every ratio, L* and hue in them is computed by build/colorlib.py
from build/spec.py, never typed by hand.

    python3 build/readmes.py            (run from assets/brand/explorations/color)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from colorlib import contrast, lstar, hsl, alpha_over  # noqa: E402
from spec import (DIRECTIONS, TRAY_ORDER, TRAY_MIN_DL, ANSI_LADDER_MIN_DL, MONO_TRAY_ALPHAS,  # noqa: E402
                  TERTIARY_MIN_DL, TERTIARY_MIN_DHUE, SLOT_PINS, LEGACY_PALETTES, LEGACY_MIN,
                  CONSOLE_NAME, SGR, GLYPH, STATE_GLYPH)
from check import ansi_ladder, mono_ladder, dhue  # noqa: E402
from palette import ROOT, write, cli_truecolor_hex, STATE_LABEL, STATE_KEYS  # noqa: E402

D = {d['id']: d for d in DIRECTIONS}
PULSE_FLOOR = 0.35      # WorkViews.kt PULSE_ALPHAS[0]


def R(fg, bg):
    return f'{contrast(fg, bg):.2f}:1'


def L(h):
    return f'{lstar(h):.0f}'


def H(h):
    return f'{hsl(h)[0]:.0f}'


def dl(a, b):
    return f'{abs(lstar(a) - lstar(b)):.0f}'


def ladder(d, scheme):
    return ' / '.join(L(d[scheme][k]) for k in TRAY_ORDER)


def ansi_ladder_txt(d):
    lad = ansi_ladder(d)
    return ' / '.join(f'{Lv:.0f}' for _, _, Lv in lad) + ' (steps ' + ', '.join(f'{abs(b[2] - a[2]):.0f}' for a, b in zip(lad, lad[1:])) + ')'


def mono_txt(d, scheme):
    return ' / '.join(f'{Lv:.0f}' for _, _, _, Lv in mono_ladder(d[scheme]))


def pct(k):
    return f'{MONO_TRAY_ALPHAS[k]:.0%}'


def pulse_floor(d):
    t = d['dark']
    h = alpha_over(t['stateWorking'], t['background'], PULSE_FLOOR)
    return h, contrast(h, t['background'])


# ----------------------------------------------------------------- 01
def readme_01():
    d = D['color-01-ink-bone-gold']
    t, l, a, c = d['dark'], d['light'], d['ansi'], d['cli']
    old_muted = '#8A8177'
    return f'''# color-01-ink-bone-gold - Ink, Bone & Gold

![preview](preview.png)

**The palette Huginn already has, finished.** Warm near-black ground, parchment
ink, one rune-gold accent. Nothing the owner already likes moves; the gaps the
brief names are filled: five surface tiers instead of two, three outline tiers,
a full semantic set (idle / working / attention / success / error / info) that is
brand-native instead of borrowed from the Electron tray, syntax tuned to a real
code ground, and a terminal layer (ANSI-16, tmux, VHS, CLI) that speaks the same
hex. The three tray states are one family at three lightness steps - dim stone,
the gold, the gold lit up - so a monochrome tray, a 16-colour terminal and a
colour-blind reader all tell them apart without hue; and four ANSI slots are
pinned to app tokens (brightRed = error, brightGreen = success, brightYellow =
attention, brightBlue = info), so the CLI's error is the app's error.

Files: `tokens.json` (every token with role, L*, contrast on its ground),
`swatches.html` (this sheet), `contrast.txt` (verbatim `tools/contrast.py` proof
for every pair plus the L*, hue and legacy-palette tables), `tray-sizes.png` (the
six tray icons at 22 and 16 px, dark and light bars, rendered with
`tools/render.mjs` and upscaled with hard pixels), `terminal/` (ANSI-16, Windows
Terminal, VHS, tmux, bash/PowerShell tables, the glyph, terminal mock and its
render).

## Intended uses

The default direction: the app (phone + desktop), the tray, the README, the VHS
demo and the CLI. It is a drop-in for `Theme.kt` because every existing hex is
either kept or given a tier name; the migration is a rename, not a redesign.

## Strengths

- Zero brand drift: ground, ink, accent, `inkMark`, `boneTile`, error and
  secondary are today's values. Everything shipped (icons, installer bitmap,
  README SVGs) stays valid.
- The warmest of the three: reads as parchment and candle-light, not as a code
  editor. The gold accent doubles as ANSI yellow and as the tmux/cursor colour,
  so the CLI is on-brand with a plain `\\033[33m`.
- The state ladder is monochrome-first by construction: L* {ladder(d, 'dark')}
  dark and {ladder(d, 'light')} light in the app tokens; {ansi_ladder_txt(d)} in
  ANSI-16 (brightBlack / yellow / brightYellow); {mono_txt(d, 'dark')} dark and
  {mono_txt(d, 'light')} light as monochrome tray tints at {pct('stateIdle')} /
  {pct('stateWorking')} / {pct('stateAttention')}. All three ladders are checked by
  `build/check.py`.
- Every text pair is AA or better on every tier it is used on, including
  secondary text on dialogs, which the current `Muted` fails
  ({old_muted} is {R(old_muted, t['surfaceContainerHigh'])} on tier 3).
- The terminal's error *is* the app's error: brightRed `{a['brightRed']}` = `error`,
  brightGreen `{a['brightGreen']}` = `stateSuccess`, brightYellow `{a['brightYellow']}` =
  `stateAttention`, brightBlue `{a['brightBlue']}` = `stateInfo`.

## Weaknesses (honest)

- Gold is a warm hue and so are warning and attention. In the terminal `warn`
  and `attention` share the brightYellow slot (`{a['brightYellow']}`) and the accent is
  yellow (`{a['yellow']}`): the same hue, {dl(a['yellow'], a['brightYellow'])} L* apart. A
  CLI "warn" and a session that "needs you" are the same colour, told apart by
  bold and the word. If the owner wants a cold warning colour, that is direction 2.
- Light-scheme `stateAttention` (`{l['stateAttention']}`) is a very dark umber. It is
  {dl(l['stateWorking'], l['stateAttention'])} L* from working as the rule demands, but in light mode
  "attention" reads as "darkest", not "brightest"; the badge dot and the word carry
  the meaning.
- The light-scheme primary darkens from #7A5A16 to `{l['primary']}` so that idle (a
  {R(l['stateIdle'], l['background'])} grey, L* {L(l['stateIdle'])}) and working (L* {L(l['stateWorking'])}) sit
  {dl(l['stateIdle'], l['stateWorking'])} L* apart. If the owner would rather keep #7A5A16, idle must
  become a dot-only colour (#8A8177, {R('#8A8177', l['background'])}).
- `Ansi.kt` takes four edits, not three: brightYellow moves from #E3C169 to
  `{a['brightYellow']}` to make the 16-colour ladder (the syntax `number` keeps #E3C169).
- The lilac tertiary `{t['tertiary']}` is {dhue(t['tertiary'], d['syntax_dark']['keyword']):.0f} degrees from
  the syntax `keyword` `{d['syntax_dark']['keyword']}`. They never share a surface (code
  well vs list rows), but a reader who knows the editor may read the subagent
  marker as "a keyword colour".
- It is the least *new*: a reviewer who wants the overhaul to be visible from
  across the room will not see it in this palette; they will see it in the
  mark and the wordmark.

## Relation to the current mark and palette

Same mark, same `inkMark` {t['inkMark']} on `boneTile` {t['boneTile']}, same ground. This is
the evolution; 02 and 03 are the departures.

## Every change from today's values

Every ratio below is computed by `build/readmes.py` from `build/spec.py`.

Dark scheme (Theme.kt `DarkColors`):

| token | today | here | why |
|---|---|---|---|
| background / surface | #12100F | {t['background']} | kept |
| onBackground / onSurface (Ink) | #E8E2DA | {t['onBackground']} | kept |
| surfaceContainerLow | Surface1 #1B1817 | {t['surfaceContainerLow']} | kept, named tier 1 |
| surfaceContainer | Surface2 #241F1D | {t['surfaceContainer']} | kept, named tier 2 |
| surfaceContainerLowest | - | {t['surfaceContainerLowest']} | new tier 0: code wells and the terminal block sit *below* the page, the way a terminal does |
| surfaceContainerHigh | = Surface2 | {t['surfaceContainerHigh']} | new tier 3: dialogs and menus were the same colour as cards |
| surfaceContainerHighest | - | {t['surfaceContainerHighest']} | new tier 4: chips and fields on a dialog |
| onSurfaceVariant (Muted) | {old_muted} | {t['onSurfaceVariant']} | {old_muted} is {R(old_muted, t['background'])} on the ground but {R(old_muted, t['surfaceContainerHigh'])} on tier 3 and {R(old_muted, t['surfaceContainerHighest'])} on tier 4: secondary text on a dialog failed AA. It survives as `stateIdle`. The new value is {R(t['onSurfaceVariant'], t['surfaceContainerHighest'])} on tier 4 |
| outline | #3A3431 | {t['outline']} | M3's outline is the 3:1 stroke (text fields); #3A3431 is {R('#3A3431', t['background'])}. The old hex becomes `outlineVariant` |
| outlineVariant | #2A2523 | {t['outlineVariant']} | decorative borders, today's outline |
| outlineHairline | - | {t['outlineHairline']} | huginn-specific divider tier, today's outlineVariant |
| primary / onPrimary (Accent) | #C8A45C / #241C09 | {t['primary']} / {t['onPrimary']} | kept ({R(t['onPrimary'], t['primary'])}) |
| primaryContainer / on | Surface2 / Ink | {t['primaryContainer']} / {t['onPrimaryContainer']} | a selected row or accent chip was a grey surface; now it carries the gold ({R(t['onPrimaryContainer'], t['primaryContainer'])}) |
| secondary / onSecondary | #7DAFEA / #0B1622 | {t['secondary']} / {t['onSecondary']} | kept |
| secondaryContainer / on | - | {t['secondaryContainer']} / {t['onSecondaryContainer']} | new; without them M3 falls back to its own purple |
| tertiary | - (M3 default pink #EFB8C8) | {t['tertiary']} | a dusty lilac for subagent markers and graph series, {R(t['tertiary'], t['background'])} on the ground, >= {TERTIARY_MIN_DL:.0f} L* or >= {TERTIARY_MIN_DHUE:.0f} degrees from every state colour (checked) |
| error / onError | #E8736D / #2A0E0C | {t['error']} / {t['onError']} | kept; `SOFT_VERB_ALPHA` 0.7 still yields {alpha_over(t['error'], t['background'], 0.7)} ({R(alpha_over(t['error'], t['background'], 0.7), t['background'])}, a UI tint, not text) |
| errorContainer / on | - | {t['errorContainer']} / {t['onErrorContainer']} | new, for banners and toasts |
| stateIdle | tray #7B8794 (blue-grey) | {t['stateIdle']} | the brand's stone, L* {L(t['stateIdle'])} |
| stateWorking | tray #7AA2F7 (blue) | {t['stateWorking']} | the accent: Huginn thinking is the gold eye; `PulseDot` already animates `primary` |
| stateAttention | tray #E0AF68 (amber) | {t['stateAttention']} | the gold lit up, L* {L(t['stateAttention'])}, + badge dot; also ANSI brightYellow |
| stateSuccess | - | {t['stateSuccess']} | = ANSI brightGreen = syntax `string`; one green everywhere |
| stateError / stateInfo | - | = error / = secondary | aliases, so the tray and the chat never pick different reds; = ANSI brightRed / brightBlue |
| inkMark / boneTile | #161310 / #E8E2DA | {t['inkMark']} / {t['boneTile']} | kept ({R(t['inkMark'], t['boneTile'])}) |
| syntax comment | #8A8177 | {d['syntax_dark']['comment']} | {R(d['syntax_dark']['comment'], t['surfaceContainerLowest'])} on the new tier-0 code ground; the other seven classes are unchanged |

Light scheme (`LightColors`):

| token | today | here | why |
|---|---|---|---|
| background / onBackground | #FAF7F2 / #1B1817 | {l['background']} / {l['onBackground']} | kept |
| surfaceContainer | surfaceVariant #EFE9E0 | {l['surfaceContainer']} | kept, named tier 2 |
| surfaceContainerLowest / Low / High / Highest | - | {l['surfaceContainerLowest']} / {l['surfaceContainerLow']} / {l['surfaceContainerHigh']} / {l['surfaceContainerHighest']} | new tiers, one step each |
| onSurfaceVariant | #5C544B | {l['onSurfaceVariant']} | kept ({R(l['onSurfaceVariant'], l['surfaceContainerHighest'])} on tier 4) |
| outline | #D3CAC0 | {l['outline']} | the 3:1 stroke ({R(l['outline'], l['background'])}); the old hex becomes `outlineVariant`, hairline {l['outlineHairline']} new |
| primary | #7A5A16 | {l['primary']} | one step darker so idle (L* {L(l['stateIdle'])}) and working (L* {L(l['stateWorking'])}) sit {dl(l['stateIdle'], l['stateWorking'])} L* apart; {R(l['primary'], l['background'])} as text |
| primaryContainer / on | - | {l['primaryContainer']} / {l['onPrimaryContainer']} | new |
| secondary | - (M3 default #625B71) | {l['secondary']} | `LightSyntax.function`, which already was the de-facto link blue |
| tertiary | - | {l['tertiary']} | the lilac, darkened ({R(l['tertiary'], l['background'])}) |
| error / onError | #B3261E / - | {l['error']} / {l['onError']} | kept; containers {l['errorContainer']} / {l['onErrorContainer']} new |
| states | - | idle {l['stateIdle']}, working {l['stateWorking']}, attention {l['stateAttention']}, success {l['stateSuccess']} | the ladder inverted: in light mode prominence is darkness (L* {ladder(d, 'light')}) |

Terminal (`mobile/core/.../ui/Ansi.kt` palette[0..15]): four edits to today's
sixteen - `yellow` #C7A24A becomes the accent {a['yellow']} exactly, `brightBlack`
#6B645D becomes `outline` {a['brightBlack']} ({R(a['brightBlack'], a['background'])} instead of {R('#6B645D', a['background'])}, so dim
tool output stays readable), `black` #3B3733 becomes `outlineVariant` {a['black']}
(same intent: visible against the ground), and `brightYellow` #E3C169 becomes
`stateAttention` {a['brightYellow']} so the 16-colour ladder has a real top step
(#E3C169 was {dl('#E3C169', a['yellow'])} L* from the accent). The other twelve slots are unchanged.

## Revision (after the creative director's review)

- `tertiary` was #A3BE8C, 0 L* and 6 degrees from `stateSuccess`: the subagent
  marker and the success dot were one green. Now {t['tertiary']} / {l['tertiary']} (light),
  and `build/check.py` has a tertiary-vs-every-state rule (>= {TERTIARY_MIN_DL:.0f} L* or
  >= {TERTIARY_MIN_DHUE:.0f} degrees; neutrals by L* only) with the row printed on the sheet.
- `stateSuccess` #96C27E -> {t['stateSuccess']}, the existing brightGreen / `string` green,
  so one green serves app, syntax and terminal (slot pin).
- The 16-colour state ladder was 49 / 69 / 79 (a 10 L* top step, under the rule
  the README claimed). brightYellow is now `stateAttention` {a['brightYellow']}:
  {ansi_ladder_txt(d)}, and the ladder is a generator rule.
- `cli-colors.ps1` mapped Error = DarkRed, Ok = DarkGreen ({R(LEGACY_PALETTES['conhost']['red'], LEGACY_PALETTES['conhost']['ground'])} and
  {R(LEGACY_PALETTES['conhost']['green'], LEGACY_PALETTES['conhost']['ground'])} on the default conhost). Both CLI tables now use the bright slots
  ({', '.join(f'{k} = {CONSOLE_NAME[v]} / {SGR[v]}' for k, v in c.items())}), which are
  today's `huginn.ps1` colours, with every legacy palette's ratio in the header.
- Both CLI files are pure 7-bit ASCII (the generator refuses otherwise); the
  raven is built from escapes, gated on a UTF-8 locale / code page, with
  `H_GLYPH_ASCII` = `{GLYPH['ascii']}` for the clients.
- The tmux glyph `▄▟▘` (a staircase, not a bird) is replaced by the ascii
  dimension's `{GLYPH['unicode']}`; the tmux header now states the `Tc` override and the
  snippet ships a real 16-colour fallback instead of claiming every hex was a slot.
- Monochrome tray tints are {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')} (L* {mono_txt(d, 'dark')}),
  checked, and the sheet shows the six tray icons at 22 and 16 px.
- The swatch sheet's `huginn status` panel is drawn from the CLI slots (it had
  coloured `warn` with the accent) and the light mock's terminal block is the
  dark tier-0 well; the terminal mock lost its macOS chrome.
- This README is generated (`build/readmes.py`): the hand-typed "4.8:1 / 3.5:1"
  are now {R(old_muted, t['background'])} / {R(old_muted, t['surfaceContainerHighest'])}, by construction.
'''


# ----------------------------------------------------------------- 02
def readme_02():
    d = D['color-02-slate-verdigris']
    t, l, a, c = d['dark'], d['light'], d['ansi'], d['cli']
    return f'''# color-02-slate-verdigris - Slate & Verdigris

![preview](preview.png)

**Night sky over oxidised bronze.** A cool slate ground (`{t['background']}`) with a
warm bone ink (`{t['onBackground']}`), one verdigris accent (`{t['primary']}`) - the patina on a
bronze raven, the green sheen on a live one - over a bronze secondary
(`{t['secondary']}`) and a lilac tertiary. It is the direction where the overhaul is
*visible in colour*: the ground is no longer near-black-brown, the accent is no
longer gold, and yet the bone ink and the bronze keep it off the teal-on-charcoal
convention that half the dark themes share. The accent sits in the ANSI cyan
slot, so yellow stays free for warnings and the terminal's semantics are the
cleanest of the three.

Files: `tokens.json`, `swatches.html` -> `preview.png`, `contrast.txt` (verbatim
`tools/contrast.py` proof plus the L*, hue and legacy-palette tables),
`tray-sizes.png` (the six tray icons at 22 and 16 px via `tools/render.mjs`), `terminal/`.

## Intended uses

The whole product if the owner wants the overhaul seen in colour; strongest on
the desktop app and in the terminal, slightly less at night on the phone, where
the slate reads a touch cooler than the parchment direction.

## Strengths

- Modern without being generic: bone ink and bronze warmth on slate is not what
  Tokyo Night, Catppuccin or One Dark do.
- The cleanest terminal semantics: the accent (cyan) and warning (brightYellow)
  are different hues, {dhue(a['cyan'], a['brightYellow']):.0f} degrees apart; `ok` is a leaf green
  (`{a['brightGreen']}`, hue {H(a['brightGreen'])}) {dhue(a['brightGreen'], a['cyan']):.0f} degrees from the verdigris, so git's greens and
  the accent never blur.
- Tray ladder L* {ladder(d, 'dark')} dark / {ladder(d, 'light')} light; {ansi_ladder_txt(d)} in
  ANSI-16; monochrome tints {mono_txt(d, 'dark')} / {mono_txt(d, 'light')}. Working (verdigris)
  and attention (pale bronze `{t['stateAttention']}`) are opposed in temperature (cool vs
  warm) as well as {dl(t['stateWorking'], t['stateAttention'])} L* apart, which survives every colour-vision
  deficiency.
- The best raven story in the palette itself: iridescence, patina, bronze.
- Slot pins: brightRed `{a['brightRed']}` = `error`, brightGreen = `stateSuccess`,
  brightYellow = `stateAttention`, brightBlue = `stateInfo`.

## Weaknesses (honest)

- Furthest from what ships: every surface, `inkMark` {t['inkMark']} and `boneTile`
  {t['boneTile']} move (2-4 units from #161310 / #E8E2DA; the owner may keep today's
  tile and only the app changes).
- It borders on the dark-dev-tool convention; only the bone ink and the bronze
  hold it off, and a future "let's make the ink pure white" would collapse it.
- The light primary `{l['primary']}` is a pine green rather than a patina; the light
  scheme carries less of the story.
- `stateInfo` `{t['stateInfo']}` and working `{t['stateWorking']}` are {dl(t['stateInfo'], t['stateWorking'])} L* apart and both cool
  ({dhue(t['stateInfo'], t['stateWorking']):.0f} degrees); info is text-only and always has a word.
- In the terminal `warn` and `attention` share brightYellow (`{a['brightYellow']}`): a
  stale device and a session that needs you are the same colour, bold and the
  word apart.
- The 16-colour CLI accent is brightCyan (`{a['brightCyan']}` with the scheme installed,
  the lit verdigris) rather than the exact accent, because DarkCyan is only
  {R(LEGACY_PALETTES['conhost']['cyan'], LEGACY_PALETTES['conhost']['ground'])} on the default conhost; the truecolor branch pins `{cli_truecolor_hex(d, 'accent')}`.

## Relation to the current mark and palette

Same mark; the tile moves from #161310 / #E8E2DA to {t['inkMark']} / {t['boneTile']} to sit in
the slate (optional). The gold is gone: the accent is the patina, the bronze
secondary is where the warmth went.

## Values

Dark: ground {t['background']}, ink {t['onBackground']}, tiers {t['surfaceContainerLowest']} / {t['surfaceContainerLow']} /
{t['surfaceContainer']} / {t['surfaceContainerHigh']} / {t['surfaceContainerHighest']}; onSurfaceVariant {t['onSurfaceVariant']}
({R(t['onSurfaceVariant'], t['surfaceContainerHighest'])} on tier 4); outline {t['outline']} ({R(t['outline'], t['background'])}); primary {t['primary']} ({R(t['primary'], t['background'])}),
onPrimary {t['onPrimary']} ({R(t['onPrimary'], t['primary'])}); secondary {t['secondary']} ({R(t['secondary'], t['background'])}); tertiary {t['tertiary']}
({R(t['tertiary'], t['background'])}); error {t['error']} ({R(t['error'], t['background'])}); states idle {t['stateIdle']}, working {t['stateWorking']},
attention {t['stateAttention']}, success {t['stateSuccess']}, info {t['stateInfo']}.

Light: ground {l['background']}, ink {l['onBackground']}; primary {l['primary']} ({R(l['primary'], l['background'])}); secondary
{l['secondary']}; tertiary {l['tertiary']} ({R(l['tertiary'], l['background'])}); error {l['error']}; states idle {l['stateIdle']},
working {l['stateWorking']}, attention {l['stateAttention']}, success {l['stateSuccess']}, info {l['stateInfo']}
(L* {ladder(d, 'light')}).

Terminal: `Ansi.kt` palette[0..15] is replaced wholesale by `terminal/slate-verdigris.ansi.json`;
the accent is `cyan` {a['cyan']}, the cursor too.

## Revision (after the creative director's review)

- `stateSuccess` was #8FCBA3, a mint {dhue('#8FCBA3', t['stateWorking']):.0f} degrees and {dl('#8FCBA3', t['stateWorking'])} L* from the
  verdigris: the two most frequent dots in the session list were two teals. Now
  `{t['stateSuccess']}` (hue {H(t['stateSuccess'])}, {dhue(t['stateSuccess'], t['stateWorking']):.0f} degrees away), and the ANSI greens moved with it
  (green {a['green']}, brightGreen {a['brightGreen']} = success); brightRed is now the app's
  `error` {a['brightRed']} (slot pin).
- `tertiary` #B9A3E3 was {dhue('#B9A3E3', t['stateInfo']):.0f} degrees from `stateInfo`; now {t['tertiary']} / {l['tertiary']},
  which clears the new tertiary rule against every state.
- brightYellow is `stateAttention` {a['brightYellow']} (was #EBC98A), so the ANSI ladder
  is {ansi_ladder_txt(d)} and a generator rule.
- "the two hues are complementary" was wrong (verdigris hue {H(t['stateWorking'])}, pale bronze
  hue {H(t['stateAttention'])}: {dhue(t['stateWorking'], t['stateAttention']):.0f} degrees); it now says opposed in temperature, and the
  L* figures carry the claim.
- CLI: bright slots in both tables ({', '.join(f'{k} = {CONSOLE_NAME[v]} / {SGR[v]}' for k, v in c.items())}),
  legacy ratios in the header, pure 7-bit ASCII, `H_GLYPH` / `H_GLYPH_ASCII`.
- tmux glyph `{GLYPH['unicode']}` from ascii-03; `Tc` override; real 16-colour fallback.
- Monochrome tray {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')} (L* {mono_txt(d, 'dark')}), checked and shown at 22 / 16 px.
- Swatch `huginn status` panel from the CLI slots; light mock's terminal is the
  dark well; terminal mock without macOS chrome; README generated.
'''


# ----------------------------------------------------------------- 03
def readme_03():
    d = D['color-03-soot-ember']
    t, l, a, c = d['dark'], d['light'], d['ansi'], d['cli']
    return f'''# color-03-soot-ember - Soot & Ember

![preview](preview.png)

**A banked fire.** Soot ground (`{t['background']}`), warm bone ink, and one ember
(`{t['primary']}`) that is the only saturated thing on the screen: the raven's eye
catching firelight. Around it, warm ash for the second voice (`{t['secondary']}`), an
olive success, a crimson error, and a cream-amber attention. In daylight the
ember cools to oxblood-brown (`{l['primary']}`) and the error to crimson (`{l['error']}`).
Restrained by rule: everything but the ember is a near-neutral or a muted
tool colour.

Files: `tokens.json`, `swatches.html` -> `preview.png`, `contrast.txt`, `tray-sizes.png`
(the six tray icons at 22 and 16 px via `tools/render.mjs`), `terminal/`.

## Intended uses

The desktop app, the installer and the README hero, where one hot accent on dark
has room to be dramatic; less suited to Android notification and permission
surfaces, where orange already means "warning" to the OS and the user.

## Strengths

- The most distinctive and the most "brand" of the three: unmistakably the
  raven's eye in firelight.
- The warmest ground, keeping the "terminal that does not look like a terminal
  emulator" intent of `Theme.kt`.
- Tray ladder L* {ladder(d, 'dark')} dark / {ladder(d, 'light')} light; {ansi_ladder_txt(d)} in ANSI-16;
  monochrome tints {mono_txt(d, 'dark')} / {mono_txt(d, 'light')}. Attention `{t['stateAttention']}` is a
  cream-amber flash {dl(t['stateWorking'], t['stateAttention'])} L* above the ember.
- Olive success (`{t['stateSuccess']}`, hue {H(t['stateSuccess'])}) and crimson error (`{t['error']}`, hue {H(t['error'])})
  separate by hue and by {dl(t['stateSuccess'], t['error'])} L*.
- The CLI really shows the ember: the truecolor branch pins `{cli_truecolor_hex(d, 'accent')}` for
  `H_ACCENT` / `$HuginnSgr.Accent`; the 16-colour branch falls back to the amber
  yellow slot.

## Weaknesses (honest)

- **Main risk: the ember is Claude's colour.** `{t['primary']}` (hue {H(t['primary'])}) on near-black
  is the same terracotta-on-dark family as the host product's own accent. For a
  Claude Code client, an ember-on-soot app and CLI can read as wearing the host's
  identity rather than having one. The direction keeps the ember on purpose -
  moving it to amber would make it a hotter copy of direction 1 - and names the
  risk here and in the comparison table.
- Ember (hue {H(t['primary'])}, L* {L(t['primary'])}) and error (`{t['error']}`, hue {H(t['error'])}, L* {L(t['error'])}) are
  {dhue(t['primary'], t['error']):.0f} degrees and {dl(t['primary'], t['error'])} L* apart: error can never be a pure red here and
  always comes with a word or an icon.
- The ember has no ANSI slot: the yellow slot is an amber (`{a['yellow']}`) so git still
  reads as yellow; the ember lives in the cursor, the tmux segment and the
  truecolor CLI branch only.
- Orange is a warning colour in every OS; a working session glowing ember reads
  as "something is wrong" until learned.
- Light scheme: working `{l['primary']}` (hue {H(l['primary'])}) and error `{l['error']}` (hue {H(l['error'])}) are
  {dhue(l['primary'], l['error']):.0f} degrees and {dl(l['primary'], l['error'])} L* apart; better than before, still two dark warm
  dots that need their words.
- `inkMark` {t['inkMark']} / `boneTile` {t['boneTile']} move a few units from #161310 / #E8E2DA, so
  tile rasters are rebuilt (or today's tile is kept).
- In the terminal `warn` and `attention` share brightYellow (`{a['brightYellow']}`).

## Relation to the current mark and palette

Same mark on a slightly warmer tile. The gold is replaced by the ember; the
parchment ink stays. Of the three it is the one that most changes what the
product *feels* like without changing what it *is*.

## Values

Dark: ground {t['background']}, ink {t['onBackground']}, tiers {t['surfaceContainerLowest']} / {t['surfaceContainerLow']} /
{t['surfaceContainer']} / {t['surfaceContainerHigh']} / {t['surfaceContainerHighest']}; onSurfaceVariant {t['onSurfaceVariant']};
outline {t['outline']} ({R(t['outline'], t['background'])}); primary {t['primary']} ({R(t['primary'], t['background'])}), onPrimary {t['onPrimary']}
({R(t['onPrimary'], t['primary'])}); secondary {t['secondary']}; tertiary {t['tertiary']} ({R(t['tertiary'], t['background'])}, a dusty
lavender, >= {TERTIARY_MIN_DL:.0f} L* or >= {TERTIARY_MIN_DHUE:.0f} degrees from every state); error {t['error']}
({R(t['error'], t['background'])}); states idle {t['stateIdle']}, working {t['stateWorking']}, attention {t['stateAttention']},
success {t['stateSuccess']}, info {t['stateInfo']}.

Light: ground {l['background']}, ink {l['onBackground']}; primary {l['primary']} ({R(l['primary'], l['background'])}, {R(l['primary'], l['surfaceContainerHighest'])} on
tier 4); secondary {l['secondary']}; tertiary {l['tertiary']} ({R(l['tertiary'], l['background'])}); error {l['error']}
({R(l['error'], l['background'])}); states idle {l['stateIdle']}, working {l['stateWorking']}, attention {l['stateAttention']},
success {l['stateSuccess']}, info {l['stateInfo']} (L* {ladder(d, 'light')}).

Terminal: `Ansi.kt` palette[0..15] replaced by `terminal/soot-ember.ansi.json`; yellow is the amber
{a['yellow']}, brightYellow is `stateAttention` {a['brightYellow']}, the cursor is the ember {a['cursor']}.

## Revision (after the creative director's review)

- The ANSI ladder was 49 / 71 / 77 with working and attention the same amber
  ({dl(a['yellow'], '#E8B467')} L*, 1 degree); brightYellow is now `stateAttention` `{a['brightYellow']}` (the
  app token moved from #F8D9B8 to it): {ansi_ladder_txt(d)}, a generator rule, and
  the swatch sheet's `huginn status` panel now uses the ANSI slots like the mock.
- The ember is in the CLI: `cli_truecolor_hex()` pins `{cli_truecolor_hex(d, 'accent')}` for the accent in
  the truecolor branch (it was the amber slot in every branch while the README
  said otherwise).
- Light working / error were #86351F and #B81E3F ({dhue('#86351F', '#B81E3F'):.0f} degrees, {dl('#86351F', '#B81E3F')} L*): now
  `{l['primary']}` and `{l['error']}` ({dhue(l['primary'], l['error']):.0f} degrees); light tertiary moved from #7B2D2D (a third
  dark red) to `{l['tertiary']}`.
- Dark tertiary was #D19A94, a salmon {dl('#D19A94', t['error'])} L* from error; now `{t['tertiary']}`, and
  the restraint claim holds again: the ember is the only saturated warm.
- The Claude-terracotta collision is owned as the main risk (above and in the
  root comparison table) rather than moved to amber.
- CLI: bright slots ({', '.join(f'{k} = {CONSOLE_NAME[v]} / {SGR[v]}' for k, v in c.items())}), legacy ratios,
  pure ASCII, `H_GLYPH` / `H_GLYPH_ASCII`; tmux glyph `{GLYPH['unicode']}`, `Tc` override and a
  real 16-colour fallback; monochrome tray {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')}
  (L* {mono_txt(d, 'dark')}) checked and shown at 22 / 16 px; light mock's terminal is the dark
  well; terminal mock without macOS chrome; README generated.
'''


# ----------------------------------------------------------------- root
def comparison_row(label, f):
    return f'| {label} | ' + ' | '.join(f(D[i]) for i in ('color-01-ink-bone-gold', 'color-02-slate-verdigris', 'color-03-soot-ember')) + ' |'


def root_readme():
    d1, d2, d3 = D['color-01-ink-bone-gold'], D['color-02-slate-verdigris'], D['color-03-soot-ember']
    g = {k: (u, s) for k, (u, s) in STATE_GLYPH.items()}

    def gl(k):
        u, s = g[k]
        return f'`{u}` / `{s}`'
    pulse = {d['id']: pulse_floor(d) for d in DIRECTIONS}
    rows = '\n'.join([
        comparison_row('ground / ink', lambda d: f'{d["dark"]["background"]} / {d["dark"]["onBackground"]}' + (' (today\'s)' if d is d1 else '')),
        comparison_row('accent (dark / light)', lambda d: f'{d["accent_name"]} {d["dark"]["accent"]} / {d["light"]["accent"]}'),
        comparison_row('second voice', lambda d: {d1['id']: 'sky', d2['id']: 'bronze', d3['id']: 'ash'}[d['id']] + f' {d["dark"]["secondary"]}'),
        comparison_row('third voice (tertiary)', lambda d: f'{d["dark"]["tertiary"]} (hue {H(d["dark"]["tertiary"])})'),
        comparison_row('temperature', lambda d: {d1['id']: 'warm (parchment)', d2['id']: 'cool with warm ink and bronze', d3['id']: 'warm-hot'}[d['id']]),
        comparison_row('tray ladder L* dark (idle / working / attention)', lambda d: ladder(d, 'dark')),
        comparison_row('tray ladder L* light', lambda d: ladder(d, 'light')),
        comparison_row('ANSI-16 ladder L* (brightBlack / accent slot / brightYellow)', lambda d: ansi_ladder_txt(d)),
        comparison_row(f'monochrome tray L* dark ({pct("stateIdle")} / {pct("stateWorking")} / {pct("stateAttention")})', lambda d: mono_txt(d, 'dark')),
        comparison_row('accent in ANSI', lambda d: {d1['id']: 'yellow (exact)', d2['id']: 'cyan (exact); CLI 16-colour uses brightCyan', d3['id']: 'yellow is an amber; the ember = cursor, tmux, truecolor CLI'}[d['id']]),
        comparison_row('warn vs accent in the terminal', lambda d: {d1['id']: f'same hue, {dl(d["ansi"]["yellow"], d["ansi"]["brightYellow"])} L*', d2['id']: f'different hues ({dhue(d["ansi"]["cyan"], d["ansi"]["brightYellow"]):.0f} degrees)', d3['id']: f'same hue, {dl(d["ansi"]["yellow"], d["ansi"]["brightYellow"])} L*'}[d['id']]),
        comparison_row('success vs working', lambda d: f'{dhue(d["dark"]["stateSuccess"], d["dark"]["stateWorking"]):.0f} degrees, {dl(d["dark"]["stateSuccess"], d["dark"]["stateWorking"])} L*'),
        comparison_row('error vs working (dark)', lambda d: f'{dhue(d["dark"]["error"], d["dark"]["stateWorking"]):.0f} degrees, {dl(d["dark"]["error"], d["dark"]["stateWorking"])} L*'),
        comparison_row('distinct from stock dark themes', lambda d: {d1['id']: 'yes (warm, parchment)', d2['id']: 'partly (bone + bronze hold it off)', d3['id']: 'from stock themes yes; from Claude\'s own terracotta, no'}[d['id']]),
        comparison_row('raven story in the palette', lambda d: {d1['id']: 'the gold eye', d2['id']: 'iridescence, patina, bronze', d3['id']: 'the eye in firelight'}[d['id']]),
        comparison_row('shipped assets that change', lambda d: {d1['id']: 'none (tile, ico, bmp, SVGs keep their hex)', d2['id']: 'tile ink/bone move 2-4 units (optional)', d3['id']: 'tile ink/bone move 2-4 units (optional)'}[d['id']]),
        comparison_row('main risk', lambda d: {d1['id']: 'least visibly new', d2['id']: 'the convention it borders on', d3['id']: 'the ember is the host product\'s terracotta family; orange reads as warning'}[d['id']]),
        comparison_row('migration effort', lambda d: {d1['id']: 'rename + new tokens; 4 edits in Ansi.kt', d2['id']: 'rename + regenerate rasters; new Ansi.kt', d3['id']: 'rename + regenerate rasters; new Ansi.kt'}[d['id']]),
    ])
    legacy_names = ', '.join(f'{n} ({p["ground"]})' for n, p in LEGACY_PALETTES.items())
    return f'''# Colour - palette directions, state colours and the terminal theme

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
  >= {TRAY_MIN_DL:.0f} L* apart in the app tokens of both schemes, in the ANSI-16 ladder
  (brightBlack / the accent slot / brightYellow) and in the monochrome tray
  tints (`onSurface` at {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')}), so a template tray, a
  16-colour terminal and a reader with any colour-vision deficiency tell them
  apart. All three ladders are generator rules. `stateSuccess`, `stateError`
  (= error) and `stateInfo` (= secondary) are text semantics separated by hue;
  every pairwise dL* and ratio is printed on the sheet and in `contrast.txt`,
  and the cells below 12 are called out as hue-only.
- **Slot pins.** Four ANSI-16 slots *are* app tokens: brightRed = `error`,
  brightGreen = `stateSuccess`, brightYellow = `stateAttention`, brightBlue =
  `stateInfo`. The CLI's error is the app's error, in every direction, by rule.
- **A third voice that cannot be mistaken for a state.** `tertiary` (subagent
  markers, graph series) is >= {TERTIARY_MIN_DL:.0f} L* or >= {TERTIARY_MIN_DHUE:.0f} degrees from every
  state colour (neutral states by L* only); the row is on the sheet.
- **Surface tiers 0-4** (`surfaceContainerLowest` .. `Highest`), with the code
  well and terminal block *below* the page, and **three outline tiers**
  (`outline` at 3:1 for strokes, `outlineVariant` decorative, `outlineHairline`
  for dividers).
- **Proof, not promise.** Text >= 4.5:1 on every tier it is used on, UI >= 3:1,
  syntax >= 4.5:1 on the code ground, every ANSI slot but black >= 3:1 on the
  terminal ground, the CLI's 16-colour slots >= {LEGACY_MIN:.0f}:1 on {legacy_names}.
  `contrast.txt` is `tools/contrast.py` output line by line.
- **Dark and light** schemes with the same role names, so `Theme.kt` is a
  rename. The terminal block of the light app is the dark tier-0 well with the
  dark ANSI-16 (the grid is dark-only; `Ansi.kt` has one palette), as the light
  "in use" mock shows.
- **One raven in the terminal.** The tmux segment and the CLI banner use the
  ascii dimension's one-row pick `{GLYPH['unicode']}` (ascii-03-compact), with `{GLYPH['block']}` for
  fonts without Geometric Shapes and `{GLYPH['ascii']}` for the 7-bit clients.

## State mapping

Every surface that shows a state, and which token, ANSI slot and glyph it takes.
Glyphs are single-width Unicode / 7-bit ASCII; the apps draw dots, not glyphs,
and brand assets carry no emoji.

| surface | state | token | ANSI slot | glyph | word |
|---|---|---|---|---|---|
| apps: session list, overview, Work views (`SessionOverviewView.kt`, `WorkViews.kt`) | running (today `primary`) | `stateWorking`; `PulseDot` breathes it | accent slot | {gl('stateWorking')} | working |
| | attention: permission, question (today `error`) | `stateAttention` + badge | brightYellow | {gl('stateAttention')} | needs you |
| | idle, turn finished (today `onSurfaceVariant`) | `stateIdle`; `SettledDot` | brightBlack | {gl('stateIdle')} | waiting |
| | no Claude, shell only (today `outline`) | `outline`, hollow dot | default foreground | {gl('outline')} | shell |
| | tool ok, tests passed | `stateSuccess` | brightGreen | {gl('stateSuccess')} | ok |
| | tool failed, exit != 0, disconnected | `stateError` (= `error`) | brightRed | {gl('stateError')} | error |
| | informational, links | `stateInfo` (= `secondary`) | brightBlue | {gl('stateInfo')} | info |
| desktop tray (`TrayIcons.kt`) | idle / working / attention | `stateIdle` / `stateWorking` / `stateAttention` + badge; template tray: `onSurface` at {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')} + badge | - | the raven itself | same three words in the tooltip |
| terminal title (`server/bin/huginn-claude-title`, `HUGINN_ICON_RUN` / `_NEEDYOU` / `_IDLE`) | running / needs you / waiting (today the emoji 🔄 ✋ ✅) | none: the title is text | - | {gl('stateWorking')}, {gl('stateAttention')}, {gl('stateIdle')} | running, needs you, waiting |
| Rounds (`core/.../ui/Rounds.kt` `RoundStatus`, `RoundsView.kt`) | OK "All clear" / ATTENTION "Worth a look" / ACTION "Needs you" | `stateSuccess` / `stateInfo` (it is news, not a demand: `Notifier.kt` keeps "needs you" and "news" apart) / `stateAttention` | brightGreen / brightBlue / brightYellow | {gl('stateSuccess')} / {gl('stateInfo')} / {gl('stateAttention')} | the verdict words |
| `huginn status` (`server/bin/huginn-status`, today cyan / green / yellow / red) | headings / ok / warn / error / dim | `H_ACCENT` / `H_OK` / `H_WARN` / `H_ERROR` / `H_MUTED` from `cli-colors.sh` | accent slot / brightGreen / brightYellow / brightRed / brightBlack | `H_GLYPH` on the first line | - |
| CLI reconnect and give-up messages (`client/huginn.sh`, `huginn.ps1`) | warn / error | `H_WARN`, `H_ERROR` / `$HuginnColor.Warn`, `.Error` | brightYellow / brightRed | `H_GLYPH_ASCII` (7-bit) | - |
| Telegram (`server/appd/huginn-appd.js` `ROUND_MARK`, today ✅ ⚠️ 🔴 ❓) | ok / attention / action / unknown | text only, no colour | - | {gl('stateSuccess')} / {gl('stateInfo')} / {gl('stateAttention')} / {gl('outline')} | All clear / Worth a look / Needs you / unknown |
| Linux notifications (`LibnotifyNotifier.kt`, today `dialog-warning` / `dialog-information`) | needs you / news | the brand raven as the icon for both; the kind is carried by the words and `--urgency` | - | - | - |
| Glance widgets (`AskWidget.kt`, `FleetWidget.kt`, today bare `GlanceTheme`) | member states | the same tokens through a `ColorProviders(light, dark)` built from the schemes | - | - | same words |

In the CLI, `warn` and `attention` share brightYellow in every direction: a
stale device and a session that needs you are the same colour, told apart by
bold and the word. Only 02 separates `warn` from the *accent* by hue.

**Motion.** `PulseDot(MaterialTheme.colorScheme.primary)` is the live marker and
`stateWorking` equals `primary` in every direction, so the breath needs no new
token. Its 0.35 floor (`WorkViews.kt` `PULSE_ALPHAS`) is decorative: at the
bottom of the breath the dot is {', '.join(f'{h} ({r:.1f}:1) in {i.split("-", 2)[2]}' for i, (h, r) in pulse.items())}
over the ground, below any text rule on purpose; the word and the dot's presence
carry the state, the motion only says "alive". The "Thinking" text fade
(0.35 -> 1 over 900 ms) follows the same rule. No other brand motion is defined
by the colour system.

## Comparison

| | 01 Ink, Bone & Gold | 02 Slate & Verdigris | 03 Soot & Ember |
|---|---|---|---|
{rows}

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
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/tray/TrayIcons.kt` | `COLORS`: IDLE #7B8794, WORKING #7AA2F7, ATTENTION #E0AF68 | `stateIdle`, `stateWorking`, `stateAttention`; on a monochrome/template tray, `onSurface` at {pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')} plus the badge (`tokens.json` `states.monochromeTray` has the flattened hex); drop the "matches the Electron client" comment |
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/tray/RavenMark.kt` | `INK` #161310, `BONE` #E8E2DA | `inkMark`, `boneTile` |
| `mobile/app/src/main/res/values/colors.xml` | `huginn_bg` #12100F, `huginn_ink` #E8E2DA, `huginn_tile` #E8E2DA | `background`, `onBackground`, `boneTile` |
| `mobile/app/src/main/res/drawable/ic_launcher_foreground.xml` | `fillColor` #161310 | `inkMark` |
| `mobile/app/src/main/res/drawable/ic_stat_huginn.xml` | `fillColor` #FFFFFF | stays white: Android tints status icons itself; where the notification builder sets an accent, it takes `primary` |
| `mobile/app/src/main/kotlin/com/silencelen/huginn/widget/AskWidget.kt`, `FleetWidget.kt` | bare `GlanceTheme {{ }}` (system Material You colours: `widgetBackground`, `onSurfaceVariant`) | `GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors))` from the same scheme as `Theme.kt`; member state dots take the state tokens |
| `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/notify/LibnotifyNotifier.kt` | `--icon=dialog-warning` (needs you), `--icon=dialog-information` (news) | `--icon=<path to the brand raven PNG>` for both; the kind stays in the words and `--urgency` |
| `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/ui/Ansi.kt` | `palette[0..15]` | `terminal/<slug>.ansi.json` `ansi16` (for 01 this is four edits: yellow, brightYellow, brightBlack, black) |
| `mobile/core/src/commonTest/kotlin/com/silencelen/huginn/TerminalGridTest.kt` | `FG` #E8E2DA, `BG` #12100F | `onBackground`, `background` |
| `mobile/ui/src/jvmTest/kotlin/com/silencelen/huginn/ui/VerbInkTest.kt` | error #E8736D / #B3261E, onSurface #E8E2DA / #1B1817 | `error`, `onSurface` of each scheme |
| `assets/brand/raven.svg`, `raven-tile.svg`, `raven-inverse.svg`, `mobile/assets/icon.svg` | fills #161310 / #E8E2DA | `inkMark`, `boneTile` (unchanged in 01; regenerate `icon.png`, `huginn.ico`, `huginn.png` via `generate.sh` in 02 / 03) |
| `assets/brand/generate.sh` | wizard.bmp `xc:'#12100F'`, `-fill '#8A8177'` | `background`, `onSurfaceVariant` |
| `mobile/app-desktop/packaging/huginn-desktop-kt.nsi` | comment naming Bg #12100F and Ink #E8E2DA | comment follows `background` / `onBackground`; `wizard.bmp` is regenerated |
| `assets/demo.tape` | `Set Theme "Catppuccin Mocha"` | the one line in `terminal/<slug>.tape.snippet` |
| `server/tmux.conf` | `status-left` with `colour238` | `terminal/tmux.conf.snippet` (status-style, window-status, left segment with `{GLYPH['unicode']}`, the `Tc` override, right segment with the existing keybind hints; a 16-colour fallback is in the same file) |
| `server/bin/huginn-status` | `C`=36 cyan, `G`=32, `R`=31, `Y`=33 | the table in `terminal/cli-colors.sh`: `H_ACCENT` for headings, `H_OK`, `H_ERROR`, `H_WARN`, `H_MUTED`, `H_GLYPH` on the first line |
| `server/bin/huginn-claude-title` lines 73-75 | `ICON_RUN` 🔄, `ICON_NEEDYOU` ✋, `ICON_IDLE` ✅ | `{g['stateWorking'][0]}`, `{g['stateAttention'][0]}`, `{g['stateIdle'][0]}` (U+{ord(g['stateWorking'][0]):04X}, U+{ord(g['stateAttention'][0]):04X}, U+{ord(g['stateIdle'][0]):04X}); `{g['stateWorking'][1]}`, `{g['stateAttention'][1]}`, `{g['stateIdle'][1]}` when the terminal is not UTF-8; still overridable via `HUGINN_ICON_*` |
| `client/huginn.sh` | no colour; only the terminal title | inline `terminal/cli-colors.sh` (7-bit ASCII): `H_WARN` / `H_ERROR` on the reconnect and give-up messages, `H_GLYPH` / `H_GLYPH_ASCII` on the help screen, same TTY / `NO_COLOR` guard |
| `client/huginn.ps1` | `Write-Host -ForegroundColor Red` / `Yellow` | `$HuginnColor.Error` / `.Warn` from `terminal/cli-colors.ps1` (the same ConsoleColor names it uses today, so this is a rename), `$HuginnGlyph` |
| `server/appd/huginn-appd.js` line 5266 `ROUND_MARK` | ✅ ⚠️ 🔴 ❓ | out of scope for brand assets (chat text), but the glyph table above gives `{g['stateSuccess'][0]}` / `{g['stateInfo'][0]}` / `{g['stateAttention'][0]}` / `{g['outline'][0]}` if the owner wants Telegram to match |
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
slots (error text {R(LEGACY_PALETTES['conhost']['red'], LEGACY_PALETTES['conhost']['ground'])} on the default conhost) are replaced by the
bright slots `huginn.ps1` already prints, with the four legacy palettes' ratios
in every CLI file and in `check.py`; both CLI files are pure 7-bit ASCII with
the raven built from escapes and a 7-bit fallback; the three-cell tmux
"staircase" is replaced by the ascii dimension's `{GLYPH['unicode']}`; the ANSI-16 state
ladder is pinned (brightYellow = `stateAttention`) and is a rule, as are the
monochrome tray tints ({pct('stateIdle')} / {pct('stateWorking')} / {pct('stateAttention')}, shown at 22 and 16 px) and the
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
'''


def terminal_index_readme():
    rows = '\n'.join(
        f'| {d["name"]} | [`../{d["id"]}/terminal/`](../{d["id"]}/terminal/) | {d["accent_ansi"]}'
        + (f' (an amber; the ember {d["dark"]["accent"]} is the cursor, the tmux segment and the truecolor CLI accent)' if d['id'].endswith('ember') else '')
        + f' | `{d["slug"]}.ansi.json`, `.windows-terminal.json`, `.vhs.json`, `.tape.snippet`, `tmux.conf.snippet`, `cli-colors.sh`, `cli-colors.ps1`, `glyph.txt`, `mock.html`, `preview.png` |'
        for d in DIRECTIONS)
    ladders = '; '.join(f'{d["name"]} {ansi_ladder_txt(d)}' for d in DIRECTIONS)
    return f'''# The terminal layer

![the three terminal mocks](preview.png)

One ANSI-16 palette per direction, derived from the same tokens as the app, so
the CLI (`huginn status`, the reconnect messages), tmux on the host, the VHS demo
GIF, the app's own terminal grid (`mobile/core/.../ui/Ansi.kt`) and a Windows
Terminal profile all show the same ground, the same ink and the same accent.
The files live beside each direction so that a direction stays self-contained:

| direction | folder | accent slot | files |
|---|---|---|---|
{rows}

## Rules every palette meets

- `background` / `foreground` are the direction's `background` / `onBackground`.
- Every slot except `black` is >= 3:1 against the ground (most are >= 4.5:1),
  so git, test runners and `ls` stay readable; the exact ratios are in each
  `*.ansi.json` and at the bottom of each terminal mock.
- `black` is the direction's `outlineVariant`: visible against the ground
  (about 1.6:1) so black-on-ground output is not lost, dark enough to be black
  on a coloured background. `brightBlack` is `outline` (about 4:1), the colour
  of dim text, which is the slot most themes get wrong.
- Red / green / yellow are tool colours first: the brand accent is never put in
  the red or green slot.
- **Slot pins**: brightRed = `error`, brightGreen = `stateSuccess`, brightYellow =
  `stateAttention`, brightBlue = `stateInfo`, in every direction, by rule.
- **The state ladder in 16 colours** (brightBlack -> accent slot -> brightYellow)
  has steps >= {ANSI_LADDER_MIN_DL:.0f} L*: {ladders}.
- The CLI's five semantic uses map to slots: ok = brightGreen, warn =
  brightYellow, error = brightRed, muted = brightBlack, accent = the
  direction's accent slot (yellow for 01 and 03, brightCyan for 02). The bash
  and PowerShell tables carry a 16-colour branch (inherits the user's terminal
  palette, the right default for a CLI, hence the *bright* slots: every default
  palette's dark red is under 2:1 on its own ground) and a truecolor branch
  (pins the brand hex; for 03 that is the ember itself), both behind the same
  TTY / `NO_COLOR` rules `server/bin/huginn-status` already follows. Both files
  are pure 7-bit ASCII because they are inlined into `client/huginn.sh` and
  `huginn.ps1`.

## The one-row raven

The tmux left segment and the CLI's first line carry the ascii dimension's pick,
`{GLYPH['unicode']}` (U+25E2 U+2586 U+2588 U+2580: tail wedge, body, raised head, bill), from
[`ascii-03-compact`](../../ascii/ascii-03-compact/). `{GLYPH['block']}` is its fallback for
fonts without Geometric Shapes; `{GLYPH['ascii']}` is the 7-bit form the clients print (the
head row of the help-screen raven already in `huginn.sh`). Each `glyph.txt` lists
the three and passes `tools/asciicheck.py`; `cli-colors.sh` / `.ps1` expose them as
`H_GLYPH` / `H_GLYPH_BLOCK` / `H_GLYPH_ASCII` and `$HuginnGlyph` / `$HuginnGlyphBlock` /
`$HuginnGlyphAscii`, choosing by locale or code page. Separators in the tmux line
are `▌` (U+258C); no powerline glyphs, which are private-use and often missing.
(The colour dimension's earlier `▄▟▘` was dropped after review: at 13 px it is a
two-step staircase, and two dimensions must not ship two ravens for one line.)

## Installing one direction

- **Windows Terminal**: paste `<slug>.windows-terminal.json` into `settings.json`
  under `"schemes"` and set the profile's `"colorScheme"` to its `name`.
- **VHS**: replace `Set Theme "Catppuccin Mocha"` in `assets/demo.tape` with the
  one line in `<slug>.tape.snippet`.
- **tmux on the host**: replace the `status-left` line in `server/tmux.conf`
  with the top block of `tmux.conf.snippet`. Truecolor needs
  `set -g default-terminal "tmux-256color"` **and** `set -as terminal-overrides ",*:Tc"`
  (without the override tmux rounds hex to the 256-colour cube); the commented
  block at the bottom is the 16-colour fallback with ANSI names only.
- **CLI**: inline `cli-colors.sh` in `client/huginn.sh` and `server/bin/huginn-status`;
  inline `cli-colors.ps1` in `client/huginn.ps1`.
- **The app's terminal grid**: copy `ansi16` from `<slug>.ansi.json` into
  `Ansi.kt` `palette[0..15]`.

Each `mock.html` is an HTML `<pre>` with spans in the ANSI colours: a `git
status`, a test run with a failure, `huginn status` with the one-row raven, the
tmux status line and the sixteen slots with their ratios, in a tab strip drawn
with the direction's own surface tiers. `preview.png` beside it is that page
rendered at 1200 wide; the sheet above is the three stacked.
'''


def main():
    write(os.path.join(ROOT, 'color-01-ink-bone-gold', 'README.md'), readme_01())
    write(os.path.join(ROOT, 'color-02-slate-verdigris', 'README.md'), readme_02())
    write(os.path.join(ROOT, 'color-03-soot-ember', 'README.md'), readme_03())
    write(os.path.join(ROOT, 'README.md'), root_readme())
    write(os.path.join(ROOT, 'terminal', 'README.md'), terminal_index_readme())


if __name__ == '__main__':
    main()
