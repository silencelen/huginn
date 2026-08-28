# Huginn changelog

## 2.87.0 — 2026-08-27

The question card stops following you to the Screen tab.

### Fixed
- **A question's buttons no longer draw over the Screen tab.** That tab is
  where you go to answer what the buttons cannot handle — a multi-part
  question's card says so itself — and the card then drew itself on top of
  the very pane it had just sent you to. The conversation keeps its card
  exactly as before; the rule (a question shows its card on every face
  except the live terminal) now lives in one shared place for both the
  phone and the desktop, so the two clients cannot drift apart on it.

## 2.86.0 — 2026-08-27

Attaching a page is a thing you ask for, not a thing on the screen.

### Changed
- **The attach button asks what kind.** Tap the clip and choose — Take photo,
  Photo library, Local file, or **Notes page**, which opens the same page list
  the chip used to. Everything the button already did is still one tap and a
  choice away; the page is simply in the same place as the rest of it.
- **The standing "Attach a page" chip is gone** from every composer, chats and
  sessions alike. It was a row of chrome above every message box, permanently
  offering something used occasionally.
- **A page you HAVE attached still says so**, in the same compact mark by the
  composer — its name, and the ✕ to send without it. Tapping it still switches
  pages. A whole page riding out with a message is not something to send
  silently, so that half stays exactly where it was.
- Against an older daemon with no pages, the menu is the three it has always
  been — nothing offers a route that can only fail.
- **The Overview now says what the session would have cost.** An "api cost"
  stat, priced at Anthropic's published API list rates **per model** — a run
  that used opus for the work and haiku for a subagent has no single blended
  rate — with cache writes billed at the TTL the record actually carries, and
  the fan-out's share named beside it: "~$457.00 · ~$112.02 of it in agents".
  Under it, in the same quiet line as the cache split, is what the figure IS:
  what this session's tokens would bill at API list rates, covered by the
  subscription and not a bill. That caption is the reason the number is
  allowed on the screen at all — the version that shipped this tab withheld
  dollars entirely, because the client would have had to invent them, and the
  daemon prices them now. Tokens on a model it has never seen are counted and
  said out loud rather than rounded into a total that would look complete, and
  a session nothing could price shows no figure rather than a zero.

## 2.85.0 — 2026-08-27

The devnotes wave: everything on the owner's list — pages you keep, a place to
rest in a long run, honest budgets, and clean exits.

### Added
- **Pages** (scratchpads): notes that live on the host — Main plus your own.
  Open them from the top bar on Chats, Sessions and inside any conversation;
  attach ONE page to a message from the composer chip (the daemon quotes it
  server-side, and nothing attaches unless you ask); "Send to…" stages a page's
  text into any chat or session draft — appended to what's already typed, never
  auto-sent. Autosave holds your text through conflicts, page switches and
  failures; a page edited on two devices says so instead of losing one side.
- **Overview**, a third tab on a session — a place to rest in a long
  unattended run. Exact token stats with the cache split, the pace and what it
  adds by each plan-window reset (tokens and percentages only — no invented
  dollars), goals and notes that autosave to the host and follow the session,
  and the map: the whole run drawn as blocks — you, the work, the answers —
  with agent branches that leave the spine and come back where they merged.
- **Polish** under a Round's prompt and goal: one caged model call drafts a
  tighter version against how a round actually runs — the single unattended
  turn, the mode's tools, the "done when" sentence the goal completes. You
  read it, then Use or Discard. Nothing is ever applied by itself.
- **Extra usage on Status**: the budget, the amount used and the state
  ("paused until the monthly reset", "limit reached") — shown for any account
  that ever enabled credits. A paused month used to hide real spend entirely.

### Changed
- The Status plan and usage sections are one shared implementation with the
  desktop, and the reset countdowns tick while you watch.
- Settings decluttered: the three "send a test" buttons are gone, the
  background-delivery counters fold away until asked for, and the connection
  test lives inside "Save and connect", which already ran it.

### Fixed
- A refused send hands back BOTH the text and the attached page — chat and
  session alike. The composer being emptied on press is a promise, not a risk.
- The auth token is excluded from device-to-device transfer; cloud backup was
  already off, and now a phone migration cannot carry the credential either.

## 2.84.0 — 2026-08-26

The conduits: the local tier and Claude, connected in exactly one direction
each.

### Added
- **Escalate to Claude** on a local chat: the conversation lands as a DRAFT
  in a new Claude chat, for you to read, edit and send. Nothing is auto-sent,
  and the local chat is untouched. A local model never calls Claude itself.

## 2.83.0 — 2026-08-26

The journey wave: how the local tier is discovered, entered and lived with.

### Added
- **Local AI in the new-chat dialog**: serving machines' models are listed
  below the hosts — one tap makes the chat (Ask is forced by design, so there
  is no second question to answer).
- **"waking the local model…"** between send and first token on a local chat.
  A cold model takes up to ~30s to load, and the thinking shimmer over that
  silence read as a hang.

## 2.82.0 — 2026-08-25

The audit wave: a six-surface review of the local-AI features in real use,
every finding adversarially verified against the code before it was fixed.

### Fixed
- The Settings "Devices" line counts machines that can RUN WORK, with serving
  machines on their own line — and no longer counts one box twice.
- **The model menu tracks reality**: the catalog refreshes when a chat opens
  (it was fetched once per app launch, freezing every machine in whatever
  state the app started with), shrinks to what the daemon will accept on a
  STARTED chat, and gains "Host default" rows so a picked model or effort can
  be cleared again.
- **Ask/Act is a real control on claude chats**, as the desktop always had —
  the chip claimed "fixed at creation" while the daemon accepted the change.
  Local chats stay honestly Ask-only.
- **A refused send costs nothing typed**: the message returns to the composer
  (it was destroyed with only a snackbar left), and notification replies
  report the daemon's actual reason instead of "huginn is unreachable".
- The new-chat dialog no longer offers a serving machine as a place to run
  claude work; Forget forgets the whole machine, not silently half of it, and
  says which services would re-enrol.

## 2.81.0 — 2026-08-25

### Changed
- **One machine, one card.** A PC that both runs work and serves local AI
  used to appear twice in Devices ("DATATREEX" and "datatreex-llm"). It is
  now one card, named the way you know the box, with each capability on its
  own line — what it will do as a device, and what it serves as local AI.
  Ask/Act belong to the work capability; Forget takes away everything the
  box holds. Chat model menus title local rows by the machine's own name
  too ("Qwen3 8B — DATATREEX").

## 2.80.0 — 2026-08-25

### Added
- **Local models in the chat model menu.** A machine serving local AI (the
  huginn local tier) now shows its models as rows named after the machine —
  picking one is picking the machine. Local chats are Ask-only and say so; the
  effort control is absent for them (a local model has no effort knob).
  Session model menus never offer local rows: a session control types into a
  live Claude pane, where a local model could never work.
- **The Devices screen explains a serving machine**: what it serves, whether it
  is reachable, and how to chat with it — with no Ask-or-Act-here buttons,
  which could never work there.

## 2.79.0 — 2026-08-25

### Fixed
- **A Round's model and effort can now be changed after creation.** The
  client API omitted both fields from round updates, so a Round born with a
  model could never be moved off it — the daemon accepted them all along. An
  empty value clears back to the host default.

## 2.78.0 — 2026-08-25

### Changed
- A device that has not asked for work since huginn restarted now reads
  **"free? not asked for work since huginn restarted"** rather than "idle".
  Reachable and free are different questions, and the row was answering the
  second with the first — which is how work got handed to a machine that was
  still finishing an earlier job.

### Fixed
- The wire-contract test — the only automated check that the app and the daemon
  still agree on the payload shape — had no Round and no Device fixture. Its
  fixtures were captured before either feature existed, so the two most likely
  to have a field renamed were the two it did not cover. Both are captured now,
  including the report's item count and its read state.

## 2.77.0 — 2026-08-25

### Added
- **"Mark done" on a round that has reported something.** The status mark goes
  quiet and the line reads "read"; the report stays exactly as it was and is still
  fully readable. Undo puts it back.

  A report saying "Needs you" is true when written and stays true forever, so the
  red mark outlived the work it was about — the only thing that would clear it was
  the next run, which for still-open findings said the same thing again.

  The control sits beside the dot it turns off rather than down among Pause and
  Run now: those act on the schedule, this acts on the report. It appears only
  when there is something to answer, so it is not a fourth permanent control.

  Marking is remembered against that RUN, so the next report arrives unanswered.

## 2.76.0 — 2026-08-25

### Fixed
- **A round that found more than the daemon keeps now says both numbers** —
  "500 items, showing 20" — instead of rendering the kept count as if it were the
  whole story. The two used to contradict each other on one screen, with the
  headline saying 500 and the line directly beneath it saying 20.

## 2.75.0 — 2026-08-25

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

## 2.74.0 — 2026-08-25

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

## 2.73.0 — 2026-08-25

### Changed
- **This build accepts both `mobile-v` and `app-v` release tags, and that is the whole point of
  it.** A tag prefix is an UPDATE CHANNEL, not a label: a client only knows the prefix it was
  built with, so renaming one does not fail loudly — the old client keeps checking, matches
  nothing, and reports "up to date" forever, with nothing able to tell it the new name.
  So the rename is sequenced. This release goes out under the OLD tag, so every phone already in
  the field can still find it; once it is installed, that phone understands both. Only then does
  publishing switch to `mobile-v`. Nothing is ever orphaned, and no release has to carry a
  duplicate 56 MB APK under two tags.
- Release titles now name the platform: **Huginn Mobile 2.73.0 (Android)**, alongside
  Huginn Desktop (Windows, Linux), Huginn CLI (Linux, macOS, Windows) and huginn-appd (server).

### Fixed
- **A round that says "Needs you" now has somewhere to go.** Tapping one opened its run — which is
  SEALED, and said only "This round has finished. It is kept here for review." Everything in that
  chain was individually right: "Needs you" is a claim about the world, not about the conversation,
  and sealing a finished run is correct. What was missing was a door between them.
  A sealed run whose report left something to do now offers **Carry on**, opening a fresh chat on
  the same machine, in the same mode, with the report already in the composer — as a DRAFT, never
  a sent message, because a round can be `act` and sending on a tap meant as reading would start
  unattended work. First thing to consume `RoundItem.suggest`, which was added for exactly this.
  An all-clear round with no items gets no button.
- **Editing a Round no longer moves it to your timezone.** The draft behind the editor carried no
  zone, so opening a Round set for 07:30 Europe/London on a device in Los Angeles and saving it
  UNCHANGED rewrote it eight hours out. Invisible too: the editor never showed a zone, and "7:30"
  reads as correct in every zone on earth. The Round's own zone is now carried through the editor
  untouched, the device's zone is only a default for a NEW round, and the cadence line names the
  clock — `Every day at 7:30 AM - Europe/London`.

## 2.72.0 — 2026-08-25

### Fixed
- **A question no longer covers the terminal or the controls.** 0.8.7 stopped the prompt card
  resizing the real terminal by floating it over the pane — which traded one problem for another:
  on the Screen tab it sat on top of the output and the controls underneath it.
  The card is back below the pane where it blocks nothing, and the viewport genuinely shrinks to
  make room. What makes that safe is the distinction the last two releases kept missing: **a
  prompt may take LAYOUT space, it may not change the REPORTED geometry.** The geometry report is
  now held while a question is up, so tmux never hears about the shrink — the terminal keeps its
  own shape and scrolls inside the shorter window instead of re-wrapping.

## 2.71.0 — 2026-08-24

### Fixed
- **The live pane kept resizing the owner's real terminal, and everything attached to it saw it.**
  Reported as wrong wrapping, jumping, garbled content and live typing not working — on the phone
  and the desktop at once, which is the tell: they share the tmux window, so one client reporting
  a bad geometry breaks it for both.
  - **A question resized the terminal, twice per question.** The prompt card was a SIBLING of the
    box that measures itself into tmux rows, so it took ~16 rows on the phone (~9 on the desktop)
    the moment Claude asked something and gave them back when it was answered. The pane re-wrapped
    under the reader exactly while they were trying to read the thing being asked. It is now an
    overlay over the pane — same card, same promise that a question is always answerable, zero rows.
  - **The copy buttons shipped in the last release did the same thing**, and worse: they were
    conditional on the pane having text, so they came and went with the content and walked the pane
    between two shapes. Moved into the tab strip, which is above the measured box entirely.
  - **Clicking a copy button stopped live typing.** The button took keyboard focus from the pane,
    and nothing gave it back. The trailing slot is now out of the focus order.
  - **The screen-error banner flapped sub-second.** It cleared on every successful poll, so one
    failed keystroke shrank the terminal and the next poll grew it back — two real resizes out of a
    transient hiccup. Overlaid.
  - The tab strip's height is now pinned, so a trailing action cannot change it by the 4dp that was
    enough to cost a row.
