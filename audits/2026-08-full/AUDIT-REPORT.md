# Huginn ecosystem — full audit, 2026-08-04

Scope: everything the owner ships or depends on — the `huginn-appd` daemon, the
Android app, the Compose Desktop app, the deprecated Electron client, the CLI and
server glue, packaging, docs, tests, and the deployed state on huginn itself.

Method: 20 parallel finder lanes, adversarial verification of every HIGH and MED,
and live reproduction wherever a claim could be executed rather than argued.
Read the companion files beside this one:

| file | what it is |
|---|---|
| `findings-lead.md` | the lead auditor's own findings, each with a live reproduction |
| `findings-lanes.md` | the full lane output, by component |
| `OWNER-WINDOWS-TESTS.md` | **for the owner** — ten minutes on PRESTIGE, answers what this box cannot |
| `../../server/appd/test-integration/` | regression tests written for confirmed findings |

---

## The short version

Nothing is on fire. The system is in better shape than its size suggests: the
HTTP surface is properly gated, the zero-dependency claim is real, no credential
leaks into logs, and the architectural contracts that were bought with past
incidents are mostly being honoured.

**17 HIGH findings, every one verified** — 14 reproduced or executed by hand, 3
confirmed unanimously by three independent refuters each. Six deserve attention
before anything else:

1. **Plan-approval prompts are invisible on both clients right now** (L11). The
   2026-08-03 fix cured the footer-phrase staleness but not this. Ask Claude for
   a plan, get the ordinary numbered steps, and the approval card never appears —
   phone, desktop, or notification. Live-reproduced, with a three-way control
   isolating the cause to one line.
2. **Windows desktop notifications almost certainly do not work at all** (L10),
   **and the desktop is suppressing the Telegram fallback while they don't**
   (L24 — measured: 7 alerts held in six hours, a `ktor-client` claiming the
   route). The installer never stamps the AUMID the app's own source says is
   mandatory. Owner test 1 settles it in a minute; turning desktop notifications
   off restores Telegram immediately.
3. **The host does not enforce the `/answer` fingerprint it documents** (L1). A
   missing *or empty* fingerprint skips the check, and a lock-screen tap can then
   answer a question the owner never saw. Demonstrated live. The Electron client
   rejects both cases correctly — the lesson was learned in this feature and
   never reached the host.
4. **`USAGE.md` tells the owner `huginn -p` has "no tools"; it reads any file on
   the host** (L29). Proven by running it. `SECURITY.md` and `ARCHITECTURE.md`
   separately describe a system with no daemon at all (L16), and
   `mobile/README.md` names the tailnet as the network boundary when the daemon
   binds `0.0.0.0` (L30). The three documents a person reads before deciding what
   is safe to run all understate the surface.
5. **A message sent while an attachment uploads is lost if you navigate away**
   (L33) — draft cleared synchronously, send parked in a view-scoped coroutine
   that dies with the composition. No error, nothing to retry from.
6. **The whole route layer of appd has no tests** (L9), and `deploy.sh` ships it
   to production behind a syntax check alone (L32). 385 passing tests cover the
   extracted libraries; the 3,202-line file holding every route, the auth gate
   and the validators has none. That is the direct reason findings 1 and 3
   survived.

Everything else is ordinary maintenance debt, ranked at the end.

---

## What was swept, and what was not

| component | lines | swept | how deeply |
|---|---|---|---|
| `server/appd` (daemon + 19 libs) | ~10.8k | yes | deepest — 8 lanes; the least-audited, highest-privilege component |
| `mobile/core` | ~6.4k | yes | client contract, reattach, SSE, protocol drift |
| `mobile/ui` | ~2.0k | yes | shared composables, usability, owner taste |
| `mobile/app` (Android) | ~10.8k | yes | lifecycle, notifications, regression check of the 2026-07-28 audit |
| `mobile/app-desktop` (Compose) | ~15.0k | yes | updater, lease, notify claim, terminal grid |
| `desktop/` (Electron, deprecated) | ~36k (mostly build output) | security only | by design — new feature findings out of scope |
| `client/` CLI + `server/bin` + `provision/` | ~0.6k | yes | first audit ever of these |
| `docs/` | 7 files | yes | drift against code |
| deployed state | — | yes | `/opt/huginn-appd`, `/var/lib/huginn-appd`, `/etc/huginn-appd`, systemd |

