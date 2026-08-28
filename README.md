<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/raven-inverse.svg">
  <img src="assets/brand/raven.svg" alt="The Huginn raven" width="120">
</picture>

<pre>
██╗  ██╗██╗   ██╗ ██████╗ ██╗███╗   ██╗███╗   ██╗
██║  ██║██║   ██║██╔════╝ ██║████╗  ██║████╗  ██║
███████║██║   ██║██║  ███╗██║██╔██╗ ██║██╔██╗ ██║
██╔══██║██║   ██║██║   ██║██║██║╚██╗██║██║╚██╗██║
██║  ██║╚██████╔╝╚██████╔╝██║██║ ╚████║██║ ╚████║
╚═╝  ╚═╝ ╚═════╝  ╚═════╝ ╚═╝╚═╝  ╚═══╝╚═╝  ╚═══╝
</pre>

### 🐦‍⬛ Thought, on call from anywhere.

**One command to reach a persistent [Claude Code](https://docs.anthropic.com/en/docs/claude-code) session running on your own server — from your laptop, desktop, or phone.**
**Plus native Android and desktop apps: chats, live session views, and permission prompts answered from your lock screen.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Host: Debian | Ubuntu](https://img.shields.io/badge/host-Debian%20%7C%20Ubuntu-A81D33)
![Terminal: PowerShell | bash | Termux](https://img.shields.io/badge/terminal-PowerShell%20%7C%20bash%20%7C%20Termux-2ea44f)
![Apps: Android | Windows | Linux](https://img.shields.io/badge/apps-Android%20%7C%20Windows%20%7C%20Linux-3DDC84)
![Built for: Claude Code](https://img.shields.io/badge/built%20for-Claude%20Code-D97757)
![Made with: bash + tmux](https://img.shields.io/badge/made%20with-bash%20%2B%20tmux-1f425f)
![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)

[**Setup**](docs/SETUP.md) · [**Usage**](docs/USAGE.md) · [**Architecture**](docs/ARCHITECTURE.md) · [**FAQ**](docs/FAQ.md) · [**Security**](docs/SECURITY.md)

</div>

---

Named for **Huginn**, one of Odin's two ravens — *thought* (his brother **Muninn** is *memory*). The ravens fly out over the world each day and return to tell him what they saw. Huginn here is your thinking surface: a Claude Code session that lives on an always-on host, that you attach to from any device, **mirrored and reattachable** — start something on your phone, pick it up full-screen at your desk.

```console
# at your desk
PS C:\> huginn                  # attach the live session; run: claude
…working on a long task… then you close the lid. it keeps running —
and when you wake the laptop, huginn auto-reconnects you right back in.

# later, from your phone (Termux) — same session, right where you left it
~ $ huginn
~ $ huginn list
main: 1 windows (attached)

# back at the desk — kick the phone and go full-screen
PS C:\> huginn solo
```

> 💡 **Want an animated demo?** A [VHS](https://github.com/charmbracelet/vhs) script lives at [`assets/demo.tape`](assets/demo.tape) — point it at your host and run `vhs assets/demo.tape` to render `docs/demo.gif`.

## ✨ Why

> I found myself driving all my Claude Code sessions from my laptop, instead of from where I was actually working. It let me centralize my knowledge and session store in one place — but it bound me to that one machine. **Huginn is my solution.**

|   |   |
|---|---|
| ♾️ **Always there** | Long/agentic tasks keep running when you close the lid. Wake the machine and huginn **auto-reconnects** you — exactly where you left off. |
| 📱 **Any device, one session** | `huginn` from laptop, desktop, or phone attaches the *same* live session. |
| 🪞 **Mirror or solo** | Two devices mirror each other (fit the smaller screen); one keystroke (`Alt-o`) detaches the rest to go full-screen. An auto-reconnect picks the right mode for you. |
| 🏷️ **Live status tabs** | `huginn costtracking` labels the terminal tab `costtracking` (Windows Terminal / iTerm / Termux) **with a live state icon** — 🔄 working · ✋ needs you (permission/input) · ✅ waiting for your next prompt — so you can tell at a glance, across tabs, which session wants you. Restored when you leave. |
| 💸 **Subscription, not metered** | Log Claude Code into your **Max/Pro** plan — flat cost, no per-token billing. (API key works too.) |
| 🔌 **No daemon, no ports** | Just SSH. Add Tailscale/WireGuard and it works from anywhere. (The apps below are the opt-in exception.) |
| 📲 **Native apps, opt-in** | An Android app and a Windows/Linux desktop app (one shared Kotlin codebase) with streaming chats, structured session views, push notifications, and permission prompts as tappable buttons — [see below](#-the-apps-optional). |
| 🪶 **Tiny core** | The terminal path is a few shell scripts + a tmux config. The value is the *pattern*. |

## 🚀 Quick start

**1. Provision a host** — a Proxmox LXC, a VM, a Raspberry Pi, or any always-on Debian/Ubuntu box. See [`provision/`](provision/).

**2. Set it up** (on the host):
```bash
git clone https://github.com/silencelen/huginn.git
sudo bash huginn/server/setup.sh
claude        # log in once with your Max/Pro subscription (or set ANTHROPIC_API_KEY)
```

**3. Install the command** (on each device):
```bash
# Windows (PowerShell)
.\huginn\client\install.ps1 -HuginnHost <host-ip-or-name>
# bash / Termux (phone)
bash huginn/client/install.sh <host-ip-or-name>
```

**4. Go:**
```
huginn               attach/create the live 'main' session
huginn solo          attach + detach other clients (full-screen resume)
huginn work          a separate named session
huginn -p "..."      one-shot headless query
huginn status        health at a glance
huginn usage         Claude Code token/cost report
huginn update        self-update this client from the repo
huginn help          full reference
```

**5. Optional — the apps:** deploy the daemon and build the Android / desktop clients — see [`mobile/README.md`](mobile/README.md) and [the apps](#-the-apps-optional) below.

## ⌨️ In a session

| Key | Action |
|---|---|
| `Alt-d` | detach (keeps running) |
| `Alt-o` | detach **all other** clients → full-screen |
| `Ctrl-b [` | scroll back |

The status bar shows these hints on the left. Full reference: [`docs/USAGE.md`](docs/USAGE.md).

## 📱 The apps (optional)

The terminal is the core; the apps are the comfortable way to live with it. One Kotlin
codebase (`mobile/`) builds both an **Android app** and a **Windows/Linux desktop app**,
talking to `huginn-appd` — a zero-dependency Node daemon on the host — over HTTP + SSE:

- **Chats** — headless Claude turns streamed token by token, in *ask* (read-only-intent)
  or *act* (tools) mode, with attachments (camera / photos / any file, transcoded and
  streamed up to 128 MB).
- **Sessions** — every tmux session, twice: a **Conversation** view built from the real
  Claude Code transcript (thinking, tool calls, subagents — structure, not screen-scraping)
  and a **Screen** view of the live pane, resized to your device while you watch.
- **Prompts become buttons.** A numbered permission question in the pane turns into
  tappable options — in the app *and* on the Android lock screen, fingerprint-checked
  host-side so a stale tap can never answer a question you didn't see.
- **Notifications that survive a sleeping phone** — high-priority FCM (measured tens of
  milliseconds into deep Doze), an alarm fallback, and optional Telegram from the host;
  stale alerts take themselves down when you answer elsewhere.
- **Desktop niceties** — tray with live state, `Ctrl+K` palette, keyboard-first
  navigation, self-updating installers for Windows and a `.deb` for Linux, all built on
  a Linux host (Windows installer included — jpackage under wine, no Windows box needed).

Start at [`mobile/README.md`](mobile/README.md) for what they do and how to build them,
and read [`docs/SECURITY.md`](docs/SECURITY.md) **before** deploying the daemon — it is a
second root-equivalent credential and the docs treat it that way. There was an older
Electron desktop client; it was retired and deleted on 2026-08-27 — the Compose one at
`mobile/app-desktop` is the desktop client.

## 🤔 Isn't this just `ssh` + `tmux`?

Yes — and that's the point. Huginn is `ssh` + `tmux` + `claude` with the sharp edges filed off: sensible multi-device tmux defaults (mirror, quick-detach, solo), a friendly one-word command with subcommands and tab-completion across PowerShell **and** bash/Termux, a container template, and a one-shot setup. The magic isn't new tech — it's the *packaging*.

## 🗂️ What's in here

| Path | What |
|---|---|
| [`provision/`](provision/) | Container template (Proxmox LXC) + a generic "any Debian/Ubuntu host" path |
| [`server/`](server/) | `setup.sh`, the `cc` launcher, `huginn-status`, a tuned `tmux.conf` |
| [`server/appd/`](server/appd/) | the phone daemon: sessions, chats, push, prompts-as-buttons over the tailnet |
| [`client/`](client/) | `huginn.ps1` + `huginn.sh` + installers + an optional Termux detach button |
| [`mobile/`](mobile/) | the Kotlin clients and the code they share: `:core` (logic + HTTP), `:ui` (Compose), `:app` (Android — [changelog](mobile/CHANGELOG.md)), `:app-desktop` (Windows/Linux — [changelog](mobile/app-desktop/CHANGELOG.md)) |
| [`docs/`](docs/) | [Setup](docs/SETUP.md) · [Usage](docs/USAGE.md) · [Architecture](docs/ARCHITECTURE.md) · [Adding a feature](docs/ADDING-A-FEATURE.md) · [Desktop migration](docs/DESKTOP-MIGRATION.md) · [FAQ](docs/FAQ.md) · [Security](docs/SECURITY.md) |

## 🧠 Memory (the other raven)

Huginn is *thought*. Its companion is **Muninn** — *memory*: an optional MCP-backed memory layer so Claude remembers across sessions. That's out of scope here, but Claude Code's `claude mcp add ...` is the hook. See [Architecture → Extending it](docs/ARCHITECTURE.md#extending-it).

## 🤝 Contributing

Issues and PRs welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md). Security reports: [`SECURITY.md`](SECURITY.md).

## 📜 License

[MIT](LICENSE) © silencelen
