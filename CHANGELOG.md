# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions use [SemVer](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/silencelen/huginn/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/silencelen/huginn/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/silencelen/huginn/releases/tag/v0.1.0
