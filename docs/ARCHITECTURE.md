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
- **`huginn-appd` (server, optional)** — the daemon the apps talk to: sessions, headless chats, push notifications, prompts-as-buttons, scheduled **Rounds**, and work **dispatch to enrolled Devices** (a phone/PC/laptop or a local-model box that runs Claude or a local LLM on the daemon's behalf — this is why the threat model is no longer one host; see [`SECURITY.md`](SECURITY.md)). Zero npm dependencies, Node ≥ 20, runs as **root**, listens on port 8787 (Tailscale address by default, `HUGINN_APPD_BIND` to change it) and requires `Authorization: Bearer <token>` on every route — the token in `/etc/huginn-appd/token`. It is the highest-privilege thing here and the one piece with a real threat model to read: [`SECURITY.md`](SECURITY.md).
- **The Kotlin clients (`mobile/`, optional)** — `:core` (logic + HTTP) and `:ui` (Compose) shared by `:app` (Android) and `:app-desktop` (Windows/Linux). They hold no session state either; they render what the daemon reports and send keystrokes back. An older Electron desktop client used to live in `desktop/`; it was retired and deleted on 2026-08-27 (see [`DESKTOP-MIGRATION.md`](DESKTOP-MIGRATION.md)).

## Why it's shaped this way

- **State lives on the host, not the client.** Every device is a thin viewer; nothing to sync. Close the laptop mid-task, open the phone, you're in the same place.
- **tmux is the persistence + multi-client layer.** "Persistent session," "mirror," and "reattach" are all native tmux — Huginn just packages sensible defaults and a friendly command.
- **Two transports, and you choose how many you run.** The terminal path is SSH and nothing else — no daemon, no ports. The app path adds `huginn-appd` on 8787, because a phone cannot usefully hold a PTY: it needs structure (which session is asking a question, what the answer buttons are) rather than a character grid. Run only the first and the daemon half of [`SECURITY.md`](SECURITY.md) doesn't apply to you; run both and you have two credentials of equal power, on two different revocation paths. Either way, add Tailscale/WireGuard for off-LAN.
- **Subscription auth is the cost story.** Claude Code can log in with a Max/Pro subscription — flat cost, no per-token billing — so an always-on agent you talk to all day doesn't run up an API bill. (API key still works if you prefer metered.)

## Headroom (the daemon's usage awareness)

Added in appd 3.0.0. A subscription plan has three usage windows — the 5-hour session cap, the week across all models, and the week's Fable-scoped pool — and before this the daemon knew only the active account's percentages, could only react by switching accounts (which does nothing for a session already running, because a live `claude` holds its token), and never noticed a window *resetting*. Headroom is the one subsystem that owns all of it.

- **The model.** `server/appd/lib/headroom.js` is pure — percentages, settings and a clock reading in, a verdict out. It holds the three windows per saved account with their reset times; `headroomTick` in `huginn-appd.js` does the I/O, polls once a minute while anything is running, persists `headroom.json` under `/var/lib/huginn-appd/`, and serves `GET /v1/headroom` plus a one-line summary on `/v1/status`. `/v1/plan` now carries the account identity (email, account id, plan) alongside the numbers, so a switch cannot label the new account's bars with the old name.
- **One arbiter.** `headroom.decide()` is the only thing that decides anything: account auto-switch (whose rules are absorbed from `lib/autoswitch.js`, not reimplemented), the Fable model ladder, and the sentinels — one verdict, one cooldown, one `why` string the clients can show.
- **The ladder uses the picker's session-only key.** `/model <name>` takes no flags and always persists the host default into `~/.claude/settings.json`, so the daemon never types it. Instead it opens the argument-less `/model` picker, finds the target row **by label** (`lib/pane.js`'s picker parse — never by row number, which shifts with the model list), moves the caret one key at a time re-reading the pane after each, and presses `s` for "use this session only". The confirmation line is required; without it the move is recorded as `delivery_unconfirmed` and nothing is assumed. A human's own `/model` puts the session off limits for a grace period, and a session the CLI moved off Fable by itself is recorded and left alone.
- **Sentinels and the hook gate.** `lib/sentinels.js` owns `/var/lib/huginn-appd/headroom/`: `STOP`, `STOP-FABLE`, a `fable-sessions` list (the SubagentStart payload carries no model, so this is how a shell hook answers "is this session on Fable?"), and a `held/` directory the routes read to say what is waiting. `hooks/huginn-headroom-gate` is a coreutils-only bash hook, installed into `~/.claude/settings.json` by `install-hooks.js` on `SubagentStart` and `PreToolUse Agent|Workflow`; it sleeps a new agent spawn while a sentinel is armed and **always exits 0** — it delays, it never denies — self-releasing 30 s before the CLI's own hook timeout so every release is its own. Details and the reasoning: [`SECURITY.md`](SECURITY.md).
- **Auto-resume watches the CLI's own auto-continue.** A session stalled on a usage limit is deterministic in its transcript (`lib/limits.js`: an API error with status 429 and the reset time). Claude Code's `autoContinueAtUsageLimit` handles the attended case and the daemon waits for it; it covers the gaps that wait cannot reach — a session appd restored after a reboot or restart, a cancelled wait, a reset more than 24 h out, headless chats and rounds (the turn is re-run with `--resume`), and workflow subagents via the gate. Only while nothing has been typed since the error, at most three times.
- **The send queue.** Every automated line — the heads-up, the ladder's keystrokes, the resume phrase — and every client message goes through one per-session queue and is released only at a real turn boundary, because the CLI otherwise splices a mid-turn message into the answer already being written. `lib/typing.js` holds the pure half (the 100,000-character cap, the bracketed-paste vs `send-keys` choice, the measured tmux command-line budget); `huginn-appd.js` holds the queue, `GET /v1/sessions/:name/typing` says what is waiting and why, and `pendingSends` rides the session list rows. An automated entry is dropped rather than delivered if a human types first.
- **Token refresh for inactive profiles.** `lib/oauth-refresh.js` (pure request/response shaping) plus `lib/accounts.js` and `lib/oauthlock.js` keep every *saved but not active* login signed in, so its headroom stays readable and the switcher has somewhere to switch to. The active login is the CLI's to refresh and is never touched — see [`SECURITY.md`](SECURITY.md) for why that boundary is the whole safety story.
- **Agent streams.** `lib/agents.js` lists every subagent and workflow member of a session (`GET /v1/sessions/:name/agents?all=1`) and reads one member's own transcript, so a client can watch a subagent rather than only the parent.

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
