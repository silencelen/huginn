'use strict';
// The full-file walker: what a session spent, and the map of what it did.
//
// Fixtures are SYNTHETIC transcripts written into a temp dir — the shapes are
// taken from real files on this host (see the measurements at the head of
// lib/sessiongraph.js) but no test here reads a real transcript, a real agent
// directory, or anything under /var/lib/huginn-appd.
//
// The load-bearing assertions are the two that were derived empirically and
// would silently produce plausible-but-wrong numbers if they regressed: usage is
// counted ONCE PER requestId, and `iterations[]` is a breakdown rather than
// extra spend. A graph that triple-counts cache reads still renders.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  sessionGraph, sessionOverview, resetCache, walkLines, usageOnce, turnNodes,
} = require('../lib/sessiongraph');

// A fixed clock in the PAST. Liveness here is `now - mtime`, so a fixture dated
// in the future reads as an agent that wrote a moment ago no matter what it is
// doing — which quietly turned every stalled-agent assertion green.
const clock = 1_756_000_000;
const T = (offsetSec) => new Date((clock + offsetSec) * 1000).toISOString();

function tmpdir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'graph-'));
}

/** An assistant record: one API call, one usage block, whatever blocks are given. */
function assistant(id, at, blocks, usage = {}, extra = {}) {
  return {
    type: 'assistant',
    uuid: `u-${id}-${Math.random().toString(36).slice(2, 8)}`,
    requestId: id,
    timestamp: T(at),
    effort: 'xhigh',
    ...extra,
    message: {
      role: 'assistant',
      model: extra.model || 'claude-opus-5',
      content: blocks,
      usage: {
        input_tokens: usage.i ?? 1,
        output_tokens: usage.o ?? 10,
        cache_read_input_tokens: usage.cr ?? 0,
        cache_creation_input_tokens: usage.cc ?? 0,
        ...(usage.iters ? { iterations: usage.iters } : {}),
      },
    },
  };
}

function userSays(text, at) {
  return { type: 'user', uuid: `us-${at}`, timestamp: T(at), message: { role: 'user', content: text } };
}

function toolResult(id, at, content = 'ok', isError = false) {
  return {
    type: 'user',
    uuid: `tr-${id}`,
    timestamp: T(at),
    message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content, is_error: isError }] },
  };
}

function write(dir, name, records) {
  const file = path.join(dir, name);
  fs.writeFileSync(file, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
  return file;
}

// ------------------------------------------------------------- the byte walk

test('the walk stops at the last COMPLETE line and resumes there', () => {
  const dir = tmpdir();
  const file = path.join(dir, 'partial.jsonl');
  fs.writeFileSync(file, '{"type":"a"}\n{"type":"b"}\n{"type":"hal');
  const seen = [];
  const at = walkLines(file, 0, fs.statSync(file).size, (d) => seen.push(d.type));
  assert.deepEqual(seen, ['a', 'b'], 'a half-written record is not parsed');
  assert.equal(at, 26, 'resume sits after the second newline, not at the file end');

  // Finish the record; only the appended part is read the second time.
  fs.appendFileSync(file, 'f"}\n');
  const more = [];
  walkLines(file, at, fs.statSync(file).size, (d) => more.push(d.type));
  assert.deepEqual(more, ['half'], 'the record completes rather than being lost');
});

test('a record straddling the chunk seam survives its multi-byte characters', () => {
  // The chunk size is 4MiB, so a straddle is forced with a record big enough to
  // cross it and a non-ASCII character placed near the seam. A string carry
  // instead of a Buffer one turns that character into replacement bytes and the
  // JSON.parse either fails or yields mojibake.
  const dir = tmpdir();
  const file = path.join(dir, 'wide.jsonl');
  const pad = 'x'.repeat(4 * 1024 * 1024 + 500);
  fs.writeFileSync(file, `${JSON.stringify({ type: 'a', pad, note: 'héllo · wörld' })}\n`);
  const seen = [];
  walkLines(file, 0, fs.statSync(file).size, (d) => seen.push(d.note));
  assert.deepEqual(seen, ['héllo · wörld']);
});

test('a garbled line is skipped without ending the walk', () => {
  const dir = tmpdir();
  const file = path.join(dir, 'garbled.jsonl');
  fs.writeFileSync(file, '{"type":"a"}\nnot json at all\n{"type":"c"}\n');
  const seen = [];
  walkLines(file, 0, fs.statSync(file).size, (d) => seen.push(d.type));
  assert.deepEqual(seen, ['a', 'c']);
});

// ------------------------------------------------------------------- usage

test('records sharing a requestId are ONE call, counted once', () => {
  // Measured on three real transcripts: 1,378 multi-record requestIds, every one
  // carrying identical usage. Per-record summing overcounted cache reads 2.7x.
  const seen = new Set();
  const usage = { i: 3, o: 400, cr: 90_000, cc: 5_000 };
  const first = usageOnce(assistant('req-1', 0, [{ type: 'thinking', thinking: 'hm' }], usage), seen);
  const second = usageOnce(assistant('req-1', 1, [{ type: 'text', text: 'hi' }], usage), seen);
  const third = usageOnce(assistant('req-1', 2, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], usage), seen);
  assert.deepEqual(first, { input: 3, output: 400, cacheRead: 90_000, cacheCreation: 5_000 });
  assert.equal(second, null, 'the same call again is not more spend');
  assert.equal(third, null);
});

