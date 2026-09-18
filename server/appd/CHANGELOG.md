# Changelog — huginn-appd

The daemon versions independently of the clients. Its releases are tagged
`appd-vX.Y.Z` and their notes are cut from **this** file.

Backfilled 2026-08-14 for 2.55.1 → 2.59.1: appd previously had no changelog of its own, so
`scripts/github-release.sh` pointed the `appd` component at `mobile/CHANGELOG.md` — where appd
appeared only as a side-note on the app releases it happened to ship with. Three versions shipped
undocumented, and the notes-cutting matcher could fuse two sections when an app and an appd
version number collided. Entries below are reconstructed from the shipping commits.

## 3.3.0 — 2026-09-18
- **`GET /v1/files/image`** — a host image Claude named by path, served to the client instead of
  printed as a path. Roots are the uploads dir, the scratchpad render dir, Claude's own scratch dir,
  and — only with `?session=` — that session's cwd. Containment is checked twice, lexically and then
  against `realpath` on both sides; a root that cannot be realpath'd is dropped rather than matched
  as a string. `nosniff`, `private, max-age=300` and an `ETag`/`304` on every response; 403, 404,
  415 and 413 for outside-root, missing, non-image and oversize. SVG is refused by name:
  `image/svg+xml` is the correct type for those bytes, which is precisely the problem.
- **`?session=` widens nothing unless the session is live on tmux.** A state file outlives the
  session that wrote it, so liveness is asked of tmux and the cwd of the state; an ended session
  and a killed one both stop widening the roots, and they fail in opposite directions.
- **`pruneUploads` sweeps at most once a minute.** A ten-file attach was ten full readdir+stat
  sweeps of the same directory on the event loop streaming those ten uploads to disk. Retention is
  measured in days; the sweep only has to beat a burst. Images are still never pruned.

## 3.2.1 — 2026-09-18
- **A STOP the session window armed now clears when that window resets.** The hook gate's STOP
  sentinel arms at `stopPct` of the 5-hour window and was meant to clear once it dropped under
  `clearBelowPct` — but the clear also required the WEEK to be under that number, so a sentinel
  armed at "session 71%" stayed up after the window reset to 4% because the week read a perfectly
  normal 69%, and every spawn on the host was held for hours. The clear now asks only the window
  that armed it; a week-armed STOP keeps the week's own hysteresis (red arms it, low clears it),
  and a red week still arms regardless.

## 3.2.0 — 2026-09-17
- **Archive a session: fully ended, with the way back kept.** `POST /v1/sessions/:name/archive`
  ends a session gracefully by default (the wind-down phrase, a 409 while a question is waiting,
  the kill once it settles; `mode:"now"` is the escape hatch) and keeps a card: title, folder,
  model, last message, the exact `cd '<cwd>' && claude --resume <uuid>`, and a COPY of the
  transcript — Claude Code clears its own after `cleanupPeriodDays`, so without the copy a stored
  resume command would silently expire. `POST /v1/archive/:id/revive` recreates the session under
  its old name (`<name>2` when taken), restoring the kept transcript first when Claude Code no
  longer has one, and marks it launching so the startup hold covers its first message. Archived
  conversations never appear in `/v1/sessions`, so the picker, the palette and the widget inherit
  nothing. 64 rows, oldest evicted first; rows never time-expire; `DELETE` is the only remover.
- **Keep-awake (off by default).** Optionally send one tiny request when no 5-hour usage window is
  running, so a work session never starts on a cold window. Between windows the usage endpoint
  reports the session row with no reset time; that one field is the trigger. Haiku 4.5 pinned by
  dated id, at most once per window, persisted before the spawn so a restart cannot double it,
  optional local quiet hours. Vetoed by a red or exhausted window, an armed STOP/STOP-FABLE, an
  account switch in flight or cooling down, an unreadable usage endpoint, and by having already
  fired inside the current window. `/v1/status` reports whether a window is running, when it ends,
  and why keep-awake last did or did not fire. Nothing activates on upgrade: Settings → Usage &
  headroom → "Keep a window rotating".
