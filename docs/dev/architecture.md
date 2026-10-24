# Architecture

Huginn keeps Claude Code running on one always-on Linux machine, the server, and lets every
other device reach the same sessions. All session state lives on the server. Every client is
a viewer and a keyboard: close the laptop in the middle of a turn, open the phone, and you are
in the same place.

There are two ways in, and they meet at the same tmux sessions:

- **The terminal path** is SSH and nothing else. `huginn` on a computer runs
  `ssh -tt <host> cc <session>`, which attaches to a tmux session on the server. The verbs
  that list, end or report on sessions are small commands run over the same SSH link.
- **The app path** is `huginn-appd`, a daemon on the server, on port 8787. A phone cannot
  usefully hold a terminal; it needs structure (which session is asking a question, what the
  answer buttons are). The daemon reads each session's pane and its Claude Code transcript and
  types into the pane, and it adds what a terminal cannot do: headless chats, push
  notifications, scheduled rounds, work dispatched to other machines.

```text
  YOUR COMPUTER (Linux, Windows)                  HUGINN SERVER (Linux, always on)
 +--------------------------------------+        +--------------------------------------+
 |                                      |  SSH   |                                      |
 |  huginn (shell client: huginn.sh,    |------->|  sshd -> cc -> tmux                  |
 |         huginn.ps1)                  |  :22   |                +- claude  (one per   |
 |    attach, session verbs, -p / -y    |        |                +- claude   session)  |
 |                                      |        |  host tools: huginn-ask,             |
 |  huginn core (Go, per-user service)  |        |    huginn-status, huginn-rounds, ... |
 |   core run   appd link: token, TLS   |        |                                      |
 |              pin-by-proof, routes    | HTTPS  |  huginn-appd (Node, root, :8787)     |
 |              /appd/* proxy           |------->|    sessions   chats      devices     |
 |              device runner           | :8787  |    rounds     projects   headroom    |
 |              watch -> notify rules   |        |    apps       push       alerts      |
 |   core tray  icon, notifications     |        |     |  capture-pane, send-keys       |
 |   console    plain `huginn` in a     |        |     |  claude -p (chats, rounds,     |
 |              terminal                |        |     |    -p / -y)                    |
 |      ^                               |        |     |  reads ~/.claude/projects/     |
 |      | AF_UNIX socket, this user     |        |                                      |
 |      |                               |        |  Claude Code hooks -> state dir,     |
 |  desktop app (Compose, JVM)          |        |  prompt sidecars, headroom gate,     |
 |                                      |        |  permission hook                     |
 +--------------------------------------+        +--------------------------------------+

  ANDROID APP (Kotlin) --- HTTPS :8787, pin-by-proof, bearer token ---> huginn-appd
                       <-- Firebase push (optional) -------------------
```

## The server

The server is a Debian or Ubuntu machine that runs as root. `server/setup.sh` installs it;
see [Install a server](../install/server.md).

### tmux and Claude Code

- **`cc`** (`server/bin/cc`) is attach-or-create: `tmux new-session -A`, starting `claude` in
  a new session. `cc <name> solo` detaches every other client first. This is what makes a
  session persistent and reattachable from any device.
- **`server/tmux.conf`** carries the multi-device settings: the window follows the smallest
  attached screen, `Alt-d` detaches, `Alt-o` detaches the other clients, a large scrollback.
- **When the daemon has to start the tmux server**, it starts it as its own systemd unit,
  `huginn-tmux.service`, and leaves a tmux server that is already running where it is.
  Sessions started from the apps therefore run outside the daemon's sandbox, and they survive
  daemon restarts and updates (`huginn-appd.service` also sets `KillMode=process`).

### Hooks

Claude Code tells Huginn what it is doing through hooks in `~/.claude/settings.json`:

- **`huginn-claude-title`** (from `server/claude-hooks.json`, merged by `setup.sh`) runs on
  every session event. It sets the terminal tab title and writes
  `/run/huginn-claude-state/<session>`: a JSON file with the session's state, its Claude
  session id and its transcript path. That file is the only link from a tmux session to its
  transcript. It also writes the sidecar files that describe an `AskUserQuestion` or
  `ExitPlanMode` prompt.
- **The daemon's own hooks** (`server/appd/hooks/`, merged by `server/appd/install-hooks.js`):
  the headroom gate (`SubagentStart`, `PreToolUse`) delays new subagents while a usage stop
  is armed; the permission hook (`PermissionRequest`) lets a tap in an app answer a tool
  prompt through Claude Code's own hook instead of keystrokes; the model-switch hook
  (`PostModelSwitch`) records each model switch so the model ladder can confirm a move. Each
  of them always exits 0: it can delay, never block.
