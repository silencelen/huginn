'use strict';
const test = require('node:test');
const assert = require('node:assert');
const push = require('../lib/pushtokens');

const T0 = 1_800_000_000_000;

test('registering a device stores its token', () => {
  const st = push.emptyState();
  const r = push.register(st, 'install-1', 'tok-a', T0);
  assert.equal(r.changed, true);
  assert.equal(push.count(st), 1);
  assert.equal(push.list(st)[0].token, 'tok-a');
});

// The reason tokens are keyed by installation and not by token: Firebase reissues a
// token after a reinstall or restore, and keying by the token itself would leave the
// old one behind to be retried forever.
test('a rotated token replaces its predecessor rather than joining it', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  const r = push.register(st, 'install-1', 'tok-b', T0 + 1000);
  assert.equal(r.rotated, true);
  assert.equal(push.count(st), 1, 'one phone, one token');
  assert.equal(push.list(st)[0].token, 'tok-b');
});

test('two different phones both register', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.register(st, 'install-2', 'tok-b', T0);
  assert.equal(push.count(st), 2);
});

// The app re-registers on every start; rewriting the file each time would be pure
// churn, so an unchanged token reports no change while still refreshing its recency.
test('re-registering the same token reports no change but is not stale', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  const r = push.register(st, 'install-1', 'tok-a', T0 + 60_000);
  assert.equal(r.changed, false);
  assert.equal(r.rotated, false);
  assert.equal(push.list(st)[0].seenAt, Math.floor((T0 + 60_000) / 1000));
});

test('the first sighting is preserved across a rotation', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.register(st, 'install-1', 'tok-b', T0 + 500_000);
  assert.equal(push.list(st)[0].firstAt, Math.floor(T0 / 1000));
});

test('a missing id or token registers nothing', () => {
  const st = push.emptyState();
  assert.equal(push.register(st, '', 'tok', T0).changed, false);
  assert.equal(push.register(st, 'install-1', '', T0).changed, false);
  assert.equal(push.count(st), 0);
});

test('a dead token can be dropped, and dropping an absent one is harmless', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  assert.equal(push.drop(st, 'install-1'), true);
  assert.equal(push.count(st), 0);
  assert.equal(push.drop(st, 'install-1'), false);
});

// A transient failure must NOT discard the token: doing so would unregister a phone
// that was merely off the network, with no way back except reinstalling.
test('failures are counted, not punished', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteFailure(st, 'install-1');
  push.noteFailure(st, 'install-1');
  assert.equal(push.count(st), 1, 'still registered');
  assert.equal(push.list(st)[0].failures, 2);
  push.noteSuccess(st, 'install-1', T0 + 1000);
  assert.equal(push.list(st)[0].failures, 0, 'cleared once something got through');
});

// Regression. The first version recorded only failures, so a push that WORKED left no
// trace beyond a 200 in the request log — unacceptable for a feature whose entire
// point is arriving while nobody is watching it arrive.
test('a delivery that got through is recorded, not just cleared', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteSuccess(st, 'install-1', T0 + 5000);
  const d = push.list(st)[0];
  assert.equal(d.pushes, 1);
  assert.equal(d.lastPushAt, Math.floor((T0 + 5000) / 1000));
  assert.equal(push.totals(st).pushed, 1);
});

test('deliveries accumulate across devices', () => {
  const st = push.emptyState();
  push.register(st, 'a', 'tok-a', T0);
  push.register(st, 'b', 'tok-b', T0);
  push.noteSuccess(st, 'a', T0 + 1000);
  push.noteSuccess(st, 'b', T0 + 2000);
  push.noteSuccess(st, 'a', T0 + 3000);
  assert.equal(push.totals(st).pushed, 3);
  assert.equal(push.totals(st).lastPushAt, Math.floor((T0 + 3000) / 1000));
});

// The tally is the record of what the host has managed to deliver, so pruning a dead
// handset must not rewrite that history.
test('the running total survives a device being dropped', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteSuccess(st, 'install-1', T0 + 1000);
  push.drop(st, 'install-1');
  assert.equal(push.totals(st).pushed, 1);
});

test('a fresh store reports nothing delivered rather than undefined', () => {
  const t = push.totals(push.emptyState());
  assert.equal(t.pushed, 0);
  assert.equal(t.lastPushAt, 0);
});

test('recording against an unknown installation does not create one', () => {
  const st = push.emptyState();
  push.noteFailure(st, 'ghost');
  push.noteSuccess(st, 'ghost');
  assert.equal(push.count(st), 0);
});

test('the store is bounded, and evicts the least recently seen', () => {
  const st = push.emptyState();
  for (let i = 0; i < push.MAX_TOKENS + 5; i++) {
    push.register(st, `install-${i}`, `tok-${i}`, T0 + i * 1000);
  }
  assert.equal(push.count(st), push.MAX_TOKENS);
  const ids = push.list(st).map((d) => d.installId);
  assert.ok(!ids.includes('install-0'), 'oldest evicted');
  assert.ok(ids.includes(`install-${push.MAX_TOKENS + 4}`), 'newest kept');
});

test('listing never has to be sorted by the caller', () => {
  const st = push.emptyState();
  push.register(st, 'older', 'tok-a', T0);
  push.register(st, 'newer', 'tok-b', T0 + 60_000);
  assert.deepEqual(push.list(st).map((d) => d.installId), ['newer', 'older']);
});

// The tally the phone's wake-up cadence is decided from. It answers a question the
// phone cannot answer alone — "was a push sent that never arrived?" — so a wrong
// answer here costs either missed alerts or a hundred and twenty needless wake-ups
// a day.

test('the per-install tally counts only that install', () => {
  const st = push.emptyState();
  push.register(st, 'phone', 'tok-a', T0);
  push.register(st, 'tablet', 'tok-b', T0);
  push.noteSuccess(st, 'phone', T0 + 1000);
  push.noteSuccess(st, 'phone', T0 + 2000);
  assert.equal(push.sentTo(st, 'phone'), 2);
  assert.equal(push.sentTo(st, 'tablet'), 0);
});

test('an unknown install reports zero rather than throwing', () => {
  // A phone whose token was pruned still calls /v1/watch. Zero is the honest
  // answer and the safe one: it can never look like a deficit.
  const st = push.emptyState();
  assert.equal(push.sentTo(st, 'never-registered'), 0);
  assert.equal(push.sentTo(push.emptyState(), 'x'), 0);
  assert.equal(push.sentTo(null, 'x'), 0);
});

test('a failed send does not count as sent', () => {
  // Otherwise every outage would look like a dropped push and tighten the alarm.
  const st = push.emptyState();
  push.register(st, 'phone', 'tok-a', T0);
  push.noteFailure(st, 'phone');
  assert.equal(push.sentTo(st, 'phone'), 0);
});
