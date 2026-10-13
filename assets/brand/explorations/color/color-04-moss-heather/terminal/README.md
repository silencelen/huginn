# Moss & Heather - terminal layer

![terminal preview](preview.png)

The CLI, tmux, the VHS demo and the app's own terminal grid (`mobile/core/.../ui/Ansi.kt`)
all read this one ANSI-16 palette. Ground `#141615` and foreground `#E2E6E1`
are the app's `background` / `onBackground`; the brand accent sits in the **green** slot
(`#7CB98E`) and is also the cursor, so a plain `\033[32m`
is on-brand without truecolor. Four slots are *pinned* to app tokens (rightmost column), so the
CLI, tmux and the app never show two different reds: the terminal's error is the app's error.

| slot | hex | on ground | L* | pinned to |
|---|---|---|---|---|
| black | `#3A423E` | 1.76:1 | 27 | |
| red | `#D65A62` | 4.76:1 | 55 | |
| green | `#7CB98E` | 7.95:1 | 70 | |
| yellow | `#D4B872` | 9.43:1 | 76 | |
| blue | `#6C9BD6` | 6.32:1 | 63 | |
| magenta | `#A98BD6` | 6.36:1 | 63 | |
| cyan | `#5EB5B0` | 7.54:1 | 68 | |
| white | `#C8CDC9` | 11.28:1 | 82 | |
| brightBlack | `#747D77` | 4.28:1 | 52 | |
| brightRed | `#EE757D` | 6.47:1 | 64 | = `error` |
| brightGreen | `#C4CF7A` | 10.87:1 | 81 | |
| brightYellow | `#E8D07E` | 11.91:1 | 84 | = `stateAttention` |
| brightBlue | `#86B5E6` | 8.45:1 | 72 | |
| brightMagenta | `#B49CDB` | 7.55:1 | 68 | = `stateInfo` |
| brightCyan | `#80CFC9` | 10.12:1 | 78 | = `stateSuccess` |
| brightWhite | `#E2E6E1` | 14.40:1 | 91 | |

cursor `#7CB98E` - selection `#2C4035`.

States as ANSI slots: idle = brightBlack, working = green, attention = brightYellow, success = brightCyan, error = brightRed, info = brightMagenta.
The 16-colour state ladder is brightBlack L* 52 -> green L* 70 -> brightYellow L* 84
(steps 19, 14; the rule is >= 12, checked by `build/check.py`).

## The CLI's five colours

`cli-colors.sh` / `cli-colors.ps1` are pure 7-bit ASCII (they are inlined into `client/huginn.sh`
and `huginn.ps1`, which must stay ASCII) and carry two branches. The 16-colour branch inherits
the user's palette, so it uses the **bright** slots - the dark red of every default palette is
under 2:1 on its own ground - and these are the colours `huginn.ps1` prints today. The ratios on
the legacy palettes the branch must survive (minimum 3:1):

| semantic | slot | SGR | ConsoleColor | Huginn scheme | conhost | powershell-5.1 | campbell | xterm |
|---|---|---|---|---|---|---|---|---|
| ok | brightGreen | 92 | Green | `#C4CF7A` 10.9:1 | `#00FF00` 15.3:1 | `#00FF00` 11.0:1 | `#16C60C` 8.5:1 | `#00FF00` 15.3:1 |
| warn | brightYellow | 93 | Yellow | `#E8D07E` 11.9:1 | `#FFFF00` 19.6:1 | `#FFFF00` 14.1:1 | `#F9F1A5` 16.9:1 | `#FFFF00` 19.6:1 |
| error | brightRed | 91 | Red | `#EE757D` 6.5:1 | `#FF0000` 5.3:1 | `#FF0000` 3.8:1 | `#E74856` 5.1:1 | `#FF0000` 5.3:1 |
| muted | brightBlack | 90 | DarkGray | `#747D77` 4.3:1 | `#808080` 5.3:1 | `#808080` 3.8:1 | `#767676` 4.3:1 | `#7F7F7F` 5.2:1 |
| accent | brightMagenta | 95 | Magenta | `#B49CDB` 7.5:1 | `#FF00FF` 6.7:1 | `#FF00FF` 4.8:1 | `#B4009E` 3.2:1 | `#FF00FF` 6.7:1 |

Grounds: conhost `#000000`, powershell-5.1 `#012456`, campbell `#0C0C0C`, xterm `#000000`. PowerShell 5.1's console
remaps DarkYellow to `#EEEDF0`, so an accent in slot 33 shows there as near-white (legible, not
gold). For `accent`, the truecolor branch pins the ember `#7CB98E` itself, which has no ANSI slot (the yellow slot is an amber so git stays yellow).

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
| `moss-heather.ansi.json` | source of truth; `Ansi.kt` palette[0..15], any emulator importer |
| `moss-heather.windows-terminal.json` | paste into Windows Terminal `settings.json` -> `schemes` |
| `moss-heather.vhs.json`, `moss-heather.tape.snippet` | the `Set Theme` line for `assets/demo.tape` |
| `tmux.conf.snippet` | status line for `server/tmux.conf` (truecolor block with the `Tc` override, and a 16-colour fallback) |
| `cli-colors.sh`, `cli-colors.ps1` | the five semantic colours and the glyph for `client/huginn.sh`, `server/bin/huginn-status`, `client/huginn.ps1` |
| `glyph.txt` | the one-row raven: Unicode, block-only, ASCII |
| `mock.html` -> `preview.png` | the render above: git status, a test run, `huginn status`, the tmux line, the 16 slots |
