# Install the clients

Install the Huginn client on each computer you will use Huginn from. It gives you:

- **`huginn`**, the command: open the console, attach to sessions, run one-off questions, manage
  the server;
- **huginn core**, a small background service that keeps the computer paired with your server,
  starts at boot (before anyone signs in), shows notifications and a tray icon, and can let
  Huginn run work on this computer if you allow it;
- optionally **the desktop app**, a window on top of huginn core.

You need the [server](server.md) installed and reachable from this computer
([Networking](networking.md)), and its access token at hand (`cat /etc/huginn-appd/token` on the
server).

## Linux

```bash
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh
```

Supported: x86_64 and arm64 with systemd. Run it as your own user, not root; huginn core installs
per user, in `~/.local`.

## Windows

In PowerShell (5.1 or 7), as your own user:

```powershell
irm https://raw.githubusercontent.com/silencelen/huginn/main/client/get.ps1 | iex
```

Supported: Windows 10 and 11, x64. Registering huginn core to start at boot asks for
administrator approval once.

> Windows builds are signed, but not yet with a publicly trusted certificate, so SmartScreen
> reports an unknown publisher. [Verify a download](verify.md) before you allow it.

## Termux (Android)

```bash
pkg install bash openssh curl
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh | sh
```

Termux gets the terminal client only (no huginn core). For notifications, live session views and
permission prompts on Android, use [the Android app](android.md).

## What the installer asks

Every installer always installs from the newest release, never from a development branch.

1. **Install the desktop app?** (Windows, and Linux with a graphical session; default yes.)
   - **Yes:** it installs huginn core and the desktop app and opens the app. The app's setup
     window takes you through the rest: the server's address, the access token, and whether
     Huginn may use this computer.
   - **No:** setup continues in the terminal, as below.
2. **The server.** Its address or name, for example `192.168.1.20` (`192.168.1.20:8787` and
   `https://…` are accepted too).
3. **SSH access**, used to attach to sessions from a terminal. The installer creates a key if this
   computer has none, adds a `Host huginn` entry to `~/.ssh/config`, and prints one line to add to
   `/root/.ssh/authorized_keys` on the server. Add it, then press Enter.
4. **Pairing.** huginn core is installed and paired with the server. Over SSH it fetches the token
   itself; otherwise it asks you to paste it (it is not shown as you type).
5. **Offer this computer as a device?** (default yes.) Huginn may then run read-only work here.
   You can widen or withdraw this later; see [Devices](../guide/devices.md).
6. **Local AI?** Only offered on a computer with a suitable GPU (default no; experimental); see
   [Local AI](../guide/local-ai.md).
7. **Install Huginn skills for Claude Code?** (default yes.) Only asked when Claude Code is on
   this computer. It links two short documents into `~/.claude/skills` that teach your Claude
   Code what `huginn` is and how to diagnose it; see [Claude Code skills](../guide/skills.md).

It ends with a summary of what it set up.

### Unattended installs

Answer the questions up front; with no terminal to ask on, unanswered questions are answered no.

| Linux | PowerShell | Meaning |
|---|---|---|
| `sh -s -- <server>` | `$env:HUGINN_HOST = '<server>'` | the server's address |
| `HUGINN_GUI=y\|n` | `$env:HUGINN_GUI` | install the desktop app |
| `HUGINN_DEVICE=y\|n` | `$env:HUGINN_DEVICE` | offer this computer as a device |
| `HUGINN_LOCAL=y\|n` | `$env:HUGINN_LOCAL` | set up local AI |
| `HUGINN_SKILLS=1\|0` | `$env:HUGINN_SKILLS` | install the Claude Code skills |

```bash
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/client/get.sh \
  | HUGINN_GUI=n HUGINN_DEVICE=n sh -s -- 192.168.1.20
```

## After installing

```bash
huginn                  # the console: sessions, devices, usage, settings
huginn main             # attach to the main session (created on first use)
huginn core status      # is huginn core running and paired
huginn doctor           # a health report of everything huginn needs on this computer
```

New to Huginn? Start with [Sessions](../guide/sessions.md) and [The console](../guide/console.md).

## The desktop app later

```bash
huginn desktop install      # install or reinstall it (Windows, Linux)
huginn desktop update
```

On Linux it is also available as a `.deb`, an Arch package and a portable build from the
[releases page](https://github.com/silencelen/huginn/releases) (`desktop-v*`). Every desktop
install brings huginn core with it.

## Without the installer

To install only the `huginn` command from a clone (no huginn core):

```bash
git clone https://github.com/silencelen/huginn.git
bash huginn/client/install.sh <server>
```

```powershell
git clone https://github.com/silencelen/huginn.git
.\huginn\client\install.ps1 -HuginnHost <server>
```

Then pair huginn core when you want it: `huginn core install`.
