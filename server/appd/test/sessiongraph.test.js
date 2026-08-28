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
        // The nested TTL breakdown of the flat cache_creation total, written
        // only when a test asks for it — the records that carry no split are
        // the case the pricing fallback exists for, and they have to stay
        // reachable from here.
        ...(usage.split
          ? {
            cache_creation: {
              ephemeral_5m_input_tokens: usage.split[0],
              ephemeral_1h_input_tokens: usage.split[1],
            },
          }
          : {}),
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

test('an agent whose meta lands a moment LATE is read again, not remembered as nameless', () => {
  // ⚠ THE ORDINARY CASE, not a rare one. `.meta.json` is a SIBLING of the
  // transcript and is written on its own schedule, so the first poll routinely
  // finds bytes and no meta. The retry the walker's comment promises used to sit
  // BELOW the equal-size early return, which meant it could only run on a poll
  // that also found new bytes — and for an agent that has stopped writing, that
  // is never. The branch kept the null: no description, no type, and no join to
  // the tool_use that spawned it, for the rest of the session.
  resetCache();
  const dir = tmpdir();
  const sid = 'slate';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'Check the switches' } }], {}),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  const agentFile = path.join(subs, 'agent-l1.jsonl');
  fs.writeFileSync(agentFile, JSON.stringify(assistant('al1', 2, [], { o: 5 })) + '\n');

  const first = sessionGraph(file, sid);
  assert.equal(first.agents.length, 1);
  assert.equal(first.agents[0].spawnNodeId, null, 'nothing to join it to yet');
  assert.equal(first.agents[0].agentType, null);
  assert.equal(first.agents[0].description, null, 'and nothing to call it');

  // The directory is populated a beat later, and the agent file NEVER grows
  // again — which is exactly what a finished agent looks like.
  fs.writeFileSync(path.join(subs, 'agent-l1.meta.json'),
    JSON.stringify({ agentType: 'Explore', toolUseId: 'ag1', spawnDepth: 1 }));

  const second = sessionGraph(file, sid);
  assert.equal(second.agents[0].agentType, 'Explore', 'the sibling was read again');
  assert.equal(second.agents[0].spawnNodeId, 'n1', 'and the join it carries finally lands');
  assert.equal(second.agents[0].description, 'Check the switches',
    'which is also the only route to the parent\'s own words for it');
  assert.equal(second.agents[0].depth, 1);
});

// ------------------------------------------------------------ totals + rate

// ------------------------------------------------------------------- errors
//
// `totals.errors` is rendered on the overview card and on the map header. It was
// EMITTED and never incremented: the count lived on the open turn, and closing
// the turn threw the turn away. A header that always reads zero does not say "we
// do not know" — it says the run went fine.

