'use strict';
// huginn-device's half of the TLS contract (docs/dev/protocol-tls.md). Run with:
//   node --test scripts/test-device-tls.js
//
// WHY THIS FILE EXISTS. The runner sends /etc/huginn-appd/token — root on the
// host — on every request, from machines that are routinely on a flat LAN with
// no VPN under them. Every assertion below is one way that token could reach
// somebody who is not the daemon: a proof over the wrong certificate accepted,
// the legacy proof accepted over TLS, a remote http:// dialled as-is, a pin
// carried across a token change, or token bytes written to a socket before the
// certificate was judged. The last one is only provable against a REAL relay,
// so this file stands up a real appd from this tree and a real TLS proxy in
// front of it, and greps everything the proxy decrypted for the token.
//
// SAFETY: scratch dirs, a throwaway token, a PRIVATE tmux socket, ports the
// kernel picks. Every daemon this starts is killed in `after`, including on a
// failed assertion — a stray appd blocks the release gates.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const tls = require('node:tls');
const http = require('node:http');
const https = require('node:https');
const crypto = require('node:crypto');
const { spawn, execFileSync, spawnSync } = require('node:child_process');

const REPO = path.resolve(__dirname, '..');
const RUNNER = path.join(REPO, 'client', 'huginn-device');
const APPD = path.join(REPO, 'server', 'appd', 'huginn-appd.js');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'device-tls-'));
const DEV_DIR = path.join(tmp, 'device');
const TOKEN = crypto.randomBytes(32).toString('hex');
// Set BEFORE the require: CONF_DIR is fixed when the module loads.
process.env.HUGINN_DEVICE_DIR = DEV_DIR;
process.env.HUGINN_APPD_TOKEN = TOKEN;
// The one escape hatch (plain http to loopback) is off unless a test turns it
// on: a developer's shell must not change what is tested.
delete process.env.HUGINN_APPD_PLAINTEXT;
const dev = require(RUNNER);
const local = require(path.join(REPO, 'client', 'huginn-local'));

const hmac = (k, m) => crypto.createHmac('sha256', k).update(m, 'utf8').digest('hex');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const confPath = path.join(DEV_DIR, 'device.json');
const readConf = () => JSON.parse(fs.readFileSync(confPath, 'utf8'));
function writeConf(c) {
  fs.mkdirSync(DEV_DIR, { recursive: true });
  fs.writeFileSync(confPath, JSON.stringify(c, null, 2));
}

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
  });
}

/** A self-signed pair of our own: the impostor's certificate. */
function mintCert(name) {
  const key = path.join(tmp, `${name}.key`);
  const cert = path.join(tmp, `${name}.crt`);
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
    '-nodes', '-days', '1', '-subj', `/CN=${name}`, '-keyout', key, '-out', cert], { stdio: 'ignore' });
  const pem = fs.readFileSync(cert, 'utf8');
  return {
    key: fs.readFileSync(key, 'utf8'), cert: pem,
    fp: new crypto.X509Certificate(pem).fingerprint256.replace(/:/g, '').toLowerCase(),
  };
}

const servers = [];
function listen(srv) {
  servers.push(srv);
  return new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)));
}

/**
 * A fake daemon over https. `answer(nonce)` builds the challenge body; every
 * request that arrives is recorded, so a test can assert no Authorization ever
 * reached it.
 */
async function fakeDaemon(cert, answer) {
  const seen = [];
  const srv = https.createServer({ key: cert.key, cert: cert.cert }, (req, res) => {
    seen.push({ url: req.url, auth: req.headers.authorization || null });
    const u = new URL(req.url, 'https://x');
    if (u.pathname === '/v1/challenge') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(answer(u.searchParams.get('nonce'))));
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"ok":true}');
  });
  const port = await listen(srv);
  return { port, seen };
}

// ------------------------------------------------------------- the real appd

let appd = null;
let appdPort = 0;
const APPD_DATA = path.join(tmp, 'appd-data');

