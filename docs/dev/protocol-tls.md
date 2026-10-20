# Daemon TLS protocol

This page is the contract between `huginn-appd` and every client that dials it. Read it before you
change anything that opens a connection to the daemon, and follow it exactly if you write a new
client.

The access token in `/etc/huginn-appd/token` is root-equivalent on the server. Every client sends it
on every request, often across a home LAN or public Wi-Fi with nothing else protecting it. The
daemon therefore speaks TLS by default, with no certificate authority and no setup, and each client
proves the daemon's certificate with the token before the token goes anywhere.

## Implementations

| Client | Language | Code |
|---|---|---|
| huginn core | Go | `core/internal/appd/` (`proof.go`, `pins.go`, `url.go`, `client.go`, `routes.go`) |
| Android app | Kotlin | `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/DaemonTls.kt` (the pure rules), `HuginnClient.kt` (when to prove and re-prove), `mobile/core/src/jvmShared/kotlin/com/silencelen/huginn/data/OkHttpDaemonTransport.kt` (the sockets) |
| Device runner | Node | `client/huginn-device`, which the shell client uses to offer a machine as a device when huginn core is not installed there |

The desktop app does no TLS of its own: it sends its requests to huginn core over the local socket,
and huginn core dials the daemon. See [architecture.md](architecture.md) and [go-core.md](go-core.md).
An older desktop install that still holds a token of its own hands it to huginn core on first start
(see [Kotlin clients](kotlin-clients.md)); until then it dials the daemon itself through the same
Kotlin client the Android app uses.

The daemon side lives in `server/appd/lib/tls.js` (certificate, proof, loopback test, plain-HTTP
policy, the one-port listener) and the request gate near the top of `handleRequest` in
`server/appd/huginn-appd.js`.

## The daemon

### One port, both protocols

The daemon listens on one port (8787 by default). It reads the first byte of each connection: `0x16`
(a TLS handshake record) goes to the HTTPS server, anything else to the plain HTTP server. A client
moves to `https://` on the address it already has; there is no second port to open.

- The HTTPS server accepts TLS 1.2 and later and speaks HTTP/1.1.
- A connection that sends nothing within 10 seconds is closed before either server sees it.
- A request whose headers are not complete within 60 seconds is answered `408` and closed. There is
  no limit on how long a request with a valid token may take to send its body (an upload on a slow
  link), but a request refused before the token check is closed if its body is still arriving 10
  seconds after the refusal.

### The certificate

On first start the daemon mints a self-signed certificate with `openssl`:

| Property | Value |
|---|---|
| Key | EC P-256 |
| Subject | `CN=huginn-appd` |
| Lifetime | 3650 days (a pin, not the expiry, is what clients trust) |
| Files | `$HUGINN_APPD_DATA/tls/cert.pem` and `key.pem` (default `/var/lib/huginn-appd/tls/`) |
| Modes | key `0600`, directory `0700`, written through a temporary name and a rename |

The daemon refuses to start, rather than fall back to plain HTTP, when `openssl` is missing, when the
key does not belong to the certificate, or when an operator-supplied file does not exist.

To rotate the certificate, delete both files and restart the daemon. Clients re-prove the new
certificate on their own (rule 5).

### Settings

Set these in the daemon's drop-in, `/etc/systemd/system/huginn-appd.service.d/site.conf`. See
[../reference/server-config.md](../reference/server-config.md).

| Variable | Default | Meaning |
|---|---|---|
| `HUGINN_APPD_PLAINTEXT` | `local` | Who may use plain HTTP. `local`: loopback only (`127.0.0.0/8`, `::1`, `::ffff:127.x`). `any`: every address, for a short migration window while old clients update; the daemon logs a warning at every start while it is set. Any other value stops the daemon at start. |
| `HUGINN_APPD_TLS_CERT` | unset | A certificate to serve instead of the minted one, for example one issued by your own CA. Set together with `HUGINN_APPD_TLS_KEY`, or neither. |
| `HUGINN_APPD_TLS_KEY` | unset | The private key for `HUGINN_APPD_TLS_CERT`. |

Clients pin an operator-supplied certificate exactly as they pin the minted one.

### What every response carries

