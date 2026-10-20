'use strict';
// A move back UP the ladder (the Fable week reset) that could not be confirmed.
//
// decide() builds a ladder_up with its from/to pointing up the ladder — from
// the rung the session was moved to, to the one it came from. The job that ran
// at once and failed wrote THOSE into the record, turning fable->opus into
// opus->fable: the session was never moved back (there is no rung above fable),
// both clients announced "moved to fable", and the Undo on the original
// notification moved it to opus. The queued path already kept the record; this
// pins the same outcome for the job that runs in the first pump pass, which is
// the usual case for an idle session.

const { test } = require('node:test');
const assert = require('node:assert');
const h = require('../lib/headroom');
const { lift } = require('./ladder-lift');

const NOW = 1_789_500_000_000;
const MIN = 60_000;
const settings = h.defaults();
const active = { slug: 'a', windows: { session: { percent: 5 }, weekly_all: { percent: 10 }, weekly_fable: { percent: 12 } } };
const movedAt = NOW - 3 * 24 * 60 * MIN;
const resetAt = NOW - 2 * 60 * MIN;

function daemon(jobResult) {
  return lift(['applyHeadroomAction', 'resolvePendingLadder', 'noteSessionModel'], {
    headroomLib: h,
    log: () => { },
    enqueueJob: async (name, run, opts) => ({ result: jobResult, opts }),
    deliverPush: async () => ({ sent: true }),
    deliverTelegram: async () => null,
    saveHeadroomState: () => { },
    repairDefaultModel: () => ({ ok: true, changed: false }),
    GLYPH: { info: 'i' },
    Date,
  });
}

/** x, moved fable -> opus by the ladder three days ago and still on opus. */
function laddered() {
  return {
    name: 'x', family: 'opus', model: 'claude-opus-5', headsUpAt: null, humanSetModelAt: null,
    nativeSwitch: { seenAt: null, to: null },
    ladder: { from: 'fable', to: 'opus', at: movedAt, delivery: 'confirmed', sessionOnly: true, repairedDefault: false },
  };
}
const view = (rec) => ({
  claudeSessionId: 'sid-x', name: 'x', state: 'idle', family: rec.family, ladder: rec.ladder,
  nativeSwitch: rec.nativeSwitch, humanSetModelAt: rec.humanSetModelAt, ladderFailedAt: rec.ladderFailedAt,
});
const decideAt = (rec, now) => h.decide({
  active, settings, now, state: { lastLadderAt: movedAt }, sessions: [view(rec)], lastFableResetAt: resetAt,
});

test('a failed move back up keeps the record of the move down', async () => {
  const rec = laddered();
  const state = { sessions: { 'sid-x': rec }, arbiter: { lastLadderAt: movedAt } };
  const up = decideAt(rec, NOW).actions.find((a) => a.type === 'ladder_up');
  assert.ok(up, 'the reset makes it a move back up');
  assert.deepEqual([up.from, up.to], ['opus', 'fable']);

  const { applyHeadroomAction } = daemon({ ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' });
  await applyHeadroomAction(up, { state, settings, now: NOW });
  assert.equal(rec.ladder.from, 'fable', 'still the move DOWN: fable -> opus');
  assert.equal(rec.ladder.to, 'opus');
  assert.equal(rec.ladder.at, movedAt, 'and when it was made');
  assert.equal(rec.ladder.delivery, 'delivery_unconfirmed');
  // The Undo route sends the session back to `ladder.from`.
  assert.equal(rec.ladder.from, 'fable', 'Undo must still mean "back to fable"');
});

test('...so it is tried again after a cooldown, and settled when it does land', async () => {
  const rec = laddered();
  const state = { sessions: { 'sid-x': rec }, arbiter: { lastLadderAt: movedAt } };
  const L = daemon({ ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' });
  const up = decideAt(rec, NOW).actions.find((a) => a.type === 'ladder_up');
  await L.applyHeadroomAction(up, { state, settings, now: NOW });

  // Next tick: still on opus. Nothing moved; the move down still stands.
  L.noteSessionModel(rec, { model: 'claude-opus-5' }, NOW + MIN, settings);
  assert.equal(rec.ladder.delivery, 'confirmed');
  const soon = decideAt(rec, NOW + 5 * MIN);
  assert.equal(soon.actions.some((a) => a.type === 'ladder_up'), false, 'not typed at again on the next tick');
  const later = decideAt(rec, NOW + 31 * MIN);
  assert.deepEqual(later.actions.filter((a) => a.type === 'ladder_up').map((a) => [a.from, a.to]), [['opus', 'fable']]);

  // The second try lands.
  const ok = daemon({ ok: true, delivery: 'confirmed', to: 'fable' });
  await ok.applyHeadroomAction(later.actions.find((a) => a.type === 'ladder_up'), { state, settings, now: NOW + 31 * MIN });
  assert.equal(rec.ladder, null);
  assert.equal(rec.family, 'fable');
  assert.equal(rec.ladderFailedAt, null);
});

test('a failed move back that landed after all is settled as back on its own model', async () => {
  const rec = laddered();
  const state = { sessions: { 'sid-x': rec }, arbiter: { lastLadderAt: movedAt } };
  const L = daemon({ ok: false, delivery: 'delivery_unconfirmed', reason: 'Claude Code recorded no switch for this session' });
  await L.applyHeadroomAction(decideAt(rec, NOW).actions.find((a) => a.type === 'ladder_up'), { state, settings, now: NOW });
  // The switch record arrived late: the session reads as Fable.
  L.noteSessionModel(rec, { model: 'Fable 5.1' }, NOW + MIN, settings);
  assert.equal(rec.ladder, null);
  assert.equal(rec.family, 'fable');
  assert.equal(rec.nativeSwitch.seenAt, null);
});
