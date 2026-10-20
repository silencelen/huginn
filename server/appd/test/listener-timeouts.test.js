'use strict';
// How long a connection may sit on the daemon's one port without finishing a
// request it is allowed to make.
//
// ⚠ THE CHECKER NOBODY STARTED. Node enforces headersTimeout and requestTimeout
// with a timer each http/https server starts from its OWN 'listening' event.
// The daemon's two servers are never listen()ed — the sniffing listener hands
// them sockets with emit('connection') — so neither timer ever ran. Anyone who
// could reach the port could open connections, send one byte (or finish a TLS
// handshake) and never complete the request: no token check ever ran for them
// and nothing ever closed them. Before 3.11 the daemon listened directly and
// Node's defaults applied.
//
// Two layers, two files' worth of promises:
//   * lib/tls.js: the listener arms the servers it is handed, whatever their
//     timeouts are — asserted fast, with second-scale timeouts;
//   * the daemon: its own servers, with its own numbers — a header stall is cut
//     at Node's minute, a refused request's unread body does not hold the
//     connection, and neither rule touches a slow body that carries the token
//     or a refusal that had no body.
//
// SAFETY: no tmux session is made (a private socket is named anyway), `claude`
// on the daemon's PATH is an inert shim, HOME and the Claude directory are
// scratch, and every port is one the kernel just handed out.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const tls = require('node:tls');
const http = require('node:http');
const https = require('node:https');
const path = require('node:path');
const crypto = require('node:crypto');
const T = require('../lib/tls');

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}

/**
 * Open a connection, do `start(socket)` once it is up, and report when (and
 * whether) the far end closed it within `ms`, with the first line it said.
 */
function hold(connect, start, ms) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    let said = '';
    const sock = connect();
    const done = (closed) => {
      clearTimeout(timer);
      sock.destroy();
      resolve({ closed, ms: Date.now() - t0, said, first: said.split('\r\n')[0] });
    };
    const timer = setTimeout(() => done(false), ms);
    sock.on('data', (c) => { said += c.toString('latin1'); });
    sock.on('error', () => { /* the close says it */ });
    sock.on('close', () => done(true));
    sock.once(sock instanceof tls.TLSSocket ? 'secureConnect' : 'connect', () => start(sock));
  });
}
const plainTo = (port) => () => net.connect(port, '127.0.0.1');
const tlsTo = (port) => () => tls.connect({ port, host: '127.0.0.1', rejectUnauthorized: false });

// ------------------------------------------------------------- lib/tls.js

