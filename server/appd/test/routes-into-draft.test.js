'use strict';
// `intoDraft` — the daemon telling a person that their message went into
// somebody's half-typed sentence anyway (decision 59, 2026-09-19).
//
// THE DECISION THIS FILE PINS. The draft hold is bounded: 60 s after the last
// live-view keystroke (`LIVE_KEYS_WINDOW_MS`), because a hold a person cannot
// see the end of is the same bug as a message that vanishes. The owner kept that
// ceiling — and required that the daemon STOP KEEPING IT TO ITSELF. Until 3.6.0
// the only trace was `draftOverdueLogLine` in the journal, which nobody holding a
// phone is reading:
//
//   typing: rv-desktop-1: unsent text has been in the live view for 28s and a
//   message cannot wait forever; sending into it anyway | composer: draft in progress
//
// So: `GET /v1/sessions/:name/typing` carries `intoDraft` — the LAST delivery to
// this session that landed in a draft, `{at, waitedMs, composer}` or null — and
// the `/keys` answer carries the same object when the delivery happened
// synchronously, so the person who pressed Send is told in the reply to their own
// request instead of on the next poll.
//
// ⚠ THE WINDOW IS AN ENV KNOB HERE (`HUGINN_APPD_LIVE_KEYS_WINDOW_MS`). The real
// ceiling is a minute; a suite that waited out a real minute per case is a suite
// nobody runs. Everything else is production code.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `intodraft-<pid>-*` on a PRIVATE `-L` socket and all of
// them are killed in after(). State goes to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11636 + pid%12 -> 11636-11647.
const PORT = 11636 + (process.pid % 12);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `intodraft-${process.pid}`;
const TMUX_SOCK = `huginn-intodraft-${process.pid}`;

const BOOT_MS = 400;
const BANNER_MS = 200;
/** Short enough to wait out, long enough to hold a send while a test looks. */
const WINDOW_MS = 2_500;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

let tmp, stateDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
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
const send = (name, text) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
});
const liveType = (name, text) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ text }),
});
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;
const composerOf = (name) => typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
function clearComposer(name) { sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']); }

async function startPane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${FAKE}`]);
  for (let i = 0; i < 60; i++) {
    if (composerOf(name) !== null) break;
    await wait(100);
  }
  return name;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-intodraft-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
      HUGINN_APPD_LIVE_KEYS_WINDOW_MS: String(WINDOW_MS),
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/rounds');
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

test('a message held past the ceiling goes in anyway — and /typing says so', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.5.2 everything below happens except the telling:
  // the hold ends, the message is welded onto their sentence, and `GET /typing`
  // answers `{queued:0, blockedBy:null}` with no field for it at all.
  const draft = 'half a thought the owner is still having and has not sent';
  const text = 'MARKER-NU the message that could not wait forever';
  const name = await startPane('told');

  await liveType(name, draft);
  const posted = await send(name, text);
  assert.equal(posted.body.blockedBy, 'draft', 'precondition: it is held first');
  assert.equal(posted.body.delivered, false);
  assert.equal(posted.body.intoDraft, null, 'a QUEUED message has not landed anywhere yet');

  // Nobody types again, so the window lapses and the ceiling fires. Waited out
  // to `delivering:false`, not just `queued:0`: the entry leaves the queue when
  // the paste STARTS and the notice is written from the outcome, so a poll in
  // between legitimately sees neither.
  for (let i = 0; i < 100; i++) {
    const t = await typingOf(name);
    if (t.queued === 0 && !t.delivering) break;
    await wait(100);
  }

  const st = await typingOf(name);
  assert.equal(st.queued, 0, 'the message went, which is the decision');
  assert.ok(st.intoDraft, 'and the daemon says where it went');
  assert.equal(typeof st.intoDraft.at, 'number');
  const nowSec = Math.floor(Date.now() / 1000);
  assert.ok(Math.abs(st.intoDraft.at - nowSec) < 30,
    `at is epoch SECONDS like every other timestamp outside /v1/headroom (got ${st.intoDraft.at})`);
  assert.ok(st.intoDraft.waitedMs >= WINDOW_MS - 500,
    `waitedMs is how long it was held (got ${st.intoDraft.waitedMs})`);
  assert.match(st.intoDraft.composer, /half a thought the owner/,
    'carrying what it landed on, so the notice can show it rather than assert it');
  assert.ok(st.intoDraft.composer.length <= 120, 'and no more than 120 characters of it');

  assert.match(readOr(daemonLog), /sending into it anyway/,
    'the journal line it is built from is still written');
});

test('the notice is cleared by the next ordinary delivery to that session', async () => {
  // It is news about ONE message. A later message into a box nobody is using is
  // the answer to "is it still true?", and the answer is no.
  const name = `${PFX}-told`;
  assert.ok((await typingOf(name)).intoDraft, 'precondition: the notice from the test above stands');

  clearComposer(name);
  // Out past LIVE_KEYS_QUIET_MS as well: a box that READS empty is still theirs
  // while keys may be in flight, and the previous test typed into this session.
  // That hold is correct and is not what this test is about.
  await wait(typing.LIVE_KEYS_QUIET_MS + 600);
  const { body } = await send(name, 'an ordinary message into a box nobody is using');
  assert.equal(body.delivered, true, JSON.stringify(body));
  assert.equal(body.intoDraft, null, "and the sender is not told about somebody else's draft");
  assert.equal((await typingOf(name)).intoDraft, null, 'the standing notice is gone');
});

test('a send that lands in a draft SYNCHRONOUSLY is told in its own answer', async () => {
  // The other half of decision 59: the person who pressed Send finds out from
  // the reply to their own request, not from the next poll. This is the shape
  // that reaches them when the keystroke window has already lapsed — their draft
  // is still on screen, nothing is holding, and the paste goes in on top of it.
  const draft = 'a sentence left in the box a while ago';
  const name = await startPane('immediate');

  await liveType(name, draft);
  await wait(WINDOW_MS + 600);                     // the window lapses; the draft stays
  assert.equal(composerOf(name), draft, 'precondition: their words are still there');

  const { body } = await send(name, 'the message that goes in on top of it');
  assert.equal(body.delivered, true, 'nothing is holding it any more');
  assert.ok(body.intoDraft, 'so the answer to the send itself carries the notice');
  assert.match(body.intoDraft.composer, /a sentence left in the box/);
  assert.equal(body.intoDraft.waitedMs < 2_000, true,
    'it waited for nothing — the ceiling had already passed when it arrived');
  assert.equal((await typingOf(name)).intoDraft.composer, body.intoDraft.composer,
    'and the poll says exactly the same thing');
});

test('an ordinary send into a free composer carries intoDraft: null', async () => {
  // The field is on every answer so a client can read it without branching on
  // whether the key is there; null is the ordinary case and must stay cheap.
  const name = await startPane('clean');
  const { body } = await send(name, 'nothing of anybody else was in the way');
  assert.equal(body.delivered, true);
  assert.equal(body.intoDraft, null);
  assert.equal((await typingOf(name)).intoDraft, null);
});
