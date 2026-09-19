# The Huginn apps (Android + desktop)

Native clients for a Huginn node. They replace driving the host through a tmux
session in Termux: chats, the live sessions and their conversations, and a real
terminal view — over your tailnet/mesh. One Kotlin codebase builds both; the
daemon they talk to lives at [`../server/appd/`](../server/appd/).

| | |
|---|---|
| `app/` | the Android client, Kotlin + Compose Material 3, package `com.silencelen.huginn` |
| `app-desktop/` | the Compose Desktop client (Windows + Linux) |
| `core/` · `ui/` | the shared halves both clients are built from — see [Modules](#modules) |
| [`../server/appd/`](../server/appd/) | `huginn-appd`, the zero-dependency Node daemon on the host |

## What it does

**Chats** are headless Claude Code turns that run on the host in its configured
working directory, streamed token by token. Each chat picks a mode at creation,
matching the CLI:

- **Ask** = `huginn -p`: reasoning, the host's memory tools if it carries any,
  reading (files and the
  web via WebFetch/WebSearch) — and a hard deny on Bash/Edit/Write, because
  Claude Code's own safe-command heuristics are content-dependent and were
  measured approving one `curl | python3` and refusing its near-twin a minute
  later. The ask/act line is drawn at mutation, deterministically.
- **Act** = `huginn -y`: also Bash, Edit, Write.

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

**Attachments, and huginn is a share target.** The attach menu in either
composer (chats and sessions share one implementation) offers the camera, the
photo library, or **any file at all** — nothing is refused for its type. A
readable format gets a "read it" marker; a binary (a tarball, a `.sqlite`, a
UniFi backup) gets an "inspect with shell tools" marker, because the useful
question is "may this be stored", not "can Read display it". The share sheet
accepts text and images
from any app, and asks where they should land: a new chat, an existing chat, or
a session (the screenshot of an error belongs in the session already working on
it). Staged as a draft at the destination, never auto-sent. Photos are transcoded to JPEG at
≤2048px before upload, because Samsung cameras shoot HEIC, Claude's Read tool
cannot open HEIC, and without transcoding the failure is a successful upload
followed by a shrug. The photo rides the message as a bracketed path marker the
model Reads; the app renders that marker back as "📷 Photo attached" rather
than the daemon's storage path. Uploads stream to `/var/lib/huginn-appd/uploads`
as the bytes arrive (server-named, 128 MB cap enforced mid-stream, pruned after
7 days) — the app imposes no size limit of its own, so the host owns the number.

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
differently: high-priority FCM (measured 17–86ms to a sleeping, even Dozing,
phone), a `setAndAllowWhileIdle` alarm (the one kind Doze honours, and it
revives the watcher if it was killed), and Telegram from the host when nothing
else got through. WorkManager is **not** one of them — its periodic work is
deferred by Doze, which is why a 15-minute poll delivered all day and nothing
overnight. The alarm's cadence is adaptive and *explained in Settings*: the
host reports how many pushes it has sent this install, the app compares that
against how many arrived, and nothing-dropped earns the relaxed hourly check
while any deficit tightens it to ten minutes immediately. Silence is not
failure — only a push that was sent and never arrived is.

**What notifies, and what un-notifies.** Two channels, split by nature:
*Sessions needing you* (blocking — a question is waiting) and *Finished work*
(news — a chat answered, or a session that ran ≥5 minutes went idle). A chat's
notification is a real conversation thread: the answer arrives as a message,
the reply box appends yours under it (free text requires the device unlocked —
bounded answer buttons deliberately do not), and huginn's next answer continues
the same thread. Notifications also know when to shut up: nothing fires for the
chat or session you are looking at (and a pocketed phone still notifies — the
gate is resumed-and-visible, not merely open); a finish stays quiet for a
session with a terminal attached; and a question answered elsewhere takes its
own stale notification down, via a silent `session_resolved` push that Telegram
never carries. Opening a chat or session clears its notification: read is
dismissed.

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
  percentages only; the token never leaves the daemon. The section is **captioned
  with the account the numbers belong to** — the signed-in email and plan, cached
  with the numbers themselves, so a switch cannot leave the new account's bars
  wearing the old name.
- **Tokens** (on the Status tab) — volume for today and the last week from ccusage. Counts are exact;
  the dollar figures are list-price estimates that run high on a Max plan and are
  labelled as a trend, not a bill.

**Headroom: the app knows what the plan has left, and says what huginn did
about it.** Appd 3.0.0 made the daemon usage-aware; this is what surfaces.

- **A pill in the top bar** — the fullest of the three windows and when it
  resets, coloured as it fills. Tap it for the whole picture.
- **Settings → Headroom** holds the policy: the percentage at which a Fable
  session gets a handoff heads-up, the percentage at which it is stepped down to
  the next model, the ladder order, the default model new runs launch with, the
  percentages that hold subagent spawns, auto-resume and the phrase it resumes
  with, and account auto-switch (which moved here from its own screen).
- **A session that hits a usage limit says so.** It shows as a notice on the
  session, not as an answer — the difference matters, because a limit is not
  something Claude said. When the window resets huginn resumes it and tells you
  which sessions came back and how; the per-session toggle on the control bar
  overrides the global default for that one session.
- **The Fable step-down is visible and reversible.** At the heads-up threshold
  the session is asked, in its own pane, to write a handoff note; at the
  step-down threshold it is moved to the next model for **that session only**,
  and the notification carries **Undo**. Your host default model is never
  rewritten.
- **Watch any agent.** A picker above a session's conversation lists Main and
  every subagent and workflow member, and reads that one's own transcript with
  the same paging as the parent's — so a long workflow can be watched at the
  level that is actually working.
- **Accounts say whether they are still usable.** Every saved login shows fresh,
  expiring, expired or gone, because the daemon now keeps the inactive ones
  refreshed; a switch to a dead one is refused with the reason instead of
  "could not switch".

**Drafts persist.** An unsent message stays in its composer across navigation and
app restarts, per session and per chat.

**The pane is resized to the phone.** tmux keeps an unattached window at its
creation size (80x24 from `cc`), so a phone would otherwise read a laptop-shaped
layout through a keyhole. The app reports the geometry it can display and the
server resizes the window to match, so Claude Code re-wraps to fit. See
"Pane sizing is a lease" below for why that is safe.

## Server

```
scp/rsync this repo to the host, then:
# first time only -- deploy.sh READS the token, it does not create one:
install -d -m 700 /etc/huginn-appd
openssl rand -hex 32 > /etc/huginn-appd/token && chmod 600 /etc/huginn-appd/token

server/appd/deploy.sh     # installs /opt/huginn-appd, restarts the unit, proves /v1/ping
cat /etc/huginn-appd/token    # paste into the app's Settings
```

`huginn-appd` listens on port **8787** and requires `Authorization: Bearer <token>`
on every route — 32 random bytes in `/etc/huginn-appd/token` (0600). The code binds
`tailscale ip -4` by default; **the author's deployment binds `0.0.0.0`**, via a systemd
drop-in (`/etc/systemd/system/huginn-appd.service.d/override.conf`) so that
`deploy.sh` rewriting the unit cannot silently revert it. That is deliberate there —
the phone reaches the host over a second mesh as well as the tailnet, and the
mesh route arrives on the LAN address, not the tailscale one.

The consequence is worth saying plainly, because the tailnet used to be doing half
the work and no longer is: **there is no network boundary left.** The tailnet, the
LAN and the mesh all reach the port, nothing rate-limits or locks out a wrong token,
and everything the daemon exposes is equivalent to root on huginn — `/keys` types
into a root Claude Code pane. The bearer token is the *only* gate. Treat it like a
root SSH key: if a device carrying it is lost, rotate the file, restart the unit
(the token is read once at startup) and re-paste it into every client.

### API

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/ping` | liveness + version, plus `via {addr, port}` — which of this host's addresses you dialled. **Requires the bearer token** (the 401 it answers without one, carrying `X-Huginn-Appd`, is itself the daemon's fingerprint) |
| GET | `/v1/challenge` | **unauthenticated.** `?nonce=<16-64 hex>` → `{proof, version}` where `proof` is hex HMAC-SHA256 with the bearer token as KEY over the nonce exactly as sent. A client verifies it against its own copy of the token and adopts a route only on a match, so the token is never sent to an address that has not already proved it holds one — a forgeable `X-Huginn-Appd` header is a fingerprint, not proof. Publishing it is safe: HMAC is a pseudo-random function of its key, the key is 256 bits, and the nonce is yours so a recorded proof cannot be replayed at your challenge. Bad or missing nonce → 400; 20/s per client address, then 429 |
| GET | `/v1/account` | signed-in account (`claude auth status`; the credentials themselves when `HUGINN_APPD_CLAUDE_DIR` is set, so it can never answer from the real CLI past the override, and always agrees with `/v1/plan`) |
| POST | `/v1/account/login` | starts interactive sign-in in a `login` session; returns the URL |
| POST | `/v1/account/logout` | needs `{confirm:"logout"}`; signs out the whole host |
| GET | `/v1/usage` | cached ccusage summary (today + 7 days) |
| GET | `/v1/plan` | plan utilization, the numbers Claude Code's `/usage` shows |
| GET | `/v1/accounts` | saved logins; `?plan=1` adds each one's headroom |
| POST | `/v1/accounts/<slug>/activate` | make a saved login the active one |
| DELETE | `/v1/accounts/<slug>` | forget a saved login |
| GET | `/v1/status` | uptime, load, disk, Claude version, MemPalace reachability |
| GET | `/v1/sessions` | tmux sessions + hook state; `?preview=1` adds titles and activity previews |
| POST | `/v1/sessions` | `{name}`; must START with a letter, digit or underscore and may then also contain `-` and `.` (a `.` is refused by its own check), max 50 chars, canonically lowercase. Reserved names (`plan`, `ask`, `compacting`, …) are refused — they collide with the state directory's own sidecars |
| DELETE | `/v1/sessions/<name>` | kill-session |
| POST | `/v1/sessions/<name>/rename` | `{name}`; moves the state file with it |
| GET | `/v1/sessions/<name>/screen` | `?cols=&rows=` report the viewer's grid (descriptive; the pane is captured as it is), `?live=1` additionally takes the pane-size LEASE and resizes tmux — one holder per session, keyed on `X-Huginn-Client`, and a second live client is answered with `leaseHeldBy` rather than taking it; `?history=` adds scrollback, `?hash=&wait=` long-polls, `?force=1` resizes past an attached client |
| DELETE | `/v1/sessions/<name>/size` | release the resize lease now; only the holder's release counts (`{released}` says whether it did) |
| GET | `/v1/sessions/<name>/transcript` | structured events; `?offset=` tails |
| POST | `/v1/sessions/<name>/keys` | `{text?, keys?, scratchpadId?}`; keys validated against an allowlist. A scratchpad is sent as a PATH the pane's Claude can read, not as its text — `null` means Main. Text up to 100,000 chars, delivered by bracketed paste. **`keys:["Enter"]` is what makes a text send a SEND**: `{text}` on its own types into the composer and does not submit — that is the live view's typing path, and `delivered:true` there means the text landed in the box, not that a turn started. Answers `{ok, queued, position, delivered, blockedBy, intoDraft}`, plus `duplicate:true` when the same message is already queued or was just delivered. `blockedBy` is one of `modal`, `trust`, `starting`, `attention`, `draft`, `turn`, or null. A person's message is delivered at once (3.5.1); only a gate holds it. Interrupt keys are never queued |
| GET | `/v1/sessions/<name>/typing` | what is waiting to be typed into this session and why it has not gone yet: `{queued, delivering, lastError, blockedBy, waitedMs, intoDraft, serverTime, serverTimeSec}`. `intoDraft` is the last delivery to this session that landed in somebody's unsent draft — `{at (seconds), waitedMs, composer}` or null, cleared by the next ordinary delivery. The draft hold ends 60 s after the last live-view keystroke (5 s when the box reads empty), and this is how a client says what happened when it did |
| POST | `/v1/sessions/<name>/answer` | `{option}` or `{options:[…]}` for multi-select, plus `fingerprint?`; answers a numbered prompt. Refuses with 409 if the pane no longer shows that question |
| GET | `/v1/watch` | change signal; `?stream=1` is SSE with a 25s keepalive, otherwise a long poll |
| GET | `/v1/clients` | which phones have checked in, and how recently |
| GET | `/v1/alerts` · POST | host-sent alerts: `{enabled, mode: fallback\|always}` |
| GET | `/v1/push` | whether FCM is configured, and the registered devices (never their tokens) |
| POST | `/v1/push/register` | `{installId, token, model?}` |
| GET | `/v1/chats` | chat list, newest first |
| POST | `/v1/chats` | `{mode: ask\|act}` |
| GET | `/v1/chats/<id>` | metadata + digest transcript + in-flight partial text |
| GET | `/v1/chats/<id>/transcript` | the same structured events as a session |
| PATCH | `/v1/chats/<id>` | `{title}` |
| DELETE | `/v1/chats/<id>` | refuses while a run is active |
| POST | `/v1/chats/<id>/messages?stream=1` | posts and streams the run as SSE; optional `scratchpadId` (`null` = Main) prepends the page, composed at receipt so a queued message is a snapshot |
| GET | `/v1/chats/<id>/stream?since=<seq>` | reattach to an in-flight run |
| POST | `/v1/chats/<id>/cancel` | SIGTERM then SIGKILL |
| POST | `/v1/uploads` | raw bytes, any type, ≤128MB (streamed to disk); server names the file, returns its path; pruned after 7 days |
| POST | `/v1/account/login/code` | pastes the OAuth code into the login pane and waits for credentials to change |
| GET | `/v1/account/login/state` | how the interactive sign-in is going |
| GET | `/v1/desktop-kt/manifest` | the desktop client's appd-side update feed — the transition path for installed 0.5.x clients; newer ones fetch from the [GitHub release](#the-desktop-client) |
| GET | `/v1/sessions/<name>/suggestions` | suggested next messages at a turn boundary (cached by transcript size) |
| GET | `/v1/chats/<id>/suggestions` | the same, for a chat |
| GET | `/v1/sessions/<name>/agents` | the individual agents behind a fan-out; `?all=1` adds every workflow member |
| GET | `/v1/sessions/<name>/agents/<agentId>/transcript` | one agent's own transcript, paged like the parent's |
| GET | `/v1/autoswitch` · POST | automatic account rotation state / `{enabled}`. An alias onto `headroom.settings.accountSwitch`, kept for one release |
| GET | `/v1/headroom` | the whole usage picture: every account's three windows, what is stalled, what is held (`held[]`), the STOP sentinels, `keepAwake`, and what the arbiter last decided and why. ⚠ **every timestamp on this route is milliseconds** — `serverTime`, `readAt`, `sentinels.*.since`, `held[].since`, `arbiter.last*At`, `keepAwake.*` — which is the one documented exception to this daemon's epoch-seconds convention. `serverTimeSec` means seconds here and everywhere else |
| PATCH | `/v1/headroom/settings` | thresholds, ladder order, default model, auto-resume + phrase, account auto-switch |
| POST | `/v1/sessions/<name>/headroom/undo` | put a laddered session back on the model it was moved off (the Undo button) |
| POST | `/v1/accounts/<slug>/refresh` | refresh one saved login's OAuth token now. It never refuses: asking it for the ACTIVE login answers `200 {ok:true, status:"active_skipped"}`, because a running `claude` holds that token in memory. The route that refuses is `/activate`, with a 409 whose text names the date |
| POST | `/v1/rounds/polish` | `{field, title?, prompt?, goal?, mode?}`; `field` is `prompt` or `goal` (anything else is a 400) and the rest is context. One better draft of that field, as a proposal a person accepts — never applied, and 200 with `{error}` when the model cannot answer |
| GET | `/v1/rounds` · POST | scheduled recurring runs (list); POST creates one from `{title, prompt, goal?, schedule, …}`, where `schedule` is `{kind:"weekly", days:[0-6], at:"HH:MM", tz?}` (or `daily`/`hourly`). ⚠ `nextRunAt` is **milliseconds** while `createdAt` and `updatedAt` beside it are seconds; `nextRunAt` is DEPRECATED for one release, read `nextRunAtSec` (null when not armed) |
| GET | `/v1/rounds/<id>` · PATCH · DELETE | one round: view / edit its schedule+goal / delete it and its run history |
| GET | `/v1/devices` | enrolled devices — machines that run dispatched `act`/`generate` work — with each one's scope and liveness. ⚠ `lastSeen` and `registeredAt` are **milliseconds** and are DEPRECATED for one release; read `lastSeenSec` / `registeredAtSec` (null when never seen). `actWhileLocked` is present only when the device said |
| POST | `/v1/devices` | enrol/register a device |
| DELETE | `/v1/devices/<id>` | unenrol; stops offering work (does not reach onto the machine) |
| POST | `/v1/devices/<id>/beat` | runner heartbeat + advertised scope; the response carries `cancel` for the device's in-flight run |
| GET | `/v1/devices/<id>/work` | the runner's long poll for the next work item |
| POST | `/v1/devices/<id>/events` | the runner streams a run's output back in batches; `{done, exitCode?, error?}` settles it |
| GET | `/v1/scratchpads` · POST | the pages the owner keeps; the GET mints Main on first sight, POST takes `{name, content?}`. A 404 here is how a client knows this daemon has no scratchpads |
| GET | `/v1/scratchpads/<id>` · PATCH · DELETE | PATCH is the autosave, `{rev, name?, content?}` — a stale `rev` comes back 409 with the current page to adopt. Main cannot be renamed or deleted |
| GET | `/v1/sessions/<name>/overview` | what this run has spent and what it did; 409 until the Claude hook has recorded a transcript. Deliberately not in the session list or the watch digest |
| GET | `/v1/sessions/<name>/graph` | the map of the same run; `?size=&agentBytes=` is a two-part cursor and answers `{unchanged:true}` while neither has moved |
| POST | `/v1/sessions/<name>/meta` | `{goals?, notes?, autoResume?}`; `autoResume` is three-valued (`true`/`false`/`null` = follow the global setting). Kept against the Claude session id and not the window name, so 409 before a first prompt has landed |
| POST | `/v1/sessions/<name>/soft-end` | type a wrap-up phrase and (when `auto`) end the session once it settles. `{phrase?, auto?, force?}`. Through the send queue, so a dialog or a waiting question holds it; 409 while a question is on screen, and 409 with `force` unset when no Claude state was ever recorded (the pane may be a plain shell, where the phrase would EXECUTE) |
| POST | `/v1/sessions/<name>/compact` | ask the session to compact its context. Same queue and the same 409s |
| POST | `/v1/sessions/<name>/archive` | `{}` wraps up gracefully and archives on settle (`{archived:false, pending:true, mode, phrase}`); the row keeps the transcript and a `resumeCommand` |
| GET | `/v1/archive` | archived sessions, newest first: `title`, `cwd`, `model`, `effort`, `permissionMode`, `gitBranch`, `lastMessage`, `transcriptPath`, `transcriptBytes`, `resumeCommand`, and `live`/`revivedAt`/`revivedAs` once revived |
| GET | `/v1/archive/<id>` · PATCH · DELETE | one archived row: view / retitle / forget it |
| GET | `/v1/archive/<id>/transcript` | the archived conversation, the same structured events as a live session; 404 for an unknown id |
| POST | `/v1/archive/<id>/revive` | start that conversation again (`claude --resume`) → `{ok, name, resumed}`; 409 when it is already running, naming the session |
| GET | `/v1/projects` · POST | clusters of sessions working one brief. POST takes `{name, kind, brief, cwd}` — `kind` is one of `software`, `infra`, `hardware`, `docs`, `research`, `other`, and an untrusted `cwd` is refused `409 {reason:"untrusted-cwd"}` rather than pre-trusted |
| GET | `/v1/projects/<id>` · DELETE | one project. `DELETE ?end=graceful` (or `{end:"graceful"\|"now"}`) also winds the sessions down and answers `{ended:[], refused:[{name, claudeName, why}]}`; with no `end=` it deletes the record and ends nothing |
| GET | `/v1/projects/<id>/dashboard` | live roll-up: `totals` (turns, tokens, `estCost.byModel`, compactions), `rate`, and a per-member list with `state`, `needsYou`, `pendingSends`, `headroom` |
| POST | `/v1/projects/<id>/spawn` | `{approve:true, manifestRev}` — spawning is the owner's decision, so `approve:false` is a 400 and a stale `manifestRev` a 409 carrying the project to re-draw from |
| POST | `/v1/projects/<id>/message` | `{from, to, text}`; appd types one member's words into another's pane, through the send queue and every gate on it. Arrives in the reader's transcript as `kind:"system"` with `project:{id, name, from}` — never as their own bubble |
| POST | `/v1/projects/<id>/members` · DELETE `…/members/<role>` | adopt a session you already have open as `{name, role}` (omit `name` and it derives `<slug>-<role>`), or drop one. The lead cannot be dropped — delete the project instead |
| GET | `/v1/apps` · POST | the apps this host serves, with `kinds` for the picker. Adding one PROBES it at every address a client of this daemon has arrived on plus huginn's own, and refuses with 422 and the exact lines that would fix it when any fails |
| GET | `/v1/apps/<id>` · PATCH · DELETE | one app row |
| POST | `/v1/apps/<id>/probe` | re-check reachability now |
| GET | `/v1/apps/<id>/icon` | the app's own favicon, cached and re-fetched at most hourly, ETag/304, `nosniff`; `icon:false` on the row when it serves none |
| GET/POST/PATCH/DELETE | `/v1/consoles*` | the pre-3.5.2 spelling of `/v1/apps*`, kept for one release |
| PATCH | `/v1/quick-actions` | the wording behind the four selection buttons. No GET — the current values ride the `/v1/status` poll every client already runs |
| GET | `/v1/models` | the pickable model list, discovered from the installed Claude Code binary; `?local=1` adds the models the enrolled local-AI devices advertise |

**Timestamps are epoch SECONDS**, with `/v1/headroom` (and the `headroom` block
inside `/v1/status`) as the documented all-milliseconds exception. Three fields
sit outside that and cannot be changed under the shipped clients, so each has a
correctly-named seconds sibling and the old spelling is **deprecated for one
release**: `lastSeen`/`registeredAt` on `/v1/devices` → `lastSeenSec` /
`registeredAtSec`; `Round.nextRunAt` → `nextRunAtSec`; and `serverTime`, which
means seconds on `/typing`, `/clients`, `/watch` and `/agents` and milliseconds
on `/headroom` → `serverTimeSec`, which means seconds on every route that
carries either.

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
scripts/ship.sh  [release|debug]   # build + publish to your store host + reindex + verify live
scripts/build.sh [release|debug]   # tests + assemble only, no publish
scripts/release-desktop.sh         # the Compose DESKTOP client -> /v1/desktop-kt
```

**`ship.sh` is the default. Reach for `build.sh` only when you genuinely do not
want the release published.** It runs `build.sh` first, so preferring it skips
nothing. A build that is sideloaded to the test phone and never shipped leaves
every other device on an older version while the work looks finished — and since
`versionCode` is a build timestamp, shipping the same `versionName` later mints a
*different* code, so the test phone is then offered a pointless update.

Requires JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`) and
`/opt/android-sdk` with `platforms/android-35` + `build-tools/35.0.0`.

- **Firebase config is not in the tree.** `app/google-services.json` is
  per-deployment and gitignored; copy `app/google-services.json.example` and fill it
  from your own Firebase project (an Android app registered for package
  `com.silencelen.huginn`). The values are not secret -- every APK carries them --
  but they name one specific project, so each deployment brings its own. Without the
  file the Google Services Gradle plugin fails the build.
- `versionCode` = seconds since 2026-01-01, computed in Gradle (devstore fleet
  convention); `HUGINN_VERSIONCODE` pins it.
- **Release** signs with `~/.huginn-app/keystore.properties` (alias
  `huginn-release`, backed up off-box — see `.shiprc` for your publish target).
  Without that file the release APK builds unsigned and both scripts refuse to
  ship it.
- **Debug** signs with the checked-in `app/debug.keystore`, so debug builds carry
  the same cert on every machine.

### The desktop client

`scripts/release-desktop.sh` builds `:app-desktop` for BOTH platforms on this
Linux box and publishes them to huginn-appd's `/v1/desktop-kt`. Its version is
`app-desktop/version.txt` and nothing else; the Gradle packaging, the generated
`BuildInfo` the updater compares against, and the release gates all read it.

The Windows installer is real, and no Windows machine is involved: `jpackage`
cannot cross-compile, so the WINDOWS `jpackage.exe` runs under wine against a
runtime image cross-linked from Windows jmods (cached at `/opt/jdk-win-x64`,
re-fetched automatically), and Linux `makensis` wraps the result. The script
proves the chain by installing the .exe under wine and launching what it
installed.

**`/v1/desktop-kt` is the only desktop channel.** There was a second one,
`/v1/desktop`, belonging to the Electron client; that client was deleted on
2026-08-27 by owner directive and the daemon stopped routing the path in appd
2.81.0, so a GET there is now an ordinary 404. The channel keeps its `-kt` name
rather than moving into the vacancy, because installed 0.5.x clients still poll
this exact path. See `docs/DESKTOP-MIGRATION.md` for how the migration ran and
how it ended.

## Modules

- **`:core`** — Kotlin Multiplatform (`androidTarget` + `jvm`), all of it in
  `commonMain`: the wire models, route resolution, the ANSI/terminal-grid and
  markdown/syntax renderers, and the pure UI rules (local echo, transcript
  grouping, live-input diffing, voice loop, watch cycle). No Android imports —
  which is what lets `:app-desktop` consume the same code the phone runs, instead
  of the hand-ported TypeScript the retired Electron client carried its own copy
  of.
- **`:ui`** — Compose Multiplatform (`androidTarget` + `jvm`), the shared LOOK:
  the theme (one palette, one syntax set), the markdown/code renderer, the
  transcript rows (user bubble, thinking, tool, ask, subagent group, orphan
  result, system note) and the terminal grid painter. Both clients render these;
  neither keeps a copy. Where the two genuinely differ it is a PARAMETER — a mono
  text style and a root-Surface flag on the theme, a `TranscriptMetrics` for
  bubble width, a `CellPainter` for the glyph blit — never an `expect`/`actual`,
  so a window narrowed to a phone's width can be given the phone's answer.
- **`:app`** — the Android application: everything that needs a `Context`, a
  keystore, an IME, a notification channel, or a screen that only a phone has.
- **`:app-desktop`** — the Compose Desktop client: a window, panes, key handling
  and a settings file. See `docs/DESKTOP-MIGRATION.md`.

Package names did not change in the split, so `:app` sources import shared types
exactly as before. Compose Multiplatform is pinned to 1.7.3 (1.8.x needs Kotlin
2.1.20+); on the Android target its artifacts delegate to AndroidX, so no skiko
renderer reaches the APK — `:ui` cost the debug APK 15 KB of dex and not one new
library (`classes.dex` and `classes2.dex` came out byte-identical across the
extraction).

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

- `:ui`'s `TerminalCanvasTest` — the grid WALK, against a `CellPainter` that
  records instead of drawing: run coalescing, a run broken by a style change, an
  over-wide glyph centred rather than shifting the row, a wide glyph spanning two
  cells, and the echo clipped at the row's end. Plus one real skia render, which
  is what catches a painter that resolves a proportional face or draws nothing.

Most of these live in `:core`'s `commonTest` and run twice, once per target;
`:app` keeps only the tests whose subject needs Android or a `MockWebServer`
(`ApiContractTest`, `SseTest`, `ReattachPlanTest`, the notification-timing ones).
`:ui` runs its suite on the jvm target only — a real `DrawScope` needs a real
`ImageBitmap`, and on the Android target that is a stubbed `android.graphics.Bitmap`.
`commonTest` uses `kotlin.test`, whose `assertEquals` takes the message LAST
where JUnit takes it first — with three String arguments that difference compiles
silently, so assertions there are converted by hand and never by `sed`.

`scripts/build.sh` runs both suites before every APK, so a regression cannot
reach the phone unnoticed; it asserts the test COUNT as well as the exit code,
because a suite that stops being discovered exits 0 having run nothing. Both
suites have been mutation-checked: a deliberate bug in the wide-character width
and in the 256-colour lookup each failed them.
