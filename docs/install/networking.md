# Networking

Huginn works on any network you control. Your computers and phone need to reach the server on
two ports:

| Port | Protocol | Used by |
|---|---|---|
| 22 | SSH | the terminal client: attaching to sessions, and the host-side commands |
| 8787 | HTTPS | huginn core, the console, the desktop app and the Android app (`huginn-appd`) |

## Keep it private

Do not expose either port to the internet. The access token is the only thing standing between a
caller and a root shell on the server, and SSH is a root login. Reach the server from:

- **your local network**, when your devices are at home or in the office with it, or
- **a VPN you control** when they are not: WireGuard, Tailscale, Headscale, ZeroTier, or your
  router's VPN server all work. Huginn needs nothing from the VPN except a route to the server.

A phone on mobile data reaches the server through the same VPN.

## Addresses

Clients are configured with the server's address, for example `192.168.1.20` or
`huginn.home.example`. Both kinds work, on the local network and over a VPN. A client can know
the server by several addresses (a LAN address at home and a VPN address away): huginn core keeps
a list and uses whichever answers.

```bash
huginn core route                     # the addresses this computer uses, and their health
huginn core route add https://10.8.0.1:8787
```

The desktop app shows the same list under Settings, Host.

## The daemon's listen address

`huginn-appd` listens on every interface by default (`0.0.0.0`), so it answers on the LAN, on a
VPN interface and on loopback. To listen on one address only, set `HUGINN_APPD_BIND` in
`/etc/systemd/system/huginn-appd.service.d/site.conf` and restart it:

```ini
[Service]
Environment=HUGINN_APPD_BIND=0.0.0.0
```

Narrowing it to a single non-loopback address breaks the tools on the server itself, which talk to
the daemon on `127.0.0.1`. To restrict who can connect, use a firewall instead.

## TLS

The daemon serves HTTPS with a self-signed certificate it creates on first start. Clients do not
need a certificate authority: when they pair, they verify the certificate against the access token
(a proof only the real daemon can produce) and remember it. Plain HTTP is accepted only from the
server itself.

Do not put a TLS-terminating reverse proxy in front of the daemon: clients pin the daemon's own
certificate, so a proxy's certificate fails that check. To use a certificate from your own
authority instead, see [Server configuration](../reference/server-config.md).

## Firewall

If the server runs a firewall, allow ports 22 and 8787 from your networks. With `ufw`:

```bash
ufw allow from 192.168.1.0/24 to any port 22,8787 proto tcp
```

If the server is a virtual machine or container behind a hypervisor firewall (Proxmox VE, for
example), add the rule there as well.
