# How huginn interfaces with the Claude Code harness

huginn drives Claude Code (the `claude` CLI) by three kinds of interface:
**hooks + files** (structured, stable), **CLI flags on `claude -p`** (structured,
stable), and **scraping the tmux pane / transcript** (fragile — breaks when the
TUI changes). This doc is the map, the adoption status of each, and — most
importantly — the checklist to run when `claude` updates.

Pinned reference: **Claude Code 2.1.227** (npm-global,
`/usr/lib/node_modules/@anthropic-ai/claude-code/`). The local schema oracle is
`.../sdk-tools.d.ts` (tool input/output types) — read it, don't guess. Model ids
are `strings(1)`-discovered from the binary (`bin/claude.exe`).

> **When `claude` updates, run the "Fragility checklist" below.** Most of these
> are TUI scrapes that have broken silently before (prompts vanishing, wrong
> labels). The regression net is `server/appd/test/pane*.test.js` +
> `test/ask.test.js`, fed real captured panes under `test/fixtures/prompts/`.

## Adoption matrix

| Area | How huginn uses it today | Status |
|---|---|---|
| **Hooks → state files** | `huginn-claude-title` (7+ events) writes `/run/huginn-claude-state/<sess>` `{state,sessionId,transcript,cwd,ts}` — the ONLY tmux-session → transcript map. | adopted |
| **Hooks → prompt sidecars** | PreToolUse(AskUserQuestion/ExitPlanMode) writes the exact `tool_input` to `/run/huginn-claude-state/{ask,plan}/<sess>`; appd `lib/ask.js` fuses it with the pane scrape for correct, width-stable button labels. Cleared on PostToolUse(same)/UserPromptSubmit/Stop/SessionEnd. | adopted (2026-08) |
| **fs.watch on the state dir** | sub-second alert + auto-soft-end response; 10s poll is the floor. Started unconditionally at boot (not gated on alerts). | adopted |
| **`claude -p --output-format stream-json`** | headless chats: `--verbose --include-partial-messages`, `system/init.session_id` captured for `--resume`, `stream_event`/`assistant`/`result` consumed. | adopted |
| **`--output-format json` envelope** | `huginn-briefing.sh` and `statuspage-investigate.sh` gate on `is_error`/`subtype` (an rc==0 API failure is otherwise logged as success). NOT used for `huginn -p` (its output is read by humans on a phone). | adopted (2026-08) |
| **`--allowedTools` / `--disallowedTools`** | ask/act fence. `--allowedTools` only AUTO-APPROVES; the deny list is the real fence. appd `TOOLS`/`DISALLOWED`; `huginn.sh -p/-y` kept in step. | adopted |
| **`--append-system-prompt`** | the unattended persona (`persona.md`) on `-p`/`-y`, briefing, escalation. | adopted |
| **`--setting-sources '' --tools '' --max-turns 1`** | the caged suggestion generator (no CLAUDE.md, no tools, one turn). | adopted |
| **Model discovery** | `strings(1)` over `bin/claude.exe`, cached on size+mtime (survives `claude update`); falls back to family aliases. `HUGINN_CLAUDE_BIN` overrides the path. | adopted; watch for a supported list command |
| **`--resume <session-id>`** | chat continuity; id from the `system/init` stream event. | adopted |
| **Transcript JSONL** | `lib/transcript.js` reads `user`/`assistant`/`system`/`queue-operation`/`ai-title`/`permission-mode`; block types text/thinking/tool_use/tool_result; `AskUserQuestion`→AskCard, `ExitPlanMode` NOT surfaced (no consumer besides the pane path). | adopted |
| **SubagentStop hook** | registered 2026-08 (keeps the tab state accurate through subagent bursts). | adopted |
| Agent SDK inside appd | — | **rejected**: appd is a zero-dependency root daemon; an SDK adds an update treadmill to the security-critical component. |
| stream-json for tmux *session* history | — | **rejected**: stream-json exists only on `-p` children; the transcript JSONL is the only history for an interactive session. |
| checkpoints / `/rewind` | — | **rejected**: no client surface consumes it; routes without UI are dead weight. |
| "Channels", teleport, resume-by-name | — | **rejected/unverified**: adopting preview features re-imports TUI-scrape-class churn into the structured channel we adopt to escape it. |

