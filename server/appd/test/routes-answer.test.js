'use strict';
// The first route-level test for huginn-appd, and the regression test for the
// /answer fingerprint guard found in the 2026-08 audit (finding L1).
//
// THE FIRST ROUTE-LEVEL TEST FOR THE DAEMON.
//
// Everything else in test/ covers lib/*.js. Nothing loaded huginn-appd.js or
// started its server, so a guard living in the 3,200-line route file could be
// opt-in for months without a red build — which is exactly what happened to the
// one below, and to the rename route beside it.
//
// The harness is cheap because the daemon was already built to be isolated:
// HUGINN_APPD_PORT / _BIND / _DATA / _TOKEN_FILE give a throwaway instance that
// cannot touch the running one, its accounts store, or its chats. Add to it
// whenever a route grows a rule worth keeping.
//
// SAFETY: this test never touches a real session. It creates its own tmux
// session, draws a fake question into it with printf, answers THAT, and kills it.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 8788 + (process.pid % 900);          // avoid the live daemon's 8787
const SESSION = `audit-fp-${process.pid}`;
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

let tmp, token, daemon;

/** The pane a live single-select question actually draws: a caret-marked run of
 *  numbered options and a footer, with none of the ordinary chrome (composer,
 *  status line, mode hint, spinner) that would mark the run as history. */
const FAKE_PROMPT = [
  'Do you want to create probe.txt?',
  '',
  '❯ 1. Yes',
  '  2. Yes, and do not ask again',
  '  3. No',
  '',
  'Enter to select · Esc to cancel',
].join('\\n');

function sh(cmd, args) {
  return execFileSync(cmd, args, { encoding: 'utf8' });
}

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* some responses have no body */ }
  return { status: res.status, body };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-it-'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
    },
    stdio: 'ignore',
  });
  // An unresolvable binary or a bad env would otherwise hang the whole suite on
  // the readiness loop instead of saying what went wrong.
  daemon.on('error', (e) => { throw e; });

  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load on this host has pushed daemon start past 10s
    try {
      const r = await api('/v1/ping');
      if (r.status === 200) break;
    } catch { /* not listening yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? The formula above gives few
  // slots, and a daemon leaked by an earlier run — a test process killed before
  // after() could fire — sits on one, answers /v1/ping happily because ping
  // needs no token, and rejects OURS. That surfaced once as twelve tests failing
  // with `401 unauthorized`, which reads like a code bug and is not one. So ask
  // an AUTHENTICATED question before trusting the port, and say plainly what is
  // wrong: `ss -ltnp | grep <port>` then kill it.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }

  // A pane of our own, holding a question that is ours to answer. `cat` keeps
  // the shell from drawing a prompt underneath, which would read as chrome and
  // (correctly) make detectPrompt call the run history.
  sh('tmux', ['new-session', '-d', '-s', SESSION, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${FAKE_PROMPT}\\n"; cat'`]);
  await new Promise((r) => setTimeout(r, 700));
});

after(() => {
  try { sh('tmux', ['kill-session', '-t', `=${SESSION}`]); } catch { /* already gone */ }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('the fixture pane is seen as a live question, with a fingerprint', async () => {
  const { status, body } = await api(`/v1/sessions/${SESSION}/screen`);
  assert.equal(status, 200);
  assert.ok(body.prompt, 'detectPrompt found no question in the fixture pane');
  assert.equal(body.prompt.question, 'Do you want to create probe.txt?');
  assert.deepEqual(body.prompt.options.map((o) => o.number), [1, 2, 3]);
  assert.match(body.prompt.fingerprint, /^[0-9a-f]{12}$/,
    'the prompt must ship with the fingerprint, so the client never computes one');
});

test('a stale fingerprint is refused — the guard is correct when it runs', async () => {
  const { status, body } = await api(`/v1/sessions/${SESSION}/answer`, {
    method: 'POST',
    body: JSON.stringify({ option: 3, fingerprint: 'deadbeef0000' }),
  });
  assert.equal(status, 409);
  assert.equal(body.reason, 'changed');
  assert.equal(body.ok, false);
});

test('an answer with no prompt on screen is refused as gone', async () => {
  const other = `${SESSION}-idle`;
  sh('tmux', ['new-session', '-d', '-s', other, '-c', tmp, 'cat']);
  try {
    const { status, body } = await api(`/v1/sessions/${other}/answer`, {
      method: 'POST',
      body: JSON.stringify({ option: 1, fingerprint: 'deadbeef0000' }),
    });
    assert.equal(status, 409);
    assert.equal(body.reason, 'gone');
  } finally {
    try { sh('tmux', ['kill-session', '-t', `=${other}`]); } catch { /* fine */ }
  }
});

// ---------------------------------------------------------------------------
// FINDING L1 (2026-08 audit): the guard is opt-in.
//
//     if (body.fingerprint && body.fingerprint !== live) { ...409... }
//
// An absent field and an empty string are both falsy, so both skip the check
// entirely and the digit is typed into whatever question is on the pane NOW.
// Both are producible by the shipping clients: HuginnClient omits the key for a
// null fingerprint, AnswerReceiver turns a blank extra into null via
// `ifBlank { null }`, and lib/fcm.js sends `String(fingerprint ?? '')`.
//
// The route's own comment says the host is where this guarantee lives. These
// two assertions are that comment, executable. Un-skip with the fix.
// ---------------------------------------------------------------------------

test('an answer with NO fingerprint is refused', async () => {
  const { status } = await api(`/v1/sessions/${SESSION}/answer`, {
    method: 'POST',
    body: JSON.stringify({ option: 3 }),
  });
  assert.equal(status, 400, 'the host must require the fingerprint, not trust the client to send one');
});

test('an answer with an EMPTY fingerprint is refused', async () => {
  // Kept separate from the case above on purpose: `''` is the value FCM puts on
  // the wire when an alert has no fingerprint, and it fails a truthiness check
  // rather than a presence check. Anyone rewriting the guard as
  // `if (!body.fingerprint) ...` will pass the first test and fail this one.
  const { status } = await api(`/v1/sessions/${SESSION}/answer`, {
    method: 'POST',
    body: JSON.stringify({ option: 3, fingerprint: '' }),
  });
  assert.equal(status, 400);
});
