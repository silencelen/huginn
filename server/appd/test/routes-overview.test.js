'use strict';
// Route-level tests for the session overview: the cheap header, the map, its
// cursor, and the notes a person writes against a run.
//
// The three things worth pinning here cannot be seen from lib/sessiongraph's own
// tests: that the graph never rides the sessions LIST (the whole point of a
// separate route), that a session with no Claude id is REFUSED rather than
// silently written under its tmux name, and that the cursor actually shortcuts.
//
// SAFETY: tmux sessions are created with a name prefix nothing else uses and
// killed in after(). The daemon runs against a scratch HUGINN_APPD_DATA and a
// scratch state dir — never /var/lib/huginn-appd, never /run/huginn-claude-state.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: two files
// once sat inside 9700-9949, and the suite passed four times before failing 16
// tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer        8788 + pid%900  ->  8788-9687
//   routes-lifecycle     9700 + pid%100  ->  9700-9799
//   routes-rounds        9800 + pid%60   ->  9800-9859
//   routes-devices       9870 + pid%50   ->  9870-9919
//   session-identity     9930 + pid%40   ->  9930-9969
//   breaker-fixes        9971 + pid%25   ->  9971-9995
//   routes-modelgate    10000 + pid%50   -> 10000-10049
//   routes-localmodels  10050 + pid%50   -> 10050-10099
//   routes-polish       10100 + pid%50   -> 10100-10149
//   routes-scratchpads  10150 + pid%50   -> 10150-10199
//   routes-overview     10200 + pid%50   -> 10200-10249   (this file)
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10200 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

const SESS = `ovtest_${process.pid}`;
const BARE = `ovbare_${process.pid}`;
// A session whose transcript is recorded but still EMPTY — the state a hook
// creates the instant before the first record lands, and the one where a cursor
// of zero and no cursor at all had become the same thing.
const EMPTY = `ovempty_${process.pid}`;
const CLAUDE_ID = `ov-${process.pid}-0000`;

let tmp, token, daemon, transcript;

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function sh(cmd, args) {
  return execFileSync(cmd, args, { encoding: 'utf8' }).trim();
}

const CLOCK = 1_756_000_000;
const T = (o) => new Date((CLOCK + o) * 1000).toISOString();

function assistant(id, at, blocks, out = 10) {
  return {
    type: 'assistant',
    uuid: `u-${id}-${at}`,
    requestId: id,
    timestamp: T(at),
    effort: 'xhigh',
    message: {
      role: 'assistant',
      model: 'claude-opus-5',
      content: blocks,
      usage: { input_tokens: 1, output_tokens: out, cache_read_input_tokens: 100, cache_creation_input_tokens: 5 },
    },
  };
}

function appendRecords(records) {
  fs.appendFileSync(transcript, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-overview-'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));
  const projects = path.join(tmp, 'projects');
  fs.mkdirSync(projects);

  transcript = path.join(projects, `${CLAUDE_ID}.jsonl`);
  fs.writeFileSync(transcript, [
    JSON.stringify({ type: 'user', uuid: 'u0', timestamp: T(0), message: { role: 'user', content: 'map this session' } }),
    JSON.stringify(assistant('r1', 1, [{ type: 'text', text: 'Reading the tree.' }])),
    JSON.stringify(assistant('r1', 1, [{ type: 'tool_use', id: 't1', name: 'Read', input: { file_path: '/a/b' } }])),
    JSON.stringify({
      type: 'user',
      uuid: 'tr1',
      timestamp: T(2),
      message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: 'ok' }] },
    }),
    '',
  ].join('\n'));

  // Three real tmux sessions: one the hook has reported a Claude id for, one it
  // has not (which is how a plain shell reaches these routes), and one whose
  // transcript is recorded and still zero bytes long.
  for (const name of [SESS, BARE, EMPTY]) {
    sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  }
  fs.writeFileSync(path.join(tmp, 'state', SESS), JSON.stringify({
    state: 'idle', sessionId: CLAUDE_ID, transcript, cwd: tmp, ts: Math.floor(Date.now() / 1000),
  }));

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one. Ask an
  // AUTHENTICATED question before trusting the port.
  const own = await api('/v1/scratchpads');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  for (const name of [SESS, BARE, EMPTY]) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------------ overview

test('the overview is the header, with no map in it', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.claudeSessionId, CLAUDE_ID);
  assert.equal(body.totals.userMessages, 1);
  assert.equal(body.totals.toolCalls, 1);
  assert.equal(body.totals.tokens.output, 10, 'two records, one requestId, one call');
  assert.equal(body.nodes, undefined, 'the cheap route stays cheap');
  assert.deepEqual(body.meta, { goals: '', notes: '', updatedAt: 0 });
});