test('the sniffing listener arms the request timeouts of the servers it feeds', async () => {
  const m = T.loadOrCreate({ dataDir: fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ltmo-')) });
  const opts = { headersTimeout: 1000, requestTimeout: 0, connectionsCheckingInterval: 200 };
  const h = (req, res) => res.end('reached the handler');
  const L = T.createSniffingListener(net,
    http.createServer(opts, h),
    https.createServer({ ...opts, cert: m.cert, key: m.key, minVersion: 'TLSv1.2' }, h));
  await new Promise((r) => L.listen(0, '127.0.0.1', r));
  const port = L.address().port;
  try {
    const [plainG, tlsG, tlsSilent] = await Promise.all([
      hold(plainTo(port), (s) => s.write('G'), 4000),
      hold(tlsTo(port), (s) => s.write('G'), 4000),
      // A finished handshake and then nothing at all.
      hold(tlsTo(port), () => { }, 4000),
    ]);
    assert.equal(true, plainG.closed, `one plain byte must not hold a connection: open after ${plainG.ms} ms`);
    assert.match(plainG.first, /^HTTP\/1\.1 408 /, 'and the answer is the timeout Node writes');
    assert.equal(true, tlsG.closed, `nor one byte after a handshake: open after ${tlsG.ms} ms`);
    assert.match(tlsG.first, /^HTTP\/1\.1 408 /);
    assert.equal(true, tlsSilent.closed, `nor a handshake on its own: open after ${tlsSilent.ms} ms`);
  } finally { L.close(); }
});

// ------------------------------------------------------------- the daemon

let tmp, token, daemon, port;
const probes = {};

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ltmo-d-'));
  for (const d of ['data', 'state', 'claude', 'home', 'shim']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'shim', 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  port = await freePort();
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-ltmo-${process.pid}`,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/v1/ping`, { headers: { authorization: `Bearer ${token}` } });
      if (r.status === 200) break;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await wait(100);
  }

  // All at once, so the file costs one header timeout rather than the sum.
  //
  // A request that never finishes its headers, plain and over TLS. Node's
  // headersTimeout is a minute and its checker runs every 30 s, so the cut lands
  // between 60 and 90 s.
  probes.stallPlain = hold(plainTo(port), (s) => s.write('G'), 100_000);
  probes.stallTls = hold(tlsTo(port), (s) => s.write('GET /v1/sessions HTTP/1.1\r\nHost: x\r\n'), 100_000);
  // A request with no token, complete headers, and a body that never finishes:
  // the 401 comes at once, and then a byte every half second.
  probes.trickle = hold(plainTo(port), (s) => {
    s.write('POST /v1/sessions HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n'
      + 'Content-Length: 100000000\r\n\r\n');
    const iv = setInterval(() => { try { s.write('x'); } catch { /* closed */ } }, 500);
    s.on('close', () => clearInterval(iv));
  }, 25_000);
  // The control for that rule: the same slow body WITH the token is somebody
  // allowed to be slow — a 24-byte upload sent over twelve seconds lands.
  probes.slowAuthed = hold(plainTo(port), (s) => {
    s.write(`POST /v1/uploads?name=slow.txt HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${token}\r\n`
      + 'Content-Type: text/plain\r\nContent-Length: 24\r\n\r\n');
    let n = 0;
    const iv = setInterval(() => {
      try { s.write('y'); } catch { /* closed */ }
      if (++n === 24) clearInterval(iv);
    }, 500);
    s.on('close', () => clearInterval(iv));
  }, 18_000);
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a refusal with no body leaves the connection usable for the next request', async () => {
  // The client probes are exactly this: an unauthenticated GET, then the real
  // request — and nothing here may make them pay a new handshake for it.
  const r = await hold(plainTo(port), (s) => {
    s.write('GET /v1/ping HTTP/1.1\r\nHost: x\r\n\r\n');
    setTimeout(() => s.write(`GET /v1/ping HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${token}\r\n\r\n`), 300);
  }, 3000);
  assert.match(r.said, /^HTTP\/1\.1 401 /, 'the refusal');
  assert.match(r.said, /HTTP\/1\.1 401 [\s\S]*HTTP\/1\.1 200 /, `then an answer on the same connection: ${r.said.slice(0, 300)}`);
});

test('a refused request cannot hold its connection with a body it never finishes', async () => {
  const r = await probes.trickle;
  assert.match(r.first, /^HTTP\/1\.1 401 /, 'it was refused at once');
  assert.equal(true, r.closed, `and must not keep the connection while the body trickles: open after ${r.ms} ms`);
  assert.ok(r.ms < 20_000, `closed after ${r.ms} ms`);
});

test('a slow body that carries the token is not cut', async () => {
  const r = await probes.slowAuthed;
  assert.match(r.first, /^HTTP\/1\.1 200 /, `the upload landed: ${r.said.slice(0, 200)}`);
  assert.match(r.said, /"bytes":24/, 'all of it');
});

test('a request that never finishes its headers is cut at the header timeout, plain and over TLS', async () => {
  const [plain, secure] = await Promise.all([probes.stallPlain, probes.stallTls]);
  assert.equal(true, plain.closed, `one plain byte held a connection for ${plain.ms} ms`);
  assert.match(plain.first, /^HTTP\/1\.1 408 /);
  assert.equal(true, secure.closed, `half a request over TLS held a connection for ${secure.ms} ms`);
  assert.match(secure.first, /^HTTP\/1\.1 408 /);
});
