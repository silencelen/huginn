'use strict';
// lib/alerthold.js: holding announcements while a display is showing Huginn itself.
//
// A display that mirrors a phone's notifications draws each one on top of what it
// was showing. While the thing it is showing is Huginn, the client there asks for
// a hold: what would have been announced waits, and goes out when the hold ends —
// if it is still true by then. These are the rules of that, as pure functions:
// what a request may ask for, how a hold begins, grows and runs out, what joins
// the queue, and what leaves it.

const { test } = require('node:test');
const assert = require('node:assert');
const hold = require('../lib/alerthold');

const T0 = 1_760_000_000_000;
const alert = (kind, subject, extra = {}) => ({
  key: `${kind}:${subject}`, kind, subject, title: `${subject} ${kind}`, text: 'words', ...extra,
});

// ---- the request ------------------------------------------------------------------

test('seconds is a number from 1 to 120, and nothing else will do', () => {
  assert.deepEqual(hold.holdRequest({ seconds: 1 }), { seconds: 1, by: 'a display' });
  assert.deepEqual(hold.holdRequest({ seconds: 120 }), { seconds: 120, by: 'a display' });
  assert.deepEqual(hold.holdRequest({ seconds: 2.5 }), { seconds: 2.5, by: 'a display' });
  for (const bad of [0, 0.99, 120.01, 121, -5, NaN, Infinity, '30', true, null, undefined, {}, [30]]) {
    const r = hold.holdRequest({ seconds: bad });
    assert.equal(r.error, 'seconds must be a number from 1 to 120', `seconds: ${String(bad)} must be refused`);
  }
  for (const body of [undefined, null, 'x', 7, [], [{ seconds: 5 }]]) {
    assert.equal(hold.holdRequest(body).error, 'seconds must be a number from 1 to 120');
  }
});

test('by is optional, a string, and cleaned before it can reach a log line', () => {
  assert.equal(hold.holdRequest({ seconds: 5, by: '  glasses  ' }).by, 'glasses');
  assert.equal(hold.holdRequest({ seconds: 5, by: '' }).by, 'a display');
  assert.equal(hold.holdRequest({ seconds: 5, by: '   ' }).by, 'a display');
  // A line break, an escape, a right-to-left override, a zero-width space, a line separator.
  const nasty = `one${String.fromCharCode(10)}two${String.fromCharCode(27)}[31m${String.fromCharCode(0x202e)}three${String.fromCharCode(0x200b)}${String.fromCharCode(0x2028)}four`;
  const cleaned = hold.holdRequest({ seconds: 5, by: nasty }).by;
  assert.equal(cleaned, 'onetwo[31mthreefour');
  for (const ch of cleaned) assert.ok(ch.codePointAt(0) >= 0x20 && ch.codePointAt(0) < 0x7f);
  // Cut to 40 characters, counted as characters: an emoji is not cut in half.
  const long = hold.holdRequest({ seconds: 5, by: `${'x'.repeat(39)}${String.fromCodePoint(0x1f576)}tail` }).by;
  assert.equal(Array.from(long).length, 40);
  assert.equal(long.codePointAt(39), 0x1f576);
  for (const bad of [null, 7, true, {}, ['glasses']]) {
    assert.equal(hold.holdRequest({ seconds: 5, by: bad }).error, 'by must be a string');
  }
});

// ---- beginning, extending, running out ---------------------------------------------

test('a hold begins now and lasts as long as it was asked to', () => {
  const r = hold.extend(null, { seconds: 30, by: 'glasses' }, T0);
  assert.deepEqual(r, { hold: { since: T0, until: T0 + 30_000, by: 'glasses' }, released: false });
  assert.equal(hold.live(r.hold, T0 + 29_999), true);
  assert.equal(hold.live(r.hold, T0 + 30_000), false);
  assert.equal(hold.live(null, T0), false);
});

