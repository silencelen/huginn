# Run the server in a Proxmox container

A small unprivileged Debian container on Proxmox VE is a good home for Huginn Server: it is
isolated from everything else, cheap to back up, and easy to rebuild.

## Create the container

On the Proxmox host, download a Debian template once:

```bash
pveam update
pveam available | grep debian-13
pveam download local debian-13-standard_13.6-1_amd64.tar.zst   # use the version listed
```

Create the container, adjusting the ID, storage, network and key to your setup:

```bash
pct create 210 local:vztmpl/debian-13-standard_13.6-1_amd64.tar.zst \
  --hostname huginn \
  --cores 4 --memory 4096 --swap 1024 \
  --rootfs local-lvm:20 \
  --unprivileged 1 --features nesting=1 \
  --net0 name=eth0,bridge=vmbr0,ip=192.168.1.20/24,gw=192.168.1.1,type=veth \
  --onboot 1 \
  --ssh-public-keys ~/.ssh/id_ed25519.pub
pct start 210
```

- **`nesting=1` is required**: systemd inside the container needs it to run services such as
  `huginn-appd` and `huginn-tmux`.
- **Size**: 2 cores and 2 GB of memory are enough for a few light sessions; 4 and 4 are comfortable
  for parallel or long agentic work. 20 GB of disk covers Node.js, Claude Code and a working tree.
- A fixed address (or a DHCP reservation) makes the server easy to find from your devices.

## Install Huginn

Inside the container (`pct enter 210`, or SSH as root):

```bash
apt-get update && apt-get install -y curl
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/server/get.sh | bash
claude      # sign Claude Code in, once
```

Then continue with [Install the clients](clients.md).

## A VPN inside the container

To reach the server from outside your network, the simplest option is often a VPN on your router
or on the Proxmox host, which needs nothing in the container. If you run a VPN client such as
Tailscale or WireGuard inside the container instead, give it the TUN device first:

```bash
cat >> /etc/pve/lxc/210.conf <<'CONF'
lxc.cgroup2.devices.allow: c 10:200 rwm
lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
CONF
pct reboot 210
```

See [Networking](networking.md) for the ports clients need.

## Firewall

If you use the Proxmox firewall for this container, allow TCP 22 and 8787 from your networks. The
Hosted apps feature can write its suggested rules in Proxmox syntax: see
[Hosted apps](../guide/hosted-apps.md).
