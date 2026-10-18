# Chats

A chat is a Claude Code conversation that runs headless on the server: no tmux session and no
terminal, only the question and the answer. Use one for a question or a small task; use a
[session](sessions.md) for long, interactive work you may want to watch or steer.

You start chats in the [apps](apps.md) (**New chat**) and, as one-shots, from any terminal with
`huginn -p` and `huginn -y`.

## Ask and Act

Every chat has a mode. It decides which tools Claude Code may use, and the server enforces it on
every run.

| Mode | Claude may | Denied | One-shot |
|---|---|---|---|
| Ask | read and search files on the server, fetch and search the web, use skills and any [extra tools](#extra-tools) | Bash, Edit, Write, NotebookEdit | `huginn -p` |
| Act | everything Ask may, and also run commands (Bash), edit and write files | none | `huginn -y` |

The line between the two is drawn at changing things. In Ask, Bash is denied outright rather than
left to Claude Code's per-command judgment, so the same request behaves the same way every time:
for something on the web, Ask uses WebFetch and WebSearch, never `curl`.

> Ask is read-only, not private. It can read any file the server's root user can read, the access
> token and Claude credentials included. Treat both modes as root.

## Model, effort and mode

Above the composer of every chat, in both apps:

| Chip | Choices |
|---|---|
| Model | The models offered by the Claude Code installed on your server, or **Host default** (whatever Claude Code on the server uses by default). |
| Effort | low, medium, high, xhigh, max, or **Host default**. |
| Ask / Act | The mode above. |

A change applies to the next message: a run keeps the settings it started with, so the chips are
disabled while one is in progress. The one-shots always use the host defaults.

## Where a chat runs

By default a chat runs on the server. When computers are enrolled as [devices](devices.md), the
**New chat** dialog also asks **Where it runs**: this server, or one of those computers, where the
chat runs in that machine's own context and within what it allows (a computer that allows only
looking, or is locked, offers Ask only). [Local AI](local-ai.md) models (experimental) appear in
the same dialog; a chat on one is always Ask, and a local model answers instead of Claude.

The rest of this page is about chats on the server.

## How a chat runs on the server

- **Working directory.** Every chat starts in `HUGINN_APPD_WORKDIR`, by default the daemon's home
  directory, `/root`. Claude Code reads that directory's `CLAUDE.md` and skills as it would for
  any session there. Set it in the daemon's settings file; see [Configure](#configure).
- **One conversation.** Each message continues the same Claude Code conversation. The app shows it
  the same way as a session's Conversation tab: text, thinking, tool calls and subagents.
- **In the background.** A run belongs to the server. Closing the app, locking the phone or losing
  the network never cancels it; open the chat again to catch up from where you left. **Stop**
  cancels it.
- **Queued messages.** A message sent while a run is going waits and is sent when the run ends.
- **Limits.** Three chats can run at once on a server; another is refused until one finishes. A
  single run stops after two hours.
- **Restarts.** Restarting or updating the daemon interrupts a run that is mid-answer. The chat
  says `interrupted: huginn-appd restarted while this was running`; send the message again if you
  still want the answer. Queued messages are delivered after the restart.
- **Notifications.** A finished chat notifies you, titled by the chat and showing the start of the
  answer. See [Notifications](notifications.md).

### What an Act chat can do

An Act chat runs as root on the server, like a session: it can install packages, edit files
anywhere and run any command. The daemon's sandbox only stops it from changing the kernel
(sysctl, kernel modules, cgroups). To confine Act chats further, a server can add a stricter
sandbox; see [Server configuration](../reference/server-config.md#a-stricter-sandbox).

## One-shots from a terminal

```bash
huginn -p "what is using the most disk under /var?"
huginn -y "rotate the logs in /srv/app/logs and tell me what you removed"
```

`huginn -p` runs one Ask turn and `huginn -y` one Act turn, prints the answer and exits. They run
on the server over SSH (set up with `huginn setup ssh`), through the server's `huginn-ask`
script, so they get exactly the same tools, persona and working directory as app chats. They do
not appear in the apps' chat list.


On the server itself, the same fence is available directly, with the prompt on standard input
(useful in scripts and cron jobs):

```bash
echo "summarise yesterday's failed systemd units" | huginn-ask ask
echo "clear the build cache in /root/app" | huginn-ask act
```

## Configure

The daemon reads its settings from
`/etc/systemd/system/huginn-appd.service.d/site.conf`, which installs and updates never
overwrite. After editing it:

```bash
sudo systemctl daemon-reload && sudo systemctl restart huginn-appd
```

| Variable | Default | What it does |
|---|---|---|
| `HUGINN_APPD_WORKDIR` | `$HOME` (`/root`) | The directory chats, rounds and one-shots run in. |
| `HUGINN_APPD_PERSONA_FILE` | `/etc/huginn/persona.md` | Optional text added to the system prompt. |
| `HUGINN_APPD_EXTRA_TOOLS` | unset | Extra tools granted in both modes, separated by spaces. |

All variables: [Server configuration](../reference/server-config.md).

### Persona

If `/etc/huginn/persona.md` exists (or the file `HUGINN_APPD_PERSONA_FILE` names), its text is
appended to the system prompt of every chat, every [round](rounds.md) and every `huginn -p` /
`huginn -y`. It is read on each run, so editing it needs no restart. With no file, there is no
persona. Interactive sessions do not use it; they read `CLAUDE.md` as usual.

```bash
sudo mkdir -p /etc/huginn
sudo tee /etc/huginn/persona.md >/dev/null <<'EOF'
You are the assistant on my home server. Answer briefly. Before changing anything
under /srv, say what you will change and why.
EOF
```

### Extra tools

`HUGINN_APPD_EXTRA_TOOLS` grants more tools to chats, rounds and one-shots, in both modes. The
usual use is an MCP server, such as a memory server. Add it to Claude Code for the root user, in
a root shell on the server:

```bash
claude mcp add --scope user memory -- npx -y @modelcontextprotocol/server-memory
```

```ini
[Service]
Environment=HUGINN_APPD_EXTRA_TOOLS=mcp__memory
```

`mcp__<server>` grants every tool of that MCP server; `mcp__<server>__<tool>` grants one. An
entry with characters other than letters, digits and `_ . : * ( ) -` is ignored. The daemon reads
the variable when it starts, so restart it after a change.

An extra tool is granted in Ask too. If Ask should stay read-only, grant only tools that do not
change anything.

### Skills

The Skill tool is granted in both modes, so chats can use the skills in the working directory's
`.claude/skills/` and the root user's own skills in `~/.claude/skills/`. A skill is instructions,
not a permission: it cannot use a tool the mode denies.