async function startAppd(tok = TOKEN) {
  fs.mkdirSync(APPD_DATA, { recursive: true });
  fs.mkdirSync(path.join(tmp, 'appd-state'), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'appd-token'), `${tok}\n`, { mode: 0o600 });
  if (!appdPort) appdPort = await freePort();
  const logFd = fs.openSync(path.join(tmp, 'appd.log'), 'a');
  appd = spawn(process.execPath, [APPD], {
    env: {
      ...process.env,
      HUGINN_APPD_TOKEN: undefined,
      HUGINN_APPD_PORT: String(appdPort),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: APPD_DATA,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'appd-token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'appd-state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'appd-claude'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-devtls-${process.pid}`,
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: ['ignore', logFd, logFd],
  });
  for (let i = 0; i < 300; i += 1) {
    if (appd.exitCode !== null) break;
    const up = await new Promise((r) => {
      const q = http.get(`http://127.0.0.1:${appdPort}/v1/ping`, (res) => { res.resume(); r(res.statusCode > 0); });
      q.on('error', () => r(false));
    });
    if (up) return;
    await wait(100);
  }
  throw new Error(`appd did not start (server-side, not this client): ${fs.readFileSync(path.join(tmp, 'appd.log'), 'utf8').slice(-1500)}`);
}

async function stopAppd() {
  if (!appd || appd.exitCode !== null) { appd = null; return; }
  const p = appd;
  await new Promise((resolve) => {
    const hard = setTimeout(() => { try { p.kill('SIGKILL'); } catch { /* gone */ } }, 3000);
    p.once('exit', () => { clearTimeout(hard); resolve(); });
    try { p.kill('SIGTERM'); } catch { resolve(); }
  });
  appd = null;
}

const appdFp = () => new crypto.X509Certificate(fs.readFileSync(path.join(APPD_DATA, 'tls', 'cert.pem')))
  .fingerprint256.replace(/:/g, '').toLowerCase();

/**
 * A TLS-terminating relay with its own certificate in front of appd: it
 * decrypts what the runner sends, records every byte, and forwards it over a
 * fresh TLS connection to the real daemon — the textbook interception, and the
 * one a relayed proof is supposed to defeat.
 */
async function mitmProxy(cert) {
  const got = [];
  const srv = tls.createServer({ key: cert.key, cert: cert.cert }, (c) => {
    const up = tls.connect({ host: '127.0.0.1', port: appdPort, rejectUnauthorized: false });
    c.on('data', (d) => { got.push(Buffer.from(d)); up.write(d); });
    up.on('data', (d) => c.write(d));
    const end = () => { c.destroy(); up.destroy(); };
    c.on('error', end); up.on('error', end); c.on('close', end); up.on('close', end);
  });
  const port = await listen(srv);
  return { port, plaintext: () => Buffer.concat(got).toString('latin1') };
}

before(async () => { await startAppd(); });

after(async () => {
  await stopAppd();
  for (const s of servers) { try { s.close(); } catch { /* closed */ } }
  spawnSync('tmux', ['-L', `huginn-devtls-${process.pid}`, 'kill-server'], { stdio: 'ignore' });
  fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
});

// ---------------------------------------------------------------- the maths

test('the shared test vector, byte for byte', () => {
  const v = dev.boundProof('a'.repeat(64), '00112233445566778899aabbccddeeff', '0'.repeat(64));
  assert.equal(v, '5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2');
  assert.ok(dev.proofMatches(v, v));
  assert.ok(dev.proofMatches(v.toUpperCase(), v), 'hex case is not a different proof');
  assert.ok(!dev.proofMatches(v.slice(0, 62) + '00', v));
  assert.ok(!dev.proofMatches(undefined, v), 'a missing proof is not a match');
  assert.ok(!dev.proofMatches('zz', v), 'malformed is not a match (and does not throw)');
});

// -------------------------------------------------------------- url upgrade

