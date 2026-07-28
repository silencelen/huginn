# Huginn (Android)

A Claude-style phone app for the **huginn** agent node (LXC 117), published to the
self-hosted [devstore](../dev-ledger/devstore). It replaces driving huginn through
a tmux session in Termux: chats, the live sessions and their conversations, and a
real terminal view, all over the tailnet.

Two halves:

| | |
|---|---|
| `app/` | Android client, Kotlin + Compose Material 3, package `com.silencelen.huginn` |
| `server/` | `huginn-appd`, a zero-dependency Node daemon that runs on huginn |

## What it does

**Chats** are headless Claude Code turns that run on huginn in `~/netplan`,
streamed token by token. Each chat picks a mode at creation, matching the CLI:

- **Ask** = `huginn -p`: reasoning plus MemPalace memory, no tools.
- **Act** = `huginn -y`: also Bash, Read, Edit, Write, Glob, Grep, WebFetch.

Closing the chat screen detaches the SSE stream but never cancels the run:
locking your phone must not kill a turn.

**Sessions** are the actual tmux sessions, the same list `huginn ls` prints.
Each session has two views:

- **Conversation** (the default) is built from that session's own Claude Code
  transcript in `~/.claude/projects/`: assistant text, thinking, tool calls
  folded together with their results, subagent output, workflow runs. This is
  structured data, not screen scraping, which is why it can show thinking and
  label a workflow by name.
- **Screen** is the live pane, for what only a pane can do: answering a prompt,
  watching progress, typing.

**Why both.** Going purely headless would lose the sessions actually running on
the laptop, which are the ones most worth seeing from a phone. Scraping the
screen as the primary source loses structure. So the transcript is the content
and tmux is the interaction, and each does what it is good at.

**Permission prompts become buttons.** A numbered question in the pane is
detected and offered as tappable options in both views. Detection requires the
live selection caret, so an assistant answer that merely ends in a numbered list
does not produce buttons.

**And they reach the lock screen.** An alert about a waiting session carries the
question and its options as notification action buttons, so it can be answered
without opening the app. Each answer carries a fingerprint of the question it was
offered for and huginn refuses it if the pane has moved on — by the time you tap,
the session may have been answered in tmux or asked something else, and a digit
delivered to the wrong prompt could accept something you never saw. The check runs
on the host in the same request, because nothing on the phone can hold the pane
still between reading it and typing into it. Moving the selection highlight does not
invalidate an answer.

**Notifications survive a sleeping phone.** Three mechanisms, because each fails
differently: high-priority FCM (seconds, needs Play Services and an app that has not
been force-stopped), a `setAndAllowWhileIdle` alarm every ten minutes (the one kind
Doze honours, and it revives the watcher if it was killed), and Telegram from the host
when nothing else got through. WorkManager is **not** one of them — its periodic work
is deferred by Doze, which is why a 15-minute poll delivered all day and nothing
overnight. Settings shows which of these is actually working, including huginn's own
record of when this phone last checked in; the app cannot testify about hours it spent
asleep, but the host was awake for them.

