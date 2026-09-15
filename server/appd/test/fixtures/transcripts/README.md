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
