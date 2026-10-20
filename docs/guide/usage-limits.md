# Usage limits

A Claude plan has usage windows, and work stops when one is full. Huginn reads those windows
for you and acts before and after a limit, so long-running and unattended work loses as little
as possible:

- it **warns** a session that is about to run out, inside the session itself;
- it **moves a session down a model**, for that session only, when its model's weekly window
  is nearly full;
- it **holds new subagents** while the account is short of room, and lets them start when room
  comes back;
- it **resumes stalled work** after the limit resets;
- with more than one saved Claude login, it can **switch logins** for new work.

Huginn calls this *headroom*. All of it runs in the daemon on the server, and its settings are
in the desktop app and the Android app under **Settings → Usage & headroom**.

## The windows

Huginn reads the plan's usage once a minute while anything is running, and every half hour for
saved logins that are not active. It watches three windows:

| Window | What it limits |
|---|---|
| Session | the 5-hour window, shared by every model |
| Week, all models | the weekly window across all models |
| Week, Fable | the weekly window for the Fable model |

A window counts as *reset* only when its reset time has passed **and** a fresh reading shows it
low again (below **Treat a window as cleared below**, default 50 %). A reset time on the
calendar alone is not enough.

## Warn, then step down

These two act on sessions running on Fable, using the Fable weekly window.

**Heads-up** (default 85 %). Huginn types a note into the session asking it to write a short
handoff (what is done, what is next, which files matter) and then continue. Each session gets
one heads-up per week. A session that is showing a question or a permission prompt is skipped.

**Step down** (default 92 %). Huginn moves the session to the next model on the ladder
(default `fable → opus → sonnet`), using the `/model` picker's **use this session only**
choice. Your default model in Claude Code's settings is not changed, and other sessions and new
sessions are not affected. A notification tells you which session moved, with **Undo**. Undo
sent while the session is mid-turn takes effect at the next turn boundary.

Huginn holds back from a move when:

- you set that session's model yourself (with `/model`) in the last 10 minutes;
- Claude Code itself already switched the session to another model (Huginn never reverses
  that; after the weekly window resets it offers, once, to move the session back);
- it moved a session in the last 30 minutes (one move at a time, so sessions do not all change
  model at once);
