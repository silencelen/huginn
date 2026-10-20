'use strict';
// A message carrying invisible characters, sent into a Claude Code that refuses them.
//
// THE FAILURE: Claude Code 2.1.30x will not submit a prompt holding characters
// its classifier calls invisible (a soft hyphen or a zero-width joiner copied off
// a web page, a stray variation selector). It strips them, puts the text back in
// the box, says `Removed 1 invisible character · review and press Enter to
// send`, and sends nothing. appd pasted such text unchanged, and then:
//
//   drawn ones (ZWNJ)   the paste was seen to land, the Enter refused, and the
//                       confirm compared the box with a probe that still had
//                       the character in it — "cleared", so the POST answered
//                       submitted:true about a message nobody had sent, and the
//                       person's retry was swallowed as a duplicate of it.
//   undrawn ones (SHY)  the paste never "appeared", the recovery left it alone,
//                       and every retry pasted one more copy.
//
// Now the characters Claude Code would remove are removed before the paste where
// that is certain (typing.sendable), and every comparison between the pane and a
// message ignores them, so what is left is reported honestly and a retry is the
// Enter the box is waiting for.
//
// The stand-in is fixtures/fake-claude-invisible.js; read its one
// simplification before adding a case here.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `inv-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch, and nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js. The send-queue
// files added together take 12400-12449; this one owns 12440 + pid%10.
const PORT = 12440 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `inv-${process.pid}`;
const TMUX_SOCK = `huginn-inv-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude-invisible.js');
const typing = require('../lib/typing');

let tmp, stateDir, token, daemon;
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

async function startPane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} ${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  return name;
}
/** What the chat composer sends: the text and its Enter. */
const send = (name, text) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
});

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-inv-'));
  stateDir = path.join(tmp, 'state');
  const dataDir = path.join(tmp, 'data');
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

test('a zero-width non-joiner in plain text: sent in one press, and reported as what happened', async () => {
  // ⚠ THE FAIL-FIRST. Before: the Enter was refused, the POST said
  // submitted:true, and nothing reached the session.
  const name = await startPane('zwnj');
  const r = await send(name, 'use the auto\u200Cscaler config');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await wait(500);
  assert.deepEqual(submitted(name), ['use the autoscaler config'], 'one prompt reached the session');
  assert.equal(r.body.submitted, true);
  assert.equal(r.body.delivered, true);
});

test('a soft hyphen the renderer never draws: sent in one press, never pasted twice', async () => {
  const name = await startPane('shy');
  const r = await send(name, 'use the auto\u00ADscaler config');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  await wait(500);
  assert.deepEqual(submitted(name), ['use the autoscaler config']);
  assert.equal(r.body.submitted, true);
  const again = await send(name, 'and redeploy');
  assert.equal(again.body.submitted, true, JSON.stringify(again.body));
  assert.deepEqual(submitted(name), ['use the autoscaler config', 'and redeploy'], 'two prompts, never welded');
});

test('where Claude Code still refuses, the answer says so and the retry sends it', async () => {
  // Outside plain ASCII, whether Claude Code keeps a joiner depends on the
  // script around it, so appd leaves it in and lets Claude Code decide. The
  // stand-in refuses (as the real one does in Latin text): the answer must be
  // honest, and pressing Send again must send it — not be swallowed as a
  // duplicate, and not paste a second copy behind the first.
  const name = await startPane('honest');
  const text = 'café auto\u200Cscaler config';
  const r = await send(name, text);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.submitted, false, `nothing was sent, and the answer says so: ${JSON.stringify(r.body)}`);
  assert.equal(r.body.delivered, false);
  assert.deepEqual(submitted(name), []);

  const retry = await send(name, text);
  assert.equal(retry.status, 200, JSON.stringify(retry.body));
  assert.notEqual(retry.body.duplicate, true, 'a message that was never sent is not a duplicate');
  await until(() => submitted(name).length > 0, 10_000, 'the retry to send it');
  assert.deepEqual(submitted(name), ['café autoscaler config'], 'once, as Claude Code cleaned it');
});