test('every http:// is dialled and stored as https://, a daemon on this machine included (scan F13)', () => {
  const remote = {
    'http://192.168.1.20:8787': 'https://192.168.1.20:8787',
    'http://[fd00::1]:8787': 'https://[fd00::1]:8787',
    'http://huginn.lan:8787': 'https://huginn.lan:8787',
    'https://10.0.0.5:8787': 'https://10.0.0.5:8787',
    'http://fd00::1:8787': 'http://fd00::1:8787',   // unparsable: left for urlRefusal to name
  };
  // Loopback has no identity: anything on this machine can hold the port while
  // appd restarts, so it is proved over TLS like anything else.
  const loopback = {
    'http://127.0.0.1:8787': 'https://127.0.0.1:8787',
    'http://127.9.9.9:8787': 'https://127.9.9.9:8787',
    'http://localhost:8787': 'https://localhost:8787',
    'http://[::1]:8787': 'https://[::1]:8787',
    'https://127.0.0.1:8787': 'https://127.0.0.1:8787',
  };
  for (const [from, to] of Object.entries({ ...remote, ...loopback })) {
    assert.equal(dev.upgradeUrl(from), to, `runner: ${from}`);
    // huginn-local hands the runner the url it stores; whatever it stores, the
    // runner dials the same thing.
    assert.equal(dev.upgradeUrl(local.daemonUrl(from)), to, `what huginn-local stores is dialled the same: ${from}`);
  }
  for (const [from, to] of Object.entries(remote)) assert.equal(local.daemonUrl(from), to, `huginn-local agrees: ${from}`);
  // A 127-prefixed NAME is not loopback.
  assert.equal(dev.upgradeUrl('http://127.example.com:8787'), 'https://127.example.com:8787');
});

test('a stored http:// url is rewritten as https:// on load, a loopback one included', () => {
  writeConf({ url: 'http://192.168.1.20:8787', name: 'x' });
  assert.equal(dev.loadConf().url, 'https://192.168.1.20:8787');
  assert.equal(readConf().url, 'https://192.168.1.20:8787', 'and written back, so status shows it');
  writeConf({ url: 'http://127.0.0.1:8787', name: 'x' });
  assert.equal(dev.loadConf().url, 'https://127.0.0.1:8787');
  assert.equal(readConf().url, 'https://127.0.0.1:8787', 'the host itself keeps its route, over TLS');
});

test('`on --url http://<remote>` and `on --url http://127.0.0.1:<port>` store https://', () => {
  for (const [typed, stored] of [['http://192.0.2.1:9', 'https://192.0.2.1:9'], ['http://127.0.0.1:9', 'https://127.0.0.1:9']]) {
    const d = fs.mkdtempSync(path.join(tmp, 'on-'));
    // Nothing listens there: the enrolment fails, but the url is saved first.
    spawnSync(process.execPath, [RUNNER, 'on', '--url', typed], {
      env: { ...process.env, HUGINN_DEVICE_DIR: d }, encoding: 'utf8', timeout: 30000,
    });
    assert.equal(JSON.parse(fs.readFileSync(path.join(d, 'device.json'), 'utf8')).url, stored, typed);
  }
});

// --------------------------------------------------------------------- pins

test('pins: newest first, capped at four, deduplicated', () => {
  writeConf({ url: 'https://127.0.0.1:1' });
  const fps = [1, 2, 3, 4, 5].map((n) => String(n).repeat(64));
  for (const f of fps) dev.addPin(TOKEN, f);
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [fps[4], fps[3], fps[2], fps[1]]);
  assert.equal(dev.MAX_PINS, 4);
  dev.addPin(TOKEN, fps[2]);
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [fps[2], fps[4], fps[3], fps[1]], 're-proving moves to front');
  assert.ok(!JSON.stringify(readConf()).includes(TOKEN), 'the config never holds the token');
});

test('pins belong to the token: a different token sees none, and its first pin replaces the set', () => {
  writeConf({ url: 'https://127.0.0.1:1' });
  dev.addPin(TOKEN, 'a'.repeat(64));
  const other = crypto.randomBytes(32).toString('hex');
  assert.deepEqual(dev.pinsFor(readConf(), other), []);
  dev.addPin(other, 'b'.repeat(64));
  assert.deepEqual(dev.pinsFor(readConf(), other), ['b'.repeat(64)]);
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [], 'and the old token\'s pins are gone, not merged');
});

// ------------------------------------------------- impostors (fake daemons)

test('MITM: a proof over a DIFFERENT certificate is refused, and the token never sent', async () => {
  const m = mintCert('mitm');
  const realFp = 'f'.repeat(64);
  // The relay case: a correct proof, but over the real daemon's fingerprint,
  // with the real fp reported beside it. Rule 1: hash what WE saw.
  const fake = await fakeDaemon(m, (n) => ({
    proof: hmac(TOKEN, n), version: '3.11.0', tls: { v: 1, fp: realFp, proof: dev.boundProof(TOKEN, n, realFp) },
  }));
  writeConf({ url: `https://127.0.0.1:${fake.port}` });
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsIntercepted && e.message.includes(dev.INTERCEPTED));
  assert.ok(fake.seen.length >= 1, 'the challenge was asked');
  assert.ok(fake.seen.every((r) => r.auth === null), 'no Authorization ever reached the impostor');
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [], 'nothing was pinned');
});

