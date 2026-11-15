# Projects

**Status:** Implemented in appd 3.4.0 onwards; the roster change in the release after 3.16.4.

**Summary:** A project is a durable cluster of Claude Code sessions with roles: a lead that sizes
the work, members the owner approves, and a dashboard that sums what they spent. The daemon
contributes names, personas, the membership record and the view; it routes no messages between
the sessions, which talk over Claude Code's own peer layer. The reasoning behind the record, the
two name grammars, the manifest contract and its tag is kept in the header comments of
`server/appd/lib/projects.js`; this record holds what is not obvious from the code.

## The lead proposes, the owner approves

The lead is briefed and answers with a fenced `huginn-project` block carrying a tag minted for
that project and present only in the lead's system prompt. The daemon reads the lead's own
transcript (never a member's), parses the last tagged block, and the owner approves it in the app
(`POST /v1/projects/<id>/spawn`). A block without the tag is reported as untagged and never acted
on: a session reads logs, pages and other people's files all day, and a planted block would
otherwise be a way to spawn twelve sessions with attacker-written first prompts.

## Changing a running roster

Until the roster change shipped, the reader stopped at the first spawn: a block the lead wrote
after its members were running went nowhere, and an owner who needed more sub-sessions ran a
second project beside the first. The owner's decision (2026-11-02) was to let the lead propose
changes to a live project and to let the owner edit members directly, with one rule: a member
leaving a project is asked to wrap up, never killed.

**D1. The same block is the proposal.** The lead gets no new tool. A tagged block written after
the spawn is read as the roster the lead wants now; the daemon diffs it against the members that
are running (`manifestDiff`): a role nobody holds is *added*, a running role the block does not
name is *removed*, a running role whose `firstPrompt` changed is *re-roled*. The contract text
tells the lead so, including that it must list every role it wants to keep. A moved `cwd`,
`model` or `effort` on a running role is not a change: a live session cannot take it without a
restart, and restarting a session the owner can see would be the surprise this avoids.

**D2. A change is a field, not a status.** The project stays `active` and carries
`pendingChange` (`{rev, summary, added, removed, changed, problem, receivedAt}`); the clients
draw it as a card with Approve and Decline. Making it a status would have meant `active ->
proposed`, which the state machine forbids for a good reason: a recap must never read as a
second cluster.

**D3. "New" is judged against what was last read, the diff against what is running.** The
reader remembers the last block it considered (`manifest.seen`); a block that matches it, a
declined block still sitting in the transcript tail, and a block already applied do not come back
as cards. The diff itself is always against the live members, because the owner may have added or
removed members directly since the lead last wrote.

**D4. Approve carries the rev and the content token.** `POST .../approve-change {rev, token}` refuses
a rev that is not the change on offer, for the spawn route's reason: a notification that sat on a lock
screen while the lead revised again must not approve the revision the owner never saw. The rev alone
cannot do that — it is `manifest.rev + 1` for every proposal until one is applied, so two blocks
written in a row share it. `token` is a digest (the whole sha256, 64 hex) of the canonical `{added, removed,
changed, summary}`, computed at detection and again by the route from the change it is about to
apply; a mismatch is `409` with the current project, and the push's fingerprint carries it so a
rewritten block is a new card. `decline-change` takes the token optionally and refuses a stale one
the same way. Applying re-roles first (a
typed line), then spawns the additions exactly as the first spawn does (same loop), then asks the
removed members to wrap up through the soft-end path, dialog check and send queue included. A
member asked to wrap up stays in the record with `endedAt` set until its session is gone.

**D5. The owner's direct edits go through the token, never through the reader.** `POST
.../members {role, description, spawn: true}` creates a member (validated by the manifest parser,
so nothing the lead could not have proposed can be typed into existence); `PATCH .../members/<role>
{description}` re-roles; `DELETE .../members/<role>?end=1` soft-ends. The role word cannot be
renamed while the session runs: it is the tmux name and the `--name` peer address every other
member holds, fixed at launch. Without `end`, the delete remains the record-only drop that adopted
sessions rely on, so no existing client starts ending sessions it only meant to drop.

**Rejected:** a hard end for a member (`end=now`): the only route that kills project sessions is
the project's own delete, and it says so in its name. A separate "propose change" tool for the
lead: the block the lead already knows is enough, and a second grammar is a second thing to tag.

## What a team costs to run

The owner's decision (2026-11-15) was to make the feature cheap to run, after one host's usage
showed that splitting a project into role sessions multiplied its spend. What was measured there:
spend follows context size, because every request re-reads the whole context (reading cached
context was over half of price-weighted spend), and team sessions rode the 1M window to about
967k, averaging 440k to 520k per request. About half of all spend was turns started by a message
from another session and almost none by a person. The busiest lead averaged five requests per
message it received, relayed items between members, ran an hourly heartbeat that mostly found
nothing, and its members slept and re-checked in loops.

The work is in three steps; this section records the first.

**E1. A context ceiling on every project session.** Every launch passes `--autocompact <tokens>`:
the session's own `context`, else the project's `contextWindow`, default 400k. Replaying three
weeks of that host's team sessions with a 400k compaction point removed about 30% of all spend,
and recovery after a compaction was observed to be quick. Measured on Claude Code 2.1.322 before
choosing the mechanism:

- The flag reaches the session's subagents. With `--autocompact 100k` a subagent compacted three
  times at 67k to 76k (debug log: `effectiveWindow=80000 thresholdSource=settings`); without it
  the same subagent grew to 192k and never compacted.
- A `--resume` does not keep it (back to the full window), so the restore path passes it every
  time, as it does effort.
- `/context` reports `used/<ceiling>`, but the status-line JSON keeps the model's window and a
  percentage of it. The daemon records the ceiling each session was launched with
  (`contextCeiling`) and divides by it (`statuslineLib.withCeiling`), so the apps' figure is a
  share of the ceiling.
- The flag accepts 100k to 1M; outside that `claude` exits at launch, so the daemon validates the
  value. It is passed only on 2.1.304 or later.

`CLAUDE_CODE_AUTO_COMPACT_WINDOW` would also work but beats every other setting, so the owner
could not move one session's window from inside it. `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` leaves
`/context` reading against 1M. An interactive `/autocompact` saves to user settings and so moves
every session on that model on the host.

A project written before 3.22.0 has no `contextWindow` and reads `null`: its sessions are restored
as they were until the owner sets one, because the restart a ceiling needs is the owner's call.

**E2. A working agreement in both personas.** The personas explained how messaging works and
never what it costs. Both now say that a message starts a full turn in the receiver, and set out
the rules: one complete message per finished task or blocker, results in a file, verify once,
probes in the same command as the work, one blocking wait with a timeout, a fresh subagent per
task. The lead is told not to schedule heartbeats because a member's idle notice is the signal. A
peer message cannot be queued or intercepted (it does not pass through appd), so these words are
the lever. The safety paragraphs are unchanged.

**E3. Members know their peers.** A member was told only the lead's name, so every hand-off went
through the lead. A member's persona now lists its peers, and a roster change types every running
member one `[Huginn]` line with the new list, marked as information that needs no reply. Only
names and role words travel, never text the lead wrote: a role is a grammar-checked word, and a
lead's prompt lifted into a persona would get system-prompt standing. The rule that a peer cannot
grant permissions is unchanged on every path.

**E4. The contract asks for a small team.** The manifest contract says what each session costs,
and asks the lead to split only where work runs in parallel or needs isolation, to choose the
cheapest model that fits, and to leave effort null unless the role needs more.

**E5. Compact before a re-role.** A member given a new task carried its whole old one into every
request of the new one. When its context is 100k tokens or more, appd types `/compact` first,
then a queue job waits for the `compact_boundary` record in its transcript (three minutes at
most), then the task. A compaction that cannot be confirmed does not cost the member its task.

**Rejected:** holding or batching `SendMessage` traffic in the daemon: it never passes through
appd, and it must not (see the module header). Step 2 adds a channel the daemon does carry.

**E6. Reports, carried by the daemon.** A member files a result that the lead need not act on this
minute with the `report` tool of huginn's MCP server. The daemon keeps it with the project
(`<data>/projects/reports/<id>.json`, the newest 300) and types the lead one digest when the lead
is next idle and the oldest item has waited 20 seconds, so members finishing together cost the lead
one turn. An urgent report is queued at once. `SendMessage` stays for "act now". The MCP server
learns its session the way `start_session` does (the pane's `#S`) and reaches the daemon through a
spool under the state directory, so it needs neither the API token nor the data directory. The
daemon decides who may file (a member of a running project, not its lead) and who may read (the
lead). Report text is a peer's: the digest frames it with the peer disclaimer and keeps the
daemon's own lines outside that frame. `peerMessageFrame` is still used, by the owner's relay route.

**E7. A watch instead of a heartbeat.** The reconcile pass looks at each running member and adds a
notice to the lead's next digest when a member finished a turn 10 minutes ago and has neither
reported nor called `SendMessage` since that work began, has been working 30 minutes with nothing
new in its transcript, has waited 15 minutes on a question for the owner, or is gone. Each episode
is said once. A member first seen idle, as after a daemon restart, is not news, because the watch
speaks only of runs it saw. A digest of notices alone goes at most once per 15 minutes. The lead's
persona now says plainly that it never needs to poll members or keep a heartbeat.
