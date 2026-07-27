# Huginn changelog

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
