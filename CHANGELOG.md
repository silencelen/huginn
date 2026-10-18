# Changelog

All notable changes to the **terminal client + server core** (`client/`, `server/`)
are documented here. The other components version independently and keep their own
changelogs: the Android app ([`mobile/CHANGELOG.md`](mobile/CHANGELOG.md)), the
desktop client ([`mobile/app-desktop/CHANGELOG.md`](mobile/app-desktop/CHANGELOG.md)),
and the daemon ([`server/appd/CHANGELOG.md`](server/appd/CHANGELOG.md)).
Format loosely follows [Keep a Changelog](https://keepachangelog.com/); versions use
[SemVer](https://semver.org/).

## [2.5.8] - 2026-10-17

From the owner's eighth recorded install on PRESTIGE.

### Fixed
- **Terminal setup finds an address that SSH reaches.** On PRESTIGE, `192.168.2.117` (the address
  people know, and what DNS gives) carries only huginn's own port from its VLAN, while
  `192.168.7.117` carries SSH. The terminal path paired, but could never attach. Once paired,
  `huginn setup ssh` asks huginn for its own addresses (appd 3.12.1). It tries SSH's port on each,
  preferring one on this computer's own network, and points `Host huginn` at the first that
  answers. It only edits a stanza that names one of huginn's own addresses. The installers run it
  themselves when SSH did not answer.

### Added
- **`huginn core tray show`** starts the tray icon now and returns: the way back after "Hide this
  icon", and what the desktop app's new Tray icon switch runs.

## [2.5.7] - 2026-10-17

From the owner's sixth and seventh recorded installs on PRESTIGE. The sixth confirmed the uninstall no
longer opens a "ping" window. The seventh took the terminal-only path, without the desktop app.

### Fixed
- **`huginn uninstall` removes local AI on Windows.** Once its services stopped, the model server
  still held its files for a moment, and deleting the folder failed with `EPERM`. The removal now
  waits up to 15 s for anything still running from the folder, ends what is left, and retries the
  delete.
- **A second `huginn uninstall` can still remove local AI** after the first one could not. The
  first had removed the CLI, which holds the local-AI manager, so the second had nothing to run it
  with. The core now carries a copy of the manager and uses it.
- **No visible console during uninstall.** The elevated step that deletes the boot task runs hidden.
  The visible window showed nothing, and a click on it paused the step.
- **No Linux `systemctl` hints on Windows** when local AI is turned off.
- **Terminal setup pairs without SSH** (run 7: the host refuses port 22 from PRESTIGE). When SSH to
  the host does not answer, setup asks for huginn's token, typed or pasted and never shown, and
  pairs over huginn's own port. Then it goes on to offer the device and local AI. `huginn core
  install` does the same instead of printing a raw `ssh: connect … timed out`. Before, nothing
  paired.
- **The host prompt takes huginn's address as people know it.** `192.168.2.117:8787` or
  `https://192.168.2.117:8787` gives SSH the bare host and keeps the address for pairing. It used to
  become an SSH host name that resolved nowhere.
- **No "Press Enter when that is done" when there is nothing to do.** With SSH not answering at all,
  setup says that attaching from a terminal needs SSH, and moves on.
- **`huginn core pair` no longer shows the token as it is typed or pasted.**

### Changed
- **One tray icon, one menu** (owner). huginn core's tray icon now carries the desktop app's items
  while the app is open: **Close to tray** and **Quit Huginn**, under Open Huginn and the sessions
  that need you. The app no longer needs an icon of its own beside it. Clicks reach the app through
  the core (`POST /core/gui`), sent once and never replayed to an app that connects later. Open
  Huginn brings a running window up directly, without starting a second process to pass the click
  on.
- **"Quit tray" is now "Hide this icon".** Next to "Quit Huginn" it read like the same thing. It
  takes the icon away until the next sign-in, and huginn core keeps running.
- **The tray's tooltip reads "Huginn — idle"**, capitalised like the rest of the prose (BRAND.md).

## [2.5.6] - 2026-10-17

From the owner's fifth recorded install on PRESTIGE.

### Changed
- **Local AI downloads report every percent, with how much and how fast**
  (`Qwen3-4B-Q4_K_M.gguf: 31%  740 of 2382 MB, 2.3 MB/s`), at most once a second. In steps of
  10%, a 2.4 GB model read "0%" for two minutes and looked stuck.

## [2.5.5] - 2026-10-17

From the owner's fourth recorded install on PRESTIGE.

### Fixed
- **No empty "ping" terminal after `huginn uninstall` on Windows.** The step that removes the
  core's own folder after it exits ran `cmd` with no console, so the `ping` it waits with opened
  a window of its own. It now runs in a console that is never shown.
- **The installer's spinner draws its wing marks in PowerShell 7** instead of `?`. Redrawn lines
  go through the PowerShell host, which can show them.
- **Long installer lines wrap under their own text** in the PowerShell client too, the way the
  core's own lines do since 2.5.4.

## [2.5.4] - 2026-10-16

From the owner's third recorded install on PRESTIGE.

### Fixed
- **The desktop app no longer runs as administrator when it is installed from an administrator
  terminal.** `huginn desktop install` opened it with the terminal's token. It now opens through
  Explorer, as the signed-in user.
- **Local AI set up from the desktop no longer prints Linux service commands on Windows.** The
  device runner's "start it with systemctl" lines are for a person enrolling by hand. `huginn
  local on` installs and starts the services itself, so they are left out there, and on Windows.
- **Download progress names the file being downloaded** (`Qwen3-4B-Q4_K_M.gguf: 40%`), not its
  `.dl.tmp` temporary file.
- **`huginn uninstall` wraps long rows under their own text** in a narrow terminal, not at the
  margin. The same goes for every installer-style line the core prints.

## [2.5.3] - 2026-10-16

### Fixed
- **On a laptop on battery, the Windows installer left huginn core stopped.** Windows' Energy Saver
  queues every start of the core's boot task. `huginn core install` now runs `huginn core start`
  itself when the core does not answer after the task is registered. It runs unelevated, so the
  core never runs as administrator, and starts the core directly when the task is held back. Until
  now, the desktop's setup then found no core (PRESTIGE).
- **A fresh `huginn desktop install` no longer pre-answers the desktop's setup.** The silent
  installer wrote its components page's defaults (local AI off, start at sign-in off), and the
  app showed those steps as "skipped", answers nobody gave. That file is removed before the app
  opens.

## [2.5.2] - 2026-10-16

### Changed
- **The installers ask about the desktop app first, before any configuration** (owner rule):
  - **Desktop:** they install huginn core (which carries the CLI) and the desktop app, then open
    it. The app's setup window walks through the rest (huginn's address, the token, this machine
    as a device) and configures the core with it. Nothing is paired in the terminal.
  - **No desktop:** the terminal onboarding runs as before: host, key, the host's trust, the core
    paired, then the device and local AI questions.
  - On Linux the question is asked only where there is a graphical session.
  - Terminal attach over SSH, in the desktop path, is set up any time with `huginn setup ssh`.

### Fixed
- **A fresh install could leave huginn core "paired" with no token, and stuck there.**
  - The core's first-start import took the desktop's address without a token: the desktop had
    saved the address it was typed, but no token yet.
  - The core then reported itself paired, so nothing ever paired it properly.
  - The desktop's setup accepted the token, but its device step failed with "no appd token"
    (PRESTIGE).
  - The import now takes an address only together with its token, and the core counts as paired
    only when it holds both.

## [2.5.1] - 2026-10-16

### Added
- `GET /core/status` reports the certificate fingerprints the core proved (`pins`). They are public
  hashes, not secrets. Huginn Desktop 2.5.1 compares them with its own pins before it hands its link
  to the core: the same certificate means the same huginn, even on another address (a LAN and a
  mesh address of one host).
- **Huginn Desktop 2.5.1 goes through huginn core.** See its changelog; this is the core it needs.

## [2.5.0] - 2026-10-16

The core side of "the desktop goes through huginn core" (docs/DESKTOP-VIA-CORE.md, owner decision
2026-10-16): the desktop app will stop holding the appd token and send everything through the core
on the same machine. This release makes the core able to carry all of it. The desktop release that
uses it comes next; until then, nothing changes for the desktop.

### Added
- **The core's `/appd` proxy is transparent.** Before, it carried short JSON calls and one GET
  stream, enough for the console. Now:
  - any method;
  - request bodies are streamed with their own Content-Type, up to appd's 128 MiB upload limit;
  - every answer is copied as it arrives: event streams over GET or POST, 25 s long polls,
    downloads;
  - appd's status, headers and body come back as appd sent them;
  - a caller's `X-Huginn-Client` and `X-Huginn-Notify` reach appd as its own, so appd still tells
    the desktop and the core apart;
  - there is no fixed timeout.
  When huginn cannot be reached, the core says why in `kind`: `unreachable`, `tls`, `auth` or
  `unpaired`.
- **A route book in the core:** the paired daemon's other addresses, such as its LAN address at
  home and its mesh address away. It is kept in core.json and works like this:
  - A request that never reached huginn is tried once more on the next address, which becomes the
    active one. That covers a connection that is refused, timed out or has no route, and an address
    whose certificate is not huginn's (somebody else's machine at a home address). In that last case
    nothing is sent, because the certificate is judged before any byte.
  - A request that may have reached huginn is never sent again, even if its answer broke off.
  - A dead address is given up on in 6 s, not the 20 s handshake limit.
  - The core moves back to the preferred address within a minute of it answering again.
  - `huginn core route` lists the addresses with their health; `add <url> [--name] [--kind]`,
    `remove`, `pin <url>|none` and `auto on|off` edit them. The same book is at `/core/routes` for
    the desktop.
  - Pairing with a different huginn clears the book.
- **`POST /core/local/seed`:** the core hands the local AI tier its appd token from its own secrets
  store and names the address to turn it on with. The desktop's "serve local models" uses it, so the
  token never passes through the app.

## [2.4.2] - 2026-10-16

### Added
- **Setup asks its questions.** After the core is installed and paired, the one-line installers
  ask, each with a default, and can be answered up front for unattended installs
  (`HUGINN_DEVICE`, `HUGINN_GUI`, `HUGINN_LOCAL` = `y` or `n`; no terminal = no):
  - Offer this machine to huginn as a device? [Y/n]: `huginn device on`, read-only (scope `look`)
    until widened with `--scope work`.
  - Install the desktop app? [Y/n]: verified and silent. On Linux it is asked only where there is a
    graphical session.
  - Serve local AI models from this machine's GPU? [y/N]: offered only where `huginn local plan`
    finds a usable GPU and the tier is not set up. On Windows it runs in one elevated window that
    shows the download.
- **`huginn desktop install` works on Windows.** It downloads the release's installer, checks its
  sha256 against the release manifest, installs silently (per-user, no admin) and opens the app.
  It used to answer with the `.exe` link to run by hand.
- After `irm …/get.ps1 | iex`, `huginn` works in the same window, not only in new ones.

### Fixed
- **Installing Huginn Desktop downgraded the core.** The desktop's installer runs the core it
  carries (`core self-install`) without comparing versions, so installing Desktop 2.3.6 after the
  2.4.1 one-liner put core 2.3.6 back (PRESTIGE).
  - `core self-install` now keeps a newer installed core and exits 0, saying so (`--force`
    overrides).
  - An older copy's `core install` hands the install to the newer core.
  - `huginn desktop install` and `huginn desktop update` put the newer core back if an older
    desktop installer replaced it.
- **The desktop question was never asked on Windows.** get.ps1's own `$gui = $false` overwrote its
  `[string]$Gui` parameter with "False" (PowerShell names are case-insensitive), which read as the
  answer no. A test now refuses any assignment that reuses a parameter's name.
- **`huginn uninstall` left the local AI tier behind on Windows.** It is LocalSystem services under
  ProgramData, and uninstall ran its removal unelevated, which the tier refuses.
  - Uninstall now asks about it on its own, with its size: "Also remove the local AI tier …?
    [Y/n]", default yes.
  - On Windows the removal runs elevated: one administrator prompt, a hidden window, its output
    in the uninstall's log.
  - Answering no keeps it, as a note.
- Uninstall no longer spends 20 s starting a stopped core when no device is enrolled to retire.
- The pairing line read `752cd51125aa37f4ΓÇª` in PowerShell: the core's UTF-8 was read as the
  console's code page. The line is ASCII now, and the PowerShell client reads the core's output and
  logs as UTF-8.

## [2.4.1] - 2026-10-16

### Fixed
- **The download bar printed "No such file or directory" while a download started**, once per
  redraw until the first bytes arrived (seen on a first install from the published 2.4.0, slow to
  start). The size probe's redirection failed before curl had created the file, and bash reports a
  failed redirection before a `2>/dev/null` written after it takes effect. The bar has had this
  since 2.3.7, in `huginn update` too; a fast link hid it. The PowerShell client was not affected.

## [2.4.0] - 2026-10-16

### Changed
- **Setting huginn up and taking it off read like an installer**, as `huginn update` does since
  2.3.7: one aligned line per component with the brand's state marks, the wingbeat on a step
  that is working, and detail only where something needs a look.
  - `huginn uninstall` first shows what is on this machine and what goes, top down, and asks once.
    Then it prints one line per component: desktop, local AI, device, core, CLI, ssh. Anything left
    behind is a note under its line. The full path-by-path list goes to a log in the temp folder,
    named at the end.
  - `huginn core install` folds its service report into lines: binary, CLI, service, linger, tray
    and paired.
  - On Windows the elevated step of `core install` runs in a window of its own that closes when it
    ends. It now hands its lines back, so they are drawn in your own terminal, and that window
    stays hidden.
  - The one-line installers (`get.sh`, `get.ps1`) and the clone installers draw one `huginn setup`
    block: release, key, ssh, CLI and trust, then the core's lines, then the desktop question.
  - `huginn setup ssh` draws the same lines.
- **Windows installs with one line: `irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1 | iex`.**
  - It asks for the host, or reads `$env:HUGINN_HOST` (and `$env:HUGINN_GUI` for the desktop
    question).
  - It runs in its own scope, so your session keeps none of the installer's variables, functions
    or `$ErrorActionPreference = 'Stop'`.
  - The `& ([scriptblock]::Create(...)) -HuginnHost` form still works. The Linux line no longer
    needs the host either.
- **Trust in the host is reported as it is.**
  - A host that does not know this computer's key says so, with the exact `authorized_keys` line
    to paste.
  - An unreachable host gives ssh's reason.
  - A host key that CHANGED is a stop, never something to paste past.
  - A host key new to this computer is ssh's own question, with its fingerprint: the installers
    never accept one on your behalf.
- `huginn core install --tray` and `--no-tray`. The tray's "skipped" message named a `--tray` flag
  that did not exist.

### Fixed
- **A first install on Windows never installed the core.** `get.ps1` called `huginn core install`
  in a scope where the `huginn` function did not exist. It printed "did not finish: The term
  'huginn' is not recognized", then carried on as if the core were there.
- **Every `huginn` command queried the terminal.**
  - On every command, the terminal went into raw mode for up to 5 s for a background-colour
    request (OSC 11) and a cursor report. bubbletea's package init does this, and the console is
    linked into every command.
  - A terminal that does not answer stalled for 5 s and lost type-ahead, including the "uninstall"
    typed at `huginn uninstall`'s prompt.
  - The core now carries bubbletea 1.3.10 without that init (`core/third_party/bubbletea/HUGINN-PATCH.md`).
    The console asks for the colour itself, just before it starts.
- `huginn uninstall` exited 1 on every ordinary run: the `Host huginn` stanza it keeps by default
  counted as "left behind". Things kept on purpose are now notes, not failures.
- When a shell file held both the installer's line and a hand-edited huginn line, uninstall removed
  the installer's line and kept the hand-edited one without saying so. It is now reported.
- `huginn uninstall`'s plan named a boot service and a tray on machines that had neither.

## [2.3.7] - 2026-10-16

### Changed
- **`huginn update` looks and reads like an installer:** one aligned line per component (client,
  core, desktop, local AI), then the time it took.
  - In a terminal, a step that is working shows the brand's wingbeat spinner, and every download
    shows a bar with its percentage, size and speed. Both are redrawn in place and settle into the
    final line.
  - The lines use the brand's state marks: `■` done, `▲` needs a look, `×` failed, `◦` skipped.
  - A step's own report is kept out of the way and shown only if the step fails.
  - Piped or logged, only the final lines are written. `NO_COLOR`, `HUGINN_ASCII=1` (7-bit marks and
    bars) and `HUGINN_REDUCED_MOTION=1` (a still frame) are honoured.
  - The same bars and spinner appear in `huginn core install`, `huginn desktop install` and
    `huginn desktop update`, in both the bash and PowerShell clients.

## [2.3.6] - 2026-10-16

### Fixed
- **Updating the Windows desktop app closes it cleanly.** Before running the installer,
  `huginn update` / `huginn desktop update` now ask the open app to quit through its single-instance
  channel, the same way its tray's "Quit Huginn" does. The installer used to end it by force, and the
  Java launcher then showed error dialogs on the way out. The request needs the secret in a file only
  your account can read, and a web page cannot send it. An app from before 2.3.6 does not know the
  request, so its last update still closes it by force.
- `huginn core update` in PowerShell shows the core's own report again ("restarted huginn core:
  X -> Y"). It was swallowed.

## [2.3.5] - 2026-10-16

### Fixed
- **Windows: the tray comes up properly at sign-in.** In 2.3.3 the "Huginn Tray" logon task started
  the tray in the same second as Explorer. The tray got a blank, unclickable icon in before the
  taskbar was ready, never finished setting up, and never connected to the core. It also kept the
  one-tray lock, so the Startup-shortcut fallback stepped aside and there was no working tray at
  all (PRESTIGE, 2026-10-16). Three changes:
  - the task now starts the tray 10 seconds after sign-in, still minutes ahead of the Startup
    folder;
  - the tray waits for the taskbar before adding its icon;
  - a tray that has not finished setting up within 30 seconds starts a fresh one and exits, once.
- **No console window flashes at sign-in.** `huginn.exe` is a console program, because it is also the
  CLI, so the task now starts the tray through `conhost --headless`.
- **The logon task follows the tray's Startup entry.** Delete that shortcut, or disable it in Task
  Manager's Startup tab, and the task no longer starts the tray (it checks at sign-in). The next
  update also removes the task. Changes to the task's definition reach existing installs on their
  next update.

## [2.3.4] - 2026-10-16

### Changed
- **`huginn update` updates everything huginn on the machine:** the client and huginn core as before,
  then the desktop app and the local-AI tier, whichever are installed. It used to stop after the
  core.
  - **Windows:** the desktop installer comes from the newest release, is checked against the sha256
    in that release's manifest, and runs silently (it is per-user: no administrator). An app that
    was open is opened again.
  - **Linux, per-user app:** updated through the same verified path as `huginn desktop install`.
  - **Linux, Debian or Arch package:** downloaded, checked against its sha256, then installed with
    `sudo` from a terminal. Without a terminal, the exact command is printed.
  - **Local-AI tier:** `huginn local update` runs from a terminal, since it asks before it changes
    anything.
- **`huginn desktop update`** (new) does the desktop step on its own.

## [2.3.3] - 2026-10-16

### Changed
- **Windows: the tray appears seconds after sign-in.** Windows runs Startup-folder shortcuts only
  after it has worked through every other app's startup entry one by one, each allowed up to
  30 seconds. On PRESTIGE the tray appeared 3.5 minutes after sign-in. huginn core now also
  registers a per-user "Huginn Tray" task triggered at your logon and run in your session, with no
  administrator prompt. The Startup shortcut stays as the fallback, and the one-tray lock keeps it
  to one icon. Existing installs get the task on their next update, unless you removed the tray's
  Startup shortcut. `huginn uninstall` removes it.

## [2.3.2] - 2026-10-16

### Fixed
- **Windows: `huginn update` no longer fails with "Access is denied" when an older huginn window is
  still open.** An update sets the running `huginn.exe` aside as `huginn.exe.old`. A console window
  left open across two updates still runs from the previous `.old`, which can then be neither deleted
  nor replaced (PRESTIGE, 2026-10-16). The update now uses a unique name when `.old` is held, and
  removes every leftover it can on later runs.
- `huginn core status` says whether a tray is **attached** (the core's own answer), and the service
  line now reads `tray at login`. The old `tray yes` line meant only that the tray starts at login,
  and was read as "a tray is running" when none was.

## [2.3.1] - 2026-10-16

### Fixed
- **Windows: an update on battery no longer leaves huginn core stopped.** With Energy Saver on, which
  Windows 11 switches on at 20% battery, Task Scheduler launched every start of the "Huginn Core" task
  and then held it queued, so the restart an update asks for never happened. The core stayed down
  until the laptop was charged (PRESTIGE, 2026-10-16). When the task has not started the core within
  20 seconds, the update now starts it directly for this login. The queued task steps aside when it
  finally runs, and the next boot is the task's again. The same happens when the domain controller
  cannot be reached at wake.
- **`huginn core start`** (new) starts a core that is set up but not running, with the same fallback
  on Windows. The desktop app's and the console's "Start huginn core" now use it.

## [2.3.0] - 2026-10-15

### Added
- **The huginn console.** Plain `huginn` in a terminal now opens a full-screen console with menus for
  everything huginn does. Home shows the raven, what needs you, and every section with live counts.
  It has screens for Sessions, Chats, Projects, Rounds, Devices, Usage, Archive, Settings and the core.
  - **Sessions:** Enter attaches over ssh, with the same reconnect behaviour as `huginn attach`.
    `Ctrl-b d` brings you back to the same spot. You can also create, send a line, end gracefully,
    rename and kill a session.
  - **Chats:** the answer streams in as it is written.
  - **Projects:** a dashboard of members; Enter attaches to one.
  - **Rounds:** Enter opens the last run, and you can pause or resume.
  - **Archive:** Enter revives a conversation and attaches to it.
  - **`/` filters** any list as you type, and **`:` is a palette** that finds screens, sessions, chats,
    projects, rounds and actions. Anything else typed there runs as `huginn <it>`.
  - Each screen's footer names the one-shot command that does the same thing.
  - Destructive actions ask you to type the name.
  - Symbols, words and colours follow the brand, with plain-text versions for older terminals
    (`HUGINN_ASCII=1`) and `NO_COLOR` honoured.
  - **What keeps working:** piped or scripted use, `HUGINN_CONSOLE=0`, Termux, and a core too old to
    have the console all keep the old behaviour of attaching to `main`, as does `huginn main`. Every
    `huginn <verb>` works as before. `huginn console` opens it by name.
- **`huginn setup ssh`** sets up the SSH side on a machine that got huginn from the desktop installer
  or a package. It makes or reuses a key and writes `Host huginn` for the host the core is paired with.
  It prints the exact line to run on the host, then checks the connection. It never edits an existing
  stanza.
- huginn core forwards requests to appd on the core's socket, with its own token and proven route
  (`/appd/v1/...`; event streams pass straight through). This is how the console reaches huginn, and
  the token never leaves the core.

### Changed
- **The installer one-liners always install huginn core**, which carries the CLI, except on Termux and
  macOS, where the core cannot run. They used to ask. The only question left is the desktop app, and
  the install ends with what it installed, what runs at boot, and the next command. `get.ps1` explains
  how to add Windows' OpenSSH client when it is missing.
- `huginn uninstall` turns linger off again when `huginn core install` had turned it on. Linger that was
  on before huginn is left, and the summary says so. On Windows it says before you confirm that Windows
  may ask for administrator approval.
- An update no longer briefly starts a second tray while the running one hands itself over.

## [2.2.0] - 2026-10-15

huginn core is the product, and it includes the CLI. The desktop app is a layer on top of it.

### Changed
- **The core carries the CLI.** Every way the core reaches a machine ends in `huginn core
  self-install`: the one-liners, `huginn core update`, `huginn update`, and the desktop app's
  bundled core. Self-install now also lays down the shell client (`~/.huginn/huginn.sh` or
  `huginn.ps1`) and its line in your shell startup files, and refreshes the local-AI helpers where
  they are installed. A machine with a core always has the CLI, at the core's own version. The
  build refuses when the embedded copy and `client/` disagree.
- **`huginn uninstall` removes the whole stack, top down.** In order: the desktop app (the
  per-user install, the distro package through `sudo` when there is a terminal to ask on, and the
  Windows app through its own uninstaller), the local-AI tier, this machine's device row (retired
  first, while the token still exists), huginn core (boot service, tray, binary, config, secrets,
  the PATH entry on Windows), then the CLI and its startup lines. `--all` still also takes the
  `Host huginn` SSH stanza and huginn's own key. It used to remove the client and leave the core
  and the desktop behind. The core owns this now; the shell clients hand it over whenever a core is
  installed.
- On Windows, deleting the boot task asks for administrator approval once when it needs it.

## [2.1.3] - 2026-10-15

### Fixed
- **`huginn ls`, `attach`, `projects` and every other session verb work again in any shell.** The
  core installs a `huginn` binary on your PATH, and the binary knew only `version`, `core` and
  `device`. Where the shell client is not loaded as a function, the binary answered with its usage.
  That covers fish (Omarchy hands interactive terminals to fish before `.bashrc` loads the client),
  `cmd.exe`, a PowerShell without your profile, and scripts. The binary now hands every verb it does
  not own to the installed client (`~/.huginn/huginn.sh` through bash, `huginn.ps1` through
  PowerShell), with the arguments unchanged. Found on stigo.

## [2.1.2] - 2026-10-15

### Fixed
- **Windows: an update no longer leaves the core stopped.** After a restart request the old core
  stops answering at once, but its process (and so its scheduled-task instance) can live a few more
  seconds while it closes its event streams. Task Scheduler ignores a start request for a task that
  is still running, so the one `schtasks /Run` sent in that window did nothing and the core stayed
  down until the task's next repeat. The update now waits for the old process to exit and repeats
  the start until the new core answers. Found on PRESTIGE: 2.1.1's own code left the core down; the
  fix restarted it in 9 s, twice, with the tray handing itself over each time.

## [2.1.1] - 2026-10-15

### Fixed
- **Updating from a 2.0.0 core restarts it even when its service manager cannot be reached.** A
  2.0.0 core has no restart request. When `systemctl --user` has no user bus in the shell, or
  Windows refuses `schtasks /End` to a non-administrator, the update now stops the old core by its
  process id. It is your own process, and its service starts the new binary. A core running a
  device job is left alone, and it picks up the new binary at its next restart. Found by updating a
  real 2.0.0 core from the published releases: before this fix it stayed on 2.0.0.
- `huginn core update` on a machine whose installed core is current but whose running core is
  still older now restarts it. It used to report "current" and leave the old one running.

## [2.1.0] - 2026-10-15

huginn core, phase 2: the tray and notifications, and updating the core in place.

### Added
- **`huginn core tray`: the core owns the tray icon and desktop notifications.** It starts at
  graphical login (an XDG autostart entry on Linux; a Startup shortcut carrying the app's
  notification identity on Windows). It shows the brand raven in three states (idle, working,
  needs you), a menu listing sessions waiting on you, and notifications. Linux uses
  `org.freedesktop.Notifications`; Windows uses toasts. Notifications work with the desktop window
  closed or the app not installed at all.
- The core follows appd's watch stream itself and decides what to notify. The rules are a port of
  the desktop's (all 33 test cases), so "needs you", "finished", "hit the usage limit" and the
  withdrawals behave the same.
- **The core claims notifications from appd only while a tray is attached and the session is
  unlocked**, so the Telegram fallback stays in charge when nobody is at the machine.
- `huginn core notify-test` posts one sample notification, to check delivery on a machine.
- **`huginn core update` takes the newest core release.** `huginn update` now also brings the core
  up to date, and so does `huginn core install` (and the one-line bootstrap) on a machine that
  already has an older core. The download is checked against the release's sha256, as at install.
  A prerelease such as 2.1.0-rc.1 counts as older than its release; a newer local build is left
  alone.
- **An update restarts the running core onto the new binary.** `huginn core self-install`, which
  every update path ends in, asks the running core to restart (`POST /core/restart`). A running
  device job finishes first. On Windows it then starts the scheduled task, and Task Scheduler's
  5-minute retry covers a start it is not allowed to make. The device id is kept.
- **A machine set up on 2.0.0 gets the tray when it updates.** The tray's login autostart is added
  once. If you remove it later, updates leave it removed. The tray also starts straight away when the
  update runs in your desktop session. It is never started from an elevated process.
- **A tray that is running when its core updates hands over to the new version** by itself. Only
  one tray runs per user.

### Upgrading from 2.0.0
- The 2.0.0 client cannot update the core by itself. Run `huginn update`, then **`huginn core
  update`** in a new shell. Installing desktop 2.1.0 also updates the core.

### Security
- Linux notification bodies are escaped before they reach the notification server. A body carries
  model output (a chat's last words), and servers that read markup (KDE, dunst, mako) would
  otherwise turn an `<a href>` in it into a clickable link, or an `<img src>` into a local file read.
- **A tray that cannot show a notification no longer takes notifications away from Telegram.** Two
  examples are a Linux session with no notification server, and Windows with no shortcut carrying
  the toast identity. The tray reports this when it connects and re-checks every minute.

### Fixed
- A tray started at login before the core had ever run read the missing runtime directory as
  "another tray is running", and left for the rest of the login.
- `client/get.sh` exited silently with no terminal (automation), and `client/get.ps1` stopped at
  its host-trust check on Windows PowerShell 5.1. Both were found by running the published
  one-liners.

## [2.0.0] - 2026-10-14

### Added
- **`huginn core`: a Go binary that runs this machine's huginn work in the background.** It starts at
  boot as you, with nobody signed in: a `systemd --user` unit plus linger on Linux, and a "Huginn
  Core" scheduled task on Windows (S4U by default, or `--logon password`). `huginn core install`
  downloads the binary from the newest `cli-v*` release, checks it against the release's
  `core-manifest.json` sha256, installs it per-user, starts it and pairs it with the host using the
  token read over ssh. Also `core status`, `core uninstall`, `core pair` and `core self-install`.
  See `docs/CORE-ARCHITECTURE.md`.
- **The device runner moved into the core.** With the core installed, `huginn device on|off|status`
  go to it and the Node runner is not used, so a machine never enrols twice. On first start the
  core adopts the device id from an existing Node runner or desktop install instead of creating a
  second row, and keeps its scope, root and act-while-locked settings.
- **One-line bootstrap:** `client/get.sh` (`curl … | sh -s -- <host>`) and `client/get.ps1`
  install the client from the newest `cli-v*` tag. They then ask whether to keep huginn running in
  the background and whether to install the desktop GUI.
- Releases carry the core binaries (`linux-x64`, `linux-arm64`, `windows-x64`, `android-arm64`)
  and `core-manifest.json`. They are built reproducibly by `core/build.sh`.

### Changed
- **The appd token is stored in a 0600 file by default, not the keyring.** A Linux login keyring
  is locked until someone logs in, and Credential Manager is likely unavailable to an S4U task.
  Both are exactly the moments a boot-time core needs the token. Set `HUGINN_SECRETS=keyring` to
  opt in to the keyring.
- **A headless machine counts as unlocked; a desktop at its login screen counts as locked.**
  Machines with no graphical seat (servers, LXCs) keep act mode, as with the Node runner. A
  desktop that booted with nobody signed in withdraws act unless "keep act mode while locked" is
  on.

### Security
- **The core refuses work whose model or effort value is not well-formed, and won't run a `.cmd`/`.bat`
  engine.** It allows effort only from appd's own levels, and model only as a plain identifier.
  On Windows, Go runs a batch file through `cmd.exe`, so a model value such as `opus&calc.exe`
  would have run a second command and gotten around read-only scope. That required a compromised
  host or edited chat state (scan F1, medium). An npm `claude.cmd` still works through `node cli.js`
  when `cli.js` sits beside it.
- **Remove this computer fails, instead of reporting success, when an installed core does not
  confirm it forgot its token** (scan F2). Press Remove again once the core answers.
- **`huginn core install --logon password` no longer puts the password on a command line.** The
  password reaches PowerShell on stdin, and the prompt does not echo it (scan F3).
- Documented: plain loopback HTTP has no peer check (docs/TLS.md, scan F4).
- **The Windows core is Authenticode-signed** (`scripts/sign-windows.sh`, by `core/build.sh` and
  `release-desktop.sh`, which refuses to release unsigned). Windows Defender's behaviour model
  quarantined the unsigned core as `Behavior:Win32/Persistence.A!ml` when it re-registered its boot
  task. The certificate is issued by the jnet.ad domain CA, so domain-joined machines trust it; a
  publicly trusted certificate is planned.

### Fixed
- **The device refusal text named the mode literally again.** Before this fix, a mode containing
  `$&` printed `{mode}`, because the Node runner and the policy generator used string
  replacement. They now match the Go and Kotlin runners byte for byte.
## [1.9.1] - 2026-10-14

### Fixed
- **The Arch install hint from `huginn desktop` works.** It printed `sudo pacman -U <url>`, but for a
  remote URL pacman also fetches `<file>.sig` and aborts on the 404. The packages are unsigned; the
  release manifest's sha256 is their integrity check. The hint is now
  `curl -fLO <url> && sudo pacman -U ./<file>`, because pacman installs a local file. Found while
  installing desktop 1.13.0 on an Arch laptop.

## [1.9.0] - 2026-10-14

### Added
- **`huginn desktop install`: a self-updating desktop install on any Linux, no root needed.** It
  downloads the portable build from the latest `desktop-v*` release and checks it against the
  release manifest's sha256. It refuses archive entries that are absolute, climb out with `..`, or
  link outside the app directory. It then installs per-user under
  `~/.local/share/huginn-desktop/` (`versions/<ver>`, an atomically swapped `current` link) and
  adds `~/.local/bin/huginn-desktop` and a menu entry that handles `huginn://`. Running it again
  repairs the install. If the desktop is installed as a distro package, it prints the removal
  command (`sudo pacman -R` / `sudo apt remove`) and never runs sudo itself. Settings in
  `~/.config/huginn-desktop-kt` carry over.
- **`huginn desktop uninstall`** removes that install, and only it. It refuses without the
  install's marker and keeps settings.
- **`huginn desktop` lists Debian, Arch and portable builds** and gives this machine's install
  command, detected from `/etc/os-release`: `sudo apt install ./….deb` on Debian/Ubuntu,
  `sudo pacman -U …` on Arch, and `huginn desktop install` everywhere. New bare-URL forms:
  `huginn desktop arch` and `huginn desktop portable`. On Windows, `huginn.ps1` has the same verbs
  and points to the installer.

## [1.8.0] - 2026-10-13

### Changed
- **One state table, shipped as data.** `server/lib/state-table.json` holds the seven state words,
  each with a Unicode glyph and a 7-bit mark: working `●`/`*`, needs you `▲`/`!`, waiting `○`/`o`,
  ok `■`/`+`, error `×`/`x`, info `▸`/`>`, shell `◦`/`-`. `scripts/gen-state-table.js` generates a
  reader for bash, python and node, and every renderer in `server/bin` reads from it, as does
  `huginn-claude-title`. A mark now means the same thing everywhere. The rounds listing no
  longer uses `!!` or `?`, and a live archived session is `*`, not `>`. `server/setup.sh`
  installs the table to `/usr/local/lib/huginn/`.
- **No shouting.** NEEDS YOU, RUNNING, DID NOT FINISH, TRANSCRIPT GONE, NOT reachable, MISSING,
  WATCHED, OFF, EXHAUSTED, FAIL, EXCEEDED and RED are now lowercase across the renderers,
  `huginn device status` and `huginn local status` / `doctor`. "needs you" still stands out by its
  mark and its place in the line.
- **One line grammar.** Each renderer's first line is `(o> huginn - <surface>`, followed by a
  blank line, the rows at a 2-space indent with details at 4, `  .  ` between fields, `->` for
  arrows, and a count line at the end. `huginn device status` puts its keys in one column.
  `huginn-status` lists sessions with `-` (was `・`). Em-dashes in host output that reaches
  PowerShell 5.1 became ` - `, and `huginn-projects --help` drops its ⚠. Both clients print
  `(o>` on the version line.
- **One help screen.** `client/help-table.txt` is the help for both clients. `scripts/gen-help.js`
  splices it into `huginn.sh` and `huginn.ps1`, and `scripts/test-client.sh` fails if either copy
  drifts. Both clients now list `devices watch` and word the device and local-AI rows the same
  way. PowerShell prints a blank line after the banner like bash does, every line fits in 80
  columns, the long `projects` rows put their description on the next line, and the footer says
  "working / needs you / waiting".
- **One colour guard.** In bash, `_huginn_color` checks the fd it writes to and NO_COLOR, and
  `huginn update` now colours the same lines PowerShell does. In PowerShell, every coloured line
  goes through `_Huginn-Say`, which checks `$script:HuginnColorOn` (NO_COLOR unset and output
  not redirected); `install.ps1` uses the same rule. Colours are the brand's slot pins: error
  brightRed, attention brightYellow, ok brightCyan (was green), muted brightBlack.
  `huginn-status` takes its slots from the state table. `_Huginn-Fail` now writes the plain
  message to stderr before it throws, so a refusal reads like bash's.
- `server/tmux.conf` carries the 16-colour fallback status bar as a commented alternative.

## [1.7.0] - 2026-10-12

### Security
- **`huginn-device` talks to appd over TLS and pins its certificate (needs appd 3.11).** Before
  this release a device runner sent the bearer token to a remote daemon in plain HTTP. It now
  uses `https://` on the same address and port and checks the daemon's self-signed certificate
  with the proof bound to the token (`docs/TLS.md`), without sending the token. Then it pins the
  certificate in `device.json`. The token goes only over a connection presenting a pinned
  certificate, and the check runs before the HTTP layer gets the socket at all. A changed
  certificate is proved again before it is trusted. If the proof fails, the runner stops with
  *"this address could not prove it is your huginn daemon; someone may be intercepting the
  connection"* and never falls back to plain HTTP. A daemon older than 3.11 is reported as such.
- **Remote `http://` addresses are upgraded to `https://`.** That covers the URL in an existing
  `device.json` or `local.json` (rewritten on load), `huginn device on --url http://…`, and the
  URLs `huginn device on` / `huginn local on` build from the ssh target. Loopback
  (`127.0.0.0/8`, `::1`, `localhost`) stays plain HTTP, which the daemon still answers there.
  `huginn device status` shows the pinned fingerprint.

## [1.6.2] - 2026-10-05

### Fixed
- **A `claude -p` run from inside a tmux session no longer takes over that session's state.**
  `huginn-claude-title` is a Claude Code hook, and a claude started by a Bash tool inside a tmux
  Claude session inherits `$TMUX`/`$TMUX_PANE`, so the hook resolved the same session name for
  both and wrote the child's session id and transcript into `/run/huginn-claude-state/<session>`
  on every hook event — the file flipped between two conversations every few seconds. appd
  re-reads it on every poll, so a client watching that session saw its history replaced by the
  child's and back (the "flicker"), the agent chips swap between two sessions' sets, and a 404
  for an agent that had been listed a second earlier, which both clients took for "needs appd
  3.0" and switched the strip off for good (their side is fixed in app 3.9.2 / desktop 1.10.1).
  The hook now walks up to the nearest `claude` ancestor and does nothing when that process
  carries `CLAUDECODE=1` — the mark a parent Claude Code puts on everything its Bash tool
  starts, and one a top-level claude never has. The hook's own environment could not tell the
  two apart: a top-level claude hands its hooks `CLAUDECODE=1` too. No claude ancestor found
  means not nested, so a host where the walk cannot run behaves exactly as before.

### Added
- **`scripts/test-claude-title.sh`** runs the hook under a fake top-level and a fake nested
  `claude` and asserts the state file is written for one and untouched for the other;
  `scripts/test-client.sh` runs it as a lane. It needs a tmux pane and skips loudly without one.
  `HUGINN_CLAUDE_STATE_DIR` points the hook at another state directory and
  `HUGINN_CLAUDE_TITLE_NESTED=0|1` forces the verdict, both for tests.

## [1.6.1] - 2026-10-03

### Added
- **`huginn devices watch <name> on|off`** marks a machine the host should expect to be always on.
  The daemon (appd 3.10+) then sends one Telegram statement when that machine has not checked in
  for ten minutes and one when it is back; `huginn devices` marks watched rows. Until now nothing
  said a device had gone.
- **`huginn device unit` on Windows prints the Windows shape** — a PowerShell snippet that registers
  a scheduled task running the headless runner **as your account, at boot, with nobody signed in**
  (Task Scheduler asks for the password once and keeps it; `ExecutionTimeLimit` is zero so it is not
  stopped on day three). From another machine: `huginn-device unit --windows --user DOMAIN\name
  --home C:\Users\name …`. Before this, `unit` printed a systemd unit on Windows and exited 0, and
  the only always-on path on a Windows box was the desktop app's Startup shortcut, which needs an
  interactive sign-in.

### Fixed
- **An npm `claude.cmd` shim on Windows runs.** Node refuses to spawn a `.cmd` without a shell; the
  headless runner now runs the `cli.js` the shim points at, so a device whose claude came from
  `npm i -g` no longer fails every job.

## [1.6.0] - 2026-10-02

### Added
- **`huginn attach <name>`** attaches to a session by name and never reads the name as a
  command. A session can be called `update`, `solo` or `list`, and `huginn update` will always
  mean the command. This form opens such a session, and the desktop's "Open in PowerShell"
  uses it. It folds case and checks the name like every other verb, and a bare
  `huginn attach` prints its usage instead of opening main. Both clients, with tab completion.

## [1.5.1] - 2026-09-19

The round-2 review's LOW findings on the CLI: help that was four different
things, a tab title that was overwritten rather than restored, a refusal that
described the name it had just refused as valid, and colour in a pipe.

### Fixed
- **`huginn <verb> --help` is one convention** — that verb's usage, exit 0,
  answered by the client with no ssh. It was four: `projects` and `llm` printed
  their full help over the wire, `headroom` printed a one-line usage on stderr
  and exited **1**, and `archive` answered `invalid session name '--help'`. A
  person types `--help` when something has already surprised them; two of those
  made it look like a second mistake. The entries are extracted from the help
  text itself, so there is no second copy to drift, and the host-side renderers
  (`huginn-archive`, `huginn-devices`, `huginn-headroom`, `huginn-rounds`,
  `huginn-status`) answer `--help` on stdout at exit 0 too, before they read the
  token.
- **The terminal tab title is put back, not overwritten.** The bash client set
  it to `$HOSTNAME` on the way out — a string that was probably never in that
  tab — while the README ("Restored when you leave") and USAGE both promised
  restoration. It now pushes the title onto the terminal's own title stack
  (`CSI 22 t`) before renaming and pops it (`CSI 23 t`) on the way out, and
  writes nothing at all when stdout is not a terminal. A terminal without the
  stack ignores both and keeps the session name.
- **`huginn status` honours `NO_COLOR` and a missing TTY.** `huginn status |
  cat`, a redirect and `$(huginn status)` all came back carrying `^[[36m`, and
  `NO_COLOR=1` — the variable that exists to say "do not" — was never read.
  Every sibling renderer is plain text already.
- **The invalid-name refusal says which rule was broken.** `huginn kill -box`
  and a 51-character name both got "use lowercase letters, digits, _ and -; no
  dots, spaces or *" — a sentence describing a name that is, by that text,
  valid. The dot, the first character and the 50-character cap are each named
  now, in the daemon's own order (`nameProblem`), in both clients.
- **`huginn llm --help` no longer prints a line of its own source** (`set -u`):
  the help was a line RANGE over the file header, and the header grew past it.
- **Completion:** `huginn projects <TAB>` completed to nothing in both clients —
  for the one verb whose whole grammar is arguments; the PowerShell completer
  had no `device` branch at all; `local` was missing `persist` (shipped 1.1.0);
  and no alias (`ls`, `st`, `mv`, `round`, `project`, `ccusage`, `unarchive`)
  was offered by either.
- **`huginn device on` / `local on` no longer dead-end when `$SSH_CONNECTION` is
  empty** — a shell running on the host itself, or an sshd wrapper that does not
  export it. The host is asked for an address it holds, loopback is used when
  the host *is* this machine, an explicit `--url` wins outright, and the refusal
  names `--url`. The PowerShell twin also stopped throwing outright when that
  probe returned fewer than three fields.
- **`install.sh` wires `~/.zshrc` and `~/.profile`, and `uninstall` knows all
  three.** A zsh login shell (every recent macOS) got an installed-but-invisible
  client, and then a leftover source line pointing at a deleted file. `~/.zshrc`
  is written when it exists or zsh is the login shell; `~/.profile` only when it
  already exists, and with a shell-guarded line, because `/bin/sh` cannot source
  a bash script.
- **huginn.ps1: the last refusal that was not one.** `_Huginn-FetchFile`'s
  failure path printed on the host stream and returned normally, so `huginn
  device update` with no `gh` and no mirror left `$?` True and `$LASTEXITCODE`
  0; the `local` caller tested that return value and then `return`ed, which is
  also a success. Both are terminating refusals now (the M3 remainder from
  1.5.0).
- **A revive restores into the `CLAUDE_DIR` the daemon was given** (daemon-side,
  `server/appd`). `restoreArchivedTranscript` and `findTranscriptFile` resolved
  `~/.claude/projects` from the process's home and ignored
  `HUGINN_APPD_CLAUDE_DIR`, so a private daemon — the CLI gate, the route
  suites, a review's own scratch daemon — wrote its fixture transcript into the
  **owner's real** transcript store, and the "does Claude Code still have its
  own copy?" guard asked about that store as well.

### Changed
- `huginn help` in both clients lists what actually ships: `device
  update|serve`, `--act-while-locked` (the whole of 1.4.0), `--url`, `local
  persist|doctor|update|unit`, `--json` on `projects` and `headroom`, and
  `llm`'s flags. The PowerShell help was also missing five whole verbs —
  `rounds`, `devices`, `device`, `local`, `llm` — that have shipped for
  versions.
- `docs/USAGE.md` and the README say what the tab title actually does, that
  `huginn status` drops colour for a pipe or `NO_COLOR`, that every verb takes
  `--help`, and that the CLI has **no route book on purpose** — it is an ssh
  front end with exactly one route, which is why a device enrolled from here and
  one enrolled from an app can hold different addresses for the same daemon.

## [1.5.0] - 2026-09-19

The round-2 review's CLI findings: both Projects write verbs could never succeed, and a refusal
from the PowerShell client was indistinguishable from success.

### Fixed
- `huginn projects new` sends the `kind` and the `brief` the daemon requires — it sent neither, so
  the verb failed 100% of the time ("kind is one of software, infra, …") and the only way to create
  a project was a phone or the desktop. New flags: `--brief TEXT|-` (the whole first message the
  lead gets; `-` reads stdin) and `--kind <software|infra|hardware|docs|research|other>`, default
  `other`. An unknown kind travels and comes back as the daemon's own sentence, so a newer daemon's
  kinds work without a client update.
- `huginn projects spawn` sends `{approve:true, manifestRev}` — the approval and the revision are
  the whole body the spawn route reads, and it used to post a `{members}` list it does not read
  with no approval at all, so every spawn answered "approve must be true — spawning is the owner's
  decision". The rev is read from the project immediately before, so a proposal the lead revised
  since you looked at it is refused rather than spawned. A 200 with nothing spawned is no longer
  reported as success.
- `huginn projects end` no longer orphans the lead or prints a remedy that cannot be run. The
  default now winds every member down through the daemon's own `?end=1` (the wrap-up phrase, then
  auto-end when idle) instead of deleting the record and leaving a real `claude` running with no
  project behind it; `--now` sends `?end=now` (it used to send the graceful one); `--keep-sessions`
  is the out-loud way to keep them, and names them. A member the daemon could not wind down is
  named with a `huginn kill <tmux name>` that resolves — the old line, "huginn projects end <name>
  --now", could never resolve, because the project had just been deleted from the list that verb
  reads.
- `huginn end` against an unreachable HOST says so and prints ssh's own sentence, instead of
  blaming a daemon that was never asked. ssh's failures exit 255; anything else is the remote
  command's code. Both clients. The `huginn-appd answered HTTP <code>` fallback in `end` and `kill`
  prints the code now — it read a variable set inside a `$( )` subshell and was always blank.
- **huginn.ps1: a refusal is a failure.** Every one was a `Write-Host` and a bare `return`, so
  `huginn kill 'bad name!'` and `huginn end nosuchsession` both left `$?` True and `$LASTEXITCODE`
  0 while the bash twin returned 1 — any Windows script, CI step or `&&` chain read a failure as a
  success. Refusals are terminating errors now (catchable with try/catch), set `$LASTEXITCODE`, and
  land on the ERROR stream where `2>$null` and `$out = …` can reach them. `$LASTEXITCODE` is reset
  at the start of every verb, so a success no longer leaves the previous refusal's 1 behind.
- `huginn update` names the mirror it actually pulls from. Both clients said "falling back to the
  $HUGINN_HOST mirror" — the one host this path deliberately never uses — and then reported pulling
  from the pinned one.
- `huginn local plan` / `local on` no longer refuse "not enough disk" on a machine with no
  `~/.config`: the free-space probe walks up to the first ancestor that exists instead of reading
  "unknown" as zero. `plan` is aimed squarely at a machine that has installed nothing, and on
  Windows `%USERPROFILE%\.config` typically does not exist yet.

### Changed
- `spawn` no longer takes `<member>:<role>` pairs or `--prompt`. The members are the LEAD's plan
  and spawn is the owner approving it; a client that re-sent the roles would be a second opinion
  about the plan on screen. The old spelling is refused by name.
- Help in both clients: session names may contain `-` (true since 1.3.0, and the refusal text
  already said so), the pinned update mirror is named instead of printing a blank, the Projects
  block matches the grammar the renderer accepts, and `huginn -p` no longer claims it "does not
  auto-approve tools" — it auto-approves four and denies four.
- `docs/USAGE.md`, which the README calls the full reference, gains every verb shipped since 0.12
  (`end`, `archive`, `revive`, `rounds`, `devices`, `device`, `local`, `llm`, `projects`,
  `desktop`, `--json`), a Projects section, and the real `-p`/`-y` tool grants as a table. Its
  environment table gains `HUGINN_UPDATE_HOST`, `HUGINN_DEVICE_DIR`, `HUGINN_DEVICE_REFRESH`,
  `HUGINN_LOCAL_DIR`, `HUGINN_LOCAL_REFRESH` and the host-side `HUGINN_APPD_URL`.
- README says out loud that the CLI is an ssh front end that runs host-side against
  `127.0.0.1:8787`: that is why the token never reaches the client, and why driving a second host
  means ssh'ing to it rather than configuring an address.
- `scripts/test-client.sh` creates a project, approves a spawn and ends it against a REAL bare
  daemon from this tree (its own HOME/DATA/STATE/CLAUDE dirs, a stand-in `claude`, a private tmux
  socket, no telegram or FCM), and the stub lane asserts the request BODIES. Neither HIGH above
  could have been green under it. ⚠ `env -i` makes tmux rewrite every TAB in its `-F` output as
  `_`, which makes the daemon read every session as unaddressable — the environment is inherited
  and overridden instead.

## [1.4.0] - 2026-09-18

### Added
- `huginn-device --act-while-locked` / `--no-act-while-locked`, stored as `actWhileLocked` in
  `device.json` and reported on enrolment and on the beat. Off by default; inert on a headless box,
  which has no screen to lock, and `status` says so.

## [1.3.0] - 2026-09-18

Projects on the command line, and nine edge-case fixes.

### Added
- `huginn projects` — the host's clusters of sessions, their members and who is waiting, rendered
  host-side by `huginn-projects`: `list`, `show <id|name>`, `new <name> [--cwd]`,
  `spawn <project> <member>:<role> [--prompt …|-]`, `msg <project> <from> <to> <text>`,
  `end <project> [--now]`, plus `--json`. Exits 2 when appd is not answering, 3 when appd has no
  Projects (an older daemon, said in one line), and 1 when a spawn only partly started.
- `huginn end <name> --force` sends the force the daemon's own 409 asks for.

### Changed
- Session names: `-` is accepted by huginn.sh, huginn.ps1 and `cc` (the rule is
  `^[a-z0-9_][a-z0-9_-]{0,49}$`, case folded, dots banned), so a session named `build-box` is no
  longer listed but unopenable; a refused name that exists on the host says so.
- `huginn end` and `huginn kill` print the daemon's own refusal instead of guessing that appd is
  down; `kill` falls back to raw tmux only when the daemon could not be reached at all.
- `huginn local --system`, `update` and `persist` fall through to sudo when pkexec cannot
  authenticate at all; a dismissed pkexec dialog still stops there.
- `huginn local off --purge` after a plain `off` finds the system units it really installed.

### Fixed
- huginn.ps1: `huginn device on|update` and `huginn local on|update|plan` fall back to the pinned
  mirror when gh returns an error body, and write downloads with `Set-Content` instead of `>`
  (Windows PowerShell 5.1 wrote UTF-16 that node could not parse).
- An ssh path over IPv6 no longer builds an unusable url; huginn-device names an address it cannot
  dial instead of saving it and retrying forever.
- huginn-device says "the local engine" instead of blaming claude, keeps the line of stderr that
  names the missing file, and stops delivery once on a 401 instead of re-posting every 500 ms.
- test-client.sh stubs bind port 0, so a gate run cannot pass or fail on somebody else's listener.

## [1.2.0] - 2026-09-17

Archive and revive.

### Added
- `huginn archive` lists what has been archived and how to bring each one back;
  `huginn archive <name> [--now]` ends a session for good and keeps that card;
  `huginn revive <id|name>` brings it back under its old name. Rendered and called on the host
  (`huginn-archive`), so the daemon's own refusal — "answer the waiting question first" — reaches
  you word for word.

## [1.1.0] - 2026-09-15

Local AI that keeps serving after you log out.

### Added
- `huginn local on --system` installs systemd **system** units behind ONE elevation prompt
  (pkexec, then sudo) on Linux, so a serving machine keeps serving with nobody logged in — the
  shape Windows already had. Elevation declined or unavailable falls back to a user unit kept
  alive by `loginctl enable-linger`, and says which one you got; linger is now attempted on every
  Linux user-unit install (it was never touched before).
- `huginn local persist` migrates an existing user-unit install to system units, or enables
  linger where there is nothing to elevate with.
- `--adapter llama-swap` adopts an externally installed llama-swap behind the same loopback and
  keyless-completions gates. `off` never stops, uninstalls or de-keys an adopted engine; adoption
  is refused on top of a managed install.
- `huginn-device` reports whether this machine serves while logged out (`persistent`), at
  enrolment and on its heartbeat, so Devices can tell "always on" from "only while someone is
  logged in".

### Changed
- `status`, `status --json` and `plan --json` report unit type, linger, persistence and adoption
  truthfully; `doctor` now **fails** (exit 1) on a user unit with no linger.

### Fixed
- Adapter mode never recorded the unit scope, so an adapter runner installed with `--system` was
  later looked for on the user bus: `status` said "not installed" about a running service and
  `off` left it running.

## [1.0.0] - 2026-09-15

Huginn 3 / CLI 1.0.

### Added
- `huginn headroom` — the fullest limit per saved account, what the daemon is holding or has moved,
  and why it last did nothing. Rendered on the host like `rounds` and `devices`.

### Changed
- All version stores move to 1.0.0 together (bash, PowerShell, the device runner and the two
  satellites); `scripts/test-client.sh` asserts the set.

## [0.14.0] - 2026-08-31

Fixes from the 2026-08-30 program-wide audit.

### Fixed
- The Windows installer creates a real passphrase-**less** SSH key on PowerShell 7,
  which had been receiving a literal `""` two-character passphrase (PowerShell 7.6 drops
  an empty `''` argument and forwards `'""'` verbatim), silently breaking passwordless
  SSH. It now answers ssh-keygen's prompts with empty lines over stdin instead of passing
  `-N`. (Verify: `ssh-keygen -y -f <key>` prints the public key with no passphrase prompt
  when the key is unprotected.)
- `huginn llm` prompts survive quotes, newlines and `$`/backticks on Windows
  PowerShell 5.1 — the prompt now rides base64 to the remote `huginn-llm -`.
- `huginn-llm` cancels a still-running turn before deleting its chat on timeout, so a
  slow answer no longer leaks the chat and leaves the generate run burning.
- The device runner stops an engine that wedges at startup (no output at all) without
  killing a run that is legitimately quiet inside a long tool, and honors a Stop
  delivered on the heartbeat.
- The local-model shim addresses an adapter backend by its real model name (an Ollama or
  LM Studio model had 404'd on every call), and no longer hangs for two minutes when a
  backend drops the connection mid-error.
- `huginn local on` enforces the documented keyless guardrail (a keyless completion must
  be refused) and persists the enrolment identity before starting the runner service.
- `huginn uninstall` reports the device directory removed only when it is actually gone.

### Changed
- `huginn -p` help no longer calls the one-shot "read-only" (it obeys your Claude
  permissions; see USAGE.md).

## [0.13.0] - 2026-08-27

### Added
- `huginn uninstall [--all] [--yes]` on both shells, behaviourally identical:
  local tier off, device off, `~/.huginn` and the installer's profile line
  removed, with a summary of what went and what deliberately stayed. The SSH
  stanza and key survive by default; `--all` takes them only when the key is
  provably huginn's own.

### Changed
- `huginn-device off` removes the bearer token with the id — server first,
  disk second, and it still refuses to lose the only handle when the DELETE
  fails; `--force` clears a machine the server cannot hear and names the row
  it strands. `huginn local off` stops leaving credentials behind; `--purge`
  removes the whole tier, models and services included.

### Fixed
- An unset `%ProgramData%` no longer throws anywhere a path is built.
- A value on a value-less flag (`--purge-models=false`) is refused instead of
  read as truthy — which would have deleted the models it named to keep.
- The device directory is reported "left (not empty)" when `rmdir` declines
  it, instead of claimed as removed.
- Both clients are pure ASCII again (em-dashes had crept into comments — a
  live PowerShell 5.1 code-page hazard on `scp`-fetched files).

## [0.12.7] - 2026-08-26

### Added
- `huginn llm "prompt"` — one question to the LOCAL TIER, answered by a
  serving machine's model, never Claude. Renders on the host (`huginn-llm`),
  where the token lives; `huginn llm -` reads the prompt from stdin so a
  file can be piped in for summarising. The chat it rides in is deleted
  either way; `--keep` (host-side) preserves it for debugging. This is the
  delegation half of the Kvasir conduits: huginn-side agents use the same
  tool.

## [0.12.6] - 2026-08-25

### Changed
- The device runner reports which MACHINE it is at enrolment. One box wearing
  two credentials (claude work + local-AI serving) now presents as one device
  with capability facets everywhere devices are listed; authority still lives
  per-row and is unchanged. Daemons from 2.76.0 emit the key and derive it
  for older runners, so every pairing keeps working.

## [0.12.5] - 2026-08-25

### Fixed
- Fetched code (the device runner, the local manager and shim) is
  syntax-checked before install, and the check ran on a `.tmp`-named
  download — which modern Node (22+) refuses to even parse
  (ERR_UNKNOWN_FILE_EXTENSION), so a machine with current Node could not
  fetch any of them. Reproduced on node 22.23.1 the day the first Node-24
  machine hit it. Downloads are checked under a `.js` name now; installed
  names are unchanged.

## [0.12.4] - 2026-08-25

### Changed
- The "needs node" refusals in `huginn device` and `huginn local` no longer
  claim that any machine running claude has node — the NATIVE claude build
  ships without it, which the first local-tier field test promptly proved.
  Both clients now name the install step instead (Node.js LTS; on Windows:
  `winget install OpenJS.NodeJS.LTS`).

## [0.12.3] - 2026-08-25

### Added
- `huginn local plan` — everything `on` would decide (hardware class, models,
  the disk gate, the device name) with nothing `on` would do. `--json` is the
  machine shape: it is how the desktop app's new set-up flow shows a real
  consent card before a human clicks anything.

### Changed
- On Windows, `huginn local on`/`off`/`update` now refuse UP FRONT from an
  unelevated shell — the services are LocalSystem, and discovering that after
  a multi-GB download was the worst place to. Verified downloads are kept
  either way, so a refused run loses nothing and a re-run resumes.
- The local-tier fetch now carries the device runner too: a machine that had
  never enrolled as a claude device could reach enrolment with no runner to
  install, and died there on a bare spawn error. The manager also names that
  condition honestly instead of failing late.

## [0.12.2] - 2026-08-25

### Changed
- Chat models are served with reasoning OFF (verified live on the pinned
  build): a quick local chat answers, it does not think out loud. Existing
  installs pick this up on their next update or activation.
- The installers now mention the optional local tier in one closing line.

## [0.12.1] - 2026-08-25

### Fixed
- **`huginn local on` no longer races two enrolments into two device rows.**
  The runner service was started before the manager enrolled, and the
  service's own first poll-loop enrolment landed a second registration — the
  second saw the first slug as taken and hash-suffixed itself. Found live on
  the first real install. Enrolment now happens before the service starts, in
  both managed and adapter mode.

## [0.12.0] - 2026-08-25

### Added
- **`huginn local` — serve local AI models to huginn from this machine.** The
  optional local tier: `huginn local on` detects the hardware class, gates on
  disk, downloads a PINNED llama-server + llama-swap (sha256-verified, loopback
  only, api-key minted), pulls the pinned models, installs the services
  (systemd on Linux, WinSW on Windows — alive with nobody logged in), and
  enrols a second device row at scope `generate`. `off|status|unit|update|
  doctor` complete the verb; `--adapter ollama|lmstudio` wraps an existing
  loopback endpoint instead of installing anything. The pins are embedded in
  the manager by `scripts/gen-local-manifest.js`, so a runtime or model bump
  is by construction a reviewed release of this client.
- **`huginn-llm-shim`** — the generate engine: the claude-CLI stream-json
  contract on its outside, one authenticated OpenAI-style endpoint on its
  inside. No tool surface by construction; failures are honest result frames;
  a 120s no-token watchdog; sessions capped and pruned; reports
  `version+contenthash`.
- The device runner spawns `conf.llm` through node for generate work, sends a
  display-only model catalog at enrolment, and **aborts a generate enrolment
  the daemon does not echo back** — an old daemon would silently file it as a
  read-only claude device.

## [0.11.0] - 2026-08-25

### Added
- **The `generate` scope** — the exclusive rung for machines that serve local
  models (the local-AI tier). A generate device runs ONLY generate work and
  generate work runs ONLY on a generate device; rank ordering alone would have
  let a claude device answer a local-model request. A granted generate argv is
  the bare stream flags plus the request: no tool flags, no persona, no effort.
  The headless runner refuses generate work unless a local engine is configured
  (`conf.llm`), and never falls through to claude.

### Changed
- **Junk scopes floor to `generate`, not `look`** — a scope nobody can reason
  about now runs nothing a claude device runs. An absent scope still enrols at
  `look`. `huginn-device on` refuses unknown scopes loudly instead of flooring
  them, and refuses `--scope generate` (that enrolment is created by `huginn
  local on`, arriving with the local tier).
- **A refusal blames the lock only when unlocking would help.** A locked
  look-scope device asked to act now names the scope, not the lock.

## [0.10.3] - 2026-08-25

### Fixed — one oversized line could lose a remote run's entire answer

A device runs claude with `--output-format stream-json --verbose
--include-partial-messages`, so a SINGLE line can carry a whole tool_result or a
whole final answer — a Read of a big file, a chatty Bash, a long report. Over the
daemon's body cap the POST came back 413, and:

1. the **whole batch** was lost, not just the oversized line;
2. if the terminal frame was in that batch it could **never** be delivered, because
   the retry is identically 413 — so the chat sat `running` forever and the machine
   was blocked from every other job;
3. the chat was then blamed on "no word for 5 minutes".

Silent, permanent, and reserved for the runs with the most to say. The runner now
keeps itself under the limit rather than discovering it: no batch is ever built too
large, an oversized line is **shrunk in place** (keeping its event type, with a
marker where the content was) rather than dropped, and **the terminal frame is
posted alone**, so whatever else happens the ending can land. The daemon's events
route also accepts a megabyte now, which helps a runner too old to know better.

- **A permanent rejection is no longer retried forever.** Any error re-queued the
  batch, so after a daemon restart — when the run is simply gone — the runner
  hammered a 404 twice a second for as long as its child lived. A 400/403/404/413
  now ends the job.
- **The runner is importable**, so the size rules can be tested at all. Until now the
  one part of the file that could silently destroy output was the part nothing could
  reach without starting a runner; `scripts/test-client.sh` drives it directly.

## [0.10.2] - 2026-08-25

### Fixed — the headless runner, which had no gate at all

Every one of these has the same shape: the runner reported success, or the wrong
reason, on a machine with **nobody sitting at it**. That is the one place a
misleading message costs the most, because there is no human to notice the advice
is useless. `client/huginn-device` now has a section in `scripts/test-client.sh`.

- **The generated systemd unit dropped `HUGINN_DEVICE_DIR`.** It relocates BOTH
  `device.json` and `appd-token`, so `HUGINN_DEVICE_DIR=/etc/huginn huginn-device on`
  followed by the very next line the tool prints installed a service reading
  `~/.config/huginn`: no config, no token. `serve()` treats that as transient and
  loops at 15 s forever, so the process never exits, `Restart=always` never fires,
  and **systemd reports the unit perfectly healthy while it does nothing at all**.
  The unit pinned `HOME` with a six-line comment about why a wrong one is fatal,
  and then dropped the variable that moves the same two files.
- **A missing work root was reported as "claude is not on this machine's PATH".**
  `spawn()` raises ENOENT for a missing executable AND for a missing cwd, and the
  handler assumed the first. So a deleted root made every job on the device fail
  forever, with the one instruction the operator had already followed. The cwd is
  checked before the spawn now, and the PATH message no longer prints the machine's
  private config path into a chat that syncs to a phone.
- **`huginn device off` said "Removed from huginn." and exited 0 when the DELETE
  failed**, having already thrown away `conf.id` — the only handle that could ever
  remove the row. So exactly when someone decommissions a machine (host asleep, VPN
  down, wrong url) the device stayed enrolled, a restart enrolled a SECOND row for
  the same box, and the stale one kept being offered work for thirty days. It now
  keeps the id, says what went wrong, and exits non-zero so a script can tell.
- **Every token-file problem read as "no appd token — put one in <path>"**, pointing
  at a file that was, in most cases, sitting right there. EACCES, EISDIR, an empty
  file and a file holding one captured newline are now named individually. The
  newline case is the nasty one: `huginn.sh` guarded the fetched token with `[ -s ]`,
  which a newline passes, so a botched `ssh huginn 'cat /etc/huginn-appd/token'`
  installed cleanly and then the runner insisted no token existed. That guard now
  requires non-whitespace content.
- **`huginn-device on` silently ignored `--flag=value` and unknown flags.** The
  parser matched only the space-separated form by exact string equality and the
  if/else chain had no else, so `--scope=own --root=/srv/build` enrolled at the
  default scope with no root and printed `Enrolled flagbox as "work"` — the word
  root never appeared in the output. Both forms are parsed now and anything
  unrecognised is refused with exit 2.
- **The host and the machine disagreed about where work runs.** `root` is only
  honoured at `work` scope (an `own` run starts in the account home), but it was
  accepted, stored and advertised regardless, so `huginn devices` on the host named
  a directory that ran nothing. Both ends changed: the runner says so at enrolment,
  and the host's line says where work actually starts.

## [0.10.1] - 2026-08-24

### Fixed
- **`huginn-device unit` printed the wrong shape of unit for the machines it is for.** It only
  offered a systemd *user* unit — one person's session, which needs `loginctl enable-linger` to
  survive a logout and is not up until somebody has logged in at least once. That is exactly
  wrong for a headless box, which is the whole reason the headless runner exists. `unit --system`
  now prints a system unit, and the user variant says which one you probably want and why.
- Both units set `HOME` explicitly. A system unit derives it from `User=`, which is right until
  somebody changes `User=` — and the runner finds *both* its own config and Claude's credentials
  under it, so a wrong HOME is a machine that enrols and then cannot log in. That reads as a
  broken Claude login rather than a wrong unit file, which is a long afternoon.

## [0.10.0] - 2026-08-24

### Added
- **`huginn device` — this machine, offered to Huginn.** `huginn devices` (plural) is the host's
  list of machines; `huginn device` (singular) is the one you are typing on. `on` enrols it,
  `off` withdraws it, `status` says what it offers and what the daemon sees, `unit` prints a
  systemd unit. This is the headless half of the desktop app's "Give Huginn access to this PC"
  toggle — for a server, a Pi or a build box, which has no desktop app and nobody sitting at it.
  - Enrolment takes the bearer token and the daemon's address over the **ssh link this machine
    has already been trusted on**. Nothing is widened: anyone who can ssh to the host can read
    that token anyway. What it removes is a credential pasted by hand between two terminals.
  - The address comes from `$SSH_CONNECTION`'s third field — the one this machine *just* reached
    the host on — rather than guessing on its behalf between a LAN address, a tailnet name and
    whatever `hostname` says.
- **`client/huginn-device`** — the runner. Node, no dependencies, in the daemon's own style. Node
  because a device must hold a long poll open, stream a child's stdout, batch it upward every
  half second *and* notice a cancel arriving in the reply to one of those batches; shell can be
  beaten into that shape, but not into one anybody should debug at 3am. The dependency is free:
  `claude` is itself a Node program, so any machine that qualifies as a device already has it.
  Fetched on demand rather than carried inside `huginn.sh`, because most devices are clients and
  never offer themselves — and validated with `node --check` before it is installed, since a
  truncated download that a service then restarts every ten seconds is worse than no runner.

### Changed
- **One policy, three programs.** What a remote request may do to a machine now lives once, in
  `shared/device-policy.json`. The Kotlin runner in the desktop app reads a generated table, the
  headless runner has the policy compiled into it by the same generator, and the daemon takes
  the scope lattice from it too. `scripts/gen-device-policy.js` expands all of that plus
  `shared/device-policy-cases.json` — every `(scope, locked, mode)` and the exact argv it must
  produce. **Both runners are asserted against the matrix rather than against each other**, since
  two implementations can be wrong in the same way and a table a person can read cannot be wrong
  quietly. The failure this prevents is silent: a runner that granted `Bash` where the policy
  says `look` would behave perfectly right up until the day it mattered.
- A headless machine reports **no lock state**, because the honest answer is that nobody is ever
  sitting at it. The desktop runner drops to read-only while the screen is locked; there is no
  equivalent here and none is faked. So the scope in the config file is the scope in force at
  3am — which is why the default is `work` and not `own`, and why `huginn device on` says so out
  loud when you widen it.

## [0.9.0] - 2026-08-24

### Added
- **`huginn rounds` and `huginn devices`.** The phone and the desktop could both see the host's
  scheduled work and the machines enrolled to run chats; from a terminal neither existed. On a
  product whose premise is that the terminal is the real surface, that was the wrong gap to
  leave open.

  `rounds` prints each round's cadence, when it next goes out, what it last found and the items
  it raised -- including **DID NOT FINISH** when a run admitted it missed its goal, which is the
  one fact a cheerful headline can hide. `devices` prints each machine's platform, its enrolled
  scope and what it will actually do right now (they differ while a machine is locked), whether
  it is reachable, and the folder a `work` run starts in -- labelled plainly as not a sandbox.

  **Both are rendered ON THE HOST**, by `huginn-rounds` and `huginn-devices` beside the existing
  `huginn-status`, and both clients just run them over ssh. That keeps one implementation of
  what a round looks like instead of one per client -- these two files have already drifted over
  a single version constant, and this has far more fields to drift over. It also keeps the
  property that the bearer token never leaves the host: the clients reach appd by ssh'ing here
  and reading `/etc/huginn-appd/token` locally, and nothing about these verbs teaches a laptop
  a new credential.


## [0.8.3] - 2026-08-16

### Added
- **`huginn desktop` — the download link for the latest Huginn Desktop build.** Prints the
  version, both platform installers with their size and sha256, and marks the one this machine
  can run; `huginn desktop windows` / `huginn desktop linux` print that url bare, so it composes
  (`curl -fLO "$(huginn desktop linux)"`). Until now the only way to get the installer onto a new
  machine was to know the tag naming and hand-build a GitHub URL.

  The link points at the **GitHub release** (`desktop-v<version>`), which is where the installed
  client's own updater fetches from — so the link and the self-update path are the same source
  and cannot drift apart. Filenames are read from the release's `manifest.json` asset (the sha256
  authority the updater verifies against), not guessed from a naming convention. It is
  deliberately **not** the daemon's `/v1/desktop-kt`: that serves the same bytes, but every route
  on it needs the host's bearer token and a browser cannot send one. So this is also the one verb
  that works from a machine that cannot reach the host at all — a GitHub fetch, no ssh.

  Tag-filtered rather than read from `/releases/latest`: four components publish into one feed
  (`v*`, `app-v*`, `appd-v*`, `desktop-v*`) and "latest" is whichever shipped last, usually not
  the desktop.

## [0.8.2] - 2026-08-14

### Security
- **`huginn update` on Windows no longer fetches replacement code from `$HUGINN_HOST`.** The scp
  fallback pulled `huginn.ps1` from whichever host the client was pointed at, and that file is
  then loaded into the shell — so `HUGINN_HOST`, which answers "which box do I drive", was also
  answering "whose code do I run". A typo, a second host or a test alias silently became a code
  source. It now uses the pinned `HUGINN_UPDATE_HOST` (default `huginn`) and announces the host
  when overridden. This shipped for `huginn.sh` in 0.6.1; the PowerShell client never got it.
  `test-client.sh` now asserts both clients pin it, so the two cannot diverge again.

## [0.8.1] - 2026-08-14

### Added
- **`huginn -p` and `-y` can now use the host's skills.** Both modes grant `Skill`, so a
  one-shot query can load `remote-ops`, `incident-triage`, `mempalace-ops` and the rest
  instead of re-deriving what they already document. A skill is markdown instructions, not
  a capability: invoking one cannot exceed the tools already granted, and `-p` keeps its
  deny list (`Bash Edit Write NotebookEdit`), so the ask/act line has not moved.
  Skills are cwd-scoped and resolve because the host runs these in `$HUGINN_WORKDIR`
  (`/root/netplan`) — verified by a headless run outside the project seeing none of them.

## [0.8.0] - 2026-08-14

### Added
- **`huginn end <name>` — a soft end.** Asks Claude to wrap up and commit, and (when
  auto-end is on for the host) ends the session once it goes idle. `huginn kill` remains
  the hard end. This is a daemon feature — it types into the pane and watches state — so
  there is no tmux fallback.
- **A shared appd reach helper in both clients.** The bearer token is root-only on the
  host, so the call runs there over the ssh alias and only the result comes back; the
  token never touches a client device.

### Changed
- **`huginn kill` now prefers the daemon's `DELETE /v1/sessions/<name>`**, which also
  clears the orphaned `/run` state file and releases the pane lease that a bare
  `tmux kill-session` leaves behind (Claude's SessionEnd hook never fires on a kill).
  Falls back to tmux when the daemon is unreachable — kill must work even when appd is down.
- **Windows parity.** `huginn.ps1` gains `end`, the appd helper, the DELETE-preferring
  `kill`, and the ask/act tool fence, so both clients expose the same verbs at the same
  version. The two version constants and this changelog head are now the release gate.

### Fixed
- **`huginn -p` never actually denied anything.** The deny-list was assembled into a
  remote shell variable and then expanded unquoted, so `claude` received `'Bash`, `Edit`,
  `Write`, `NotebookEdit'` — literal quote characters, no valid tool name — and the fence
  the code documents as "the real fence" was inert. `--allowedTools` only auto-approves,
  so a read-only `-p` query could still be granted Bash. The flag is now built client-side
  and interpolated so its quoting is bash syntax on the host. Anyone who ran
  `huginn update` after the soft-end work landed has the broken form; update again.

### Infrastructure
- **A GitHub release pipeline** (`scripts/github-release.sh`): one repo, four
  independently-versioned components, so tags are namespaced — `vX.Y.Z` is the
  CLI/server core, `app-vX.Y.Z` the Android app, `desktop-vX.Y.Z` the Compose
  desktop client, `appd-vX.Y.Z` the daemon. Notes are cut from the component's
  changelog section (a release with no changelog section is refused); artifacts
  attach with display labels; runs entirely locally via `gh` — no Actions.
  `ship.sh` and `release-desktop.sh` now mirror every release to GitHub
  automatically (best-effort — a GitHub hiccup never fails a shipped release;
  `HUGINN_NO_GH_RELEASE=1` skips).

## [0.7.1] - 2026-08-10

### Changed
- **No hardcoded deployment values anywhere in the tree** (part of preparing the repo
  to go public). The one-shots' remote working directory now follows the host's
  `$HUGINN_WORKDIR` (default `$HOME`) instead of a hardcoded project directory; `cc`'s
  default matches. `huginn-status`'s memory-node probe is opt-in via
  `HUGINN_MEMPALACE_HOST`/`HUGINN_MEMPALACE_MARKER` and prints nothing when unset.
  Alongside (appd 2.55.0): the daemon's working directory, memory-node probe, and
  out-of-band alert script are environment-driven (`HUGINN_APPD_WORKDIR`,
  `HUGINN_APPD_MEMPALACE_HOST`/`_MARKER`, `HUGINN_APPD_TELEGRAM_SCRIPT`) with generic
  defaults — a deployment's real values belong in a systemd drop-in
  (`server/appd/systemd.d/`). `ship.sh` reads its publish target from the environment
  or a gitignored `.shiprc`; `release-desktop.sh` and `deploy.sh` derive the daemon
  address from `tailscale ip -4` instead of a literal.

## [0.7.0] - 2026-08-10

### Added
- **The raven.** Huginn's brand is now one dark raven mark everywhere — and the CLI
  got its share: a small ASCII raven on the help screen in both clients. The canonical
  vector lives at [`assets/brand/raven.svg`](assets/brand/raven.svg); the Android and
  desktop apps shipped matching icons the same day (launcher/status-bar, window/taskbar/
  tray/installer).

### Changed
- The two clients' version numbers are aligned again (`huginn.ps1` had drifted to
  reporting 0.6.0 while `huginn.sh` was 0.6.1).

## [0.6.1] - 2026-08-10

### Fixed
- **`huginn update` no longer trusts whichever host you point at.** The `scp` fallback
  used to fetch replacement *code* from `$HUGINN_HOST` — a user-settable variable that
  answers "which box do I drive", routinely repointed at test boxes or mistyped. Fetching
  now comes from a **pinned** `$HUGINN_UPDATE_HOST` (default: the `huginn` alias), which
  never follows `$HUGINN_HOST`; overriding it must be deliberate, and the update names the
  host it trusted on every run.

## [0.6.0] - 2026-07-24

Fixes found by an adversarially-verified review of the huginn node.

### Fixed
- **Exact tmux targets (`-t =name`).** tmux resolves targets by exact match, then
  *prefix*, then glob — and a unique prefix resolves silently, so `huginn kill andvari`
  could destroy a session named `andvariautofill`. Kill, rename, and the reconnect
  client-count all anchor with `=` now; a typo fails loudly instead of hitting a
  neighbor.
- **Reconnect no longer resurrects a killed session.** Killing a session from one device
  while another's reconnect loop was mid-backoff used to recreate it (with a brand-new
  `claude` quietly burning quota). The loop checks `has-session` first and exits cleanly
  when the session is gone.
