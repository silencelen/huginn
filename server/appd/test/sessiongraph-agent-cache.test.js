'use strict';
// /graph and /overview read nothing they have already read.
//
// The walker has always resumed each transcript where it stopped, but three
// reads happened again on every call: each workflow run's journal, TWICE (once
// for its summaries, once for which agents settled), the head of any agent whose
// first record yields no label, and the meta of any agent whose meta never
// landed. A run journal reaches a megabyte on a real fan-out, and the dashboard
// and the map poll every few seconds. Counted in bytes, so the test cannot
// flake on a busy host.
//
// Fixtures are SYNTHETIC transcripts written into a temp dir.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const graph = require('../lib/sessiongraph');

const clock = 1_756_000_000;
const T = (s) => new Date((clock + s) * 1000).toISOString();
const L = (o) => `${JSON.stringify(o)}\n`;

const user = (text, at) => ({ type: 'user', timestamp: T(at), message: { role: 'user', content: text } });
const call = (id, at, blocks, o = 5) => ({
  type: 'assistant', requestId: id, timestamp: T(at),
  message: { role: 'assistant', model: 'claude-opus-5', content: blocks, usage: { input_tokens: 1, output_tokens: o } },
});
const result = (id, at, content = 'ok') => ({
  type: 'user', timestamp: T(at), message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content }] },
});

function session() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'graph-cache-'));
  const sid = 'gc1';
  const file = path.join(dir, `${sid}.jsonl`);
  fs.writeFileSync(file, [
    user('fan out', 0),
    call('p1', 1, [
      { type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'Check the switches' } },
      { type: 'tool_use', id: 'wf1', name: 'Workflow', input: { script: "export const meta = {name: 'sweep'}" } },
    ]),
    result('wf1', 2, 'started wf_cache-run'),
    result('ag1', 30, 'switches fine'),
  ].map(L).join(''));
  const subs = path.join(dir, sid, 'subagents');
  const run = path.join(subs, 'workflows', 'wf_cache-run');
  fs.mkdirSync(run, { recursive: true });
  const agent = (where, id, records, meta) => {
    fs.writeFileSync(path.join(where, `agent-${id}.jsonl`), records.map(L).join(''));
    if (meta) fs.writeFileSync(path.join(where, `agent-${id}.meta.json`), JSON.stringify(meta));
  };
  agent(subs, 'd1', [user('Check the switches', 3), call('d1a', 4, [{ type: 'text', text: 'fine' }])],
    { agentType: 'Explore', toolUseId: 'ag1', spawnDepth: 1 });
  // A first record that yields no label, and no meta at all: the two retries.
  agent(subs, 'd2', [call('d2a', 5, [{ type: 'tool_use', id: 'x1', name: 'Bash', input: { command: 'true' } }])], null);
  for (const id of ['w1', 'w2']) {
    agent(run, id, [user(`Sweep ${id}`, 6), call(`${id}a`, 7, [{ type: 'text', text: 'swept' }])],
      { agentType: 'workflow-subagent', spawnDepth: 1 });
  }
  // A journal of a realistic size: one result line per member, and padding
  // `started` lines the way a long run accumulates them.
  const lines = [L({ type: 'result', agentId: 'w1', result: { summary: 'Swept, clean.' } })];
  for (let k = 0; k < 2000; k++) lines.push(L({ type: 'started', agentId: `pad${k}`, note: 'x'.repeat(100) }));
  fs.writeFileSync(path.join(run, 'journal.jsonl'), lines.join(''));
  return { file, sid };
}

function bytesRead(fn) {
  let bytes = 0;
  const orig = { readSync: fs.readSync, readFileSync: fs.readFileSync };
  fs.readSync = function (...a) { const n = orig.readSync.apply(fs, a); bytes += n; return n; };
  fs.readFileSync = function (...a) { const r = orig.readFileSync.apply(fs, a); bytes += Buffer.isBuffer(r) ? r.length : Buffer.byteLength(r); return r; };
  let out;
  try { out = fn(); } finally { Object.assign(fs, orig); }
  return { out, bytes };
}

test('a second graph over unchanged files reads nothing', () => {
  graph.resetCache();
  const { file, sid } = session();
  const first = bytesRead(() => graph.sessionGraph(file, sid));
  assert.equal(first.out.agents.length, 4);
  const byId = new Map(first.out.agents.map((a) => [a.id, a]));
  assert.equal(byId.get('w1').status, 'done', 'settled in the journal');
  assert.equal(byId.get('w1').summary, 'Swept, clean.');
  assert.equal(byId.get('d1').status, 'done', 'merged by the parent');
  assert.equal(byId.get('d2').description, null, 'no label to be had');
  assert.ok(first.bytes > 0);

  const second = bytesRead(() => graph.sessionGraph(file, sid));
  assert.deepEqual(second.out.agents, first.out.agents);
  assert.equal(second.bytes, 0, `read ${second.bytes} bytes again`);
  const overview = bytesRead(() => graph.sessionOverview(file, sid));
  assert.equal(overview.bytes, 0, 'nor does the overview, which is the same walk');
  assert.equal(overview.out.totals.agentCount, 4);
});

test('a journal that grows is seen by the next graph', () => {
  graph.resetCache();
  const { file, sid } = session();
  const run = path.join(path.dirname(file), sid, 'subagents', 'workflows', 'wf_cache-run');
  const before = graph.sessionGraph(file, sid).agents.find((a) => a.id === 'w2');
  assert.notEqual(before.status, 'done');
  fs.appendFileSync(path.join(run, 'journal.jsonl'), L({ type: 'result', agentId: 'w2', result: { summary: 'Two  nits.' } }));
  const after = graph.sessionGraph(file, sid).agents.find((a) => a.id === 'w2');
  assert.equal(after.status, 'done');
  assert.equal(after.summary, 'Two nits.');
});
