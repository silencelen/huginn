'use strict';
// "Back to Fable" on a session CLAUDE CODE moved off Fable by itself — the
// headroom undo route's native branch — when the session is mid-turn, so the
// move waits in the send queue for the turn boundary.
//
// While it waited, the route's own in-flight record ({from: opus, to: fable,
// how: 'undo-native'}) read as one of appd's ladder moves: a second press
// "undid" it with `back = ladder.from` — opus — and queued a second job, so the
// session went to Fable and straight back to opus. And when the queued job could
// not confirm, nothing cleared that record: it held every ladder move off the
// session for good and aimed the next press at opus.
//
// The route block is lifted out of huginn-appd.js and run against a stand-in
// send queue: a job is queued (the turn is running) and runs at boundary().

const { test } = require('node:test');
const assert = require('node:assert');
const headroomLib = require('../lib/headroom');
const { lift, between } = require('./ladder-lift');

const ROUTE = between(
  "    if ((m = p.match(/^\\/v1\\/sessions\\/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})\\/headroom\\/undo$/)) && req.method === 'POST') {",
  '      return sendJson(res, 200, body);\n    }',
);

function harness(rec) {
  const st = { sessions: { 'sid-work': rec }, arbiter: { lastLadderAt: 0 } };
  const queue = [];
  const moved = [];
  let ladderResult = { ok: true, delivery: 'confirmed' };
  const { box } = lift([], {
    requireSession: async () => true,
    readSessionState: () => ({ sessionId: 'sid-work' }),
    sendErr: (res, code, error) => { res.code = code; res.body = { error }; },
    sendJson: (res, code, body) => { res.code = code; res.body = body; },
    hstate: () => st,
    pendingEntries: () => queue,
    loadHeadroomSettings: () => headroomLib.defaults(),
    applyLadder: async (name, to) => { moved.push(to); return ladderResult; },
    // Mid-turn: the queue holds the job for the boundary.
    enqueueJob: async (name, run, opts) => {
      queue.push({ kind: opts.kind, automated: !!opts.automated, origin: opts.origin, run, opts });
      return { result: null, dropped: null };
    },
    saveHeadroomState: () => { },
    log: () => { },
    Date,
  }, { extra: `this.route = async function (p, req, res) { let m;\n${ROUTE}\n};` });
  return {
    st, queue, moved,
    fail(r) { ladderResult = r; },
    async press() {
      const res = {};
      await box.route('/v1/sessions/work/headroom/undo', { method: 'POST' }, res);
      return res;
    },
    /** The turn ends: what is queued runs, in order, and settles. */
    async boundary() {
      while (queue.length) {
        const e = queue.shift();
        e.opts.onSettle({ result: await e.run() });
      }
    },
  };
}

const nativelyMoved = () => ({
  name: 'work', family: 'opus', ladder: null, humanSetModelAt: null, offeredLadderUpAt: Date.now() - 60_000,
  nativeSwitch: { seenAt: Date.now() - 3 * 86_400_000, to: 'opus', how: 'native' },
});

test('pressing "Back to Fable" twice while the session is mid-turn moves it to Fable once', async () => {
  const hx = harness(nativelyMoved());
  const first = await hx.press();
  assert.deepEqual([first.code, first.body.queued, first.body.to], [200, true, 'fable']);
  const second = await hx.press();
  assert.equal(second.code, 200);
  assert.equal(second.body.to, 'fable', 'the second press is the same request, not an undo of it');
  assert.equal(second.body.queued, true);
  assert.equal(hx.queue.length, 1, 'one move waiting, not two');

  await hx.boundary();
  assert.deepEqual(hx.moved, ['fable'], 'it ends on Fable');
  const rec = hx.st.sessions['sid-work'];
  assert.equal(rec.ladder, null);
  assert.equal(rec.family, 'fable');
  assert.equal(rec.nativeSwitch.seenAt, null);
});

test('a queued "Back to Fable" that does not land leaves no record behind', async () => {
  const hx = harness(nativelyMoved());
  await hx.press();
  hx.fail({ ok: false, delivery: 'delivery_unconfirmed', reason: 'the model picker never appeared' });
  await hx.boundary();
  const rec = hx.st.sessions['sid-work'];
  assert.equal(rec.ladder, null, 'nothing is "waiting for a turn boundary" any more');
  assert.equal(rec.nativeSwitch.to, 'opus', 'and the native mark is still the truth: nothing moved');

  // So the arbiter is not held off by a move that is not coming, and the next
  // press aims at Fable again.
  const v = headroomLib.decide({
    active: { slug: 'a', windows: { weekly_fable: { percent: 30 } } },
    sessions: [{ claudeSessionId: 'sid-work', name: 'work', state: 'idle', ...rec }],
    now: Date.now(), state: {},
  });
  assert.doesNotMatch(v.why, /waiting for a turn boundary/);
  hx.fail({ ok: true, delivery: 'confirmed' });
  const again = await hx.press();
  assert.equal(again.body.to, 'fable');
});

test('a "Back to Fable" record whose job is gone (a restart) is not taken for a ladder move', async () => {
  const rec = nativelyMoved();
  rec.ladder = { from: 'opus', to: 'fable', at: Date.now() - 3600_000, delivery: 'pending', sessionOnly: true, how: 'undo-native' };
  const hx = harness(rec);                         // the queue is empty: the job died with the old daemon
  const r = await hx.press();
  assert.equal(r.body.to, 'fable');
  assert.equal(hx.queue.length, 1);
  await hx.boundary();
  assert.deepEqual(hx.moved, ['fable']);
});
