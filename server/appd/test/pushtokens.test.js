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

test('a dead-token drop cannot delete the replacement registered while sending', () => {
  // The real sequence: FCM answers UNREGISTERED for the token being retired at the
  // same moment the phone registers its new one. A drop keyed on the install alone
  // deleted the NEW token, and push stayed silent until the app was reopened.
  const st = push.emptyState();
  push.register(st, 'install-1', 'old-token', 1000, { model: 'SM-F946U' });
  push.register(st, 'install-1', 'new-token', 2000, { model: 'SM-F946U' });
  assert.equal(push.drop(st, 'install-1', 'old-token'), false, 'the stale verdict must not apply');
  assert.equal(push.list(st)[0].token, 'new-token');
  // The unguarded form still works, and so does a matching token.
  assert.equal(push.drop(st, 'install-1', 'new-token'), true);
  assert.deepStrictEqual(push.list(st), []);
});

// ------------------------------------------------------ the uninstall sweep
//
// A validate-only pass over every stored token. It COLLECTS and applies nothing:
// the sweep holds the network for one round trip per phone, and the register
// route writes the same file meanwhile, so the caller re-reads before acting.

/** A probe that answers from a table, and records what it was asked. */
function stubProbe(byToken) {
  const asked = [];
  const impl = async (token) => {
    asked.push(token);
    const r = byToken[token];
    if (r instanceof Error) throw r;
    return r ?? { ok: true, dead: false, status: 200, error: null };
  };
  impl.asked = asked;
  return impl;
}

const GONE = { ok: false, dead: true, status: 404, error: 'Requested entity was not found.' };

test('the sweep asks about every stored token', async () => {
  const st = push.emptyState();
  push.register(st, 'a', 'tok-a', T0);
  push.register(st, 'b', 'tok-b', T0);
  const probe = stubProbe({});
  const r = await push.reconcile(st, probe);
  assert.equal(r.checked, 2);
  assert.deepEqual(probe.asked.sort(), ['tok-a', 'tok-b']);
  assert.deepEqual(r.dead, []);
});

test('the sweep names the install AND the token it was about', async () => {
  // The token comes back so the caller's drop can be guarded on the install
  // still holding it — rotation is exactly when a dead verdict arrives.
  const st = push.emptyState();
  push.register(st, 'uninstalled', 'tok-dead', T0);
  const r = await push.reconcile(st, stubProbe({ 'tok-dead': GONE }));
  assert.equal(r.dead.length, 1);
  assert.equal(r.dead[0].installId, 'uninstalled');
  assert.equal(r.dead[0].token, 'tok-dead');
});

// The property the whole design rests on: a sweep that wrote as it went would
// erase a token registered during its own network round trips.
test('the sweep changes nothing itself', async () => {
  const st = push.emptyState();
  push.register(st, 'uninstalled', 'tok-dead', T0);
  await push.reconcile(st, stubProbe({ 'tok-dead': GONE }));
  assert.equal(push.count(st), 1, 'the caller applies outcomes against a fresh read');
});

// ⚠ The failure mode that would empty the registry in one pass. This runs
// unattended and drops rows; an outage read as "dead" would unregister every
// phone at once, with no way back but reinstalling the app on each.
test('an outage mid-sweep is counted, never treated as a dead token', async () => {
  const st = push.emptyState();
  push.register(st, 'a', 'tok-a', T0);
  push.register(st, 'b', 'tok-b', T0);
  const r = await push.reconcile(st, stubProbe({
    'tok-a': { ok: false, dead: false, status: 503, error: 'try again' },
    'tok-b': { ok: false, dead: false, status: 500, error: 'internal' },
  }));
  assert.deepEqual(r.dead, []);
  assert.equal(r.failed, 2);
});

test('a probe that throws is a broken sender, not a dead phone', async () => {
  const st = push.emptyState();
  push.register(st, 'a', 'tok-a', T0);
  const r = await push.reconcile(st, stubProbe({ 'tok-a': new Error('socket hang up') }));
  assert.deepEqual(r.dead, []);
  assert.equal(r.failed, 1, 'counted, so a run of these is visible without being acted on');
});

