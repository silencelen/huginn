'use strict';
// A Round run that died on a usage limit and is re-run after the reset gets its
// whole time budget, and no second run starts while it waits.
//
// The cap was measured from the ORIGINAL fire. A 03:00 Round whose window reset at
// 05:30 was re-run then, and the next 30-second tick found it 2 h 31 min into a
// 15-minute budget: cancelled at once, and the wrap-up turn was told it had used
// all of it. And because a run held for a reset is not in activeRuns, the next slot
// fired a second run beside it; after the reset both re-ran, the older one under
// no Round cap at all.
//
// The daemon's own rerunStalledRun, roundsTick and fireRound, lifted into a sandbox
// with a fake clock, an in-memory chat store and stand-ins for the spawn.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const roundsLib = require('../lib/rounds');
const { lift } = require('./appd-lift');

const T0 = 1_772_967_600;                 // 2026-03-08 03:00 PST, when the Round fired
const SESSION = '0f3c9a52-6c1e-4d1e-9a40-6f7d3c2b1a00';

/** One Round with one held-open run, and the daemon pieces that act on them. */
function world({ autoResume = true } = {}) {
  const clock = { ms: T0 * 1000 };
  class FakeDate extends Date { static now() { return clock.ms; } }
  const metas = new Map();
  const activeRuns = new Map();
  const cancelled = [];
  const spawned = [];
  const round = {
    id: 'r1', title: 'PBS check', enabled: true, timeoutSec: 900, currentChatId: 'c1',
    schedule: { kind: 'daily', at: '03:00', tz: 'America/Los_Angeles' },
    nextRunAt: (T0 + 86_400) * 1000, runs: [],
  };
  metas.set('c1', {
    id: 'c1', roundId: 'r1', roundStartedAt: T0, claudeSessionId: SESSION, host: 'local',
    stall: { at: (T0 + 5) * 1000, window: 'five_hour', resetsAt: (T0 + 9000) * 1000,
      userText: 'Check PBS and report.', attempts: 0 },
  });
  const copy = (o) => JSON.parse(JSON.stringify(o));
  const { fns } = lift(['rerunStalledRun', 'roundsTick', 'fireRound', 'clampRoundTimeout', 'waitingForReset'], {
    Date: FakeDate, process: { env: {} }, crypto, roundsLib,
    log: () => {},
    activeRuns,
    loadMeta: (id) => (metas.has(id) ? copy(metas.get(id)) : null),
    saveMeta: (m) => { metas.set(m.id, copy(m)); },
    updateMeta: (id, fn) => { const m = metas.get(id); if (m) fn(m); return m; },
    appendMsg: () => {},
    startRunAnywhere: (meta, text) => {
      const run = { chatId: meta.id, text };
      activeRuns.set(meta.id, run);
      spawned.push(meta.id);
      return { run };
    },
    cancelRun: (run) => { cancelled.push(run.chatId); },
    listRounds: () => [round],
    loadRound: () => round,
    updateRound: (id, fn) => { fn(round); return round; },
    recordSkippedRound: () => {},
    isLocalFamily: () => false,
    loadHeadroomSettings: () => ({ autoResume }),
    runDateIn: () => '2026-03-08',
    chatDir: (id) => `/nonexistent/${id}`,
    fs: { rmSync: () => {} },
  }, { constants: ['DEFAULT_ROUND_TIMEOUT_S', 'ROUND_WRAPUP_GRACE_MS'] });
  return { fns, clock, metas, activeRuns, cancelled, spawned, round };
}

test('a Round re-run after a late reset is not cancelled by the cap it spent waiting', async () => {
  const w = world();
  w.clock.ms = (T0 + 9010) * 1000;        // the window reset at 05:30; the re-run starts
  const r = w.fns.rerunStalledRun('c1');
  assert.equal(r.ok, true, r.error);
  assert.ok(w.activeRuns.has('c1'));

  w.clock.ms = (T0 + 9035) * 1000;        // the next rounds tick
  await w.fns.roundsTick();
  assert.deepEqual(w.cancelled, [], 'cancelled for passing a 15-minute cap measured from 03:00');
  assert.equal(w.metas.get('c1').roundStartedAt, T0 + 9010, 'the budget runs from the re-run');

  w.clock.ms = (T0 + 9010 + 901) * 1000;  // and the re-run's own budget still binds it
  await w.fns.roundsTick();
  assert.deepEqual(w.cancelled, ['c1']);
});

test('no second run fires while the previous one is held for a reset', () => {
  const w = world();
  const fired = w.fns.fireRound(w.round);
  assert.equal(fired.code, 409, `a second run fired: ${JSON.stringify(fired)}`);
  assert.match(fired.error, /waiting for the usage limit to reset/);
  assert.deepEqual(w.spawned, []);
  assert.deepEqual([...w.metas.keys()], ['c1'], 'no new run chat was created');
});

test('with auto-resume off, a held run does not block the Round for good', () => {
  // Nothing will ever re-run it, so it is not "still going".
  const w = world({ autoResume: false });
  const fired = w.fns.fireRound(w.round);
  assert.equal(fired.error, undefined, fired.error);
  assert.equal(w.spawned.length, 1);
});
