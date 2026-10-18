# Devices

A device is one of your own computers that Huginn may run work on, in that computer's own
context: its files, its tools and its own Claude Code. From the desktop app or the Android app
you can then start a chat that runs on your laptop instead of on the server, or schedule a
[round](rounds.md) there.

You decide what each computer allows, on that computer. The server only ever sends a request;
the computer turns it into a command line itself, from its own settings.

## What a device needs

- **huginn core**, installed and paired with the server. It enrols the computer and runs the
  work. See [Install the clients](../install/clients.md).
- **Claude Code** on that computer, signed in. A device run is `claude` on that computer, with
  that computer's own login. It is found on `PATH`, or wherever `--claude` points.
- **Nothing inbound.** huginn core connects out to the server and asks for work. The computer
  listens on no port, so offering it needs no firewall change, on your network or elsewhere.

## Turn it on

```bash
huginn device on                              # offer this computer, read-only (scope look)
huginn device on --scope work --root ~/work   # allow changes, starting in ~/work
huginn device status                          # what it offers, and what the server sees
huginn device off                             # stop offering it
```

`huginn device` with no verb is `huginn device status`. The client installer asks whether to
offer the computer (default yes, read-only); `huginn device on` and `huginn device off` change
that at any time.

In the desktop app the same settings are under **Settings → Devices**: **Available to huginn**,
**What it may do**, **Keep act mode while locked**, **Folder for Work runs** and
**Path to claude**.

### Options for `huginn device on`

Only the options you pass change; the others keep their current values.

