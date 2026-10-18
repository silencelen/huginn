# FAQ

## General

### Is this just SSH and tmux?

The terminal part is: `huginn` attaches to a tmux session on your server over SSH, with defaults
tuned for several devices (mirroring, one-key full screen, automatic reconnect) and the same
command in bash, PowerShell and Termux. Huginn adds what SSH and tmux cannot: a daemon that turns
Claude Code's transcripts and prompts into app views and lock-screen buttons, chats, push
notifications, usage-limit handling, scheduled rounds, and huginn core, which keeps each computer
paired and can let Huginn run work on it.

### Do I need a Claude subscription?

Claude Code needs either a Claude Pro or Max subscription or an Anthropic API key. A subscription
suits an agent you use all day, because it is not billed per token. Sign in once on the server
with `claude`.

### What machine should the server be?

Any always-on Debian or Ubuntu machine that has nothing else to do: a virtual machine, a container
(see [Proxmox container](install/proxmox-lxc.md)) or a small computer. Two cores and 2 GB of
memory are a floor. Huginn runs Claude Code as root there, so do not use a machine that holds
data or services you would not hand to an agent. See [Security](security.md).

### Can I run the server as a normal user?

No. The server runs as root, on a machine of its own. Isolation comes from the machine boundary,
not from a user account on a shared machine.

### Is macOS supported?

No. Clients run on Linux, Windows and Termux (terminal only); the Android app needs Android 10 or
later.

### Can I reach the server from outside my network?

Yes, through a VPN you control (WireGuard, Tailscale or your router's VPN server, for example).
Do not expose port 22 or 8787 to the internet. See [Networking](install/networking.md).

## Sessions

### Two devices are attached and the screen is small. Why?

tmux sizes a shared session to the smallest attached screen, so both devices see all of it. Run
`huginn solo`, or press `Alt-o` while attached, to detach the other devices and take the full
screen. Use different session names for separate work. See [Sessions](guide/sessions.md).

### My laptop slept or lost Wi-Fi. Is the session gone?

No. The session runs on the server; a dropped link only ends your SSH connection, and `huginn`
reconnects you when the network is back. Press `Ctrl-C` to stop it from retrying.

### How do I scroll back?

`Ctrl-b [` enters scroll mode; `PageUp`, `PageDown` and the arrow keys move; `q` leaves it.

### Where do new sessions start?

In root's home directory, unless you set a working directory. Sessions started from a terminal
use `HUGINN_WORKDIR` from the server's login environment; sessions and chats started from the apps
use the daemon's `HUGINN_APPD_WORKDIR`. See [Server configuration](reference/server-config.md).

### Typing `huginn` opens a menu instead of my session.

That is [the console](guide/console.md). `huginn main` always attaches to the main session. Set
`HUGINN_CONSOLE=0` to make plain `huginn` attach, as it does in scripts and pipes.

## Chats and one-shots

### Is `huginn -p` read-only?

It cannot change anything: Bash, Edit, Write and NotebookEdit are denied. It can read any file the
server's user can read, and fetch web pages, so do not paste untrusted prompts into it expecting a
sandbox. `huginn -y` may also run commands and edit files. See [Chats](guide/chats.md).

### Can Claude remember things across sessions?

Add an MCP memory server to Claude Code on the server (`claude mcp add …`). To let chats and
one-shots use it, add its tools to `HUGINN_APPD_EXTRA_TOOLS`; see
[Server configuration](reference/server-config.md).

## Usage limits

### Why did a session switch to another model?

It was close to a usage limit. Huginn watches your plan's windows, warns a session first, and
then moves that session (only that one) down a model so it keeps working. Your default model is
not changed. See [Usage limits](guide/usage-limits.md).

### Does Huginn resume a session after a limit resets?

Yes, including when Claude Code itself cannot: after a restart, an update or a reboot, Huginn
types the resume prompt into sessions that stalled on a limit once it resets.

## Apps and notifications

### Where do I get the apps?

The desktop app comes with the client installer (or `huginn desktop install`). The Android app is
an APK on the [releases page](https://github.com/silencelen/huginn/releases); see
[Android](install/android.md). There is no app-store listing.

### Why are Android notifications sometimes late?

The published APK cannot receive push from your server, because push is tied to the Firebase
project an APK is built with. It checks the server itself every 10 minutes while the phone sleeps,
and instantly while the app is open. For instant notifications, build the app with your own
Firebase project; see [Android](install/android.md).

### Windows says the installer is from an unknown publisher.

Windows builds are signed, but not yet with a publicly trusted certificate. Check the download
against the published checksum before you allow it; see [Verifying downloads](install/verify.md).

## Troubleshooting

### `huginn` is not recognized after installing on Windows.

Open a new PowerShell window, or reload your profile with `. $PROFILE`.

### `huginn core status` says it is not paired.

Pair it with your server: `huginn core pair --url https://<server>:8787` (it asks for the access
token, which you print on the server with `cat /etc/huginn-appd/token`).

### Attaching says to run `huginn setup ssh`.

The computer was set up from the desktop app, which does not need SSH. Run `huginn setup ssh` once
to add the SSH key and the `Host huginn` entry that attaching uses.

### Does this work with the Claude Code IDE extensions?

Huginn runs Claude Code in the terminal on your server. IDE integrations are separate and run
where the IDE runs.