- **The screen menu shifted under your finger.** Read live, a URL scrolling on or off the pane
  inserted or removed the FIRST item while the menu was open, moving everything below it by a row —
  a tap aimed at "Wind down…" could land on "Kill session…". The link list is snapshotted on open.

## 2.70.0 — 2026-08-24

### Added
- **You can get text back out of the screen view.** It could render a live pane and offered no
  way to take anything from it. Found the hard way: a 450-character sign-in URL on a headless
  machine, visible on screen and impossible to use.
  - **Copy link**, and it undoes the wrap. A terminal breaks a long URL across rows, so even a
    text-selection gesture would have handed back five fragments with newlines in them. The
    unwrap is exact rather than a guess — the pane reports its own column count, and a row that
    reaches it is one the terminal broke, not one the writer ended.
  - **Copy screen** copies what is *there*, with no reflowing. A terminal draws in columns, and a
    copy that silently rejoined its rows would corrupt every table, tree and progress bar on it.
    Only the link copy undoes a wrap, and only inside the link.
  - Offered only when there is something to take, and named for what it will give you: "Copy
    link", or "Copy 3 links" when the pane holds more than one.

## 2.69.0 — 2026-08-24

### Added
- **You can write a Round.** Until now `createRound` existed in the client library and nothing
  called it, so a scheduled job could only be made with curl — the feature was real and
  unreachable. There is a New round button on the Rounds tab, an Edit on every row, and a
  full-screen form: name, what it should do, how you will know it finished, when, what it may
  do, where it runs, and who to tell.
  - **Full screen, not a sheet.** Seven decisions do not fit in half a phone without putting
    scrolling inside scrolling.
  - **The goal field explains itself where it sits**: with one, each run answers whether it got
    there, and a run that did not is flagged even when it sounds pleased with itself.
  - **Choosing "Change things" says so out loud** — it runs unattended, nobody will be watching,
    and it will not stop to ask. Wanting something on a schedule is not consent to that.
  - A device is only offerable for an `act` Round if its **enrolled** scope allows it — not what
    it will do this second, or a Round for next Sunday would be un-pickable because the laptop is
    locked on a Tuesday.
  - A live cadence line under the picker ("Sun at 7:00 PM"), so a wrong schedule is wrong
    visibly rather than after a save that discards the typing.
  - Tapping a Round that has never run now opens the Round instead of swallowing the tap.

### Fixed
- **The app never knew which machine a Round ran on.** The daemon has always sent `host` and
  `hostName`; the model simply lacked the fields, so every Round read as local however it was
  placed — and a Round on a Device is the one thing neither feature can do alone.
- `createRound` did not send `goal` **at all**, so a Round written by a client could never have
  had a finish line. It and `host` now go with it.

## 2.68.0 — 2026-08-24

### Added
- **A Devices screen.** Until now the phone could only *pick* a machine in the new-chat dialog —
  it could not see which machines were enrolled, what each would allow, whether one was locked
  or running, or take an enrolment away. All of that was desktop-only. Now it is in Settings →
  Devices, with Ask here / Act here / Forget on every machine.
  - **In Settings, not the bottom bar.** Chats, Sessions, Rounds and Status are the daily loop;
    how many machines are enrolled is something you look at when you are changing it. A fifth
    bar slot would have cost one of the four a place it earns every day.
  - **Act is offered from what a machine will do RIGHT NOW**, not from what it is enrolled at, so
    a locked machine does not show a button that always fails.
  - **Forget asks first, and says what it does not do**: huginn stops offering that machine work,
    but nothing reaches onto the machine — a runner still going there will enrol again. The
    desktop does not confirm and does not need to; a phone is a pocketful of mis-taps.
  - The rows are one composable shared with the desktop, so a device cannot read as one thing on
    a laptop and another in a pocket. It says "own, look while locked" rather than just "look" —
    showing only the second makes a correctly-configured machine look misconfigured.
  - No scope control anywhere on the screen, and one line under the list saying why: what a
    machine will do is set on the machine. A control here would be a lie about where the decision
    is made, and the lie would only surface the day it mattered.

## 2.67.0 — 2026-08-24

### Changed
- **A round's Pause is a button, not a switch.** It was a filled Switch riding the title row,
  which on a dark list was the loudest thing on screen — louder than the status mark and the
  report it sits beside — for a control you touch about twice a year. Pausing is not a mode you
  set, it is a thing you do, so it now reads as a word next to Run now, at the same weight.
- **Rounds stay live while you are looking at them.** The list refreshed only on resume or a
  manual pull, so tapping Run now left the row showing the previous verdict. It now polls while
  the Rounds tab is on screen and stops when you leave, the same shape as the sessions poller.
- The status dot sits on the round's TITLE rather than centred between title and cadence, where
  it read as floating between the two and belonging to neither.

### Fixed
- A machine enrolled since the last refresh is now offerable: the new-chat dialog reads the
  device list as it opens, instead of showing whatever was known at resume.
- A finished round no longer shows suggestion chips above its "kept for review" note.

## 2.66.0 — 2026-08-24

### Added
- **Start a chat on another machine, from the phone.** New chat now asks where it runs when
  any machine is enrolled — this host, or one of them. Machines that cannot take the job stay
  VISIBLE but unselectable with the reason, because "asleep" and "read-only" need different
  actions from whoever is reading, and hiding a device you enrolled would just look like it
  had disappeared. Act is disabled for a machine that will only Look: the host would refuse
  anyway, so the answer arrives before the tap rather than after it.
- The question only appears when there is a choice to make. With nothing enrolled, "where"
  is not a question, and asking it on the one dialog every chat goes through would be noise.

## 2.65.0 — 2026-08-24

### Changed
- **Rounds has its own tab.** It was a band above the chat list, which made each of them read
  as the other's preamble — a Round is not a conversation: it has a cadence, a goal and a
  verdict. It now sits in the bottom bar between Sessions and Status, with the same list on a
  wide screen kept to a readable measure rather than stretched.

### Added
- **A finished round says so instead of offering a composer.** The run is one turn against its
  goal, and afterwards it is kept for reading only. The input is replaced by a note, because a
  send would be refused by the host anyway.
- **"Did not finish" on a round that missed its goal**, ahead of the headline — a headline can be
  perfectly cheerful while the job was not done.
- **A chat that runs on another machine is badged with its name.** Only when it is not this
  host: a badge on every row is a badge that says nothing.

## 2.64.0 — 2026-08-23

### Added
- **Rounds on the phone.** The host's scheduled work now sits above the chat list: what each
  Round is, when it next goes out, and what it last found — the report itself on a full-width
  line rather than a truncated fragment, because the report is the point of the feature.
  Tapping a Round opens the run behind it; the switch pauses it; Run now fires it.
  State is one small dot beside the title, in the app's own palette, and the quiet states are
  drawn smaller as well as duller so a screen of healthy Rounds recedes.
- A host with Rounds and no conversations no longer reads as empty. It is set up, not empty,
  and "No chats yet" would have hidden the only thing it had.

### Fixed
- **A reused session name showed the DEAD session's conversation.** The daemon could briefly
  serve a previous session's transcript for a reused tmux name, and the merge APPENDED it — so
  a one-second window on the host became a permanent artefact on screen. `claudeSessionId` is
  now the window's identity: when it changes the view resets instead of appending, and the read
  offset and history handle are dropped with it (they are positions in a file this session never
  wrote). Self-healing regardless of what the daemon says.

## 2.63.0 — 2026-08-15

### Push moves to a new Firebase project

The app registers for push against a different Firebase project now. Nothing
about push changes in use, but the move invalidates the old registration: the
first launch after updating mints a fresh token and re-registers it with the
daemon, and until that happens the host has no way to reach this device. If a
notification seems to go missing right after the update, open the app once.

The reason is that a Firebase project id is permanent — it cannot be renamed,
only replaced — and the old one carried a name with no business being in a
public repository. The config file that names it has left the tree as well: it
is per-deployment now, with a google-services.json.example to fill in from your
own project. The key the new project issues is restricted to this package and
its signing certificates, which the old one never was.

## 2.62.0 — 2026-08-12

### The ask bar

A second widget: a search-bar for huginn, the launcher's quick-search shape.
Tap it and a thin overlay opens over the home screen with the keyboard already
up — type a question, pick Ask or Act, hit send, and it becomes a chat you land
in as the answer streams. Nothing is created on the host until you actually
send, and the overlay honours the app lock: it is a second doorway into
free-text-to-the-host, so it demands the same unlock the front door does.

## 2.61.0 — 2026-08-12

### The fleet on your home screen

A home-screen widget shows the session fleet without opening the app: who needs
you first, what is working, what is waiting, plus how many chats are running.
Tap a session to open it; tap **New chat** to land in the app with the ask/act
question already open; the arrow refreshes on demand.

The widget draws the last observation the app recorded — every path an
observation arrives by (push, stream, alarm, background poll) feeds it, so it
stays current without a watcher of its own, and an "as of" time says exactly how
fresh what you are looking at is. Placement and a half-hour system tick also
refetch on their own, so the widget keeps working even with notifications
switched off. Resizing it taller shows more sessions.

No daemon change: appd stays at 2.59.x.

## 2.60.0 / appd 2.59.0 — 2026-08-11

### Update the app from within the app

Settings gains a **Software update** section: the app checks the project's public
GitHub releases, and — on your tap — downloads a newer build, verifies its sha256,
and hands it to the system installer. It is no longer tied to the devstore; the
check never downloads on its own, and nothing installs without your confirmation
and Android's own "install unknown apps" prompt.

### Context used, and when a session is compacting

A session now shows a "context used" reading (a small meter in the header, `ctx
N%` in the list) and a **Compacting…** marker while it is rewriting its context.
The session menu gains **Compact context**, which reclaims context from your
phone without opening the pane.

### Under the daemon (appd 2.59.0)

A multi-question dialog that could not be answered from the desktop buttons now
serves a card that sends you to the Screen tab instead of misfiring; the daemon
also surfaces the context percentage and a compaction signal, and gained the
`/compact` action the button above uses.

## 2.59.0 / appd 2.57.0 — 2026-08-11

### Wind down a session

The session menu (both the row menu and the open-session menu) gains **Wind
down…** beside "Kill": it asks Claude to wrap up and commit, and — when the host
has auto-end on — the session ends itself once it settles. It only sends a
message, so it is not destructive.

### Photo thumbnails

A photo you attached now shows as a real thumbnail in the chat history instead of
a "photo attached" line, read back from the host (it falls back to the line if
the file has been removed there).

### Sharper question buttons

The buttons for a Claude question now carry the exact option text and its
description, fused on the host from what Claude actually asked rather than
reconstructed from the on-screen width — so a long option is no longer clipped,
and an answer no longer bounces because the pane re-wrapped underneath it.

Alongside (appd 2.57.0, deployed): soft-end/auto-end on the host, the prompt
sidecars + fusion this release renders, and photo uploads served back for the
thumbnails.

## 2.58.0 — 2026-08-10

### The raven

Huginn is Odin's thought-raven, and now the icon is one: a dark raven, drawn as
a single silhouette, on a bone tile. The old mark read as a waterfowl head and
was light-on-dark — a bone-white raven is not a raven.

Everywhere the app shows an identity, it is the same bird now: the launcher
icon (with a proper Android 13 themed/monochrome variant, which the old icon
never had), the status-bar notification silhouette, and the devstore listing.
The canonical mark lives at `assets/brand/raven.svg`; every platform's icon
derives from that one path, so the phone, the desktop client and the CLI all
say Huginn the same way.

## 2.57.0 / appd 2.54.0 — 2026-08-10

### The conversation holds the whole session

The Conversation tab showed the most recent part and said so, with no way to ask
for the rest. On a long session that was a sliver — measured on a real transcript
here, 51 messages out of 3452.

