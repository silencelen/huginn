'use strict';
// The daemon behind TLS (docs/TLS.md): the bound challenge, the pinned bearer
// channel, and the plaintext policy — against throwaway daemons on their own
// ports, data dirs and tmux sockets, like every other routes-* test.
const { test, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const https = require('node:https');
const path = require('node:path');
const crypto = require('node:crypto');

const daemons = [];
after(() => {
  for (const d of daemons) {
    try { d.proc.kill('SIGTERM'); } catch { /* gone */ }
    fs.rmSync(d.tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
  }
});

let portSeq = 0;
async function startDaemon({ bind = '127.0.0.1', env = {} } = {}) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-rtls-'));
  for (const d of ['state', 'data']) fs.mkdirSync(path.join(tmp, d));
  const token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = 10100 + (process.pid % 400) * 2 + (portSeq++);
  const proc = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: bind,
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-rtls-${process.pid}-${port}`,
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      ...env,
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  proc.stderr.on('data', (c) => { stderr += c; });
  const d = { proc, tmp, token, port, bind, stderr: () => stderr };
  daemons.push(d);
  const exited = new Promise((r) => proc.on('exit', (code) => r(code)));
  for (let i = 0; i < 300; i++) {
    const code = await Promise.race([exited, new Promise((r) => setTimeout(() => r('wait'), 100))]);
    if (code !== 'wait') { d.exitCode = code; return d; }
    try {
      const r = await req(d, { secure: true, path: '/v1/ping' });
      if (r.status === 200) break;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
  }
  d.fp = fs.existsSync(path.join(tmp, 'data', 'tls', 'cert.pem'))
    ? new crypto.X509Certificate(fs.readFileSync(path.join(tmp, 'data', 'tls', 'cert.pem'))).fingerprint256.replace(/:/g, '').toLowerCase()
    : null;
  return d;
}

/** One request on a fresh connection; reports the leaf fingerprint THIS handshake saw. */
function req(d, { secure, path: p, auth = true, host = d.bind }) {
  const mod = secure ? https : http;
  return new Promise((resolve, reject) => {
    const r = mod.request({
      host, port: d.port, path: p, method: 'GET', agent: false, rejectUnauthorized: false,
      headers: auth ? { authorization: `Bearer ${d.token}` } : {},
    }, (res) => {
      const pc = secure ? res.socket.getPeerCertificate() : null;
      let b = ''; res.on('data', (c) => { b += c; });
      res.on('end', () => {
        let body = null; try { body = JSON.parse(b); } catch { /* none */ }
        resolve({ status: res.statusCode, body, headers: res.headers, fp: pc && pc.fingerprint256 ? pc.fingerprint256.replace(/:/g, '').toLowerCase() : null });
      });
    });
    r.on('error', reject);
    r.setTimeout(5000, () => r.destroy(new Error('timeout')));
    r.end();
  });
}
const hmac = (key, s) => crypto.createHmac('sha256', key).update(s, 'utf8').digest('hex');

/**
 * A non-loopback address of this host, or null when there is none (skip).
 * A connected UDP socket's local address rather than os.networkInterfaces():
 * the latter needs netlink, which sandboxed runners refuse (EAFNOSUPPORT), and
 * connecting a UDP socket sends nothing.
 */
async function lanAddr() {
  const dgram = require('node:dgram');
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4');
    s.on('error', () => { s.close(); resolve(null); });
    s.connect(9, '192.0.2.1', () => {
      let a = null;
      try { a = s.address().address; } catch { /* none */ }
      s.close();
      resolve(a && !a.startsWith('127.') && a !== '0.0.0.0' ? a : null);
    });
  });
}

test('over https the challenge carries a proof bound to the certificate the client saw', async () => {
  const d = await startDaemon();
  const nonce = crypto.randomBytes(16).toString('hex');
  const r = await req(d, { secure: true, path: `/v1/challenge?nonce=${nonce}`, auth: false });
  assert.equal(r.status, 200);
  assert.ok(r.fp && r.fp === d.fp, 'the handshake presented the minted certificate');
  assert.equal(r.body.tls.v, 1);
  assert.equal(r.body.tls.proof, hmac(d.token, `huginn-tls-v1:${nonce}:${r.fp}`), 'verifiable from the client side alone');
  assert.notEqual(r.body.tls.proof, hmac(d.token, `huginn-tls-v1:${nonce}:${'0'.repeat(64)}`), 'and useless for any other certificate');
});

test('over plain http (loopback) the challenge has no tls proof — nothing to bind to', async () => {
  const d = await startDaemon();
  const nonce = crypto.randomBytes(16).toString('hex');
  const r = await req(d, { secure: false, path: `/v1/challenge?nonce=${nonce}`, auth: false });
  assert.equal(r.status, 200);
  assert.equal(r.body.proof, hmac(d.token, nonce), 'the legacy proof, for clients older than 3.11');
  assert.equal(r.body.tls, undefined);
});

test('the bearer works over https, and ping says the connection is secure', async () => {
  const d = await startDaemon();
  const s = await req(d, { secure: true, path: '/v1/ping' });
  assert.equal(s.status, 200);
  assert.equal(s.body.secure, true);
  const p = await req(d, { secure: false, path: '/v1/ping' });
  assert.equal(p.status, 200, 'loopback plaintext still works: hooks, the CLI over ssh');
  assert.equal(p.body.secure, false);
});

test('the certificate survives a restart, so pins do too', async () => {
  const d = await startDaemon();
  const fp = d.fp;
  d.proc.kill('SIGTERM');
  await new Promise((r) => d.proc.on('exit', r));
  const d2 = await startDaemon({ env: { HUGINN_APPD_DATA: path.join(d.tmp, 'data') } });
  // d2 has its own token; the cert lives in the data dir it was pointed at.
  const r = await req(d2, { secure: true, path: '/v1/ping', auth: false });
  assert.equal(r.fp, fp);
});

test('default policy: plain http from off this host is refused with 426, the challenge included', async (t) => {
  const lan = await lanAddr();
  if (!lan) return t.skip('no non-loopback IPv4 on this host');
  const d = await startDaemon({ bind: lan });
  const nonce = crypto.randomBytes(16).toString('hex');
  const c = await req(d, { secure: false, path: `/v1/challenge?nonce=${nonce}`, auth: false });
  assert.equal(c.status, 426);
  assert.equal(c.body.tls, true);
  assert.equal(c.body.proof, undefined, 'no proof an old client could adopt the address on');
  const p = await req(d, { secure: false, path: '/v1/ping' });
  assert.equal(p.status, 426, 'a token sent in the clear is not honoured');
  assert.equal(p.body.version, undefined);
  const s = await req(d, { secure: true, path: '/v1/ping' });
  assert.equal(s.status, 200, 'the same address over https works');
});

test('HUGINN_APPD_PLAINTEXT=any restores plain http from anywhere, for a migration window', async (t) => {
  const lan = await lanAddr();
  if (!lan) return t.skip('no non-loopback IPv4 on this host');
  const d = await startDaemon({ bind: lan, env: { HUGINN_APPD_PLAINTEXT: 'any' } });
  const p = await req(d, { secure: false, path: '/v1/ping' });
  assert.equal(p.status, 200);
});

test('a bad plaintext policy is fatal at start, not a silent default', async () => {
  const d = await startDaemon({ env: { HUGINN_APPD_PLAINTEXT: 'sure' } });
  assert.equal(d.exitCode, 1);
  assert.match(d.stderr(), /HUGINN_APPD_PLAINTEXT must be/);
});

test('an operator certificate that cannot be loaded is fatal — never a fallback to plain http', async () => {
  const d = await startDaemon({ env: { HUGINN_APPD_TLS_CERT: '/nonexistent/cert.pem', HUGINN_APPD_TLS_KEY: '/nonexistent/key.pem' } });
  assert.equal(d.exitCode, 1);
  assert.match(d.stderr(), /FATAL: tls: .*does not exist/);
});
