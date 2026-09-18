'use strict';
// The consoles routes: a hand-curated registry of the pages this host serves,
// with a liveness probe, and an approval card the daemon shows and never runs.
//
// THE PROMISE UNDER TEST: a list you can trust. Every case here is a way it
// stops being trustworthy while still rendering — a row that says "up" because
// the probe behind it has not come back yet, an edit that silently loses to
// another client's, an address that was judged and then stored as something
// else, and a card of root commands that drifts from the machine it names.
//
// SAFETY, and why this suite PRE-WRITES the store file: the seed list is real.
// `http://huginn:8088/` and its three siblings are live services on this host,
// so a suite that let the daemon seed itself would have its background sweep
// GET the operator's actual armap, jtyper, board view and BTC sim. Instead the
// store is written before the daemon starts, pointing at fixture servers this
// file owns on ephemeral ports. Seeding itself is covered in consoles.test.js,
// which needs no sockets for it.
//
// No tmux sessions are created; the daemon is given a private tmux socket
// anyway, so nothing it does at startup can reach the operator's server.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const http = require('node:http');
const path = require('node:path');
const crypto = require('node:crypto');
const consolesLib = require('../lib/consoles');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. See the full table
// in routes-scratchpads.test.js. Wave 3 owns 10650-10749:
//
//   routes-projects     10650 + pid%50   -> 10650-10699
//   routes-consoles     10700 + pid%50   -> 10700-10749   (this file)
//
// Only the DAEMON takes a number from the block. The fixture servers below bind
// port 0 and are told their port by the kernel, which is one fewer range to
// collide with.
const PORT = 10700 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

// Private tmux socket shared with the daemon under test, so nothing it does at
// startup surfaces in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, dataDir, token, daemon;
let okServer, hangServer, okPort, hangPort;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    // A bound on the TEST's own patience. Without it, an unbounded probe inside
    // the daemon would hang this runner instead of failing it — `node --test`
    // has no default per-test timeout.
    signal: AbortSignal.timeout(init.timeoutMs || 10_000),
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

async function list() {
  const { status, body } = await api('/v1/consoles');
  assert.equal(200, status);
  return body;
}

