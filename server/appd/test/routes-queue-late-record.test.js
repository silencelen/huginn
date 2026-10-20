'use strict';
// One boundary, one automated message — when the transcript is LATE.
//
// THE FAILURE: after the queue submits an automated message, the next one waits
// for a NEW turn boundary. The hook's idle stamp was already windowed for that
// (`Math.max(entry.at, q.deliveredAt)`); the transcript was not. Claude Code
// writes the delivered prompt's record a beat after the Enter (its transcript is
// flushed in batches, after any synchronous UserPromptSubmit hook), and until it
// does, the tail still ends on the boundary that released the first message. The
// second was read as idle and pasted into the turn the first had just started —
// spliced in by Claude Code, the first message's instruction never carried out.
//
// Two shapes, both here: two messages waiting in one queue, and a second message
// arriving a moment after the first went in alone (the queue struct is reaped
// the instant it empties, so the first message's mark must outlive it).
//
// The stand-in (fixtures/fake-claude-records.js) writes each prompt's record
// HG_FAKE_RECORD_DELAY_MS after the submit.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `qlate-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch, and nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js. The send-queue
// files added together take 12400-12449; this one owns 12430 + pid%10.
const PORT = 12430 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `qlate-${process.pid}`;
const TMUX_SOCK = `huginn-qlate-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude-records.js');
const typing = require('../lib/typing');

// Long enough that every pump pass after the first delivery happens before the
// record exists; real Claude Code is ~100 ms, which is the same race, narrower.
const RECORD_DELAY_MS = 2_500;

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
const submitted = (name) => readOr(outFor(name)).split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
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
    await wait(100);
  }
  throw new Error(`timed out waiting for ${what}`);
}

const rec = (o) => JSON.stringify(o);
const PROMPT = rec({ type: 'user', message: { role: 'user', content: 'carry on with the migration' }, origin: { kind: 'human' }, promptSource: 'typed' });
const TOOL_USE = rec({ type: 'assistant', message: { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 'toolu_1', name: 'Bash', input: { command: 'make' } }] } });
const TOOL_RESULT = rec({ type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_1', content: 'ok' }] } });
const END_TURN = rec({ type: 'assistant', message: { stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } });
const TURN = rec({ type: 'system', subtype: 'turn_duration', durationMs: 4000 });
const append = (file, ...lines) => fs.appendFileSync(file, `${lines.join('\n')}\n`);

async function startPane(suffix, records, delayMs = RECORD_DELAY_MS) {
  const name = `${PFX}-${suffix}`;
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${records.join('\n')}\n`);
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_TRANSCRIPT=${transcript} `
    + `HG_FAKE_RECORD_DELAY_MS=${delayMs} ${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  return { name, transcript };
}
function writeState(name, state, transcript) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}

function seedProject(memberTmux, role = 'worker') {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const slug = `qlate${madeSessions.size}`;
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify({
    id, name: 'Late Record', slug, kind: 'infra', status: 'active', brief: 'seeded', cwd: tmp,
    lead: { role: 'lead', name: `${slug}-lead`, claudeName: `${slug}/lead`, sessionId: null, spawnedAt: now, endedAt: null },
    members: [{
      role, name: memberTmux, claudeName: `${slug}/${role}`, sessionId: null, cwd: tmp, model: null,
      effort: null, mode: null, firstPrompt: null, spawnedAt: now, endedAt: null,
    }],
    manifest: { tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '', summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1 },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  }));
  return { id, role };
}
const relay = (id, role, text) => api(`/v1/projects/${id}/message`, {
  method: 'POST', body: JSON.stringify({ from: 'lead', to: role, text }),
});
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qlate-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  const home = path.join(tmp, 'home');
  const claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, home, claudeDir]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

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
  const own = await api('/v1/projects');
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

