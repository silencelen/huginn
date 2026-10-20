# Rounds

A round is a job Huginn runs on a schedule, with nobody watching. At each run it starts a fresh
Claude turn with the prompt you wrote, lets it work, and keeps a short report of what it found:
a status, a one-line headline, and only the items that need a decision, each with a suggested
next step. You get a notification when the report needs you.

Typical rounds: a daily look at the logs of a service, a weekly review of open alerts, a
monthly check that backups restore.

## Create a round

Rounds are created and edited in the desktop app and the Android app: open **Rounds**, then
**New round**.

| Field | What it is |
|---|---|
| **Name** | A short title, up to 80 characters. It is also the notification's title. |
| **What should it do?** | The prompt the run receives, up to 20,000 characters. |
| **How will you know it finished?** | Optional. The goal, written as a test of done ("every alert from the last 7 days has been classified"). Up to 500 characters. |
| Schedule | **Daily**, **Weekly** (pick the days), **Monthly** (pick dates, 1 to 31) at a **Time (24-hour)**, or **Every…** a number of minutes, from 5 minutes to 7 days. |
| **What it may do** | **Look and report** (ask) or **Change things** (act). See below. |
| **Where it runs** | **Huginn** (the server), or one of your [devices](devices.md). Shown when you have devices. |
| **Tell me** | **Every time**, **When it needs me** (default) or **Never**. |

**Polish** next to the prompt and the goal asks Claude for a better draft of that one field,
written to fit how a round actually runs. The draft is shown beside yours with **Use this** and
**Discard**; nothing changes until you choose. Polish needs something written first, and it is
a one-turn Claude call with no tools.

A new round uses your device's time zone, and keeps it: it fires at the same wall-clock time
across daylight-saving changes, whatever device you later edit it from. A time the clocks skip
when they go forward (2:30 AM on that night) runs just after the jump, at 3:30 AM. A monthly
date that a month does not have (the 31st in April) is skipped that month.

## Ask or act

| Choice | Mode | What the run may do |
|---|---|---|
| **Look and report** | ask | Read files, search, fetch web pages. `Bash`, `Edit`, `Write` and `NotebookEdit` are denied. |
| **Change things** | act | Also run commands and edit files. |

A round starts in ask mode unless you choose otherwise. An act round runs unattended with a
shell: nobody will be watching it, and it will not stop to ask. Choose act only for work you
would let Claude Code do alone.

Both modes also get any tools listed in `HUGINN_APPD_EXTRA_TOOLS` (an MCP server's tools, for
example), and the text in `HUGINN_APPD_PERSONA_FILE` is appended to the run's system prompt;
see [Server configuration](../reference/server-config.md).

A round placed on a device must fit that device's scope: an act round needs a device at scope
`work` or `own`. This is checked when you save. Whether the device is locked is checked when the
round runs.

Rounds always run on a Claude model; they cannot use [local AI](local-ai.md) models.

## What a run receives

Each run is its own new chat, so one bad week cannot affect the next. The message it receives
is, in order:

1. the goal, if you set one, stated as `GOAL — this run is done when: ...`;
2. your prompt;
3. the report instructions: how to end the turn with a `huginn-report` block.

The report block carries a tag generated for that run alone. A block without the tag is
ignored, so a report-shaped block that the run happens to read in a log or a web page cannot be
mistaken for its own.

A run gets one turn. It ends when Claude stops.

### Time budget

A run has a time budget of 15 minutes. When it runs past that, Huginn stops it and resumes the
same conversation once, with a message that begins `TIME IS UP` and asks for the report from
what the run has already gathered. That last turn has 3 minutes. A run that still produces no
report is recorded as having run out of time.

The apps do not show the time budget or the catch-up switch below; set them through the API
(`timeoutSec`, 60 to 7200 seconds, and `catchUp`); see the [API reference](../reference/api.md).

### Missed runs