test('a peer that accepts TCP and never finishes TLS fails the request instead of hanging it (scan F1)', async () => {
  // Already pinned, so api() goes straight to the pinned channel — the path the
  // serve loop sits on. The listener accepts and then says nothing at all.
  const held = [];
  const silent = net.createServer((sock) => { held.push(sock); sock.on('error', () => { }); });
  await new Promise((r) => silent.listen(0, '127.0.0.1', r));
  try {
    writeConf({ url: `https://127.0.0.1:${silent.address().port}` });
    dev.addPin(TOKEN, 'a'.repeat(64));
    const t0 = Date.now();
    await assert.rejects(dev.api('GET', '/v1/ping', null, { timeoutMs: 600 }), /did not finish within 600 ms|timed out/);
    assert.ok(Date.now() - t0 < 5000, `bounded by the request's timeout (took ${Date.now() - t0} ms)`);
  } finally {
    for (const s of held) s.destroy();
    silent.close();
  }
});

test('legacy-only answer over https (no tls object) is NOT a proof', async () => {
  const c = mintCert('legacy');
  const fake = await fakeDaemon(c, (n) => ({ proof: hmac(TOKEN, n), version: '3.10.3' }));
  writeConf({ url: `https://127.0.0.1:${fake.port}` });
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsOldDaemon && /older than 3\.11/.test(e.message));
  assert.ok(fake.seen.every((r) => r.auth === null));
});

test('a plain-HTTP-only daemon (pre-3.11) is reported as old, never downgraded to', async () => {
  const seen = [];
  const srv = http.createServer((req, res) => { seen.push(req.headers.authorization || null); res.end('{}'); });
  const port = await listen(srv);
  writeConf({ url: `https://127.0.0.1:${port}` });
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsOldDaemon === true);
  assert.deepEqual(seen, [], 'not one HTTP request reached it');
});

test('a correct proof pins, and the pinned request carries the token', async () => {
  const c = mintCert('good');
  const fake = await fakeDaemon(c, (n) => ({ proof: hmac(TOKEN, n), version: '3.11.0', tls: { v: 1, fp: c.fp, proof: dev.boundProof(TOKEN, n, c.fp) } }));
  writeConf({ url: `https://127.0.0.1:${fake.port}` });
  await dev.api('GET', '/v1/ping');
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [c.fp]);
  assert.equal(fake.seen[0].url.startsWith('/v1/challenge'), true);
  assert.equal(fake.seen[0].auth, null, 'the probe carries no token');
  assert.equal(fake.seen[1].auth, `Bearer ${TOKEN}`);
});

// ------------------------------------------- loopback squatters (scan F13)

/**
 * What grabs 127.0.0.1:<port> while appd is down: plain HTTP, answering every
 * request the way a daemon would, and handing out act work to whoever polls.
 */
async function squatter() {
  const seen = [];
  const srv = http.createServer((req, res) => {
    seen.push({ url: req.url, auth: req.headers.authorization || null });
    res.writeHead(200, { 'Content-Type': 'application/json', 'X-Huginn-Appd': '9.9.9' });
    res.end(JSON.stringify({ ok: true, id: 'dev1', scope: 'work', work: { id: 'w1', chatId: 'c1', prompt: 'hi', mode: 'act' } }));
  });
  const port = await listen(srv);
  return { port, seen };
}

test('F13: a plain-http squatter on 127.0.0.1 is never sent the token, by any call', async () => {
  const sq = await squatter();
  writeConf({ url: `http://127.0.0.1:${sq.port}`, id: 'dev1', scope: 'work' });
  assert.equal(dev.loadConf().url, `https://127.0.0.1:${sq.port}`, 'a loopback http:// is dialled as https://');
  for (const call of [
    () => dev.enrol(dev.loadConf()),
    () => dev.api('GET', '/v1/devices/dev1/work?wait=1&locked=0', undefined, { timeoutMs: 5000 }),
    () => dev.api('POST', '/v1/devices/dev1/beat', { locked: false, scope: 'work' }),
  ]) {
    await assert.rejects(call(), (e) => e.tlsOldDaemon === true
      && e.message.includes(`${dev.PLAINTEXT_ENV}=local`) && /another program/.test(e.message), 'refused, and says why');
  }
  assert.deepEqual(sq.seen, [], 'not one request reached it, let alone a token');
});