- **Plain HTTP from off the server** (under the default policy) is answered before anything else,
  `/v1/challenge` included, with `426`, the headers `Upgrade: TLS/1.3, HTTP/1.1` and
  `Connection: close`, and the body `{"error": "...", "tls": true}`. A current client treats this as
  "dial the same address with `https://`".
- **`X-Huginn-Appd: <version>`** is on every response, the `401` included. A client can use it to
  tell the daemon from another HTTP server on the same address without sending the token.
- **The request log** marks any plain HTTP request from off the server with ` PLAIN <address>`, so you
  can find clients that still send the token in the clear.
- **`GET /v1/ping`** (authenticated) reports `"secure": true` or `false` for the connection it
  answered.

## The proof

A self-signed certificate cannot be checked against an authority, so the client checks the daemon
instead, using the secret both sides already hold, without sending it.

### The challenge route

`GET /v1/challenge?nonce=<N>` is the only route the daemon answers without a token.

| Input or answer | Rule |
|---|---|
| `nonce` | 16 to 64 hexadecimal characters, fresh for every probe. Anything else is `400`. huginn core sends 64 characters (32 random bytes); the Kotlin client sends 32. |
| Rate | 20 challenges per second per client address; more is `429`. |
| Answer over TLS | `{"proof": "<legacy>", "version": "<appd version>", "tls": {"v": 1, "fp": "<daemon's fingerprint>", "proof": "<bound proof>"}}` |
| Answer over plain HTTP (loopback) | The same without `tls`: there is no certificate to bind to. |

The bound proof does not depend on the nonce being secret: it binds the daemon's own certificate
fingerprint. A client should still draw the nonce from a secure random source.

### The exchange

```text
client: N = a fresh random nonce, lowercase hex
client: open TLS, accept any certificate,
        F = sha256(DER of the leaf certificate), lowercase hex, no separators
client: GET /v1/challenge?nonce=N              (no Authorization header)
daemon: {"proof": ..., "version": "...",
         "tls": {"v": 1, "fp": "<its own fingerprint>",
                 "proof": HMAC-SHA256(token, "huginn-tls-v1:" + N + ":" + fp)}}
client: proved if and only if
        tls.proof == HMAC-SHA256(token, "huginn-tls-v1:" + N + ":" + F)    (constant-time compare)
```

The HMAC key is the token's text as UTF-8 bytes; the output is lowercase hex. The `huginn-tls-v1:`
prefix separates this proof from the legacy one (an HMAC over the bare nonce): `:` never appears in a
valid nonce, so one proof can never stand in for the other.

Someone intercepting the connection has to show the client their own certificate M. The proof the
client then expects is over `sha256(M)`, which only a holder of the token can compute. The real
daemon's proof is over the real fingerprint, so relaying it does not help.

## Rules every client follows

1. **Hash what your own handshake saw.** Never use `tls.fp` from the response body: an interceptor
   would report the real one. It is there for logs and display only.
2. **Never accept the legacy `proof`.** It proves the daemon, not the channel, and the daemon gives
   it to anybody who asks, so a relay can fetch it for any nonce. A missing `tls` object over
   `https://` means not proved. A `tls.v` the client does not know also means not proved; the fix is
   to update the client. Plain HTTP has no certificate to bind a proof to, so nothing over plain
   HTTP is ever proved.
3. **The probe connection carries no token and is never reused.** Open a fresh connection for the
   challenge, send no `Authorization` header, and close it after the answer. Requests that carry the
   token go through a separate client whose TLS check is the pin.
4. **Pin, then send.** On a match, add F to the pin set. A request that carries the token accepts the
   server's certificate if and only if its leaf fingerprint is in the pin set. Hostname verification
   is off on that channel because the pin replaces it: the certificate is named `huginn-appd` and
   clients dial by IP address. System CAs are not consulted.
5. **A changed certificate is re-proved, never accepted on sight.** When a connection fails because
   the leaf is not pinned, run the proof against that address on a fresh probe connection. On a
   match, the daemon has rotated: add the new pin (at most four, newest first, a re-proved pin moved
   to the front) and retry once. Pin what the probe proved, never the certificate the failed
   handshake saw. If the proof fails, or the retry meets yet another unpinned certificate, stop and
   tell the user, word for word: *"this address could not prove it is your huginn daemon; someone may
   be intercepting the connection"*. Never fall back to plain HTTP.
