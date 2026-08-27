# Setup

Three parts: a **host**, the **server install**, and the **client** on each device —
plus an optional fourth, the [daemon + apps](#4-optional-the-daemon--the-apps).

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

## 4. Optional: the daemon + the apps

The terminal path above needs nothing but SSH. The **Android and desktop apps** need one
more piece on the host: `huginn-appd`, an HTTP daemon the apps talk to. **Read
[`SECURITY.md`](SECURITY.md) first** — the daemon is a second root-equivalent credential.

On the host, in the repo checkout:
```bash
# one time: create the bearer token the apps will authenticate with
install -d -m 700 /etc/huginn-appd
openssl rand -hex 32 > /etc/huginn-appd/token && chmod 600 /etc/huginn-appd/token

server/appd/deploy.sh          # installs /opt/huginn-appd, starts the unit, proves /v1/ping
cat /etc/huginn-appd/token     # paste this into each app's Settings
```

By default it binds the host's **Tailscale address** on port 8787 (override with a
`HUGINN_APPD_BIND` drop-in — see [`../server/appd/systemd.d/`](../server/appd/systemd.d/)).

Then the clients: build the **Android app** with `mobile/scripts/build.sh` (or
`ship.sh` if you run a self-hosted app store), and the **Windows/Linux desktop app**
with `mobile/scripts/release-desktop.sh` — both build on a Linux host, the Windows
installer included. Details, requirements, and what the apps do:
[`../mobile/README.md`](../mobile/README.md).

## Verify
```
huginn status      # uptime, auth (should show your subscription), sessions, disk
huginn             # drops you into the 'main' session; run: claude
```

## Uninstall (per device)

```bash
huginn uninstall          # unenrols this machine, then removes the client + its tokens
huginn uninstall --all    # and the 'Host huginn' SSH stanza, and huginn's own key if it made one
```

It unenrols **before** it deletes — the row can only be retired with the token that is
about to go — and prints what it removed and what it deliberately left. Details, including
why your SSH key survives by default: [`USAGE.md`](USAGE.md#uninstalling).

The desktop app is removed the usual way for its platform (Programs and Features, or
`apt purge huginn-desktop-kt`), and its uninstaller does the same unenrol-then-delete.
