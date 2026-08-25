# Huginn Desktop changelog

## 0.8.14

- **Forget now asks first**, as the phone already did. It sat directly beside
  "Ask here" and "Act here" and fired immediately — and what it does is easy to
  misread: the machine comes back with the same id under a minute later, because
  a runner that is still running simply re-enrols. So the button looked like it
  had done nothing, when what it had actually done was kill whatever that device
  was running. Same words as the phone's dialog, deliberately.
- A device that has not asked for work since huginn restarted reads "free? not
  asked for work since huginn restarted" rather than "idle".

## 0.8.13

- **"Mark done" on a round that has reported something** — the status mark goes
  quiet and the line reads "read", with the report untouched and still readable.
  Undo puts it back. Remembered against that run, so the next report arrives
  unanswered.

## 0.8.12

- A round that reports more findings than the daemon keeps now says both numbers
  — "500 items, showing 20" — rather than showing the kept count as the total.

The Compose Multiplatform desktop client, versioned separately from the phone
app and from the Electron desktop client. Its releases go to `/v1/desktop-kt`;
the Electron client's go to `/v1/desktop` and the two never mix — see
`scripts/release-desktop.sh`.

<!-- ───────────────────────────────────────────────────────────────────────────
     UNRELEASED — merge this into the next version's section.

     `release-desktop.sh` greps for `^## <version>$` and REFUSES to build without
     it, so this heading cannot ship as-is: rename it to the version being cut
     (and fold these notes in with whatever else that release carries). That
     refusal is deliberate — it is what stops a release going out with no notes.
     ─────────────────────────────────────────────────────────────────────────── -->

## 0.8.11

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

## 0.8.10

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

## 0.8.9

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

## 0.8.8

### Fixed
- **A question no longer covers the terminal or the controls.** 0.8.7 stopped the prompt card
  resizing the real terminal by floating it over the pane — which traded one problem for another:
  on the Screen tab it sat on top of the output and the controls underneath it.
  The card is back below the pane where it blocks nothing, and the viewport genuinely shrinks to
  make room. What makes that safe is the distinction the last two releases kept missing: **a
  prompt may take LAYOUT space, it may not change the REPORTED geometry.** The geometry report is
  now held while a question is up, so tmux never hears about the shrink — the terminal keeps its
  own shape and scrolls inside the shorter window instead of re-wrapping.

### Changed
- **Dropped "(Compose)" from the app name.** It is now just "Huginn Desktop" in the Start Menu,
  the uninstall entry, the installer, and the diagnostics header. The qualifier only ever existed
  to tell this client apart from the Electron one, which is gone.
  The rename is DISPLAY ONLY: the install directory and the uninstall registry key both key off
  `APP_ID` (`huginn-desktop-kt`), and the toast AUMID is untouched, so an upgrade cannot land
  beside the old copy, orphan its uninstall entry, or lose notification identity. The one thing
  that does move is the Start Menu folder, so the installer now deletes the old one by its literal
  pre-rename name.

### Changed
- **Dropped "(Compose)" from the app name.** It is now just "Huginn Desktop" in the Start Menu,
  the uninstall entry, the installer, and the diagnostics header. The qualifier only ever existed
  to tell this client apart from the Electron one, which is gone — so it had stopped saying
  anything and just made the name longer.
  The rename is DISPLAY ONLY: the install directory and the uninstall registry key both key off
  `APP_ID` (`huginn-desktop-kt`), and the toast AUMID is untouched, so an upgrade cannot land
  beside the old copy, orphan its uninstall entry, or lose notification identity. The one thing
  that does move is the Start Menu folder, so the installer now deletes the old one by its literal
  pre-rename name — otherwise a machine upgrading across this would keep a second, working, never-
  again-updated shortcut forever.

## 0.8.7

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

## 0.8.6

### Added
- **Copy link / Copy screen on the Screen tab.** The pane is a canvas, so there was nothing to
  select with a mouse either — the same gap the phone had, and the same fix, from the same shared
  code. A wrapped URL is rejoined using the pane's real column count; the screen copy reflows
  nothing.

