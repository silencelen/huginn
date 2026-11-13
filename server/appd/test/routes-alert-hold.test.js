'use strict';
// POST and DELETE /v1/alerts/hold: a display that is showing Huginn itself asks
// for announcements to wait.
//
// Some displays mirror a phone's notifications, drawing each one on top of what
// they were showing. When that is Huginn's own client, every notification covers
// the page being read. While it is awake that client holds announcements, a few
// seconds at a time, and they go out when it stops.
//
// ⚠ TWO ANNOUNCERS, AND BOTH ARE HELD. The daemon's alert watcher is one. The
// other is the phone, which compares GET /v1/watch on its own and posts "needs
// you" and a chat's finish from it with no push involved. So besides queueing its
// own alerts the daemon answers a phone behind the hold with the digest from when
// the hold began: nothing changed, nothing to say. That is checked here in all
// three shapes of the watch route (a plain look, a parked poll, the stream), for
// a phone registered for push, against a client with no push token and a caller
// that names no install, who must both go on seeing every change as it happens.
//
// The watcher's half is checked end to end with a stand-in relay script that
// writes down what it is given: an alert decided during a hold is in `held`, is
// logged as held, reaches the relay only after the hold ends.
//
// PORT: taken from the kernel (listen on 0, read it back, close).
//
// SAFETY: never touches a real session. The tmux session lives on a private
// `-L` socket that after() kills, the daemon's data, state and Claude dir are a
// scratch directory, every HUGINN_* variable of the calling shell is dropped, the
// push key points at nothing and the relay is the stand-in below.

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
const TMUX_SOCK = `huginn-test-alerthold-${process.pid}`;
const NAME = `ah-${process.pid}`;
// A phone registered for push, a client that is not, as their install ids.
const PHONE = `phone-${process.pid}`;
const DESK = `desk-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** The relay, standing in: it keeps the words it is handed as "$2" and reaches nobody. */
const RELAY = `#!/usr/bin/env bash
printf '%s\\n' "$2" >> "$RELAY_OUT"
exit 0
`;

let tmp, token, daemon, base, logFile, relayOut, chat;

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
async function api(pathname, method = 'GET', body = undefined, install = '') {
  const res = await fetch(base + pathname, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      ...(install ? { 'x-huginn-client': install } : {}),
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch { /* no body */ }
  return { status: res.status, body: json };
}
async function until(what, fn, ms = 30_000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await wait(200);
  }
}
const hold = (seconds, by) => api('/v1/alerts/hold', 'POST', by === undefined ? { seconds } : { seconds, by });
const unhold = () => api('/v1/alerts/hold', 'DELETE');
const watchAs = async (install, query = '') => (await api(`/v1/watch${query}`, 'GET', undefined, install)).body;
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
const daemonLog = () => fs.readFileSync(logFile, 'utf8');
const relayed = () => { try { return fs.readFileSync(relayOut, 'utf8'); } catch { return ''; } };
const alertState = () => { try { return JSON.parse(fs.readFileSync(path.join(tmp, 'data', 'alerts.json'), 'utf8')); } catch { return {}; } };

/** The `event: state` frames of a watch stream, as they arrive. */
async function openStream(install) {
  const ac = new AbortController();
  const res = await fetch(`${base}/v1/watch?stream=1`, {
    headers: { authorization: `Bearer ${token}`, 'x-huginn-client': install },
    signal: ac.signal,
  });
  const frames = [];
  const pump = (async () => {
    const dec = new TextDecoder();
    let buf = '';
    try {
      for await (const chunk of res.body) {
        buf += dec.decode(chunk, { stream: true });
        for (let i = buf.indexOf('\n\n'); i >= 0; i = buf.indexOf('\n\n')) {
          const frame = buf.slice(0, i);
          buf = buf.slice(i + 2);
          const data = /^data: (.*)$/m.exec(frame);
          if (/^event: state$/m.test(frame) && data) frames.push(JSON.parse(data[1]));
        }
      }
    } catch { /* closed below */ }
  })();
  return { frames, close: async () => { ac.abort(); await pump; } };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-alerthold-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'work']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const relay = path.join(tmp, 'relay.sh');
  fs.writeFileSync(relay, RELAY, { mode: 0o755 });
  relayOut = path.join(tmp, 'relayed.txt');
  logFile = path.join(tmp, 'daemon.log');
  // One inert session, so tmux ANSWERS: the watch route says 503 when it cannot ask.
  tmux(['new-session', '-d', '-s', NAME, '-c', tmp, '-x', '80', '-y', '24', 'cat']);

  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_|TMUX)/.test(k)));
  const out = fs.openSync(logFile, 'a');
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
      HUGINN_APPD_TELEGRAM_SCRIPT: relay,
      HUGINN_APPD_MEMPALACE_HOST: '',
      HUGINN_FCM_KEY: path.join(tmp, 'no-fcm-key.json'),
      HUGINN_CLAUDE_BIN: path.join(tmp, 'no-claude-binary'),
      RELAY_OUT: relayOut,
    },
    stdio: ['ignore', out, out],
  });
  fs.closeSync(out);
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not listening yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) {
    throw new Error(`port ${port} is held by another huginn-appd that refuses this run's token`);
  }

  // The daemon's own watcher is switched off until the test that is about it,
  // so nothing it might say gets in between. Holding does not depend on it: the
  // phone's own comparison of the digest is held either way.
  assert.equal((await api('/v1/alerts', 'POST', { enabled: false })).status, 200);
  const reg = await api('/v1/push/register', 'POST', { installId: PHONE, token: `tok-${process.pid}`, model: 'test phone' });
  assert.equal(reg.status, 200);
  const made = await api('/v1/chats', 'POST', { mode: 'ask', title: 'the chat that keeps finishing' });
  assert.equal(made.status, 201);
  chat = made.body.id;
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

