'use strict';
// The reachability PREREQUISITE at the route (decision 54): an app is added only
// once it answers on every address a client of this daemon arrives on, and a row
// that was already here and does not is marked rather than deleted.
//
// THE PROMISE UNDER TEST, in the owner's words: if a device can reach huginn to
// see the Apps page, it must be able to reach the app. Every case here is a way
// that stops being true while the row still says "up" — an app bound to one of
// this host's addresses and probed from that same one, an address the daemon
// learned last week and has not checked against since, a refusal with no subject.
//
// ⚠ HOW TWO ADDRESSES ARE ARRANGED WITHOUT OPENING A PORT TO THE NETWORK. A
// wildcard bind would put this test's daemon on the tailnet for the length of the
// run. Instead 127.0.0.2 — a second loopback address, local by construction on
// Linux — is SEEDED into the store's `clientAddresses`, which is exactly the
// state a daemon is in after a client arrived on an address it is not currently
// being dialled at. The fixture app then listens on 127.0.0.1 and, for the
// reachable case, on 127.0.0.2 as well: one port, two sockets, which is precisely
// the difference a rebind makes.
//
// SAFETY: every address here is loopback and every port is this file's own or in
// its block, so nothing can reach the operator's real armap, trainer, board view
// or sim — those are on the tailnet address only.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');
const crypto = require('node:crypto');
const appsLib = require('../lib/apps');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. The full table is in
// routes-scratchpads.test.js; the Apps rebrand owns 11500-11549:
//
//   routes-apps-icons   11500 + pid%25   -> 11500-11524
//   routes-apps-reach   11525 + pid%12   -> 11525-11536   (this file, the daemon)
//   routes-apps-reach   11537 + pid%12   -> 11537-11548   (this file, the two-address fixture)
//
// The two-address fixture cannot take an ephemeral port: it binds the SAME port
// twice, once per loopback address, so the number has to be chosen rather than
// handed out. The one-address fixture below binds port 0 like everything else.
const PORT = 11525 + (process.pid % 12);
const BOTH_PORT = 11537 + (process.pid % 12);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

// Private tmux socket shared with the daemon under test, so nothing it does at
// startup surfaces in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, dataDir, token, daemon;
let oneAddr, bothA, bothB, onePort;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    signal: AbortSignal.timeout(init.timeoutMs || 20_000),
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

function rowOf(body, id) { return (body.apps || []).find((a) => a.id === id) || null; }