test('two automated sends behind one turn: the second waits even while the first\'s record is unwritten', async () => {
  // ⚠ THE FAIL-FIRST. Before, the second relay followed the first in within a
  // pump pass, read off a tail that still ended on the old boundary.
  const { name, transcript } = await startPane('pair', [PROMPT, TOOL_USE]);
  writeState(name, 'running', transcript);
  const { id, role } = seedProject(name);
  await relay(id, role, 'MARKER-ONE');
  const second = await relay(id, role, 'MARKER-TWO');
  assert.equal(second.body.queued, 2, JSON.stringify(second.body));

  append(transcript, TOOL_RESULT, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length >= 1, 15_000, 'the first relay to be submitted');
  assert.match(submitted(name)[0], /MARKER-ONE/);

  // Before the first prompt's record exists, and after it lands: either way the
  // turn it started is running, and the second relay waits for its end.
  await wait(RECORD_DELAY_MS + 1_500);
  assert.equal(submitted(name).length, 1, 'the second relay must not be spliced into the first one\'s turn');
  const held = await typingOf(name);
  assert.equal(held.queued, 1, `still waiting, not lost (lastError: ${held.lastError})`);
  assert.equal(held.blockedBy, 'turn');

  append(transcript, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 2, 15_000, 'the second relay at the next boundary');
  assert.match(submitted(name)[1], /MARKER-TWO/);
  assert.doesNotMatch(submitted(name)[1], /MARKER-ONE/);
});

test('an automated send a moment after another went in alone still waits for that turn', async () => {
  // The queue struct is reaped the instant the first relay leaves it, so what
  // the second is judged against has to outlive it.
  const { name, transcript } = await startPane('alone', [PROMPT, END_TURN, TURN]);
  writeState(name, 'idle', transcript);
  const { id, role } = seedProject(name);
  const first = await relay(id, role, 'MARKER-ALONE');
  assert.equal(first.body.delivered, true, 'an idle session takes the first at once');
  const second = await relay(id, role, 'MARKER-AFTER');
  assert.equal(second.body.delivered, false, 'the second must not follow it into its turn');
  assert.equal(second.body.blockedBy, 'turn');

  await wait(RECORD_DELAY_MS + 1_500);
  assert.equal(submitted(name).length, 1);
  append(transcript, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 2, 15_000, 'the second relay at the next boundary');
  assert.match(submitted(name)[1], /MARKER-AFTER/);
});

test('a record slower than the wait\'s bound is still waited for while the hook says the turn runs', async () => {
  // The wait for a delivery to show up is bounded (typing.DELIVERY_SHOW_MAX_MS)
  // so a pane that never writes a transcript cannot hold the lane forever — but
  // a slow synchronous UserPromptSubmit hook is not that pane: the title hook
  // has already marked the turn `running`, and that keeps the wait going.
  const slow = typing.DELIVERY_SHOW_MAX_MS + 3_000;
  const { name, transcript } = await startPane('slowhook', [PROMPT, TOOL_USE], slow);
  writeState(name, 'running', transcript);
  const { id, role } = seedProject(name);
  await relay(id, role, 'MARKER-SLOW-ONE');
  await relay(id, role, 'MARKER-SLOW-TWO');
  append(transcript, TOOL_RESULT, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length >= 1, 15_000, 'the first relay to be submitted');
  writeState(name, 'running', transcript);   // the title hook, on UserPromptSubmit

  await wait(slow + 1_500);
  assert.equal(submitted(name).length, 1, 'not past the bound either, while the turn is running');
  append(transcript, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 2, 15_000, 'the second relay at the next boundary');
  assert.match(submitted(name)[1], /MARKER-SLOW-TWO/);
});

test('an automated send into a session nobody has typed into lately still goes at once', async () => {
  // The guard must not become a wall: with no delivery behind it there is
  // nothing to wait for, and an idle session is told straight away.
  const { name, transcript } = await startPane('fresh', [PROMPT, END_TURN, TURN]);
  writeState(name, 'idle', transcript);
  const { id, role } = seedProject(name);
  const r = await relay(id, role, 'MARKER-FRESH');
  assert.equal(r.body.delivered, true, JSON.stringify(r.body));
  assert.match(submitted(name)[0], /MARKER-FRESH/);
});
