# bubbletea v1.3.10, patched for huginn

An unmodified copy of github.com/charmbracelet/bubbletea v1.3.10 (MIT, see
LICENSE), wired in by the `replace` in core/go.mod, with ONE change: its
`tea_init.go` is left out.

That file's `init()` calls `lipgloss.HasDarkBackground()`, which makes termenv
put the terminal in raw mode, write an OSC 11 background query plus a cursor
report request, and read the answer from the terminal (up to 5 s,
termenv.OSCTimeout). Because huginn is one binary, the console's packages are
linked into every command, so EVERY `huginn ...` on a terminal did this at
startup: a round trip per command over ssh, a 5 s stall in a terminal that
does not answer, and keystrokes typed ahead (the "uninstall" answer to `huginn
uninstall`'s prompt) eaten as if they were the reply.

The query exists so a Bubble Tea program does not hang waiting for an answer
after it has taken the terminal. huginn makes the same call in
internal/console.Run, just before the program starts, so the console keeps that
protection and nothing else pays for it. Upstream says the workaround goes in
v2; drop this copy when the console moves to v2.

To refresh: copy the new version's non-test .go files, go.mod and LICENSE here,
delete tea_init.go, and update the version in this file and in core/go.mod.
