# Huginn

**One command to reach a persistent [Claude Code](https://docs.anthropic.com/en/docs/claude-code) instance running on a server — from your laptop, desktop, or phone.**

Named for Huginn, one of Odin's two ravens (*thought*; his brother **Muninn** is *memory*). Huginn is the thinking surface: a Claude Code session that lives on an always-on host, that you attach to from any device, with the session mirrored and reattachable so you can start something on your phone and pick it up full-screen on your desktop.

```
            you (laptop / desktop / phone, over SSH/Tailscale)
                              │  huginn
                              ▼
                    ┌──────────────────┐
                    │   Huginn host    │   always-on Debian/Ubuntu box
                    │  (LXC / VM / Pi) │   (Proxmox LXC, a VM, a cloud box…)
                    │                  │
                    │  Claude Code     │   on your Max/Pro subscription
                    │  in tmux         │   persistent + reattachable
                    └──────────────────┘
```

## Why

- **It's always there.** Long tasks keep running when you close the lid. Reconnect and it's exactly where you left it.
- **Any device, same session.** `huginn` from your laptop, desktop, or phone (Termux) attaches the *same* live session. Detach on one, resume on another.
- **Mirror or solo.** Two devices on one session mirror each other (great for "watch from the couch"); one keystroke detaches the others so a resume comes up full-screen.
- **Subscription, not metered.** Authenticate Claude Code with your Max/Pro plan — flat cost, no per-token billing. (An API key works too.)

## Quick start

**1. Provision a host** (see [`provision/`](provision/)) — a Proxmox LXC, a VM, a Raspberry Pi, or any always-on Debian/Ubuntu box.

**2. Set it up** — on the host:
```bash
git clone https://github.com/<you>/huginn.git
sudo bash huginn/server/setup.sh
claude   # log in once with your Max/Pro subscription (or API key)
```

**3. Install the command** on each device you'll use:
- **Windows (PowerShell):** [`client/install.ps1`](client/install.ps1)
- **bash / Termux (phone):** [`client/install.sh`](client/install.sh)

**4. Use it:**
```
huginn               attach/create the live 'main' session  (run claude inside)
huginn work          a separate named session
huginn solo          attach + detach all other clients (full-screen resume)
huginn list          list sessions
huginn -p "..."      one-shot headless query
huginn help          full reference
```

See [`docs/USAGE.md`](docs/USAGE.md) for the complete command + keybinding reference and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how it fits together.

## What's in here

| Path | What |
|---|---|
| [`provision/`](provision/) | Host templates — Proxmox LXC recipe + a generic "any Debian/Ubuntu host" path |
| [`server/`](server/) | `setup.sh`, the `cc` session launcher, `huginn-status`, and a tuned `tmux.conf` |
| [`client/`](client/) | `huginn.ps1` (Windows) + `huginn.sh` (bash/Termux) + installers + a Termux detach button |
| [`docs/`](docs/) | Setup walkthrough, usage/keybind reference, architecture |

## License

MIT — see [`LICENSE`](LICENSE).
