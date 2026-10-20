'use strict';
// GET /v1/watch WITHOUT the stream, on a host whose tmux will not answer.
//
// ⚠ A FAILED LOOK IS NOT AN EMPTY HOST. `listSessions` returns null when tmux
// could not be asked (a fork that hit EAGAIN, the 10 s timeout, a server
// restarting) and [] only when tmux said there is nothing. The stream refuses to
// publish the null; the long poll hashed it as an empty session map and answered
// 200 `changed: true`. Every Android caller of this route (the heartbeat, the
// watch worker, the widget refresh, the push reconcile) asks with no hash and no
// wait, takes that answer as every session gone, cancels the "needs you"
// notifications of questions that are still waiting, and re-posts them all as
// new on its next good look. GET /v1/sessions says 503 for the same failure, and
// each of those callers keeps its baseline when the watch call fails.
//
// SAFETY: a PRIVATE tmux socket (`-L`); the one session made here runs an inert
// `cat` and is killed in after(). The tmux the DAEMON sees is a shim that fails
// one subcommand on request; the test's own tmux calls go to the real binary. The
// port is one the kernel just handed out, and every path is scratch.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');

const TMUX_SOCK = `huginn-test-watchdown-${process.pid}`;
const NAME = `wd-${process.pid}-build`;
const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();

let tmp, stateDir, token, daemon, base, breakFile;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const tmux = (args) => execFileSync(realTmux, ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });

/** A port the kernel just handed out, never a computed one (see routes-tls.test.js). */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => resolve(port)); });
  });
}

async function watch(query) {
  const res = await fetch(`${base}/v1/watch${query}`, { headers: { authorization: `Bearer ${token}` } });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-watchdown-'));
  stateDir = path.join(tmp, 'state');
  for (const d of ['state', 'data', 'claude', 'home', 'shim']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  breakFile = path.join(tmp, 'break-tmux');
  // Fails the named subcommands the way a loaded host does: non-zero, with a
  // message that is NOT "no server running", so the daemon cannot read it as
  // an observation of nothing.
  fs.writeFileSync(path.join(tmp, 'shim', 'tmux'),
    '#!/bin/sh\n'
    + 'if [ -f "$HG_TMUX_BREAK" ]; then\n'
    + '  for a in "$@"; do\n'
    + '    if grep -qx -- "$a" "$HG_TMUX_BREAK" 2>/dev/null; then\n'
    + '      echo "resource temporarily unavailable" >&2\n'
    + '      exit 1\n'
    + '    fi\n'
    + '  done\n'
    + 'fi\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });
  // Nothing here starts a run, but a daemon must never be able to reach the
  // real Claude Code from a test.
  fs.writeFileSync(path.join(tmp, 'shim', 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });

  // One session that is waiting on its owner — the thing a phantom-empty answer
  // makes the phone forget.
  tmux(['new-session', '-d', '-s', NAME, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  fs.writeFileSync(path.join(stateDir, NAME), JSON.stringify({
    state: 'attention', sessionId: `sid-${NAME}`, transcript: null, cwd: tmp, ts: Date.now(),
  }));

  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HG_TMUX_BREAK: breakFile,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try {
      const r = await fetch(`${base}/v1/ping`, { headers: { authorization: `Bearer ${token}` } });
      if (r.status === 200) break;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await wait(100);
  }
});

after(() => {
  try { fs.rmSync(breakFile, { force: true }); } catch { /* gone */ }
  if (daemon) daemon.kill('SIGTERM');
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('the control: a heartbeat look sees the waiting session', async () => {
  const r = await watch('');
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal('attention', r.body.sessions[NAME], JSON.stringify(r.body.sessions));
});

test('a heartbeat look while tmux is not answering is a 503, not an empty host', async () => {
  const good = await watch('');
  fs.writeFileSync(breakFile, 'list-sessions\n');
  try {
    const r = await watch('');
    assert.equal(503, r.status, `got ${r.status} ${JSON.stringify(r.body)}`);
    assert.match(r.body.error, /tmux is not answering/);
    assert.equal(undefined, r.body.sessions, 'and no session map at all, empty or otherwise');
  } finally { fs.rmSync(breakFile, { force: true }); }

  const back = await watch('');
  assert.equal(200, back.status);
  assert.equal(good.body.hash, back.body.hash, 'and the next good look is the world it was before');
});

test('a long poll that never got a good look is a 503 at its deadline too', async () => {
  // `wait` is milliseconds: long enough for the loop to look again (it sleeps
  // 3 s between looks) and find tmux still silent.
  const known = (await watch('')).body.hash;
  fs.writeFileSync(breakFile, 'list-sessions\n');
  try {
    const r = await watch(`?hash=${known}&wait=3500`);
    assert.equal(503, r.status, `got ${r.status} ${JSON.stringify(r.body)}`);
  } finally { fs.rmSync(breakFile, { force: true }); }
});
