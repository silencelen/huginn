'use strict';
// One `ps` snapshot of the process table serves every background-work question
// (psSnapshot) for five seconds: polls a few seconds apart share it, and asks
// that arrive together share the one that is running.
//
// The daemon's REAL psSnapshot, lifted out of huginn-appd.js, with `ps` and the
// clock stood in for.

const { test } = require('node:test');
const assert = require('node:assert');
const { lift } = require('./appd-lift');

function setup() {
  let now = 1_000_000;
  const calls = { ps: 0 };
  const { fns } = lift(['psSnapshot'], {
    Date: { now: () => now },
    run: async () => {
      calls.ps += 1;
      await new Promise((r) => setImmediate(r)); // a real ps takes a while to answer
      return { err: null, stdout: '', stderr: '' };
    },
    parsePs: () => new Map(),
    psCache: { at: 0, procs: new Map() },
    psInFlight: null,
  }, { constants: ['PS_SNAPSHOT_TTL_MS'] });
  return { psSnapshot: fns.psSnapshot, calls, tick: (ms) => { now += ms; } };
}

test('polls a few seconds apart share one ps', async () => {
  const s = setup();
  await s.psSnapshot();
  s.tick(2_000);
  await s.psSnapshot();
  s.tick(2_000);
  await s.psSnapshot();
  assert.equal(s.calls.ps, 1, 'three asks inside five seconds');
});

test('after five seconds the process table is read again', async () => {
  const s = setup();
  await s.psSnapshot();
  s.tick(5_000);
  await s.psSnapshot();
  assert.equal(s.calls.ps, 2);
});

test('asks that arrive together share one ps', async () => {
  // The session list asks for every row at once; each ask found the cache
  // expired and started its own ps.
  const s = setup();
  const answers = await Promise.all([s.psSnapshot(), s.psSnapshot(), s.psSnapshot()]);
  assert.equal(s.calls.ps, 1, 'three rows, one ps');
  assert.ok(answers.every((a) => a === answers[0]), 'every row reads the same snapshot');
  s.tick(5_000);
  await Promise.all([s.psSnapshot(), s.psSnapshot()]);
  assert.equal(s.calls.ps, 2, 'expired again: one more ps, not two');
});
