# The apps

Huginn has two graphical apps, and they show the same things in the same way:

- **The desktop app**, for Windows and Linux. It is a window on top of huginn core, which holds
  the connection to your server, the token, the tray icon and the notifications. Install it with
  the client: [Install the clients](../install/clients.md).
- **The Android app**, which pairs with the server directly:
  [Install the Android app](../install/android.md).

Both talk to `huginn-appd` on your server over HTTPS. Nothing runs in the apps themselves: every
session and chat runs on the server, and closing an app never stops one.

## Sessions

The Sessions list shows every tmux session on the server, the same list `huginn ls` prints. Each
row carries a state, the session's title, its last activity, how much of its context window is
used, and how long ago it changed.

| State | Meaning |
|---|---|
| working | Claude Code is running a turn. |
| needs you | A question or permission prompt is on screen, waiting for an answer. |
| waiting | Claude Code is idle, ready for your next message. |
| shell | No Claude Code is running in the session, only a shell. |

**New session** starts Claude Code on the server in its working directory. A session's menu
renames it, wraps it up, archives it or kills it; see [Sessions](sessions.md). Sessions that
belong to a project are grouped under Projects instead; see [Projects](projects.md).

Open a session to see three tabs.

### Conversation

The default tab, built from the session's own Claude Code transcript rather than from the screen:
your messages, Claude's answers, its thinking, each tool call folded together with its result,
and the output of subagents and workflows. Code blocks, commands and diffs are syntax
highlighted. When a session fans out to subagents, a picker above the conversation lets you
follow any one of them.

- **Sending.** Type in the composer and send. If a question or dialog is on screen, your message
  waits until it is answered, then goes in.
- **Control bar.** Model, effort and permission mode. These send the same `/model` and `/effort`
  commands and the same Shift+Tab you would type, and read the current values back from the
  transcript.
- **Suggestions.** At the end of a turn a few suggested next messages may appear above the
  composer. Tapping one fills the composer; it never sends.
- **Drafts.** An unsent message stays in its composer across navigation and restarts, per session
  and per chat.

### Screen

The live tmux pane, for what only a terminal can do: watching progress, answering a dialog,
typing.

- **Sized to your device.** While you watch, the server resizes the session's window to fit your
  screen, so Claude Code re-wraps to it. The size is held only while you keep watching and
  returns when you leave (or after 90 seconds without renewal). One device holds the size at a
  time.
- **A terminal is attached.** The pane keeps that terminal's size, and the view says so.
  **Fit anyway** resizes it, which shrinks the other person's window.
- **Typing.** Turn on **Live** to send keystrokes as you type. On Android a key row adds what a
  phone keyboard lacks: Esc, Shift+Tab, Tab, the arrows, Ctrl+C, Ctrl+D, Ctrl+L, Ctrl+R, Page Up
  and Page Down.

### Overview

What the run has done and spent (tokens and an estimated cost), your own goals and notes for the
session, and whether it picks itself back up after a usage limit. See
[Usage limits](usage-limits.md).

## Permission prompts become buttons

When Claude Code shows a numbered question in the pane (a permission prompt, a choice, a plan to
approve), the apps offer its options as buttons in both the Conversation and the Screen tab.
Questions that take several answers are offered as a multi-select. Detection requires Claude
Code's live selection marker, so an answer that merely ends in a numbered list does not produce
buttons.

Every answer carries a fingerprint of the question it was offered for. The server reads the pane
again in the same request and refuses the answer if that question is no longer on screen, because
it was answered in a terminal, from another device, or replaced by a different question. A stale
tap can never answer something you did not see. Moving the selection highlight does not change
the fingerprint. The same check protects the answer buttons on Android notifications; see
[Notifications](notifications.md).

## Chats

A chat is a Claude Code conversation that runs headless on the server, without a tmux session.
Start one with **New chat** and pick **Ask** (read, search and the web; nothing that changes
anything) or **Act** (also runs commands and edits files). The answer streams in as it is
written. Closing the app, locking the phone or losing the network does not stop it; reopen the
chat to catch up. Messages you send while it is still answering are queued and sent when it
finishes. See [Chats](chats.md) for the modes, models and where chats run.

