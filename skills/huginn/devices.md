# Devices

A device is one of the person's own computers that the server may run work on, in that
computer's own context: its files, its tools, its own Claude Code login. This machine is a device
when huginn core here is paired and `huginn device on` has been run. Nothing listens: the core
connects out to the server and asks for work. The machine builds the `claude` command line itself
from its own settings, so the server can never widen what a machine allows.

```bash
huginn device status          # settings, id, enrolled, busy, locked
huginn device on              # offer it, read-only (scope look)
huginn device on --scope work --root ~/work
huginn device on --act-while-locked
huginn device off             # withdraw it; the row on the server is removed
huginn devices                # every machine, as the server sees them (over SSH)
huginn devices watch <name> on|off   # alert when an always-on machine goes quiet
```

## Scopes

| Scope | ask (read, report) | act (change, run) | a run starts in |
|---|---|---|---|
| `look` | yes | no | the home directory |
| `work` | yes | yes | `--root` (home when unset) |
| `own` | yes | yes | the home directory |
| `generate` | local AI only | no | n/a (set by `huginn local on`, never by hand) |

Ask grants `Read Glob Grep WebFetch WebSearch Skill` and denies `Bash Edit Write NotebookEdit`;
act grants `Bash Read Edit Write Glob Grep WebFetch WebSearch Skill`. `work` is not a sandbox:
only the starting directory differs from `own`. A scope the machine cannot read counts as the
narrowest.

## Locked screens

While the screen is locked (Windows lock or sign-in screen; a locked Linux session or a login
screen with nobody signed in; or a lock state that cannot be read), act is withdrawn and the
machine behaves as `look`. A headless Linux box with no graphical seat never counts as locked.
`--act-while-locked` keeps act on that one machine. A running job finishes under the settings it
started with.

## What `huginn device status` shows

```text
  device:  on  (...)
           name laptop . scope work . root /home/alex/work . act while locked false
           id 3f2a... . enrolled true . busy false . unlocked
```

`enrolled false` with `on`: the core has not reached the server yet (see its note, and
`huginn core route`). A device runs one job at a time; the server queues the rest. The server
drops a machine it has not heard from for 30 days.

Widening a scope (`work`, `own`) or turning on act while locked lets the server run commands on
this machine unattended: ask the person first, every time.
