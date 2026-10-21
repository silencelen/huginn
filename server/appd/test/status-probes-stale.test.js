'use strict';
// GET /v1/status's two slow facts — `claude --version`, and the memory node's
// health over ssh — are served from the last answer while ONE refresh runs.
//
// They used to be refreshed inside the status request that found them expired:
// the ssh probe once a minute, the version once an hour. Those requests took
// half a second to a second (the route's p90), and two polls that arrived
// together each started a probe of their own.
//
// The daemon's REAL functions, lifted out of huginn-appd.js, with `run` and the
// clock under the test's control.

const { test } = require('node:test');
const assert = require('node:assert');
const { SRC, lift } = require('./appd-lift');

// The probes' own state and constants, each one line at the top level of the
// daemon: lifted with them, so they run on the daemon's numbers.
const STATE = SRC.split('\n').filter((l) => /^(?:let|const) (?:cachedClaudeVersion|claudeVersionAt|claudeVersionAsk|CLAUDE_VERSION_TTL_MS|mpCache|mpAsk|MP_FRESH_MS|MP_STAND_IN_MS)\b/.test(l)).join('\n');

/** The probes and their state, fresh for each test; `run` answers when told to. */
function setup() {
  // A real epoch: a cache that has never been filled is stamped 0, which has to
  // read as long ago.
  const box = { now: T0, asks: [], answer: null };
  const { fns } = lift(['claudeVersion', 'mempalaceState'], {
    Date: { now: () => box.now },
    MEMPALACE_HOST: 'memory.example.net',
    MEMPALACE_MARKER: '~/.mempalace/REBUILD_IN_PROGRESS',
    run: (cmd, args) => new Promise((resolve) => {
      box.asks.push(cmd);
      box.answer = (stdout) => resolve({ err: null, stdout, stderr: '' });
    }),
  }, { extra: STATE });
  return { ...fns, box };
}

const T0 = Date.parse('2026-10-20T12:00:00.000Z');
const tick = () => new Promise((r) => setImmediate(r));
/** The call's answer if it has one without waiting on a probe, else 'still waiting'. */
async function now(promise) {
  const waiting = Symbol('waiting');
  const got = await Promise.race([promise, (async () => { await tick(); await tick(); return waiting; })()]);
  return got === waiting ? 'still waiting' : got;
}

test('an expired memory-node answer is served at once while one refresh runs', async () => {
  const { mempalaceState, box } = setup();
  const first = mempalaceState();
  box.answer('ok\n');
  assert.equal(await first, 'ok');
  assert.equal(box.asks.length, 1);

  box.now = T0 + 61_000;
  const polls = [mempalaceState(), mempalaceState(), mempalaceState()];
  for (const p of polls) assert.equal(await now(p), 'ok');
  assert.equal(box.asks.length, 2, 'three polls started more than one probe');

  box.answer('daemon-down\n');
  await tick();
  box.now = T0 + 62_000;
  assert.equal(await mempalaceState(), 'daemon-down');
  assert.equal(box.asks.length, 2);
});

test('an answer too old to stand in is waited for, and shared', async () => {
  const { mempalaceState, box } = setup();
  const first = mempalaceState();
  box.answer('ok\n');
  await first;

  box.now = T0 + 31 * 60 * 1000;
  const a = mempalaceState();
  const b = mempalaceState();
  assert.equal(await now(a), 'still waiting');
  assert.equal(box.asks.length, 2);
  box.answer('rebuilding\n');
  assert.deepStrictEqual([await a, await b], ['rebuilding', 'rebuilding']);
});

test('the claude version is served from the last answer past its hour', async () => {
  const { claudeVersion, box } = setup();
  const first = claudeVersion();
  box.answer('2.1.300 (Claude Code)\n');
  assert.equal(await first, '2.1.300 (Claude Code)');

  box.now = T0 + 61 * 60 * 1000;
  assert.equal(await now(claudeVersion()), '2.1.300 (Claude Code)');
  assert.equal(await now(claudeVersion()), '2.1.300 (Claude Code)');
  assert.deepStrictEqual(box.asks, ['claude', 'claude']);
  box.answer('2.1.302 (Claude Code)\n');
  await tick();
  assert.equal(await claudeVersion(), '2.1.302 (Claude Code)');
});
