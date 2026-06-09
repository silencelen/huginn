# Security Policy

## Reporting a vulnerability

If you find a security issue in Huginn's scripts or setup, please **report it privately** rather than opening a public issue:

- Use GitHub's [**private vulnerability reporting**](https://github.com/silencelen/huginn/security/advisories/new) (Security → Report a vulnerability), or
- Open a minimal issue asking for a private contact channel.

Please include: what the issue is, how to reproduce it, and the impact. You'll get an acknowledgement as soon as possible.

## Scope

Huginn is a thin wrapper over SSH + tmux + Claude Code. Most of your security posture comes from **how you deploy it** (SSH hardening, non-root user, key restrictions, network exposure). See [`docs/SECURITY.md`](docs/SECURITY.md) for the threat model and a hardening checklist before reporting deployment-config concerns.

## Supported versions

This is a small, single-branch project; fixes land on `main`.
