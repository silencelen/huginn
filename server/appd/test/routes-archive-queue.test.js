'use strict';
// A graceful archive's wrap-up phrase is a SEND, and goes through the send queue.
//
// THE FAILURE: POST /v1/sessions/:name/archive (graceful, the clients' default)
// checked the state file and the dialog refusal and then pasted the phrase
// straight at the pane. Every other gate the queue has was skipped:
//
//   the draft guard   the owner typed into the Screen tab (`git push --force to
//                     main and`), then archived instead of sending. The phrase
//                     went in after their words and the Enter submitted
//                     `git push --force to main andFinish outstanding items, …`
//                     as one instruction, carried out in the wrap-up turn.
//   the shell refusal claude had died under the pane without its SessionEnd hook,
//                     so a state file still stood; the phrase was handed to the
//                     shell with an Enter.
//
// /soft-end and a project's graceful delete already send the same phrase through
// enqueueSend and arm the end from its settle. The archive now does too.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `arq-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch, and nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js. The send-queue
// files added together take 12400-12439; this one owns 12410 + pid%10.
const PORT = 12410 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `arq-${process.pid}`;
const TMUX_SOCK = `huginn-arq-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

const PHRASE = 'Finish outstanding items, commit your work, and prepare to end the session.';
const DRAFT = 'git push --force to main and';

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

/** The title hook's record of a Claude session at a turn boundary. */
function writeIdle(name) {
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${[
    JSON.stringify({ type: 'user', message: { content: 'tidy the backup notes' } }),
    JSON.stringify({ type: 'assistant', message: { stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } }),
    JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 }),
  ].join('\n')}\n`);
  // Stamped now: a state file older than the tmux session is a previous holder's.
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}

async function startClaudePane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_BOOT_MS=300 HG_FAKE_CLAUDE_BANNER_MS=100 `
    + `${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  writeIdle(name);
  return name;
}

/** What is left when claude dies without its SessionEnd hook: a shell, and a state file. */
async function startShellPane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    "env PS1='$ ' /bin/sh"]);
  await until(() => /\$\s*$/.test(capture(name).trimEnd()), 10_000, `${name}'s shell prompt`);
  writeIdle(name);
  return name;
}

const archive = (name) => api(`/v1/sessions/${name}/archive`, {
  method: 'POST', body: JSON.stringify({ mode: 'graceful' }),
});
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-arq-'));
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

test('a graceful archive waits for the draft in the live view instead of submitting it', async () => {
  // ⚠ THE FAIL-FIRST. Before, the phrase was pasted behind the draft at once
  // and both were submitted as one prompt.
  const name = await startClaudePane('draft');
  // The Screen tab typing: text with no Enter, which is what stamps live keys.
  const typed = await api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ text: DRAFT }) });
  assert.equal(typed.status, 200, JSON.stringify(typed.body));
  await until(() => composerOf(name) === DRAFT, 5_000, 'the draft to be in the box');

  const r = await archive(name);
  assert.equal(r.status, 202, JSON.stringify(r.body));

  await wait(1_500);
  assert.deepEqual(submitted(name), [], 'the draft was not submitted, with or without the phrase');
  assert.equal(composerOf(name), DRAFT, 'and it is still the person\'s, untouched');
  assert.equal(r.body.pending, true);
  assert.equal(r.body.delivered, false, 'nothing was typed over somebody mid-sentence');
  assert.equal(r.body.blockedBy, 'draft', 'and the answer says what it is waiting for');
  assert.equal((await typingOf(name)).blockedBy, 'draft');

  // They give the box back; the phrase then goes on its own.
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']);
  await until(() => submitted(name).length > 0, 20_000, 'the phrase once the box is clear');
  assert.deepEqual(submitted(name), [PHRASE], 'the wrap-up phrase alone, never welded to the draft');
});

test('a graceful archive into a pane that dropped to a shell is refused, not typed', async () => {
  const name = await startShellPane('shell');
  const r = await archive(name);
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.match(String(r.body && r.body.error), /shell/);
  await wait(500);
  assert.equal(capture(name).includes('Finish outstanding'), false, 'nothing was handed to the shell');
});
