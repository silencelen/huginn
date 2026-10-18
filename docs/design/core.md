# huginn core: the CLI as the core, the desktop app on top

**Status:** Implemented in huginn core 2.0.0 to 2.5.x. Phase 1 shipped in 2.0.0, the tray and
notifications in 2.1.x, the core carrying the CLI in 2.2.0, the `/appd/*` proxy in 2.3.0 (made
transparent in 2.5.0, see [desktop-via-core.md](desktop-via-core.md)). Three planned pieces are not
built: a single updater for core and desktop, the local AI manager inside the core, and one Go
binary replacing the shell clients. See [As built](#13-as-built).

**Summary:** before 2.0, every always-on feature of a Huginn client lived in whichever program
happened to be running: the desktop app's device runner existed only while someone was signed in,
the headless runner was a second implementation of the same protocol in another language, and the
desktop app could be installed without the `huginn` command. This record re-shapes the client side
so that one Go binary, `huginn`, is the core of every install. Its background mode, `huginn core`,
starts at boot as the user, holds the server link (token, routes, TLS pins), runs device work, owns
the tray and notifications, and lays down the CLI. The desktop app is a window on top of the core
and cannot be installed without it. The server side (`huginn-appd`, `cc`, tmux) and the Android app
are out of scope except where the wire contract is touched.

## 1. Why

Three problems with the client, which are one problem seen three ways:

1. **Background features lived in the GUI.** The desktop's act-mode runner (`device/DeviceRunner.kt`)
   existed only while the JVM ran, and the JVM ran only while someone was signed in. A machine that
   rebooted to its login screen disappeared as a device.
2. **Two implementations of one thing.** The headless runner (`client/huginn-device`, Node) and the
   desktop runner (Kotlin) spoke the same protocol and enforced the same generated policy, in two
   languages, with two sets of bugs. The desktop runner had a captured-scope bug the Node runner
   never had; the Node runner never probed the screen lock at all.
3. **The GUI could exist without the CLI.** `CliSync.kt` updated CLI files only if they were already
   there ("presence is the consent"), so a desktop-only machine had no `huginn` command, no headless
   runner and no `huginn local`.

The maintainer's goal: the CLI is the **core**; the desktop is a package on top of the core's
features; always-on features run in the core, so they never depend on a window being open; the GUI
can never be installed without the core; installing the CLI offers the GUI.

## 2. Decisions

| # | Decision | Notes |
|---|---|---|
| D1 | **The core is one Go binary, and it is `huginn`.** | Planned to replace `huginn.sh`, `huginn.ps1`, `huginn-device`, `huginn-local` and `huginn-llm-shim`. One static binary per OS and architecture, no runtime dependency. As built, the shell clients and the Node satellites remain; see §13. |
| D2 | **Background mode is `huginn core`**; unit `huginn-core.service`, Windows task "Huginn Core". | The Kotlin `:core` module keeps its name; it is internal. |
| D3 | **Per-user, starts at boot without a login.** Linux: `systemd --user` plus `loginctl enable-linger`. Windows: a boot-time scheduled task as the account. | Act mode needs the user's `~/.claude` login and must act as the user. |
| D4 | **The local AI engine stays a machine-scope service** that the core installs and supervises. | llama-swap as before (systemd system unit, or WinSW as LocalSystem). Root is needed only here. |
| D5 | **The core owns the tray and the notifications.** | SNI on Linux, Win32 on Windows. A headless machine has no tray. |
| D6 | ~~Closing the GUI window quits the GUI process.~~ **Superseded in 2.5.7: one tray icon, one menu.** | The core's tray carries the app's items while the app is open (Open Huginn, Close to tray, Quit Huginn, and its own "Hide this icon" last), and Close to tray works with it. The app attaches as `/core/events?role=gui`, reports Close to tray through `/core/presence`, and the tray's clicks reach it as `gui.cmd` through `POST /core/gui`, sent once and never replayed. The app shows its own icon only where the core has no tray. |
| D7 | **Per-user install, no root**, self-updating. | `~/.local/share/huginn`, `%LOCALAPPDATA%\huginn`. See §7 for how distro packages fit. |
| D8 | **One bootstrap script per OS** is the primary entry point. | `client/get.sh` and `client/get.ps1`. The 2.0.0 bootstrap asked whether to install the desktop GUI and whether to keep huginn running in the background. Since 2.3.0 it always installs the core; since 2.4.2 it asks whether to offer the machine as a device, install the desktop app, and (where a GPU qualifies) serve local AI. |
| D9 | **GUI-first installers install the core first.** | No installer can leave a machine with a GUI and no core. |
| D10 | **Lockstep versions, starting at 2.0.0**, for core and GUI. | The CLI was at 1.9 and the desktop at 1.13. appd and the Android app keep their own numbers. The IPC carries a protocol version anyway. |
| D11 | **Credentials in the OS keyring, with a 0600 file fallback** for headless machines. The GUI never holds the appd token. | Reversed for the boot service while building; see §12. |
| D12 | **The GUI reaches appd through the core**, which is a local authenticated proxy. | The same appd HTTP API, spoken to the core's socket; the core adds the token, TLS pin-by-proof and route choice. Built in 2.3.0 (for the console) and 2.5.0 (for the desktop). |
| D13 | **One device row per (machine id, OS user), minted by the core.** | The GUI never enrols. The local AI tier's `-llm` row is a child of the machine. |
| D14 | **The core claims notifications while the user is at the machine** (session unlocked, input idle under 10 minutes). | Away from the machine, appd's alert relay takes over. An open GUI also suppresses toasts for what it is showing. |
| D15 | **Platforms: Linux (Debian and Arch), Windows 10/11, Termux.** | Termux is a terminal client only, with no background mode. macOS is out of scope. |
| D16 | **Phased migration, runner first** (§10). | Each phase is a shippable release train. |

## 3. Shape

The target shape, as designed. §13 records where the build differs.

```text
 bootstrap (curl|sh / irm|iex)  -- or --  GUI-first installer (.exe/.deb/.pkg/.tar.gz)
        +- installs `huginn` - pairs with appd - [GUI? y/N] - [background? y/N]

 +---------------------------- one binary: huginn ----------------------------+
 |  huginn <verb>          interactive front: attach (ssh -tt + reconnect),   |
 |                          list/status/kill/end/llm/projects/..., update,    |
 |                          device/local/desktop/core management              |
 |                                                                            |
 |  huginn core run        THE CORE (boot service, no UI)                     |
 |    +- appd link         token (keyring/file) . routes . TLS pin-by-proof   |
 |    +- proxy             the GUI's appd API, SSE and uploads pass through   |
 |    +- device runner     act mode; lock probe; policy table (generated)     |
 |    +- local AI          installs/supervises llama-swap; -llm row; shim     |
 |    +- watch stream      /v1/watch -> notify rules -> tray role             |
 |    +- updater           itself + the GUI, one version                      |
 |    +- IPC server        unix socket                                        |
 |                                                                            |
 |  huginn core tray       TRAY ROLE (starts at graphical login)              |
 |    tray icon + menu . toasts (AUMID) . presence/idle . huginn:// handler   |
 |    -> talks to `core run` over IPC; hosts the core itself if none runs     |
 +----------------------------------------------------------------------------+
                     ^ IPC (same appd API + /core/* verbs)
 Huginn Desktop (Compose, JVM): launched by the tray, the start menu or huginn://;
   holds no token and runs no device work.
                     |
                     v (through the core) HTTPS, pin-by-proof, outbound only
                 huginn-appd (server)
```

### 3.1 Why two roles in one binary (`run` and `tray`)

A Windows task that starts at boot "whether the user is logged on or not" runs in **session 0**:
there is no desktop, so no tray icon and no toasts (toasts also need the app's AUMID in the
interactive session). Input idle time (`GetLastInputInfo`) is readable only from the interactive
session too. So the always-on half and the on-screen half cannot be one Windows process.

Linux could do both in one process: a lingering user service shares the user bus with the
graphical session, so SNI and `org.freedesktop.Notifications` are reachable once someone signs in.
The design uses the same split there for three reasons: one design to test, a crash in tray or UI
code cannot take the runner down, and the tray role naturally owns the graphical-session facts
(idle, lock, `DISPLAY`/`WAYLAND_DISPLAY`).

**Fallback:** if the user declines the background service, there is no `core run` service; the tray
role, started at login, hosts the core in-process, so features work while signed in, as the desktop
app did before. Turning on a feature that needs boot-time presence (act-mode availability, local AI)
offers to install the service at the moment it matters.

## 4. Who owns what (before 2.0 and the target)

| Component | Before 2.0 | Target | Notes |
|---|---|---|---|
| Attach, reconnect, mirror/solo, tab title | `huginn.sh` / `huginn.ps1` | `huginn` front | Exact semantics in §9. |
| Remote verbs (list, status, kill, end, llm, projects, ...) | shell clients over ssh | `huginn` front | Keep ssh as the transport for verbs that use ssh; see Q3. |
| Device runner | `huginn-device` (Node) **and** `DeviceRunner.kt` | core | One implementation. Kotlin runner deleted in phase 1. |
| Lock probe | `LockProbe.kt` only (the Node runner always said "unlocked") | core | Windows `LogonUI`/`quser` works from session 0; Linux reads logind `LockedHint` of the user's graphical session. |
| Unenrol debt (a machine turned off while offline deletes its row once the server answers) | `Unenrol.kt` | core | |
| Local AI manager and shim | `huginn-local`, `huginn-llm-shim` (Node); `LocalServe.kt` in the desktop | core | Consent stays a human click: `plan`, shown, then `on`. The shim becomes a sub-mode of the binary. |
| appd token, routes, pins, route health | desktop `settings.json` (plaintext, 0600); `~/.config/huginn/appd-token` | core store (keyring or 0600 file) | Migrated once, then wiped from `settings.json`. |
| Route resolution, challenge proof, TLS pins, `PUT /v1/apps/routes` | Kotlin `:core` plus `AppStore` | core | A security-critical rewrite. The TLS protocol document ([docs/dev/protocol-tls.md](../dev/protocol-tls.md)) is the spec and its test vector is the gate. |
| GUI to appd HTTP (about 70 endpoints, SSE, uploads) | `HuginnClient` direct | GUI through the core proxy | Kotlin keeps `HuginnClient`; only its transport changes (§5). |
| Watch stream and the `X-Huginn-Notify` claim | `AppStore.watchLoop` plus `Presence` | core (claim inputs from the tray role and the GUI) | D14. |
| Notify rules, router, enrichment | `NotifyRules.kt` / `NotifyRouter.kt` | core | `NotifyRules` is pure; ported with its tests. |
| Toasts, libnotify, balloons | desktop `Notifiers` | tray role | WinRT toasts; AUMID unchanged. |
| Tray icon and menu | Compose `Tray` | tray role | Icons rendered from the brand raven at build time. |
| `huginn://` scheme | `SchemeRegistrar` plus `Activation` | tray role and core | `answer`/`undo` need the token, so the core performs them. The rule is kept: a link acts only with the nonce of the toast that issued it, and the fingerprint stays the daemon's staleness check. |
| Single instance | loopback socket plus `instance.lock` | core IPC | The GUI asks the core whether a window is open; the core focuses it. |
| Autostart | desktop writes a Startup `.lnk` / `autostart/*.desktop` | core installer | |
| Self-update | `DesktopUpdater` and `huginn update` / `CliSync` | core updater | One version, one trust root. `CliSync` deleted. |
| Setup and onboarding | `SetupController` plus probes (GUI); `install.sh`/`.ps1` | bootstrap plus `huginn setup`; the GUI renders the same steps via `/core/setup/*` | The probes become core RPCs. |
| Pane lease, landing, layout, drafts, history, UI poll | GUI | GUI | Stays. |

## 5. IPC

### 5.1 Transport and who may connect

- **Linux:** `$XDG_RUNTIME_DIR/huginn/core.sock` (directory 0700, socket 0600). systemd sets
  `XDG_RUNTIME_DIR` for user services, so this holds for a lingering service started before any
  login as well.
- **Windows:** **AF_UNIX as well** (Windows 10 1803 and later; JDK 16+ and Go both support it). The
  socket file is `%LOCALAPPDATA%\huginn\run\core.sock`, in a directory whose ACL admits only the
  user. One transport on both platforms means one Kotlin adapter. A named pipe
  (`\\.\pipe\huginn-core-<user SID>`, DACL of that SID plus SYSTEM, `PIPE_REJECT_REMOTE_CLIENTS`)
  was the fallback if AF_UNIX failed across sessions (`run` in session 0, tray and GUI in session 1
  or later). It did not fail (Q1), so no pipe was built.
- **No TCP listener, ever.** The desktop's old single-instance loopback socket needed a shared
  secret because loopback has no identity; a socket ACL'd to the user does not.
- **Peer check on every accept**, before the HTTP layer reads a byte: on Linux the `SO_PEERCRED` uid
  must equal ours; on Windows `SIO_AF_UNIX_GETPEERPID`, then `OpenProcessToken`, and the user SID
  must equal ours. This is belt and braces over the file modes and ACL.

### 5.2 Protocol

HTTP/1.1 over the socket (Go `net/http` serves it natively). The JVM side is `CoreSocket`: a
`java.net.Socket` over a **non-blocking** `SocketChannel(UNIX)`, handed to OkHttp through
`socketFactory` and a fixed `dns`, and to Ktor through `engine { preconfigured = ... }`. It exists
to avoid three traps, each of which hangs or kills the app rather than failing one call:

1. `Channels.newInputStream/newOutputStream` share the channel's `blockingLock()` on JDK 17, so a
   blocked read stops every write. The adapter reads and writes the channel directly.
2. A blocking channel ignores `SO_TIMEOUT`, and OkHttp's pool health check reads with a 1 ms
   timeout. The adapter is non-blocking and waits on a `Selector` for at most `soTimeout`.
3. A close from another thread (Okio's timeout watchdog, `Call.cancel()`) surfaces as the unchecked
   `ClosedSelectorException`. The adapter translates it to `SocketException`.

The protocol rules:

- Every request carries `X-Huginn-Core-Proto: <n>`. A mismatch is a loud `426` with both versions
  and the fix ("run `huginn update`"). Lockstep releases (D10) make this rare; the check makes it
  safe.
- **`/appd/*` is the proxy.** `/appd/v1/chats` goes to appd's `/v1/chats` on the active route,
  with the bearer token added, `X-Huginn-Notify` set by the core when the caller sent none (D14),
  and TLS pin-by-proof enforced. Streams (SSE, uploads) pass through unbuffered. The proxy forwards
  only to the core's own proven routes, so it is not an open relay, and it strips any
  `Authorization` header the client sends.
- **`/core/*` holds the core's own verbs.** As designed: `status`, `device` (get or set scope,
  on/off, act-while-locked), `local` (plan, on, off, status, persist), `update` (check, apply,
  progress stream), `routes`, `setup/*` (the onboarding probes), `presence` (the GUI pushes
  `{focused, closeToTray}`), `gui` (the tray's `open` with a `huginn://` target, `quit`,
  `closeToTray`) and `events` (an SSE stream of core state for the GUI's settings pages and the
  tray). As built in 2.5.x: `status`, `device`, `events`, `pair`, `unpair`, `presence`, `gui`,
  `tray`, `restart`, `routes` and `local/seed`. Updating, setup and local AI stay with the CLI.

### 5.3 What the Kotlin side changes

`HuginnClient` stays. Its transport gains a third implementation next to Ktor and OkHttp: a core
transport that talks to the socket and prefixes `/appd`. The route book, pin book, challenge and TLS
trust manager stop being used on the desktop. They stay in `:core` for Android, which talks to appd
directly. Settings pages that read `settings.json` for device, route or local state read `/core/*`
instead.

## 6. Lifecycle per platform

### Linux (Debian, Arch, containers)

- `huginn core install` writes `~/.config/systemd/user/huginn-core.service`
  (`ExecStart=<path> core run`, `Restart=always`), runs `systemctl --user enable --now`, and
  `loginctl enable-linger $USER`. Linger for one's own user is allowed under the default polkit
  rules. If it is refused, the core says so and runs only while the user is signed in.
- The tray role starts from `~/.config/autostart/huginn-core-tray.desktop` (`huginn core tray`). On
  a Wayland compositor such as Hyprland, SNI is shown by the bar's tray module (for example
  waybar's). GNOME needs the AppIndicator extension; the tray role says so once, in a notification,
  and carries on.
- A headless machine gets the service only. No tray role is installed without a graphical session.
- System scope (`--system`, for a machine whose device should run as a dedicated user) is kept as an
  option, not the default.

### Windows 10/11

- `huginn core install` registers the "Huginn Core" task: trigger AtStartup, run as the account,
  `ExecutionTimeLimit 0`, restart on failure, `IgnoreNew`. The logon type is **S4U** by default
  (no stored password; see Q2), with `--logon password` as the alternative. Registering either
  needs one UAC prompt, because "log on as a batch job" is an administrator right by default on
  client editions.
- The tray role starts from a Startup-folder shortcut "Huginn" (`huginn.exe core tray`), stamped
  with the AUMID `com.silencelen.huginn.desktop-kt`. The AUMID is unchanged from the desktop app, so
  toast identity and notification settings carry over. The Start Menu shortcut "Huginn Desktop"
  launches the GUI.
- `huginn://` is registered under HKCU (the desktop app registers it as before; the core's tray
  opens `huginn://` targets in the app through `POST /core/gui`).
- Replacing a running `.exe`: rename the running binary aside (`huginn.exe` to `huginn.exe.old`,
  which Windows allows while it runs), move the new one in, restart the task and the tray role, and
  delete `.old` on the next start.

### Termux

The `huginn` front only (attach, verbs, update). No core service, no tray, no device. `huginn core`
explains that the phone's background presence is the Android app's job.

## 7. Install, layout and packages

### 7.1 Per-user layout

| | Linux | Windows |
|---|---|---|
| binary | `~/.local/share/huginn/versions/<v>/huginn`, `current` pointing at `<v>`, `~/.local/bin/huginn` pointing at `current/huginn` | `%LOCALAPPDATA%\huginn\bin\huginn.exe` (plus `.old` during an update), on the user PATH |
| GUI | `~/.local/share/huginn/versions/<v>/desktop/` (the portable app image) | `%LOCALAPPDATA%\Programs\huginn-desktop-kt\` |
| config | `~/.config/huginn/` (`core.json`, routes) | `%APPDATA%\huginn\` |
| secrets | `~/.config/huginn/secrets` (0600), or Secret Service on request | `%APPDATA%\huginn\secrets` (user-only ACL), or Credential Manager on request |
| state and logs | `~/.local/state/huginn/` | `%LOCALAPPDATA%\huginn\state\` |
| local AI tier | `~/.config/huginn-local` (system or user units) | `%ProgramData%\huginn-local` (WinSW, LocalSystem) |

On Linux the core and the GUI share the `versions/<v>` directory, so the lockstep update is one
symlink swap.

### 7.2 Bootstrap flow

1. Fetch the release manifest from the project's GitHub releases, pick `<os>-<arch>`, verify the
   sha256, install the binary.
2. `huginn setup`: the host, an SSH key and the `Host huginn` alias, then pairing with appd (Q6),
   with the token going into the core's secrets store.
3. Install the desktop app, for the same version, if asked.
4. Install the background service (on Windows, the one UAC prompt).
5. Shell integration: PATH, completion, and the `rclaude`/`rcc` aliases. The profile lines must
   match what `huginn uninstall` removes, byte for byte, and a test keeps them in step.

### 7.3 GUI-first installers (D9)

**The installers carry the core binary** (about 10 MB next to a JVM app image of about 100 MB), so a
GUI install works offline and can never resolve to a different core version. Each one installs or
upgrades the per-user core first, then the GUI, then runs the GUI's setup.

- **NSIS `.exe`:** a mandatory core section; the uninstaller stops the tray role and the core task,
  and asks the core to unenrol instead of reading the token itself.
- **Portable `tar.gz`:** already the per-user Linux path; it gains `huginn` next to the launcher.

### 7.4 Distro packages (`.deb`, `.pkg.tar.zst`)

A package manager installs as root into `/usr` or `/opt`; it cannot write into a user's home, and
D7 says per-user. The proposed resolution (Q4):

- A `huginn` package installs `/usr/bin/huginn`, a system copy updated only by the package manager
  and never self-updated. The `huginn-desktop` package installs to `/opt/huginn-desktop-kt/` and
  depends on `huginn`, so the package dependency enforces "no GUI without core".
- The per-user parts (service unit, linger, tray autostart, secrets) are created by the user's
  first `huginn setup` or first GUI launch, exactly as with a per-user install.
- A machine has **one** core: if both `/usr/bin/huginn` and a per-user `huginn` exist, the per-user
  copy refuses to install its service and names the copy that owns the machine.
- Packages under `/opt` are never installed from the app.

As built, the desktop packages carry the core binary the same way the other installers do (§7.3):
the first launch copies the bundled core into the per-user layout and offers to set it up. The
"one core per machine" refusal is built; a separate `huginn` distro package is not.

## 8. Updates and trust

- **Release assets:** before 2.0 the `cli-v*` releases had no assets, and `huginn update` fetched
  script source. Since 2.0.0 a release carries the core binaries for `linux-x64`, `linux-arm64`,
  `windows-x64` and `android-arm64`, plus `core-manifest.json` (version and a sha256 per artifact).
  The desktop's releases carry its installers and the same kind of manifest.
- **Trust root:** the project's GitHub releases, with a sha256 per artifact; never the server the
  client is paired with.
- **Known gap:** `huginn core update` trusts the sha256 published in the same release as the
  binary, so it catches corruption but not a substituted release (a stolen publish credential). The
  fix is a signature over the manifest with a key the clients pin, checked by both clients. An
  Authenticode check on Windows is not used because the Windows builds are not yet signed with a
  publicly trusted certificate.
- **One updater (designed):** `huginn update` and the core's background check apply core and GUI
  together; the GUI shows "update ready" and never downloads anything itself. Distro installs only
  print the `apt` or `pacman` line. As built, `huginn update` updates the client, core, desktop app
  and local AI in one run, and the desktop app keeps its own updater as well.

## 9. Port risks

These were the places where the shell and Kotlin code carried long comments explaining why; each
needed a named test in Go.

1. **Attach loop:** `ssh -tt` with keepalives; reconnect judged by session duration (three exits
   under 5 s means give up), backoff from 2 s to 15 s with 25 % jitter; the one atomic remote line
   (has-session, count clients, mirror or solo); never resurrect a killed session; `=name`
   exact-match targets; the name rule `^[a-z0-9_][a-z0-9_-]{0,49}$` after lowercasing; CSI 22/23
   title push and pop only on a TTY.
2. **appd-over-ssh error classes:** reached, HTTP error with the daemon's own text, or not reached,
   with ssh's exit 255 distinguished; never `curl -f`; tmux fallback for `kill` only when the daemon
   was not reached.
3. **Remote quoting:** everything after the host is shell input on the host. Use stdin or base64
   everywhere (a deny-list for `-p` once shipped word-split and denied nothing).
4. **TLS pin-by-proof:** Go `tls.Config{InsecureSkipVerify, VerifyConnection}` checks the leaf
   before any request byte is written. Keep: the fingerprint from the client's own handshake, the HMAC
   `huginn-tls-v1:<nonce>:<fp>`, constant-time compare, pins keyed by a pin tag, at most four pins,
   re-prove once, a handshake deadline, a separate probe connection, refusal of daemons too old to
   prove, and http-to-https on load and save. Gate: the protocol document's test vector plus the
   end-to-end tests in `scripts/test-device-tls.js`, pointed at Go.
5. **Runner output:** 128 KiB line and 192 KiB batch caps with in-place shrinking; the terminal
   frame always alone; permanent errors (400/403/404/413) versus 401 (stop delivery, keep the child)
   versus transient; cancel arrives on beats and acks; a 180 s startup watchdog only; the working
   directory checked before spawn; error text from the start of stderr.
6. **Policy:** `scripts/gen-device-policy.js` gains a sixth output, a Go table. The Go binary keeps
   `print-argv`, so `server/appd/test/device-policy-cases.test.js` runs the 144-case matrix against
   it unchanged. An unknown scope becomes `generate` (refused at enrolment); `generate` is exclusive
   and ignores the lock; `--resume` only for a UUID; `actWhileLocked` only a literal `true`, always
   sent as a boolean; the scope-echo check.
7. **Spawning claude on Windows:** `.cmd`/`.bat` shims resolved to the sibling `cli.js` and run
   through `node`, never a shell (Go's `exec` has the same `.cmd` trap). The native `claude.exe`
   needs none of it.
8. **Local AI elevation:** pkexec, then sudo, with an explicit environment; exit 126 (declined)
   stops, 127 (absent) tries the next; `runuser` plus `XDG_RUNTIME_DIR` for user-bus calls as root;
   files chowned back to the user; the Windows administrator check before any download; adapters (Ollama, LM
   Studio) never installed or stopped.
9. **Off and uninstall order:** unenrol on the server before deleting credentials; keep the id if
   the DELETE fails unless forced; remove units in whichever scope they exist; never touch an
   adopted engine; refuse to purge `/` or `$HOME`; remove only an exact `Host huginn` stanza and a
   key marked as Huginn's.
10. **Drift between the shell clients, to resolve rather than port:** the PowerShell `device on`
    ignored `HUGINN_DEVICE_DIR`, the PowerShell `local on` leaked `$env:HUGINN_LOCAL_DIR`, and the
    two had different local-directory defaults. Go picks one behaviour per item.
11. **The desktop probed CLI text:** `TerminalLaunch.kt` detected attach-by-name by looking for a
    shell function name, and `CliSync.kt` read version strings by regex. Both changed in phase 1.

## 10. Phases

Each phase ships as one lockstep release train (core and GUI at the same version).

**Phase 1: the core exists; act mode is always on (2.0.0)**
- Go `huginn core run|install|uninstall|status`, `huginn device on|off|status`, and the IPC server
  with `/core/*` (device, status, events), without the proxy.
- The Go runner (generated policy table, `print-argv`, lock probe, unenrol debt), TLS pin-by-proof
  and the token store.
- The core adopts the existing device id from `~/.config/huginn/device.json` or the desktop's
  `settings.json`, so no machine gets a second row (Q7).
- Desktop: `DeviceRunner.kt` deleted; the Settings "Available to huginn" panel drives
  `/core/device`. With no core installed, the GUI says so and offers to install its bundled core.
- The shell scripts stay as the interactive front; `huginn device` and `huginn core` dispatch to
  the binary.
- **Exit criteria:** a machine nobody signs in to keeps its device row through a reboot, on Linux
  and on Windows; the 144-case matrix green against Go; the TLS vector and end-to-end tests green.

**Phase 2: the core owns the machine (2.1.x and later)**
- The proxy, routes, the watch stream, notify rules and the claim (D14), the tray role,
  `huginn://`, one updater for core and GUI, local AI (`huginn local`, the shim as a sub-mode), and
  single instance through IPC.
- Desktop: the core transport; token, routes and pins wiped from `settings.json`; the desktop's
  tray, notifiers, autostart, scheme registrar, updater, `CliSync` and `LocalServe` deleted.
- Installers carry the core (§7).
- **Exit criteria:** with the GUI closed, notifications still arrive at the desk, and through the
  alert relay when away; the GUI relaunches from the tray; one update moves both.

**Phase 3: one binary**
- Go `huginn` replaces `huginn.sh` and `huginn.ps1` (attach, verbs, completion, aliases,
  uninstall); the bootstrap scripts become "download the binary, run `huginn setup`".
- The Node satellites are deleted; the source-grepping parts of `scripts/test-client.sh` become Go
  tests, and its black-box sections point at the binary.
- **Exit criteria:** a clean Windows machine and a clean Arch machine go from one bootstrap line to
  attach, GUI and background service.

**Existing installs** move in phase 1 or 2 with their device ids adopted. `huginn core install`
stops and removes the Node runner services (`huginn-local-runner`, `huginn-device.service`) once
the Go runner has enrolled under the same id.

## 11. Questions and answers

| # | Question | Answer |
|---|---|---|
| Q1 | Can the JVM GUI speak HTTP over a local socket, including SSE? | **Yes, on Linux and Windows.** Linux: 11 of 11 probe checks on a Debian server, 10 of 10 on an Arch workstation in the app's own jlink runtime. Windows 11: 10 of 10 in-session and **cross-session**, with the server in an S4U task (session 0) and the client in the signed-in desktop (session 1); the peer check resolved the user by SID. AF_UNIX on both platforms; no named-pipe fallback. |
| Q2 | Which logon type for the Windows boot task? | **S4U by default.** Measured from an S4U task on Windows 11: session 0, Credential Manager and DPAPI available, HTTPS works, the profile and `~\.claude` present; no stored password needed. A task does not start on battery unless `DisallowStartIfOnBatteries=false` (it sits "Queued"); the core's task XML sets it. Battery saver can still hold a start back, so since 2.5.3 `huginn core install` starts the core directly when it does not answer after registration. |
| Q3 | Do the remote verbs keep using ssh, or go through appd? | Keep ssh: it works without a token and is the terminal path's whole point. Revisit per verb. |
| Q4 | Distro packages: how do they fit a per-user core? | As in §7.4, with "one core per machine". |
| Q5 | Tag and manifest namespace for a unified release? | Proposed: one `huginn-v*` tag. As built, releases keep the `cli-v*` and `desktop-v*` tags at the same version. |
| Q6 | Pairing: the token was copied by hand or over ssh (`cat /etc/huginn-appd/token`). | Keep the ssh path (`huginn setup` already has ssh). Since 2.5.7, when SSH to the server does not answer, setup asks for the token (typed or pasted, never shown) and pairs over the daemon's own port. A pairing code or QR is a separate project. |
| Q7 | Adopting device ids: what if a machine has both a Node runner id and a desktop id? | Prefer the one appd saw most recently. Dropped ids are recorded, never deleted automatically at import, because a dropped id may belong to a live old runner. |
| Q8 | Manifest signing key: now or later? | Recommended for the first binary release, because a binary has no equivalent of the `bash -n` / `node --check` parse check the script updates had. Not built; see §8. |
| Q9 | Go tray library | `fyne.io/systray` v1.12.2 with `CGO_ENABLED=0`. Linux: works with SNI hosts. Windows: works, but Windows 11 puts every new icon in the `^` overflow. Decided: leave that to Windows. No supported API promotes an icon; writing `IsPromoted` under `HKCU\Control Panel\NotifyIconSettings` is undocumented and is the user's choice to make. Notifications are not affected. |
| Q10 | Idle and lock on Linux Wayland, for the claim | Measured on a Hyprland session: logind reports `IdleHint=no` and `IdleSinceHint=0` on the active Wayland session, because nothing feeds logind idle there. The tray role uses `ext-idle-notify-v1` instead; `LockedHint` is still read from logind for the lock fence. Unknown means "not at the machine", so the alert relay wins. |

## 12. Decisions made while building phase 1

- **D11 reversed for the boot service: the token lives in the 0600 file store by default**, with
  the keyring as an opt-in (`HUGINN_SECRETS=keyring`). A Linux login keyring is locked until a
  login, and Credential Manager is likely unavailable to an S4U task: exactly the moments an
  always-on core needs the token.
- **"No graphical session" is split by seat0 `CanGraphical`:** a headless machine counts as
  unlocked (act-scope servers keep working, as with the Node runner); a desktop at its login screen
  counts as locked, so act mode needs "keep act mode while locked".
- **Pin tags:** the Node runner (`HMAC(token,"huginn-tls-v1:pinset")[:16]`) and Kotlin
  (`sha256("huginn-tls-pins:"+token)[:16]`) disagreed; the core follows Node. Migrated pins cost one
  re-proof.
- **`huginn core self-install` is the one install path:** per-user layout, an atomic `current`
  swap, pruning to two versions, and the Windows PATH set through the registry. `core install`
  self-installs first and refuses beside a package-owned `/usr/bin/huginn`.
- **Reproducible builds:** `core/build.sh <version>` builds with CGO off for linux-x64, linux-arm64,
  windows-x64 and android-arm64, and writes `core-manifest.json` and `SHA256SUMS`.
- **Proof that linger is enough:** on a real systemd host, with a throwaway user and a private appd,
  `core install` wrote the unit and enabled linger; the user manager was stopped and restarted with
  no login session, and the core came back with the same device id and ran an act chat.

## 13. As built

### Phase 2: tray and notifications (2.1.x)

- `core/internal/notify` ports the desktop's notify rules and watch cycle with all 33 test cases.
- `core/internal/tray` is the tray role (`huginn core tray`), with icons generated from the brand
  raven, D-Bus notifications on Linux and WinRT toasts on Windows, and `huginn core notify-test`.
- The core follows appd's watch stream and feeds the tray summary and notices. It **claims**
  notifications only while a tray is attached that can deliver them (`deliver=0|1` on
  `/core/events`, re-checked every minute) and the session is unlocked. Presence from the GUI
  suppresses the toast for whatever the GUI is showing.
- Linux notification bodies are escaped before they reach the notification server. A body carries
  model output, and servers that read markup would otherwise turn an `<a href>` into a link or an
  `<img src>` into a local file read.

### The upgrade path (2.1.0 to 2.1.3)

Every route by which a newer core reaches a machine ends in `huginn core self-install`, run by the
new binary: `huginn core update`, `huginn update` (lockstep, D10), `huginn core install` and the
bootstrap on a machine with an older core, and the desktop app at launch when the core it carries
is newer. `self-install` then:

- **Restarts a running core of another version** (`POST /core/restart`). The core waits for a
  running device job to finish, then `core run` exits 0 and systemd's `Restart=always` brings it
  back. On Windows, `self-install` runs `schtasks /Run`, waits for the old process to exit, and
  repeats `/Run` until the new core answers. The wait is needed because the old core closes its
  socket before its process exits, and Task Scheduler ignores `/Run` for an instance that is still
  running. A 2.0.0 core has no `/core/restart`, so it is restarted through its service manager, or
  by its pid when the service manager cannot be reached (no user bus in an ssh shell, or a task the
  user may not end), unless it is running a job.
- **Adds the tray's login autostart once** to a machine whose core predates the tray. A marker in
  the state directory (`tray-autostart`) records that, so an autostart the person removed is not put
  back.
- **Starts the tray now**, when the process is in a desktop session (on Windows, not session 0),
  not elevated, and no tray is attached. Windows `core install` runs elevated, so the PowerShell
  client and the desktop app run an unelevated `self-install` afterwards to start the tray.
- **Hands a running tray over:** a tray whose core restarted onto a newer version checks that the
  stable binary reports the core's version, starts it as the tray, and quits. A lock file in the
  runtime directory allows one tray per user.

The binary on PATH shadowed the shell client wherever the client is not a loaded shell function
(fish, `cmd.exe`, a PowerShell without its profile, scripts). Since 2.1.3 the binary hands every verb
it does not own to the installed client (`~/.huginn/huginn.sh` or `huginn.ps1`), with
`HUGINN_VIA_CORE` guarding against a loop.

### The core is the product (2.2.0)

- `internal/clientkit` embeds `client/huginn.sh` and `client/huginn.ps1` plus the local AI helpers,
  and `core self-install` lays them down with the shell startup lines. `core/build.sh` refuses when
  the embedded copy and `client/` drift or the versions disagree. A machine with a core always has
  the CLI, at the core's version.
- `huginn uninstall` belongs to the core and works top down: desktop app, local AI tier, device row
  (retired first, while the token still exists), core, CLI, and with `--all` the SSH stanza and key.
  The shell clients delegate whenever a core exists.
- The desktop app shows only a "Set up / Start huginn core" screen while the core does not answer,
  with a 20 s grace period for update restarts.
- The desktop's own uninstallers remove only the desktop app; `scripts/test-client.sh` asserts they
  never touch `~/.huginn`, the device row, the local tier or user units. `CliSync` is retired.

### Later

- **2.3.0:** the console ([console.md](console.md)) and the first `/appd/*` proxy.
- **2.5.0 and 2.5.1:** the transparent proxy and the route book, and the desktop app moving onto
  them ([desktop-via-core.md](desktop-via-core.md)).
- **2.5.7:** one tray icon and one menu (D6).

### Not built

- **One binary (phase 3).** The shell clients remain the implementation of attach and most verbs;
  the core embeds them and forwards to them. The console has its own Go attach loop.
- **Local AI inside the core.** The Node manager (`client/huginn-local`) and shim remain. The core
  carries a copy of the manager (since 2.5.7, so uninstall can still run it) and hands the tier its
  token (`POST /core/local/seed`, 2.5.0). The local tier's generate row still runs on the Node
  runner (`client/huginn-device`), which also remains for machines without a core.
- **One updater.** `huginn update` updates every component, but the desktop app keeps its own
  updater.
- **Setup through `/core/setup/*`.** The desktop's setup pairs the core through `/core/pair`.
- **Removing the desktop's tray, notifiers, scheme registrar and single-instance code.** They stay
  as the fallback where the core has no tray.
