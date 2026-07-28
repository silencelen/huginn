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
  push.noteSuccess(st, 'install-1');
  assert.equal(push.list(st)[0].failures, 0, 'cleared once something got through');
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