Scroll to the top and there is **Load earlier messages**, one page per tap, until
you reach the beginning. That warning belongs to the **Screen** tab, where it
stays and is true: a Claude pane runs on the terminal's alternate screen and has
no scrollback at all. The transcript always held the full history; only the
reader was capped.

Needs huginn-appd 2.54.0, which is deployed.

### Things that used to go blank a few seconds after opening a session

The model control fell back to a placeholder, and the composer showed Send on a
session that was working (or Stop on one that had finished). Both were the same
cause: the screen keeps session details that a transcript update does not repeat,
and this app was quietly dropping four of them each time it caught up — including
the one the Send/Stop control is derived from. Worst on a folded phone, where the
sessions list is not on screen to correct it.

### A delivered message stops saying it is still waiting

2.56.0's notes claimed this and were wrong: the fix landed in shared code that
this app was not using. It is genuinely fixed now.

## 2.56.0 / appd 2.53.1 — 2026-08-04

### A waiting message stops saying it is still waiting

A message you type while Claude is mid-turn is queued, and it shows a badge that
says so. The badge used to stay until you left the session and came back, because
the host announced the delivery by sending the whole message a second time —
which also meant the conversation briefly held two copies of it, the older one
still marked as waiting. The host now reports just the delivery, and the badge
clears where the message already is.

You will only see the difference on a message sent while Claude is busy, which is
exactly when it was wrong before.

### Answer buttons only appear when they can be trusted

The buttons on a "needs you" notification carry a fingerprint of the question they
were drawn for, and the host refuses an answer whose fingerprint no longer
matches — that is what stops a tap answering some *later* question that has taken
its place. The host now requires that fingerprint rather than accepting an answer
without one, so this release stops offering buttons in the rare case where there
is none to send. The notification still arrives and still opens the session.

Nothing you would have noticed: in practice the host never sends options without a
fingerprint. This closes the gap on both sides rather than trusting that to stay
true.

## 2.52.0 / appd 2.47.0 — 2026-07-30

### Any file can be sent now, including router backups
Attaching a UniFi backup was refused. The rule was "only what Claude can read
directly", which conflated two different questions: whether a file may be stored
on huginn, and whether the Read tool can display it. A backup, a tarball, a
database or a capture can all be examined perfectly well — with `file`, `unzip`,
`sqlite3` — they just cannot be *read* as text.

So nothing is refused for its type any more. What the type decides instead is
what the message asks Claude to do: readable things say "read it", binaries say
"inspect it with shell tools", which is what the refusal was really protecting
against — a binary handed to Read comes back as gibberish and the answer is a
shrug. Binaries need act mode, and the message says so.

The size limit went from 20 MB to 128 MB, because both ends now stream the file
straight through instead of holding it in memory: the daemon writes to disk as
bytes arrive, and the phone reads from the file to the socket. The limit lives on
the host alone now, so there is no stale number in the app quietly refusing what
the daemon would have taken.

## 2.51.0 / appd 2.46.0 — 2026-07-29

### The agent count now matches the one Claude Code prints
The work sheet counted only agents that had already started, because it counts
files on huginn and a planned agent has no file until it runs. So "1 of 2
agents" sat beside the pane's own "1/6 agents done" and read as a different
fan-out. The total now comes from the TUI's own row, and the wording matches it.

### A queued message behaves like one
Two things were half-right. A message still waiting was drawn where it was
typed — which is mid-turn, so it sat *above* the rest of the answer it was typed
during and above the answer it was waiting for. It now waits at the bottom,
where the pane shows it and where you look for it. And on delivery it used to
just lose its "queued" tag in place; now it moves into its real position in the
conversation, so the bubble goes where the exchange actually happened instead of
being stranded mid-answer.

## 2.50.0 — 2026-07-29 (switchable routes to huginn)

### The app now knows more than one way to reach huginn
Android runs one VPN at a time, so the address that reaches huginn depends on
which tunnel is up: the tailnet address while Tailscale holds the slot, and
huginn's LAN address (through the yggdrasil mesh gateway) while Yggdrasil does.
Connecting one tunnel used to leave the app talking to an address that no longer
routed, with nothing to do but retype the URL.

Settings now offers both routes as a choice. Leave it on Auto and the app probes
them on startup, keeping whichever answers; tap a route to pin it and auto stays
out of the way. Typing a custom URL pins it too, so a deliberate address is never
silently overridden. If nothing answers the existing setting is kept rather than
cleared, because a dead network should not look like a misconfigured app.

The chosen route is stored where the background workers already read it, so a
switch applies to notifications and the watch service as well as the UI.

## 2.49.1 / appd 2.45.0 — 2026-07-29 (audit fixes, round 8)

### Changing an alert setting during a check no longer undoes itself
The alert watcher took a copy of its settings when a check began and wrote the
whole thing back when the check finished — so turning alerts off, or changing
the mode, while a check was in flight was silently reverted a moment later. The
watcher now writes only the bookkeeping it owns and leaves your settings alone.

### A refused run no longer destroys the messages it was given
Queued messages are removed from the queue before the run that will deliver them
is known to have started. When the start was refused — too many runs at once, or
one already active — they were simply gone, after you had been told they were
queued. They now go back to the front of the queue.

## 2.49.0 / appd 2.44.0 — 2026-07-28 (audit fixes, round 7)

### A momentary tmux hiccup could fake a wave of answered questions
Any failure to list sessions — a fork that hit a resource limit, the tmux server
restarting — was reported as "there are no sessions". The alert watcher read
that as every waiting question having been answered: it cancelled all those
notifications, then announced them again when tmux came back. A blip became a
burst of wrong notifications in both directions. Failing to look is now
distinguished from looking and seeing nothing, and a tick that cannot see skips
rather than concludes.

### Instant alerts could stop for good after a reboot
The watcher on huginn's session-state directory is attached once at startup, and
that directory lives on storage cleared by a reboot. Starting before it existed
threw once, logged once, and left alerts on the slower ten-second poll for the
rest of the daemon's life with nothing saying so. It now creates the directory,
and retries if the watch is ever lost.

### Sending while a photo was still uploading lost it
The message went without the photo, and because the attachment stayed staged it
silently rode your *next* message instead. Attaching something is a statement
about the message you are writing, so the send now waits for the upload — with a
timeout, so a stuck upload cannot strand the message.

## 2.48.0 / appd 2.43.0 — 2026-07-28 (audit fixes, round 6)

### Session controls were wrong on the folded phone
Whether a session is "working" decides which control the composer offers —
interrupt while it runs, send when it is idle. That state came from the sessions
list, which is only kept fresh while the list is on screen. Folded, a session
opens *alone*, so the list stopped updating and the flag froze: a session that
started working still offered Send, and one that finished still offered Stop.
It now reads the live state the session view already polls for itself.

### Closing voice mode could turn the microphone back on
Text-to-speech reports completion by posting back to the main thread, so a
completion already in flight when you dismissed the sheet still arrived — and
drove the loop's "finished speaking, now listen" step, starting the recogniser
for a sheet you had closed. Dismissal is now final: pending callbacks are inert
and the engines refuse to restart.

### A message could vanish from the top of a long conversation
Reading the tail of a long transcript assumed the window always lands mid-record
and dropped its first line. When the window happened to land exactly on a record
boundary, that line was a whole message — and it was thrown away. The reader now
checks the file instead of assuming.

## 2.47.0 — 2026-07-28 (audit fixes, round 5)

### The background heartbeat could end silently and never restart
Each beat scheduled the next one only *after* its network check — a check
allowed 25 seconds, inside a receiver the system guarantees about ten. If the
process was killed in that window (Doze, memory pressure, the system reclaiming
a background app — precisely the conditions this alarm exists to survive) no
next alarm was ever scheduled, and the fallback was simply over until you next
opened the app by hand. The next beat is now armed *before* any work, so the
chain survives the check dying in any manner at all.

### "Stop watching" now actually stops
The notification's Stop button stopped the service but left the preference on,
so the next heartbeat tick — or the next time you opened the app — quietly
started it again. It looked like it worked and came back within the hour. It
now turns the setting off too, which is what the button always claimed to do.

## 2.46.0 / appd 2.42.0 — 2026-07-28 (audit fixes, round 4)

### Answering one waiting session no longer hides the others
When several sessions needed you at once, they shared a single "3 sessions need
you" notification — posted under the first session's slot. Answering that one
cancelled the slot, and the notice about the rest vanished with it, their
transitions already consumed. Each waiting session now gets its own
notification, which also means each can be answered from the lock screen
instead of only the lucky first one.

### Claude's own prompts no longer appear as things you said
"[Your previous response had no visible output...]" is Claude Code nudging
itself after an empty turn. It was rendering as a message bubble you appeared to
have typed. Found by scanning every real transcript on the host rather than
guessing at shapes. Attachment markers stay visible — the rule matches known
system phrasings, not "anything in brackets", precisely so your photos and
files keep showing.

### Sending the same message twice no longer produces three
Queued messages were tracked one-per-text, so sending identical text twice while
Claude was busy left the first stuck with a "queued" badge forever and invented
a third bubble on delivery. Two sends, two messages, both delivered.

## 2.45.0 / appd 2.41.0 — 2026-07-28 (audit fixes, round 3)

### Typing in one session could land in another
Live typing queues keystrokes and drains them in the background. The drainer
remembered whichever session started it, but a later burst joined the same queue
without starting a new one — so typing in a session, switching, and typing again
sent the new keystrokes to the **old** session's pane. Arbitrary text into the
wrong live Claude Code session, which can answer a prompt or run something you
never saw. Every keystroke now carries its own destination.

### A permission prompt could vanish because it mentioned a redirect
The selection caret was matched anywhere on the line, so a `>` inside an
option's own text counted as one — and "Yes, and don't ask again for `echo a >
b`" is ordinary Claude Code wording. Two options then looked selected, the
guard that requires exactly one rejected the whole dialog, and that permission
prompt got **no buttons in the app, no options on its notification, and no
lock-screen answer**. The caret is only ever drawn at the start of a line, and
is now matched only there.

## 2.44.0 / appd 2.40.1 — 2026-07-28 (audit fixes, round 2)

### Folding the phone no longer loses your place
Every unfold, rotate, or theme change rebuilds the activity, and three things
did not survive it:

- **The screen you were on.** Reading a session and unfolding threw you back to
  the sessions list, every time.
- **"Lock now".** The lock flag lived in the activity, so locking and unfolding
  reopened the app unchallenged — inside the grace window that was supposed to
  keep it shut.
- **The notification you already dealt with.** The launching intent is returned
  forever, so each rebuild re-read it and navigated you back to that session or
  chat again. It is consumed now.

### Answering a prompt in the app is checked like answering from the lock screen
Tapping an option on the prompt card typed a bare digit into the pane with no
verification — while the same answer from a notification went through the
guarded endpoint that refuses a stale one. Exactly backwards: the in-app card
shows a *polled* screen and is the most likely to be out of date, and a digit
landing in a question you never saw can accept something you never agreed to.
Both paths now carry the question's fingerprint, and you get told when the
session has moved on instead of being left thinking the tap landed.

### A chat stream that dies mid-turn now recovers
The streaming client had no read timeout at all, so a socket lost to a network
change looked identical to Claude thinking — the composer stayed disabled until
the app was restarted. Bounded now, comfortably above the server's existing
15-second heartbeat.

## 2.43.1 / appd 2.39.1 — 2026-07-28 (audit fixes, round 1)

A 15-lane audit of every file in the app and daemon produced 94 confirmed
findings. This is the first round of fixes — the ones that could lose your data
or take the whole service down.

### The daemon could be crash-looped by an ordinary chat message
`spawn('claude')` had no error handler, and an unhandled child-process error is
fatal to Node. If the claude binary were ever unresolvable — a wedged update, a
PATH change — sending any message killed the daemon outright, taking every
phone's live stream and the alert watcher with it, and systemd's restart just
queued up the next crash. Reproduced on this host before fixing.

### Two paths could silently lose or duplicate your messages
`POST /messages` and `PATCH` both loaded the chat's metadata, awaited the
request body, then wrote that stale snapshot back. Two quick follow-ups meant
the second overwrote the first — a message you were told was queued, gone. A
send landing as a run ended could resurrect an already-delivered message and
answer it twice. And a mode toggle timed across a run's start could erase the
chat's session id, losing its entire conversation context on the next turn.

