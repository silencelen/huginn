'use strict';
// The reader and the bulk records Claude Code 2.1.300 writes between turns.
//
// `api-request` records carry the whole API request a turn sent, 100 KB to over
// a megabyte each, beside `api-request-blob` and `api-request-shape`; in real
// 2.1.300 transcripts they are most of the file. The reader has no case for any
// of them, yet it decoded and JSON.parsed every one, on every window size a cold
// open tried and again for the page it built. These tests hold it to the cheap
// path: such a record is recognised by its first key and skipped unread, unless
// it is the last word on one of the three fields every record sets — and each
// byte and each record of a window is read once however far the window grows.
//
// The record shapes keep Claude Code's KEY ORDER, which is what the skip reads:
// `type` first on the bulk records, `type` after `parentUuid`/`isSidechain` on
// a conversation's own records. Entirely synthetic content.

const { test, afterEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { readTranscript, lastWorkAt } = require('../lib/transcript');

const T0 = Date.parse('2026-10-18T09:00:00.000Z');
let tick = 0;
const at = () => new Date(T0 + (tick++) * 1000).toISOString();

const user = (text) => ({
  parentUuid: null, isSidechain: false, type: 'user', message: { role: 'user', content: text },
  uuid: `u${tick}`, timestamp: at(), cwd: '/work/app', sessionId: 's', version: '2.1.300', gitBranch: 'main',
});
const reply = (text) => ({
  parentUuid: null, isSidechain: false,
  message: { model: 'claude-opus-5-5', role: 'assistant', content: [{ type: 'text', text }] },
  type: 'assistant', uuid: `a${tick}`, timestamp: at(), cwd: '/work/app', sessionId: 's', version: '2.1.300', gitBranch: 'main',
});
// The request's own body names `cwd`, `gitBranch` and `timestamp` too, nested:
// only the TOP-LEVEL ones are the record's.
const apiRequest = (kb, extra = {}) => ({
  type: 'api-request', sessionId: 's', id: `r${tick}`, timestamp: at(), version: '2.1.300', querySource: 'repl',
  shapeHash: 'h0', params: {
    system: [{ type: 'text', text: 'x'.repeat(kb * 1024) }],
    tools: [{ name: 'Bash', input_schema: { properties: { cwd: { type: 'string' }, gitBranch: { type: 'string' }, timestamp: { type: 'string' } } } }],
  },
  keep: 1, tail: 2, cacheControl: {}, requestDigest: 'd0', ...extra,
});
const blob = () => ({ type: 'api-request-blob', sessionId: 's', hash: `b${tick}`, message: { role: 'user', content: 'y'.repeat(2048) } });

/** `turns` exchanges, each with a request record of `kb` KB between question and answer. */
function conversation(turns, kb) {
  const out = [];
  for (let i = 0; i < turns; i++) out.push(user(`question ${i}`), apiRequest(kb), blob(), reply(`answer ${i}`));
  return out;
}

function writeFixture(records) {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tr-api-')), 's.jsonl');
  fs.writeFileSync(p, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
  return p;
}

// Every JSON.parse and every fs.readSync the reader makes, while a test watches.
const realParse = JSON.parse;
const realReadSync = fs.readSync;
let parses = [];
let reads = [];
function watch() {
  parses = [];
  reads = [];
  JSON.parse = function (text, reviver) { parses.push(String(text)); return realParse.call(JSON, text, reviver); };
  fs.readSync = function (fd, buf, off, len, pos) {
    const n = realReadSync.call(fs, fd, buf, off, len, pos);
    reads.push({ pos, n });
    return n;
  };
}
afterEach(() => { JSON.parse = realParse; fs.readSync = realReadSync; });

const isRequest = (s) => s.startsWith('{"type":"api-request');

test('a cold open parses none of the api-request records in its window', () => {
  // ~70 KB a turn: twelve events need a window well past the first 256 KB.
  const p = writeFixture(conversation(30, 64));
  watch();
  const r = readTranscript(p, { limit: 400 });
  JSON.parse = realParse;
  assert.equal(parses.filter(isRequest).length, 0, 'an api-request record was parsed');
  // ...and the page is the conversation, nothing of the requests in it.
  assert.ok(r.events.length >= 12, `only ${r.events.length} events`);
  assert.deepStrictEqual([...new Set(r.events.map((e) => e.kind))].sort(), ['assistant', 'user']);
  assert.equal(r.events.at(-1).text, 'answer 29');
  assert.equal(r.cwd, '/work/app');
  assert.equal(r.gitBranch, 'main');
});

test('a history page and a resumed poll parse none of them either', () => {
  const p = writeFixture(conversation(40, 32));
  const text = fs.readFileSync(p, 'utf8');   // ASCII: a character is a byte
  const startOf = (q) => text.indexOf(`{"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":"${q}"}`);
  watch();
  // A page that ends where `question 30` begins, i.e. on an answer.
  const page = readTranscript(p, { limit: 20, until: startOf('question 30') });
  assert.equal(page.events.length, 20);
  assert.equal(page.events.at(-1).text, 'answer 29');
  // A resumed read that holds a typed message and no dequeue looks ABOVE its
  // start for a drain (drainAbove), through 256 KB of the same records.
  const resumed = readTranscript(p, { offset: startOf('question 39'), limit: 400 });
  JSON.parse = realParse;
  assert.deepStrictEqual(resumed.events.map((e) => e.text), ['question 39', 'answer 39']);
  assert.equal(parses.filter(isRequest).length, 0, 'an api-request record was parsed');
});

test('a trailing api-request still has the last word on the window\'s last activity', () => {
  // The record is written as the request goes out, so at the live end of a
  // turn it is the newest thing in the file — and the page always reported its
  // timestamp. Skipping it unread must not change what the page says.
  const records = [...conversation(6, 16), user('one more'), apiRequest(16)];
  const p = writeFixture(records);
  watch();
  const r = readTranscript(p, { limit: 400 });
  JSON.parse = realParse;
  assert.equal(r.lastActivityTs, Math.floor(Date.parse(records.at(-1).timestamp) / 1000));
  // Its nested `cwd` is the REQUEST's, not the record's: the page keeps the
  // conversation's.
  assert.equal(r.cwd, '/work/app');
  assert.equal(r.gitBranch, 'main');
  // Read exactly once, and only that one: the earlier requests are all behind
  // a record that set every field after them.
  assert.equal(parses.filter(isRequest).length, 1);
});

test('a trailing request record that names its own cwd and branch is still heard', () => {
  // Not a shape Claude Code writes today; the reader reported top-level fields
  // from ANY record before, so a skipped record must keep doing so.
  const p = writeFixture([...conversation(4, 8), apiRequest(8, { cwd: '/work/other', gitBranch: 'feature' })]);
  const r = readTranscript(p, { limit: 400 });
  assert.equal(r.cwd, '/work/other');
  assert.equal(r.gitBranch, 'feature');
});

test('no byte and no record of a growing window is read twice', () => {
  // A history page wants `limit` events and doubles its window until it has
  // them: 256 KB, 512 KB, 1 MB, 2 MB here. Every size used to re-read and
  // re-parse the whole window from scratch, and the page itself was then built
  // from a third read and parse of the winning size.
  const p = writeFixture(conversation(120, 12));
  const tail = readTranscript(p, { limit: 12 });
  watch();
  const page = readTranscript(p, { limit: 200, until: tail.windowStart });
  JSON.parse = realParse;
  fs.readSync = realReadSync;
  assert.equal(page.events.length, 200);
  // The one-byte boundary probe aside, the reads tile the window without overlap.
  const spans = reads.filter((x) => x.n > 1).map((x) => [x.pos, x.pos + x.n]).sort((a, b) => a[0] - b[0]);
  assert.ok(spans.length > 1, 'the window never grew: the fixture is too small to test this');
  for (let i = 1; i < spans.length; i++) {
    assert.ok(spans[i][0] >= spans[i - 1][1], `bytes ${spans[i][0]}..${spans[i - 1][1]} were read twice`);
  }
  const seen = new Map();
  for (const s of parses) seen.set(s, (seen.get(s) || 0) + 1);
  const twice = [...seen.values()].filter((c) => c > 1).length;
  assert.equal(twice, 0, `${twice} records were parsed more than once`);
});

test('lastWorkAt passes over request records at the end of the file unread', () => {
  const records = [...conversation(5, 16), apiRequest(16), blob(), apiRequest(16)];
  const p = writeFixture(records);
  watch();
  const at = lastWorkAt(p);
  JSON.parse = realParse;
  // The newest record a PERSON did something in is the last answer.
  assert.equal(at, Math.floor(Date.parse(records.at(-4).timestamp) / 1000));
  assert.equal(parses.filter(isRequest).length, 0, 'an api-request record was parsed');
});
