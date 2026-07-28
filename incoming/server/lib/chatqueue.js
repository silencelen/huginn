'use strict';
// The pending-message queue for a chat whose run is still in flight.
//
// A headless `claude -p` cannot take input mid-run — its stdin is closed at
// spawn — so "send while busy" has to mean "hold it and deliver when the run
// ends". That is also exactly what Claude Code's own composer does in a tmux
// session, so the app ends up behaving the same on both surfaces instead of
// dead-ending with an error on one of them.
//
// Pure functions over the chat's meta object, so the rules live in one place and
// under test; the route and the run-close handler just call these.

const MAX_PENDING = 20;
const MAX_TEXT = 100_000;

function list(meta) {
  return Array.isArray(meta.pending) ? meta.pending : [];
}

/** @returns {{ok: true, position: number} | {ok: false, code: number, error: string}} */
function pushPending(meta, text, now) {
  if (typeof text !== 'string' || !text.trim()) {
    return { ok: false, code: 400, error: 'text required' };
  }
  if (text.length > MAX_TEXT) return { ok: false, code: 400, error: 'text too long' };
  const pending = list(meta);
  if (pending.length >= MAX_PENDING) {
    return { ok: false, code: 429, error: `too many queued messages (${MAX_PENDING})` };
  }
  meta.pending = pending.concat([{ text: text.trim(), ts: now }]);
  meta.updatedAt = now;
  return { ok: true, position: meta.pending.length };
}

/**
 * Removes and returns everything deliverable as ONE prompt, in send order —
 * drained together, matching the interactive queue rather than firing a separate
 * run per message. Null when there is nothing to deliver.
 */
function takePending(meta) {
  const pending = list(meta);
  if (!pending.length) return null;
  meta.pending = [];
  return pending.map((p) => p.text).join('\n\n');
}

/** Cancel means stop: a cancelled run must not immediately respawn from the queue. */
function clearPending(meta) {
  const had = list(meta).length;
  meta.pending = [];
  return had;
}

/**
 * Pending messages as transcript events, so the conversation shows them where
 * they will land rather than swallowing them until delivery. They exist only
 * until they are delivered — at which point they appear in the real transcript
 * as ordinary user records and the queue is empty — so they cannot duplicate.
 */
function queuedEvents(meta, baseSeq = 0) {
  return list(meta).map((p, i) => ({
    seq: baseSeq + i + 1,
    kind: 'user',
    ts: p.ts ?? null,
    sidechain: false,
    text: p.text,
    queued: true,
  }));
}

module.exports = { pushPending, takePending, clearPending, queuedEvents, MAX_PENDING, MAX_TEXT };
