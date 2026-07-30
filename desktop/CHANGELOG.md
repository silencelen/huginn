# Huginn Desktop changelog

## 0.2.0

The first-audit release. Four parallel audits (main-process, renderer/design,
live-on-Windows, security) plus the owner's own bug list from a day of real
use. Everything below was either felt in daily use or would have been.

**Things that were silently broken.** Rename did nothing anywhere — Electron
does not implement `window.prompt()`, it throws; all dialogs are now in-app.
Self-update had never worked: the updater armed its auth header once at
launch, before the token existed, so every check 401'd for the life of the
process; it now re-arms per check. Notifications could never appear: the app
never claimed the AppUserModelID its own Start Menu shortcut carries, so
Windows dropped every toast.

**Things that misbehaved.** A sent message reappeared in the composer,
because the draft's 400ms save fired after the send cleared it. Chats froze
after a laptop sleep — the stream died and nothing reattached, so a finished
answer never arrived. A hidden window kept polling, and the pane poll is what
renews the tmux size lease, so a minimized desktop could pin a session's
geometry indefinitely. Deleting the open chat left the pane showing raw IPC
error text with a composer that sent into nothing.

**Things you couldn't change.** Mode had no control at all; model and effort
were dim grey selects whose native dropdowns rendered unreadably on Windows.
All three are now one "Next turn" group that says what it does.

**Layout.** The Status and Settings screens were rendering inside the 300px
list column — a real grid bug, not a styling opinion. Both now span the
window: Status reads as sections with aligned values, a humanized uptime, a
disk meter, per-limit reset countdowns and the active limit in bold; Settings
has one save model (fields commit on blur with a Saved mark) instead of two,
and the accounts flow is a three-step stepper. Session and chat headers lost
their button clutter — destructive verbs live in an overflow menu, and
Interrupt is gone because the composer's Stop already sends Escape.

**Look.** The active-row left accent bar is gone. Dim text was failing
contrast (4.33:1 on cards; code comments at 2.56:1) and now passes. Both
lists speak one state language, sessions show recency instead of geometry,
and both can create and rename.

**Security.** A forged `huginn://` link could approve a permission prompt on
a root-equivalent host without any freshness check — the fingerprint is now
mandatory. A single settings write could point the app, its Bearer token, and
its update feed at an arbitrary server: the server address is allowlisted and
the update feed is pinned. Also: permissions default-denied, CSP tightened,
link schemes filtered, and control bytes stripped from toast XML (one raw
byte from tool output would have made a "needs you" toast silently fail).

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
