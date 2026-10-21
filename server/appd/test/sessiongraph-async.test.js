'use strict';
// /graph, /overview and the project dashboard walk a cold session without
// holding the event loop.
//
// A cold walk is the whole of a session — parent and every agent file — and on
// the heaviest session measured that was ~1.4 GB. appd has one thread, so the
// synchronous walk held every other request until it finished. The async walk
// is the same walk in bounded steps with a turn of the loop between them; the
// cursors stay the source of truth, so a second caller that walks the same file
// in between shortens the work rather than doubling it. Asserted by counting the
// pauses and by what runs inside them, never with a stopwatch.
//
// Fixtures are SYNTHETIC transcripts written into a temp dir.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const graph = require('../lib/sessiongraph');
const projects = require('../lib/projects');

const clock0 = 1_756_000_000;
const NOW = (clock0 + 5000) * 1000;
const T = (s) => new Date((clock0 + s) * 1000).toISOString();
const L = (o) => `${JSON.stringify(o)}\n`;

const user = (text, at) => ({ type: 'user', timestamp: T(at), message: { role: 'user', content: text } });
const call = (id, at, blocks, o = 3) => ({
  type: 'assistant', requestId: id, timestamp: T(at),
  message: { role: 'assistant', model: 'claude-opus-5', content: blocks, usage: { input_tokens: 2, output_tokens: o } },
});
const result = (id, at, content = 'ok') => ({
  type: 'user', timestamp: T(at), message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content }] },
});

/** A parent of several walk steps, a direct agent and a two-member run. */
function session(name, { turns = 60 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'graph-async-'));
  const sid = name;
  const file = path.join(dir, `${sid}.jsonl`);
  const lines = [user('start', 0)];
  for (let k = 0; k < turns; k++) {
    const at = 10 + k * 20;
    lines.push(user(`turn ${k}`, at));
    lines.push(call(`p${k}`, at + 1, [{ type: 'tool_use', id: `b${k}`, name: 'Bash', input: { command: `step ${k}` } }]));
    // Tool output large enough that the parent spans several steps.
    lines.push(result(`b${k}`, at + 2, 'o'.repeat(100 * 1024)));
    lines.push(call(`p${k}r`, at + 3, [{ type: 'text', text: `Done with ${k}.` }]));
  }
  lines.push(call('pa', 2000, [
    { type: 'tool_use', id: 'ag1', name: 'Agent', input: { description: 'Check the switches' } },
    { type: 'tool_use', id: 'wf1', name: 'Workflow', input: { script: "export const meta = {name: 'sweep'}" } },
  ]));
  lines.push(result('wf1', 2001, 'started wf_async-run'));
  lines.push(result('ag1', 2100, 'switches fine'));
  fs.writeFileSync(file, lines.map(L).join(''));
  const subs = path.join(dir, sid, 'subagents');
  const run = path.join(subs, 'workflows', 'wf_async-run');
  fs.mkdirSync(run, { recursive: true });
  const agent = (where, id, recs, meta) => {
    fs.writeFileSync(path.join(where, `agent-${id}.jsonl`), recs.map(L).join(''));
    fs.writeFileSync(path.join(where, `agent-${id}.meta.json`), JSON.stringify(meta));
  };
  const work = (id, at) => {
    const recs = [user(`Task ${id}`, at)];
    for (let k = 0; k < 30; k++) {
      recs.push(call(`${id}-${k}`, at + k + 1, [{ type: 'tool_use', id: `${id}t${k}`, name: 'Read', input: { file_path: `/srv/example/${k}` } }], 4));
      recs.push(result(`${id}t${k}`, at + k + 1, 'a'.repeat(40 * 1024)));
    }
    return recs;
  };
  agent(subs, 'd1', work('d1', 2002), { agentType: 'Explore', toolUseId: 'ag1', spawnDepth: 1 });
  agent(run, 'w1', work('w1', 2003), { agentType: 'workflow-subagent', spawnDepth: 1 });
  agent(run, 'w2', work('w2', 2004), { agentType: 'workflow-subagent', spawnDepth: 1 });
  fs.writeFileSync(path.join(run, 'journal.jsonl'), L({ type: 'result', agentId: 'w1', result: { summary: 'Clean.' } }));
  return { file, sid };
}