**Not covered, and why:**

- **Real Windows behaviour.** There is no Windows machine in this loop. Toast
  delivery, GPU rendering, AUMID and scheme survival across install, and the
  self-update round trip are all handed to the owner as
  `OWNER-WINDOWS-TESTS.md` rather than claimed here. L10 is the one place this
  audit reasons about Windows, and it is careful to separate what is statically
  certain (no AUMID is stamped anywhere) from what is documented behaviour
  (Windows then drops the toast).
- **On-device Android behaviour.** Wireless ADB to the owner's phone exists, but
  it is a shared personal device; nothing was installed and no UI was driven.
  Android findings are static, or inherited from the 2026-07-28 run's on-device
  work.
- **The Electron client's features.** Deprecated; only its security posture and
  the two previously-fixed HIGHs were checked.

---

## Baseline: the gates, run unpiped, with real counts

Contract C11 says never trust `BUILD SUCCESSFUL` — an up-to-date Gradle task runs
zero tests. Counts below are from `build/test-results/**/*.xml` and the TAP
output, with `--rerun-tasks`.

| suite | tests | skipped | failures |
|---|---|---|---|
| `server/appd` (`node --test "test/*.test.js"`) | 385 | 0 | 0 |
| `:core` | 466 | 0 | 0 |
| `:ui` | 7 | 0 | 0 |
| `:app` | 49 | 0 | 0 |
| `:app-desktop` | 188 | 0 | 0 |
| **total** | **1,095** | **0** | **0** |

All green. Two things the totals hide, both filed as findings:

- `:ui` has **7 tests for 2,022 lines** — the module both apps render from.
- `huginn-appd.js` has **0 tests for 3,202 lines** (L9).

---

## Re-verification: the 29 findings the 2026-07-28 audit never verified

That run hit a usage limit with findings outstanding. The memory recorded "~35
candidates never verified"; the exact number, recovered by diffing finding titles
against verdict titles in the run's own journal, is **29** (108 findings, 96
verdicts, 12 duplicate titles collapsing to 29 distinct unjudged).

Each was re-judged against *current* code — paths moved in the KMP migration, so
"the old file is gone" was not accepted as "fixed".

**Result: 13 FIXED, 16 STILL OPEN** (2 HIGH, 5 MED, 8 LOW, 1 INFO).