- **Fast-fail on instant failures**, bounded by duration rather than exit code: three
  consecutive sub-5-second exits stop the loop and point at the real error instead of
  scrolling it away with endless "link dropped" retries.
- **Safer self-update in both clients**: syntax-check before install, `.bak` of the
  previous version, `gh` exit code checked, no BOM written on the PowerShell path,
  `scp -o BatchMode=yes` so a keyless device fails instead of prompting mid-update.
- PowerShell 5.1: `-p`/`-y` one-shots base64-encode the remote script (5.1 mangles
  embedded quotes when marshalling native arguments, which silently dropped the persona
  *and* the tool grant).
- Session names validated on `kill`/`rename`; the bash reconnect backoff no longer
  overshoots its 15s cap.

### Changed
- Jittered reconnect backoff (±25%) — every tab shares one tunnel, and an unjittered
  schedule made all of them re-handshake on the identical second after a relay flap.
- `ConnectTimeout=10`, so an unreachable host reports promptly instead of appearing
  frozen for minutes.

## [0.5.0] - 2026-07-01

### Added
- **Date-shortcut keywords for `huginn usage`.** `huginn usage today | yesterday | week | month`
  expand to the right `ccusage -s/-u` date range without hand-typing `YYYYMMDD` — e.g.
  `huginn usage today` instead of `huginn usage -s 20260701`. `week` is a rolling 7 days,
  `month` is month-to-date. An optional report-type word after the keyword switches the
  grouping (`huginn usage week session`, `huginn usage month blocks`); default is `daily`.
  Date math runs server-side via GNU `date`, so behavior is identical regardless of the
  client OS/date flavor. Tab completion (bash + PowerShell) offers the new keywords after
  `usage`/`cost`/`ccusage`.

