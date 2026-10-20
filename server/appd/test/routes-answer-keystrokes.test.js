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
/**
 * A pane running fixtures/stub-dialog.js — a numbered question that answers,
 * focuses and re-asks the way the real selector does. Every byte typed at it
 * lands in `keys`; what it did with them, in `events`.
 */
function stubSession(suffix, spec) {
  const name = `${PFX}-${suffix}`;
  const keys = path.join(tmp, `${suffix}.keys`);
  const events = path.join(tmp, `${suffix}.events`);
  const q = (s) => `'${String(s).replace(/'/g, "'\\''")}'`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_STUB_SPEC=${q(JSON.stringify(spec))} HG_STUB_KEYS=${q(keys)} HG_STUB_EVENTS=${q(events)} `
    + `${q(process.execPath)} ${q(path.join(__dirname, 'fixtures', 'stub-dialog.js'))}`]);
  return { name, keys, events };
}

// ---- the digit answers its row; the Enter after it is not blind -------------

const PERMISSION = {
  question: 'Do you want to create probe.txt?',
  options: [{ label: 'Yes' }, { label: 'Yes, and do not ask again' }, { label: 'No' }],
  draft: 'half a thought, not sent yet',
};
/** Plan approval's last row is an input row: a digit only moves the caret onto it. */
const PLAN = {
  question: 'Would you like to proceed?',
  options: [{ label: 'Yes, and use auto mode' }, { label: 'Yes, manually approve edits' },
    { label: 'No, keep planning', input: true }],
};

test('a digit that answers the question gets no Enter after it: the draft in the box stays unsent', async () => {
  const { name, keys, events } = stubSession('digit', PERMISSION);
  const p = await promptOn(name);
  const r = await answer(name, { option: 1, fingerprint: p.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await until('the answer', () => readOr(events).includes('ANSWERED 1 by digit'));
  await wait(500);
  assert.equal(readOr(keys), '1', 'the digit, and nothing after it');
  assert.doesNotMatch(readOr(events), /SUBMITTED/, 'the half-typed draft under the dialog was not sent');
});

test('a digit that only focuses its row gets the Enter that answers it', async () => {
  const { name, keys, events } = stubSession('focus', PLAN);
  const p = await promptOn(name);
  const r = await answer(name, { option: 3, fingerprint: p.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await until('the answer', () => readOr(events).includes('ANSWERED 3 by enter'));
  assert.equal(readOr(keys), '3\r');
});

// ---- the answer memo lasts only until the question leaves the pane ----------

const answeredByDigit = (events) => readOr(events).split('\n').filter((l) => l === 'ANSWERED 1 by digit').length;

test('the same question asked again after its answer can be answered: the memo ends with the question', async () => {
  // Claude runs `npm test` again after a fix: the second dialog is the first one
  // byte for byte, fingerprint and all, and a flat-minute memo refused the Yes
  // to it as "already answered" while the session sat waiting on it.
  const { name, events } = stubSession('reask', { ...PERMISSION, reaskMs: 1200 });
  const first = await promptOn(name);
  assert.equal((await answer(name, { option: 1, fingerprint: first.fingerprint })).status, 200);
  await until('the same question again', () => readOr(events).includes('ASKED AGAIN'));
  const again = await promptOn(name);
  assert.equal(again.fingerprint, first.fingerprint, 'precondition: the second question is the first one again');
  const r = await answer(name, { option: 1, fingerprint: again.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await until('the second answer', () => answeredByDigit(events) === 2);
});

test('a pane slow to show its answer keeps the memo until a look sees the question gone', async () => {
  // What the memo is for: a second copy of the answer reading a pane that has
  // not caught up, which would type the digit twice. Once the question has been
  // SEEN to leave, the identical question after it is a new one.
  const { name, keys, events } = stubSession('slow', { ...PERMISSION, answerDelayMs: 2500, reaskMs: 1500 });
  const first = await promptOn(name);
  assert.equal((await answer(name, { option: 1, fingerprint: first.fingerprint })).status, 200);
  const twice = await answer(name, { option: 1, fingerprint: first.fingerprint });
  assert.equal(twice.status, 409, JSON.stringify(twice.body));
  assert.equal(twice.body.error, 'that question has already been answered');
  await until('a screen without the question', async () => !(await api(`/v1/sessions/${name}/screen`)).body?.prompt);
  await until('the same question again', () => readOr(events).includes('ASKED AGAIN'));
  const again = await promptOn(name);
  const r = await answer(name, { option: 1, fingerprint: again.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await until('the second answer', () => answeredByDigit(events) === 2);
  assert.equal(readOr(keys), '11', 'one digit per answer, none for the refused copy');
});

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
