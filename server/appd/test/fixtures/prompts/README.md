# Captured prompt/pane fixtures

Real `tmux capture-pane -p -e` output from a **throwaway** `claude` session
(never the owner's), captured 2026-08-10, Claude Code **2.1.227**, geometry
**80x24** unless the name says `-46`. These are the regression net for
`lib/pane.js` `detectPrompt`/`parseStatusLine`/`parseSpinner` and for
`lib/ask.js` fusion. Do not hand-edit — recapture from a real session if the
harness TUI changes (see the capture recipe in `docs/dev/claude-code-integration.md`).

Each capture was taken in the same shell command as a sentinel `grep`, so a
mistimed capture failed loudly instead of becoming a fixture.

| file | shape it pins |
|---|---|
| `ask-wrapped-desc-80.txt` / `-46.txt` | one-question AskUserQuestion, a ~90-char option label that WRAPS at 80 and again at 46 + descriptions + the TUI-added `4. Type something.` / `5. Chat about this` rows. The width-instability pair: fusion must produce the SAME fingerprint from both. |
| `ask-wrapped-desc.input.json` | the exact `AskUserQuestion` `tool_input` the hook would deliver for the pair above (read verbatim from the throwaway's transcript). |
| `ask-multi-80.txt` | multiSelect question, `[ ]` rows, descriptions. |
| `ask-multi-toggled-80.txt` | same after toggling options 1 and 3 in tmux (`[✔]`). |
| `ask-multi-review-80.txt` | the Right-arrow review/submit tab. |
| `ask-2q-tab1-80.txt` | a TWO-question AskUserQuestion on question 1, with the `←  ☐ Database  ☐ Cache  ✔ Submit  →` tab strip — the multi-question header case. |
| `ask-review-80.txt` | the review/submit screen reached after both questions are answered (headers `☒ ☒`, "Ready to submit your answers?", Submit/Cancel). |
| `ask-preview-80.txt` | options carrying `preview` content (side-by-side layout). |
| `plan-approval-80.txt` | ExitPlanMode approval dialog above a numbered plan body. |
| `plan-approval-with-task-80.txt` | same with a background task row on screen (the SPINNER_RE / progress-row regression). |
| `exitplanmode.input.json` | the runtime `ExitPlanMode` `tool_input`. **NOTE: at 2.1.227 it DOES carry `plan` (full markdown) + `planFilePath`, contradicting `sdk-tools.d.ts` which marks the field deprecated/absent.** The plan sidecar can therefore be richer than a marker. |
| `trust-dialog-80.txt` | the folder-trust dialog (question sits several lines above the option run; OSC-8 `Security guide` link is NOT the question). |
| `statusline-manual-80.txt` | `⏸ manual mode on` status line. |
| `statusline-plan-hint.txt` | `⏸ plan mode on` / `⏵⏵ accept edits on` hint lines (MODE_HINT_RE + multi-word liveMode). |
| `bash-permission-80.txt` | **2.1.306 (2026-10-23)**: a real Bash permission dialog, captured from a throwaway session in a neutral directory — the top rule, the description, the command between `╌╌╌` rules, `Yes` / `Yes, and always allow access to … from this project` / `Yes, and switch to auto mode` / `No`. The shape the request registry's keystroke fallback and `permhook.dialogCommandBlock` bind to. |
| `bash-permission.hook.json` | the `PermissionRequest` hook payload for it (2.1.306): `tool_name`, `tool_input`, `permission_mode` and `permission_suggestions` (`addDirectories` + `setMode`); the directory replaced by a placeholder. No `tool_use_id` on this event. |
| `bash-permission-multiline-80.txt` | **2.1.306**: a Bash dialog whose command WRAPS: the command block's rows carry a `│ ` gutter and the wrap falls at spaces, so `permhook.dialogCommandBlock` does not reproduce the command byte for byte. The legacy content match therefore misses this shape; the registry answers it by id. |
| `plan-approval-v3-80.txt` | **2.1.306**: the ExitPlanMode dialog — `Yes, and use auto mode` / `Yes, manually approve edits` / `Tell Claude what to change` (an INPUT row: a digit focuses it, typed text replaces its label, Enter sends the feedback, shift+tab approves with it). |
| `plan-approval-feedback-typed-80.txt` | the same dialog after pressing `3` and typing a sentence: the row shows the words, the caret sits on it. |
| `plan-approval-manual-80.txt` | **2.1.306**: the ExitPlanMode dialog in the DEFAULT (manual) permission mode — `Exit plan mode?` / `Claude wants to exit plan mode` / `Yes, and switch to default (ask each time) for this session` / `No`. No plan text, no feedback row; feedback reaches Claude only through the hook (`deny` + `message`). |
| `exitplanmode-2.1.306.hook.json` | the `PermissionRequest` payload for ExitPlanMode (2.1.306): `tool_input` is `{plan, planFilePath}` (path replaced by a placeholder), no suggestions. A hook `allow` here is accepted and IGNORED; a `deny` with a `message` IS honoured (the plan comes back revised with the message as its reason). |
| `posttoolusefailure-read.hook.json` | a `PostToolUseFailure` payload (2.1.306, a Read of a missing file): `tool_use_id`, `error`, `is_interrupt`, `duration_ms` beside `tool_name`/`tool_input`. What the title hook drops a parked request on. |
| `ask-3q-preview-80.txt` | **2.1.306**: a THREE-question AskUserQuestion on question 1 — the `←  ☐ Database  ☐ Monitors  ☐ Name  ✔ Submit  →` strip, two options with a `preview` box drawn beside the focused one, `Notes: press n to add notes`, and an UNNUMBERED `Chat about this` row (no `Type something.` row on this build). |
| `ask-3q-preview.input.json` | the exact `tool_input` of that call, with `preview` on question 1's options and `multiSelect` on question 2. The model was asked for a `kind: "text"` question and produced a plain choice: the text/number kinds are gated behind an SDK flag on this build. |

A real permission dialog is `bash-permission-80.txt` (2.1.306). The older hand-verified inline
fixture in `pane.test.js` (the "Do you want to create hello.txt?" case) still covers the
2.1.227 shape. The `*.hook.json` fixtures are whole hook payloads with host paths replaced by
placeholders (`/home/alex/…`); the pane captures are verbatim.
| `fast-mode-unavailable-80.txt` | **SYNTHESISED, not captured** (Claude Code 2.1.306 wording): a refused `/fast on` with the `Fast mode requires usage credits …` sentence above a live composer and a `[alex] Fable 5.1 · ctx 4% · main` status line. Pins `fastModeUnavailable` in `lib/pane.js`. Replace it with a real capture the first time the sentence is seen on a live session. |