**Code is coloured.** Code blocks and the commands on tool cards are syntax
highlighted — shell, C-family, JSON, config and diffs (whole-line by sign, so an
Edit's result reads at a glance). The highlighter is a lexer, so a missed keyword
costs a colour and never the text.

**Multiple accounts.** Settings lists every Claude login saved on the host and
switches between them, showing how much of the week each has used. Switching is
a credentials-file swap, which means a **running** session keeps the account it
started with until it restarts — the app says so rather than pretending
otherwise. The outgoing account is snapshotted before every switch so a refreshed
token cannot strand it. Saved credentials stay on the host (0600 in a 0700 dir)
and are never sent to the phone.

**Model, effort and mode** can be changed from a session's control bar. These
send the same `/model` and `/effort` commands and the same Shift+Tab a person
would type, and the current values are read back from the transcript.

**Account and usage** live in Settings: which account huginn is signed in as,
sign in / switch (the interactive flow runs in a `login` session and the URL is
handed to the browser), sign out behind a confirmation that says it signs out the
whole host, and two different usage readings:

- **Plan usage** (on the Status tab) — the same rows `/usage` prints (current session, current week
  all-models, current week per-model), each with a bar and a reset countdown,
  read from `/api/oauth/usage` with the host's own credentials. The app is handed
  percentages only; the token never leaves the daemon.
- **Tokens** (on the Status tab) — volume for today and the last week from ccusage. Counts are exact;
  the dollar figures are list-price estimates that run high on a Max plan and are
  labelled as a trend, not a bill.

**Drafts persist.** An unsent message stays in its composer across navigation and
app restarts, per session and per chat.

**The pane is resized to the phone.** tmux keeps an unattached window at its
creation size (80x24 from `cc`), so a phone would otherwise read a laptop-shaped
layout through a keyhole. The app reports the geometry it can display and the
server resizes the window to match, so Claude Code re-wraps to fit. See
"Pane sizing is a lease" below for why that is safe.

## Server

```
scp/rsync this repo to huginn, then:
server/deploy.sh          # installs /opt/huginn-appd, mints the token, starts the unit
cat /etc/huginn-appd/token    # paste into the app's Settings
```

`huginn-appd` binds **huginn's Tailscale address only** (`tailscale ip -4`) on port
**8787** and requires `Authorization: Bearer <token>` on every route. Both matter:
everything the daemon exposes is equivalent to root on huginn, so the tailnet is
the network boundary and the token is the authorization one. The token is 32 random
bytes in `/etc/huginn-appd/token` (0600), generated on first deploy.

### API

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/ping` | liveness + version |
| GET | `/v1/account` | signed-in account (`claude auth status`) |
| POST | `/v1/account/login` | starts interactive sign-in in a `login` session; returns the URL |
| POST | `/v1/account/logout` | needs `{confirm:"logout"}`; signs out the whole host |
| GET | `/v1/usage` | cached ccusage summary (today + 7 days) |
| GET | `/v1/plan` | plan utilization, the numbers Claude Code's `/usage` shows |
| GET | `/v1/accounts` | saved logins; `?plan=1` adds each one's headroom |
| POST | `/v1/accounts/<slug>/activate` | make a saved login the active one |
| DELETE | `/v1/accounts/<slug>` | forget a saved login |
| GET | `/v1/status` | uptime, load, disk, Claude version, MemPalace reachability |
| GET | `/v1/sessions` | tmux sessions + hook state; `?preview=1` adds titles and activity previews |
| POST | `/v1/sessions` | `{name}`; letters/digits/underscore, canonically lowercase |
| DELETE | `/v1/sessions/<name>` | kill-session |
| POST | `/v1/sessions/<name>/rename` | `{name}`; moves the state file with it |
| GET | `/v1/sessions/<name>/screen` | `?cols=&rows=` leases a resize, `?history=` adds scrollback, `?hash=&wait=` long-polls, `?force=1` resizes past an attached client |
| DELETE | `/v1/sessions/<name>/size` | release the resize lease now |
| GET | `/v1/sessions/<name>/transcript` | structured events; `?offset=` tails |
| POST | `/v1/sessions/<name>/keys` | `{text?, keys?}`; keys validated against an allowlist |
| POST | `/v1/sessions/<name>/answer` | `{option, fingerprint?}`; answers a numbered prompt. Refuses with 409 if the pane no longer shows that question |
| GET | `/v1/watch` | change signal; `?stream=1` is SSE with a 25s keepalive, otherwise a long poll |
| GET | `/v1/clients` | which phones have checked in, and how recently |
| GET | `/v1/alerts` · POST | host-sent alerts: `{enabled, mode: fallback\|always}` |
| POST | `/v1/alerts/test` | sends one Telegram alert now |
| GET | `/v1/push` | whether FCM is configured, and the registered devices (never their tokens) |
| POST | `/v1/push/register` | `{installId, token, model?}` |
| POST | `/v1/push/test` | pushes a real message to every registered device |
| GET | `/v1/chats` | chat list, newest first |
| POST | `/v1/chats` | `{mode: ask\|act}` |
| GET | `/v1/chats/<id>` | metadata + digest transcript + in-flight partial text |
| GET | `/v1/chats/<id>/transcript` | the same structured events as a session |
| PATCH | `/v1/chats/<id>` | `{title}` |
| DELETE | `/v1/chats/<id>` | refuses while a run is active |
| POST | `/v1/chats/<id>/messages?stream=1` | posts and streams the run as SSE |
| GET | `/v1/chats/<id>/stream?since=<seq>` | reattach to an in-flight run |
| POST | `/v1/chats/<id>/cancel` | SIGTERM then SIGKILL |

SSE events: `started`, `delta`, `assistant`, `tool_start`, `tool`, `result`,
`error`, `done`. Each run keeps a bounded replay buffer so a phone that locks
mid-answer catches up on reconnect instead of losing the turn.

### Pane sizing is a lease

Resizing a tmux window requires `window-size manual`, and a manual window does
**not** re-fit when a client attaches later (verified on tmux 3.6b): it would
leave a 45x40 window inside a 200x50 terminal. So a resize is never a permanent
change. It expires on its own (90 s), is renewed while you keep viewing, and is
released when you leave the screen, when the daemon shuts down, and by a sweep at
startup that clears any window left manual by a previous crash. A resize is also
refused outright while another client is attached, unless explicitly forced.

### Session state

The sessions list gets its working/needs-you/waiting state from
`/run/huginn-claude-state/<session>`, written by `huginn-claude-title` (the hook
that also sets the terminal tab icon) in the [huginn CLI
repo](https://github.com/silencelen/huginn) at `server/bin/`. Since v2 that file is JSON and also carries the Claude
**session id and transcript path**, which is the only way to map a tmux session
to its transcript, and therefore what makes the Conversation view possible. It
is tmpfs and cleared on `SessionEnd`, so a session with no file simply shows
"no claude" — the honest answer for a session sitting at a shell prompt. An
older hook that writes only the bare state word is still accepted.

## Build and ship

```bash
scripts/ship.sh  [release|debug]   # build + scp to devserv + reindex + verify live
scripts/build.sh [release|debug]   # tests + assemble only, no publish
```

**`ship.sh` is the default. Reach for `build.sh` only when you genuinely do not
want the release published.** It runs `build.sh` first, so preferring it skips
nothing. A build that is sideloaded to the test phone and never shipped leaves
every other device on an older version while the work looks finished — and since
`versionCode` is a build timestamp, shipping the same `versionName` later mints a
*different* code, so the test phone is then offered a pointless update.

Requires JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`) and
`/opt/android-sdk` with `platforms/android-35` + `build-tools/35.0.0`.

