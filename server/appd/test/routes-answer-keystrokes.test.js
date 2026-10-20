'use strict';
// What /answer types into a pane, and when it refuses to type at all: the
// checkbox rows of a multi-select question. Against a throwaway daemon and a
// private tmux socket.
//
// PORT: taken from the kernel (listen on 0, read it back, close), not from the
// fixed table the older route files keep in step by hand — a port the kernel
// just handed out is free now, and no other file can be holding it.
//
// SAFETY: never touches a real session. Every tmux session lives on a private
// `-L` socket that after() kills, the daemon's data, state, Claude dir and FCM
// key all point into a scratch directory, and every HUGINN_* variable of the
// shell that runs the suite is dropped, so nothing can reach a live relay.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const PFX = `ak-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let tmp, stateDir, token, daemon, base;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}
function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
const readOr = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '');
async function api(pathname, init = {}) {
  const res = await fetch(base + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
async function until(what, fn, ms = 15_000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const got = await fn();
    if (got) return got;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await wait(100);
  }
}
/** The question /screen serves for `name`, once it serves one. */
const promptOn = (name) => until(`a question on ${name}`,
  async () => (await api(`/v1/sessions/${name}/screen`)).body?.prompt);
const answer = (name, body) => api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify(body) });

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ak-'));
  stateDir = path.join(tmp, 'state');
  for (const d of ['state', 'data', 'claude', 'scratch']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_FCM_KEY: path.join(tmp, 'no-fcm-key.json'),
      HUGINN_CLAUDE_BIN: path.join(tmp, 'no-claude-binary'),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not listening yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) {
    throw new Error(`port ${port} is held by another huginn-appd that refuses this run's token`);
  }
});

after(async () => {
  try { sh('tmux', ['kill-server']); } catch { /* none left */ }
  if (daemon && daemon.exitCode === null) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      daemon.once('exit', () => { clearTimeout(hard); resolve(); });
      daemon.kill('SIGTERM');
    });
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** A pane that draws `frame` (printf-escaped rows) and keeps every byte typed at it, raw, in `out`. */
function staticSession(suffix, frame) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.keys`);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${frame.join('\\n')}\\n"; stty raw -echo; cat > ${out}'`]);
  return { name, out };
}

// ---- a multi-select question takes a SET -----------------------------------

/** AskUserQuestion with multiSelect, as 2.1.x draws it (test/pane.test.js MULTI_DIALOG). */
const MULTI = [
  '←  ☐ Toppings  ✔ Submit  →',
  'Which toppings do you want?',
  '❯ 1. [ ] Cheese',
  '  Add cheese to your order',
  '  2. [ ] Mushrooms',
  '  Add mushrooms to your order',
  '  3. [ ] Olives',
  '  4. [ ] Type something',
  '     Submit',
  '────────────',
  '  5. Chat about this',
  'Enter to select · ↑/↓ to navigate · Esc to cancel',
];

test('one option for a checkbox row of a multi-select question is refused, and nothing is typed', async () => {
  // A digit there only ticks its box: the question stays open, and an answer of
  // `ok` plus the memo it writes refused the proper set for a minute after.
  const { name, out } = staticSession('multi', MULTI);
  const p = await promptOn(name);
  assert.equal(p.multiSelect, true, 'precondition: the pane reads as a multi-select question');
  const r = await answer(name, { option: 2, fingerprint: p.fingerprint });
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.equal(r.body.reason, 'changed');
  assert.match(r.body.error, /several answers/);
  assert.equal(r.body.fingerprint, p.fingerprint, 'the question is handed back to be answered as a set');
  await wait(500);
  assert.equal(readOr(out), '', 'no digit and no Enter reached the question');
  // …and the real answer is not then turned away as "already answered".
  const set = await answer(name, { options: [2], fingerprint: p.fingerprint });
  assert.notEqual(set.body && set.body.error, 'that question has already been answered', JSON.stringify(set.body));
});

test('the row of a multi-select question that is not a box still takes one option', async () => {
  const { name, out } = staticSession('multi-chat', MULTI);
  const p = await promptOn(name);
  const r = await answer(name, { option: 5, fingerprint: p.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.label, 'Chat about this');
  await until('the digit to reach the pane', () => readOr(out).startsWith('5'));
});
