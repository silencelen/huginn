# Install the server

Huginn Server runs on one always-on Linux machine. It hosts Claude Code in persistent tmux
sessions and runs `huginn-appd`, the daemon your clients pair with.

## Requirements

- **Debian 12 or 13, or Ubuntu 22.04 or 24.04**, on x86_64 or arm64.
- **A machine of its own**: a virtual machine, a container (for example a Proxmox LXC; see
  [Proxmox container](proxmox-lxc.md)) or a small dedicated computer. Huginn runs Claude Code as
  root on this machine, and an agent that can run shell commands has the run of the machine it is
  on. Keep personal data and other services off it. The [security guide](../security.md) explains
  why the boundary is the machine.
- **2 GB of memory and 10 GB of disk** for a light workload; more for many parallel sessions.
- **Root access**, and an SSH server your other computers can reach (port 22).
- **A Claude subscription** (Pro or Max) or an Anthropic API key.

## Install

As root on the server:

```bash
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/server/get.sh | sudo bash
```

This downloads the newest Huginn release to `/usr/local/src/huginn` and runs its
`server/setup.sh`, which:

1. installs the packages Huginn needs: Node.js 22, Claude Code, tmux, Python 3, OpenSSL, jq,
   and `ccusage` for usage reports;
2. installs the host tools (`cc`, `huginn-status`, `huginn-headroom` and the others) to
   `/usr/local/bin`, the tmux configuration to `/root/.tmux.conf` (an existing one is saved as
   `.tmux.conf.pre-huginn`), Claude Code hooks that keep terminal titles current, and Claude
   Code's status line (`huginn-statusline`, which gives the apps each session's live context
   window; a status line you already had keeps printing, chained after it). It also turns Claude
   Code's feedback prompts off and sets `fastModePerSessionOptIn` where those settings are absent;
   see [Server configuration](../reference/server-config.md#claude-code-settings-the-installer-writes);
3. installs `huginn-appd`: the daemon in `/opt/huginn-appd`, an access token in
   `/etc/huginn-appd/token`, a TLS certificate, and the `huginn-appd` systemd service;
4. checks that the daemon answers, and prints your next steps.

You can also install from a clone:

```bash
git clone https://github.com/silencelen/huginn.git
sudo bash huginn/server/setup.sh
```

### Options

Set these before the command (for the one-liner: `curl … | sudo HUGINN_WORKDIR=/root/work bash`):

| Variable | Effect |
|---|---|
| `HUGINN_WORKDIR=/path` | Where new sessions and chats start. Default: root's home directory. |
| `HUGINN_NO_DAEMON=1` | Install a terminal-only server, without `huginn-appd`. Clients can then only attach over SSH: no console, desktop app, Android app or devices. |
| `HUGINN_REF=<tag>` | Install a specific release instead of the newest. |
| `HUGINN_FORCE=1` | Overwrite host tools that were edited on this machine (otherwise they are kept and listed). |
| `HUGINN_KEEP_CLAUDE_FEEDBACK=1` | Leave Claude Code's feedback prompts on (the installer otherwise sets `feedbackSurveyRate: 0`, `feedbackDrafts: "off"` and `CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY=1` where they are absent). |

## Sign Claude Code in

Once, as root on the server:

```bash
claude
```

Choose to sign in with your Claude account (the code-paste flow works over SSH), or set
`ANTHROPIC_API_KEY` in root's environment for metered API billing.

## Check it

```bash
huginn-status                     # uptime, Claude sign-in, sessions, disk
systemctl status huginn-appd      # the daemon
```

The access token your clients will ask for:

```bash
cat /etc/huginn-appd/token
```

Treat it like a password for the whole machine: anyone who has it can run commands as root on the
server. Paste it only into your own devices.

## Next

- Make sure your computers can reach the server: [Networking](networking.md).
- Install the client on each computer: [Install the clients](clients.md).
- Change the daemon's settings (listen address, working directory, alerts): [Server
  configuration](../reference/server-config.md).

## Update

Run the same command again. It downloads the newest release and re-runs setup, which replaces the
host tools and the daemon and keeps your token, certificate, data, sessions and settings. Running
sessions are not interrupted.

## Uninstall

```bash
sudo bash /usr/local/src/huginn/server/uninstall.sh            # keeps chats, settings and the token
sudo bash /usr/local/src/huginn/server/uninstall.sh --purge    # removes those too
```

It removes the daemon, its service and drop-ins, the Claude Code hooks setup added, Huginn's status
line (the one you had before it is put back) and the host tools. Claude Code, Node.js, tmux and
your files stay, and so do the fast-mode and feedback settings the installer added to
`~/.claude/settings.json`. `--purge` deletes the token, which disconnects every paired client.
Remove each client separately: [Uninstall](uninstall.md).
