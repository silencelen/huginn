'use strict';
// lib/consoles.js — the rules a console is stored, judged and probed by.
//
// Every case here is a way this feature LIES while the row on screen still looks
// right: a probe that reports "up" because the request it is waiting on has not
// come back yet, an address that carries a password into a diagnostics bundle,
// an approval card that has drifted from the commands somebody is expected to
// paste into a root shell on another machine, a version guard that silently
// takes the losing edit.
//
// Pure: no daemon, no routes. Two tests bind a REAL socket on an ephemeral port
// (`listen(0)`) because the one thing that cannot be faked is a server that
// accepts a connection and then says nothing — the whole point of the timeout.
// The route-level half is routes-consoles.test.js.

const { test } = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const consoles = require('../lib/consoles');

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
  assert.equal(consoles.REFUSED_USERINFO, consoles.urlProblem('http://admin:hunter2@huginn:8088/'));
  assert.equal(consoles.REFUSED_USERINFO, consoles.urlProblem('https://admin@100.97.198.90:8092/board'));
  assert.equal(null, consoles.urlProblem('http://huginn:8088/'), 'the same address without the credential is fine');
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
    assert.equal(consoles.REFUSED_SCHEME, consoles.urlProblem(bad), `${bad} must be refused`);
  }
});

test('the host must be one huginn cannot reach the public internet with — one case per class', () => {
  // The shape rule copied from the clients' RouteGuard.kt (see lib/consoles.js).
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
    assert.equal(null, consoles.urlProblem(url), `${why} (${url}) must be allowed`);
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
    assert.equal(consoles.REFUSED_HOST, consoles.urlProblem(url), `${why} (${url}) must be refused`);
  }
});

test('https does not open the host up, unlike the clients’ route guard', () => {
  // The deliberate divergence, asserted so nobody "fixes" it back. RouteGuard
  // lets https go anywhere because the risk it guards is a bearer sent in the
  // clear. The risk HERE is that this daemon fetches the address itself, on a
  // timer, forever — TLS does not make an arbitrary outbound request safe.
  assert.equal(consoles.REFUSED_HOST, consoles.urlProblem('https://example.com/board'));
  assert.equal(null, consoles.urlProblem('https://huginn:8443/board'));
});

test('a path is allowed — a console is a page — but not one playing traversal games', () => {
  assert.equal(null, consoles.urlProblem('http://huginn:8092/boards/sensorstick/?layer=F.Cu'));
  for (const bad of [
    'http://huginn:8088/../../etc/shadow',
    'http://huginn:8088/a/../b',
    'http://huginn:8088/%2e%2e/%2e%2e/etc',
  ]) {
    assert.equal(consoles.REFUSED_TRAVERSAL, consoles.urlProblem(bad), `${bad} must be refused`);
  }
  // Refused on the RAW string, before WHATWG normalisation collapses it: a check
  // on the parsed URL would pass every one of the above and store the collapsed
  // result, which is a different address from the one that was judged.
  assert.equal('http://huginn:8088/b', new URL('http://huginn:8088/a/../b').href, 'the normalisation this guards against');
});

test('control characters, spaces and backslashes never reach a stored address', () => {
  assert.ok(consoles.urlProblem('http://huginn:8088/a b'));
  assert.ok(consoles.urlProblem('http://huginn:8088/\u0000'), 'a NUL truncates at every syscall boundary below the runtime');
  assert.ok(consoles.urlProblem('http://huginn:8088\\evil'));
});

// ---------------------------------------------------------------- the record

test('a record has every field a client reads, with a definite value', () => {
  const rec = consoles.buildRecord({ id: 'armap', name: 'Architecture map', url: 'http://huginn:8088/' }, 1789460000);
  assert.deepEqual(
    ['id', 'name', 'url', 'kind', 'notes', 'addedAt', 'version'],
    Object.keys(rec),
    'the record contract — a row that decoded is a row that renders',
  );
  assert.equal('other', rec.kind, 'an unstated kind is `other`, never absent');
  assert.equal('', rec.notes);
  assert.equal(1, rec.version);
  assert.equal(1789460000, rec.addedAt);
});

test('an unknown kind is coerced, not refused, so a newer client can still write a row', () => {
  assert.equal('other', consoles.cleanKind('hologram'));
  assert.equal('lab', consoles.cleanKind('LAB'));
});