## Attachments

The attach button in any composer (chats and sessions alike) takes:

- **Android:** the camera, the photo library, or any file.
- **Desktop:** a file picker, a paste from the clipboard, or files dragged onto the window.

You can attach several at once. Nothing is refused for its type.

- **Photos and images** are converted to JPEG, at most 2048 pixels on the long edge, before they
  are uploaded, so Claude Code can read them. On Android this includes camera formats such as
  HEIC; the desktop app converts the image formats it can decode and sends any other file as it
  is.
- **The upload** goes to the server and is stored in `/var/lib/huginn-appd/uploads` under a name
  the server chooses. The limit is 128 MB per file, set by the server; the apps add none of their
  own.
- **The message** carries the file's path. Claude reads images, PDFs and text files directly. For
  anything else (an archive, a database, a backup file) it is told to inspect the file with shell
  tools, which needs an Act chat or a session.
- **Retention.** Files other than images are deleted after 7 days (set with
  `HUGINN_APPD_UPLOAD_KEEP_DAYS`). Images are kept, because they back the thumbnails in chat
  history, until you delete them from that directory.

**Share to Huginn (Android).** Share text or an image from any app to Huginn and choose where it
goes: a new chat, a session, or an existing chat. It is added to that composer's draft and never
sent on its own.

## The Android app

- **Tabs:** Chats, Sessions, Rounds and Status, with Settings behind the gear. Status shows the
  server's health, your plan's usage windows and when they reset, and token use today and this
  week. See [Rounds](rounds.md).
- **Notifications** that carry a waiting question as answer buttons, so you can answer from the
  lock screen. See [Notifications](notifications.md).
- **Widgets:** *Huginn sessions* (who needs you, what is working, a quick way to start a chat)
  and *Ask huginn* (type a question and it becomes a chat).
- **Dictation:** the microphone in the composer turns speech into text.
- **Wide screens** (tablets, unfolded phones) show the list beside the open chat or session.

## The desktop app

The desktop app runs on huginn core and cannot work without it. If huginn core is not set up or
not running, the window offers the one button that fixes it.

- **The tray icon is huginn core's.** Its menu has **Open Huginn**, the sessions that need you
  (one click opens each), what is working, **Close to tray**, **Quit Huginn** and **Hide this
  icon**. A hidden icon comes back at your next sign-in, or from **Settings > Appearance &
  behaviour > Tray icon**.
- **Close to tray** (on by default) hides the window when you close it; **Open Huginn** brings it
  back. With the tray icon hidden, closing the window quits the app. huginn core keeps running
  either way.
- **Ctrl+K** opens the palette: find any chat, session, page, project or app by name, jump to a
  Settings section, or start a new chat.
- **The rail** on the left holds Chats, Sessions, Projects, Rounds, Devices, Apps, Pages, Status
  and Settings. Projects and Apps appear when your server has any. Pages are your own notes, which
  a message can carry along.
- **Right-click** any row for every action on it; Ctrl+click and Shift+click select several.
- **Updates.** The app checks the project's GitHub releases when it starts and every four hours,
  downloads a newer build and verifies its checksum, then waits. **Settings > Updates &
  diagnostics** offers **Install and restart**; nothing restarts on its own. A `.deb` or Arch
  package needs root to install, so the app never asks for a password: it shows the `sudo apt
  install …` or `sudo pacman -U …` command for the file it downloaded. `huginn desktop install`
  switches Linux to the per-user build, which updates itself.

### Keyboard

Press **F1** for the full list.

