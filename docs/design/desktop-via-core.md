# The desktop app through huginn core

**Status:** Implemented in huginn core 2.5.0 and 2.5.1 and Huginn Desktop 2.5.1, with setup
pairing the core in desktop 2.5.2.

**Summary:** the desktop app **never holds the appd token**. Every request it makes to the server
goes through huginn core on the same machine, over the core's local socket. To carry it, the core's
`/appd/*` proxy became a transparent reverse proxy (streaming in both directions, no fixed timeout,
headers and caller identity passed through), and the desktop's route book moved into the core. An
existing desktop install hands its link to the core once, and deletes its own token only after a
request through the core has worked.

## Decision

The maintainer decided, on 2026-10-16, that the desktop app sends everything through the core. The
alternative was "the desktop adopts the core's pairing": it copies the address, token and pin out of
the core and keeps talking to the server directly. That was rejected.

Why:
- One credential per machine, kept in one place: the core's secrets store. Rotating, retiring and
  uninstalling it are each one action.
- The desktop's settings file stops being a secret.
- A fresh desktop install on a machine whose core is paired has nothing to set up. Before this, a
  reinstalled desktop app did not pick up the core's configuration and asked for everything again.
- The core is the always-on part (boot service, tray). It is the right place for the link to the
  server.

Unchanged: the Android app (a phone has no core), the CLI's ssh paths, and appd's API.

## What the desktop sent

Measured by reading the code before the change:

| Kind | Where | Examples |
|---|---|---|
| JSON REST, about 90 calls (GET, POST, PATCH, PUT, DELETE) | `HuginnClient` (`mobile/core/.../data/HuginnClient.kt`), one instance built in `AppStore` | status, sessions, chats, rounds, projects, apps, devices, usage |
| SSE, read line by line | same | `GET /v1/watch?stream=1`, `GET /v1/chats/:id/stream?since=N`, **`POST /v1/chats/:id/messages?stream=1`** |
| Long polls (25 s wait) | `SessionController` | `GET /v1/sessions/:n/screen?...&wait=25000` (the Screen tab) |
| Raw binary upload (not multipart), with its real Content-Type | `AttachmentController` | `POST /v1/uploads?name=` |
| Binary downloads | | `/v1/uploads/:name`, `/v1/files/image`, `/v1/apps/:id/icon` |
| WebSockets | none | |

The client also sends per-client headers that appd relies on:
- `X-Huginn-Client` (`desktop-kt-<uuid>`): identifies the client for the pane-size lease, the notify
  claim and reported app routes.
- `X-Huginn-Notify`.

It also ran its own route book (LAN, mesh VPN, tailnet and custom addresses, with health checks and
automatic switching) and its own TLS pinning, keyed to the token.

## What the core's proxy carried before

`/appd/*` (`core/internal/daemon/proxy.go`) was built in 2.3.0 for the console: short JSON calls and
one GET stream. Through it, the desktop would have broken in seven places:

1. **Chat streaming.** A POST stream was buffered, and the chat stream GET carried neither
   `stream=1` nor an `Accept` header, so it was buffered too; both were cut at 20 s.
2. **The Screen tab's 25 s long poll**, and 150 s model calls, both against the 20 s cap.
3. **Uploads.** The Content-Type was forced to `application/json`, and appd picks the file's
   extension from it. The body was buffered and capped at 32 MiB, against appd's 128 MiB.
4. **Downloads** came back labelled `application/json`.
5. **Response headers** were dropped.
6. **Identity.** The desktop's `X-Huginn-Client` and `X-Huginn-Notify` were replaced by the core's.
7. **Addresses.** The core kept one appd URL: no route list, no failover.

## Design

### Core: a transparent reverse proxy (core 2.5.0)

- **Engine.** `httputil.ReverseProxy` over the core's existing appd transport. The director sets
  the URL from the pairing and adds `Authorization`; the transport's TLS pin is the core's, proved
  by the token.
- **Streaming in both directions, for every request:**
  - request bodies are streamed, never buffered, capped at appd's own 128 MiB;
  - response bodies are copied as they arrive (`FlushInterval: -1`), so SSE over GET or POST, long
    polls and downloads all work the same way.
- **Timeouts.** No fixed 20 s. A request lives as long as the caller's connection does: the
  desktop's own timeout tiers (30 s, 150 s, streams) stay authoritative. A transport-level idle
  limit, plus the dial and TLS timeouts, catches a dead peer.
- **Headers.**
  - Requests forward Content-Type, Accept, Range and `X-Huginn-*` (not `Authorization`, which the
    core sets).
  - Responses forward everything except hop-by-hop headers: Content-Type, Content-Disposition,
    `X-Content-Type-Options`, `X-Huginn-*` and caching headers.