test('a name is one line with no control characters in it', () => {
  assert.equal('BTC 15m simulator', consoles.cleanName('  BTC\t15m\nsimulator  '));
  assert.equal('a'.repeat(consoles.MAX_NAME), consoles.cleanName('a'.repeat(200)));
  assert.ok(consoles.nameProblem(''), 'a console with no name is not a row anybody can find again');
  assert.ok(consoles.nameProblem('a'.repeat(consoles.MAX_NAME + 1)));
});

test('an id is a route segment, unique, and derived from the name when none is given', () => {
  assert.equal('btc-15m-simulator', consoles.idFor('BTC 15m simulator'));
  assert.equal(null, consoles.idProblem('armap', []));
  assert.ok(consoles.idProblem('armap', ['armap']), 'two rows with one id is a PATCH that edits either');
  assert.ok(consoles.idProblem('Armap', []), 'uppercase is not the grammar');
  assert.ok(consoles.idProblem('../etc', []), 'an id is a path segment in every route below');
  assert.ok(consoles.idProblem('-lead', []));
});

// ------------------------------------------------------------- list algebra

const BASE_LIST = () => consoles.add([], { name: 'Architecture map', url: 'http://huginn:8088/', kind: 'docs' }, 100).consoles;

test('add refuses a bad address before it ever reaches the store', () => {
  const r = consoles.add([], { name: 'Evil', url: 'http://admin:pw@example.com/' }, 100);
  assert.equal(false, r.ok);
  assert.equal(400, r.status);
  assert.equal(consoles.REFUSED_USERINFO, r.error, 'the first rule that fails is the one reported');
});

test('add stops at the cap rather than growing a list nobody can read', () => {
  let list = [];
  for (let i = 0; i < consoles.MAX_CONSOLES; i++) {
    const r = consoles.add(list, { id: `c${i}`, name: `Console ${i}`, url: `http://huginn:${9000 + i}/` }, 100);
    assert.equal(true, r.ok);
    list = r.consoles;
  }
  const over = consoles.add(list, { id: 'one-more', name: 'One more', url: 'http://huginn:9999/' }, 100);
  assert.equal(false, over.ok);
  assert.equal(400, over.status);
});

test('rename keeps the id, the address and the probe’s meaning, and bumps the version', () => {
  const list = BASE_LIST();
  const r = consoles.rename(list, 'architecture-map', 'Armap');
  assert.equal(true, r.ok);
  assert.equal('Armap', r.console.name);
  assert.equal('architecture-map', r.console.id, 'the id is the route segment and never moves');
  assert.equal('http://huginn:8088/', r.console.url);
  assert.equal(2, r.console.version);
  assert.equal(false, r.urlChanged, 'a rename does not invalidate an observation');
  assert.equal(1, r.consoles.length);
  assert.equal('Armap', consoles.findConsole(r.consoles, 'architecture-map').name, 'and it is in the list, not only in the answer');
});

test('re-pointing a console marks the observation stale, because the old latency is about a different address', () => {
  const list = BASE_LIST();
  const r = consoles.setUrl(list, 'architecture-map', 'http://huginn:8188/');
  assert.equal(true, r.ok);
  assert.equal('http://huginn:8188/', r.console.url);
  assert.equal(true, r.urlChanged);
});

test('an edit against a stale version is refused WITH the current row', () => {
  // The scratchpad contract: 409 carrying the object, because an editor that
  // collided has to show what it collided with. A guard that answered with a
  // bare sentence would leave the client with two versions and no way to pick.
  const list = consoles.rename(BASE_LIST(), 'architecture-map', 'Armap').consoles;   // version is now 2
  const r = consoles.patch(list, 'architecture-map', { version: 1, name: 'Stale edit' });
  assert.equal(false, r.ok);
  assert.equal(409, r.status);
  assert.equal('Armap', r.console.name, 'the CURRENT row travels with the refusal');
  assert.equal(2, r.console.version);
  // And the losing edit changed nothing.
  assert.equal('Armap', consoles.findConsole(list, 'architecture-map').name);
});

test('a write with no version at all is a 400, never an unguarded overwrite', () => {
  const r = consoles.patch(BASE_LIST(), 'architecture-map', { name: 'No guard' });
  assert.equal(false, r.ok);
  assert.equal(400, r.status);
});