test('failed tool calls reach the totals, and the totals match the blocks', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'serr1.jsonl', [
    userSays('run the gate', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t1', 2, 'exit 1', true),
    assistant('r2', 3, [{ type: 'tool_use', id: 't2', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t2', 4, 'exit 2', true),
    assistant('r3', 5, [{ type: 'tool_use', id: 't3', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t3', 6, 'ok'),
    userSays('and again', 7),
    assistant('r4', 8, [{ type: 'tool_use', id: 't4', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t4', 9, 'exit 3', true),
    userSays('enough', 10),
  ]);
  const g = sessionGraph(file, 'serr1');
  const nodeSum = g.nodes.reduce((n, x) => n + (x.errors || 0), 0);
  assert.equal(nodeSum, 3, 'two in the first turn, one in the second');
  assert.equal(g.totals.errors, nodeSum, 'the header is the sum of what is drawn under it');
});

test('a turn still RUNNING has its failures in the header already', () => {
  // The last turn is previewed rather than closed, so its errors have not been
  // rolled up yet. Counted here for the same reason `turns` is: a header and the
  // blocks beneath it that disagree while a run is live is the one moment
  // somebody is actually watching.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'serr2.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t1', 2, 'exit 1', true),
  ]);
  const g = sessionGraph(file, 'serr2');
  assert.equal(g.nodes.reduce((n, x) => n + (x.errors || 0), 0), 1);
  assert.equal(g.totals.errors, 1, 'not zero until the turn happens to end');
});

test('a failure landing after its turn was closed is still a failure', () => {
  // A compact boundary closes the turn where it falls. The tool_result for work
  // issued before it arrives with no open turn to charge — and the alternative
  // to counting it straight into the totals is not counting it at all.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'serr3.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], { o: 5 }),
    {
      type: 'system',
      subtype: 'compact_boundary',
      uuid: 'cb',
      timestamp: T(2),
      compactMetadata: { trigger: 'auto', preTokens: 100, postTokens: 10 },
    },
    toolResult('t1', 3, 'exit 1', true),
  ]);
  const g = sessionGraph(file, 'serr3');
  assert.equal(g.totals.errors, 1);
  assert.equal(g.nodes.reduce((n, x) => n + (x.errors || 0), 0), 0,
    'it belongs to no block on the map, which is why the totals must carry it');
});

test('a clean session says zero rather than a number the roll-up invented', () => {
  // Closed turns AND an open one, because the count is assembled from both and
  // a roll-up that charged per turn rather than per failure would read three.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'serr4.jsonl', [
    userSays('one', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t1', 2, 'ok'),
    userSays('two', 3),
    assistant('r2', 4, [{ type: 'tool_use', id: 't2', name: 'Bash', input: {} }], { o: 5 }),
    toolResult('t2', 5, 'ok'),
    userSays('three', 6),
    assistant('r3', 7, [{ type: 'text', text: 'done' }], { o: 5 }),
  ]);
  const g = sessionGraph(file, 'serr4');
  assert.equal(g.totals.turns, 3, 'two closed and one in flight');
  assert.equal(g.totals.errors, 0);
});

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

// ----------------------------------------------------------- the cost estimate
//
// What the session WOULD have billed at API list rates. The account is on a
// subscription so none of it was charged — the client says so — but the tokens
// are real and the arithmetic has to be too.
//
// The numbers below are exact on purpose: a rate or a bucket that drifts should
// fail here rather than move a total by a few percent and keep rendering. The
// load-bearing cases are the ones where a wrong answer still looks like a right
// one — the same requestId counted twice, a cache write charged at the TTL
// nobody recorded, and an agent's spend priced at its parent's model.

test('every record is priced against the model IT names', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce1.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], { i: 0, o: 1_000_000 }, { model: 'claude-opus-5' }),
    assistant('r2', 2, [{ type: 'text', text: 'b' }], { i: 0, o: 1_000_000 }, { model: 'claude-haiku-4-5' }),
  ]);
  const g = sessionGraph(file, 'sce1');
  assert.equal(g.totals.estCost.usd, 30, '$25 of opus output plus $5 of haiku output');
  assert.deepEqual(g.totals.estCost.byModel, [
    { model: 'claude-opus-5', usd: 25 },
    { model: 'claude-haiku-4-5', usd: 5 },
  ]);
  assert.equal(g.totals.estCost.unpricedTokens, 0);
});

test('the 5m/1h cache-creation split is read, and each half billed at its own rate', () => {
  // The nested `cache_creation` object is on every real record measured on this
  // host, and the two TTLs are not the same price: 1.25x input against 2x. A
  // walker that only read the flat total would have to pick one, and either
  // pick is wrong for half the tokens.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce2.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }],
      { i: 0, o: 0, cc: 2_000_000, split: [1_000_000, 1_000_000] }, { model: 'claude-opus-5' }),
  ]);
  const g = sessionGraph(file, 'sce2');
  assert.equal(g.totals.tokens.cacheCreation, 2_000_000, 'the FLAT total is still what was written');
  assert.equal(g.totals.estCost.usd, 16.25, '$6.25 at the 5m rate plus $10 at the 1h rate');
});

test('a cache write with no split is carried as unsplit and billed at the CHEAPER rate', () => {
  // Nothing said which TTL this was, so the estimate takes the low candidate
  // rather than inventing spend it has no record of.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce3.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], { i: 0, o: 0, cc: 1_000_000 }, { model: 'claude-opus-5' }),
  ]);
  const g = sessionGraph(file, 'sce3');
  assert.equal(g.totals.tokens.cacheCreation, 1_000_000, 'not lost on the way to the price');
  assert.equal(g.totals.estCost.usd, 6.25, 'the 5-minute rate');
  assert.notEqual(g.totals.estCost.usd, 10, 'not the 1-hour rate');
});

