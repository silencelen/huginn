'use strict';
// lib/tls.js on its own: the proof, the certificate store, the policy, and the
// one-port listener. The daemon-level behaviour is in routes-tls.test.js.
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const http = require('node:http');
const https = require('node:https');
const path = require('node:path');
const T = require('../lib/tls');

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'appd-tls-'));

test('the bound proof matches the vector every client asserts (docs/dev/protocol-tls.md)', () => {
  assert.equal(
    T.boundProof('a'.repeat(64), '00112233445566778899aabbccddeeff', '0'.repeat(64)),
    '5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2');
});

test('the bound proof depends on the fingerprint — a relayed proof does not transfer', () => {
  const tok = 'b'.repeat(64);
  assert.notEqual(T.boundProof(tok, 'ab'.repeat(16), '1'.repeat(64)), T.boundProof(tok, 'ab'.repeat(16), '2'.repeat(64)));
});

test('first start mints a key pair: key 0600 in a 0700 dir, fingerprint = sha256 of the DER', () => {
  const d = tmpdir();
  const m = T.loadOrCreate({ dataDir: d });
  assert.equal(m.minted, true);
  assert.equal(fs.statSync(m.keyFile).mode & 0o777, 0o600);
  assert.equal(fs.statSync(path.dirname(m.keyFile)).mode & 0o777, 0o700);
  const der = new (require('node:crypto').X509Certificate)(m.cert).raw;
  assert.equal(m.fp, require('node:crypto').createHash('sha256').update(der).digest('hex'));
  assert.deepEqual(fs.readdirSync(path.dirname(m.keyFile)).sort(), ['cert.pem', 'key.pem'], 'no temp files left');
});

test('a second start reuses the pair, so pins survive a restart', () => {
  const d = tmpdir();
  const a = T.loadOrCreate({ dataDir: d });
  const b = T.loadOrCreate({ dataDir: d });
  assert.equal(b.minted, false);
  assert.equal(a.fp, b.fp);
});

test('a key that does not belong to the certificate fails at start, not at the first handshake', () => {
  const a = T.loadOrCreate({ dataDir: tmpdir() });
  const b = T.loadOrCreate({ dataDir: tmpdir() });
  assert.throws(() => T.loadOrCreate({ dataDir: tmpdir(), certFile: a.certFile, keyFile: b.keyFile }), /not the private key/);
});

test('operator-supplied files: both or neither, and missing ones are named — never silently minted over', () => {
  const a = T.loadOrCreate({ dataDir: tmpdir() });
  assert.throws(() => T.loadOrCreate({ dataDir: tmpdir(), certFile: a.certFile }), /together/);
  assert.throws(() => T.loadOrCreate({ dataDir: tmpdir(), certFile: '/nonexistent/c.pem', keyFile: a.keyFile }), /\/nonexistent\/c\.pem does not exist/);
  const ok = T.loadOrCreate({ dataDir: tmpdir(), certFile: a.certFile, keyFile: a.keyFile });
  assert.equal(ok.fp, a.fp);
  assert.equal(ok.minted, false);
});

test('plaintext policy: local by default, any on request, anything else refused', () => {
  assert.equal(T.plaintextPolicy({}), 'local');
  assert.equal(T.plaintextPolicy({ HUGINN_APPD_PLAINTEXT: 'ANY' }), 'any');
  assert.throws(() => T.plaintextPolicy({ HUGINN_APPD_PLAINTEXT: 'yes' }), /must be "local" or "any"/);
});

test('loopback means this host only', () => {
  for (const a of ['127.0.0.1', '127.8.9.1', '::1', '::ffff:127.0.0.1']) assert.equal(T.isLoopback(a), true, a);
  for (const a of ['192.168.7.117', '::ffff:192.168.7.117', '10.42.0.1', 'fd00::1', '', undefined, '1127.0.0.1']) {
    assert.equal(T.isLoopback(a), false, String(a));
  }
});

test('one port serves both protocols, connection by connection', async () => {
  const m = T.loadOrCreate({ dataDir: tmpdir() });
  const h = (req, res) => res.end(String(Boolean(req.socket.encrypted)));
  const L = T.createSniffingListener(net, http.createServer(h), https.createServer({ cert: m.cert, key: m.key }, h));
  await new Promise((r) => L.listen(0, '127.0.0.1', r));
  const port = L.address().port;
  const get = (mod) => new Promise((resolve, reject) => {
    mod.get({ host: '127.0.0.1', port, path: '/', rejectUnauthorized: false, agent: false }, (res) => {
      const pc = res.socket.getPeerCertificate ? res.socket.getPeerCertificate() : null;
      let b = ''; res.on('data', (c) => { b += c; });
      res.on('end', () => resolve({ body: b, fp: pc && pc.fingerprint256 && pc.fingerprint256.replace(/:/g, '').toLowerCase() }));
    }).on('error', reject);
  });
  try {
    assert.deepEqual(await get(http), { body: 'false', fp: undefined });
    assert.deepEqual(await get(https), { body: 'true', fp: m.fp });
    assert.deepEqual(await get(http), { body: 'false', fp: undefined });
  } finally { L.close(); }
});

test('a connection that never speaks is dropped by the idle timer', async () => {
  const m = T.loadOrCreate({ dataDir: tmpdir() });
  const L = T.createSniffingListener(net, http.createServer(), https.createServer({ cert: m.cert, key: m.key }), { idleMs: 200 });
  await new Promise((r) => L.listen(0, '127.0.0.1', r));
  try {
    const closed = await new Promise((resolve) => {
      const s = net.connect(L.address().port, '127.0.0.1');
      const t = setTimeout(() => { s.destroy(); resolve(false); }, 3000);
      s.on('close', () => { clearTimeout(t); resolve(true); });
      s.on('error', () => { });
    });
    assert.equal(closed, true);
  } finally { L.close(); }
});