/** The address set as it is on disk, which is the thing that survives a restart. */
function storedAddrs() {
  const raw = JSON.parse(fs.readFileSync(path.join(dataDir, appsLib.STORE_NAME), 'utf8'));
  return (raw.clientAddresses || []).map((e) => e.addr).sort();
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-apps-reach-'));
  dataDir = path.join(tmp, 'data');
  fs.mkdirSync(dataDir);
  fs.mkdirSync(path.join(tmp, 'state'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // The app that was never rebound: one address, which is the whole defect.
  oneAddr = http.createServer((req, res) => { res.writeHead(200, { 'content-type': 'text/plain' }); res.end('one'); });
  await new Promise((r) => oneAddr.listen(0, '127.0.0.1', r));
  onePort = oneAddr.address().port;

  // The app after the rebind: the same port, answering at both addresses.
  const serve = (req, res) => { res.writeHead(200, { 'content-type': 'text/plain' }); res.end('both'); };
  bothA = http.createServer(serve);
  bothB = http.createServer(serve);
  await new Promise((resolve, reject) => {
    bothA.once('error', (e) => reject(new Error(
      `could not listen on 127.0.0.1:${BOTH_PORT} (${e.code}) — find it with: ss -ltnp | grep ${BOTH_PORT}`)));
    bothA.listen(BOTH_PORT, '127.0.0.1', resolve);
  });
  await new Promise((resolve, reject) => {
    bothB.once('error', (e) => reject(new Error(
      `could not listen on 127.0.0.2:${BOTH_PORT} (${e.code}) — is 127.0.0.0/8 local on this host?`)));
    bothB.listen(BOTH_PORT, '127.0.0.2', resolve);
  });

  const now = Math.floor(Date.now() / 1000);
  fs.writeFileSync(path.join(dataDir, appsLib.STORE_NAME), JSON.stringify({
    schema: appsLib.SCHEMA,
    seeded: true,
    // What a daemon that has been running for a while knows: two addresses
    // clients arrived on, one of them not seen for longer than the TTL.
    // ⚠ AND WHO ARRIVED ON THEM. 192.168.2.131 is the shape this host actually
    // has: the Yggdrasil LAN gateway NATs every phone on the LAN side, so that
    // is the address the daemon sees on the far end of the socket and the only
    // one a firewall rule on heimdall could ever match.
    clientAddresses: [
      { addr: '127.0.0.2', lastSeenAt: now - 60, remotes: [{ addr: '192.168.2.131', lastSeenAt: now - 60 }] },
      { addr: '10.9.9.9',
        lastSeenAt: now - appsLib.CLIENT_ADDR_TTL_SEC - 3600,
        remotes: [{ addr: '192.168.2.44', lastSeenAt: now - 60 }] },
    ],
    consoles: [
      appsLib.buildRecord({ id: 'onebound', name: 'Bound to one address', kind: 'tool',
        url: `http://127.0.0.1:${onePort}/`, unit: 'onebound.service', notes: 'never rebound' }, 1789460000),
      appsLib.buildRecord({ id: 'rebound', name: 'Bound to both', kind: 'tool',
        url: `http://127.0.0.1:${BOTH_PORT}/`, unit: 'rebound.service', notes: 'rebound' }, 1789460000),
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
  for (let i = 0; i < 300; i++) {
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
  for (const s of [oneAddr, bothA, bothB]) { if (s) s.close(); }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------ the address set

test('the daemon checks against the addresses clients arrive on, plus its own', async () => {
  const body = await list();
  // 127.0.0.1 is where this suite dials it and is also its bind, so it arrives
  // by both routes; 127.0.0.2 is the one a previous life learned.
  assert.deepEqual(['127.0.0.1', '127.0.0.2'], [...body.clientAddresses].sort(),
    `the set on the wire: ${JSON.stringify(body.clientAddresses)}`);
  // ⚠ AND THE SET FORGETS. A laptop that was on the LAN once must not make every
  // app on this host unaddable months later; 10.9.9.9 is past the TTL.
  assert.ok(!body.clientAddresses.includes('10.9.9.9'), 'an address nobody has arrived on for a week is gone');

  // ⚠ AND THE CLIENTS GO WITH IT. A remote is remembered FOR an arrival; when
  // the arrival expires there is no address left for its clients to be clients
  // of, and a stale one would come back as a firewall source months later.
  assert.deepEqual(['192.168.2.131'], body.clientRemotes['127.0.0.2'],
    `who the daemon has seen on 127.0.0.2: ${JSON.stringify(body.clientRemotes)}`);
  assert.deepEqual(['127.0.0.1'], body.clientRemotes['127.0.0.1'], 'and this suite, on the address it dials');
  assert.ok(!('10.9.9.9' in body.clientRemotes), 'the expired arrival took 192.168.2.44 with it');
});

test('an address a client actually arrives on is written down and survives a restart', async () => {
  // The set is not an observation about an app — it is a fact about this host's
  // clients, accumulated over days. A daemon that forgot it on every restart
  // would allow adds after a reboot that it refused an hour earlier.
  assert.ok(storedAddrs().includes('127.0.0.1'),
    `this suite arrived on 127.0.0.1, so the store has it: ${JSON.stringify(storedAddrs())}`);
  assert.ok(!storedAddrs().includes('10.9.9.9'), 'and the expired one was dropped on the way through');
});

// ------------------------------------------------------- existing rows

test('a row bound to one of this host’s addresses is UNREACHABLE, with the lines that would fix it', async () => {
  const r = await api('/v1/apps/onebound/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.up, '⚠ THE LIE THIS FEATURE COULD TELL: the daemon fetched it perfectly well');
  assert.equal(false, r.body.reachable.ok, 'and a device on the other address still cannot open it');

  const byAddr = Object.fromEntries(r.body.reachable.addresses.map((a) => [a.addr, a]));
  assert.equal(true, byAddr['127.0.0.1'].ok, 'EVERY address is asked, not just the first that fails');
  assert.equal(false, byAddr['127.0.0.2'].ok);
  assert.equal('connection refused', byAddr['127.0.0.2'].error, 'and it says why');

  // ⚠⚠ AND THERE IS NO FIREWALL HALF. 127.0.0.2 is LOOPBACK: it never crosses
  // the veth chain, so 117.fw has no say over it and the rebind above is the
  // entire remedy. The old code put `-source 127.0.0.2` here — an arrival
  // address, one of THIS host's own, in a rule that could never match anything —
  // which is the defect 3.5.2 removed. 3.6.1 removed the other half: the header
  // naming another machine's root-owned file stood over a single line saying
  // there was nothing to put in it (r2 L3).
  assert.deepEqual([
    '# on huginn — 127.0.0.2 does not reach this app',
    'systemctl edit onebound.service   # ExecStart: bind 0.0.0.0 instead of 127.0.0.1',
    'systemctl restart onebound.service',
    `ss -ltn | grep :${onePort}`,
    '# 127.0.0.2 passes on its own once the unit binds 0.0.0.0',
  ], r.body.reachable.fix, 'the exact lines, for THIS row, against THIS address');
  assert.ok(!r.body.reachable.fix.some((l) => l.includes('117.fw')),
    'and no header over an empty section');
  assert.ok(!r.body.reachable.fix.some((l) => l.startsWith('IN ACCEPT')),
    'an arrival address is never a -source, and a loopback one wants no rule at all');
});

test('the same app after a rebind answers everywhere and carries no lines at all', async () => {
  const r = await api('/v1/apps/rebound/probe', { method: 'POST' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.reachable.ok);
  assert.deepEqual(['127.0.0.1', '127.0.0.2'], r.body.reachable.addresses.map((a) => a.addr).sort());
  assert.deepEqual([], r.body.reachable.fix, 'a row that passes shows nothing to copy');
});

test('the background sweep marks the failing row and NEVER removes it', async () => {
  // ⚠ DECISION 54's other half. The prerequisite gates ADDING; a row that
  // predates the gate, or that broke after passing it, is somebody's row.
  // Deleting it would be this feature destroying the registry it exists to keep.
  const body = await list();
  assert.equal(2, body.apps.length, 'both rows are still here');
  assert.equal(false, rowOf(body, 'onebound').reachable.ok, 'marked');
  assert.ok(rowOf(body, 'onebound').reachable.fix.length, 'with its remedy on it');
  assert.equal(true, rowOf(body, 'rebound').reachable.ok, 'and its neighbour is untouched');
});

// ------------------------------------------------------------- adding a row

test('adding an app that answers on only one of the addresses is 422, and nothing is stored', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Another one bound', url: `http://127.0.0.1:${onePort}/thing`, unit: 'other.service' }),
  });
  assert.equal(422, r.status, JSON.stringify(r.body));
  assert.equal(false, r.body.reachable.ok);
  assert.deepEqual(['127.0.0.2'], r.body.reachable.addresses.filter((a) => !a.ok).map((a) => a.addr));
  assert.ok(r.body.reachable.fix.includes('# 127.0.0.2 passes on its own once the unit binds 0.0.0.0'),
    `the refusal travels with the remedy, and for a loopback address that is the rebind alone: ${JSON.stringify(r.body.reachable.fix)}`);
  assert.ok(r.body.reachable.fix.some((l) => l.startsWith('systemctl edit other.service')),
    'which is step 1, named for this row');
  assert.equal(null, rowOf(await list(), 'another-one-bound'), 'and NOTHING was stored');
});

test('adding an app that answers everywhere is 201, already reachable', async () => {
  const r = await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Also rebound', url: `http://127.0.0.1:${BOTH_PORT}/other`, kind: 'tool' }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.reachable.ok, 'the add would have been refused otherwise, so this is never in doubt');
  assert.equal(null, r.body.up, 'and the LIVENESS probe has still not run — the two are different questions');
  assert.ok(rowOf(await list(), 'also-rebound'));
});

test('a refusal is never a delete: the row it collides with is not touched', async () => {
  const before = (await list()).apps.length;
  await api('/v1/apps', {
    method: 'POST',
    body: JSON.stringify({ name: 'Yet another', url: `http://127.0.0.1:${onePort}/x` }),
  });
  assert.equal(before, (await list()).apps.length);
});
