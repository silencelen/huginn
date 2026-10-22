'use strict';
// The state watcher ticks the alerts at once when a session's state changes,
// and not for the hook's same-state rewrite on every tool call
// (lib/statewatch). Each such rewrite used to cost a whole alert tick.
//
// SAFETY: a scratch daemon on a kernel-assigned port with scratch data, state
// and Claude folders; its sessions run `sleep` on a private tmux socket;
// no-owner-contact blanks the relay, the push key and the memory host.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

const TMUX_SOCK = `huginn-awatch-${process.pid}`;
let tmp, daemon, port, logFile;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}

function tmux(...args) {
  const env = { ...process.env };
  delete env.TMUX;
  return spawnSync('tmux', ['-L', TMUX_SOCK, ...args], { env, stdio: 'ignore' }).status;
}

/** The hook's write: the whole file, by rename. */
function writeState(name, state) {
  const p = path.join(tmp, 'state', name);
  fs.writeFileSync(`${p}.tmp`, JSON.stringify({
    state, sessionId: `00000000-0000-4000-8000-0000000000${name === 'aw1' ? '01' : '02'}`,
    transcript: null, cwd: tmp, ts: Date.now(),
  }));
  fs.renameSync(`${p}.tmp`, p);
}

function alertState() {
  try { return JSON.parse(fs.readFileSync(path.join(tmp, 'data', 'alerts.json'), 'utf8')); } catch { return null; }
}

/** Polls until [pred] holds on the alert state; the state, or null after [ms]. */
async function until(pred, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const s = alertState();
    if (s && pred(s)) return s;
    await wait(15);
  }
  return null;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'huginn-awatch-'));
  for (const d of ['data', 'state', 'claude']) fs.mkdirSync(path.join(tmp, d));
  fs.writeFileSync(path.join(tmp, 'token'), `awatch-${process.pid}-0123456789abcdef0123456789\n`, { mode: 0o600 });
  port = await freePort();
  for (const n of ['aw1', 'aw2']) assert.equal(tmux('new-session', '-d', '-s', n, 'sleep 600'), 0);
  logFile = path.join(tmp, 'daemon.log');
  const log = fs.openSync(logFile, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: ['ignore', log, log],
  });
  for (let i = 0; i < 300; i++) {
    try { await fetch(`http://127.0.0.1:${port}/v1/ping`); break; } catch { await wait(100); }
  }
  writeState('aw1', 'running');
  writeState('aw2', 'running');
  const seen = await until((s) => s.prev && s.prev.sessions
    && s.prev.sessions.aw1 === 'running' && s.prev.sessions.aw2 === 'running', 30_000);
  assert.ok(seen, 'a first tick observed both sessions running');
});

after(async () => {
  if (daemon) daemon.kill('SIGTERM');
  await wait(200);
  tmux('kill-server');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
});

test('a running session\'s file rewritten on every tool call does not tick the alerts each time', async () => {
  // Start from a tick a change just caused, so the quiet window starts fresh.
  writeState('aw2', 'idle');
  const fresh = await until((s) => s.prev && s.prev.sessions && s.prev.sessions.aw2 === 'idle', 5_000);
  assert.ok(fresh, 'the change ticked');
  const ticks = new Set();
  const start = Date.now();
  let i = 0;
  while (Date.now() - start < 2_400) {
    if (i++ % 10 === 0) writeState('aw1', 'running'); // every 150 ms
    const s = alertState();
    if (s && s.prevAt && s.prevAt !== fresh.prevAt) ticks.add(s.prevAt);
    await wait(15);
  }
  // Sixteen rewrites in 2.4 s, inside the 3 s quiet window. Only the 10 s poll
  // can land in it, once at most; every rewrite used to tick.
  assert.ok(ticks.size <= 1, `${ticks.size} alert ticks for 16 same-state rewrites`);
});

test('a session going idle reaches the alerts at once', async () => {
  const t0 = Date.now();
  writeState('aw1', 'idle');
  const s = await until((x) => x.prev && x.prev.sessions && x.prev.sessions.aw1 === 'idle', 1_500);
  assert.ok(s, `running -> idle not observed within 1.5 s (${Date.now() - t0} ms)`);
});

test('a question reaches the alerts at once', async () => {
  // No relay and no push key here, so the alert reaches nobody and is rolled
  // back for a retry; the log line is the tick deciding it.
  const before = fs.readFileSync(logFile, 'utf8').length;
  writeState('aw2', 'attention');
  const end = Date.now() + 1_500;
  let decided = false;
  while (!decided && Date.now() < end) {
    decided = fs.readFileSync(logFile, 'utf8').slice(before).includes('session_attention for aw2');
    if (!decided) await wait(20);
  }
  assert.ok(decided, 'no tick decided the question within 1.5 s');
});
