'use strict';
// Route-level tests for the desktop update channel, written the day the second
// one was retired. There is exactly one now — /v1/desktop-kt, the Compose
// client's — and the two facts worth a real socket are that it still serves,
// and that /v1/desktop is not merely empty but NOT A ROUTE: the Electron client
// was deleted on 2026-08-27 by owner directive, and a deployed host still has
// its old DATA_DIR/desktop full of installers for a program that no longer
// exists. Those bytes must be unreachable, not "unreferenced".
//
// SAFETY: no tmux, no real claude, no live daemon. A throwaway daemon on a
// scratch HUGINN_APPD_DATA and a scratch state dir, both removed in after().

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: two files
// once sat inside 9700-9949, and the suite passed four times before failing 16
// tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer        8788 + pid%900  ->  8788-9687
//   routes-lifecycle     9700 + pid%100  ->  9700-9799
//   routes-rounds        9800 + pid%60   ->  9800-9859
//   routes-devices       9870 + pid%50   ->  9870-9919
//   session-identity     9930 + pid%40   ->  9930-9969
//   breaker-fixes        9971 + pid%25   ->  9971-9995
//   routes-modelgate    10000 + pid%50   -> 10000-10049
//   routes-localmodels  10050 + pid%50   -> 10050-10099
//   routes-polish       10100 + pid%50   -> 10100-10149
//   routes-scratchpads  10150 + pid%50   -> 10150-10199
//   routes-overview     10200 + pid%50   -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349   (this file)
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10300 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

// What a real release stages, small enough to assert byte-for-byte.
const KT_VERSION = '0.16.0';
const KT_EXE = `Huginn-Desktop-Setup-${KT_VERSION}.exe`;
const KT_BYTES = 'MZ-ish, but only just';

let tmp, dataDir, ktDir, retiredDir, token, daemon;

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, ...(init.headers || {}) },
  });
  const text = await res.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* an artifact is not JSON */ }
  return { status: res.status, headers: res.headers, text, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-desktop-'));
  dataDir = path.join(tmp, 'data');
  ktDir = path.join(dataDir, 'desktop-kt');
  retiredDir = path.join(dataDir, 'desktop');
  fs.mkdirSync(ktDir, { recursive: true });
  fs.mkdirSync(path.join(tmp, 'state'));
  // Stocked the way release-desktop.sh stocks it: local file moves, never HTTP.
  fs.writeFileSync(path.join(ktDir, 'manifest.json'),
    JSON.stringify({ version: KT_VERSION, artifacts: [{ platform: 'windows', file: KT_EXE }] }));
  fs.writeFileSync(path.join(ktDir, KT_EXE), KT_BYTES);

  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run sits on one of the few slots this formula gives, answers /v1/ping
  // happily because ping needs no token, and rejects OURS — which surfaces as
  // 401s that read like a code bug and are not one.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('the surviving channel serves its manifest', async () => {
  const r = await api('/v1/desktop-kt/manifest');
  assert.equal(r.status, 200);
  assert.equal(r.body.version, KT_VERSION);
});

test('the surviving channel streams an artifact whole, with its length', async () => {
  const r = await api(`/v1/desktop-kt/${KT_EXE}`);
  assert.equal(r.status, 200);
  assert.equal(r.text, KT_BYTES);
  assert.equal(r.headers.get('content-length'), String(Buffer.byteLength(KT_BYTES)));
  assert.equal(r.headers.get('content-type'), 'application/octet-stream');
});

test('a name it does not hold is the CHANNEL\'s 404, not the fall-through', async () => {
  // The distinction is the whole point of the next test: this route exists, so
  // it answers for itself. `not found` here would mean it had stopped being
  // routed at all.
  const missing = await api('/v1/desktop-kt/Huginn-Desktop-Setup-9.9.9.exe');
  assert.equal(missing.status, 404);
  assert.equal(missing.body.error, 'no such file');

  // Decoded BEFORE it is validated, so the encoded traversal is the one that
  // has to be refused by name rather than by the path separator never arriving.
  const traversal = await api('/v1/desktop-kt/..%2Fdesktop%2FHuginn-Setup-0.4.0.exe');
  assert.equal(traversal.status, 400);
  assert.equal(traversal.body.error, 'bad name');
});

test('the retired Electron channel is not a route, and its directory is never made or read', async () => {
  // The daemon has been up and serving through everything above and has not
  // created DATA_DIR/desktop. Nothing in it does any more.
  assert.equal(fs.existsSync(retiredDir), false,
    'the daemon created DATA_DIR/desktop — something still models the Electron channel');

  // Now stock it exactly as a host that once ran the Electron client still has
  // it, and prove the bytes are unreachable rather than merely unreferenced.
  fs.mkdirSync(retiredDir);
  fs.writeFileSync(path.join(retiredDir, 'manifest.json'), JSON.stringify({ version: '0.4.0' }));
  fs.writeFileSync(path.join(retiredDir, 'Huginn-Setup-0.4.0.exe'), 'stale electron installer');
  fs.writeFileSync(path.join(retiredDir, 'latest.yml'), 'version: 0.4.0\n');

  for (const p of ['/v1/desktop/manifest', '/v1/desktop/Huginn-Setup-0.4.0.exe', '/v1/desktop/latest.yml']) {
    const r = await api(p);
    assert.equal(r.status, 404, `${p} answered ${r.status}`);
    // The daemon's generic miss — NOT `no desktop releases yet`, which would
    // mean the route was still mounted and merely unstocked.
    assert.equal(r.body.error, 'not found', `${p} is still handled specially`);
  }

  // And the surviving channel is untouched by any of that.
  assert.equal((await api('/v1/desktop-kt/manifest')).body.version, KT_VERSION);
});
