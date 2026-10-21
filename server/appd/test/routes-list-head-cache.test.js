'use strict';
// The two lists every client polls — GET /v1/sessions?preview=1 and GET
// /v1/chats — take Claude Code's own title off each row's transcript. They used
// to read and parse the transcript's tail for every row on every poll, changed
// or not. Now the title is cached on the file's size and mtime.
//
// Proven from outside the daemon: once a list has served a title, the file is
// rewritten with a DIFFERENT title in exactly the same number of bytes and given
// back its mtime. A daemon that re-reads serves the new title; one that trusts
// the unchanged size and mtime serves the old one. Then a record is appended,
// and the new title must arrive on the next poll.
//
// PORT: taken from the kernel (listen on 0, read it back, close).
//
// SAFETY: never touches a real session. The tmux session lives on a private
// `-L` socket that after() kills, the daemon's data, state and Claude dir are a
// scratch directory, and every HUGINN_* variable of the calling shell is dropped.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const NAME = `hc-${process.pid}`;
const SESSION_ID = '0d6c2c4e-6f53-4a8e-9a4b-1d0c8d7f2a01';
const CHAT_SESSION_ID = '5b0e7f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
// A fixed mtime, set after every write: a restored mtime must compare EQUAL,
// and a float of seconds round-trips through utimes exactly only when it is whole.
const MTIME = new Date('2026-10-01T00:00:00.000Z');

let tmp, token, daemon, base, sessionFile, chatFile;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}
const tmux = (args) => execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });
async function api(pathname) {
  const res = await fetch(base + pathname, { headers: { authorization: `Bearer ${token}` } });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** A transcript whose newest ai-title is `title`, padded so two titles of one length make one file size. */
function transcript(title) {
  const t = '2026-10-01T09:00:00.000Z';
  return [
    { parentUuid: null, isSidechain: false, type: 'user', message: { role: 'user', content: 'hello' }, timestamp: t, cwd: '/work', sessionId: 's' },
    { parentUuid: null, isSidechain: false, message: { role: 'assistant', content: [{ type: 'text', text: 'hi there' }] }, type: 'assistant', timestamp: t, cwd: '/work', sessionId: 's' },
    { type: 'ai-title', aiTitle: title, sessionId: 's' },
    { type: 'permission-mode', permissionMode: 'default', sessionId: 's' },
  ].map((r) => JSON.stringify(r)).join('\n') + '\n';
}
function writeKeepingStat(file, text) {
  fs.writeFileSync(file, text);
  fs.utimesSync(file, MTIME, MTIME);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-hc-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'work']) fs.mkdirSync(path.join(tmp, d));
  const workdir = path.join(tmp, 'work');
  const projects = path.join(tmp, 'claude', 'projects', workdir.replace(/\//g, '-'));
  fs.mkdirSync(projects, { recursive: true });
  sessionFile = path.join(projects, `${SESSION_ID}.jsonl`);
  chatFile = path.join(projects, `${CHAT_SESSION_ID}.jsonl`);
  writeKeepingStat(sessionFile, transcript('Alpha title'));
  writeKeepingStat(chatFile, transcript('Alpha chat'));

  // A chat whose Claude session wrote chatFile.
  const chatId = crypto.randomUUID();
  fs.mkdirSync(path.join(tmp, 'data', 'chats', chatId), { recursive: true });
  fs.writeFileSync(path.join(tmp, 'data', 'chats', chatId, 'meta.json'), JSON.stringify({
    id: chatId, title: 'stored title', mode: 'ask', host: 'local', model: null, effort: null,
    createdAt: 1, updatedAt: 1, claudeSessionId: CHAT_SESSION_ID, lastSnippet: null, turns: 1,
  }));

  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  tmux(['new-session', '-d', '-s', NAME, '-c', tmp, '-x', '80', '-y', '24', 'cat']);
  // Stamped after the session exists, or it reads as a previous incarnation's.
  await wait(1100);
  fs.writeFileSync(path.join(tmp, 'state', NAME), JSON.stringify({
    state: 'idle', sessionId: SESSION_ID, transcript: sessionFile, cwd: workdir, ts: Date.now(),
  }));

  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: workdir,
      HUGINN_FCM_KEY: path.join(tmp, 'no-fcm-key.json'),
      HUGINN_CLAUDE_BIN: path.join(tmp, 'no-claude-binary'),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not listening yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) {
    throw new Error(`port ${port} is held by another huginn-appd that refuses this run's token`);
  }
});

after(async () => {
  try { tmux(['kill-server']); } catch { /* none left */ }
  if (daemon && daemon.exitCode === null) {
    await new Promise((resolve) => {
      const hard = setTimeout(() => { try { daemon.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
      daemon.once('exit', () => { clearTimeout(hard); resolve(); });
      daemon.kill('SIGTERM');
    });
  }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

async function sessionTitle() {
  const { status, body } = await api('/v1/sessions?preview=1');
  assert.equal(status, 200);
  const row = body.sessions.find((s) => s.name === NAME);
  assert.ok(row, `${NAME} is not on the list`);
  return row.title;
}
async function chatTitle() {
  const { status, body } = await api('/v1/chats');
  assert.equal(status, 200);
  assert.equal(body.chats.length, 1);
  return body.chats[0].title;
}

test('the session list does not re-read a transcript whose size and mtime have not moved', async () => {
  assert.equal(await sessionTitle(), 'Alpha title');
  // Same bytes count, same mtime, different words.
  writeKeepingStat(sessionFile, transcript('Bravo title'));
  assert.equal(fs.statSync(sessionFile).size, Buffer.byteLength(transcript('Alpha title')));
  assert.equal(await sessionTitle(), 'Alpha title', 'the transcript was read again');
  // A record lands: the next poll has it.
  fs.appendFileSync(sessionFile, `${JSON.stringify({ type: 'ai-title', aiTitle: 'Charlie title', sessionId: 's' })}\n`);
  assert.equal(await sessionTitle(), 'Charlie title');
});

test('the chat list does not re-read one either', async () => {
  assert.equal(await chatTitle(), 'Alpha chat');
  writeKeepingStat(chatFile, transcript('Bravo chat'));
  assert.equal(await chatTitle(), 'Alpha chat', 'the transcript was read again');
  fs.appendFileSync(chatFile, `${JSON.stringify({ type: 'ai-title', aiTitle: 'Charlie chat', sessionId: 's' })}\n`);
  assert.equal(await chatTitle(), 'Charlie chat');
});
