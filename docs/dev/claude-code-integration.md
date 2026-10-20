# Claude Code integration

Huginn does not reimplement Claude Code. It drives the `claude` CLI that is installed on the
server, through three kinds of interface:

| Kind | Examples | How it breaks |
|---|---|---|
| Hooks and files | the hook scripts, `/run/huginn-claude-state/`, transcript JSONL | A renamed event or field. Usually loud in tests. |
| `claude -p` flags and output | `--output-format stream-json`, `--allowedTools`, `--resume` | A renamed flag or event. Loud: the run fails. |
| Reading the tmux pane | prompts, the `/model` picker, the composer | A redrawn dialog. **Silent**: a prompt stops showing up in the apps. |

This page maps each interface to the code that depends on it, lists the checks to run when
Claude Code updates, and names the interfaces that are safe to build on.

> **Rule for pull requests.** If your change reads anything Claude Code writes or draws, or you
> checked Huginn against a new Claude Code release, put the version you verified against
> (`claude --version` on the server) in the PR description.

## Hooks

Four hook scripts are installed into the Claude Code settings of the account that runs the
sessions (`~/.claude/settings.json`; root by default, `HUGINN_HOME` in `server/setup.sh`).

| Script | Events (matcher) | Timeout | Installed by |
|---|---|---|---|
| `server/bin/huginn-claude-title` | `UserPromptSubmit`, `PreToolUse` (`.*`), `PostToolUse` (`.*`), `Notification`, `Stop`, `SubagentStop`, `PreCompact`, `PostCompact`, `SessionStart`, `SessionEnd` | 10 s, `async: true` | `server/setup.sh`, which merges `server/claude-hooks.json` with `jq` |
| `server/appd/hooks/huginn-headroom-gate` | `SubagentStart` (`*`), `PreToolUse` (`Agent\|Workflow`) | 1800 s | `server/appd/install.sh` through `install-hooks.js` |
| `server/appd/hooks/huginn-permission-hook` | `PermissionRequest` (`*`) | 960 s | same |
| `server/appd/hooks/huginn-modelswitch-hook` | `PostModelSwitch` (`*`) | 10 s | same |

Every hook script follows the same contract:

- **Always exit 0.** Claude Code reads a non-zero exit as a block, and a block on
  `UserPromptSubmit` or `Stop` stalls the turn.
- **Print nothing**, except the permission hook's well-formed decision. `UserPromptSubmit`
  injects stdout into the prompt.
- **Act only for the top-level interactive `claude` in a Huginn tmux pane.** Headless `claude -p`
  runs, cron jobs and nested `claude` children return at once.

### The title hook and the state directory

`huginn-claude-title` maps each event to a state and records it in
`/run/huginn-claude-state/<session>` (a tmpfs; the directory is 0700, the files 0600):

```json
{"state":"running","sessionId":"<uuid>","transcript":"/root/.claude/projects/-root/<uuid>.jsonl","cwd":"/root","ts":1700000000000}
```