### An alert that failed to send was lost forever
The code cleared its repeat-guard and a comment promised a retry, but the
observation advanced regardless — and alerts only fire on transitions, so the
transition was consumed. A blocking question that hit a brief network blip on
both channels was never delivered again, on any channel. Failed alerts now keep
their edge for the next tick.

### Push deficit detection was silently disabled
The streaming watch path omitted the host's push tally, so every state change
overwrote the phone's copy with zero — meaning the phone could no longer tell a
quiet night from a broken delivery path, and would never tighten its fallback.

Also: one bad FCM payload could unregister every device at once
(INVALID_ARGUMENT is not proof of a dead token); FCM and Google-token requests
had no timeout, so one hung socket could stall the whole alert pipeline; and
the app fired API calls before its credentials finished loading — 63 rejected
requests a day, a flashed error and an empty list on every cold start.

## 2.42.1 — 2026-07-28

### File attach in chats actually attaches
Picking a file from a chat's attach menu did nothing at all — the chat
composer's file handler was left unwired (sessions had it), so the tap fell
into an empty default. The daemon log was the tell: photos arrived, files never
even tried. Wired, and the file path is now crash-proof besides: anything that
throws while reading or uploading becomes a visible "Attachment failed" chip
with the reason, never silence.

## 2.42.0 / appd 2.38.0 — 2026-07-28

### A second photo no longer "deletes" the conversation (it never did)
Sending a second image made the history above it vanish from view. Nothing was
lost: each photo embeds itself into the transcript as megabytes of base64, and
the reader tailed the file by a fixed 256KB of *bytes* — two photos pushed the
whole first exchange behind the window, so the view began mid-turn-2. The tail
is now measured in *events*, growing its window until the conversation is
actually in it. Your "what's this" chat shows both turns again — reopen it.

Also gone: the "[Image: original 1530x2048…]" bubble — that caption is
coordinate-mapping instructions for the model, not something you said.

### The attach button is now a real attachment menu
**Take photo** (straight into the camera), **Photo library**, or **File** —
in both chat and session composers. Files cover what huginn can genuinely read:
PDFs and text in its many suits (md, json, csv, logs, configs, code). Types it
would print as garbage — docx, zip, apk — are refused at upload with a plain
sentence, because a mute failure downstream is worse than a named one here.
File chips show the filename; file messages render as "📎 name".

## 2.41.0 — 2026-07-28

### Share to an existing chat or session
Sharing always started a new chat, which was wrong exactly when sharing is most
useful: the screenshot of an error belongs in the session already working on
that error, with all its context. A destination sheet now asks — new chat
first (the safe default for a link), then sessions with their live state, then
recent chats. Text appends to the target's draft without clobbering anything
half-typed; a photo stages on the target's composer. Dismissing the sheet drops
the share: you were just shown everywhere it could go.

Under the hood the attachment slot now has an *owner*. It was a single global,
and a photo staged on one screen could ride a send from another if you moved
fast enough. Every stage, send, chip and clear now names its surface, so a
photo can only leave in a message from the place it was attached — which is
also what lets a share staged for the destination survive the previous
screen's teardown during navigation.

## 2.40.1 / appd 2.37.1 — 2026-07-28

### Photo messages read like photos, not plumbing
A photo-only message used to display — and title its chat — as the raw
attachment marker: "[Attached image at /var/lib/huginn-appd/uploads/img-…".
The bracketed path is for Claude; people now see "📷 Photo attached" in the
transcript bubble, and chat titles and list snippets derived from such messages
say "📷 photo" instead of where the daemon stored a file.

Also: the README now documents everything this week actually built —
notification channels and un-notification rules, the adaptive heartbeat,
attachments and the share sheet, deterministic ask mode, and the new API routes.

## 2.40.0 — 2026-07-28

### Photos in sessions, not just chats
The attach button now lives in the session conversation composer too — same
chip, same states, same photo-alone-is-a-message rule. Screenshot an error and
send it to the session that is working on the problem; Claude in that session
reads it like any file. One shared implementation for both composers, so
"works in chats but not sessions" cannot happen by drift.

## 2.39.2 — 2026-07-28

### Sharing into a cold-started huginn works
A share often arrives in a fresh process, and the new-chat call raced the
settings load — it went out with a blank token, was refused, and the share died
silently on the Sessions screen. It now waits for the credentials to load
first.

## 2.39.1 — 2026-07-28

### Attaching a photo failed for every image — fixed
The transcoder's first pass asks Android only for the image's dimensions, and in
that mode the decoder returns null *by design*. 2.39.0 read that null as "could
not read this image" and rejected every photo ever attached. The dimensions are
now the success signal, as they always should have been.

Portrait photos also arrive upright now: the camera records rotation as EXIF
metadata rather than turning the pixels, a re-encode drops the metadata, and
without applying it first every portrait shot would have reached huginn
sideways.

## 2.39.0 / appd 2.36.1 — 2026-07-28

### Share to huginn, and photo attachments
huginn now appears in Android's share sheet. A link or paragraph from any app
becomes a new chat with the text staged as the draft — compose around it, then
send. A shared photo, or one attached with the new 🖼 button in the chat
composer, uploads to huginn and the chat reads it: snap the breaker panel, ask
what the tripped one feeds.

Everything is transcoded to JPEG and downscaled to 2048px before upload —
Samsung cameras shoot HEIC, which Claude cannot read, and the failure without
transcoding is the worst kind: upload fine, chat runs, shrug about an unreadable
file. A photo alone is a complete message; the staged chip shows uploading /
attached / failed states and can be removed before sending. Uploads are pruned
on huginn after 7 days.

One honest note: ask-mode chats could always read files on huginn — `-p` mode
auto-allows read-only tools, and a scoped-Read rule tried during this work
turned out to be a no-op and was removed rather than shipped as a fake fence.
The ask/act line is drawn at mutation, not at reading.

## appd 2.35.0 — 2026-07-28 (host)

### No "finished" buzz for a session you are sitting at
session_finished would have fired for every long turn of a session actively
driven from a terminal — a push per exchange, all evening, for someone at the
keyboard watching it happen. The finish now stays quiet while a tmux client is
attached, the terminal-side sibling of the app's foreground suppression.

Only the finish. A question always alerts: an attached-but-idle terminal in
another room missing a blocking ask is the one failure worse than a redundant
buzz. And attachment is read from the final stretch of the run, deliberately not
sticky — a ten-second attach early in a two-hour run must not silence its finish.
The phone app never attaches, so app-driven sessions still notify unless the app
itself is open on them.

## 2.38.0 / appd 2.34.0 — 2026-07-28

### A notification that stops being true stops being shown
Answer a session's question in tmux, or from another device, and the phone's
"needs you" notification used to sit in the shade forever — inviting a tap the
host would refuse, since the question it referred to no longer exists. huginn
now pushes a silent resolution the moment a waiting question is handled, and the
phone takes the stale notification down. Nothing appears; something disappears.
Telegram never carries these — "andrev answered" arriving as a message would be
noise about something you yourself just did.

Two companions to that:
- **Opening a chat or session clears its notification.** Read is dismissed —
  leaving it up would just be a chore handed back to you.
- **An answered question resets the repeat guard.** The 30-minute quiet window
  exists to stop the *same* question re-alerting; once one was answered, the next
  question from that session is genuinely new and alerts immediately.

## 2.37.0 — 2026-07-28

### No buzz about the screen you are looking at
A chat finishing while you were watching it stream in still posted a
notification, and a session asking a question while you had that session open
did the same — announcing what was already in front of you. Every messaging app
suppresses this, and for the same reason: a buzz that carries nothing teaches
you that buzzes carry nothing, and then the ones that matter get ignored too.

Suppressed only while the app is actually in the foreground on that exact chat
or session. A conversation left open in a pocketed phone still notifies, and the
suppressed alert is consumed rather than deferred — navigating away later does
not make an already-seen question suddenly buzz.

## 2.36.1 — 2026-07-28

### The app states its own version beside appd's
The Status tab showed "appd 2.33.0" with nothing beside it, which reads as this
app's version to anyone who has not internalised that the phone app and the host
daemon are separate release lines — updating to 2.35.0 and then seeing "2.33.0"
looked like the update had not landed. Status now shows **This app** and **appd
(host)** as adjacent rows, and the connection toast names both: "Connected to
huginn — app 2.36.1, appd 2.33.0".

## 2.36.0 — 2026-07-28

### The conversation survives huginn answering
2.34.0 made a chat notification a thread, and replying did append to it — but only
until huginn answered. The next result rebuilt the notification from scratch with
a single message, and everything above it vanished, so a two-turn exchange was
never visible as one.

Both writers now append to the same thread. Caught by driving the shade on a real
phone: the reply reached the chat, the chat answered, and the notification came
back showing one message with no trace of what had been asked.

## 2.35.0 / appd 2.33.0 — 2026-07-28

### A long session finishing now tells you
Chats have announced their results for a while; sessions never did, so the one
thing the app is most useful for — start something slow, put the phone down, get
told when it lands — only worked for the wrong half. A session going idle now
notifies, and tapping it opens that session.

Gated on how long it ran (5 minutes), because a session goes idle after *every*
turn. Without the gate this is a notification per exchange, which is the fastest
possible way to get the whole app muted. The threshold is the line between "I am
working in this session" and "I left it running and walked away", and only the
second is worth interrupting anyone for. A session that stops to ask something is
not a finish — that is the other notification, and reporting both would say
"finished" about a session actively waiting on you.

### Notification channels now split by nature, not by type
"Chat results" became **"Finished work"** and now carries finished sessions too.
A session waiting on you is *blocking* — work has stopped until you answer — while
a chat result and a finished session are both news you asked for. Splitting them
that way is what lets the chatty kind be silenced without silencing the kind that
blocks.

## 2.34.0 — 2026-07-28

### A chat notification is now a conversation, not a line of text
Replying used to overwrite the notification body: huginn's answer vanished and
"Sent: ..." took its place. A chat is a conversation, so it now renders as one —
huginn's answer, then your reply underneath it, then whatever comes next. The
thread is read back off the shade itself rather than kept in a store of our own,
which means swiping the notification away really does forget it; a parallel
history could otherwise resurrect messages you had deliberately cleared.

A successful send now says nothing at all. It does not need to: your reply is
already in the thread and huginn's answer arrives as the next message in it.
Only failures speak up, and they repeat your text back, because the box it was
typed into is gone by then.

## 2.33.0 — 2026-07-28

### Settings says what the background check has decided, and why
The wake-up cadence swings by a factor of six and used to choose silently, so the
one bug it has had could only be found by reading a night of server logs after
the fact. Settings now states the decision in the same terms the rule is written
in: *"40 of 40 pushes arrived — nothing dropped, so the backup check only runs
hourly"*, or, in red, *"2 pushes huginn sent never arrived, so the backup check
has tightened to every 10 minutes"*. The line is derived from the very function
the alarm uses, so the screen cannot claim one thing while the alarm does
another.

## 2.32.0 — 2026-07-28

### Notification answers stay bounded; free text needs the phone unlocked
A session's notification offers the options huginn itself put on the screen, and
nothing else. That bound is what lets the answer buttons work on a locked phone
with no login at all: whoever taps one can only choose among answers the session
was already waiting for. Free text into a pane is a different power — arbitrary
instruction to Claude Code on the host — so it is deliberately not offered there.
A session asking something the buttons cannot express is answered by opening the
app, one tap away on the same notification.

The chat reply box, being free text, now **requires the device to be unlocked**
before it sends. The answer buttons deliberately do not: a choice among offered
options is bounded, which is exactly why it needs no authentication.

### Chat results have their own notification channel
They were sharing the sessions channel, so silencing the chatty one silenced the
blocking one too. A session waiting on you has stopped working until you answer;
a chat that finished is news you asked for. Now tunable separately in Android's
notification settings.

## 2.31.0 — 2026-07-28