test('the overview says what the session would have billed at API list rates', async () => {
  // One opus call: 1 input, 10 output, 100 cache read, 5 cache written with no
  // TTL recorded. At list rates that is $0.000005 + $0.00025 + $0.00005 +
  // $0.00003125 — the last at the 5-minute rate, because nothing said which TTL
  // it was and the estimate takes the cheaper candidate.
  //
  // The account is on a subscription and none of this was charged; the number
  // answers "what would this have cost on the API", and the client captions it
  // as an estimate. That caption is the reason it is allowed on a screen.
  const { status, body } = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.totals.estCost.usd, 0.000336);
  assert.deepEqual(body.totals.estCost.byModel, [{ model: 'claude-opus-5', usd: 0.000336 }]);
  assert.equal(body.totals.estCost.unpricedTokens, 0, 'every token here is on the price table');
  assert.equal(body.totals.agentEstCostUsd, 0, 'no agents ran — a number, not an absence');
});

test('the sessions LIST is not made to carry any of it', async () => {
  // The list is polled by every client and by the notification poller. A
  // whole-file walk per session per poll would make the cheapest route here the
  // most expensive one, so the graph lives behind a route somebody opens.
  const { body } = await api('/v1/sessions?preview=1');
  const row = body.sessions.find((s) => s.name === SESS);
  assert.ok(row, 'the session is listed');
  for (const key of ['totals', 'rate', 'nodes', 'graph', 'agents', 'overview']) {
    assert.equal(row[key], undefined, `a session row must not carry ${key}`);
  }
});

test('a session with no recorded transcript says so instead of guessing', async () => {
  const { status, body } = await api(`/v1/sessions/${BARE}/overview`);
  assert.equal(status, 409);
  assert.match(body.error, /transcript/);
});

test('an unknown session is a 404 on every one of these routes', async () => {
  for (const suffix of ['overview', 'graph']) {
    const { status } = await api(`/v1/sessions/nosuch_${process.pid}/${suffix}`);
    assert.equal(status, 404, suffix);
  }
});

// --------------------------------------------------------------------- graph

