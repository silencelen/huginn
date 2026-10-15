# Prototype Q1 — the GUI speaks HTTP to the core over AF_UNIX

`docs/CORE-ARCHITECTURE.md` §11 Q1. Not product code.

- `server/` — Go: `net/http` on an AF_UNIX listener, dir 0700 + socket 0600 + a
  peer check on every accept (Linux `SO_PEERCRED`; Windows `SIO_AF_UNIX_GETPEERPID`
  → process token → SID compare), `X-Huginn-Core-Proto` gate (426), SSE, upload,
  download, a proxy-shaped `/appd/*` route that strips `Authorization`.
- `client/UnixSocketProbe.java` — `CoreSocket`, a `java.net.Socket` over a
  non-blocking `SocketChannel(UNIX)`, plugged into OkHttp 4.12.0 (the engine under
  the app's Ktor client) through `socketFactory` + a fixed `dns`. 10 cases.
- `run.sh [workdir]` — builds both, runs the cases, then an other-uid peer test.

Run: `./run.sh` (needs go, JDK 17, and the app's gradle cache for the jars).
