'use strict';
const test = require('node:test');
const assert = require('node:assert');
const {
  emptyState, noteSeen, appOnline, listClients, pruneClients,
  FRESH_STREAM_MS, FRESH_BEAT_MS,
} = require('../lib/clients');

const T0 = 1_800_000_000_000;

test('a client that has never checked in is not online', () => {
  assert.equal(appOnline(emptyState(), T0), false);
});

test('a recent check-in counts as online', () => {
  const st = noteSeen(emptyState(), 'phone-1', { kind: 'stream' }, T0);
  assert.equal(appOnline(st, T0 + 1000), true);
});

test('a stream gone quiet for minutes is treated as gone', () => {
  const st = noteSeen(emptyState(), 'phone-1', { kind: 'stream' }, T0);
  assert.equal(appOnline(st, T0 + FRESH_STREAM_MS + 1), false);
});

// The reason freshness is per-kind. A single short window would mark a perfectly
// healthy phone absent for most of every ten-minute cycle, and Telegram would then
// duplicate nearly every alert — which is how a reader learns to ignore both.
test('a ten-minute background check is not mistaken for an absent phone', () => {
  const st = noteSeen(emptyState(), 'phone-1', { kind: 'heartbeat' }, T0);
  assert.equal(appOnline(st, T0 + 10 * 60_000), true, 'one cadence later: still here');
  assert.equal(appOnline(st, T0 + 20 * 60_000), true, 'one missed beat is forgiven');
  assert.equal(appOnline(st, T0 + FRESH_BEAT_MS + 1), false, 'two missed: hand over');
});

test('the same silence means different things for a stream and a beat', () => {
  const gap = 10 * 60_000;
  const stream = noteSeen(emptyState(), 'a', { kind: 'stream' }, T0);
  const beat = noteSeen(emptyState(), 'b', { kind: 'heartbeat' }, T0);
  assert.equal(appOnline(stream, T0 + gap), false);
  assert.equal(appOnline(beat, T0 + gap), true);
});

test('a client that never said which mechanism gets the forgiving window', () => {
  const st = noteSeen(emptyState(), 'phone-1', {}, T0);
  assert.equal(appOnline(st, T0 + 10 * 60_000), true);
});

// The whole point of the fallback: an app that cannot display a notification is
// not a delivery route, and counting it as one would hold Telegram back in favour
// of nothing at all.
test('a connected client with notifications denied is not a route', () => {
  const st = noteSeen(emptyState(), 'phone-1', { kind: 'stream', notify: false }, T0);
  assert.equal(appOnline(st, T0 + 1000), false);
});

test('one muted client does not hide another that can notify', () => {
  let st = noteSeen(emptyState(), 'muted', { notify: false }, T0);
  st = noteSeen(st, 'ok', { notify: true }, T0);
  assert.equal(appOnline(st, T0 + 1000), true);
});

// Regression: an omitted header must not silently promote a known-muted client.
test('a check-in that says nothing about notifications keeps the last answer', () => {
  let st = noteSeen(emptyState(), 'phone-1', { notify: false }, T0);
  st = noteSeen(st, 'phone-1', { kind: 'heartbeat' }, T0 + 1000);
  assert.equal(st.clients['phone-1'].notify, false);
  assert.equal(appOnline(st, T0 + 2000), false);
});

test('repeat check-ins accumulate rather than replacing the record', () => {
  let st = noteSeen(emptyState(), 'phone-1', { kind: 'stream' }, T0);
  st = noteSeen(st, 'phone-1', { kind: 'heartbeat' }, T0 + 60_000);
  const c = st.clients['phone-1'];
  assert.equal(c.count, 2);
  assert.equal(c.firstAt, T0, 'first sighting is kept');
  assert.equal(c.lastAt, T0 + 60_000);
  assert.equal(c.kind, 'heartbeat', 'kind reflects the latest check-in');
});

test('an empty id is ignored rather than recorded as a client', () => {
  const st = noteSeen(emptyState(), '', { kind: 'stream' }, T0);
  assert.deepEqual(st.clients, {});
});

test('listing reports age and freshness, newest first', () => {
  let st = noteSeen(emptyState(), 'old', { kind: 'stream' }, T0);
  st = noteSeen(st, 'new', { kind: 'stream' }, T0 + 10 * 60_000);
  const list = listClients(st, T0 + 10 * 60_000);
  assert.deepEqual(list.map((c) => c.id), ['new', 'old']);
  assert.equal(list[0].ageSeconds, 0);
  assert.equal(list[0].fresh, true);
  assert.equal(list[1].ageSeconds, 600);
  assert.equal(list[1].fresh, false);
});

test('a long-gone client is forgotten', () => {
  let st = noteSeen(emptyState(), 'retired', {}, T0);
  st = noteSeen(st, 'current', {}, T0 + 8 * 24 * 3600_000);
  pruneClients(st, T0 + 8 * 24 * 3600_000);
  assert.deepEqual(Object.keys(st.clients), ['current']);
});

test('overlong ids and user agents are truncated, not rejected', () => {
  const st = noteSeen(emptyState(), 'x'.repeat(200), { ua: 'y'.repeat(500) }, T0);
  const [c] = listClients(st, T0);
  assert.ok(c.ua.length <= 80);
});
