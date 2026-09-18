# Transcript fixtures

`limit-429.jsonl` — the last six records of a real session that ran out of
headroom (`~/.claude/projects/-root-netplan/6304067d-…jsonl`, 2026-08-31, CLI
2.1.227), ending on the record the daemon keys auto-resume off:

```json
{"type":"assistant", …, "message":{"model":"<synthetic>","content":[{"type":"text",
 "text":"You've hit your session limit · resets 3:10am (America/Los_Angeles)"}]},
 "error":"rate_limit","isApiErrorMessage":true,"apiErrorStatus":429}
```

Three things about it are load-bearing and none is obvious enough to invent:
the stall is an **ordinary assistant record**, not an error record type; its
`message.model` is `<synthetic>` because the CLI wrote the text, not a model;
and the five records before it are a perfectly normal turn, so "the session
stopped" is visible ONLY in those three fields.

Scrubbed: thinking signatures emptied, IPs replaced, `sessionId`/`requestId`
replaced, long tool results clipped. The 429 record's own flags and text are
verbatim.

---

`skill-invocation.jsonl` — two real skill loads, the shape behind "when huginn
calls a skill, the skill is being printed in the session chat tab as a message
from the user". Records 1–6 are
`~/.claude/projects/-root-netplan/14a27e99-…jsonl` lines 5, 16, 17, 18, 19, 22
(a **project** skill, invoked with args); records 7–10 are
`~/.claude/projects/-root-netplan/68099091-…jsonl` lines 3589, 3591, 3592, 3593
(a **bundled** skill, no args). Both 2026-09, CLI 2.1.258.

One invocation is four records:

```json
{"type":"assistant", …,"content":[{"type":"tool_use","id":"toolu_018u…","name":"Skill",
 "input":{"skill":"incident-triage","args":"Kuma monitor-down spam …"}}]}
{"type":"user", …,"content":[{"type":"tool_result","tool_use_id":"toolu_018u…",
 "content":"Launching skill: incident-triage"}]}
{"type":"user","isMeta":true,"sourceToolUseID":"toolu_018u…", …,
 "content":[{"type":"text","text":"Base directory for this skill: …"}]}
{"type":"attachment","attachment":{"type":"command_permissions", …}}
```

What is load-bearing, and what a hand-written fixture would miss:

- the third record is an ordinary **`type: "user"`** record whose text is just
  the skill's prose. Nothing in the CONTENT marks it — no wrapper tag, no
  caveat — so every machine-text heuristic sails past it and it renders as a
  multi-KB message the owner appears to have typed. **`sourceToolUseID` is the
  only tell**, and it is exact: across all 891 transcripts on this host every
  one of the 65 records carrying it is a Skill body, all 65 are `user` +
  `isMeta`, and no other record type carries the field at all.
- `isMeta` alone is NOT the tell. It also marks image captions, `/model`
  caveats and a resumed session's "Continue from where you left off." — the
  last of which must keep rendering as a message.
- the body arrives AFTER the tool_result, so by then the tool call is gone from
  `pendingTools`; finding the card it belongs to needs an index that outlives
  the result.
- the two bodies differ: a project skill opens with `Base directory for this
  skill: <path>` (54 of the 65 on this host), a bundled one goes straight into
  its own prose with no marker and carries `turnCompanion: true`.

Scrubbed: thinking signatures emptied, `sessionId`/`requestId` replaced, both
skill bodies clipped to their first lines, the opening message and the
assistant's reply shortened. Every field that classification depends on
(`type`, `isMeta`, `sourceToolUseID`, `turnCompanion`, the tool_use `input`,
the tool_result text) is verbatim.

`slash-command.jsonl` — a slash command the owner actually typed, from
`~/.claude/projects/-root-netplan/4a82cf98-…jsonl` lines 1915–1918. The command
is a `user` record whose `message.content` is a **string** of
`<command-name>/<command-message>/<command-args>` tags, its stdout is a second
`user` record, and the sentence the owner typed next is a third — three records
that all look alike and of which exactly one is a message. Kept as the
regression guard for that, and as the counterpart to the skill shape: this one
IS the user's intent (it collapses to a chip), the skill body is not.

Scrubbed: `sessionId` replaced, the trailing message shortened,
its `file-history-snapshot` emptied of the tracked-file list it carried.
