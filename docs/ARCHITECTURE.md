# Architecture

Huginn has a small core and an optional large half. The core — the `huginn` command, `cc`, a tmux config — is a few shell scripts, and there the value really is the *pattern*, not the code. The optional half is not small: `huginn-appd` is a root-owned Node daemon of roughly 6,700 lines that puts an HTTP surface on the host, and the Kotlin phone/desktop clients that talk to it are another ~33,000. Which half you deploy decides what your security model has to cover, so the two are kept separate throughout this document.

```
   terminal client                      app client
   (laptop · phone · Termux)            (Android · desktop)
        │  huginn                            │  HTTP + SSE, port 8787
        │  ssh -tt huginn cc                 │  Authorization: Bearer <token>
        └─────────────────┬──────────────────┘
                          ▼
        ┌─────────────────────────────────┐
        │   Huginn host (always-on)       │
        │                                 │
        │   /usr/local/bin/cc  ─────────────►  tmux session 'main'
        │   ~/.tmux.conf                  │       └─ claude (Claude Code)
        │   /usr/local/bin/huginn-status  │             ▲
        │                                 │             │ capture-pane
        │   huginn-appd (root)  ────────────────────────┘ send-keys
        └─────────────────────────────────┘
```

Both clients end up at the same place: one Claude Code process in one tmux session. The terminal attaches to it; the app reads it with `capture-pane` (plus the session's Claude Code transcript) and drives it with `send-keys`.

## Pieces

- **`huginn` (client)** — a shell function that wraps `ssh`. `huginn` runs an auto-reconnect loop around `ssh -tt huginn cc`; subcommands map to small remote commands (`tmux ls`, `tmux kill-session`, `claude -p`, …). It carries no *session* state — the host holds everything — but does two ergonomic jobs locally: re-attaching after a dropped link, and naming the terminal tab after the session. Targets a `Host huginn` SSH alias, overridable via `HUGINN_HOST`.
- **`cc` (server)** — `tmux new-session -A` (attach-or-create). `cc solo` uses `tmux attach -d` to detach other clients on the way in. This is what makes sessions persistent and reattachable.
- **`tmux.conf` (server)** — the multi-device ergonomics: `window-size smallest` (mirror fits the smaller screen), `Alt-d` detach, `Alt-o` detach-others, big scrollback, status-bar hints.
- **`huginn-status` (server)** — a one-glance health summary.
- **`huginn-appd` (server, optional)** — the daemon the apps talk to: sessions, headless chats, push notifications, prompts-as-buttons. Zero npm dependencies, Node ≥ 20, runs as **root**, listens on port 8787 (Tailscale address by default, `HUGINN_APPD_BIND` to change it) and requires `Authorization: Bearer <token>` on every route — the token in `/etc/huginn-appd/token`. It is the highest-privilege thing here and the one piece with a real threat model to read: [`SECURITY.md`](SECURITY.md).
- **The Kotlin clients (`mobile/`, optional)** — `:core` (logic + HTTP) and `:ui` (Compose) shared by `:app` (Android) and `:app-desktop` (Windows/Linux). They hold no session state either; they render what the daemon reports and send keystrokes back. The older Electron client in `desktop/` is deprecated.

## Why it's shaped this way

- **State lives on the host, not the client.** Every device is a thin viewer; nothing to sync. Close the laptop mid-task, open the phone, you're in the same place.
- **tmux is the persistence + multi-client layer.** "Persistent session," "mirror," and "reattach" are all native tmux — Huginn just packages sensible defaults and a friendly command.
- **Two transports, and you choose how many you run.** The terminal path is SSH and nothing else — no daemon, no ports. The app path adds `huginn-appd` on 8787, because a phone cannot usefully hold a PTY: it needs structure (which session is asking a question, what the answer buttons are) rather than a character grid. Run only the first and the daemon half of [`SECURITY.md`](SECURITY.md) doesn't apply to you; run both and you have two credentials of equal power, on two different revocation paths. Either way, add Tailscale/WireGuard for off-LAN.
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
