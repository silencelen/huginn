# CLI reference

Every `huginn` command, by group. `huginn help` prints the same list in your terminal, and
`huginn <verb> --help` prints one verb's entry without contacting the server.

`rclaude` and `rcc` are aliases for `huginn`. In bash and PowerShell, `Tab` completes verbs,
sub-verbs and the names of live sessions.

## How the commands run

| Kind | Commands | Needs |
|---|---|---|
| Over SSH | sessions, `list`, `status`, `usage`, `-p`, `-y` | SSH to the server (`Host huginn`, or the alias in `HUGINN_HOST`) |
| Over SSH, through the daemon | `rename`, `end`, `kill`, `archive`, `revive`, `rounds`, `devices`, `headroom`, `projects`, `llm` | SSH, and the daemon (`huginn-appd`) running on the server. The command runs on the server and reads the access token there, so the token never reaches this computer. `rename` and `kill` also work when the daemon is down. |
| On this computer, through huginn core | `huginn` (the console), `console`, `setup`, `core`, `device`, `doctor`, `skills`, `uninstall` | huginn core installed (and, for most, running and paired) |
| On this computer only | `desktop`, `update`, `version`, `help` | Nothing; `desktop` and `update` download from GitHub Releases |

huginn core runs on Linux and Windows. On Termux, only the SSH commands are available.

## Sessions

See [Sessions](../guide/sessions.md).

| Command | What it does |
|---|---|
| `huginn main` | Attach to the `main` session, creating it if needed. A new session starts Claude Code. |
| `huginn <name>` | Attach to the session `<name>`, creating it if needed. |
| `huginn attach <name>` | Attach to `<name>` even when it is also a verb (`update`, `solo`, `list`, `console` and so on). |
| `huginn solo [name]` | Attach and detach every other device from the session, so this one gets the full screen. Default: `main`. |
| `huginn list` (`ls`) | List sessions on the server, and which ones have a device attached. |
| `huginn status` (`st`) | The server's health: uptime, Claude Code sign-in, sessions, disk. |
| `huginn rename <old> <new>` (`mv`) | Rename a session. A session that belongs to a project is refused, with what to do first. |
| `huginn end <name> [--force]` | Soft end: type the wrap-up message into the session and, with auto-end on (the default), end it once Claude Code settles. Refused while a question waits. `--force` sends the message even when the server has no record of Claude Code running in the session. |
| `huginn kill <name>` | Hard end: stop the session now. Unsaved work is lost. |
| `huginn archive <name> [--now]` | End the session for good and keep the way back: title, working directory, a copy of the transcript and the exact `claude --resume` command. Wraps up first, like `end`; `--now` skips the wrap-up. |
| `huginn archive` | List archived sessions and how to bring each one back. |
| `huginn revive <id\|name>` (`unarchive`) | Recreate an archived session and resume its conversation. |

Session names are lowercase letters, digits, `_` and `-`, up to 50 characters, starting with a
letter, digit or `_`; no dots or spaces; case-insensitive.

## Console

See [The console](../guide/console.md).

| Command | What it does |
|---|---|
| `huginn` | In an interactive terminal with huginn core 2.3 or later: open the console. Piped, redirected, with `TERM=dumb`, with `HUGINN_CONSOLE=0`, or without huginn core: attach to `main`. |
| `huginn console` | Open the console by name. Needs huginn core 2.3 or later and a terminal. |

## Setup and pairing

See [Install the clients](../install/clients.md).

