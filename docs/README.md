# Huginn documentation

Huginn keeps Claude Code sessions running on a server you own and puts them within reach of every
device you use. **Huginn Server** runs on one always-on Linux machine; the **Huginn Client** (the
`huginn` command and huginn core, with an optional desktop app) runs on each of your computers,
and an Android app connects the same way.

## Install

| | |
|---|---|
| [Overview](install/README.md) | what you need and in which order |
| [The server](install/server.md) | one command on a Debian or Ubuntu machine |
| [Proxmox container](install/proxmox-lxc.md) | running the server in a Proxmox VE LXC |
| [The clients](install/clients.md) | Linux, Windows and Termux |
| [Android](install/android.md) | the Android app, and push notifications |
| [Networking](install/networking.md) | ports, VPNs, addresses and TLS |
| [Verifying downloads](install/verify.md) | checksums and Windows signatures |
| [Updating](install/updating.md) | keeping the server and clients current |
| [Uninstalling](install/uninstall.md) | removing the clients and the server |

## Use

| | |
|---|---|
| [Sessions](guide/sessions.md) | attach, mirror, reconnect, rename and end sessions |
| [The console](guide/console.md) | the full-screen terminal view |
| [The apps](guide/apps.md) | the desktop app and the Android app |
| [Chats](guide/chats.md) | one-off questions and tasks, from the apps and `huginn -p` / `-y` |
| [Notifications](guide/notifications.md) | what notifies, where, and the optional alert relay |
| [Devices](guide/devices.md) | letting Huginn run work on your other computers |
| [Projects](guide/projects.md) | groups of sessions led by one session |
| [Rounds](guide/rounds.md) | scheduled, unattended work on the server |
| [Usage limits](guide/usage-limits.md) | how Huginn handles your plan's limits |
| [Hosted apps](guide/hosted-apps.md) | listing the web apps your server serves |
| [Claude Code skills](guide/skills.md) | what Claude Code knows about Huginn on your computers and the server; `huginn doctor` |
| [Local AI](guide/local-ai.md) | serving local models from a computer with a GPU (experimental) |

## Reference

| | |
|---|---|
| [Commands](reference/cli.md) | every `huginn` command |
| [Environment variables](reference/environment.md) | client-side settings |
| [Server configuration](reference/server-config.md) | files, daemon settings and logs |
| [API](reference/api.md) | the daemon's HTTP API |
| [Releases and compatibility](reference/releases.md) | what is released where, and what goes together |

## More

- [Security](security.md): what Huginn exposes, how it is protected, and a hardening checklist.
- [FAQ](faq.md): common questions and problems.
- [Contributing](../CONTRIBUTING.md) and the [developer docs](dev/README.md).
- [Design records](design/README.md): how and why the larger pieces were built.
