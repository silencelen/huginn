# FAQ

### Is this just `ssh` + `tmux`?
The `huginn` command, yes — packaged. It's `ssh -t host tmux …` with multi-device-friendly tmux defaults, a one-word command (with subcommands + tab-completion) that works the same in PowerShell **and** bash/Termux, a container template, and a one-shot setup. No new protocol — just the sharp edges filed off. The repo also ships the parts that *aren't* ssh + tmux and are opt-in: `huginn-appd` (an HTTP daemon on the host) and the Android/desktop apps that talk to it. Deploy those and the answer changes — see [`SECURITY.md`](SECURITY.md).

### Do I need a Claude Max/Pro subscription?
No, but it's the point. Claude Code can log in with a **Max/Pro subscription** (flat cost — an always-on agent you talk to all day doesn't run up an API bill) **or** an `ANTHROPIC_API_KEY` (metered). Use whichever you have.

### What host do I need?
Any always-on Debian/Ubuntu box reachable over SSH: a Proxmox LXC, a VM, a Raspberry Pi, an old laptop, or a cloud instance. ~2 cores / 2 GiB is a floor; 4 / 4 is comfortable for agentic work. See [`provision/`](../provision/).

### How does it work from my phone?
Install [Termux](https://termux.dev) + OpenSSH on the phone, run `client/install.sh`, and you're in. For cellular/off-LAN access, put the host and phone on [Tailscale](https://tailscale.com) and point the SSH alias at the MagicDNS name.

### Two devices are attached and the screen is tiny — why?
That's the **mirror**: tmux sizes a shared session to the *smallest* attached screen so both clients see the full content. To go full-screen on one device: `huginn solo` (kicks the others on attach) or press **`Alt-o`** while attached. Use different session **names** (`huginn work`) to avoid mirroring entirely.

### My laptop slept / Wi-Fi dropped — do I lose the session?
No. The session runs in tmux **on the host**, so a dropped link only kills the local `ssh` client, not the work. The attach **auto-reconnects** you within ~45s of waking, right where you left off — mirror if another device is attached, otherwise full-screen. Press `Ctrl-C` during the retry to stop it, or set `HUGINN_NO_RECONNECT=1` to turn it off. (PowerShell note: a session you opened *before* updating still runs the old client — open a fresh window so the new auto-reconnect loop is the one driving the attach.)

### Can I name my terminal tabs?
They name themselves — the attach labels the tab `huginn:<session>`, so `huginn costtracking` gives a `huginn:costtracking` tab (Windows Terminal / iTerm / Termux), restored when you leave. Disable with `HUGINN_NO_TITLE=1`. If a tab won't rename, your terminal is likely set to suppress application title changes, or has a pinned tab title.

### How do I scroll back?
`Ctrl-b [` enters scroll mode (then `PageUp`/arrows, `q` to quit). For touch-scroll on mobile, uncomment `set -g mouse on` in `server/tmux.conf` (trade-off: desktop drag-select then needs Shift).

### Can I run more than one session?
Yes. `huginn` is the `main` session; `huginn <name>` creates/attaches others. `huginn list`, `huginn rename`, `huginn kill` manage them.

### Where do sessions open (working directory)?
`$HOME` by default. Set `export HUGINN_WORKDIR=/path/to/project` in the host login shell to change it.

### Is `huginn -p` safe to point at an untrusted prompt? It has no tools, right?
No — it has tools. `-p` and `-y` differ only in which tools the client *auto-approves*, and `--allowedTools` never restricts the ones it leaves out. Headless Claude Code gets `Read`/`Glob`/`Grep` for free, so a `-p` one-shot reads any file the host user can; and if the host's `~/.claude/settings.json` uses a permissive `permissions.defaultMode`, `-p` will run `Bash` and `Write` as well. Treat both flags as "an agent with your shell account", not as a sandbox. See [Usage → Headless one-shots](USAGE.md#headless-one-shots).

### My one-shot `huginn -y` won't run tools / says it needs permission.
Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist (`Bash Read Edit Write Glob Grep WebFetch`). For broader headless autonomy, run the node as a **non-root user**.

### Can Claude remember things across sessions?
Add an MCP memory server to the host's Claude Code (`claude mcp add …`). That's the "Muninn" half — out of scope for this repo, but the hook is there. See [Architecture → Extending it](ARCHITECTURE.md#extending-it).

### Is it secure to run an AI agent on a server like this?
The terminal path is as exposed as your SSH: use key-only auth, restrict keys (e.g., Tailscale-only `from=` clauses), prefer a non-root user, and understand that anyone who can reach the session can drive Claude Code with whatever permissions it has. If you also deploy `huginn-appd` for the phone/desktop apps, you have a **second** credential of the same power — a bearer token on an HTTP port, held by every device you paste it into, and revoking an SSH key does nothing to it. Read [`SECURITY.md`](SECURITY.md) before exposing either.

### Windows: `huginn` isn't recognized after install.
Reload your profile (`. $PROFILE`) or open a new PowerShell window. The installer appends a source line to `$PROFILE`.

### Does this work with the JetBrains/VS Code Claude Code integrations?
This is the terminal flow. You run `claude` inside the tmux session over SSH. IDE integrations are a separate path.