| Option | What it sets |
|---|---|
| `--scope look\|work\|own` | What the server may ask of this computer (see [Scopes](#scopes)). A first enrolment with no scope is `look`. |
| `--root DIR` | Where a run starts at scope `work`. Ignored at other scopes. |
| `--name NAME` | The name shown in device lists. Default: the computer's host name. 1 to 40 letters, digits, spaces, dots, dashes or underscores. |
| `--claude PATH` | The `claude` program to run, when the one on `PATH` is not the right one. |
| `--act-while-locked` | Keep act mode while the screen is locked (see [Locked screens](#locked-screens)). |
| `--no-act-while-locked` | Withdraw act mode while the screen is locked. This is the default. |

`huginn device status` prints the current settings, the device's id, whether it is enrolled,
whether it is busy, and whether the screen is locked:

```text
  device:  on  (...)
           name laptop · scope work · root /home/alex/work · act while locked false
           id 3f2a... · enrolled true · busy false · unlocked
```

## Scopes

A scope is the most this computer will do for a request. Requests come in two modes: **ask**
(read and report) and **act** (change things and run commands).

| Scope | Ask | Act | Where a run starts |
|---|---|---|---|
| `look` | yes | no | the home directory |
| `work` | yes | yes | the `--root` directory (the home directory when none is set) |
| `own` | yes | yes | the home directory |

The two modes grant these Claude Code tools:

| Mode | Allowed | Denied |
|---|---|---|
| ask | `Read`, `Glob`, `Grep`, `WebFetch`, `WebSearch`, `Skill` | `Bash`, `Edit`, `Write`, `NotebookEdit` |
| act | `Bash`, `Read`, `Edit`, `Write`, `Glob`, `Grep`, `WebFetch`, `WebSearch`, `Skill` | none |

> **`work` is not a sandbox.** The only difference between `work` and `own` is the directory a
> run starts in. A run with `Bash` can leave that directory. The real limits are the scope
> itself and the lock rule below. Give `work` or `own` only to a computer you would let Claude
> Code act on unattended.

A computer that serves [local AI](local-ai.md) holds a second, separate enrolment at a fourth
scope, `generate`. That scope runs local-model requests only, never Claude, and nothing else
runs on it. `huginn local on` creates it; you do not set it with `huginn device on`.

A scope this computer cannot read (a typo in a hand-edited settings file, for example) counts
as the narrowest one, which runs no Claude work at all.

## Who decides

The server sends a request: the prompt, the mode, and optionally a model, an effort level and a
conversation to resume. It never sends tool permissions. The computer checks the request
against its own scope and lock state, then builds the `claude` command line itself. It also
checks the model name and effort level before using them.

This is why the scope can only be widened on the computer itself. Someone who reaches the
server can queue work, but cannot make a `look` computer run `Bash`.

The server checks the same rules before it queues anything, so a request that cannot run is
refused when you make it, in plain words, for example:

```text
this machine is set to look, which cannot run act
this machine is locked, so it is read-only until someone unlocks it
```

The computer's own check is the one that counts.

> A paired computer holds a copy of the server's access token, which is root-equivalent on the
> server. Only pair computers you trust as much as the server, and rotate the token if one is
> lost. See [Security](../security.md).

## Locked screens

When nobody is at the computer, act mode is withdrawn: while the screen is locked, the computer
behaves as `look` and runs only ask requests. A running job finishes under the settings it
started with.

What counts as locked:

| Computer | Locked when |
|---|---|
| Windows | the lock screen or the sign-in screen is showing |
| Linux with a desktop | the session is locked, or the login screen is showing with nobody signed in |
| Linux with no graphical seat (a server, a container) | never: there is no screen to lock |
| Any, when the lock state cannot be read | always: unknown counts as locked |

To let a computer keep acting while locked, for example a desktop that works unattended
overnight, turn it on there:

```bash
huginn device on --act-while-locked
```

Only that setting, on that computer, changes this; it widens nothing else. A `look` computer is
still `look`. The computer keeps reporting its lock state either way, and device lists show
both the enrolled scope and what it does right now (`own -> look while locked`).

Local AI serving is not affected by the lock: a `generate` enrolment runs no tools, so there is
nothing to withdraw.

## Run work on a device

- **From the apps.** The **Devices** page lists your computers. **Ask here** and **Act here**
  start a new chat that runs on that computer. Each button is available only while the
  computer is reachable, free, and its current scope allows that mode.
- **On a schedule.** A round can run on a device; see [Rounds](rounds.md).

A device runs one job at a time; further work waits in a queue for it. Stopping a chat that is
still queued takes the work back before the computer picks it up. Stopping one that is already
running reaches the computer at its next report, and the computer ends the run itself.

## See all devices

```bash
huginn devices
```

```text
(o> huginn - devices

  + laptop
    linux  .  work  .  idle
    v2.5.8  .  work runs in /home/alex/work (not a sandbox)

  o office-pc  (watched: expected always on)
    windows  .  own -> look while locked  .  not reachable  (last seen 12m ago)

  2 machines, 1 reachable
```

One entry per computer. The mark is `*` while a job is running, `+` when the computer is
reachable, and `o` while it is waiting to check in again. A computer that also serves local AI
shows a second line with the models it serves.

| State | Meaning |
|---|---|
| `working on a job` | running a request now |
| `idle` | reachable and free |
| `N queued` | work is waiting for it |
| `not reachable (last seen ...)` | not heard from for 3 minutes or more |
| `free? unknown - has not asked for work since huginn restarted` | reachable, but it has not asked for work since the daemon restarted, so it may still be busy |

A computer the server has not heard from for 30 days is dropped from the list. To remove one
sooner, use **Forget** on the Devices page.

`huginn devices` runs on the server over SSH. On a computer set up from the desktop app, run
`huginn setup ssh` once first.

## Watch an always-on computer

A computer that should never go quiet, such as a desktop that runs rounds, can be watched:

```bash
huginn devices watch office-pc on
huginn devices watch office-pc off
```

Name it by its name or its id. When two devices share a name, the command lists their ids and
asks you to pick one.

The server then messages you when a watched computer has not checked in for 10 minutes, and
again when it is back. A computer that keeps dropping out is reported at most once every
6 hours. The messages go through the alert relay script set in `HUGINN_APPD_TELEGRAM_SCRIPT`;
with no relay configured, nothing is sent. See [Server configuration](../reference/server-config.md).

## Starting at boot

huginn core starts when the computer starts, before anyone signs in, so a device is available
after a reboot without a login.

**Linux.** huginn core runs as a systemd user service, `huginn-core.service`, and
`huginn core install` enables *linger* for your account so the service starts at boot. If
linger cannot be enabled, the service starts at your next login instead, and the installer
says so; an administrator can turn it on later:

```bash
sudo loginctl enable-linger "$USER"
```

**Windows.** huginn core runs as the **Huginn Core** scheduled task, as your account, with
nobody signed in. Registering it asks for administrator approval once. The task starts at boot,
at your sign-in, and every 5 minutes if it is not already running, so it recovers by itself if
it stops. `huginn core install --logon` picks how the task signs in as you:

| `--logon` | Behavior |
|---|---|
| `s4u` (default) | No stored password. The task has no network credentials. |
| `password` | Task Scheduler stores your password. Works everywhere, but must be redone when the password changes. |

Check either with `huginn core status`.

## Computers without huginn core

On a computer with only the `huginn` command, `huginn device` uses a small Node.js runner
instead of huginn core. It needs Node.js, and adds `huginn device update`, `huginn device unit`,
`huginn device serve` and a `--url` option for the daemon's address. With huginn core installed
those verbs are not needed, and `huginn device on` does not take `--url`.

## Related

- [Rounds](rounds.md): scheduled work, on the server or on a device
- [Local AI](local-ai.md): serve local models from a computer
- [Security](../security.md)
- [CLI reference](../reference/cli.md)
