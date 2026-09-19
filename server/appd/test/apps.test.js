'use strict';
// lib/apps.js — the rules an app is stored, judged, probed and pictured by.
// (Called consoles until 3.5.0; decisions 53-56.)
//
// Every case here is a way this feature LIES while the row on screen still looks
// right: a probe that reports "up" because the request it is waiting on has not
// come back yet, an address that carries a password into a diagnostics bundle,
// a row that says "up" while the phone reading it cannot open the page, fix
// lines that have drifted from the machine they name, a favicon that is really
// 40 KB of somebody's SPA index.html, a version guard that silently takes the
// losing edit.
//
// Pure: no daemon, no routes. A handful of tests bind a REAL socket on an
// ephemeral port (`listen(0)`) because the one thing that cannot be faked is a
// server that accepts a connection and then says nothing — the whole point of
// the timeout. The route-level half is routes-apps.test.js, with
// routes-apps-icons.test.js and routes-apps-reach.test.js beside it.

const { test } = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const appsLib = require('../lib/apps');

/**
 * A test bound of its own, so a probe that never returns FAILS instead of
 * hanging. `node --test` has no default per-test timeout: without this, the
 * fail-first proof for the unbounded-probe case would wedge the runner rather
 * than going red, which is a worse test than none.
 */
function within(ms, promise, what) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${what} did not finish in ${ms}ms`)), ms).unref()),
  ]);
}

// ------------------------------------------------------------ the URL rule

test('an address carrying a username and password is refused', () => {
  // A credential in a field the diagnostics bundle prints, and there is no case
  // for one against a page on this host. RouteGuard refuses it on the daemon's
  // own address for exactly this reason; a console address is handed to a phone
  // browser as well, which would then store it.
  assert.equal(appsLib.REFUSED_USERINFO, appsLib.urlProblem('http://admin:hunter2@huginn:8088/'));
  assert.equal(appsLib.REFUSED_USERINFO, appsLib.urlProblem('https://admin@100.97.198.90:8092/board'));
  assert.equal(null, appsLib.urlProblem('http://huginn:8088/'), 'the same address without the credential is fine');
});

test('a scheme that is not http or https is refused, including this product’s own', () => {
  // `file://` would make the probe a local file read. `javascript:` and `data:`
  // are handed to a browser by the clients' open-in-browser handoff. And
  // `huginn:` is THIS PRODUCT'S deep-link scheme — the one defect D-E is about
  // ("don't know how to open the link 'huginn'"), which is why a bare
  // `huginn:8088` must be refused rather than read as host-and-port.
  for (const bad of [
    'file:///etc/passwd',
    'ftp://huginn/pub',
    'javascript:alert(1)',
    'data:text/html,<b>hi',
    'huginn:8088',
    'huginn:8088/board',
    '//huginn:8088/',
  ]) {
    assert.equal(appsLib.REFUSED_SCHEME, appsLib.urlProblem(bad), `${bad} must be refused`);
  }
});

test('the host must be one huginn cannot reach the public internet with — one case per class', () => {
  // The shape rule copied from the clients' RouteGuard.kt (see lib/apps.js).
  // A host LIST cannot survive a registry the owner edits, so this is a class
  // test and not a name test.
  const allowed = {
    loopback: 'http://127.0.0.1:8088/',
    'loopback by name': 'http://localhost:8088/',
    'loopback v6': 'http://[::1]:8088/',
    'lan 10': 'http://10.0.0.5:8088/',
    'lan 192.168': 'http://192.168.2.131:8088/',
    'lan 172.16-31': 'http://172.20.4.4:8088/',
    'tailnet cgnat': 'http://100.97.198.90:8088/',
    'tailnet magicdns': 'https://huginn.taildbeef.ts.net/',
    'mesh ula': 'http://[fd7a:115c:a1e0::1]:8088/',
    'single-label name': 'http://huginn:8088/',
  };
  for (const [why, url] of Object.entries(allowed)) {
    assert.equal(null, appsLib.urlProblem(url), `${why} (${url}) must be allowed`);
  }

  const refused = {
    'public v4': 'http://8.8.8.8/',
    'public name': 'http://example.com/',
    'public name over TLS': 'https://example.com/',      // ⚠ stricter than RouteGuard, on purpose
    'a subdomain that merely looks internal': 'http://huginn.example.com/',
    'link-local': 'http://169.254.1.1/',
    'public v6': 'http://[2606:4700::1111]/',
    '172.32 is not private': 'http://172.32.0.1/',
    '100.128 is not the CGNAT block': 'http://100.128.0.1/',
  };
  for (const [why, url] of Object.entries(refused)) {
    assert.equal(appsLib.REFUSED_HOST, appsLib.urlProblem(url), `${why} (${url}) must be refused`);
  }
});

test('https does not open the host up, unlike the clients’ route guard', () => {
  // The deliberate divergence, asserted so nobody "fixes" it back. RouteGuard
  // lets https go anywhere because the risk it guards is a bearer sent in the
  // clear. The risk HERE is that this daemon fetches the address itself, on a
  // timer, forever — TLS does not make an arbitrary outbound request safe.
  assert.equal(appsLib.REFUSED_HOST, appsLib.urlProblem('https://example.com/board'));
  assert.equal(null, appsLib.urlProblem('https://huginn:8443/board'));
});

test('a path is allowed — a console is a page — but not one playing traversal games', () => {
  assert.equal(null, appsLib.urlProblem('http://huginn:8092/boards/sensorstick/?layer=F.Cu'));
  for (const bad of [
    'http://huginn:8088/../../etc/shadow',
    'http://huginn:8088/a/../b',
    'http://huginn:8088/%2e%2e/%2e%2e/etc',
  ]) {
    assert.equal(appsLib.REFUSED_TRAVERSAL, appsLib.urlProblem(bad), `${bad} must be refused`);
  }
  // Refused on the RAW string, before WHATWG normalisation collapses it: a check
  // on the parsed URL would pass every one of the above and store the collapsed
  // result, which is a different address from the one that was judged.
  assert.equal('http://huginn:8088/b', new URL('http://huginn:8088/a/../b').href, 'the normalisation this guards against');
});

test('control characters, spaces and backslashes never reach a stored address', () => {
  assert.ok(appsLib.urlProblem('http://huginn:8088/a b'));
  assert.ok(appsLib.urlProblem('http://huginn:8088/\u0000'), 'a NUL truncates at every syscall boundary below the runtime');
  assert.ok(appsLib.urlProblem('http://huginn:8088\\evil'));
});

// ---------------------------------------------------------------- the record

test('a record has every field a client reads, with a definite value', () => {
  const rec = appsLib.buildRecord({ id: 'armap', name: 'Architecture map', url: 'http://huginn:8088/' }, 1789460000);
  assert.deepEqual(
    ['id', 'name', 'url', 'kind', 'notes', 'unit', 'addedAt', 'version'],
    Object.keys(rec),
    'the record contract — a row that decoded is a row that renders',
  );
  assert.equal('other', rec.kind, 'an unstated kind is `other`, never absent');
  assert.equal('', rec.notes);
  assert.equal('', rec.unit, 'an unknown unit is the empty string, never absent — [fixLines] says so in words');
  assert.equal(1, rec.version);
  assert.equal(1789460000, rec.addedAt);
});

test('an unknown kind is coerced, not refused, so a newer client can still write a row', () => {
  assert.equal('other', appsLib.cleanKind('hologram'));
  assert.equal('lab', appsLib.cleanKind('LAB'));
});

