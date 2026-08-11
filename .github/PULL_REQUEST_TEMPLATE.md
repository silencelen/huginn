<!-- Thanks for contributing to Huginn! The terminal core stays lean (bash / tmux /
     PowerShell); the daemon and apps have tests — run them. -->

## What & why
<!-- What does this change, and what need does it serve? -->

## Surfaces touched
- [ ] Server core (`setup.sh` / `cc` / `huginn-status` / `tmux.conf` / `server/bin`)
- [ ] CLI client (`huginn.ps1` **and** `huginn.sh` kept in sync)
- [ ] Daemon (`server/appd/` — zero-dep, `node --test` passes)
- [ ] Apps (`mobile/` — lowest-module rule per `docs/ADDING-A-FEATURE.md`; suites pass)
- [ ] Provisioning / docs / branding

## Checklist
- [ ] No secrets, real IPs, or hostnames (placeholders only)
- [ ] Shell scripts are LF; new runnable scripts marked `+x`
- [ ] CLI client files stay ASCII-only and in sync (subcommand + help + completion)
- [ ] Tested on the surface(s) I changed

## Notes
<!-- Anything reviewers should know. -->