| Event | `state` | Shown as |
|---|---|---|
| `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `SubagentStop`, `PreCompact`, `PostCompact` | `running` | working |
| `Notification` with `notification_type` other than `idle_prompt` | `attention` | needs you |
| `Stop`, `SessionStart`, `Notification` with `idle_prompt` | `idle` | waiting |
| `SessionEnd` | file removed | |

`ts` is milliseconds. This file is the only link between a tmux session name and its Claude
Code session id and transcript path; without `jq` on the server it degrades to the bare state
word and the apps lose the structured conversation view.

The same hook keeps three dot-prefixed subdirectories (the leading dot keeps them out of the
session-name namespace):

| Path | Written on | Cleared on |
|---|---|---|
| `.compacting/<session>` (empty marker) | `PreCompact` | `PostCompact`, `Stop`, `SessionEnd` |
| `.ask/<session>` | `PreToolUse` for `AskUserQuestion` | that tool's `PostToolUse`, `UserPromptSubmit`, `Stop`, `SessionEnd` |
| `.plan/<session>` | `PreToolUse` for `ExitPlanMode` | same |

A sidecar holds `{v, tool, sessionId, ts, input}`, where `input` is the exact `tool_input`. The
daemon fuses it with the pane scrape (`server/appd/lib/ask.js`): labels come from the sidecar,
which is width-independent; liveness and the caret come from the pane. A stale sidecar never
creates buttons on its own, because fusion requires a matching prompt on screen. A sidecar is
deliberately not cleared on another tool's `PreToolUse`: background agents fire that event while a
main-thread question is still waiting.

The hook also sets the terminal tab title (`<glyph> <session>`) by writing an OSC 2 sequence,
wrapped in tmux passthrough, to the pane's tty. That needs `allow-passthrough on`, which
`server/tmux.conf` sets.

Two process-tree rules keep the state file honest:

- **Nested `claude` guard.** A `claude` started by a Bash tool inside a session inherits `TMUX`
  and `TMUX_PANE` and would overwrite the parent's state. The hook walks up to the nearest
  `claude` ancestor (by `comm`, by a `cli.js` argv, or by a `claude.exe` path) and skips if that
  process's environment carries `CLAUDECODE`. A top-level `claude` was started by a shell and
  does not carry it. The hook's own environment cannot tell the two apart.
- **Recovered tmux variables.** Claude Code can host a session's process in a background PTY
  that does not pass `TMUX` and `TMUX_PANE` on. The hook then takes them from the nearest
  ancestor that has them, but only if that pane's own process is one of the hook's ancestors.
  The hook therefore depends on the pane's process tree containing the `claude` that fires it.

The daemon watches the directory with `fs.watch` for sub-second reaction and keeps a 10-second
poll as a floor. The hooks read `HUGINN_CLAUDE_STATE_DIR` and the daemon reads
`HUGINN_APPD_STATE_DIR`; both default to `/run/huginn-claude-state`.

### The permission hook

`huginn-permission-hook` lets a tap in an app answer a prompt through Claude Code's own
`PermissionRequest` hook instead of keystrokes.

1. For a `Bash` or `AskUserQuestion` prompt in the top-level session (its `session_id` must
   equal the one in the state file), the hook writes `.perm/<session>/<id>.json` and polls.
2. `/answer` in the daemon (`server/appd/lib/permhook.js`) matches the tap against the dialog on
   screen **exactly**: an `AskUserQuestion` by its whole question and option list, a Bash prompt
   by its whole command. On exactly one match it writes `<id>.ans`.
3. The hook claims the answer by renaming it to `<id>.taken` and prints it. If the hook has not
   claimed it within a moment, the daemon retracts it and types the digit instead.

The decision the hook prints:

```json
{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}
```

For `AskUserQuestion`, `allow` carries
`updatedInput: {...tool_input, answers: {"<question text>": "<label>"}}`; a multi-select answer
is the labels joined with `", "`. The hook gives up 60 s before its 960 s timeout
(`HUGINN_PERM_WAIT`, default 900) so that it always exits on its own.

Behaviour of Claude Code that this design rests on:

- The TUI draws the dialog while the hook waits; whichever answers first wins.
- Answering at the keyboard does not stop a waiting hook. A request file therefore means "a
  hook is alive", never "this prompt is on screen", and matching is by content.
- The hook fires in headless `claude -p` runs too, and the run waits for it. That is why the
  hook acts only for the pane's top-level session.
- An `allow` for `ExitPlanMode` is accepted and ignored. Plan approval stays on keystrokes.
- With zero or several matching requests (parallel subagents share a session id), nothing goes
  through the hook.

### The headroom gate

`huginn-headroom-gate` delays agent spawns while the daemon has armed a usage sentinel in
`HUGINN_HEADROOM_DIR` (default `/var/lib/huginn-appd/headroom`). It never denies: it exits 0 and
releases itself 30 s before its timeout, because Claude Code kills a hook at its `timeout`
(seconds) and then allows the spawn silently. It uses coreutils and `sed` only, since it runs on
the spawn path of every agent. Both events are needed: `SubagentStart` is the only one that sees
agents spawned inside a Workflow, and `PreToolUse` is the only one that sees a foreground Agent
or Workflow call before it starts. Its entries must never carry `async: true`; an async hook
cannot hold anything.

### The model-switch hook

`huginn-modelswitch-hook` records each `PostModelSwitch` (`from_model`, `to_model`,
`requested_model`, `source`) to `.modelswitch/<session>`. The headroom ladder accepts it as
confirmation of a session-only switch, next to the confirmation line on the pane.

### How the settings file is edited

`server/appd/install-hooks.js` edits `~/.claude/settings.json` directly; there is no supported
CLI command for it. It parses first and refuses with exit 2 on a file it cannot read, adds
entries by the script's basename so a second run changes nothing, and writes through a temporary
file and a rename with the same 2-space JSON Claude Code uses.

```text
node install-hooks.js [--settings <path>] [--script <abs path>]
                      [--headroom-dir <path>] [--uninstall] [--dry-run]
