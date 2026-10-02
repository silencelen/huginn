'use strict';
// lib/apps.js — the app-ADDRESS model after the 2026-10-02 breaker round
// (appd 3.9.1). Every case here was reproduced against 3.9.0 by a blind breaker
// and confirmed by a skeptic; each test names the symptom it was found as.
//
// Pure lib, no daemon. Probes go through an injected `fetch` so a test can say
// exactly which addresses answer — and can SEE which ones were dialled, which is
// the whole of the other-host finding.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const appsLib = require('../lib/apps');

function tmpDir(tag) { return fs.mkdtempSync(path.join(os.tmpdir(), `apps-addr-${tag}-`)); }

/** A fetch that answers 200 for the hosts in `up`, refuses the rest, and records every URL. */
function fakeFetch(up, opts = {}) {
  const seen = [];
  const f = async (url) => {
    seen.push(url);
    if (opts.delayMs) await new Promise((r) => setTimeout(r, opts.delayMs));
    const host = new URL(url).hostname.replace(/^\[|\]$/g, '');
    if (up.includes(host)) return { status: 200, body: null };
    const e = new TypeError('fetch failed');
    e.cause = { code: 'ECONNREFUSED' };
    throw e;
  };
  f.seen = seen;
  return f;
}

function mkStore(dir, extra = {}) {
  const s = appsLib.createStore({ dir, icons: false, hostAddr: '', timeoutMs: 500, ...extra });
  s.stop();
  return s;
}

// ------------------------------------------------- an address is a bare host

test('an address with a path, fragment, query, userinfo or port is refused, not spliced into the probe URL', () => {
  // 2026-10-02: '127.0.0.3#' was stored and probed at http://127.0.0.3#:P/, which
  // dialled port 80 — a decoy there made the row pass; '127.0.0.3/admin/reboot?x#'
  // made every sweep GET a caller-chosen path.
  for (const bad of ['127.0.0.2/', '127.0.0.3#', 'foo?bar', '10.1.1.1/x#', 'a@10.0.0.1', '10.0.0.1:80',
    '127.0.0.3/admin/reboot?confirm=1#', 'huginn\\x', '[::1]:80', '10.0.0.1 10.0.0.2']) {
    assert.equal('', appsLib.normalizeAddr(bad), `refused: ${JSON.stringify(bad)}`);
  }
  const bad = appsLib.add([], { name: 'X', url: 'http://127.0.0.1:8088/', addresses: ['127.0.0.3#'] }, 100);
  assert.equal(400, bad.status, 'a row carrying one is a 400');
});

test('addresses are canonical: one spelling per address, names in their ASCII form', () => {
  // 2026-10-02: fd00::6 and fd00:0:0:0:0:0:0:6 were two required addresses, each probed.
  assert.equal('fd00::6', appsLib.normalizeAddr('fd00:0:0:0:0:0:0:6'));
  assert.equal('fd00::6', appsLib.normalizeAddr('[FD00::6]'));
  assert.equal('127.0.0.1', appsLib.normalizeAddr('::ffff:7f00:1'), 'the hex v4-mapped form too');
  assert.equal('127.0.0.1', appsLib.normalizeAddr('::ffff:127.0.0.1'));
  assert.equal('xn--vil-9la', appsLib.normalizeAddr('évil'), 'an IDN label is stored as what DNS will be asked');
  assert.deepEqual(['fd00::6'], appsLib.cleanAddrs(['fd00::6', 'fd00:0:0:0:0:0:0:6', '[FD00::6]']));
});

test('a dotted name passes exactly when the app-URL host rule passes it', () => {
  // 2026-10-02: huginn.tail1234.ts.net was accepted, huginn.jnet.ad silently
  // dropped. The rule is the URL rule's, said once.
  assert.equal('huginn.tail1234.ts.net', appsLib.normalizeAddr('HUGINN.tail1234.ts.net'));
  assert.equal('x.localhost', appsLib.normalizeAddr('x.localhost'));
  assert.equal('', appsLib.normalizeAddr('huginn.jnet.ad'));
});