test('one dead phone in a healthy fleet is the only one reported', async () => {
  const st = push.emptyState();
  push.register(st, 'live', 'tok-live', T0);
  push.register(st, 'gone', 'tok-gone', T0);
  push.register(st, 'flaky', 'tok-flaky', T0);
  const r = await push.reconcile(st, stubProbe({
    'tok-gone': GONE,
    'tok-flaky': { ok: false, dead: false, status: 503, error: 'try again' },
  }));
  assert.deepEqual(r.dead.map((d) => d.installId), ['gone']);
  assert.equal(r.failed, 1);
  assert.equal(r.checked, 3);
});

test('an empty registry sweeps without asking FCM anything', async () => {
  const probe = stubProbe({});
  const r = await push.reconcile(push.emptyState(), probe);
  assert.equal(r.checked, 0);
  assert.equal(probe.asked.length, 0, 'no round trip, no token, no cost');
});

// ------------------------------------------------ the counter and its epoch
//
// WHAT WENT WRONG. The phone compares the host's tally for this install against
// what it actually received, and on 2026-09-15 it read "1274 of 916 pushes
// arrived — nothing dropped": more received than ever sent. Both numbers were
// honest and neither was comparable, because they count from different
// starting points. The host's per-install tally lived on the TOKEN row, and
// `register` rebuilt that row from scratch whenever Firebase reissued the token
// — so the host silently restarted at 0 on 2026-08-15 while the phone's own
// count kept climbing from July. The live file said it plainly: 917 for the one
// install, 1321 delivered host-wide.
//
// Two properties fix it, and they are separate. The count must not restart when
// the phone is still the same install (below), and when it DOES have to restart
// — a dropped registration, a store recreated from nothing — the phone has to be
// told, which is what the epoch is for. A number that quietly rebases is worse
// than no number: it disables the deficit check permanently, and the check is
// the only thing that tells a quiet night from a broken delivery path.

test('a rotated token does not restart the delivery count', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteSuccess(st, 'install-1', T0 + 1000);
  push.noteSuccess(st, 'install-1', T0 + 2000);
  push.register(st, 'install-1', 'tok-b', T0 + 3000);
  assert.equal(push.sentTo(st, 'install-1'), 2,
    'the phone is the same install; its own tally did not restart, so this must not either');
  assert.equal(push.list(st)[0].lastPushAt, Math.floor((T0 + 2000) / 1000),
    'and the last delivery is still when it happened');
});

test('the count never goes backwards within an epoch', () => {
  // Every write path, in the order a real phone hits them: register, deliver,
  // re-register unchanged, rotate, deliver again.
  const st = push.emptyState();
  let high = 0;
  const seen = () => {
    const n = push.sentTo(st, 'install-1');
    assert.ok(n >= high, `count fell from ${high} to ${n}`);
    high = n;
  };
  push.register(st, 'install-1', 'tok-a', T0); seen();
  push.noteSuccess(st, 'install-1', T0 + 1000); seen();
  push.register(st, 'install-1', 'tok-a', T0 + 2000); seen();
  push.register(st, 'install-1', 'tok-b', T0 + 3000); seen();
  push.noteSuccess(st, 'install-1', T0 + 4000); seen();
  assert.equal(high, 2);
});

test('an install gets an epoch when its counter is created', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  const e = push.epochOf(st, 'install-1');
  assert.equal(typeof e, 'string');
  assert.ok(e.length >= 8, 'long enough that two stores cannot collide');
  assert.equal(push.list(st)[0].pushEpoch, e, 'and the panel sees the same one');
});

test('the epoch survives a restart, because it is in the file the daemon reloads', () => {
  // A restart is exactly this: the same JSON, read back by a new process.
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteSuccess(st, 'install-1', T0 + 1000);
  const before = push.epochOf(st, 'install-1');
  const reloaded = JSON.parse(JSON.stringify(st));
  assert.equal(push.epochOf(reloaded, 'install-1'), before);
  assert.equal(push.sentTo(reloaded, 'install-1'), 1);
  // And the app re-registering on start must not disturb either.
  push.register(reloaded, 'install-1', 'tok-a', T0 + 5000);
  assert.equal(push.epochOf(reloaded, 'install-1'), before);
  assert.equal(push.sentTo(reloaded, 'install-1'), 1);
});

