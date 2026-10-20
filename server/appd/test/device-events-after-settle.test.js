'use strict';
// A device's batch that arrives after its run has ended is refused, and a run that
// is ended twice is ended once.
//
// The events route looked the run up when the request's headers arrived and used
// that entry again after awaiting the body. If the run ended in between (the
// device's small done frame overtaking a big batch on another connection, or the
// run being lost), the batch was applied to a settled run: the answer and a second
// result landed after "the run ended without a result", after a Round had been
// filed and sealed. And when a done frame and a loss both settled one run, the
// second settle counted another finished run and its finish() freed the chat's
// slot while the NEXT run, started from the queue by the first settle, held it.
//
// The daemon's own events route (wrapped as a function), Run and settleRun, lifted
// into a sandbox.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const { lift, between } = require('./appd-lift');

const DEV = 'aaaaaaaa-0000-4000-8000-000000000001';
const WORK = 'bbbbbbbb-0000-4000-8000-000000000002';

const ROUTE = between(
  "if ((m = dsub.match(/^\\/work\\/([0-9a-f-]{36})\\/events$/)) && req.method === 'POST') {",
  '\n      }\n');

/** The events route over one remote run, with the request body held until released. */
function eventsRoute() {
  const remoteRuns = new Map();
  const applied = [];
  const settled = [];
  const replies = [];
  let release;
  const bodyArrives = new Promise((r) => { release = r; });
  const run_ = { chatId: 'c1', cancelled: false };
  const entry = { run_, deviceId: DEV, chatId: 'c1', lastHeard: 0, startedAt: 0 };
  remoteRuns.set(WORK, entry);
  const { fns } = lift([], {
    remoteRuns, crypto,
    log: () => {},
    deviceState: { devices: {} },
    devicesLib: { noteSeen: () => {} },
    readJsonBody: () => bodyArrives,
    loadMeta: () => ({ id: 'c1' }),
    handleClaudeEvent: (meta, r, ev) => { applied.push(ev); },
    settleRun: (r, how) => { settled.push(how); },
    sendErr: (res, code, error) => { replies.push({ code, error }); },
    sendJson: (res, code, body) => { replies.push({ code, body }); },
  }, {
    extra: `async function route(req, res, dsub, devId) {\n  let m;\n  ${ROUTE}\n  return 'not this route';\n}`,
    exports: ['route'],
  });
  return { fns, remoteRuns, applied, settled, replies, release };
}

const req = { method: 'POST' };
const path = `/work/${WORK}/events`;
const ANSWER = JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'All 12 tests pass.' }] } });
const RESULT = JSON.stringify({ type: 'result', is_error: false, num_turns: 1 });

test('a batch whose run ended while its body was arriving is refused, not applied', async () => {
  const r = eventsRoute();
  const pending = r.fns.route(req, {}, path, DEV);      // headers in; the body is slow
  // Meanwhile the run ends: the done frame landed first (or the run was lost).
  r.remoteRuns.delete(WORK);
  r.release({ lines: [ANSWER, RESULT] });
  await pending;
  assert.deepEqual(r.applied, [], 'applied to a run that had already been settled');
  assert.deepEqual(r.settled, []);
  assert.equal(r.replies.length, 1);
  assert.equal(r.replies[0].code, 404, 'a 404 is what a runner treats as final');
});

test('a batch for a run still going is applied as before', async () => {
  const r = eventsRoute();
  const pending = r.fns.route(req, {}, path, DEV);
  r.release({ lines: [ANSWER] });
  await pending;
  assert.equal(r.applied.length, 1);
  assert.equal(r.replies[0].code, 200);
});

/** settleRun over one local chat whose queue holds a message, with Run as the daemon has it. */
function settling() {
  const activeRuns = new Map();
  const meta = { id: 'c1', finishedRuns: 0, pending: [{ text: 'and the disk?', ts: 1 }] };
  const records = [];
  const started = [];
  const { fns } = lift(['Run', 'settleRun'], {
    activeRuns,
    log: () => {},
    noteRunStall: () => false,
    appendMsg: (id, rec) => { records.push(rec); },
    updateMeta: (id, fn) => { fn(meta); return meta; },
    loadMeta: () => JSON.parse(JSON.stringify(meta)),
    saveMeta: (m) => { meta.pending = m.pending; },
    takePending: (m) => { const t = (m.pending || []).map((p) => p.text).join('\n\n'); m.pending = []; return t || null; },
    drainPending: (m) => { const had = m.pending || []; m.pending = []; return had; },
    startQueuedRun: (m, text) => {
      const next = new fns.Run(m.id);
      activeRuns.set(m.id, next);
      started.push(next);
      return { run: next };
    },
  });
  return { fns, activeRuns, meta, records, started };
}

test('a run settled twice counts one ending and keeps the next run in its slot', () => {
  const s = settling();
  const first = new s.fns.Run('c1');
  s.activeRuns.set('c1', first);
  s.fns.settleRun(first, { failureText: 'LAPTOP: no word for 5 minutes' });
  assert.equal(s.started.length, 1, 'the first settle starts the queued message');
  const next = s.started[0];
  assert.equal(s.activeRuns.get('c1'), next);

  // The done frame that was already in flight settles the same run again.
  s.fns.settleRun(first, { failureText: null });
  assert.equal(s.activeRuns.get('c1'), next, 'the next run lost its slot: running:false, and Stop answers 409');
  assert.equal(s.meta.finishedRuns, 1, 'one run, one ending');
  assert.equal(s.records.filter((r) => r.type === 'error').length, 1);
});

test('a stale run finishing never frees a newer run\'s slot', () => {
  const s = settling();
  const old = new s.fns.Run('c1');
  const newer = new s.fns.Run('c1');
  s.activeRuns.set('c1', newer);
  old.finish();
  assert.equal(s.activeRuns.get('c1'), newer);
  newer.finish();
  assert.equal(s.activeRuns.has('c1'), false);
});
