# Layout of a Huginn Server

## Processes

```text
systemd
  huginn-appd.service        node /opt/huginn-appd/huginn-appd.js   (root, port 8787, HTTPS + loopback HTTP)
  huginn-tmux.service        the tmux server the daemon started: one session per Claude Code
    tmux session "main"      claude; exec $SHELL -l        <- started by cc or POST /v1/sessions
    tmux session "<name>"    ...
  sshd                       clients attach with `ssh huginn -- cc <name>` and run the host tools
```

A tmux server started by a person (`cc` from an ssh login before the daemon ran) lives outside
`huginn-tmux.service`; the daemon finds it on the default socket all the same.

## Files

| Path | What | On update |
|---|---|---|
| `/usr/local/src/huginn/` | the release the installer ran from; `.huginn-ref` names it | replaced |
| `/opt/huginn-appd/` | `huginn-appd.js`, `lib/`, `hooks/`, `install-hooks.js` | replaced |
| `/etc/huginn-appd/token` | the access token, 0600, at least 32 chars | kept |
| `/etc/huginn-appd/fcm-service-account.json` | optional Firebase key for Android push | yours |
| `/etc/huginn/persona.md` | optional text appended to chats, rounds, `huginn -p`/`-y` | yours |
| `/etc/systemd/system/huginn-appd.service` + `.d/hardening.conf` | the unit and its sandbox | replaced |
| `/etc/systemd/system/huginn-appd.service.d/site.conf` | the operator's settings (`Environment=` lines) | never touched |
| `/var/lib/huginn-appd/` | `tls/` (cert.pem, key.pem), `uploads/`, `chats/`, `accounts/`, `archive/`, `rounds/`, `projects/`, `scratchpads/`, `session-meta/`, `headroom/`, `*.json` | kept |
| `/run/huginn-claude-state/` | hooks' state, tmpfs, 0700; cleared at boot | |
| `/usr/local/bin/` | the host tools (below) | replaced unless edited here and newer |
| `/usr/local/lib/huginn/` | `state-table.json` and its readers (`.sh`, `.py`, `.js`) | replaced |
| `/usr/local/share/huginn/skills/huginn-server/` | this skill | replaced |
| `~/.tmux.conf` | Huginn's tmux config (`allow-passthrough on`); the one before is `.tmux.conf.pre-huginn` | replaced |
| `~/.claude/settings.json` | Claude Code settings; the installer merges the `hooks` object only | hooks re-merged |
| `~/.claude/skills/huginn-server` | a link to this skill | |
| `~/.claude/projects/<slug>/<session-id>.jsonl` | Claude Code's own transcripts, which the daemon reads | |

`~` is the sessions account's home: root by default, `HUGINN_HOME` at install for another.

## The state directory

`/run/huginn-claude-state/<tmux session>` is JSON written by `huginn-claude-title` on every
hook event:

```json
{"state":"running","sessionId":"<uuid>","transcript":"/root/.claude/projects/-root/<uuid>.jsonl","cwd":"/root","ts":1700000000000}
```

`state` is `running` (working), `attention` (needs you) or `idle` (waiting); the file is removed
at `SessionEnd`. It is the only link between a tmux name and a Claude Code session id. Beside it:

| Dir | Written when | Holds |
|---|---|---|
| `.compacting/<session>` | PreCompact to PostCompact | an empty marker |
| `.ask/<session>`, `.plan/<session>` | `AskUserQuestion` / `ExitPlanMode` is on screen | the tool input, so the apps draw buttons |
| `.perm/<session>/<id>.json`, `.ans`, `.taken` | a `PermissionRequest` hook is waiting | the request; the apps' answer; the hook's claim |
| `.modelswitch/<session>` | PostModelSwitch | from/to model |
| `.status/<session>` | each status-line refresh (when installed) | Claude Code's status-line JSON: context, model, limits, cost |

Read these freely; never write them. A stale file for a session that no longer exists is normal.

## Hooks in `~/.claude/settings.json`

| Script | Events | Purpose |
|---|---|---|
| `/usr/local/bin/huginn-claude-title` | UserPromptSubmit, PreToolUse, PostToolUse, Notification, Stop, SubagentStop, PreCompact, PostCompact, SessionStart, SessionEnd (async) | the state file and the tab title |
| `/opt/huginn-appd/hooks/huginn-permission-hook` | PermissionRequest | lets a tap in an app answer a prompt |
| `/opt/huginn-appd/hooks/huginn-headroom-gate` | SubagentStart, PreToolUse (Agent, Workflow) | delays agent spawns while usage is short |
| `/opt/huginn-appd/hooks/huginn-modelswitch-hook` | PostModelSwitch | records model switches |

Every hook exits 0 and prints nothing (except the permission decision); they act only for the
top-level `claude` in a Huginn tmux pane. Nested `claude -p` runs you start are skipped by design.

## Sessions and how they are made

- From a terminal: `cc <name>` (what `huginn <name>` runs over ssh). Starts in `$HUGINN_WORKDIR`
  or the home directory. Names are lowercase, digits, `_`, `-`, up to 50 characters.
- From the apps or the console: `POST /v1/sessions`, starting in `HUGINN_APPD_WORKDIR`.
- Chats and rounds are not tmux sessions: they are `claude -p` runs the daemon starts, with
  `--allowedTools`/`--disallowedTools` fences (ask: no Bash/Edit/Write; act: with them).
- A project is a group of sessions with roles (`huginn-projects`), led by one.
