# HTTP API

`huginn-appd` serves the HTTP API that huginn core, the desktop app and the Android app use. You
can call it from your own scripts and tools.

> **The API is not yet versioned and may change between daemon releases.** The `/v1` prefix is
> not a stability promise. When a field is renamed, the old name is usually kept for one release.
> Every response carries the daemon's version in the `X-Huginn-Appd` header.

## Base URL

```text
https://<server>:8787/v1/...        from another computer
http://127.0.0.1:8787/v1/...        on the server itself
```

The port is `HUGINN_APPD_PORT` (default `8787`). One port serves both protocols: HTTPS to
everyone, plain HTTP only to the server itself. A plain HTTP request from another address gets
`426` with `{"error": "...", "tls": true}`.

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
# on the server
curl -s -H "Authorization: Bearer $(cat /etc/huginn-appd/token)" http://127.0.0.1:8787/v1/ping
```

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
  -H "Authorization: Bearer $TOKEN" https://192.168.1.20:8787/v1/ping
```

`-k` skips the certificate-authority check, and `--pinnedpubkey` still refuses any other key. The
pin changes when the certificate is replaced.

## Conventions

- **JSON in and out.** Request bodies are JSON (`Content-Type: application/json`), at most 256 KiB
  unless a route says otherwise. Responses are JSON unless a route serves a file or a stream.
- **Errors** are a non-2xx status with `{"error": "<a sentence>"}`. Some `409` responses carry the
  current object instead, so a client can show what it collided with.
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
| Changes | `GET /v1/watch?stream=1` is a server-sent event stream. An `event: state` frame arrives when sessions, chats or usage change; a comment line (`: ka <time>`) every 25 seconds proves the connection is alive; `event: bye` after 30 minutes means reconnect. Without `stream=1`, the route is a long poll: pass the last `hash` you saw. |
| A chat run | `POST /v1/chats/<id>/messages?stream=1` answers with an event stream of the run. Each event has an `id:` (its sequence number) and one of the types `started`, `delta`, `assistant`, `tool_start`, `tool`, `result`, `error`, `done`. Reattach after a dropped connection with `GET /v1/chats/<id>/stream?since=<last id>`; the daemon replays what you missed. |
| A session's screen | `GET /v1/sessions/<name>/screen?hash=<h>&wait=<ms>` holds the request until the screen differs from `h`, or the wait ends. |

## Routes

All routes need the token except `GET /v1/challenge`.

### Daemon and server

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/challenge` | **Unauthenticated: no token.** `?nonce=<16-64 hex>` returns `{proof, version}`, plus `tls: {v, fp, proof}` over HTTPS. Limited to 20 requests per second per address. |
| GET | `/v1/ping` | `{ok, version, host, via: {addr, port}, secure}`: liveness, and which of the server's addresses you reached. Requires the bearer token, like every route except the challenge. |
| GET | `/v1/status` | Server health: uptime, load, disk, Claude Code version, the server's addresses, session and chat counts, usage summary, quick-action wording. |
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
| GET | `/v1/usage` | Token and cost summary for today and the last 7 days. |
| GET | `/v1/headroom` | The whole usage picture: every account's limits, what is stalled or held, and why. Timestamps in milliseconds. |
| PATCH | `/v1/headroom/settings` | Thresholds, model order, default model, auto-resume and account switching. |
| GET, POST | `/v1/autoswitch` | Deprecated alias for the account-switching part of `/v1/headroom/settings`. |

### Sessions

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/sessions` | Sessions and their states. `?preview=1` adds titles and activity previews. |
| POST | `/v1/sessions` | `{name}`: create a session running Claude Code. |
| DELETE | `/v1/sessions/<name>` | End the session at once. |
| POST | `/v1/sessions/<name>/rename` | `{name}`: rename it. |
| GET | `/v1/sessions/<name>/screen` | The terminal screen. `?cols=&rows=` report your size; `?live=1` also resizes the pane for you (one client at a time holds this lease); `?history=` adds scrollback; `?hash=&wait=` long-polls. |
| DELETE | `/v1/sessions/<name>/size` | Release your screen-size lease. |
| GET | `/v1/sessions/<name>/transcript` | The conversation as structured events. `?offset=` continues from a position. |
| POST | `/v1/sessions/<name>/keys` | `{text?, keys?, scratchpadId?}`: type into the session. `text` alone fills the prompt without sending; add `keys: ["Enter"]` to send. Allowed keys: `Enter`, `Escape`, `Tab`, `BTab`, `Space`, `BSpace`, `DC`, `IC`, arrows, `Home`, `End`, `PPage`, `NPage`, `C-a` to `C-z`, `M-a` to `M-z`, `F1` to `F12`. |
| GET | `/v1/sessions/<name>/typing` | What is waiting to be typed into the session, and what holds it. |
| POST | `/v1/sessions/<name>/answer` | `{option}`, or `{options: [...]}` for a multiple choice: answer the question on screen. `409` if that question is gone. |
| POST | `/v1/sessions/<name>/soft-end` | `{phrase?, auto?, force?}`: ask Claude Code to wrap up, then end the session once it settles. |
| POST | `/v1/sessions/<name>/compact` | Ask the session to compact its context. |
| POST | `/v1/sessions/<name>/archive` | Wrap up and archive the session. |
| POST | `/v1/sessions/<name>/meta` | `{goals?, notes?, autoResume?}`: per-session notes and settings. |
| GET | `/v1/sessions/<name>/suggestions` | Suggested next messages at a turn boundary. |
| GET | `/v1/sessions/<name>/overview` | What the run has spent and done. |
| GET | `/v1/sessions/<name>/graph` | A map of the run and its agents. |
| GET | `/v1/sessions/<name>/agents` | The agents behind a fan-out. `?all=1` adds every workflow member. |
| GET | `/v1/sessions/<name>/agents/<agentId>/transcript` | One agent's transcript. |
| POST | `/v1/sessions/<name>/headroom/undo` | Put a session back on the model the usage ladder moved it off. |

