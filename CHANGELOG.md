# Changelog

All notable changes to the **terminal client + server core** (`client/`, `server/`)
are documented here. The other components version independently and keep their own
changelogs: the Android app ([`mobile/CHANGELOG.md`](mobile/CHANGELOG.md)), the
Compose desktop client ([`mobile/app-desktop/CHANGELOG.md`](mobile/app-desktop/CHANGELOG.md)),
and the deprecated Electron client ([`desktop/CHANGELOG.md`](desktop/CHANGELOG.md)).
Format loosely follows [Keep a Changelog](https://keepachangelog.com/); versions use
[SemVer](https://semver.org/).

## [Unreleased]

### Added
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
