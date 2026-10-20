'use strict';
// A session stalled on a usage limit is not a candidate for a ladder move.
//
// Auto-resume waits for the 429 to still be the session's last record at the
// reset. A move typed into a stalled session (the 429 is a turn boundary, so the
// job runs at once) leaves Claude Code's `/model` command record and its
// `Set model to … for this session only` output after the 429 — and the resume
// pass cannot tell those from a person answering: it filed the stall as
// "a person answered it", the next pass cleared it, the desktop announced the
// session as resumed, and nothing ever continued it. The arbiter was never told
// about stalls at all, although the tick's own comment says a stalled session
// is not a ladder candidate.

const { test } = require('node:test');
const assert = require('node:assert');
const h = require('../lib/headroom');
const resume = require('../lib/resume');

const NOW = 1_789_500_000_000;
const MIN = 60_000;
const settings = h.defaults();
const fableAt = (percent, severity = 'normal') => ({
  slug: 'a',
  windows: { session: { percent: 5 }, weekly_all: { percent: 40 }, weekly_fable: { percent, severity, label: 'Current week (Fable)' } },
});
const sess = (name, over = {}) => ({
  claudeSessionId: `sid-${name}`, name, state: 'idle', family: 'fable', ladder: null,
  headsUpAt: NOW - 60 * MIN, nativeSwitch: { seenAt: null, to: null }, humanSetModelAt: null, ...over,
});
const stall = (window, over = {}) => ({
  at: NOW - 2 * MIN, window, resetsAt: NOW + 4 * 24 * 60 * MIN, resetsAtSource: 'endpoint',
  resumedAt: null, queuedAt: null, gaveUpAt: null, how: null, attempts: 0, nativeArmed: false, ...over,
});
const moves = (v) => v.actions.filter((a) => /^ladder_/.test(a.type)).map((a) => `${a.type}:${a.name}`);

test('a session stalled on the Fable week is not moved down; it is resumed after the reset', () => {
  const v = h.decide({
    active: fableAt(100, 'exceeded'), settings, now: NOW, state: { lastLadderAt: NOW - 60 * MIN },
    sessions: [sess('b', { stall: stall('weekly_fable') })],
  });
  assert.deepEqual(moves(v), [], v.why);
  assert.match(v.why, /b is stalled on a usage limit/);
});

test('nor is one stalled on the 5-hour window while the Fable week is red', () => {
  const v = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall('session') }), sess('c')],
  });
  assert.deepEqual(moves(v), ['ladder_down:c'], 'the next session gets the move instead');
});

test('once its stall has been resumed, the session is a candidate again', () => {
  const v = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall('session', { resumedAt: NOW - MIN, how: 'appd' }) })],
  });
  assert.deepEqual(moves(v), ['ladder_down:b']);
  // A blank stall (every session record carries one) is no stall.
  const blank = h.decide({
    active: fableAt(95), settings, now: NOW, state: { lastLadderAt: 0 },
    sessions: [sess('b', { stall: stall(null, { at: null }) })],
  });
  assert.deepEqual(moves(blank), ['ladder_down:b']);
});

test('a laddered session stalled on another limit is not moved back up under its 429 either', () => {
  const ladder = { from: 'fable', to: 'opus', at: NOW - 3 * 24 * 60 * MIN, delivery: 'confirmed' };
  const input = (s) => ({
    active: fableAt(10), settings, now: NOW, state: { lastLadderAt: 0 },
    lastFableResetAt: NOW - 30 * MIN, sessions: [s],
  });
  assert.deepEqual(moves(h.decide(input(sess('x', { family: 'opus', ladder, stall: stall('session') })))), []);
  assert.deepEqual(moves(h.decide(input(sess('x', { family: 'opus', ladder })))), ['ladder_up:x'], 'the control');
});

test('why it matters: what a move leaves after the 429 reads as a person to the resume pass', () => {
  // The records Claude Code 2.1.30x writes for the picker's session-only switch.
  const after = [
    { type: 'user', isMeta: true, message: { content: '<local-command-caveat>Caveat: …</local-command-caveat>' } },
    { type: 'user', message: { content: '<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args></command-args>' } },
    { type: 'user', message: { content: '<local-command-stdout>Set model to `Opus 5` for this session only</local-command-stdout>' } },
  ];
  assert.equal(after.some((r) => resume.isHumanRecord(r)), true,
    'so the move must not be made: the resume would be cancelled as "a person answered it"');
});
