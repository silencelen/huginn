# HTTP API

`huginn-appd` serves the HTTP API that huginn core, the desktop app and the Android app use. You
can call it from your own scripts and tools.

> **The API is not yet versioned and may change between daemon releases.** The `/v1` prefix is
> not a stability promise. When a field is renamed, the old name is usually kept for one release.
> Every response carries the daemon's version in the `X-Huginn-Appd` header.

## Base URL

```text
https://<server>:8787/v1/...        from another computer
https://127.0.0.1:8787/v1/...       on the server itself
```

The port is `HUGINN_APPD_PORT` (default `8787`). One port serves both protocols: HTTPS to
everyone, plain HTTP only to the server itself. A plain HTTP request from another address gets
`426` with `{"error": "...", "tls": true}`.

Do not send the token over plain HTTP, even on the server. Plain HTTP cannot tell the daemon from
anything else: while the daemon is stopped or restarting, any account on the server can take the
port and read what a plain request carries. Huginn's own tools on the server use HTTPS pinned to
the daemon's key, as below.

## Authentication

Send the access token on every request:

```text
Authorization: Bearer <token>
```

The token is in `/etc/huginn-appd/token` on the server. It is root-equivalent: anything that holds
it can run commands on the server through sessions and act chats. See [Security](../security.md).

- A missing or wrong token gets `401 {"error": "unauthorized"}`. There is no rate limiting on
  authentication.
- `GET /v1/challenge` is the only route that needs no token (see below).

```bash
# on the server, as root
PIN=$(openssl x509 -in /var/lib/huginn-appd/tls/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64)
curl -s -k --pinnedpubkey "sha256//$PIN" \
  -H @<(printf 'Authorization: Bearer %s\n' "$(cat /etc/huginn-appd/token)") https://127.0.0.1:8787/v1/ping
```