test('a name is one line with no control characters in it', () => {
  assert.equal('BTC 15m simulator', appsLib.cleanName('  BTC\t15m\nsimulator  '));
  assert.equal('a'.repeat(appsLib.MAX_NAME), appsLib.cleanName('a'.repeat(200)));
  assert.ok(appsLib.nameProblem(''), 'an app with no name is not a row anybody can find again');
  assert.ok(appsLib.nameProblem('a'.repeat(appsLib.MAX_NAME + 1)));
});

test('an id is a route segment, unique, and derived from the name when none is given', () => {
  assert.equal('btc-15m-simulator', appsLib.idFor('BTC 15m simulator'));
  assert.equal(null, appsLib.idProblem('armap', []));
  assert.ok(appsLib.idProblem('armap', ['armap']), 'two rows with one id is a PATCH that edits either');
  assert.ok(appsLib.idProblem('Armap', []), 'uppercase is not the grammar');
  assert.ok(appsLib.idProblem('../etc', []), 'an id is a path segment in every route below');
  assert.ok(appsLib.idProblem('-lead', []));
});

// ------------------------------------------------------------- list algebra

const BASE_LIST = () => appsLib.add([], { name: 'Architecture map', url: 'http://huginn:8088/', kind: 'docs' }, 100).apps;

test('add refuses a bad address before it ever reaches the store', () => {
  const r = appsLib.add([], { name: 'Evil', url: 'http://admin:pw@example.com/' }, 100);
  assert.equal(false, r.ok);
  assert.equal(400, r.status);
  assert.equal(appsLib.REFUSED_USERINFO, r.error, 'the first rule that fails is the one reported');
});

test('add stops at the cap rather than growing a list nobody can read', () => {
  let list = [];
  for (let i = 0; i < appsLib.MAX_APPS; i++) {
    const r = appsLib.add(list, { id: `c${i}`, name: `Console ${i}`, url: `http://huginn:${9000 + i}/` }, 100);
    assert.equal(true, r.ok);
    list = r.apps;
  }
  const over = appsLib.add(list, { id: 'one-more', name: 'One more', url: 'http://huginn:9999/' }, 100);
  assert.equal(false, over.ok);
  assert.equal(400, over.status);
});

test('rename keeps the id, the address and the probe’s meaning, and bumps the version', () => {
  const list = BASE_LIST();
  const r = appsLib.rename(list, 'architecture-map', 'Armap');
  assert.equal(true, r.ok);
  assert.equal('Armap', r.app.name);
  assert.equal('architecture-map', r.app.id, 'the id is the route segment and never moves');
  assert.equal('http://huginn:8088/', r.app.url);
  assert.equal(2, r.app.version);
  assert.equal(false, r.urlChanged, 'a rename does not invalidate an observation');
  assert.equal(1, r.apps.length);
  assert.equal('Armap', appsLib.findApp(r.apps, 'architecture-map').name, 'and it is in the list, not only in the answer');
});

test('re-pointing a console marks the observation stale, because the old latency is about a different address', () => {
  const list = BASE_LIST();
  const r = appsLib.setUrl(list, 'architecture-map', 'http://huginn:8188/');
  assert.equal(true, r.ok);
  assert.equal('http://huginn:8188/', r.app.url);
  assert.equal(true, r.urlChanged);
});

test('an edit against a stale version is refused WITH the current row', () => {
  // The scratchpad contract: 409 carrying the object, because an editor that
  // collided has to show what it collided with. A guard that answered with a
  // bare sentence would leave the client with two versions and no way to pick.
  const list = appsLib.rename(BASE_LIST(), 'architecture-map', 'Armap').apps;   // version is now 2
  const r = appsLib.patch(list, 'architecture-map', { version: 1, name: 'Stale edit' });
  assert.equal(false, r.ok);
  assert.equal(409, r.status);
  assert.equal('Armap', r.app.name, 'the CURRENT row travels with the refusal');
  assert.equal(2, r.app.version);
  // And the losing edit changed nothing.
  assert.equal('Armap', appsLib.findApp(list, 'architecture-map').name);
});

test('a write with no version at all is a 400, never an unguarded overwrite', () => {
  const r = appsLib.patch(BASE_LIST(), 'architecture-map', { name: 'No guard' });
  assert.equal(false, r.ok);
  assert.equal(400, r.status);
});

test('remove takes the row out and says which one it was', () => {
  const list = BASE_LIST();
  const r = appsLib.remove(list, 'architecture-map');
  assert.equal(true, r.ok);
  assert.equal(0, r.apps.length);
  assert.equal('architecture-map', r.app.id);
  assert.equal(404, appsLib.remove(r.apps, 'architecture-map').status, 'and removing it twice is a 404, not a second success');
});

// -------------------------------------------------------------------- rows

test('an app that has never been probed says so — null, which is not false', () => {
  const rec = appsLib.buildRecord({ id: 'armap', name: 'Armap', url: 'http://huginn:8088/' }, 100);
  const row = appsLib.appRow(rec, undefined);
  assert.equal(null, row.up, '`up:false` would draw this as an outage on a daemon that started 200ms ago');
  assert.equal(0, row.lastProbeAt);
  assert.equal(null, row.latencyMs);
  assert.equal(null, row.httpStatus);
  assert.equal(false, row.icon, 'no icon is a boolean, so a client never has to guess at a missing key');
  // ⚠ AND THE SAME TRI-STATE AGAIN. `reachable.ok:false` on a row nothing has
  // checked would draw "needs retrofit" across a freshly restarted daemon.
  assert.deepEqual({ ok: null, checkedAt: 0, addresses: [], fix: [], note: '' }, row.reachable);
});

/**
 * ⚠ THE FAIL-FIRST CASE. `consoleRow` rebuilt the record with `now` standing in
 * for a missing `addedAt`, so a stored row that never carried one was stamped
 * with the clock ON EVERY READ: "added 2 seconds ago", then 2 seconds ago again,
 * forever, and permanently once any later write persisted whichever value the
 * last read happened to invent. A row's age is a fact about the row, not about
 * when somebody looked at it.
 */
test('A STORED ROW\'S addedAt IS STICKY — a read never re-stamps it', () => {
  const stored = { id: 'armap', name: 'Armap', url: 'http://huginn:8088/', kind: 'docs', notes: '', version: 1 };
  const first = appsLib.appRow(stored, undefined).addedAt;
  const second = appsLib.appRow(stored, undefined).addedAt;
  assert.equal(first, second, 'two reads of one record cannot disagree about when it was added');
  assert.equal(0, first, 'never recorded reads as zero — "it was already here" — not as now');

  // A row that HAS one keeps it, whatever the clock says.
  const kept = appsLib.appRow({ ...stored, addedAt: 1789460000 }, undefined);
  assert.equal(1789460000, kept.addedAt);

  // And the same on the way out of the file, which is where a bad value would
  // become permanent: the next write saves what the load returned.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-addedat-'));
  try {
    appsLib.writeEnvelope(dir, { schema: appsLib.SCHEMA, seeded: true, consoles: [stored] });
    assert.equal(0, appsLib.readEnvelope(dir).consoles[0].addedAt);
    assert.equal(0, appsLib.readEnvelope(dir).consoles[0].addedAt, 'and it is the same value twice');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }

  // A NEW row still gets the clock — that is the one moment the stamp is a fact.
  assert.equal(1789460000, appsLib.buildRecord({ id: 'x', name: 'X', url: 'http://huginn:8088/' }, 1789460000).addedAt);
});

