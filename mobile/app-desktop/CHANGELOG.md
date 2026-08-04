# Huginn Desktop (Compose) changelog

The Compose Multiplatform desktop client, versioned separately from the phone
app and from the Electron desktop client. Its releases go to `/v1/desktop-kt`;
the Electron client's go to `/v1/desktop` and the two never mix — see
`scripts/release-desktop.sh`.

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
