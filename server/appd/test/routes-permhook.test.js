'use strict';
// /answer through a waiting PermissionRequest hook (lib/permhook.js), against a
// throwaway daemon and a private tmux socket — same isolation as routes-answer.
// A simulated hook claims the decision the way hooks/huginn-permission-hook does
// (rename <id>.ans -> <id>.taken); the pane runs `cat`, so a typed digit would be
// echoed onto it and is detectable.
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 9690 + (process.pid % 300);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-ph-${process.pid}`;
let tmp, token, daemon, stateDir;

const PROMPT = [
  'Do you want to create probe.txt?',
  '',
  '❯ 1. Yes',
  '  2. Yes, and do not ask again',
  '  3. No',
  '',
  'Enter to select · Esc to cancel',
].join('\\n');

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* none */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ph-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(tmp, 'data'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_STATE_DIR: stateDir,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) throw new Error(`port ${PORT} is held by another huginn-appd`);
});
after(() => {
  try { sh('tmux', ['kill-server']); } catch { /* none */ }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

let n = 0;
async function questionSession() {
  const name = `ph-${process.pid}-${n++}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', `sh -c 'printf "${PROMPT}\\n"; cat'`]);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'attention', sessionId: `sid-${name}`, ts: Date.now() }));
  await wait(600);
  const { body } = await api(`/v1/sessions/${name}/screen`);
  assert.ok(body && body.prompt, 'the fake question is on screen');
  return { name, fingerprint: body.prompt.fingerprint };
}
function writeReq(name, fields) {
  const dir = path.join(stateDir, '.perm', name);
  fs.mkdirSync(dir, { recursive: true });
  const id = `${Date.now()}000000-${process.pid}`;
  fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify({
    v: 1, id, pid: process.pid, sessionId: `sid-${name}`, ts: Date.now(), ...fields,
  }));
  return { dir, id };
}
/** A hook that claims whatever answer appears. Resolves the decision it got. */
function fakeHook(dir, id) {
  return new Promise((resolve) => {
    const iv = setInterval(() => {
      try {
        fs.renameSync(path.join(dir, `${id}.ans`), path.join(dir, `${id}.taken`));
        clearInterval(iv);
        resolve(JSON.parse(fs.readFileSync(path.join(dir, `${id}.taken`), 'utf8')));
      } catch { /* not yet */ }
    }, 20);
    setTimeout(() => { clearInterval(iv); resolve(null); }, 8000);
  });
}
const typedOnPane = (name) => sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`])
  .split('\n').some((l) => /^\s*[0-9]\s*$/.test(l));

test('a tap with a matching hook waiting is delivered THROUGH the hook, and nothing is typed', async () => {
  const { name, fingerprint } = await questionSession();
  const { dir, id } = writeReq(name, { tool: 'Write', input: { file_path: '/srv/x/probe.txt', content: 'hi' } });
  const got = fakeHook(dir, id);
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 1, fingerprint }) });
  assert.equal(r.status, 200);
  assert.equal(r.body.via, 'hook');
  assert.deepEqual(await got, { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } });
  await wait(300);
  assert.equal(typedOnPane(name), false, 'no digit reached the pane');
});

test('"No" through the hook is a deny that interrupts, like the TUI', async () => {
  const { name, fingerprint } = await questionSession();
  const { dir, id } = writeReq(name, { tool: 'Write', input: { file_path: '/srv/x/probe.txt' } });
  const got = fakeHook(dir, id);
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 3, fingerprint }) });
  assert.equal(r.body.via, 'hook');
  assert.equal((await got).hookSpecificOutput.decision.interrupt, true);
});

test('a hook that never claims the answer: it is withdrawn and the digit is typed as before', async () => {
  const { name, fingerprint } = await questionSession();
  const { dir } = writeReq(name, { tool: 'Write', input: { file_path: '/srv/x/probe.txt' } });
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 1, fingerprint }) });
  assert.equal(r.status, 200);
  assert.equal(r.body.via, undefined);
  await wait(400);
  assert.equal(typedOnPane(name), true, 'the keystroke path answered it');
  assert.deepEqual(fs.readdirSync(dir).filter((f) => !f.endsWith('.json')), [], 'no answer left for a late hook');
});

test('a request for a DIFFERENT prompt is ignored: keys, and the stale hook gets nothing', async () => {
  const { name, fingerprint } = await questionSession();
  const { dir, id } = writeReq(name, { tool: 'Bash', input: { command: 'rm -rf /srv/other' } });
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 1, fingerprint }) });
  assert.equal(r.body.via, undefined);
  assert.equal(fs.existsSync(path.join(dir, `${id}.ans`)), false);
});

test('"Yes, and do not ask again" changes standing permissions, so it always goes to the keys', async () => {
  const { name, fingerprint } = await questionSession();
  writeReq(name, { tool: 'Write', input: { file_path: '/srv/x/probe.txt' } });
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 2, fingerprint }) });
  assert.equal(r.body.via, undefined);
});

test('a request from a previous claude in the same tmux name (other session id) is ignored', async () => {
  const { name, fingerprint } = await questionSession();
  const { dir, id } = writeReq(name, { tool: 'Write', input: { file_path: '/srv/x/probe.txt' }, sessionId: 'sid-old' });
  const r = await api(`/v1/sessions/${name}/answer`, { method: 'POST', body: JSON.stringify({ option: 1, fingerprint }) });
  assert.equal(r.body.via, undefined);
  assert.equal(fs.existsSync(path.join(dir, `${id}.ans`)), false);
});