test('remove takes the row out and says which one it was', () => {
  const list = BASE_LIST();
  const r = consoles.remove(list, 'architecture-map');
  assert.equal(true, r.ok);
  assert.equal(0, r.consoles.length);
  assert.equal('architecture-map', r.console.id);
  assert.equal(404, consoles.remove(r.consoles, 'architecture-map').status, 'and removing it twice is a 404, not a second success');
});

// -------------------------------------------------------------------- rows

test('a console that has never been probed says so — null, which is not false', () => {
  const rec = consoles.buildRecord({ id: 'armap', name: 'Armap', url: 'http://huginn:8088/' }, 100);
  const row = consoles.consoleRow(rec, undefined);
  assert.equal(null, row.up, '`up:false` would draw this as an outage on a daemon that started 200ms ago');
  assert.equal(0, row.lastProbeAt);
  assert.equal(null, row.latencyMs);
  assert.equal(null, row.httpStatus);
});

test('every row says where the probe ran from', () => {
  // The one word that keeps this honest: the daemon reached it FROM HUGINN. The
  // phone reading this list generally cannot reach any of these addresses until
  // the approval card has been applied.
  const rec = consoles.buildRecord({ id: 'armap', name: 'Armap', url: 'http://huginn:8088/' }, 100);
  assert.equal('host', consoles.consoleRow(rec, { up: true, lastProbeAt: 5, latencyMs: 3, httpStatus: 200 }).reachableFrom);
});

// ------------------------------------------------------------- the seed list

test('the seed is the contract’s four pages, addressed the way a phone would open them', () => {
  const seeded = consoles.seedConsoles(100);
  assert.deepEqual(['armap', 'jtyper', 'board', 'btc15m'], seeded.map((c) => c.id));
  assert.deepEqual(
    ['http://huginn:8088/', 'http://huginn:8091/', 'http://huginn:8092/', 'http://huginn:8093/'],
    seeded.map((c) => c.url),
    'the host’s own name and not 127.0.0.1 — loopback would make the probe pass and the tap fail',
  );
  for (const c of seeded) assert.equal(null, consoles.urlProblem(c.url), `${c.id} must pass the daemon's own rule`);
});

// ------------------------------------------------------------ the approval card

test('the approval card carries exactly the commands the contract names, and applied:false', () => {
  // ⚠ THIS IS TEXT SOMEBODY PASTES INTO A ROOT SHELL ON TWO MACHINES. It is
  // asserted literally — not by shape — because a drifted rule here is four
  // firewall lines that do not match the ports the units were rebound to, and
  // nothing in this product would notice.
  const card = consoles.approvalCard(false, '/var/lib/huginn-appd/consoles-rebind-applied');
  assert.equal(false, card.applied);
  assert.equal('owner', card.runBy);
  assert.deepEqual(['rebind', 'firewall'], card.steps.map((s) => s.id));

  assert.deepEqual([
    'systemctl edit armap.service            # ExecStart: bind 0.0.0.0 instead of the tailnet address',
    'systemctl edit jtyper-trainer.service   # same',
    'systemctl edit boardserver.service      # same',
    'systemctl restart armap jtyper-trainer boardserver',
    "ss -ltnp | grep -E '8088|8091|8092'",
  ], card.steps[0].commands, 'btc15m-sim is absent because it already falls back to 0.0.0.0');

  assert.equal('/etc/pve/firewall/117.fw', card.steps[1].file);
  assert.deepEqual([
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8092 -log nolog',
    'IN ACCEPT -source 192.168.2.131 -p tcp -dport 8093 -log nolog',
  ], card.steps[1].commands, 'one source, one port each — the convention already in that file');
  assert.equal('heimdall', card.steps[1].where, 'another machine entirely, which this daemon never touches');
});

test('nothing in the card is a verb this daemon could run', () => {
  // Decision 47 as a property: the payload is data. If a future edit added an
  // endpoint or a shell string the daemon executes, it would have to add a field
  // here first, and this fails when it does.
  const card = consoles.approvalCard(false, '/tmp/marker');
  const allowedStep = ['id', 'where', 'summary', 'file', 'commands'];
  assert.deepEqual(['applied', 'runBy', 'markerPath', 'title', 'why', 'steps', 'note'], Object.keys(card));
  for (const s of card.steps) assert.deepEqual(allowedStep, Object.keys(s));
  const source = fs.readFileSync(path.join(__dirname, '..', 'lib', 'consoles.js'), 'utf8');
  for (const forbidden of ['child_process', 'execFile', 'spawn(']) {
    assert.ok(!source.includes(forbidden), `lib/consoles.js must never reach for ${forbidden}`);
  }
});

