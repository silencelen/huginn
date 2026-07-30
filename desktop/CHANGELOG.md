# Huginn Desktop changelog

## 0.1.0

First release. A desktop counterpart to the Android app, talking to the same
huginn-appd daemon (2.50.0 pairs with this release) over the tailnet.

What it does:

- **Chats** — ask/act headless Claude runs: create, stream live (deltas, tool
  activity), queue mid-run follow-ups, cancel, delete; markdown answers with
  copyable syntax-highlighted code cards; suggestion chips; drafts that
  survive restarts.
- **Sessions** — every tmux Claude Code session: state dots, Claude's own
  titles, background-work lines, pane previews. Conversation tab renders the
  real transcript (thinking, tool cards, subagent fan-outs); Screen tab is a
  live cell-grid render of the actual pane with prompt-answer buttons,
  key chips, live typing, and pinch-free Ctrl+scroll font sizing.
- **Status** — host health, plan-limit bars, ccusage token counts.
- **Always-on** — one SSE watch stream instead of the phone's push/alarm
  machinery; the token lives in the OS keyring (DPAPI on Windows).
- **Self-updating** — installers and update feed served by the daemon itself
  (`/v1/desktop`); the app checks on launch and every 4 hours, downloads in
  the background, applies on restart. Windows NSIS and Linux AppImage
  auto-update; the deb is manual.

Build story worth remembering: the Windows installer builds ON huginn (wine64
+ wine32:i386 under xvfb) — no Windows box in the release loop.
