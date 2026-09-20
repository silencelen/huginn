'use strict';
// The apps routes: a hand-curated registry of the pages this host makes and
// serves itself, with a liveness probe, a reachability probe and a favicon —
// and `/v1/consoles*` alongside as an alias for one release (decision 56).
//
// THE PROMISE UNDER TEST: a list you can trust. Every case here is a way it
// stops being trustworthy while still rendering — a row that says "up" because
// the probe behind it has not come back yet, a row that says "up" to a phone
// that cannot open it, an edit that silently loses to another client's, an
// address that was judged and then stored as something else, and root commands
// that drift from the machine they name.
//
// SAFETY, and why this suite PRE-WRITES the store file: the seed list is real.
// `http://huginn:8088/` and its three siblings are live services on this host,
// so a suite that let the daemon seed itself would have its background sweep
// GET the operator's actual armap, jtyper, board view and BTC sim. Instead the
// store is written before the daemon starts, pointing at fixture servers this
// file owns on ephemeral ports. Seeding itself is covered in apps.test.js,
// which needs no sockets for it.
//
// ⚠ NOTHING HERE MAY ASSUME A SEED PORT IS DEAD ON LOOPBACK. Two of the four
// rows below exist to prove the D10 re-pointing, which lands them on
// 127.0.0.1:<seed port> by construction — and once the retrofit these very fix
// lines ask for rebinds a unit to 0.0.0.0, that address ANSWERS. An assertion
// that something does not answer there is an assertion about how far the
// operator has got with an unrelated job; [DEAD_PORT] is where those belong.
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
const appsLib = require('../lib/apps');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. See the full table
// in routes-scratchpads.test.js. Wave 3 owns 10650-10749:
//
//   routes-projects     10650 + pid%50   -> 10650-10699
//   routes-apps         10700 + pid%50   -> 10700-10749   (this file)
//   routes-apps-icons   11500 + pid%25   -> 11500-11524
//   routes-apps-reach   11525 + pid%25   -> 11525-11549
//
// Only the DAEMON takes a number from the block. The fixture servers below bind
// port 0 and are told their port by the kernel, which is one fewer range to
// collide with.
const PORT = 10700 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

// ⚠ AND ONE FIXED PORT OUTSIDE THE TABLE: 8088, on LOOPBACK. The seed's ports
// are not negotiable — they are armap, the trainer, the board view and the sim
// — so proving a re-pointed seed row can actually be probed means listening on
// one of them at the address the daemon binds. 127.0.0.1:8088 is free on this
// host for exactly the reason D10 exists: the real armap is on the TAILNET
// address only.
const SEED_PORT = 8088;

// ⚠⚠ AND ONE PORT THAT MUST STAY DEAD. A row that proves "the sweep MARKS an
// unreachable app" needs somewhere nothing answers, and a SEED port is no longer
// that place: the retrofit these fix lines ask for binds those units to 0.0.0.0,
// which covers every loopback address, so 127.0.0.1:8092 went from refusing to
// answering the day somebody ran the remedy. Port 9 is discard — reserved,
// unbindable without privilege, and refused instantly rather than timing out —
// and it is the same stand-in apps.test.js uses for "nothing is there".
const DEAD_PORT = 9;
require('./retry-fetch');

// Private tmux socket shared with the daemon under test, so nothing it does at
// startup surfaces in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, dataDir, token, daemon;
let okServer, hangServer, seedServer, okPort, hangPort;

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
  const { status, body } = await api('/v1/apps');
  assert.equal(200, status);
  return body;
}