test('F13: a TLS squatter on a loopback http:// is the interception case, and gets no token', async () => {
  const m = mintCert('squatter');
  const realFp = 'f'.repeat(64);
  const fake = await fakeDaemon(m, (n) => ({
    proof: hmac(TOKEN, n), version: '3.11.0', tls: { v: 1, fp: realFp, proof: dev.boundProof(TOKEN, n, realFp) },
  }));
  writeConf({ url: `http://127.0.0.1:${fake.port}` });
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsIntercepted === true);
  assert.ok(fake.seen.length >= 1 && fake.seen.every((r) => r.auth === null), 'only the tokenless probe reached it');
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), []);
});

// Where this token's daemon has proved itself over TLS before, an answer with no
// TLS cannot be "a daemon older than 3.11": the pin says it was not. Telling the
// reader to update a current daemon, on the word of whoever took the port, is
// the wrong advice; it is the interception sentence instead.
test('a plain-HTTP answer where this token has a pin is the interception case, not an old daemon', async () => {
  const sq = await squatter();
  writeConf({ url: `https://127.0.0.1:${sq.port}`, id: 'dev1', scope: 'work' });
  dev.addPin(TOKEN, 'a'.repeat(64));
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsIntercepted === true && !e.tlsOldDaemon
    && /did not answer TLS, and the daemon pinned for this token does/.test(e.message)
    && !/older than 3\.11/.test(e.message), 'named an interception');
  assert.deepEqual(sq.seen, [], 'not one request reached it');
});

test('a re-proof that gets no TLS proof, for a token that has a pin, is the interception case', async () => {
  const c = mintCert('legacy-after-pin');
  const fake = await fakeDaemon(c, (n) => ({ proof: hmac(TOKEN, n), version: '3.10.3' }));
  writeConf({ url: `https://127.0.0.1:${fake.port}` });
  dev.addPin(TOKEN, 'b'.repeat(64));
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsIntercepted === true
    && /without TLS proof, and the daemon pinned for this token does/.test(e.message)
    && !/older than 3\.11/.test(e.message), 'named an interception');
  assert.ok(fake.seen.length >= 1 && fake.seen.every((r) => r.auth === null), 'only the tokenless probe reached it');
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), ['b'.repeat(64)], 'nothing new was pinned');
});

test('F13 end to end: `serve` pointed at a loopback squatter sends no token and runs none of its work', { skip: process.platform === 'win32' }, async () => {
  const sq = await squatter();
  const d = fs.mkdtempSync(path.join(tmp, 'serve-'));
  const marker = path.join(d, 'ran');
  const claude = path.join(d, 'claude');
  fs.writeFileSync(claude, `#!/bin/sh\ntouch '${marker}'\ncat >/dev/null\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(d, 'device.json'), JSON.stringify({
    url: `http://127.0.0.1:${sq.port}`, id: 'dev1', scope: 'work', root: d, claude, name: 'serve-squat',
  }));
  const child = spawn(process.execPath, [RUNNER, 'serve'], {
    env: { ...process.env, HUGINN_DEVICE_DIR: d }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (b) => { out += b; });
  child.stderr.on('data', (b) => { out += b; });
  try {
    // The old runner enrolled, polled and spawned within a second or two.
    for (let i = 0; i < 50 && !/not reaching huginn/.test(out) && !fs.existsSync(marker); i += 1) await wait(100);
    await wait(1000);
  } finally {
    child.kill('SIGKILL');
  }
  assert.ok(!fs.existsSync(marker), `the squatter's act work was run:\n${out}`);
  assert.deepEqual(sq.seen, [], 'nothing reached the squatter');
  assert.match(out, /not reaching huginn: .*older than 3\.11.*HUGINN_APPD_PLAINTEXT=local/, out);
});

