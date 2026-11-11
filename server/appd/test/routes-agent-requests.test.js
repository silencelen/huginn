'use strict';
// A BACKGROUND subagent's permission request: no dialog on the pane, so it is
// listed and answered by id, never makes the session `attention`, never holds a
// send, and goes back to Claude Code undecided when nobody answers it in time.
// Throwaway daemon and a private tmux socket — same isolation as routes-permhook.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { budget: waitBudget } = require('./load-budget');

const PORT = 11000 + (process.pid % 100); // 11000-11099 (port-blocks.test.js checks for overlaps)
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-ar-${process.pid}`;
const PASS_MS = 30_000;

let tmp, token, daemon, stateDir;

const RULE = '─'.repeat(60);
const DASH = '╌'.repeat(60);
// The Bash dialog a FOREGROUND subagent's request draws on the main pane.
const DIALOG = [
  RULE, ' Bash command', DASH, ' touch probe.txt', DASH, ' Do you want to proceed?',
  ' ❯ 1. Yes', '   2. Yes, and do not ask again', '   3. No', '', ' Esc to cancel',
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
async function until(what, fn, ms = waitBudget(12_000)) {
  const by = Date.now() + ms;
  let last;
  while (Date.now() < by) {
    last = await fn();
    if (last) return last;
    await wait(200);
  }
  throw new Error(`timed out waiting for ${what}`);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ar-'));
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
      HUGINN_AGENT_REQ_PASS_MS: String(PASS_MS),
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
/** A session whose main thread is RUNNING (waiting on its agent) with no dialog — or with `screen` drawn. */
function session(screen = 'working…') {
  const name = `ar-${process.pid}-${n++}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', `sh -c 'printf "${screen}\\n"; cat'`]);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'running', sessionId: `sid-${name}`, ts: Date.now() }));
  return name;
}
function writeReq(name, fields) {
  const dir = path.join(stateDir, '.perm', name);
  fs.mkdirSync(dir, { recursive: true });
  const id = `${Date.now()}000000-${process.pid}`;
  fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify({
    v: 1, id, pid: process.pid, sessionId: `sid-${name}`, ts: Date.now(),
    agentId: 'a0123456789abcdef', agentType: 'general-purpose', permissionMode: 'auto', pane: true, ...fields,
  }));
  return { dir, id };
}
/** A hook that claims whatever answer appears. Resolves the parsed answer, or null. */
function fakeHook(dir, id, ms = waitBudget(15_000)) {
  return new Promise((resolve) => {
    const iv = setInterval(() => {
      try {
        fs.renameSync(path.join(dir, `${id}.ans`), path.join(dir, `${id}.taken`));
        clearInterval(iv);
        resolve(JSON.parse(fs.readFileSync(path.join(dir, `${id}.taken`), 'utf8')));
      } catch { /* not yet */ }
    }, 20);
    setTimeout(() => { clearInterval(iv); resolve(null); }, ms);
  });
}
const listed = async (name) => (await api(`/v1/sessions/${name}/agent-requests`)).body;
const row = async (name) => {
  const { body } = await api('/v1/sessions');
  const rows = Array.isArray(body) ? body : (body && body.sessions) || [];
  return rows.find((r) => r.name === name);
};

test('a background agent\'s request is listed by id, the session stays running, and Allow reaches the hook', async () => {
  const name = session();
  const { dir, id } = writeReq(name, { tool: 'Bash', input: { command: 'git push origin main' } });
  const got = await until('the request to be listed', async () => {
    const b = await listed(name);
    return b && b.requests && b.requests.length ? b.requests : null;
  });
  assert.equal(got[0].id, id);
  assert.equal(got[0].command, 'git push origin main');
  assert.equal(got[0].agentType, 'general-purpose');
  assert.ok(got[0].expiresAt - got[0].askedAt === PASS_MS);
  const r = await row(name);
  assert.equal(r.agentRequests, 1, 'the list carries its own mark');
  assert.equal(r.state, 'running', 'a background agent\'s request is not a question on this session');

  const hook = fakeHook(dir, id);
  const res = await api(`/v1/sessions/${name}/agent-requests/${id}`, { method: 'POST', body: JSON.stringify({ decision: 'allow' }) });
  assert.equal(res.status, 200);
  assert.deepEqual(await hook, { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } });
  fs.rmSync(path.join(dir, `${id}.json`), { force: true });   // the real hook's exit trap
  assert.deepEqual((await listed(name)).requests, []);
});

test('Deny reaches the hook as a deny with a message', async () => {
  const name = session();
  const { dir, id } = writeReq(name, { tool: 'Bash', input: { command: 'rm -rf build' } });
  await until('the request to be listed', async () => ((await listed(name)).requests || []).length > 0);
  const hook = fakeHook(dir, id);
  const res = await api(`/v1/sessions/${name}/agent-requests/${id}`, { method: 'POST', body: JSON.stringify({ decision: 'deny' }) });
  assert.equal(res.status, 200);
  const ans = await hook;
  assert.equal(ans.hookSpecificOutput.decision.behavior, 'deny');
  assert.match(ans.hookSpecificOutput.decision.message, /Huginn/);
});

test('a bad decision is 400, an unknown or gone id is 404', async () => {
  const name = session();
  const { id } = writeReq(name, { tool: 'Bash', input: { command: 'true' } });
  await until('the request to be listed', async () => ((await listed(name)).requests || []).length > 0);
  assert.equal((await api(`/v1/sessions/${name}/agent-requests/${id}`, { method: 'POST', body: JSON.stringify({ decision: 'maybe' }) })).status, 400);
  assert.equal((await api(`/v1/sessions/${name}/agent-requests/1794000000000000000-1`, { method: 'POST', body: JSON.stringify({ decision: 'allow' }) })).status, 404);
  fs.rmSync(path.join(stateDir, '.perm', name, `${id}.json`), { force: true });   // the agent moved on
  assert.equal((await api(`/v1/sessions/${name}/agent-requests/${id}`, { method: 'POST', body: JSON.stringify({ decision: 'allow' }) })).status, 404);
});

test('a request left unanswered past its time goes back to Claude Code undecided', async () => {
  const name = session();
  const { dir, id } = writeReq(name, { tool: 'Bash', input: { command: 'sleep 1' }, ts: Date.now() - PASS_MS - 1000 });
  const ans = await fakeHook(dir, id);
  assert.ok(ans, 'the hook was handed an answer');
  assert.equal(ans.hookSpecificOutput.decision, null, 'no allow, no deny: the hook prints nothing and the CLI declines');
});

test('a subagent request whose dialog IS on the pane stays the keyboard\'s: not listed, the session needs you, POST is 409', async () => {
  const name = session(DIALOG);
  const { id } = writeReq(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  await until('the session to read as needing an answer', async () => (await row(name)).state === 'attention');
  assert.deepEqual((await listed(name)).requests, []);
  const res = await api(`/v1/sessions/${name}/agent-requests/${id}`, { method: 'POST', body: JSON.stringify({ decision: 'allow' }) });
  assert.equal(res.status, 409);
});