- **`huginn-statusline`** (set as `statusLine` by `setup.sh`) is not a hook but lives in the
  same file: Claude Code runs it on every redraw of its status line, and it records the status
  JSON as `/run/huginn-claude-state/.status/<session>` — the live context window, model,
  effort, fast mode, limits and cost the daemon serves on every session — then prints the
  status line that was configured before it, or `<model> · ctx N%`.

Which events, which flags and which files Huginn depends on are listed in
[Claude Code integration](claude-code-integration.md).

### Host tools

`server/bin/` holds the commands the clients run over SSH:

| Tool | What it does |
|---|---|
| `cc` | Attach to or create a session |
| `huginn-ask` | One headless turn for `huginn -p` (ask: read, search, web; Bash, Edit, Write denied) and `huginn -y` (act), fenced exactly like an app chat |
| `huginn-status` | One-glance health summary |
| `huginn-rounds`, `huginn-devices`, `huginn-headroom`, `huginn-archive`, `huginn-projects`, `huginn-llm` | Read the daemon's API on the server and print a report, so the rendering exists once and the token never leaves the server |
| `huginn-claude-title` | The title and state hook above |
| `huginn-statusline` | Claude Code's status line above |

The state marks and words these tools print come from `server/lib/state-table.json` through
generated readers (`server/lib/state-table.{sh,py,js}`).

### huginn-appd

`server/appd/huginn-appd.js` is a Node program with no npm dependencies. Most of its logic is
in pure modules under `server/appd/lib/`, which is what the test suite exercises. It runs as
root, listens on port 8787 (every interface by default, `HUGINN_APPD_BIND` to change it), and
requires `Authorization: Bearer <token>` on every route except `GET /v1/challenge`. The token
is `/etc/huginn-appd/token` and is equivalent to root on the server.

The port serves HTTPS with a certificate the daemon mints itself; plain HTTP is accepted only
from loopback. Clients pin that certificate by proving it with the token before they send the
token. The full contract is [Daemon TLS protocol](protocol-tls.md).

| Subsystem | What it does | Main code |
|---|---|---|
| Sessions | Lists tmux sessions with the state the hook recorded; reads the screen with `capture-pane` and the conversation from the Claude Code transcript; types with `send-keys`; detects numbered prompts and answers them | `lib/pane.js`, `lib/transcript.js`, `lib/ask.js`, `lib/typing.js` |
| Send queue | Every automated line and every client message to a session goes through one per-session queue, released only at a real turn boundary, so nothing is spliced into an answer being written | `lib/typing.js` |
| Pane lease | A live view may resize a tmux window. The resize is a lease: one holder per session, keyed on `X-Huginn-Client`, renewed while viewing, expiring on its own, released on leave and swept at startup | `huginn-appd.js` |
| Chats | Headless turns: `claude -p --output-format stream-json` in the configured working directory, in ask or act mode, streamed as server-sent events with a replay buffer so a client that reconnects catches up | `huginn-appd.js`, `lib/chatqueue.js` |
| Devices | Machines that offer to run work. A device pulls: it long-polls for work, sends heartbeats and streams output back. What a request may become on the device is the generated device policy | `lib/devices.js`, `lib/device-policy-table.js` |
| Rounds | Scheduled, recurring headless runs with a goal and a time cap | `lib/rounds.js` |
| Projects | Several sessions working one brief, with a lead and members | `lib/projects.js` |
| Headroom | The plan's usage windows for every saved account, and one arbiter that decides account switches, the model ladder and stops; resumes sessions stalled on a usage limit | `lib/headroom.js`, `lib/sentinels.js`, `lib/limits.js`, `lib/resume.js` |
| Apps | A registry of web apps this server hosts, probed at every address a client has used | `lib/apps.js` |
| Push and alerts | Firebase push to the Android app when a service-account key is configured; an optional alert script for when no client is watching | `lib/fcm.js`, `lib/pushtokens.js`, `lib/alerts.js` |
| Also | Archive (ended sessions, revivable), scratchpads, uploads, saved logins | `lib/archive.js`, `lib/scratchpads.js`, `lib/uploads.js`, `lib/accounts.js` |

The daemon speaks three state words on the wire for a session: `running`, `attention` and
`idle`. Clients map them, once, to the words a person reads: working, needs you, waiting
(and shell when there is no Claude Code in the pane).

