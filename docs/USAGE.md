# Usage

## Command

| Command | What it does |
|---|---|
| `huginn` | attach (or create) the live **`main`** session; run `claude` / `claude --resume` inside |
| `huginn <name>` | a separate named session (e.g. `huginn work`) |
| `huginn solo [name]` | attach **and detach all other clients** — resume full-screen (kick your phone) |
| `huginn list` / `ls` | list running sessions + attach status |
| `huginn status` / `st` | health: uptime, auth (subscription), sessions, disk |
| `huginn rename <old> <new>` / `mv` | rename a session (e.g. promote `main` to a name, freeing `main`) |
| `huginn kill <name>` | end a session |
| `huginn -p "question"` | one-shot **headless** query — reasoning only (no tools) |
| `huginn -y "task"` | one-shot that **may use tools** (bash / files / web) |
| `huginn help` / `?` / `/help` | this reference |
| `rclaude` | alias for `huginn` |

`<Tab>` completes subcommands. Override the target host per-device with `HUGINN_HOST` (PowerShell: `$env:HUGINN_HOST`, bash: `export HUGINN_HOST`).

## Inside a session (tmux keys)

| Key | Action |
|---|---|
| **`Alt-d`** | detach (leave the session running) — `Ctrl-b d` also works |
| **`Alt-o`** | detach **all other** clients — go full-screen on this device |
| **`Ctrl-b [`** | enter scroll mode (then `PageUp`/arrows; `q` to exit) |
| `Ctrl-b c` | new window (tab) · `Ctrl-b n`/`p` next/prev · `Ctrl-b ,` rename |
| `Ctrl-b w` | window/session picker |

The status bar shows the handiest of these on the left.

## The mirror model

When **two devices attach the same session**, tmux mirrors them and sizes the window to the **smaller** screen, so both see the full content (great for watching a long run from the couch).

When you only want one device full-screen:
- coming back fresh → **`huginn solo`** (kicks the others on attach), or
- already attached in mirror → press **`Alt-o`**.

Different session **names** are fully independent (no mirroring) — use them to keep separate work apart: `huginn`, `huginn work`, `huginn scratch`.

## Headless one-shots

`huginn -p "..."` runs a single prompt and prints the answer (no tools — safe for quick questions). `huginn -y "..."` allows tools (bash/files/web) for "go do it" tasks. Both bill against whatever the host's Claude Code is authenticated with.

> Note: Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist instead. Run the node as a non-root user if you want broader headless autonomy.
