# Ink, Bone & Gold - terminal layer

![terminal preview](preview.png)

The CLI, tmux, the VHS demo and the app's own terminal grid (`mobile/core/.../ui/Ansi.kt`)
all read this one ANSI-16 palette. Ground `#12100F` and foreground `#E8E2DA`
are the app's `background` / `onBackground`; the brand accent sits in the **yellow** slot
(`#C8A45C`) and is also the cursor, so a plain `\033[33m`
is on-brand without truecolor. Four slots are *pinned* to app tokens (rightmost column), so the
CLI, tmux and the app never show two different reds: the terminal's error is the app's error.

| slot | hex | on ground | L* | pinned to |
|---|---|---|---|---|
| black | `#3A3431` | 1.55:1 | 22 | |
| red | `#D1544F` | 4.60:1 | 52 | |
| green | `#69A95B` | 6.71:1 | 63 | |
| yellow | `#C8A45C` | 8.06:1 | 69 | |
| blue | `#5B8FD6` | 5.73:1 | 59 | |
| magenta | `#A974C4` | 5.41:1 | 57 | |
| cyan | `#4FA5A8` | 6.58:1 | 63 | |
| white | `#CFC8BF` | 11.45:1 | 81 | |
| brightBlack | `#7A736C` | 4.06:1 | 49 | |
| brightRed | `#E8736D` | 6.42:1 | 62 | = `error` |
| brightGreen | `#8CCB7B` | 9.89:1 | 76 | = `stateSuccess` |
| brightYellow | `#F3D58F` | 13.31:1 | 86 | = `stateAttention` |
| brightBlue | `#7DAFEA` | 8.31:1 | 70 | = `stateInfo` |
| brightMagenta | `#C495DC` | 7.84:1 | 68 | |
| brightCyan | `#6FC4C7` | 9.39:1 | 74 | |
| brightWhite | `#F2ECE4` | 16.17:1 | 94 | |

cursor `#C8A45C` - selection `#4A3F2A`.

States as ANSI slots: idle = brightBlack, working = yellow, attention = brightYellow, success = brightGreen, error = brightRed, info = brightBlue.
The 16-colour state ladder is brightBlack L* 49 -> yellow L* 69 -> brightYellow L* 86
(steps 20, 17; the rule is >= 12, checked by `build/check.py`).

## The CLI's five colours

`cli-colors.sh` / `cli-colors.ps1` are pure 7-bit ASCII (they are inlined into `client/huginn.sh`
and `huginn.ps1`, which must stay ASCII) and carry two branches. The 16-colour branch inherits
the user's palette, so it uses the **bright** slots - the dark red of every default palette is
under 2:1 on its own ground - and these are the colours `huginn.ps1` prints today. The ratios on
the legacy palettes the branch must survive (minimum 3:1):

| semantic | slot | SGR | ConsoleColor | Huginn scheme | conhost | powershell-5.1 | campbell | xterm |
|---|---|---|---|---|---|---|---|---|
| ok | brightGreen | 92 | Green | `#8CCB7B` 9.9:1 | `#00FF00` 15.3:1 | `#00FF00` 11.0:1 | `#16C60C` 8.5:1 | `#00FF00` 15.3:1 |
| warn | brightYellow | 93 | Yellow | `#F3D58F` 13.3:1 | `#FFFF00` 19.6:1 | `#FFFF00` 14.1:1 | `#F9F1A5` 16.9:1 | `#FFFF00` 19.6:1 |
| error | brightRed | 91 | Red | `#E8736D` 6.4:1 | `#FF0000` 5.3:1 | `#FF0000` 3.8:1 | `#E74856` 5.1:1 | `#FF0000` 5.3:1 |
| muted | brightBlack | 90 | DarkGray | `#7A736C` 4.1:1 | `#808080` 5.3:1 | `#808080` 3.8:1 | `#767676` 4.3:1 | `#7F7F7F` 5.2:1 |
| accent | yellow | 33 | DarkYellow | `#C8A45C` 8.1:1 | `#808000` 5.0:1 | `#EEEDF0` 13.0:1 | `#C19C00` 7.5:1 | `#CDCD00` 12.3:1 |

Grounds: conhost `#000000`, powershell-5.1 `#012456`, campbell `#0C0C0C`, xterm `#000000`. PowerShell 5.1's console
remaps DarkYellow to `#EEEDF0`, so an accent in slot 33 shows there as near-white (legible, not
gold). For `accent`, the truecolor branch pins `#C8A45C`, the same hex as the slot.

## The one-row raven

The tmux segment and the CLI banner use the ascii dimension's pick, `◢▆█▀`
([`ascii-03-compact/raven-1line-a.txt`](../../../ascii/ascii-03-compact/)): tail wedge, body,
raised head, bill; four single-width cells. `▗▆█▀` is its fallback for fonts without
Geometric Shapes (U+25E2). `(o)==-` is the 7-bit form the clients print: the head row of
the help-screen raven already in `huginn.sh`. `glyph.txt` lists the three, one block each, and
passes `tools/asciicheck.py`. (The colour dimension's own earlier three-cell glyph was dropped:
it rendered as a staircase, and two dimensions must not ship two ravens for one tmux line.)

## Files

| file | consumer |
|---|---|
| `ink-bone-gold.ansi.json` | source of truth; `Ansi.kt` palette[0..15], any emulator importer |
| `ink-bone-gold.windows-terminal.json` | paste into Windows Terminal `settings.json` -> `schemes` |
| `ink-bone-gold.vhs.json`, `ink-bone-gold.tape.snippet` | the `Set Theme` line for `assets/demo.tape` |
| `tmux.conf.snippet` | status line for `server/tmux.conf` (truecolor block with the `Tc` override, and a 16-colour fallback) |
| `cli-colors.sh`, `cli-colors.ps1` | the five semantic colours and the glyph for `client/huginn.sh`, `server/bin/huginn-status`, `client/huginn.ps1` |
| `glyph.txt` | the one-row raven: Unicode, block-only, ASCII |
| `mock.html` -> `preview.png` | the render above: git status, a test run, `huginn status`, the tmux line, the 16 slots |