test('the 8-address limit is counted after normalising, the way the store counts', () => {
  // 2026-10-02: nine typed entries that normalise to eight were refused with the
  // 8-address limit, while cleanAddrs of the same list stored exactly eight.
  const list = ['10.0.0.1', '10.0.0.2', '10.0.0.3', '10.0.0.4', '10.0.0.5', '10.0.0.6', '10.0.0.7', 'FD00::1', 'fd00::1'];
  assert.equal(8, appsLib.cleanAddrs(list).length, 'precondition');
  assert.equal(null, appsLib.addrsProblem(list));
  assert.match(appsLib.addrsProblem([...list, '10.0.0.9']), /8-address limit/);
  // The string form splits on any whitespace JS knows, NBSP included.
  assert.deepEqual(['10.0.0.1', '10.0.0.2'], appsLib.cleanAddrs('10.0.0.1 10.0.0.2'));
});

// ------------------------------------------------------- bodies

test('a JSON null (or any non-object) body is a 400, not a TypeError', () => {
  // 2026-10-02: POST/PATCH /v1/apps with body `null` answered 500 "something
  // went wrong on the host" and logged "Cannot read properties of null".
  for (const body of [null, 7, 'str', [], true]) {
    const a = appsLib.add([], body, 100);
    assert.equal(400, a.status, `add ${JSON.stringify(body)}`);
  }
  const rec = appsLib.add([], { name: 'X', url: 'http://127.0.0.1:8088/' }, 100).app;
  for (const body of [null, 7, [], true]) {
    const p = appsLib.patch([rec], rec.id, body);
    assert.equal(400, p.status, `patch ${JSON.stringify(body)}`);
  }
});

// ------------------------------------------------------- concurrent writes

