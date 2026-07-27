# Huginn (Android)

A Claude-style phone app for the **huginn** agent node (LXC 117), published to the
self-hosted [devstore](../dev-ledger/devstore). It replaces driving huginn through
a tmux session in Termux: chats, the live session list, and a usable terminal view,
all over the tailnet.

Two halves:

| | |
|---|---|
| `app/` | Android client, Kotlin + Compose Material 3, package `com.silencelen.huginn` |
| `server/` | `huginn-appd`, a zero-dependency Node daemon that runs on huginn |

## What it does

**Chats** are headless Claude Code turns that run on huginn in `~/netplan`, streamed
token by token. Each chat picks a mode at creation, matching the CLI:

- **Ask** = `huginn -p`: reasoning plus MemPalace memory, no tools.
- **Act** = `huginn -y`: also Bash, Read, Edit, Write, Glob, Grep, WebFetch.

Transcripts and the Claude session ID live server-side under
`/var/lib/huginn-appd/chats/<id>/`, so a chat resumes with real context and survives
the app being killed. Closing the chat screen detaches the SSE stream but never
cancels the run: locking your phone must not kill a turn.

**Sessions** are the actual tmux sessions, the same list `huginn ls` prints. Rows
carry the live state the Claude Code hooks record (working / needs you / waiting),
so you can see which session wants you without opening any of them. Creating one
runs the same thing `cc` does: Claude Code in `~/netplan`, falling back to a login
shell on exit.

**Terminal** renders `capture-pane -e` output with real colour, auto-sized so the
pane's full width fits the screen, plus a key row for what a phone keyboard cannot
send (Esc, Shift+Tab, arrows, Ctrl-C/D/L/R, PgUp/PgDn). It is not a terminal
emulator and does not pretend to be one: there is no PTY, no cursor placement and
no scrollback paging. It covers the actual phone job, which is reading what Claude
is asking, answering it, approving a tool, and hitting Esc.

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
| GET | `/v1/status` | uptime, load, disk, Claude version, MemPalace reachability |
| GET | `/v1/sessions` | tmux sessions + hook-recorded state |
| POST | `/v1/sessions` | `{name}`; letters/digits/underscore, canonically lowercase |
| DELETE | `/v1/sessions/<name>` | kill-session |
| GET | `/v1/sessions/<name>/screen` | capture-pane with SGR, plus pane geometry |
| POST | `/v1/sessions/<name>/keys` | `{text?, keys?}`; keys are validated against an allowlist |
| GET | `/v1/chats` | chat list, newest first |
| POST | `/v1/chats` | `{mode: ask\|act}` |
| GET | `/v1/chats/<id>` | metadata + full transcript + in-flight partial text |
| PATCH | `/v1/chats/<id>` | `{title}` |
| DELETE | `/v1/chats/<id>` | refuses while a run is active |
| POST | `/v1/chats/<id>/messages?stream=1` | posts and streams the run as SSE |
| GET | `/v1/chats/<id>/stream?since=<seq>` | reattach to an in-flight run, replaying from `seq` |
| POST | `/v1/chats/<id>/cancel` | SIGTERM then SIGKILL |

SSE events: `started`, `delta`, `assistant`, `tool_start`, `tool`, `result`,
`error`, `done`. Each run keeps a bounded replay buffer so a phone that locks
mid-answer catches up on reconnect instead of losing the turn.

### Session state

The sessions list gets its working/needs-you/waiting state from
`/run/huginn-claude-state/<session>`, written by `huginn-claude-title` (the hook
that also sets the terminal tab icon) in the [huginn CLI
repo](https://github.com/silencelen/huginn) at `server/bin/`. That file is tmpfs
and cleared on `SessionEnd`, so a session with no file simply shows "no claude" —
which is the honest answer for a session sitting at a shell prompt.

## Build and ship

```bash
scripts/build.sh [release|debug]   # tests + assemble + refuse an unsigned release
scripts/ship.sh  [release|debug]   # build + scp to devserv + reindex + verify live
```

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
emulator path here. `app/src/test/` covers the two hand-rolled parsers that would
otherwise be unverified, and both suites are fed bytes captured from the real
system: `AnsiTest` uses verbatim `tmux capture-pane -e` output from a live Claude
Code pane, and `SseTest` replays the exact frames the live daemon emitted, plus the
failure shapes a phone actually hits (a 401, a stream cut mid-answer). `build.sh`
runs them before every APK, so an unnoticed regression cannot reach the phone.