- `versionCode` = seconds since 2026-01-01, computed in Gradle (devstore fleet
  convention); `HUGINN_VERSIONCODE` pins it.
- **Release** signs with `~/.huginn-app/keystore.properties` (alias
  `huginn-release`, backed up to `devserv:~/backups/huginn-app-keystore/`).
  Without that file the release APK builds unsigned and both scripts refuse to
  ship it.
- **Debug** signs with the checked-in `app/debug.keystore`, so debug builds carry
  the same cert on every machine.

## Testing

There is no device and no `/dev/kvm` on huginn, so there is no instrumentation or
emulator path here. The tests are therefore the only automated check, and they are
fed bytes captured from the real system rather than invented:

- `TerminalGridTest` — cell layout and SGR, using verbatim `tmux capture-pane -e`
  output from a live Claude Code pane, including the OSC 8 hyperlinks and the
  wide/ambiguous glyphs that made v1's rendering drift.
- `SseTest` — the exact SSE frames the live daemon emitted, plus the failures a
  phone hits (a 401, a stream cut mid-answer).
- `MarkdownTest` — the block and inline cases Claude actually writes, including
  the ones that bite (`snake_case`, an unclosed fence).
- `ApiContractTest` — decodes **real captured daemon responses** (scrubbed of
  content, shape intact), so a renamed server field fails here instead of
  silently showing an empty screen on a phone this host cannot run.
- `server/test/` — the daemon's pure logic under `node --test`: pane parsing,
  prompt detection (including the numbered-list false positive), the transcript
  reader (tailing, torn lines, garbage lines, tool/result pairing), sign-in URL
  extraction, and the ccusage field mapping.

`scripts/build.sh` runs both suites before every APK, so a regression cannot
reach the phone unnoticed. Both suites have been mutation-checked: a deliberate
bug in the wide-character width and in the 256-colour lookup each failed them.
