'use strict';
// The ladder's `/model` job and somebody's unsent draft.
//
// THE FAILURE: the queue's draft guard left every pane SCRIPT out, on the
// grounds that a script types no text. The one script there is — the model
// ladder's picker walk, which the arbiter runs and the Undo button runs — starts
// by typing `/model` and pressing Enter. With the owner's half-written sentence
// in the box at a turn boundary, the job was released, `/model` went in after
// their words, and the Enter submitted
//
//   please check the backup and then/model
//
// as a prompt. The picker never opened, and the draft was sent for them.
//
// The job now waits for a bare caret (typing.jobHold in the pump, and one more
// look inside applyLadder before the first key), held with `blockedBy:"draft"`
// like a message is, and runs once the box is clear.
//
// The door in is POST /v1/sessions/:name/headroom/undo against a SEEDED
// headroom record: the same job the arbiter queues, reachable without a usage
// endpoint or a percentage to cross.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `ldraft-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch; the usage endpoints point at
// a closed loopback port and Telegram is blanked, so nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js. The send-queue
// files added together take 12400-12439; this one owns 12400 + pid%10.
const PORT = 12400 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `ldraft-${process.pid}`;
const TMUX_SOCK = `huginn-ldraft-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

const DRAFT = 'please check the backup and then';

let tmp, stateDir, dataDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const composerOf = (name) => typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const submitted = (name) => readOr(outFor(name)).split('\n').filter((l) => l.trim());
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

/** A stand-in `claude` whose box already holds somebody's words when it paints. */
async function startPane(suffix, typed) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_BOOT_MS=300 HG_FAKE_CLAUDE_BANNER_MS=100 `
    + `${typed ? `HG_FAKE_CLAUDE_TYPED=${JSON.stringify(typed)} ` : ''}${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  return name;
}

/** The session at a turn boundary, as the title hook and the transcript say. */
function atBoundary(name) {
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${[
    JSON.stringify({ type: 'user', message: { content: 'run the nightly check' } }),
    JSON.stringify({ type: 'assistant', message: { model: 'claude-opus-5', stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } }),
    JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 }),
  ].join('\n')}\n`);
  // Stamped now, never earlier: a state file older than the tmux session belongs
  // to a previous holder of the name, and the daemon rightly ignores it.
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}

/** A session appd has moved down the ladder, so Undo has something to put back. */
function seedLaddered(names) {
  const sessions = {};
  for (const n of names) {
    sessions[`sid-${n}`] = {
      family: 'opus',
      ladder: { from: 'fable', to: 'opus', at: Date.now() - 60_000, delivery: 'confirmed', sessionOnly: true },
    };
  }
  fs.writeFileSync(path.join(dataDir, 'headroom.json'), JSON.stringify({ v: 1, sessions }));
}

const undo = (name) => api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

let held, clear;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ldraft-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  const home = path.join(tmp, 'home');
  const claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, home, claudeDir]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // Both panes are up, and the record seeded, BEFORE the daemon starts: the
  // headroom state is read once, and the first arbiter tick is seconds away.
  held = await startPane('held', DRAFT);
  clear = await startPane('clear', null);
  atBoundary(held);
  atBoundary(clear);
  seedLaddered([held, clear]);

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
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
      HUGINN_APPD_USAGE_URL: 'http://127.0.0.1:9/usage',
      HUGINN_APPD_OAUTH_ACCOUNT_URL: 'http://127.0.0.1:9/account',
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      HUGINN_FCM_KEY: '',
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

test('the ladder job waits while the box holds somebody\'s draft, and never submits it', async () => {
  // ⚠ THE FAIL-FIRST. Before, the job was released at once: `/model` went in
  // after the draft and the Enter submitted both as one prompt.
  assert.equal(composerOf(held), DRAFT, 'the stand-in painted the draft');
  const r = await undo(held);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.applied, false, 'nothing has moved');

  await wait(1_500);
  assert.deepEqual(submitted(held), [],
    'nothing may be submitted while the draft is in the box — least of all the draft with /model on it');
  assert.equal(composerOf(held), DRAFT, 'and the draft is still there, untouched');
  assert.equal(r.body.queued, true, 'the job is waiting, not run and failed');
  const t = await typingOf(held);
  assert.equal(t.queued, 1);
  assert.equal(t.blockedBy, 'draft', 'and it says what it is waiting for, in the word a held message uses');

  // The person gives the box back. The job runs into a bare caret: `/model` on
  // its own (the stand-in has no picker, so the move itself goes unconfirmed —
  // what matters is what was SUBMITTED).
  sh('tmux', ['send-keys', '-t', `=${held}:`, 'C-u']);
  await until(() => submitted(held).length > 0, 15_000, 'the job to run once the box is clear');
  assert.deepEqual(submitted(held), ['/model'], 'the job typed into an empty box, and only there');
});

test('a bare caret is not held: the job still runs at once', async () => {
  // The guard must not turn into a wall. An empty box is the state the job
  // was always meant to run in.
  const r = await undo(clear);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.queued, false, 'released in the first pass');
  assert.deepEqual(submitted(clear), ['/model']);
});
