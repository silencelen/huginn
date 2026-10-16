# Core architecture — `huginn` as the core, the desktop as a GUI on top

Status: **P1 BUILT on branch `core` (2026-10-14), not released.** See §12 for what is done and what is left.

This document is the plan and the decision record for re-shaping the huginn *client* side: the CLI
becomes the core of every machine's install, and the Compose desktop becomes a graphical package on top
of it. The host side (`huginn-appd`, `cc`, tmux) and the Android app are out of scope except where the
wire contract is touched.

## 1. Why

Three things are wrong with the client today, and they are the same problem seen three ways.

1. **Background features live in the GUI.** The desktop's act-mode runner (`device/DeviceRunner.kt`)
   exists only while the JVM runs, and the JVM runs only while someone is logged in. That is exactly how
   RIG kept disappearing as a device (audit 2026-10-03): nobody signed in, so no runner.
2. **Two implementations of one thing.** The headless runner (`client/huginn-device`, Node) and the
   desktop runner (Kotlin) speak the same protocol and enforce the same generated policy, in two languages,
   with two sets of bugs (the desktop's captured-scope bug E1 never existed in Node; the Node runner never
   probes the lock at all).
3. **The GUI can exist without the CLI.** `CliSync.kt` updates CLI files only if they are already there
   ("presence is the consent"), so a desktop-only machine has no `huginn` command, no headless runner and
   no `huginn local`.

The goal, in the owner's words: the CLI is the **core**; the desktop is a package on top of the core's
features; always-on features run in the core so they are never bound to the GUI being open; the GUI can
never be installed without the core; installing the CLI offers the GUI.

## 2. Decisions

| # | Decision | Notes |
|---|---|---|
| D1 | **The core is one Go binary, and it IS `huginn`.** | Replaces `huginn.sh`, `huginn.ps1`, `huginn-device`, `huginn-local`, `huginn-llm-shim`. One static binary per OS/arch, no runtime dependency. |
| D2 | **Background mode is `huginn core`**; unit `huginn-core.service`, Windows task "Huginn Core". | The Kotlin `:core` module keeps its name; it is internal. |
| D3 | **Per-user, starts at boot without a login.** Linux: `systemd --user` + `loginctl enable-linger`. Windows: boot-time scheduled task as the account. | Act mode needs the user's `~/.claude` login and must act as the user. |
| D4 | **The local-AI engine stays a machine-scope service** that the core installs and supervises. | llama-swap as today (systemd system unit / WinSW LocalSystem). Root only here. |
| D5 | **The core owns the tray and the notifications.** | SNI on Linux, Win32 on Windows. Headless boxes simply have no tray. |
| D6 | **Closing the GUI window quits the GUI process.** | The tray relaunches it. No JVM idling in the background; "Close to tray" goes away. |
| D7 | **Per-user install, no root**, self-updating. | `~/.local/share/huginn`, `%LOCALAPPDATA%\huginn`. See §7 for how distro packages fit. |
| D8 | **One bootstrap script per OS** is the primary entry point. | `curl -fsSL …/install.sh \| sh`, `irm …/install.ps1 \| iex`. It asks *Install the desktop GUI too? [y/N]* and *Keep huginn running in the background? [y/N]*. |
| D9 | **GUI-first installers install the core first.** | The .exe/.deb/.pkg/tarball cannot leave a machine with a GUI and no core. |
| D10 | **Lockstep versions, starting at huginn 2.0.0** for core + GUI. | CLI was 1.9, desktop 1.13. appd and the Android app keep their own numbers. IPC carries a protocol version anyway. |
| D11 | **Credentials in the OS keyring, 0600 file fallback** for headless boxes. | The GUI never holds the appd token. |
| D12 | **The GUI reaches appd through the core**, which is a local authenticated proxy. | Same appd HTTP API, spoken to the core's socket/pipe; the core adds token, TLS pin-by-proof and route choice. |
| D13 | **One device row per (machine-id, OS user), minted by the core.** | The GUI never enrols. The `-llm` row is a child of the machine. |
| D14 | **The core claims notifications while the user is at the machine** (session unlocked, input idle < 10 min). | Away = appd's Telegram fallback. An open GUI also suppresses toasts for what it is showing. |
| D15 | **Platforms: Linux (Debian + Arch), Windows 10/11, Termux.** | Termux = client only, no background. macOS out of scope. |
| D16 | **Phased migration, runner first** (§10). | Each phase is a shippable train. |

## 3. Shape

