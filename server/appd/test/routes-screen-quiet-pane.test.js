'use strict';
// A screen long poll parked on a quiet pane peeks it slowly (lib/screenwait), and
// input sent through the daemon wakes it at once.
//
// The daemon under test sees a tmux shim that logs every argv and execs the real
// binary; only calls that TARGET the session under test are counted.
//
// PORT: taken from the kernel. SAFETY: the tmux session lives on a private `-L`
// socket and runs `cat`; data, state and Claude dirs are scratch; every
// HUGINN_* variable of the calling shell is dropped and no-owner-contact blanks
// the relay, the push key and the memory host.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { untilDrawn } = require('./until-drawn');

const TMUX_SOCK = `huginn-quiet-${process.pid}`;
const NAME = `qp-${process.pid}`;
const SEP = '\x1f';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let tmp, token, daemon, base, logFile;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
  });
}
const tmux = (args) => execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });
async function api(pathname, init = {}) {
  const res = await fetch(base + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
/** The tmux calls aimed at the session under test since the log was cleared. */
function peeks() {
  const text = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : '';
  return text.split('\n').filter(Boolean).map((l) => l.split(SEP)).filter((argv) => argv.includes(`=${NAME}:`));
}
const clearLog = () => fs.writeFileSync(logFile, '');

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qp-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'shim']) fs.mkdirSync(path.join(tmp, d));
  logFile = path.join(tmp, 'tmux.log');
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  fs.writeFileSync(path.join(tmp, 'shim', 'tmux'),
    '#!/bin/sh\n'
    + `line=$(printf '%s\\037' "$@")\n`
    + `printf '%s\\n' "$line" >> "$HG_TMUX_LOG"\n`
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });
  tmux(['new-session', '-d', '-s', NAME, '-c', tmp, '-x', '80', '-y', '24', `sh -c 'printf "quiet pane\\n"; exec cat'`]);
  untilDrawn(TMUX_SOCK, NAME, /quiet pane/);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_APPD_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HG_TMUX_LOG: logFile,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_CLAUDE_BIN: path.join(tmp, 'no-claude-binary'),
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
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

test('a poll parked on a pane quiet for ten seconds peeks it every 1.5 s', async () => {
  const first = await api(`/v1/sessions/${NAME}/screen`);
  assert.equal(first.status, 200);
  await wait(10_500); // the pane has now been quiet past the last step
  clearLog();
  const t0 = Date.now();
  const r = await api(`/v1/sessions/${NAME}/screen?hash=${first.body.hash}&wait=6000`);
  assert.equal(r.status, 200);
  assert.equal(r.body.unchanged, true, 'nothing typed, so nothing changed');
  const n = peeks().length;
  // ~6 s at 1.5 s is four or five peeks plus the opening read; it was ~29 when
  // every poll ticked at 130 ms for its first 3 s.
  assert.ok(n <= 8, `${n} tmux calls in ${Date.now() - t0} ms of waiting`);
});

test('input through the daemon wakes a parked poll at once', async () => {
  const first = await api(`/v1/sessions/${NAME}/screen`);
  await wait(10_500);
  const poll = api(`/v1/sessions/${NAME}/screen?hash=${first.body.hash}&wait=10000`);
  await wait(400); // parked, on the slow step
  const sent = Date.now();
  const k = await api(`/v1/sessions/${NAME}/keys`, { method: 'POST', body: JSON.stringify({ text: 'x' }) });
  assert.equal(k.status, 200, JSON.stringify(k.body));
  const r = await poll;
  const took = Date.now() - sent;
  assert.equal(r.status, 200);
  assert.ok(!r.body.unchanged, 'the echo of the key changed the pane');
  assert.ok(took < 1000, `answered ${took} ms after the key, not on the next slow peek`);
});
