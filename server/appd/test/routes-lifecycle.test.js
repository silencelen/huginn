'use strict';
// Route-level tests for the session lifecycle: /keys text delivery, the DELETE
// hygiene (kill AND remove the orphaned state file), and the soft-end route
// including the auto-end-on-settle timing. Clones the throwaway-daemon harness
// from routes-answer.test.js.
//
// SAFETY: never touches a real session. Every tmux session it makes is named
// `life-<pid>-*`, runs an inert `cat >/dev/null` so any typed text cannot
// execute, and is killed in after(). State files are written into a scratch
// HUGINN_APPD_STATE_DIR, never the live daemon's /run directory.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 9700 + (process.pid % 250);
const BASE = `http://127.0.0.1:${PORT}`;
const PFX = `life-${process.pid}`;

let tmp, stateDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) { return execFileSync(cmd, args, { encoding: 'utf8' }); }
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
function mkSession(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}
function sessionAlive(name) {
  try { sh('tmux', ['has-session', '-t', `=${name}`]); return true; } catch { return false; }
}
function writeState(name, state, ts = Math.floor(Date.now() / 1000)) {
  fs.writeFileSync(path.join(stateDir, name),
    JSON.stringify({ state, sessionId: null, transcript: null, cwd: tmp, ts }));
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
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-life-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
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
  for (let i = 0; i < 100; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
});

after(() => {
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('/keys delivers literal text into the pane', async () => {
  const name = mkSession('keys');
  const { status } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'HELLO-FROM-KEYS' }),
  });
  assert.equal(status, 200);
  await wait(300);
  assert.match(capture(name), /HELLO-FROM-KEYS/);
});

test('DELETE kills the session AND removes its state file', async () => {
  const name = mkSession('del');
  writeState(name, 'idle');
  assert.ok(fs.existsSync(path.join(stateDir, name)), 'precondition: state file exists');

  const { status } = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(status, 200);
  assert.equal(sessionAlive(name), false, 'session killed');
  assert.equal(fs.existsSync(path.join(stateDir, name)), false,
    'the orphaned state file must be removed (SessionEnd hook never fires on a kill)');
});

test('soft-end types the phrase and reports it', async () => {
  const name = mkSession('soft');
  writeState(name, 'idle');
  const { status, body } = await api(`/v1/sessions/${name}/soft-end`, {
    method: 'POST', body: JSON.stringify({ auto: false }),
  });
  assert.equal(status, 200);
  assert.equal(body.auto, false);
  assert.match(body.phrase, /finish/i);
  await wait(300);
  // The phrase (its first word is enough; the pane wraps) reached the pane.
  assert.match(capture(name), /Finish/);
});

test('soft-end refuses while a question is waiting', async () => {
  const name = mkSession('attn');
  writeState(name, 'attention');
  const { status } = await api(`/v1/sessions/${name}/soft-end`, {
    method: 'POST', body: JSON.stringify({}),
  });
  assert.equal(status, 409);
});

test('soft-end refuses a pane with no Claude state unless forced', async () => {
  const name = mkSession('shell');            // no state file written
  const refused = await api(`/v1/sessions/${name}/soft-end`, {
    method: 'POST', body: JSON.stringify({}),
  });
  assert.equal(refused.status, 409, 'a plain shell would EXECUTE the phrase');
  const forced = await api(`/v1/sessions/${name}/soft-end`, {
    method: 'POST', body: JSON.stringify({ force: true, auto: false }),
  });
  assert.equal(forced.status, 200);
});

test('soft-end 404s an unknown session', async () => {
  const { status } = await api(`/v1/sessions/${PFX}-nope/soft-end`, {
    method: 'POST', body: JSON.stringify({ force: true }),
  });
  assert.equal(status, 404);
});