## 0.8.5

### Added
- **New round, and Edit on every row.** The Rounds pane could show and run scheduled jobs but not
  make one — that needed curl. The editor replaces the list rather than floating over it: it is a
  form with seven decisions in it, and a dialog would put half of them behind a scrollbar inside
  a scrollbar. Shared with the phone, so a schedule cannot be written two different ways.
- Clicking a Round that has never run opens the Round instead of doing nothing.

### Fixed
- The empty state told you to "create one from the daemon". You can create one from here now.

## 0.8.4

### Changed
- The device rows are now the same composable the phone draws, which gained a confirm-before-
  forget and a line saying where scope is actually set. The desktop keeps its own empty state,
  since "turn on the toggle in Settings" is advice only this app can give — and it now also
  names `huginn device on` for machines that have no desktop app at all.

## 0.8.3

### Changed
- A round's Pause is a word beside Run now rather than a filled Switch, and the status dot sits
  on the title line. Shared with the phone — one composable, both clients.

### Fixed
- A finished round no longer shows suggestion chips above its "kept for review" note.

## 0.8.2

### Fixed
- **This machine was enrolling but never being given work.** The runner is kept in step with
  its setting from the app's five-second poll, and `start()` cancelled and relaunched
  unconditionally — so the loop was torn down every five seconds and could never hold a
  25-second long poll open. Measured against the live host: **518 registrations in 45 minutes
  and 4 work polls**, with every job queued to this machine sitting untouched until the daemon
  declared it silent. It looked healthy throughout, because registering and beating are short
  requests that always succeeded; only the one request that is *supposed* to be silent was
  being killed. `start()` is now idempotent.
- A transient poll failure no longer costs the enrolment — it is caught at the poll rather
  than unwinding to a re-enrol, so a flaky link stops producing a stream of registrations.
- Stopping the runner destroys the child it spawned. A cancelled coroutine stops reading, but
  the process it started keeps running with nobody listening.

## 0.8.1

### Added
- **A chat that runs on another machine is badged with its name**, ahead of the act mark —
  which machine a chat is changing matters more than that it can. Only ever drawn for a remote
  chat; the common case needs no label, and marking every row would stop the unusual one
  standing out.
- **A finished round replaces its composer with a note.** The run has ended and is kept for
  reading; the host refuses a send with 409, so offering an input that cannot deliver would be
  a small dishonesty.

## 0.8.0

### Give Huginn access to this PC

This machine can now offer itself to huginn as a place to run work — the feature the desktop
client was always the right home for, because it is already installed, already trusted, and
already running.

- **A Devices destination.** Every machine enrolled with the daemon, what it is willing to do,
  and the one control that matters here: start something on it. Ask here, Act here, Forget.
  Not a management console — a device's settings live ON that device, which is the point of the
  scope model — so this reads state and starts work.
- **The runner, in Settings.** One switch, a scope (Look / Work / Own), the folder a Work run
  starts in, and an optional path to the CLI for when `PATH` does not carry it. Off by default
  and it stays that way: a feature that arrives already on is one nobody consented to.
  - Nothing listens on a port. The app asks huginn for work and posts results back, so a laptop
    away from home behaves exactly like the desktop next door.
  - **The argv is built here, from this machine's own scope.** The daemon sends a request and no
    authority; if it sent tool grants, whoever held its bearer token would hold this computer.
  - **Locked means read-only.** While the screen is locked the machine drops to Look and refuses
    Act — not because a lock screen is a boundary, but because nobody is there to catch a bad
    call. Windows and Linux are detected; where detection is unavailable the machine reports
    itself as locked and will only ever Look, and Settings says so rather than leaving you to
    wonder why Act is refused.
  - Work scope is stated plainly as **the folder a run starts in, not a sandbox** — a command
    that is allowed to run can leave any folder, and overstating a fence is worse than not
    having one.
