# Design records

A design record explains how and why one of Huginn's larger pieces was designed: the problem it
solved, the decisions taken, the alternatives that were rejected, and the measurements that settled
a question. Records are kept after the work ships, so the reasoning stays next to the code.

Records are historical. Each one describes the design at the time it was decided and built, and
later releases may have changed details. **For how Huginn behaves now, read the current
documentation:**

- [docs/guide](../guide/README.md): using Huginn.
- [docs/reference](../reference/README.md): commands, environment variables, server configuration
  and the API.
- [docs/dev](../dev/README.md): architecture, building and testing, for contributors.

When a record and the current documentation disagree, the current documentation wins.

## Reading a record

Every record opens with a short header:

| Field | Meaning |
|---|---|
| **Status** | `Implemented in <versions>`, `Partly implemented`, `Complete` (a finished migration), or `Superseded by <record>`. |
| **Summary** | One paragraph: what was decided, and why. |

Versions name the component they belong to. Since 2.0.0 the terminal client, huginn core and the
desktop app share one version number (for example core 2.5.0 and desktop 2.5.0); before that the
CLI and the desktop app were numbered separately. The server daemon (`huginn-appd`, "appd") and
the Android app have version numbers of their own. Decision numbers
such as D1 or Q4 are local to the record they appear in.

## Index

| Record | Status | What it covers |
|---|---|---|
| [huginn core](core.md) | Implemented in core 2.0.0 to 2.5.x; some phases not built | The `huginn` binary becomes the core of every client install: a per-user boot service that holds the server link, runs device work, owns the tray and notifications, and carries the CLI. The desktop app becomes a window on top of it. |
| [The console](console.md) | Implemented in core 2.3.0 | The full-screen terminal interface that bare `huginn` opens, built into the core binary. |
| [The desktop app through huginn core](desktop-via-core.md) | Implemented in core 2.5.0 and 2.5.1, desktop 2.5.1 | The desktop app stops holding the server token and sends every request through huginn core, which gains a transparent proxy and a route book. |
| [The local AI tier](local-tier.md) | Implemented in appd 2.73.0 to 2.75.0 and CLI 0.11.0 to 1.1.0; experimental | Serving local models from a client computer to the server, behind a `generate` policy rung that never substitutes one engine for another. |

### Archive

Finished migrations, kept for their reasoning.

| Record | Status | What it covers |
|---|---|---|
| [Desktop: Electron to Compose Multiplatform](archive/desktop-electron-to-compose.md) | Complete | Why the desktop client moved from Electron to Compose Multiplatform, sharing one Kotlin codebase with the Android app, and what the spikes proved. |