function rowOf(body, id) {
  return (body.consoles || []).find((c) => c.id === id) || null;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-consoles-'));
  dataDir = path.join(tmp, 'data');
  fs.mkdirSync(dataDir);
  fs.mkdirSync(path.join(tmp, 'state'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // A page that answers, and one that accepts the connection and then says
  // nothing at all — the only shape of failure a timeout is needed for.
  okServer = http.createServer((req, res) => {
    const code = Number(req.url.slice(1)) || 200;
    res.writeHead(code, { 'content-type': 'text/plain' });
    res.end('ok');
  });
  await new Promise((r) => okServer.listen(0, '127.0.0.1', r));
  okPort = okServer.address().port;

  hangServer = net.createServer(() => { /* accept, and never answer */ });
  await new Promise((r) => hangServer.listen(0, '127.0.0.1', r));
  hangPort = hangServer.address().port;

  // The store, written before the daemon reads it. See the SAFETY note above.
  fs.writeFileSync(path.join(dataDir, consolesLib.STORE_NAME), JSON.stringify({
    schema: consolesLib.SCHEMA,
    seeded: true,
    consoles: [
      consolesLib.buildRecord({ id: 'alive', name: 'A page that answers', kind: 'tool',
        url: `http://127.0.0.1:${okPort}/`, notes: 'fixture' }, 1789460000),
      consolesLib.buildRecord({ id: 'hang', name: 'A page that hangs', kind: 'lab',
        url: `http://127.0.0.1:${hangPort}/`, notes: 'fixture' }, 1789460000),
    ],
  }, null, 2), { mode: 0o600 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: tmp,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {   // 30s cap: a loaded host has pushed start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one.
  const own = await api('/v1/consoles');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (okServer) okServer.close();
  if (hangServer) hangServer.close();
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------------ the probe

test('the list route exists, which is how a client knows the feature is here', async () => {
  // Both clients probe this once per connection and hide the whole Consoles
  // surface on a 404, rather than showing a door that leads to an error (the
  // archive/scratchpads precedent). "200 with a list" is a contract.
  const body = await list();
  assert.ok(Array.isArray(body.consoles));
  assert.equal(consolesLib.MAX_CONSOLES, body.max, 'the cap is on the wire, so a client can say what it is');
  assert.deepEqual(consolesLib.KINDS, body.kinds, 'and the kind vocabulary, so the picker is not a second copy');
  assert.equal('host', body.reachableFrom, 'said once at the top as well as on every row');
  assert.equal(consolesLib.PROBE_INTERVAL_MS, body.probeIntervalMs);
});

test('every row carries the probe fields, with null where there has been no observation', async () => {
  const body = await list();
  const row = rowOf(body, 'alive');
  assert.ok(row, 'the store written before startup is the store the daemon read');
  for (const field of ['id', 'name', 'url', 'kind', 'notes', 'addedAt', 'version',
    'up', 'lastProbeAt', 'latencyMs', 'httpStatus', 'reachableFrom']) {
    assert.ok(field in row, `${field} must be present on every row`);
  }
  assert.equal('host', row.reachableFrom);
  assert.ok(row.up === null || row.up === true, 'never probed, or probed and up — but never a bare false here');
});

test('the list answers at the speed of a file read, not at the speed of the network', async () => {
  // ⚠ The store holds a console that accepts connections and never answers. If
  // the list route awaited its own sweep, this call would take at least the
  // probe timeout — and a client polls this route while the view is open.
  const started = Date.now();
  await list();
  assert.ok(Date.now() - started < 1_500, `the list took ${Date.now() - started}ms`);
});

// ---------------------------------------------------------------- the card

test('the approval card rides the list, unapplied, with the exact commands', async () => {
  // Decision 47: the app shows these and NOTHING runs them. Asserted at the
  // route because this is the form the clients actually decode.
  const { approval } = await list();
  assert.equal(false, approval.applied, 'the marker file does not exist, and the daemon never creates it');
  assert.equal('owner', approval.runBy);
  assert.equal(path.join(dataDir, consolesLib.REBIND_MARKER_NAME), approval.markerPath);
  assert.deepEqual(['rebind', 'firewall'], approval.steps.map((s) => s.id));
  // ⚠ LITERALS, not the module's own constants. Comparing the wire to the same
  // constant the wire was built from asserts that JSON round-trips — a drifted
  // port would sail through it, and these are four rules somebody pastes into
  // another machine's firewall to match ports three units were rebound to.
  assert.deepEqual([
    'systemctl edit armap.service            # ExecStart: bind 0.0.0.0 instead of the tailnet address',
    'systemctl edit jtyper-trainer.service   # same',
    'systemctl edit boardserver.service      # same',
    'systemctl restart armap jtyper-trainer boardserver',
    "ss -ltnp | grep -E '8088|8091|8092'",
  ], approval.steps[0].commands);
  assert.deepEqual([
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8092 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8093 -log nolog',
  ], approval.steps[1].commands);
  assert.equal('/etc/pve/firewall/117.fw', approval.steps[1].file);
  assert.equal(false, fs.existsSync(approval.markerPath), 'and listing the consoles did not create it either');
});

// ------------------------------------------------------------ writing a row

test('a console can be added, and it is in the list afterwards', async () => {
  const r = await api('/v1/consoles', {
    method: 'POST',
    body: JSON.stringify({ name: 'Board view', url: `http://127.0.0.1:${okPort}/board`, kind: 'tool', notes: 'KiCad' }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal('board-view', r.body.id, 'the id is derived from the name when none is given');
  assert.equal(1, r.body.version);
  assert.equal(null, r.body.up, 'a brand new row has no observation, which is not the same as being down');
  assert.ok(rowOf(await list(), 'board-view'));
});

test('an address with a username and password in it is refused at the route', async () => {
  const r = await api('/v1/consoles', {
    method: 'POST',
    body: JSON.stringify({ name: 'Sneaky', url: 'http://admin:hunter2@127.0.0.1:9/' }),
  });
  assert.equal(400, r.status);
  assert.equal(consolesLib.REFUSED_USERINFO, r.body.error);
  assert.equal(null, rowOf(await list(), 'sneaky'), 'and nothing was stored');
});

test('a scheme that is not http or https is refused at the route', async () => {
  for (const url of ['file:///etc/shadow', 'javascript:alert(1)', 'huginn:8088']) {
    const r = await api('/v1/consoles', { method: 'POST', body: JSON.stringify({ name: 'Bad scheme', url }) });
    assert.equal(400, r.status, `${url} must be refused`);
    assert.equal(consolesLib.REFUSED_SCHEME, r.body.error);
  }
});

test('an address on the public internet is refused, because this daemon is the one that would fetch it', async () => {
  const r = await api('/v1/consoles', {
    method: 'POST',
    body: JSON.stringify({ name: 'Elsewhere', url: 'https://example.com/' }),
  });
  assert.equal(400, r.status);
  assert.equal(consolesLib.REFUSED_HOST, r.body.error);
});

test('a rename persists, and takes the version with it', async () => {
  const before = rowOf(await list(), 'alive');
  const r = await api('/v1/consoles/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: before.version, name: 'The page that answers' }),
  });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal('The page that answers', r.body.name);
  assert.equal(before.version + 1, r.body.version);

  const after = rowOf(await list(), 'alive');
  assert.equal('The page that answers', after.name, 'and it survived the trip through the file');
  assert.equal(before.url, after.url, 'a rename does not touch the address');
});

test('an edit against a stale version is 409 and carries the row it collided with', async () => {
  // Two clients on one registry is the normal case here — the desktop and the
  // phone are both polling. The loser has to be told what it lost to, or it
  // cannot show a person the difference. This is the saveScratchpad contract,
  // which both clients already adopt as an ANSWER rather than a throw.
  const current = rowOf(await list(), 'alive');
  const r = await api('/v1/consoles/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version - 1, name: 'Stale' }),
  });
  assert.equal(409, r.status);
  assert.ok(r.body.console, 'the current row travels with the refusal');
  assert.equal(current.name, r.body.console.name);
  assert.equal(current.version, r.body.console.version);
  assert.equal(current.name, rowOf(await list(), 'alive').name, 'and the losing edit changed nothing');
});

test('a PATCH with no version at all is refused rather than applied blind', async () => {
  const r = await api('/v1/consoles/alive', { method: 'PATCH', body: JSON.stringify({ name: 'No guard' }) });
  assert.equal(400, r.status);
});

test('deleting a console removes it, and deleting it again is a 404', async () => {
  const r = await api('/v1/consoles/board-view', { method: 'DELETE' });
  assert.equal(200, r.status);
  assert.equal(true, r.body.ok);
  assert.equal(null, rowOf(await list(), 'board-view'), 'gone from the list');

  const again = await api('/v1/consoles/board-view', { method: 'DELETE' });
  assert.equal(404, again.status, 'a second delete is not a second success');
});

test('an id that is not in the grammar cannot even reach a handler', async () => {
  // The id is a path segment in every route here, so the grammar is the route.
  const r = await api('/v1/consoles/NOT_AN_ID', { method: 'DELETE' });
  assert.equal(404, r.status);
  const probe = await api('/v1/consoles/nope/probe', { method: 'POST' });
  assert.equal(404, probe.status);
});

// ------------------------------------------------------------ probing for real

test('an on-demand probe answers with the status and how long it took', async () => {
  const r = await api('/v1/consoles/alive/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.up);
  assert.equal(200, r.body.httpStatus);
  assert.ok(Number.isFinite(r.body.latencyMs) && r.body.latencyMs >= 0, 'a latency, not a null');
  assert.ok(r.body.lastProbeAt > 0, 'and a time it was taken');

  // The observation is on the LIST too — the clients read it from there, not
  // from the answer to a probe nobody else made.
  const row = rowOf(await list(), 'alive');
  assert.equal(true, row.up);
  assert.equal(200, row.httpStatus);
});

test('a console that accepts the connection and never answers is DOWN, and the request still comes back', async () => {
  // ⚠ THE TEST THIS ROUTE EXISTS FOR. `fetch` has no default timeout. Without
  // the deadline in probeConsole this request never returns, the client's
  // spinner never stops, and one wedged listener holds a daemon connection open
  // for as long as the socket lives. A refused connection would fail fast and
  // prove nothing; this server completes the handshake and then says nothing.
  const started = Date.now();
  const r = await api('/v1/consoles/hang/probe', { method: 'POST' });
  const took = Date.now() - started;
  assert.equal(200, r.status);
  assert.equal(false, r.body.up);
  assert.equal(null, r.body.httpStatus, 'nothing was answered, so there is no status to report');
  assert.ok(took < 8_000, `the probe came back in ${took}ms, near its own deadline rather than the client's`);
  assert.equal(false, rowOf(await list(), 'hang').up, 'and the list says so as well');
});

test('a console re-pointed somewhere else drops the observation it no longer describes', async () => {
  // `alive` has a real, successful probe on it by now. Point it at the hanging
  // server and the old "up, 200, 3 ms" must not survive — a row showing the
  // previous address's latency is a lie with a number on it.
  const current = rowOf(await list(), 'alive');
  assert.equal(true, current.up, 'precondition: it has an observation');
  const r = await api('/v1/consoles/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version, url: `http://127.0.0.1:${hangPort}/` }),
  });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(null, r.body.up, 'the row is back to never-observed');
  assert.equal(null, r.body.httpStatus);
  assert.equal(null, rowOf(await list(), 'alive').up);
});