- **A first message the pane swallowed is now sent, not merely logged.** Bytes pasted in the last
  second before Claude Code paints are discarded before the TUI attaches, so waiting for them could
  only time out — and the Enter that followed went into a composer that was by then up and empty.
  On a timeout the daemon looks once more: a composer drawn and holding nothing gets the message
  re-pasted once, then Enter; a composer holding anything else is left strictly alone (a bare Enter
  there submitted somebody's half-written line — it did); a pane with no composer keeps the old
  behaviour. Six sends made ~800 ms before the paint against the real binary: six delivered, each
  exactly once.
- **The startup hold covers the sessions `cc` makes, which is most of them.** The gate was keyed on
  a mark only the create route set. A session with no mark is now held when tmux says its window
  was born seconds ago, the pane has no composer and no shell prompt, and the pane was started with
  a command that runs `claude`. A plain shell is never held, nor an empty pane running something
  else. `HUGINN_APPD_STARTUP_GRACE_MS` widens the 20 s ceiling.
- **Renaming a session no longer strands the messages queued for it.** The rename moved everything
  but the send queue and the startup mark; both move now, the queue's timer with them, and a rename
  to the name it already has stops erasing the row it was meant to move.
- **A held send says what it is waiting for.** `POST /v1/sessions/:name/keys` answers `blockedBy`
  beside the queue position, in the words `GET /typing` uses.

## 3.1.1 — 2026-09-17
- **A skill Claude loads is a card, not a message from you.** Claude Code writes a skill's whole
  instruction body into the transcript as a `user` record with nothing in the text to mark it, so
  the Conversation tab showed it as a multi-kilobyte message you appeared to have typed (16.6 KB
  of one skill, in one real session). The `Skill` card is now the whole of a skill load and names
  the skill instead of dumping its input; the body folds into it, or shows as one compact chip when
  the call itself has scrolled off. The record's `sourceToolUseID` is the tell — `isMeta` alone also
  marks image captions and a resumed run's "continue" line, which must stay messages. Replayed over
  891 transcripts: 65 user bubbles reclassified, nothing else moved.
- **A paste is confirmed on screen before Enter is pressed.** Measured against the real Claude Code:
  bytes that arrive 0.8–2 s before the composer paints sit in the box unsent, and 3.0.7's 20-second
  grace could expire and deliberately paste into a pane it had just found empty (it did, on the
  owner's own session at 23:54:49). Every send now waits — 50 ms polls, 3 s bound — until the
  composer visibly holds the text or a `[Pasted text]` marker, then presses Enter, then confirms
  the composer let go within a second; otherwise it writes a grep-able journal line with the pane's
  bottom rows. The composer is the LAST caret on screen, never the bottom region: a submitted
  message is echoed above the box with the same `❯`.

## 3.1.0 — 2026-09-16
- **A serving device says whether it serves while nobody is logged in.** The generate-scope device
  row carries a `persistent` facet, accepted at enrolment and on the 60-second beat, so Devices
  can tell a machine that serves 24/7 from one that serves only while somebody is logged in.
  Nullable on the wire: a runner that never said carries no field at all (older CLIs are
  unaffected, and a client can tell "no" from "never said").
- **`/v1/ping` says which of the daemon's addresses answered you** — `via: {addr, port}`, the
  local end of the caller's own socket and nothing else. Clients with pinned routes use it to
  label the route they are actually on.

## 3.0.7 — 2026-09-15
- **Your first message no longer prints under the answer to it.** Claude Code writes the opening
  prompt of a run three ways — a queue `enqueue`, a `dequeue` when it drains, and the ordinary
  `user` record — and the transcript reader treated the `dequeue` as nothing. The prompt stayed
  "queued", floated to the bottom, and the real record was dropped as its duplicate. Every chat and
  every session did this; escalating a local chat to Claude made it obvious, because there the whole
  conversation is one long message and one reply. A drained queue is now DELIVERED at the point it
  drained, in send order, and each delivered message swallows exactly one later copy of itself.
  Replayed over all 907 transcripts on the host: 838 reorder, nothing gained, nothing lost.
- **A message sent the instant a session is created is held until Claude is up, then delivered.**
  `POST /v1/sessions` answers ~30 ms after tmux starts; Claude Code draws its composer about two
  seconds later, in one write. A paste in that window vanished, or submitted and came back typed
  but unsent. The send queue now knows a third reason to wait — `blockedBy: "starting"` — for a
  session the daemon launched whose composer has not appeared; it releases the moment the caret
  draws (20 s grace, then delivers anyway and says so in the journal). Only sessions appd started
  are ever held; a plain shell pane is never. Clients that show the wait should render
  `starting` as "waiting for Claude to start", not as a turn finishing.

## 3.0.6 — 2026-09-15

- **A session held up by a question says so again.** "Huginn needs you" went quiet: a session
  sitting on an AskUserQuestion or a plan approval kept reporting `running`, so both clients drew
  it as *working* — no red row, no notification, and the send queue's modal gate opened straight
  into the dialog. The state word comes from the one flat file `huginn-claude-title` keeps per
  session, and that file is last-writer-wins across the whole session: the PreToolUse that RAISES
  the dialog writes `running`, the `Notification` that corrects it to `attention` arrives about
  six seconds later (measured against a live dialog on Claude Code 2.1.258) — and fires exactly
  once, so the next tool call from any background agent of that session puts `running` back and
  nothing ever takes it off again. Wave 1 made background agents ordinary, which is when a
  long-standing race became the normal case. The fix reads the marker the hook already keeps for
  this and only this thread: the AskUserQuestion / ExitPlanMode sidecar, which is deliberately not
  cleared by another tool's PreToolUse. A `running` session with a live sidecar for its own
  session id now reports `attention`, dated from when the dialog was raised; `idle` is never
  promoted, so a sidecar whose clear was missed cannot pin a session at "needs you".

## 3.0.5 — 2026-09-15

- **The push counter survives a restart and carries an epoch.** The phone decides how often to
  wake itself by comparing the host's tally of pushes sent to this install against what actually
  arrived — and on 2026-09-15 that page read "1274 of 916 pushes arrived — nothing dropped". Both
  numbers were honest; neither was comparable. The tally lived on the token row, and a Firebase
  token reissue rebuilt that row from zero, so the host restarted counting in August while the
  phone kept counting from July. A retired registration and an unreadable `push.json` lose it the
  same way. The count now carries across a rotation, and every count carries a `pushEpoch` — a
  per-install string minted when the counter is created and reissued whenever it genuinely has to
  restart, returned on `GET /v1/push` beside each device and on `/v1/watch` (long poll and stream)
  beside `pushesSent`. A client compares only within an epoch and rebaselines when it changes,
  which is the difference between a deficit check that works and one that answers "nothing is
  ever dropped" forever. Additive: a client that does not read the field is unaffected.

## 3.0.4 — 2026-09-15

- **The daemon's own lines stop waiting on a boundary that will never come.** With a person's
  message out of the turn queue (3.0.3), what is left in it is headroom's heads-up, the model
  ladder and the resume phrase — and on plenty of real sessions the marker they wait for is never
  written at all: `system/turn_duration` appears only when a Stop hook ran, and 14 of the 25 most
  recent transcripts on this host contain none. So an assistant record that ended its turn now
  counts as a boundary too, and the title hook's own state file is read as a second source: an
  `idle` stamped after the line was queued releases it, an `attention` holds it, because prose
  typed into a numbered prompt is lost or misread.
- **A held message can say how long it has been held.** `GET /v1/sessions/:name/typing` reports
  `waitedMs` beside `blockedBy`, so a client showing a send queued behind a dialog can say "held
  for 90 s" rather than looking like nothing happened.
- **Nothing leaves the queue undelivered in silence.** Every drop writes one journal line naming
  the origin, kind, session and reason. The reason used to live only in a struct that `GET
  /typing` reads, which is why the 43 messages 3.0.2 fixed left no trace anywhere — that was half
  the bug, and it is the half a log would have caught in a minute.

## 3.0.3 — 2026-09-15

- **A person's message lands at once, busy session or not.** 3.0.0 held a message sent into a
  running session in the daemon's queue until the turn ended — nothing showed in the pane, and on
  a session inside a long agent turn the sender saw the message vanish. 2.x typed it immediately
  and Claude Code's own queue showed it under the composer and took it up after the turn; that is
  back. Only a dialog on screen still holds a message (text typed into a selector is swallowed
  with no trace). The turn-boundary queue now serves the daemon's own automated lines only —
  headroom's heads-up, the model ladder and the resume phrase, which must not land mid-turn.

## 3.0.2 — 2026-09-15

- **A message sent into a busy session is never lost.** 3.0.0's turn-boundary queue only opened
  when the transcript's LAST record was the turn marker, but Claude Code writes bookkeeping
  records after it (last-prompt, ai-title, mode, permission-mode, atis-latch, cost-state), so on
  a session that kept working a queued message waited ten minutes and was dropped without a
  word — 43 of them on one session. The boundary is now read from the last CONVERSATIONAL record,
  and a person's message is never dropped on timeout: it waits for the turn to end, however long
  that takes. Automated lines (headroom's heads-up, ladder and resume sends) keep their timeout.

## 3.0.1 — 2026-09-15

- **Quick-action templates are host-owned.** The wording the clients put in front of a quoted
  selection (Explain · Execute · Ask in new chat · Quote) now lives on this host, is served on
  `/v1/status` beside the soft-end phrase, and is editable from either client through
  `PATCH /v1/quick-actions` — so the two clients cannot drift into two different prompts for the
  same button. Control characters are stripped and each template is capped at 400 characters,
  because these strings get typed into a terminal.

## 3.0.0 — 2026-09-15

Huginn 3. The daemon becomes usage-aware: one subsystem, one arbiter, and every automated line it
types into a session goes through the same turn-boundary queue.

- **Headroom.** `lib/headroom.js` keeps a per-account model of the three windows Claude's `/usage`
  shows (session, week, week-Fable) with their reset times, polled every minute while anything is
  running, and notices a window resetting — which nothing did before. `GET /v1/headroom` is the
  whole picture; `/v1/status` carries a one-line summary; `/v1/plan` now says WHOSE usage it is
  (email, account id, plan), cached together with the numbers so a switch cannot label the new
  account's bars with the old name.
- **A long message reaches a session in one piece.** `POST /v1/sessions/:name/keys` accepted
  8,000 characters and typed them with a single `tmux send-keys -l`; the cap is now 100,000, the
  same as a chat body. Delivery switched from typing to **bracketed paste** (`tmux load-buffer`
  on stdin, then `paste-buffer -p -d`), measured at 0.011s for 20KB against 1.7s for the same
  text in 2KB chunks, with no length limit at all because the payload never touches the tmux
  command line — which has a hard ~16,340-character budget that a long session name eats into
  1:1. The pane shows ONE "[Pasted text]" placeholder instead of one per chunk. `-p` is not
  optional: plain `paste-buffer` silently rewrites every newline to a carriage return, and the
  pane renders correctly while the transcript stores the wrong bytes. `send-keys` with the
  150ms submit beat survives as the fallback when the pane buffer cannot be reached.
- **A message is no longer delivered into a turn that is still running.** The CLI queues a
  mid-turn message and then splices it into the turn in flight — recorded in the transcript as
  `"reason":"absorbed_mid_turn"` — so the message that was already running never finishes what
  it was asked to do. Session sends are now queued in the daemon and released only at a real
  turn boundary (one `system`/`turn_duration` record per completed turn) with the pane also
  checked for an open dialog, which the transcript cannot see. New `GET
  /v1/sessions/:name/typing` reports what is waiting and why; `pendingSends` joins the session
  list rows. Interrupt keys are never queued. This is also the road every automated line takes:
  the heads-up, the `/model` step-down and the resume phrase below are queued sends, which is
  why none of them can land mid-answer. New pure module `lib/typing.js` with its own suite.
- **One arbiter.** Account auto-switch (unchanged rules, now `settings.accountSwitch`), the Fable
  model ladder for LIVE sessions (a framed heads-up at 85 % so Fable writes its own handoff note,
  a session-only switch to Opus at 92 % at the next turn boundary — the `/model` picker's
  "this session only" choice, so the host's default model is never rewritten — and back up when the
  week resets, only for sessions the daemon itself moved), and the sentinels are decided in one place with one cooldown. A switch
  helps new runs only — a running session keeps its token — which is why the ladder exists. If the
  CLI moves a session off Fable by itself the daemon records it and never fights it.
- **Auto-resume.** A session stalled on a usage limit is deterministic in its transcript (an API
  error with status 429 and the reset time). Claude Code's own auto-continue handles the simple
  case; the daemon watches it and covers what it cannot: sessions it restored after a reboot or
  restart, a wait that was cancelled, a reset more than a day out, headless chats and rounds
  (re-run the turn), and — through the hook gate — workflow subagents. Only if nothing was typed
  since the error, at most three times, and it says which sessions resumed and how. Default on;
  per-session override.
- **The Fable consent dialog is a prompt card.** When Fable hits its cap the CLI asks whether to
  bill usage credits, switch for this session, or cancel — and an unanswered dialog loses the turn.
  The pane detector now recognises it and both clients offer its rows as buttons, the session-only
  switch recommended; left unanswered for two minutes on a session with auto-resume on, the daemon
  picks that row itself. The 92 % step-down exists so the dialog rarely appears at all.
- **A hook gate for workflows.** `deploy.sh` installs a `SubagentStart` (and `PreToolUse
  Agent|Workflow`) hook that HOLDS new agent spawns while `headroom/STOP` or `STOP-FABLE` exists —
  verified to hold agents spawned inside a Workflow script while the parent keeps working. The
  daemon arms the sentinels at 70 % session / 88 % Fable-week and clears them below 50 %. The gate
  delays and never denies: a hook that times out fails open in Claude Code, so the hold self-releases
  30 s before its own timeout.
- **Idle logins stay signed in.** Saved accounts that are NOT active are refreshed on a timer
  (five minutes before their access token expires) with the same grant, client id and lock file the
  CLI uses, written back under the account's own id, never touching the live credentials file — the
  active login is the CLI's to refresh, and refreshing it out from under a running `claude` is how
  it signs itself out. A switch to a login whose refresh token has expired is refused with the
  reason instead of failing on the next run. `activate()` now takes the CLI's refresh lock.
- **Subagents are streams.** `GET /v1/sessions/:name/agents?all=1` lists every agent and workflow
  member of a session, and `GET /v1/sessions/:name/agents/:agentId/transcript` reads one agent's own
  transcript with the same paging as the parent's.
- `GET/POST /v1/autoswitch` are kept as aliases for one release and go in 3.1.

## 2.85.0 — 2026-09-01

- **Sessions survive a reboot / power cut.** Until now, when huginn lost power or
  the box rebooted, the tmux server died with it and every live Claude Code session
  was gone — the conversations were still on disk (Claude Code can `--resume` any of
  them) but nothing knew which tmux session held which id. The daemon now keeps a
  durable registry of live sessions in `DATA_DIR/active-sessions.json`: a session is
  recorded when it is created, its Claude session id is filled in by a reconcile
  timer once the title hook writes it (and sessions started outside the daemon, e.g.
  interactive `cc`, are picked up the same way), and it is dropped when the session
  is killed or an auto wind-down settles. On startup, if the tmux server is GONE
  (the tell of a real reboot, since the server otherwise lives in its own systemd
  scope and outlives an ordinary `systemctl restart huginn-appd`), each recorded
  session is recreated running `claude --resume <id>`, so the conversations come
  back where they left off. An appd-only restart changes nothing — the sessions
  never died, and the restore is a deliberate no-op when the server is still there,
  so nothing is ever double-created. A session with no resumable transcript on disk
  comes back as a fresh `claude` in the same directory rather than being lost. The
  id is validated as uuid-shaped before it reaches the shell tmux runs, so a
  malformed id degrades to a fresh session instead of injecting. New pure module
  `lib/session-registry.js` with its own suite.

## 2.84.0 — 2026-09-01

- **Test sessions no longer surface in the desktop as phantom sessions.** The
  route/lifecycle test suites create throwaway tmux sessions (`life-<pid>-*`,
  `ident-<pid>-*`, `ov*_<pid>`, …) on the same default tmux socket the live
  daemon and the interactive `cc` sessions share. When a test child was SIGKILLed
  (a spend-limit kill, a wedged run) before its `after()` cleanup could fire, those
  sessions leaked and appeared in the client's session list as backend noise. The
  daemon now accepts `HUGINN_APPD_TMUX_SOCKET`: when set, every tmux call is pinned
  to a private `-L <name>` server, and the tests point it at a per-pid socket
  nobody reads. Production leaves it unset — behaviour on the default socket is
  unchanged. `-L`, not `TMUX_TMPDIR`, because an inherited `$TMUX` from the pane
  that launched the suite overrides the latter but never the former.

## 2.83.0 — 2026-08-31

Fixes from the 2026-08-30 program-wide audit.

- **Remote runs are no longer killed during a long, quiet tool call.** A device
  `/beat` now refreshes its in-flight run's liveness (runners beat every 60s, well
  inside the 5-minute silence window), so a build that streams nothing for minutes is
  not mistaken for a dead device — and the beat carries a `cancel` flag so a Stop
  reaches a run sitting in a quiet tool.
- **The "Auto-switch is stuck" push fires again.** It had been dead at the default
  threshold: the gate read an undefined value, so `percent >= undefined` was always
  false and the once-a-day warning never fired. It now uses the effective threshold.
- **A crash mid-write no longer silently destroys a chat or a scheduled Round.** Chat
  `meta.json` and round files are written atomically (tmp+rename, `0o600`), and the chat
  directory is `0o700` so a transcript stays off other users even if the data dir is
  loosened.
- **A missed Round slot is recorded as a skipped run** (and can notify) instead of only
  logging, and one corrupt round file no longer stalls every later round in the tick.
- **A project-level FCM 404 no longer unregisters the whole fleet** — a dead token is
  decided by the error code, never the bare HTTP status.
- Auto-switch no longer reverts a settings change made while its tick was running, and
  account activation reports `identityRestored` honestly.

## 2.82.0 — 2026-08-28

Tokens bill at the rate that was in force when they were spent.

- **The session estimate learned that a price is a fact about a moment.** Claude
  Sonnet 5 launched on an introductory rate — $2/$10 per MTok against the $3/$15
  sticker — and the table priced every sonnet-5 token at sticker, overstating any
  session that touched that model by a third. `lib/pricing.js` now carries a
  `RATE_ERAS` table beside the family cards, and each record reaches the pricer
  with its own timestamp. On two real transcripts on this host the model's line
  moves $0.3463 → $0.2309 and $0.5045 → $0.3363.
- **This is permanent, not a four-day patch.** The window closes on 2026-08-31,
  but a session from August priced next March still bills what August cost — the
  era is a property of the tokens, not of today's date, and nothing here expires
  when the window does. It is the rule the unsplit cache write already followed:
  price what the record says happened.
- **The era is Sonnet 5's alone.** A family is matched on a loose substring
  because families are disjoint out there; an era lives *inside* one, next to
  siblings that were never on it. `claude-sonnet-4-6` has been $3/$15 its whole
  life, so the era's marker is anchored — the id must end at the model or at one
  of the date suffixes that really reach a transcript. A leak there would have
  understated every 4-6 session by a third with a perfectly renderable number.
- **A record with no timestamp takes the cheaper of the two cards it might have
  been**, read off the cards rather than assumed. An undated record was written
  on one side of the boundary or the other and nothing says which; an estimate
  put in front of a person should under-state what it cannot evidence rather
  than invent spend it has no record of.
- **The wire shape is unchanged.** `totals.estCost.byModel` is still one row per
  model *name*: a session spanning the boundary shows a single `claude-sonnet-5`
  row whose dollars are the era-correct sum. The eras are an accounting detail of
  the pricer, and no client decodes them. `usd`, `agentEstCostUsd` and
  `unpricedTokens` keep their contracts, and rounding still happens once per
  rendered row.
- **Both accumulation paths carry the timestamp** — the spine and the per-agent
  one, which calls the pricer separately. That second one is the failure nothing
  on screen would have looked wrong for: with the timestamp dropped, an agent's
  sonnet-5 spend silently lands on the undated fallback, which is *correct* for a
  record inside the window and wrong for one after it.

## 2.81.0 — 2026-08-27

The Electron desktop client is deleted, and its update channel with it.

- **`/v1/desktop` is gone** — not emptied, not deprecated: unrouted. A GET to it
  falls through to the ordinary 404, like any path that was never a route at
  all. `DATA_DIR/desktop` is no longer read, joined or created, so a directory
  of that name left on a deployed host is stale bytes nothing can reach.
- **`/v1/desktop-kt` is untouched**, and deliberately stays under its own name
  rather than being promoted into the vacancy: the 0.5.x clients still
  installed poll THAT path, and a retired route is better left a 404 than
  quietly re-pointed at a different application's feed.
- **Why the split existed, and why removing it is safe.** Two channels, two
  directories, two manifests, a release-script assertion that the Electron feed
  was still intact after every Compose release, and a test that neither channel
  could reach the other's files — all of it protected one thing: a running
  program being handed an "update" that replaced it with a different
  application. That program no longer exists in the repo, by owner directive on
  2026-08-27 — strictly Compose. A guard with nothing behind it stops being a
  guard and becomes a rule that fails a release for the wrong reason.
- Installed Electron copies are not migrated, and now never will be: the
  graceful handover that was planned — a final notice release, then a Compose
  installer taking over the Windows install path and uninstall key — was
  superseded by the directive. Those copies simply go stale where they sit, and
  should be uninstalled by hand.

## 2.80.0 — 2026-08-27

The session overview learns what the run would have cost.

- **`totals.estCost`**: the session's tokens priced at Anthropic's published
  API list rates, broken down **per model** — a session that ran opus for the
  work and haiku for a subagent has no single blended rate, so one figure over
  both would be a number about nothing. Cache writes bill by TTL, read from the
  nested `usage.cache_creation` split (1.25× input at five minutes, 2× at an
  hour); a write whose TTL nothing recorded takes the cheaper of the two rather
  than inventing spend it has no record of. A model the table has never seen —
  the local tier, the CLI's own `<synthetic>` records — is never rounded to a
  neighbouring family: its tokens come back as `estCost.unpricedTokens`, said
  out loud instead of quietly dropped. `totals.agentEstCostUsd` is the share of
  the total that came from agent files, so a fan-out can say how much of the
  bill it was. Both are null when no record carried usage at all, and both ride
  `totals` — so the cheap `/overview` route carries them, not just `/graph`.
- ⚠ **It is an estimate, and none of it was charged.** This account is on a
  subscription; the figure answers "what would this session have billed on the
  API", and the clients caption it as exactly that. Rates live in a static
  table in `lib/pricing.js`, cached 2026-08-27 — nothing asks the network what
  anything costs, so a transcript prices the same way on a box with no route
  out, and that date is the only thing that says when the table was true.

## 2.79.0 — 2026-08-27

The devnotes wave, server side: pages, the session map, honest budgets, and
rows that leave when their machine does.

- **Scratchpads**: `/v1/scratchpads` CRUD — Main minted on first list and
  protected, rev-guarded saves that answer a 409 with the current page, and
  the reference lane: `scratchpadId` on a chat message quotes the page into
  the text server-side (composed at queue-receipt, so the transcript shows
  what was actually sent), on session keys it rides as a marker to a per-send
  snapshot file. A page whose own content contains the closing marker gets a
  tag on both ends, report-tag style. Never in the watch digest.
- **Session overview**: `lib/sessiongraph` walks a session's whole transcript
  incrementally — turns become blocks, subagents join the spine through their
  `.meta.json` tool-use id (both workflow directories now scanned; one was
  invisible), token totals are summed once per API call after measuring that
  per-record summing overcounts cache reads 2.7×. Three routes: `/overview`,
  `/graph` with a two-number cursor (`unchanged` still carries the meta, so
  goals typed on one device reach the other on an idle session), `/meta` into
  a per-`claudeSessionId` store the tmux name can never corrupt.
- **`/v1/rounds/polish`**: the caged one-shot, aimed at a Round's own runtime
  frame — sonnet, no tools, no persona, one turn, input-hashed single-flight;
  failures degrade and are never cached.
- **Plan**: the `spend` block is parsed (minor units + exponent, percent,
  severity, disabled reason) and extra usage shows for any account that EVER
  enabled credits — the old `is_enabled` gate hid a paused month's real spend.
- **Devices**: `devices.json` writes are atomic+0600 like the other stores; an
  FCM UNREGISTERED verdict retires the client row with the token; a daily
  validate-only push sweep discovers uninstalled phones on a quiet host; and
  device views carry `lastSeen` so "not reachable" can say since when.
- **A seal landing inside a request's own body window is refused** instead of
  the stale pre-read waving the message through to reopen the chat — the one
  interleave settleRun's single-turn shape cannot cover.
- Session previews filter terminal DA/DSR replies (a `❯ 4;21;…c` echo was
  presented as the session's last line).
- Removed: `POST /v1/push/test` and `POST /v1/alerts/test` — nothing real
  called them.

## 2.78.0 — 2026-08-25

- Refusals and host badges name the MACHINE a person knows ("DATATREEX"),
  never the "-llm" serving credential the UI no longer shows anywhere:
  hostNameFor, canServe, canRun and the wrong-model refusal all resolve
  through the machine key when a claude sibling names the box.

## 2.77.0 — 2026-08-25

- An UNSTARTED chat (no turns, no claude session, nothing running) may
  re-decide its model freely — including onto a local machine, between
  machines, and back to Claude. The pin protects HISTORY, which an empty
  chat does not have; before this, both clients' "New chat, then pick the
  model" flow was refused with an instruction to start the new chat the
  user was already looking at. Crossing onto a machine runs the same
  checks as creation (resolution, serving state, forced ask); a STARTED
  chat keeps every existing pin.

## 2.76.0 — 2026-08-25

- One box, ONE device. Every row now carries a `machine` key — reported by
  current runners, derived from the name for older rows (`<host>-llm` rejoins
  its host) — so every renderer folds a machine's claude and serving
  enrolments into one object with capability facets. The rows themselves stay
  separate where authority is decided: the serving credential can never gain
  claude reach, and nothing about routing changed.
- Catalog rows are titled by the machine's own name ("Qwen3 8B - DATATREEX"),
  not the serving credential's - the `-llm` names are internal labels.

## 2.75.0 — 2026-08-25

### Added
- **The local-model family.** A generate-scope enrolment may advertise a model
  catalog (display-only, cleaned at ingest); the daemon mints it a stable
  `llmSlug` and echoes it — every local row id is `local-<llmSlug>-<modelSlug>`.
  `GET /v1/models?local=1` unions those rows (`family:"local"`, `available`,
  `host`), opt-in so old clients never see rows they would 400 on. Picking a
  local row IS the host choice: the chat is forced ask-mode, pinned to its
  machine for life, and refused at the button when the machine cannot serve.
  The work item rides as mode `generate`; Rounds refuse the family at create,
  patch AND fire; a local-family run can never record spend, whatever its
  result frame claims.

## 2.74.0 — 2026-08-25

### Added
- **The `generate` policy rung** (the local-AI tier, ahead of the feature).
  `generate` joins the scope lattice as an EXCLUSIVE rung: it matches only
  itself, in both directions — so a claude device can never be offered
  local-model work, and a serving device can never be offered ask or act. A
  locked generate device keeps serving (a generate run mutates nothing, and
  dropping it to look would sideways-grant ask). Junk scopes at registration
  now floor to generate; an absent scope still enrols at look. `scopeCovers`
  replaces raw lattice comparison everywhere.

### Changed
- A pre-check refusal blames the lock only when unlocking would actually help;
  otherwise it names the scope, because the scope is what somebody would change.
- Registration scopes are normalised (trim + lowercase), matching the runners.

No generate work can be produced yet — chats and Rounds cannot reach the mode
until the local tier ships. This release is the fence, ahead of the field.

## 2.73.0 — 2026-08-25

### Changed
- **An unknown model or effort id is refused loudly.** `POST`/`PATCH` on
  `/v1/chats` and `/v1/rounds` used to treat any unrecognised model id as
  "use the host default" — a typo or a foreign id silently changed which model
  answered, and nothing said so. Absent, null or empty still means the default;
  an unknown non-empty id is now a 400 naming the id and pointing at
  `/v1/models`. Effort gets the identical matrix. A refused PATCH no longer
  half-applies the rest of the body.

## 2.72.0 — 2026-08-25

### Fixed — a broken transcript no longer kills the daemon

Found while writing a test for something smaller. `handleClaudeEvent` was called
from a stdout `data` handler with **no try/catch** — the device path at
`/work/:id/events` already had one; the local spawn did not. That function appends
to the transcript, so a disk that is full, read-only, or holding a file where a
directory should be turned one broken chat into an **unhandled exception and a dead
process**: every tmux session's reader, every other chat, every Round. The `close`
handler called `settleRun` unguarded for the same reason. Both are guarded now.

### Fixed — a run that cannot write its ending still gives back its slot

`settleRun` does several disk writes before `run_.finish()`, and a throw abandoned
the entry in `activeRuns` forever. That set IS the local run pool
(`MAX_CONCURRENT_RUNS = 3`), so each leak permanently cost a slot; after three,
every locally-hosted chat and every `local` Round got 429 "too many concurrent
runs" with nothing running, until somebody restarted the daemon. The finish is in a
`finally` now.

### Fixed — reachable is not the same as free

`remoteRuns` is in-memory, so `deploy.sh` wipes it — while the far machine is still
running its claude and is single-job: it will not ask for work again until that
child exits, minutes or hours later. The daemon reported that device
`online:true, running:false, queued:0`, accepted the next job with a 202, and the
job sat undelivered until it was declared "no word for 5 minutes". A heartbeat
proves reachable; only asking for work proves free. `awaitingPoll` now says which
question can actually be answered, and `huginn devices` shows it.

### Fixed — run transcripts no longer outlive the history that points at them

`finishRoundRun` promised the conversation "stays readable forever", and after the
11th run the chat id was evicted from `runs[]` — while round chats are filtered out
of `/v1/chats` by design, so there was no other path to it. Not openable, not
listable, not deletable: a daily Round left ~355 orphan transcript directories a
year, invisible and impossible to count against. Evicted transcripts are pruned,
and the promise is narrowed to what is true.

### Changed
- The device events route accepts a megabyte, so an older runner's oversized batch
  is not rejected whole. The runner itself now stays well under it — see cli 0.10.3.
- A queued message that IS dropped now says so in the transcript, quoting what was
  dropped. ⚠ Probed rather than assumed: a send during a Round run is already
  refused with an actionable 409, so this path is depth rather than a live fix.

614 tests, floor raised to 610.

## 2.71.0 — 2026-08-25

### Added — a report can be marked as read

`POST /v1/rounds/:id/ack`, body `{"acknowledged": true|false}`.

A report that says `action` is true the moment it is written and stays true
forever, because nothing could ever say otherwise. So a row held a red mark about
findings that had already been read, worked through, and in some cases fixed — and
the only thing that would ever clear it was the next run, which for still-open
findings simply said `action` again. **A signal that cannot be answered stops being
a signal**, and a row that goes on saying "Needs you" about something handled
teaches its reader to stop believing the words.

⚠ **The mark lives on the RUN, not on the Round**, and that is the whole design.
`lastRun` is replaced wholesale when a Round fires again, so next week's report
arrives unanswered for free. Held on the Round it would need code to remember to
clear it, and that code would eventually not run — leaving a Round permanently
quiet about findings nobody had seen. The history copy is updated too, matched by
chat and timestamp, so one run cannot carry two answers about whether it was read.

The report itself is never edited: this records that somebody saw it. A clean `ok`
run is not offered the control at all — there is nothing to acknowledge on an
all-clear — but an `ok` that admits it missed its goal is, because promotion to
`attention` is exactly the case most worth answering.

610 tests, floor raised to 605.

## 2.70.1 — 2026-08-25

### Fixed — the item count fix from 2.69.0 shipped doing nothing

`parseReport` was taught to record how many items a run really reported, and the
run RECORD dropped the field on its way to the reader. So both halves were right,
both were unit-tested, and the wire between them was not: every reader fell back
to the capped length and a round that found 500 things still showed "20 items"
under a headline saying 500 — exactly the behaviour 2.69.0 claimed to fix.

Caught by a live run, not by the suite. There is now an end-to-end test: a stub
reports 30 findings, and the assertion is that `lastRun.itemsTotal` is 30 while
`lastRun.items` is 20.

Which is the same failure the whole breaker pass is about, turned on its author:
being sure a thing was decided is not the same as being sure it was DONE.

### Fixed — a leaked test daemon no longer reads as twelve code failures

The port formula gives few slots, and a daemon leaked by an earlier run (a test
process killed before `after()` could fire) sits on one, answers `/v1/ping`
happily because ping needs no token, and rejects the new run's. That surfaced as
twelve tests failing with `401 unauthorized`, which reads like a bug in the
daemon and is not one. Every port-binding test file now asks an authenticated
question before trusting the port, and names the port to go and look at.

Two such daemons were in fact still running, left over from the adversarial
breaker pass.

606 tests.

## 2.70.0 — 2026-08-25

### Fixed — a report block a run READ is no longer accepted as its answer

A Round exists to go and read things: a log, a page, a mailbox, a repo. All of it
is text somebody else may have written, and a fenced `huginn-report` block sitting
in that content used to be indistinguishable from the run's own answer.

The interesting part is what the attacker's best outcome was. Not a lie — SILENCE.
`{"status":"ok","goalMet":true}` makes `shouldNotify` return false, so a round
that found something real would say nothing at all, and the row would show a clean
green week. Nobody goes looking for a report they were never told was missing.

Each run now gets a tag, minted when it fires and appearing only in its prompt:

```
THIS RUN'S TAG: a7f3c91b2d
```

The opening fence must carry it. Content written before the run cannot know it, so
a planted block is discarded no matter where it appears — ordering is not a
defence, because injected text chooses where it appears. A block without the tag
is reported as `unknown` with the reason said plainly, which NOTIFIES: the failure
direction is noise, never a forged clean week. That also covers the run simply
forgetting its own contract.

The tag rides in the work item, so a run on a Device is covered by the same fence
with no client change — and a device is the clearest case for why it exists, since
the machine holding the file system is the one reading the logs.

Runs recorded before this shipped have no tag, and an untagged block is still
accepted for them: refusing one with nothing to compare against would have turned
every round in flight at deploy time into a malformed report.

⚠ The tag is stated on a line of its own because the first wording buried it in
prose, and the test stub — matching `huginn-report ([A-Za-z0-9_-]+)` — captured the
word `block` out of "a fenced huginn-report block". A model reading the same prose
could have made the same mistake.

605 tests, floor raised to 600. Verified by disabling only the tag filter: four
tests fail, including the end-to-end one. A fifth passed either way — the planted
block happened to come first — and is kept, annotated, as the scenario rather than
as proof.

## 2.69.0 — 2026-08-25

### Fixed — the report channel

Six defects in the path between what a run says and what a person reads. They all
fail the same direction: every one made a run look BETTER than it was.

- **A fenced command inside an item no longer destroys the report.** The parser was
  a non-greedy regex, so it stopped at the first ``` anywhere — including one
  inside a JSON string. The contract asks every item for "the next step", and for
  an ops round the next step is a command, so writing the most useful possible
  item cut the block, failed the JSON parse, and threw away the status, the
  headline and every item; the operator was then buzzed with `unknown`, which
  reads like a broken run rather than the thing that needed them. Replaced with a
  line scanner: a closing fence is a line that is nothing but backticks, which is
  CommonMark's own rule and is exactly what separates a real terminator from a
  quoted one, because JSON cannot hold a literal newline inside a string.
- **The contract quoted back is no longer a valid report.** `REPORT_CONTRACT`
  contains a syntactically complete example, so a run that wrote its real report
  and then quoted the instructions to explain itself had the placeholder win under
  "the last block wins" — delivering template text as the answer AND, because the
  forged status was `ok`, silencing the notification entirely. A real `action`
  report vanished behind a clean green row.
- **A run cut off mid-block no longer puts JSON debris in the notification.** The
  fallback's fence stripper was the same non-greedy regex, so half a JSON object
  arrived as the notification text.
- **Escape sequences and newlines are stripped from single-line fields.** Report
  text is written by a model and a model writes what it read — a log line, a
  fetched page — and a terminal executes some of what it is handed. A round titled
  `Nightly scan` + ESC[2K + CR + `ALL CLEAR` erased its own line and reprinted, so
  `huginn rounds` showed ALL CLEAR for a round holding a DISK FULL headline and
  two action items. Titles, headlines and item titles are single-line by contract;
  `detail` and `suggest` keep their newlines and lose only the characters that
  move a cursor.
- **A capped report records how many items there really were.** 500 findings
  rendered as "20 items" directly under a headline saying 500 — two contradicting
  numbers on one screen, and the one an operator acts on was the wrong one.
- **A run's chat is dated in the Round's own zone.** The title used
  `toISOString()`, so an evening round in America/Los_Angeles — 7 hours into the
  next UTC day — was filed under tomorrow every time. The Sunday 19:00 round
  produced a chat titled Monday, the one date it never ran on.

Also in the host renderers: a round executing right now no longer prints "paused"
if it was disabled mid-run, and "in 1 days" is now "tomorrow".

597 tests, floor raised to 595.

## 2.68.0 — 2026-08-25

### Fixed
- **The two channels announcing a round no longer disagree.** Push led with "did not finish — "
  while the Telegram fallback indexed the *reported* status, so an `ok` report with `goalMet:false`
  — the one case this design calls out as most worth surfacing — arrived as a **green tick and a
  clean sentence**, on the channel used precisely when the app is not there to show the warning
  row. The display status and text are now one pure function, `reportDisplay`, so there is nowhere
  for a second opinion to live.
- **A round pinned to a machine that is gone can be edited again.** Both clients send `host` on
  every save, so an unenrolled device made EVERY edit fail — including changing only the title —
  with an error naming something the person did not touch, and their typing discarded. If it was
  the only device the editor hid the where-it-runs chips, so there was no way to move the round
  back. Permanently uneditable, while Pause/Resume kept working so the row looked alive. Keeping a
  host that no longer exists is now allowed; MOVING onto one that never existed, and widening an
  `act` round onto a look-only machine, are both still refused. The editor draws the section
  whenever a round is pinned elsewhere, showing the missing machine as "a machine that is gone".
- **A midnight daylight-saving gap no longer moves a round to the wrong day.** In America/Havana,
  America/Santiago and Atlantic/Azores the spring-forward begins at 00:00, and a wall time that
  does not exist resolved to 23:xx the PREVIOUS local day — a different date, weekday and
  day-of-month. A `Daily at 12:00 AM` round ran twice on one day and never on the transition day;
  `Sundays at 12:00 AM` fired on **Saturday** while the row still read Sundays; `Monthly on the
  8th` fired on the 7th. Silent on every surface, because the cadence is rendered from the
  schedule and the schedule was never wrong.
- **Switching a round to Interval no longer destroys its timezone.** An interval does not use one,
  but dropping it destroyed it — and the editor seeds itself from `schedule.tz`, so a
  `9:00 AM Asia/Tokyo` round toggled to Interval and back landed on the editing device's zone,
  eight hours out, with no way to recover the original.
- A device that has been removed is named as "a removed device" rather than by raw uuid when its
  run is lost — the resolver for exactly that case existed and was called four lines too late.

580 appd tests. Thirteen new ones fail against the previous build.

## 2.67.0 — 2026-08-25

The two findings the review flagged as needing re-adjudication rather than trust. Both were real,
and both were somewhat different from the summary — so both were reproduced first.

### Fixed
- **A mode nobody defined is now refused, not mapped to a scope.** `modeNeeds` is a plain object,
  so `modeNeeds.constructor` is a *function* and `indexOf` of it is `-1`. The daemon refused these
  by accident of that arithmetic; the headless runner's `rank(scope) >= -1` was true for **every**
  scope, so the one function whose whole job is to say no returned "no refusal" on a look-only
  machine. Nothing escalated — `argvFor` compares `mode === 'act'` literally, so the tools stayed
  read-only — but a fence that fails open is not a fence. Kotlin was immune to the prototype trick
  and wrong for a different reason: it mapped the unknown to `work`, which let anything
  unrecognised run on any machine enrolled at work or own. All four implementations now refuse
  outright, and **36 hostile rows are in the shared case matrix** so both runners are held to it.
- **A session id that is really a flag can no longer reach an argv.** `meta.claudeSessionId` was
  stored verbatim from `ev.session_id` — an event the DEVICE posts — and rode back to that machine
  as the value of `--resume`. `--resume` takes its value optionally, so a string beginning with
  `--` does not become the id, it becomes the **next flag**: `--resume
  --dangerously-skip-permissions` is two flags, not one. An unvalidated string in flag position is
  authority travelling inside a request, and a work item is defined as carrying a request and no
  authority. Now validated as a uuid **at the source and at the fence** — the device builds its own
  argv, so it does not get to assume the other end checked.

560 appd tests. Two new regression tests, both of which fail against the previous build.

## 2.66.0 — 2026-08-25

Four root causes from an adversarial review of Rounds and Devices — 139 edge cases probed by nine
parallel agents, every finding refuted by an independent skeptic before it counted. The pure layer
held: 18,000 DST-crossing fires across 25 zones, the 364-row scope/lock/mode grant matrix, and
every status/notify combination came back correct. These are the bookkeeping AROUND it, and they
all failed in the same direction — **the surfaces showed a clean week while the work had not
happened, had happened twice, or had happened after the owner pressed stop.**

### Fixed
- **A scheduled fire that is refused is now a recorded run.** It used to be one log line. Eight
  ordinary triggers reach that path — the device was unenrolled, asleep, locked, narrowed its
  scope, was already running something (two Rounds on one machine at 03:00: **the second was
  dropped every night**), the local pool was full, or the slot was missed with catchUp off. In
  every one, `runs` stayed 0, `lastRun` stayed null and nothing was sent — *even with notifyWhen
  "always"*, because a failure that never becomes a run can never be notified about. The row read
  "Daily at 3:00 AM · in 51m" for a job that had not run since the laptop went to sleep. Recorded
  as `attention`, not `action`: nothing is wrong with the world, something is wrong with the
  arrangement.
- **Cancelling a remote job now takes the work back.** `cancelRun` killed a process a remote run
  does not have and left the item in the device's queue, so the machine was handed it on its next
  poll — up to 25s away, or hours for a sleeping laptop — and ran the owner's `act` prompt for
  real with full grants, while the chat said it had been cancelled. A run withdrawn before handover
  now settles immediately instead of sitting at "stopping" until the silence timer fires.
- **A work item can no longer outlive its run.** `loseRemoteRun` deleted the run and left the queue
  entry at the front, so a laptop waking hours later executed a dead job whose every result was
  rejected 404 — work done, billed, and thrown away. The `/work` handout now drops any item whose
  run has ended, and `deviceQueues` is a view of `remoteRuns` rather than a life of its own.
- **A poll that hung up no longer swallows the job.** The waiter was flagged answered before its
  close handler unparked it; in that window `queueWork` handed the dead waiter an item and dropped
  it. The job was created, accepted, and never ran. `respond` now reports whether it delivered, and
  an undelivered item goes back on the queue.
- **The Round's verdict is read from the facts, not inferred from prose.** Four failures, one
  contract: only the LAST assistant message was parsed, so an ordinary agentic turn (report, one
  more tool call, "Confirmed.") filed the word "Confirmed." as the week's finding; one streamed
  token before a crash turned "claude exited 1" into a cheerful progress line; `is_error` — the
  flag `lib/rounds` own header says this design exists for — was never read; and a delivered report
  is now kept even when the run was cancelled, because on a device that is the NORMAL outcome and
  it used to file "did not finish" over the top of "7 of 7 backups verified".
- **The seal survives a message typed mid-run.** `settleRun` held a snapshot from before
  `finishRoundRun` wrote it, so any queued message erased `sealed`, the verdict and `endedAt` — the
  chat reopened and the owner's next question was filed as the Round's official report. Verbatim
  the failure `reconcileInterruptedRuns` comment says was already fixed.
- **`PATCH /v1/rounds/:id` re-reads after the body arrives.** It loaded the Round before the await,
  and a phone sends the whole prompt on save, so the window was every edit. A run finishing inside
  it had its record erased after its push had gone out; a run STARTING inside it had
  `currentChatId` reset to null, defeating the "previous run is still going" guard and putting two
  live `claude` processes on the same act work.
- **A restart no longer loses the Round's run.** `reconcileInterruptedRuns` marked the chat
  interrupted but had no `roundId` branch, so `runs` stayed 0, `currentChatId` dangled at a dead
  chat, and the chat was left UNSEALED — feeding the same "your question becomes the report"
  cascade.
- **Deleting a Round stops the work it is doing right now.** The run lost every surface at once —
  absent from `/v1/rounds` and from `/v1/chats`, nothing in either client to press — while holding
  a pool slot until the 2-hour hard cap. For an `act` Round, "delete the schedule" has to stop it.

Seven regression tests, six of which fail against the previous build.

## 2.65.0 — 2026-08-24

### Changed
- **A schedule with no zone now means THIS HOST's zone.** `validateSchedule` takes a default and
  `buildRound` passes `Intl`'s resolved zone; a client that knows its own still sends it and
  still wins. The shared Round editor is multiplatform and has no calendar to ask, so without
  this it could not have produced a valid schedule at all. Read from `Intl` rather than `$TZ`
  because that is the same source the wall-clock arithmetic resolves through — the zone a Round
  is stored with and the zone it fires by cannot then disagree.
- **A zone is still required, just easy to supply.** Blank, whitespace and absent all fall back;
  a nonsense zone is still refused, and no zone anywhere is still refused. A schedule with no
  zone cannot be fired at a time, and storing one would be storing a bug.
- Editing a Round's schedule falls back to **that Round's own zone** before the host's, so
  changing the time on a Round written in another zone does not silently move it.

## 2.64.0 — 2026-08-24

### Changed
- **The scope lattice is no longer written down here.** `lib/devices.js` reads it from a
  generated table cut from `shared/device-policy.json`, the same file the two device runners
  read. This daemon never sends tool grants and never will — but it does hold the same ordering
  the runners hold, to decide whether to *offer* work at all, and a lattice that disagreed by one
  position would have it offering work every device refuses (looks broken) or withholding work a
  device would have taken (looks dead). Both failures are silent.
- `server/appd/test/device-policy-cases.test.js` asserts the generated files still match the
  policy, drives the headless runner over the shared case matrix, and checks this daemon's
  pre-check agrees with it about every refusal. It binds no ports, so it has no range in the
  allocation table.

## 2.63.0 — 2026-08-24

### Fixed
- **A chat that ran on another machine came back EMPTY.** The reader renders Claude's own
  transcript file, located by session id under *this* host's `~/.claude/projects` — but a run on
  a device wrote that file on the device. The lookup found nothing, so the conversation showed
  no answer and no user message either, while the chat list row still showed the text (that
  comes from meta). A working feature that looked like it had swallowed the message.
  A remote chat now reads from `messages.jsonl`, which already held every event the device
  streamed back, in the same shape and honouring the same paging contract — the reader hands
  `offset` back and APPENDS what returns, so returning everything each poll would have doubled
  the conversation on screen. It renders before the device even picks the job up, so the
  message you just sent is visible while it is still queued.
- A device's failure is now readable in the conversation rather than only in the list. Errors
  are recorded as `error`, and the readers know six kinds — `error` is not one — so it was
  rendering as nothing at all.
- A remote chat no longer hunts this host for a title by session id: that file is on the other
  machine, so the lookup can only find nothing, or once find something that is not it.

## 2.62.0 — 2026-08-24

### Fixed
- **A sealed run no longer offers suggestions.** A suggestion chip FILLS THE COMPOSER, and a
  finished round has none — so the chips were controls that could not do the thing they
  offered. Found by driving the real phone: "This round has finished. It is kept here for
  review." was rendering directly above two perfectly tappable suggestions. `/suggestions` now
  answers `{ suggestions: [], reason: "sealed" }`, which fixes every client at once and saves
  generating them.

## 2.61.0 — 2026-08-24

### Added
- **A Round can state its goal, and is asked whether it reached it.** The goal goes at the
  front of the prompt as a completion test — a scheduled run has nobody to ask "is this
  enough?", so the only thing that can tell it when to stop is a sentence written in advance.
  The report answers with `goalMet`, which is tri-state on purpose: true, false, and *did not
  say*. A Round with no goal has nothing to answer, and coercing that to false would report
  every one of them as having failed.
- **An unmet goal is promoted, never hidden.** A run that says `ok` while admitting it did not
  finish has not had a clean week — it has quietly not done the job, which is the failure most
  worth surfacing precisely because nothing else about it looks wrong. So `ok` + `goalMet:false`
  reports as `attention`, the notification says "did not finish", and `reportedStatus` keeps
  what the run actually claimed. Promotion only ever raises: `action` is never softened.
- **A finished run is SEALED.** One turn against a stated goal and then it is over: the chat is
  kept and readable forever, and refuses new messages with 409. Both clients replace the
  composer with a note rather than disabling it beside one — offering an input that cannot
  deliver is the kind of small dishonesty that makes a working feature feel broken. A queue
  waiting on a sealed run is dropped rather than drained, since draining it would reopen the
  thing that just ended.
- **A chat says which machine it runs on.** `host` and a daemon-resolved `hostName` on both the
  list row and the detail, so a remote chat is obvious at a glance. Resolved server-side because
  a client looking it up itself would print a bare uuid for a device since unenrolled.

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