test('a record with no requestId is counted on its own uuid', () => {
  const seen = new Set();
  const a = assistant('x', 0, [], { o: 7 });
  const b = assistant('x', 1, [], { o: 7 });
  delete a.requestId; delete b.requestId;
  assert.equal(usageOnce(a, seen).output, 7);
  assert.equal(usageOnce(b, seen).output, 7, 'two anonymous calls are two calls, not one');
});

test('iterations are a breakdown, never added to the total', () => {
  // On 4,940 real records the iterations' output_tokens summed EXACTLY to the
  // top-level output_tokens. Adding them would double every generation figure.
  const seen = new Set();
  const tok = usageOnce(assistant('req-i', 0, [], {
    o: 900, iters: [{ input_tokens: 1, output_tokens: 400 }, { input_tokens: 1, output_tokens: 500 }],
  }), seen);
  assert.equal(tok.output, 900);
});

// -------------------------------------------------------------- the spine

function simpleSession(dir, sid) {
  return write(dir, `${sid}.jsonl`, [
    userSays('Fix the failing gate', 0),
    assistant('r1', 1, [{ type: 'text', text: 'Reading the gate first.\nThen the log.' }], { o: 50 }),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Read', input: { file_path: '/a/gate.sh' } }], { o: 50 }),
    toolResult('t1', 2),
    assistant('r2', 3, [{ type: 'tool_use', id: 't2', name: 'Edit', input: { file_path: '/a/gate.sh' } }], { o: 30, cc: 900 }),
    toolResult('t2', 4),
    assistant('r3', 5, [{ type: 'tool_use', id: 't3', name: 'Edit', input: { file_path: '/a/other.sh' } }], { o: 20 }),
    toolResult('t3', 6),
    assistant('r4', 7, [{ type: 'text', text: 'Fixed. The gate was piping its exit code away.' }], { o: 11 }),
  ]);
}

test('a turn becomes one action block and one response block', () => {
  resetCache();
  const dir = tmpdir();
  const g = sessionGraph(simpleSession(dir, 's1'), 's1');
  assert.deepEqual(g.nodes.map((n) => n.kind), ['user', 'action', 'response']);
  assert.equal(g.nodes[0].label, 'Fix the failing gate');
  assert.equal(g.nodes[1].label, 'Reading the gate first.', 'the intent is the first prose line, not the whole paragraph');
  assert.equal(g.nodes[2].label, 'Fixed. The gate was piping its exit code away.');
  assert.equal(g.nodes[1].detail, 'Edit×2 · Read×1', 'busiest tool first');
  assert.equal(g.nodes[1].toolCalls, 3);
  assert.equal(g.nodes[1].files, 2, 'two distinct paths, three edits');
});