test('the estimate dedupes by requestId exactly as the token totals do', () => {
  // ⚠ THE SAME FINDING THE TOTALS REST ON, and it has to hold on this path
  // separately: the per-model accumulation is a second pass over the same
  // records, and a dollar figure that triples is as renderable as a right one.
  resetCache();
  const dir = tmpdir();
  const usage = { i: 0, o: 1_000_000 };
  const file = write(dir, 'sce4.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'thinking', thinking: 'hm' }], usage, { model: 'claude-opus-5' }),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], usage, { model: 'claude-opus-5' }),
    assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Bash', input: {} }], usage, { model: 'claude-opus-5' }),
  ]);
  const g = sessionGraph(file, 'sce4');
  assert.equal(g.totals.tokens.output, 1_000_000, 'three records, one API call');
  assert.equal(g.totals.estCost.usd, 25, 'and one call\'s worth of dollars, not three');
});

test('an agent is priced at its OWN model, and its share of the bill is reported', () => {
  // A haiku Explore under an opus parent is the ordinary shape of a fan-out.
  // Pricing the agent at the parent's rate would overstate it fivefold — and
  // the client wants to say "of which this much was in agents", so the share
  // has to come off the same accumulation as the total.
  resetCache();
  const dir = tmpdir();
  const sid = 'sce5';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('fan out', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'look' } }],
      { i: 0, o: 1_000_000 }, { model: 'claude-opus-5' }),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-a1.meta.json'), JSON.stringify({ toolUseId: 'ag1', agentType: 'Explore' }));
  fs.writeFileSync(path.join(subs, 'agent-a1.jsonl'),
    JSON.stringify(assistant('a-r1', 2, [{ type: 'text', text: 'found it' }],
      { i: 0, o: 1_000_000 }, { model: 'claude-haiku-4-5' })) + '\n');

  const g = sessionGraph(file, sid);
  assert.equal(g.totals.estCost.usd, 30, 'the estimate covers the parent and everything it spawned');
  assert.equal(g.totals.agentEstCostUsd, 5, 'and says how much of that was the agent');
  assert.deepEqual(g.totals.estCost.byModel.map((r) => r.model), ['claude-opus-5', 'claude-haiku-4-5']);
});

test('a model the table has never seen is reported unpriced, not guessed at', () => {
  // The local tier writes transcripts too, and its ids are not on any Anthropic
  // price list. Rounding them to the nearest family would produce a total that
  // is confidently wrong; counting them separately keeps the estimate
  // answerable.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce6.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], { i: 0, o: 1_000_000 }, { model: 'claude-opus-5' }),
    assistant('r2', 2, [{ type: 'text', text: 'b' }], { i: 0, o: 1_000_000 }, { model: 'qwen3-coder-30b' }),
  ]);
  const g = sessionGraph(file, 'sce6');
  assert.equal(g.totals.estCost.usd, 25, 'only what could honestly be priced');
  assert.equal(g.totals.estCost.unpricedTokens, 1_000_000, 'and the rest is SAID, not dropped');
  assert.deepEqual(g.totals.estCost.byModel.map((r) => r.model), ['claude-opus-5']);
});

test('a session that has spent nothing says null rather than a dollar figure', () => {
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce7.jsonl', [
    userSays('go', 0),
    {
      type: 'assistant',
      uuid: 'a1',
      requestId: 'r1',
      timestamp: T(1),
      message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'text', text: 'hi' }] },
    },
  ]);
  const g = sessionGraph(file, 'sce7');
  assert.equal(g.totals.tokens.output, 0, 'no record carried usage');
  assert.equal(g.totals.estCost, null, 'so there is nothing to price, and $0.00 would be a claim');
  assert.equal(g.totals.agentEstCostUsd, null);
});

// A price is a fact about a moment: Sonnet 5's introductory rate ($2/$10 per
// MTok against the $3/$15 sticker) closed at 2026-09-01T00:00:00Z, and tokens
// spent while it was open were spent at it. The walker's job here is only to
// carry each record's OWN timestamp to the pricer — on both paths.
const IN_WINDOW = '2026-08-30T12:00:00Z';
const AT_STICKER = '2026-09-02T12:00:00Z';