test('the async graph is the sync graph', async () => {
  const s = session('ga1');
  graph.resetCache();
  const sync = graph.sessionGraph(s.file, s.sid, { now: NOW });
  graph.resetCache();
  const asyncG = await graph.sessionGraphAsync(s.file, s.sid, { now: NOW });
  assert.deepEqual(asyncG, sync);
  assert.equal(asyncG.agents.length, 3);
  graph.resetCache();
  assert.deepEqual(await graph.sessionOverviewAsync(s.file, s.sid, { now: NOW }), graph.sessionOverview(s.file, s.sid, { now: NOW }));
});

test('a cold walk pauses between steps, and a warm one never does', async () => {
  const s = session('ga2', { turns: 80 });
  graph.resetCache();
  const size = fs.statSync(s.file).size;
  assert.ok(size > 3 * 2 * 1024 * 1024, 'a parent of several steps');
  let pauses = 0;
  const pause = () => { pauses++; return Promise.resolve(); };
  await graph.sessionGraphAsync(s.file, s.sid, { now: NOW, pause });
  assert.ok(pauses >= Math.floor(size / (2 * 1024 * 1024)) + 3,
    `one pause per step of the parent and one at least per agent file (${pauses})`);
  pauses = 0;
  await graph.sessionGraphAsync(s.file, s.sid, { now: NOW, pause });
  assert.equal(pauses, 0, 'nothing new: nothing to wait for');
});

test('other work runs while a cold graph is being walked', async () => {
  const s = session('ga3');
  graph.resetCache();
  let walked = false;
  const seen = [];
  setImmediate(() => seen.push(walked));
  await graph.sessionGraphAsync(s.file, s.sid, { now: NOW });
  walked = true;
  assert.deepEqual(seen, [false]);
});

test('a second caller walking the same session in a pause shortens the work, never doubles it', async () => {
  const s = session('ga4', { turns: 80 });
  graph.resetCache();
  let pauses = 0;
  let other = null;
  let midway = null;
  const pause = async () => {
    pauses++;
    // The shapes of other requests landing mid-walk on the same session: an
    // /overview that walks alongside this one, step for step, and later a
    // /graph-shaped sync walk straight to the end.
    if (pauses === 1) other = graph.sessionOverviewAsync(s.file, s.sid, { now: NOW });
    if (pauses === 3) midway = graph.sessionGraph(s.file, s.sid, { now: NOW });
    await new Promise((resolve) => setImmediate(resolve));
  };
  const g = await graph.sessionGraphAsync(s.file, s.sid, { now: NOW, pause });
  const o = await other;
  assert.ok(pauses >= 3 && midway, 'the interleaving happened');
  graph.resetCache();
  const fresh = graph.sessionGraph(s.file, s.sid, { now: NOW });
  assert.deepEqual(g, fresh, 'every record counted exactly once');
  assert.deepEqual(midway, fresh, 'and the sync walk in the middle saw the same session');
  const { nodes, agents, workflows, ...freshOverview } = fresh;
  assert.deepEqual(o, freshOverview, 'and so did the walk that ran alongside');
});

test('the dashboard rolls up through the async walk and still skips members that did not move', async () => {
  graph.resetCache();
  const members = ['gm1', 'gm2', 'gm3'].map((name) => {
    const s = session(name, { turns: 4 });
    return { role: name, sessionId: s.sid, transcript: s.file };
  });
  const io = {
    sizeOf: (f) => fs.statSync(f).size,
    agentBytesOf: graph.agentBytesOf,
    now: NOW,
  };
  const syncCache = new Map();
  const want = projects.rollupMembers(members, {
    ...io, cache: syncCache, overview: (f, id) => graph.sessionOverview(f, id, { now: NOW }),
  });
  graph.resetCache();
  const asyncCache = new Map();
  let walks = 0;
  const overview = (f, id) => { walks++; return graph.sessionOverviewAsync(f, id, { now: NOW }); };
  const got = await projects.rollupMembersAsync(members, { ...io, cache: asyncCache, overview });
  assert.deepEqual(got, want);
  assert.equal(walks, 3);
  await projects.rollupMembersAsync(members, { ...io, cache: asyncCache, overview });
  assert.equal(walks, 3, 'nobody grew, so nobody was walked');
  fs.appendFileSync(members[1].transcript, L(user('one more', 4000)));
  await projects.rollupMembersAsync(members, { ...io, cache: asyncCache, overview });
  assert.equal(walks, 4, 'only the member that grew');
});