test('tokens split at the last tool call, so a response is not billed for the work', () => {
  resetCache();
  const dir = tmpdir();
  const g = sessionGraph(simpleSession(dir, 's2'), 's2');
  const action = g.nodes.find((n) => n.kind === 'action');
  const response = g.nodes.find((n) => n.kind === 'response');
  assert.equal(action.tokens.output, 100, 'r1 counted once despite two records');
  assert.equal(response.tokens.output, 11);
  assert.equal(g.totals.tokens.output, 111);
  assert.equal(g.totals.tokens.cacheCreation, 900);
});

test('a turn with no prose gets a synthesized label rather than a blank block', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 's3.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'ls' } }], {}),
    toolResult('t1', 2),
    assistant('r2', 3, [{ type: 'tool_use', id: 't2', name: 'Write', input: { file_path: '/x' } }], {}),
    toolResult('t2', 4),
  ]);
  const g = sessionGraph(file, 's3');
  assert.equal(g.nodes[1].label, '2 tool calls · edited 1 file');
});

test('machinery wearing the user role is not a message from a person', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 's4.jsonl', [
    userSays('the one real instruction', 0),
    { ...userSays('Approach this as the design lead…', 1), isMeta: true },
    userSays('<system-reminder>do a thing</system-reminder>', 2),
    userSays('<bash-input>ls</bash-input>', 3),
    userSays('[SYSTEM NOTIFICATION - NOT USER INPUT] a task finished', 4),
    assistant('r1', 5, [{ type: 'text', text: 'done' }], {}),
  ]);
  const g = sessionGraph(file, 's4');
  assert.equal(g.totals.userMessages, 1);
  assert.deepEqual(g.nodes.map((n) => n.kind), ['user', 'response']);
});

test('a compaction is its own mark, carrying what it dropped', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 's5.jsonl', [
    userSays('long job', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], {}),
    toolResult('t1', 2),
    {
      type: 'system',
      subtype: 'compact_boundary',
      uuid: 'c1',
      timestamp: T(3),
      compactMetadata: { trigger: 'auto', preTokens: 860_870, postTokens: 13_044, cumulativeDroppedTokens: 847_826 },
    },
    assistant('r2', 4, [{ type: 'text', text: 'carrying on' }], {}),
  ]);
  const g = sessionGraph(file, 's5');
  const compact = g.nodes.find((n) => n.kind === 'compact');
  assert.ok(compact, 'the break in the conversation is visible on the map');
  assert.equal(compact.dropped, 847_826);
  assert.equal(g.totals.compactions, 1);
  assert.equal(g.totals.droppedTokens, 847_826);
  assert.equal(compact.detail, '860,870 → 13,044 tokens');
  // The compaction ENDS the turn: work before it and work after it are not one
  // block, because the model that did the second half could not see the first.
  assert.deepEqual(g.nodes.map((n) => n.kind), ['user', 'action', 'compact', 'response']);
});

test('turn_duration ends a turn but never dates it', () => {
  // Real turn_duration records reported 8.6h and 19.4h on turns that lasted
  // minutes. Trusting durationMs would put a nine-hour block on the map.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 's6.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], {}),
    toolResult('t1', 20),
    { type: 'system', subtype: 'turn_duration', uuid: 'td', timestamp: T(21), durationMs: 30_942_368, messageCount: 355 },
    assistant('r2', 22, [{ type: 'text', text: 'second turn' }], {}),
  ]);
  const g = sessionGraph(file, 's6');
  const action = g.nodes.find((n) => n.kind === 'action');
  assert.equal(action.durMs, 1_000, 'the clock is the records, not the record that claims to be a clock');
  assert.deepEqual(g.nodes.map((n) => n.kind), ['user', 'action', 'response']);
});

