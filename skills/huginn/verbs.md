# `huginn` verbs

`huginn help` prints this list in the terminal; `huginn <verb> --help` prints one verb's rows
without contacting the server. `rclaude` and `rcc` are aliases for `huginn`. Session names are
lowercase letters, digits, `_` and `-`, up to 50 characters, starting with a letter, digit or
`_`; case-insensitive.

## How each group runs

| Group | Runs | Needs |
|---|---|---|
| sessions, `list`, `status`, `usage`, `-p`, `-y` | over SSH to the server | `Host huginn` (or `HUGINN_HOST`) |
| `rename`, `end`, `kill`, `archive`, `revive`, `rounds`, `devices`, `headroom`, `projects`, `llm` | over SSH, through the daemon, rendered on the server | SSH and `huginn-appd` running there |
| `huginn` (console), `console`, `setup`, `core`, `device`, `doctor`, `skills`, `uninstall` | here, through huginn core | huginn core installed (most: running and paired) |
| `desktop`, `update`, `version`, `help` | here | nothing; downloads come from GitHub Releases |

huginn core runs on Linux and Windows. On Termux only the SSH verbs exist.

## Sessions

| Command | What it does |
|---|---|
| `huginn main` | Attach to `main`, creating it; a new session starts Claude Code. |
| `huginn <name>` | Attach to that session, creating it. |
| `huginn attach <name>` | Attach even when the name is a verb (`update`, `solo`, `list`, `console`). |
| `huginn solo [name]` | Attach and detach every other device (full screen). |
| `huginn list` / `ls` | Sessions on the server and who is attached. |
| `huginn status` / `st` | Server health: uptime, Claude Code sign-in, sessions, disk. |
| `huginn rename <old> <new>` / `mv` | Rename; refused for a project member. |
| `huginn end <name> [--force]` | Soft end: types the wrap-up message, ends once idle (auto-end on). Refused while a question waits. |
| `huginn kill <name>` | Hard end, now. Unsaved work is lost. |
| `huginn archive <name> [--now]` | End for good, keeping title, cwd, transcript copy and the `claude --resume` line. |
| `huginn archive` / `huginn revive <id\|name>` | List the archive / bring one back. |

Detach with `Alt-d` (or `Ctrl-b d`); `Alt-o` detaches everyone else. Attaching reconnects by
itself after a dropped link (`HUGINN_NO_RECONNECT=1` turns that off).

## This machine

| Command | What it does |
|---|---|
| `huginn` / `huginn console` | The console (needs huginn core 2.3+, a terminal). `HUGINN_CONSOLE=0` makes plain `huginn` attach to `main`. |
| `huginn setup ssh [--host H] [--user U]` | Key + `Host huginn` entry + check the server accepts the key. Moves the entry to another server address only when SSH proves the same host key. |
| `huginn core install [--no-tray\|--tray] [--logon s4u\|password]` | Install the core, its boot service and tray autostart; pair it. Re-runnable. |
| `huginn core status` / `start` / `update` / `uninstall` | Is it running and paired / start it / newest release / stop starting it at boot. |
| `huginn core pair --url https://<host>:8787` | Pair; token from stdin, `HUGINN_APPD_TOKEN`, or a hidden prompt. |
| `huginn core route [add <url> \| remove <url> \| pin <url>\|none \| auto on\|off]` | The server's addresses the core knows, their health, and switching. |
| `huginn core tray show` | Start the tray icon now, from the desktop session. |
| `huginn device [status]` / `on [--scope look\|work\|own] [--root DIR] [--name N] [--act-while-locked]` / `off` | What this machine offers the server. See devices.md. |
| `huginn doctor [--json]` | Deterministic health report of this machine's Huginn; exit 1 when a check fails. |
| `huginn skills [status]` / `install` / `remove` | The Huginn skills for Claude Code in `~/.claude/skills`. |
| `huginn local ...` | The optional local AI tier. See local-ai.md. |
| `huginn desktop [win\|linux\|arch\|portable\|install\|uninstall\|update]` | The desktop app's downloads and per-user install. |
| `huginn update` | Everything Huginn here, from the newest release (CLI, core, desktop, local AI). |
| `huginn uninstall [--all] [--yes]` | The whole stack, top down: desktop, local AI, device row, skills, core, CLI. `--all` also the SSH entry and Huginn's own key. |
| `huginn version` | The CLI version. |

## The server, rendered there

| Command | What it does |
|---|---|
| `huginn devices` / `huginn devices watch <name> on\|off` | Enrolled machines; mark one as always on (alerts when it goes quiet). |
| `huginn rounds` | Scheduled work on the server and its last runs (edited in the apps). |
| `huginn headroom [--json]` | Usage left per Claude account; what Huginn held or moved. |
| `huginn projects [--json]` / `show` / `new <name> --brief TEXT\|-` / `spawn` / `msg` / `end` | Groups of sessions led by one. |
| `huginn llm "prompt" [--model ROW] [--timeout S]` / `huginn llm -` | One question to the local tier (not Claude). |
| `huginn usage [daily\|weekly\|monthly\|session\|blocks] [today\|yesterday\|week\|month]` | ccusage token and cost report from the server. |
| `huginn -p "question"` | One read-only headless turn on the server (Bash, Edit, Write denied). |
| `huginn -y "task"` | One headless turn with tools. |

## Environment variables worth knowing

`HUGINN_HOST` (the SSH alias, default `huginn`), `HUGINN_CONSOLE=0`, `HUGINN_NO_RECONNECT`,
`HUGINN_NO_TITLE`, `NO_COLOR`, `HUGINN_ASCII=1|0`, `HUGINN_REDUCED_MOTION`, `HUGINN_USER`
(SSH user, default `root`). Installer presets: `HUGINN_GUI`, `HUGINN_DEVICE`, `HUGINN_LOCAL`,
`HUGINN_SKILLS` (`y|n` or `1|0`). Advanced: `HUGINN_APPD_TOKEN`, `HUGINN_SECRETS`,
`HUGINN_CONFIG_DIR`, `HUGINN_UPDATE_HOST`, `HUGINN_CORE_FILE`.