| # | today | sev | finding | where it lives now |
|---|---|---|---|---|
| 1 | **STILL_OPEN** | HIGH | Voice mode speaks the previous turn's answer, one behind, in any chat with history | `...tlin/com/silencelen/huginn/ui/VoiceSheet.kt:113` |
| 2 | **STILL_OPEN** | HIGH | Pinch-zoom reads a stale fontScale captured by pointerInput(Unit) | `.../com/silencelen/huginn/ui/TerminalScreen.kt:128` |
| 3 | **STILL_OPEN** | MED | setAuthenticationRequired on chat Reply is a no-op below Android 12: lock-screen free tex... | `...encelen/huginn/notify/SessionWatchWorker.kt:364` |
| 4 | **STILL_OPEN** | MED | Voice sheet wedges in Thinking with no exit on duplicate answer text or a failed turn | `...otlin/com/silencelen/huginn/ui/VoiceMode.kt:72` |
| 5 | **STILL_OPEN** | MED | History load scrolls to a maxValue read before the inserted rows are measured, landing at... | `.../com/silencelen/huginn/ui/TerminalScreen.kt:155` |
| 6 | **STILL_OPEN** | MED | PromptCard checkbox baseline never reseeds from later frames, so Answer can revert toggle... | `.../com/silencelen/huginn/ui/TerminalScreen.kt:375` |
| 7 | **STILL_OPEN** | MED | Push bookkeeping in fire-and-forget coroutine; one lost write pins the 10-minute alarm fo... | `...len/huginn/notify/HuginnMessagingService.kt:114` |
| 8 | **STILL_OPEN** | LOW | Wrapped assistant prose beginning 'Running N agents' becomes a phantom durable status row | `server/appd/lib/pane.js:304` |
| 9 | **STILL_OPEN** | LOW | previewLines misses ⏸/⏹ mode footers, so manual-mode furniture headlines session previews | `server/appd/lib/pane.js:50` |
| 10 | **STILL_OPEN** | LOW | Saving the token/URL never arms background delivery; fresh installs without working FCM g... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:634` |
| 11 | **STILL_OPEN** | LOW | Replacing an in-flight attachment can be overwritten by the stale upload (owner check has... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:459` |
| 12 | **STILL_OPEN** | LOW | Renaming a session mid-attention/mid-run mis-keys alerts: spurious 'answered', duplicate ... | `server/appd/lib/alerts.js:163` |
| 13 | **STILL_OPEN** | LOW | Heartbeat's WatchService revive is blocked by API 31+ FGS background-start rules and fail... | `...n/com/silencelen/huginn/notify/Heartbeat.kt:219` |
| 14 | **STILL_OPEN** | LOW | Framework theme hardcodes dark while Compose follows the system: dark flash and dark fram... | `mobile/app/src/main/res/values/themes.xml:3` |
| 15 | **STILL_OPEN** | LOW | Dictation dialog closes silently after the second consecutive silence timeout | `...otlin/com/silencelen/huginn/ui/Dictation.kt:99` |
| 16 | **STILL_OPEN** | INFO | allowBackup=false silently wipes token, drafts, and watch baseline on reinstall - undocum... | `mobile/app/src/main/AndroidManifest.xml:49` |
| 17 | **FIXED** | NONE | Live-typing drainer delivers queued keystrokes to the wrong session | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1237` |
| 18 | **FIXED** | NONE | Reopening a running chat doubles the streamed text (partialText seed + since=0 replay) | `...com/silencelen/huginn/ui/HuginnViewModel.kt:72` |
| 19 | **FIXED** | NONE | Transient transcript-fetch failure renders a populated chat as a brand-new empty chat, wi... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1488` |
| 20 | **FIXED** | NONE | Drafts for deleted chats and killed/renamed sessions persist forever | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1528` |
| 21 | **FIXED** | NONE | INVALID_ARGUMENT classified as dead token — a payload error silently unregisters a live p... | `server/appd/lib/fcm.js:35` |
| 22 | **FIXED** | NONE | detectPrompt: '>' anywhere in an option line marks it selected, killing live prompts | `server/appd/lib/pane.js:184` |
| 23 | **FIXED** | NONE | Glyph classes omit U+2727 ✧ — the exact glyph the comment records as the live workflow-wa... | `server/appd/lib/pane.js:293` |
| 24 | **FIXED** | NONE | Drafts for deleted chats and killed/renamed sessions persist forever | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1534` |
| 25 | **FIXED** | NONE | PromptCard single answers bypass the guarded /answer endpoint (blind digit, no fingerprin... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1411` |
| 26 | **FIXED** | NONE | Chat SSE failure leaves a phantom active tool and a chat view that never updates again | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1626` |
| 27 | **FIXED** | NONE | Live-typing drainer captures the first session's name; keystrokes queued after a session ... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:1348` |
| 28 | **FIXED** | NONE | Send is enabled while the attachment is still Uploading; the photo silently rides the nex... | `...com/silencelen/huginn/ui/HuginnViewModel.kt:389` |
| 29 | **FIXED** | NONE | wantAfterGrant survives a denial and auto-launches dictation when the mic is granted else... | `...otlin/com/silencelen/huginn/ui/Dictation.kt:115` |

The two HIGHs are both in shared code, so they now affect the desktop as well as
the phone — the KMP migration silently widened their blast radius:

- **Voice mode speaks the previous turn's answer, one behind**, in any chat with
  history (`VoiceSheet.kt`). Voice mode is unusable for its purpose in an
  existing conversation.
- **Pinch-zoom reads a stale `fontScale`** captured by `pointerInput(Unit)`
  (`TerminalScreen.kt`), so terminal zoom snaps back to the size it had when the
  view was composed.

---

## Findings

**194 findings survived verification**; 1 was refuted and is recorded at the
bottom of `findings-lanes.md` rather than deleted, so the same wrong idea is not
rediscovered next time. Full detail — evidence, failure scenario, suggested fix,
refuter verdict — is in `findings-lanes.md` (lane output) and `findings-lead.md`
(the lead auditor's own, each with a live reproduction).

| severity | count |
|---|---|
| HIGH | 17 |
| MED | 65 |
| LOW | 84 |
| INFO | 28 |
| **surviving total** | **194** |
| refuted and dropped | 1 |


Two caveats on these numbers, so they are not read as more than they are:

- **LOW and INFO were not individually refuted.** Adversarial verification was
  spent on HIGH (three independent refuters each) and MED (one each). The 84 LOW
  and 28 INFO entries are lane output, held to the same evidence standard by the
  lane brief but not separately challenged.
- **Severity is post-verification.** Where a refuter revised a rating, the revised
  one is used and the change is shown in `findings-lanes.md`.

### Everything rated HIGH or above, after verification


Every HIGH was verified: 14 by the lead auditor by hand (live reproduction or
direct execution — see the `L` reference for each in `findings-lead.md`), and
3 by three independent refuters each, all unanimous.

### Lane coverage — what each lane says it did and did not reach

| lane | findings | self-reported gaps |
|---|---|---|
| :app (Android shell) — correctness, lifecycl | 11 | ntrols.kt, ChatScreen.kt past line 120, all of :core/:ui internals (other lane), google-services/FCM config, and any on-device/ADB verification (static analysis only, per lane instructions). |
| :app-desktop (Compose Desktop shell) — corre | 10 | not read except PresenceTest. |
| :core — HuginnClient.kt, HTTP/SSE, reattach, | 6 | ier/Heartbeat/FCM behaviour (:app lane); appd beyond the chat-run/watch/SSE/long-poll seams (its own lane); no Android-instrumented or on-device run (no device); Electron desktop/ untouched. |
| :ui shared composables — usability, state la | 11 | Strip/WorkSheet/AgentRow/AutoScrollToNewest etc.) were NOT re-reported per brief; note the phone copies also lack the desktop's 3-minute work-strip linger, which will resolve when #20 lands. |
| CLI client + server glue + provisioning (nev | 13 | so every huginn.ps1 / install.ps1 finding is read-only reasoning, and I deliberately did not report the PS 5.1-vs-7 `ssh-keygen -N '""'` marshalling question because I cannot demonstrate it. |
| Electron desktop client (/opt/huginn/desktop | 5 | not audited as source): out/main/index. |
| KMP layering contract and shell-level duplic | 8 | NOT covered in this lane: behavioral correctness of any of this code (only parity/placement), :app-desktop's shell-only subsystems (updater, notify/, tray/, attach/, Presence/Landing/Faults — accepted |
| alerting / watch / FCM push / push tokens /  | 10 | not reach a shell or an argument slot; a claiming client that dies does NOT permanently suppress Telegram (freshness windows expire: 180s stream / 1320s beat); push installId == watch X-Huginn-Client  |
| appd async ordering, state persistence, and  | 7 |  (line 278) and idByPrint (1133) grow unboundedly but at ~100 bytes per Claude session id (negligible); deliverOrphanedQueues logs 'delivering' before the refusal check (cosmetic log-order). |
| appd route authorization and HTTP surface | 11 | (C6); notify-claim discipline (C7); chat-run concurrency/queue correctness; any Kotlin/:core/:ui/:app/:app-desktop code; the Electron client; CLI/provision/packaging; deploy/release scripts. |
| appd secrets hygiene, credential handling, a | 7 | rming resolveArtifact's traversal defence is sound; the mobile/, desktop/, app-desktop/ trees; the appd test suite (I listed the 18 test files but ran none — the lead's gate lane owns that). |
| command and tmux injection in appd | 10 | not audit the detector's own correctness. |
| documentation drift and deployed-state drift | 17 | of the apps, and the netplan repo's own docs. I created no tmux sessions, no scratch daemons and no Xvfb; all API probes were GETs without client-id headers, so clients.json was not touched. |
| feature parity, phone vs desktop | 9 | NOT covered in this lane: pixel/style parity (colors, spacing beyond what code comments state), phone notify/* internals (WatchService, Heartbeat, FCM paths) and desktop notify/*, update/*, tray/*, di |
| lib/accounts.js + lib/autoswitch.js + lib/us | 9 | store (forbidden — the analysis of those paths is code-read only, notably finding 3's clobber-back race is reasoned, not reproduced); superseded/ archive files not opened (names/sizes only). |
| lib/pane.js — prompt detector and all pane-r | 6 | /stripAnsi beyond unit tests; the scrollback capture path (huginn-appd.js:515); Kotlin-side prompt-card consumers; rest of huginn-appd.js. Evidence captures preserved in scratchpad/panelab/. |
| lib/transcript.js — transcript reading, pagi | 9 |  sidechain check, Electron desktop client, any live-daemon HTTP probing (all demos ran in-process against the module; no daemon, tmux, or repo state touched; scratch /tmp/tr-* dirs removed). |
| lib/uploads.js + lib/desktop.js — upload spo | 7 | NOT audit authentication itself beyond confirming every route in my lane sits behind the single `authorized(req)` gate at line 2033. |
| packaging and release — mobile/scripts/{buil | 10 | not audited as code): /opt/huginn/mobile/app/build. |
| protocol contract drift — what clients SEND  | 7 | h); notification rendering beyond payload keys; voice/dictation and share-target flows; test suites not run (no build gates needed for this lane); no interaction with any owner tmux session. |
| test coverage map — find the next TermKeys | 12 | not audit correctness of appd routes, accounts/OAuth, transcript parsing, alerts, FCM, or any Compose rendering — only whether tests exist and whether existing tests assert properties. |


---

## Parity matrix — phone vs desktop

Built by enumerating both shells (`mobile/app` = Android, `mobile/app-desktop` =
Compose Desktop) rather than from the docs. Verdicts:

- **PARITY** — present on both, and by design the same pixels (it lives in `:ui`).
- **PLATFORM** — one-sided and correctly so; moving it would be wrong.
- **GAP** — one-sided with no platform reason. These are the ones that matter.

| capability | phone | desktop | verdict |
|---|---|---|---|
| Chats (list, view, send, stream) | yes | yes | PARITY |
| Sessions (list, transcript, screen) | yes | yes | PARITY |
| Terminal / live typing | yes | yes | PARITY |
| Prompt answering (question cards) | yes | yes | PARITY — one server detector feeds both (C2) |
| Attachments / uploads | yes | yes | PARITY |
| Account switching + sign-in flow | yes | yes | PARITY — both drive the host's login session |
| Settings | `SettingsScreen` | `SettingsView` | PARITY |
| Status | `StatusScreen` | `StatusView` | PARITY |
| Self-update | devstore | `/v1/desktop-kt` | PLATFORM — different distribution channels |
| Notifications | FCM + shade | tray/toast + notify claim | PLATFORM — same purpose, native mechanisms |
| Background delivery | `WatchService`, `Heartbeat`, `BootReceiver` | always-on process | PLATFORM — Doze has no desktop analogue |
| Share-target sheet | yes | — | PLATFORM — an Android intent surface |
| Camera capture | yes | — | PLATFORM |
| Biometric app lock | `AppLock` | — | PLATFORM-ish — the OS login is the desktop's lock; a keyring is the analogue (already deferred work) |
| Fold / two-pane handling | yes | — | PLATFORM |
| Command palette (Ctrl+K) | — | `CommandPalette` | PLATFORM — needs a keyboard |
| Keyboard shortcut model | — | `Shortcuts`, `TermKeys` | PLATFORM |
| Right-click context menus | — | `common/Menus.kt` | PLATFORM |
| System tray | — | yes | PLATFORM |
| **Voice mode** (hands-free loop) | `VoiceSheet`, `VoiceLoop` | — | **GAP** |
| **Dictation** (speech → composer) | `Dictation`, `SpeechEngines` | — | **GAP** |
| **Diagnostics bundle** | — | `diag/Diagnostics.kt` | **GAP** |
| Tooltips | — | — | neither (owner's known-rough list) |

### The three real gaps

**Diagnostics is the cheap one and the one that costs support time.** The desktop
can produce a full diagnostics dump (version, connection, token present, watch
stream state, notifier, claim status); the phone cannot. When the owner reports
"the phone stopped notifying", there is no equivalent single artifact to ask for
— and the phone is the surface with the most ways to go quiet (Doze, force-stop,
battery manager, FCM registration). The data all exists in `:core` already; this
is a `:ui` screen reading state the phone client holds, not new plumbing.

**Voice and dictation are a genuine question, not an oversight.** Both are built
on Android's `SpeechRecognizer`/`TextToSpeech` services, which have no JVM
equivalent — so the *engines* are legitimately platform code. But `VoiceLoop` is
already a pure reducer, and `Speakable.render` is pure text shaping; both would
sit in `:core` untouched. What is missing on desktop is an engine implementation,
which on Windows means SAPI/WinRT speech through the same PowerShell bridge the
toast notifier already uses. That is real work, and worth doing only if the owner
wants voice at the desk — a question for him, not a defect to file.

Nothing on the desktop is missing from the phone except by platform necessity,
which is the direction that matters given the phone is the older, more-used
client. The shared `:core`+`:ui` split is doing its job: every capability in the
PARITY rows is one implementation, not two.


---

## Fixes-held check

Both prior audits' fixes were checked against current code rather than assumed.

### The Electron 0.2.0 audit (2026-07-30) — both HIGHs still fixed

| fix | status | evidence |
|---|---|---|
| forgeable `huginn://answer` without a fingerprint | **HELD** | `desktop/src/main/notify/activation.ts:44` — `if (fingerprint === null \|\| fingerprint === '') return null` |
| baseUrl → token + update-feed RCE chain | **HELD** | `desktop/src/main/updater.ts:23` — `const FEED_URL = 'http://…/v1/desktop'`, hard-coded, not read from settings (contract C4) |
| `setAppUserModelId` missing (Windows dropped all toasts) | **HELD** | `desktop/src/main/index.ts:31` |
| `window.prompt()` throws in Electron | **HELD** | no `window.prompt(` in `desktop/src` |

The activation fix is worth reading twice, because it rejects **both** a missing
fingerprint and an **empty string** — the same JavaScript truthiness trap the
daemon still has open (L1). The project learned this lesson in this feature; the
host never got it. See L19.

### The mobile audit (2026-07-28) — applied fixes held; 16 of 29 unverified ones are still open

The rounds 1–8 fixes were spot-checked and hold (`fcm.js` no longer treats
`INVALID_ARGUMENT` as a dead token and has a regression test; `detectPrompt`'s
caret is anchored to line start; the ✧ glyph is in both status regexes with the
live-capture fixture). Full results in the re-verification table above.

### The 2026-08-01→04 fix wave — all five checked held

See L6. Notably `detectPrompt`'s footer-staleness fix was verified against a
**live** permission prompt whose footer (`Esc to cancel · Tab to amend`) appears
in none of the old hard-coded phrase list — so the absence-of-chrome rule is
structurally right, not merely patched for the reported case. It does not,
however, cover plan-approval dialogs (L11).

### One correction to the record

The audit brief and the contract summary both state there are "only 4
expect/actual (HTTP engine, IO dispatcher, glyph blit ×2)". There are **2**, both
in `core/.../data/Platform.kt`. The glyph blit is a source-set split
(`CellPainter.android.kt` / `CellPainter.jvm.kt`), not expect/actual — a better
mechanism. Since "how many expect/actual exist" is the number the project uses to
police contract C1, the docs should say 2.


---

## Ranked next actions

Ranked by (does it silently break something the owner relies on) × (how cheap is the fix).

### 1. Make plan approvals visible again — L11

One line in `lib/pane.js`, and it restores a daily interaction on both clients at
once. The regression fixture and its two controls are already written. This is
the highest value-per-character change in the whole report: a numbered plan is
the *default* shape of a Claude plan, and right now every one of them costs the
owner a trip to the raw Screen tab. `appd 2.52.3` and nothing else needs to move
— no client release, because the detector is server-side.

### 2. Find out whether Windows notifications work at all — L10

Ten minutes of the owner's time (`OWNER-WINDOWS-TESTS.md`, test 1) decides
whether the desktop's entire always-on layer is functional. Do this *before*
building the AUMID fix, because the fix has two shapes and the test says which
matters. If it fails, the interim mitigation is one toggle: turn desktop
notifications off, which un-suppresses the Telegram fallback immediately.

### 3. Enforce the `/answer` fingerprint on the host — L1

Four lines, and it closes the gap between what the route's own comment promises
and what it does. The regression test is written and skipped, waiting. Pair it
with the phone change (do not draw answer buttons without a fingerprint) so the
failure surfaces as a missing button rather than a 400.

### 4. Read the name back after a rename — L8

Small, and it fixes two things at once: an API that returns a name which 404s,
and an orphaned session→transcript mapping that silently blinds the Conversation
view. Reading the name back from tmux is better than rejecting `.`, because it is
correct for whatever character tmux decides to rewrite next.

### 5. Give appd a route-level test suite — L9

The harness exists now (`server/appd/test-integration/`) and cost about thirty
lines, because the daemon was already built to run isolated. Two of the four
findings above live in the untested file; 385 green tests are why nobody looked.
Move the directory under `test/` once the skipped assertions pass, so the gate
covers routes from then on.

### 5b. Ship 0.3.2 — currently prepared and unreleased

`version.txt` says 0.3.2 with a written changelog; `/v1/desktop-kt` still serves
0.3.1. The fixes described in that changelog (the updater's four-hour sulk, the
landing-position flush) are in the code and not on the owner's machine. Either
release it or revert the bump — an uncommitted version bump is a trap for whoever
runs the release script next, since it will publish whatever else is in the tree
alongside it.

### 6. Correct the three documents that understate the surface — L29, L16, L30

`USAGE.md` says `huginn -p` has "no tools" (it reads any file — proven by
running it), `SECURITY.md` and `ARCHITECTURE.md` describe a system with no
daemon, and `mobile/README.md` names the tailnet as the network boundary when
the daemon binds `0.0.0.0` and the bearer token is the only gate. Each is a
one-paragraph edit and each is a claim someone would act on.

### 7. Reconcile the three-way `server/bin` split — L28

`cc`'s newest copy is in netplan, `huginn-claude-title`'s is in the huginn repo,
and only `/usr/local/bin` has both current. Running the documented `setup.sh`
today silently reverts three live fixes to `cc`. Fix the repo copies, then make
`setup.sh` refuse to overwrite a newer installed file.

### 8. Stop losing messages sent during an upload — L33

Route the submit through `ChatController`'s scope instead of the composable's,
or clear the draft only after the send returns.

### 9. Harden the systemd unit — L2

A drop-in, verified working on a scratch instance, with the one directive that
breaks it (`PrivateTmp`) already identified. Defence in depth under the component
that holds every credential.

### 10. Close the standing items

- **Task #20 duplicate deletion.** Confirmed still open, and confirmed
  *equivalent* — the copies have not diverged, so this is safe mechanical work
  rather than a behaviour change. Do it the next time the phone is touched, one
  commit per symbol.
- **Electron cutover.** The >1-day-of-real-use gate has long passed; the
  procedure is written in `docs/DESKTOP-MIGRATION.md`. Worth sequencing *after*
  the AUMID question is settled, since cutover moves the install path and would
  otherwise change two unknowns at once.
- **Compose desktop keyring.** Assessed, not rediscovered: the plaintext-0600
  token is an honest, documented trade-off. On a single-user Windows box its real
  exposure is any process running as the owner — the same posture as the SSH key
  sitting beside it. Low priority.
- **The med/low backlog.** 16 of the 29 previously-unverified findings are still
  open, plus the surviving lane findings. None is urgent; they are listed in
  `findings-lanes.md` in severity order so they can be picked off opportunistically.


---

## Regression tests written

Rule 4a of the brief permits committing a regression test that pins a confirmed
finding. Two were written, in a new `server/appd/test-integration/` directory:

| file | pins | state |
|---|---|---|
| `answer-fingerprint.test.js` | L1 — the `/answer` guard being opt-in | 3 pass, 2 skipped |
| `pane-plan-approval.test.js` | L11 — plan-approval dialogs undetected | 2 pass, 1 skipped |

```
node --test "test-integration/*.test.js"
# tests 8 | pass 5 | fail 0 | skipped 3
```

**Why they are outside `test/`.** `test/*.test.js` is a shipping gate —
`build.sh` refuses to release when it fails. Three assertions describe behaviour
the code does not have yet, so they are marked `skip` with the finding id on
them. Un-skip each one in the same commit as its fix, then move both files under
`test/`, at which point appd finally has a route-level suite.

The passing assertions are not filler: they are the **controls** that make the
failing ones meaningful. `pane-plan-approval.test.js` asserts that the same
dialog *is* detected with a dash-bulleted plan, and *is* detected when the
numbered plan sits outside the 24-line window. A "fix" that passes the skipped
test while breaking either control has fixed nothing.

One methodology note worth keeping: **fixtures for `detectPrompt` must be
captured from a live pane, never written by hand.** My first draft of the plan
fixture invented a box-drawn dialog and produced a confident false positive —
real Claude Code prompts are not box-drawn. The committed fixture matches live
captures.
