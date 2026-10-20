'use strict';
// A line a device re-sends after a lost response is applied once, and a line that
// merely looks like an earlier one is applied every time.
//
// The replay guard hashed each whole batch, and was wrong both ways. A runner that
// retries a batch has usually picked up more output meanwhile and sends it along,
// so the usual replay hashed differently and was applied twice: doubled text in
// the live answer, a second assistant record in the transcript. And two batches
// that genuinely matched, such as a slow local model's one-token deltas (" the",
// then " the" again a few seconds later), were dropped as replays, leaving words
// missing from the live answer and from a stopped run's saved partial.
//
// The daemon's own events route (wrapped as a function), Run and handleClaudeEvent,
// lifted into a sandbox.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const { lift, between } = require('./appd-lift');

const DEV = 'aaaaaaaa-0000-4000-8000-000000000001';
const WORK = 'bbbbbbbb-0000-4000-8000-000000000002';

const ROUTE = between(
  "if ((m = dsub.match(/^\\/work\\/([0-9a-f-]{36})\\/events$/)) && req.method === 'POST') {",
  '\n      }\n');

/** One remote run, and a way to post batches to its events route. */
function device() {
  const remoteRuns = new Map();
  const records = [];
  const replies = [];
  const { fns } = lift(['Run', 'handleClaudeEvent', 'toolInputDigest', 'isSessionId'], {
    remoteRuns, crypto,
    log: () => {},
    deviceState: { devices: {} },
    devicesLib: { noteSeen: () => {} },
    readJsonBody: async (req) => req.body,
    loadMeta: () => ({ id: 'c1' }),
    appendMsg: (id, rec) => { records.push(rec); },
    updateMeta: () => {},
    ranOnLabel: () => null,
    settleRun: () => {},
    sendErr: (res, code, error) => { replies.push({ code, error }); },
    sendJson: (res, code, body) => { replies.push({ code, body }); },
  }, {
    constants: ['SEEN_LINES_MAX', 'isLocalFamily'],
    extra: `async function route(req, res, dsub, devId) {\n  let m;\n  ${ROUTE}\n  return 'not this route';\n}`,
    exports: ['route'],
  });
  const run_ = new fns.Run('c1');
  remoteRuns.set(WORK, { run_, deviceId: DEV, chatId: 'c1', lastHeard: 0, startedAt: 0 });
  return {
    records,
    /** POSTs one batch; resolves with the route's reply. */
    post: async (lines) => {
      replies.length = 0;
      await fns.route({ method: 'POST', body: { lines } }, {}, `/work/${WORK}/events`, DEV);
      return replies[0];
    },
    /** The live answer as a watching app saw it, delta by delta. */
    live: () => run_.buffer.filter((i) => i.event === 'delta').map((i) => i.data.text).join(''),
    answers: () => records.filter((r) => r.type === 'assistant').map((r) => r.text),
  };
}

// Lines as Claude Code writes them: each with its own uuid.
const delta = (text) => JSON.stringify({
  type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
  uuid: crypto.randomUUID(),
});
const said = (text) => JSON.stringify({
  type: 'assistant', message: { content: [{ type: 'text', text }] }, uuid: crypto.randomUUID(),
});
// A local-model shim line with no uuid at all.
const bareDelta = (text) => JSON.stringify({
  type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
});

test('a replay that picked up new lines applies the old ones once and the new ones once', async () => {
  const d = device();
  const lost = [delta('Hello, '), delta('the build '), delta('is green.'), said('Hello, the build is green.')];
  await d.post(lost);                                     // applied, but the response never got back
  const replay = [...lost, delta('All 12 '), delta('tests pass.')];
  const r = await d.post(replay);                         // the runner's retry, with what arrived meanwhile
  assert.equal(r.code, 200);
  assert.deepEqual(d.answers(), ['Hello, the build is green.'], 'the assistant record was written twice');
  assert.equal(d.live(), 'Hello, the build is green.All 12 tests pass.');
});

test('a byte-identical replay is recognised as one, and applied once', async () => {
  const d = device();
  const batch = [said('I checked. Nothing burning.')];
  const first = await d.post(batch);
  assert.notEqual(first.body.duplicate, true);
  const again = await d.post(batch);
  assert.equal(again.body.duplicate, true, 'a replay is answered as one, so the runner stops retrying');
  assert.deepEqual(d.answers(), ['I checked. Nothing burning.']);
});

test('a batch split in chunks and replayed after a later chunk failed is applied once', async () => {
  const d = device();
  const c1 = [delta('One, '), delta('two, ')];
  const c2 = [delta('three.'), said('One, two, three.')];
  await d.post(c1);
  await d.post(c2);                                       // applied; its response is lost
  await d.post(c1);                                       // the retry re-sends the whole batch
  await d.post([...c2, delta(' Four.')]);
  assert.deepEqual(d.answers(), ['One, two, three.']);
  assert.equal(d.live(), 'One, two, three. Four.');
});

test('identical one-token batches from a local model are all applied', async () => {
  const d = device();
  for (const t of [' the', ' disk', ' is', ' fine;', ' the', ' pool', ' is', ' fine.']) {
    const r = await d.post([bareDelta(t)]);
    assert.notEqual(r.body.duplicate, true, `"${t}" was dropped as a replay`);
  }
  assert.equal(d.live(), ' the disk is fine; the pool is fine.');
});

