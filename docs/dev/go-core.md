# Go core

huginn core is the Go module in `core/` (`github.com/silencelen/huginn/core`). It builds one
static binary, `huginn`, for Linux (x86_64, arm64), Windows (x64) and Termux (arm64). How it
fits with the rest of Huginn is in [Architecture](architecture.md); how to build it is in
[Building](building.md#huginn-core).

## Layout

```text
core/
  cmd/huginn/            the binary: verb dispatch and the commands it owns
  internal/              the packages (below)
  test/e2e.sh            end-to-end against a real daemon from the tree
  test/e2e-migrate.sh    first start adopts an existing device row
  third_party/bubbletea/ a patched copy of a dependency (see below)
  build.sh               reproducible builds of every target
  go.mod                 Go 1.26; the replace for bubbletea
```

### `cmd/huginn`

| File | What it holds |
|---|---|
| `main.go` | Verb dispatch and the usage text |
| `client.go` | Hands every verb the binary does not own to the installed shell client |
| `consolecmd.go` | `huginn console`, and plain `huginn` in an interactive terminal |
| `core.go` | `huginn core run`, `install`, `start`, `uninstall`, `status`, `pair`, `tray`, `self-install` |
| `route.go` | `huginn core route add`, `remove`, `pin`, `auto` |
| `device.go`, `device_printargv.go` | `huginn device on`, `off`, `status`; `print-argv`, which prints the argument list the policy gives (the case-matrix test drives it) |
| `setup.go` | `huginn setup ssh`: the SSH key and `Host` entry for attaching |
| `doctor.go` | `huginn doctor [--json]`: runs `internal/doctor` and prints it, exit 1 on a failed check |
| `skills.go` | `huginn skills status|install|remove`, and the installer's `Install Huginn skills for Claude Code? [Y/n]` (`skillsOffer`, which `core install` runs; the hidden `skills offer` is the PowerShell client's way to it after its elevated step) |
| `upgrade.go`, `downgrade.go` | What `self-install` does to a running core and tray; never replacing a newer installed core |
| `uninstall*.go` | `huginn uninstall [--all] [--yes]`, top down: desktop app, local AI tier, device row (on the server first), core, shell client; `--all` also removes the `Host huginn` SSH entry and huginn's own key |
| `installui.go`, `elevate.go`, `startup_*.go` | Installer-style output, the one elevated step on Windows, Windows start-up details |

### `internal/`

| Package | What it does |
|---|---|
| `appd` | The client for `huginn-appd`: token resolution, TLS pinned by a token-bound proof, the route book, the typed calls the runner makes. The contract is [Daemon TLS protocol](protocol-tls.md) |
| `clientkit` | The shell client the core carries and installs (see below) |
| `console` | The interactive console, on Bubble Tea. Talks only to the core's socket; attaches over SSH |
| `daemon` | Assembles `huginn core run`: store, token, appd client, device runner, IPC routes, the `/appd/*` proxy, the route book, the watch stream, the local tier's token seed |
| `doctor` | `huginn doctor`: the chain of checks over a `Probe` (files for real, commands, the socket and the network through function fields a test replaces); `Default()` is the real machine, `Render` the installer-style lines, `JSON` the stable array |
| `install` | The per-user layout and the atomic version swap; the one implementation behind the installers, the desktop app's setup and `self-install` |
| `ipc` | HTTP/1.1 over AF_UNIX with a per-connection peer check, the protocol-version gate and the server-sent event hub |
| `localtier` | Where the local AI tier lives and where its token file goes |
| `lock` | Is somebody at this machine? Three answers: unlocked, locked, unknown (treated as locked) |
| `notify` | What to notify about, from the daemon's watch digest. Pure: no OS, no network, no clock |
| `policy` | What a remote request may become on this machine. `table_gen.go` is generated from `shared/device-policy.json` |
| `proc` | Starting the tray detached from its caller; session and elevation checks |
| `runner` | The device runner: pull work, run `claude -p`, stream output, honour cancels |
| `service` | The boot service: the systemd user unit and linger on Linux, the scheduled tasks and shortcuts on Windows |
| `skills` | The Claude Code skills the core carries (`files/`, a copy of `skills/huginn*` at the repository root): laid under the install root, linked (Linux) or copied with a marker (Windows) into `~/.claude/skills`, replacing only what Huginn made; `Decide` is the installer's question as a pure rule |
| `store` | `core.json`, the secrets store (file or OS keyring) and the one-time import of older on-disk state |
| `tray` | The tray role: icon, menu and OS notifications, drawn from `/core/events` |
| `traystate` | The event types shared by `core run` and the tray role |
| `ui` | The installer-style progress lines the shell clients print too |
| `version` | `Version` (set at link time) and `Proto`, the IPC protocol version |