test('a turn still running is on the map before it ends', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 's7.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'Starting the sweep.' }], { o: 5 }),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], { o: 5 }),
  ]);
  const g = sessionGraph(file, 's7');
  assert.deepEqual(g.nodes.map((n) => n.kind), ['user', 'action']);
  assert.equal(g.nodes[1].label, 'Starting the sweep.');
  assert.equal(g.totals.turns, 1, 'the open turn counts, or the header reads one behind reality');
});

test('an empty turn is not a block', () => {
  assert.deepEqual(turnNodes(null, 0), []);
});

// -------------------------------------------------------------- incremental

test('a grown transcript parses only what was appended', () => {
  resetCache();
  const dir = tmpdir();
  const file = simpleSession(dir, 's8');
  const first = sessionGraph(file, 's8');
  assert.equal(first.totals.tokens.output, 111);
  const firstNodes = first.nodes.length;

  fs.appendFileSync(file, [
    JSON.stringify(userSays('one more thing', 10)),
    JSON.stringify(assistant('r5', 11, [{ type: 'text', text: 'On it.' }], { o: 40 })),
    '',
  ].join('\n'));
  const second = sessionGraph(file, 's8');
  assert.equal(second.totals.tokens.output, 151, 'the head is not re-counted');
  assert.equal(second.nodes.length, firstNodes + 2);
  // Ids are positional and append-only, which is what lets a client keep its
  // scroll position and its open detail sheet across a poll.
  assert.equal(second.nodes[0].id, 'n0');
  assert.equal(second.nodes[1].id, first.nodes[1].id);
});

test('an unchanged transcript answers from the cache', () => {
  resetCache();
  const dir = tmpdir();
  const file = simpleSession(dir, 's9');
  const a = sessionGraph(file, 's9');
  const b = sessionGraph(file, 's9');
  assert.deepEqual(b.totals.tokens, a.totals.tokens);
  assert.equal(b.cursor.size, a.cursor.size);
  assert.equal(b.nodes.length, a.nodes.length, 'a second read does not double the map');
});

test('a SHRUNK file is a different session, and everything is forgotten', () => {
  // The transcript path is derived from the session id, so a smaller file at the
  // same path is not corruption — it is a new session that took the same name.
  // Continuing the old parse would add its tokens to somebody else's bill.
  resetCache();
  const dir = tmpdir();
  const file = simpleSession(dir, 's10');
  const before = sessionGraph(file, 's10');
  assert.ok(before.totals.tokens.output > 100);
  fs.writeFileSync(file, JSON.stringify(assistant('n1', 0, [{ type: 'text', text: 'fresh' }], { o: 3 })) + '\n');
  const after = sessionGraph(file, 's10');
  assert.equal(after.totals.tokens.output, 3);
  assert.equal(after.nodes.length, 1);
});

test('a transcript that is not there is not a crash', () => {
  resetCache();
  assert.equal(sessionGraph('/nonexistent/nope.jsonl', 'x'), null);
  assert.equal(sessionOverview('/nonexistent/nope.jsonl', 'x'), null);
});

// ------------------------------------------------------------------ agents