test('extending keeps the start, moves the end, and never shortens', () => {
  const first = hold.extend(null, { seconds: 60, by: 'glasses' }, T0).hold;
  const longer = hold.extend(first, { seconds: 60, by: 'glasses' }, T0 + 20_000);
  assert.deepEqual(longer.hold, { since: T0, until: T0 + 80_000, by: 'glasses' });
  // A second holder asking for less does not cut the first one short.
  const shorter = hold.extend(longer.hold, { seconds: 5, by: 'other' }, T0 + 30_000);
  assert.equal(shorter.hold.until, T0 + 80_000);
  assert.equal(shorter.hold.since, T0);
  assert.equal(shorter.hold.by, 'other');
});

test('a hold that ran out is not extended: the next request begins a new one', () => {
  const first = hold.extend(null, { seconds: 10, by: 'glasses' }, T0).hold;
  const again = hold.extend(first, { seconds: 10, by: 'glasses' }, T0 + 10_000);
  assert.equal(again.released, false);
  assert.equal(again.hold.since, T0 + 10_000);
});

test('renewed without a break for ten minutes, it ends on the renewal that crosses it', () => {
  let h = hold.extend(null, { seconds: 30, by: 'glasses' }, T0).hold;
  let t = T0;
  // Renewed every 20 s: still held one renewal short of ten minutes.
  while (t + 20_000 < T0 + hold.MAX_RUN_MS) {
    t += 20_000;
    const r = hold.extend(h, { seconds: 30, by: 'glasses' }, t);
    assert.equal(r.released, false, `still held ${(t - T0) / 1000} s in`);
    assert.equal(r.hold.since, T0);
    h = r.hold;
  }
  const crossing = hold.extend(h, { seconds: 30, by: 'glasses' }, t + 20_000);
  assert.deepEqual(crossing, { hold: null, released: true });
  // And the request after that starts over.
  const fresh = hold.extend(crossing.hold, { seconds: 30, by: 'glasses' }, t + 25_000);
  assert.equal(fresh.released, false);
  assert.equal(fresh.hold.since, t + 25_000);
});

test('the view is epoch seconds, and null once the hold is not live', () => {
  const h = { since: T0 + 400, until: T0 + 30_400, by: 'glasses' };
  assert.deepEqual(hold.view(h, T0 + 1000), { since: T0 / 1000, until: T0 / 1000 + 31, by: 'glasses' });
  assert.equal(hold.view(h, T0 + 30_400), null);
  assert.equal(hold.view(null, T0), null);
});

// ---- the queue ------------------------------------------------------------------------

test('what a tick decided joins the queue, stamped with when it was held', () => {
  const t = hold.take(undefined, [alert('session_finished', 'build'), alert('chat_finished', 'c1')], T0);
  assert.deepEqual(t.held.map((a) => [a.kind, a.subject, a.heldAt]),
    [['session_finished', 'build', T0], ['chat_finished', 'c1', T0]]);
  assert.equal(t.queued.length, 2);
  assert.equal(t.cancelled.length, 0);
  // The alert's own fields ride along: they are what gets sent later.
  assert.equal(t.held[0].title, 'build session_finished');
});

test('an alert replaces an earlier one with the same key, and moves to the end', () => {
  const first = hold.take([], [alert('chat_finished', 'c1', { text: 'old' }), alert('session_finished', 'build')], T0).held;
  const t = hold.take(first, [alert('chat_finished', 'c1', { text: 'new' })], T0 + 5000);
  assert.deepEqual(t.held.map((a) => [a.subject, a.text]), [['build', 'words'], ['c1', 'new']]);
});

test('an entry that comes back after a failed send keeps the time it was first held', () => {
  const first = hold.take([], [alert('session_finished', 'build')], T0).held;
  const back = hold.take([], [first[0]], T0 + 60_000).held;
  assert.equal(back[0].heldAt, T0);
});

