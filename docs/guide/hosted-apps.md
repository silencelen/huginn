# Hosted apps

Sessions on the server often build small web pages: a dashboard, a tool, a docs site, an
experiment. The **Apps** page in the desktop app and the Android app is the list of those pages,
so you can open any of them from any device. For each one Huginn checks two things:

- **is it up**: does it answer when the server itself opens it;
- **can your devices reach it**: does it answer on every address your clients use to reach the
  server.

When a check fails, the row says what failed and gives you the lines that would fix it.
**Huginn never opens a port, never edits a firewall and never runs those lines.** You read
them, and you run them where they belong.

## Add an app

On the **Apps** page, choose **Add app**.

| Field | What it is |
|---|---|
| **Name** | Up to 60 characters. |
| **Address** | The page's URL, starting with `http://` or `https://`. |
| Kind | `dashboard`, `tool`, `docs`, `lab` or `other`. A label only. |
| **Unit (optional)** | The systemd unit that serves the page, such as `trainer.service`. Huginn never starts, stops or reads it; the name only makes the fix lines exact. |
| **Also check from (optional)** | Extra addresses this app must answer at, besides loopback and your devices' routes. Up to 8 bare hosts, with no port, separated by spaces or commas. |
| **Note** | One line, up to 200 characters. |

The address must be one the server can check without reaching the public internet:

- loopback (`127.0.0.1`, `localhost`);
- a private LAN address (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`);
- a `100.64.0.0/10` address or a `*.ts.net` name (common VPN overlays);
- a private IPv6 address (`fc00::/7`);
- a name with no dot in it, such as `nas` or `huginn`, which your local DNS or hosts file
  resolves.

Public addresses and public domain names are refused, because the daemon fetches each address
on a timer. An address may not carry a username or password, or contain `..` path segments.

The server keeps up to 32 apps. Each app also gets the page's icon, fetched from the page.

Sessions can register the pages they build through the daemon's API (`POST /v1/apps`, with
the access token); see the [API reference](../reference/api.md).

A new server starts with an empty list.

## Is it up

The daemon opens each app's address from the server, with a 2-second limit, every 5 minutes.
Opening the Apps page starts a fresh check when the last one is more than 30 seconds old, and
**Check now** on a row checks it immediately. An app that does not answer within the limit
counts as down.

## Can your devices reach it

A page the server can open is not necessarily one your phone can open: a service bound to
`127.0.0.1`, or a firewall between your devices and the server, keeps it out of reach. So for
an app the server itself serves, Huginn checks the app at every address it **must** answer on:

- `127.0.0.1`;
- every address your devices have pinned as a route to the server (each desktop app and Android
  app reports its routes when it starts, and a reported route counts for 30 days);
- any address in the app's **Also check from**.

When one of those saved addresses stops answering, the Apps page names the device that saved it
and offers **Forget**, which removes the address for every device. A device that still has it
saved reports it again the next time it starts, so forget an address once nothing uses it.

Huginn also checks, for information only, the addresses clients have actually arrived on in the
last 7 days. These show under **Checked from** on the row and never fail it on their own.

**Adding an app that does not answer at every required address is refused**, with the
addresses that failed and what happened at each. An app already on the list that stops
answering is marked, not removed.

An app whose address names another machine is checked only at its own address and any
**Also check from** addresses, because Huginn cannot know how your devices reach that machine.
It gets no fix lines.

## Fix lines

A row that fails a check carries the lines that would fix it, and **Copy fix** copies them. They
come in two parts, because there are usually two machines involved.

**On the server** ("on huginn" in the lines). Which lines depend on what the check saw:

- **Refused**: nothing is listening at that address, which a rebind fixes. With a unit on the
  row, the lines are `systemctl edit <unit>` (change the bind address in `ExecStart` to
  `0.0.0.0`), `systemctl restart <unit>`, and `ss -ltn` to confirm. Without a unit, a sentence
  names the address the app does answer on.
- **Timed out**, or an HTTP 5xx: the connection was accepted, so the bind is not the problem
  and the app itself is not answering. With a unit, the lines are `systemctl status`,
  `journalctl -u <unit>` and `systemctl restart`.

**In the firewall**, wherever the firewall that filters traffic to the server is: one rule per
client network that could not get through. The source of each rule is the clients themselves,
as the daemon saw them connect:

- three or more clients in one private `/24` become that `/24`; one or two stay single
  addresses;
- VPN overlay (`100.64.0.0/10`) and IPv6 clients are never widened;
- loopback is never a source; a failing `127.0.0.1` needs no firewall rule;
- where no client has been seen yet, the line is a comment telling you to add the network
  yourself.

Example: an app at `http://192.168.1.20:8091/` whose unit binds only `127.0.0.1`, reached by a
phone, a laptop and a tablet on `192.168.1.0/24`:

```text
# on huginn — 192.168.1.20 does not reach this app
systemctl edit trainer.service   # ExecStart: bind 0.0.0.0 instead of 127.0.0.1
systemctl restart trainer.service
ss -ltn | grep :8091
# in the firewall that filters traffic to this host, if there is one
allow tcp from 192.168.1.0/24 to port 8091
```

### Firewall dialect

By default the firewall lines are written in a neutral form, `allow tcp from <source> to port
<port>`, for you to translate to your firewall. If the server is a Proxmox VE guest and the
firewall is the Proxmox guest firewall, the daemon can write Proxmox syntax instead:

| Variable | Default | What it sets |
|---|---|---|
| `HUGINN_APPD_FIREWALL` | `generic` | `generic`, or `proxmox` for Proxmox VE guest-firewall lines |
| `HUGINN_APPD_FIREWALL_HOST` | `the Proxmox host` | with `proxmox`: the hypervisor the header names |
| `HUGINN_APPD_FIREWALL_FILE` | `/etc/pve/firewall/<vmid>.fw` | with `proxmox`: the firewall file the header names |

Set them in the daemon's drop-in, `/etc/systemd/system/huginn-appd.service.d/site.conf`:

```ini
[Service]
Environment=HUGINN_APPD_FIREWALL=proxmox
Environment=HUGINN_APPD_FIREWALL_HOST=pve1
Environment=HUGINN_APPD_FIREWALL_FILE=/etc/pve/firewall/105.fw
```

Then run `systemctl daemon-reload && systemctl restart huginn-appd`. The same example as above,
in Proxmox syntax:

```text
# on pve1 — /etc/pve/firewall/105.fw
IN ACCEPT -source 192.168.1.0/24 -p tcp -dport 8091 -log nolog
```

These values only ever appear as text in the fix lines. See also
[Server configuration](../reference/server-config.md) and [Networking](../install/networking.md).

## Related

- [Networking](../install/networking.md)
- [Server configuration](../reference/server-config.md)
- [API reference](../reference/api.md)
