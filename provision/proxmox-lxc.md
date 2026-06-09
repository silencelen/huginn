# Provision a Huginn host as a Proxmox LXC

This is the container template the project was built on: a small, unprivileged Debian LXC. Adjust the placeholders (`<VMID>`, IPs, bridge, storage) to your environment.

## Create the container

```bash
# Download a Debian template (once):
pveam update && pveam available | grep debian-13
pveam download local debian-13-standard_13.1-2_amd64.tar.zst

# Create the LXC (run on the Proxmox host):
pct create <VMID> local:vztmpl/debian-13-standard_13.1-2_amd64.tar.zst \
  --hostname huginn \
  --cores 4 --memory 4096 --swap 2048 \
  --rootfs <STORAGE>:20 \
  --unprivileged 1 --features nesting=1 \
  --net0 name=eth0,bridge=vmbr0,ip=<LAN_IP>/24,gw=<GATEWAY>,type=veth \
  --nameserver <DNS> \
  --onboot 1 --ostype debian \
  --ssh-public-keys ~/.ssh/your_laptop_key.pub
```

Notes:
- **`nesting=1`** is required (npm/systemd inside the container).
- **20 GiB rootfs** is plenty for Node + Claude Code + your working tree; bump if your projects are large.
- Pick CPU/RAM for how heavy your sessions get — 4 cores / 4 GiB is comfortable; bump for parallel/agentic work.

## (Optional) Tailscale, for reaching it from anywhere

If you want `huginn` to work off your LAN (e.g. from your phone on cellular), add TUN passthrough so the container can run Tailscale:

```bash
cat >> /etc/pve/lxc/<VMID>.conf <<'EOF'
lxc.cgroup2.devices.allow: c 10:200 rwm
lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
EOF
pct start <VMID>
pct exec <VMID> -- bash -c 'curl -fsSL https://tailscale.com/install.sh | sh && tailscale up --hostname=huginn'
```
Then point each device's `Host huginn` alias at the Tailscale MagicDNS name `huginn` (works on-LAN and off-LAN).

## Then

Inside the container, follow [`../server/`](../server/):
```bash
git clone https://github.com/<you>/huginn.git
sudo bash huginn/server/setup.sh
claude    # log in once (Max/Pro subscription, or API key)
```

> Any always-on Debian/Ubuntu host works the same way — a VM, a Raspberry Pi, or a cloud box. See [`generic-host.md`](generic-host.md).
