'use strict';
// A ladder move appd could not CONFIRM — the picker never appeared, a dialog
// opened before Enter, no record of the switch within five seconds — and what
// the arbiter makes of it afterwards.
//
// The record such an attempt left behind ({to: 'opus', delivery:
// 'delivery_unconfirmed'}) was read as "already on opus" while the session was
// still on Fable: it was never tried again, however red the week got, and
// after the reset its "move back" was a job the queue dropped every time. The
// next read of the transcript is the proof either way, so the record is
// settled by it; and a session whose move keeps failing is tried again, but
// only when no other session is waiting for the one move a cooldown allows.

const { test } = require('node:test');
const assert = require('node:assert');
const h = require('../lib/headroom');
const { lift } = require('./ladder-lift');

const NOW = 1_789_500_000_000;
const MIN = 60_000;
const settings = h.defaults();                  // ladderPct 92, cooldown 30 min

const active = (fable, extra = {}) => ({
  slug: 'a', email: 'a@example.test',
  windows: {
    session: { percent: 5 }, weekly_all: { percent: 10 },
    weekly_fable: { percent: fable, label: 'Current week (Fable)', ...extra },
  },
});
const sess = (name, over = {}) => ({
  claudeSessionId: `sid-${name}`, name, state: 'idle', family: 'fable', ladder: null,
  headsUpAt: NOW - 3 * 60 * MIN, nativeSwitch: { seenAt: null, to: null }, humanSetModelAt: null, ...over,
});
const moves = (v) => v.actions.filter((a) => a.type === 'ladder_down' || a.type === 'ladder_up')
  .map((a) => `${a.type}:${a.name}`);

const unconfirmed = (at) => ({ from: 'fable', to: 'opus', at, delivery: 'delivery_unconfirmed', sessionOnly: true });

test('an unconfirmed move on a session still on Fable is tried again once the cooldown is over', () => {
  const tried = NOW - 31 * MIN;
  const v = h.decide({
    active: active(95), settings, now: NOW,
    state: { lastSwitchAt: 0, lastLadderAt: tried },
    sessions: [sess('dev', { ladder: unconfirmed(tried), ladderFailedAt: tried })],
  });
  assert.deepEqual(moves(v), ['ladder_down:dev'], v.why);
  assert.doesNotMatch(v.why, /already on/);
});

test('...and not before: the cooldown holds it, not a move that never happened', () => {
  const tried = NOW - 5 * MIN;
  const v = h.decide({
    active: active(95), settings, now: NOW,
    state: { lastSwitchAt: 0, lastLadderAt: tried },
    sessions: [sess('dev', { ladder: unconfirmed(tried), ladderFailedAt: tried })],
  });
  assert.deepEqual(moves(v), []);
  assert.match(v.why, /cooling down/);
});

test('an unconfirmed move that DID land is a move: held as moved, and moved back after the reset', () => {
  const tried = NOW - 2 * 24 * 60 * MIN;
  const onOpus = sess('dev', { family: 'opus', ladder: unconfirmed(tried), ladderFailedAt: tried });
  const red = h.decide({ active: active(97), settings, now: NOW, state: { lastLadderAt: tried }, sessions: [onOpus] });
  assert.deepEqual(moves(red), [], 'it is on opus already: nothing to move');

  const reset = h.decide({
    active: active(10), settings, now: NOW, state: { lastLadderAt: tried }, sessions: [onOpus],
    lastFableResetAt: NOW - 30 * MIN,
  });
  assert.deepEqual(moves(reset), ['ladder_up:dev']);
});

test('a session that never left Fable is not "moved back" when the week resets', () => {
  // The job such a move-back queues carries family opus and the queue drops it
  // as soon as it sees fable — every tick, for the rest of the session.
  const tried = NOW - 2 * 24 * 60 * MIN;
  const v = h.decide({
    active: active(10), settings, now: NOW, state: { lastLadderAt: tried },
    sessions: [sess('dev', { ladder: unconfirmed(tried), ladderFailedAt: tried })],
    lastFableResetAt: NOW - 30 * MIN,
  });
  assert.deepEqual(moves(v), []);
});

test('a session whose move failed goes after every session that has not been tried', () => {
  // The sessions arrive most-recently-active first, and appd's own `/model`
  // makes the failed one the most recently active.
  const tried = NOW - 40 * MIN;
  const both = h.decide({
    active: active(95), settings, now: NOW, state: { lastLadderAt: tried },
    sessions: [sess('stuck', { ladderFailedAt: tried }), sess('fresh')],
  });
  assert.deepEqual(moves(both), ['ladder_down:fresh']);

  // Alone, it is tried again.
  const alone = h.decide({
    active: active(95), settings, now: NOW, state: { lastLadderAt: tried },
    sessions: [sess('stuck', { ladderFailedAt: tried })],
  });
  assert.deepEqual(moves(alone), ['ladder_down:stuck']);

  // Two that both failed: the one that failed longest ago first.
  const two = h.decide({
    active: active(95), settings, now: NOW, state: { lastLadderAt: tried },
    sessions: [sess('recent', { ladderFailedAt: tried }), sess('older', { ladderFailedAt: tried - 60 * MIN })],
  });
  assert.deepEqual(moves(two), ['ladder_down:older']);

  // A failure from before the week reset is history, not a reason to wait.
  const lastWeek = h.decide({
    active: active(95), settings, now: NOW, state: { lastLadderAt: tried },
    sessions: [sess('was', { ladderFailedAt: NOW - 8 * 24 * 60 * MIN }), sess('fresh')],
    lastFableResetAt: NOW - 24 * 60 * MIN,
  });
  assert.deepEqual(moves(lastWeek), ['ladder_down:was']);
});

