# Captured prompt/pane fixtures

Real `tmux capture-pane -p -e` output from a **throwaway** `claude` session
(never the owner's), captured 2026-08-10, Claude Code **2.1.227**, geometry
**80x24** unless the name says `-46`. These are the regression net for
`lib/pane.js` `detectPrompt`/`parseStatusLine`/`parseSpinner` and for
`lib/ask.js` fusion. Do not hand-edit — recapture from a real session if the
harness TUI changes (see the capture recipe in `docs/HARNESS.md`).

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

A real permission dialog is NOT captured here — the attempt mistimed onto the
composer. Permission-dialog detection is covered by the hand-verified inline
fixture in `pane.test.js` (the "Do you want to create hello.txt?" case).
