# Security model

Huginn puts a capable AI coding agent on an always-on host and lets you drive it from anywhere. That's powerful — and worth understanding before you expose it. This is the *threat model & hardening* doc; for **reporting a vulnerability**, see [`/SECURITY.md`](../SECURITY.md).

## What's exposed — the SSH/tmux core

- **Whoever can SSH to the session can drive Claude Code** with whatever permissions it's been granted (run commands, edit files, hit the network) as the host user.
- **The host holds your Claude credentials** (`~/.claude/.credentials.json`) — subscription tokens or an API key.
- **Headless one-shots are not a smaller surface.** `huginn -p` reads any file the host user can (`Read`/`Glob`/`Grep` need no grant in headless mode), and a permissive `permissions.defaultMode` lets it run `Bash` and `Write` too. `--allowedTools` auto-approves; it never restricts. See [Usage → Headless one-shots](USAGE.md#headless-one-shots).

## What's exposed — `huginn-appd` (the app daemon)

`server/appd/` is optional: skip this section if you never deploy it. If you do, it is the largest and highest-privilege component in the system, and it is a **second** credential of equal power to your SSH key — revoking one does nothing to the other.

- **It runs as root and speaks plain HTTP on port 8787.** Not HTTPS. It assumes the transport under it is already private (a mesh VPN, your LAN); on anything else the token crosses in clear text.
- **Check what it binds.** By default it binds the host's Tailscale address (`tailscale ip -4`). Setting `HUGINN_APPD_BIND=0.0.0.0` — as this author's deployment does, so a phone can reach it over a second mesh as well — puts it on *every* interface the host has, and there is then no network boundary at all. That is a legitimate choice; it is not a free one, because the token becomes the only thing standing between the port and root.
- **A token holder can execute code, by design.** `POST /v1/sessions/<name>/keys` types arbitrary text into a Claude Code pane and `/answer` presses buttons in it; chats spawn `claude -p` on the host. There is no read-only tier. Anything the daemon exposes is equivalent to root on the host.
- **There is no rate limit on authentication.** Every route sits behind one constant-time bearer comparison and nothing else — no lockout, no backoff, no delay on a wrong token. Keep the port off the public internet; a 32-byte token is only unguessable while nobody is allowed to guess quickly.
- **Copies of the token are wherever you pasted it** — the Android app's settings, the desktop client's config file, any `curl` script or shell history. One token serves the whole host, so a lost phone means rotating for every device.
- **Rotating it:** write fresh random bytes to `/etc/huginn-appd/token` (0600, at least 32 characters or the daemon refuses to start), `systemctl restart huginn-appd` — it is read once at startup, so an edit alone changes nothing — then re-paste into every client. Until a client is updated, it gets a 401.
- **Richer credentials live behind it.** `/var/lib/huginn-appd/accounts/` holds a full OAuth blob per saved Claude login, and `/etc/huginn-appd/fcm-service-account.json` is a Google service-account key for push. Both are root-only on disk, and both are worth more to an attacker than the token that guards them.
- **Uploads are not filtered by type**, up to 128 MB each, into `/var/lib/huginn-appd/uploads/`. Deliberate — an attachment is whatever the phone had, and an `act` chat may need to `unzip` or `sqlite3` it — but an authenticated client can put arbitrary bytes on the host's disk.

## Hardening checklist

- ✅ **Key-only SSH.** Disable password auth (`PasswordAuthentication no`). One keypair per device — easy to revoke.
- ✅ **Restrict keys.** In `authorized_keys`, scope keys to your network, e.g. a Tailscale-only clause:
  `from="100.64.0.0/10" ssh-ed25519 AAAA… my-phone`. Phones are lost more often than laptops — restrict them harder.
- ✅ **Prefer a non-root user.** Run Claude Code as a dedicated unprivileged user. Smaller blast radius, and it unlocks Claude Code's permission modes (which it disables under root).
- ✅ **Don't put SSH on the public internet.** Use a mesh VPN (Tailscale/WireGuard) or your LAN. If you must expose it, restrict source IPs and use a non-standard setup.
- ✅ **Set Claude Code's permissions, not just the flags.** The fence is `permissions` in `~/.claude/settings.json` (and the project's). The allowlist `-y` passes only *auto-approves* what it names — a permissive `defaultMode` makes `-p` and `-y` the same thing, and tightening the allowlist does not narrow either one.
- ✅ **Protect the credentials file.** `chmod 600 ~/.claude/.credentials.json`; back up the host; rotate API keys if a host is compromised.
- ✅ **Unprivileged containers.** If using an LXC, keep it `unprivileged 1`.
- ✅ **Bind the daemon as narrowly as your setup allows.** Leave `HUGINN_APPD_BIND` unset (Tailscale-only) unless you have a reason; if you widen it, know that you have traded the network boundary for the token and firewall port 8787 accordingly.
- ✅ **Treat the app token as a root key.** Store it the way you'd store one, rotate it when a device is lost or the host is suspect, and don't paste it into anything you wouldn't hand an SSH key.

## What Huginn does *not* do

- It does not encrypt anything beyond SSH's transport — the daemon is plain HTTP and leans entirely on the network under it.
- It does not sandbox Claude Code's tool use — that's Claude Code's permission system, configured by you. Neither `-p` nor `-y` is a sandbox.
- It does not manage secrets — your project secrets on the host are visible to the agent.
- It does not throttle, log-and-alert, or lock out failed authentication on the daemon.

## Rules of thumb

- Treat the Huginn host like a machine you'd SSH into with full trust — because that's what it is.
- The phone is the weakest link: restrict its key, and lean on the lock screen + Tailscale.
- Review what tools/MCP servers you grant; an agent is only as safe as its narrowest permission.
- If you run the daemon, count your credentials. There are two of root-equivalent power, and only one of them is an SSH key.