## [0.4.1] - 2026-06-23

### Fixed
- **Session names are case-insensitive.** `huginn Test` and `huginn test` now resolve to
  the same tmux session instead of silently creating two. Names are lowercased before any
  tmux-facing call in both clients, and host-side `cc` lowercases too as a backstop for
  older clients.

## [0.4.0] - 2026-06-18

### Added
- **Live session-state in the tab title.** The terminal tab now shows a state icon in
  front of the session name that tracks what Claude is doing, so across many tabs you
  can see at a glance which session needs you:
  - 🔄 **working** — a prompt is being processed / a tool is running
  - ✋ **needs you** — stopped for a permission or input request
  - ✅ **waiting** — turn finished (or just launched), waiting for your next prompt

  Driven entirely host-side by Claude Code hooks (`server/claude-hooks.json` →
  `huginn-claude-title`). State is pushed to the tab by wrapping an OSC-2 title in tmux's
  passthrough escape (needs `allow-passthrough on`, now shipped in `tmux.conf`), which
  tmux forwards to every attached client — so a mirrored phone and desktop both update.
  `setup.sh` installs the hook script and merges the hooks into `~/.claude/settings.json`
  (idempotently, preserving any hooks you already have). Headless `claude -p` and cron
  runs are unaffected — the hook no-ops when there's no tmux. Clients are untouched; this
  is a server-side feature (the client-version bump to 0.4.0 is just to keep `huginn
  version` aligned with the release).

