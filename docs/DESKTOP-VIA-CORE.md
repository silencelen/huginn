# Huginn Desktop through huginn core

Status: **shipped** in core 2.5.0/2.5.1 and Huginn Desktop 2.5.1 (2026-10-16). The design was approved
by the owner on 2026-10-16; the decisions on its open questions are recorded below.

## Decision

Owner, 2026-10-16: the desktop app **never holds the appd token**. Every request it makes to huginn
goes through the huginn core on the same machine, over the core's local socket. The other option
was "the desktop adopts the core's pairing" (it copies the address, token and pin out of the core);
the owner chose this one instead.

Why it matters:
- One credential per machine, kept in one place: the core's secrets store. Rotating, retiring and
  uninstalling it are each one action.
- The desktop's settings file stops being a secret.
- A fresh desktop on a machine whose core is paired has nothing to set up. That was the complaint
  from the PRESTIGE reinstall: "the desktop didn't pick up the core's config".
- The core is the always-on part (boot service, tray). It is the right owner for the link to huginn.

Unchanged: the Android app (a phone has no core), the CLI's ssh paths, and appd's API.

## What the desktop sends today

Measured by reading the code (2026-10-16 inventory):

| Kind | Where | Examples |
|---|---|---|
| JSON REST, about 90 calls (GET/POST/PATCH/PUT/DELETE) | `HuginnClient` (mobile/core/.../data/HuginnClient.kt), one instance built in `AppStore` | status, sessions/*, chats/*, rounds, projects, apps, devices, usage |
| SSE, read line by line | same | `GET /v1/watch?stream=1`, `GET /v1/chats/:id/stream?since=N`, **`POST /v1/chats/:id/messages?stream=1`** |
| Long polls (25 s wait) | `SessionController` | `GET /v1/sessions/:n/screen?…&wait=25000` (the Screen tab) |
| Raw binary upload (not multipart), real Content-Type | `AttachmentController` | `POST /v1/uploads?name=` |
| Binary downloads | | `/v1/uploads/:name`, `/v1/files/image`, `/v1/apps/:id/icon` |
| WebSockets | none | |

The client also sends per-client headers that appd relies on:
- `X-Huginn-Client` (`desktop-kt-<uuid>`): the pane-size lease owner, the notify claim and reported
  app routes.
- `X-Huginn-Notify`.

It also runs its own route book (LAN, Yggdrasil, tailnet, custom; health and auto-switch) and TLS
pinning, keyed to the token.

## What the core's proxy carries today

`/appd/*` (core/internal/daemon/proxy.go) was built for the console: short JSON calls and one GET
stream. Through it, the desktop would break:

1. **Chat streaming.** A POST stream is buffered, and the chat stream GET carries neither
   `stream=1` nor an `Accept` header, so it is buffered too; both are cut at 20 s.
2. **The Screen tab's 25 s long poll,** and the 150 s model calls, both against the 20 s cap.
3. **Uploads.** The Content-Type is forced to application/json, and appd picks the file's
   extension from it. The body is buffered and capped at 32 MiB, against appd's 128 MiB.
4. **Downloads** come back labelled application/json.
5. **Response headers** are dropped.
6. **Identity.** The desktop's `X-Huginn-Client` and `X-Huginn-Notify` are replaced by the core's.
7. **Addresses.** The core keeps one appd URL: no route list, no failover.

## Design

### Core: the proxy becomes a transparent reverse proxy (core 2.5.0)

- **Engine.** `httputil.ReverseProxy` over the core's existing appd transport. The Director sets
  the URL from the pairing and adds `Authorization`; the transport's TLS pin is the core's,
  "proved by the token".
- **Streaming.** Streaming both ways, for every request:
  - request bodies are streamed, never buffered, capped at appd's own 128 MiB;
  - response bodies are copied as they arrive (`FlushInterval: -1`), so SSE over GET or POST, long
    polls and downloads all work the same way.
- **Timeouts.** No fixed 20 s. A request lives as long as the caller's connection does: the
  desktop's own timeout tiers (30 s, 150 s, streams) stay authoritative. A transport-level idle
  limit, plus the dial and TLS timeouts, catches a dead peer.
- **Headers.**
  - Requests forward Content-Type, Accept, Range and `X-Huginn-*` (not Authorization, which the
    core sets).
  - Responses forward everything except hop-by-hop headers: Content-Type, Content-Disposition,
    `X-Content-Type-Options`, `X-Huginn-*`, caching headers.
- **Identity.** A caller's `X-Huginn-Client` and `X-Huginn-Notify` pass through unchanged. The core
  uses its own only when the caller sent none (the console, the tray). appd's per-client behaviour
  is unchanged: the pane lease, the notify claim and app routes.
  - This is safe because the caller is already this user: the socket is 0600 in a 0700 directory,
    and the peer is checked per connection (`SO_PEERCRED`; on Windows `SIO_AF_UNIX_GETPEERPID` plus
    the token SID).
- **Errors.** appd's own status codes and bodies pass through untouched. When appd cannot be
  reached, the core answers 502 with `{"error": "...", "kind": "unreachable" | "tls" | "unpaired" |
  "auth"}`, so the desktop can show "huginn is unreachable" and not a parse failure. Not paired
  stays 503 `unpaired`.
- **Scope.** Still `/v1/` only. The desktop needs nothing else, and `/core/*` stays separate.

Tests:
- e2e through the socket for each traffic kind above, against a real appd: POST SSE, GET SSE, a
  25 s long poll, a 5 MB binary upload with `image/png`, an icon download with its headers, and the
  client-id pass-through.
- A mutation check that each one fails against today's proxy.

### Desktop: the core is the transport (desktop 2.5.0)

- **Client.** `HuginnClient` gets a core transport, built through its existing `configure` hook.
  - It is OkHttp with `CoreSocketFactory` (the AF_UNIX socket the desktop already uses for
    `/core/*`), the DNS stub and the `X-Huginn-Core-Proto` header.
  - Its base is `http://huginn-core/appd`. That is a plain-http loopback base, so the pinning
    interceptor never engages.
  - It sends no Authorization header. Everything above `HuginnClient` stays as it is.
- **Setup.** The ROUTE and TOKEN steps are replaced by one "huginn core" step, after the CoreGate
  the app already has:
  - Core paired: nothing to ask. The step shows "connected to huginn at <address> through huginn
    core".
  - Core not paired: the app asks for the address and token once and hands them to the core
    (`POST /core/pair`). The token goes into the core's secrets store and never into the desktop's
    settings.
- **Settings.** Removed: the token row, the route book UI, the pins, `droppedUrl`, `routeHealth`
  and the `/etc/huginn-appd/token` dev read. Settings > Host shows the core's pairing read-only,
  with "Pair again" (it calls `/core/pair`) and "Unpair" (`/core/unpair`, as Remove this computer
  already does).
- **Migration** of an existing install, once, at start:
  - Core paired: verify one request through the core works, then delete the token, pins and routes
    from settings.
  - Core not paired, and settings hold a token: hand the desktop's URL and token to the core
    (`POST /core/pair`), verify, then delete them from settings. A desktop-only machine
    "upgrades" into a paired core with nothing asked.
  - Whatever happens, never delete the settings token before a request through the core has
    succeeded.
- **Connection state.** The offline banner and RouteWitness read the core's 502 `kind` (and the
  core's `/core/events` link state) instead of their own probes.

### Addresses: the route book moves into the core (owner decision)

The core keeps a **route book**, as the desktop does today:
- **Candidate addresses.** The address it was paired on, plus any added later:
  - by the desktop's migration (its pinned routes);
  - by `huginn core route add <url>`;
  - learned from appd.
- **Health per address.** Each address has a last-seen and a last-failed time, from real requests
  and from a light background probe while one is failing.
- **Auto-switch** (on by default). A request that cannot reach the active address is retried once
  on the next healthy one, and the active address moves there.
- **Pinning.** The same certificate pin applies to every address: it is appd's certificate,
  proved by the token, whatever the address.

The desktop's Settings > Host shows the book: list, add, remove, pin one, auto-switch on/off.
Edits are `/core/routes` calls, and the core persists them in core.json.

## Decisions (owner, 2026-10-16)

1. **The local AI tier gets its token from the core.** A new `/core/local/enrol` writes the tier's
   `device/appd-token`. On Windows, ProgramData is written inside the elevated step the tier's
   manager already takes, and the core hands the token to that step over a file only this user can
   read. The token never passes through the desktop.
2. **Order: core 2.5.0 first, then desktop 2.5.0.** A desktop that finds an older core keeps its
   direct link until the core is updated.
3. **The route book moves into the core** (above). It is not retired.

## Out of scope

The Android app, appd's API, and token scoping on appd (a device-scoped token instead of the
master token: worth doing, separately).