test('HUGINN_APPD_PLAINTEXT=local is the old behaviour, on purpose and only to loopback', async () => {
  const sq = await squatter();
  try {
    process.env.HUGINN_APPD_PLAINTEXT = 'local';
    assert.equal(dev.plaintextLoopback(), true);
    assert.equal(dev.upgradeUrl('http://127.0.0.1:8787'), 'http://127.0.0.1:8787');
    assert.equal(dev.upgradeUrl('http://192.168.1.20:8787'), 'https://192.168.1.20:8787', 'never another host');
    assert.equal(dev.upgradeUrl('https://127.0.0.1:8787'), 'https://127.0.0.1:8787', 'never a downgrade');
    assert.ok(dev.plainRefusal(new URL('http://192.0.2.1:8787')), 'the socket-side check refuses a remote host too');
    writeConf({ url: `http://127.0.0.1:${sq.port}` });
    await dev.api('GET', '/v1/ping');
    assert.deepEqual(sq.seen.map((r) => r.auth), [`Bearer ${TOKEN}`], 'the opted-in plain request carries the token');
    for (const v of ['any', '1', 'true', 'yes', '']) {
      process.env.HUGINN_APPD_PLAINTEXT = v;
      assert.equal(dev.plaintextLoopback(), false, `"${v}" is not the switch`);
    }
  } finally {
    delete process.env.HUGINN_APPD_PLAINTEXT;
  }
  assert.match(dev.plainRefusal(new URL('http://127.0.0.1:1')), /HUGINN_APPD_PLAINTEXT=local/);
});

// ------------------- a peer that has not proved itself: bounded, and shown not obeyed
//
// The challenge is the one exchange with a peer nobody has proved yet. Its
// answer used to be read whole, with only an idle timeout: a peer dripping a
// byte at a time stalled the serve loop for good, and one streaming without
// end grew this process. And whatever it (or the daemon) said reached the log
// and the terminal with its control sequences intact.

/** A TLS listener that hands each request's raw socket to `onRequest`, once its head is in. */
async function rawTls(cert, onRequest) {
  const socks = [];
  const srv = tls.createServer({ key: cert.key, cert: cert.cert }, (s) => {
    socks.push(s);
    s.on('error', () => { });
    let head = '';
    let answered = false;
    s.on('data', (d) => {
      head += d;
      if (!answered && head.includes('\r\n\r\n')) { answered = true; onRequest(s); }
    });
  });
  const port = await listen(srv);
  return { port, kill: () => { for (const s of socks) s.destroy(); } };
}

test('a challenge answer that drips is ended by its deadline instead of stalling the runner', { timeout: 15000 }, async () => {
  const timers = [];
  const peer = await rawTls(mintCert('drip'), (s) => {
    s.write('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{');
    // A byte every 100 ms: the idle timeout never fires.
    const t = setInterval(() => { if (!s.destroyed) s.write(' '); }, 100);
    t.unref();
    timers.push(t);
  });
  try {
    const t0 = Date.now();
    await assert.rejects(dev.proveDaemon(`https://127.0.0.1:${peer.port}`, TOKEN, { deadlineMs: 800 }), /within 800 ms/);
    assert.ok(Date.now() - t0 < 4000, `bounded by the deadline (took ${Date.now() - t0} ms)`);
    assert.ok(dev.CHALLENGE_DEADLINE_MS > 0 && dev.CHALLENGE_DEADLINE_MS <= 30000, 'and the default deadline is a real one');
  } finally {
    for (const t of timers) clearInterval(t);
    peer.kill();
  }
});

test('a challenge answer bigger than a few KiB is cut off, not read into memory', { timeout: 20000 }, async () => {
  const chunk = Buffer.alloc(64 * 1024, 0x20);
  let sent = 0;
  const peer = await rawTls(mintCert('flood'), (s) => {
    s.write('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n');
    const pump = () => {
      while (!s.destroyed && sent < 64 * 1024 * 1024) {
        sent += chunk.length;
        if (!s.write(chunk)) { s.once('drain', pump); return; }
      }
    };
    pump();
  });
  try {
    const t0 = Date.now();
    await assert.rejects(dev.proveDaemon(`https://127.0.0.1:${peer.port}`, TOKEN), /ran past \d+ bytes/);
    assert.ok(Date.now() - t0 < 5000, `cut off at once (took ${Date.now() - t0} ms)`);
    assert.ok(dev.CHALLENGE_MAX_BYTES <= 8192, 'a few KiB: appd answers in about 300 bytes');
  } finally { peer.kill(); }
});