test('a rotation keeps the epoch: the counter did not restart, so nothing rebaselines', () => {
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  const before = push.epochOf(st, 'install-1');
  push.register(st, 'install-1', 'tok-b', T0 + 1000);
  assert.equal(push.epochOf(st, 'install-1'), before);
});

test('a store recreated from nothing mints a new epoch, never the old one', () => {
  // The unreadable-file case. push.json is read on every touch and any failure
  // — missing, truncated, EACCES — yields an empty state, which the next write
  // then makes permanent. Restarting the count at 0 under the SAME epoch would
  // leave the phone comparing its 1274 against a host that had forgotten
  // everything, and reading the deficit as "nothing dropped" forever.
  const first = push.emptyState();
  push.register(first, 'install-1', 'tok-a', T0);
  push.noteSuccess(first, 'install-1', T0 + 1000);

  const recreated = push.emptyState();
  push.register(recreated, 'install-1', 'tok-a', T0 + 2000);
  assert.equal(push.sentTo(recreated, 'install-1'), 0, 'the count genuinely did restart');
  assert.notEqual(push.epochOf(recreated, 'install-1'), push.epochOf(first, 'install-1'),
    'so the phone must be told to rebaseline');
});

test('a dropped install that comes back is a new epoch', () => {
  // A dead-token retirement really does forget the tally, so the phone must not
  // go on comparing against the count that replaces it.
  const st = push.emptyState();
  push.register(st, 'install-1', 'tok-a', T0);
  push.noteSuccess(st, 'install-1', T0 + 1000);
  const before = push.epochOf(st, 'install-1');
  push.drop(st, 'install-1');
  push.register(st, 'install-1', 'tok-b', T0 + 2000);
  assert.equal(push.sentTo(st, 'install-1'), 0);
  assert.notEqual(push.epochOf(st, 'install-1'), before);
});

test('two installs never share an epoch', () => {
  const st = push.emptyState();
  push.register(st, 'phone', 'tok-a', T0);
  push.register(st, 'tablet', 'tok-b', T0);
  assert.notEqual(push.epochOf(st, 'phone'), push.epochOf(st, 'tablet'));
});

test('an epoch is asked for safely about an install that is not there', () => {
  assert.equal(push.epochOf(push.emptyState(), 'ghost'), null);
  assert.equal(push.epochOf(null, 'ghost'), null);
});

test('a row written before epochs gets one without losing its count', () => {
  // The live file on the day this was written: a real tally, no epoch. Minting
  // one keeps the number — it is not wrong, it is merely unvouched-for — and
  // tells the phone to rebaseline against it, which is the honest answer to a
  // count whose starting point the host can no longer account for.
  const st = { tokens: { legacy: { token: 'tok-a', firstAt: T0, seenAt: T0, model: 'SM-F966U',
    failures: 0, pushes: 917, lastPushAt: T0 } }, pushed: 1321 };
  const r = push.register(st, 'legacy', 'tok-a', T0 + 1000);
  assert.equal(push.sentTo(st, 'legacy'), 917, 'the tally is kept');
  assert.equal(typeof push.epochOf(st, 'legacy'), 'string');
  assert.equal(r.changed, true, 'and the daemon is told to write the file, or it is minted forever');
});

test('a delivery to a row written before epochs mints one too', () => {
  // The app re-registers on start, but a push can land first on a host that has
  // been up for weeks. Either write path is enough.
  const st = { tokens: { legacy: { token: 'tok-a', firstAt: T0, seenAt: T0, failures: 0, pushes: 4 } } };
  push.noteSuccess(st, 'legacy', T0 + 1000);
  assert.equal(push.sentTo(st, 'legacy'), 5);
  assert.equal(typeof push.epochOf(st, 'legacy'), 'string');
});
