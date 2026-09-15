'use strict';
// Route-level tests for the host-owned quick-action templates: the defaults a
// host with no file serves, the partial PATCH, the revision check, the three
// refusals, and the fact that an edit actually lands on disk in the normalised
// shape and is still there after a restart.
//
// The RULES are asserted in quickactions.test.js, away from HTTP. What is here
// is everything the pure library cannot see: the file, the status payload, the
// status codes and the auth gate.
//
// SAFETY: touches no session and no pane. The daemon under test gets a scratch
// HUGINN_APPD_DATA and HUGINN_APPD_STATE_DIR and a private tmux socket, so the
// `tmux list-sessions` behind /v1/status can never see the operator's desktop.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: this file
// and routes-lifecycle both sat inside 9700-9949, and the suite passed four times
// before failing 16 tests on an unlucky pair of pids. The width is what makes a
// range, not the base, so both are fixed here:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349
//   routes-agent-transcript  10400 + pid%50 -> 10400-10449
//   routes-resume       10450 + pid%10   -> 10450-10459
//   routes-refresh      10460 + pid%40   -> 10460-10499
//
// ⚠ 10450-10499 was ONE block in the wave-1 contract; it is SPLIT — resume keeps
// the bottom ten, refresh the upper forty. Widening either back re-collides.
//   routes-typing       10500 + pid%50   -> 10500-10549
//   routes-quick-actions 10600 + pid%50  -> 10600-10649  (this file)
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10600 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
// Private tmux socket shared with the daemon under test (HUGINN_APPD_TMUX_SOCKET),
// so the session listing behind /v1/status answers from an empty server rather
// than the operator's desktop. `-L`, not TMUX_TMPDIR: an inherited $TMUX from
// the launching pane overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, dataDir, stateDir, tokenFile, token, daemon;

const QA_FILE = () => path.join(dataDir, 'quick-actions.json');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const patch = (obj, init = {}) =>
  api('/v1/quick-actions', { method: 'PATCH', body: JSON.stringify(obj), ...init });
const quickActions = async () => (await api('/v1/status')).body.quickActions;

async function startDaemon() {
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: tokenFile,
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load on this host has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
}

async function stopDaemon() {
  if (!daemon) return;
  const dead = new Promise((r) => daemon.once('exit', r));
  daemon.kill('SIGTERM');
  await dead;
  daemon = null;
  // The port must be free before the replacement binds it, or the restart test
  // fails as EADDRINUSE and reads like a bug in the store.
  await wait(200);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qa-'));
  dataDir = path.join(tmp, 'data');
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(dataDir);
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  tokenFile = path.join(tmp, 'token');
  fs.writeFileSync(tokenFile, token, { mode: 0o600 });

  await startDaemon();
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
});