// What a terminal ACTS on: erase-line and CR (rewrite a line), OSC 52 (set the
// clipboard) and OSC 0 (retitle) ended by BEL, a one-byte C1 CSI, DEL, NUL.
const HOSTILE = 'boom\u001b[2K\rALL CLEAR\u001b]52;c;cHduZWQ=\u0007\u001b]0;owned\u0007\u009b31mred\u007f\u0000 end';
const CONTROLS = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/;

async function hostilePeer() {
  const c = mintCert('hostile');
  const srv = https.createServer({ key: c.key, cert: c.cert }, (req, res) => {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end(HOSTILE);
  });
  return listen(srv);
}

test('an unproved peer\'s answer reaches an error message with its control sequences blanked', async () => {
  const port = await hostilePeer();
  writeConf({ url: `https://127.0.0.1:${port}` });
  await assert.rejects(dev.api('GET', '/v1/ping'), (e) => {
    assert.match(e.message, /challenge at \S+ answered 500 boom/);
    assert.ok(!CONTROLS.test(e.message), JSON.stringify(e.message));
    return true;
  });
  assert.equal(dev.termSafe('a\tb\nc'), 'a\tb\nc', 'newline and tab are text, and stay');
  assert.equal(dev.termSafe(HOSTILE).length, HOSTILE.length, 'blanked, not deleted: the text keeps its shape');
  assert.ok(!CONTROLS.test(dev.termSafe(HOSTILE)));
});