- **Identity.** A caller's `X-Huginn-Client` and `X-Huginn-Notify` pass through unchanged. The core
  uses its own only when the caller sent none (the console, the tray). appd's per-client behaviour
  is unchanged: the pane lease, the notify claim and app routes.
  - This is safe because the caller is already this user: the socket is 0600 in a 0700 directory,
    and the peer is checked on every connection (`SO_PEERCRED` on Linux; on Windows
    `SIO_AF_UNIX_GETPEERPID` plus the token's SID).
- **Errors.** appd's own status codes and bodies pass through untouched. When appd cannot be
  reached, the core answers 502 with `{"error": "...", "kind": "unreachable" | "tls" | "unpaired" |
  "auth"}`, so the desktop can say the server is unreachable instead of failing to parse. Not
  paired stays 503 `unpaired`.
- **Scope.** Still `/v1/` only. The desktop needs nothing else, and `/core/*` stays separate.

Tests:
- End-to-end through the socket for each traffic kind above, against a real appd: POST SSE, GET
  SSE, a 25 s long poll, a 5 MB binary upload as `image/png`, an icon download with its headers, and
  the client-id pass-through.
- A mutation check that each of those fails against the old proxy.

### Desktop: the core is the transport (desktop 2.5.1)

- **Client.** `HuginnClient` gets a core transport, built through its existing `configure` hook.
  - It is OkHttp with `CoreSocketFactory` (the AF_UNIX socket the desktop already used for
    `/core/*`), a DNS stub and the `X-Huginn-Core-Proto` header.
  - Its base is `http://huginn-core/appd`. That is a plain-HTTP base, so the pinning interceptor
    never engages.
  - It sends no `Authorization` header. Everything above `HuginnClient` stays as it was.
- **Setup.** The address and token steps pair the core instead of the app:
  - Core paired: nothing to ask. The steps pass on their own.
  - Core not paired: the app asks for the address and token once and hands them to the core
    (`POST /core/pair`). The core proves the token against the address and keeps it in its secrets
    store; the app's settings never hold it.
- **Settings.** Removed: the token row, the app's own route book, the pins, and the dropped-route
  and route-health state. Settings > Host shows the core's pairing and route book, with pair again
  (`/core/pair`) and unpair (`/core/unpair`, which Remove this computer already used).
- **Migration** of an existing install, once, at start:
  - Core paired: verify that one request through the core works, then delete the token and pins
    from settings.
  - Core not paired, and settings hold a token: hand the desktop's URL and token to the core
    (`POST /core/pair`), verify, then delete them from settings. A desktop-only machine upgrades
    into a paired core with nothing asked.
  - The app's other addresses join the core's route book.
  - A core paired with a different server is never migrated into. "Different" is judged by the
    certificate the core proved (`GET /core/status` reports the fingerprints, which are public
    hashes), so the same server on a LAN address and a mesh address is recognised as one.
  - Whatever happens, the settings token is never deleted before a request through the core has
    succeeded.
- **Connection state.** The offline banner and the route witness read the core's 502 `kind` and its
  `/core/events` link state instead of probing on their own.
- **Escape hatch.** With `HUGINN_DESKTOP_DIRECT=1` in its environment, the app keeps a link of its
  own for that run.

### Addresses: the route book moves into the core

The core keeps a **route book**, as the desktop did:

- **Candidate addresses:** the address it was paired on, plus any added later, by the desktop's
  migration (its pinned routes) or by `huginn core route add <url>`. The book is kept in `core.json`.
- **Health per address:** each address has a last-seen and a last-failed time, from real requests
  and from a light background probe while one is failing.
- **Automatic switching** (on by default): a request that never reached the server is retried once
  on the next healthy address, and the active address moves there. That covers a refused, timed-out
  or unroutable connection, and an address whose certificate is not the server's (another machine
  at a home address); in that last case nothing is sent, because the certificate is judged before
  any byte. A request that may have reached the server is never sent again, even if its answer
  broke off.
- **Measured limits, as built:** a dead address is given up on in 6 s rather than the 20 s
  handshake limit, and the core moves back to the preferred address within a minute of it answering
  again.
- **Pinning:** the same certificate pin applies to every address, because it is appd's certificate,
  proved by the token, whatever the address.
- **Pairing with a different server clears the book.**

`huginn core route` lists the addresses with their health; `add <url> [--name] [--kind]`,
`remove`, `pin <url>|none` and `auto on|off` edit them. The desktop's Settings > Host shows the same
book through `/core/routes`.

## Decisions on the open questions

1. **The local AI tier gets its token from the core**, never through the desktop. The design named
   the call `/core/local/enrol`; it was built as **`POST /core/local/seed`**: the core writes the
   tier's `device/appd-token` from its own secrets store, as the user, and answers with the address
   to turn the tier on with (the one the core uses now). On Windows, where the tier's services run
   from ProgramData, the manager's own elevated step reads the token from there.
2. **Order: core 2.5.0 first, then the desktop.** A desktop that finds an older core keeps its
   direct link until the core is updated.
3. **The route book moves into the core** (above). It is not retired.

## Out of scope

The Android app, appd's API, and token scoping on appd (a device-scoped token instead of the master
token: worth doing, separately).
