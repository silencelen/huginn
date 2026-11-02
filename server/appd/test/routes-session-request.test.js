'use strict';
// start_session end to end against a throwaway daemon: Claude (its MCP server,
// mcp/huginn-mcp.js) parks a request, the asking session's card shows it as a
// `session` question, the owner approves it — and the daemon makes the session
// in the directory, queues the opening prompt, and only then tells the tool.
// A decline tells the tool started:false and makes nothing. A chat asks the same
// way through its own routes.
//
// SAFETY: a private tmux socket, a scratch data/state dir, and `claude` shadowed
// on the daemon's PATH by the composer stand-in (fixtures/fake-claude.js), so
// nothing real is launched and the prompt's arrival can be read off a file.
//
// PORT ALLOCATION (table in routes-lifecycle.test.js):
//   routes-session-request  11950 + pid%50  -> 11950-11999   (this file)

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const sr = require('../lib/sessionreq');

const PORT = 11950 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const PFX = `sreq${process.pid}`;

let tmp, stateDir, token, daemon, projectDir;
function sh(cmd, args) { if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args]; return execFileSync(cmd, args, { encoding: 'utf8' }); }
async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, { ...init, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) } });
  let body = null; try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
async function until(what, fn, ms = 20_000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const got = await fn();
    if (got) return got;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await wait(100);
  }
}
function sessionAlive(name) { try { sh('tmux', ['has-session', '-t', `=${name}`]); return true; } catch { return false; } }
/** A plain pane standing for the ASKING session, with the state file the routes expect. */
function askerSession(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', 'cat >/dev/null']);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'running', sessionId: null, transcript: null, cwd: tmp, ts: Math.floor(Date.now() / 1000) }));
  return name;
}
/** The MCP server as the asker's Claude Code would run it: told which session (or chat) it is in. */
function mcp(env) {
  const p = spawn(process.execPath, [path.join(__dirname, '..', 'mcp', 'huginn-mcp.js')], {
    env: { ...process.env, TMUX: '', TMUX_PANE: '', HUGINN_CHAT_ID: '', HUGINN_SESSION_NAME: '', HUGINN_APPD_STATE_DIR: stateDir, HUGINN_START_POLL_MS: '50', ...env },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let buf = ''; const waiting = new Map();
  p.stdout.on('data', (c) => {
    buf += c;
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const m = JSON.parse(buf.slice(0, nl)); buf = buf.slice(nl + 1);
      const w = waiting.get(m.id); if (w) { waiting.delete(m.id); w(m); }
    }
  });
  let id = 0;
  return {
    start(args) {
      const mid = ++id;
      const done = new Promise((resolve) => { waiting.set(mid, resolve); });
      p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: mid, method: 'tools/call', params: { name: 'start_session', arguments: args } }) + '\n');
      return done.then((m) => JSON.parse(m.result.content[0].text));
    },
    close() { p.stdin.end(); },
  };
}
const sentTo = (name) => { try { return fs.readFileSync(path.join(tmp, 'out', `${name}.txt`), 'utf8'); } catch { return ''; } };

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-sreq-'));
  for (const d of ['state', 'data', 'bin', 'project', 'out']) fs.mkdirSync(path.join(tmp, d));
  stateDir = path.join(tmp, 'state');
  projectDir = fs.realpathSync(path.join(tmp, 'project'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  // The create route runs `claude; exec "$SHELL" -l` in the new pane. This
  // `claude` is the composer stand-in, writing what it is sent to out/<session>.txt
  // (it reads its own tmux session name — $TMUX is set in the pane).
  fs.writeFileSync(path.join(tmp, 'bin', 'claude'), [
    '#!/bin/sh',
    'n=$(tmux display-message -p "#S" 2>/dev/null || echo unknown)',
    `HG_FAKE_CLAUDE_OUT="${path.join(tmp, 'out')}/$n.txt" HG_FAKE_CLAUDE_BOOT_MS=300 HG_FAKE_CLAUDE_BANNER_MS=200 \\`,
    `  exec "${process.execPath}" "${path.join(__dirname, 'fixtures', 'fake-claude.js')}"`,
    '',
  ].join('\n'), { mode: 0o755 });
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env, PATH: `${path.join(tmp, 'bin')}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT), HUGINN_APPD_BIND: '127.0.0.1', HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'), HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK, HUGINN_APPD_WORKDIR: tmp,
      HUGINN_FCM_KEY: path.join(tmp, 'no-fcm-key.json'),
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) { try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ } await wait(100); }
  if ((await api('/v1/rounds')).status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd that refuses this run's token`);
  }
});

