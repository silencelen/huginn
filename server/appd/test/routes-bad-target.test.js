'use strict';
// A request target the URL parser cannot read is the CALLER'S mistake: a 400,
// and the daemon goes on answering everybody else.
//
// Node's HTTP parser hands the request listener targets that `new URL` then
// refuses to parse — `//`, `//[`, an absolute form whose port is past 65535. The
// parse is the first thing the listener does, before the plaintext policy, the
// challenge and the token check, so it is reachable by anybody who can open the
// port. A throw there used to escape the async listener as an unhandled
// rejection, and Node exits on one: a single unauthenticated line stopped the
// daemon, and a handful in a row outran the service manager's restart limit.
//
// The requests go over a RAW socket because no HTTP client will send these
// targets on purpose — that is the point of them. Both transports, because the
// one port serves plain HTTP and TLS through two servers sharing one listener.
//
// SAFETY: throwaway daemons on kernel-assigned ports, each with a scratch data
// dir, a scratch ~/.claude, a private tmux socket and a token of its own.

require('./no-owner-contact'); // before any daemon is spawned
const { test, after } = require('node:test');
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

/** The targets that used to stop the daemon, each of which Node's parser accepts. */
const BAD_TARGETS = ['//', '//[', 'http://127.0.0.1:99999/v1/ping'];

const daemons = [];
after(async () => {
  for (const d of daemons) {
    if (d.proc.exitCode === null && d.proc.signalCode === null) {
      await new Promise((resolve) => {
        const hard = setTimeout(() => { try { d.proc.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
        const giveUp = setTimeout(resolve, 10_000);
        d.proc.once('exit', () => { clearTimeout(hard); clearTimeout(giveUp); resolve(); });
        try { d.proc.kill('SIGTERM'); } catch { resolve(); }
      });
    }
    fs.rmSync(d.tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
  }
});

/** A port the kernel just handed out, so this file never collides with another. */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => resolve(port)); });
  });
}

/**
 * TLS that trusts this file's own daemon and nothing else: the certificate it
 * minted is the only CA, and since that certificate names no host, the identity
 * checked in place of `127.0.0.1` is the certificate itself. A port lost to some
 * other listener between freePort() and the daemon's bind then fails the
 * handshake, and the token is never written to it.
 *
 * Read per call: the file does not exist until the daemon has started, and the
 * startup loop below retries a throw like any other not-yet.
 */
function trustOnly(d) {
  const pem = fs.readFileSync(path.join(d.tmp, 'data', 'tls', 'cert.pem'));
  const fp = new crypto.X509Certificate(pem).fingerprint256;
  return {
    ca: pem,
    checkServerIdentity: (_host, peer) => (peer.fingerprint256 === fp ? undefined
      : new Error(`not this test's daemon (certificate ${peer.fingerprint256})`)),
  };
}

/** One authenticated GET on a fresh connection. */
function get(d, { secure, path: p }) {
  const mod = secure ? https : http;
  return new Promise((resolve, reject) => {
    const r = mod.request({
      host: '127.0.0.1', port: d.port, path: p, method: 'GET', agent: false, ...(secure ? trustOnly(d) : {}),
      headers: { authorization: `Bearer ${d.token}` },
    }, (res) => {
      let b = '';
      res.on('data', (c) => { b += c; });
      res.on('end', () => {
        let body = null; try { body = JSON.parse(b); } catch { /* none */ }
        resolve({ status: res.statusCode, body });
      });
    });
    r.on('error', reject);
    r.setTimeout(5000, () => r.destroy(new Error('timeout')));
    r.end();
  });
}