### Changed
- **Dropped the `huginn:` tab-title prefix.** Tabs now read `<session>` (and `<icon>
  <session>` while Claude runs) instead of `huginn:<session>` — the state icon already
  signals it's a huginn session, so the prefix was redundant.

## [0.3.0] - 2026-06-17

### Added
- **Session-name tab completion.** Completing the first word now offers live session
  names (from `tmux ls` on the host) alongside the subcommands, so a bare
  `huginn <Tab>` lists everything you can attach to; `huginn kill`, `solo`, `rename`,
  and `mv` complete to existing session names for their argument. The session list is
  cached in-memory for ~5s so repeated `<Tab>` doesn't `ssh` on every keystroke
  (`BatchMode`/`ConnectTimeout` keep a missing key or slow link from hanging the
  prompt). Both clients.

### Fixed
- **Reject malformed session names.** A session name must be letters, digits, and
  underscore only (no `-`, `*`, spaces, etc.). This stops a typo'd flag like
  `huginn --hlp` from falling through to the attach path and spawning a junk tmux
  session. Enforced client-side (immediate, friendly error) and again server-side in
  `cc` (defense in depth for older clients).

## [0.2.0] - 2026-06-17

### Added
- **Auto-reconnecting attach.** When the link drops (laptop sleep, Wi-Fi flap) the
  client transparently re-attaches the still-running session — the work lives in
  tmux on the host, so only the ssh client died, not the session. It reconnects on
  any non-zero ssh exit (a clean `Alt-d` detach or normal exit returns `0` and
  stops the loop) and uses SSH keepalives so a half-open socket after sleep dies in
  ~45s instead of hanging. On reconnect it chooses **mirror vs solo dynamically** —
  mirror if another device is still attached, otherwise solo (full-screen, evicting
  the stale "ghost" client the dead link left behind). Opt out: `HUGINN_NO_RECONNECT=1`.
