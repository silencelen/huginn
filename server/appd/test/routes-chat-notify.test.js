'use strict';
// A chat made with `notify: false` is never announced.
//
// It is for a client that shows the answer itself. For such a chat the
// `chat_finished` alert only repeats what the person is already reading, and on a
// phone whose notifications are mirrored to another display it lands on top of
// the answer it repeats.
//
// ⚠ ONE LIST FEEDS BOTH ANNOUNCERS, and that is what this proves. The daemon's
// alert watcher and GET /v1/watch are built from the same chatStates(); a phone
// compares the watch digest on its own and posts "finished" from it with no push
// involved. So the chat has to be missing from the DIGEST — leaving only the push
// out would move the notification, not remove it. The digest is the observable
// here: the quiet chat is absent from it at every stage (new, running a run's
// worth of history, finished) while an ordinary chat made beside it is present.
//
// And the rest of the chat is untouched: listed, read, renamed like any other.
//
// PORT: taken from the kernel (listen on 0, read it back, close).
//
// SAFETY: never touches a real session. The tmux session lives on a private
// `-L` socket that after() kills, the daemon's data, state and Claude dir are a
// scratch directory, and every HUGINN_* variable of the calling shell is dropped.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

require('./retry-fetch');
const TMUX_SOCK = `huginn-test-chatnotify-${process.pid}`;
const NAME = `cn-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let tmp, token, daemon, base;

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
async function api(pathname, method = 'GET', body = undefined) {
  const res = await fetch(base + pathname, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch { /* no body */ }
  return { status: res.status, body: json };
}
const metaFile = (id) => path.join(tmp, 'data', 'chats', id, 'meta.json');
/** Give a chat the on-disk shape of one whose run has finished, as settleRun leaves it. */
function finishARun(id, snippet) {
  const m = JSON.parse(fs.readFileSync(metaFile(id), 'utf8'));
  m.finishedRuns = (m.finishedRuns || 0) + 1;
  m.turns = (m.turns || 0) + 1;
  m.lastSnippet = snippet;
  m.updatedAt = Math.floor(Date.now() / 1000);
  fs.writeFileSync(metaFile(id), JSON.stringify(m));
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-chatnotify-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'work']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  // One inert session, so tmux ANSWERS: the watch route says 503 when it cannot ask.
  tmux(['new-session', '-d', '-s', NAME, '-c', tmp, '-x', '80', '-y', '24', 'cat']);

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
      HUGINN_APPD_WORKDIR: path.join(tmp, 'work'),
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
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* best effort */ }
});

let quiet, ordinary;

test('notify: false is stored on the chat, and only then', async () => {
  const q = await api('/v1/chats', 'POST', { mode: 'ask', title: 'shown elsewhere', notify: false });
  assert.equal(q.status, 201);
  assert.strictEqual(q.body.notify, false);
  quiet = q.body.id;

  const o = await api('/v1/chats', 'POST', { mode: 'ask', title: 'an ordinary chat' });
  assert.equal(o.status, 201);
  assert.ok(!('notify' in o.body), 'an ordinary chat carries no notify field at all');
  ordinary = o.body.id;

  // true says what leaving it out says, and is not stored either.
  const t = await api('/v1/chats', 'POST', { mode: 'ask', title: 'said out loud', notify: true });
  assert.equal(t.status, 201);
  assert.ok(!('notify' in t.body));
  assert.ok(!('notify' in JSON.parse(fs.readFileSync(metaFile(t.body.id), 'utf8'))));
});

test('anything that is not a boolean is refused, and no chat is made', async () => {
  const before_ = (await api('/v1/chats')).body.chats.length;
  for (const bad of ['false', 'never', 0, null, {}]) {
    const r = await api('/v1/chats', 'POST', { mode: 'ask', notify: bad });
    assert.equal(r.status, 400, `notify: ${JSON.stringify(bad)} must be refused`);
    assert.match(r.body.error, /notify must be true or false/);
  }
  assert.equal((await api('/v1/chats')).body.chats.length, before_);
});

test('the quiet chat is absent from the watch digest; the ordinary one is in it', async () => {
  const w = await api('/v1/watch');
  assert.equal(w.status, 200);
  assert.ok(w.body.chats && typeof w.body.chats === 'object');
  assert.ok(ordinary in w.body.chats, 'the ordinary chat is what a watcher compares');
  assert.ok(!(quiet in w.body.chats), 'the quiet chat must not be there to compare');
});

test('a finished run of the quiet chat changes nothing a watcher can see', async () => {
  const mark = (await api('/v1/watch')).body;

  finishARun(quiet, 'the answer, already on its own display');
  const afterQuiet = (await api('/v1/watch')).body;
  assert.ok(!(quiet in afterQuiet.chats));
  assert.equal(afterQuiet.hash, mark.hash, 'a parked phone is not woken by it');

  // The control: the same finish on the ordinary chat IS news, in the same digest.
  finishARun(ordinary, 'an answer nobody was watching arrive');
  const afterOrdinary = (await api('/v1/watch')).body;
  assert.notEqual(afterOrdinary.hash, mark.hash);
  assert.equal(afterOrdinary.chats[ordinary].finishedRuns, 1);
  assert.equal(afterOrdinary.chats[ordinary].snippet, 'an answer nobody was watching arrive');
  assert.ok(!(quiet in afterOrdinary.chats));
});

test('it is still a chat: listed, read, renamed', async () => {
  const list = (await api('/v1/chats')).body.chats;
  const row = list.find((c) => c.id === quiet);
  assert.ok(row, 'GET /v1/chats lists it');
  assert.strictEqual(row.notify, false);

  const one = await api(`/v1/chats/${quiet}`);
  assert.equal(one.status, 200);
  assert.strictEqual((one.body.chat || one.body).notify, false);

  const renamed = await api(`/v1/chats/${quiet}`, 'PATCH', { title: 'renamed' });
  assert.equal(renamed.status, 200);
  assert.equal(JSON.parse(fs.readFileSync(metaFile(quiet), 'utf8')).title, 'renamed');
});

test('PATCH cannot turn the setting on or off', async () => {
  await api(`/v1/chats/${quiet}`, 'PATCH', { notify: true });
  assert.strictEqual(JSON.parse(fs.readFileSync(metaFile(quiet), 'utf8')).notify, false);
  assert.ok(!(quiet in (await api('/v1/watch')).body.chats));

  await api(`/v1/chats/${ordinary}`, 'PATCH', { notify: false });
  assert.ok(!('notify' in JSON.parse(fs.readFileSync(metaFile(ordinary), 'utf8'))));
  assert.ok(ordinary in (await api('/v1/watch')).body.chats);
});