after(async () => {
  if (daemon && daemon.exitCode === null) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      daemon.once('exit', () => { clearTimeout(hard); resolve(); });
      daemon.kill('SIGTERM');
    });
  }
  try { sh('tmux', ['kill-server']); } catch { /* none */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test("a session's request shows on its card, is approved, and the new session opens in the directory with the prompt queued", async () => {
  const asker = askerSession('asker');
  const s = mcp({ HUGINN_SESSION_NAME: asker });
  try {
    const newName = `${PFX}-new`;
    const prompt = 'R2ST: read the deploy notes in this directory and summarise the week';
    const result = s.start({ directory: projectDir, prompt, name: newName });
    const q = await until('the question on the card', async () => {
      const r = await api(`/v1/sessions/${asker}/questions`);
      return (r.body.questions || []).find((x) => x.kind === 'session');
    });
    assert.equal(q.toolName, 'start_session');
    assert.equal(q.mcpServer, 'huginn');
    assert.deepEqual(q.input, { directory: projectDir, prompt, name: newName.toLowerCase() });
    assert.deepEqual(q.requester, { sessionName: asker, chatId: null });
    assert.ok(q.expiresAt > Date.now() && q.expiresAt <= Date.now() + sr.EXPIRE_MS);
    assert.equal(q.bound, true);
    assert.equal('hookId' in q, false);
    const screen = await api(`/v1/sessions/${asker}/screen`);
    assert.ok((screen.body.questions || []).some((x) => x.id === q.id), 'the same list rides /screen');
    assert.equal(sessionAlive(newName), false, 'nothing exists before the owner answers');

    const wrong = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: 'nope' }) });
    assert.equal(wrong.status, 409); assert.equal(wrong.body.reason, 'changed');
    const gone = await api(`/v1/sessions/${asker}/questions/1-1`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: q.fingerprint }) });
    assert.equal(gone.status, 409); assert.equal(gone.body.reason, 'gone');
    const noFp = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow' }) });
    assert.equal(noFp.status, 400);
    assert.equal(sessionAlive(newName), false, 'a refused answer starts nothing');

    const ok = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: q.fingerprint }) });
    assert.equal(ok.status, 200, JSON.stringify(ok.body));
    assert.equal(ok.body.started, true);
    assert.equal(ok.body.via, 'daemon');
    assert.equal(ok.body.name, newName.toLowerCase());
    assert.equal(ok.body.cwd, projectDir);
    assert.ok(sessionAlive(newName), 'the session exists');
    assert.equal(sh('tmux', ['display-message', '-p', '-t', `=${newName}:`, '#{pane_current_path}']).trim(), projectDir);
    // The tool learns exactly what the daemon did.
    assert.deepEqual(await result, { started: true, name: newName.toLowerCase(), cwd: projectDir });
    // The prompt reaches the new session through the queue once its composer paints.
    const got = await until('the prompt in the new session', () => sentTo(newName.toLowerCase()).includes(prompt) && sentTo(newName.toLowerCase()));
    assert.equal(got.trim().split('\n').filter((l) => l.includes('R2ST')).length, 1, 'the prompt arrived once');
    const after_ = await api(`/v1/sessions/${asker}/questions`);
    assert.equal((after_.body.questions || []).some((x) => x.kind === 'session'), false, 'answered: off the card');
  } finally { s.close(); }
});

