# Provision a Huginn host (any Debian/Ubuntu box)

Huginn just needs an always-on Linux host you can SSH into. A Proxmox LXC, a VM, a Raspberry Pi, an old laptop, or a cloud instance all work.

## Requirements
- **Debian 12/13 or Ubuntu 22.04/24.04** (other distros work; `server/setup.sh` assumes `apt`).
- **Always on** — that's the point; sessions persist while you're away.
- **SSH reachable** from your devices — directly on your LAN, or over a mesh VPN like [Tailscale](https://tailscale.com) / WireGuard for off-LAN access.
- **A Claude subscription (Max/Pro)** to log Claude Code into — or an `ANTHROPIC_API_KEY`.

## Steps
1. Get SSH access to the host with your key (`ssh-copy-id`, or paste your pubkey into `~/.ssh/authorized_keys`).
2. On the host:
   ```bash
   git clone https://github.com/<you>/huginn.git
   sudo bash huginn/server/setup.sh
   claude            # authenticate once (paste-code flow works over SSH)
   ```
3. Install the client command on each device — see [`../client/`](../client/) and [`../docs/SETUP.md`](../docs/SETUP.md).

## Notes
- **Run Claude Code as a non-root user** if you can — better practice and it unlocks `--dangerously-skip-permissions` (Claude Code refuses that flag as root). `setup.sh` defaults to root for simplicity; adapt `User` in the client SSH alias and the `HUGINN_HOME` for setup if you use a dedicated user.
- **Resources:** Node + Claude Code are light at idle; agentic runs use more CPU/RAM. 2 cores / 2 GiB is a floor; 4 / 4 is comfortable.
- **Off-LAN:** the cleanest path is Tailscale on the host + each device, then a `Host huginn` SSH alias pointing at the MagicDNS name.