```

`HUGINN_CLAUDE_SETTINGS` names another settings file; set but empty is an error, so a test
cannot reach the live file by accident.

## Headless runs (`claude -p`)

| Caller | Arguments (after `claude`) | Prompt |
|---|---|---|
| App chats and Rounds (`startRun` in `server/appd/huginn-appd.js`) | `-p --output-format stream-json --verbose --include-partial-messages [--model M] [--effort E] [--resume <uuid>] [--append-system-prompt <persona>] --allowedTools "<tools>" [--disallowedTools "<tools>"]` | stdin |
| `huginn -p` / `huginn -y` (`server/bin/huginn-ask ask\|act`) | `-p --allowedTools "<tools>" [--disallowedTools "<tools>"] [--append-system-prompt <persona>]` | stdin |
| Device runs (`ArgvFor` in `core/internal/policy`) | `-p --output-format stream-json --verbose --include-partial-messages [--model] [--effort] [--resume <uuid>] --allowedTools ... [--disallowedTools ...]` | stdin |
| Reply suggestions, Round polish | `-p --setting-sources '' --model haiku\|sonnet --max-turns 1 --tools '' -- <prompt>` | argv |
| Keep-awake ping (`server/appd/lib/keepawake.js`) | `-p --setting-sources '' --strict-mcp-config --no-session-persistence --model <id> --max-turns 1 --tools '' -- <prompt>` | argv |

### The tool fence

`--allowedTools` only auto-approves; it does not restrict. In `-p` mode `Read`, `Glob` and
`Grep` are available with no grant, so the line between ask and act is drawn at mutation, and
`--disallowedTools` is the actual fence. Deny is also what makes ask mode deterministic: Claude
Code's safe-Bash classification depends on the command text, so an un-granted Bash is approved
for one command and refused for the next.

| Mode | `--allowedTools` | `--disallowedTools` |
|---|---|---|
| ask (`huginn -p`, ask chats) | `Skill WebFetch WebSearch` + extra tools | `Bash Edit Write NotebookEdit` |
| act (`huginn -y`, act chats) | `Skill Bash Read Edit Write Glob Grep WebFetch WebSearch` + extra tools | none |

Extra tools come from `HUGINN_APPD_EXTRA_TOOLS` (space-separated; anything not shaped like a tool
name is dropped). `huginn-ask` reads the same variables from the daemon's unit, so the terminal
and the apps have one fence. Device runs take their tool lists from `shared/device-policy.json`
instead (see [go-core.md](go-core.md)); the `look` scope also grants `Read Glob Grep` explicitly.

### The persona

When `HUGINN_APPD_PERSONA_FILE` (default `/etc/huginn/persona.md`) exists, its text is passed
with `--append-system-prompt` to app chats, Rounds, `huginn -p` and `huginn -y`. The daemon reads
it on every run, so editing it needs no restart. No file, no persona. It is never sent to device
runs, suggestions, polish or keep-awake pings.

Project sessions are interactive, not `-p`: the daemon starts
`claude --name <project>/<role> [--model M] [--effort E] --append-system-prompt-file <role file>`
in tmux, where the role file is the project's own brief under the daemon's data directory.

### The caged calls

Suggestions, polish and keep-awake run with `--setting-sources ''` (no `CLAUDE.md`, no user
settings, therefore none of Huginn's hooks), `--tools ''` and `--max-turns 1`, in the daemon's
data directory. Keep-awake must not use `--bare`: with it, Claude Code reads only
`ANTHROPIC_API_KEY` or `apiKeyHelper` and never the subscription login whose window the ping
exists to start.

### The stream-json events the daemon consumes

| Event | Fields used |
|---|---|
| `system`, subtype `init` | `session_id`, stored for `--resume` only if it is a UUID (a value starting with `--` would become the next flag) |
| `stream_event` | `content_block_delta` with `text_delta`; `content_block_start` with a `tool_use` block |
| `assistant` | `message.content[]` blocks `text` and `tool_use`; `message.model` (which model answered) |
| `result` | `is_error`, `result`, `duration_ms`, `total_cost_usd`, `num_turns` |

A usage-limit failure in a `-p` run arrives as a `result` with `is_error` and the reason in
`result`, not as a transcript record.

### Other CLI calls

| Call | Used for |
|---|---|
| `claude; exec "$SHELL" -l` in `tmux new-session` | every interactive session (`server/bin/cc`, `POST /v1/sessions`) |
| `claude auth status` | parsed as JSON: `loggedIn`, `email`, `orgName`, `subscriptionType`, `authMethod`, `apiProvider` |
| `claude auth login` in a tmux session named `login` | sign-in from the apps; the pane is read for the URL and the result |
| `claude auth logout` | sign-out from the apps |
| `claude --version` | reported in `GET /v1/status`, cached for an hour |

## Files Huginn reads

| Path | Read by | What is relied on |
|---|---|---|
| `~/.claude/projects/<slug>/<session-id>.jsonl` (slug = the cwd with `/` replaced by `-`) | `lib/transcript.js`, `lib/typing.js`, `lib/limits.js` | Record types `user`, `assistant`, `system`, `queue-operation`, `ai-title`, `permission-mode`; block types `text`, `thinking`, `tool_use`, `tool_result`. |
| `<projects>/<slug>/<session-id>/subagents/agent-<id>.jsonl` and `.meta.json`; workflow runs under `subagents/workflows/` and `<session-id>/workflows/` | `lib/agents.js` | One transcript per subagent or workflow member. |
| `~/.claude/settings.json` | `install-hooks.js`, `server/setup.sh` | The `hooks` object. Written, nothing else touched. |
| `~/.claude.json` | `cwdIsTrusted` in `huginn-appd.js` | `projects[<dir>].hasTrustDialogAccepted`, read up the directory tree. Never written. |
| `~/.claude/.credentials.json` | `lib/accounts.js` | The active login (`claudeAiOauth`). |
| `~/.claude/shell-snapshots/` in a process's argv | `lib/tasks.js` | The `source <snapshot> && eval '<command>'` wrapper Claude Code puts around Bash tool calls. |
| The `claude` executable | `lib/models.js` | Model ids appear as literal strings (`strings -n 6`, `claude-(fable\|opus\|sonnet\|haiku)-…`). |

Notes:

- **Turn boundaries.** The send queue releases a message only at a real turn boundary
  (`isBoundaryRecord` in `lib/typing.js`): a `system` record with subtype `turn_duration`, an
  `assistant` record with `isApiErrorMessage: true`, or an `assistant` record whose
  `message.stop_reason` is `end_turn`. Only `user`, `assistant` and `system`/`turn_duration`
  count as conversation; every other record type is bookkeeping.
- **Usage-limit stalls.** An interactive session stalled on a limit ends on an `assistant` record
  with `isApiErrorMessage: true` and `apiErrorStatus: 429`. `parseLimitError` reads the window
  and reset time from its text (`You've hit your session limit · resets …`,
  `You're out of usage credits …`).
