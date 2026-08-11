'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const { createPending, stepSoftEnd, KILL_STABLE_MS, ARM_TIMEOUT_MS, TTL_MS } = require('../lib/softend');

// Drive the machine through a sequence of (state, dtMs) observations and return
// the final action + pending.
function run(steps, t0 = 1_000_000) {
  let now = t0;
  let pending = createPending(now);
  let last = null;
  for (const [state, dt] of steps) {
    now += dt;
    const r = stepSoftEnd(pending, state, now);
    pending = r.pending;
    last = r.action;
  }
  return { action: last, pending };
}

test('running arms, then a stable idle kills', () => {
  const { action } = run([
    ['running', 100],
    ['idle', 100],
    ['idle', KILL_STABLE_MS + 10],
  ]);
  assert.equal(action, 'kill');
});

test('first running observation reports arm', () => {
  let now = 0;
  const r = stepSoftEnd(createPending(now), 'running', now + 50);
  assert.equal(r.action, 'arm');
  assert.equal(r.pending.armed, true);
});

test('idle before arming just waits (that idle predates the phrase)', () => {
  const { action } = run([
    ['idle', 100],
    ['idle', 500],
  ]);
  assert.equal(action, 'wait');
});

test('attention cancels even after arming (a wrap-up question must not be killed)', () => {
  const { action } = run([
    ['running', 100],
    ['idle', 100],
    ['attention', 100],
  ]);
  assert.equal(action, 'cancel');
});

test('queued-phrase race: brief idle then running clears the kill timer', () => {
  // turn ends -> momentary idle -> queued wrap-up submits -> running -> real idle
  const { action, pending } = run([
    ['running', 100],   // arm
    ['idle', 200],      // brief turn-boundary idle (< KILL_STABLE_MS)
    ['running', 300],   // queued phrase submitted, wrap-up running
    ['idle', 100],      // wrap-up done
  ]);
  // idleSince was reset by the running observation, so this last idle has not
  // held long enough yet.
  assert.equal(action, 'wait');
  assert.ok(pending.idleSince != null);
});

test('never-armed session expires after the arm timeout', () => {
  const { action } = run([
    ['idle', 100],
    ['idle', ARM_TIMEOUT_MS + 10],
  ]);
  assert.equal(action, 'expire');
});

test('no state file, past the arm timeout, expires', () => {
  const { action } = run([
    [null, 100],
    [null, ARM_TIMEOUT_MS + 10],
  ]);
  assert.equal(action, 'expire');
});

test('TTL is an absolute backstop even while running', () => {
  const now = 0;
  const p = { requestedAt: now, armed: true, idleSince: null };
  const r = stepSoftEnd(p, 'running', now + TTL_MS + 1);
  assert.equal(r.action, 'expire');
});
