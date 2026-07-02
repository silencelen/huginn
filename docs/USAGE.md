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
| `huginn usage [args]` / `cost` | Claude Code token/cost report ([ccusage]) — e.g. `usage monthly`, `session`, `blocks --live` |
| `huginn usage <when>` | shortcut date range: `today` \| `yesterday` \| `week` \| `month` — e.g. `usage today`, `usage week session` |
| `huginn update` | self-update the client from the repo (`gh` → `scp` fallback) |
| `huginn version` | print the client version + target host |
| `huginn help` / `?` / `/help` | this reference |
| `rclaude` / `rcc` | aliases for `huginn` |

`<Tab>` completes subcommands. Override the target host per-device with `HUGINN_HOST` (PowerShell: `$env:HUGINN_HOST`, bash: `export HUGINN_HOST`).

[ccusage]: https://github.com/ryoppippi/ccusage

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

## Staying connected (auto-reconnect)

Your session lives in tmux **on the host**, so a dropped link — laptop sleep, Wi-Fi flap, a sketchy tunnel — only kills the local `ssh` client, not the work. The attach transparently re-runs and drops you back in:

- It reconnects on **any non-zero `ssh` exit** (the dropped-link signal). A clean detach (`Alt-d`) or normal exit returns `0` and ends cleanly — those don't reconnect.
- SSH keepalives (`ServerAliveInterval`/`CountMax`) make a half-open socket after sleep die in ~45s instead of hanging, then it retries with a short backoff.
- On reconnect it picks **mirror vs solo dynamically**: mirror if another device is still attached, otherwise solo (full-screen) — which also evicts the stale "ghost" client the dead link left behind.
- During the retry wait, press **`Ctrl-C`** to stop. To turn the whole behavior off, set **`HUGINN_NO_RECONNECT=1`** (`$env:HUGINN_NO_RECONNECT='1'`).

## Named terminal tabs

The attach renames your terminal tab/window to **`huginn:<session>`** — so `huginn costtracking` shows a `huginn:costtracking` tab in Windows Terminal (and iTerm/Termux) — and restores the previous title when you leave. Disable with **`HUGINN_NO_TITLE=1`** (`$env:HUGINN_NO_TITLE='1'`). If a tab won't rename, check your terminal isn't configured to suppress application title changes (or has a pinned tab title).

## Environment variables

| Variable | Effect |
|---|---|
| `HUGINN_HOST` | target host / SSH alias (default `huginn`) |
| `HUGINN_NO_RECONNECT` | set to `1` to disable auto-reconnect |
| `HUGINN_NO_TITLE` | set to `1` to disable terminal-tab naming |
| `HUGINN_WORKDIR` *(host)* | working directory new sessions open in (default `$HOME`) |

## Headless one-shots

`huginn -p "..."` runs a single prompt and prints the answer (no tools — safe for quick questions). `huginn -y "..."` allows tools (bash/files/web) for "go do it" tasks. Both bill against whatever the host's Claude Code is authenticated with.

> Note: Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist instead. Run the node as a non-root user if you want broader headless autonomy.
