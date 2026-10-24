# Is huginn healthy?

Run in this order; stop when something explains the question.

```bash
huginn-status                                   # the one-screen answer
systemctl is-active huginn-appd huginn-tmux     # both should say active
journalctl -u huginn-appd -t huginn-appd -n 50 --no-pager
tmux ls                                         # the sessions (yours among them)
ls /run/huginn-claude-state                     # one file per live session (plus the dot dirs)
huginn-devices                                  # the person's other machines, as the daemon sees them
huginn-headroom                                 # usage left; what the daemon is holding back
df -h /                                         # the daemon reports disk in /v1/status too
```

| Finding | Meaning | Next |
|---|---|---|
| `huginn-appd` inactive or failed | no client can see sessions, chats or devices; ssh attach still works | `journalctl -u huginn-appd -n 100` for the reason; the fix (restart, config) is the person's |
| the journal shows `401` lines | a client with a wrong or rotated token | which client and when; the person re-pairs it |
| `EADDRINUSE` on 8787 | something else holds the port, or two daemons | `ss -ltnp 'sport = :8787'` |
| `huginn-tmux` inactive while `tmux ls` works | the tmux server was started by a person, not the service; fine | nothing |
| `tmux ls` empty while the apps expect sessions | the server rebooted (the daemon restores sessions it knew about) or they were ended | the daemon log around boot |
| a session's state file missing while the session runs | hooks not wired for that account, no `jq`, or a session started by hand without the hooks | `jq .hooks ~/.claude/settings.json`; `claude --version` |
| a session stuck `running` for a long time | a long tool call, or a hook that stalls the turn | `tmux capture-pane -p -t '=<name>:'` only if it is the person's own or they ask |
| `huginn-headroom` exit 2 | the daemon is not answering at all | as the first row |
| `huginn-headroom` shows held sessions | usage limits: the daemon paused work until a reset | nothing; it resumes on its own |
| disk above 90% | transcripts, uploads and chats grow; the daemon prunes non-image uploads after `HUGINN_APPD_UPLOAD_KEEP_DAYS` | tell the person; do not delete |
| `claude --version` differs from what `huginn-status` reports | Claude Code updated; the daemon caches the version for an hour | nothing |

## After Claude Code updates

Huginn reads Claude Code's screen, hooks and transcript files. A new Claude Code release can
silently change what the daemon sees (prompt detection, status line, hook payloads). If the apps
stop showing permission prompts or the session state after an update, that is the first
suspect: note the new `claude --version` and tell the person; the project's developer docs
(`docs/dev/claude-code-integration.md` in the release under `/usr/local/src/huginn`) hold the
fragility checklist.

## What to say

One paragraph: the services' state, how many sessions, anything in the log worth a look, usage
headroom, disk. Then the one next step, and whether it needs the person (restarts, edits,
updates always do).
