'use strict';
// A tmux session name outlives the session that owned it, and the daemon keys a
// session's state on the name. These pin the rule that keeps the two apart:
// state written before the CURRENT holder of a name was born belongs to a
// previous incarnation and must not be served.
//
// The bug this fixes, as reported: create `testsession`, finish, exit, create a
// new `testsession` — and the app's conversation tab shows the DEAD session's
// history while the screen tab correctly shows a fresh one. Claude's SessionEnd
// hook never fires on a kill, so the state file survives the session; measured on
// the author's host, 24 state files existed for 5 live sessions.
//
// SAFETY: never touches a real session. Every tmux session is named
// `ident-<pid>-*`, runs an inert `cat >/dev/null`, and is killed in after().
// State goes to a scratch HUGINN_APPD_STATE_DIR, never the live /run directory.

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
//   session-identity    9930 + pid%40    ->  9930-9969   (this file)
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 9930 + (process.pid % 40);
const BASE = `http://127.0.0.1:${PORT}`;
const PFX = `ident-${process.pid}`;

let tmp, stateDir, token, daemon, transcript;
const madeSessions = new Set();

function sh(cmd, args) { return execFileSync(cmd, args, { encoding: 'utf8' }); }

function mkSession(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}

/**
 * TRAILING COLON, and it is not optional: `-t '=name'` makes display-message
 * return an EMPTY string with exit 0 — every format field blank, no error. This
 * helper silently measured every session as born at epoch 0 until it was fixed,
 * which is the same trap the daemon's sessionExists documents.
 */
function bornAt(name) {
  const out = sh('tmux', ['display-message', '-p', '-t', `=${name}:`,
    '#{session_name}\t#{session_created}']).trim();
  const [found, created] = out.split('\t');
  assert.equal(found, name, `tmux did not resolve ${name} (target form?)`);
  return Number(created);
}

/** A state file as the hook writes one, with an explicit write time. */
function writeState(name, ts, sessionId = 'sess-' + name) {
  fs.writeFileSync(path.join(stateDir, name),
    JSON.stringify({ state: 'idle', sessionId, transcript, cwd: tmp, ts }));
}

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ident-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(stateDir, 'ask'));
  fs.mkdirSync(path.join(stateDir, 'plan'));
  // A real file on disk, so a served state cannot 409 for the OTHER reason
  // ("recorded transcript file is gone") and pass this suite by accident.
  transcript = path.join(tmp, 'transcript.jsonl');
  fs.writeFileSync(transcript,
    JSON.stringify({ type: 'user', message: { role: 'user', content: 'hi' } }) + '\n');
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
      HUGINN_APPD_STATE_DIR: stateDir,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load on this host has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
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
});

after(() => {
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('a state file older than the session does not become its transcript', async () => {
  const name = mkSession('stale');
  // Exactly the leak: the previous holder of this name wrote state, was killed
  // without SessionEnd, and the file outlived it.
  writeState(name, bornAt(name) - 3600, 'dead-session-id');

  const { status, body } = await api(`/v1/sessions/${name}/transcript`);
  assert.equal(status, 409, 'the dead session\'s transcript must not be served');
  assert.match(body.error, /no transcript recorded/);
});

test('the stale state is hidden from the sessions list too', async () => {
  const name = mkSession('list');
  writeState(name, bornAt(name) - 3600, 'dead-session-id');

  const { body } = await api('/v1/sessions');
  const row = body.sessions.find((s) => s.name === name);
  assert.ok(row, 'the session itself is still listed');
  assert.equal(row.state, null, 'a dead session\'s state is not this one\'s state');
  assert.equal(row.claudeSessionId, null);
  assert.equal(row.hasTranscript, false);
});

test('state written after the session was born IS served', async () => {
  // The guard must not be so eager that it blanks live sessions.
  const name = mkSession('live');
  writeState(name, bornAt(name) + 1, 'live-session-id');

  const { status, body } = await api(`/v1/sessions/${name}/transcript`);
  assert.equal(status, 200);
  assert.equal(body.claudeSessionId, 'live-session-id');
});

test('state written in the same second as the birth is served', async () => {
  // Boundary: the hook can fire inside the second tmux created the session, and
  // rejecting that would blank every fast-starting session.
  const name = mkSession('same');
  writeState(name, bornAt(name), 'same-second-id');

  const { status, body } = await api(`/v1/sessions/${name}/transcript`);
  assert.equal(status, 200);
  assert.equal(body.claudeSessionId, 'same-second-id');
});

test('creating a session clears what the last holder of the name left behind', async () => {
  const name = `${PFX}-fresh`;
  madeSessions.add(name);
  writeState(name, Math.floor(Date.now() / 1000) - 3600, 'dead-session-id');
  fs.writeFileSync(path.join(stateDir, 'ask', name), '{"v":1}');
  fs.writeFileSync(path.join(stateDir, 'plan', name), '{"v":1}');

  const { status } = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name }) });
  assert.equal(status, 201);

  assert.equal(fs.existsSync(path.join(stateDir, name)), false, 'state file cleared at create');
  assert.equal(fs.existsSync(path.join(stateDir, 'ask', name)), false, 'ask sidecar cleared');
  assert.equal(fs.existsSync(path.join(stateDir, 'plan', name)), false, 'plan sidecar cleared');
});

test('DELETE clears the prompt sidecars, not just the state file', async () => {
  const name = mkSession('sidecars');
  writeState(name, bornAt(name) + 1);
  fs.writeFileSync(path.join(stateDir, 'ask', name), '{"v":1}');
  fs.writeFileSync(path.join(stateDir, 'plan', name), '{"v":1}');

  const { status } = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(status, 200);
  assert.equal(fs.existsSync(path.join(stateDir, name)), false);
  assert.equal(fs.existsSync(path.join(stateDir, 'ask', name)), false,
    'a sidecar left behind would fuse buttons onto the next session of this name');
  assert.equal(fs.existsSync(path.join(stateDir, 'plan', name)), false);
});