test('auto soft-end kills the session once idle has held', async () => {
  const name = mkSession('auto');
  writeState(name, 'running');
  const { status, body } = await api(`/v1/sessions/${name}/soft-end`, {
    method: 'POST', body: JSON.stringify({ auto: true }),
  });
  assert.equal(status, 200);
  assert.equal(body.auto, true);
  assert.equal(body.queued, true, 'sent while running, so it queued');

  // Arm: a running observation. Rewriting the state file triggers the daemon's
  // fs.watch -> softEndTick.
  writeState(name, 'running');
  await wait(300);
  // Now settle: idle, and keep it idle past the stability gate (KILL_STABLE_MS
  // = 3s). Two idle observations >3s apart are needed; the rewrite drives a tick.
  writeState(name, 'idle');
  await wait(3400);
  writeState(name, 'idle');
  // Give the debounced tick + tmux kill time to land.
  for (let i = 0; i < 20 && sessionAlive(name); i++) await wait(150);
  assert.equal(sessionAlive(name), false, 'a settled auto soft-end should have ended the session');
});

test('attention cancels a pending auto soft-end (the wrap-up asked a question)', async () => {
  const name = mkSession('cancel');
  writeState(name, 'running');
  await api(`/v1/sessions/${name}/soft-end`, { method: 'POST', body: JSON.stringify({ auto: true }) });
  writeState(name, 'running');
  await wait(300);
  writeState(name, 'attention');      // the wrap-up turned into a question
  await wait(600);
  writeState(name, 'attention');
  await wait(600);
  assert.equal(sessionAlive(name), true, 'a question must never be followed by a kill');
});

// --- uploads served back for chat-history thumbnails ------------------------

