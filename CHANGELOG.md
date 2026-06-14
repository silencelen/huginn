# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions use [SemVer](https://semver.org/).

## [Unreleased]

### Added
- **`huginn update`** — self-update the client in place from the repo. Pulls
  `client/huginn.{ps1,sh}` via `gh` (works for a private repo) and falls back to
  `scp` from the host's `/usr/local/share/huginn-cli/` mirror, then re-sources itself.
- **`huginn version`** — print the installed client version (and target host).
- **`rcc` alias** in both clients (parity with `rclaude`).

### Fixed
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

[Unreleased]: https://github.com/silencelen/huginn/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/silencelen/huginn/releases/tag/v0.1.0
