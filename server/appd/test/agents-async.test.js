'use strict';
// The agents route lists without holding the event loop.
//
// appd has one thread, and `GET /v1/sessions/<s>/agents?all=1` used to run its
// whole listing synchronously: on a cold open of a session with ~160 agents the
// phone's /screen and /v1/sessions requests completed in the same instant as
// /agents, 3.5 s in, because they had been waiting behind it. listAgentsAsync is
// the same listing cut into steps, one agent file per step, with a turn of the
// loop between them. Asserted by counting the pauses and by what runs in them,
// never with a stopwatch.
//
// Fixtures are SYNTHETIC agent folders written into a temp dir.

const { test, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const agents = require('../lib/agents');

// Every temp dir this file makes, removed when it is done: some of these
// fixtures are megabytes, and the suite runs on every deploy.
const scratchDirs = [];
const scratch = (dir) => { scratchDirs.push(dir); return dir; };
after(() => { for (const d of scratchDirs) fs.rmSync(d, { recursive: true, force: true }); });

const NOW = 1_800_000_000;
const L = (o) => `${JSON.stringify(o)}\n`;

function stage(count) {
  const root = scratch(fs.mkdtempSync(path.join(os.tmpdir(), 'agents-async-')));
  const dir = path.join(root, 'subagents');
  const run = path.join(dir, 'workflows', 'wf_async-run');
  fs.mkdirSync(run, { recursive: true });
  for (let i = 0; i < count; i++) {
    const inRun = i % 3 === 0;
    const where = inRun ? run : dir;
    const id = `a${String(i).padStart(6, '0')}`;
    const file = path.join(where, `agent-${id}.jsonl`);
    fs.writeFileSync(file, [
      { type: 'user', timestamp: new Date((NOW - 900) * 1000).toISOString(), message: { role: 'user', content: `Task ${i}` } },
      {
        type: 'assistant', requestId: `r${i}`, timestamp: new Date((NOW - 800) * 1000).toISOString(),
        message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'text', text: `Finished ${i}.` }] },
      },
    ].map(L).join(''));
    fs.writeFileSync(path.join(where, `agent-${id}.meta.json`),
      JSON.stringify({ agentType: inRun ? 'workflow-subagent' : 'Explore', spawnDepth: 1 }));
    const at = new Date((NOW - 600 - i) * 1000);
    fs.utimesSync(file, at, at);
  }
  fs.writeFileSync(path.join(run, 'journal.jsonl'),
    L({ type: 'result', agentId: 'a000000', result: { summary: 'Done.' } }));
  return dir;
}

test('the async listing is the same rows as the sync one', async () => {
  const a = stage(9);
  const b = stage(9);
  const sync = agents.listAgents(a, NOW, fs, 200, { all: true });
  const asyncRows = await agents.listAgentsAsync(b, NOW, fs, 200, { all: true });
  assert.equal(sync.length, 9);
  assert.deepEqual(asyncRows, sync);
  // And the default window too: it is one implementation behind both.
  assert.deepEqual(await agents.listAgentsAsync(b, NOW, fs, 24, {}), agents.listAgents(a, NOW, fs, 24, {}));
});

test('it gives the loop a turn after every file it reads, and none when it reads nothing', async () => {
  const dir = stage(12);
  let pauses = 0;
  const yieldFn = () => { pauses++; return Promise.resolve(); };
  const rows = await agents.listAgentsAsync(dir, NOW, fs, 200, { all: true, yieldFn });
  assert.equal(rows.length, 12);
  assert.equal(pauses, 12 + 1, 'one per agent file, one for the run journal');
  pauses = 0;
  await agents.listAgentsAsync(dir, NOW, fs, 200, { all: true, yieldFn });
  assert.equal(pauses, 0, 'everything remembered: nothing to wait for');
});

test('other work runs while a cold listing is in progress', async () => {
  const dir = stage(20);
  let rowsDone = false;
  const seenMidway = [];
  // Queued BEFORE the listing starts: a synchronous listing could not let it
  // run until every row was built.
  setImmediate(() => seenMidway.push(rowsDone));
  const rows = await agents.listAgentsAsync(dir, NOW, fs, 200, { all: true });
  rowsDone = true;
  assert.equal(rows.length, 20);
  assert.deepEqual(seenMidway, [false], 'the queued callback ran before the listing finished');
});
