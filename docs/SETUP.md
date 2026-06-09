# Setup

Three parts: a **host**, the **server install**, and the **client** on each device.

## 1. Host

Pick an always-on Linux box and get SSH access with your key. See [`../provision/`](../provision/) — there's a Proxmox LXC recipe and a generic "any Debian/Ubuntu host" path.

## 2. Server install

On the host:
```bash
git clone https://github.com/<you>/huginn.git
sudo bash huginn/server/setup.sh
```
This installs Node, Claude Code, tmux, the `cc` launcher, `huginn-status`, and the tuned `tmux.conf`.

Then authenticate Claude Code once:
```bash
claude
```
Choose **"log in with your Claude account"** (Max/Pro subscription) — the paste-code flow works fine over SSH. (Or set `ANTHROPIC_API_KEY` instead for metered API billing.)

Optionally set where sessions open (default `$HOME`):
```bash
echo 'export HUGINN_WORKDIR=$HOME/projects' >> ~/.bashrc
```

## 3. Client (each device)

Each device needs three things — the installer does all three:
1. an SSH key authorized on the host,
2. a `Host huginn` SSH alias,
3. the `huginn` command sourced in your shell profile.

**Windows (PowerShell)** — laptop, desktop:
```powershell
git clone https://github.com/<you>/huginn.git
.\huginn\client\install.ps1 -HuginnHost <host-ip-or-name>
```

**bash / Termux (phone)**:
```bash
git clone https://github.com/<you>/huginn.git
bash huginn/client/install.sh <host-ip-or-name>
```

The installer prints your device's **public key** — append it to the host's `~/.ssh/authorized_keys` (one time per device), then `huginn status` should work.

> **Reusing an existing key (e.g. you already SSH to the host):** skip the installer's key step — just add a `Host huginn` block to `~/.ssh/config` pointing `IdentityFile` at your existing key, copy `client/huginn.ps1` (or `.sh`) into `~/.huginn/`, and source it from your profile.

### Off-LAN access
Put the host and each device on [Tailscale](https://tailscale.com), then set the alias `HostName` to the host's MagicDNS name (e.g. `huginn`). Now the same command works from anywhere.

### Optional: phone detach button
On the phone, `bash client/termux-detach-button.sh` adds a one-tap **DTACH** key to Termux's keyboard row.

## Verify
```
huginn status      # uptime, auth (should show your subscription), sessions, disk
huginn             # drops you into the 'main' session; run: claude
```
