# PR 6 · Terminal

**Goal.** One state table shipped as data, one colour guard, one line grammar,
no emoji, no shouting; the two help screens from one table.

**The state table** (`server/lib/state-table.{sh,py,js}` generated from one source, or one JSON read by all three): the seven words with Unicode and 7-bit glyphs (`BRAND.md` §4 table). Readers: `server/bin/huginn-projects:192-204` (`stateWords`), `huginn-devices:101-119` (`state_of`), `huginn-rounds:52-54` (`WORD`/`MARK`), `huginn-headroom:167` (`MODE_WORD`), `huginn-archive` marks, `huginn-claude-title:70-78` (already the table), the help footers (`huginn.sh:925`, `huginn.ps1:721`), `server/appd/huginn-appd.js:5266` `ROUND_MARK` and `lib/alerts.js:289`, `lib/devices.js:402-404`.
- Marks mean one thing: `*` working, `!` needs you, `o` waiting, `+` ok, `x` error, `>` info, `-` shell. Rounds' `!!` and `?` go; archive's `>` (live) becomes `*`.
- Lowercase everywhere except "needs you" (keep its emphasis by position, not caps): NEEDS YOU, RUNNING, DID NOT FINISH, TRANSCRIPT GONE, NOT reachable, MISSING, WATCHED, OFF, EXHAUSTED, FAIL, EXCEEDED, RED (`huginn-rounds`, `-devices`, `-headroom`, `-archive`, `-projects`, `huginn-local:1631-1653`, `ThisMachine.kt:373-377` on the desktop too).

**One colour guard.** bash: `_huginn_color` already guards on `-t 2` and `NO_COLOR`; `huginn-status` on `-t 1`. PowerShell: add `$script:HuginnColorOn = -not $env:NO_COLOR -and -not [Console]::IsOutputRedirected` and gate every `-ForegroundColor` (`huginn.ps1:410, 414, 626, 845-882, 1111, 1122`; `client/install.ps1:23, 42-47`); `update` colours the same lines in both clients (bash is uncoloured today). Slot numbers only. `_Huginn-Fail` (`huginn.ps1:161-165`) writes the plain two-line message to stderr before the throw so a refusal looks like bash's.

**Line grammar** (every renderer): first line `(o> huginn — <surface>` (7-bit: `(o> huginn - <surface>`), `  .  ` separator, `->` arrows, blank-line padding, a count footer, 2/4 indents, one key column width in `huginn-device status:1081-1119` and `huginn-local status:1533-1541`; `huginn-status:58` bullet `・` → `-`; em-dashes in server output → ` - ` where the output reaches PowerShell 5.1 (`huginn-status:50, 69`, `huginn-archive:117, 136, 169`, `huginn-projects` errors, `huginn-llm:66, 91, 111`, `setup.sh:51, 112`, `install.sh:76`); `huginn-projects:395` ⚠ → `!`. Version line gets `(o>` (`huginn.sh:1004`, `huginn.ps1:820`).

**Telegram** (`huginn-appd.js:5266, 5295-5299, 5798, 8399, 8497, 8516, 8575, 8748, 8820, 8951, 9280`; `lib/alerts.js:289-290`; `lib/devices.js:402-404`): lead with the glyph table, not emoji: `▲ main needs you`, `× usage limit reached`, `■ weekly audit · all clear`, `○ laptop offline`, `* laptop back`. Chat markers 📷 📎 📝 in `humanizeUserText` (`:287-301`) → words ("photo", "file", "page").

**Help screens** (`huginn.sh:845-927, 994-1000`; `huginn.ps1:638-723, 811-817`): generate both bodies from one table (`client/help-table.txt` + a build step, or a shared heredoc checked by `scripts/test-client.sh`); add `devices watch` to bash; one wording for device/local rows; the PowerShell blank line after the banner; wrap at 80 columns; re-align `projects spawn`/`end` and the `usage` continuation lines; the footer says "working / needs you / waiting". The four-row bird stays (owner's decision).

**tmux** (`server/tmux.conf:18-33`): add the 16-colour fallback block from `explorations/color/color-04-moss-heather/terminal/tmux.conf.snippet` as the commented alternative; the `Tc` override note stays.

**Acceptance.** `scripts/test-client.sh` passes on the host; `LC_ALL=C grep -P '[^\x00-\x7F]'` over everything the clients print is empty; `huginn status | cat` and `NO_COLOR=1 huginn status` carry no escapes; a Telegram message with no emoji; both help screens diffed against the table.
