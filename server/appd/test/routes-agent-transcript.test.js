'use strict';
// Route-level tests for the stream picker: the unfiltered agent list and the
// per-agent transcript the app switches the conversation view to.
//
// The two things worth pinning here cannot be seen from lib/agents' own tests:
// that the agent route's PAGING is byte-identical to the parent's (a second
// implementation of `offset`/`until` that drifts would lose messages in the
// middle of a conversation, silently), and that the agent id never becomes a
// path. The id arrives from a client, and the only reason `agent-../x` is not a
// file read is that the route matches basenames from listAgentFiles instead of
// joining. That is a property, not a line of code, so it is tested as one.
//
// SAFETY: tmux sessions carry a name prefix nothing else uses and are killed in
// after(), on a PRIVATE tmux socket. The daemon runs against a scratch
// HUGINN_APPD_DATA and a scratch HUGINN_APPD_STATE_DIR — never
// /var/lib/huginn-appd, never /run/huginn-claude-state. The fixture tree is
// COPIED out of test/fixtures/agents; the originals are never touched.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did once: two
// files both sat inside 9700-9949 and the suite passed four times before failing
// 16 tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer            8788 + pid%900  ->  8788-9687
//   routes-lifecycle         9700 + pid%100  ->  9700-9799
//   routes-rounds            9800 + pid%60   ->  9800-9859
//   routes-devices           9870 + pid%50   ->  9870-9919
//   session-identity         9930 + pid%40   ->  9930-9969
//   breaker-fixes            9971 + pid%25   ->  9971-9995
//   routes-modelgate        10000 + pid%50   -> 10000-10049
//   routes-localmodels      10050 + pid%50   -> 10050-10099
//   routes-polish           10100 + pid%50   -> 10100-10149
//   routes-scratchpads      10150 + pid%50   -> 10150-10199
//   routes-overview         10200 + pid%50   -> 10200-10249
//   push-retire             10250 + pid%50   -> 10250-10299
//   routes-desktop          10300 + pid%50   -> 10300-10349
//   routes-headroom         10350 + pid%50 -> 10350-10399     (wave 1)
//   routes-agent-transcript 10400 + pid%50 -> 10400-10449     (this file)
//   routes-resume           10450 + pid%50 -> 10450-10499     (wave 1)
//   routes-quick-actions    10600 + pid%50 -> 10600-10649     (wave 2)
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10400 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

const SESS = `agtr_${process.pid}`;
// A session the hook has never reported a Claude id for — the shape a plain
// shell reaches these routes in.
const BARE = `agbare_${process.pid}`;
const CLAUDE_ID = `agtr-${process.pid}-0000`;
const TMUX_SOCK = `huginn-test-${process.pid}`;

// The real fixture ids (test/fixtures/agents/README.md). Deliberately NOT
// uuid-shaped: that is why the route has its own id pattern.
const DIRECT = 'agent-af7ca864cee1939de';
const DIRECT_OLD = 'agent-acdf276aeabf0df8f';
const WF_MEMBER = 'agent-a67c22d167ec48615';
const WF_RUN = 'wf_26d79030-31f';

