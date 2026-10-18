# Projects

A project is a group of sessions that work on one thing together. Each project has a **lead**:
a Claude Code session that reads your brief, sizes the work and proposes the other sessions it
needs, each with a role, a first prompt and a directory. Nothing else starts until you approve
that proposal. Once you do, Huginn creates the members, and the lead and the members talk to
each other directly.

You run projects from the **Projects** page in the desktop app and the Android app, or with
`huginn projects` in a terminal.

## How a project runs

1. **You start it** with a name and a brief. Huginn makes the project's directory, starts the
   lead session in it, and types the brief in as the lead's first message.
2. **The lead proposes.** It explores, then ends its turn with a proposal: up to 12 member
   sessions, each with a role and a first prompt, and optionally a directory, a model and an
   effort level. The apps show the proposal as a card with **Spawn**, **Edit** and **Discard**.
3. **You approve it**, with **Spawn** in the app or `huginn projects spawn` in a terminal.
   Huginn creates one session per member and types in each member's first prompt.
4. **The sessions work together.** Members report to the lead, and the lead assigns work to
   members. Messages between them start a turn in the receiving session at once.
5. **You end it** when the work is done, which winds the sessions down.

To change a proposal, talk to the lead in its own session (`huginn <slug>-lead`) and it writes
a new one. An approval is tied to the version of the proposal you saw: if the lead has revised
it since, the approval is refused with "the proposal has changed" instead of starting sessions
you never read.

Huginn refuses to spawn members while the account is out of room (see
[Usage limits](usage-limits.md)), so a project is not started half-way into a full window.

## Names

A project's name becomes a **slug**: lowercase letters, digits and dashes, at most 24
characters. Each session in the project is named after the slug and its role:

| Name | Form | Used for |
|---|---|---|
| Session name | `<slug>-<role>` | attaching: `huginn api-rework-tests` |
| Peer name | `<slug>/<role>` | messages between sessions, and `huginn projects msg` |

The lead's role is `lead`, so its session is `<slug>-lead`. A role is lowercase letters, digits
and dashes, at most 16 characters. The slugs `login`, `main`, `huginn` and `lead` are reserved.

Project sessions are listed on the project's page, not on the **Sessions** page: the Sessions
page shows the sessions outside every project, with one line saying how many are inside. From
a project's page you can also bring an existing session in (**Add member**) or take one out
(**Drop from project**, which leaves the session running).

## Commands

```bash
huginn projects
huginn projects new "API rework" --kind software --brief - < brief.md
huginn projects show "API rework"
huginn projects spawn "API rework"
huginn projects msg "API rework" api-rework/lead api-rework/tests "Run the full suite now."
huginn projects end "API rework"
```

| Command | What it does |
|---|---|
| `huginn projects` | One line per project: members, how many need you, the lead's state, when it was created. Also `huginn projects list`. |
| `huginn projects show <project>` | The project's members, one line each, with the name to attach. |
| `huginn projects new <name> --brief TEXT\|- [--kind KIND] [--cwd DIR]` | Start a project and launch its lead. |
| `huginn projects spawn <project>` | Approve the lead's current proposal and create its members. |
| `huginn projects msg <project> <from> <to> <text>` | Put one message from one member into another member's session. |
| `huginn projects end <project> [--now \| --keep-sessions]` | End the project and wind its sessions down. |

`<project>` is the project's name, its slug or its id. When two projects share a name, the
command lists their ids and asks for one. Add `--json` to any command to print the daemon's
answer unformatted.

### `new`

| Option | Meaning |
|---|---|
| `--brief TEXT` | Required. The whole first message the lead receives, and what it sizes the project from. Up to 4,000 characters. `--brief -` reads it from standard input, so a brief in a file can be piped in. |
| `--kind KIND` | One of `software`, `infra`, `hardware`, `docs`, `research`, `other` (default `other`). A label only; nothing behaves differently by kind. |
| `--cwd DIR` | The project's directory: an absolute path on the server that already exists. Leave it out to have Huginn make one (see [Where projects live](#where-projects-live)). |

### `spawn`

`spawn` takes no member list and no prompts: it approves the plan the lead wrote, at the
version it is on. It works only while the project has a proposal waiting. If some members fail
to start, the ones that did start keep running, the command names the ones that did not and
why, and it exits with status 1.

### `msg`

`<from>` and `<to>` are peer names (`<slug>/<role>`). The text is everything after `<to>`, so
quotes are optional. The message starts a turn in the receiving session at once.

### `end`

| Option | What happens to the sessions |
|---|---|
| (none) | Each session gets the wrap-up phrase and ends once it goes idle, as with `huginn end`. |
| `--now` | Each session is ended immediately. |
| `--keep-sessions` | Only the project record is removed. The sessions keep running, and the command prints their names. |

A session that is showing a dialog (a permission or folder-trust question) cannot be handed the
wrap-up phrase. The command names it, leaves it running, and exits with status 1; end it with
`huginn kill <session>`.

### Exit status

| Status | Meaning |
|---|---|
| `0` | Done. |
| `1` | A refusal or usage error, or a `spawn` or `end` that only partly succeeded. |
| `2` | The daemon is not answering. |
| `3` | The daemon is too old to have projects. |

The `huginn projects` commands run on the server over SSH. On a computer set up from the
desktop app, run `huginn setup ssh` once first.

## Where projects live

A project created without `--cwd` gets its own directory, `<projects dir>/<slug>`, made when
the project is created. The projects directory is `~/projects` in the home directory of the
account that runs the sessions (`/root/projects` on a default install). Set
`HUGINN_APPD_PROJECTS_DIR` in the daemon's drop-in to move it; see
[Server configuration](../reference/server-config.md).

```ini
[Service]
Environment=HUGINN_APPD_PROJECTS_DIR=/srv/projects
```

Then run `systemctl daemon-reload && systemctl restart huginn-appd`. (If you added a stricter
sandbox with a `ReadWritePaths=` list, include the projects directory in it.)

### Trust the projects directory in Claude Code once

Claude Code asks whether to trust a folder the first time it opens there, and a session sitting
on that question cannot take part in a project. Huginn checks for trust before it starts a lead
and refuses with instructions if the directory is not trusted; it never answers the question
for you.

Trust is inherited from a parent directory, so trusting the projects directory once covers
every project made under it. On the server, as the account that runs the sessions:

```bash
mkdir -p ~/projects
cd ~/projects && claude
```

Accept the folder-trust question, then exit Claude Code. Do the same once for any directory you
pass with `--cwd` that is not under a trusted one.

## Limits

| Limit | Value |
|---|---|
| Members per project | 12 |
| Projects per server | 64 |
| Brief | 4,000 characters |

## Related

- [Sessions](sessions.md)
- [Usage limits](usage-limits.md)
- [CLI reference](../reference/cli.md)