/** A parent that spawns two direct agents, one of which comes back. */
function agentSession(dir, sid) {
  const file = write(dir, `${sid}.jsonl`, [
    userSays('audit the fleet', 0),
    assistant('r1', 1, [{ type: 'text', text: 'Fanning out.' }], { o: 10 }),
    assistant('r1', 1, [
      { type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'Check the routers', subagent_type: 'general-purpose', prompt: 'go' } },
      { type: 'tool_use', id: 'ag2', name: 'Agent', input: { description: 'Check the switches', subagent_type: 'Explore', prompt: 'go' } },
    ], { o: 10 }),
    toolResult('ag1', 30, 'router report'),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-a1.meta.json'),
    JSON.stringify({ agentType: 'general-purpose', description: 'Check the routers', toolUseId: 'ag1', spawnDepth: 1 }));
  fs.writeFileSync(path.join(subs, 'agent-a1.jsonl'), [
    JSON.stringify(userSays('Check the routers', 2)),
    JSON.stringify(assistant('a-r1', 3, [{ type: 'tool_use', id: 'x', name: 'Bash', input: {} }], { o: 700, cr: 5_000 })),
    '',
  ].join('\n'));
  fs.writeFileSync(path.join(subs, 'agent-a2.meta.json'),
    JSON.stringify({ agentType: 'Explore', description: 'Check the switches', toolUseId: 'ag2', spawnDepth: 2 }));
  fs.writeFileSync(path.join(subs, 'agent-a2.jsonl'), [
    JSON.stringify(assistant('a-r2', 3, [{ type: 'text', text: 'looking' }], { o: 60 })),
    '',
  ].join('\n'));
  return file;
}

test('an agent branches from the tool_use and merges at the tool_result', () => {
  resetCache();
  const dir = tmpdir();
  const g = sessionGraph(agentSession(dir, 'sa1'), 'sa1');
  const a1 = g.agents.find((a) => a.id === 'a1');
  const a2 = g.agents.find((a) => a.id === 'a2');
  assert.equal(a1.spawnNodeId, 'n1', 'the branch leaves the action block that issued it');
  assert.equal(a1.mergeNodeId, 'n1');
  assert.equal(a1.status, 'done');
  assert.equal(a1.description, 'Check the routers');
  assert.equal(a1.depth, 1);
  assert.equal(a2.depth, 2, 'a nested agent says so, and the layout indents it');
  assert.equal(a2.mergeNodeId, null, 'nothing came back for the second one');
  assert.deepEqual(g.nodes[1].agents, ['a1', 'a2'], 'the block knows its own branches');
});

test('agent tokens are summed from the agent files and kept separate', () => {
  resetCache();
  const dir = tmpdir();
  const g = sessionGraph(agentSession(dir, 'sa2'), 'sa2');
  assert.equal(g.totals.agentCount, 2);
  assert.equal(g.totals.agentTokens.output, 760);
  assert.equal(g.totals.agentTokens.cacheRead, 5_000);
  assert.equal(g.totals.tokens.output, 10, 'the parent is not billed for its agents');
});

test('a workflow member hangs off the run, and the run off the turn that launched it', () => {
  resetCache();
  const dir = tmpdir();
  const sid = 'sw1';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('review it', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'wf1', name: 'Workflow', input: { script: '…' } }], { o: 5 }),
    toolResult('wf1', 2,
      'Workflow launched in background. Task ID: w3w2\nTranscript dir: /x/subagents/workflows/wf_abc-123'),
  ]);
  const runDir = path.join(dir, sid, 'subagents', 'workflows', 'wf_abc-123');
  fs.mkdirSync(runDir, { recursive: true });
  fs.writeFileSync(path.join(runDir, 'agent-w1.meta.json'),
    JSON.stringify({ agentType: 'workflow-subagent', spawnDepth: 1 }));
  fs.writeFileSync(path.join(runDir, 'agent-w1.jsonl'),
    JSON.stringify(assistant('w-r1', 3, [{ type: 'text', text: 'reviewing' }], { o: 12 })) + '\n');
  fs.writeFileSync(path.join(runDir, 'journal.jsonl'), [
    JSON.stringify({ type: 'started', agentId: 'w1' }),
    JSON.stringify({ type: 'result', agentId: 'w1', result: { summary: 'Two findings, both real.' } }),
    '',
  ].join('\n'));

  const g = sessionGraph(file, sid);
  const w = g.agents.find((a) => a.id === 'w1');
  assert.equal(w.workflowId, 'wf_abc-123');
  assert.equal(w.spawnNodeId, 'n1', 'the run directory named in the tool_result is the only join there is');
  assert.equal(w.summary, 'Two findings, both real.');
  assert.equal(w.status, 'done', 'a journal result is a workflow member\'s epitaph — its parent never gets one');
  assert.deepEqual(g.workflows, [{ id: 'wf_abc-123', nodeId: 'n1', ts: w.spawnTs, members: 1 }]);
});