test('a declined request starts nothing, and the tool is told declined with the words', async () => {
  const asker = askerSession('asker2');
  const s = mcp({ HUGINN_SESSION_NAME: asker });
  try {
    const result = s.start({ directory: projectDir, prompt: 'R2ST decline me', name: `${PFX}-never` });
    const q = await until('the question', async () => ((await api(`/v1/sessions/${asker}/questions`)).body.questions || []).find((x) => x.kind === 'session'));
    const r = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'deny', message: 'not now', fingerprint: q.fingerprint }) });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    assert.deepEqual(r.body, { ok: true, via: 'daemon', started: false });
    assert.deepEqual(await result, { started: false, reason: 'declined: not now' });
    assert.equal(sessionAlive(`${PFX}-never`), false);
    assert.equal(sentTo(`${PFX}-never`), '', 'the prompt went nowhere');
    assert.equal(((await api(`/v1/sessions/${asker}/questions`)).body.questions || []).length, 0);
  } finally { s.close(); }
});

test('a name already taken answers the card with the create route\'s 409 and leaves the request to retry', async () => {
  const asker = askerSession('asker3');
  const taken = askerSession('taken');
  const s = mcp({ HUGINN_SESSION_NAME: asker });
  try {
    const result = s.start({ directory: projectDir, prompt: 'R2ST clash', name: taken });
    const q = await until('the question', async () => ((await api(`/v1/sessions/${asker}/questions`)).body.questions || []).find((x) => x.kind === 'session'));
    const clash = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: q.fingerprint }) });
    assert.equal(clash.status, 409, JSON.stringify(clash.body));
    assert.match(clash.body.error, /already exists/);
    assert.equal(clash.body.question.id, q.id, 'still waiting');
    const again = await api(`/v1/sessions/${asker}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', name: `${PFX}-renamed`, fingerprint: q.fingerprint }) });
    assert.equal(again.status, 200, JSON.stringify(again.body));
    assert.equal(again.body.name, `${PFX}-renamed`);
    assert.deepEqual(await result, { started: true, name: `${PFX}-renamed`, cwd: projectDir });
    assert.ok(sessionAlive(`${PFX}-renamed`));
  } finally { s.close(); }
});

test("a chat's request shows on the chat and is approved there; an unnamed request is named after the directory", async () => {
  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  const chatId = made.body.id;
  const s = mcp({ HUGINN_CHAT_ID: chatId });
  try {
    const result = s.start({ directory: projectDir, prompt: 'R2ST from a chat' });
    const q = await until('the question on the chat', async () => ((await api(`/v1/chats/${chatId}/questions`)).body.questions || [])[0]);
    assert.equal(q.kind, 'session');
    assert.deepEqual(q.requester, { sessionName: null, chatId });
    assert.equal(q.input.name, null);
    const detail = await api(`/v1/chats/${chatId}`);
    assert.ok((detail.body.questions || []).some((x) => x.id === q.id), 'the chat detail carries the list');
    const r = await api(`/v1/chats/${chatId}/questions/${q.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: q.fingerprint }) });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    assert.equal(r.body.name, 'project', 'named after the directory');
    assert.deepEqual(await result, { started: true, name: 'project', cwd: projectDir });
    assert.ok(sessionAlive('project'));
    await until('the prompt', () => sentTo('project').includes('R2ST from a chat'));
    assert.deepEqual((await api(`/v1/chats/${chatId}/questions`)).body.questions, []);
    // The next unnamed request for the same directory gets the next free name.
    const s2 = mcp({ HUGINN_CHAT_ID: chatId });
    try {
      const r2 = s2.start({ directory: projectDir, prompt: 'R2ST again' });
      const q2 = await until('the second question', async () => ((await api(`/v1/chats/${chatId}/questions`)).body.questions || [])[0]);
      const ok2 = await api(`/v1/chats/${chatId}/questions/${q2.id}`, { method: 'POST', body: JSON.stringify({ behavior: 'allow', fingerprint: q2.fingerprint }) });
      assert.equal(ok2.status, 200, JSON.stringify(ok2.body));
      assert.equal(ok2.body.name, 'project-2');
      assert.equal((await r2).name, 'project-2');
    } finally { s2.close(); }
  } finally { s.close(); }
});