The pin is the daemon's own public key, so curl refuses any other listener before it sends the
token (see [TLS and pinning](#tls-and-pinning); if you set `HUGINN_APPD_TLS_CERT`, read that file
instead). The header is read from a process substitution, so the token never shows in the
process list, where any other account on the server could read it.

## TLS and pinning

The daemon serves a self-signed certificate (or your own; see
[Server configuration](server-config.md#use-your-own-certificate)). Huginn's clients do not use a
certificate authority. They pin the certificate after the daemon proves it holds the token:

1. Open a TLS connection accepting any certificate, and compute `F`, the lowercase hex SHA-256 of
   the leaf certificate's DER encoding as your handshake saw it.
2. Without the token, request `GET /v1/challenge?nonce=<N>`, where `N` is 16 to 64 random hex
   characters.
3. Accept only if the response's `tls.proof` equals
   `HMAC-SHA256(token, "huginn-tls-v1:" + N + ":" + F)`. Compare in constant time, and never use the
   fingerprint the response reports.
4. From then on, send the token only over connections whose leaf certificate has fingerprint `F`.
   If the certificate changes, run the proof again before trusting the new one.

The full contract, with a test vector, is in [TLS protocol](../dev/protocol-tls.md).

For a script, a simpler equivalent is to pin the certificate's public key, read once from the
server over SSH:

```bash
# on the server: the public-key pin of the daemon's certificate
openssl x509 -in /var/lib/huginn-appd/tls/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```

```bash
# on another computer
curl -s -k --pinnedpubkey 'sha256//<pin from above>' \
  -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") https://192.168.1.20:8787/v1/ping
```

`-k` skips the certificate-authority check, and `--pinnedpubkey` still refuses any other key. The
pin changes when the certificate is replaced.

## Conventions

- **JSON in and out.** Request bodies are JSON (`Content-Type: application/json`), at most 256 KiB
  unless a route says otherwise. Responses are JSON unless a route serves a file or a stream.
- **Errors** are a non-2xx status with `{"error": "<a sentence>"}`. Some `409` responses carry the
  current object instead, so a client can show what it collided with.
- **`503` means tmux could not be read.** A session route answers `404` only when tmux says the
  session does not exist. When tmux cannot be reached, or reports no server while the daemon
  cannot confirm there is none (see [The tmux service](server-config.md#the-tmux-service)), the
  route answers `503`, changes nothing, and can be retried. A create answers `503` instead of
  starting a tmux server it cannot vouch for.
- **Timestamps are Unix epoch seconds.** The exception is `/v1/headroom` (and the `headroom` block
  in `/v1/status`), which uses milliseconds throughout. A field ending in `Sec` is always seconds.
- **`X-Huginn-Client: <id>`** identifies one client installation (up to 64 characters). Send a
  stable value: the daemon uses it for the screen-size lease, the clients list and per-client
  state.
- **Session names** start with a letter, digit or underscore, continue with letters, digits, `_`,
  `-` or `.`, are at most 50 characters, and are lowercase. Chats, rounds, devices, projects,
  archived sessions and scratchpad pages are identified by UUIDs.

## Streams

| Stream | How |
|---|---|
| Changes | `GET /v1/watch?stream=1` is a server-sent event stream. An `event: state` frame arrives when sessions, chats or usage change; a comment line (`: ka <time>`) every 25 seconds proves the connection is alive; `event: bye` after 30 minutes means reconnect. Without `stream=1`, the route is a long poll: pass the last `hash` you saw. It answers `503` when tmux cannot be read, never an empty session list. |
| A chat run | `POST /v1/chats/<id>/messages?stream=1` answers with an event stream of the run. Each event has an `id:` (its sequence number) and one of the types `started`, `delta`, `assistant`, `tool_start`, `tool`, `result`, `error`, `done`. Reattach after a dropped connection with `GET /v1/chats/<id>/stream?since=<last id>`; the daemon replays what you missed. |
| A session's screen | `GET /v1/sessions/<name>/screen?hash=<h>&wait=<ms>` holds the request until the screen differs from `h`, or the wait ends. |

## Routes

All routes need the token except `GET /v1/challenge`.

### Daemon and server

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/challenge` | **Unauthenticated: no token.** `?nonce=<16-64 hex>` returns `{version, tls: {v, fp, proof}}` over HTTPS, and `{proof, version}` over plain HTTP (this host only, by default) for clients older than 3.11. Limited to 20 requests per second per address. |
| GET | `/v1/ping` | `{ok, version, host, via: {addr, port}, secure}`: liveness, and which of the server's addresses you reached. Requires the bearer token, like every route except the challenge. |
| GET | `/v1/status` | Server health: uptime, load, disk, Claude Code version, the server's addresses, session and chat counts, usage summary, quick-action wording, `cycleOnDeploy` and the current runtime generation (`runtime: {gen, claude, since}`; see [Runtime generation and restart](#runtime-generation-and-restart)). |
| PATCH | `/v1/quick-actions` | Change the wording behind the quick-reply buttons. Current values come with `/v1/status`. |
| GET | `/v1/models` | The models the installed Claude Code offers. `?local=1` adds models served by local-AI devices. |
| GET | `/v1/watch` | Change signal. See [Streams](#streams). |
| GET | `/v1/clients` | Which clients have checked in, and how recently. |

### Claude account and usage

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/account` | The Claude account the server is signed in to. |
| POST | `/v1/account/login` | Start an interactive sign-in in a `login` session; returns the sign-in URL. |
| GET | `/v1/account/login/state` | How the sign-in is going. |
| POST | `/v1/account/login/code` | `{code}`: paste the code from the sign-in page. |
| POST | `/v1/account/logout` | `{confirm: "logout"}`: sign the server out. |
| GET | `/v1/accounts` | Saved logins. `?plan=1` adds each one's usage. |
| POST | `/v1/accounts/<slug>/activate` | Make a saved login the active one. |
| POST | `/v1/accounts/<slug>/refresh` | Refresh a saved login's credentials now. |
| DELETE | `/v1/accounts/<slug>` | Forget a saved login. |
| GET | `/v1/plan` | Plan usage: the numbers Claude Code's `/usage` shows. |
| GET | `/v1/usage` | Token and cost summary for today and the last 7 days, from the daemon's own ledger of the transcripts (`source: "huginn"`; cost at list rates, `costIsEstimate`). `correlation` is the latest weekly ccusage comparison, or null. |
| GET | `/v1/headroom` | The whole usage picture: every account's limits, what is stalled or held, and why. Timestamps in milliseconds. |
| PATCH | `/v1/headroom/settings` | Thresholds, model order, default model, auto-resume and account switching. |
| GET, POST | `/v1/autoswitch` | Deprecated alias for the account-switching part of `/v1/headroom/settings`. |
| GET | `/v1/settings/claude` | `{fastModeDefault, fastModePerSession, feedbackOff, statusLine}`: the Claude Code settings the apps show, read from the sessions account's `settings.json` (`fastMode`, `fastModePerSessionOptIn`, the three feedback keys the installer turns off, and whether Huginn's status line is installed). |
| PATCH | `/v1/settings/claude` | `{fastModeDefault: true \| false}`: write `fastMode` in that file, for every new session and chat. Written through a link, keeping the file's mode and everything else in it; `409` if the file cannot be parsed. Answers as the GET. |

### Sessions

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/sessions` | Sessions and their states. A session whose Claude is waiting on its own `start_session` request reads `attention` here (and in `/v1/watch`) until the request is answered or expires; the send queue still treats it as working. Every row carries `runtime` (see [Runtime generation and restart](#runtime-generation-and-restart)). `?preview=1` adds titles, activity previews and the background work: `bgShells`, `bgAgents`, `bgMonitors` (Claude Code's own count off the pane, beside the tasks directory) and `bgTask`. `state`, `stateSince`, `claudeSessionId` and `hasTranscript` are read from the session's state file; for a live session that has none (the hook has had no event since the file was lost) they come from the session's own process — the Claude session id from its `--resume`/`--session-id` argument, else the registry, else its project record, else the one transcript in its working directory that is newer than the session and no other session's; the state from that transcript's turn boundary; the working directory from `/proc` — and the daemon then seeds the state file, so the row reads like any other from then on. A session whose id cannot be named with a transcript on disk stays `null`. |
| GET | `/v1/sessions/recent-dirs` | The directories recent sessions started in (the server default first), for the create dialog. |
| POST | `/v1/sessions` | `{name, cwd?}`: create a session running Claude Code, in `cwd` (an existing directory on the server) or the server default. |
| DELETE | `/v1/sessions/<name>` | End the session at once. |
| POST | `/v1/sessions/<name>/rename` | `{name}`: rename it. Queued messages move with it; `409` if one is still being typed after 15 seconds. |
| GET | `/v1/sessions/<name>/screen` | The terminal screen. `?cols=&rows=` report your size; `?live=1` also resizes the pane for you (one client at a time holds this lease); `?history=` adds scrollback; `?hash=&wait=` long-polls. |
| DELETE | `/v1/sessions/<name>/size` | Release your screen-size lease. |
| GET | `/v1/sessions/<name>/transcript` | The conversation as structured events. `?offset=` continues from a position. A `system` event with a `body` is a headline over a longer text: `fold: "peer"` is a message from another session or a subagent's report, `fold: "compact"` is the summary written after a compaction. A `tool` event from the `send_file` tool carries `file: {id, name, mime, size, image, caption}`, fetched from `/v1/outbox/<id>`. |
| POST | `/v1/sessions/<name>/keys` | `{text?, keys?, scratchpadId?, shell?}`: type into the session. `text` alone fills the prompt without sending; add `keys: ["Enter"]` to send. `shell: true` runs `text` as a Claude Code **shell-mode** (`!`) command, on purpose: the server types the `!` itself, then the command, then Enter, and accepts the shell-mode submit for that one send; `text` is one line (a leading `!` is dropped), `scratchpadId` is not allowed with it, and the Enter is implied: `keys` is left out or `["Enter"]` (any other key is a `400`), and the send is a submit either way — never counted as typing in the live view, so never held as a draft. Without the flag a message that starts with `!`, or a send into a prompt already in shell mode, is refused with `409` and nothing is typed. Allowed keys: `Enter`, `Escape`, `Tab`, `BTab`, `Space`, `BSpace`, `DC`, `IC`, arrows, `Home`, `End`, `PPage`, `NPage`, `C-a` to `C-z`, `M-a` to `M-z`, `F1` to `F12`. |
| GET | `/v1/sessions/<name>/typing` | What is waiting to be typed into the session, and what holds it. |
| GET | `/v1/sessions/<name>/questions` | What the session is asking, as requests: `{questions: [{id, kind, toolName, agentId?, agentType?, mcpServer?, input, suggestions?, fingerprint, since, bound, fallback}]}`. `kind` is `ask` (an AskUserQuestion: `input.questions`), `permission` (any other tool: `input` is the tool's own input, `suggestions` what "always allow" would grant), `plan` (`input.plan`, `input.planFilePath`) or `session` (Claude asked for a new session with its `start_session` tool: `input: {directory, prompt, name}` — `name` null when it left the choice to you — plus `requester: {sessionName, chatId}` saying who asked and `expiresAt` (ms), an hour after it was asked; after that it is gone). `bound` means answerable through the route below; `fallback: "screen"` otherwise. The same list rides `/screen` as `questions`. |
| POST | `/v1/sessions/<name>/questions/<id>` | Answer one request, with its `fingerprint` (required). An ask: `{answers: {"<question text>": string \| string[]}, annotations?, response?}` with every question answered (a list only for a multi-select). A permission: `{behavior: "allow", updatedPermissions?}` (entries from `suggestions` only) or `{behavior: "deny", message?}`. A plan: `{behavior: "allow", mode?: "auto"}` or `{behavior: "deny", message}`. A session request: `{behavior: "allow", start: true, id: "<this question's id>", name?}` creates the session in `input.directory` (named `name`, else the request's name, else after the directory) and queues `input.prompt` to it through the send queue, answering `200 {ok, via: "daemon", started: true, name, cwd, delivered, queued}` — or the create's own `400`/`409` with `error` and the still-waiting `question`, so you can answer again with another `name` or decline. **`start: true` and the echoed `id` are required**: they are what the session card sends after showing the whole prompt, and an allow without them (the permission shape `{behavior: "allow"}`, a wrong or missing `id`) is refused `409 {reason: "unsupported", error: "this request needs the session card — update the app"}` with the request left waiting — not declined, nothing started. For a session request the `fingerprint` is a full content hash over `{directory, prompt, name}` (treat it as opaque; do not assume its length), so a request whose file was rewritten after the card was drawn no longer matches and is refused `409 {reason: "changed"}` with nothing started. `{behavior: "deny", message?}` answers `200 {ok, via: "daemon", started: false}` and the prompt goes nowhere. Otherwise `200 {ok, via: "hook" \| "keys"}`; `409 {reason: "gone" \| "changed" \| "unbound"}`. |
| POST | `/v1/sessions/<name>/answer` | `{option, fingerprint}`, or `{options: [...], fingerprint}` for a multiple choice: answer the question on screen by its row number. With a question's `fingerprint` from the registry the answer goes through that request's hook; with the pane's it types the row. A question of kind `session` is never answered by row: `409 {reason: "unsupported"}`, the request stays waiting, and only `POST .../questions/<id>` with the session card's body starts it. `409` (`gone`, `changed`, `undetected`) if that question is gone. |
| POST | `/v1/sessions/<name>/soft-end` | `{phrase?, auto?, force?}`: ask Claude Code to wrap up, then end the session once it settles. |
| POST | `/v1/sessions/<name>/restart` | `{force?: true}`: quit the session's Claude Code with `/exit` at a safe point and bring the same conversation back on the current runtime generation, in the same tmux session (see [Runtime generation and restart](#runtime-generation-and-restart)). `202 {queued: true, blockedBy}` with `blockedBy` `null` or what holds it: `running`, `dialog`, `draft`, `background`, `attached`, `load`. `409 {reason: "restarting"}` when one is already queued or running; `409 {reason: "no-claude"}` when no Claude session is recorded here (nothing to resume); `404`; `503` when tmux cannot be observed. |
| POST | `/v1/sessions/<name>/compact` | Ask the session to compact its context. |
| GET | `/v1/sessions/<name>/context` | `{context, model, breakdown}`: the live context window and model (from the status line, see below) and the last `/context` table Claude Code wrote, parsed: `breakdown: {asOf (ms), uuid, totalTokens, maxTokens, percent, categories: [{id, name, tokens, percent, kind}], autocompactWindow, autocompactSource, model: {display, id}, mcpTools: [{name, tokens}], mcpAvailable: [name], skills: [{name, tokens}]}`, or `null` until the session has run `/context`. `kind` is `used`, `free`, `buffer` or `deferred`; ids are Claude Code's (`system_prompt`, `system_tools`, `mcp_tools`, `mcp_instructions`, `mcp_tools_deferred`, `system_tools_deferred`, `custom_agents`, `memory_files`, `skills`, `messages`, `autocompact_buffer`, `compact_buffer`, `free_space`). |
| POST | `/v1/sessions/<name>/context/refresh` | Type `/context` into the session, through the send queue: `202 {queued, delivered, blockedBy}`. Sent mid-turn, Claude Code runs it when the turn ends; the next GET has the table. `409 {reason, error}` when it cannot be queued: `shell` (no Claude Code here, or its prompt is in shell mode), `dialog` (a question is on screen), `busy` (the same command is already waiting). |
| POST | `/v1/sessions/<name>/fast` | `{on: true \| false}`: type `/fast on` or `/fast off` into the session, through the same queue with the same answers. With `fastModePerSessionOptIn` set (the installer sets it), this changes only this session. |
| POST | `/v1/sessions/<name>/archive` | Wrap up and archive the session. |
| POST | `/v1/sessions/<name>/meta` | `{goals?, notes?, autoResume?}`: per-session notes and settings. |
| GET | `/v1/sessions/<name>/suggestions` | Suggested next messages at a turn boundary. |
| GET | `/v1/sessions/<name>/overview` | What the run has spent and done. |
| GET | `/v1/sessions/<name>/graph` | A map of the run and its agents. |
| GET | `/v1/sessions/<name>/agents` | The agents behind a fan-out. `?all=1` adds every workflow member. |
| GET | `/v1/sessions/<name>/agents/<agentId>/transcript` | One agent's transcript. |
| POST | `/v1/sessions/<name>/headroom/undo` | Put a session back on the model the usage ladder moved it off. |

Every session row (`GET /v1/sessions`) and `GET /v1/sessions/<name>/screen` carry what Claude Code's
own status line last reported for the session, when the server's status line is installed (it is,
by `server/setup.sh`): `context: {used, window, percent, exceeds200k}` (tokens; `null` before the
first response), `model` (the display name, for example `Fable 5.1`), `effort` (`low` to `max`),
`fast: {on, available, reason}` (`reason` is Claude Code's own sentence when fast mode cannot be
used, read off the screen; `available` is decided on the preview list and the screen, and `true` on
the plain list), `limits: {fiveHour, sevenDay, spend}` (each `{usedPercent, resetsAt}` in seconds,
or `null`) and `cost: {usd, durationMs, apiMs, linesAdded, linesRemoved}`. Each is `null` without
a record, and a record older than six hours or from another Claude session under the same name is
ignored. `contextPercent` keeps being served for older clients, filled from `context.percent`,
else from a status line drawn in the `[name] Model · ctx N%` shape.

### Runtime generation and restart

A Claude Code process loads its MCP servers, hooks, settings and binary once, at start, so a
session started before a deploy or a `claude update` keeps the old wiring until it is restarted.
The daemon hashes what a session loads — the `claude` binary's identity (its real path, size and
modification time), the sessions account's `~/.claude/settings.json` (all of it but `fastMode`,
the one key the daemon writes itself), the user-scope `mcpServers` of `~/.claude.json`, and the
daemon's own version, which stands for the hooks and the MCP server it installed — into a short
**generation**, re-reads the inputs every minute, and records the generation on every session it
launches (a session started outside the daemon takes the generation current at its birth).

Every session row carries `runtime: {stale, gen, current, claude, currentClaude, restarting}`:
`gen` is the generation the session was launched under (`null` when none was recorded, which is a
session that was running before the daemon learned generations and counts as stale once),
`current` the daemon's, `claude` and `currentClaude` the Claude Code versions, `stale` whether
the two generations differ, and `restarting` whether the session's Claude is being cycled right
now. `/v1/watch` hashes `stale` and `restarting` and carries them as `runtime: {<name>: {stale,
restarting}}`, so a client learns of either without polling rows.

`POST /v1/sessions/<name>/restart` queues the restart as a send-queue job. At a turn boundary
with an empty prompt the daemon re-checks the safe point — idle by the state file and the
transcript, no dialog, nothing in the box, no background work, no attached terminal, the host
inside the CPU-pressure lull the release cuts wait for — captures the `claude` process's flags
from `/proc`, asks it to quit with `/exit`, and relaunches it in the same tmux session and
directory with the same flags and `--resume <claudeSessionId>` in place of any `--resume` or
`-c`. Background work is counted the way Claude Code counts it: the tasks directory (shells and
agents) and the mode hint under the prompt (`⏵⏵ auto mode on · 1 shell, 1 monitor · ↓ to manage`),
which is the only place a Monitor shows. `/exit` has three outcomes on Claude Code 2.1.316 and
the cycle reads the pane for which: the process ends; Claude Code draws its "Background work is
running" prompt (work it counts that the daemon could not see), which the cycle backs out of with
Esc — never answers — and holds as `background` for five minutes; or the command is taken and
nothing happens, in which case `/exit` is tried once more and a second no-op leaves the process
running (a signal only when no prompt is drawn at all). A project session is relaunched as the
reboot restore launches it: its persona, its effort, its model only when nothing resumes.
Messages queued meanwhile wait and are delivered once the prompt is back; nothing is dropped.
`{force: true}` skips the `attached` hold and the load gate for that one restart and nothing
else. A session that had no state file is cycled once its id has been derived and seeded (see
`GET /v1/sessions`); `409 no-claude` is only for a session nothing names with a transcript on
disk, and the sweep never takes one. A job that finds no turn boundary within ten minutes gives up (the row reads `stale`
again); a relaunch that fails is retried once after 60 s and then left `stale` for a manual
restart. The journal has one line per outcome, `restart: <name>: <old gen> -> <new gen>`.

With `HUGINN_APPD_CYCLE_ON_DEPLOY` on, the default (`0` in `site.conf` turns it off; `/v1/status`
reports it as `cycleOnDeploy`), the daemon cycles stale sessions on its own: one at a time, each
at its own safe point, 30 s apart, and only while the host is in its lull. Each pass walks the
stale sessions — never tried under this generation first, oldest first; the tried behind them in
the order they were tried — reads each one's safe point as it stands, and takes the first that
is at one. A sweep job that still cannot cycle its session (a hold appeared, Claude Code's exit
prompt said work is running, no turn boundary within a minute) yields at once: the session is
backed off — 10 min, then 30, 60, 2 h, 4 h, 6 h, reset by a new generation — and goes to the
back of the line, and the next candidate is taken in the same pass. An attached session is
skipped and stays `stale` for a manual restart. The journal gets one line per decision (`restart
sweep: picked <name> (…)`, `restart sweep: <name>: … backed off <wait>`, `restart: <name>: <old
gen> -> <new gen>`) and a summary at most hourly (`restart sweep: N stale, M cycled, K backed
off in the last …`). A session under restart is exempt from the project reconcile, the
restore-registry prune, the state sweep and the alert watcher, so a cycle sends no
`session_finished` or `session_resolved`.

### Chats

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/chats` | Chats, newest first. |
| POST | `/v1/chats` | `{mode: "ask" \| "act", model?, effort?, fast?, title?, host?}`: create a chat; `host` is a device id to run it on instead of the server; `fast` (`true`, `false` or `null`, the default) pins fast mode for its runs, passed to Claude Code as `--settings '{"fastMode":…}'`; `null` leaves the host default (`PATCH /v1/settings/claude`). Returns the chat with its `id` (`201`). |
| GET | `/v1/chats/<id>` | The chat, its transcript and any text still arriving, and `questions`: what its Claude is asking you (only `session` requests — a chat run draws no dialogs), in the shape of a session's `GET /questions`. |
| PATCH | `/v1/chats/<id>` | `{title?, model?, effort?, fast?, mode?}`: rename it, or change what the next turn runs with. |
| DELETE | `/v1/chats/<id>` | Delete it. Refused while a run is active. With `?ifEmpty=1`, deletes it only if nothing was ever sent in it and answers `409` otherwise; the clients send this when the person leaves an empty chat. |
| POST | `/v1/chats/<id>/messages` | `{text, scratchpadId?}`: send a message. `?stream=1` streams the run; otherwise `202`. A message sent while a run is active is queued. |
| GET | `/v1/chats/<id>/stream` | `?since=<id>`: reattach to a running run. Besides the turn's events, a `questions` event (`{questions: [...]}`) whenever the chat's question list changes while the run is alive. |
| GET | `/v1/chats/<id>/questions` | `{questions: [...]}`: the chat's waiting `session` requests, as above. |
| POST | `/v1/chats/<id>/questions/<qid>` | Answer one, with its `fingerprint`: the same body and answers as a session's `POST /questions/<id>` for kind `session` — an allow needs `start: true` and the echoed `id` here too, else `409 {reason: "unsupported"}` and the request stays. |
| POST | `/v1/chats/<id>/cancel` | Stop the run. |
| GET | `/v1/chats/<id>/transcript` | The chat as structured events, like a session's. |
| GET | `/v1/chats/<id>/suggestions` | Suggested next messages. |

See [Security](../security.md#chats-ask-and-act) for what each mode may do.

#### Claude's own tools

Chats and sessions have huginn's MCP server (`huginn`), two tools whose results the server reads back
and whose requests it surfaces to you:

- `send_file(path, caption?)` copies a file into the outbox (`GET /v1/outbox/<id>`); the transcript's
  `tool` event carries `file`.
- `start_session(directory, prompt, name?)` **asks** you for a new session. The call parks a request
  that appears as a question of kind `session` on the asking session's card
  (`GET /v1/sessions/<name>/questions`) or chat (`GET /v1/chats/<id>/questions`), with the directory,
  the prompt verbatim and the name, and blocks until you answer or an hour passes. The tool returns
  exactly what the server did: `{"started": true, "name", "cwd"}` after the session was created and
  the prompt queued to it, else `{"started": false, "reason"}` (`declined` / `declined: <your words>`,
  `expired`, `cancelled`, `withdrawn`, or why it could not be made). Nothing is started, and the
  prompt is sent nowhere, unless you approve. The `directory` must be an absolute path to an existing
  directory on the server (checked when the tool is called and again when you approve). While a
  request waits you are told once: the asking session reads `attention` (a `session_attention`
  push), and a chat gets a `chat_attention` push (see
  [Push: a waiting session request](#push-a-waiting-session-request)).

### Files

| Method | Path | What it does |
|---|---|---|
| POST | `/v1/uploads` | The raw file as the body (not multipart), with its `Content-Type`; `?name=` gives the original name. Up to 128 MB. Returns `{ok, path, bytes, ext, readable}`; name `path` in a message so Claude Code can open the file. |
| GET | `/v1/uploads/<name>` | An uploaded file. |
| GET | `/v1/outbox/<id>` | A file Claude sent with its `send_file` tool, by the id on the transcript's `tool` event (`file.id`). Images come back inline as their image type; anything else as `application/octet-stream`, `Content-Disposition: attachment`. Only ids the server minted are served. |
| GET | `/v1/files/image` | `?path=<path>[&session=<name>]`: an image on the server that a transcript names, from the uploads, the scratchpads, Claude Code's scratch folder, or the named session's working folder. |

### Rounds

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/rounds` | Scheduled runs. |
| POST | `/v1/rounds` | `{title, prompt, goal?, schedule, ...}`: create one. `schedule` is one of `{kind: "daily", at: "HH:MM", tz?}`, `{kind: "weekly", days: [0-6], at, tz?}` (0 is Sunday), `{kind: "monthly", dates: [1-31], at, tz?}` (a date a month does not have is skipped that month) or `{kind: "interval", everyMinutes: 5-10080, tz?}`. `tz` is an IANA zone name; without it the round uses the server's zone. |
| GET, PATCH, DELETE | `/v1/rounds/<id>` | View, change or delete a round. |
| POST | `/v1/rounds/<id>/run` | Run it now. |
| POST | `/v1/rounds/<id>/ack` | Mark its latest report as read and dealt with. |
| POST | `/v1/rounds/polish` | `{field: "prompt" \| "goal", ...}`: a suggested rewording of that field, never applied by itself. |

### Archived sessions

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/archive` | Archived sessions, newest first, each with a `resumeCommand`. |
| GET, DELETE | `/v1/archive/<id>` | View or forget one. |
| GET | `/v1/archive/<id>/transcript` | Its conversation. |
| POST | `/v1/archive/<id>/revive` | Start it again in a new session. `409` if it is already running. |

### Projects

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/projects` | Projects. A `404` means the daemon has no projects feature. |
| POST | `/v1/projects` | `{name, kind, brief, cwd?}`: start a project and its lead session. `kind` is `software`, `infra`, `hardware`, `docs`, `research` or `other`. |
| GET, PATCH | `/v1/projects/<id>` | View or change one. `PATCH` needs the current `rev`. |
| DELETE | `/v1/projects/<id>` | Delete it. `?end=graceful` (or `{end: "graceful" \| "now"}`) also ends its sessions. |
| GET | `/v1/projects/<id>/dashboard` | Live totals and per-member state. The answer leaves within about a second: a member whose transcript is still being read carries `pending: true` with its last known numbers (or zero), `pending` counts such members, and the next poll has the rest. |
| POST | `/v1/projects/<id>/spawn` | `{approve: true, manifestRev}`: approve the lead's proposal and create its members. |
| POST | `/v1/projects/<id>/discard` | Send a proposal back to the lead. |
| POST | `/v1/projects/<id>/approve-change` | `{rev, token}`: carry out the roster change the lead proposed for a running project (see `pendingChange` below). Added roles are spawned, removed ones are asked to wrap up (never killed), re-roled ones are told their new task; the lead is told. Both fields are required (`400` without). `409` with the current project when `rev` is not the change on offer, or when `token` is not the token of the change now waiting — the lead rewrote its block after the card was drawn; redraw from the project returned. |
| POST | `/v1/projects/<id>/decline-change` | `{token?}`: drop the pending roster change and tell the lead. With a `token` that is not the current change's, `409` with the current project, as approve. |
| POST | `/v1/projects/<id>/message` | `{from, to, text}`: pass a message from one member to another. |
| POST | `/v1/projects/<id>/members` | `{name?, role}`: add a running session as a member (a record edit; nothing is launched). `{role, description, spawn: true, cwd?, model?, effort?}`: create a NEW member in a running project, the way an approved manifest does; `description` is its whole first message. Both respect the member limit. |
| PATCH | `/v1/projects/<id>/members/<role>` | `{description}`: give a member a new task; it is typed into the member, framed as owner-approved. The role word itself cannot change while the session runs (`409`). |
| DELETE | `/v1/projects/<id>/members/<role>` | Remove a member from the record; the session keeps running. With `?end=1` (or `{end: true}`) the member is asked to wrap up and end (the soft-end) and stays in the record, with `endedAt` set, until its session is gone. `end=now` is refused: a member is never ended outright. |

A project row (`GET /v1/projects`, and `row` in the detail) carries `pendingChange`: `null`, or
the roster change the lead proposed for a running project, waiting for Approve or Decline:

```
pendingChange: {
  rev: int,                    // the manifest rev this becomes when approved; pass it to approve-change
  token: string,               // 64 hex (a whole sha256) of {added, removed, changed, summary}; pass it to approve-change.
                               // Opaque: compare it, never assume its length.
                               // The rev is the same for every proposal until one is applied; the token changes
                               // whenever the words on the card do, so a stale card cannot approve a rewritten block
  summary: string | null,      // the lead's one line
  added:   [ {role, firstPrompt, cwd, model, effort, mode} ],   // spawned on approve
  removed: [ {role} ],                                          // asked to wrap up on approve
  changed: [ {role, firstPrompt} ],                             // told the new task on approve
  problem: string | null,      // set when the change cannot be applied as proposed (the member limit); approve answers 409 with it
  receivedAt: unix seconds
}
```

The lead proposes a change by writing its manifest block again, describing the roster it now
wants; the daemon diffs it against the running members. A block that changes nothing proposes
nothing. The project's `status` stays `active` while a change waits.

### Devices

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/devices` | Enrolled devices, their scopes and when they were last seen. |
| GET | `/v1/devices/<id>` | One device. |
| PATCH | `/v1/devices/<id>` | `{watch: true \| false}`: alert when this device stops checking in. |
| DELETE | `/v1/devices/<id>` | Unenrol it: stop offering it work. |

`POST /v1/devices`, `POST /v1/devices/<id>/beat`, `GET /v1/devices/<id>/work` and
`POST /v1/devices/<id>/work/<runId>/events` are the device runner's own protocol, used by huginn
core. They are not meant for scripts.

### Hosted apps

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/apps` | Web apps listed in Huginn, with the kinds a new one can be. |
| POST | `/v1/apps` | Add one. The daemon checks it is reachable from every address its clients use, and refuses with `422` and the firewall rules that would fix it when it is not. |
| PATCH, DELETE | `/v1/apps/<id>` | Change or remove one. |
| POST | `/v1/apps/<id>/probe` | Check it again now. |
| GET | `/v1/apps/<id>/icon` | Its icon. |
| PUT | `/v1/apps/routes` | `{addrs, name?}`: the addresses this client reaches the server on (keyed by `X-Huginn-Client`), and the client's name for itself. Each address becomes one every app must answer on; a failing one is listed with `pinnedBy`, the clients that saved it. |
| DELETE | `/v1/apps/routes/<addr>` | Forget an address every client reported, so apps no longer have to answer there: for a retired address. A client that still saves it reports it again at its next launch. `404` when no client reports it. |

`/v1/consoles` and the paths under it are a deprecated spelling of `/v1/apps`.

### Scratchpads

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/scratchpads` | Scratchpad pages. The first call creates **Main**. |
| POST | `/v1/scratchpads` | `{name, content?}`: create a page. |
| GET, PATCH, DELETE | `/v1/scratchpads/<id>` | View, save or delete a page. `PATCH` needs the current `rev`; a stale one gets `409` with the current page. Main cannot be renamed or deleted. |

### Notifications

| Method | Path | What it does |
|---|---|---|
| GET, POST | `/v1/alerts` | Server-sent alerts through the relay script: `{enabled, mode: "fallback" \| "always"}`. |
| GET | `/v1/push` | Whether push is set up, and the registered phones (never their tokens). |
| POST | `/v1/push/register` | `{installId, token, model?}`: register a phone for push. |

### Desktop updates

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/desktop-kt/manifest` | An update feed for older desktop releases. Current releases update from GitHub. |
| GET | `/v1/desktop-kt/<file>` | A file from that feed. |

### Push: a waiting session request

A session waiting on its own `start_session` request reads `attention` while the request waits, so it gets the ordinary `session_attention` push, once, and a `session_resolved` when the request is answered or expires. Built from a question of kind `session`, that push carries **no buttons**: `options` is empty, as for `project_change` below, and the text names the directory and who asked (`session <name> asks to start a session in <directory>`). The prompt Start would paste fits only on the card, so the notification opens the session and the card answers. A client must not draw Start or Decline from this push, whatever `options` a daemon sends; `POST .../answer` refuses a session request by row anyway.

A chat has no `attention` state, so a request from a chat is its own push, `chat_attention`: `subject` is the chat id, `title` is `<chat title> needs you` (`A chat needs you` for an untitled chat), `text` the request's line (`a chat asks to start a session in <directory>`), `fingerprint` the request's content token, and `options` empty. It is sent once per request, through the same channels as `chat_finished` (Telegram carries it too when push reaches no device), and nothing is sent when the request is answered or expires. Like the session push it has **no buttons**: it opens the chat, whose card (`GET /v1/chats/<id>/questions`) answers.

### Push: `project_change`

Sent when a lead proposes a roster change. `payload: {projectId, rev, token}` (`changeRev` duplicates `rev`); `options` is empty — the push carries **no buttons**. The text Approve would type into members (the lead's new task for a re-roled role, the whole first prompt of an added one) fits only on the project page's card, so approval happens there: `POST /v1/projects/<id>/approve-change {rev, token}` and `POST /v1/projects/<id>/decline-change {token}` are the page's calls, and the push's `text` says to open the project. A client must not draw Approve from this push, whatever `options` a daemon sends. The `fingerprint` carries the token, so a proposal the lead rewrote is a new notification, not an update of the one being read.

### Push: a project's sessions

A project session is the lead or a member of a project that is not archived: a `GET /v1/sessions` row whose `project` is set. A project's sessions hand work to one another, so they are announced as one project:

- **No `session_finished`** for a project session, lead or member. Any other session still gets one when a run of at least 5 minutes ends, unless a terminal was attached to it while it ran.
- **`project_idle`**, once per working burst of the project. A burst starts when any of its sessions starts running and ends when every one of them has been quiet, neither `running` nor `attention`, for 5 minutes; a session that runs again sooner extends the same burst. The push is sent when the burst ends, and only if it worked at least 5 minutes, from its first run start to the last moment any of its sessions was running. `subject` is the project id, `title` `<project name> is idle`, `text` `Worked <duration>; <roles> ran.` (the roles that ran, in the order they first ran, the lead as `lead`: `Worked 42m; lead, guide, vault ran.`), `options` empty (**no buttons**) and `payload: {projectId}`: a tap opens the project. It goes through the same channels as `session_finished` (Telegram carries it when push reaches no device) and is not withheld for an attached terminal. Nothing is sent for a project archived or deleted before its sessions went quiet. A burst rides the alert watcher's saved observation, so one open across a daemon restart is kept; switching alerts on starts afresh and announces nothing already true.
- **`session_attention`** for a project session keeps its `subject` (the session name, so a tap opens that member) and its `text`; its `title` is `<project name> · <role> needs you`, `lead` for the lead. Any other session's is `<name> needs you`.
