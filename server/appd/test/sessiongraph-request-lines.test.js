'use strict';
// The graph walk steps over request records without decoding them.
//
// Since mid-October Claude Code writes the whole API request into the
// transcript before every call — 40-150 KB a line, most of a long session's
// bytes. The graph does nothing with one but move its clock (firstTs/lastTs),
// so walkLines now skips such a line by its first bytes and reads its timestamp
// back only when that timestamp is the one the old walk would have left behind.
// Pinned here: the records the consumer sees and the clock it ends with are the
// old walk's, in every position a request line can sit; and the walk no longer
// parses the request bodies.
//
// Fixtures are SYNTHETIC transcripts written into a temp dir.

const { test, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const graph = require('../lib/sessiongraph');

// Every temp dir this file makes, removed when it is done: some of these
// fixtures are megabytes, and the suite runs on every deploy.
const scratchDirs = [];
const scratch = (dir) => { scratchDirs.push(dir); return dir; };
after(() => { for (const d of scratchDirs) fs.rmSync(d, { recursive: true, force: true }); });

const clock0 = 1_756_000_000;
const T = (s) => new Date((clock0 + s) * 1000).toISOString();
const L = (o) => `${JSON.stringify(o)}\n`;
// The walker's own reading of a record's clock.
const tsOf = (d) => {
  if (!d || !d.timestamp) return null;
  const n = Date.parse(d.timestamp);
  return Number.isFinite(n) ? Math.floor(n / 1000) : null;
};

let q = 0;
/** A request record: `type` first, the way the CLI writes one, and a big body. */
const request = (at, kb = 64) => ({
  type: 'api-request', sessionId: 's', agentId: 'a', id: `q${++q}`,
  ...(at == null ? {} : { timestamp: T(at) }),
  params: { messages: [{ role: 'user', content: 'r'.repeat(kb * 1024) }] },
});
const blob = () => ({ type: 'api-request-blob', sessionId: 's', hash: `h${++q}`, message: { role: 'user', content: 'b' } });
const user = (text, at) => ({ type: 'user', timestamp: T(at), message: { role: 'user', content: text } });
const call = (id, at, text) => ({
  type: 'assistant', requestId: id, timestamp: T(at),
  message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'text', text }], usage: { input_tokens: 1, output_tokens: 7 } },
});
const untimed = () => ({ type: 'mode', mode: 'normal' });

function write(lines) {
  const dir = scratch(fs.mkdtempSync(path.join(os.tmpdir(), 'graph-req-')));
  const f = path.join(dir, 's.jsonl');
  fs.writeFileSync(f, lines.map((x) => (typeof x === 'string' ? x : L(x))).join(''));
  return f;
}

/** The old walk, observed: every record it hands over, and the clock it keeps. */
function oldWalk(file, cuts) {
  const seen = [];
  const clock = { firstTs: null, lastTs: null };
  let at = 0;
  for (const to of cuts) {
    at = graph.walkLines(file, at, to, (d) => {
      const ts = tsOf(d);
      if (ts) { if (!clock.firstTs) clock.firstTs = ts; clock.lastTs = ts; }
      if (!String(d.type).startsWith('api-request')) seen.push(d);
    });
  }
  return { seen, clock, at };
}

function newWalk(file, cuts) {
  const seen = [];
  const clock = { firstTs: null, lastTs: null };
  let at = 0;
  for (const to of cuts) {
    at = graph.walkLines(file, at, to, (d) => {
      const ts = tsOf(d);
      if (ts) { if (!clock.firstTs) clock.firstTs = ts; clock.lastTs = ts; }
      // A request record whose `type` is not its first key is not recognised
      // by its bytes, so it is parsed and handed over like any other record.
      // Both consumers ignore every request record but for its clock, so the
      // comparison is over the records they act on.
      if (!String(d.type).startsWith('api-request')) seen.push(d);
    }, clock);
  }
  return { seen, clock, at };
}

const shapes = {
  'requests between calls': [user('go', 1), request(2), call('a', 3, 'x'), blob(), request(4), call('b', 5, 'y')],
  'the walk ends on a request': [user('go', 1), call('a', 2, 'x'), blob(), request(9)],
  'the walk ends on requests, the last without a timestamp': [user('go', 1), call('a', 2, 'x'), request(8), request(null)],
  'a request is the first record': [request(1), user('go', 2), call('a', 3, 'x')],
  'nothing but requests': [blob(), request(4), request(6), blob()],
  'requests, then records without a clock': [user('go', 1), request(5), untimed(), untimed()],
  'a request older than the record before it': [user('go', 10), request(3), untimed()],
  'a corrupt request line': [user('go', 1), call('a', 2, 'x'), `{"type":"api-request","timestamp":"${T(50)}","params":{"cut\n`],
  'a corrupt request line, then a good one': [user('go', 1), `{"type":"api-request","timestamp":"${T(50)}","par\n`, request(7)],
  'an unfinished request at the end': [user('go', 1), call('a', 2, 'x'), `{"type":"api-request","timestamp":"${T(60)}","params":{`],
  'a request that only looks like one later in its line': [user('go', 1), { note: 'not first', type: 'api-request', timestamp: T(40) }, untimed()],
};

test('the walk hands over the same records and ends on the same clock as before', () => {
  for (const [name, lines] of Object.entries(shapes)) {
    const f = write(lines);
    const size = fs.statSync(f).size;
    // One walk, and the same bytes walked in pieces — the shape of a poll that
    // lands while a session writes.
    const cuts = [[size], [Math.floor(size / 3), Math.floor((2 * size) / 3), size], [7, 70000, 70100, size]];
    for (const c of cuts) {
      const a = oldWalk(f, c);
      const b = newWalk(f, c);
      assert.deepEqual(b.seen, a.seen, `${name} (cuts ${c}): records`);
      assert.deepEqual(b.clock, a.clock, `${name} (cuts ${c}): clock`);
      assert.equal(b.at, a.at, `${name} (cuts ${c}): resume point`);
    }
  }
});

test('a session that ends waiting on the model is dated by its last request', () => {
  graph.resetCache();
  const f = write([user('go', 0), call('a', 5, 'working'), blob(), request(600)]);
  const g = graph.sessionGraph(f, 'sreq1', { now: (clock0 + 700) * 1000 });
  assert.equal(g.totals.lastActivityTs, clock0 + 600);
  assert.equal(g.totals.wallMs, 600 * 1000);
  assert.equal(g.totals.startedAt, clock0);
});

test('request bodies are stepped over, not parsed', () => {
  graph.resetCache();
  const lines = [user('go', 0)];
  for (let k = 0; k < 40; k++) lines.push(blob(), request(10 + k * 10, 96), call(`c${k}`, 11 + k * 10, `step ${k}`));
  const f = write(lines);
  const size = fs.statSync(f).size;
  let parsed = 0;
  const realParse = JSON.parse;
  JSON.parse = function (text, ...rest) { parsed += String(text).length; return realParse.call(this, text, ...rest); };
  let g;
  try { g = graph.sessionGraph(f, 'sreq2', { now: (clock0 + 900) * 1000 }); } finally { JSON.parse = realParse; }
  assert.equal(g.totals.tokens.output, 40 * 7, 'every call still counted');
  assert.equal(g.totals.lastActivityTs, clock0 + 401);
  // The request bodies are ~95% of this file. What is still parsed is every
  // other record — and nothing of the requests, because the walk ends on a call.
  assert.ok(parsed < size * 0.1, `parsed ${parsed} of ${size} bytes`);
});