- **A Rounds destination.** The host's scheduled work, its cadence in words, what it last found,
  and Run now. Full width, because a Round row already carries its whole report.

### Fixed
- **A reused session name showed the DEAD session's conversation.** Same fix as the phone: a
  changed `claudeSessionId` now resets the transcript window instead of appending to it, and the
  read offset goes with it.

## 0.7.0

### The rail speaks icon

The nav rail's text tabs — Chats, Sessions, Status — repeated the header of
the very pane they opened: two adjacent columns saying the same word. Each tab
is now an icon (a chat bubble, a terminal, a gauge; Settings keeps its gear at
the foot), with the word on hover and in the accessibility description, and
the rail slims from 104dp to 52dp — the width goes to the panes that carry
actual content. Everything the rail already told you stays: the count under
the icon, the attention dot (now riding the icon's shoulder), and the same
selection wash the open list row uses.

## 0.6.0

### Updates come from GitHub now

The desktop client updates itself from the project's public GitHub releases
instead of the private daemon feed — huginn is a public project, so its updates
are not siloed to it. The safety property is unchanged: the source is still a
compile-time constant (the repo, never a Settings value), the download is HTTPS,
and every installer is verified against the sha256 in the release manifest before
it can be run. An already-installed 0.5.x client updates to this version over the
old feed, once; from here on it is GitHub.

### Context used, and when a session is compacting

The session header shows a "context used" meter (it tints when the window is
nearly full), and both the list and the header show a **Compacting…** marker
while a session is rewriting its context. The session menu gains **Compact
context**, which reclaims context without opening the pane.

### Multi-part questions point you the right way

A question that carries several parts can't be answered by one button tap, so its
card now says so and offers to jump to the Screen tab, where the parts are stepped
through — instead of a button that quietly answers the wrong thing.

## 0.5.0

### Wind down a session

Alongside "End session" the session menu now has **Wind down…**: it sends Claude
a wrap-up instruction (finish outstanding items, commit, prepare to end) and —
when the host has auto-end on — the session ends itself once it settles. A
wrap-up that turns into a question keeps the session open. It only sends a
message, so unlike End it is not destructive and your draft is left alone.

### Questions read right, every time

The question card is rebuilt on the host's fused prompt: the exact option text
(no longer whatever fitted the pane width), the one-line description under each
option, and an "N of N" marker when a dialog carries more than one question. When
Claude asks something the host cannot read off the screen at all, a card still
appears; answering it verifies against the live screen and, if it has to, sends
you to the Screen tab. A multi-select toggled directly in tmux is no longer
reverted when you press Answer.

### Photo thumbnails

A photo you attached shows as an actual thumbnail in the history, not a "photo
attached" line. (It falls back to the line if the file has been removed on the
host.)

### Up and down through what you have sent

The composer remembers what you have sent, per chat and per session: Up and Down
walk back through it like a shell, and it survives a restart. On the Screen tab
with live keyboard on, the arrows go to the pane as before.

## 0.4.0

### The raven