test('a workflow member that stopped writing without a result is not called done', () => {
  resetCache();
  const dir = tmpdir();
  const sid = 'sw2';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('review it', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'wf1', name: 'Workflow', input: {} }], { o: 5 }),
    toolResult('wf1', 2, 'Transcript dir: /x/subagents/workflows/wf_zz'),
  ]);
  const runDir = path.join(dir, sid, 'subagents', 'workflows', 'wf_zz');
  fs.mkdirSync(runDir, { recursive: true });
  fs.writeFileSync(path.join(runDir, 'agent-w9.jsonl'),
    JSON.stringify(assistant('w-r9', 3, [{ type: 'text', text: 'half way' }], { o: 4 })) + '\n');
  fs.writeFileSync(path.join(runDir, 'journal.jsonl'), JSON.stringify({ type: 'started', agentId: 'w9' }) + '\n');
  const old = new Date((clock - 86_400) * 1000);
  fs.utimesSync(path.join(runDir, 'agent-w9.jsonl'), old, old);

  const g = sessionGraph(file, sid);
  assert.equal(g.agents.find((a) => a.id === 'w9').status, 'stalled');
});

test('a failed agent is failed, not done', () => {
  resetCache();
  const dir = tmpdir();
  const sid = 'sf1';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'd' } }], {}),
    toolResult('ag1', 5, 'exceeded the limit', true),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-f1.meta.json'), JSON.stringify({ toolUseId: 'ag1', agentType: 'x' }));
  fs.writeFileSync(path.join(subs, 'agent-f1.jsonl'), JSON.stringify(assistant('f', 2, [], {})) + '\n');
  const g = sessionGraph(file, sid);
  assert.equal(g.agents.find((a) => a.id === 'f1').status, 'failed');
});

test('an agent nobody claims is an orphan, not a silent omission', () => {
  // A map that leaves work out is worse than one with a loose end: those tokens
  // were spent whether or not the join survived a compaction.
  resetCache();
  const dir = tmpdir();
  const sid = 'so1';
  const file = write(dir, `${sid}.jsonl`, [userSays('go', 0), assistant('r1', 1, [{ type: 'text', text: 'ok' }], {})]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-o1.jsonl'),
    JSON.stringify(assistant('o', 2, [{ type: 'text', text: 'ran anyway' }], { o: 99 })) + '\n');
  const old = new Date((clock - 86_400) * 1000);
  fs.utimesSync(path.join(subs, 'agent-o1.jsonl'), old, old);
  const g = sessionGraph(file, sid);
  const o = g.agents.find((a) => a.id === 'o1');
  assert.equal(o.status, 'orphan');
  assert.equal(o.spawnNodeId, null);
  assert.equal(g.totals.agentTokens.output, 99, 'unjoined work still counts as spend');
});

test('a growing agent file is running, and its tokens keep up', () => {
  resetCache();
  const dir = tmpdir();
  const sid = 'sr1';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'live' } }], {}),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-r1.meta.json'), JSON.stringify({ toolUseId: 'ag1' }));
  const agentFile = path.join(subs, 'agent-r1.jsonl');
  fs.writeFileSync(agentFile, JSON.stringify(assistant('ar1', 2, [], { o: 5 })) + '\n');
  const now = Date.now();
  const first = sessionGraph(file, sid, { now });
  assert.equal(first.agents[0].status, 'running');
  assert.equal(first.totals.agentTokens.output, 5);

  fs.appendFileSync(agentFile, JSON.stringify(assistant('ar2', 3, [], { o: 6 })) + '\n');
  const second = sessionGraph(file, sid, { now });
  assert.equal(second.totals.agentTokens.output, 11, 'the append is added, not re-summed from zero');
});

// ------------------------------------------------------------ totals + rate

