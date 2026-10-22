'use strict';
// lib/statewatch: which bursts from the state-directory watcher need an alert
// tick at once, and which are the hook's same-state rewrites that can wait.

const { test } = require('node:test');
const assert = require('node:assert');
const { verdict } = require('../lib/statewatch');

const NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_-]{0,49}$/;

/** A verdict against `files` (name -> state word now) and `seen`. */
function v(names, files, seen, { now = 10_000, lastTickAt = 9_000, quietMs = 3_000 } = {}) {
  return verdict({
    names,
    stateOf: (n) => (Object.prototype.hasOwnProperty.call(files, n) ? files[n] : null),
    seen,
    isSession: (n) => NAME_RE.test(n),
    now,
    lastTickAt,
    quietMs,
  });
}
const seenOf = (o) => Object.assign(Object.create(null), o);

test('before any tick has completed, everything ticks', () => {
  assert.equal(v(['a'], { a: 'running' }, null), 'tick');
});

test('a rewrite that still says what the last tick saw waits for the quiet window', () => {
  const seen = seenOf({ a: 'running', b: 'idle' });
  assert.equal(v(['a'], { a: 'running', b: 'idle' }, seen), 'skip');
  assert.equal(v(['a', 'a', 'b'], { a: 'running', b: 'idle' }, seen), 'skip');
  // Once no tick has run for the quiet window, it ticks: who is attached stays fresh.
  assert.equal(v(['a'], { a: 'running' }, seen, { now: 12_000, lastTickAt: 9_000 }), 'tick');
});

test('a changed word ticks at once', () => {
  const seen = seenOf({ a: 'running' });
  assert.equal(v(['a'], { a: 'idle' }, seen), 'tick');
  // A question pending reads as `attention` through readSessionState.
  assert.equal(v(['a'], { a: 'attention' }, seen), 'tick');
});

test('a session appearing or going ticks at once', () => {
  assert.equal(v(['b'], { a: 'running', b: 'running' }, seenOf({ a: 'running' })), 'tick');
  assert.equal(v(['a'], {}, seenOf({ a: 'running' })), 'tick');
});

test('a file the watcher could not name ticks at once', () => {
  assert.equal(v([null], { a: 'running' }, seenOf({ a: 'running' })), 'tick');
  assert.equal(v(['a', null], { a: 'running' }, seenOf({ a: 'running' })), 'tick');
});

test('the hook\'s temp files and the sidecar folders are not sessions', () => {
  const seen = seenOf({ a: 'running' });
  assert.equal(v(['a.tmp', '.ask', '.permhook'], { 'a.tmp': 'idle' }, seen), 'skip');
  // A file with no state that was never seen is not a change either.
  assert.equal(v(['b'], { a: 'running' }, seen), 'skip');
});

test('a session called __proto__ is compared like any other', () => {
  const seen = Object.create(null);
  seen.__proto__ = 'running';
  // JSON.parse, because a literal `{ __proto__: … }` sets the prototype instead.
  assert.equal(v(['__proto__'], JSON.parse('{"__proto__":"running"}'), seen), 'skip');
  assert.equal(v(['__proto__'], JSON.parse('{"__proto__":"idle"}'), seen), 'tick');
});
