# Changelog — huginn-appd

The daemon versions independently of the clients. Its releases are tagged
`appd-vX.Y.Z` and their notes are cut from **this** file.

Backfilled 2026-08-14 for 2.55.1 → 2.59.1: appd previously had no changelog of its own, so
`scripts/github-release.sh` pointed the `appd` component at `mobile/CHANGELOG.md` — where appd
appeared only as a side-note on the app releases it happened to ship with. Three versions shipped
undocumented, and the notes-cutting matcher could fuse two sections when an app and an appd
version number collided. Entries below are reconstructed from the shipping commits.

## 2.60.0 — 2026-08-23

### Added
- **Rounds — work this host does on a schedule, and the report it comes back with.**
  A Round is not a new execution engine: it creates a chat and posts one message to it, so
  every transcript, SSE stream, cancel button and push notification a chat already had
  applies to a scheduled run for free. `/v1/rounds` CRUD plus `/run`, a 30 s tick against a
  persisted `nextRunAt` (no cron, survives restarts), one chat per run so a wedged week
  cannot poison the next.
  - **Structured schedules, not cron strings** (`daily`/`weekly`/`monthly`/`interval` with an
    IANA zone), resolved through `Intl`. The briefing cron once ran at 2pm for months because
    its hours were written as if UTC on a box in `America/Los_Angeles`; a schedule that
    carries its own zone cannot drift into that. The cadence renders itself ("Sundays at
    7:00 PM") so no client owns a second copy of the rules.
  - **An output contract, parsed rather than quoted.** A Round's prompt carries a fenced
    `huginn-report` block (status / headline / items each with a suggested next step) and the
    daemon reads it out of the run. Same lesson as the briefing's move to
    `--output-format json`: success has to be a FLAG, not a guess. A missing or malformed
    block is recorded as `unknown` + `malformed` rather than dropped, because a broken
    contract that goes silent looks exactly like a clean week.
  - Rails for unattended work: `ask` mode unless asked otherwise, `notifyWhen` defaulting to
    `attention`, overlap skip, missed-fire skip with a `catchUp` opt-in, a 15-minute per-Round
    cap rather than the 2 h global one, and a busy pool deferring to the next tick.
  - Reports go down the EXISTING delivery funnel — push first, Telegram only when the app has
    gone quiet — rather than inventing a second policy beside the one in `lib/clients`.
  - A Round's run chats are hidden from `/v1/chats`. Listing them would have announced every
    scheduled run twice: once as `chat_finished` from the alert watcher, once as its report.
- **Devices — other machines that can run a chat in their context.** `/v1/devices` to enrol
  and list, a long-poll for work, batched results back, and a `host` field on a chat that
  decides where it runs. Local spawns as before; remote hands the same argv shape to the
  device, which streams the same stream-json back, so the transcript store, SSE, push and
  prompt-cards are untouched.
  - **The daemon sends a request, never a permission.** No tool grants travel in a work item;
    the device builds its own argv from its own scope. Otherwise one leaked bearer token would
    stop meaning "this host" and start meaning "the owner's PC". There is a test asserting the
    absence.
  - Pull, not push: a device needs no inbound port and no static address, so a laptop away
    from home behaves exactly like the desktop next door.
  - Results arrive in batches with an explicit terminal frame, not as one long chunked upload:
    a home network drops, and a dropped stream is indistinguishable from a finished run.
  - A Round can name a device, which is the thing neither feature could do alone.

### Fixed
- **A reused tmux session name served the DEAD session's conversation.** State files under
  `/run/huginn-claude-state` are keyed by session NAME, and the name outlives the session:
  Claude's `SessionEnd` hook is what removes the file and that hook never fires on a kill.
  Measured on the author's host, 24 state files existed for 5 live sessions, the oldest a
  month dead. Reuse one of those names and the transcript route served the corpse while the
  screen tab — which scrapes the live pane and cannot lie — showed the real session.
  `session_created` now separates them, and the create route clears what the last holder of a
  name left behind, including the `ask`/`plan`/`compacting` sidecars.
- **`display-message` was trusted in two ways it cannot be.** A bare `=name` target returns an
  EMPTY string with exit 0 — every format field blank, no error — so it needs the trailing
  colon; and unlike `has-session` it exits 0 for a session that does not exist. The returned
  `#{session_name}` is now the proof the target resolved. The same trap had been sitting in the
  create route's `#S` readback since it was written, silently falling through to the requested
  name, so its "what tmux actually called it" safeguard had never once worked.
- **The tmux server is now started in its own scope** (`systemd-run --scope`, only when no
  server is running). It used to daemonise from `POST /v1/sessions` and inherit this unit's
  cgroup, which made `systemctl restart huginn-appd` a SIGTERM to every Claude Code session on
  the box — a routine deploy would have killed them all and reported success. Also ends the
  `ProtectSystem=strict` inheritance that made `/opt` read-only inside app-created sessions.
  A `KillMode=process` drop-in is the floor under this on the live host.

### Changed
- Run-close bookkeeping moved out of the spawn's own handler into `settleRun`, so a run that
  happened on another machine reaches the same ending. It carries three separately-learned
  lessons — the durable finish mark, the Round hand-off, cancel-means-stop — and a second copy
  for the remote path would have drifted from it within a release.
- One implementation of "stop this run" (`cancelRun`), shared by the cancel route and a Round
  timeout.

## 2.59.2 — 2026-08-14

### Added
- **`Skill` is granted to both ask and act chats**, so phone and desktop conversations can
  reach the host's project skills (23 of them) instead of re-deriving what they already
  document. A skill is markdown instructions rather than a capability: invoking one cannot
  exceed the tools already granted, and ask mode's deny list is untouched. They resolve only
  because `HUGINN_APPD_WORKDIR` points at the project — skills are cwd-scoped, verified by a
  headless run outside the project seeing none of them.

## 2.59.1 — 2026-08-11

### Fixed
- **Tall prompt dialogs went undetected at narrow pane widths.** `detectPrompt`'s run collection,
  header scan and question extraction were all bounded by a fixed `lastContent - 24` lookback. A
  3-4 option dialog with wrapping descriptions runs taller than 24 rows once the pane is narrow —
  fine to 72 columns, but at 64 columns option 1 sits 25 rows above the last line, one past the
  window. The run then started at option 2, the "must be 1..n contiguous" guard failed, and the
  whole prompt was discarded, so a single-question AskUserQuestion fell back to the degraded
  "use the Screen tab" card. Reproduced and bisected by width.

## 2.59.0 — 2026-08-11

### Added
- **`POST /v1/sessions/:name/compact`** types `/compact` into the pane, backing the clients'
  one-tap context manager. Same guards as `/soft-end`: refuses a plain shell with no recorded
  Claude state, never fires while a question is waiting, and reports the queued case when sent
  mid-turn. Live-verified: PreCompact fires and the composer clears.
- Context-used and compaction signals for the conversations surface.

### Fixed
- `isCompacting()` gained a 5-minute mtime TTL so a missed PostCompact cannot pin "Compacting…"
  on forever.

## 2.58.0 — 2026-08-11

### Fixed
- **A multi-question AskUserQuestion could not be answered from the desktop buttons.** A
  multi-part dialog is answered through the TUI's tab strip, so the single digit-then-Enter path
  over-answers: the digit selects and advances question 1 while the Enter confirms question 2's
  default, silently answering two and skidding the pane past the card, after which every tap
  409s. `promptFor` now detects `questionCount > 1` and serves a deliberately non-answerable
  degraded card that routes to the Screen tab rather than misfiring buttons.

### Added
- `parseStatusLine` splits the `·`-separated statusline to extract context and compaction state.

## 2.57.0 — 2026-08-11

### Fixed
- **Root cause of "buttons sometimes work, sometimes don't; text sometimes off".** New pure
  module `lib/ask.js` validates the hook's AskUserQuestion sidecar and fuses it with the pane run
  `detectPrompt` found: positional match on a whitespace-collapsed prefix (covering width
  truncation in either direction), TUI-added rows flagged `extra`, and `multiSelect` required to
  agree or it drops to pane-only. The fused prompt carries the hook's exact question, labels and
  descriptions alongside the pane's caret, which makes `promptFingerprint` **width-stable** — the
  same question at 80 and 46 columns now fingerprints identically. That is what had made a
  lock-screen answer 409 as "changed" after a re-wrap.

## 2.56.0 — 2026-08-11

### Added
- **Soft end / hard end.** `POST /v1/sessions/:name/soft-end` types a wrap-up phrase into the
  pane; with auto-end on (the default) the session is hard-ended once it settles. The timing is
  pure, in `lib/softend.js`: a 3s idle-stability gate closes the queued-phrase race, attention
  cancels, 60s arm-timeout, 6h TTL.
- Uploads are retrievable over GET.

### Fixed
- `hardEndSession()` now also removes the orphaned `/run` state file (Claude's SessionEnd hook
  never fires on a kill) and releases the pane lease; the DELETE route and auto-end share it.
- The state watch starts unconditionally at boot, so auto-end responds within a second whether or
  not alerts are enabled.
- Pane-scrape fixes.

## 2.55.1 — 2026-08-10

### Security
- **`authorized()` uses string operations instead of `/^Bearer\s+(.+)$/`.** The regex backtracked
  polynomially on a hostile header, pre-auth, on every request (js/polynomial-redos). Node's 431
  header cap bounded the damage; the parse is now linear regardless.
- **`MEMPALACE_MARKER` is validated against a plain path charset** before it can be interpolated
  into the ssh probe command. A value carrying shell syntax now disables the probe instead of
  reaching a shell (js/shell-command-injection-from-environment). The variable is root-set, but
  the flow should not exist.
- `accounts.js` insufficient-password-hash dismissed as a false positive: the SHA-256 derives an
  identifier from a rotating token and nothing verifies against it.
