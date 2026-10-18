# Installation overview

Huginn has two parts. Install them in this order:

1. **[The server](server.md)**, once, on an always-on Debian or Ubuntu machine that has nothing
   else to do: a virtual machine, a container or a small computer. One command installs Claude
   Code, tmux and `huginn-appd`, the daemon your devices pair with.
2. **[The clients](clients.md)**, on each computer you will use: Linux, Windows, or Termux on
   Android (terminal only). One command installs the `huginn` command and huginn core, and offers
   the desktop app.
3. **[The Android app](android.md)**, if you want sessions, chats and permission prompts on your
   phone.

Before you start, decide how your devices will reach the server: on your local network, or through
a VPN you control when you are away. See [Networking](networking.md).

## What you need

| | |
|---|---|
| Server | Debian 12 or 13, or Ubuntu 22.04 or 24.04 (x86_64 or arm64), root access, 2 GB of memory |
| Claude | a Claude Pro or Max subscription, or an Anthropic API key |
| Network | ports 22 and 8787 reachable from your devices; never from the internet |
| Clients | Linux (x86_64, arm64), Windows 10 or 11 (x64), Termux; Android 10 or later for the app |

macOS is not supported.

## After installing

- [Updating](updating.md) the server and the clients.
- [Verifying downloads](verify.md).
- [Uninstalling](uninstall.md).
