# Contributing to Huginn

Thanks for your interest! Huginn is intentionally small — a few shell scripts and a tmux config — so contributions should keep it lean, portable, and dependency-free.

## Ground rules

- **Stay tiny.** No frameworks, no daemons, no build step. Plain `bash`, `tmux`, and PowerShell.
- **Stay portable.** The server side targets Debian/Ubuntu; clients target Windows PowerShell and bash/Termux. Test a change on at least the surface it touches.
- **No secrets, ever.** No real IPs, hostnames, keys, or tokens in the repo. Use placeholders (`<host>`, `<VMID>`).
- **Keep `huginn.ps1` and `huginn.sh` in sync** — they're a pair. A new subcommand goes in both, plus the help text and the tab-completer.

## Good first contributions

- A `client/install.sh` path for fish/zsh, or a macOS note.
- Provisioning recipes for other hosts (cloud-init, Docker, NixOS, a systemd service for the node).
- A real demo GIF (see [`assets/demo.tape`](assets/demo.tape)).
- Docs fixes, clearer wording, more FAQ entries.

## Dev notes

- **Line endings matter.** Shell scripts must be **LF** (enforced by [`.gitattributes`](.gitattributes)); PowerShell stays CRLF. Don't commit CRLF shell scripts — they break on the Linux host.
- **Executable bits:** server `bin/*`, `setup.sh`, and the `*.sh` installers are tracked `+x`. If you add a runnable script, set it: `git update-index --chmod=+x path`.
- **tmux specifics:** the `Alt-o` binding relies on `##{client_tty}` (deferred format expansion). If you touch it, test with two attached clients.

## Workflow

1. Fork → branch → change.
2. `shellcheck` your bash where you can; `Invoke-ScriptAnalyzer` for PowerShell is a bonus.
3. Open a PR with a clear description of what and why. Small, focused PRs merge fastest.

## Code of conduct

Be kind and constructive. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
