'use strict';
// POST /v1/uploads onto a store that fills up mid-file: the rest of the body is
// drained, so the caller reads the answer instead of a reset.
//
// ⚠ A DRAIN THAT RE-PAUSED ITSELF. A store failure is meant to drain the rest of
// the body (#42), and `stop()` does resume the request — but the 'data' listener
// was still attached, so the very next chunk went to `out.write()` on the stream
// `stop()` had just destroyed, which returns false, which paused the request
// again with nothing left to resume it. The 507 was written while the client was
// still sending; the client then sat on a full TCP window until the keep-alive
// timer reset the connection about 6 s later. A client that writes the whole
// body before reading (OkHttp's order, and the desktop's direct fallback) got a
// write error first, and the connection itself was dead for the next request.
// The #42 test sends 26 bytes, which never reaches a second chunk.
//
// The condition is a REAL full filesystem, a tmpfs a few hundred kilobytes big,
// because that is the shape of the bug; a host that cannot mount one skips.
//
// SAFETY: only the upload store is the small tmpfs, and it is unmounted in
// after(). A private tmux socket, a scratch HOME and Claude directory, and a
// port the kernel just handed out.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');

const BODY_BYTES = 16 * 1024 * 1024;   // far more than the socket buffers hold
const DEADLINE_MS = 5000;              // the reset this replaces came at ~6 s

let tmp, store, mounted = false, token, daemon, port;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-upfull-'));
  for (const d of ['data', 'state', 'claude', 'home', 'shim']) fs.mkdirSync(path.join(tmp, d));
  store = path.join(tmp, 'data', 'uploads');
  fs.mkdirSync(store);
  try {
    execFileSync('mount', ['-t', 'tmpfs', '-o', 'size=256k', 'tmpfs', store], { stdio: 'ignore' });
    mounted = true;
  } catch { return; }
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
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-upfull-${process.pid}`,
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
});

after(async () => {
  if (daemon) { daemon.kill('SIGKILL'); await wait(200); }
  if (mounted) { try { execFileSync('umount', [store], { stdio: 'ignore' }); } catch { /* busy or gone */ } }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/**
 * A client that writes the WHOLE body before it reads anything, then asks a
 * second question on the same connection. Every byte and every error is kept.
 */
function writeThenRead() {
  return new Promise((resolve) => {
    const sock = net.connect(port, '127.0.0.1');
    let got = '';
    let error = null;
    let sent = 0;
    let closed = false;
    const finish = (why) => { clearTimeout(timer); sock.destroy(); resolve({ got, error, sent, why }); };
    const timer = setTimeout(() => finish('deadline'), DEADLINE_MS);
    sock.setEncoding('latin1');
    sock.on('data', (c) => {
      got += c;
      if (/HTTP\/1\.1 507[\s\S]*HTTP\/1\.1 200/.test(got)) finish('answered twice');
    });
    sock.on('error', (e) => { error = error || e.code || e.message; });
    sock.on('close', () => { closed = true; finish('closed'); });
    sock.on('connect', async () => {
      sock.write('POST /v1/uploads?name=backup.bin HTTP/1.1\r\nHost: x\r\n'
        + `Authorization: Bearer ${token}\r\nContent-Type: application/octet-stream\r\n`
        + `Content-Length: ${BODY_BYTES}\r\n\r\n`);
      const chunk = Buffer.alloc(64 * 1024, 0x61);
      while (sent < BODY_BYTES && !closed && !error) {
        const n = Math.min(chunk.length, BODY_BYTES - sent);
        const flowing = sock.write(n === chunk.length ? chunk : chunk.subarray(0, n));
        sent += n;
        if (!flowing) {
          await new Promise((r) => { sock.once('drain', r); sock.once('close', r); });
        }
      }
      if (closed || error) return;
      // Only now does this client look at what came back — then it asks again.
      sock.write(`GET /v1/ping HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${token}\r\n\r\n`);
    });
  });
}

test('a full store drains the body: the client finishes sending, reads the 507, and the connection lives', async (t) => {
  if (!mounted) { t.skip('this host cannot mount a tmpfs, so a full store cannot be built'); return; }
  const r = await writeThenRead();
  assert.equal(null, r.error, `the client hit ${r.error} after ${r.sent} of ${BODY_BYTES} bytes (${r.why})`);
  assert.equal(BODY_BYTES, r.sent, `the whole body went out (${r.why})`);
  assert.match(r.got, /^HTTP\/1\.1 507 /, `the store is full, and the answer says so: ${r.got.slice(0, 200)}`);
  assert.match(r.got, /could not save the file \(ENOSPC\)/, 'with the errno');
  assert.match(r.got, /HTTP\/1\.1 507[\s\S]*HTTP\/1\.1 200 /,
    `and the same connection answers the next request (${r.why}): ${r.got.slice(0, 400)}`);
});