test('the row asks TWO questions — is it alive, and can this host\u2019s clients reach it', () => {
  // ⚠ THE WHOLE POINT OF 3.5.0. `reachableFrom:'host'` used to carry the caveat
  // for every row at once, which meant a row could say "up" while the phone
  // holding it could not open the page and the list said so only in general.
  // Now the row itself carries the per-address answer, so a client never has to
  // decide which of two fields it believes.
  const rec = appsLib.buildRecord({ id: 'armap', name: 'Armap', url: 'http://huginn:8088/' }, 100);
  const row = appsLib.appRow(rec, { up: true, lastProbeAt: 5, latencyMs: 3, httpStatus: 200 }, {
    icon: true,
    reachable: { ok: false, checkedAt: 7, addresses: [{ addr: '192.168.2.117', ok: false, error: 'connection refused' }], fix: ['x'] },
  });
  assert.equal(true, row.up, 'the daemon fetched it');
  assert.equal(false, row.reachable.ok, 'and your devices still cannot');
  assert.equal(true, row.icon);
  assert.ok(!('reachableFrom' in row), 'the one-word version is gone — reachable.addresses says strictly more');
});

// ------------------------------------------------------------- the seed list

test('the seed is the contract’s four pages, at the address the daemon was handed', () => {
  // ⚠ D10, AND THE REASON THIS TAKES AN ADDRESS AT ALL. The four seeded units
  // bind this host's TAILNET address; the name `huginn` resolves to its LAN
  // address. A seed pinned to the name therefore addresses an interface on
  // which none of them listen — `curl http://huginn:8088/` answers nothing
  // while `curl http://100.97.198.90:8088/` answers 200 — so every row read
  // "not answering from the host" forever, in the one deployment that has these
  // units. The probe could never pass, whatever anybody did.
  const seeded = appsLib.seedApps('100.97.198.90', 100);
  assert.deepEqual(['armap', 'jtyper', 'board', 'btc15m'], seeded.map((c) => c.id));
  assert.deepEqual(
    ['http://100.97.198.90:8088/', 'http://100.97.198.90:8091/',
      'http://100.97.198.90:8092/', 'http://100.97.198.90:8093/'],
    seeded.map((c) => c.url),
    'the address the daemon itself binds — the one interface these units are on',
  );
  for (const c of seeded) assert.equal(null, appsLib.urlProblem(c.url), `${c.id} must pass the daemon's own rule`);
});

test('the seed address is chosen from what the daemon can offer, skipping the wildcard it binds', () => {
  // ⚠ THE CASE THE LIVE HOST IS IN. huginn-appd's unit sets
  // HUGINN_APPD_BIND=0.0.0.0 so the tailnet, mesh and loopback pins a client
  // holds all reach one listener. A fix that read only the bind would resolve
  // to `0.0.0.0`, fall back to the name, and change nothing on the one host
  // that has this defect — so the daemon offers its bind AND the address
  // `tailscale ip -4` gives, and the first that stands up wins.
  assert.equal('100.97.198.90', appsLib.pickHostAddr('0.0.0.0', '100.97.198.90'));
  assert.equal('192.168.2.117', appsLib.pickHostAddr('192.168.2.117', '100.97.198.90'), 'a real bind is already the answer');
  assert.equal('', appsLib.pickHostAddr('0.0.0.0', ''), 'and nothing on offer is not a guess');
  assert.equal('', appsLib.pickHostAddr('::', 'example.com'), 'a public host is never an answer here');
  assert.equal('fd00::5', appsLib.pickHostAddr('fd00::5'), 'a mesh ULA is an address this registry accepts');
  assert.equal('', appsLib.pickHostAddr('fe80::1%eth0'), 'a link-local with a zone id means nothing to another process');
});

test('the wildcard the daemon binds seeds the fallback, not a URL nothing can open', () => {
  assert.equal('http://huginn:8088/', appsLib.seedApps(appsLib.pickHostAddr('0.0.0.0'), 100)[0].url);
  assert.equal('http://100.97.198.90:8088/',
    appsLib.seedApps(appsLib.pickHostAddr('0.0.0.0', '100.97.198.90'), 100)[0].url);
});

test('a daemon that knows no address of its own falls back to the host name rather than inventing one', () => {
  // The fallback is the OLD seed, exactly: a host that cannot say where it is
  // should write what it used to write, not a guess and not an empty row.
  for (const nothing of [undefined, null, '', '   ', 42]) {
    assert.deepEqual(
      ['http://huginn:8088/', 'http://huginn:8091/', 'http://huginn:8092/', 'http://huginn:8093/'],
      appsLib.seedApps(nothing, 100).map((c) => c.url),
      `${JSON.stringify(nothing)} is not an address`,
    );
  }
});

test('an address the daemon would refuse on a typed row cannot arrive through the seed either', () => {
  // `HUGINN_APPD_BIND=0.0.0.0` is a legal way to start this daemon and a
  // meaningless thing to put in a URL. The seed is not exempt from the rule
  // every other row in this registry is judged by.
  for (const bad of ['0.0.0.0', '::', 'example.com', '8.8.8.8']) {
    assert.equal('http://huginn:8088/', appsLib.seedApps(bad, 100)[0].url, `${bad} must not be seeded`);
  }
});

// ------------------------------------------------- re-seeding an existing store

test('a row still carrying the old seeded address is re-pointed; one the owner edited is not', () => {
  // The fix has to reach the stores that already exist — a daemon that only
  // seeds correctly on a host that has never run it leaves every real
  // installation with four rows that can never answer. The line between "the
  // old seed wrote this" and "the owner chose this" is the EXACT previous
  // literal, and nothing looser: a path, a port or a host of their own is them
  // speaking, and this must never overwrite it.
  const list = [
    appsLib.buildRecord({ id: 'armap', name: 'Architecture map', url: 'http://huginn:8088/' }, 100),
    appsLib.buildRecord({ id: 'jtyper', name: 'jtyper trainer', url: 'http://huginn:9099/' }, 100),
    appsLib.buildRecord({ id: 'board', name: 'PCB board view', url: 'http://huginn:8092/boards' }, 100),
    appsLib.buildRecord({ id: 'btc15m', name: 'BTC 15m simulator', url: 'http://127.0.0.1:8093/' }, 100),
    appsLib.buildRecord({ id: 'mine', name: 'Something of my own', url: 'http://huginn:8088/' }, 100),
  ];
  const r = appsLib.migrateSeedUrls(list, '100.97.198.90');
  assert.deepEqual(['armap'], r.changed, 'only a seed row still holding the literal the old seed wrote');
  assert.equal('http://100.97.198.90:8088/', r.apps[0].url);
  assert.equal(2, r.apps[0].version, 'the address moved, so a client holding version 1 is holding a stale row');
  assert.equal('http://huginn:9099/', r.apps[1].url, 'a re-pointed row is the owner speaking');
  assert.equal('http://huginn:8092/boards', r.apps[2].url, 'and so is a row with a path on it');
  assert.equal('http://127.0.0.1:8093/', r.apps[3].url, 'and so is a row already moved by hand');
  assert.equal('http://huginn:8088/', r.apps[4].url, 'a row the owner made is not a seed row, whatever it points at');
});

test('re-seeding twice changes nothing the second time', () => {
  const once = appsLib.migrateSeedUrls(appsLib.seedApps(null, 100), '100.97.198.90');
  assert.equal(4, once.changed.length, 'precondition: a pre-D10 store is four stale rows');
  assert.deepEqual([], appsLib.migrateSeedUrls(once.apps, '100.97.198.90').changed,
    'the file is rewritten once, not on every load');
});

