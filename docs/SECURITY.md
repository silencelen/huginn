# Security model

Huginn puts a capable AI coding agent on an always-on host and lets you drive it from anywhere. That's powerful — and worth understanding before you expose it. This is the *threat model & hardening* doc; for **reporting a vulnerability**, see [`/SECURITY.md`](../SECURITY.md).

## What's exposed

- **Whoever can SSH to the session can drive Claude Code** with whatever permissions it's been granted (run commands, edit files, hit the network) as the host user.
- **The host holds your Claude credentials** (`~/.claude/.credentials.json`) — subscription tokens or an API key.
- **There is no extra auth layer.** Huginn is SSH + tmux; your SSH posture *is* your security posture.

## Hardening checklist

- ✅ **Key-only SSH.** Disable password auth (`PasswordAuthentication no`). One keypair per device — easy to revoke.
- ✅ **Restrict keys.** In `authorized_keys`, scope keys to your network, e.g. a Tailscale-only clause:
  `from="100.64.0.0/10" ssh-ed25519 AAAA… my-phone`. Phones are lost more often than laptops — restrict them harder.
- ✅ **Prefer a non-root user.** Run Claude Code as a dedicated unprivileged user. Smaller blast radius, and it unlocks Claude Code's permission modes (which it disables under root).
- ✅ **Don't put SSH on the public internet.** Use a mesh VPN (Tailscale/WireGuard) or your LAN. If you must expose it, restrict source IPs and use a non-standard setup.
- ✅ **Mind the tool permissions.** Interactive Claude Code prompts before acting; headless `huginn -y` runs with an allowlist. Keep that allowlist as tight as your use case allows.
- ✅ **Protect the credentials file.** `chmod 600 ~/.claude/.credentials.json`; back up the host; rotate API keys if a host is compromised.
- ✅ **Unprivileged containers.** If using an LXC, keep it `unprivileged 1`.

## What Huginn does *not* do

- It does not encrypt anything beyond SSH's transport.
- It does not sandbox Claude Code's tool use — that's Claude Code's permission system, configured by you.
- It does not manage secrets — your project secrets on the host are visible to the agent.

## Rules of thumb

- Treat the Huginn host like a machine you'd SSH into with full trust — because that's what it is.
- The phone is the weakest link: restrict its key, and lean on the lock screen + Tailscale.
- Review what tools/MCP servers you grant; an agent is only as safe as its narrowest permission.
