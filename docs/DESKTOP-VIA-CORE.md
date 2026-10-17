# Huginn Desktop through huginn core

Status: **design, for owner review** (2026-10-16). Nothing here is built yet.

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
  from the PC01 reinstall: "the desktop didn't pick up the core's config".
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

### Addresses (routes)

The core keeps one URL for now. On PC01 that is enough since the LAN-first and Yggdrasil work:
`https://192.168.10.20:8787` answers at home (LAN) and away (the mesh's unsafe route to the home
LAN).

If it is needed later, a route book moves into the core rather than staying in the desktop:
- candidates learned at pairing time and from appd;
- health checks and failover;
- the same certificate pin on every address.

## Open questions for the owner

1. **The local AI tier from the desktop.** "Serve local models from this machine" (Settings > This
   machine) passes the URL and token to the tier's manager, which writes `<localData>/device/appd-token`
   for the tier's always-on runner service. With no token in the desktop, either:
   - (a) the core writes that file on request (a new `/core/local/enrol`; on Windows the ProgramData
     path needs the elevated step the manager already makes); or
   - (b) the desktop's button runs `huginn local on`, which fetches the token over ssh, as the CLI
     does.
   Recommended: **(a)**. It works without ssh, and the token moves core to tier without passing
   through the app.
2. **Order.** Recommended: core 2.5.0 first (the proxy is useful to the console too), then desktop
   2.5.0 behind it. A desktop that finds an older core keeps its direct link until the core is
   updated (`huginn update` updates both).
3. **The route book.** Recommended: retire it with this change and add a core route book only if a
   machine needs it. Today one URL plus the mesh covers home and away.

## Out of scope

The Android app, appd's API, and token scoping on appd (a device-scoped token instead of the
master token: worth doing, separately).