### Chats

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/chats` | Chats, newest first. |
| POST | `/v1/chats` | `{mode: "ask" \| "act", model?, effort?, title?, host?}`: create a chat; `host` is a device id to run it on instead of the server. Returns the chat with its `id` (`201`). |
| GET | `/v1/chats/<id>` | The chat, its transcript and any text still arriving. |
| PATCH | `/v1/chats/<id>` | `{title}`: rename it. |
| DELETE | `/v1/chats/<id>` | Delete it. Refused while a run is active. |
| POST | `/v1/chats/<id>/messages` | `{text, scratchpadId?}`: send a message. `?stream=1` streams the run; otherwise `202`. A message sent while a run is active is queued. |
| GET | `/v1/chats/<id>/stream` | `?since=<id>`: reattach to a running run. |
| POST | `/v1/chats/<id>/cancel` | Stop the run. |
| GET | `/v1/chats/<id>/transcript` | The chat as structured events, like a session's. |
| GET | `/v1/chats/<id>/suggestions` | Suggested next messages. |

See [Security](../security.md#chats-ask-and-act) for what each mode may do.

### Files

| Method | Path | What it does |
|---|---|---|
| POST | `/v1/uploads` | The raw file as the body (not multipart), with its `Content-Type`; `?name=` gives the original name. Up to 128 MB. Returns `{ok, path, bytes, ext, readable}`; name `path` in a message so Claude Code can open the file. |
| GET | `/v1/uploads/<name>` | An uploaded file. |
| GET | `/v1/files/image` | `?path=<path>[&session=<name>]`: an image on the server that a transcript names, from the uploads, the scratchpads, Claude Code's scratch folder, or the named session's working folder. |

### Rounds

| Method | Path | What it does |
|---|---|---|
| GET | `/v1/rounds` | Scheduled runs. |
| POST | `/v1/rounds` | `{title, prompt, goal?, schedule, ...}`: create one. `schedule` is `{kind: "weekly", days: [0-6], at: "HH:MM", tz?}`, or `daily` or `hourly`. |
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
| GET | `/v1/projects/<id>/dashboard` | Live totals and per-member state. |
| POST | `/v1/projects/<id>/spawn` | `{approve: true, manifestRev}`: approve the lead's proposal and create its members. |
| POST | `/v1/projects/<id>/discard` | Send a proposal back to the lead. |
| POST | `/v1/projects/<id>/message` | `{from, to, text}`: pass a message from one member to another. |
| POST | `/v1/projects/<id>/members` | `{name?, role}`: add a session as a member. |
| DELETE | `/v1/projects/<id>/members/<role>` | Remove a member. |

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
| PUT | `/v1/apps/routes` | The addresses this client reaches the server on (keyed by `X-Huginn-Client`). |

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