test('a request that does not say how long is refused, and holds nothing', async () => {
  for (const bad of [0, 0.5, 121, -1, '30', true, null, {}]) {
    const r = await hold(bad);
    assert.equal(r.status, 400, `seconds: ${JSON.stringify(bad)} must be refused`);
    assert.equal(r.body.error, 'seconds must be a number from 1 to 120');
  }
  const none = await api('/v1/alerts/hold', 'POST', {});
  assert.equal(none.status, 400);
  for (const bad of [7, null, {}, ['a display']]) {
    const r = await hold(30, bad);
    assert.equal(r.status, 400, `by: ${JSON.stringify(bad)} must be refused`);
    assert.equal(r.body.error, 'by must be a string');
  }
  const a = await api('/v1/alerts');
  assert.equal(a.status, 200);
  assert.strictEqual(a.body.hold, null);
  assert.strictEqual(a.body.held, 0);
});

test('a hold begins, is reported, is extended, and is ended by DELETE', async () => {
  const t0 = Math.floor(Date.now() / 1000);
  const first = await hold(30, '  test display  ');
  assert.equal(first.status, 200);
  assert.equal(first.body.hold.by, 'test display');
  assert.ok(first.body.hold.since >= t0 - 1 && first.body.hold.since <= t0 + 5, 'it began now');
  assert.ok(Math.abs(first.body.hold.until - first.body.hold.since - 30) <= 1, 'and lasts what was asked');
  assert.deepEqual(first.body.held, []);
  assert.ok(!('released' in first.body));

  const seen = await api('/v1/alerts');
  assert.deepEqual(seen.body.hold, first.body.hold);
  assert.strictEqual(seen.body.held, 0);

  const longer = await hold(90);
  assert.equal(longer.status, 200);
  assert.equal(longer.body.hold.since, first.body.hold.since, 'an extension keeps the start');
  assert.ok(longer.body.hold.until >= first.body.hold.until + 55, 'and moves the end');
  assert.equal(longer.body.hold.by, 'a display', 'named by whoever asked last, or not at all');

  const ended = await unhold();
  assert.equal(ended.status, 200);
  assert.deepEqual(ended.body, { hold: null, held: [] });
  assert.strictEqual((await api('/v1/alerts')).body.hold, null);
  // Ending what is not there is not an error: a display going to sleep does not know.
  assert.deepEqual((await unhold()).body, { hold: null, held: [] });
});