test('a question answered while it waits takes itself out, and the resolution goes with it', () => {
  const q = hold.take([], [alert('session_attention', 'build'), alert('session_finished', 'other')], T0).held;
  const t = hold.take(q, [alert('session_resolved', 'build')], T0 + 4000);
  assert.deepEqual(t.held.map((a) => [a.kind, a.subject]), [['session_finished', 'other']]);
  assert.deepEqual(t.cancelled.map((a) => [a.kind, a.subject]), [['session_attention', 'build']]);
  assert.equal(t.queued.length, 0);
});

test('a resolution for a question announced before the hold waits like the rest', () => {
  const t = hold.take([], [alert('session_resolved', 'build')], T0);
  assert.deepEqual(t.held.map((a) => a.kind), ['session_resolved']);
  // A second question from the same session, and its answer: only that pair goes.
  const again = hold.take(t.held, [alert('session_attention', 'build')], T0 + 1000).held;
  const done = hold.take(again, [alert('session_resolved', 'build', { key: 'session-resolved:build:2' })], T0 + 2000);
  assert.deepEqual(done.held.map((a) => a.kind), ['session_resolved']);
  assert.equal(done.held[0].heldAt, T0);
});

test('the queue holds fifty and lets the oldest go', () => {
  const many = Array.from({ length: hold.MAX_HELD + 7 }, (_, i) => alert('chat_finished', `c${i}`));
  const t = hold.take([], many, T0);
  assert.equal(t.held.length, hold.MAX_HELD);
  assert.equal(t.held[0].subject, 'c7');
  assert.equal(t.held[hold.MAX_HELD - 1].subject, `c${hold.MAX_HELD + 6}`);
});

test('what is not an alert never joins, and a damaged queue reads as what is sound in it', () => {
  const t = hold.take('nonsense', [null, 'x', { kind: 'chat_finished' }, { key: 'k' }, alert('chat_finished', 'c1')], T0);
  assert.deepEqual(t.held.map((a) => a.subject), ['c1']);
  assert.deepEqual(hold.queue([null, 7, { key: 'k' }, alert('chat_finished', 'c2')]).map((a) => a.subject), ['c2']);
  assert.deepEqual(hold.queue({ not: 'an array' }), []);
});

test('the holder is told what waits: the words, and when', () => {
  const held = hold.take([], [alert('session_attention', 'build', { fingerprint: 'f', options: [{ number: 1 }] })], T0 + 900).held;
  assert.deepEqual(hold.heldView(held), [{
    key: 'session_attention:build', kind: 'session_attention', subject: 'build',
    title: 'build session_attention', text: 'words', at: T0 / 1000,
  }]);
  assert.deepEqual(hold.heldView(undefined), []);
});

// ---- the release: only what is still true ---------------------------------------------

// Name-keyed maps as the daemon's observation carries them.
const obs = (o = {}) => ({ sessions: {}, chats: {}, chatAsks: {}, sessionsProject: {}, ...o });

test('a question is sent if the session is still waiting on it', () => {
  const held = hold.take([], [alert('session_attention', 'build')], T0).held;
  assert.equal(hold.release(held, obs({ sessions: { build: 'attention' } })).alerts.length, 1);
  for (const state of ['running', 'idle', 'off']) {
    const r = hold.release(held, obs({ sessions: { build: state } }));
    assert.deepEqual([r.alerts.length, r.dropped.length], [0, 1], `not while it is ${state}`);
  }
  assert.equal(hold.release(held, obs()).alerts.length, 0, 'nor when the session is gone');
});

test('a finish is sent if the session is still idle', () => {
  const held = hold.take([], [alert('session_finished', 'build')], T0).held;
  assert.equal(hold.release(held, obs({ sessions: { build: 'idle' } })).alerts.length, 1);
  for (const state of ['running', 'attention']) {
    assert.equal(hold.release(held, obs({ sessions: { build: state } })).alerts.length, 0, `not while it is ${state}`);
  }
  assert.equal(hold.release(held, obs()).alerts.length, 0);
});