test('the graph carries the spine, the cursor and the notes', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/graph`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.v, 1);
  assert.deepEqual(body.nodes.map((n) => n.kind), ['user', 'action']);
  assert.equal(body.nodes[0].label, 'map this session');
  assert.ok(body.cursor.size > 0);
  assert.equal(body.cursor.agentBytes, 0);
  assert.ok(body.meta, 'the map and the notes arrive together, so one poll serves the screen');
});

test('the graph and the overview never quote two different estimates', async () => {
  // Both shapes are built from one walk of one file, so they cannot honestly
  // disagree — but they are assembled at two call sites, and a field added to
  // one of them is exactly the kind of thing that ships half-done. A person
  // reading $0.34 on the map and $0.31 on the header has no way to tell which
  // is the lie.
  const graph = await api(`/v1/sessions/${SESS}/graph`);
  const overview = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(graph.status, 200, JSON.stringify(graph.body));
  assert.ok(graph.body.totals.estCost, 'the map carries the estimate too');
  assert.deepEqual(graph.body.totals.estCost, overview.body.totals.estCost);
  assert.equal(graph.body.totals.agentEstCostUsd, overview.body.totals.agentEstCostUsd);
});

test('a matching cursor answers unchanged instead of the whole map', async () => {
  const first = await api(`/v1/sessions/${SESS}/graph`);
  const { size, agentBytes } = first.body.cursor;
  const again = await api(`/v1/sessions/${SESS}/graph?size=${size}&agentBytes=${agentBytes}`);
  assert.equal(again.status, 200);
  assert.equal(again.body.unchanged, true);
  assert.equal(again.body.nodes, undefined);
  assert.deepEqual(again.body.cursor, { size, agentBytes });
});

test('the unchanged reply still carries the notes, or an idle session never learns them', async () => {
  // ⚠ THE CASE THIS EXISTS FOR: goals typed on the phone against a session
  // nobody is running. An idle session is exactly the one whose cursor never
  // moves, so every poll from the watching desktop takes the short-circuit —
  // and a short-circuit carrying only the cursor meant the edit arrived when the
  // run next wrote a byte, which for an idle session is never. Both clients
  // apply `meta` from either shape; that is the contract, and it is only true if
  // the field is on both.
  await api(`/v1/sessions/${SESS}/meta`, {
    method: 'POST', body: JSON.stringify({ goals: 'typed on the phone', notes: 'while nothing ran' }),
  });
  const first = await api(`/v1/sessions/${SESS}/graph`);
  const { size, agentBytes } = first.body.cursor;
  const again = await api(`/v1/sessions/${SESS}/graph?size=${size}&agentBytes=${agentBytes}`);
  assert.equal(again.body.unchanged, true, 'nothing moved, which is the whole point');
  assert.equal(again.body.meta.goals, 'typed on the phone');
  assert.equal(again.body.meta.notes, 'while nothing ran');
  assert.ok(again.body.meta.updatedAt > 0);
});

test('a fetch with NO cursor is never "unchanged" — there is nothing to be unchanged from', async () => {
  // ⚠ `Number(null)` is 0 and an untouched transcript's cursor is also 0, so a
  // first fetch of a session that has not written a byte yet was answered
  // `unchanged: true` with no map in it. The client had nothing to hold, and
  // sat on an empty screen for as long as the session stayed quiet. The
  // agentBytes half already tested for PRESENCE; the size half only tested the
  // value it coerced to.
  const empty = path.join(tmp, 'projects', `${CLAUDE_ID}-empty.jsonl`);
  fs.writeFileSync(empty, '');
  fs.writeFileSync(path.join(tmp, 'state', EMPTY), JSON.stringify({
    state: 'idle', sessionId: `${CLAUDE_ID}-empty`, transcript: empty, cwd: tmp,
    ts: Math.floor(Date.now() / 1000),
  }));
  const { status, body } = await api(`/v1/sessions/${EMPTY}/graph`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.unchanged, undefined, 'a first fetch always gets the map');
  assert.deepEqual(body.nodes, [], 'even when the map is empty, which is the answer');
  assert.equal(body.cursor.size, 0);

  // And the short-circuit still works when the cursor is actually SENT as zero.
  const again = await api(`/v1/sessions/${EMPTY}/graph?size=0&agentBytes=0`);
  assert.equal(again.body.unchanged, true, 'an explicit 0 is a cursor; an absent one is not');
});

test('a stale cursor gets the map, and the map has grown', async () => {
  const before = await api(`/v1/sessions/${SESS}/graph`);
  const { size, agentBytes } = before.body.cursor;
  appendRecords([
    { type: 'user', uuid: 'u1', timestamp: T(10), message: { role: 'user', content: 'and now this' } },
    assistant('r2', 11, [{ type: 'text', text: 'Understood.' }], 7),
  ]);
  const after2 = await api(`/v1/sessions/${SESS}/graph?size=${size}&agentBytes=${agentBytes}`);
  assert.equal(after2.body.unchanged, undefined, 'a grown file is not unchanged');
  assert.deepEqual(after2.body.nodes.map((n) => n.kind), ['user', 'action', 'user', 'response']);
  assert.equal(after2.body.totals.tokens.output, 17, 'the head was not re-counted');
  assert.ok(after2.body.cursor.size > size);
});

// --------------------------------------------------------------------- notes

test('goals and notes are kept, and come back on the overview', async () => {
  const save = await api(`/v1/sessions/${SESS}/meta`, {
    method: 'POST',
    body: JSON.stringify({ goals: 'land the walker', notes: 'watch the 33MB case' }),
  });
  assert.equal(save.status, 200, JSON.stringify(save.body));
  assert.equal(save.body.meta.goals, 'land the walker');
  assert.ok(save.body.meta.updatedAt > 0);

  const o = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(o.body.meta.notes, 'watch the 33MB case');
});

test('one field at a time, without erasing the other', async () => {
  // Two editors on one screen, each autosaving on its own debounce: a save that
  // sent only the field being typed and cleared the rest would delete the notes
  // every time somebody edited the goals.
  await api(`/v1/sessions/${SESS}/meta`, { method: 'POST', body: JSON.stringify({ goals: 'g1', notes: 'n1' }) });
  await api(`/v1/sessions/${SESS}/meta`, { method: 'POST', body: JSON.stringify({ goals: 'g2' }) });
  const o = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(o.body.meta.goals, 'g2');
  assert.equal(o.body.meta.notes, 'n1');
});

test('the file is written under the CLAUDE id, never the tmux name', async () => {
  // tmux names are reused and the hook's state files outlive their sessions. A
  // notes file named after the window would be handed to whoever takes the name
  // next, and then overwritten by them.
  await api(`/v1/sessions/${SESS}/meta`, { method: 'POST', body: JSON.stringify({ goals: 'keyed right' }) });
  const dir = path.join(tmp, 'data', 'session-meta');
  assert.deepEqual(fs.readdirSync(dir), [`${CLAUDE_ID}.json`]);
  const onDisk = JSON.parse(fs.readFileSync(path.join(dir, `${CLAUDE_ID}.json`), 'utf8'));
  assert.equal(onDisk.goals, 'keyed right');
  assert.equal((fs.statSync(path.join(dir, `${CLAUDE_ID}.json`)).mode & 0o777), 0o600);
});

test('a session with no Claude id is refused, not filed under its window name', async () => {
  const { status, body } = await api(`/v1/sessions/${BARE}/meta`, {
    method: 'POST', body: JSON.stringify({ goals: 'nowhere to put this' }),
  });
  assert.equal(status, 409);
  assert.match(body.error, /session id/);
  assert.equal(fs.existsSync(path.join(tmp, 'data', 'session-meta', `${BARE}.json`)), false);
});

test('the caps are refusals with a reason, not silent truncation', async () => {
  const long = await api(`/v1/sessions/${SESS}/meta`, {
    method: 'POST', body: JSON.stringify({ notes: 'x'.repeat(20_001) }),
  });
  assert.equal(long.status, 400);
  assert.match(long.body.error, /20,000/);

  const wrong = await api(`/v1/sessions/${SESS}/meta`, {
    method: 'POST', body: JSON.stringify({ goals: 42 }),
  });
  assert.equal(wrong.status, 400);

  const o = await api(`/v1/sessions/${SESS}/overview`);
  assert.equal(o.body.meta.goals, 'keyed right', 'a refused save changed nothing');
});
