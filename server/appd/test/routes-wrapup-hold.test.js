'use strict';
// A wrap-up phrase waits for an EMPTY box — the rule the model ladder's job keeps.
//
// The three ways a session is wound down — POST /soft-end, a graceful
// POST /archive, and a project's graceful DELETE — type the same wrap-up phrase
// and then end the session once it settles. An ordinary message is held by
// somebody's draft only while the Screen tab is being typed in (the 60 s
// live-keys window); after that, or for a draft typed at an attached terminal, it
// goes in after the draft. For the wrap-up phrase that meant the draft was
// submitted with it, carried out in the wrap-up turn, and the session ended.
//
// So (owner decision, 2026-10-20) a wrap-up phrase waits while the box holds
// anything at all, reported as `blockedBy: "draft"` like the ladder job; and if
// the box is still not empty when the wait runs out, the phrase is not typed, the
// end is not armed, and the reason is logged and reported. An ordinary message
// keeps the Screen tab's 60 s rule exactly as it was.
//
// The wait is ten minutes; HUGINN_APPD_EMPTY_BOX_WAIT_MS shortens it here.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `wrap-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch, and nothing leaves the host.

require('./retry-fetch'); // and with it ./no-owner-contact, before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — blocks are checked for overlaps by port-blocks.test.js.
const PORT = 12450 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
const PFX = `wrap-${process.pid}`;
const TMUX_SOCK = `huginn-wrap-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

const PHRASE = 'Finish outstanding items, commit your work, and prepare to end the session.';
const DRAFT = 'git push --force to main and';
// Long enough to see the hold and clear the box inside it; short enough to wait out.
const WAIT_MS = 6_000;

let tmp, stateDir, dataDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
function alive(name) {
  try { sh('tmux', ['has-session', '-t', `=${name}`]); return true; } catch { return false; }
}
const composerOf = (name) => typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const submitted = (name) => readOr(outFor(name)).split('\n').filter((l) => l.trim());
const journal = () => readOr(daemonLog);
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

async function until(fn, ms, what) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await wait(150);
  }
  throw new Error(`timed out waiting for ${what}`);
}

function writeState(name, state, transcript) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}

/**
 * A stand-in `claude` at a turn boundary whose box holds `typed` when it paints:
 * text nobody is typing in the Screen tab — a draft left at an attached terminal.
 */
async function startPane(suffix, typed = DRAFT) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_BOOT_MS=300 HG_FAKE_CLAUDE_BANNER_MS=100 `
    + `${typed ? `HG_FAKE_CLAUDE_TYPED=${JSON.stringify(typed)} ` : ''}${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${[
    JSON.stringify({ type: 'user', message: { content: 'tidy the backup notes' } }),
    JSON.stringify({ type: 'assistant', message: { stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } }),
    JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 }),
  ].join('\n')}\n`);
  // Stamped now: a state file older than the tmux session is a previous holder's.
  writeState(name, 'idle', transcript);
  return { name, transcript };
}

/** The wrap-up turn runs and ends: what makes an ARMED end fire. */
async function wrapUpTurn(name, transcript) {
  writeState(name, 'running', transcript);
  await wait(700);
  writeState(name, 'idle', transcript);
  // The end needs idle to HOLD (lib/softend KILL_STABLE_MS); the rewrite is only
  // a nudge for the state-file watch, so the check comes sooner than the 10 s tick.
  await wait(3_600);
  writeState(name, 'idle', transcript);
}

const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-wrap-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  const home = path.join(tmp, 'home');
  const claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, home, claudeDir]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: home,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
      HUGINN_APPD_EMPTY_BOX_WAIT_MS: String(WAIT_MS),
      HUGINN_APPD_USAGE_URL: 'http://127.0.0.1:9/usage',
      HUGINN_APPD_OAUTH_ACCOUNT_URL: 'http://127.0.0.1:9/account',
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

function reap(child, ms = 10_000) {
  if (child.exitCode !== null || child.signalCode) return Promise.resolve();
  return new Promise((resolve) => {
    const hard = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
    const giveUp = setTimeout(resolve, ms);
    const done = () => { clearTimeout(hard); clearTimeout(giveUp); resolve(); };
    child.once('exit', done);
    try { child.kill('SIGTERM'); } catch { done(); }
  });
}

