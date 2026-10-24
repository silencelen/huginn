---
name: huginn-server
description: This machine is a Huginn Server - Claude Code sessions run here in tmux, driven by huginn-appd (the daemon) and reached from the huginn clients. Use when asked whether huginn is healthy, what huginn-appd, cc, huginn-status, the huginn-* host tools or the files under /run/huginn-claude-state are, where Huginn's data, logs, hooks and settings live on this server, or before touching tmux sessions, the daemon or ~/.claude/settings.json here.
---

# Huginn Server: you are running on it

This Claude Code session is one of the sessions Huginn hosts. It lives in a tmux session (named
like `main` or `api-refactor`) started by `cc` or by the daemon, and the daemon reads your
state through hooks in this account's `~/.claude/settings.json`. Clients on the person's other
machines attach to this tmux session over SSH, or watch it through the daemon on port 8787.

## What is here

| Piece | Where | Read with |
|---|---|---|
| `huginn-appd`, the daemon (Node, runs as root) | `/opt/huginn-appd`, unit `huginn-appd.service` | `systemctl status huginn-appd`; `journalctl -u huginn-appd -t huginn-appd -n 100` |
| its settings | `/etc/systemd/system/huginn-appd.service.d/site.conf` (the operator's), `hardening.conf` (replaced on update) | `systemctl show huginn-appd -p Environment` |
| the access token (root-equivalent; never print it) | `/etc/huginn-appd/token` | do not |
| the daemon's data: chats, uploads, rounds, projects, archive, accounts, TLS certificate | `/var/lib/huginn-appd/` | `ls`, read-only |
| the sessions | tmux, in `huginn-tmux.service` when the daemon started the server | `tmux ls`; `huginn-status` |
| per-session state the hooks write | `/run/huginn-claude-state/<session>` and the dot dirs `.ask`, `.plan`, `.perm`, `.compacting`, `.modelswitch`, `.status` | `cat` (read-only) |
| the hooks | `/usr/local/bin/huginn-claude-title`, `/opt/huginn-appd/hooks/*`, wired in `~/.claude/settings.json` | `jq .hooks ~/.claude/settings.json` |
| host tools | `/usr/local/bin/cc`, `huginn-status`, `huginn-ask`, `huginn-rounds`, `huginn-devices`, `huginn-headroom`, `huginn-archive`, `huginn-projects`, `huginn-llm`; the state table in `/usr/local/lib/huginn/` | run them; `--help` on each |
| the release that was installed | `/usr/local/src/huginn` | `cat /usr/local/src/huginn/.huginn-ref` |
| this skill | `/usr/local/share/huginn/skills/huginn-server`, linked from `~/.claude/skills/huginn-server` | |

Details: [layout.md](layout.md). The tools and what they read: [tools.md](tools.md).

## Is huginn healthy?

Run `huginn-status` first. Then, only as needed, `systemctl status huginn-appd`,
`journalctl -u huginn-appd -t huginn-appd -n 50`, `tmux ls`, `huginn-devices`,
`huginn-headroom`. The decision table is in [health.md](health.md). Report what you ran and what
it said; name the one next step.

The clients reach the API through these host tools (they ssh here and the tool reads the token
locally), so the token never leaves this machine. Keep it that way: use the tools, not `curl`
with the token.

## Safe without asking

- Reading: `huginn-status`, `huginn-rounds`, `huginn-devices`, `huginn-headroom`,
  `huginn-archive` (list), `huginn-projects` (list, show), `tmux ls`, `journalctl`,
  `systemctl status`, `cat` of state files, `ls` under `/var/lib/huginn-appd`.
- `huginn-ask ask` (a read-only headless turn), `huginn-llm` (the local tier; no Claude usage).

## Not without the person

- Restarting or stopping `huginn-appd` or `huginn-tmux.service` (every client loses its stream;
  a running chat is recorded as interrupted; stopping the tmux service ends every session,
  yours included).
- Killing, ending, renaming or archiving a tmux session (`tmux kill-session`,
  `huginn-archive <name>`, `huginn-projects end`), or sending keys into another session.
- Editing `~/.claude/settings.json` or the hook scripts (a hook that exits non-zero stalls every
  session's turns), `/etc/huginn-appd/*`, `site.conf`, or anything under `/var/lib/huginn-appd`.
- Re-running `server/setup.sh`, `huginn-appd/install.sh`, `claude update`, or anything that
  changes the daemon or Claude Code versions.
- `huginn-ask act`, `huginn-projects new|spawn|msg`, `huginn-devices watch`, rotating the token.

When one of those is the right fix, say so with the exact command and what it interrupts, and
let the person run it (or confirm first).

## Who else is here

Other tmux sessions are other Claude Code sessions, often unattended. Do not attach to them,
read their panes, or type into them. The daemon's `.perm` and `.ask` files are how the apps
answer their prompts; never write there.