Platform code is split by file suffix (`_linux.go`, `_windows.go`, `_other.go`, `_unix.go`).
Where a decision can be pure, it is a pure function of its inputs (for example
`store.PathsFor`, `service.TaskXML`, `lock.WindowsReading`), so the Windows answer is tested
on Linux.

## The shell client and the binary

Two programs answer to `huginn`:

- **The shell client** (`~/.huginn/huginn.sh`, sourced as a bash function, or
  `~/.huginn/huginn.ps1` in a PowerShell profile) owns attaching and the session verbs. A loaded
  function shadows the binary on `PATH`.
- **The binary** (`~/.local/bin/huginn`, `%LOCALAPPDATA%\huginn\bin\huginn.exe`) owns
  `version`, `core`, `device`, `console`, `setup`, `doctor`, `skills` and `uninstall`.

Each hands the other what it does not own:

- The shell client calls the binary for `huginn core ...`, `huginn device ...` (when a core is
  installed), `huginn setup`, `huginn console`, `huginn doctor`, `huginn skills`, and for plain
  `huginn` in an interactive terminal once `huginn console --probe` exits 0.
- The binary, called with any other verb (from fish, `cmd.exe`, a PowerShell without the
  profile, a script), runs the installed shell client with that verb: `bash -c '. "$0" &&
  huginn "$@"'` on Linux, and on Windows `powershell.exe` with the arguments passed as JSON in
  an environment variable so PowerShell never re-parses them. It sets `HUGINN_VIA_CORE=1`, and a
  binary that sees that variable never forwards, so the two cannot loop. It ignores Ctrl-C
  while the client runs, so an attached session gets it.

Plain `huginn` opens the console only when both ends are a terminal, `TERM` is not `dumb` and
`HUGINN_CONSOLE` is not `0`; otherwise it attaches to `main`, as it always has.

## The embedded shell client

`core/internal/clientkit/files/` holds copies of `client/huginn.sh`, `client/huginn.ps1`,
`client/huginn-local` and `client/huginn-llm-shim`, with carriage returns stripped and a `.txt`
suffix so `.gitattributes` does not give the copy CRLF. They are embedded with `//go:embed`.
`huginn core self-install` writes the shell client into `~/.huginn/` and adds the lines that
load it to your shell start-up files (matched whole-line on removal, so a line you wrote by
hand is reported, never rewritten). The local AI helpers are refreshed only where they are already installed.

The copy must be the commit's `client/`:

- `TestEmbeddedMatchesClient` fails when a copy differs from its source.
- `core/build.sh` refuses to build, and prints the fix.
- For a version without a suffix, `core/build.sh` also refuses unless the carried
  `huginn.sh` says the same `HUGINN_VERSION`.

Refresh the copies after changing any of the four files:

```bash
for f in huginn.sh huginn.ps1 huginn-local huginn-llm-shim; do
  tr -d '\r' < "client/$f" > "core/internal/clientkit/files/$f.txt"
done
```

## The embedded skills