test('a resolution is always sent', () => {
  const held = hold.take([], [alert('session_resolved', 'build')], T0).held;
  assert.equal(hold.release(held, obs()).alerts.length, 1);
  assert.equal(hold.release(held, obs({ sessions: { build: 'attention' } })).alerts.length, 1);
});

test('a project going idle is sent unless one of its sessions is busy again', () => {
  const held = hold.take([], [alert('project_idle', 'p1')], T0).held;
  const members = { 'p1-lead': { id: 'p1', name: 'Site', role: 'lead' }, 'p1-web': { id: 'p1', name: 'Site', role: 'web' }, 'p2-lead': { id: 'p2', name: 'Other', role: 'lead' } };
  const quiet = obs({ sessionsProject: members, sessions: { 'p1-lead': 'idle', 'p1-web': 'idle', 'p2-lead': 'running' } });
  assert.equal(hold.release(held, quiet).alerts.length, 1, 'another project being busy is not this one');
  for (const state of ['running', 'attention']) {
    const busy = obs({ sessionsProject: members, sessions: { 'p1-lead': 'idle', 'p1-web': state, 'p2-lead': 'idle' } });
    assert.equal(hold.release(held, busy).alerts.length, 0, `not while a member is ${state}`);
  }
  assert.equal(hold.release(held, obs()).alerts.length, 1, 'a project with no session left has nothing busy');
});

test('a chat finish is sent if the chat is there and not running again', () => {
  const held = hold.take([], [alert('chat_finished', 'c1')], T0).held;
  assert.equal(hold.release(held, obs({ chats: { c1: { running: false, finishedRuns: 2 } } })).alerts.length, 1);
  assert.equal(hold.release(held, obs({ chats: { c1: { running: true, finishedRuns: 2 } } })).alerts.length, 0);
  assert.equal(hold.release(held, obs()).alerts.length, 0, 'a deleted chat announces nothing');
});

test('a chat request is sent if that request is still waiting', () => {
  const held = hold.take([], [alert('chat_attention', 'c1', { requestId: 'r7' })], T0).held;
  assert.equal(hold.release(held, obs({ chatAsks: { c1: { title: 't', asks: [{ id: 'r7' }] } } })).alerts.length, 1);
  assert.equal(hold.release(held, obs({ chatAsks: { c1: { title: 't', asks: [{ id: 'r8' }] } } })).alerts.length, 0);
  assert.equal(hold.release(held, obs()).alerts.length, 0);
});

test('a kind this file does not know is sent rather than swallowed', () => {
  const held = hold.take([], [alert('something_new', 'x')], T0).held;
  assert.equal(hold.release(held, obs()).alerts.length, 1);
});

test('the release keeps the order things were held in, and says what it dropped', () => {
  const held = hold.take([], [
    alert('session_resolved', 'a'), alert('session_finished', 'b'), alert('chat_finished', 'c1'), alert('session_attention', 'a'),
  ], T0).held;
  const r = hold.release(held, obs({ sessions: { a: 'attention', b: 'running' }, chats: { c1: { running: false } } }));
  assert.deepEqual(r.alerts.map((x) => [x.kind, x.subject]),
    [['session_resolved', 'a'], ['chat_finished', 'c1'], ['session_attention', 'a']]);
  assert.deepEqual(r.dropped.map((x) => [x.kind, x.subject]), [['session_finished', 'b']]);
  assert.ok(r.alerts.every((x) => x.heldAt === T0), 'a released alert still says it was held');
});

test('a session called __proto__ is a session like any other', () => {
  const held = hold.take([], [alert('session_attention', '__proto__'), alert('session_finished', 'constructor')], T0).held;
  // Reloaded from JSON these maps are plain objects, whose prototype answers both names.
  assert.equal(hold.release(held, obs()).alerts.length, 0);
  const sessions = Object.create(null);
  sessions.__proto__ = 'attention'; sessions.constructor = 'idle';
  assert.equal(hold.release(held, obs({ sessions })).alerts.length, 2);
});
