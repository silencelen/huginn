# Architecture

Huginn is deliberately small — a few shell scripts and a tmux config. The value is the *pattern*, not the code.

```
   device A (laptop)         device B (phone)
        │  huginn                 │  huginn
        └──────────┬──────────────┘
                   │  ssh -tt huginn cc       (the `huginn` command = a thin,
                   │                            auto-reconnecting ssh wrapper)
                   ▼
        ┌─────────────────────────────┐
        │   Huginn host (always-on)   │
        │                             │
        │   /usr/local/bin/cc  ──────────►  tmux session 'main'
        │                             │        └─ claude (Claude Code)
        │   ~/.tmux.conf              │
        │   /usr/local/bin/huginn-status
        └─────────────────────────────┘
```

## Pieces

- **`huginn` (client)** — a shell function that wraps `ssh`. `huginn` runs an auto-reconnect loop around `ssh -tt huginn cc`; subcommands map to small remote commands (`tmux ls`, `tmux kill-session`, `claude -p`, …). It carries no *session* state — the host holds everything — but does two ergonomic jobs locally: re-attaching after a dropped link, and naming the terminal tab after the session. Targets a `Host huginn` SSH alias, overridable via `HUGINN_HOST`.
- **`cc` (server)** — `tmux new-session -A` (attach-or-create). `cc solo` uses `tmux attach -d` to detach other clients on the way in. This is what makes sessions persistent and reattachable.
- **`tmux.conf` (server)** — the multi-device ergonomics: `window-size smallest` (mirror fits the smaller screen), `Alt-d` detach, `Alt-o` detach-others, big scrollback, status-bar hints.
- **`huginn-status` (server)** — a one-glance health summary.

## Why it's shaped this way

- **State lives on the host, not the client.** Every device is a thin viewer; nothing to sync. Close the laptop mid-task, open the phone, you're in the same place.
- **tmux is the persistence + multi-client layer.** "Persistent session," "mirror," and "reattach" are all native tmux — Huginn just packages sensible defaults and a friendly command.
- **SSH is the only transport.** No daemon, no web server, no ports beyond SSH. Add Tailscale/WireGuard for off-LAN and it works from anywhere.
- **Subscription auth is the cost story.** Claude Code can log in with a Max/Pro subscription — flat cost, no per-token billing — so an always-on agent you talk to all day doesn't run up an API bill. (API key still works if you prefer metered.)

## Client-side resilience

The host is the source of truth, but the client does a little work so the connection *feels* seamless:

- **Auto-reconnect.** Because the session lives in tmux on the host, a dropped link (sleep, Wi-Fi flap) only kills the `ssh` client. The attach is a loop: re-run `ssh` on any non-zero exit (the drop signal), stop on `0` (a clean `Alt-d` detach or normal exit). SSH keepalives bound the post-sleep hang to ~45s. On reconnect it reads `tmux list-clients` and picks **mirror** (another device is attached) or **solo** (only the dead "ghost" client remains → evict it, go full-screen) — atomically, in one remote command, so `cc` itself stays untouched. Opt out: `HUGINN_NO_RECONNECT=1`.
- **Named tabs.** The client sets the terminal title to `huginn:<session>` before attaching. tmux's default `set-titles off` means the inner Claude TUI's own title escapes never reach the outer terminal, so the title sticks for the whole session and is restored on exit. Opt out: `HUGINN_NO_TITLE=1`.

### One constraint: the clients are pure ASCII

`huginn update` can fetch the client over `scp`, which carries no byte-order mark. Windows PowerShell 5.1 then decodes a BOM-less file as the system ANSI code page — non-ASCII bytes (box-drawing, em-dashes) get mangled and the parser breaks. So `client/huginn.{ps1,sh}` are kept **ASCII-only**; they then parse identically whether fetched via `gh` (which writes a BOM) or `scp` (none), on PowerShell 5.1 or 7. Keep it that way — the docs can use whatever characters they like, but the two client files cannot.

## Extending it

- **MCP servers / memory.** Add MCP servers to the host's Claude Code (`claude mcp add ...`) for tools and persistent memory across sessions. (The author pairs Huginn with a separate memory node — "Muninn" — but that's out of scope here.)
- **More headless surfaces.** The `huginn -p`/`-y` headless path (`claude -p`) is the substrate for bots/automation — wire it to a chat bridge, a cron job, or a webhook.
- **Non-root user.** Running Claude Code as a dedicated non-root user is better practice and unlocks broader headless autonomy.

## Naming

Huginn and **Muninn** are Odin's two ravens — *thought* and *memory* — who fly out over the world each day and return to tell him what they saw. Huginn here is the thinking surface (the agent you reach from anywhere); memory is a natural companion piece.
