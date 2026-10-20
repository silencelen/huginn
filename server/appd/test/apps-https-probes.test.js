'use strict';
// lib/apps.js — an https:// app, probed for real: a self-signed certificate, a
// server that picks its certificate by name, and a TLS failure that must not be
// read as "nothing is listening".
//
// Every homelab console that speaks https out of the box (Proxmox, PBS,
// Cockpit, UniFi) ships a self-signed certificate. The probes used to dial with
// certificate verification on, so such an app was "not answering": every add
// was a 422 telling the owner to rebind a unit that was already bound and
// listening, and a row forced in by an edit read as down forever. A valid
// certificate fared no better on this host — the reachability probe dials
// 127.0.0.1, which is not the name on it.
//
// SAFETY: real TLS listeners on 127.0.0.1, ephemeral ports (`listen(0)`). The
// certificate is minted into a mkdtemp'd dir by lib/tls (openssl, as the suite
// already needs) and removed again. No fetch is injected anywhere in this file:
// the transport the daemon really uses is the thing under test.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const os = require('node:os');
const path = require('node:path');
const tls = require('node:tls');
const appsLib = require('../lib/apps');
const tlsLib = require('../lib/tls');

let tmp, selfSigned, byName, plain;
const sniSeen = [];

const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)));
const page = (req, res) => { res.writeHead(200, { 'content-type': 'text/html' }); res.end('<html><head></head></html>'); };

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'apps-https-'));
  const { cert, key } = tlsLib.loadOrCreate({ dataDir: tmp });
  // What Proxmox, Cockpit and UniFi ship: a certificate nothing vouches for.
  selfSigned = https.createServer({ cert, key }, page);
  // A server that has NO certificate unless the client names the site — the way
  // a reverse proxy holding a cert for one name behaves.
  const ctx = tls.createSecureContext({ cert, key });
  byName = https.createServer({
    SNICallback: (name, cb) => {
      sniSeen.push(name);
      if (name === 'localhost') cb(null, ctx); else cb(new Error(`no certificate for ${name}`));
    },
  }, page);
  // Plain http, for an https:// address typed against it.
  plain = http.createServer(page);
  selfSigned.port = await listen(selfSigned);
  byName.port = await listen(byName);
  plain.port = await listen(plain);
});

after(() => {
  for (const s of [selfSigned, byName, plain]) {
    if (!s) continue;
    if (typeof s.closeAllConnections === 'function') s.closeAllConnections();
    s.close();
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

function tmpDir(tag) { return fs.mkdtempSync(path.join(os.tmpdir(), `apps-https-${tag}-`)); }

test('a self-signed https app is UP: the liveness probe reads its status', async () => {
  const p = await appsLib.probeApp({ id: 'pve', url: `https://127.0.0.1:${selfSigned.port}/` });
  assert.equal(p.up, true, JSON.stringify(p));
  assert.equal(p.httpStatus, 200);
});

test('a self-signed https app on this host can be added, and its row reads up and reachable', async () => {
  const dir = tmpDir('add');
  const store = appsLib.createStore({ dir, icons: false, hostAddr: '' });
  store.stop();
  try {
    store.list();
    const r = await store.add({
      name: 'Cockpit', url: `https://127.0.0.1:${selfSigned.port}/`, unit: 'cockpit.socket',
    });
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.reachable.ok, true);
    assert.deepEqual(r.reachable.fix, [], 'no rebind lines for a unit that is bound and listening');

    const row = await store.probeNow(r.app.id);
    assert.equal(row.up, true, JSON.stringify(row));
    assert.equal(row.httpStatus, 200);
    assert.equal(row.reachable.ok, true);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the reachability probe dials the address but names the STORED host in SNI', async () => {
  sniSeen.length = 0;
  const r = await appsLib.reachOne(`https://localhost:${byName.port}/`, '127.0.0.1');
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.ok(sniSeen.includes('localhost'), `SNI seen: ${JSON.stringify(sniSeen)}`);
});

test('an IP literal is never put in SNI', async () => {
  sniSeen.length = 0;
  const r = await appsLib.reachOne(`https://127.0.0.1:${byName.port}/`, '127.0.0.1');
  assert.equal(r.ok, false, 'that server has nothing to offer a client that names no site');
  assert.deepEqual(sniSeen, [], 'and the probe named none');
});

test('a TLS failure is the app, not the bind: no rebind lines, no "fix the bind first"', async () => {
  // https:// typed for a page that speaks plain http — the listener is there,
  // bound and accepting; it just does not speak TLS.
  const url = `https://127.0.0.1:${plain.port}/`;
  const row = await appsLib.reachOne(url, '127.0.0.1');
  assert.equal(row.ok, false);
  assert.equal(appsLib.reachedButSilent(row), true, `${row.error} must read as reached`);

  const fix = appsLib.fixLines({ url, unit: 'trainer.service' }, [row]);
  assert.ok(!fix.some((l) => /bind 0\.0\.0\.0/.test(l)), fix.join('\n'));
  assert.ok(fix.some((l) => /not refused/.test(l)), fix.join('\n'));
  const refusal = appsLib.reachRefusal({ addresses: [row] });
  assert.ok(!/fix the bind first/.test(refusal), refusal);
});

test('certificate and handshake codes all read as reached; refusals still do not', () => {
  for (const error of ['DEPTH_ZERO_SELF_SIGNED_CERT', 'UNABLE_TO_VERIFY_LEAF_SIGNATURE', 'CERT_HAS_EXPIRED',
    'SELF_SIGNED_CERT_IN_CHAIN', 'ERR_TLS_CERT_ALTNAME_INVALID', 'ERR_SSL_WRONG_VERSION_NUMBER', 'EPROTO']) {
    assert.equal(appsLib.reachedButSilent({ error }), true, error);
  }
  for (const error of ['connection refused', 'no route', 'name does not resolve', 'ECONNRESET', 'did not answer']) {
    assert.equal(appsLib.reachedButSilent({ error }), false, error);
  }
});

test('the favicon fetch still verifies: icon bytes off an unverified server are not kept', async () => {
  // The probes read a status code; the icon's bytes are cached and served back
  // to every client, so that fetch keeps the certificate check.
  const got = await appsLib.fetchIcon(`https://127.0.0.1:${selfSigned.port}/`, { timeoutMs: 2000 });
  assert.equal(got.ok, false, JSON.stringify(got));
});
