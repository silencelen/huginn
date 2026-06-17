# FAQ

### Is this just `ssh` + `tmux`?
Yes — packaged. Huginn is `ssh -t host tmux …` with multi-device-friendly tmux defaults, a one-word command (with subcommands + tab-completion) that works the same in PowerShell **and** bash/Termux, a container template, and a one-shot setup. No new protocol — just the sharp edges filed off.

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

### My one-shot `huginn -y` won't run tools / says it needs permission.
Claude Code refuses `--dangerously-skip-permissions` when running as **root**, so `-y` uses an explicit tool allowlist (`Bash Read Edit Write Glob Grep WebFetch`). For broader headless autonomy, run the node as a **non-root user**.

### Can Claude remember things across sessions?
Add an MCP memory server to the host's Claude Code (`claude mcp add …`). That's the "Muninn" half — out of scope for this repo, but the hook is there. See [Architecture → Extending it](ARCHITECTURE.md#extending-it).

### Is it secure to run an AI agent on a server like this?
It's as exposed as your SSH. Use key-only auth, restrict keys (e.g., Tailscale-only `from=` clauses), prefer a non-root user, and understand that anyone who can reach the session can drive Claude Code with whatever permissions it has. See [`SECURITY.md`](SECURITY.md).

### Windows: `huginn` isn't recognized after install.
Reload your profile (`. $PROFILE`) or open a new PowerShell window. The installer appends a source line to `$PROFILE`.

### Does this work with the JetBrains/VS Code Claude Code integrations?
This is the terminal flow. You run `claude` inside the tmux session over SSH. IDE integrations are a separate path.
