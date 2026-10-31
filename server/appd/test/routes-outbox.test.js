'use strict';
// GET /v1/outbox/<id> against a real daemon: a file Claude sent (send_file) is
// served only by an id lib/outbox.js minted, an image inline as its image type,
// anything else as an octet-stream ATTACHMENT, never rendered.
//
// SAFETY: a scratch HUGINN_APPD_DATA, state dir and private tmux socket; nothing
// of the operator's is touched.
//
// PORT ALLOCATION (table in routes-lifecycle.test.js):
//   routes-outbox  11800 + pid%50  -> 11800-11849   (this file)

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const outbox = require('../lib/outbox');

const PORT = 11800 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let tmp, dataDir, token, daemon, png, pdf;

async function get(id, auth = true) {
  const res = await fetch(`${BASE}/v1/outbox/${encodeURIComponent(id)}`, { headers: auth ? { authorization: `Bearer ${token}` } : {} });
  return { status: res.status, headers: res.headers, buf: Buffer.from(await res.arrayBuffer()) };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-outbox-'));
  dataDir = path.join(tmp, 'data');
  const src = path.join(tmp, 'src');
  fs.mkdirSync(dataDir, { recursive: true }); fs.mkdirSync(src);
  fs.writeFileSync(path.join(src, 'chart.png'), 'PNG-BYTES');
  fs.writeFileSync(path.join(src, 'report "q3".pdf'), 'PDF-BYTES');
  png = outbox.deposit(path.join(src, 'chart.png'), { outboxDir: path.join(dataDir, 'outbox'), dataDir });
  pdf = outbox.deposit(path.join(src, 'report "q3".pdf'), { outboxDir: path.join(dataDir, 'outbox'), dataDir });
  token = crypto.randomBytes(32).toString('hex');
  const tokenFile = path.join(tmp, 'token');
  fs.writeFileSync(tokenFile, token, { mode: 0o600 });
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT), HUGINN_APPD_BIND: '127.0.0.1', HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: tokenFile, HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK, HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try { if ((await fetch(`${BASE}/v1/ping`, { headers: { authorization: `Bearer ${token}` } })).status === 200) break; } catch { }
    await wait(100);
  }
});

after(async () => {
  if (daemon) { const dead = new Promise((r) => daemon.once('exit', r)); daemon.kill('SIGTERM'); await dead; }
  try { execFileSync('tmux', ['-L', TMUX_SOCK, 'kill-server'], { stdio: 'ignore' }); } catch { }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('an image Claude sent is served inline as its image type', async () => {
  const r = await get(png.id);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'image/png');
  assert.match(r.headers.get('content-disposition'), /^inline; filename="chart\.png"$/);
  assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(r.buf.toString(), 'PNG-BYTES');
});

test('anything else is a download: octet-stream, attachment, a header-safe name', async () => {
  const r = await get(pdf.id);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'application/octet-stream');
  assert.equal(r.headers.get('content-disposition'), 'attachment; filename="report _q3_.pdf"');
  assert.match(r.headers.get('content-security-policy'), /sandbox/);
  assert.equal(r.buf.toString(), 'PDF-BYTES');
});

test('only a minted id is served, and only with the token', async () => {
  assert.equal((await get(`${png.id}.json`)).status, 404, 'the sidecar is not a file to serve');
  assert.equal((await get('../token')).status, 404);
  assert.equal((await get('out-1793400000000-000000.png')).status, 404, 'a well-formed id that was never sent');
  assert.equal((await get(png.id, false)).status, 401);
});