`core/internal/skills/files/` is a copy of `skills/huginn/` and `skills/huginn-doctor/` at the
repository root, embedded the same way (`embed` cannot reach outside the package). `core
self-install` lays them under the install root (`<root>/skills/<name>/`) and refreshes the ones
already in `~/.claude/skills`; `huginn core install` asks whether to link them there the first
time (`HUGINN_SKILLS=1|0` presets the answer; no terminal means no, not remembered); `huginn
skills install|remove|status` is the same by hand. A link whose target is under the install
root, or a directory carrying the `.huginn-skill` marker (Windows copies), is Huginn's to
replace and remove; anything else at that name is the person's and is left alone.

`TestEmbeddedMatchesSkills` fails when a copy differs from its source in either direction, and
`core/build.sh` refuses to build. After editing a skill:

```bash
rm -r core/internal/skills/files/huginn core/internal/skills/files/huginn-doctor
cp -r skills/huginn skills/huginn-doctor core/internal/skills/files/
```

`skills/huginn-server/` is not embedded: `server/setup.sh` installs it from the checkout.

## IPC

`internal/ipc` serves HTTP/1.1 on an AF_UNIX socket, on Linux and on Windows:

| | Linux | Windows |
|---|---|---|
| Socket | `$XDG_RUNTIME_DIR/huginn/core.sock` (fallback `/run/user/<uid>/huginn/`) | `%LOCALAPPDATA%\huginn\run\core.sock` |
| Directory | `0700` | user-only ACL |
| Peer check | `SO_PEERCRED`: the peer's uid must be ours | `SIO_AF_UNIX_GETPEERPID`, then the process token's user SID must be ours |

The peer is checked on every accepted connection, before the HTTP layer reads a byte. Every
request must carry `X-Huginn-Core-Proto: <version.Proto>`; anything else is answered `426`
with both versions. Bump `Proto` in `internal/version` on any incompatible change to `/core/*`
or `/appd/*`. There is no TCP listener.

The desktop app computes the same socket path (`CorePaths.kt` in `mobile/app-desktop`);
change both together.

To talk to a running core by hand:

```bash
curl -s --unix-socket "$XDG_RUNTIME_DIR/huginn/core.sock" \
  -H 'X-Huginn-Core-Proto: 1' http://huginn-core/core/status
```

### Routes

