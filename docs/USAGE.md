# Usage

## Command

| Command | What it does |
|---|---|
| `huginn` | attach (or create) the live **`main`** session; run `claude` / `claude --resume` inside |
| `huginn <name>` | a separate named session (e.g. `huginn work`) |
| `huginn solo [name]` | attach **and detach all other clients** — resume full-screen (kick your phone) |
| `huginn list` / `ls` | list running sessions + attach status |
| `huginn status` / `st` | health: uptime, auth (subscription), sessions, disk |
| `huginn headroom` | how much plan usage is left on each saved account, what the daemon is holding or has moved, and why — see [Headroom](#headroom-usage-limits) |
| `huginn rename <old> <new>` / `mv` | rename a session (e.g. promote `main` to a name, freeing `main`) |
| `huginn end <name> [--force]` | **soft end**: ask Claude to wrap up and commit, then end the session once it goes idle (if auto-end is on for the host). `--force` sends the phrase into a pane with no recorded Claude state. A daemon feature — there is no tmux fallback |
| `huginn kill <name>` | end a session now (hard). Falls back to raw `tmux kill-session` **only** when the daemon cannot be reached at all |
| `huginn archive <name> [--now]` | end it for good and keep the way back: the title, the cwd, the last thing said, a copy of the transcript and the exact `claude --resume`. Graceful like `end`; `--now` skips the wrap-up |
| `huginn archive` | what has been archived, and how to bring each one back |
| `huginn revive <id\|name>` | recreate an archived session, restore the kept transcript, and resume into it |
| `huginn rounds` | what this host does on a schedule, and what the last runs found |
| `huginn devices` | the machines enrolled with this host, their scope, and whether they are reachable |
| `huginn device [status]` | what **this** machine offers huginn — see [Devices](#this-machine-as-a-device) |
| `huginn device on\|off\|update\|unit\|serve` | offer this machine, stop offering it, refresh its runner, print a systemd unit, run the runner in the foreground |
| `huginn local [status\|plan\|on\|off\|update\|persist\|doctor\|unit]` | this machine as a **local-AI serving** machine — see [`LOCAL-TIER.md`](LOCAL-TIER.md) |
| `huginn llm "prompt"` | one question answered by the local tier (a serving machine's model), never Claude |
| `huginn projects` | the clusters of sessions on this host, and who is waiting — see [Projects](#projects) |
| `huginn desktop [windows\|linux]` | download links for the latest Huginn Desktop build; with a platform, the bare URL for scripting |
| `huginn -p "question"` | one-shot **headless** query — reads files; **not a sandbox**, see [Headless one-shots](#headless-one-shots) |
| `huginn -y "task"` | one-shot that also grants the mutating tools (bash / files / web) |
| `huginn usage [args]` / `cost` | Claude Code token/cost report ([ccusage]) — e.g. `usage monthly`, `session`, `blocks --live` |
| `huginn usage <when>` | shortcut date range: `today` \| `yesterday` \| `week` \| `month` — e.g. `usage today`, `usage week session` |
| `huginn update` | self-update the client from the repo via `gh`; without it, from the **pinned** mirror host (`HUGINN_UPDATE_HOST`, never `HUGINN_HOST` — this is code your shell then runs) |
| `huginn uninstall` | remove huginn from **this** machine — see [Uninstalling](#uninstalling) |
| `huginn version` | print the client version + target host |
| `huginn help` / `?` / `/help` | this reference |
| `--json` | on `projects` and `headroom`: print what the daemon said, unparsed |
| `rclaude` / `rcc` | aliases for `huginn` |

Every daemon-backed verb runs **on the host** (see the README's [`ssh` + `tmux`](../README.md#-isnt-this-just-ssh--tmux) note): the bearer token is read there and never reaches the client, and `HUGINN_HOST` decides which box you ssh to, not which daemon a client dials.

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

## Projects

A **project** is a cluster of sessions with roles and a lead that sizes the work. The lead is a
real Claude session: you give it a brief, it proposes the members — roles, first prompts,
directories — and *you* approve the proposal. Nothing is spawned without that approval.

| Command | What it does |
|---|---|
| `huginn projects` / `list` | one line per project: members, who needs you, the lead's state |
| `huginn projects show <project>` | the members, one line each, with the name to attach |
| `huginn projects new <name> --brief TEXT\|- [--kind KIND] [--cwd DIR]` | start one: launches the lead and types the brief into it |
| `huginn projects spawn <project>` | **approve the lead's proposal** and create its members |
| `huginn projects msg <project> <from> <to> <text>` | put one line in one member's pane, addressed from another |
| `huginn projects end <project> [--now\|--keep-sessions]` | end the project and wind its sessions down |

- `--brief` is not a description: it is the **whole first message the lead receives** and what it
  sizes the project from. `-` reads it from stdin, so a brief in a file can be piped in.
- `--kind` is one of `software`, `infra`, `hardware`, `docs`, `research`, `other` (default
  `other`). It labels the cluster; nothing branches on it.
- `--cwd` must be a directory Claude Code has already been **trusted** in — the folder-trust
  dialog blocks session registration entirely, so a lead launched into an untrusted directory
  could never be messaged. The daemon refuses with the fix in its own words.
- **`spawn` takes no member list.** The plan is the lead's, and the approval carries the
  *revision* it was given, so a proposal the lead has revised since you read it comes back "the
  proposal has changed" instead of spawning sessions you never saw. To change the plan, talk to
  the lead in its own session (`huginn <slug>-lead`).
- A member's **peer** name is `<slug>/<role>`; its **tmux** name is `<slug>-<role>`, and that is
  the one `huginn <name>` attaches.
- `end` winds the sessions down gently by default (the wrap-up phrase, then auto-end when idle);
  `--now` ends them outright, `--keep-sessions` deletes only the record and prints the names of
  the sessions it left running. A member sitting on a dialog cannot be handed the phrase — it is
  named, and the exit status is non-zero.
- Exit codes: **1** a refusal, or a spawn/end that only partly succeeded; **2** the daemon is not
  answering; **3** the daemon is too old to have Projects.

## This machine as a device

`huginn device on` offers the machine you are typing on to the host as a place to run work, at a
scope you choose (`--scope look|work|own`, `--root DIR`). `huginn device status` says what it
offers and what the host sees, including whether it keeps acting while the screen is locked
(`--act-while-locked` / `--no-act-while-locked`). `huginn device unit` prints a systemd unit that
keeps the runner up; `huginn device update` refreshes the runner from the pinned sources.
`huginn devices` (plural) is the host's list of every enrolled machine.

## Headroom (usage limits)

`huginn headroom` prints, per saved Claude login, the **fullest** of the three windows a plan has
(the 5-hour session cap, the week across all models, the week's Fable pool), when it resets, which
sessions the daemon has moved or is holding, and the one-line reason it last did nothing. It is
rendered on the host, like `rounds` and `devices`, so the client stays a thin viewer.

The acting half needs the daemon (`huginn-appd` 3.0.0+); without it this is a report and nothing
more. What the daemon does with those numbers is configured in the phone and desktop apps under
**Settings → Headroom**:

| Field | What it sets |
|---|---|
| Heads-up at | the percentage at which a Fable session is told, in its own pane, to write a handoff note (default 85 %) |
| Step down at | the percentage at which that session is moved to the next model down, for that session only (default 92 %) |
| Ladder | the order it steps through (default `fable → opus → sonnet`) |
| Default model | the model chats, rounds and restored sessions are launched with |
| Hold spawns at | session and Fable-week percentages at which new subagent spawns are made to wait (defaults 70 % / 88 %), cleared below 50 % |
| Auto-resume | whether a session stalled on a usage limit is resumed when the window resets, and the phrase used to resume it |
| Account auto-switch | the old auto-switch settings, which now live here |

**Auto-resume is also per session.** The global setting is the default; a session's control bar
carries its own toggle, so one long unattended run can resume itself while an attended session is
left where you left it. A session that hits a limit shows it as a notice rather than as an answer,
and the daemon says which sessions came back and how (Claude Code's own auto-continue, or huginn).

**The model step-down is session-only.** It is the `/model` picker's "use this session only"
choice; the host's default model in `~/.claude/settings.json` is never rewritten. Typing `/model
<name>` yourself *does* persist it as the host default — that is the CLI's behaviour, not huginn's.

**Cost-sensitive headless lanes: `CLAUDE_CODE_NO_MODEL_FALLBACK=1`.** When the Fable weekly pool is
spent, Claude Code will silently run a `-p` one-shot on the next model down rather than fail. huginn
leaves that alone (a silent swap beats a dead run) and launches chats and rounds with an explicit
`--model` instead. If you have a lane where the *wrong model* is worse than *no answer* — a
scheduled job whose output feeds something else, a budget you are holding — set this variable on
the host for that lane and the run fails instead of falling back.

## Environment variables

| Variable | Effect |
|---|---|
| `HUGINN_HOST` | target host / SSH alias (default `huginn`) |
| `HUGINN_NO_RECONNECT` | set to `1` to disable auto-reconnect |
| `HUGINN_NO_TITLE` | set to `1` to disable terminal-tab naming |
| `HUGINN_UPDATE_HOST` | the host `huginn update` and the runner fetches fall back to when `gh` is unavailable. **Pinned by default and deliberately not `HUGINN_HOST`** — this is the host whose code your shell then runs, so overriding it is a trust decision and the client says so every time |
| `HUGINN_DEVICE_DIR` | where `huginn device` keeps `device.json` and its copy of the appd token (default `~/.config/huginn`) |
| `HUGINN_DEVICE_REFRESH` | set to anything to re-fetch the device runner on the next `device on` |
| `HUGINN_LOCAL_DIR` | where the local-AI tier installs (default `~/.config/huginn-local`; `%ProgramData%\huginn-local` on Windows) |
| `HUGINN_LOCAL_REFRESH` | set to anything to re-fetch the local-tier manager on the next `local on` |
| `HUGINN_APPD_URL` *(host)* | the daemon address the host-side renderers (`headroom`, `archive`, `projects`) use, for driving a second daemon on the same box. They still read `/etc/huginn-appd/token`, so a daemon with a different token answers 401 |
| `HUGINN_WORKDIR` *(host)* | working directory new sessions open in (default `$HOME`) |
| `CLAUDE_CODE_NO_MODEL_FALLBACK` *(host)* | set to `1` so a headless run FAILS instead of silently falling back to another model when the Fable pool is spent — see [Headroom](#headroom-usage-limits) |

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

`huginn -p "..."` runs a single prompt and prints the answer; `huginn -y "..."` is the "go do it" variant. The difference between them is the tool *grant* the client passes through, and both grants are real:

| | `--allowedTools` (auto-approved) | `--disallowedTools` (denied) |
|---|---|---|
| `huginn -p` | `Skill mcp__mempalace WebFetch WebSearch` | `Bash Edit Write NotebookEdit` |
| `huginn -y` | `Skill Bash Read Edit Write Glob Grep WebFetch WebSearch mcp__mempalace` | *(none)* |

These are kept in step with the daemon's own `TOOLS`/`DISALLOWED` sets, byte for byte, so a
one-shot from the CLI and a chat from the app grant the same thing. Neither grant is passed at all
if the host carries no persona file (`/usr/local/share/huginn-cli/persona.md`) — the run degrades
to a bare `claude -p`. Both bill against whatever the host's Claude Code is authenticated with.

> ⚠️ **Neither one is a sandbox, and `-p` is not "no tools" — it auto-approves four of them.** `--allowedTools` **auto-approves** the tools it names — it does not restrict the ones it omits. Headless Claude Code already has the read-only tools (`Read`/`Glob`/`Grep`) with no grant at all, so `huginn -p` can read any file the host user can: your credentials, your `.env`s, every project on the box. And whether an *ungranted* tool runs is decided by the host's own permission settings (`~/.claude/settings.json`), not by the flag — with a permissive `permissions.defaultMode`, a `-p` one-shot will run `Bash` and `Write` too. (Measured on the author's host, where it did: `id -un` → `root`, and a file created.) Read `-p` as "no mutation *intended*", never "no mutation *possible*", and never reach for `--allowedTools` as a fence. It is a convenience, not a boundary.

> Note: Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist instead. Run the node as a non-root user if you want broader headless autonomy.