test('an existing seed row learns the unit behind it, and a unit the owner typed is theirs', () => {
  // ⚠ THE FIX HAS TO REACH THE STORES THAT ALREADY EXIST. A row written by a
  // pre-3.5 daemon has no unit, falls into the no-unit branch of fixLines, and
  // asks the owner to work out what to edit — on the one host where the daemon
  // knows. Separate from the URL migration because an address is a CHOICE and a
  // unit is a fact about this host.
  const list = [
    appsLib.buildRecord({ id: 'armap', name: 'Armap', url: 'http://100.97.198.90:8088/' }, 100),
    appsLib.buildRecord({ id: 'board', name: 'Board', url: 'http://100.97.198.90:8092/', unit: 'mine.service' }, 100),
    appsLib.buildRecord({ id: 'mine', name: 'Mine', url: 'http://100.97.198.90:9100/' }, 100),
  ];
  const r = appsLib.migrateSeedUnits(list);
  assert.deepEqual(['armap'], r.changed, 'only a seed row that has none');
  assert.equal('armap.service', r.apps[0].unit);
  assert.equal(2, r.apps[0].version, 'the row changed, so a client holding version 1 is holding a stale one');
  assert.equal('mine.service', r.apps[1].unit, 'a unit the owner typed is never overwritten');
  assert.equal('', r.apps[2].unit, 'and a row that is not one of the four is left alone');
  assert.deepEqual([], appsLib.migrateSeedUnits(r.apps).changed, 'and it runs once, not on every load');
});

test('a daemon with no address of its own re-points nothing', () => {
  assert.deepEqual([], appsLib.migrateSeedUrls(appsLib.seedApps(null, 100), '').changed,
    'there is nowhere better to move them to, so they are left alone');
  assert.deepEqual([], appsLib.migrateSeedUrls(appsLib.seedApps(null, 100), '0.0.0.0').changed,
    'and a wildcard bind is not an address either');
});

// ------------------------------------------- the fix lines (decisions 54, 55)

test('a failing row carries the exact rebind and firewall lines, and only for the addresses that failed', () => {
  // ⚠ THIS IS TEXT SOMEBODY PASTES INTO A ROOT SHELL ON TWO MACHINES. Asserted
  // literally — not by shape — because a drifted line here is a firewall rule
  // that does not match the port the unit was rebound to, and nothing in this
  // product would notice.
  const rec = appsLib.buildRecord({
    id: 'jtyper', name: 'jtyper trainer', url: 'http://100.97.198.90:8091/', unit: 'jtyper-trainer.service',
  }, 100);
  const fix = appsLib.fixLines(rec, [
    { addr: '100.97.198.90', ok: true },
    { addr: '192.168.2.117', ok: false, error: 'connection refused' },
  ]);
  assert.deepEqual([
    '# on huginn — 192.168.2.117 does not reach this app',
    'systemctl edit jtyper-trainer.service   # ExecStart: bind 0.0.0.0 instead of 100.97.198.90',
    'systemctl restart jtyper-trainer.service',
    'ss -ltn | grep :8091',
    '# on heimdall — /etc/pve/firewall/117.fw',
    'IN ACCEPT -source 192.168.2.117 -p tcp -dport 8091 -log nolog',
  ], fix);
});

test('a row with no unit still gets an instruction, naming the address it does answer on', () => {
  // ⚠ "bind 0.0.0.0" WITH NOTHING TO EDIT IS NOT AN INSTRUCTION. An app the
  // owner added themselves has no unit on the row, and the line has to say what
  // it is replacing or it is advice rather than a fix.
  const rec = appsLib.buildRecord({ id: 'mine', name: 'Mine', url: 'http://100.97.198.90:9100/thing' }, 100);
  const fix = appsLib.fixLines(rec, [
    { addr: '100.97.198.90', ok: true },
    { addr: '192.168.2.117', ok: false, error: 'no route' },
  ]);
  assert.ok(fix.some((l) => l.startsWith('bind 0.0.0.0 instead of 100.97.198.90')), fix.join('\n'));
  assert.ok(fix.some((l) => l.includes('name it on the row')), 'and it says how to get the exact line');
  assert.ok(!fix.some((l) => l.startsWith('systemctl edit')), 'never a systemctl line with no unit in it');
  assert.ok(fix.includes('IN ACCEPT -source 192.168.2.117 -p tcp -dport 9100 -log nolog'),
    'the port comes off the row, not off a hard-coded list of four');
});

test('one firewall line per FAILING address, and none for the ones that answered', () => {
  const rec = appsLib.buildRecord({ id: 'armap', name: 'Armap', url: 'http://100.97.198.90:8088/', unit: 'armap.service' }, 100);
  const fix = appsLib.fixLines(rec, [
    { addr: '100.97.198.90', ok: true },
    { addr: '192.168.2.117', ok: false, error: 'connection refused' },
    { addr: '127.0.0.1', ok: false, error: 'connection refused' },
  ]);
  const rules = fix.filter((l) => l.startsWith('IN ACCEPT'));
  assert.deepEqual([
    'IN ACCEPT -source 192.168.2.117 -p tcp -dport 8088 -log nolog',
    'IN ACCEPT -source 127.0.0.1 -p tcp -dport 8088 -log nolog',
  ], rules, 'an address that already answers does not need a rule opened for it');
  assert.deepEqual([], appsLib.fixLines(rec, [{ addr: '100.97.198.90', ok: true }]),
    'and a row that passes carries no lines at all — that was the old card’s whole problem');
});

test('btc15m keeps the one true half of the D11 claim: where its bind actually lives', () => {
  // ⚠ D11. The old card EXEMPTED btc15m-sim as "already binds 0.0.0.0" while its
  // own firewall step opened 8093 — two halves of one card disagreeing about one
  // service. `ss -ltn` says 100.97.198.90:8093: the 0.0.0.0 in sim/app.py is the
  // fallback for a host with no tailscale address, not what it does here. What
  // survives is directions, not an exemption: `systemctl edit btc15m-sim.service`
  // would edit the wrong file.
  const rec = appsLib.buildRecord({
    id: 'btc15m', name: 'BTC 15m simulator', url: 'http://100.97.198.90:8093/', unit: 'btc15m-sim.service',
  }, 100);
  const fix = appsLib.fixLines(rec, [{ addr: '100.97.198.90', ok: true }, { addr: '192.168.2.117', ok: false }]);
  assert.ok(fix.some((l) => l.includes('sim/app.py')), `the bind note must ride the line: ${fix.join(' | ')}`);
  assert.ok(fix.some((l) => l.startsWith('systemctl edit btc15m-sim.service')), 'and it is still asked for');
  assert.ok(!/\balready\b/i.test(fix.join(' ')),
    'no unit is claimed to be done already — this daemon cannot see any host but its own, and was wrong about this one');
});

test('a row whose address is nonsense still produces lines rather than an exception', () => {
  // fixLines is exported and the store is not its only caller. A row whose url
  // does not parse can only exist by hand-editing the store file — which is
  // exactly when somebody is looking at this list to work out what is wrong, and
  // the worst possible moment for the list route to throw.
  const rec = { id: 'broken', url: 'not a url', unit: '' };
  const fix = appsLib.fixLines(rec, [{ addr: '192.168.2.117', ok: false }]);
  assert.ok(fix.length, 'it still says something');
  assert.ok(fix.some((l) => l.includes('the one address it answers on')), fix.join(' | '));
  assert.equal('', appsLib.hostnameOf('not a url'));
  assert.equal('', appsLib.portOf('not a url'));
});

test('nothing in the fix lines is a verb this daemon could run', () => {
  // Decision 47 as a property: the payload is data. If a future edit added an
  // endpoint or a shell string the daemon executes, it would have to add a way
  // to run one here first, and this fails when it does.
  const rec = appsLib.buildRecord({ id: 'armap', name: 'Armap', url: 'http://100.97.198.90:8088/', unit: 'armap.service' }, 100);
  for (const line of appsLib.fixLines(rec, [{ addr: '192.168.2.117', ok: false }])) {
    assert.equal('string', typeof line, 'every element is text, never a { run } of any kind');
  }
  const source = fs.readFileSync(path.join(__dirname, '..', 'lib', 'apps.js'), 'utf8');
  for (const forbidden of ['child_process', 'execFile', 'spawn(']) {
    assert.ok(!source.includes(forbidden), `lib/apps.js must never reach for ${forbidden}`);
  }
});