```
 bootstrap (curl|sh / irm|iex)  ── or ──  GUI-first installer (.exe/.deb/.pkg/.tar.gz)
        └─ installs `huginn` ─ pairs with appd ─ [GUI? y/N] ─ [background? y/N]

 ┌──────────────────────────── one binary: huginn ────────────────────────────┐
 │  huginn <verb>          interactive front: attach (ssh -tt + reconnect),   │
 │                          list/status/kill/end/llm/projects/…, update,      │
 │                          device/local/desktop/core management              │
 │                                                                            │
 │  huginn core run        THE CORE (boot service, no UI)                     │
 │    ├─ appd link         token (keyring/file) · routes · TLS pin-by-proof   │
 │    ├─ proxy             GUI's appd API, SSE and uploads pass through       │
 │    ├─ device runner     act mode; lock probe; policy table (generated)     │
 │    ├─ local AI          installs/supervises llama-swap; -llm row; shim     │
 │    ├─ watch stream      /v1/watch → notify rules → tray role               │
 │    ├─ updater           itself + the GUI, one version                      │
 │    └─ IPC server        unix socket / named pipe                           │
 │                                                                            │
 │  huginn core tray       TRAY ROLE (starts at graphical login)              │
 │    tray icon + menu · toasts (AUMID) · presence/idle · huginn:// handler   │
 │    → talks to `core run` over IPC; hosts the core itself if none is running│
 └────────────────────────────────────────────────────────────────────────────┘
                     ▲ IPC (same appd API + /core/* verbs)
 Huginn Desktop (Compose, JVM) — launched by the tray / start menu / huginn://;
   quits on window close; no token, no runner, no updater, no autostart.
                     │
                     ▼ (through the core) HTTPS, pin-by-proof, outbound only
                 huginn-appd (host)
```

### 3.1 Why two roles in one binary (`run` and `tray`)