test('the marker is the owner’s word, and the daemon never writes it', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-marker-'));
  try {
    const store = consoles.createStore({ dir, fetch: async () => { throw new Error('no probing here'); } });
    store.stop();
    assert.equal(false, store.applied());
    assert.equal(false, store.approval().applied);
    // Listing creates the store file (the seed). The marker it does not.
    store.list();
    assert.ok(fs.existsSync(consoles.storePath(dir)), 'the store file exists');
    assert.equal(false, fs.existsSync(consoles.markerPath(dir)), 'the marker does not — a daemon that wrote it would be claiming credit');

    fs.writeFileSync(consoles.markerPath(dir), '');   // the owner, after a netplan session
    assert.equal(true, store.applied());
    assert.equal(true, store.approval().applied, 'and the card flips without a restart');
  } finally {
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
    const probe = await within(10_000, consoles.probeConsole(
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
      const probe = await within(10_000, consoles.probeConsole({ id: 'x', url: `${base}/${code}` }), `probe ${code}`);
      assert.equal(true, probe.up, `${code} means something is serving`);
      assert.equal(code, probe.httpStatus);
      assert.ok(probe.latencyMs >= 0 && probe.latencyMs < 5_000, 'with a latency on it');
    }
    const broken = await within(10_000, consoles.probeConsole({ id: 'x', url: `${base}/503` }), 'probe 503');
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

  const refused = await within(10_000, consoles.probeConsole({ id: 'x', url: `http://127.0.0.1:${port}/` }), 'refused probe');
  assert.equal(false, refused.up);

  // A row whose address does not pass the rule can only exist by hand-editing
  // the store file. It is not fetched — a guard applied at the route and not at
  // the fetch is not a guard.
  let called = false;
  const probe = await consoles.probeConsole(
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
  const out = await within(10_000, consoles.probeAll(recs, { fetch: slowFetch }), 'probeAll');
  assert.equal(12, Object.keys(out).length, 'every console is probed');
  assert.ok(peak <= consoles.PROBE_CONCURRENCY, `peak in flight was ${peak}`);
});

test('the journal speaks on a state CHANGE and is quiet otherwise', () => {
  // A line every sweep is 288 a day per console, which is how a journal stops
  // being read.
  const rec = { id: 'armap' };
  const up = { up: true };
  const down = { up: false };
  assert.equal(null, consoles.probeChange(rec, undefined, up), 'a first observation that is fine says nothing');
  assert.match(consoles.probeChange(rec, undefined, down), /not answering/, 'a first observation that is not fine does');
  assert.equal(null, consoles.probeChange(rec, up, up));
  assert.equal(null, consoles.probeChange(rec, down, down), 'a console that is still down does not repeat itself');
  assert.match(consoles.probeChange(rec, up, down), /stopped answering/);
  assert.match(consoles.probeChange(rec, down, up), /answering again/);
});

// --------------------------------------------------------------- the store

test('the store seeds once, and an owner who empties it is not argued with', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'consoles-store-'));
  try {
    const store = consoles.createStore({ dir, fetch: async () => { throw new Error('no probing'); } });
    store.stop();
    assert.equal(4, store.list().length, 'the first list seeds the contract’s four');
    for (const c of store.list()) store.remove(c.id);
    assert.equal(0, store.list().length);
    assert.equal(0, store.list().length, 'and listing again does not put them back');
    assert.equal(0o600, fs.statSync(consoles.storePath(dir)).mode & 0o777, 'tmp+rename at 0600 like every other store here');
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
    const store = consoles.createStore({ dir, fetch: async () => ({ status: 200, body: null }) });
    store.stop();
    store.list();                                              // the seed writes
    const probed = await store.probeNow('armap');
    assert.equal(true, probed.up, 'precondition: there is an observation to leak');
    store.add({ name: 'Extra', url: 'http://huginn:9100/' });   // an add writes
    store.patch('armap', { version: 1, name: 'Armap' });        // a patch writes

    const raw = JSON.parse(fs.readFileSync(consoles.storePath(dir), 'utf8'));
    for (const c of raw.consoles) {
      assert.deepEqual(
        ['id', 'name', 'url', 'kind', 'notes', 'addedAt', 'version'],
        Object.keys(c),
        `${c.id} is stored as a record and nothing else`,
      );
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
