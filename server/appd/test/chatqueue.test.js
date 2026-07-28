'use strict';
// A headless run cannot be fed mid-flight, so "send while busy" means queue and
// deliver on completion — the same thing the interactive composer does.

const { test } = require('node:test');
const assert = require('node:assert');
const { pushPending, takePending, clearPending, queuedEvents, MAX_PENDING } = require('../lib/chatqueue');

test('messages queue in order and report their position', () => {
  const meta = {};
  assert.deepStrictEqual(pushPending(meta, 'first', 100), { ok: true, position: 1 });
  assert.deepStrictEqual(pushPending(meta, 'second', 101), { ok: true, position: 2 });
  assert.deepStrictEqual(meta.pending.map((p) => p.text), ['first', 'second']);
  assert.strictEqual(meta.updatedAt, 101);
});

test('the queue drains as ONE prompt in send order', () => {
  // One run for the batch, not one run per message: the same as the composer.
  const meta = {};
  pushPending(meta, 'do this', 1);
  pushPending(meta, 'then this', 2);
  assert.strictEqual(takePending(meta), 'do this\n\nthen this');
  assert.deepStrictEqual(meta.pending, [], 'and the queue is emptied by taking it');
});

test('taking an empty queue yields nothing to run', () => {
  assert.strictEqual(takePending({}), null);
  assert.strictEqual(takePending({ pending: [] }), null);
});

test('blank and oversized messages are refused', () => {
  const meta = {};
  assert.strictEqual(pushPending(meta, '   ', 1).code, 400);
  assert.strictEqual(pushPending(meta, null, 1).code, 400);
  assert.strictEqual(pushPending(meta, 'x'.repeat(100_001), 1).code, 400);
  assert.deepStrictEqual(meta.pending, undefined);
});

test('the queue has a ceiling', () => {
  const meta = {};
  for (let i = 0; i < MAX_PENDING; i++) assert.ok(pushPending(meta, `m${i}`, i).ok);
  const over = pushPending(meta, 'one too many', 99);
  assert.strictEqual(over.ok, false);
  assert.strictEqual(over.code, 429);
  assert.strictEqual(meta.pending.length, MAX_PENDING);
});

test('cancelling drops the queue and reports how much it dropped', () => {
  // Otherwise pressing stop would immediately start the next queued message,
  // making the stop button launch the thing it was pressed to end.
  const meta = {};
  pushPending(meta, 'a', 1);
  pushPending(meta, 'b', 2);
  assert.strictEqual(clearPending(meta), 2);
  assert.deepStrictEqual(meta.pending, []);
  assert.strictEqual(clearPending(meta), 0);
});

test('queued messages render as pending user events after the real ones', () => {
  const meta = {};
  pushPending(meta, 'waiting here', 500);
  const evs = queuedEvents(meta, 7);
  assert.strictEqual(evs.length, 1);
  assert.strictEqual(evs[0].kind, 'user');
  assert.strictEqual(evs[0].queued, true);
  assert.strictEqual(evs[0].text, 'waiting here');
  assert.strictEqual(evs[0].seq, 8, 'sequenced after the transcript events');
  assert.strictEqual(evs[0].ts, 500);
});

test('a chat with nothing queued adds no events', () => {
  assert.deepStrictEqual(queuedEvents({}, 3), []);
});

test('text is trimmed but not otherwise altered', () => {
  const meta = {};
  pushPending(meta, '  spaced out  ', 1);
  assert.strictEqual(meta.pending[0].text, 'spaced out');
});