The Huginn raven — the same dark mark the phone app now carries — everywhere
this client shows an identity: the window and taskbar icon (which until now was
Java's default coffee cup), the Windows installer and its Start Menu shortcut,
the .deb's desktop entry, and the tray. The tray keeps its state colours
(grey idle, blue working, amber attention) but the dot is a raven now; attention
additionally carries a badge dot so it still reads on a monochrome tray theme.
Window and tray icons stay drawn in code rather than shipped as files, so they
cannot go missing from a package; the installer icons come from
`packaging/huginn.ico` / `huginn.png`, generated from `assets/brand/`.

### Enter sends

Both composers wanted Ctrl+Enter to send and treated a plain Enter as a newline.
That was deliberate once — a guard against firing off half-written messages — but
it reads as a broken send, because every other chat you use sends on Enter.

Enter sends now, in chats and sessions alike. Shift+Enter gives you a new line,
and Ctrl+Enter still sends if that is what your hands have learned.

## 0.3.3

Sessions can be started from here now, and the conversation is the whole
conversation.

### You can start a session without SSHing in

The Sessions list could open, rename and end sessions but never make one — it
even said so, in the empty state — so starting one from a desk meant logging into
the host. There is a **+ New** button now. It refuses a name the host would
refuse, and one that is already taken, while you type rather than after the
round trip. Claude Code starts in the new session automatically.

One thing worth knowing: tmux quietly rewrites some characters, so a session you
call `notes.today` is really called `notes_today`. The app now follows the name
tmux actually used rather than the one you typed — before this it would have
opened a session that does not exist.

### The Conversation tab holds the whole session

It used to show the tail and say "Showing the most recent part of this session."
On a long session that was a sliver — measured on a real transcript here, 51
messages out of 3452 — and there was no way to ask for the rest.

Scroll to the top and there is a **Load earlier messages** link, until you reach
the beginning of the conversation. Each click fetches one page, so a long session
stays quick to open.

That warning belongs to the **Screen** tab, where it stays: a Claude pane runs on
the terminal's alternate screen and genuinely has no scrollback. The transcript
always had the full history — only the reader was capped.

Needs **huginn-appd 2.54.0** or newer for the history.

### Scrolling up in a live session no longer snaps back

Reported in 0.3.2's notes as fixed; this is the release that carries it to the
session view as well as the chat view.

### A message sent during an upload could disappear, in sessions too

Same as the chat fix in 0.3.2, applied to the session composer.

## 0.3.2

Windows notifications should work for the first time, questions come back to the
conversation view, and a message you send while a file is uploading can no longer
vanish.

### Windows notifications never had an identity

Windows files every notification under the calling app's identity and throws it
away — silently, no error, nothing in any log — if that identity does not match an
installed Start Menu shortcut. This app has always said it needed that stamp; the
installer never applied it. So on Windows the toasts were almost certainly going
nowhere, and worse than nowhere: while this app is open it tells the daemon it is
handling notifications, which holds back the Telegram message that would otherwise
have reached you. It swallowed the notice and the fallback.

The installer now stamps the identity, and registers a name for it so the app
appears in Settings > Notifications — being unable to find it there is the other
way toasts disappear without saying anything.

This one cannot be tested from the build host, because there is no Windows machine
in the loop. If notifications still do not arrive after this update, that is worth
knowing quickly.

### Scrolling up in a live session no longer snaps back

The follower stopped following when you dragged the list with a finger — which is
the right rule on a phone, and meaningless with a mouse, because a wheel does not
produce a drag. On the desktop it meant the latch could never be released: scroll
up to read something while Claude is still typing and the next token pulls you
back to the bottom. A wheel or trackpad scroll now counts as taking control, the
same way a drag does.

### A message sent during an upload could disappear

Attach something large, type a message, press send, then switch to another chat
or session while the upload is still going: the composer had already emptied, the
send was cancelled with it, and the message was gone with nothing left to retry
from. The text comes back to the composer now if the send cannot be completed.

### The update section stops reporting a problem you already fixed

The app checks for updates at launch — which on a fresh install is before you
have typed a token, so the first check failed and Settings said "update check
failed" for four hours no matter what you did about it. A failed check now
retries within half a minute, backing off while the problem persists, and any
wait ends the moment the token changes — because the token is the thing that
was usually wrong.

### Quitting no longer forgets the last thing you opened

The remembered position is written on a short delay so that walking a list is
not a disk write per key repeat — but that means the change most likely to be
lost was the last one you made, the one just before quitting. Both ways out of
the app now write it immediately.

### Not in this release, but you already have it

Questions and permission prompts had stopped appearing in the conversation view
on desktop and phone alike, leaving the raw Screen tab as the only way to
answer. That was the host reading the pane with an outdated idea of what Claude
Code draws under a question, and it was fixed on the host (2.52.2) — both
clients got it at once, no update required.

## 0.3.1

Nothing new. Six things that were wrong, five of them found by using the app
rather than by reading it, and the worst of them a way to lose your token.

### Live typing put junk in the pane

Every capital letter and every shifted symbol arrived with a stray glyph in
front of it: typing `ABC` into a live shell produced `￿A￿B￿C`. A bare
Shift press has no character, and the platform says so with a code point that
this app was reading as if it were one. Modifiers now send nothing. Because a
held modifier repeats, a slow Shift was spraying rather than prefixing.

Insert was refused by the host, and not quietly: keystrokes are batched into one
request, so a single Insert took every character typed alongside it down with
it. The host accepts it now, which means this release wants **huginn-appd
2.52.1 or newer**.

And the first keystroke after turning Live on was swallowed — the pane took
focus a moment after the click that asked for it, and whatever you typed in
between went nowhere. Turning Live on and typing `ls` put `s` in the pane.

### Your token could disappear on update

Saving settings replaced the file by renaming a temporary one over it, which on
Windows does not replace anything, fails, and returns a value the app was
ignoring. The first save worked because there was no file yet; every save after
it silently did nothing. The visible cost was a token that vanished across an
update. Settings are now swapped atomically, the file is left readable only by
you, and a settings file that cannot be parsed is copied aside before defaults
land on top of it rather than being overwritten with no trace.

If your token went missing on the way here, enter it once more. It will stay
this time.

### The installer no longer half-installs over a running app

Updating while the app was open left the old files locked and the new ones
partly written, and said nothing about it. The installer now finds the running
app, asks it to close, and — because this app hides to the tray rather than
exiting — ends it outright if it will not. It refuses to continue rather than
proceeding into a broken install.

### Drafts, the status bar, and where the window opens

A message half-typed into a session was discarded the moment you looked at
anything else. Session drafts are now kept the way chat drafts already were:
they survive switching, and they survive a restart. They follow a session that
gets renamed, and they are cleaned up when one is killed.

The bar along the foot had a single error slot that only a click could clear,
so the first failure of a run pinned itself there for the rest of the session —
which is why it could read `unauthorized` while you sat watching a session
stream. Each source now reports its own state and a success clears it.

The window opens where you left it: Chats or Sessions, and the thing you had
open, if it is still there. Glancing at Status or Settings does not count, and
an install that has never recorded a position opens on Sessions.

### Still not proven

The same as 0.3.0 — Windows notifications, the answer buttons on them, and the
interface on real graphics hardware. The live-typing fixes above were verified
by typing into a real pane on huginn, so they are proven on Linux and reasoned
on Windows.

## 0.3.0

The polish release. The app worked; it did not yet feel finished. Three passes:
what the phone can do and this could not, what a mouse-and-keyboard machine
should have, and how densely the whole thing sits.

### The chat was showing you less than the phone was

It read the daemon's flat summary of a conversation, which contains no thinking
records and no tool results — so no amount of rendering could have shown them.
It now reads the same transcript the phone reads. Tool cards open onto what the
tool actually returned, thinking appears, and a fan-out folds into one row you
can expand.

Suggestions exist now, in chats and in sessions: at the end of a turn, a few
things you might say next, which fill the box rather than sending themselves.
Drafts survive closing the app. Model, effort and mode can be changed, and go
quiet mid-turn because the host fixes those when a run starts. Chats can be
renamed and deleted, and when the host refuses — it will not delete a chat with
a run in flight — it says so in its own words. A message sent while something is
already running appears where it will land, marked as waiting.

### The session view can show you what the work is doing

A strip above the composer while a session is working: what it is doing, which
background shells are still going and for how long, how many agents are out.
Open it and each agent is there — what it was asked, what it is doing now, and
once it settles, its own account of what it found. It stays for a few minutes
after the work ends, because that is exactly when those conclusions become worth
reading, and vanishing then would be the worst possible moment.

### It behaves like a desktop program

Right-click a chat or a session for everything you can do to it. Hover a state
dot and it tells you which state and for how long. The window remembers its size
and where the pane divider was. Ctrl and Shift select more than one row, and the
menu addresses them together. Along the foot, a line that says which route you
are on, whether the watch stream is attached, what is working, and what is
waiting on a human — it replaced an error banner that used to shove the whole
view down whenever the network hiccuped.

### It sits more tightly

List rows, the rail, the palette and the transcript itself are all closer than
they were: the rhythm that reads as comfortable under a thumb reads as loose
under a mouse, where the eye travels further and you are scanning rather than
dwelling. The phone is untouched — it keeps its own spacing, and the difference
is a setting the window chooses rather than two copies of the same screen.

### Still not proven

The same three as 0.2.0: Windows notifications, the answer buttons on them, and
the interface on real graphics hardware.

## 0.2.0

The release that makes this worth living in rather than looking at. Everything
here exists because the Electron client has it and this one did not.

### It tells you when something needs you

A notification router driven by the watch stream: a session asking a question
raises one, a chat finishing raises a quieter one, and both are taken back down
when the question is answered or you open the thing they were about. Finishes
are counted rather than watched for, because a run that starts and ends between
two glances was never seen running at all. The first look after launch teaches
it what is already true instead of announcing all of it.

On Windows the toast carries the answer buttons, and each one is stamped with
the question's fingerprint — so a button clicked after the pane has moved on is
refused rather than landing somewhere it did not belong. Elsewhere, and if
anything about that path fails, it falls back to a plain notification rather
than going quiet.

There is a tray icon that says at a glance whether anything wants you, and the
window can close to it and keep listening.

### It takes what you give it

Paste a screenshot, drop a file, or pick one. Images are shrunk before they are
sent, because a twelve-megapixel screenshot costs tokens for pixels no model can
use. Anything else streams from disk rather than through memory, so sending a
large file is not an act of faith.

### It can be driven from the keyboard

Ctrl+K finds any chat or session, and does not make you spell them — `hdk` is
enough for `huginn-desktop-kt`. Ctrl+1/2/3 and Ctrl+, move between views, Ctrl+N
starts a chat, Alt with the arrow keys walks the list without making you leave
what you were typing, Esc steps back, and F1 lists all of it. Nothing steals a
keystroke while you are writing.

### It handles accounts, and it will tell you what is wrong

Saved logins with their weekly usage, switching between them, and adding one —
including the two answers a hopeful interface hides: that the login you just
added was already there, or that the token now saved belongs to somebody other
than the account you meant.

Settings can also copy a description of this install for pasting into a chat
when something looks wrong. Your token is not in it, and a test asserts that
from every field it could have leaked through.

### Still not proven

The Windows notification path, the answer buttons on it, and the whole interface
on real graphics hardware. Everything so far was verified on a Linux box with a
software renderer; the parts only Windows can answer are still open.

## 0.1.0

First packaged build, and the first that can replace itself.

### It installs on Windows, and nothing but this Linux box built it

There is a Windows installer now, produced end to end on huginn. `jpackage`
cannot cross-compile — on Linux its only valid output types are app-image, rpm
and deb — so the Windows one runs under wine, against a Windows runtime image
linked from Windows jmods, and Linux `makensis` wraps the result. The Linux
`.deb` comes from Compose's own packaging. The two builds share every jar except
skiko, which ships a native per platform and is resolved through a configuration
of its own; a single global declaration is what once put a Linux renderer inside
a Windows package.

The installer is deliberately its own application as far as Windows is
concerned: its own directory, Start Menu entry, uninstall key and executable
name, so it can sit beside the Electron client until that one retires.

### It checks for its own updates

On launch and every four hours the app asks huginn for the newest release,
compares it against its own version, downloads the build for its platform and
checks the file against the SHA-256 the release listing already carries. Then it
stops and says it is ready. Nothing installs itself: these builds are unsigned,
and an application that relaunches into new code without being asked is one you
cannot tell apart from something that went wrong.

Where updates come from is fixed in the program itself and is not the server
address in Settings. That address is editable — it has to be, because which one
reaches huginn depends on which VPN is connected — and an update feed that
followed it would turn one mistyped host into permission to run an executable.