- **Named terminal tabs.** The attach renames the terminal tab/window to
  `huginn:<session>` — so `huginn costtracking` gives you a `huginn:costtracking`
  tab in Windows Terminal (and iTerm/Termux) — and restores the previous title on
  exit. Works because tmux's default `set-titles off` shields the outer terminal
  from the inner TUI's own title escapes. Opt out: `HUGINN_NO_TITLE=1`.
- **`huginn usage`** (aliases `cost`, `ccusage`) — Claude Code token/cost report via
  [ccusage](https://github.com/ryoppippi/ccusage); e.g. `huginn usage monthly`,
  `session`, `blocks --live`.
- **`huginn update`** — self-update the client in place from the repo. Pulls
  `client/huginn.{ps1,sh}` via `gh` (works for a private repo) and falls back to
  `scp` from the host's `/usr/local/share/huginn-cli/` mirror, then re-sources itself.
- **`huginn version`** — print the installed client version (and target host).
- **`rcc` alias** in both clients (parity with `rclaude`).

### Fixed
- **Clients are pure ASCII.** The `huginn update` `scp` fallback delivers the file
  with no BOM; Windows PowerShell 5.1 then decodes it as the system ANSI code page,
  which mangled the old box-drawing/em-dash characters and desynced the parser
  (the help here-string was read as code). Both clients are ASCII-only now, so they
  parse identically regardless of transport (`gh` vs `scp`) or PowerShell version.
- **`cc` defaults `TERM`** (`export TERM="${TERM:-xterm-256color}"`). Windows OpenSSH
  has no `TERM` env var, so it opens the session with `TERM` unset and tmux aborts with
  `open terminal failed: not a terminal` — even though the PTY is allocated correctly.
  Defaulting it server-side fixes every Windows client at once, with no per-device config.
- **Attach forces a PTY** (`ssh -t` → `ssh -tt`) for `huginn`, `huginn <name>`, and
  `huginn solo` — belt-and-suspenders for clients that don't allocate a PTY by default.
- `huginn.sh` `-p`/`-y` now single-quote-escape the prompt (parity with the PowerShell
  client) — prompts containing `'` no longer break the remote command.

### Changed
- `-p`/`-y` are **persona-aware**: when the host carries
  `/usr/local/share/huginn-cli/persona.md`, one-shots inject it via
  `--append-system-prompt` and enable memory tools; on a generic host they degrade
  to a plain `claude -p` headless query.
- Usage guards on `-p`/`-y`/`rename`/`kill` print a clear message instead of issuing a
  malformed remote command; `-p`/`-y` are folded into one branch in both clients.
- `setup.sh` now requires root and says so clearly.
- Installers accept a configurable SSH user (`-User` / `HUGINN_USER`, default `root`)
  to support running the node as a non-root user.

## [0.1.0] - 2026-06-08

Initial public release.

### Added
- **Server**: `setup.sh` (installs Node + Claude Code + tmux + scripts), the `cc`
  session launcher (attach-or-create, plus `solo` mode), `huginn-status`, and a
  multi-device-tuned `tmux.conf`.
- **Client command** `huginn` for **Windows PowerShell** (`huginn.ps1`) and
  **bash/Termux** (`huginn.sh`): subcommands `solo`, `list`, `status`, `rename`,
  `kill`, `-p`/`-y` one-shots, `help`, tab-completion, and a `rclaude` alias.
- **Installers** (`install.ps1`, `install.sh`) — set up the SSH alias, key, and profile.
- **Multi-device ergonomics**: mirrored sessions (`window-size smallest`),
  `Alt-d` detach, `Alt-o` detach-others, scrollback, status-bar keybind hints,
  and an optional Termux one-tap detach button.
- **Provisioning**: Proxmox LXC template + a generic "any Debian/Ubuntu host" guide.
- **Docs**: README, Setup, Usage, Architecture, FAQ, Security model, Contributing.

[Unreleased]: https://github.com/silencelen/huginn/compare/v0.7.1...HEAD
[0.7.1]: https://github.com/silencelen/huginn/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/silencelen/huginn/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/silencelen/huginn/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/silencelen/huginn/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/silencelen/huginn/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/silencelen/huginn/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/silencelen/huginn/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/silencelen/huginn/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/silencelen/huginn/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/silencelen/huginn/releases/tag/v0.1.0
