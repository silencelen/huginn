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
written in a row share it. `token` is a digest (sha256, 16 hex) of the canonical `{added, removed,
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
