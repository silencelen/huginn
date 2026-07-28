# Huginn changelog

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