- **Injected text.** `machineText` in `lib/transcript.js` holds a list of known bracketed
  openings (`[SYSTEM NOTIFICATION - NOT USER INPUT]`, image captions and others) that Claude Code
  writes into `user` records. A new wording not on the list shows up as a message the person
  appears to have typed.
- **Compaction.** A `system`/`compact_boundary` record is followed by a `user` record with
  `isCompactSummary: true` and `isVisibleInTranscriptOnly: true` and no `isMeta` ("This session is
  being continued from a previous conversation…"). It is drawn as a folded `compact` note, the send
  queue skips it (`isConversationalRecord`, `hasHumanUserRecord`), and the Overview does not count
  it as a message. A compaction while idle adds `system`/`informational` "Compacted while idle,
  before the prompt cache expired", and its hooks are PreCompact, SessionStart (source `compact`)
  and PostCompact with no turn and no Stop. Its `trigger` is `auto`, the same as one mid-turn, so
  the title hook keeps the state PreCompact found and PostCompact restores it.
- **Subagent hand-back.** A subagent's report is queued as a bare `<agent-message from="<id>">`
  whose first line is the harness frame (`[Subagent hand-back] … The report follows:`) and whose
  report lines are indented two spaces. Absorbed into a running turn it leaves `enqueue`, the Agent
  `tool_result`, a `queued_command` attachment and `remove`, with no `user` record after it.
  `peerNoteFromQueue` reads it on both queue operations; `peerBody` removes the frame and indent.
- **Pastes.** A long paste is stored as `<pasted_content id="d842">\n…\n</pasted_content id="d842">`
  in a `user` record with `origin.kind: "human"`. The id is four hex digits and repeats across
  messages. The clients fold it (`WrappedText`); `unwrapPasted` drops the tags from chat titles
  and Overview labels.
- **Last activity.** A session's `activityAt` is the timestamp of the transcript's last `user`,
  `assistant` or `system` record (`lastWorkAt`). tmux `window_activity` moves on every redraw and
  every title the state hook writes, and the transcript's mtime moves without a record being
  added, so neither is used except for a session with no transcript.
- **Model discovery.** The scan reads `HUGINN_CLAUDE_BIN`, default
  `/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe` (the global npm install that
  `server/setup.sh` performs). It is cached on the file's size and modification time, so it
  follows `claude update`. If it finds nothing, callers fall back to the family aliases
  `fable`, `opus`, `sonnet`, `haiku`.

### Account endpoints

The daemon also calls the endpoints Claude Code itself uses for subscription logins:
`https://api.anthropic.com/api/oauth/usage` and `/api/oauth/account` for usage and identity, and
`https://platform.claude.com/v1/oauth/token` to keep saved but inactive logins signed in (the
active login is Claude Code's to refresh and is never touched). These are not a published API.
A change there shows up as empty usage bars or `GET /v1/headroom` errors, not as a failed session.

## Reading the pane

The daemon reads sessions with `tmux capture-pane` and types into them with `tmux send-keys`.
What it reads off the screen:

| What | Code | Used for |
|---|---|---|
| Numbered selectors (permission prompts, `AskUserQuestion`, plan approval) | `detectNumberedPrompt` in `lib/pane.js` | Prompt cards, notification actions, `/answer` |
| The folder-trust dialog (unnumbered) | `detectTrustDialog` | Answered with arrow keys |
| Any other unnumbered selector (`❯` indented under a column-0 rule, no `N.`) | `detectUnnumberedSelector` | Holds a send as a dialog; not offered as a prompt |
| The usage-credits consent dialog | `consentRecommended` | A prompt card with a recommended row |
| The `/model` picker and the `Switch model?` confirmation | `parseModelPicker`, `LADDER_*_RE` in `huginn-appd.js` | The headroom ladder's session-only switch |
| The composer (`❯` between `─` rules) and a settled paste | `composerDrawn`, `pasteLanded` and others in `lib/typing.js` | The send queue |
| The composer in shell mode (`!` where the `❯` was; Enter runs the box as a command) | `composerShellMode` | Nothing is submitted into it (409) |
| Spinner, progress rows, `N/M agents done` | `parseSpinner`, `parseStatusExtras` | Working status in the apps |
| Mode hint (`⏵⏵ accept edits on`, `⏸ plan mode on`) | `MODE_HINT_RE`, `parseStatusLine` | Live mode |
| A status line `[<session>] <model> · ctx <N>% · <branch>` | `parseStatusLine` | Live model, context percentage, branch |
| The sign-in pane | `extractLoginUrl`, `loginPaneState` | Sign-in from the apps |

Huginn does not install a Claude Code status line. If you configure one that prints the shape
above, the daemon reads the live model, context percentage and branch from it; otherwise those
fields stay empty.

## Fragility checklist

Run this whenever Claude Code updates on a server you develop against. Most items are places
where Huginn reads the TUI's shape; when Claude Code redraws one, it fails silently. Tests run
from `server/appd` with `node --test test/<file>`; the whole suite is `node --test` there (see
[testing.md](testing.md)).

1. **Prompt detection** (`detectNumberedPrompt`): the option-row regex, the `❯` caret anchored
   at the line start, the `[ ]`/`[✔]` checkboxes, a footer of at most 4 unrecognised lines, 2 to
   9 options numbered contiguously from 1 with exactly one caret, the stop at option 1 that keeps
   a numbered plan body out, and the `☐ ☑ ☒ ←` tab-strip headers. Tests: `pane.test.js`,
   `pane-fixtures.test.js`, `ask.test.js`.
2. **Spinner glyphs** (`SPINNER_RE`): must match the running spinner and must not match the
   progress-row glyphs `◯ ◐ ◑ ◒ ◓ ⧉`, or a workflow row under a plan approval reads as chrome and
   the prompt is discarded. Test: `pane-fixtures.test.js` (`plan-approval-with-task-80.txt`).
3. **Mode hint** (`MODE_HINT_RE`, `⏵ ⏴ ⏸ ⏹ ▶`) and the multi-word mode parse in
   `parseStatusLine` (`accept edits on` has no "mode"). Test: `pane-fixtures.test.js`.
4. **Status line** (`parseStatusLine`): the `[<session>] <model> · …` contract. If you change the
   status line you draw, change the parser with it.
5. **Spinner and progress rows** (`parseSpinner`, `parseStatusExtras`): the spinner glyph class,
   the `N/M agents done` phrasing, and the durable/transient row split. The Kotlin side re-parses
   `N/M agents done` in `TranscriptGroups.plannedAgents` (`mobile/core`). Tests: `pane.test.js`,
   `PlannedAgentsTest.kt`.
6. **Chrome markers** used by `previewLines` and the prompt detector's chrome test: `❯`, `⏵⏵`,
   the `[name]` bracket.
7. **The composer and the send queue** (`lib/typing.js`): the empty and typed caret lines
   (`CARET_EMPTY_RE`, `CARET_TYPED_RE`) within the bottom 20 lines, paste-settled detection, and
   bracketed paste. Tests: `typing.test.js` (it sweeps every fixture and asserts each still reads
   as a dialog), `routes-typing.test.js`, `routes-paste-delivery.test.js`,
   `routes-paste-settle.test.js`, `routes-queue-boundary.test.js`.
8. **Turn-boundary records** (`isBoundaryRecord`): `system`/`turn_duration`,
   `isApiErrorMessage`, `stop_reason: "end_turn"`. A change here holds every queued message until
   it times out. Tests: `typing.test.js`, `routes-queue-boundary.test.js`.
9. **Sign-in wording** (`extractLoginUrl`, `loginPaneState`): `Paste code here`,
   `Login successful` and the other success phrases. Test: `pane.test.js`.
10. **`/answer` key sequences**: a single answer is the digit alone, which the selector answers
    on; an `Enter` follows only when a look after the digit shows it merely moved the caret onto
    an input row (`No, keep planning`, `Type something.`). A multi-select answer toggles digits
    (120 ms apart), presses `Right` for the review tab, waits 250 ms, then `Enter`. The trust
    dialog is answered with arrow keys. Tests: `pane.test.js` (`multiToggleDigits`),
    `routes-permhook.test.js`, `routes-answer.test.js`, `routes-answer-keystrokes.test.js`.
11. **Injected-text list** (`machineText`). Test: `transcript.test.js`.
12. **Bash wrapper argv** (`lib/tasks.js`): the `~/.claude/shell-snapshots` source plus
    `eval '…'` shape. Test: `tasks.test.js`.
13. **Model discovery** (`lib/models.js`): model ids as literal strings in the executable, and
    the global npm install path. Check `GET /v1/models` on a running daemon.
14. **The `/model` picker** (`parseModelPicker`), the ladder's only in-session lever. Load-bearing
    copy: the `Select model` heading (the gate that stops a numbered list in an answer from being
    walked with arrow keys), the row labels, matched by family name because row numbers shift as
    models come and go, and the `✔`/`❯` glyphs for the current model and the highlight. Then the
    `s` key ("use this session only"), the optional `Switch model?` dialog with its
    `❯ 1. Yes, switch to <model>` row, and the confirmation line `Set model to <model> … for this
    session only`. If any of these change, the ladder stops moving sessions, and the symptom is
    `delivery_unconfirmed` in `GET /v1/headroom`, not an error. Tests: `pane.test.js`,
    `pane-fixtures.test.js` (`model-picker-80.txt`, `model-picker-v2-80.txt`,
    `switch-model-confirm-80.txt`), `routes-headroom.test.js`.
15. **The trust dialog** (`detectTrustDialog`): its two row labels (`No, exit`,
    `Yes, I trust this folder`), its question text and the `Enter to confirm` footer. Test:
    `pane-fixtures.test.js` (`trust-dialog-unnumbered-80.txt`).
16. **The usage-credits consent dialog** (`consentRecommended`). Its fixture
    `fable-consent-80.txt` is assembled from the dialog's known wording, not captured from a live
    session; replace it with a real capture the first time you see the dialog. The transcript side
    (the 429 record and the `out of usage credits` text) is real. Tests: `pane.test.js`,
    `routes-headroom.test.js`, `headroom.test.js`, `transcript.test.js`.
17. **Hook roster and payloads**: the event names in the hook table above and the input fields
    listed under [Stable interfaces](#stable-interfaces). A renamed event or field drops a sidecar
    back to the pane-only path without an error. Tests: `install-hooks.test.js`,
    `hook-gate.test.js`, `permission-hook-script.test.js`, `permhook.test.js`,
    `routes-permhook.test.js`.
18. **Permission-hook behaviour**: that the dialog and the hook still race, that a keyboard
    answer still leaves the hook waiting, that `ExitPlanMode` still ignores `allow`, and that
    `-p` runs still wait on the hook. These are behaviours, not shapes; verify them on a
    throwaway session.
19. **The title hook's process tree**: the nested-claude guard and the tmux-variable recovery.
    Test: `scripts/test-claude-title.sh`, which must run inside a tmux pane (it skips otherwise).
20. **Device argv**: `cd server/appd && HUGINN_DEVICE_BIN=<path to huginn> node --test
    test/device-policy-cases.test.js` runs the shared policy matrix against the Go runner's
    `huginn device print-argv`.
21. **Compaction, hand-back and paste shapes** (see the notes under
    [Files Huginn reads](#files-huginn-reads)): the `isCompactSummary` record, the idle-compaction
    hook order, the `<agent-message>` hand-back frame and indent, and the `<pasted_content>` tags.
    A change draws the summary or a report as something the person typed, or leaves an idle
    session reading as working. Tests: `transcript.test.js`, `peer-transcript.test.js`,
    `typing.test.js`, `sessiongraph.test.js`, `scripts/test-claude-title.sh` (cases 8 to 10), and
    `WrappedTextTest.kt`.

### Capturing a new fixture

Pane fixtures under `server/appd/test/fixtures/prompts/` are real `tmux capture-pane -p -e`
output; do not edit them by hand. Capture from a throwaway session on its own tmux socket, so
nothing touches your real sessions, and name the file after the shape and the width:

```bash
tmux -L huginn-capture new-session -d -s cap -x 80 -y 24 "claude --setting-sources ''"
# drive the session to the dialog you need, then:
tmux -L huginn-capture capture-pane -p -e -t '=cap:' > server/appd/test/fixtures/prompts/<shape>-80.txt
tmux -L huginn-capture kill-server
```

`--setting-sources ''` keeps your own settings and Huginn's hooks out of the throwaway session.
Check the capture contains the dialog before you commit it.

## Stable interfaces

These are structured, and Huginn builds on them freely:

- **Hook input** (JSON on stdin): `hook_event_name`, `session_id`, `transcript_path`, `cwd`,
  `notification_type`, `tool_name`, `tool_input`; for `PostModelSwitch` also `from_model`,
  `to_model`, `requested_model`, `source`.
- **Hook output**: the `PermissionRequest` decision object above.
- **Huginn's own files** under `/run/huginn-claude-state/`, whose schema Huginn defines.
- **Transcript JSONL** record and block types listed above.
- **`--output-format stream-json`** events: `system`/`init`, `stream_event`, `assistant`,
  `result`.
- **CLI flags in use**: `-p`, `--output-format`, `--verbose`, `--include-partial-messages`,
  `--model`, `--effort`, `--resume`, `--name`, `--append-system-prompt`,
  `--append-system-prompt-file`, `--allowedTools`, `--disallowedTools`, `--setting-sources`,
  `--strict-mcp-config`, `--no-session-persistence`, `--tools`, `--max-turns`; the subcommands
  `auth status`, `auth login`, `auth logout`; `--version`.

## What Huginn deliberately does not use

- **The Agent SDK inside the daemon.** `huginn-appd` is a zero-dependency daemon running as root;
  an SDK would add a dependency update cycle to the most privileged component.
- **stream-json for interactive sessions.** It exists only for `-p` children. The transcript JSONL
  is the only history an interactive session has.
- **Checkpoints and `/rewind`, and preview features.** No client surface consumes them, and
  adopting preview features would bring TUI-scrape-style churn into the structured interfaces.

## Related

- [architecture.md](architecture.md): where the daemon, the hooks and the clients sit.
- [testing.md](testing.md): running the suites and the count floors.
- [../reference/server-config.md](../reference/server-config.md): `HUGINN_APPD_PERSONA_FILE`,
  `HUGINN_APPD_EXTRA_TOOLS` and the other daemon settings.