test('serve logs an unproved peer\'s answer without its control sequences', { skip: process.platform === 'win32' }, async () => {
  const port = await hostilePeer();
  const d = fs.mkdtempSync(path.join(tmp, 'ctl-serve-'));
  fs.writeFileSync(path.join(d, 'device.json'), JSON.stringify({ url: `https://127.0.0.1:${port}`, name: 'ctl', scope: 'look' }));
  const child = spawn(process.execPath, [RUNNER, 'serve'], { env: { ...process.env, HUGINN_DEVICE_DIR: d }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  child.stdout.on('data', (b) => { out += b; });
  child.stderr.on('data', (b) => { out += b; });
  try {
    for (let i = 0; i < 100 && !/not reaching huginn/.test(out); i += 1) await wait(100);
  } finally {
    child.kill('SIGKILL');
  }
  assert.match(out, /not reaching huginn: challenge at \S+ answered 500 boom/, out);
  const bad = [...out].filter((ch) => CONTROLS.test(ch));
  assert.deepEqual(bad.map((ch) => ch.codePointAt(0).toString(16)), [], 'no control but newline and tab reached the log');
});

test('status shows the daemon\'s words without their control sequences', async () => {
  const c = mintCert('status');
  const srv = https.createServer({ key: c.key, cert: c.cert }, (req, res) => {
    const u = new URL(req.url, 'https://x');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    if (u.pathname === '/v1/challenge') {
      const n = u.searchParams.get('nonce');
      return res.end(JSON.stringify({ proof: hmac(TOKEN, n), version: '3.13.2', tls: { v: 1, fp: c.fp, proof: dev.boundProof(TOKEN, n, c.fp) } }));
    }
    return res.end(JSON.stringify({ online: true, scope: 'work\u001b]0;owned\u0007', effectiveScope: 'look\u009b2J\r', queued: 0 }));
  });
  const port = await listen(srv);
  const d = fs.mkdtempSync(path.join(tmp, 'ctl-status-'));
  fs.writeFileSync(path.join(d, 'device.json'), JSON.stringify({ url: `https://127.0.0.1:${port}`, name: 'ctl', scope: 'look', id: 'dev9' }));
  const st = await new Promise((resolve) => {
    const child = spawn(process.execPath, [RUNNER, 'status'], { env: { ...process.env, HUGINN_DEVICE_DIR: d }, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', (b) => { out += b; });
    child.stderr.on('data', (b) => { out += b; });
    child.on('close', () => resolve(out));
  });
  assert.match(st, /huginn sees it as reachable, scope work +\]0;owned +\(-> look +2J +right now\)/, st);
  assert.ok(!CONTROLS.test(st), JSON.stringify(st));
});

// --------------------------------------------------- end to end, real appd

test('E2E: enrol against a real appd over https, pin its own certificate, long-poll', async () => {
  fs.rmSync(DEV_DIR, { recursive: true, force: true });
  const r = spawnSync(process.execPath, [RUNNER, 'on', '--url', `https://127.0.0.1:${appdPort}`, '--scope', 'look', '--name', 'tls-e2e'], {
    env: { ...process.env }, encoding: 'utf8', timeout: 60000,
  });
  assert.equal(r.status, 0, `on failed: ${r.stdout}\n${r.stderr}`);
  const conf = readConf();
  assert.ok(conf.id, 'enrolled');
  assert.deepEqual(dev.pinsFor(conf, TOKEN), [appdFp()], 'the pin is the daemon\'s own leaf');
  const w = await dev.api('GET', `/v1/devices/${conf.id}/work?wait=1&locked=0`, undefined, { timeoutMs: 10000 });
  assert.ok(w && typeof w === 'object', 'the long poll answers over the pinned channel');
  const st = spawnSync(process.execPath, [RUNNER, 'status'], { env: { ...process.env }, encoding: 'utf8', timeout: 30000 });
  assert.match(st.stdout, new RegExp(`tls +pinned sha256 ${appdFp()}`));
});

test('E2E F13: `on --url http://127.0.0.1:<port>` (the host itself) proves the real appd over TLS and stores https://', async () => {
  const d = fs.mkdtempSync(path.join(tmp, 'host-'));
  const r = spawnSync(process.execPath, [RUNNER, 'on', '--url', `http://127.0.0.1:${appdPort}`, '--scope', 'look', '--name', 'tls-host'], {
    env: { ...process.env, HUGINN_DEVICE_DIR: d }, encoding: 'utf8', timeout: 60000,
  });
  assert.equal(r.status, 0, `on failed: ${r.stdout}\n${r.stderr}`);
  const conf = JSON.parse(fs.readFileSync(path.join(d, 'device.json'), 'utf8'));
  assert.equal(conf.url, `https://127.0.0.1:${appdPort}`, 'stored as it is dialled');
  assert.ok(conf.id, 'enrolled');
  assert.deepEqual(dev.pinsFor(conf, TOKEN), [appdFp()], 'the pin is the daemon\'s own leaf');
});

test('E2E MITM: a TLS relay in front of the real appd is refused, and gets no token', async () => {
  const proxy = await mitmProxy(mintCert('relay'));
  const pinned = readConf();
  // Twice: once already pinned (the pin check refuses, the re-proof fails), and
  // once from nothing (the first proof fails). Neither may write the token.
  for (const start of [pinned, { url: '' }]) {
    writeConf({ ...start, url: `https://127.0.0.1:${proxy.port}` });
    await assert.rejects(dev.api('GET', '/v1/ping'), (e) => e.tlsIntercepted === true, JSON.stringify(start.tls || null));
  }
  const seen = proxy.plaintext();
  assert.match(seen, /GET \/v1\/challenge\?nonce=/, 'the relay did see the probe');
  assert.ok(!seen.includes(TOKEN), 'the token never reached the relay');
  assert.ok(!/authorization/i.test(seen), 'nor did any Authorization header');
  writeConf(pinned);
});

test('E2E rotation: the daemon mints a new certificate; the runner re-proves and pins it', async () => {
  const before = appdFp();
  await stopAppd();
  fs.rmSync(path.join(APPD_DATA, 'tls'), { recursive: true, force: true });
  await startAppd();
  const after = appdFp();
  assert.notEqual(after, before);
  const conf = readConf();
  await dev.api('GET', `/v1/devices/${conf.id}`);
  assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [after, before], 'new pin first, old kept');
});

test('E2E token change: pins are cleared and the new token proves afresh', async () => {
  // Re-pairing: appd keeps its certificate, the runner gets a new token. The
  // old pin set must not be trusted under it.
  const conf = readConf();
  const t2 = crypto.randomBytes(32).toString('hex');
  assert.deepEqual(dev.pinsFor(conf, t2), []);
  await stopAppd();
  const prev = process.env.HUGINN_APPD_TOKEN;
  try {
    process.env.HUGINN_APPD_TOKEN = t2;
    await startAppd(t2);
    // The device row may or may not survive; only the proof matters here.
    await dev.api('GET', `/v1/devices/${conf.id}`).catch((e) => { if (e.tlsIntercepted || e.pinMismatch) throw e; });
    assert.deepEqual(dev.pinsFor(readConf(), t2), [appdFp()]);
    assert.deepEqual(dev.pinsFor(readConf(), TOKEN), [], 'the old token\'s pins are gone');
  } finally {
    process.env.HUGINN_APPD_TOKEN = prev;
  }
});
