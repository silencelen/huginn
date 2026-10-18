// Package version is the one place the core's version and IPC protocol live.
package version

// Version is the lockstep client version (core + GUI ship together; D10 in
// docs/design/core.md). Release tooling stamps it with -ldflags -X.
var Version = "2.0.0-dev"

// Proto is the IPC protocol version, sent as X-Huginn-Core-Proto on every
// request between the GUI/tray and the core. Bump it on any incompatible
// change to /core/* or /appd/* semantics.
const Proto = "1"