test('settleLadder: the next read of the session decides an unconfirmed record', () => {
  const u = unconfirmed(NOW);
  assert.equal(h.settleLadder(u, 'fable'), null, 'still on the rung it was leaving: nothing moved');
  assert.deepEqual(h.settleLadder(u, 'opus'), { ...u, delivery: 'confirmed' }, 'on the rung it was going to');
  assert.equal(h.settleLadder(u, 'sonnet'), u, 'anything else proves nothing');
  assert.equal(h.settleLadder(u, null), u, 'nor does no reading at all');
  for (const other of [{ ...u, delivery: 'confirmed' }, { ...u, delivery: 'pending' }, { repairedDefault: true }]) {
    assert.equal(h.settleLadder(other, 'fable'), other, `${other.delivery || 'a record with no move'} is left alone`);
  }
  assert.equal(h.settleLadder(null, 'fable'), null);
});

/**
 * The daemon's own path: applyHeadroomAction (and the queued settle) write the
 * record, the next tick's noteSessionModel reads the session, decide decides.
 */
function daemon(jobResult) {
  const logs = [];
  const jobs = [];
  const L = lift(['applyHeadroomAction', 'resolvePendingLadder', 'noteSessionModel'], {
    headroomLib: h,
    log: (...a) => logs.push(a.join(' ')),
    enqueueJob: async (name, run, opts) => { jobs.push({ name, opts }); return { result: jobResult }; },
    deliverPush: async () => ({ sent: true }),
    deliverTelegram: async () => null,
    saveHeadroomState: () => { },
    repairDefaultModel: () => ({ ok: true, changed: false }),
    GLYPH: { info: 'i' },
    Date,
  });
  return { ...L, logs, jobs };
}

test('the daemon\'s failed move is settled on the next read, and tried again after the cooldown', async () => {
  const d = daemon({ ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' });
  const rec = { name: 'dev', family: 'fable', model: 'claude-fable-5-1', ladder: null, nativeSwitch: { seenAt: null, to: null } };
  const state = { sessions: { 'sid-dev': rec }, arbiter: { lastLadderAt: 0 } };
  const at = NOW;
  await d.applyHeadroomAction(
    { type: 'ladder_down', claudeSessionId: 'sid-dev', name: 'dev', from: 'fable', to: 'opus', pct: 93 },
    { state, settings, now: at });
  assert.equal(rec.ladder.delivery, 'delivery_unconfirmed');
  assert.equal(rec.ladderFailedAt, at);

  // Next tick: the transcript still ends on a Fable reply.
  d.noteSessionModel(rec, { model: 'claude-fable-5-1' }, at + MIN, settings);
  assert.equal(rec.ladder, null, 'nothing moved, so there is no move to remember');
  assert.equal(rec.family, 'fable');

  const later = at + 31 * MIN;
  const v = h.decide({
    active: active(96), settings, now: later, state: { lastLadderAt: state.arbiter.lastLadderAt },
    sessions: [sess('dev', { family: rec.family, ladder: rec.ladder, ladderFailedAt: rec.ladderFailedAt })],
  });
  assert.deepEqual(moves(v), ['ladder_down:dev'], v.why);
});

test('a failed move that landed after all is recorded as appd\'s, not as Claude Code\'s own', async () => {
  const d = daemon({ ok: false, delivery: 'delivery_unconfirmed', reason: 'Claude Code recorded no switch for this session' });
  const rec = { name: 'dev', family: 'fable', model: 'claude-fable-5-1', ladder: null, nativeSwitch: { seenAt: null, to: null } };
  const state = { sessions: { 'sid-dev': rec }, arbiter: { lastLadderAt: 0 } };
  await d.applyHeadroomAction(
    { type: 'ladder_down', claudeSessionId: 'sid-dev', name: 'dev', from: 'fable', to: 'opus', pct: 93 },
    { state, settings, now: NOW });
  // The switch record arrived after the five-second window.
  d.noteSessionModel(rec, { model: 'Opus 5' }, NOW + MIN, settings);
  assert.equal(rec.family, 'opus');
  assert.equal(rec.ladder.delivery, 'confirmed');
  assert.equal(rec.nativeSwitch.seenAt, null, 'appd moved it; it is not a native fallback');
});

test('the queued path: a move that waited for a boundary and then failed is settled the same way', async () => {
  const d = daemon(null);                        // accepted, not run: the turn is still going
  const rec = { name: 'dev', family: 'fable', model: 'claude-fable-5-1', ladder: null, nativeSwitch: { seenAt: null, to: null } };
  const state = { sessions: { 'sid-dev': rec }, arbiter: { lastLadderAt: 0 } };
  const action = { type: 'ladder_down', claudeSessionId: 'sid-dev', name: 'dev', from: 'fable', to: 'opus', pct: 93 };
  await d.applyHeadroomAction(action, { state, settings, now: NOW });
  assert.equal(rec.ladder.delivery, 'pending');

  d.jobs[0].opts.onSettle({ result: { ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' } });
  assert.equal(rec.ladder.delivery, 'delivery_unconfirmed');
  assert.ok(rec.ladderFailedAt >= NOW, 'the failure is stamped');

  d.noteSessionModel(rec, { model: 'claude-fable-5-1' }, Date.now() + MIN, settings);
  assert.equal(rec.ladder, null);
});