test('a unit name is refused rather than coerced, because it ends up in a root command line', () => {
  // `kind` is coerced so a newer client can still write a row; a unit is not,
  // because the cost of being wrong is a different thing entirely.
  assert.equal(null, appsLib.unitProblem(undefined), 'absent is fine — most rows have no unit');
  assert.equal(null, appsLib.unitProblem('jtyper-trainer.service'));
  assert.equal(null, appsLib.unitProblem('getty@tty1.service'));
  assert.ok(appsLib.unitProblem('armap.service; rm -rf /'), 'a shell fragment is not a unit name');
  assert.ok(appsLib.unitProblem('../../etc/passwd'));
  assert.ok(appsLib.unitProblem('a'.repeat(100)));
  assert.equal('', appsLib.cleanUnit('armap.service && curl evil'), 'and cleanUnit drops what it cannot vouch for');
});

test('the seed rows name the unit that serves each one', () => {
  // Without this the four rows this host ships with would fall into the
  // no-unit branch above and ask the owner to work out what to edit — on the
  // one deployment where the daemon knows the answer.
  const byId = Object.fromEntries(appsLib.seedApps('100.97.198.90', 100).map((r) => [r.id, r.unit]));
  assert.deepEqual({
    armap: 'armap.service',
    jtyper: 'jtyper-trainer.service',
    board: 'boardserver.service',
    btc15m: 'btc15m-sim.service',
  }, byId);
});

