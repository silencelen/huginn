# Subagent fixtures

Real subagent transcripts, copied off this host on 2026-09-15 out of
`~/.claude/projects/-root-netplan/4726c1d0-…/subagents/` (CLI 2.1.258) and
checked for email/token-shaped strings before landing here. Nothing is
hand-written: the point of these files is that `lib/agents.js` and the
`/v1/sessions/:name/agents/:agentId/transcript` route are exercised against the
layout the CLI actually writes, including the parts no synthetic fixture would
think to include — `attachment` records between turns, a `.meta.json` whose
workflow form carries no `toolUseId`, and a run journal that says `failed`.

```
subagents/agent-af7ca864cee1939de.jsonl        direct agent (haiku smoke run)
subagents/agent-af7ca864cee1939de.meta.json      agentType + toolUseId + spawnDepth
subagents/agent-acdf276aeabf0df8f.jsonl        direct agent (opus smoke run)
subagents/agent-acdf276aeabf0df8f.meta.json
subagents/workflows/wf_26d79030-31f/
    agent-a67c22d167ec48615.jsonl              workflow member
    agent-a67c22d167ec48615.meta.json          {"agentType":"workflow-subagent","spawnDepth":1}
    journal.jsonl                              trimmed to 4 lines: the member's
                                               own started + failed pair, then two
                                               unrelated started lines
```

Ids are the real `agent-<hex>` basenames, which is the point of them: they are
**not** uuids, so the route's id pattern is `^agent-[0-9a-f]{6,32}$` and not the
uuid one used elsewhere in the daemon.

The journal was trimmed because the original is a megabyte. Everything else is
byte-for-byte what the CLI wrote. Copy the tree into a scratch directory before
a test touches it — mtimes are what `status` and the `RECENT_S` filter read, and
a fixture whose mtime is "whenever git checked it out" makes a flaky test.