test('overlapping adds and a delete during an add all stay applied', async () => {
  // 2026-10-02: three parallel POSTs all answered 201 and only the last survived;
  // a DELETE made while an add was probing was undone when the add saved.
  const dir = tmpDir('race');
  try {
    const store = mkStore(dir, { fetch: fakeFetch(['127.0.0.1'], { delayMs: 150 }) });
    store.list();   // seed
    const [a, b, c] = await Promise.all(['alpha', 'beta', 'gamma'].map((n) =>
      store.add({ name: n, url: 'http://127.0.0.1:9999/' })));
    assert.deepEqual([true, true, true], [a.ok, b.ok, c.ok]);
    const pending = store.add({ name: 'delta', url: 'http://127.0.0.1:9999/' });
    assert.equal(true, store.remove('armap').ok, 'a delete while delta probes');
    assert.equal(true, (await pending).ok);
    const ids = store.list().map((r) => r.id);
    for (const id of ['alpha', 'beta', 'gamma', 'delta']) assert.ok(ids.includes(id), `${id} kept: ${ids}`);
    assert.ok(!ids.includes('armap'), `the delete stays applied: ${ids}`);

    const twice = await Promise.all([1, 2].map(() => store.add({ name: 'same', url: 'http://127.0.0.1:9999/' })));
    assert.deepEqual([201, 409], twice.map((r) => (r.ok ? 201 : r.status)).sort(), 'two adds of one id: one wins, one is told');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------- an unreadable store

test('a consoles.json that does not parse is kept, copied aside, never overwritten, and said', async () => {
  // 2026-10-02: one trailing comma made the next GET re-seed the store over the
  // owner's rows and routes, with no backup and no log line.
  const dir = tmpDir('corrupt');
  try {
    const bytes = '{"schema":1,"seeded":true,"consoles":[{"id":"mine1","name":"Mine","url":"http://127.0.0.1:1/"},]}';
    fs.writeFileSync(appsLib.storePath(dir), bytes);
    const lines = [];
    const store = mkStore(dir, { log: (l) => lines.push(l), fetch: fakeFetch(['127.0.0.1']) });
    store.noteClientAddress('127.0.0.1', '127.0.0.1');   // a request arrives first: no flush may land
    assert.throws(() => store.list(), (e) => e.status === 503 && /does not parse/.test(e.message));
    assert.throws(() => store.remove('mine1'), (e) => e.status === 503);
    await assert.rejects(store.add({ name: 'n', url: 'http://127.0.0.1:2/' }), (e) => e.status === 503);
    const r = store.noteReportedRoutes(['127.0.0.2'], 'phone');
    assert.equal(true, r.ok, 'a report is still taken in memory');
    assert.equal(bytes, fs.readFileSync(appsLib.storePath(dir), 'utf8'), 'the owner\'s bytes are untouched');
    const copies = fs.readdirSync(dir).filter((f) => f.startsWith(`${appsLib.STORE_NAME}.unreadable-`));
    assert.equal(1, copies.length, `one copy aside: ${fs.readdirSync(dir)}`);
    assert.equal(bytes, fs.readFileSync(path.join(dir, copies[0]), 'utf8'));
    assert.ok(lines.some((l) => /consoles\.json does not parse/.test(l)), lines.join(' | '));

    // Fixed by hand: the list comes back, with the owner's row, not the seeds.
    fs.writeFileSync(appsLib.storePath(dir), bytes.replace('},]', '}]'));
    assert.deepEqual(['mine1'], store.list().map((x) => x.id));
    assert.ok(store.requiredAddresses().includes('127.0.0.2'), 'and what was reported meanwhile survived');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------- reported routes

test('a report REPLACES that client\'s own routes: a removed route is withdrawn at once', () => {
  // 2026-10-02: PUT /v1/apps/routes only ever added; an unpinned route stayed
  // required of every app (every add 422) for 30 days, with no way to remove it.
  const dir = tmpDir('replace');
  try {
    const store = mkStore(dir);
    store.list();
    let r = store.noteReportedRoutes(['127.0.0.2', '127.0.0.3'], 'fold');
    assert.deepEqual(['127.0.0.2', '127.0.0.3'], r.fresh);
    r = store.noteReportedRoutes(['127.0.0.2'], 'fold');
    assert.deepEqual(['127.0.0.3'], r.gone);
    assert.deepEqual(['127.0.0.1', '127.0.0.2'], store.requiredAddresses());

    // Another device still reporting a route keeps it required.
    store.noteReportedRoutes(['127.0.0.2'], 'desk');
    store.noteReportedRoutes([], 'fold');
    assert.deepEqual(['127.0.0.1', '127.0.0.2'], store.requiredAddresses(), 'desk still pins it');
    store.noteReportedRoutes([], 'desk');
    assert.deepEqual(['127.0.0.1'], store.requiredAddresses(), 'an empty report clears that client');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('one client cannot push out another client\'s routes; the cap is per client', () => {
  // 2026-10-02: a client reporting 32 addresses evicted the phone's real route
  // (global oldest-first eviction at 32).
  const dir = tmpDir('cap');
  try {
    const store = mkStore(dir);
    store.list();
    store.noteReportedRoutes(['127.0.0.2'], 'phone');
    const flood = store.noteReportedRoutes(Array.from({ length: 32 }, (_, i) => `10.200.0.${i + 1}`), 'flooder');
    assert.equal(false, flood.ok);
    assert.equal(400, flood.status);
    assert.match(flood.error, new RegExp(`${appsLib.MAX_ROUTES_PER_CLIENT}`));
    assert.ok(store.requiredAddresses().includes('127.0.0.2'), 'the phone\'s route is untouched');
    // Many clients: the oldest CLIENT goes, never a slice of the others.
    for (let i = 0; i < appsLib.MAX_REPORTERS + 2; i++) store.noteReportedRoutes(['127.0.0.9'], `c${i}`);
    const by = store.reportedRoutes().find((e) => e.addr === '127.0.0.9').by;
    assert.ok(by.length <= appsLib.MAX_REPORTERS, `by is bounded: ${by.length}`);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a route the host rule refuses is NAMED, and the valid rest of the report still applies', () => {
  // 2026-10-02: 'huginn.jnet.ad' vanished from a 200 with no word to anybody.
  // And the rest must still apply: a 3.9.0 client sends every pin unfiltered and
  // never reads the body, so an all-or-nothing refusal cost it every route.
  const dir = tmpDir('refuse');
  try {
    const store = mkStore(dir);
    store.list();
    store.noteReportedRoutes(['127.0.0.2'], 'fold');
    const r = store.noteReportedRoutes(['127.0.0.3', 'huginn.jnet.ad', '8.8.8.8'], 'fold');
    assert.equal(true, r.ok);
    assert.deepEqual(['huginn.jnet.ad', '8.8.8.8'], r.refused.map((x) => x.addr));
    assert.ok(r.refused.every((x) => typeof x.why === 'string' && x.why));
    assert.deepEqual(['127.0.0.1', '127.0.0.3'], store.requiredAddresses(), 'the valid host replaced the old set');
    assert.equal(true, store.noteReportedRoutes(['huginn.tail1234.ts.net'], 'fold').ok, 'a ts.net name is a route');
    // The reporter id is cleaned of anything that is not printable ASCII.
    store.noteReportedRoutes(['127.0.0.4'], 'a\tb<script>');
    assert.ok(store.reportedRoutes().some((e) => e.by.includes('ab<script>')), JSON.stringify(store.reportedRoutes()));
    assert.match(appsLib.reportedClientId('\u0001\u0002'), /^a client$/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a route reported before the store exists survives a restart', () => {
  // 2026-10-02: the first list seeded the file without reportedAddresses, so a
  // daemon restart before the next flush forgot the route.
  const dir = tmpDir('early');
  try {
    const first = mkStore(dir);
    first.noteReportedRoutes(['127.0.0.2'], 'fold');
    first.list();
    const again = mkStore(dir);
    assert.deepEqual(['127.0.0.1', '127.0.0.2'], again.requiredAddresses());
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a rollback to appd 3.8 cannot erase reported routes or a row\'s own addresses', async () => {
  // 2026-10-02: 3.8 rebuilds consoles.json from its own record shape and drops
  // `addresses` and `reportedAddresses` on its first request; after rolling
  // forward the routes and every "Also check from" list were gone for good.
  const dir = tmpDir('rollback');
  try {
    const store = mkStore(dir, { fetch: fakeFetch(['127.0.0.1', '127.0.0.5']) });
    store.list();
    store.noteReportedRoutes(['127.0.0.5'], 'fold');
    const added = await store.add({ name: 'Fixture', url: 'http://127.0.0.1:9999/', addresses: ['127.0.0.5'] });
    assert.equal(true, added.ok, JSON.stringify(added));

    // What 3.8 writes back: its own whitelist of fields.
    const raw = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    const as38 = {
      schema: 1, seeded: true, clientAddresses: raw.clientAddresses,
      consoles: raw.consoles.map(({ addresses, ...rest }) => rest),
    };
    fs.writeFileSync(appsLib.storePath(dir), JSON.stringify(as38));

    const forward = mkStore(dir);
    assert.deepEqual(['127.0.0.5'], forward.get('fixture').addresses, 'the row\'s own list is back');
    assert.deepEqual(['127.0.0.1', '127.0.0.5'], forward.requiredAddresses(), 'and so is the route');

    // A row 3.8 deleted and re-added under the same id does not inherit the old list.
    const readded = { ...as38, consoles: as38.consoles.map((c) => (c.id === 'fixture' ? { ...c, addedAt: c.addedAt + 5 } : c)) };
    fs.writeFileSync(appsLib.storePath(dir), JSON.stringify(readded));
    assert.deepEqual([], mkStore(dir).get('fixture').addresses);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('fields this version does not know survive its rewrite of the store', () => {
  const dir = tmpDir('unknown');
  try {
    mkStore(dir).list();
    const raw = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    raw.fromTheFuture = { keep: true };
    raw.consoles[0].futureField = 7;
    fs.writeFileSync(appsLib.storePath(dir), JSON.stringify(raw));
    const s = mkStore(dir);
    s.remove(raw.consoles[1].id);
    const after = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    assert.deepEqual({ keep: true }, after.fromTheFuture);
    assert.equal(7, after.consoles[0].futureField);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------- an app on another host

test('an app on ANOTHER host is checked at its own address, never at this host\'s loopback', async () => {
  // 2026-10-02: an app at another LAN host passed because an UNRELATED local
  // service answered on 127.0.0.1 at the same port; the real app was never asked.
  const dir = tmpDir('remote');
  try {
    const lies = fakeFetch(['127.0.0.1']);   // only the local decoy answers
    const store = mkStore(dir, { fetch: lies, hostAddr: '192.168.2.117' });
    store.list();
    store.noteReportedRoutes(['192.168.2.117'], 'fold');
    const r = await store.add({ name: 'Elsewhere', url: 'http://192.168.7.50:8088/' });
    assert.equal(false, r.ok);
    assert.equal(422, r.status);
    assert.ok(!lies.seen.some((u) => u.includes('127.0.0.1')), `loopback was never dialled: ${lies.seen}`);
    assert.deepEqual(['192.168.7.50'], r.reachable.addresses.map((a) => a.addr));
    assert.deepEqual([], r.reachable.fix, 'no rebind lines for a unit on a machine huginn does not run');
    assert.match(r.error, /another host/);

    const honest = mkStore(tmpDir('remote2'), { fetch: fakeFetch(['192.168.7.50']), hostAddr: '192.168.2.117' });
    const ok = await honest.add({ name: 'Elsewhere', url: 'http://192.168.7.50:8088/' });
    assert.equal(true, ok.ok, JSON.stringify(ok));
    assert.match(ok.reachable.note, /another host/);

    // This host's OWN address is local: substituted and required as before.
    const local = await mkStore(tmpDir('remote3'), { fetch: fakeFetch(['127.0.0.1', '192.168.2.117']), hostAddr: '192.168.2.117' })
      .add({ name: 'Here', url: 'http://192.168.2.117:8088/' });
    assert.equal(true, local.ok);
    assert.ok(local.reachable.addresses.some((a) => a.addr === '127.0.0.1' && a.required));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a verdict with nothing but loopback required says so instead of vouching for devices', async () => {
  // 2026-10-02: with no route reported, "reachable from your devices" meant
  // "answers on loopback" — and a phone that arrived elsewhere could not open it.
  const r = await appsLib.reachabilityProbe({ url: 'http://127.0.0.1:9/' },
    { required: ['127.0.0.1'], advisory: ['127.0.0.2'] }, { fetch: fakeFetch(['127.0.0.1']) });
  assert.equal(true, r.ok);
  assert.match(r.note, /only loopback is required/);
  assert.match(r.note, /also not answering at 127\.0\.0\.2/);
});

test('a seed row is this host\'s app wherever its URL points; a lookalike on another port is not', () => {
  // 2026-10-02: the live seeds carry the retired tailnet IP 100.97.198.90.
  assert.equal(true, appsLib.isSeedRow({ id: 'armap', url: 'http://100.97.198.90:8088/' }));
  assert.equal(false, appsLib.isSeedRow({ id: 'armap', url: 'http://192.168.7.50:9999/' }));
  assert.equal(false, appsLib.isSeedRow({ id: 'other', url: 'http://100.97.198.90:8088/' }));
  const dir = tmpDir('seedlocal');
  try {
    const store = mkStore(dir);
    store.list();
    const plan = store.addressSet({ id: 'jtyper', url: 'http://100.97.198.90:8091/', addresses: [] });
    assert.equal(undefined, plan.remote, 'judged as this host, by loopback and reported routes');
    assert.ok(plan.required.includes('127.0.0.1'));
    const far = store.addressSet({ id: 'x', url: 'http://100.97.198.90:8091/', addresses: [] });
    assert.equal('100.97.198.90', far.remote, 'the same URL on a non-seed row is somebody else\'s');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
