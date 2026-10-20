'use strict';
// A message queued behind a chat run that died on a usage limit waits for the
// re-run after the reset, even when huginn-appd restarts during the wait.
//
// settleRun leaves that queue on disk on purpose, to drain when the re-run
// finishes (#39). deliverOrphanedQueues runs at every start and drained it
// straight into the window the stall had just proved empty: the queued message
// died on the same limit, noteRunStall kept the first stall as it must, and
// nothing put the message back. It was accepted with a 202 and never answered.
//
// The daemon's own deliverOrphanedQueues, lifted into a sandbox over an
// in-memory chat store and the real queue helpers.

const { test } = require('node:test');
const assert = require('node:assert');
const chatqueue = require('../lib/chatqueue');
const { lift } = require('./appd-lift');

const T0 = 1_760_000_000;
const STALL = { at: T0 * 1000, window: 'five_hour', resetsAt: (T0 + 9000) * 1000, userText: 'A: summarise the logs' };

/** Startup's queue drain over these chats; returns what it started and the store. */
function restartWith(chats) {
  const store = new Map(chats.map((m) => [m.id, JSON.parse(JSON.stringify(m))]));
  const started = [];
  const { fns } = lift(['deliverOrphanedQueues', 'waitingForReset'], {
    log: () => {},
    activeRuns: new Map(),
    // As listChats returns them: the meta, with `pending` as a count.
    listChats: () => [...store.values()].map((m) => ({ ...m, pending: (m.pending || []).length })),
    loadMeta: (id) => JSON.parse(JSON.stringify(store.get(id))),
    saveMeta: (m) => { store.set(m.id, JSON.parse(JSON.stringify(m))); },
    takePending: chatqueue.takePending,
    startQueuedRun: (m, text) => { started.push({ id: m.id, text }); return { run: {} }; },
  });
  fns.deliverOrphanedQueues();
  return { started, store };
}

const queued = [{ text: 'B: and the disk usage too', ts: T0 + 5 }];

test('a restart leaves a stalled chat\'s queue for the re-run after the reset', () => {
  const { started, store } = restartWith([{ id: 'c1', stall: { ...STALL }, pending: queued }]);
  assert.deepEqual(started, [], 'nothing is spawned into the exhausted window');
  assert.equal(store.get('c1').pending.length, 1, 'the queued message is still on disk');
  assert.equal(store.get('c1').pending[0].text, 'B: and the disk usage too');
});

test('a queue with no stall behind it is still delivered at start', () => {
  const { started, store } = restartWith([{ id: 'c2', pending: queued }]);
  assert.deepEqual(started.map((s) => s.id), ['c2']);
  assert.equal(started[0].text, 'B: and the disk usage too');
  assert.equal(store.get('c2').pending.length, 0);
});

test('a stall already re-run, or given up on, no longer holds the queue', () => {
  const { started } = restartWith([
    { id: 'c3', stall: { ...STALL, resumedAt: (T0 + 9010) * 1000 }, pending: queued },
    { id: 'c4', stall: { ...STALL, gaveUpAt: (T0 + 9010) * 1000 }, pending: queued },
  ]);
  assert.deepEqual(started.map((s) => s.id).sort(), ['c3', 'c4']);
});