function rowOf(body, id) {
  return (body.apps || []).find((c) => c.id === id) || null;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-apps-'));
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

  // A stand-in for armap, on the seed's own port at the address this daemon
  // binds, so a re-pointed seed row has something real to answer it.
  //
  // ⚠ AND IT IS ALLOWED TO LOSE THE RACE. The moment armap.service is rebound to
  // 0.0.0.0 — the remedy this feature prints — the real armap holds this port on
  // every address and the stand-in cannot have it. That is not a failure: what
  // the two tests below need is that SOMETHING answers on 127.0.0.1:8088, and
  // the thing that took the port is the thing the row names. Any other error is
  // still thrown, because it means something unexplained holds the port.
  seedServer = http.createServer((req, res) => { res.writeHead(200, { 'content-type': 'text/plain' }); res.end('armap'); });
  await new Promise((resolve, reject) => {
    seedServer.once('error', (e) => {
      if (e.code === 'EADDRINUSE') { seedServer = null; resolve(); return; }
      reject(new Error(`could not listen on 127.0.0.1:${SEED_PORT} (${e.code}) — something else holds it; `
        + `find it with: ss -ltnp | grep ${SEED_PORT}`));
    });
    seedServer.listen(SEED_PORT, '127.0.0.1', resolve);
  });

  // The store, written before the daemon reads it. See the SAFETY note above.
  fs.writeFileSync(path.join(dataDir, appsLib.STORE_NAME), JSON.stringify({
    schema: appsLib.SCHEMA,
    seeded: true,
    consoles: [
      appsLib.buildRecord({ id: 'alive', name: 'A page that answers', kind: 'tool',
        url: `http://127.0.0.1:${okPort}/`, notes: 'fixture' }, 1789460000),
      appsLib.buildRecord({ id: 'hang', name: 'A page that hangs', kind: 'lab',
        url: `http://127.0.0.1:${hangPort}/`, notes: 'fixture' }, 1789460000),
      // What a daemon BEFORE D10 left on disk: the seed pinned to a name that
      // resolves to an interface none of the four units listen on. These are
      // safe to probe either way — nothing answers on 192.168.2.117 at these
      // ports — and the daemon must re-point them onto the address it binds.
      appsLib.buildRecord({ id: 'armap', name: 'Architecture map', kind: 'docs',
        url: 'http://huginn:8088/', notes: 'the old seed' }, 1789460000),
      appsLib.buildRecord({ id: 'board', name: 'PCB board view', kind: 'tool',
        url: 'http://huginn:8092/', notes: 'the old seed' }, 1789460000),
      // And one the OWNER re-pointed, which must come through untouched.
      appsLib.buildRecord({ id: 'jtyper', name: 'jtyper trainer', kind: 'lab',
        url: 'http://huginn:8091/owner-edited', notes: 'their edit' }, 1789460000),
      // A row that CANNOT be reached, whatever state the host is in — see
      // [DEAD_PORT]. It carries a unit so its fix lines are the exact ones.
      appsLib.buildRecord({ id: 'stale', name: 'A page that moved', kind: 'tool',
        url: `http://127.0.0.1:${DEAD_PORT}/`, unit: 'stale.service', notes: 'fixture' }, 1789460000),
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
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? The port formula gives few
  // slots, and one leaked by an earlier run — a test process killed before
  // after() could fire — sits on one and refuses OUR token. /v1/ping is
  // authenticated like every other route, so the start loop above never sees
  // its 200 and spins its whole cap, and then every call below 401s: a wall of
  // `401 unauthorized` that reads like a code bug and is not one. So ask an
  // AUTHENTICATED question before trusting the port, and say plainly what is
  // wrong: `ss -ltnp | grep <port>`, then kill it.
  const own = await api('/v1/apps');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (okServer) okServer.close();
  if (hangServer) hangServer.close();
  if (seedServer) seedServer.close();
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------------ the probe

test('the list route exists, which is how a client knows the feature is here', async () => {
  // Both clients probe this once per connection and hide the whole Consoles
  // surface on a 404, rather than showing a door that leads to an error (the
  // archive/scratchpads precedent). "200 with a list" is a contract.
  const body = await list();
  assert.ok(Array.isArray(body.apps));
  assert.equal(appsLib.MAX_APPS, body.max, 'the cap is on the wire, so a client can say what it is');
  assert.deepEqual(appsLib.KINDS, body.kinds, 'and the kind vocabulary, so the picker is not a second copy');
  // ⚠ THE APPROVAL CARD IS GONE FROM THE LIST BODY (decision 55). What is left
  // of the marker is one boolean; the remedy itself rides the row that needs it.
  assert.equal(false, body.retrofitApplied, 'the marker file does not exist, and the daemon never creates it');
  assert.ok(!('approval' in body), 'the card left the page — fix lines are per row now');
  assert.ok(!('reachableFrom' in body), 'and the one-word caveat with it');
  // The addresses every app on this host has to answer on, so a client can say
  // what a refusal was measured against instead of showing one with no subject.
  assert.ok(Array.isArray(body.clientAddresses));
  assert.ok(body.clientAddresses.includes('127.0.0.1'),
    `this suite reached the daemon on 127.0.0.1, so it is in the set: ${JSON.stringify(body.clientAddresses)}`);
});

test('the list says WHO arrived on each address, which is what a -source has to be', async () => {
  // ⚠ ADDITIVE, AND THE OTHER HALF OF `clientAddresses`. The arrival addresses
  // say what the probe checked against; these say who the daemon has actually
  // seen on each of them — the only thing it knows that can honestly go after
  // `-source` in a rule on heimdall. A client can show it; nothing has to.
  const body = await list();
  assert.ok(body.clientRemotes && typeof body.clientRemotes === 'object' && !Array.isArray(body.clientRemotes),
    `a map of arrival -> clients: ${JSON.stringify(body.clientRemotes)}`);
  assert.deepEqual(['127.0.0.1'], body.clientRemotes['127.0.0.1'],
    `this suite dials 127.0.0.1 from 127.0.0.1: ${JSON.stringify(body.clientRemotes)}`);
  for (const addr of body.clientAddresses) {
    assert.ok(Array.isArray(body.clientRemotes[addr]), `every arrival has an entry, even an empty one: ${addr}`);
  }

  // ⚠ NOT ON THE ALIAS. `/v1/consoles` answers the 3.4 body exactly (decision
  // 56), and a field invented in 3.5.2 is not in it.
  const alias = await api('/v1/consoles');
  assert.ok(!('clientRemotes' in alias.body), 'the alias is a frozen shape, not a second copy of the new one');
});

test('/v1/consoles answers the 3.4 BODY, key for key against a capture of the old contract', async () => {
  // ⚠⚠ DECISION 56, AND THE WHOLE POINT OF KEEPING THE PATH. An un-updated app
  // 3.5.x or desktop 1.5.x still draws its Consoles page off this route. If the
  // alias answered the NEW body, `consoles` would have become `apps` underneath
  // those clients and every one of them would decode an EMPTY list — a feature
  // that had silently lost its contents, which is worse than the 404 the alias
  // exists to avoid, because a 404 at least hides the surface.
  //
  // The reference is the body CAPTURED FROM THE OLD CONTRACT — the same fixture
  // the Kotlin side decodes in its own tests. Nothing in this daemon can run the
  // 3.4 code any more, so a shape assertion against the module's own constants
  // would only prove the module agrees with itself.
  const captured = JSON.parse(fs.readFileSync(
    path.join(__dirname, '..', '..', '..', 'mobile', 'app', 'src', 'test', 'resources', 'consoles.json'), 'utf8'));

  const alias = await api('/v1/consoles');
  assert.equal(200, alias.status);
  assert.deepEqual(Object.keys(captured), Object.keys(alias.body),
    'the list body, key for key and in order — a set comparison would pass a body that had grown a field');
  assert.ok(Array.isArray(alias.body.consoles) && alias.body.consoles.length, 'and the rows are under `consoles`');
  assert.deepEqual(Object.keys(captured.consoles[0]), Object.keys(alias.body.consoles[0]),
    'and every row, key for key');

  // The list-level words the old copy hangs on, and the card that is gone.
  assert.equal('host', alias.body.reachableFrom);
  assert.equal(appsLib.PROBE_INTERVAL_MS, alias.body.probeIntervalMs);
  // ⚠ NULL, NOT A SYNTHESISED CARD. Decision 55 deleted it; a daemon that kept
  // inventing one would hand an old client four root commands computed from a
  // belief this version no longer holds. `ConsoleApproval?` is nullable at
  // desktop-v1.5.1, so the card simply does not draw.
  assert.equal(null, alias.body.approval);

  // ⚠ THE NEW FIELDS ARE DROPPED, not merely ignored. The old clients are
  // `ignoreUnknownKeys = true` and would tolerate them; "the alias answers the
  // 3.4 body" is only checkable if it is exactly true.
  for (const gone of ['unit', 'icon', 'iconAt', 'reachable']) {
    assert.ok(!(gone in alias.body.consoles[0]), `${gone} is not a field the 3.4 contract has`);
  }
  assert.ok(!('apps' in alias.body) && !('retrofitApplied' in alias.body)
    && !('clientAddresses' in alias.body) && !('clientRemotes' in alias.body));

  // The same rows, the same order, under the other name.
  const fresh = await api('/v1/apps');
  assert.deepEqual(fresh.body.apps.map((a) => a.id), alias.body.consoles.map((c) => c.id));
});

test('every id-bearing route under the alias answers, in the 3.4 shape', async () => {
  const probe = await api('/v1/consoles/armap/probe', { method: 'POST' });
  assert.equal(200, probe.status, JSON.stringify(probe.body));
  assert.equal('armap', probe.body.id);
  assert.deepEqual(appsLib.LEGACY_ROW_FIELDS.concat('reachableFrom'), Object.keys(probe.body),
    'a single-row answer is the old row too, or an editor decodes one shape and a list another');
  assert.equal(404, (await api('/v1/consoles/nope')).status, 'and an unknown route under the alias is still 404');
  assert.equal(404, (await api('/v1/apps/nope')).status);
});

test('a 409 under the alias carries the row as `console`, not `app`', async () => {
  // ConsoleConflict at desktop-v1.5.1 is `{error, console}`. An editor that
  // collided has to show what it collided WITH, and a key it cannot read is a
  // refusal with nothing in it.
  const current = rowOf(await list(), 'alive');
  const r = await api('/v1/consoles/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version - 1, name: 'Stale' }),
  });
  assert.equal(409, r.status);
  assert.ok(r.body.console, 'the old key');
  assert.ok(!('app' in r.body), 'and only the old key');
  assert.equal(current.name, r.body.console.name);
  assert.deepEqual(appsLib.LEGACY_ROW_FIELDS.concat('reachableFrom'), Object.keys(r.body.console));

  // And the new path still uses the new one.
  const fresh = await api('/v1/apps/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version - 1, name: 'Stale' }),
  });
  assert.equal(409, fresh.status);
  assert.ok(fresh.body.app && !('console' in fresh.body));
});

test('a 422 under the alias is the same 422, because an old client shows the error string', async () => {
  // It has no field for `reachable` and ignores it — but the sentence names the
  // addresses that failed, so the refusal still explains itself on a client that
  // predates the reason for it.
  const r = await api('/v1/consoles', {
    method: 'POST',
    timeoutMs: 20_000,
    body: JSON.stringify({ name: 'Old client add', url: `http://127.0.0.1:${hangPort}/` }),
  });
  assert.equal(422, r.status, JSON.stringify(r.body));
  assert.ok(/does not answer/.test(r.body.error), r.body.error);
  assert.equal(null, rowOf(await list(), 'old-client-add'), 'and nothing was stored');
});

test('every row carries the probe fields, with null where there has been no observation', async () => {
  const body = await list();
  const row = rowOf(body, 'alive');
  assert.ok(row, 'the store written before startup is the store the daemon read');
  for (const field of ['id', 'name', 'url', 'kind', 'notes', 'unit', 'addedAt', 'version',
    'up', 'lastProbeAt', 'latencyMs', 'httpStatus', 'icon', 'iconAt', 'reachable']) {
    assert.ok(field in row, `${field} must be present on every row`);
  }
  assert.ok(!('reachableFrom' in row), 'superseded by reachable.addresses, which says strictly more');
  assert.equal('boolean', typeof row.icon);
  // A definite value, always — a row with no icon says 0 rather than omitting
  // the key, so a client never has to tell "missing" from "never".
  assert.equal('number', typeof row.iconAt);
  assert.equal(0, row.iconAt, 'nothing has been fetched for this row');
  for (const field of ['ok', 'checkedAt', 'addresses', 'fix']) {
    assert.ok(field in row.reachable, `reachable.${field} must be present on every row`);
  }
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

// ------------------------------------------------------- the seeded addresses

test('the rows the old seed wrote are re-pointed at the address this daemon binds', async () => {
  // ⚠ D10 AT THE ROUTE. `http://huginn:8088/` resolves to this host's LAN
  // address; the four seeded units bind its TAILNET address, so the old seed
  // named an interface nothing listens on and every row read "not answering
  // from the host" forever. This daemon binds 127.0.0.1 (HUGINN_APPD_BIND in
  // `before`), so that is where its seed rows belong.
  const body = await list();
  assert.equal(`http://127.0.0.1:${SEED_PORT}/`, rowOf(body, 'armap').url);
  assert.equal('http://127.0.0.1:8092/', rowOf(body, 'board').url);
  assert.equal('http://huginn:8091/owner-edited', rowOf(body, 'jtyper').url,
    'a row the owner re-pointed is never moved, whatever it points at');
  // Both seed migrations have run on this store: the address moved on the two
  // rows still holding the old literal, and all three seed rows learned the
  // unit that serves them (they were written here without one, as a pre-3.5
  // daemon would have).
  assert.equal(3, rowOf(body, 'armap').version, 'the address moved and the unit landed, so the version moved twice');
  assert.equal(2, rowOf(body, 'jtyper').version,
    'a row the owner re-pointed still learns its unit — that is a fact about this host, not a choice about an address');
  assert.equal(1, rowOf(body, 'alive').version, 'and a row that is not one of the four is left entirely alone');
});

test('a re-pointed seed row can actually be probed, which is the entire point of moving it', async () => {
  const r = await api('/v1/apps/armap/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.up, 'the stand-in on the seeded address answered — at http://huginn:8088/ nothing ever would');
  assert.equal(200, r.body.httpStatus);
  assert.equal(true, rowOf(await list(), 'armap').up, 'and the list carries it');
});

// ------------------------------------ the retrofit marker and the row's own fix

test('the approval card is GONE and the marker is one boolean on the list', async () => {
  // Decision 55: the big card of root commands left the page. What replaces it
  // is per row, computed from what the probe saw, and asserted where the failing
  // rows are. The marker survives for the transition and nothing else.
  const body = await list();
  assert.ok(!('approval' in body), 'no card');
  assert.equal(false, body.retrofitApplied);
  assert.equal(false, fs.existsSync(path.join(dataDir, appsLib.REBIND_MARKER_NAME)),
    'and listing the apps did not create the marker either — it is the owner’s word');

  // The daemon reads it and never writes it, and it flips without a restart.
  fs.writeFileSync(path.join(dataDir, appsLib.REBIND_MARKER_NAME), '');
  try {
    assert.equal(true, (await list()).retrofitApplied);
  } finally {
    fs.unlinkSync(path.join(dataDir, appsLib.REBIND_MARKER_NAME));
  }
  assert.equal(false, (await list()).retrofitApplied);
});

test('a row that the sweep finds unreachable is MARKED with its own lines, and never deleted', async () => {
  // ⚠ DECISION 54 AT THE ROUTE, on rows that were on disk before the gate
  // existed. `stale` points at the discard port, so nothing answers there on any
  // host in any state, and this daemon's one client address is 127.0.0.1. The
  // row has to survive, marked.
  const before = (await list()).apps.length;
  const r = await api('/v1/apps/stale/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(false, r.body.reachable.ok, `nothing answers on ${DEAD_PORT} at this address`);
  assert.deepEqual(['127.0.0.1'], r.body.reachable.addresses.map((a) => a.addr));
  assert.ok(r.body.reachable.addresses[0].error, 'and it says why');
  assert.ok(r.body.reachable.checkedAt > 0, 'with a time it was checked');

  // ⚠ THE LINES SOMEBODY PASTES INTO A ROOT SHELL ON TWO MACHINES, literally.
  //
  // ⚠⚠ AND THE HEIMDALL HALF IS A COMMENT, NOT A RULE. The one address failing
  // here is LOOPBACK, which never crosses the veth chain — 117.fw has no say
  // over it and the rebind above is the entire remedy. The old code put
  // `-source 127.0.0.1` here, a rule that could never match, which is the defect
  // 3.5.2 removes in both its forms (that one, and naming the arrival address).
  assert.deepEqual([
    '# on huginn — 127.0.0.1 does not reach this app',
    'systemctl edit stale.service   # ExecStart: bind 0.0.0.0 instead of 127.0.0.1',
    'systemctl restart stale.service',
    `ss -ltn | grep :${DEAD_PORT}`,
    '# on heimdall — /etc/pve/firewall/117.fw',
    '# 127.0.0.1 passes on its own once the unit binds 0.0.0.0',
  ], r.body.reachable.fix);
  assert.ok(!r.body.reachable.fix.some((l) => l.startsWith('IN ACCEPT')),
    'no rule at all is better than one that cannot match');

  assert.equal(before, (await list()).apps.length, 'the row is still there — a failing app is marked, not removed');
  assert.equal(false, rowOf(await list(), 'stale').reachable.ok, 'and the list carries the mark');
});

test('the seeded rows name the unit that serves each one, so the fix line is exact', async () => {
  const body = await list();
  assert.equal('armap.service', rowOf(body, 'armap').unit);
  assert.equal('boardserver.service', rowOf(body, 'board').unit);
  assert.equal('', rowOf(body, 'alive').unit, 'and a row nobody named a unit for says so with a definite value');
});

test('an app that answers at every address huginn answers on is reachable, with no lines to show', async () => {
  const r = await api('/v1/apps/armap/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.reachable.ok, 'the stand-in on 127.0.0.1:8088 answers at the one address this daemon knows');
  assert.deepEqual([], r.body.reachable.fix, 'a row that passes carries no remedy — that was the old card’s whole problem');
});


// ------------------------------------------------------------ writing a row

test('an app that answers everywhere can be added, and it is in the list afterwards', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({
      name: 'Board view', url: `http://127.0.0.1:${okPort}/board`, kind: 'tool', notes: 'KiCad', unit: 'boardview.service',
    }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal('board-view', r.body.id, 'the id is derived from the name when none is given');
  assert.equal(1, r.body.version);
  assert.equal('boardview.service', r.body.unit);
  assert.equal(null, r.body.up, 'a brand new row has no observation, which is not the same as being down');
  // ⚠ IT PASSED THE PREREQUISITE, which is why it exists at all. `up` is still
  // null — the liveness probe has not run — and `reachable` is already true,
  // because the add would have been refused otherwise.
  assert.equal(true, r.body.reachable.ok);
  assert.ok(rowOf(await list(), 'board-view'));
});

test('an unknown kind is a 400 on the wire, before any probe runs (L2)', async () => {
  // ⚠ AND BEFORE THE NETWORK. The shape rules come first precisely so a body
  // that cannot be a row never costs a probe — this one names an address that
  // WOULD pass, so a 400 here also proves the order.
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Hologram', url: `http://127.0.0.1:${okPort}/holo`, kind: 'hologram' }),
  });
  assert.equal(400, r.status, JSON.stringify(r.body));
  assert.match(r.body.error, /kind is one of/, r.body.error);
  assert.equal(null, rowOf(await list(), 'hologram'), 'and nothing was written');
});

test('an app that does not answer at every known address is REFUSED with 422 and the lines that would fix it', async () => {
  // ⚠ DECISION 54. The shape is fine and the world is not: `hangPort` accepts
  // the connection and says nothing, so the address this daemon's clients
  // arrive on cannot reach it. A row that would have to be marked "needs
  // retrofit" the moment it landed is a row nobody should be able to create.
  const r = await api('/v1/apps', {
    method: 'POST',
    timeoutMs: 20_000,
    body: JSON.stringify({ name: 'Never answers', url: `http://127.0.0.1:${hangPort}/`, unit: 'wedged.service' }),
  });
  assert.equal(422, r.status, JSON.stringify(r.body));
  assert.ok(/does not answer/.test(r.body.error), r.body.error);
  assert.equal(false, r.body.reachable.ok, 'the refusal carries the measurement, not just a sentence');
  assert.deepEqual(['127.0.0.1'], r.body.reachable.addresses.map((a) => a.addr));
  assert.equal('timed out', r.body.reachable.addresses[0].error);
  assert.ok(r.body.reachable.fix.some((l) => l.startsWith('systemctl edit wedged.service')),
    `the remedy travels with the refusal: ${JSON.stringify(r.body.reachable.fix)}`);
  assert.equal(null, rowOf(await list(), 'never-answers'), 'and NOTHING was stored');
});

test('a unit name that is not a unit name is refused before anything is probed', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Sneaky unit', url: `http://127.0.0.1:${okPort}/`, unit: 'armap.service; rm -rf /' }),
  });
  assert.equal(400, r.status, 'a 400, not a 422 — the refusal is about what was typed');
  assert.equal(null, rowOf(await list(), 'sneaky-unit'));
});

test('an id that is already taken is a 409 carrying the row it collided with', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Board view', url: `http://127.0.0.1:${okPort}/other` }),
  });
  assert.equal(409, r.status, JSON.stringify(r.body));
  assert.equal('board-view', r.body.app.id, 'the existing row travels with the refusal');
});