`/etc/huginn/persona.md` (`HUGINN_APPD_PERSONA_FILE`), when present, is appended to the system
prompt of chats, rounds and `huginn -p`/`-y`. Every daemon setting is in
[Server configuration](../reference/server-config.md); the routes are in
[the API reference](../reference/api.md).

## huginn core

huginn core is one Go binary named `huginn`, installed per user on Linux and Windows. It is
the part of the client that is always on: it starts at boot, before anyone logs in, and it is
the only part of a computer that holds the daemon's token. Details of the code are in
[Go core](go-core.md).

The binary has several roles:

| Role | Started by | What it is |
|---|---|---|
| `huginn core run` | The boot service (`huginn-core.service` under `systemd --user` with linger; the "Huginn Core" scheduled task on Windows) | The core itself: the link to the daemon, the IPC server, the device runner, the watch stream |
| `huginn core tray` | Graphical login | The tray icon, its menu and OS notifications. It holds no token and makes no decisions; it shows what `core run` publishes |
| `huginn console` | Plain `huginn` in an interactive terminal | A terminal UI over the core's IPC socket |
| `huginn core ...`, `huginn device ...`, `huginn setup ssh`, `huginn uninstall` | You | Verbs the binary owns |
| Every other verb | You | Handed to the shell client (`~/.huginn/huginn.sh` or `huginn.ps1`) |

On Windows the boot task runs in session 0, which has no desktop, so it cannot draw a tray
icon or post a toast. That is why the tray is a separate role. Linux uses the same split, so
that a crash in tray code cannot take the device runner down.

### IPC

The core serves HTTP/1.1 on an AF_UNIX socket, on both Linux and Windows:

- Linux: `$XDG_RUNTIME_DIR/huginn/core.sock`. Windows: `%LOCALAPPDATA%\huginn\run\core.sock`.
- The directory is `0700` (a user-only ACL on Windows) and the socket `0600`.
- Every accepted connection's peer is checked against the core's own user before a byte is
  read: `SO_PEERCRED` on Linux, `SIO_AF_UNIX_GETPEERPID` and the process token's SID on
  Windows.
- Every request carries `X-Huginn-Core-Proto`. A different protocol version is refused with
  `426`, naming both versions.
- There is no TCP listener.

Two route families share the socket:

- **`/core/*`**, the core's own verbs: `status`, `events` (a server-sent event stream of core
  state, for the tray, the console and the desktop app), `pair`, `unpair`, `routes`, `device`,
  `presence`, `tray`, `gui`, `restart`, `local/seed`.
- **`/appd/*`**, a transparent proxy to the daemon. `/appd/v1/...` goes to the daemon's
  `/v1/...` with the core's token added, over the core's proven, pinned connection. Request
  and response bodies stream both ways (server-sent events, 25-second long polls, uploads and
  downloads all pass through), the daemon's status, headers and body come back unchanged, and
  a caller's `X-Huginn-Client` and `X-Huginn-Notify` headers reach the daemon as its own. Only
  `/v1/` paths are forwarded. When the core cannot help, it answers `503` with
  `"kind": "unpaired"`, or `502` with `"kind"` set to `unreachable`, `tls` or `auth`.

### The link to the daemon

