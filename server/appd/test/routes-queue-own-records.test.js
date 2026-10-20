'use strict';
// The 'owner typed first' drop rule, against a transcript that RECORDS what is sent.
//
// THE FAILURE: an automated send waiting in the queue is dropped when a person
// speaks after it was queued — and "a person speaking" was any `user` record
// with text in it. Two kinds of record that no person wrote passed that test:
//
//   appd's own   two automated messages queued behind one turn (two relays, a
//                heads-up and a resume). The turn ends, the first is pasted and
//                submitted, and Claude Code records it EXACTLY as it records a
//                message the owner typed. The second one's scan starts where it
//                was queued, before that record: "dropped an automated project
//                message: you typed first" — the one-boundary-one-message rule
//                promises it waits for the next boundary instead.
//   Claude Code's  a background task finishes while a send is held, and its
//                notification drains in as a `user` record (`origin.kind:
//                "task-notification"`) — the same drop, with nobody at the keys.
//
// routes-queue-boundary.test.js holds the same promise with a transcript that
// never grows, which is why it could not see this. Here the stand-in
// (fixtures/fake-claude-records.js) appends Claude Code's prompt record on every
// submit, before its box redraws.
//
// THE DOOR is POST /v1/projects/:id/message (automated, every gate), with the
// project record seeded on disk as routes-queue-boundary does.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `qown-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch, and nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js. The send-queue
// files added together take 12400-12449; this one owns 12420 + pid%10.
const PORT = 12420 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `qown-${process.pid}`;
const TMUX_SOCK = `huginn-qown-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude-records.js');
const typing = require('../lib/typing');

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
/** Every prompt the stand-in has submitted, whole — one per submit. */
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
// The real shape (fixtures/transcripts/task-notification-echo.jsonl, record 4).
const NOTIFICATION = rec({
  type: 'user',
  message: { role: 'user', content: '<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n'
    + '<summary>Background command "tail the log" completed (exit code 0)</summary>\n</task-notification>' },
  origin: { kind: 'task-notification' },
  promptSource: 'system',
});

/** A recording stand-in mid-turn, as the transcript and the title hook say. */
async function startMidTurn(suffix) {
  const name = `${PFX}-${suffix}`;
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${PROMPT}\n${TOOL_USE}\n`);
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_TRANSCRIPT=${transcript} ${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  writeState(name, 'running', transcript);
  return { name, transcript };
}
function writeState(name, state, transcript) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}
const append = (file, ...lines) => fs.appendFileSync(file, `${lines.join('\n')}\n`);

/** A project whose worker is this session; the lead need not exist. */
function seedProject(memberTmux, role = 'worker') {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const slug = `qown${madeSessions.size}`;
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify({
    id, name: 'Own Records', slug, kind: 'infra', status: 'active', brief: 'seeded', cwd: tmp,
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
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qown-'));
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

test('the second of two automated sends behind one turn is not dropped by the first one\'s record', async () => {
  // ⚠ THE FAIL-FIRST. Before, the second relay was dropped as "you typed first"
  // the moment the first one's record was written.
  const { name, transcript } = await startMidTurn('pair');
  const { id, role } = seedProject(name);
  const first = await relay(id, role, 'MARKER-ONE');
  assert.equal(first.status, 202, JSON.stringify(first.body));
  assert.equal(first.body.blockedBy, 'turn');
  const second = await relay(id, role, 'MARKER-TWO');
  assert.equal(second.body.queued, 2, 'both wait on the same queue');

  // The turn ends; the title hook writes its one idle.
  append(transcript, TOOL_RESULT, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 1, 15_000, 'the first relay to be submitted');
  assert.match(submitted(name)[0], /MARKER-ONE/);

  // Its record is on disk now (the stand-in writes it before the box redraws),
  // and the turn it started is running. The second relay must still be there.
  await wait(2_500);
  const held = await typingOf(name);
  assert.equal(held.queued, 1, `the second relay is waiting, not dropped (lastError: ${held.lastError})`);
  assert.equal(held.blockedBy, 'turn');
  assert.doesNotMatch(String(held.lastError || ''), /typed first/);
  assert.equal(submitted(name).length, 1, 'and it did not follow the first into its turn');

  // The first relay's turn ends. One boundary, one message: now it goes, alone.
  append(transcript, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 2, 15_000, 'the second relay at the next boundary');
  assert.match(submitted(name)[1], /MARKER-TWO/);
  assert.doesNotMatch(submitted(name)[1], /MARKER-ONE/, 'a prompt of its own');
});

test('a background task\'s notification is not the owner typing', async () => {
  const { name, transcript } = await startMidTurn('notify');
  const { id, role } = seedProject(name);
  const r = await relay(id, role, 'MARKER-NOTIFY');
  assert.equal(r.body.blockedBy, 'turn');

  // The turn ends and a finished background task's notification drains straight
  // into the idle session, starting a turn of its own (the hook says running).
  writeState(name, 'running', transcript);
  append(transcript, TOOL_RESULT, END_TURN, TURN, NOTIFICATION);
  await wait(2_500);
  const held = await typingOf(name);
  assert.equal(held.queued, 1, `the relay is still waiting, not dropped (lastError: ${held.lastError})`);
  assert.doesNotMatch(String(held.lastError || ''), /typed first/);
  assert.equal(submitted(name).length, 0, 'and it waits for the notification\'s turn to end');

  append(transcript, END_TURN, TURN);
  writeState(name, 'idle', transcript);
  await until(() => submitted(name).length === 1, 15_000, 'the relay at the end of that turn');
  assert.match(submitted(name)[0], /MARKER-NOTIFY/);
});

test('a person who does type in the meantime still drops the automated send', async () => {
  // The rule itself must survive the fix: it is the one thing that stops appd
  // talking over the owner. A plain typed record nobody queued is a person.
  const { name, transcript } = await startMidTurn('person');
  const { id, role } = seedProject(name);
  const r = await relay(id, role, 'MARKER-PERSON');
  assert.equal(r.body.blockedBy, 'turn');
  append(transcript, TOOL_RESULT, END_TURN, TURN,
    rec({ type: 'user', message: { role: 'user', content: 'actually, stop and look at the logs' }, origin: { kind: 'human' }, promptSource: 'typed' }));
  writeState(name, 'running', transcript);
  await until(async () => (await typingOf(name)).queued === 0, 10_000, 'the relay to be dropped');
  assert.match(String((await typingOf(name)).lastError), /you typed first/);
  assert.equal(submitted(name).length, 0);
});