after(async () => {
  await stopDaemon();
  // -L targets only OUR socket, never the default server.
  try { execFileSync('tmux', ['-L', TMUX_SOCK, 'kill-server'], { stdio: 'ignore' }); } catch { /* none */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------------- defaults

test('a host with no file serves the built-in wording on /v1/status, at rev 0', async () => {
  assert.equal(fs.existsSync(QA_FILE()), false, 'precondition: nothing written yet');
  const qa = await quickActions();
  assert.deepEqual(qa, {
    rev: 0,
    explain: 'Explain this, briefly:\n\n{selection}',
    execute: 'Run this and show me the output:\n\n{selection}',
    askInNewChat: '{selection}\n\nWhat is going on here?',
    quote: '',
  });
  assert.equal(fs.existsSync(QA_FILE()), false, 'and reading the defaults does not write a file');
});

// -------------------------------------------------------------- the happy path

test('PATCH of one field leaves the other three alone and answers with the whole object', async () => {
  const before = await quickActions();
  const { status, body } = await patch({ explain: 'Why does this work?\n\n{selection}' });
  assert.equal(status, 200);
  assert.equal(body.explain, 'Why does this work?\n\n{selection}');
  assert.equal(body.execute, before.execute, 'a PATCH is not a PUT');
  assert.equal(body.askInNewChat, before.askInNewChat);
  assert.equal(body.quote, before.quote);
  // And the status poll now carries the edit, which is the whole point: the
  // other client learns about it without being told.
  assert.deepEqual(await quickActions(), body);
});

test('each accepted edit increments rev', async () => {
  const start = (await quickActions()).rev;
  assert.equal((await patch({ quote: 'from my terminal:' })).body.rev, start + 1);
  assert.equal((await patch({ quote: 'from the pane:' })).body.rev, start + 2);
});

test('the edit is on disk, normalised, and the stored file names its revision', async () => {
  const stored = JSON.parse(fs.readFileSync(QA_FILE(), 'utf8'));
  assert.equal(stored.quote, 'from the pane:');
  assert.equal(stored.rev, (await quickActions()).rev);
  assert.ok(Number.isInteger(stored.updatedAt) && stored.updatedAt > 1_700_000_000,
    'updatedAt is stamped in seconds, for whoever reads the file by hand');
  assert.equal('updatedAt' in (await quickActions()), false, 'but it is not on the wire');
});

// ----------------------------------------------------------- the revision gate

test('a stale rev is refused with 409 and changes nothing', async () => {
  const before = await quickActions();
  const { status, body } = await patch({ rev: before.rev - 1, quote: 'stomped' });
  assert.equal(status, 409);
  assert.equal(body.error, 'quick actions changed underneath you');
  assert.deepEqual(await quickActions(), before, 'the other client\'s edit survived');
});

test('the current rev is accepted — the check is a match, not a ban', async () => {
  const before = await quickActions();
  const { status, body } = await patch({ rev: before.rev, quote: 'from the pane —' });
  assert.equal(status, 200);
  assert.equal(body.rev, before.rev + 1);
});

// --------------------------------------------------------------- the refusals

test('a wrapping template with no {selection} is refused, in the contract\'s words', async () => {
  const before = await quickActions();
  const { status, body } = await patch({ explain: 'explain this please' });
  assert.equal(status, 400);
  assert.equal(body.error, 'explain must contain {selection} exactly once');
  assert.deepEqual(await quickActions(), before, 'a refused PATCH writes nothing');
});

test('two placeholders are refused as firmly as none', async () => {
  const { status, body } = await patch({ execute: 'Run {selection} and then {selection}' });
  assert.equal(status, 400);
  assert.equal(body.error, 'execute must contain {selection} exactly once');
});

test('the quote lead-in may not carry {selection}', async () => {
  const { status, body } = await patch({ quote: 'you said {selection}' });
  assert.equal(status, 400);
  assert.equal(body.error, 'quote must not contain {selection}');
});

test('a template over 400 characters is refused', async () => {
  const { status, body } = await patch({ askInNewChat: `${'x'.repeat(390)}{selection}` }); // 401
  assert.equal(status, 400);
  assert.equal(body.error, 'askInNewChat is too long (max 400 characters)');
  // Exactly the cap is fine — the boundary is not off by one over HTTP either.
  const at = `${'x'.repeat(389)}{selection}`;
  assert.equal((await patch({ askInNewChat: at })).status, 200);
  await patch({ askInNewChat: '{selection}\n\nWhat is going on here?' });   // put it back
});

// ------------------------------------------------ normalisation and durability

test('a template sent with CRLF line endings is stored with plain newlines', async () => {
  const { status, body } = await patch({ explain: 'Explain this:\r\n\r\n{selection}\r\n' });
  assert.equal(status, 200);
  assert.equal(body.explain, 'Explain this:\n\n{selection}', 'folded, trimmed, and not double-spaced');
  const raw = fs.readFileSync(QA_FILE(), 'utf8');
  assert.equal(raw.includes('\\r'), false, 'no carriage return survives into the file');
  assert.equal(JSON.parse(raw).explain, 'Explain this:\n\n{selection}');
});

test('the templates survive a daemon restart', async () => {
  const before = await quickActions();
  assert.ok(before.rev > 0, 'precondition: something was edited');
  await stopDaemon();
  await startDaemon();
  assert.deepEqual(await quickActions(), before,
    'the file is the record — a restart must not quietly hand back the defaults');
});

test('an unauthenticated PATCH is 401 and never reaches the store', async () => {
  const before = await quickActions();
  const res = await fetch(`${BASE}/v1/quick-actions`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ quote: 'from the internet' }),
  });
  assert.equal(res.status, 401);
  assert.deepEqual(await quickActions(), before);
});