6. **Every `http://` is dialled as `https://`, loopback included.** An `http://` URL is dialled as
   `https://` on the same host and port, and a daemon on this machine is proved and pinned like any
   other: a loopback port has no identity, and any account (or, on a phone, any app) can bind it
   while the daemon or a port-forward is down. Store the upgraded form where the client stores
   addresses, so the user sees `https://`. The one exception is the client setting
   `HUGINN_APPD_PLAINTEXT=local`, which mirrors the daemon's setting of the same name: with it, an
   `http://` URL whose host is loopback (`127.0.0.0/8`, `::1`, or the bare name `localhost`) stays
   plain HTTP and carries the token with no peer check. It is off by default, read from the
   environment of the process that dials, never on Android, and meant only for a daemon on this
   machine that has no TLS. Do not treat `*.localhost` or other names as loopback. There is no
   setting that re-enables cleartext to a remote host, and there must never be one.
7. **Pins belong to the token.** One daemon has one certificate however many addresses (LAN, VPN,
   tunnel) reach it, so the pin set is per paired daemon, not per address. Pairing with a different
   token starts a new, empty pin set.
8. **Refuse a daemon older than 3.11.** Such a daemon answers `https://` with plain HTTP, or answers
   the challenge without `tls`. Tell the user, word for word: *"the daemon is older than 3.11 and has
   no TLS; update it"*. Do not downgrade. **But only where TLS is not already known to be there.**
   When the client holds a pin for this token, the daemon has proved itself over TLS before, so the
   same answers come from something else: report them with the interception sentence of rule 5,
   never the old-daemon one, which would send the reader to update a current daemon on the word of
   whoever answered. On a loopback address with no pin, either sentence may lead, but the client
   adds that on this machine the answer may also be another program holding the port. Any hint
   follows the sentence; the sentence itself stays word for word.

### Implementation notes

These are not numbered rules, but the current clients do them and their tests hold them:

- **Judge the leaf inside the handshake.** The pin check runs as part of certificate verification
  (Go `tls.Config.VerifyConnection`, a pinning `X509TrustManager` on the JVM), so a refused peer
  never receives a byte of the request, `Authorization` included.
- **Give the handshake a deadline.** A peer that accepts TCP and never finishes TLS must fail the
  request, not hold it. huginn core uses 20 seconds for connect plus handshake and 15 seconds for the
  whole probe.
- **Do not reuse a connection across a pin change.** A pooled connection was judged against the pins
  of the moment it opened. Both clients drop idle connections when the token or the pin set changes;
  huginn core also turns off TLS session resumption.
- **Do not follow redirects.** The daemon never redirects, so a redirect is reported as it is.
  huginn core also ignores `HTTPS_PROXY` and similar settings, so the connection never passes through
  a third party.
- **Keep the token's path on the daemon.** huginn core refuses a request path that starts with `//`,
  carries a scheme or otherwise resolves to a different origin; the Kotlin client percent-encodes
  every path segment it builds from a name.
- **A wrong token looks like an interception.** When the proof fails but `tls.fp` equals the
  fingerprint the client saw, the clients append *" (if the token was changed recently, check it
  first)"* to the sentence in rule 5. This changes the wording only, never the verdict.
- **No token, no probe.** With an empty token the clients report an authentication error and send
  nothing; a proof under an empty key would fail against every daemon.

## Pin storage

The pin set is stored next to a short tag derived from the token, so pins cannot outlive a re-pair
even if a store forgets to clear them: a set whose tag does not match the current token reads as
empty. The tag is local to each client and never crosses the wire.

| Client | Where | Tag |
|---|---|---|
| huginn core | `pins` in `core.json`, keyed by tag | first 16 hex characters of `HMAC-SHA256(token, "huginn-tls-v1:pinset")` |
| Device runner | the `tls` object in its `device.json`: `{"token": <tag>, "pins": [...]}` | the same as huginn core, so huginn core can import it |
| Android app | the app's settings, as `{"owner": <tag>, "pins": [...]}` | first 16 hex characters of `SHA-256("huginn-tls-pins:" + token)` |

The two tag schemes differ, which is harmless: tags never leave the client, and a migrated pin set
costs one fresh proof.