test('there is no icon to serve until a probe has cached one', async () => {
  assert.equal(404, (await api('/v1/apps/armap/icon')).status);
  assert.equal(404, (await api('/v1/apps/nosuchapp/icon')).status, 'and an unknown id is the same 404');
});

test('an address with a username and password in it is refused at the route', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Sneaky', url: 'http://admin:hunter2@127.0.0.1:9/' }),
  });
  assert.equal(400, r.status);
  assert.equal(appsLib.REFUSED_USERINFO, r.body.error);
  assert.equal(null, rowOf(await list(), 'sneaky'), 'and nothing was stored');
});

test('a scheme that is not http or https is refused at the route', async () => {
  for (const url of ['file:///etc/shadow', 'javascript:alert(1)', 'huginn:8088']) {
    const r = await api('/v1/apps', { method: 'POST', body: JSON.stringify({ name: 'Bad scheme', url }) });
    assert.equal(400, r.status, `${url} must be refused`);
    assert.equal(appsLib.REFUSED_SCHEME, r.body.error);
  }
});

test('an address on the public internet is refused, because this daemon is the one that would fetch it', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Elsewhere', url: 'https://example.com/' }),
  });
  assert.equal(400, r.status);
  assert.equal(appsLib.REFUSED_HOST, r.body.error);
});