test('a session spanning a rate boundary prices each record at the rate in force', () => {
  // Both records name the same model, so the wire still shows ONE row — a
  // client that renders a breakdown must not suddenly see claude-sonnet-5
  // twice. What changes is the dollars inside it.
  resetCache();
  const dir = tmpdir();
  const file = write(dir, 'sce9.jsonl', [
    userSays('go', 0),
    assistant('r1', 1, [{ type: 'text', text: 'a' }], { i: 0, o: 1_000_000 },
      { model: 'claude-sonnet-5', timestamp: IN_WINDOW }),
    assistant('r2', 2, [{ type: 'text', text: 'b' }], { i: 0, o: 1_000_000 },
      { model: 'claude-sonnet-5', timestamp: AT_STICKER }),
  ]);
  const g = sessionGraph(file, 'sce9');
  assert.equal(g.totals.estCost.usd, 25, '$10 at the intro output rate plus $15 at the sticker one');
  assert.notEqual(g.totals.estCost.usd, 30, 'not both at sticker, which is the whole gap');
  assert.deepEqual(g.totals.estCost.byModel, [{ model: 'claude-sonnet-5', usd: 25 }],
    'one row per model NAME, whatever eras it was spread across');
});

test('an AGENT\'s records carry their own timestamps to the pricer too', () => {
  // ⚠ THE ONE NOTHING ON SCREEN WOULD LOOK WRONG FOR. The agent path is a second
  // accumulation with its own call into the pricer, so a timestamp threaded
  // through the spine and forgotten here leaves every agent's Sonnet 5 spend at
  // the undated fallback — and the fallback is the CHEAPER card, so it would be
  // silently correct for the intro-window record and silently wrong for the one
  // after it. That is why this agent writes on BOTH sides of the boundary:
  // pricing the pair at one rate is wrong in one direction before the fix and
  // the other direction if the timestamp is ever dropped again.
  resetCache();
  const dir = tmpdir();
  const sid = 'sce10';
  const file = write(dir, `${sid}.jsonl`, [
    userSays('fan out', 0),
    assistant('r1', 1, [{ type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'look' } }],
      { i: 0, o: 2_000_000 }, { model: 'claude-opus-5' }),
  ]);
  const subs = path.join(dir, sid, 'subagents');
  fs.mkdirSync(subs, { recursive: true });
  fs.writeFileSync(path.join(subs, 'agent-a1.meta.json'),
    JSON.stringify({ toolUseId: 'ag1', agentType: 'Explore' }));
  fs.writeFileSync(path.join(subs, 'agent-a1.jsonl'), [
    assistant('a-r1', 2, [{ type: 'text', text: 'in the window' }], { i: 0, o: 1_000_000 },
      { model: 'claude-sonnet-5', timestamp: IN_WINDOW }),
    assistant('a-r2', 3, [{ type: 'text', text: 'after it' }], { i: 0, o: 1_000_000 },
      { model: 'claude-sonnet-5', timestamp: AT_STICKER }),
  ].map((r) => JSON.stringify(r)).join('\n') + '\n');

  const g = sessionGraph(file, sid);
  assert.equal(g.totals.agentEstCostUsd, 25, '$10 inside the window, $15 after it');
  assert.notEqual(g.totals.agentEstCostUsd, 30, 'not both at sticker');
  assert.notEqual(g.totals.agentEstCostUsd, 20, 'and not both at the undated fallback');
  assert.equal(g.totals.estCost.usd, 75, '$50 of opus output on the spine, plus the agent');
  assert.deepEqual(g.totals.estCost.byModel.find((r) => r.model === 'claude-sonnet-5'),
    { model: 'claude-sonnet-5', usd: 25 }, 'still one row for the agent\'s model');
});

test('the overview carries the estimate too, or the cheap route is the one without the number', () => {
  // The overview strips the map and keeps the header — and the cost estimate is
  // header, not map. It rides `totals`, so it survives the strip by
  // construction; this pins that it actually does.
  resetCache();
  const dir = tmpdir();
  const o = sessionOverview(simpleSession(dir, 'sce8'), 'sce8');
  assert.equal(o.nodes, undefined, 'still the cheap route');
  assert.equal(o.totals.estCost.usd, 0.00842,
    '4 input + 111 output + 900 unsplit cache writes, all opus');
  assert.deepEqual(o.totals.estCost.byModel, [{ model: 'claude-opus-5', usd: 0.00842 }]);
  assert.equal(o.totals.agentEstCostUsd, 0, 'no agents ran, which is a number and not an absence');
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