Losing the pin store costs one re-proof, never a refusal. An unreadable store reads as empty.

## Several addresses

Because pins belong to the token, every address of one daemon is judged by the same pin.

- **huginn core** keeps a list of addresses (its route book). A request that never reached the
  daemon (connection refused or timed out, no route, a certificate that is not pinned and does not
  prove, or no TLS at all) is tried once on the next address, which becomes the active one. Nothing
  is retried once a request has left: an answer from the daemon, of any status, or a connection that
  broke mid-answer. With a single address, a failed proof stays the interception error from rule 5.
- **The Kotlin client** raises a failed proof as `DaemonTlsException`, which is deliberately not an
  HTTP error, so the app looks for another address as it does for an unreachable one.

## Known limits

- **A TLS-terminating reverse proxy cannot sit in front of the daemon.** The daemon proves its own
  certificate. A proxy that ends TLS (Caddy, nginx, Traefik with its own certificate) shows clients a
  different one, and every client refuses it, which is exactly what the proof is for. Use TLS
  passthrough (SNI routing without termination), or give the daemon the CA-issued certificate
  directly with `HUGINN_APPD_TLS_CERT` and `HUGINN_APPD_TLS_KEY`.
- **`HUGINN_APPD_PLAINTEXT=local` has no peer check.** A client run with it sends the token to
  whatever listens on the loopback port, and another local account that binds the port while the
  daemon restarts could read it. Leave it unset: the daemon serves TLS on loopback too, and huginn
  core, the device runner and the apps prove it there. The host-side tools in `server/bin` still
  speak plain HTTP to `127.0.0.1`, with the same limit.

See [../security.md](../security.md) for the daemon's wider threat model.

## Test vector

```text
token = "a" repeated 64 times
nonce = "00112233445566778899aabbccddeeff"
fp    = "0" repeated 64 times
HMAC-SHA256(token, "huginn-tls-v1:00112233445566778899aabbccddeeff:" + fp)
      = 5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2
```

Recompute it with Node:

```bash
node -e 'const c=require("crypto");
console.log(c.createHmac("sha256","a".repeat(64))
  .update("huginn-tls-v1:00112233445566778899aabbccddeeff:"+"0".repeat(64))
  .digest("hex"))'
```

Every implementation asserts this value, so they agree byte for byte:

| Implementation | Test | Run it with |
|---|---|---|
| Daemon | `server/appd/test/tls.test.js` | `cd server/appd && node --test` |
| huginn core | `TestBoundProofVector` in `core/internal/appd/unit_test.go` | `cd core && go test ./internal/appd` |
| Kotlin clients | `mobile/core/src/commonTest/kotlin/com/silencelen/huginn/DaemonTlsTest.kt` | `cd mobile && ./gradlew :core:jvmTest` |
| Device runner | `scripts/test-device-tls.js` | `node --test scripts/test-device-tls.js` (also run by `scripts/test-client.sh`) |

Beyond the vector, the suites test the behaviour end to end:

- `server/appd/test/tls.test.js` and `routes-tls.test.js`: certificate minting and reuse, the
  one-port listener, the idle timer, the `426` policy, and fatal starts.
- `core/internal/appd/tls_test.go`: a relayed proof over a different certificate refused, the legacy
  answer refused, an unknown `tls.v` refused, no downgrade, the leaf judged before any request byte,
  rotation re-proved once, no connection reuse across a pin change, redirects not followed.
- `mobile/core/src/jvmTest/kotlin/com/silencelen/huginn/data/TlsTransportIntegrationTest.kt`: the
  same properties over real JVM handshakes.
- `scripts/test-device-tls.js`: a real daemon from the tree behind a real TLS relay, with everything
  the relay decrypted searched for the token.
- Loopback, in all three clients (`core/internal/appd/loopback_test.go`, the loopback tests in
  `DaemonTlsTest.kt` and `TlsTransportIntegrationTest.kt`, and `scripts/test-device-tls.js`): a
  plain-HTTP listener on a loopback port, answering like a daemon, never receives an
  `Authorization` header, a relayed legacy proof is never adopted, and the device runner runs none
  of the work such a listener hands out.

If you change the proof input, give it a new `tls.v`: every client refuses a version it does not
know. Keep the vector in all four suites. See [testing.md](testing.md) for running the gates.