test('a rename persists, and takes the version with it', async () => {
  const before = rowOf(await list(), 'alive');
  const r = await api('/v1/apps/alive', {
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

test('PATCH {unit: ""} CLEARS the unit, and the row says so', async () => {
  // The client backlog asked whether this was even possible: `unit` is the one
  // field whose value ends up in a command line a person runs as root, so it is
  // REFUSED when malformed rather than coerced — and "" had to be proved to be
  // the empty case rather than a malformed one. It is: absent and empty both
  // mean "huginn does not know which unit serves this", and [fixLines] says so
  // in words instead of naming a unit that has moved.
  const start = rowOf(await list(), 'board-view');
  assert.equal('boardview.service', start.unit, 'it has one to clear');

  const r = await api('/v1/apps/board-view', {
    method: 'PATCH', body: JSON.stringify({ version: start.version, unit: '' }),
  });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal('', r.body.unit, 'cleared, not left alone and not refused');
  assert.equal(start.version + 1, r.body.version);

  const after = rowOf(await list(), 'board-view');
  assert.equal('', after.unit, 'and it survived the trip through the file');
  assert.equal(start.name, after.name, 'clearing one field touches nothing else');

  // Putting one back is the same call with a name in it.
  const back = await api('/v1/apps/board-view', {
    method: 'PATCH', body: JSON.stringify({ version: after.version, unit: 'boardview.service' }),
  });
  assert.equal(200, back.status, JSON.stringify(back.body));
  assert.equal('boardview.service', back.body.unit);
});

test('an edit against a stale version is 409 and carries the row it collided with', async () => {
  // Two clients on one registry is the normal case here — the desktop and the
  // phone are both polling. The loser has to be told what it lost to, or it
  // cannot show a person the difference. This is the saveScratchpad contract,
  // which both clients already adopt as an ANSWER rather than a throw.
  const current = rowOf(await list(), 'alive');
  const r = await api('/v1/apps/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version - 1, name: 'Stale' }),
  });
  assert.equal(409, r.status);
  assert.ok(r.body.app, 'the current row travels with the refusal');
  assert.equal(current.name, r.body.app.name);
  assert.equal(current.version, r.body.app.version);
  assert.equal(current.name, rowOf(await list(), 'alive').name, 'and the losing edit changed nothing');
});

test('a PATCH with no version at all is refused rather than applied blind', async () => {
  const r = await api('/v1/apps/alive', { method: 'PATCH', body: JSON.stringify({ name: 'No guard' }) });
  assert.equal(400, r.status);
});

test('deleting an app removes it, and deleting it again is a 404', async () => {
  const r = await api('/v1/apps/board-view', { method: 'DELETE' });
  assert.equal(200, r.status);
  assert.equal(true, r.body.ok);
  assert.equal(null, rowOf(await list(), 'board-view'), 'gone from the list');

  const again = await api('/v1/apps/board-view', { method: 'DELETE' });
  assert.equal(404, again.status, 'a second delete is not a second success');
});

test('an id that is not in the grammar cannot even reach a handler', async () => {
  // The id is a path segment in every route here, so the grammar is the route.
  const r = await api('/v1/apps/NOT_AN_ID', { method: 'DELETE' });
  assert.equal(404, r.status);
  const probe = await api('/v1/apps/nope/probe', { method: 'POST' });
  assert.equal(404, probe.status);
});

// ------------------------------------------------------------ probing for real

test('an on-demand probe answers with the status and how long it took', async () => {
  const r = await api('/v1/apps/alive/probe', { method: 'POST' });
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

test('an app that accepts the connection and never answers is DOWN, and the request still comes back', async () => {
  // ⚠ THE TEST THIS ROUTE EXISTS FOR. `fetch` has no default timeout. Without
  // the deadline in probeConsole this request never returns, the client's
  // spinner never stops, and one wedged listener holds a daemon connection open
  // for as long as the socket lives. A refused connection would fail fast and
  // prove nothing; this server completes the handshake and then says nothing.
  const started = Date.now();
  const r = await api('/v1/apps/hang/probe', { method: 'POST' });
  const took = Date.now() - started;
  assert.equal(200, r.status);
  assert.equal(false, r.body.up);
  assert.equal(null, r.body.httpStatus, 'nothing was answered, so there is no status to report');
  assert.ok(took < 8_000, `the probe came back in ${took}ms, near its own deadline rather than the client's`);
  assert.equal(false, rowOf(await list(), 'hang').up, 'and the list says so as well');
});

test('an app re-pointed somewhere else drops the observation it no longer describes', async () => {
  // `alive` has a real, successful probe on it by now. Point it at the hanging
  // server and the old "up, 200, 3 ms" must not survive — a row showing the
  // previous address's latency is a lie with a number on it.
  const current = rowOf(await list(), 'alive');
  assert.equal(true, current.up, 'precondition: it has an observation');
  const r = await api('/v1/apps/alive', {
    method: 'PATCH',
    body: JSON.stringify({ version: current.version, url: `http://127.0.0.1:${hangPort}/` }),
  });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(null, r.body.up, 'the row is back to never-observed');
  assert.equal(null, r.body.httpStatus);
  assert.equal(null, rowOf(await list(), 'alive').up);
});
