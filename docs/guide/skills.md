# Claude Code skills

Huginn ships three [skills](https://docs.anthropic.com/en/docs/claude-code/skills) for Claude
Code: short documents Claude Code loads when a conversation turns to Huginn, so that a Claude Code
on your computer, or a session on your server, knows what `huginn` is, where its files live and
what is safe to do.

| Skill | Where it loads | What it teaches |
|---|---|---|
| `huginn` | a Claude Code on a client computer | what Huginn is; where huginn core, the CLI, `core.json`, the secrets, the socket and the logs live on this computer per OS; every verb; the console; routes; device scopes; local AI; what to ask before changing |
| `huginn-doctor` | the same | the runbook for "huginn cannot be reached" or "will not start": `huginn doctor --json` first, then the chain binary, service, socket, pairing, routes and their TLS pins, ssh, server, with the commands per OS and where to stop and ask you |
| `huginn-server` | Claude Code sessions on the server | the server's layout (tmux, `huginn-appd`, the hooks, `/run/huginn-claude-state`, `/var/lib/huginn-appd`), the host tools, how to answer "is huginn healthy", and what not to touch without you |

They are plain Markdown in the repository under `skills/`, with placeholders only, and you can
read them there. A skill only informs: it grants no tool and changes no permission.

## On a client computer

huginn core carries `huginn` and `huginn-doctor` and lays them under its install directory on
every install and update (`~/.local/share/huginn/skills/` on Linux, `%LOCALAPPDATA%\huginn\skills\`
on Windows). The installer asks once whether to put them where Claude Code finds them:

```text
Install Huginn skills for Claude Code? [Y/n]
```

The question is asked only when Claude Code is on the computer (`claude` on `PATH`, or a
`~/.claude` directory) and nothing of Huginn's is in `~/.claude/skills` yet. With no terminal to
ask on, the answer is no, as for every installer question; the preset `HUGINN_SKILLS=1` or `0`
answers it up front. A no is remembered, so updates and later installs do not ask again;
`huginn skills install` is always there.

| Command | What it does |
|---|---|
| `huginn skills` (`status`) | One line per skill: linked, copied, stale, yours, or not installed; and whether Claude Code is here. Exit 1 when a skill is not installed as this version lays it. |
| `huginn skills install` | Put them in `~/.claude/skills`: on Linux a link per skill to the install directory's copy, on Windows a copy of each (a link needs a privilege there). Replaces only what Huginn put there; a skill you wrote under the same name is left alone and named. |
| `huginn skills remove` | Take Huginn's links or copies out again, and remember the no. The copies under the install directory stay with huginn core. |

Updates (`huginn update`, `huginn core update`) refresh what is already in `~/.claude/skills`;
`huginn uninstall` removes it. A Claude Code session loads a skill at its start: open a new one
after installing.

## On the server

`server/setup.sh` installs `huginn-server` to `/usr/local/share/huginn/skills/huginn-server/` and
links it into the sessions account's `~/.claude/skills/huginn-server`, as that account, so every
session on the server has it. Something else at that name is left alone, with a note.
`server/uninstall.sh` removes the link and the copy. There is no question: a server runs Claude
Code by definition.

## Checking a computer: `huginn doctor`

The `huginn-doctor` skill starts with `huginn doctor`, which you can run yourself. It is a
deterministic report of this computer's Huginn, with no Claude involved and nothing changed:

```bash
huginn doctor          # lines, like the installer's
huginn doctor --json   # [{"id","state","detail","fix"}, ...] for scripts
```

It walks the chain a client depends on, in order: the core binary and the CLI, the boot service,
the core's socket, the tray, the pairing, each server address (reachable, latency, and whether its
certificate is the one the pairing proved), the daemon through the core, this computer's device
enrolment, the local AI tier if installed, SSH (`Host huginn`, `ssh -G`, port 22 by a TCP
connect only, the known host key), Claude Code and the skills. Each row is `ok`, `warn`, `fail`
or `skip` (not applicable here) with the one thing to try. Exit status 0 when nothing failed,
1 otherwise. Take the first failure in the list: a break early in the chain explains every row
after it.

## Related

- [Install the clients](../install/clients.md): the installer's questions and presets.
- [CLI reference](../reference/cli.md): `huginn doctor` and `huginn skills`.
- [Install the server](../install/server.md): what `setup.sh` puts where.