### Reply to a finished chat from the notification
A chat finishing used to announce only that it had ("huginn finished: audit the
arr stack"), which raises exactly one question — *and?* — that you had to unlock
the phone to answer. The notification now carries **the answer itself**, and a
**reply box**: type a sentence in the shade and it goes straight to that chat.
The notification then stays, updated with what was sent, rather than vanishing
and leaving no evidence the send worked. Tapping it opens that chat.

### Tapping a notification works while the app is already open
The notifications launch SINGLE_TOP, so a tap on an app that is already running
delivers to `onNewIntent` — which nothing read. Tapping "andrev needs you" did
nothing at all whenever the app happened to be foregrounded, which is exactly
when you are most likely to tap one. Fixed for sessions and chats alike.

### The overnight wake-up cadence was backwards
Measured on the owner's phone, 02:00-06:00: **33 wake-ups**, in the hours with
the least to report. The alarm relaxed to hourly only while a push had arrived
in the last two hours, and a quiet night sends no pushes — so silence was read
as failure and the alarm tightened to ten minutes precisely when nothing was
happening.

Silence is not failure. Only a push that was *sent and never arrived* is, and
the phone cannot tell those apart on its own — so huginn now reports how many
pushes it has sent this install, and the phone compares that against how many
actually landed. Nothing dropped, however long the quiet: stay hourly. Something
dropped: tighten immediately, without waiting out any window.

## appd 2.32.0 — 2026-07-28 (host)

### A short chat could finish without ever telling you
A chat created *and* finished between two observations was absent from the
previous one, so the "don't announce history" rule skipped it — and it never
alerted at all. A one-line question answered in eight seconds is an ordinary
thing to want told about, and it was silently the one thing this could not
report. The watcher now stamps when it last looked, so a chat born since then is
news while one that predates it stays history. Verified live: the same request
that produced nothing now pushes in ~8s.

## appd 2.31.1 — 2026-07-28 (host)

### A finished chat now quotes its answer
The alert carries the last thing Claude said, so the notification and the
Telegram fallback both say what happened rather than only that something did.

**A field that evaporated one layer down.** `chatStates()` grew a `snippet`, the
alert code read it, and it was null every single time: `digest()` rebuilds each
chat from an explicit field list and silently dropped anything not named. Nothing
failed — the value simply disappeared between two correct-looking functions, and
only reading the notification on a real phone showed it. Pinned by a test, along
with the reason `snippet` and `title` stay OUT of the change hash: they are
payload, and hashing them would wake every parked phone to report a rename.

### The watch response reports pushes sent
So the phone can tell a quiet night from a broken delivery path. See 2.31.0.

## appd 2.30.0 — 2026-07-28 (host only, no app update needed)

### Alerts are noticed instantly instead of on a timer
huginn checked every ten seconds whether a session had started waiting on you,
so a question could sit unnoticed for that long before the (sub-100ms) push even
began. It now watches the file the session hook writes, and reacts the moment it
changes. Measured end to end — session starts waiting, phone asleep, app not
running — **176-428ms**, down from ~1.9s. The ten-second check stays underneath
as a floor, since not every alert has a file to watch.

### A pushed alert no longer forgets it was sent
When a push delivered an alert, huginn cleared the marker that prevents the same
alert repeating — so a session flipping in and out of waiting could push every
single time with no rate limit. Delivery by push now counts as delivery.
Verified: flapping a session immediately produced exactly one push.

## 2.29.1 — 2026-07-28

Each arriving push now defers the wake-up alarm directly, instead of waiting for
the next beat to notice push is healthy. On a phone receiving pushes the alarm
may never fire at all.

## 2.29.0 — 2026-07-28

### Snapchat-fast, without a process camped on your battery
Notifications were measured on the phone across every state that matters, timed
from the moment huginn decides to tell you:

| state | delivery |
|---|---|
| app open | 42ms |
| app backgrounded | 21ms |
| app process killed | 86ms |
| screen off | 17ms |
| deep Doze (unplugged, asleep) | 72ms |
| deep Doze **and** process killed | 35ms |
| deep Doze, killed, **and off the battery allowlist** | 34ms |

No foreground service, no persistent connection, nothing kept alive — this is
push doing exactly what it is for.

### The wake-up alarm now stays out of the way
Because push proved that fast even with the app killed and unexempted, the
ten-minute background check was insurance against something that does not need
it — at 144 device wake-ups a day. It now stretches to **hourly while push is
delivering**, and tightens back to ten minutes on its own if pushes stop for a
couple of hours. Same safety net, a sixth of the cost, self-correcting.

### One thing worth knowing
Force-stopping the app (or an aggressive "battery saver" that does it for you)
stops notifications entirely — Android blocks push to force-stopped apps until
they are opened again. Measured: no delivery while force-stopped, back to 56ms
after a single launch. If notifications ever go quiet, opening the app once is
the fix.

## 2.28.1 — 2026-07-28

### The crash behind three releases of voice trouble
Every runtime permission request has been crashing the app since 2.22.0 — the
release that added the biometric lock. Making the main screen a different kind
of Android activity quietly pulled in an old support library whose permission
handling rejects the request codes the modern one generates. The mic tap was
simply the first permission this app had asked for since. Found by attaching a
debugger to the phone and reading the stack trace; fixed by pinning the modern
library.

This also explains the earlier "the voice button does nothing" reports: they
were this crash, and the three fixes before it were treating symptoms of a bug
introduced two releases before the feature existed.

### Dictation can find the recogniser again
The app declared that it looks for the speech *dialog* but never the speech
*service*, so Android answered "no recogniser here" on a phone carrying
Google's. Both are declared now. Verified on-device: the mic opens Listening,
and speech lands in the message box.

### A question notification could lose its buttons
A push carrying a question and its answer buttons could be replaced moments
later by a generic "Waiting for your answer" from the app's own follow-up
check. The push now claims the session first, so nothing re-announces what you
have already been told.

## 2.28.0 — 2026-07-28

### Dictation rebuilt on the path this phone actually has
2.27.1's honest error named the condition: this phone has no app left that
handles the old system speech dialog — modern builds provide speech input as a
background service instead (the same engine voice mode uses). The dictation mic
now hosts its own small "Listening…" dialog on that service path: tap, speak,
and the words land in the message box. The old system dialog remains as a
fallback for devices that still have one, and a phone with neither says so in
the dialog rather than pretending.

The service path needs the microphone permission (the old dialog carried its
own): the first tap asks, and the dictation you asked for starts the moment the
grant lands — no second tap. All three mics — chats, session conversation, and
the terminal composer — use the new path.

## 2.27.1 — 2026-07-28

### The voice buttons stop being silent, for real this time
The report was exact: "it used to open the Google speech to text, now nothing" —
and that named the actual culprit. The dictation mic hid itself behind an
availability check that asks Android whether the speech dialog is *visible to
this app*, not whether it exists; without a `queries` declaration that answer
can be no on a phone where dictation works fine, so the mic vanished and the
new waveform button sat in its place, eating the taps meant for it.

Three fixes, all with the same shape — no silent gates:

- The app now declares the speech-recognition query, so the visibility check
  answers honestly.
- The dictation mic never hides itself again. It always shows, and a launch
  that truly cannot work says so in a toast instead of not existing.
- The waveform button **always opens the voice sheet**. If the microphone
  permission is missing, the ask happens inside the sheet, visibly — with a
  Grant button and a note about what it means if no dialog appears — instead of
  behind an unopened sheet where a blocked request looked like a dead button.

## 2.27.0 — 2026-07-28

### Live typing echoes instantly
Characters you type in Live mode now appear at the cursor the moment you press
them, slightly translucent, and solidify when the pane confirms — perceived echo
drops from a round trip to a frame. The echo is deliberately cowardly about
guessing: it never predicts a line wrap (it clips at the row's end), any key
whose effect it cannot foresee — Enter, arrows, backspacing past what you just
typed — silences it until the next real frame, and every authoritative frame
wins. A wrong guess would be a ghost character floating in a live pane, which is
worse than the latency this hides.

### The voice button always answers
In 2.26.0, tapping the voice button could visibly do nothing: on some builds the
system auto-denies the microphone request without ever showing a dialog, and the
silent version of that reads as a broken button. Now every outcome speaks — a
grant opens the voice sheet immediately (no second tap), a denial explains
itself and points at App info → Permissions, and a missing speech service says
so on the sheet instead of sitting mute.

## 2.26.0 — 2026-07-28

### Voice mode
The waveform button beside a chat's mic opens a hands-free conversation: it
listens, sends what you said, and reads the answer aloud — then listens again,
until you close the sheet. Tap anywhere while it speaks to cut in; a silence
just makes it keep listening; a real mic failure stops the loop instead of
retrying into the same wall.

Answers are translated for ears before they are spoken: code blocks become
"code omitted", links speak their text rather than their targets, tables are
skipped, and a very long answer is cut at a sentence with "the rest is on
screen" — because markdown is for eyes, and a minute of recited punctuation is
nobody's assistant. The full answer always remains in the chat.

Voice mode uses the streaming recognizer, which needs the microphone permission
(asked on first use); declining it leaves the dictation mic working as before,
since that one goes through the system dialog. If a phone has no recognizer or
no speech engine, the loop degrades to silent steps rather than hanging.

## 2.25.0 — 2026-07-28

### Suggestions come to chats
The same next-message chips the sessions conversation got: up to three grounded
suggestions at each turn boundary, filling the composer rather than sending,
stepping aside the moment you type or a new turn starts. Same generation, same
cache — a chat and a session are the same transcript wearing different UIs.

### The top-bar slot earns its keep
The space the settings gear vacated now holds controls for the thing you are
looking at. In a session: rename, fit the pane to the phone, interrupt (Esc),
kill. In a chat: rename, delete. Destructive ones confirm first.

### Settled agents show their conclusions
The work sheet's settled agents used to end on their last tool call ("last:
StructuredOutput" — technically true, useless). The workflow journal records
each agent's own summary of what it concluded, and that is what a settled row
now shows: "Traced the migration; sound." Live agents keep the live line.

## 2.24.0 — 2026-07-28

### Multi-select questions work
When huginn asks a pick-several question, the app now shows real checkboxes:
toggle what you want, then one **Answer** button submits the set. Nothing touches
the session until you answer, so a half-formed selection is never typed into the
pane — and if you had already toggled some options in tmux, the app starts from
that state and the host reconciles the difference rather than blindly re-toggling.

Under the hood the dialog takes a little dance (digits toggle, a review tab,
then submit), learned by driving a real one and watched all the way through: the
end-to-end test pre-toggled an option by hand in tmux, asked the app for a
different set, and the session recorded exactly that set.

Notification buttons stay single-select only — one tap cannot honestly express a
set, so a multi-select question's notification opens the app instead.

## 2.23.0 — 2026-07-28

### Accounts rotate themselves
The reason for keeping several Max accounts, finally automated: when the active
account's binding limit crosses ~95%, huginn switches to the saved account with
the most headroom and notifies you it did — push if the app is reachable,
Telegram otherwise. Decided and executed on the host, so it works with the phone
in a drawer.

It is deliberately conservative: no switch unless a candidate is meaningfully
fresher (not just less dead), a 30-minute cooldown so a misjudgment cannot
oscillate, an account with unknown headroom is never chosen, and running
sessions keep their account until they restart — the same rule manual switching
has always had. The toggle lives in Settings under the saved accounts, with the
last rotation shown beneath it.

## 2.22.1 — 2026-07-27

### Sessions is now the home screen
The app opens on Sessions instead of Chats — watching and steering running
sessions is what this app is for; chats keep their tab one tap away.

## 2.22.0 — 2026-07-27

### The app lock now actually locks
The toggle was on and nothing ever happened — no lock, no prompt. Two compounding
faults: the system-level prompt this was built on fails silently on this device,
and the failure path "failed open", unlocking before the first frame drew. An
invisible security feature is indistinguishable from a missing one. Rebuilt on the
Android library made to absorb exactly these per-device differences, and failure
now fails CLOSED: the lock screen stays, shows the reason, and offers retry.
There is also a **Lock now** button under the toggle — proof on demand, since the
normal trigger (a minute away) makes "is it even on?" unanswerable by looking.

### Dead workflows exorcised, round two
Ghost workflow rows survived in two more places: a finished workflow reporting
"4/4 agents done" still mirrored into the strip as if running (done is done — those
rows are dropped now), and the sessions list preview could headline a dead workflow
forever, because the terminal keeps those rows in its footer and the preview read
the footer. The work sheet also forgets settled agents after 45 minutes instead of
six hours — it answers "what is happening", not "what has happened since lunch".

## 2.21.0 — 2026-07-27

### Question popups actually pop up
When huginn asks a question through Claude Code's question tool, the app used to
show the raw tool call — `AskUserQuestion {"questions":[{"question":"…` — and no
buttons anywhere. The dialog's on-screen shape defeated the prompt detector three
ways at once: each option carries an indented description line, a rule sits inside
the list before the built-in choices, and a help footer sits underneath. All three
are now understood (pinned by tests against a live capture), so the question
appears as tappable buttons in both the Conversation and Screen tabs, answered
through the same fingerprint guard as every other prompt. Verified end to end
against a real dialog: parsed, answered "Blue" from the API, Claude confirmed.

In the transcript, the question renders as a proper card — header, question,
choices — and once answered it shows the chosen reply instead of the choices.
Raw JSON appears nowhere.

## 2.20.0 — 2026-07-27

### Suggested next messages
When a turn ends, the conversation offers up to three tappable suggestions for
your next message — grounded in what was just said, with the first one answering
any question huginn left hanging. Tapping one **fills the composer** rather than
sending: a suggestion is a draft, not a decision. They step aside for a live
permission prompt, disappear the moment you start typing or a new turn starts,
and are generated once per turn boundary by a small, caged model call on the host
(no tools, one turn, none of huginn's own instructions) — cached, so reopening a
conversation costs nothing.

### Stale workflows no longer haunt the strip
The terminal keeps workflow and board rows in its footer after a run finishes —
readable as history there, but mirrored into the work strip they claimed a
workflow was still running when it had long ended. Pane-derived rows now show
only while the session is actually working; background shells and agents are
unaffected, because their liveness is measured rather than read off a screen.

## 2.19.0 — 2026-07-27

### Tap the work strip to see the agents
The strip now opens into a sheet: the TUI's progress rows, the background shells
with elapsed times, and — the point — **each individual agent** behind "0/4 agents
done". Every agent shows whether it is working or settled, which workflow run it
belongs to, the task it was given (in the parent's own words), and the very last
thing it did, read live from its own transcript. Polling for this runs only while
the sheet is open.

### The strip stopped flapping
Two separate causes. The per-tool rows ("Running 2 shell commands · 4s…",
"Searching for 1 pattern…") turn over at tool speed, so the strip grew a line and
lost it again on repeat — they are now a single slot that updates its text in place
and never blinks out mid-turn. And the strip sometimes echoed lines of the
conversation itself: the TUI marks ordinary messages with a bullet the parser was
reading as a progress glyph, and old scroll text looked like status. Progress rows
now match only the real progress glyphs, and only in the bottom of the pane where
the status area actually lives.

### Sends that sat in the composer
Rarely, a message sent from the conversation tab landed in Claude's input box
without submitting — the text and the Enter arrived in one burst, which the TUI
occasionally reads as a paste, inserting the newline instead of submitting. huginn
now puts a beat between the text and the Enter, making it a distinct keypress
every time.

### Composer padding, take three
A couple of pixels of breathing room under the entry bubble (6dp) — the previous
fix removed the doubled inset but took the gap to nothing.

## 2.18.0 — 2026-07-27

### Background work is visible everywhere it was invisible
A session blocked on a twenty-minute build looked stalled from the conversation and
the list, with the truth only on the tmux screen. Now:

- **The sessions list says "background work"** (with a pulsing dot) instead of
  "waiting", and shows the longest-running command — "⚙ npm run build · 14m" — plus
  a count of any other shells and agents.
- **The conversation work strip stays up while background work runs**, even after
  the turn ends, listing each background shell with its elapsed time.
- **Workflows and fan-outs now show the way Claude Code itself shows them.** The
  strip lifts the TUI's own progress rows verbatim: "Waiting for 1 dynamic workflow
  to finish", "wave-3 · 0/4 agents done · 7m 39s · ↓ 562k tokens", "Running 2
  agents". The earlier version missed these entirely — the workflow-wait status has
  no "…" and lived outside the line the parser matched.

Detection is exact rather than guessed: a background shell counts only while a
process in that session still holds its output open, and only when the transcript
itself called that task background — a foreground command writes to the same place,
and counting it would show you the thing you were already watching.

### A killed session closes its own view
If a session exits while you are inside it, the app returns to the sessions list
with a note, instead of leaving you on a dead screen.

### The doubled gap under the composer is gone
The layout reserved the navigation-bar height twice — once by the scaffold, once by
the composer — which read as a thick dead band on phones with button navigation.
One owner per inset now.

### Also
Live typing coalesces faster (15ms window), and each background-task fact above
comes from a live capture rather than assumption: the wrapper argv, the workflow
rows, and the foreground-task trap were all measured on running sessions.

## 2.17.0 — 2026-07-27

### The conversation shows what Claude is doing, live
Right after sending a message the conversation went silent while Claude did its
opening thinking — all the life was on the Screen tab. A **work strip** now sits
above the composer showing exactly what Claude Code itself shows: "Gallivanting… ·
3m 15s · ↓ 10.0k tokens", read live off the pane's own status line. When that line
is not on screen, the strip falls back to what the transcript says is in flight —
the running tool and its argument, plus "· N subagents" while a fan-out or workflow
is underway. Tapping the strip jumps to the newest content.

### Live typing is faster, and now provably in order
Two changes. Keystrokes used to each ride their own request, fired independently —
which was not just slow but unordered: typed fast enough, "ls" could arrive "sl".
They now go through a single ordered queue that merges each burst into one request.
And the screen poll re-checks the pane every 130ms right after activity (decaying
to 450ms when idle) instead of a flat 700ms, so the echo of what you type comes
back at roughly keystroke speed.

### Unfolded, the open chat or session gets the width
The list pane narrowed from 348dp to 292dp — it is a picker, not a reading
surface — so the conversation or terminal alongside it takes the difference.

### Less dead space under the composer
The entry bubble's bottom padding no longer stacks on top of the system bar inset.

## 2.16.0 — 2026-07-27

### Following now locks on
Auto-scroll was still failing in sessions: the "new messages" pill appeared where a
scroll should have happened. The cause was structural — the app asked "are they at
the bottom?" at the moment content arrived, but by then the new content was already
laid out, so a reader who WAS at the bottom measured as scrolled-up. Following is now
a **latch**: reaching the bottom by any route locks it on, and only your own finger
dragging away breaks it. Scroll back down and it re-engages. Programmatic scrolling
can never be mistaken for you leaving, because it never involves a finger.

### Type straight into the terminal
The Screen view has a **Live** toggle on the key bar: the keyboard types into the
tmux pane keystroke by keystroke — backspace, Enter, pastes — instead of composing
in a bubble below. Arrow keys, Tab and Esc from a hardware keyboard go through too.
The compose bubble remains the default, because premeditated input is still most
input.

### Voice
Every composer — chats, session conversation, terminal — has a mic. It uses the
system speech dialog and appends what you said to the draft, so dictating a prompt
while walking works the way it does in any messaging app.

### Subagents and workflows are now visible units
A fan-out used to drown the conversation in interleaved subagent chatter with no way
to see any single delegated task as a whole. Consecutive subagent activity now folds
into one **Subagent** card: closed, it shows the task (in the parent's own words) and
a step count; open, the full play-by-play — thinking, tools, results — rendered like
the main thread. Workflow tool calls keep their named cards. Both conversation
surfaces, sessions and chats, share the rendering.

### Unfolded, the app becomes two panes
On a Z Fold's inner screen (or any window ≥700dp) the bottom bar becomes a rail and
the list screens become list-plus-detail: chats on the left, the open chat on the
right, same for sessions, with the selected row highlighted. Status and Settings keep
a readable measure instead of stretching. Folded, everything is exactly as before.

### Lock the app
Settings → Security can require fingerprint, face or the device PIN to open the app
after it has been away for a minute. Huginn is a hand on the homelab; an unlocked
phone handed to someone should not include it. Quick hops to another app and back do
not re-prompt. The toggle is disabled (and says why) on a phone with no screen lock.

### Fixed: "Fit anyway" kept coming back
Forcing a resize worked, and then the banner returned on the next poll anyway —
huginn kept reporting "blocked" merely because a client was attached, even though the
pane already fit. Blocked now means a resize is actually NEEDED and refused, so the
banner stays gone until the geometry genuinely diverges again.

### Chats wear their state
Working chats pulse and say "working"; queued messages show amber with a count; idle
chats show when they last spoke. The settings gear is gone from inside chats and
sessions — that slot is reserved for per-session controls to come.

## 2.15.0 — 2026-07-27

### Answer a session from the notification
A "needs you" alert used to say only that — something was waiting, with no hint what,
so the only possible response was to unlock the phone, find the app, find the session
and read it there. The question is right in the pane and the app already turns it into
buttons once you are inside; now it travels with the alert.

**The notification carries the question and its options as tappable buttons.** Tap
"2. No, tell Claude what to do differently" on the lock screen and that is what the
session receives. Both paths do it — a push, and the background check that finds a
transition on its own — so what you see does not depend on which noticed first.

### An answer cannot land on the wrong question
Worth spelling out, because this is where the feature could have done real damage. By
the time you tap, the session may have been answered in tmux, moved to a different
question, or gone back to an idle composer. Sending the digit regardless would type it
into whatever is there now, and in a Claude Code pane that is not a harmless
keystroke — it could accept a **different** prompt you never saw.

So each answer carries a fingerprint of the question it was offered for, and **huginn
refuses it if the pane no longer shows that same question.** The check happens on the
host in the same request, because nothing on the phone can hold the pane still between
reading it and typing into it. Moving the selection highlight does *not* invalidate an
answer — that changes nothing about what is being asked.

A tap always tells you what happened: the answer that was sent, or why it was refused.
An action button that silently does nothing is worse than no button.

### Also
Telegram now reports the question too, as a statement — "Asked: …" with the options
listed — so the fallback channel is as informative as the push. Two sessions waiting at
once no longer overwrite each other's notification, which previously meant the first
one's buttons silently vanished.

## 2.14.1 — 2026-07-27 (server-side only)

### Fixed: a push that worked left no trace
2.14.0 logged and counted push FAILURES but not successes, so two test pushes that
had genuinely been delivered showed "0 delivered" in Settings and produced no log
line — the only evidence was a 200 buried in the request log. For a feature whose
entire point is arriving while nobody is watching it arrive, "it worked" has to be at
least as visible as "it did not".

huginn now logs each delivery with the device it reached, keeps a running total, and
records when each phone was last pushed to. The total survives a handset being
pruned, since it is a record of what the host managed to deliver. No app update
needed — the count Settings was already trying to show now has something behind it.

## 2.14.0 — 2026-07-27

### Real push: huginn now reaches this phone in seconds, asleep or not
Firebase Cloud Messaging is wired up. High-priority FCM is the one transport Google
delivers straight into Doze, so an alert no longer waits for the ten-minute background
check — it arrives about as fast as any message on your phone.

The other two paths stay exactly as they were, because all three fail differently.
FCM needs Play Services, a network, and an app that has not been force-stopped. The
alarm needs none of those and gets there within ten minutes. Telegram needs neither
the app nor the phone to be reachable at all. **Push first, alarm underneath, Telegram
when nothing else got through.**

Settings → Background delivery now leads with whether push is working, and separates
two things worth separating: whether **huginn** can send, and whether **this phone**
has registered to receive. Those fail for different reasons, and a single "push: on"
would hide the second. There is a **Send a test push** button that goes the whole way
through Google rather than faking the last step.

### Telegram now steps aside on real delivery, not a guess
Previously the fallback was decided by whether the phone had checked in recently.
Now, when FCM accepts a message, that is evidence the app was actually reached, and
Telegram holds back on that basis instead. Set alerts to "always" in Settings if you
would rather have both regardless.

### Details worth knowing
Registration tokens are stored per installation rather than per token, because
Firebase reissues them after a reinstall or restore — keyed the other way, every
reinstall would leave a dead token behind to be retried forever. A token FCM reports
as **dead** is forgotten; any other failure is only counted, so an outage or a lapsed
credential can never quietly unregister a working phone.

Messages are data-only by design. A `notification` payload would be drawn by the
system without consulting the app, which is simpler but means the app never learns it
has already told you — and the ten-minute alarm would then repeat the same alert
later. This way one place decides what you have already seen.

## 2.13.0 — 2026-07-27

### Notifications that survive a sleeping phone
The honest problem with 2.12.0: the app's own notifications worked all day and
then stopped overnight. **Android's Doze mode was the cause**, and it defeats both
mechanisms the app had. WorkManager's periodic check is *deferred* while the device
is idle — it does not run and then catch up, it waits for the screen to come on. The
foreground service keeps its process alive but loses network access, so its
connection dies and every retry fails silently. Nothing reported a fault; it simply
went quiet. (The same is true of devstore's six-hourly update check, for the same
reason.)

Three things changed, each fixing a different way it failed:

**A background check that fires while the phone is asleep.** Ten-minute alarms of
the one kind Doze honours, independent of the service, so they keep working when it
has been killed — and they restart it when they find it gone.

**Battery optimisation is now surfaced, and askable.** Without an allowlist entry
Android suspends this app's network while idle, so the check still fires but reaches
nothing — a failure that looks exactly like no check at all. Settings now says
plainly whether the app is exempt, with a button to ask. **This is the single
setting most likely to be why notifications were not arriving.**

**Silence now means failure.** The watching connection used to be a long poll,
where a socket killed by a network change was indistinguishable from one patiently
waiting — so the app went on believing it was watching for hours. huginn now sends
a keepalive every 25 seconds and the app gives up after 60, reconnecting at once
rather than after a two-minute backoff, and immediately when the network returns.

### Telegram is now a fallback rather than a duplicate
huginn records when your phone last checked in, so it can tell whether the app is
reachable. When it is, huginn stays quiet and lets the app notify you; when it is
not, Telegram carries the alert. Two notifications for one event teaches you to
dismiss both without reading, and then the one that mattered is gone too. Set it
back to "always" in Settings if you would rather have both.

### Background delivery, in Settings
A new section answers "is this actually working?" without guesswork: whether the app
is exempt from battery optimisation, when it last reached huginn, when the
background check last ran, and — from the host, which never sleeps — **when huginn
last heard from this phone and how many times it has checked in.** That last figure
is the one worth trusting. Lock the phone, leave it half an hour, and it will tell
you whether the vigil held.

### Fixed: short chats finished without telling you
A chat that started and finished between two observations was never seen running, so
its finish was never reported — measured on a run that took five seconds against a
ten-second check. Completed runs are now counted rather than inferred from an edge,
so a finish cannot fall through a gap. This also covers back-to-back runs, where a
queued message restarts a chat and it never appears to stop.

### Fixed: transitions during a gap were absorbed instead of reported
The comparison baseline was rebuilt from scratch each time the watcher started, so
anything that changed while it was down was quietly accepted as "how things have
always been" — and the watcher is most likely to have been killed exactly while the
phone was asleep. The baseline now persists, so a restart compares rather than
forgets. Installing a new version of the app also used to cancel its alarms
silently; it now re-arms itself.

### Still true
Real FCM push — the kind Google delivers straight to a sleeping phone — needs a
Firebase project created under your own Google account. Everything above is what
can be built without one, and it is close: the practical difference is seconds
versus up to ten minutes when the phone is deeply asleep.

## 2.12.0 — 2026-07-27

### Push notifications that arrive with the app closed
**huginn now messages you itself.** Turn on "Message me when a session needs me"
in Settings and the host watches its own state and reaches out over the Telegram
path this homelab already uses — so an alert arrives when your phone has been in a
pocket for two hours, with the app not running and no battery cost.

That is the difference from what shipped before: watching continuously is instant
but only lives as long as the app does, and the periodic check needs the app too.
Only the host can notice something while the phone is asleep.

It is deliberately quiet. It alerts on the **transition** into needing an answer,
never on a session that has simply been waiting a while; it stays silent about
whatever was already true when you switched it on; the same subject will not
repeat inside half an hour; and a send that fails is retried rather than swallowed.
A chat finishing is announced by name. There is a **Send a test** button, and
every message is logged on the host like every other alert it sends.

Real push to the app itself needs a Firebase project, which only you can create
under your own Google account — say the word and I will wire it up. Until then
this reaches the same phone by a route that already works.

## 2.11.1 — 2026-07-27

### Switching accounts now moves the identity too
With three accounts saved, switching swapped the credentials correctly but Claude
Code kept **naming the account you left**. Its identity — the email, org and rate
tier that `claude auth status` reports — lives in a different file from the
credentials, so moving the tokens alone left the two disagreeing.

The identity now travels with the tokens. Where a saved account has no identity
recorded yet, the stale one is **removed** rather than left in place, because
Claude Code re-derives it from the token the next time it runs, and no answer is
better than a confidently wrong one. Meanwhile the app reports the account by
asking the credentials themselves, so it never shows "not signed in" on a host
that is signed in.

Verified across all three accounts: each switch reports the right account
immediately, in the app and from the command line, and switching back restores
exactly what was there before.

## 2.11.0 — 2026-07-27

### Adding a second and third account actually adds them
The reason a second sign-in kept producing the account you already had: **the
authorize page uses whichever account your browser is already signed into.** Click
through it while signed in as one account and it authorizes that one, whatever you
intended. Nothing in the app could tell, so it saved what it was given and showed
you two rows that were one account.

Adding an account now asks which one you are adding, and:

- aims the sign-in page at that account,
- says up front that the browser's current session decides it, and that a private
  window or signing out of claude.ai is how to be sure,
- **asks the new token who it belongs to** and tells you the answer — so a
  duplicate or the wrong account is stated at the moment it happens, with a **Try
  again** button, rather than turning up later as a row that looks like an account
  you do not have.

Once three genuinely different accounts are saved, each row shows its own plan and
how much of its own week it has used, and switching picks the one with headroom.

## 2.10.1 — 2026-07-27

### Accounts are named by their own credentials
A saved account was labelled with whatever `claude auth status` reported at the
time, which describes the login that is **active** — so a profile could be filed
under the wrong person whenever those two reads disagreed. Nothing was lost (a
profile is keyed by its credentials, since 2.8.0), but the name could be wrong,
and a wrong name here is not cosmetic: it made the per-account headroom figures
describe an account other than the one named.

Each profile's name now comes from asking its own token who it belongs to, so it
cannot disagree with the credentials it labels.

That immediately surfaced something worth knowing on this host: **two saved
profiles were the same Claude account signed in twice.** They share one usage
limit, so switching between them gains nothing — which is the opposite of the
point. Settings now says so plainly instead of showing two rows that look like
two accounts, and marks any name it could not confirm.

## 2.10.0 — 2026-07-27

### Alerts in seconds, not fifteen minutes
**Watch continuously** in Settings keeps a live connection to huginn, so a session
waiting on an answer reaches you in seconds. Previously the only option was a
periodic check on Android's fifteen-minute floor, which is fine for "the disk is
filling" and useless for "Claude is asking you something".

The connection is a single request the host holds open until something an alert
depends on actually changes, so idling costs one parked connection rather than a
poll loop. What counts as a change is deliberately narrow: a session's state, a
chat starting or finishing, a message joining a queue. Panes repainting, token
counts and titles do **not** wake your phone.

Android requires an ongoing notification while this runs. That is the honest cost,
so it is made to earn its place: it shows what huginn is doing right now, sits on
the quietest channel the system allows, and carries a **Stop watching** action.
Watching resumes after a reboot, and turning it off restores the periodic check.

### Signing in happens in the app
Adding an account no longer sends you into a terminal session to paste a code. The
sign-in page opens in your browser and the code goes into a field in the app,
which reports what happened — including quoting the reason if the code is refused,
rather than whatever fragment the pane happened to end on.

## 2.9.0 — 2026-07-27

### Sending to a busy chat queues instead of failing
Typing into a chat that was still working used to dead-end with an error, while
the same thing typed into a session simply queued. Chats now behave the same way:
the message is held, shown in the conversation as **queued**, and delivered when
the current turn ends. Several waiting messages are delivered together as one
turn, which is what the interactive composer does. Pressing stop drops what is
waiting, because stop should not launch the next thing.

Messages queued before the daemon restarts are delivered on startup rather than
sitting unanswered.

### Sessions and chats are ordered by real recency
Sessions were sorted on tmux's session activity, which does not move when a pane
produces output — on sessions that had been busy for hours it read **eight hours
stale**, so the order was effectively frozen. They now sort on window activity,
which tracks output. Chats were already newest-first.

### Interrupt a session from the conversation
With nothing typed, a working session offers a stop button that sends Esc, the
same interrupt as at the keyboard. Previously that meant switching to the Screen
tab to find the key.

### A notification when a chat finishes
The background check now also notices a chat that was running and is not any
more, so a long answer you walked away from tells you it is done.

## 2.8.1 — 2026-07-27

### The model control names the version
"Opus" is no longer enough to know what you are talking to, since Claude Code can
be running Opus 5 or Opus 4.8. The control now reads **Opus 5**, **Opus 4.8**,
**Fable 5**, **Sonnet 4.6** and so on, everywhere the model appears.

The version was never actually missing: the pane's status line already says
"Opus 5" and the transcript carries a full model id. The label was collapsing both
to a family name. Ids are now formatted with their version by the host, so there
is one implementation of that rule rather than one per surface.

### And you can pick a specific version
The model menu is **discovered from the installed CLI** rather than hardcoded, so
it lists what that copy of Claude Code actually offers — currently Fable 5,
Opus 5, Opus 4.8, Sonnet 5, Sonnet 4.6, Haiku 4.5 — and follows a `claude update`
instead of going stale. Dated snapshots, `-fast` and `-v1` variants are left out
so the menu stays a menu, and a named variant cannot appear as a second,
indistinguishable entry. If discovery ever finds nothing, the family aliases,
which always work, are used instead.

## 2.8.0 — 2026-07-27

### Fixed: adding a second account could lose the first one
This one lost real data and is worth explaining. Saved logins were filed under
the email that `claude auth status` reported, while the credentials themselves
came from a separate read of the credentials file. Those are two different reads
of two different things, and any skew between them filed one account's secrets
under another account's name — which does not merely mislabel it, it **overwrites
that account's saved copy**. On this host it happened within minutes: two saved
profiles ended up holding the same login.

A profile is now identified by a fingerprint of its own credentials, so a wrong
label can only ever be a wrong label. Two logins cannot collide, and the label
corrects itself the next time that account is made active, when the host can be
asked authoritatively who it is. Existing profiles are migrated on startup and
duplicates left by the old scheme are collapsed.

**If an account has gone missing from your list, sign in to it once more** and it
will come back. Its stored copy was overwritten before this fix; nothing else was
affected, and the account itself was never touched.

### Adding and switching accounts is smoother
- The account you are using is snapshotted **before** a sign-in starts, so adding
  an account can no longer cost you the one you were on.
- Completing a sign-in retires the temporary `login` session by itself, instead of
  leaving it in your sessions list waiting at a prompt. A sign-in still in
  progress is left alone.
- Settings lists every account with the one in use marked, rather than hiding it,
  and the account in use cannot be forgotten out from under itself.
- Each row still shows how much of the week that account has used, so the choice
  is about headroom rather than guesswork.

## 2.7.1 — 2026-07-27

### Fixed: the effort control never showed the effort
It was meant to read as its current value all along, like the model and mode
chips do. Two things stopped it:

- A tail read of the transcript only reports fields whose records happen to fall
  inside it, and the app carried forward the model, mode and title but **not the
  effort** — so a couple of seconds after opening a session it reverted to null
  and the chip fell back to the word "Effort". Every session-level field is now
  carried forward, including cwd and last-activity.
- Effort was only picked up when an assistant turn stamped it, so a change made
  mid-turn would not show until that turn finished. `/effort` and `/model` are
  now read from the command itself, so the control reflects the change straight
  away, and a later turn still wins if it disagrees.

## 2.7.0 — 2026-07-27

Refinement pass.

- **Chats pick a model and effort too**, not just sessions, from a bar at the top
  of the chat. These apply to the next turn; a turn already running keeps what it
  started with, because the flags are fixed when it launches. Values outside the
  documented aliases and levels are rejected rather than passed to a spawn.
- **Chats are named by Claude**, using the same generated title a session gets,
  instead of the first sixty characters of whatever you typed.
- **The sessions list stays live** while you are looking at it, rather than
  waiting for a manual refresh.
- **The Screen tab tells the truth about scrollback.** It can now load pane
  history where history exists, and where it does not it says so: Claude Code
  runs on the terminal's alternate screen, which keeps no scrollback at all
  (every Claude pane reports zero history, while a shell pane reports hundreds).
  A "load earlier output" button there would have been a button that does
  nothing, so instead it points at the Conversation tab, which has the whole
  session.

## 2.6.0 — 2026-07-27

### The conversation follows new messages
It opened on the newest message but then stopped following, for four separate
reasons, all now fixed:

- The retained event window is **capped**, so on a long session the event count
  stops changing — and the follower was keyed on that count, so it went quiet
  exactly where it was needed most. It now keys on the transcript's byte offset,
  which keeps advancing.
- A **streaming** answer grows without adding an item, so token-by-token arrival
  moved nothing. It follows the text now.
- The item count ignored the "showing the most recent part" header, so it always
  aimed one item short of the end.
- "Near the bottom" was judged by item index against stale layout, which counted
  a message taller than the screen as at-the-bottom while you sat at its top.
  It is now measured from real geometry, and following scrolls to the end of the
  content rather than the top of the last message.

Scrolling up to read something older still stops the follow, as it should — and a
**New messages** button now appears when something arrives while you are up
there, so "not following" can never be mistaken for "nothing happened".

## 2.5.0 — 2026-07-27

### Fixed: slash commands looked like garbled messages you sent
Running a command like `/model fable` writes three separate records: a caveat
aimed at the model, the command wrapped in tags, and the command's output. All
three were rendered as things you had said, tag markup and all. Now the caveat is
dropped, the command shows as `/model fable`, and its output shows as a short
note with terminal colour codes stripped. A record that mixes plumbing with real
text keeps the real text, so a message can never be swallowed.

### Fixed: the model button did nothing visible
The model and mode came from the transcript, which reports what the **last
completed turn** used — so after changing the model, the control kept showing the
old one until the next turn happened. Both are now read from the session's status
line, which is current, and the change also appears in the conversation as the
command that ran.

### Notifications you can actually verify
- Settings now shows whether **Android** is allowing notifications, which is a
  separate question from whether the app wants to send them and the usual reason
  none arrive.
- **Allow** requests the permission, **System settings** opens the right page, and
  **Send a test** posts one through the same code path the real alerts use.
- The permission state is re-checked whenever the app comes back to the
  foreground, so turning it on in system settings is reflected immediately.

## 2.4.0 — 2026-07-27

### Multiple accounts, switchable when a plan runs out
- Settings lists every Claude login saved on huginn and switches between them
  with one tap. Each row shows its plan and **how much of the week it has used**,
  which is the thing you are actually deciding on.
- The account currently signed in is saved automatically, so an account you have
  used is always there to come back to. "Add account" runs the normal sign-in
  flow; "Forget" removes a saved copy from huginn without signing it out anywhere.
- Switching changes the login for the whole host. **Sessions already running keep
  the old account until they restart**; new runs use the new one. The app says so
  rather than implying otherwise, because Claude Code holds its token in memory
  and there is no way to move a live session.
- The account being left is snapshotted immediately before every switch, so a
  token refreshed since it was last saved cannot strand it.
- Saved credentials live beside the daemon's own data, 0600 inside a 0700
  directory, and are never sent to the phone: the app receives emails, plans and
  percentages only.

## 2.3.0 — 2026-07-27

### Fixed: follow-up messages vanished from a conversation
Sending a second message while Claude was still working showed nothing in the
Conversation tab, though it was plainly there in the Screen tab. A message typed
mid-turn is **queued** by Claude Code, and a queued message is written to the
transcript only as a queue record — it never becomes an ordinary message record,
even after it is delivered. The reader dropped those, so every follow-up was
invisible. They now appear, marked **queued** until they are picked up.

Machine text that Claude Code injects (background-task notifications, system
reminders) is shown as a one-line note instead of a message bubble, since it is
input to the model but nobody said it.

### Model, effort and mode, from the session
- A control bar on both session tabs sets the **model** and **effort** level, and
  cycles the **permission mode**.
- The current values are read back from the session's own transcript, so the bar
  shows what the session is actually on, not what the app last asked for.
- These send the same `/model` and `/effort` commands and the same Shift+Tab you
  would type by hand. It is a shortcut for keys, not a separate control channel.

### Moved
- **Plan usage and token counts now live on the Status tab**, with the rest of
  huginn's live state. Settings keeps the account.

## 2.2.0 — 2026-07-27

### Code is coloured now
- Code blocks in a conversation are syntax highlighted, and so are the commands
  on tool cards, which is where most of the code you actually read lives.
- Shell, Kotlin/Java/JS/Python-ish, JSON, config and **diffs** — a diff colours
  whole lines by their sign, so an Edit's result reads at a glance.
- Five restrained hues that sit inside the app's palette rather than a rainbow,
  with separate light and dark sets. The highlighter is a lexer, so a missed
  keyword only ever costs a colour: the text always renders whole.

### Plan usage, the same numbers as `/usage`
- Settings now shows the real plan utilization Claude reports, not just token
  volume: **current session**, **current week (all models)** and **current week
  (per model)**, each with a bar and a "resets in 3h 12m" countdown.
- The bar colours by headroom, since this is the number that actually stops work.
- Extra-usage credits appear only when that feature is switched on for the
  account, so a disabled 100%-used counter cannot alarm you for no reason.
- Read on the host with its own credentials; the app is handed percentages and
  reset times only, never a token.
- Token counts (exact) and the list-price dollar estimate (labelled) are still
  there, below the plan limits, under "Tokens".

## 2.1.0 — 2026-07-27

From using it: two fixes you asked for, and account + usage in Settings.

### Fixed
- **A conversation opens at the newest message.** It was landing at the very
  first message of the session. The auto-follow rule could never fire on a cold
  open, because with nothing laid out yet it read your position as "scrolled to
  the top" and decided not to move. It now jumps to the newest message on open,
  then follows new arrivals only while you are already at the bottom.
- **An unsent message stays put.** Type into a session or chat, navigate away,
  come back, and the text is still there — including after the app is killed.
  Drafts are kept per session and per chat, and the session's Conversation and
  Screen tabs share one draft, since they send to the same place.

### Claude account, in Settings
- Shows the account huginn is signed in as, its plan and org.
- **Sign in / switch account** starts the real interactive flow in a session
  called `login` and hands the sign-in URL straight to your browser (the pane
  hard-wraps that 450-character URL, which is impossible to copy on a phone).
  Paste the code back in the Screen tab.
- **Sign out** is there too, behind a confirmation that says what it really
  does: it signs out the whole host, so every session and every scheduled job
  stops working until someone signs back in.

### Usage, in Settings
- Tokens for today and the last 7 days, with the cache-read share.
- Token counts are exact. Dollar figures are list-price estimates that run high
  on a Max plan, and are labelled as a trend rather than a bill.
- Computed on the host and cached: it walks every transcript and takes about
  half a minute, so the app shows the cached number and refreshes behind it.

## 2.0.1 — 2026-07-27

Fixes from a review of the 2.0.0 changes, most of them in the pane-resize path.

- **A leased pane could be stranded at phone size permanently.** `window-size`
  is a per-window option, and the lease targeted "the session's current window".
  Opening or switching to another window (prefix+c) after a lease was taken left
  the original window at `manual` with every release path — expiry, leaving the
  screen, shutdown, and the startup sweep — silently missing it, because
  `list-sessions` only reports each session's *active* window. Leases now record
  the concrete window id and act on it, and the sweep enumerates every window.
  This was the exact failure the lease design existed to prevent.
- **Opening any session resized it**, even when the Screen tab was never opened,
  because the last session's geometry was never cleared.
- **"Fit anyway" was sticky.** It kept re-sending on every poll, renewing the
  lease indefinitely, so its 90-second expiry could never fire. It is now a
  single shot.
- **Polling no longer runs while the app is backgrounded**, so a backgrounded app
  can no longer hold a laptop's window at phone geometry.
- **Permission-prompt detection no longer fires on an ordinary numbered list.**
  An assistant answer ending in "1. … 2. … 3. …" matched every rule and produced
  fake buttons, and tapping one typed a digit into Claude's composer. Detection
  now requires the live selection caret and refuses a run with the composer
  below it.
- **The parked long poll costs far less on huginn**: one tmux process per tick
  instead of three, at a slower tick, measured against an idle baseline.
- **A dead socket no longer freezes the screen forever** — screen polls have a
  bounded read and call timeout, so the retry/backoff path can actually run.
- Transcript events are capped in memory instead of growing without limit, and a
  non-numeric `offset` is rejected rather than returning an undecodable response.

## 2.0.0 — 2026-07-27

A development pass on how sessions are shown. v1 rendered a tmux session by
scraping its screen; v2 reads the session's actual Claude Code transcript, and
keeps the pane for the things only a live pane can do.

### Sessions are a conversation now, not a screenshot

- Each session opens on **Conversation**, built from that session's own Claude
  Code transcript: assistant text, **thinking** (collapsed, expandable),
  tool calls folded together with their results, **subagent** output marked and
  indented, and **workflow** runs labelled with the workflow's name.
- The session list leads with Claude Code's **own generated title** for each
  session, plus the last couple of meaningful pane lines, so you can tell four
  sessions apart without opening any of them.
- Sessions can be renamed from the app.

### The Screen tab is a real character grid

- The pane is parsed into cells and drawn at exact cell metrics, so the glyphs
  Claude Code's interface is built from (box drawing, `●`, `❯`, `⏵⏵`, emoji) can
  no longer shift the columns after them. Borders line up.
- **The pane is resized to your phone.** With no client attached, tmux keeps a
  window at the size it was created (80x24), so v1 showed a laptop-shaped layout
  through a keyhole. The app now reports the geometry it can actually display and
  the server resizes the tmux window to match, so Claude Code re-wraps its own
  output to fit the phone. The resize is a lease that expires and is released on
  exit, on a crash, and on daemon restart, so a laptop attaching later is never
  left with a shrunken window. If another client is attached the resize is
  refused, with a "Fit anyway" override. (2.0.0 had a hole in this; see 2.0.1.)
- Pinch to zoom; the column count follows the text size.
- OSC 8 hyperlinks (which Claude Code wraps around file paths) are consumed
  instead of dumping raw URLs into the text.
- A block cursor, drawn hollow so it never hides the character under it.

### Permission prompts are buttons

- When a session asks a numbered question ("Do you want to proceed?"), the app
  detects it and offers the options as **tappable buttons**, in both the
  Conversation and Screen tabs. Answering no longer means hunting for a digit on
  a soft keyboard while the interface redraws.

### Notifications

- An optional background check notices when a session starts **waiting on you**
  and posts a notification; tapping it opens that session. It fires on the
  transition, not repeatedly, and needs the phone to be on the tailnet.

### Chats

- Answers render as **markdown**: real code blocks with a copy button, inline
  code, headings, lists and quotes, instead of one flat run of text.
- Chat history now comes from the transcript too, so a chat also shows thinking
  and tool results, not just the final answer.

### Under the hood

- The screen endpoint **long-polls**: the server holds the request until the
  screen actually changes. An idle session costs one parked connection instead of
  a capture every second, and a busy one updates as soon as it changes.
- Session state files now carry the Claude session id and transcript path,
  written by the `huginn-claude-title` hook.
- 71 automated tests (47 Kotlin, 24 for the daemon), including tests that decode
  real captured daemon responses so a wire-format change cannot pass unnoticed.

Same signing key as 1.0.0, so this updates in place.

## 1.0.0 — 2026-07-27

First release. Replaces driving huginn from a tmux session in Termux.

### Chats
- Headless Claude Code conversations that run on huginn in `~/netplan`, streamed
  token by token over SSE.
- Two modes, chosen per chat: **Ask** has reasoning and MemPalace memory but no
  tools (the CLI's `huginn -p`), **Act** also reads and writes files, runs
  commands and fetches the web (`huginn -y`).
- Transcripts and Claude session IDs live on the server, so a chat resumes with
  full context and survives the app being killed. Leaving a chat detaches the
  stream but never cancels the turn.
- Cost and duration are shown per turn.

### Sessions
- The real tmux sessions on huginn, the same list `huginn ls` prints.
- Each row carries the live state the Claude hooks record: working, needs you, or
  waiting.
- Create a session (opens Claude Code in `~/netplan`, exactly like `cc`) or end one.

### Terminal
- Renders the pane with colour, and a key row for what a phone keyboard cannot
  send: Esc, Shift+Tab, arrows, Ctrl-C/D/L/R, PgUp/PgDn.

### Status
- Host health: uptime, load, disk, Claude Code version, MemPalace reachability.

### Notes
- Tailnet only. `huginn-appd` binds huginn's Tailscale address and every request
  needs the bearer token from `/etc/huginn-appd/token`.
