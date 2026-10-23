'use strict';
// The one shared tmux session listing (tmuxListSessions) never answers across a
// change that matters: what the daemon itself does to the session set shows at
// once, a session started outside it is found by the very next request, and one
// killed outside it reads as gone, not as tmux failing to answer.
//
// PORT: taken from the kernel. SAFETY: tmux sessions on a private `-L` socket
// running `cat` (claude is a stub that does the same), scratch data, state and
// Claude dirs; no-owner-contact blanks the relay, the push key and the memory host.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const TMUX_SOCK = `huginn-tls-${process.pid}`;
let tmp, token, daemon, base;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const tmux = (args) => execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
  });
}
async function api(pathname, init = {}) {
  const res = await fetch(base + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const listed = async () => (((await api('/v1/sessions')).body || {}).sessions || []).map((s) => s.name);

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-tls-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'bin']) fs.mkdirSync(path.join(tmp, d));
  fs.writeFileSync(path.join(tmp, 'bin', 'claude'), '#!/bin/sh\nexec cat\n', { mode: 0o755 });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_APPD_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      PATH: `${path.join(tmp, 'bin')}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_CLAUDE_BIN: path.join(tmp, 'bin', 'claude'),
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // Something for the listing to hold: every case below starts with a fresh one.
  tmux(['new-session', '-d', '-s', 'tls-base', '-c', tmp, 'cat']);
});

after(async () => {
  try { tmux(['kill-server']); } catch { /* none left */ }
  if (daemon && daemon.exitCode === null) {
    const gone = new Promise((r) => daemon.once('exit', r));
    daemon.kill('SIGTERM');
    await Promise.race([gone, wait(5_000)]);
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a session the daemon creates is listed at once', async () => {
  assert.ok((await listed()).includes('tls-base'), 'the listing is primed');
  const made = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: 'tls-made' }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.ok((await listed()).includes('tls-made'), 'not in the listing right after the create');
});

test('a session the daemon renames moves at once', async () => {
  await listed();
  const r = await api('/v1/sessions/tls-made/rename', { method: 'POST', body: JSON.stringify({ name: 'tls-renamed' }) });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  const names = await listed();
  assert.ok(names.includes('tls-renamed') && !names.includes('tls-made'), names.join(', '));
});

test('a session the daemon ends is gone at once', async () => {
  await listed();
  assert.equal((await api('/v1/sessions/tls-renamed', { method: 'DELETE' })).status, 200);
  assert.ok(!(await listed()).includes('tls-renamed'), 'still listed right after the kill');
});

test('a session started outside the daemon is found by the next request', async () => {
  await listed(); // a shared answer from before it
  tmux(['new-session', '-d', '-s', 'tls-outside', '-c', tmp, 'cat']);
  const { status } = await api('/v1/sessions/tls-outside/screen');
  assert.equal(status, 200, 'a miss in the shared answer must ask tmux again before saying 404');
});

test('a session killed outside the daemon reads as gone, not as tmux failing', async () => {
  await api('/v1/sessions/tls-outside/screen'); // found, and in the shared answer
  tmux(['kill-session', '-t', '=tls-outside']);
  const { status } = await api('/v1/sessions/tls-outside/screen');
  assert.equal(status, 404, 'a failed read asks tmux afresh whether the session is gone');
  await wait(1100);
  assert.ok(!(await listed()).includes('tls-outside'), 'still listed a second later');
});