test('a hold nobody renews runs out by itself', async () => {
  const short = await hold(2, 'test display');
  assert.equal(short.status, 200);
  assert.ok(short.body.hold, 'it was live when it answered');
  await wait(2300);
  assert.strictEqual((await api('/v1/alerts')).body.hold, null);
});

test('the digest stands still for a phone behind the hold, and for nobody else', async () => {
  const before_ = await watchAs(PHONE);
  assert.equal(before_.chats[chat].finishedRuns, 0);
  assert.equal((await hold(60, 'test display')).status, 200);

  finishARun(chat, 'said during the hold');

  const phone = await watchAs(PHONE);
  assert.equal(phone.hash, before_.hash, 'the phone is told nothing changed');
  assert.equal(phone.chats[chat].finishedRuns, 0);
  assert.notEqual(phone.chats[chat].snippet, 'said during the hold');
  assert.strictEqual((await watchAs(PHONE, `?hash=${before_.hash}`)).changed, false);
  assert.ok(Math.abs(phone.serverTime - Date.now() / 1000) < 10, 'the clock in the answer is not held');

  const desk = await watchAs(DESK);
  assert.notEqual(desk.hash, before_.hash, 'a client with no push token is behind no display');
  assert.equal(desk.chats[chat].finishedRuns, 1);
  assert.equal(desk.chats[chat].snippet, 'said during the hold');
  const anon = await watchAs('');
  assert.equal(anon.hash, desk.hash, 'nor is a caller that names no install');

  assert.equal((await unhold()).status, 200);
  const after_ = await watchAs(PHONE);
  assert.equal(after_.hash, desk.hash, 'the hold over, the phone sees what there is');
  assert.equal(after_.chats[chat].finishedRuns, 1);

  // The same when the hold simply runs out. The look in the middle only counts
  // if it came back while the hold was certainly live: a host busy enough to
  // take seconds over one request is not what this is about.
  const began = Date.now();
  assert.equal((await hold(6, 'test display')).status, 200);
  finishARun(chat, 'said during a short hold');
  const during = await watchAs(PHONE);
  if (Date.now() - began < 5000) assert.equal(during.chats[chat].finishedRuns, 1);
  await until('the short hold to run out', async () => (await watchAs(PHONE)).chats[chat].finishedRuns === 2, 30_000);
});

test('a parked phone stays parked until the hold ends', async () => {
  const known = (await watchAs(PHONE)).hash;
  assert.equal((await hold(60, 'test display')).status, 200);
  let answer = null;
  const poll = watchAs(PHONE, `?hash=${known}&wait=40000`).then((b) => { answer = b; return b; });
  finishARun(chat, 'said while it was parked');
  // Longer than one of the poll's own three-second looks.
  await wait(4500);
  assert.strictEqual(answer, null, 'woken by a change it was not to be told about');
  assert.equal((await watchAs(DESK)).chats[chat].finishedRuns, 3, 'which did happen');

  assert.equal((await unhold()).status, 200);
  const woken = await Promise.race([poll, wait(15_000).then(() => null)]);
  assert.ok(woken, 'the end of the hold wakes it');
  assert.strictEqual(woken.changed, true);
  assert.equal(woken.chats[chat].finishedRuns, 3);
});

