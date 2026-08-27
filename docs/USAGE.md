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
| `huginn -p "question"` | one-shot **headless** query — reads files; **not a sandbox**, see [Headless one-shots](#headless-one-shots) |
| `huginn -y "task"` | one-shot that also grants the mutating tools (bash / files / web) |
| `huginn usage [args]` / `cost` | Claude Code token/cost report ([ccusage]) — e.g. `usage monthly`, `session`, `blocks --live` |
| `huginn usage <when>` | shortcut date range: `today` \| `yesterday` \| `week` \| `month` — e.g. `usage today`, `usage week session` |
| `huginn update` | self-update the client from the repo (`gh` → `scp` fallback) |
| `huginn uninstall` | remove huginn from **this** machine — see [Uninstalling](#uninstalling) |
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

## Uninstalling

`huginn uninstall` puts the machine back the way the installer found it. It asks you to
type `uninstall` first (`--yes` skips that), and it does the two halves in this order:

1. **The server, first.** Every enrolment this machine holds — itself as a device, and
   `<host>-llm` if it also serves local models — is retired from the daemon *while the
   token that can do it still exists*. Wipe first and those rows are unremovable from
   here forever: they sit in `huginn devices` reading "not reachable" and go on being
   offered work by a machine that is gone.
2. **The disk, second.** `~/.huginn` (the client, the device runner, the local-AI
   manager), `~/.config/huginn` (the enrolment and its copy of the appd token),
   `~/.config/huginn-local` (models, sessions, runtime — often several GB), and the
   `source ~/.huginn/huginn.sh` line the installer put in your profile.

If the host is unreachable the uninstall **still finishes** — an uninstaller does not get
a second run — and it names the row it stranded so you can retire it from the host later.
That is the one place the "never destroy the only handle" rule is deliberately inverted;
`huginn device off` on its own still refuses, because that one *can* be run again.

**What it leaves, on purpose:** your SSH key and the `Host huginn` stanza. The installer
only *creates* a key when there is not one already, and afterwards nothing can tell the
key it generated from the one you have used for years — `~/.ssh/id_ed25519` is the default
name for both. `huginn uninstall --all` removes the stanza, and removes the key pair only
when it is huginn's by filename (`id_ed25519_huginn`) or by the comment in its `.pub`.
Anything ambiguous is kept, and the summary says which and why.

The summary at the end lists exactly what went and what stayed. The `huginn` function is
still loaded in the shell you ran it from until you open a new one.

### The desktop app

The desktop client has its own uninstaller and does the same two halves. On Windows,
Programs and Features → *Huginn Desktop* unenrols both rows, stops and deregisters the
`huginn-local-*` WinSW services, and then removes `%USERPROFILE%\.config\huginn-desktop-kt`
(the settings file holds the bearer token in plaintext, and so does the `.corrupt` copy
beside it), the update cache, `%ProgramData%\huginn-local`, the `huginn:` URL scheme, and
the two CLI files it keeps current in `~/.huginn` — named one by one, so a base client you
installed separately survives. On Linux, `apt purge huginn-desktop-kt` does the same for
every real user's home; plain `apt remove` leaves configuration alone, the Debian way.

## Headless one-shots

`huginn -p "..."` runs a single prompt and prints the answer; `huginn -y "..."` is the "go do it" variant. The difference between them is the tool *grant* the client passes through: `-y` names `Bash Read Edit Write Glob Grep WebFetch`, `-p` names only the memory MCP — and neither names anything at all if the host carries no persona file. Both bill against whatever the host's Claude Code is authenticated with.

> ⚠️ **Neither one is a sandbox, and `-p` is not "no tools".** `--allowedTools` **auto-approves** the tools it names — it does not restrict the ones it omits. Headless Claude Code already has the read-only tools (`Read`/`Glob`/`Grep`) with no grant at all, so `huginn -p` can read any file the host user can: your credentials, your `.env`s, every project on the box. And whether an *ungranted* tool runs is decided by the host's own permission settings (`~/.claude/settings.json`), not by the flag — with a permissive `permissions.defaultMode`, a `-p` one-shot will run `Bash` and `Write` too. (Measured on the author's host, where it did: `id -un` → `root`, and a file created.) Read `-p` as "no mutation *intended*", never "no mutation *possible*", and never reach for `--allowedTools` as a fence. It is a convenience, not a boundary.

> Note: Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist instead. Run the node as a non-root user if you want broader headless autonomy.
