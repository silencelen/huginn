---
name: huginn
description: Huginn keeps Claude Code sessions running on a server the person owns; this machine reaches them with the `huginn` command, huginn core and the console, or the desktop and Android apps. Use when the person mentions huginn, huginn core, the huginn CLI or console, "my sessions on the server", devices, rounds, routes, the local AI tier, or asks what a `huginn ...` command does or where huginn keeps its files on this machine.
---

# Huginn on this machine

Huginn has two parts:

- **Huginn Server**: one always-on Linux machine the person controls. Claude Code runs there in
  persistent tmux sessions, and `huginn-appd` (the daemon, port 8787, HTTPS) serves the clients.
- **Huginn Client**: what is on THIS machine. The `huginn` command (a shell function from
  `~/.huginn/huginn.sh`, or `huginn.ps1` in PowerShell), **huginn core** (one Go binary, also
  called `huginn`, that runs as a per-user service, keeps the pairing with the server, draws the
  tray and the console, and can run work here as a *device*), and optionally the desktop app.
  The Android app connects to the same server.

Two programs answer to `huginn`: the shell function owns attaching and the session verbs (over
SSH, `Host huginn`); the binary owns `core`, `device`, `console`, `setup`, `doctor`, `skills`
and `uninstall`. Each hands the other what it does not own, so any spelling works.

## Where things live here

| What | Linux | Windows |
|---|---|---|
| huginn core binary | `~/.local/bin/huginn` -> `~/.local/share/huginn/current/huginn` (versions under `~/.local/share/huginn/versions/`) | `%LOCALAPPDATA%\huginn\bin\huginn.exe` |
| the shell client | `~/.huginn/huginn.sh`, loaded by `~/.bashrc` (and `~/.zshrc`, `~/.profile`) | `~\.huginn\huginn.ps1`, loaded by `$PROFILE` |
| `core.json` (pairing, routes, pins, device settings; never the token) | `~/.config/huginn/core.json` | `%APPDATA%\huginn\core.json` |
| the access token | `~/.config/huginn/secrets/` (0600 files; `HUGINN_SECRETS=keyring` uses the Secret Service) | `%APPDATA%\huginn\secrets\` |
| state (markers, no data) | `~/.local/state/huginn/` | `%LOCALAPPDATA%\huginn\state\` |
| the core's socket | `$XDG_RUNTIME_DIR/huginn/core.sock` | `%LOCALAPPDATA%\huginn\run\core.sock` |
| the boot service | `~/.config/systemd/user/huginn-core.service`, with linger | the "Huginn Core" scheduled task; "Huginn Tray" task + Startup `Huginn.lnk` |
| logs | `journalctl --user -u huginn-core.service` | `huginn core status`; Task Scheduler's history |
| these skills | `~/.local/share/huginn/skills/<name>`, linked from `~/.claude/skills/<name>` | `%LOCALAPPDATA%\huginn\skills\<name>`, copied to `~\.claude\skills\<name>` |
| local AI tier (optional) | `~/.config/huginn-local` | `%ProgramData%\huginn-local` |
| SSH to the server | `Host huginn` in `~/.ssh/config`, key `~/.ssh/id_ed25519` | the same, under the profile |

Nothing here is shared between accounts: every path is per user. Details: [core.md](core.md).

## The verbs, in one breath

- Sessions (over SSH): `huginn main`, `huginn <name>`, `huginn attach <name>`, `huginn ls`,
  `huginn status`, `huginn end <name>`, `huginn kill <name>`, `huginn archive`, `huginn revive`.
- The console: plain `huginn` in a terminal (`huginn console`). [console.md](console.md).
- This machine: `huginn core status|install|start|pair|route|update`, `huginn device on|off|status`,
  `huginn setup ssh`, `huginn doctor [--json]`, `huginn skills install|remove|status`,
  `huginn update`, `huginn uninstall`.
- The server, rendered there (over SSH, the token never leaves the server): `huginn devices`,
  `huginn rounds`, `huginn headroom`, `huginn projects`, `huginn llm`, `huginn usage`,
  `huginn -p "question"` (read-only), `huginn -y "task"` (with tools).
- `huginn help`, and `huginn <verb> --help` for one verb, answered locally.

The full table with flags: [verbs.md](verbs.md). Devices and scopes: [devices.md](devices.md).
Local AI: [local-ai.md](local-ai.md).

## How to behave

- **Read first.** `huginn core status`, `huginn device status`, `huginn skills status`,
  `huginn core route` and `huginn doctor --json` change nothing. Prefer them to guessing.
- **Ask before anything that ends or changes work for the person**: `huginn end`, `huginn kill`,
  `huginn archive`, `huginn revive`, `huginn -y`, `huginn device on --scope work|own`,
  `huginn core pair`, `huginn core route remove|pin`, `huginn update`, `huginn uninstall`,
  `huginn local on|off`. A bare `huginn <name>` attaches a terminal: it is for the person, not
  for you.
- **The token is root on the server.** Never print it, never put it in argv, never copy it out
  of `~/.config/huginn/secrets` or `/etc/huginn-appd/token`. `huginn core pair` reads it from
  stdin or a prompt for that reason.
- **Never edit `core.json` by hand** while the core runs; use `huginn core route` and
  `huginn device on` (they go through the core's socket).
- **Something is broken?** Use the `huginn-doctor` skill: step 1 is `huginn doctor --json`.
- On the server itself, Claude Code sessions have the `huginn-server` skill instead; this one is
  about the client machine.