| Command | What it does |
|---|---|
| `huginn setup ssh [--host HOST] [--user USER]` | Set up SSH to the server on a computer installed from the desktop app or a package: create a key if there is none, add a `Host huginn` entry to `~/.ssh/config`, and check that the server accepts the key (if it does not, it prints the line to add to `~/.ssh/authorized_keys` on the server and waits for you). `--host` defaults to the server huginn core is paired with; `--user` defaults to `HUGINN_USER`, or `root`. Existing keys are never replaced. When the address does not answer SSH from here, the `Host huginn` entry, whether this run has just written it or it was already there, is switched to another of the server's addresses only when SSH shows that address has the same host key; otherwise it is kept, and setup prints the server's addresses with the `huginn setup ssh --host ADDRESS` command that switches it. |
| `huginn core install [flags]` | Install huginn core to start at boot, as you, with nobody signed in, and pair it with the server (it fetches the token over SSH, or asks you to paste it). See [huginn core](#huginn-core) for the flags. |
| `huginn core pair --url URL` | Pair huginn core with the server at `URL` (for example `https://192.168.1.20:8787`). The token is read from standard input, or from `HUGINN_APPD_TOKEN`; at a prompt it is not shown as you type. huginn core must be running. |

## Health check

| Command | What it does |
|---|---|
| `huginn doctor` | A deterministic report of this computer's Huginn, with nothing changed: the core binary and the CLI (installed, versions match), the boot service (`huginn-core.service` and linger; the "Huginn Core" task), whether the core answers on its socket, the tray, the pairing, every server address (reachable, latency, certificate is the proved one), the daemon's `/v1/status` through the core (daemon and Claude Code versions), this computer's device enrolment and scope, the local AI tier if installed, SSH (`Host huginn`, `ssh -G`, port 22 by a TCP connect only, the host key in `known_hosts`), Claude Code and the Huginn skills. One line per check, `ok`, `warn`, `fail` or `skip`, with the one thing to try under a line that is not ok. |
| `huginn doctor --json` | The same as a JSON array of `{id, state, detail, fix}` on standard output, for scripts. The ids are stable: `core.binary`, `core.cli`, `core.service`, `core.socket`, `core.tray`, `core.pairing`, `route:<url>` per address, `server.status`, `device`, `local-ai`, `ssh.config`, `ssh.resolve`, `ssh.port`, `ssh.hostkey`, `claude.code`, `claude.skills`. |

Exit status: 0 when no check failed, 1 otherwise (`warn` and `skip` do not fail it). See
[Claude Code skills](../guide/skills.md#checking-a-computer-huginn-doctor).

## Claude Code skills

See [Claude Code skills](../guide/skills.md).

| Command | What it does |
|---|---|
| `huginn skills` (`status`) | One line per skill huginn core carries (`huginn`, `huginn-doctor`): linked or copied into `~/.claude/skills`, stale, yours (a skill you wrote under that name), or not installed; then whether Claude Code is on this computer. Exit 1 when a skill is not installed as this version lays it. |
| `huginn skills install` | Put them in `~/.claude/skills`: a link per skill to huginn core's copy on Linux, a copy on Windows. Replaces only what Huginn put there; a skill you wrote is left alone and named. Forgets an earlier no. |
| `huginn skills remove` | Take Huginn's links or copies out, leave anything else, and remember the no so updates and installs do not put them back. |

The installer asks `Install Huginn skills for Claude Code? [Y/n]` when Claude Code is on the
computer and nothing of Huginn's is in `~/.claude/skills` yet; `HUGINN_SKILLS=1|0` answers up
front, and with no terminal the answer is no. Updates refresh what is there; `huginn uninstall`
removes it.

## huginn core

huginn core is the per-user background service on Linux and Windows: it keeps the computer paired
with the server, runs at boot, owns the tray icon and notifications, and can offer the computer as
a device. `huginn core` with no sub-verb is `huginn core status`.

| Command | What it does |
|---|---|
| `huginn core install` | Download the newest huginn core release, check its SHA-256, install it (`~/.local/bin/huginn` on Linux, `%LOCALAPPDATA%\huginn\bin\huginn.exe` on Windows), set it to start at boot, start it, and pair it. On Linux it is a systemd user service (`huginn-core.service`) with lingering enabled; on Windows a scheduled task ("Huginn Core"), which asks for administrator approval once. |
| `  --no-tray` | Do not start the tray icon at login. |
| `  --tray` | Linux: start the tray at login even when you run the install from an SSH shell with no graphical session. |
| `  --logon s4u\|password` | Windows only. `s4u` (default) stores no password; `password` asks for your Windows password once and Task Scheduler keeps it. |
| `huginn core status` | Whether huginn core is running and paired, its secret store, this computer's device settings, the tray, and the boot service. |
| `huginn core start` | Start huginn core now if it is set up to start at boot but is not running. |
| `huginn core update` | Install the newest huginn core release and restart huginn core onto it. |
| `huginn core route` | The server addresses huginn core knows and the health of each. |
| `huginn core route add <url> [--name N] [--kind K]` | Add another address for the same server (`K`: `lan`, `mesh`, `tailnet` or `custom`). |
| `huginn core route remove <url>` | Remove an address. |
| `huginn core route pin <url>\|none` | Use only this address, with no switching; `none` removes the pin. |
| `huginn core route auto on\|off` | Whether an address that stops answering hands over to the next one. |
| `huginn core pair --url URL` | Pair with a server; see [Setup and pairing](#setup-and-pairing). |
| `huginn core tray show` | Start the tray icon now, from your own desktop session (not as administrator). |
| `huginn core uninstall` | Stop starting huginn core at boot: remove the boot service and the tray's autostart. To remove everything, use `huginn uninstall`. |

## Devices

A device is a computer that Huginn may run work on. See [Devices](../guide/devices.md).

| Command | What it does |
|---|---|
| `huginn devices` | The computers enrolled with the server: what each offers, and when it was last seen. Needs the daemon. |
| `huginn devices watch <name> on\|off` | Mark a device as always on. The daemon then sends a message through your alert relay, if you configured one, when the device has not checked in for ten minutes, and again when it is back. |
| `huginn device [status]` | What this computer offers Huginn, and what the server sees. |
| `huginn device on [flags]` | Offer this computer to Huginn. Run it again with other flags to change the settings. |
| `  --scope look\|work\|own` | `look` reads, `work` edits inside its root, `own` runs anything. |
| `  --root DIR` | The directory `work` scope is limited to. |
| `  --name N` | The name the device is listed under (default: the host name). |
| `  --act-while-locked`, `--no-act-while-locked` | Whether the computer keeps acting while its screen is locked. Default: a lock screen withdraws acting. |
| `huginn device off` | Stop offering this computer; huginn core removes its entry from the server. |

On a computer without huginn core (Termux), `huginn device` uses a separate Node.js runner instead.
There, `huginn device on` also takes `--url URL` (the daemon's address, when it cannot be worked out
over SSH), and three more sub-verbs exist: `huginn device update` (fetch a newer runner),
`huginn device serve` (the runner itself, what its service starts) and
`huginn device unit [--system]` (print a systemd unit, or on Windows a scheduled task, that keeps
the runner up). With huginn core installed, those three report that they are not needed.

## Local AI (experimental)

Local models served from one of your computers, for questions that do not need Claude. Needs
Node.js on the serving computer. See [Local AI](../guide/local-ai.md).

| Command | What it does |
|---|---|
| `huginn llm "prompt" [--model ROW] [--timeout S]` | Ask the local tier one question: a serving computer's model answers, not Claude. `--model` picks one computer's model; `--timeout` gives up after `S` seconds (default 120). `huginn llm -` reads the prompt from standard input. Needs the daemon. |
| `huginn local [status]` | What this computer serves as local AI, if anything. |
| `huginn local plan` | What `huginn local on` would install here, without installing anything. |
| `huginn local on` | Serve local models from this computer (a download of about 5 GB). |
| `huginn local persist` | Make an existing install survive logout (system services, or lingering). |
| `huginn local doctor` | Check the install; exits non-zero when it is not doing what it says. |
| `huginn local update` | Fetch the manager and its pinned runtime again. |
| `huginn local unit [--system]` | Print the service definitions this tier installs. |
| `huginn local off [--purge-models] [--purge]` | Stop serving. `--purge-models` also deletes the models; `--purge` removes the whole install. |

## Projects

A project is a group of sessions with roles, led by one session that proposes the work. See
[Projects](../guide/projects.md). All project commands need the daemon.

| Command | What it does |
|---|---|
| `huginn projects [--json]` (`project`, `projects list`) | One line per project: members, who needs you, the lead's state. |
| `huginn projects show <project>` | The project's members, one line each, with the name to attach to. |
| `huginn projects new <name> --brief TEXT\|- [--kind KIND] [--cwd DIR]` | Start a project: launch its lead session with `--brief` as the lead's whole first message (`-` reads it from standard input). `KIND`: `software`, `infra`, `hardware`, `docs`, `research` or `other` (default). |
| `huginn projects spawn <project>` | Approve the lead's proposal and create its members. |
| `huginn projects msg <project> <from> <to> <text>` | Send one line from one member to another. |
| `huginn projects end <project> [--now\|--keep-sessions]` | End the project and wind its sessions down. `--now` ends them outright; `--keep-sessions` leaves them running and names them. |

`--json` prints the daemon's answer as JSON instead of rendering it. A `<project>` is the project's
id or name.

## Rounds

See [Rounds](../guide/rounds.md).

| Command | What it does |
|---|---|
| `huginn rounds` (`round`) | What the server does on a schedule, and what the last runs found. Needs the daemon. Rounds are created and edited in the apps. |

## Usage and headroom

See [Usage limits](../guide/usage-limits.md).

| Command | What it does |
|---|---|
| `huginn headroom [--json]` | Usage left on each Claude account the server holds, what Huginn moved or is holding back, and why. Needs the daemon. |
| `huginn usage [report] [options]` (`cost`, `ccusage`) | Claude Code token and cost report from the server, by [ccusage](https://github.com/ryoppippi/ccusage). `report`: `daily` (default), `weekly`, `monthly`, `session`, `blocks` or `statusline`. Options go to ccusage, for example `huginn usage blocks --live`. |
| `huginn usage today\|yesterday\|week\|month [report]` | The same report limited to that date range, for example `huginn usage week session`. |

## Desktop app

The desktop app runs on Windows (x64) and Linux (x86-64), on top of huginn core. See
[The apps](../guide/apps.md).

| Command | What it does |
|---|---|
| `huginn desktop` | Download links for the newest desktop release (Windows, Debian/Ubuntu, Arch, portable) with sizes and SHA-256 sums, marking the one for this computer. |
| `huginn desktop win\|linux\|arch\|portable` | That build's URL alone, for scripts: `curl -fLO "$(huginn desktop linux)"`. |
| `huginn desktop install` | Linux: install or repair the per-user, self-updating build (no root, any distribution). Windows (PowerShell): download the installer, check its SHA-256, and run it per user. |
| `huginn desktop uninstall` | Linux: remove the per-user install; settings are kept. On Windows, remove the app from Settings > Apps, or use `huginn uninstall`. |
| `huginn desktop update` | Update the installed desktop app to the newest release: on Windows, the per-user Linux install, or the Debian or Arch package (with `sudo`). |

## Update and uninstall

| Command | What it does |
|---|---|
| `huginn update` | Update everything Huginn on this computer that is installed, to the newest release: this CLI, huginn core, the desktop app and the local AI tier. The local AI tier asks before it changes anything (on Windows, run `huginn local update` yourself; it asks for administrator approval). In PowerShell, open a new window, or run `. $PROFILE`, to load the new CLI. |
| `huginn uninstall [--all] [--yes]` | Remove the whole stack from this computer, top down: the desktop app, the local AI tier, this computer's device entry on the server, huginn core and this CLI. It asks you to type `uninstall` first; `--yes` skips that. Your SSH key and `Host huginn` entry are kept; `--all` removes the entry too, and the key only when it is Huginn's own (by file name or the comment in its `.pub`). |
| `huginn version` | The CLI version and the server alias it targets. |

`huginn update` and the release downloads come from GitHub. `HUGINN_UPDATE_HOST` names an SSH host
to fetch client updates from instead; it is unset by default.

## One-shots

One headless Claude Code turn on the server, printed to your terminal. Both run through the
server's `huginn-ask` command, with the same tool fence as chats in the apps. See
[Chats](../guide/chats.md).

| Command | What it does |
|---|---|
| `huginn -p "question"` | Ask: Claude Code may read, search and use the web; Bash, Edit, Write and NotebookEdit are denied. |
| `huginn -y "task"` | Act: Claude Code may also run commands and edit files (Bash, Read, Edit, Write, Glob, Grep, WebFetch, WebSearch). |

Both also get any extra tools the server grants (`HUGINN_APPD_EXTRA_TOOLS`) and the optional
persona file (`HUGINN_APPD_PERSONA_FILE`, default `/etc/huginn/persona.md`); see
[Server configuration](server-config.md). They run in `HUGINN_WORKDIR` as the server's SSH login
sees it, else the daemon's working directory, else the home directory.

`-p` is a fence against changes, not a sandbox: it can read any file the server's account can
read. Use both with the same care as an SSH shell on the server.

## Help

| Command | What it does |
|---|---|
| `huginn help` (`?`, `/help`, `-h`, `--help`) | The full command list. |
| `huginn <verb> --help` | One verb's usage, answered locally: `huginn archive --help`, `huginn device --help`. |
