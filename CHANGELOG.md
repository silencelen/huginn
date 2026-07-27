# Huginn changelog

## 1.0.0 — 2026-07-27

First release. Replaces driving huginn from a tmux session in Termux.

### Chats
- Headless Claude Code conversations that run on huginn in `~/netplan`, streamed
  token by token over SSE.
- Two modes, chosen per chat: **Ask** has reasoning and MemPalace memory but no
  tools (the CLI's `huginn -p`), **Act** also reads and writes files, runs
  commands and fetches the web (`huginn -y`).
- Tools appear inline as they run, with the one field worth seeing (the command,
  the path, the query).
- Transcripts and Claude session IDs live on the server, so a chat resumes with
  full context and survives the app being killed. Leaving a chat detaches the
  stream but never cancels the turn: locking your phone does not stop the work.
- Cost and duration are shown per turn.

### Sessions
- The real tmux sessions on huginn, the same list `huginn ls` prints. A session
  you open here is the one your laptop attaches to.
- Each row carries the live state the Claude hooks record: working, needs you, or
  waiting. You can see which session wants you without opening any of them.
- Create a session (opens Claude Code in `~/netplan`, exactly like `cc`) or end one.

### Terminal
- Renders the pane with real colour, sized so the full width fits your screen.
- A key row for what a phone keyboard cannot send: Esc, Shift+Tab (permission
  modes), arrows, Ctrl-C/D/L/R, PgUp/PgDn.
- Send a line with or without Enter, because Claude Code's composer takes
  multi-line input and you do not always want to submit.

### Status
- Host health at a glance: uptime, load, disk, Claude Code version, MemPalace
  reachability, sessions and running chats.

### Notes
- Tailnet only. `huginn-appd` binds huginn's Tailscale address and every request
  needs the bearer token from `/etc/huginn-appd/token`.
- Signed with a dedicated release key (`huginn-release`), so future versions
  update in place.