- an account switch is going out at the same time;
- the session is stalled on a usage limit (it is resumed after the reset instead; see
  [Resume after a limit resets](#resume-after-a-limit-resets)).

**Moving back up.** After the Fable week resets and reads below **Move back up below** (default
50 %), Huginn moves each session it moved back to its own model, one at a time, while the
session is idle. It moves back only the sessions it moved.

A chat or round that names no model and starts while the Fable week is at or past the step-down
level runs on the next model down, and the chat says which model it ran on.

> Typing `/model <name>` in a session yourself saves that model as Claude Code's default on the
> server. That is Claude Code's behavior, not Huginn's. Huginn only ever uses the
> session-only choice.

### Model prompts nobody answers

When Fable reaches the point where further use would bill usage credits, Claude Code shows a
choice in the session, and an unanswered choice loses the turn. Huginn shows the question in
the apps like any other prompt. If nobody answers within 2 minutes and the session's
auto-resume is on, Huginn picks the recommended option, which is a session-only model change.

## Hold new subagents

Huginn installs a small hook into Claude Code on the server that runs whenever a subagent or a
workflow is about to start. While the account is short of room, the hook makes the new agent
wait; when room comes back, the agent starts. Agents already running are not touched.

| Hold | Starts at | Holds |
|---|---|---|
| Session hold | the 5-hour window at **Hold new subagents at** (default 70 %), or the all-models week at the step-down level | new agents in every session |
| Fable hold | the Fable week at **Hold Fable subagents at** (default 88 %) | new agents in sessions running on Fable |

A hold clears when its window drops below **Treat a window as cleared below** (default 50 %).
The gap between the two numbers keeps a hold from switching on and off while a percentage
wobbles.

The hook only delays; it never refuses. A held agent starts after about 30 minutes even if the
hold is still on. If the daemon stops while a hold is on, the hold lapses by itself after
15 minutes. `huginn headroom` shows how many agents are waiting.

The hook applies to every Claude Code session on the server, including ones Huginn did not
start. To remove it:

```bash
sudo node /opt/huginn-appd/install-hooks.js --uninstall
```

Updating the server installs it again.

Huginn also refuses to [spawn a project's members](projects.md) while the session hold is on.

## Resume after a limit resets

Claude Code continues a session by itself when a limit resets, as long as it is still running,
in the foreground, and the reset is less than 24 hours away. Huginn covers what that does not:

| What stalled | What Huginn does after the reset |
|---|---|
| A session where Claude Code's own wait is armed | waits for it, and steps in only if it has not continued 90 seconds after the reset |
| A session with no wait armed (Claude Code exited or restarted, or the reset is more than 24 hours away, as a weekly one usually is) | types the resume phrase into the session |
| A chat or a round | re-runs the stalled turn in the same conversation |
| Work on a [device](devices.md) | puts the work back in the device's queue |

Huginn resumes only when the window has really reset (see [The windows](#the-windows)), only if
nothing has been typed since the limit hit (your own message cancels it), and at most three
times per stall. The default resume phrase is:

```text
Your usage limit has reset. Continue the task you were working on when the limit was reached; do not repeat work that is already complete.
```

Auto-resume is on by default. The global setting is the default for every session, and each
session's control bar in the apps has its own switch, so one long unattended session can resume
itself while an attended one waits for you.

## Saved logins and switching

The server can keep more than one Claude login. Manage them in the apps under
**Settings → Host & sign-in**: **Saved logins** lists them, shows which one is active and how
usable each token is, and **Add a login** signs another Claude account in on the server through
the browser. The daemon keeps saved logins that are not active signed in by refreshing their
tokens shortly before they expire. It never refreshes the active login, which belongs to Claude
Code.

**Account switching** is off by default, and shown only when the server has more than one
saved login. When it is on:

- Huginn switches when the active login's fullest window reaches **Switch at** (default 95 %),
- only to a login whose fullest window is at least **Only to an account this much freer**
  (default 20 points) below that, preferring one with Fable room left,
- and at most once every 30 minutes.

A switch changes the login for new work. A session that is already running keeps the account
it started with until it restarts, which is why the model step-down exists.

## Keep a window rotating

The 5-hour window starts with the first request after it lapses. **Keep a window rotating**
(off by default) sends one tiny request whenever no window is running, at most once per window,
so a window is always under way and its reset tends to land while nobody is working. It costs a
fraction of a cent and a sliver of the weekly pool each time. With it on you can pick the model
for that request (default Claude Haiku 4.5) and **Quiet hours** during which nothing is sent.

## Settings

All of these are under **Settings → Usage & headroom** in the desktop app and the Android app.
They are stored on the server and apply to every client.

| Setting | Default | What it sets |
|---|---|---|
| Heads-up at | 85 % | Fable week level for the heads-up note |
| Move down the ladder at | 92 % | Fable week level for the step-down |
| Move back up below | 50 % | level the Fable week must fall below before a moved session goes back |
| Hold new subagents at | 70 % | 5-hour window level for the session hold |
| Hold Fable subagents at | 88 % | Fable week level for the Fable hold |
| Treat a window as cleared below | 50 % | level at which a window counts as reset and holds clear |
| Ladder | `fable → opus → sonnet` | order a session steps through; any of `fable`, `opus`, `sonnet`, `haiku` |
| Auto-resume | on | whether stalled work resumes after a reset |
| What to type on resume | see above | up to 300 characters; may not start with `/` |
| Default model | your Claude Code default | the model Claude Code defaults to on the server. Stepping a session down never changes it: if Claude Code's saved default moves, Huginn puts this one back. Chats that choose no model use it |
| Heads-up message | see above | up to 600 characters; must contain `{pct}`; may not start with `/` |
| Account switching | off | see [Saved logins and switching](#saved-logins-and-switching) |
| Switch at | 95 % | |
| Only to an account this much freer | 20 | |
| Keep a window rotating | off | see above |

Heads-up must be below step-down, the cleared level below the subagent hold, and the move-back
level below step-down.

## `huginn headroom`

```bash
huginn headroom          # the report
huginn headroom --json   # the daemon's answer, unformatted
```

```text
(o> huginn - headroom

  Headroom: warn   (Current week (Fable) at 87%, resets Thu 09:00)

  * alex@example.com
    Current week (Fable)  .  87%  .  resets Thu 09:00
    Current session 41%  .  Current week, all models 63%
    live  .  read just now

  Moved by huginn
    api-rework: fable -> opus  (confirmed, 2h ago)

  Holding 2 new agents: general-purpose x2
    oldest held 4m ago

  Arbiter: ladder down  .  on api-rework  .  -> opus  .  2h ago
    why: handed docs-site a heads-up at 87% of the Fable week

  Settings: heads-up 85%  .  step down 92%  .  hold agents 70% session / 88% Fable week  .  ladder fable -> opus -> sonnet  .  auto-resume on  .  account switch off

  1 account
```

It shows, per saved login, the fullest window and when it resets (the active login is marked
`*`; a saved login's numbers can be old), the sessions Huginn moved, sessions waiting for a
reset, agents being held, the last thing Huginn did, and **why** it did or did not act. The
`why` line is usually the answer to "why is this session still on Fable at 94 %".


| Exit status | Meaning |
|---|---|
| `0` | Printed. |
| `1` | No access token on the server, or the daemon cannot report headroom. |
| `2` | The daemon is not answering. |

`huginn headroom` runs on the server over SSH. On a computer set up from the desktop app, run
`huginn setup ssh` once first.

## Headless one-shots

When the Fable week is full, Claude Code runs a `-p` one-shot on the next model down rather
than failing. If a wrong model is worse than no answer for some job (a scheduled script whose
output feeds something else), set Claude Code's `CLAUDE_CODE_NO_MODEL_FALLBACK=1` for that job,
and it fails instead.

## Related

- [Sessions](sessions.md)
- [Rounds](rounds.md)
- [Projects](projects.md)
- [Security](../security.md): what the headroom hook and token refresh touch on the server