## Fragility checklist — run on every `claude` update

Each item is a place huginn reads the TUI's shape. When the harness redraws, one
of these breaks silently. Anchor test in parentheses.

1. `lib/pane.js` **`detectPrompt`** — option regex, caret `❯`, checkbox glyphs,
   the ≤4-footer-line budget, 24-line window, 1..n contiguity, the ≥10-option
   refusal, the option-1 break (plan bodies), the tab-`headers` parse.
   (`pane.test.js`, `pane-fixtures.test.js`)
2. `lib/pane.js` **`SPINNER_RE`** — must match the running spinner glyphs but
   NOT the progress-row glyphs `◯◐◑◒◓⧉` (else a workflow row under a plan
   approval reads as chrome and nulls the prompt). (`pane-fixtures.test.js`)
3. `lib/pane.js` **`MODE_HINT_RE`** (`⏵⏴⏸⏹▶`) and **`parseStatusLine`** mode regex
   (multi-word, e.g. "accept edits"). (`pane-fixtures.test.js`)
4. `lib/pane.js` **`parseStatusLine`** model/branch — depends on
   `huginn-statusline.sh`'s `[sess] Model · branch` line, a contract we author on
   BOTH sides. Change one, change the other.
5. `lib/pane.js` **`parseSpinner`** / **`parseStatusExtras`** — spinner glyph
   class, `N/M agents done` phrasing, the durable-vs-transient row split.
6. `TranscriptGroups.kt` **`plannedAgents`** — re-parses `N/M agents done` from
   the server-scraped status strings (client side).
7. `lib/pane.js` chrome constants (`❯`, `⏵⏵`, `[name]` bracket) used by
   `previewLines` + `detectPrompt`'s `isChrome`.
8. `lib/pane.js` **`extractLoginUrl` / `loginPaneState`** — `claude auth login`
   wording (`Paste code here`, `Login successful`).
9. `huginn-appd.js` `/answer` multi-select **key choreography** (digits → Right →
   Enter) and the 150/120/250 ms timings.
10. `lib/transcript.js` **`machineText`** bracketed-preamble allowlist — a new
    injected-prompt wording renders as a user bubble.
11. `lib/tasks.js` the `~/.claude/shell-snapshots` + `eval '…'` bash-wrapper argv
    shape.
12. `lib/models.js` **`strings(1)`** discovery — depends on model ids being
    literal strings in the bundle and on the npm-global binary path.

Plus the **hook event roster + `tool_input` shapes** (verify against
`sdk-tools.d.ts`): a renamed field or event silently drops a sidecar back to the
pane-only path.

## Stable interfaces (safe to depend on)

- Hook stdin JSON: `hook_event_name`, `session_id`, `transcript_path`, `cwd`,
  `notification_type`, `tool_name`, `tool_input.*`.
- The `/run/huginn-claude-state/…` files (huginn's own schema).
- Transcript JSONL record + block types (above).
- `--output-format stream-json` events (`system/init.session_id`,
  `stream_event`, `assistant`, `result`).
- `--output-format json` envelope (`is_error`, `subtype`, `api_error_status`,
  `stop_reason`, `result`, `total_cost_usd`).
- CLI flags in use (`-p`, `--model`, `--effort`, `--resume`,
  `--append-system-prompt`, `--allowedTools`, `--disallowedTools`,
  `--include-partial-messages`, `--setting-sources`, `--tools`, `--max-turns`,
  `--output-format`, `auth status|login|logout`, `--version`).

## Verification log

- **2026-08-11** — pinned 2.1.227. Confirmed via `sdk-tools.d.ts`:
  `AskUserQuestionInput` = 1–4 questions × {question ends `?`, header ≤12 chars,
  2–4 options {label, description, preview?}, multiSelect}; the "Other" row is
  TUI-added. **Live finding:** `ExitPlanModeInput` DOES carry `{plan,
  planFilePath}` at runtime, contradicting `sdk-tools.d.ts` which marks the field
  deprecated — the plan sidecar carries real plan text. Notification types
  observed: `idle_prompt`, `permission_request`. Adopted prompt sidecars +
  fusion (appd 2.57.0). Open: a supported model-list command to retire
  `strings(1)`; `claude update` ENOTEMPTY churn detection (a wedged update once
  stalled the host ~2 weeks).
