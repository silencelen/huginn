# TLS between clients and huginn-appd

Since appd **3.11.0** every client talks to the daemon over TLS, with no
certificate authority and no setup. This page is the contract the daemon and
every client (Android, desktop, `huginn-device`) implement. Read it before
touching anything that dials appd.

## Why

The bearer token in `/etc/huginn-appd/token` is root-equivalent on the host: it
drives Claude Code sessions that run as root. Before 3.11 it crossed the network
in plain HTTP, which was only safe for people whose network was already
encrypted (Tailscale, a mesh VPN). huginn is a public project, so it has to be
safe on a flat home LAN, public Wi-Fi or a VPS with nothing in between.

## The daemon

- **One port, both protocols.** appd still listens on one port (8787 by
  default). It reads the first byte of each connection: `0x16` (a TLS handshake
  record) goes to the HTTPS server, anything else to plain HTTP. Clients move
  to `https://` on the address they already have.
- **Its own certificate.** On first start appd mints a self-signed P-256
  certificate (`openssl`, ten years) at `$HUGINN_APPD_DATA/tls/{cert,key}.pem`,
  key `0600`. To use a CA-issued certificate instead, set
  `HUGINN_APPD_TLS_CERT` and `HUGINN_APPD_TLS_KEY`. Clients pin either kind the
  same way. **Rotation:** delete both files and restart; clients re-prove the
  new certificate on their own (below).
- **Plain HTTP is loopback-only by default.** `HUGINN_APPD_PLAINTEXT=local` (the
  default) answers plain HTTP only from `127.0.0.0/8` and `::1`. Anything else
  gets `426` with `{"error": …, "tls": true}`, and that covers `/v1/challenge`
  too. `HUGINN_APPD_PLAINTEXT=any` restores the pre-3.11 behaviour for a
  migration window while old clients update; the daemon logs a warning at every
  start while it is set.
- `/v1/ping` reports `secure: true|false` for the connection it answered.

## The proof: a pin bound to the token

A self-signed certificate can't be checked against any authority, so the client
checks the **daemon** instead, using the secret both sides already hold, without
ever sending it:

```
client: nonce N = 32 random bytes, hex
client: open TLS, accept any certificate, record F = sha256(DER of the leaf), lowercase hex
client: GET /v1/challenge?nonce=N         (NO Authorization header)
daemon: {"proof": …legacy…, "version": "3.11.0",
         "tls": {"v": 1, "fp": "<its own fp>", "proof": HMAC-SHA256(token, "huginn-tls-v1:" + N + ":" + fp)}}
client: accept iff tls.proof == HMAC-SHA256(token, "huginn-tls-v1:" + N + ":" + F)   (constant time)
```

Someone intercepting the connection has to show the client their own
certificate M. The proof the client then expects is over `sha256(M)`, and only a
holder of the token can compute it. The real daemon's proof is over the real
fingerprint, so relaying it doesn't help.

**Rules every client follows:**

1. **Hash what your own handshake saw.** Never use `tls.fp` from the response
   body: an attacker would just report the real one. It is there for logs and
   display only.
2. **Never accept the legacy `proof` over TLS.** It proves the daemon, not the
   channel. A missing `tls` object over https means *not proved*.
3. **The probe connection carries no token and is never reused.** Close it after
   the challenge. Requests that carry the token go through a separate client
   whose TLS check is the pin.
4. **Pin, then send.** On a match, add F to the client's pin set for this daemon.
   Requests that carry the token accept a server certificate **if and only if**
   its leaf fingerprint is in the pin set. Hostname verification is off on that
   channel, because the pin replaces it: the certificate's name is `huginn-appd`
   and clients dial by IP. System CAs are not consulted.
5. **A changed certificate is re-proved, never accepted on sight.** If a
   connection fails because the leaf is not pinned, run the proof against that
   address. On a match the daemon has rotated: add the new pin, keeping at most
   four, newest first, and retry. If the proof fails, stop and tell the user:
   *"this address could not prove it is your huginn daemon; someone may be
   intercepting the connection."* Never fall back to plain HTTP.
6. **Plain HTTP only to loopback.** An `http://` URL whose host is loopback
   (`127.0.0.0/8`, `::1`, `localhost`) stays as it is. Any other `http://` URL
   is used as `https://` on the same host and port. Store the upgraded form
   where the client stores routes, so the user sees `https://`. There is no
   setting that re-enables cleartext to a remote host.
7. **Pins belong to the token.** One daemon has one certificate however many
   addresses (LAN, mesh, tunnel) reach it, so the pin set is per paired daemon,
   not per address. Re-pairing with a different token clears the pin set.
8. **A daemon older than 3.11** answers https with nothing (its port speaks only
   HTTP) or answers the challenge without `tls`. Report it as *"the daemon is
   older than 3.11 and has no TLS; update it."* Do not downgrade.

## Known limits

- **A TLS-terminating reverse proxy can't sit in front of appd.** The daemon
  proves its own certificate, and a proxy that ends TLS (Caddy, nginx, Traefik
  with its own certificate) shows clients a different one, so every client
  refuses it. That refusal is exactly what the proof exists to do. Use TLS
  passthrough (SNI routing with no termination), or give appd the CA-issued
  certificate directly with `HUGINN_APPD_TLS_CERT` and `HUGINN_APPD_TLS_KEY`.
- **Android can't speak plain HTTP to `[::1]`.** The network security config
  allows cleartext only to `localhost` and `127.0.0.1`, and an IPv6 literal
  can't be listed reliably. Use `http://127.0.0.1:<port>` on the phone.

## Test vector

```
token = "a" * 64
nonce = "00112233445566778899aabbccddeeff"
fp    = "0" * 64
HMAC-SHA256(token, "huginn-tls-v1:00112233445566778899aabbccddeeff:" + "0"*64)
      = 5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2
```

`server/appd/test/tls.test.js` asserts this value, and so does each client's
test suite, so every implementation agrees byte for byte.
