# Huginn

A persistent Claude Code session on your own host, reached from a terminal
client (bash, PowerShell, Termux), an Android app and a Compose desktop app.
`docs/ARCHITECTURE.md` explains the pieces; `CONTRIBUTING.md` the dev notes.

## Build and test gates

- Daemon: `node --test` in `server/appd` (no dependencies).
- CLI: `scripts/test-client.sh` (needs the daemon token for the project tools).
- Kotlin: `mobile/scripts/build.sh` runs both suites and asserts the counts;
  `mobile/scripts/release-desktop.sh` likewise. Needs the Android SDK.
- `client/huginn.sh` and `client/huginn.ps1` print 7-bit ASCII only.

## Brand and UI

The visual identity is decided and written up; do not improvise colours, type
or state words:

- `assets/brand/design-card.html`: the design card, the one-page reference for
  what ships (mark, tile, palette, states, type, scale, components, motion,
  terminal, surfaces). Generated from the token, state and mark files by
  `python3 assets/brand/design-card.py`; regenerate it after changing any of them.
- `assets/brand/BRAND.md`: the rules (mark, tile, terminal art, tokens, type,
  motion, surfaces, voice). Every colour comes from
  `assets/brand/explorations/color/color-04-moss-heather/tokens.json`.
- `assets/brand/UI-AUDIT.md`: what the current screens do against those rules.
- `assets/brand/pr/`: the six pull requests that bring the product in line, with
  file-level targets and acceptance checks.
- `assets/brand/RESKIN-HANDOFF.md`: the state of the reskin branch and what to
  do first (the Kotlin on it is uncompiled).

State language in code: `mobile/ui/.../theme/StateColors.kt` is the one place a
state maps to a colour or a word (working · needs you · waiting · shell · ok ·
error · info). Needs-you is the lit accent, never `colorScheme.error`.