A run that comes due while the server is down or the daemon is restarting still starts if it
is less than 10 minutes late. Later than that, it is skipped and the round waits for its next
slot. A round never starts a second run while the previous one is still going.

## The report

| Field | Meaning |
|---|---|
| status | `ok` (nothing needs anyone), `attention` (worth knowing) or `action` (something needs doing) |
| headline | One line, under 90 characters. It is the notification text. |
| goal met | Whether the run reached the goal you set. |
| items | Only the things that need a decision or an action, each with a title, the detail and a suggested next step. A clean run has none. Up to 20 are kept. |

Two rules make the status honest:

- A run that reports `ok` but says it did not meet its goal is shown as `attention`, and its
  text starts with `did not finish —`.
- A run that ends without a usable report block is shown as `unknown`, with the start of what
  it said last. A run that fails outright (it could not start, or it crashed) is shown as
  `action` with `run failed: ...`.

The apps word these as **all clear** (`ok`), **worth a look** (`attention`), **needs you**
(`action`) and **unclear** (`unknown`). The whole run is kept as a chat, so you can open it to
see exactly what the round did.

### Notifications

**Tell me** decides when a report notifies you:

| Setting | Notifies for |
|---|---|
| Every time | every report |
| When it needs me | every report that is not `ok`, including `unknown` |
| Never | nothing |

The notification goes to the desktop app and the Android app. If you have configured an alert
relay script (`HUGINN_APPD_TELEGRAM_SCRIPT`), the report is sent there only when no app received
the notification, so it does not arrive twice.

### Mark a report done

A report that needs attention stays marked until you deal with it. **Mark done** on the round
records that you have read it; the next run's report arrives unmarked again. **Undo** reverses
it. Marking a report never changes what it says.

## Manage rounds

Each round on the **Rounds** page has **Run now**, **Pause** (or **Resume**) and **Edit**.

- **Run now** starts a run immediately, outside the schedule.
- **Pause** stops the schedule. **Resume** arms the round again from now, so a round paused for
  a month does not fire the moment it is resumed.
- Changing the schedule re-arms the round at the next slot of the new schedule.
- **Delete**, in the editor, stops a run that is in progress and removes the round. The reports
  it already produced stay, as chats.

A round pinned to a device that has since been removed cannot run. The editor shows it as
"a machine that is gone"; pick **Huginn** to bring it back to the server.

## `huginn rounds`

```bash
huginn rounds
```

```text
(o> huginn - rounds

  ! Weekly alert review
    Sundays at 7:00 PM  .  in 3 days  .  ask  .  on this host
    needs you  .  2d ago  .  2 items  .  goal met
    Two alerts fire daily with no action taken
      - Disk alert on the build volume
        -> Raise the threshold or clean up old build caches
      - Certificate expiry warning
        -> Renew the certificate before Friday

  + Daily backup check
    Daily at 6:00 AM  .  in 15h  .  ask  .  on office-pc
    all clear  .  9h ago  .  goal met

  2 rounds
```

The first line of each round is a mark and the name; the second is the cadence, when it runs
next (or `working` while it runs, `paused` while paused), the mode, and where it runs. Then the
last report: its status, how long ago, the number of items, and whether the goal was met,
followed by the headline and up to five items with their next steps.

| Mark | Meaning |
|---|---|
| `*` | running now |
| `!` | the last report needs you |
| `>` | worth a look, or unclear |
| `+` | all clear, or marked done |
| `o` | not run yet |

`huginn rounds` is read-only. It runs on the server over SSH; on a computer set up from the
desktop app, run `huginn setup ssh` once first.

## Usage limits

Rounds use your Claude plan like any other work. A round that stops on a usage limit is re-run
when the limit resets, if auto-resume is on; see [Usage limits](usage-limits.md). The re-run
gets the round's whole time budget, and a slot that comes due while it waits is recorded as not
run.

## Related

- [Devices](devices.md): run a round on one of your computers
- [Chats](chats.md)
- [Notifications](notifications.md)
- [Usage limits](usage-limits.md)