async function upload(bytes, contentType) {
  const res = await fetch(`${BASE}/v1/uploads`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': contentType },
    body: bytes,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

test('an uploaded image is served back byte-identical with a safe type + nosniff', async () => {
  // A 1x1 PNG.
  const png = Buffer.from(
    '89504e470d0a1a0a0000000d494844520000000100000001080600000' +
    '01f15c4890000000d49444154789c6360000002000100' +
    '05fe02fea7c1b2c40000000049454e44ae426082', 'hex');
  const up = await upload(png, 'image/png');
  assert.equal(up.status, 200);
  const base = up.body.path.split('/').pop();

  const res = await fetch(`${BASE}/v1/uploads/${base}`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'image/png');
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
  const got = Buffer.from(await res.arrayBuffer());
  assert.ok(got.equals(png), 'served bytes must be identical to what was uploaded');
});

test('a non-image upload serves as octet-stream', async () => {
  const up = await upload(Buffer.from('id,name\n1,a\n'), 'text/csv');
  assert.equal(up.status, 200);
  const base = up.body.path.split('/').pop();
  const res = await fetch(`${BASE}/v1/uploads/${base}`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/octet-stream');
  res.body?.cancel?.();
});

test('uploads GET refuses a traversal name and 404s a missing one', async () => {
  const trav = await fetch(`${BASE}/v1/uploads/${encodeURIComponent('../token')}`,
    { headers: { authorization: `Bearer ${token}` } });
  assert.ok(trav.status === 400 || trav.status === 404, 'a separator in the name must not resolve');
  trav.body?.cancel?.();

  const missing = await fetch(`${BASE}/v1/uploads/up-does-not-exist.jpg`,
    { headers: { authorization: `Bearer ${token}` } });
  assert.equal(missing.status, 404);
  missing.body?.cancel?.();
});

test('uploads GET requires auth', async () => {
  const res = await fetch(`${BASE}/v1/uploads/whatever.jpg`);
  assert.equal(res.status, 401);
  res.body?.cancel?.();
});

// --- ask-sidecar fusion at the route layer ---------------------------------

function writeAskSidecar(name, questions) {
  fs.mkdirSync(path.join(stateDir, 'ask'), { recursive: true });
  fs.writeFileSync(path.join(stateDir, 'ask', name),
    JSON.stringify({ v: 1, tool: 'AskUserQuestion', sessionId: 's', ts: Math.floor(Date.now() / 1000),
      input: { questions } }));
}
const COLOR_Q = [{
  question: 'Pick a color?', header: 'Color', multiSelect: false,
  options: [
    { label: 'Red', description: 'warm' },
    { label: 'Green', description: 'calm' },
    { label: 'Blue', description: 'cool' },
  ],
}];
const COLOR_PANE = [
  'Pick a color?', '',
  '❯ 1. Red', '  2. Green', '  3. Blue',
  '  4. Type something.', '  5. Chat about this', '',
  'Enter to select · Esc to cancel',
].join('\\n');

// A session whose pane already shows `paneText` (printf runs, then cat holds so
// the shell prompt never draws under it — a shell prompt reads as chrome and
// correctly makes detectPrompt call the run history).
function mkSessionWithPane(suffix, paneText) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${paneText}\\n"; cat'`]);
  madeSessions.add(name);
  return name;
}

test('screen fuses the hook sidecar: hook labels + descriptions, TUI extras flagged', async () => {
  const name = mkSessionWithPane('fuse', COLOR_PANE);
  writeAskSidecar(name, COLOR_Q);
  await wait(500);
  const { status, body } = await api(`/v1/sessions/${name}/screen`);
  assert.equal(status, 200);
  assert.ok(body.prompt, 'a prompt should be detected');
  assert.equal(body.prompt.source, 'hook', 'the sidecar should have been fused in');
  assert.deepEqual(body.prompt.options.slice(0, 3).map((o) => o.label), ['Red', 'Green', 'Blue']);
  assert.equal(body.prompt.options[0].description, 'warm', 'hook descriptions come through');
  assert.ok(body.prompt.options.slice(3).every((o) => o.extra === true), 'Type something / Chat about this flagged');
  assert.match(body.prompt.fingerprint, /^[0-9a-f]{12}$/);
});

test('a multi-part AskUserQuestion is NOT tappable — served as a Screen-tab card', async () => {
  // Three questions in one call. The pane shows question 1; the sidecar carries
  // all three. Fusion detects questionCount>1 and must NOT offer buttons (the
  // digit path over-answers), instead a read-only ask card that /answer refuses
  // so the client routes to the Screen tab.
  const name = mkSessionWithPane('mq', [
    '←  ☐ Fruit  ☐ Color  ☐ Size  ✔ Submit  →', '',
    'Pick a fruit?', '',
    '❯ 1. Apple', '  2. Banana',
    '  3. Type something.', '  4. Chat about this', '',
    'Enter to select · Esc to cancel',
  ].join('\\n'));
  writeAskSidecar(name, [
    { question: 'Pick a fruit?', header: 'Fruit', multiSelect: false,
      options: [{ label: 'Apple' }, { label: 'Banana' }] },
    { question: 'Pick a color?', header: 'Color', multiSelect: false,
      options: [{ label: 'Red' }, { label: 'Green' }] },
    { question: 'Pick a size?', header: 'Size', multiSelect: false,
      options: [{ label: 'Small' }, { label: 'Large' }] },
  ]);
  await wait(400);
  const { body } = await api(`/v1/sessions/${name}/screen`);
  assert.equal(body.prompt, null, 'a multi-part question must not be a tappable prompt');
  assert.ok(body.ask, 'it is offered as a read-only ask card');
  assert.equal(body.ask.multiPart, true);

  // A tap reaches /answer and is refused as undetected → client opens Screen tab.
  const ans = await api(`/v1/sessions/${name}/answer`, {
    method: 'POST', body: JSON.stringify({ option: 1, fingerprint: body.ask.fingerprint }),
  });
  assert.equal(ans.status, 409);
  assert.equal(ans.body.reason, 'undetected');
});

test('a sidecar with no readable pane run and attention state yields a degraded card', async () => {
  const name = mkSession('degraded');
  // A pane the detector cannot read as a run (just prose), so fusion has no run.
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'printf "thinking about it...\\n"', 'Enter']);
  writeState(name, 'attention');
  writeAskSidecar(name, COLOR_Q);
  await wait(400);
  const { body } = await api(`/v1/sessions/${name}/screen`);
  assert.equal(body.prompt, null, 'no live run to answer directly');
  assert.ok(body.ask, 'the degraded card should be present');
  assert.equal(body.ask.answerable, false);
  assert.equal(body.ask.question, 'Pick a color?');

  // Answering it with no live run is refused distinctly so the client deep-links.
  const ans = await api(`/v1/sessions/${name}/answer`, {
    method: 'POST', body: JSON.stringify({ option: 1, fingerprint: body.ask.fingerprint }),
  });
  assert.equal(ans.status, 409);
  assert.equal(ans.body.reason, 'undetected');
});