A Windows task that starts at boot "whether the user is logged on or not" runs in **session 0**: no
desktop, so no tray icon and no toasts (toasts also need the app's AUMID in the interactive session).
Input-idle time (`GetLastInputInfo`) is also only readable from the interactive session. So the always-on
half and the on-screen half cannot be one Windows process.

Linux could do both in one process (a lingering user service shares the user bus with the graphical
session, so SNI and `org.freedesktop.Notifications` are reachable once someone logs in), but we use the
same split there for three reasons: one design to test, a crash in tray/UI code cannot take the runner
down, and the tray role naturally owns the graphical-session facts (idle, lock, `DISPLAY`/`WAYLAND_DISPLAY`).

**Fallback:** if the user answered *no* to running in the background, there is no `core run` service; the
tray role (started at login) hosts the core in-process, so features work while logged in, exactly like
today's desktop. Turning on a feature that needs boot-time presence (act-mode availability, local AI)
offers to install the service (D8 asks once at install; this asks again at the moment it matters).

## 4. Who owns what (today → target)

| Component | Today | Target | Notes |
|---|---|---|---|
| Attach, reconnect, mirror/solo, tab title | `huginn.sh` / `huginn.ps1` | `huginn` front | Exact semantics in §9 (port risks). |
| Remote verbs (list/status/kill/end/llm/projects/…) | sh/ps1 over ssh | `huginn` front | Keep ssh as transport for the verbs that are ssh today; see Q3 in §11. |
| Device runner | `huginn-device` (Node) **and** `DeviceRunner.kt` | core | One implementation. Kotlin runner deleted in P1. |
| Lock probe | `LockProbe.kt` only (Node always says "unlocked") | core | Windows `LogonUI`/`quser` works from session 0; Linux: logind `LockedHint` of the user's graphical session. |
| Unenrol debt (IDLE/RETIRE/SETTLE) | `Unenrol.kt` | core | |
| Local AI manager + shim | `huginn-local`, `huginn-llm-shim` (Node); `LocalServe.kt` door | core | Consent stays a human click: `plan` → shown → `on`. Shim becomes `huginn llm-shim` (a sub-mode of the binary). |
| appd token, routes, pins, route health | desktop `settings.json` (plaintext, 0600); `~/.config/huginn/appd-token` | core store (keyring / 0600) | Migrated once, then wiped from `settings.json`. |
| Route resolution, challenge proof, TLS pins, route witness, `PUT /v1/apps/routes` | `:core` Kotlin + `AppStore` | core | Security-critical rewrite; docs/TLS.md is the spec, its test vector the gate. |
| GUI → appd HTTP (~70 endpoints, SSE, uploads) | `HuginnClient` direct | GUI → core proxy | Kotlin keeps `HuginnClient`; only its transport changes (§5). |
| Watch stream + `X-Huginn-Notify` claim | `AppStore.watchLoop` + `Presence` | core (claim inputs from the tray role + GUI) | D14. |
| Notify rules + router + enrichment | `NotifyRules.kt` / `NotifyRouter.kt` (desktop) | core | `NotifyRules` is pure; port with its tests. |
| Toasts / libnotify / balloon | `Notifiers` (desktop) | tray role | WinRT toast via the existing PowerShell path or native COM; AUMID unchanged. |
| Tray icon + menu | Compose `Tray` | tray role | Icons from `TrayIcons`/`RavenMark` rendered to PNG/ICO at build time. |
| `huginn://` scheme | `SchemeRegistrar` + `Activation` (desktop) | tray role / core | `answer`/`undo` need the token → core. Mandatory fingerprint rule kept. |
| Single instance | loopback socket + `instance.lock` | core IPC | GUI asks the core "is a window open?"; the core focuses it. |
| Autostart | desktop writes Startup `.lnk` / `autostart/*.desktop` | core installer | GUI never writes autostart. |
| Self-update | `DesktopUpdater` (+ portable swap, 1.13) and `huginn update` / `CliSync` | core updater | One version, one trust root. `CliSync` deleted. |
| Setup / onboarding | `SetupController` + probes (GUI); `install.sh`/`.ps1` | bootstrap + `huginn setup`; GUI renders the same steps via `/core/setup/*` | The probes become core RPCs; first-run pre-answers come from the installer. |
| Pane lease, landing, layout, drafts, history, UI poll | GUI | GUI | Stays. Pane lease release on quit matters more with quit-on-close. |

## 5. IPC

### 5.1 Transport and who may connect

- **Linux:** `$XDG_RUNTIME_DIR/huginn/core.sock` (dir 0700, socket 0600). Fallback when there is no
  runtime dir (lingering service started before any login): systemd sets `XDG_RUNTIME_DIR` for user
  services, so this holds for both roles.
- **Windows:** **AF_UNIX as well** (Windows 10 1803+; JDK 16+ and Go both support it), socket file in
  `%LOCALAPPDATA%\huginn\run\core.sock` whose directory ACL is the user only. One transport on both
  platforms, one Kotlin adapter. The named pipe `\\.\pipe\huginn-core-<user SID>` (DACL = that SID +
  SYSTEM, `PIPE_REJECT_REMOTE_CLIENTS`) is the fallback **only if** the Windows probe (§11 Q1) shows
  AF_UNIX failing across sessions (`run` in session 0, tray/GUI in session 1+).
- **No TCP listener, ever.** Today's `SingleInstance` loopback socket needs a shared secret because
  loopback has no identity; a socket/pipe ACL'd to the user does not.
- **Peer check on every accept**, before the HTTP layer reads a byte: Linux `SO_PEERCRED` uid must equal
  ours; Windows `SIO_AF_UNIX_GETPEERPID` → `OpenProcessToken` → user SID must equal ours. Belt and braces
  over the file modes/ACL (`proto/ipc/server/peer_*.go`).

### 5.2 Protocol

HTTP/1.1 over the socket (Go `net/http` serves it natively). The JVM side is `CoreSocket`
(`proto/ipc/client/UnixSocketProbe.java`): a `java.net.Socket` over a **non-blocking**
`SocketChannel(UNIX)`, handed to OkHttp through `socketFactory` + a fixed `dns`, and to Ktor through
`engine { preconfigured = … }`. Three traps it exists to avoid, each of which hangs or kills the app
rather than failing a call:
1. `Channels.newInputStream/newOutputStream` share the channel's `blockingLock()` on JDK 17, so a blocked
   read stops every write — the adapter reads/writes the channel directly.
2. A blocking channel ignores `SO_TIMEOUT`, and OkHttp's pool health check reads with a 1 ms timeout —
   the adapter is non-blocking and waits on a `Selector` for at most `soTimeout`.
3. A close from another thread (Okio's timeout watchdog, `Call.cancel()`) surfaces as the UNCHECKED
   `ClosedSelectorException` — the adapter translates it to `SocketException`.

- Every request carries `X-Huginn-Core-Proto: <n>`; a mismatch is a loud `426` with both versions and the
  fix ("run `huginn update`"). Lockstep releases (D10) make this rare; the check makes it safe.
- **`/appd/*` — the proxy.** `/appd/v1/chats` → appd `/v1/chats` on the active route, with the bearer
  token added, `X-Huginn-Notify` set by the core (D14), TLS pin-by-proof enforced. Streams (SSE, chunked
  uploads) are passed through unbuffered. The proxy forwards only to the core's own proven routes — it is
  not an open relay, and it strips any `Authorization` header the client sends.
- **`/core/*` — the core's own verbs.** `status`, `device` (get/set scope, on/off, act-while-locked),
  `local` (plan/on/off/status/persist, with a log stream for the elevated step), `update` (check/apply,
  progress stream), `routes` (list/pin/active/probe), `setup/*` (the onboarding probes), `presence` (the
  GUI pushes `{visible, focused, view}`), `gui` (`open`, `focus`, `navigate`), `events` (an SSE stream of
  core state for the GUI's settings pages and the tray).

### 5.3 What the Kotlin side changes

`HuginnClient` stays. Its transport gets a third implementation next to Ktor/OkHttp: `CoreTransport`,
which talks to the socket/pipe and prefixes `/appd`. The route book, pin book, challenge and TLS trust
manager stop being used on desktop (they stay in `:core` for Android, which talks to appd directly).
Settings pages that today read `settings.json` for device/route/local state read `/core/*` instead.

## 6. Lifecycle per platform

### Linux (Debian, Arch, LXCs)

- `huginn core install` writes `~/.config/systemd/user/huginn-core.service`
  (`ExecStart=<path> core run`, `Restart=always`), `systemctl --user enable --now`, and
  `loginctl enable-linger $USER` (allowed for one's own user under default polkit; if refused, the core
  says so and runs only while logged in — the three-valued linger answer `huginn-local` already handles).
- Tray role: `~/.config/autostart/huginn-core-tray.desktop` → `huginn core tray`. Wayland/Omarchy:
  SNI is shown by waybar's tray module; GNOME needs the AppIndicator extension (the tray role says so once,
  in a notification, and carries on headless).
- Headless boxes (CT 203, buildbox): the service only; no tray role is installed when there is no graphical
  session.
- System scope (`--system`, for a box whose device should run as a dedicated user): kept as an option, as
  `huginn-device unit --system` does today, but not the default.

### Windows 10/11

- `huginn core install` registers the "Huginn Core" task: trigger AtStartup, run as the account, settings
  as today's runner snippet (`ExecutionTimeLimit 0`, restart on failure, `IgnoreNew`).
  **Logon type is open (Q2):** stored password (works everywhere, breaks on password change) vs **S4U**
  (no password; no network credentials and probably no Credential Manager — the token would use the file
  fallback). Registering either is expected to need one UAC prompt, because "log on as a batch job" is
  an admin right by default on client SKUs. Verify both on PC01 before choosing.
- Tray role: Startup-folder shortcut "Huginn" → `huginn.exe core tray`, stamped with AUMID
  `com.silencelen.huginn.desktop-kt` (unchanged, so toast identity and notification settings carry over).
  The Start Menu shortcut "Huginn Desktop" launches the GUI.
- `huginn://` registered under HKCU to `huginn.exe core activate "%1"`.
- Replacing a running `.exe`: rename the running binary aside (`huginn.exe` → `huginn.exe.old`, allowed
  while it runs), move the new one in, restart the task and the tray role, delete `.old` next start.

### Termux

`huginn` front only (attach + verbs + update). No `core`, no tray, no device. `huginn core …` explains
that the phone's background presence is the Android app's job.

## 7. Install, layout and packages

### 7.1 Per-user layout

| | Linux | Windows |
|---|---|---|
| binary | `~/.local/share/huginn/versions/<v>/huginn`, `current` → `<v>`, `~/.local/bin/huginn` → `current/huginn` | `%LOCALAPPDATA%\huginn\bin\huginn.exe` (+ `.old` during an update), on the user PATH |
| GUI | `~/.local/share/huginn/versions/<v>/desktop/` (the portable app-image) | `%LOCALAPPDATA%\Programs\huginn-desktop-kt\` (today's NSIS path, kept) |
| config | `~/.config/huginn/` (`core.json`, `device.json`, routes) | `%APPDATA%\huginn\` |
| secrets | Secret Service, else `~/.config/huginn/secrets` (0600) | Credential Manager, else `%APPDATA%\huginn\secrets` (user-only ACL) |
| state/logs | `~/.local/state/huginn/` | `%LOCALAPPDATA%\huginn\state\` |
| local AI (machine) | `/var/lib/huginn-local` (system units) | `%ProgramData%\huginn-local` (WinSW, as today) |

Core and GUI share the `versions/<v>` directory on Linux so the lockstep update is one symlink swap
(the 1.13 portable-install mechanism, generalised).

### 7.2 Bootstrap flow

1. Fetch the release manifest for the pinned repo, pick `<os>-<arch>`, verify sha256, install the binary.
2. `huginn setup`: host → ssh key + `Host huginn` alias (today's `install.sh` steps 1–2), appd pairing
   (§11 Q6), token into the keyring.
3. *Install the desktop GUI too? [y/N]* → fetch + verify + unpack the GUI for the same version.
4. *Keep huginn running in the background? [y/N]* → `huginn core install` (Windows: the one UAC prompt).
5. Shell integration: PATH, completion (`huginn completion bash|zsh|powershell`), the `rclaude`/`rcc`
   aliases. Profile lines must match `huginn uninstall` byte for byte (today's trap, kept as a test).

### 7.3 GUI-first installers (D9)

Proposed: **the installers carry the core binary** (≈10 MB against a ≈100 MB JVM app-image), so a GUI
install works offline and can never resolve to a different core version. Each one installs (or upgrades)
the per-user core first, then the GUI, then runs the GUI's setup, which drives `/core/setup/*`.

- **NSIS .exe:** gains a mandatory "Huginn core" section; `EnsureNotRunning` also stops the tray role and
  the core task; the uninstaller asks the core to unenrol (`huginn device off`) instead of reading
  `settings.json` for the token itself.
- **Portable tar.gz:** already the per-user Linux path; it gains `huginn` next to the launcher.

### 7.4 Distro packages (.deb / .pkg.tar.zst) — the open conflict

A package manager installs as root into `/usr` or `/opt`; it cannot write into a user's home, and D7
says per-user. Proposed resolution (Q4):

- `huginn` package → `/usr/bin/huginn` (system copy, updated only by apt/pacman, never self-updated);
  `huginn-desktop` package → `/opt/huginn-desktop-kt/`, `Depends: huginn` / `depends=('huginn')`.
  The package dependency enforces "no GUI without core".
- The per-user parts (service unit, linger, tray autostart, secrets) are created by the user's first
  `huginn setup` / first GUI launch, exactly as with a per-user install.
- A machine has **one** core: if both `/usr/bin/huginn` and a per-user `huginn` exist, the per-user one
  refuses to install its service and says which copy owns the machine. (The owner rule from 2026-10-14
  stands: packages under `/opt` are never installed from the app.)

## 8. Updates and trust

- **Release assets (new):** `cli-v*` releases have had no assets; `huginn update` fetched script source.
  The unified release `huginn-v2.0.0` (tag namespace to confirm, Q5) carries
  `huginn-<v>-{linux-x64,linux-arm64,windows-x64,android-arm64}` + the GUI artifacts
  (`windows-x64` .exe, `linux-x64` .deb, `linux-x64-arch`, `linux-x64-portable`) + `manifest.json`
  `{version, proto, artifacts{key:{file,size,sha256}}}`.
- **Trust root unchanged:** the pinned `silencelen/huginn` GitHub releases, sha256 per artifact, never
  `$HUGINN_HOST`. The scp mirror (`huginn:/usr/local/share/huginn-cli/`) stays as the offline fallback for
  the binary. Recommended addition: an ed25519 signature over `manifest.json` (key held like the other
  release keys), because a binary has no "parse check" equivalent of `bash -n` / `node --check`.
- **One updater:** `huginn update` and the core's background check (launch + every 4 h, as the desktop
  does today) both apply core + GUI together. The GUI shows "update ready" from `/core/update` and never
  downloads anything itself. Distro installs only print the `sudo apt|pacman` line.
- **Release identity gate:** the denylist scan (`/etc/huginn-appd/release-deny.txt`, `strings`-based)
  runs over the Go binaries too.

## 9. Port risks (the subtle parts, from the 2026-10-14 inventory)

These are the places where the current code carries long *why* comments; each needs a named test in Go.

1. **Attach loop:** `ssh -tt` with keepalives; reconnect judged by session duration (3 exits < 5 s = give
   up), 2 s→15 s backoff ±25 %; the one atomic remote line (has-session / count clients / mirror or solo);
   never resurrect a killed session; `=name` exact-match targets; the name rule `^[a-z0-9_][a-z0-9_-]{0,49}$`
   after lowercasing; CSI 22/23 title push/pop only on a TTY.
2. **appd-over-ssh error classes:** reached / HTTP error with the daemon's own text / not reached, with
   ssh's 255 distinguished; never `curl -f`; tmux fallback for `kill` only when the daemon was not reached.
3. **Remote quoting:** everything after the host is shell input on the host. Use stdin/base64 everywhere
   (the `-p` deny-list once shipped word-split, denying nothing).
4. **TLS pin-by-proof:** Go `tls.Config{InsecureSkipVerify, VerifyConnection}` checks the leaf before any
   request byte is written — the property `JudgedAgent` exists for. Keep: own-handshake fingerprint, HMAC
   `huginn-tls-v1:<nonce>:<fp>`, constant-time compare, pins keyed by `pinTag`, ≤ 4, re-prove once,
   handshake deadline, separate probe connection, old-daemon refusal, http→https on load and save.
   Gate: the docs/TLS.md test vector + the end-to-end tests in `test-device-tls.js` re-pointed at Go.
5. **Runner output:** 128 KiB line / 192 KiB batch caps with in-place string shrinking; terminal frame
   always alone; permanent (400/403/404/413) vs 401 (stop delivery, keep the child) vs transient; cancel
   arrives on beats and acks; 180 s startup watchdog only; cwd checked before spawn; error text from the
   *start* of stderr.
6. **Policy:** `gen-device-policy.js` gains a 6th output, a Go table; the Go binary keeps `print-argv` so
   `server/appd/test/device-policy-cases.test.js` runs the 144-case matrix against it unchanged.
   Unknown scope → `generate` (refused at enrol); `generate` exclusive and lock-blind; `--resume` only for
   a UUID; `actWhileLocked` only a literal `true`, always sent as a boolean; the scope-echo check.
7. **Spawning claude on Windows:** `.cmd`/`.bat` shims resolved to the sibling `cli.js` and run through
   `node`, never a shell (Go `exec` has the same `.cmd` trap). The native `claude.exe` needs none of it.
8. **Local AI elevation:** pkexec→sudo with an explicit env, 126 (declined) stops vs 127 (absent) tries the
   next; `runuser` + `XDG_RUNTIME_DIR` for user-bus calls as root; chown hand-back; Windows admin check
   before any download; adapters (Ollama/LM Studio) never installed or stopped.
9. **Off/uninstall order:** unenrol on the server before deleting credentials; keep the id if the DELETE
   fails unless forced; remove units in whichever scope they exist; never touch an adopted engine;
   `purgeDir` refuses `/` and `$HOME`; only an exact `Host huginn` stanza and a key marked as huginn's.
10. **sh/ps1 drift to resolve, not port:** ps1 `device on` ignores `HUGINN_DEVICE_DIR`; ps1 `local on`
    leaks `$env:HUGINN_LOCAL_DIR`; different local-dir defaults. Go picks one behaviour per item.
11. **The desktop probes CLI text:** `TerminalLaunch.kt` detects attach-by-name by looking for the
    `_huginn_attach_named` function; `CliSync.kt` reads version strings by regex. Both change in P1.

## 10. Phases

Each phase ships as one lockstep train (core + GUI same version).

**P1 — the core exists; act mode is always-on (huginn 2.0.0)**
- Go `huginn core run|install|uninstall|status`, `huginn device on|off|status`, IPC server with `/core/*`
  (device, status, events) — **not** the proxy yet.
- Go runner (policy table generated, `print-argv`, lock probe, unenrol debt), TLS pin-by-proof, token
  store (keyring + file).
- Adopts the existing device id from `~/.config/huginn/device.json` or the desktop's `settings.json`
  `deviceId`, so no machine gets a second row (§11 Q7).
- Desktop: `DeviceRunner.kt` deleted; the Settings "Available to huginn" panel drives `/core/device`.
  If no core is installed, the GUI says so and offers to install it (bundled binary).
- The shell scripts stay as the interactive front in P1; `huginn device`/`core` dispatch to the binary.
- **Exit:** RIG-class box keeps its device row through a reboot with nobody logged in, on Linux and on
  PC01; 144-case matrix green against Go; TLS vector + E2E green.

**P2 — the core owns the machine (2.1.x)**
- Proxy (`/appd/*`), routes, watch stream, notify rules + claim (D14), tray role, `huginn://`, updater for
  core + GUI, local AI (`huginn local …`, shim as a sub-mode), single-instance via IPC.
- Desktop: `CoreTransport`; token/routes/pins wiped from `settings.json`; tray, notifiers, autostart,
  scheme registrar, updater, `CliSync`, `LocalServe` deleted; window close quits.
- Installers carry the core; NSIS/deb/pacman/portable rewritten (§7).
- **Exit:** GUI closed → toasts still arrive at the desk, Telegram when away; GUI relaunches from the tray;
  one update moves both.

**P3 — one binary (2.2.x)**
- Go `huginn` replaces `huginn.sh` / `huginn.ps1` (attach, verbs, completion, aliases, uninstall);
  bootstrap scripts become "download binary, run `huginn setup`".
- Node satellites deleted from the tree; `test-client.sh` source-greps rewritten as Go tests; black-box
  sections re-pointed at the binary.
- **Exit:** a clean Windows and a clean Arch box go from one bootstrap line to attach + GUI + background.

**Existing installs** (RIG/CT 203 `rig-llm`, PC01, a Linux laptop, buildbox) move in P1/P2 with their
device ids adopted; the Node runner services (`huginn-local-runner`, `huginn-device.service`) are stopped
and removed by `huginn core install` once the Go runner has enrolled under the same id.

## 11. Open questions

| # | Question | Proposed answer |
|---|---|---|
| Q1 | Can the JVM GUI speak HTTP over a local socket, including SSE? | **PROVEN on Linux and Windows (2026-10-14).** Linux: 11/11 on huginn, 10/10 on a Linux laptop in the app's own jlink runtime. Windows (PC01, Win 11): 10/10 in-session AND **cross-session** — server in an S4U task (session 0), client in the signed-in desktop (session 1); peer check resolved `HOME\alex` by SID. **AF_UNIX on both platforms; no named-pipe fallback needed.** |
| Q2 | Windows boot task logon type | **S4U, default (2026-10-14).** Measured from an S4U task on PC01: session 0, Credential Manager ok, DPAPI ok, HTTPS 200, profile + `~\.claude` present — no stored password needed. ⚠ A task does not START on battery unless `DisallowStartIfOnBatteries=false` (it sits "Queued"); the core's TaskXML already sets it. |
| Q3 | Do the remote verbs keep using ssh, or go through appd? | Keep ssh in P3 (it works without a token and is the terminal path's whole point). Revisit only per verb. |
| Q4 | Distro packages: system `/usr/bin/huginn` + `Depends`, as in §7.4? | Yes, with "one core per machine". |
| Q5 | Tag/manifest namespace for the unified release (`huginn-v2.0.0`? keep `desktop-v*` for the GUI feed?). | One tag `huginn-v*`; old clients' `desktop-v*` feed gets a final release whose installer carries the core (the migration step). |
| Q6 | Pairing: today the token is copied by hand or over ssh (`cat /etc/huginn-appd/token`). | Keep the ssh path (`huginn setup` already has ssh); a pairing code/QR is a separate project (onboarding audit §A). |
| Q7 | Adopting device ids: what if a machine has both a Node runner id and a desktop id today? | Prefer the one appd saw most recently; delete the other row through the API. |
| Q8 | Manifest signing key: introduce now (P1) or later? | P1 — the first binary release is the moment the "parse check" disappears. |
| Q9 | Go tray library | `fyne.io/systray` v1.12.2, `CGO_ENABLED=0`. **Linux PROVEN** (a Linux laptop/quickshell SNI). **Windows: works** — the icon registers (`HKCU\Control Panel\NotifyIconSettings` entry) but Windows 11 puts every NEW icon in the `^` overflow (`IsPromoted` unset; the current desktop's icon is hidden the same way). P2: the tray role may set `IsPromoted=1` on its own entry at install. |
| Q10 | Idle/lock on Linux Wayland for the claim | **Measured on a Linux laptop 2026-10-14:** logind `IdleHint=no`, `IdleSinceHint=0` on the active Wayland session — nothing on Omarchy feeds logind idle, so it is useless there. The tray role must use `ext-idle-notify-v1` (Hyprland supports it); `LockedHint` is still read from logind for the lock fence. Unknown ⇒ "not at the machine" (Telegram wins). |

## 12. Progress (2026-10-14)

**P1 built and tested on Linux** (branch `core`, nothing merged or released):

| Piece | Where | Tests |
|---|---|---|
| Device policy, 6th generator output, `print-argv` | `core/internal/policy`, `scripts/gen-device-policy.js` | 144-case matrix against the Go binary (11/11), 704/707 byte-identical edge runs (the 3 are a Node `$&` replace quirk — Go matches Kotlin) |
| appd client, TLS pin-by-proof | `core/internal/appd` | 22 tests incl. E2E vs a real appd behind a relay; mutation-checked |
| IPC (AF_UNIX, peer check, 426 gate, SSE hub) | `core/internal/ipc` | 6 |
| store (core.json, secrets, migration plan), service (systemd --user + linger; Windows task XML S4U/password; AUMID shortcut), lock probe | `core/internal/{store,service,lock}` | golden + flow tests |
| Runner | `core/internal/runner` | 13 incl. fake appd + fake claude; mutation-checked |
| Daemon + `huginn core run/install/uninstall/status/pair`, `huginn device on/off/status` | `core/internal/daemon`, `core/cmd/huginn` | `core/test/e2e.sh` 16/16 and `e2e-migrate.sh` 7/7 against a real appd |
| Desktop: `CoreDevice` + `CoreSocket`/`CoreClient`; `DeviceRunner`/`LockProbe` deleted | `mobile/app-desktop/.../core/` | 652 desktop tests; `CoreClientTest` drives the real Go core from Kotlin |

**Decisions made while building (owner APPROVED 2026-10-14):**
- **D11 reversed for the boot service: the token lives in the 0600 file store by default**, keyring opt-in
  (`HUGINN_SECRETS=keyring`) — a Linux login keyring is locked until a login, and Credential Manager is
  likely unavailable to an S4U task, i.e. exactly when always-on matters.
- **"No graphical session" is split by seat0 `CanGraphical`:** a headless box is unlocked (act-scope
  servers keep working, as with the Node runner); a desktop at its login screen is locked (act needs
  "keep act mode while locked", the RIG setting).
- **Pin tags:** Node (`HMAC(token,"huginn-tls-v1:pinset")[:16]`) and Kotlin (`sha256("huginn-tls-pins:"+token)[:16]`)
  disagree; the core follows Node. Migrated pins cost one re-proof. TLS.md should name one.
- **Dropped ids are recorded, never auto-deleted** at import (a dropped id may be a live old runner).

**Packaging + clients (done 2026-10-14, later the same day):**
- `huginn core self-install` (per-user layout, atomic `current` swap, prune to 2, Windows PATH via the
  registry) is the one install path; `core install` self-installs first and refuses beside a
  package-owned `/usr/bin/huginn` (one core per machine).
- `core/build.sh <ver>`: reproducible CGO-off builds for linux-x64/arm64, windows-x64, android-arm64
  + `core-manifest.json` + `SHA256SUMS`. Tags stay `cli-v` / `desktop-v` (same version) in P1; Q5's
  single tag is P2 with the updater.
- `huginn.sh` / `huginn.ps1`: `huginn core install|status|uninstall|…` (fetch → sha256 → self-install
  → pair over ssh); `huginn device` goes to the core when one is installed. `client/get.sh` /
  `get.ps1` bootstrap with the background + GUI prompts.
- Desktop: `release-desktop.sh` builds the core at the desktop's version; Gradle bundles it in
  Linux app-resources (`-PcoreBinary`); NSIS installs `core\huginn.exe` and self-installs it; the app
  self-installs a bundled core at launch and offers "Set up huginn core".
- **Real-systemd proof on huginn (throwaway user, private appd, all removed after):** `core install`
  → unit + linger; the user manager stopped and restarted with NO login session → same device id,
  and an act chat ran on it.

**Left before a 2.0.0 cut:**
1. Windows: kit DONE (Q1/Q2/Q9 above). Left: one real `huginn core install` on PC01; the NSIS
   `CORE_EXE` section is verified only by the release build.
2. A full `release-desktop.sh` run (it publishes, so it is a release decision).
3. `cli-v2.0.0` release: `core/build.sh 2.0.0` → `scripts/github-release.sh core 2.0.0 core/dist/2.0.0/*`.

## 13. Phase 2 progress (2026-10-15)

**Released in CLI 2.1.0 / 2.1.1 and desktop 2.1.1 (2026-10-15):** the core owns the tray and notifications (§4 rows "Watch stream +
claim", "Notify rules", "Toasts", "Tray icon + menu").
- `core/internal/notify`: the desktop's NotifyRules + WatchCycle, all 33 test cases.
- `core/internal/tray`: the tray role (`huginn core tray`); icons generated from the brand raven
  (`icons/gen.sh`); notifications via D-Bus on Linux and WinRT toasts on Windows; `notify-test`.
- `daemon/watch.go`: the appd watch stream → tray summary + notices. Claim = a tray attached AND
  the session unlocked. Presence from the GUI suppresses the toast for whatever it is showing.
- The GUI stands down while `coretray` is attached.
- Proven: e2e 21/21 against a real appd. On PC01: the Startup shortcut, the tray in session 1,
  the icon registered with the shell, a toast delivered under the app's identity (on the lock
  screen, content shown as "Private").

**Upgrade path (built 2026-10-15, e2e 29/29).** Every route by which a newer core reaches a
machine ends in `huginn core self-install`, run by the new binary: `huginn core update`, `huginn
update` (lockstep, D10), `huginn core install` and the bootstrap on a machine with an older core,
and the desktop app at launch when the core it carries is newer (`CoreSetup.bundledIsNewer`).
`self-install` then:
- asks a running core of another version to restart (`POST /core/restart`). The core waits for a
  running job to finish, then `core run` exits 0. systemd's `Restart=always` brings it back in
  10 s. On Windows, `self-install` runs `schtasks /Run`; the task's 5-minute repeat covers a start
  it may not make. A 2.0.0 core has no `/core/restart`, so it is restarted with `systemctl --user
  restart` or `schtasks /End` + `/Run`. If those cannot be reached (no user bus in an ssh shell; a
  task this user may not end), the old core is stopped by its pid (2.1.1), unless it is running a
  job. Proven against the published 2.0.0 binary.
- adds the tray's login autostart once to a machine whose core service predates the tray. A
  marker in the state dir (`tray-autostart`) records that, so an autostart the person removed is
  not put back. Linux needs a graphical session for it, the same rule as install.
- starts the tray now, when this process is in a desktop session (Windows: not session 0) and not
  elevated, and no tray is attached. Windows `core install` runs elevated, so the PowerShell
  client and the desktop app run an unelevated `self-install` afterwards to start the tray.
- A running tray whose core restarted onto a newer version hands over by itself. It checks that
  the stable binary reports the core's version, starts it as the tray, and quits. A lock file in
  the runtime dir allows one tray per user; a second tray waits 15 s for an outgoing one, then
  leaves.
- ⚠ The 2.0.0 client cannot drive this: it has no `core update`. Upgrading from 2.0.0 means
  `huginn update`, then `huginn core update` in a new shell, or installing desktop 2.1.0.

**Windows 11 overflow (decided 2026-10-15): leave it to Windows.** New tray icons start in the `^`
overflow menu, and the person pins one with a drag. No supported API promotes an icon; writing
`IsPromoted` under `HKCU\Control Panel\NotifyIconSettings` is undocumented and is the kind of
thing a user should decide. The notifications are not affected.

**Hardware check, PC01 (2026-10-15, after the owner updated it):** the tray handoff works
(2.1.0 tray → 2.1.1 tray in about 20 s, counted by the core); a toast posts and withdraws in
session 1; the core claims notifications for that tray. **Found:** an unelevated update restart
could leave the core DOWN. The old core closes its socket before its process exits (shutdown waits
up to 5 s on open event streams), Task Scheduler ignores `/Run` for a running instance, and the
one `/Run` landed in that window. Fixed in 2.1.2: wait for the old pid, then repeat `/Run`
until the new core answers (proven twice on PC01, 9 s each). **Not explained:** right after
the owner's update the core reported `tray: true` with no tray process running (and no tray
started at login since). A killed tray, and a killed raw client, both drop the count within 2 s,
so this was not reproduced; watch for it. The owner saw SEVERAL huginn tray icons after the update and closed each one. Windows leaves a dead icon behind for a process that was killed rather than quit, and the desktop installer runs `taskkill /F /T` on the app. Lead: which trays existed during an app update, and which of them were killed. **a Linux laptop (Omarchy/Hyprland, 2026-10-15):** after the desktop's in-app update to 2.1.2 (bundled
core self-installed) and `huginn core install` (systemd user unit + linger), self-install from the
graphical environment wrote the autostart and started the tray. The only StatusNotifierItem on the
bus is the core tray's, so the desktop stood its own icon down. A D-Bus notification posts and
withdraws, and `notifyClaim` is true. **Found:** the core's `huginn` binary on PATH shadowed the
shell client wherever the client is not a loaded function (fish, which Omarchy execs before
`.bashrc` sources the client; cmd.exe; no-profile PowerShell). Fixed in 2.1.3: the binary hands
every verb it does not own to `~/.huginn/huginn.{sh,ps1}` (`HUGINN_VIA_CORE` guards the loop).

**Security review of the 2.1.0 range (2026-10-15): no HIGH or CRITICAL.** Fixed before the cut:
Linux notification bodies are escaped (markup injection from model output); the core claims
notifications only for a tray that can deliver (`deliver=0|1` on `/core/events`); a missing
runtime dir no longer reads as "another tray". **Open:** `huginn core update` trusts the sha256 in
the same release as the binary, so it catches corruption but not a substituted release (a stolen
publish credential). An Authenticode check would refuse every machine outside home.arpa today,
because the signing CA is the domain's. Do it once Azure public trust is resumed, or sign
`core-manifest.json` with a pinned key (minisign) and check it in both clients.

Not started in P2: the `/appd/*` proxy (the GUI still holds its token), the single updater, and
local AI in the core.