test('the marker is the owner’s word, and the daemon never writes it', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-marker-'));
  try {
    const store = appsLib.createStore({ dir, icons: false, fetch: async () => { throw new Error('no probing here'); } });
    store.stop();
    assert.equal(false, store.retrofitApplied());
    // Listing creates the store file (the seed). The marker it does not.
    store.list();
    assert.ok(fs.existsSync(appsLib.storePath(dir)), 'the store file exists');
    assert.equal(false, fs.existsSync(appsLib.markerPath(dir)),
      'the marker does not — a daemon that wrote it would be claiming credit');

    fs.writeFileSync(appsLib.markerPath(dir), '');   // the owner, after a netplan session
    assert.equal(true, store.retrofitApplied(), 'and it flips without a restart');
    assert.equal(appsLib.REBIND_MARKER_NAME, 'consoles-rebind-applied',
      'the NAME never changed — renaming it would silently un-apply every owner who already ran it');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------ the reachability prerequisite

test('an address a client arrived on is normalised, and a wildcard is not one', () => {
  // ⚠ `::ffff:127.0.0.1` IS 127.0.0.1. A dual-stack listener reports v4 peers in
  // mapped form; without this the same interface enters the set twice under two
  // spellings, is probed twice, and one of the spellings is not something
  // anybody would paste into a firewall rule.
  assert.equal('127.0.0.1', appsLib.normalizeAddr('::ffff:127.0.0.1'));
  assert.equal('100.97.198.90', appsLib.normalizeAddr(' 100.97.198.90 '));
  assert.equal('fd7a:115c:a1e0::1', appsLib.normalizeAddr('[fd7a:115c:a1e0::1]'));
  assert.equal('', appsLib.normalizeAddr('fe80::1%eth0'), 'a link-local carries a zone id that means nothing to another process');
  assert.equal('', appsLib.normalizeAddr('0.0.0.0'), 'a wildcard is a legal bind and a meaningless thing to dial');
  assert.equal('', appsLib.normalizeAddr('::'));
  assert.equal('', appsLib.normalizeAddr('8.8.8.8'), 'the daemon’s own address is not exempt from the daemon’s own URL rule');
  assert.equal('', appsLib.normalizeAddr(''));
});

test('the reachability probe asks for the SAME page at a different address', () => {
  assert.equal('http://192.168.2.117:8091/trainer', appsLib.reachUrl('http://100.97.198.90:8091/trainer', '192.168.2.117'));
  assert.equal('http://[fd7a::1]:8091/', appsLib.reachUrl('http://100.97.198.90:8091/', 'fd7a::1'), 'a v6 literal gets its brackets back');
  assert.equal('https://192.168.2.117:443/', appsLib.reachUrl('https://huginn/', '192.168.2.117'), 'the scheme default is a port');
});

test('nothing to check against is ok:null with a note, never ok:false', async () => {
  // ⚠ A FRESH DAEMON HAS NO OPINION. Folding "nobody has connected yet" to
  // "unreachable" would refuse every app on a new install until somebody
  // connected twice, which turns a check into a wall.
  const rec = appsLib.buildRecord({ id: 'x', name: 'X', url: 'http://127.0.0.1:1/' }, 100);
  const r = await appsLib.reachabilityProbe(rec, [], { fetch: async () => { throw new Error('nothing should be fetched'); } });
  assert.equal(null, r.ok);
  assert.deepEqual([], r.addresses);
  assert.deepEqual([], r.fix);
  assert.match(r.note, /has not seen a client arrive/);
});

test('every known address is asked, and one that does not answer makes the app unreachable', async () => {
  const server = http.createServer((req, res) => { res.writeHead(200); res.end('ok'); });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  try {
    const rec = appsLib.buildRecord({ id: 'x', name: 'X', url: `http://127.0.0.1:${port}/`, unit: 'x.service' }, 100);

    const pass = await within(9000, appsLib.reachabilityProbe(rec, ['127.0.0.1']), 'the passing probe');
    assert.equal(true, pass.ok);
    assert.deepEqual([{ addr: '127.0.0.1', ok: true }], pass.addresses);
    assert.deepEqual([], pass.fix, 'a row that passes carries no remedy');

    // 192.0.2.1 is TEST-NET-1 and is refused by the URL rule, so the second
    // address here is a loopback port with nothing on it: the failure mode a
    // real rebind produces (the listener is on one address, not the other).
    const fail = await within(9000, appsLib.reachabilityProbe(rec, ['127.0.0.1', '127.0.0.2']), 'the failing probe');
    assert.equal(false, fail.ok);
    assert.deepEqual(['127.0.0.1', '127.0.0.2'], fail.addresses.map((a) => a.addr), 'EVERY address is asked, not the first that fails');
    assert.equal(true, fail.addresses[0].ok);
    assert.equal(false, fail.addresses[1].ok);
    assert.ok(fail.addresses[1].error, 'and it says why, because "unreachable" with no reason is not actionable');
    assert.ok(fail.fix.includes(`IN ACCEPT -source 127.0.0.2 -p tcp -dport ${port} -log nolog`),
      `the failing address is the one in the rule: ${fail.fix.join(' | ')}`);
  } finally {
    server.close();
  }
});

test('a 5xx from an address is unreachable FROM there, with the status as the reason', async () => {
  const server = http.createServer((req, res) => { res.writeHead(503); res.end('nope'); });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const rec = appsLib.buildRecord({ id: 'x', name: 'X', url: `http://127.0.0.1:${server.address().port}/` }, 100);
    const r = await within(9000, appsLib.reachabilityProbe(rec, ['127.0.0.1']), 'the 503 probe');
    assert.equal(false, r.ok);
    assert.equal('http 503', r.addresses[0].error);
  } finally {
    server.close();
  }
});

test('an address that accepts and never answers fails on the deadline, not on the client’s patience', async () => {
  // The reachability probe fans out over every known address; one wedged
  // listener without a deadline would hold the add that triggered it open for as
  // long as the socket lived.
  const hang = net.createServer(() => { /* accept, and never answer */ });
  await new Promise((r) => hang.listen(0, '127.0.0.1', r));
  try {
    const rec = appsLib.buildRecord({ id: 'x', name: 'X', url: `http://127.0.0.1:${hang.address().port}/` }, 100);
    const started = Date.now();
    const r = await within(9000, appsLib.reachabilityProbe(rec, ['127.0.0.1'], { timeoutMs: 400 }), 'the hanging probe');
    assert.equal(false, r.ok);
    assert.equal('timed out', r.addresses[0].error);
    assert.ok(Date.now() - started < 4000, `it came back in ${Date.now() - started}ms, on its own deadline`);
  } finally {
    hang.close();
  }
});

test('the store remembers the addresses clients ARRIVE on, and forgets them after a week', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-addrs-'));
  let clock = 1789000000000;
  try {
    const store = appsLib.createStore({
      dir, icons: false, hostAddr: '', nowMs: () => clock, fetch: async () => { throw new Error('no probing'); },
    });
    store.stop();
    assert.deepEqual([], store.addresses(), 'a daemon nobody has connected to knows nothing');

    store.noteClientAddress('::ffff:127.0.0.1');
    store.noteClientAddress('192.168.2.117');
    store.noteClientAddress('0.0.0.0');
    assert.deepEqual(['127.0.0.1', '192.168.2.117'], store.addresses().sort(),
      'the mapped spelling folded into one, and the wildcard was never an address');

    // The set persists: it is a fact about this host's clients, not an
    // observation about an app, and a daemon that forgot it on every restart
    // would allow adds after a reboot that it refused an hour earlier.
    store.list();                       // the seed makes the file exist
    const onDisk = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    assert.deepEqual(['127.0.0.1', '192.168.2.117'], onDisk.clientAddresses.map((e) => e.addr).sort());

    // ⚠ AND IT FORGETS. A laptop that was on the LAN once in March must not make
    // every app on this host unaddable in September.
    clock += (appsLib.CLIENT_ADDR_TTL_SEC + 60) * 1000;
    assert.deepEqual([], store.addresses(), 'seven days without being seen again is gone');
    clock += 1000;
    store.noteClientAddress('192.168.2.117');
    assert.deepEqual(['192.168.2.117'], store.addresses(), 'and one poll brings it back');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('this host’s own address is in the set even before anybody connects', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-selfaddr-'));
  try {
    const store = appsLib.createStore({
      dir, icons: false, hostAddr: '100.97.198.90', fetch: async () => { throw new Error('no probing'); },
    });
    store.stop();
    assert.deepEqual(['100.97.198.90'], store.addresses(),
      'the address the seeded rows are written with is one an app has to answer on');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('adding an app is REFUSED when it does not answer everywhere, and nothing is stored', async () => {
  // ⚠ DECISION 54, THE WHOLE POINT. A row that would have to be marked "needs
  // retrofit" the moment it landed is a row nobody should be able to create.
  const server = http.createServer((req, res) => { res.writeHead(200); res.end('ok'); });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-add422-'));
  try {
    const store = appsLib.createStore({ dir, icons: false, hostAddr: '', timeoutMs: 700 });
    store.stop();
    store.noteClientAddress('127.0.0.1');
    store.noteClientAddress('127.0.0.2');

    const r = await within(9000, store.add({
      name: 'Half bound', url: `http://127.0.0.1:${server.address().port}/`, unit: 'half.service',
    }), 'the refused add');
    assert.equal(false, r.ok);
    assert.equal(422, r.status, '422: the shape was fine, the world is not');
    assert.equal(false, r.reachable.ok);
    assert.deepEqual(['127.0.0.2'], r.reachable.addresses.filter((a) => !a.ok).map((a) => a.addr));
    assert.ok(r.reachable.fix.some((l) => l.startsWith('systemctl edit half.service')), 'the remedy travels with the refusal');
    assert.equal(null, appsLib.findApp(store.list(), 'half-bound'), 'and NOTHING was stored');

    // The same add, once the addresses it has to answer on are only the ones it does.
    const ok = await within(9000, appsLib.createStore({
      dir: fs.mkdtempSync(path.join(os.tmpdir(), 'apps-add201-')), icons: false, hostAddr: '127.0.0.1', timeoutMs: 700,
    }).add({ name: 'Half bound', url: `http://127.0.0.1:${server.address().port}/` }), 'the allowed add');
    assert.equal(true, ok.ok);
    assert.equal(true, ok.reachable.ok);
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a fresh daemon that has never been connected to allows the add, and says what it could not check', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-addnull-'));
  try {
    const store = appsLib.createStore({
      dir, icons: false, hostAddr: '', fetch: async () => { throw new Error('nothing should be fetched'); },
    });
    store.stop();
    const r = await store.add({ name: 'Anything', url: 'http://127.0.0.1:9/' });
    assert.equal(true, r.ok, 'a check with nothing to check against is not a refusal');
    assert.equal(null, r.reachable.ok);
    assert.match(r.reachable.note, /nothing to check this against/);
    assert.ok(appsLib.findApp(store.list(), 'anything'), 'and it is in the list');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a row that was already here and now fails is MARKED, never deleted', async () => {
  // The prerequisite gates ADDING. An app that predates the gate, or that broke
  // after passing it, is somebody's row — deleting it would be this feature
  // destroying the registry it exists to keep.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-retrofit-'));
  try {
    appsLib.writeEnvelope(dir, {
      schema: appsLib.SCHEMA,
      seeded: true,
      consoles: [appsLib.buildRecord({ id: 'old', name: 'An older row', url: 'http://127.0.0.1:9/', unit: 'old.service' }, 100)],
    });
    const lines = [];
    const store = appsLib.createStore({ dir, icons: false, hostAddr: '', timeoutMs: 500, log: (l) => lines.push(l) });
    store.stop();
    store.noteClientAddress('127.0.0.1');
    await within(9000, store.sweep(), 'the sweep');

    const row = store.rows()[0];
    assert.equal('old', row.id, 'still there');
    assert.equal(false, row.reachable.ok, 'and marked');
    assert.ok(row.reachable.fix.some((l) => l.startsWith('systemctl edit old.service')), 'with its own remedy on it');
    assert.ok(lines.some((l) => /needs retrofit/.test(l)), `the journal says so once: ${JSON.stringify(lines)}`);
    assert.equal(1, store.list().length, 'and the sweep removed nothing');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------ favicons (decision 53)

test('the page’s icon link is read as a TOKEN LIST, so apple-touch-icon is not the favicon', () => {
  // ⚠ `/\bicon\b/` ALSO MATCHES `apple-touch-icon` and `mask-icon` — a 180px PNG
  // and a monochrome SVG, neither of which is the favicon and one of which is
  // routinely 100 KB. Decision 53 names `icon` and `shortcut icon`.
  assert.equal('/f.png', appsLib.iconHrefFromHtml('<link rel="icon" href="/f.png">'));
  assert.equal('/f.ico', appsLib.iconHrefFromHtml("<link rel='shortcut icon' href='/f.ico'>"));
  assert.equal('/f.png', appsLib.iconHrefFromHtml('<link rel=icon href=/f.png>'), 'unquoted attributes are legal html');
  assert.equal('', appsLib.iconHrefFromHtml('<link rel="apple-touch-icon" href="/big.png">'));
  assert.equal('', appsLib.iconHrefFromHtml('<link rel="mask-icon" href="/m.svg">'));
  assert.equal('', appsLib.iconHrefFromHtml('<link rel="stylesheet" href="/a.css">'));
  assert.equal('/f.png', appsLib.iconHrefFromHtml(
    '<link rel="stylesheet" href="/a.css"><link rel="apple-touch-icon" href="/b.png"><link rel="icon" href="/f.png">'),
  'and it keeps looking past the ones that are not it');
});

test('an icon is fetched from /favicon.ico first, and from the page’s link when there is none', async () => {
  const png = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');
  let served = [];
  const server = http.createServer((req, res) => {
    served.push(req.url);
    if (req.url === '/favicon.ico' && server.wellKnown) {
      res.writeHead(200, { 'content-type': 'image/x-icon' }); return res.end(png);
    }
    if (req.url === '/favicon.ico') { res.writeHead(404); return res.end(); }
    if (req.url === '/brand.png') { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(png); }
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html><head><link rel="icon" href="/brand.png"></head><body></body></html>');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  try {
    server.wellKnown = true;
    const direct = await within(9000, appsLib.fetchIcon(base), 'the well-known fetch');
    assert.equal(true, direct.ok);
    assert.equal('image/x-icon', direct.contentType);
    assert.deepEqual(['/favicon.ico'], served, 'the page is not fetched at all when the well-known path answers');

    served = [];
    server.wellKnown = false;
    const linked = await within(9000, appsLib.fetchIcon(base), 'the linked fetch');
    assert.equal(true, linked.ok);
    assert.equal('image/png', linked.contentType);
    assert.deepEqual(['/favicon.ico', '/', '/brand.png'], served, 'the page is read ONCE, then the href it named');
  } finally {
    server.close();
  }
});

test('an SPA that answers /favicon.ico with its own index.html does not get html cached as its icon', async () => {
  // ⚠ THE COMMON CASE, and the one that would put 40 KB of markup behind an
  // image content type. The TYPE is the test, not the path and not the status.
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html><head></head><body>app</body></html>');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const r = await within(9000, appsLib.fetchIcon(`http://127.0.0.1:${server.address().port}/`), 'the spa fetch');
    assert.equal(false, r.ok);
    assert.match(r.why, /names no icon/);
  } finally {
    server.close();
  }
});

test('an icon larger than the cap is refused rather than truncated', async () => {
  // Half an icon is not an icon, and a cache of half icons is worse than an
  // empty one. Both the declared length and the bytes are checked — a response
  // may not carry a length, and one that does may be lying.
  const big = Buffer.alloc(appsLib.ICON_MAX_BYTES + 1024, 7);
  const server = http.createServer((req, res) => {
    if (req.url === '/lying') {
      res.writeHead(200, { 'content-type': 'image/png' });   // chunked: no content-length at all
      return res.end(big);
    }
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': String(big.length) });
    res.end(big);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const declared = await within(9000, appsLib.getBounded(`${base}/favicon.ico`), 'the declared-length fetch');
    assert.equal(false, declared.ok);
    assert.match(declared.why, /larger than/);
    const lying = await within(9000, appsLib.getBounded(`${base}/lying`), 'the no-length fetch');
    assert.equal(false, lying.ok, 'content-length is a claim, not a limit');
  } finally {
    server.close();
  }
});

test('an icon follows ONE same-origin redirect and never leaves the host', async () => {
  const png = Buffer.from('89504e470d0a1a0a', 'hex');
  const server = http.createServer((req, res) => {
    if (req.url === '/favicon.ico') { res.writeHead(302, { location: '/static/icon.png' }); return res.end(); }
    if (req.url === '/static/icon.png') { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(png); }
    if (req.url === '/away.ico') { res.writeHead(302, { location: 'http://example.com/icon.png' }); return res.end(); }
    res.writeHead(404); res.end();
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const hop = await within(9000, appsLib.getBounded(`${base}/favicon.ico`), 'the redirected fetch');
    assert.equal(true, hop.ok);
    assert.equal('image/png', hop.contentType);
    // ⚠ A PAGE STEERING A ROOT-EQUIVALENT DAEMON'S FETCH. The host rule was
    // applied to the address that was STORED, and a redirect is not a re-judge.
    const away = await within(9000, appsLib.getBounded(`${base}/away.ico`), 'the off-host redirect');
    assert.equal(false, away.ok);
    assert.match(away.why, /off this host/);
  } finally {
    server.close();
  }
});

test('an icon is re-fetched at most once an hour, and at once when the address moves', () => {
  const rec = { id: 'x', url: 'http://127.0.0.1:1/' };
  const now = 1789000000000;
  assert.equal(true, appsLib.iconDue(null, rec, now), 'never fetched');
  assert.equal(false, appsLib.iconDue({ sourceUrl: rec.url, fetchedAtMs: now - 60_000 }, rec, now), 'a minute ago is fresh');
  assert.equal(true, appsLib.iconDue({ sourceUrl: rec.url, fetchedAtMs: now - appsLib.ICON_REFRESH_MS - 1 }, rec, now));
  assert.equal(true, appsLib.iconDue({ sourceUrl: 'http://127.0.0.1:2/', fetchedAtMs: now }, rec, now),
    're-pointing a row drops the old page’s brand rather than wearing it until the hour is up');
});

test('a failed icon fetch is remembered, so a page with no favicon is not asked every sweep', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-iconfail-'));
  const server = http.createServer((req, res) => { res.writeHead(404); res.end(); });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    const url = `http://127.0.0.1:${server.address().port}/`;
    appsLib.writeEnvelope(dir, {
      schema: appsLib.SCHEMA, seeded: true,
      consoles: [appsLib.buildRecord({ id: 'noicon', name: 'No icon', url }, 100)],
    });
    const store = appsLib.createStore({ dir, hostAddr: '127.0.0.1', timeoutMs: 700 });
    store.stop();
    await within(9000, store.sweep(), 'the first sweep');
    const meta = appsLib.readIconMeta(dir, 'noicon');
    assert.ok(meta, 'the failure is written down');
    assert.equal(null, meta.contentType, 'with no type, which is what hasIcon reads');
    assert.equal(false, store.rows()[0].icon, 'and the row says it has none');
    assert.equal(false, appsLib.iconOf(dir, 'noicon').ok, 'nothing to serve');
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});


// -------------------------------------------------------------------- probes

test('a server that accepts and never answers is DOWN, not pending', async () => {
  // ⚠ THE TEST THIS MODULE EXISTS FOR. `fetch` has no default timeout: without
  // the AbortSignal in probeConsole this never resolves, and the route that
  // called it never answers either. A socket that completes the TCP handshake
  // and then writes nothing is the exact failure — a refused connection would
  // fail fast and prove nothing.
  const server = net.createServer(() => { /* accept, and say nothing, forever */ });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  try {
    const started = Date.now();
    const probe = await within(10_000, appsLib.probeApp(
      { id: 'hang', url: `http://127.0.0.1:${port}/` },
      { timeoutMs: 300 },
    ), 'the probe of a hanging server');
    assert.equal(false, probe.up);
    assert.equal(null, probe.httpStatus, 'nothing was answered, so there is no status to report');
    assert.ok(Date.now() - started < 5_000, 'and it gave up near its deadline rather than near the runner’s patience');
    assert.ok(probe.lastProbeAt > 0, 'a failed probe is still an observation with a time on it');
  } finally {
    server.close();
  }
});

test('an answer under 500 is up, including one that refuses you', async () => {
  // A 401 or a 403 is a PAGE, served by a process that is alive. Calling that
  // "down" would have the board view reading as an outage for as long as it has
  // auth in front of it.
  const server = http.createServer((req, res) => {
    const code = Number(req.url.slice(1)) || 200;
    res.writeHead(code, { 'content-type': 'text/plain' });
    res.end('x');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    for (const code of [200, 302, 401, 403, 404]) {
      const probe = await within(10_000, appsLib.probeApp({ id: 'x', url: `${base}/${code}` }), `probe ${code}`);
      assert.equal(true, probe.up, `${code} means something is serving`);
      assert.equal(code, probe.httpStatus);
      assert.ok(probe.latencyMs >= 0 && probe.latencyMs < 5_000, 'with a latency on it');
    }
    const broken = await within(10_000, appsLib.probeApp({ id: 'x', url: `${base}/503` }), 'probe 503');
    assert.equal(false, broken.up, 'a 5xx is the server saying it is broken — the one HTTP answer that means down');
    assert.equal(503, broken.httpStatus, 'and the status is still reported, so the row can say which kind of down');
  } finally {
    server.close();
  }
});

test('a refused connection is down, and an address the rule would refuse is never fetched at all', async () => {
  const server = net.createServer(() => {});
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  await new Promise((r) => server.close(r));   // nothing is listening there now

  const refused = await within(10_000, appsLib.probeApp({ id: 'x', url: `http://127.0.0.1:${port}/` }), 'refused probe');
  assert.equal(false, refused.up);

  // A row whose address does not pass the rule can only exist by hand-editing
  // the store file. It is not fetched — a guard applied at the route and not at
  // the fetch is not a guard.
  let called = false;
  const probe = await appsLib.probeApp(
    { id: 'evil', url: 'http://example.com/' },
    { fetch: async () => { called = true; return { status: 200 }; } },
  );
  assert.equal(false, called, 'the probe must not dial an address the rule refuses');
  assert.equal(false, probe.up);
});

test('probeAll stays within its concurrency, so 32 consoles is not 32 sockets', async () => {
  let inFlight = 0;
  let peak = 0;
  const slowFetch = async () => {
    inFlight += 1;
    peak = Math.max(peak, inFlight);
    await new Promise((r) => setTimeout(r, 20));
    inFlight -= 1;
    return { status: 200, body: null };
  };
  const recs = Array.from({ length: 12 }, (_, i) => ({ id: `c${i}`, url: `http://huginn:${9000 + i}/` }));
  const out = await within(10_000, appsLib.probeAll(recs, { fetch: slowFetch }), 'probeAll');
  assert.equal(12, Object.keys(out).length, 'every console is probed');
  assert.ok(peak <= appsLib.PROBE_CONCURRENCY, `peak in flight was ${peak}`);
});

test('the journal speaks on a state CHANGE and is quiet otherwise', () => {
  // A line every sweep is 288 a day per console, which is how a journal stops
  // being read.
  const rec = { id: 'armap' };
  const up = { up: true };
  const down = { up: false };
  assert.equal(null, appsLib.probeChange(rec, undefined, up), 'a first observation that is fine says nothing');
  assert.match(appsLib.probeChange(rec, undefined, down), /not answering/, 'a first observation that is not fine does');
  assert.equal(null, appsLib.probeChange(rec, up, up));
  assert.equal(null, appsLib.probeChange(rec, down, down), 'a console that is still down does not repeat itself');
  assert.match(appsLib.probeChange(rec, up, down), /stopped answering/);
  assert.match(appsLib.probeChange(rec, down, up), /answering again/);
});

// --------------------------------------------------------------- the store

test('the store seeds once, and an owner who empties it is not argued with', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-store-'));
  try {
    const store = appsLib.createStore({ dir, fetch: async () => { throw new Error('no probing'); } });
    store.stop();
    assert.equal(4, store.list().length, 'the first list seeds the contract’s four');
    for (const c of store.list()) store.remove(c.id);
    assert.equal(0, store.list().length);
    assert.equal(0, store.list().length, 'and listing again does not put them back');
    assert.equal(0o600, fs.statSync(appsLib.storePath(dir)).mode & 0o777, 'tmp+rename at 0600 like every other store here');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a store that has never existed seeds straight at the address it was given', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-seedaddr-'));
  try {
    const store = appsLib.createStore({ dir, hostAddr: '127.0.0.1', fetch: async () => { throw new Error('no probing'); } });
    store.stop();
    assert.deepEqual([
      'http://127.0.0.1:8088/', 'http://127.0.0.1:8091/', 'http://127.0.0.1:8092/', 'http://127.0.0.1:8093/',
    ], store.list().map((c) => c.url));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the store re-points the rows a pre-D10 daemon wrote, once, and says so', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-reseed-'));
  try {
    // Exactly what a daemon before this fix left on disk.
    appsLib.writeEnvelope(dir, { schema: appsLib.SCHEMA, seeded: true, consoles: appsLib.seedApps(null, 100) });
    const lines = [];
    const store = appsLib.createStore({
      dir,
      hostAddr: '100.97.198.90',
      log: (l) => lines.push(l),
      fetch: async () => { throw new Error('no probing'); },
    });
    store.stop();
    assert.deepEqual([
      'http://100.97.198.90:8088/', 'http://100.97.198.90:8091/',
      'http://100.97.198.90:8092/', 'http://100.97.198.90:8093/',
    ], store.list().map((c) => c.url));
    const onDisk = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    assert.equal('http://100.97.198.90:8088/', onDisk.consoles[0].url, 'and it survived the trip through the file');
    assert.equal(1, lines.length, `one journal line, not one per load — got ${JSON.stringify(lines)}`);

    // ⚠ load() is called by every read path here. A migration that fired on each
    // one would rewrite the store forever and log forever with it.
    store.list(); store.rows(); store.get('armap');
    assert.equal(1, lines.length, 'said once');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a row the owner re-pointed survives a daemon that learned its own address', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-reseed-'));
  try {
    const seeded = appsLib.seedApps(null, 100);
    seeded[1] = appsLib.buildRecord({ ...seeded[1], url: 'http://huginn:8091/trainer' }, 100);
    appsLib.writeEnvelope(dir, { schema: appsLib.SCHEMA, seeded: true, consoles: seeded });
    const store = appsLib.createStore({ dir, hostAddr: '100.97.198.90', fetch: async () => { throw new Error('no probing'); } });
    store.stop();
    assert.equal('http://huginn:8091/trainer', store.get('jtyper').url, 'their edit is not a seed to be re-applied');
    assert.equal('http://100.97.198.90:8088/', store.get('armap').url, 'and its untouched sibling still moves');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('an observation is never written to disk, because it stops being true', async () => {
  // A daemon that restarted at 04:00 must not tell somebody at 09:00 that a
  // console was up, on the strength of a measurement taken before the reboot.
  //
  // Asserted as "the file holds RECORDS", key for key, and after every write
  // path this store has — the seed, an add and a patch. Grepping for the field
  // names would only have pinned whichever path the test happened to run.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-store-'));
  try {
    const store = appsLib.createStore({
      dir, icons: false, hostAddr: '', fetch: async () => new Response('ok', { status: 200 }),
    });
    store.stop();
    store.list();                                                     // the seed writes
    const probed = await store.probeNow('armap');
    assert.equal(true, probed.up, 'precondition: there is an observation to leak');
    await store.add({ name: 'Extra', url: 'http://huginn:9100/' });   // an add writes
    store.patch('armap', { version: 1, name: 'Armap' });               // a patch writes

    const raw = JSON.parse(fs.readFileSync(appsLib.storePath(dir), 'utf8'));
    for (const c of raw.consoles) {
      assert.deepEqual(
        ['id', 'name', 'url', 'kind', 'notes', 'unit', 'addedAt', 'version'],
        Object.keys(c),
        `${c.id} is stored as a record and nothing else`,
      );
    }
    // ⚠ AND `reachable` IS NOT ON DISK EITHER. It is an observation with the
    // same half-life as `up`: a daemon that restarted at 04:00 must not tell
    // somebody at 09:00 that their phone could reach an app, on the strength of
    // a check made before the reboot.
    assert.ok(!JSON.stringify(raw.consoles).includes('reachable'), 'no reachability observation is persisted');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