let tmp, token, daemon, agentsDir;

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
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' }).trim();
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-agtr-'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  // The layout agentsDirFor() expects: <projects>/<sessionId>.jsonl beside
  // <projects>/<sessionId>/subagents/.
  const projects = path.join(tmp, 'projects');
  fs.mkdirSync(path.join(projects, CLAUDE_ID), { recursive: true });
  agentsDir = path.join(projects, CLAUDE_ID, 'subagents');
  fs.cpSync(path.join(__dirname, 'fixtures', 'agents', 'subagents'), agentsDir, { recursive: true });

  // The PARENT transcript is a byte-for-byte copy of one agent's file. That is
  // what makes the paging comparison exact: the two routes read identical bytes
  // through different code paths, so any difference in the page is the route's.
  const parent = path.join(projects, `${CLAUDE_ID}.jsonl`);
  fs.copyFileSync(path.join(agentsDir, `${DIRECT}.jsonl`), parent);

  // Mtimes decide both the RECENT_S filter and `status`, and a checked-out
  // file's mtime is whatever git felt like. Choose them.
  const old = new Date(Date.now() - 3 * 60 * 60 * 1000);
  for (const rel of [`${DIRECT_OLD}.jsonl`, path.join('workflows', WF_RUN, `${WF_MEMBER}.jsonl`)]) {
    fs.utimesSync(path.join(agentsDir, rel), old, old);
  }

  for (const name of [SESS, BARE]) {
    sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  }
  fs.writeFileSync(path.join(tmp, 'state', SESS), JSON.stringify({
    state: 'idle', sessionId: CLAUDE_ID, transcript: parent, cwd: tmp, ts: Math.floor(Date.now() / 1000),
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
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
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
  for (const name of [SESS, BARE]) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  // Reap the private server outright (-L targets only OUR socket, never default).
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------- the list view

test('the default list is still the 45-minute sheet', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/agents`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.deepEqual(body.agents.map((a) => a.id), ['af7ca864cee1939de'],
    'two of the three fixtures went cold hours ago');
  assert.equal(body.active, 1);
});

test('?all=1 is the picker view: every agent, with the fields it needs', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/agents?all=1`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.agents.length, 3);
  const member = body.agents.find((a) => a.id === 'a67c22d167ec48615');
  assert.equal(member.workflowId, WF_RUN, 'the grouping the picker renders');
  assert.equal(member.agentType, 'workflow-subagent');
  assert.equal(member.depth, 1);
  assert.equal(member.status, 'failed', 'from the run journal, not from a graph walk');
  assert.equal(body.agents.find((a) => a.id === 'af7ca864cee1939de').status, 'running');
});

// ------------------------------------------------------- one agent's stream

test('an agent transcript is a page, with the agent named and no parent extras', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.agentId, DIRECT);
  assert.equal(body.workflowId, null, 'a direct agent belongs to no run');
  assert.ok(body.events.length > 0, 'an agent file is an ordinary transcript');
  assert.equal(typeof body.nextOffset, 'number');
  assert.equal(typeof body.windowStart, 'number');
  assert.ok('modelDisplay' in body);
  // `activity` and `tasks` describe the PARENT's pane and its background
  // shells. They belong to the parent no matter which stream is on screen, and
  // shipping them here would have the app show a subagent as "running npm test"
  // because the session it branched from is.
  assert.equal(body.activity, undefined);
  assert.equal(body.tasks, undefined);
  assert.equal(body.bgAgents, undefined);
});

test('offset/limit page exactly like the parent route', async () => {
  // Same bytes on disk, two routes. Any difference in these numbers is a second
  // paging implementation drifting from the first — which does not fail loudly,
  // it loses messages out of the middle of a conversation.
  for (const q of ['?limit=3', '?limit=3&offset=0', '?limit=5', '?limit=999999', '?limit=0']) {
    const mine = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript${q}`);
    const parent = await api(`/v1/sessions/${SESS}/transcript${q}`);
    assert.equal(mine.status, 200, JSON.stringify(mine.body));
    assert.equal(parent.status, 200, JSON.stringify(parent.body));
    assert.deepEqual(mine.body.events, parent.body.events, `events differ at ${q}`);
    assert.equal(mine.body.nextOffset, parent.body.nextOffset, `nextOffset differs at ${q}`);
    assert.equal(mine.body.windowStart, parent.body.windowStart, `windowStart differs at ${q}`);
    assert.equal(mine.body.truncated, parent.body.truncated, `truncated differs at ${q}`);
  }
});

test('until reads backwards, exactly like the parent route', async () => {
  // The half a naive re-implementation gets wrong: `until` ends the window here
  // instead of at the live end of the file, and it is the only way to reach the
  // history of a long conversation.
  const first = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript?limit=2`);
  const q = `?limit=2&until=${first.body.windowStart}`;
  const back = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript${q}`);
  const backParent = await api(`/v1/sessions/${SESS}/transcript${q}`);
  assert.equal(back.status, 200, JSON.stringify(back.body));
  assert.deepEqual(back.body.events, backParent.body.events, 'backwards paging differs');
  assert.equal(back.body.windowStart, backParent.body.windowStart);
  assert.ok(back.body.windowStart <= first.body.windowStart,
    'until must walk towards the head, not stand still');
});

test('a garbage cursor is refused, not read as "from the top"', async () => {
  for (const q of ['?offset=abc', '?until=abc', '?offset=NaN', '?until=x1']) {
    const { status, body } = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript${q}`);
    assert.equal(status, 400, `expected 400 for ${q}, got ${status} ${JSON.stringify(body)}`);
    assert.match(body.error, /must be a number/);
  }
  // An EMPTY value is 0 rather than an error — `Number('')` is 0 — which is odd
  // enough to look like a bug here. It is not this route's decision to make:
  // the parent has always behaved that way and the contract is "identical".
  for (const q of ['?offset=', '?until=']) {
    const mine = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript${q}`);
    const parent = await api(`/v1/sessions/${SESS}/transcript${q}`);
    assert.equal(mine.status, parent.status, `status differs at ${q}`);
    assert.deepEqual(mine.body.events, parent.body.events, `events differ at ${q}`);
  }
});

test('a workflow member is served through its run directory', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/agents/${WF_MEMBER}/transcript`);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.agentId, WF_MEMBER);
  assert.equal(body.workflowId, WF_RUN,
    'a member has no toolUseId, so the run dir IS its parent link');
  assert.ok(body.events.length > 0);
});