| Route | What it does |
|---|---|
| `GET /core/status` | Version, pairing, address, proved pins, device status, whether a tray is attached |
| `GET /core/events` | Server-sent events: core state, the tray summary and notices. The tray and the desktop app attach here |
| `POST /core/pair`, `POST /core/unpair` | Pair with a daemon (the core proves the address with the token before keeping it), or forget it. Pairing the same daemon again keeps the device runner and its job; pairing another one, or unpairing, stops a job that is running |
| `GET`, `POST`, `PATCH`, `DELETE /core/routes` | The route book. An edit applies to the running client in place: a job that is running carries on |
| `GET`, `PATCH /core/device` | The device runner's state and settings |
| `POST /core/presence` | The desktop app reports what it is showing, so the core does not notify about it |
| `POST /core/tray` | `{"cmd": "hide"}`: the desktop app asks the tray to take its icon away |
| `POST /core/gui` | The tray menu acting on the desktop app: `open` (with a `huginn://` target), `quit`, `closeToTray`. Delivered once, never replayed |
| `POST /core/restart` | Exit so the supervisor restarts the new binary (used by `self-install`) |
| `POST /core/local/seed` | Write the local AI tier's token file and return the address to use |
| `/appd/v1/...` | The transparent proxy to the daemon (see [Architecture](architecture.md#ipc)) |

## Running a core from the tree

Build it, then run it in the foreground under a scratch home so it touches nothing of yours,
the way `core/test/e2e.sh` does:

```bash
(cd core && go build -o /tmp/huginn-dev ./cmd/huginn)
export D="$(mktemp -d)"
mkdir -p "$D/home" "$D/run" && chmod 700 "$D/run"
export HOME="$D/home" XDG_CONFIG_HOME="$D/home/.config" \
       XDG_STATE_HOME="$D/home/.local/state" XDG_RUNTIME_DIR="$D/run"
/tmp/huginn-dev core run
```

In a second terminal with the same environment, pair it with a daemon. The token goes on
standard input or in `HUGINN_APPD_TOKEN`; against the development daemon from
[Building](building.md#run-a-daemon-from-the-tree):

```bash
/tmp/huginn-dev core pair --url http://127.0.0.1:18787 < "$T/token"
/tmp/huginn-dev core status
```

To try the desktop app or the console against your build with your normal pairing, stop your
installed core and run your build in its place; it uses the same config directory and socket:

```bash
systemctl --user stop huginn-core.service
/tmp/huginn-dev core run
# when you are done:
systemctl --user start huginn-core.service
```

A second `core run` exits at once when a core already serves the socket, which is why you
stop the installed one first.

To install your build over your own, run `/tmp/huginn-dev core self-install`. It keeps a newer
installed core (a plain build calls itself `2.0.0-dev`) unless you add `--force`.

`HUGINN_CONFIG_DIR` moves only the config directory, for a second profile on one account; the
state and runtime directories stay where they are.

## Platform services

### Linux

`huginn core install`:

1. self-installs the binary into
   `${XDG_DATA_HOME:-~/.local/share}/huginn/versions/<version>/`, swaps the relative `current`
   link atomically, and points `~/.local/bin/huginn` at `current/huginn`;
2. writes `~/.config/systemd/user/huginn-core.service` (`ExecStart=<~/.local/bin/huginn> core
   run`, `Restart=always`, `RestartSec=10`) and runs `systemctl --user enable --now`;
3. runs `loginctl enable-linger <user>`, so the service starts at boot with nobody logged in.
   If huginn turned linger on, `huginn uninstall` turns it off again. If logind refuses, the
   core says so and runs only while you are logged in;
4. writes the tray's autostart entry, `~/.config/autostart/huginn-core-tray.desktop`
   (`huginn core tray`), when the shell has a graphical session (`DISPLAY` or
   `WAYLAND_DISPLAY`). `--tray` writes it anyway; `--no-tray` skips it.

The unit always points at the stable path, never into `versions/<version>`, so an update that
swaps `current` is picked up by the next start. One core per machine: a per-user install
refuses to install its service beside a package-owned `/usr/bin/huginn`.

A core in the middle of a device job is not restarted, on either platform (a restart of the unit,
or ending the Windows task, would stop the job's `claude` with it): `core install` asks it to
restart onto the installed binary once the job is done, as an update does.

The tray is a StatusNotifierItem (`fyne.io/systray`) and notifications go over D-Bus
(`org.freedesktop.Notifications`), with the body escaped. A lock file in the runtime directory
allows one tray per user. The lock probe finds the user's graphical session among their logind
sessions and reads `LockedHint`. With no graphical session, the answer depends on whether the
machine can show a graphical login (logind's seat0 `CanGraphical`): a headless box counts as
unlocked, and a desktop sitting at its login screen counts as locked. A probe that fails is
unknown, which the fence treats as locked.

The default secrets store is a `0600` file under `~/.config/huginn/secrets/`, because a login
keyring is locked at boot. `HUGINN_SECRETS=keyring` uses the Secret Service instead.

### Windows

`huginn core install` installs `%LOCALAPPDATA%\huginn\bin\huginn.exe` and puts that directory
on the user's `PATH`. A running `huginn.exe` is renamed aside so the new one can take its
place. Then it registers two scheduled tasks and a shortcut:

| Item | What it is |
|---|---|
| "Huginn Core" task | Runs `huginn.exe core run` as you, at least privilege. Triggers: boot (30 seconds delayed), a repeat every 5 minutes, and your logon, so a start that failed before the network was up is retried. `IgnoreNew` drops a start while one runs. Runs on battery; restarts on failure |
| "Huginn Tray" task | Runs `huginn.exe core tray` at your logon, in your session (`InteractiveToken`), so the icon appears at once |
| Startup shortcut `Huginn.lnk` | The same tray command, stamped with the AppUserModelID `com.silencelen.huginn.desktop-kt`, the identity toasts are posted under |

The boot task's logon type is **S4U** by default: no password is stored, the task has no
network credentials, and the token lives in the user-only secrets file under
`%APPDATA%\huginn\secrets`. `--logon password` stores your password with Task Scheduler
instead; it is read without echo. Registering the boot task needs one elevated run (the
PowerShell client runs `core install` elevated, then starts the tray unelevated); the tray task
does not.

The boot task runs in session 0, which has no desktop. That is why the tray is a separate
process: toasts and the tray icon need the signed-in session, and so does the input-idle time.
The lock probe reads which sessions `LogonUI.exe` runs in and `quser`, both of which work from
session 0. Toasts are posted through PowerShell's WinRT bridge under the AppUserModelID; a
toast whose AUMID matches no Start Menu or Startup shortcut is dropped by Windows without an
error.

### Termux

`core/build.sh` also builds `android-arm64`, but on Termux `huginn core` (except `status`), the
console and the binary's `uninstall` refuse: Android stops background processes, and on a
phone the Android app is the device. The shell client is the whole client there.

## The vendored bubbletea

`core/third_party/bubbletea/` is an unmodified copy of `github.com/charmbracelet/bubbletea`
v1.3.10 (MIT), wired in by a `replace` in `core/go.mod`, with one file left out:
`tea_init.go`.

That file's `init()` queries the terminal's background colour: raw mode, an OSC 11 query and a
cursor report, then a wait of up to five seconds for the answer. Because `huginn` is one
binary, the console's packages are linked into every command, so every `huginn ...` on a
terminal paid for it: a round trip per command over SSH, a five-second stall where the terminal
does not answer, and type-ahead eaten as the reply. The console makes the same query itself in
`console.Run`, just before the program starts, so it keeps the protection and nothing else pays.

To move to a newer bubbletea: copy the new version's non-test `.go` files, `go.mod` and
`LICENSE` into `core/third_party/bubbletea/`, delete `tea_init.go`, and update the version in
`HUGINN-PATCH.md` and `core/go.mod`. Drop the copy when the console moves to bubbletea v2.

## Tests

```bash
cd core
go vet ./... && go test ./...
core/test/e2e.sh && core/test/e2e-migrate.sh     # from the repository root
```

| What | Where |
|---|---|
| Policy table against the shared case matrix | `internal/policy` (and `HUGINN_DEVICE_BIN` for the daemon's matrix test) |
| TLS proof, pins, route switching, a real daemon behind a relay | `internal/appd` |
| Socket modes, peer check, the `426` gate, the event hub | `internal/ipc` |
| Unit and task files, golden-compared | `internal/service` (`testdata/`, `-update` rewrites) |
| Console screens, golden-compared | `internal/console` (`testdata/`, `-update` rewrites) |
| Notification rules, and the watch stream from a real daemon | `internal/notify` |
| Runner against a fake daemon and a fake `claude` | `internal/runner` |
| Embedded client matches `client/` | `internal/clientkit` |
| Embedded skills match `skills/`; install, refresh and remove against a scratch home | `internal/skills` |
| The doctor's checks against a fake machine (files in a scratch home; commands, socket and network faked) | `internal/doctor` |
| Pairing, proxy, route book, device runner, upgrade, uninstall | `core/test/e2e.sh` |

The tests that start a real daemon need `node` and `tmux` and skip without them. Details,
floors and the rules every test follows are in [Testing](testing.md).