test('a stream to a phone behind the hold sends no frame until it ends', async () => {
  const s = await openStream(PHONE);
  try {
    await until('the stream\'s first frame', () => s.frames.length >= 1);
    assert.equal(s.frames[0].chats[chat].finishedRuns, 3);
    assert.equal((await hold(60, 'test display')).status, 200);
    finishARun(chat, 'said while it streamed');
    await wait(4500);
    assert.equal(s.frames.length, 1, 'a frame went out during the hold');
    assert.equal((await unhold()).status, 200);
    await until('the frame after the hold', () => s.frames.length >= 2, 15_000);
    assert.equal(s.frames[1].chats[chat].finishedRuns, 4);
  } finally {
    await s.close();
  }
});

test('what the watcher decides during a hold waits, is logged as held, and goes out when it ends', async () => {
  // Every alert to the relay, whoever else looks reachable; and a fresh baseline.
  assert.equal((await api('/v1/alerts', 'POST', { enabled: true, mode: 'always' })).status, 200);
  // A hold begun and ended is a tick now rather than at the timer's next beat:
  // the watcher has looked once, so the finish below is a change to it.
  await hold(5, 'test display');
  await unhold();
  await until('the watcher\'s first look', () => !!alertState().prev);
  assert.equal(relayed(), '', 'nothing so far was news');

  assert.equal((await hold(60, 'test display')).status, 200);
  finishARun(chat, 'the answer that had to wait');
  // The watcher looks every ten seconds. Asking again keeps the hold live and
  // says what waits, as a display's own renewals do.
  const waiting = await until('the finish to be held', async () => {
    const r = await hold(60, 'test display');
    return r.body.held.length ? r.body.held : null;
  }, 60_000);
  assert.equal(waiting.length, 1);
  assert.equal(waiting[0].kind, 'chat_finished');
  assert.equal(waiting[0].subject, chat);
  assert.equal(waiting[0].title, 'the chat that keeps finishing');
  assert.equal(waiting[0].text, 'the answer that had to wait');
  assert.equal(typeof waiting[0].key, 'string');
  assert.ok(Math.abs(waiting[0].at - Date.now() / 1000) < 60);
  assert.deepEqual(Object.keys(waiting[0]).sort(), ['at', 'key', 'kind', 'subject', 'text', 'title']);
  assert.strictEqual((await api('/v1/alerts')).body.held, 1);
  assert.ok(daemonLog().includes(`alerts: holding chat_finished for ${chat} (test display)`), 'logged as held');
  assert.ok(!daemonLog().includes(`alerts: sent chat_finished for ${chat}`), 'and not as sent');
  assert.equal(relayed(), '', 'the relay heard nothing');

  const ended = await unhold();
  assert.equal(ended.body.held.length, 1, 'DELETE answers with what was waiting');
  await until('the relay to carry it', () => relayed().includes('the answer that had to wait'));
  assert.ok(daemonLog().includes(`alerts: sent chat_finished for ${chat}`));
  await until('the queue to empty', async () => (await api('/v1/alerts')).body.held === 0);
  await wait(300);
  assert.equal(relayed().split('the answer that had to wait').length - 1, 1, 'said once');
});

test('switching alerts off empties the queue', async () => {
  // Under a hold, so no tick sends what is put there by hand.
  assert.equal((await hold(60, 'test display')).status, 200);
  const file = path.join(tmp, 'data', 'alerts.json');
  await until('a queue entry to stay put', async () => {
    const st = JSON.parse(fs.readFileSync(file, 'utf8'));
    st.held = [{ key: 'chat:left-behind:1', kind: 'chat_finished', subject: 'left-behind', title: 't', text: 'x', heldAt: Date.now() }];
    fs.writeFileSync(file, JSON.stringify(st));
    return (await api('/v1/alerts')).body.held === 1;
  });
  assert.equal((await api('/v1/alerts', 'POST', { enabled: false })).status, 200);
  assert.strictEqual((await api('/v1/alerts')).body.held, 0);
  assert.ok(!('held' in JSON.parse(fs.readFileSync(file, 'utf8'))));
  await unhold();
  assert.equal((await api('/v1/alerts', 'POST', { enabled: true })).status, 200);
});
