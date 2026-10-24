# The host tools

All in `/usr/local/bin`. The `huginn-*` readers render the daemon's API on this machine and read
the token from `/etc/huginn-appd/token` themselves; the clients just `ssh huginn <tool>`. So the
token never leaves the server, and there is one renderer for every client. Each takes `--help`
and exits 0 for it.

| Tool | What it does | Writes? |
|---|---|---|
| `cc [name] [solo]` | attach to or create the tmux session `name` (default `main`); a new one starts `claude` | creates a session |
| `huginn-status` | uptime, load, Claude Code version and sign-in, sessions, memory node, disk | no |
| `huginn-rounds` | scheduled work and the last runs | no |
| `huginn-devices [watch <name> on\|off]` | enrolled machines, scope, reachability; `watch` marks one as always on | `watch` writes |
| `huginn-headroom [--json]` | usage left per Claude account; what the daemon held or moved; exit 2 when the daemon does not answer | no |
| `huginn-archive [<session> [--now]] \| revive <id>` | list archived sessions; archive a live one (wraps up first); revive one | archive/revive write |
| `huginn-projects [list\|show\|new\|spawn\|msg\|end]` | projects: list, members, start, approve, message, end | all but list/show write |
| `huginn-llm [--model ROW] [--timeout S] "prompt"` / `huginn-llm -` | one question to the local AI tier; exit 1 with the reason when no machine serves | makes and removes a chat |
| `huginn-ask ask\|act` (prompt on stdin) | one headless Claude Code turn with the chats' tool fence; what `huginn -p` / `-y` run | `act` can change files |
| `huginn-claude-title` | the hook; not for running by hand | the state dir |

`huginn-status` and `tmux ls` are the health reads. `huginn-ask act`, `huginn-archive <name>`,
`huginn-projects new|spawn|msg|end` and `huginn-devices watch` change things: ask first.

## The API, when a tool does not cover it

The daemon serves `https://127.0.0.1:8787/v1/...` with a self-signed certificate and the bearer
token. Prefer the tools. If a direct call is unavoidable, pin the certificate and keep the token
out of argv (the daemon's own doc shows the exact `curl --pinnedpubkey` form, which reads the
token through a header file, never on the command line). `GET /v1/status`, `/v1/sessions`,
`/v1/devices`, `/v1/headroom` are reads; anything else changes state.

## The tmux server

`tmux ls` lists every session; the daemon's own tmux lives in `huginn-tmux.service`, so a
`systemctl restart huginn-appd` does not end sessions, but `systemctl stop huginn-tmux` does.
`tmux capture-pane -p -t '=<name>:'` reads a pane (the trailing colon makes the name exact);
reading another person's session is still their business, so do not unless asked. Never
`send-keys` into a session that is not yours.