- **Pairing.** `huginn core pair --url <address>` (or the desktop app's setup) gives the core
  an address and the token. The core proves the daemon's certificate with the token and keeps
  the token in its secrets store: a `0600` file under `~/.config/huginn/secrets/` by default,
  because a login keyring is locked at boot. `HUGINN_SECRETS=keyring` opts into the OS
  keyring.
- **The route book.** One daemon can have several addresses (a LAN address at home, a VPN
  address away). The core keeps them in `core.json`, tracks each one's health, and retries a
  request that never reached the daemon once on the next healthy address. Every address is
  judged by the same pin. `huginn core route add|remove|pin|auto` edits the book.

### Device runner

`huginn device on` offers the computer to the server as a place to run work, with a scope
(`look`, `work` or `own`). The runner opens only outbound connections: it long-polls the
daemon for work, sends heartbeats, runs `claude -p` with the argument list the generated
device policy gives for that scope and request, and streams the output back. A lock probe
tells it whether someone is at the machine, because act-mode work is fenced while the screen
is locked unless the person allowed it.

### Notifications

`core run` follows the daemon's watch stream (`/v1/watch?stream=1`), runs the notification
rules (`core/internal/notify`, a port of the apps' rules with their tests) and publishes a
summary and notices on `/core/events`. The tray role draws them: D-Bus notifications on
Linux, toasts on Windows. The core tells the daemon it will show notifications
(`X-Huginn-Notify: 1`) only while a tray is attached and the session is unlocked; otherwise
the daemon's other channels take over.

### Updates and the local AI tier

- The shell client fetches a newer core (`huginn update`, `huginn core update`), checks its
  sha256, and runs the new binary's `huginn core self-install`. Self-install puts the binary
  in the per-user layout, lays down the shell client it carries, and restarts the running core
  and tray onto the new version.
- The experimental local AI tier is a separate program, `huginn-local`
  (see [Local AI](../guide/local-ai.md)). huginn core hands it the daemon's token
  (`POST /core/local/seed`) so that no other program has to hold one, and removes it on
  `huginn uninstall`.

## The shell client

`client/huginn.sh` (bash, sourced from your shell start-up file as a function) and
`client/huginn.ps1` (PowerShell) are the `huginn` command people type. They target an SSH
host alias, `huginn` unless `HUGINN_HOST` says otherwise.

- **Attach** runs `ssh -tt <host> cc <session>` in a reconnect loop: a dropped link reconnects,
  a clean detach stops, and three exits in under five seconds give up. On reconnect it mirrors
  if another device is attached and takes the session solo if only its own dead client
  remains. It names the terminal tab after the session.
- **Session verbs** (`ls`, `kill`, `end`, `rounds`, `devices`, `projects`, ...) run small
  commands or host tools over SSH.
- **`huginn -p` and `huginn -y`** pipe the prompt to `huginn-ask` on the server.
- **Core verbs** go to the binary. Plain `huginn` in an interactive terminal opens the console
  when a core that has one is installed, and attaches to `main` otherwise.

huginn core carries the shell client inside itself and installs it, so a computer with a core
always has the matching client. When the binary is called with a verb it does not own (from
fish, `cmd.exe` or a script, where the shell function is not loaded), it runs the installed
shell client with that verb and sets `HUGINN_VIA_CORE=1` so the two can never loop.

On Termux the shell client is the whole client: huginn core does not run there.

## The console

`huginn` with no arguments in an interactive terminal opens the console
(`core/internal/console`, built on Bubble Tea). It reaches everything through the core's
socket: `/core/*` for the core, `/appd/*` for the daemon, so the token never enters the
console's process. Only attaching uses SSH, with the same reconnect rules as the shell
client. See [The console](../guide/console.md).

## The Kotlin apps

One Kotlin codebase in `mobile/` builds both apps:

| Module | Targets | What it holds |
|---|---|---|
| `:core` | Android, JVM | Wire models, `HuginnClient` (every HTTP call either app makes), the route book and TLS pinning, the terminal grid and markdown renderers, pure UI rules |
| `:ui` | Android, JVM | The shared look: theme, state colours, markdown, transcript rows, the terminal painter |
| `:app` | Android | Everything that needs a phone: notifications, the background watcher, the widget, the keystore |
| `:app-desktop` | JVM | The desktop frame: window, panes, keys, settings |

Neither app holds session state. They render what the daemon reports and send keystrokes
back.

- **The Android app** talks to the daemon directly over HTTPS, with the token and the
  pin-by-proof rules from `:core`. It receives Firebase push when it was built with the same
  Firebase project as the server's key; otherwise a background watcher and an adaptive alarm
  check for sessions that need you.
- **The desktop app** reaches the daemon only through huginn core. Its `HuginnClient` runs on
  a transport that sends every request to the core's `/appd/*` proxy over the AF_UNIX socket,
  so the app holds no token and does no TLS. Its `/core/*` calls drive pairing, the route book
  and the device settings. While no core answers, the app shows only a screen that sets up or
  starts huginn core. An install that still holds a token of its own from before hands it to
  the core at start, and deletes it once a request through the core has worked;
  `HUGINN_DESKTOP_DIRECT=1` keeps the app on its own link, for debugging.

See [Kotlin clients](kotlin-clients.md) for the module rules.

## Who holds the token

| Holder | Where |
|---|---|
| The server | `/etc/huginn-appd/token`, read by the daemon and the host tools |
| huginn core | Its secrets store (a `0600` file by default) |
| The Android app | Its own settings store |
| The local AI tier, when enabled | A `0600` file in the tier's directory, written by huginn core |
| The Node device runner, on a computer without huginn core | `~/.config/huginn/appd-token` (`0600`) |

The desktop app, the tray role and the console never hold it. Anything that holds the token
is root-equivalent on the server; see [Security](../security.md).