after(async () => {
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a soft end waits while the box holds a draft, then goes alone and ends the session', async () => {
  // ⚠ THE FAIL-FIRST. A draft nobody is typing in the Screen tab never held a
  // send, so the phrase went in after it and both were submitted as one prompt.
  const { name, transcript } = await startPane('soft');
  const r = await api(`/v1/sessions/${name}/soft-end`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await wait(1_500);
  assert.deepEqual(submitted(name), [], 'nothing was submitted while the draft was in the box');
  assert.equal(composerOf(name), DRAFT, 'and the draft is untouched');
  assert.equal(r.body.delivered, false);
  assert.equal(r.body.blockedBy, 'draft', 'the word the ladder job and a held message report');
  assert.equal((await typingOf(name)).blockedBy, 'draft');

  // The box is cleared: the phrase goes on its own, and the end is armed from it.
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']);
  await until(() => submitted(name).length > 0, 10_000, 'the phrase once the box is empty');
  assert.deepEqual(submitted(name), [PHRASE]);
  await wrapUpTurn(name, transcript);
  await until(() => !alive(name), 15_000, 'the session to be ended once the wrap-up settled');
});

test('a soft end whose box never empties gives up: nothing typed, nothing armed, and it says why', async () => {
  const { name, transcript } = await startPane('giveup');
  const r = await api(`/v1/sessions/${name}/soft-end`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'draft');

  await until(async () => (await typingOf(name)).queued === 0, WAIT_MS + 8_000, 'the wait to run out');
  assert.deepEqual(submitted(name), [], 'the phrase was never typed');
  assert.equal(composerOf(name), DRAFT, 'and the draft was left as it was');
  const t = await typingOf(name);
  assert.match(String(t.lastError), /unsent text/, `the reason is reported: ${JSON.stringify(t)}`);
  assert.match(journal(), new RegExp(`typing: ${name}: .*unsent text`), 'and logged');
  assert.match(journal(), new RegExp(`soft-end: ${name}: the wrap-up phrase did not land .*not arming the auto-end`));

  // Nothing is armed, so a turn that runs and settles ends nothing.
  await wrapUpTurn(name, transcript);
  await wait(2_000);
  assert.equal(alive(name), true, 'the session is still there');
});

test('a graceful archive waits the same way, and gives up without archiving', async () => {
  const { name, transcript } = await startPane('archive');
  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'graceful' }) });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  await wait(1_500);
  assert.deepEqual(submitted(name), [], 'nothing was submitted while the draft was in the box');
  assert.equal(r.body.delivered, false);
  assert.equal(r.body.blockedBy, 'draft');

  await until(async () => (await typingOf(name)).queued === 0, WAIT_MS + 8_000, 'the wait to run out');
  assert.deepEqual(submitted(name), []);
  assert.match(String((await typingOf(name)).lastError), /unsent text/);
  assert.match(journal(), new RegExp(`archive: ${name}: the wrap-up phrase did not land .*not arming the auto-end`));
  await wrapUpTurn(name, transcript);
  await wait(2_000);
  assert.equal(alive(name), true, 'not ended');
  const rows = (await api('/v1/archive')).body;
  assert.equal(JSON.stringify(rows).includes(`sid-${name}`), false, 'and not archived');
});

test('a project\'s graceful delete waits the same way for its member', async () => {
  const { name } = await startPane('member');
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify({
    id, name: 'Wrap Up', slug: 'wrapup', kind: 'infra', status: 'active', brief: 'seeded', cwd: tmp,
    lead: { role: 'lead', name: 'wrapup-lead', claudeName: 'wrapup/lead', sessionId: null, spawnedAt: now, endedAt: null },
    members: [{
      role: 'worker', name, claudeName: 'wrapup/worker', sessionId: null, cwd: tmp, model: null,
      effort: null, mode: null, firstPrompt: null, spawnedAt: now, endedAt: null,
    }],
    manifest: { tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '', summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1 },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  }));
  const r = await api(`/v1/projects/${id}`, { method: 'DELETE', body: JSON.stringify({ end: 'graceful' }) });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await wait(1_500);
  assert.deepEqual(submitted(name), [], 'nothing was submitted while the draft was in the box');
  assert.equal((await typingOf(name)).blockedBy, 'draft');

  sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']);
  await until(() => submitted(name).length > 0, 10_000, 'the phrase once the box is empty');
  assert.deepEqual(submitted(name), [PHRASE]);
});

test('an ordinary message keeps the Screen tab\'s rule: a draft nobody is typing does not hold it', async () => {
  // Unchanged on purpose. Only a draft typed in the Screen tab in the last 60 s
  // holds an ordinary send (decision 59); the empty-box rule is for the phrases
  // that end a session.
  const { name } = await startPane('ordinary');
  const r = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'and redeploy', keys: ['Enter'] }),
  });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.delivered, true, 'sent at once, as before');
  assert.equal(r.body.blockedBy, null);
  assert.equal(submitted(name).length, 1);
  assert.match(submitted(name)[0], /and redeploy$/);
});