test('anything that is not agent-<hex> is refused before any file is touched', async () => {
  // `agent-../x` arrives percent-encoded because a literal `../` is normalised
  // out of the path by URL parsing long before the route sees it — which is
  // exactly why the encoded form is the one worth testing.
  const bad = [
    'agent-..%2Fx',
    'agent-%2e%2e%2f%2e%2e%2fetc%2fpasswd',
    'agent-ZZZZZZ',                      // not hex
    'agent-abc',                         // too short
    `agent-${'a'.repeat(33)}`,           // too long
    'agent-af7ca864cee1939de.jsonl',     // the basename, not the id
    'agent-%zz',                         // a malformed escape is not an id either
  ];
  for (const id of bad) {
    const { status, body } = await api(`/v1/sessions/${SESS}/agents/${id}/transcript`);
    assert.equal(status, 400, `expected 400 for ${id}, got ${status} ${JSON.stringify(body)}`);
    assert.match(body.error, /invalid agent id/);
  }
});

test('the BARE hex is accepted too, because that is what /agents emits', async () => {
  // ⚠ `GET /agents` emits `path.basename(f).replace(/^agent-|\.jsonl$/g, '')` —
  // the BARE hex — and the client hands that back verbatim. A route that took
  // only the prefixed form 400'd every stream chip; and a 400 is not the 404 the
  // client's compat path watches for, so it did not even degrade to "this daemon
  // is too old" — the strip showed the raw daemon error instead.
  const bare = DIRECT.replace(/^agent-/, '');
  const prefixed = await api(`/v1/sessions/${SESS}/agents/${DIRECT}/transcript`);
  const plain = await api(`/v1/sessions/${SESS}/agents/${bare}/transcript`);
  assert.equal(prefixed.status, 200, JSON.stringify(prefixed.body));
  assert.equal(plain.status, 200, JSON.stringify(plain.body));
  // And BOTH echo the prefixed form, which is what the file on disk is called
  // and what the client's own shortId() strips.
  assert.equal(plain.body.agentId, DIRECT);
  assert.equal(prefixed.body.agentId, DIRECT);
  assert.deepEqual(plain.body.events, prefixed.body.events);
});

test('an id resolves against the enumeration, never against the filesystem', async () => {
  // The regex is a guess about what a path resolver will do; the enumeration is
  // the fact. Proved by planting a real file the enumeration cannot see — a
  // name that is not `agent-*.jsonl` — and asking for it by a VALID id. If the
  // route joined the id onto the directory this would find something.
  fs.writeFileSync(path.join(agentsDir, 'agent-deadbeef.txt'), 'not a transcript\n');
  const hidden = await api(`/v1/sessions/${SESS}/agents/agent-deadbeef/transcript`);
  assert.equal(hidden.status, 404);
  assert.match(hidden.body.error, /no such agent/);

  const unknown = await api(`/v1/sessions/${SESS}/agents/agent-abcdef123456/transcript`);
  assert.equal(unknown.status, 404);
  assert.match(unknown.body.error, /no such agent/);
});

test('a session with no recorded transcript says so, and an unknown one 404s', async () => {
  const bare = await api(`/v1/sessions/${BARE}/agents/${DIRECT}/transcript`);
  assert.equal(bare.status, 409, JSON.stringify(bare.body));
  assert.match(bare.body.error, /no transcript recorded/);

  const gone = await api(`/v1/sessions/nosuch_${process.pid}/agents/${DIRECT}/transcript`);
  assert.equal(gone.status, 404);
  assert.match(gone.body.error, /no such session/);
});
