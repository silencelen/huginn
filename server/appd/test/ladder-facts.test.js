'use strict';
// The `laddered` fact on /v1/watch, and the `ladder` mark on a /v1/sessions row:
// which sessions the model ladder has MOVED, and to what.
//
// Both apps read a name appearing in `laddered` as "<name> moved to <model> —
// its Fable window ran out", with an Undo, and a name leaving it as "<name> is
// back on its own model". Every ladder record that carried a `to` went in: a
// move merely QUEUED behind a running turn, one that could not be confirmed (no
// "Moved" push is sent for those, on purpose), and the owner's own "Back to
// Fable" on a native switch while it was on its way — which announced a
// downgrade to fable whose Undo moved the session to opus. A queued move that
// was then dropped announced a move back that had not happened either.
//
// headroomFacts and headroomForSession are lifted out of huginn-appd.js and run
// over the records the daemon writes; the facts go through the real watch
// digest, which is what a client compares frame to frame.

const { test } = require('node:test');
const assert = require('node:assert');
const headroomLib = require('../lib/headroom');
const { digest } = require('../lib/watch');
const { lift } = require('./ladder-lift');

const AT = 1_789_500_000_000;

function daemon(sessions) {
  const st = {
    mode: 'red', sessions, sentinels: { STOP: null, 'STOP-FABLE': null },
    arbiter: { lastResumeAt: 0, lastLadderAt: AT },
  };
  const L = lift(['headroomFacts', 'headroomForSession'], {
    headroomLib,
    hstate: () => st,
    loadSessionMeta: () => null,
    loadHeadroomSettings: () => headroomLib.defaults(),
  });
  return { st, ...L };
}
const rec = (name, family, ladder) => ({ name, family, ladder, nativeSwitch: { seenAt: null, to: null }, stall: null });

const MOVE = { from: 'fable', to: 'opus', at: AT, sessionOnly: true };

test('only a move that happened is a move', () => {
  const d = daemon({
    'sid-queued': rec('queued', 'fable', { ...MOVE, delivery: 'pending' }),
    'sid-unconf': rec('unconf', 'fable', { ...MOVE, delivery: 'delivery_unconfirmed' }),
    'sid-moved': rec('moved', 'opus', { ...MOVE, delivery: 'confirmed' }),
    // A move back UP queued behind a turn: still on opus until it lands.
    'sid-goingup': rec('goingup', 'opus', { ...MOVE, delivery: 'pending' }),
    // The owner's "Back to Fable" on a session Claude Code moved itself.
    'sid-native': rec('native', 'opus', { from: 'opus', to: 'fable', at: AT, delivery: 'pending', sessionOnly: true, how: 'undo-native' }),
  });
  assert.deepEqual(d.headroomFacts().laddered, { moved: 'opus', goingup: 'opus' });
  for (const [id, want] of [['sid-queued', null], ['sid-unconf', null], ['sid-moved', 'opus'],
    ['sid-goingup', 'opus'], ['sid-native', null]]) {
    assert.equal(d.headroomForSession(id).ladder, want, `${id}'s "on <model>" mark`);
  }
});

test('a move queued and then dropped never reaches the apps at all', () => {
  const d = daemon({ 'sid-x': rec('x', 'fable', null) });
  const frames = [];
  const frame = () => frames.push(digest([], [], d.headroomFacts()).headroom.laddered);
  frame();
  d.st.sessions['sid-x'].ladder = { ...MOVE, delivery: 'pending' };     // queued mid-turn
  frame();
  d.st.sessions['sid-x'].ladder = null;                                  // dropped: the owner typed
  frame();
  assert.deepEqual(frames, [{}, {}, {}], 'no "moved to opus", then no "back on its own model"');
});

test('a queued move that lands appears when it lands, and leaves when the move back lands', () => {
  const d = daemon({ 'sid-x': rec('x', 'fable', { ...MOVE, delivery: 'pending' }) });
  const seen = () => digest([], [], d.headroomFacts()).headroom.laddered;
  assert.deepEqual(seen(), {});
  Object.assign(d.st.sessions['sid-x'], { family: 'opus', ladder: { ...MOVE, delivery: 'confirmed' } });
  assert.deepEqual(seen(), { x: 'opus' });
  d.st.sessions['sid-x'].ladder = { ...MOVE, delivery: 'pending' };     // the week reset: going back up
  assert.deepEqual(seen(), { x: 'opus' }, 'not "back on its own model" until it is');
  Object.assign(d.st.sessions['sid-x'], { family: 'fable', ladder: null });
  assert.deepEqual(seen(), {});
});
