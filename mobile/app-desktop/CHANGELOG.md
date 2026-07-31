# Huginn Desktop (Compose) changelog

The Compose Multiplatform desktop client, versioned separately from the phone
app and from the Electron desktop client. Its releases go to `/v1/desktop-kt`;
the Electron client's go to `/v1/desktop` and the two never mix — see
`scripts/release-desktop.sh`.

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