test('the header counts what a person would count', () => {
  resetCache();
  const dir = tmpdir();
  const g = sessionGraph(simpleSession(dir, 'st1'), 'st1');
  assert.equal(g.totals.userMessages, 1);
  assert.equal(g.totals.toolCalls, 3);
  assert.equal(g.totals.filesTouched, 2);
  assert.deepEqual(g.totals.models, ['claude-opus-5']);
  assert.deepEqual(g.totals.efforts, ['xhigh']);
  assert.equal(g.totals.wallMs, 7_000);
});

test('a model the CLI made up is not a model anybody chose', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'st2.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], {}, { model: '<synthetic>' }),
    assistant('r2', 2, [{ type: 'text', text: 'b' }], {}, { model: 'claude-fable-5' }),
  ]);
  assert.deepEqual(sessionGraph(file, 'st2').totals.models, ['claude-fable-5']);
});

test('the burn rate is what the session wrote, over the window it wrote it in', () => {
  resetCache();
  const dir = tmpdir();
  // Two calls a minute apart, 600 written tokens each, at the live end.
  const file = write(dir, 'st3.jsonl', [
    userSays('go', 0),
    assistant('r1', 60, [{ type: 'text', text: 'a' }], { i: 0, o: 500, cc: 100, cr: 1_000_000 }),
    assistant('r2', 120, [{ type: 'text', text: 'b' }], { i: 0, o: 500, cc: 100, cr: 1_000_000 }),
  ]);
  const g = sessionGraph(file, 'st3');
  assert.equal(g.rate.tokensPerMin10, 600, '1200 written tokens over the 2 minutes the session has existed');
  assert.equal(g.rate.allTokensPerMin10, 1_000_600, 'cache reads are reported, just not as the headline');
  assert.equal(g.rate.activeRecently, false, 'a session that last spoke in 2027 is not live now');
});

test('the rate is anchored on the last API call, not the last line in the file', () => {
  // Measured on a real transcript: its final record is an `attachment` written
  // 108 minutes after the last assistant record. Anchoring the window on the
  // file's last timestamp put no spend at all inside it, and a session that had
  // been burning 40k/min reported a rate of zero.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'st5.jsonl', [
    userSays('go', 0),
    assistant('r1', 60, [{ type: 'text', text: 'a' }], { i: 0, o: 600, cc: 0, cr: 0 }),
    { type: 'attachment', uuid: 'att', timestamp: T(60 + 108 * 60), attachment: { type: 'deferred_tools_delta' } },
  ]);
  const g = sessionGraph(file, 'st5');
  assert.equal(g.rate.tokensPerMin10, 600, 'the pace it was going when it was going');
  assert.equal(g.rate.activeRecently, false, 'and this is the field that says whether that is now');
});

test('the overview is the header without the map', () => {
  resetCache();
  const dir = tmpdir();
  const o = sessionOverview(simpleSession(dir, 'st4'), 'st4');
  assert.equal(o.nodes, undefined, 'the cheap route carries no nodes');
  assert.equal(o.agents, undefined);
  assert.equal(o.totals.tokens.output, 111);
  assert.ok(o.cursor.size > 0);
});

test('the cursor moves when an AGENT grows even though the parent did not', () => {
  // Without agentBytes a fan-out would look frozen: the parent writes nothing
  // while six agents run, so a parent-size cursor says "unchanged" for minutes.
  resetCache();
  const dir = tmpdir();
  const sid = 'sc1';
  const file = agentSession(dir, sid);
  const a = sessionGraph(file, sid);
  fs.appendFileSync(path.join(dir, sid, 'subagents', 'agent-a2.jsonl'),
    JSON.stringify(assistant('a-r3', 9, [{ type: 'text', text: 'more' }], { o: 1 })) + '\n');
  const b = sessionGraph(file, sid);
  assert.equal(b.cursor.size, a.cursor.size);
  assert.ok(b.cursor.agentBytes > a.cursor.agentBytes, 'the cursor notices');
});