async function startDaemon() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-badtarget-'));
  for (const dir of ['state', 'data']) fs.mkdirSync(path.join(tmp, dir));
  const token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  const proc = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-badtarget-${process.pid}-${port}`,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: ['ignore', logFd, logFd],
  });
  fs.closeSync(logFd);
  const d = { proc, tmp, token, port, log: () => fs.readFileSync(path.join(tmp, 'daemon.log'), 'utf8') };
  daemons.push(d);
  for (let i = 0; i < 300; i++) {
    if (proc.exitCode !== null) throw new Error(`daemon exited at start:\n${d.log()}`);
    try {
      const r = await get(d, { secure: true, path: '/v1/ping' });
      if (r.status === 200) return d;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`daemon never answered:\n${d.log()}`);
}

/**
 * Writes one request line by hand and reads whatever comes back until the peer
 * closes. `status` is null when the connection died without an answer, which is
 * what a daemon that crashed on the line looks like from here.
 */
function raw(d, { secure, target }) {
  return new Promise((resolve) => {
    const s = secure
      ? tls.connect({ host: '127.0.0.1', port: d.port, ...trustOnly(d) })
      : net.connect({ host: '127.0.0.1', port: d.port });
    let buf = '';
    let settled = false;
    const done = (error) => {
      if (settled) return;
      settled = true;
      const m = /^HTTP\/1\.1 (\d{3})/.exec(buf);
      const at = buf.indexOf('\r\n\r\n');
      let body = null;
      try { body = at >= 0 ? JSON.parse(buf.slice(at + 4)) : null; } catch { /* not json */ }
      resolve({ status: m ? Number(m[1]) : null, body, raw: buf, error: error ? error.message : null });
    };
    s.setTimeout(5000, () => { s.destroy(); done(new Error('timeout')); });
    s.on(secure ? 'secureConnect' : 'connect', () => {
      s.write(`GET ${target} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n`);
    });
    s.on('data', (c) => { buf += c; });
    s.on('end', () => done());
    s.on('close', () => done());
    s.on('error', (e) => done(e));
  });
}

for (const secure of [false, true]) {
  const via = secure ? 'TLS' : 'plain HTTP';
  test(`over ${via}, an unparseable request target is a 400 and the daemon keeps answering`, async () => {
    const d = await startDaemon();
    for (const target of BAD_TARGETS) {
      const r = await raw(d, { secure, target });
      assert.equal(r.status, 400, `${JSON.stringify(target)} over ${via}: ${r.error || r.raw.slice(0, 200) || 'no answer'}`);
      assert.equal(typeof (r.body && r.body.error), 'string', 'the error shape every other refusal uses');
      assert.ok(!r.raw.includes(d.token), 'nothing secret on an unauthenticated answer');
      assert.equal(d.proc.exitCode, null, `the daemon is still running after ${JSON.stringify(target)}`);
      for (const s of [true, false]) {
        const ping = await get(d, { secure: s, path: '/v1/ping' });
        assert.equal(ping.status, 200, `and still answers /v1/ping over ${s ? 'TLS' : 'plain HTTP'} after ${JSON.stringify(target)}`);
      }
    }
    for (const target of BAD_TARGETS) {
      assert.ok(d.log().includes(JSON.stringify(target)), `the log names ${JSON.stringify(target)}, quoted`);
    }
  });
}

test('a target that does parse is routed exactly as before', async () => {
  // The guard is for targets that cannot be read at all. An absolute form with a
  // real port and a scheme-relative target both name a path, and that path is
  // what is served, as it always was.
  const d = await startDaemon();
  for (const target of [`http://127.0.0.1:${d.port}/v1/ping`, '//elsewhere/v1/ping']) {
    for (const secure of [false, true]) {
      const r = await new Promise((resolve, reject) => {
        const mod = secure ? https : http;
        const q = mod.request({
          host: '127.0.0.1', port: d.port, path: target, method: 'GET', agent: false, ...(secure ? trustOnly(d) : {}),
          headers: { authorization: `Bearer ${d.token}` },
        }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
        q.on('error', reject);
        q.setTimeout(5000, () => q.destroy(new Error('timeout')));
        q.end();
      });
      assert.equal(r, 200, `${target} over ${secure ? 'TLS' : 'plain HTTP'}`);
    }
  }
});