| Keys | Action |
|---|---|
| Enter / Shift+Enter | Send / new line |
| Ctrl+K | Find a chat or session |
| Ctrl+1 / 2 / 3 | Chats / Sessions / Status |
| Ctrl+N / Ctrl+Shift+N | New Ask chat / new Act chat |
| Ctrl+, | Settings |
| Ctrl+P | Pages |
| Ctrl+Shift+P | Show the open page beside the conversation |
| Ctrl+Shift+J / Ctrl+Shift+K | Projects / Apps (when the server has them) |
| Alt+Up / Alt+Down | Previous / next in the list, even while typing |
| Ctrl+B | Hide or show the list pane |
| Ctrl+[ / Ctrl+] / Ctrl+\ | Narrower / wider / reset the list pane |
| Esc | Back to the list |
| Ctrl+Shift+H | Hide to the tray |
| Tab / Shift+Tab | Next / previous control |
| Ctrl+Tab | Type a tab character into the message |

### First-run setup

The first time it opens, the desktop app shows **Set up huginn on this computer**. Each step
checks itself against the real thing (the server's answer, the enrolment, a notification you
saw) and says what it found. You can skip any step and come back with **Settings > Host &
sign-in > Run setup again**. Setup configures huginn core, not the app: the app keeps no address
or token of its own.

| Step | What it does |
|---|---|
| huginn's address | The server's IP address or name, for example `192.168.1.20`; `https://` and the port are added for you. More addresses (a VPN one, say) go in **Settings > Host** later. |
| huginn's token | Paste the token; on the server, `sudo cat /etc/huginn-appd/token` prints it. **Connect** hands it to huginn core, which proves the server's certificate against it and keeps it. |
| Claude on this computer | Finds `claude` here and checks that it starts. Only needed if Huginn may run work on this computer. |
| Let huginn run work here | Enrols this computer as a device and sets what Huginn may do on it. Nothing listens on a port: huginn core asks the server for work. See [Devices](devices.md). |
| Serve local AI from this computer | Experimental. Shows the full plan before anything downloads; a long download can finish in the background while setup goes on. See [Local AI](local-ai.md). |
| Notifications | Sends a test notification and asks whether it appeared. |
| Open at sign-in | huginn core's tray already starts at sign-in; this opens the window too. |

If the installer already paired huginn core, the first two steps say so and need nothing typed.

## Settings worth knowing

Settings is grouped into the same sections in both apps, with a search box. Some rows exist on
only one of them.

| Section | Setting | What it does |
|---|---|---|
| Host & sign-in | Routes (Android), Host (desktop) | The addresses that reach your server, tried in order, up to eight. **Switch automatically** moves to the next one that answers. On the desktop these live in huginn core. |
| Host & sign-in | Token | The access token. On the desktop, huginn core keeps it. |
| Host & sign-in | Saved logins, Add a login | The Claude accounts signed in on the server, which one is in use, and how usable each is. Signing out signs out the whole server. |
| Usage & headroom | All rows | Warnings, model step-down and auto-resume around plan limits. See [Usage limits](usage-limits.md). |
| Chats & sessions | Quick actions | What **Explain**, **Execute** and **Ask in new chat** put in the composer for text you select. |
| Chats & sessions | Soft end | The phrase typed into a session to wind it down, and whether it then ends on its own (set on the server). |
| Notifications | All rows | See [Notifications](notifications.md). |
| Devices | Available to huginn, What it may do, Keep act mode while locked (desktop) | Whether and how Huginn may run work on this computer. See [Devices](devices.md). |
| Privacy & security | Lock the app (Android) | Asks for your screen lock before showing Huginn and hides it from the recents preview. |
| Privacy & security | Remove this computer's access (desktop) | Unenrols this computer and unpairs huginn core. Nothing on the server is deleted. |
| Appearance & behaviour | Close to tray, Tray icon, Start with your session (desktop) | How the window and the tray icon behave, and whether the window opens at sign-in. |
| Appearance & behaviour | Terminal text size (Android) | Text size in the Screen tab. |
| Updates & diagnostics | Check for updates | Looks for a newer build and installs it when you say so. |
| Updates & diagnostics | Copy diagnostics | Puts the app's version, connection, notification delivery and recent log on the clipboard, without the token. |
