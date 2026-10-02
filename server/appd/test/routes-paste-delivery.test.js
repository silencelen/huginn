'use strict';
// Message delivery into a pane that honours BRACKETED PASTE the way the real
// TUI does — the 2026-10-02 breaker round (appd 3.9.0, ten confirmed defects).
//
// Every case here was reproduced end to end against the released daemon with a
// bracketed-paste stand-in (fixtures/fake-claude-bp.js) and is the fail-first
// for its fix:
//
//   0  a short message with a `---` / `======` line was never submitted, and the
//      POST answered delivered:true
//   1  a message line starting with `❯` was never submitted, and the retry was
//      swallowed as a duplicate
//   2  an echoed numbered list held every later send as 'modal', with no ceiling
//   3  a footer phrase ("esc to cancel") in the conversation did the same
//   4  two identical POSTs 15-40 ms apart were both submitted
//   5  a short message inside the person's draft made the draft "ours"
//   6  the person's own `[Pasted text …]` marker made their draft "ours"
//   7  a short draft that was a substring of the last delivered message, too
//   8  a TUI seconds behind its input got the message twice, run together
//   9  ESC[201~ inside the text ended the paste early and submitted half of it
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `bp-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11675 + pid%25 -> 11675-11699.
const PORT = 11675 + (process.pid % 25);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `bp-${process.pid}`;
const TMUX_SOCK = `huginn-bp-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude-bp.js');
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

/** Every message the stand-in submitted, decoded. */
function submitted(name) {
  return readOr(outFor(name)).split('\n').filter(Boolean).map((l) => JSON.parse(l));
}
function composerOf(name) {
  return typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
}

async function startPane(suffix, { typed = '', lag = 0, rows = 30 } = {}) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', String(rows),
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_TYPED='${typed}' HG_FAKE_LAG_MS=${lag} `
    + `${process.execPath} ${FAKE}`]);
  for (let i = 0; i < 60 && composerOf(name) === null; i++) await wait(100);
  return name;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-bp-'));
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
      // Panes are made outside the daemon and are up before any send.
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
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

test('precondition: the stand-in keeps newlines inside a paste and submits only on a bare Enter', async () => {
  const name = await startPane('pre');
  execFileSync('tmux', ['-L', TMUX_SOCK, 'load-buffer', '-b', 'hgbp', '-'], { input: 'one\ntwo' });
  sh('tmux', ['paste-buffer', '-b', 'hgbp', '-p', '-d', '-t', `=${name}:`]);
  await wait(200);
  assert.equal(composerOf(name), 'one\n  two', 'the second line is an indented continuation row');
  assert.deepEqual(submitted(name), [], 'the newline inside the paste submitted nothing');
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
  await wait(300);
  assert.deepEqual(submitted(name), ['one\ntwo']);
});

test('0: a message with a markdown rule or setext underline is submitted, whole, once', async () => {
  for (const [suffix, text] of [['hr', '## Plan\n---\nstep one: rebuild the index tonight'],
    ['setext', 'Status\n======\nall green']]) {
    const name = await startPane(suffix);
    const { status, body } = await send(name, text);
    assert.equal(status, 200, JSON.stringify(body));
    assert.equal(body.delivered, true, JSON.stringify(body));
    assert.equal(body.submitted, true, 'and the pane let go of it');
    assert.deepEqual(submitted(name), [text]);
    assert.equal(composerOf(name), '', 'nothing left in the box');
  }
});

test('1: a message line starting with ❯ is submitted, and an honest repeat is still a duplicate', async () => {
  const name = await startPane('caret');
  const text = 'look at this pane:\n❯ hello world';
  const first = await send(name, text);
  assert.equal(first.body.delivered, true, JSON.stringify(first.body));
  assert.deepEqual(submitted(name), [text]);
  // The 3.5.1 rule still stands for a copy that WENT: pressing Send again within
  // 30 s is the same message, not a second one.
  const again = await send(name, text);
  assert.equal(again.body.duplicate, true, JSON.stringify(again.body));
  assert.deepEqual(submitted(name), [text], 'still exactly once');
});

test('the truth: a message left in the box answers delivered:false / submitted:false, and its retry is NOT swallowed', async () => {
  // A TUI that never catches up (60 s behind) with somebody's words in the box:
  // the paste never appears, the box holds something else, recovery 'leave'.
  const name = await startPane('leave', { typed: 'somebody else typing', lag: 60_000 });
  const text = 'please look at the overnight backup report';
  const { status, body } = await send(name, text);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, `typed is not delivered: ${JSON.stringify(body)}`);
  assert.equal(body.submitted, false);
  assert.equal(body.queued, 0, 'and nothing is waiting either');
  assert.match(body.lastError || '', /not sent/);
  assert.match((await typingOf(name)).lastError || '', /not sent/, '/typing says the same');
  // Finding 1(b): the person presses Send again. A message that never went is
  // not a duplicate of itself.
  const again = await send(name, text);
  assert.notEqual(again.body.duplicate, true, JSON.stringify(again.body));
  try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
});

test('a message already stranded in the box is submitted by an Enter, not pasted a second time', async () => {
  const text = 'please look at the overnight backup report';
  const name = await startPane('stranded', { typed: text });
  const { body } = await send(name, text);
  assert.equal(body.delivered, true, JSON.stringify(body));
  assert.deepEqual(submitted(name), [text], 'once — not the message twice in one prompt');
});

test('2: an echoed numbered list does not hold the next send as a dialog', async () => {
  const name = await startPane('list', { rows: 25 });
  const list = '1. fix the backup job\n2. then rerun the audit';
  assert.equal((await send(name, list)).body.delivered, true);
  const { body } = await send(name, 'thanks, and also check the disk');
  assert.equal(body.blockedBy, null, JSON.stringify(body));
  assert.equal(body.delivered, true, JSON.stringify(body));
  assert.deepEqual(submitted(name), [list, 'thanks, and also check the disk']);
});

test('3: a dialog footer phrase in the conversation does not hold the next send', async () => {
  const name = await startPane('footer');
  for (const text of ['plan:\n  1. back up the db\n  2. rebuild the index',
    'the picker says esc to cancel, which do I pick?', 'never mind, go ahead']) {
    const { body } = await send(name, text);
    assert.equal(body.delivered, true, `${text}: ${JSON.stringify(body)}`);
  }
  assert.equal(submitted(name).length, 3);
});

test('4: two identical sends a few ms apart are submitted once', async () => {
  const text = 'please restart the media pipeline now';
  for (const gap of [15, 25, 35]) {
    const name = await startPane(`twice-${gap}`);
    const a = send(name, text);
    await wait(gap);
    const b = send(name, text);
    const [ra, rb] = await Promise.all([a, b]);
    await wait(500);
    assert.deepEqual(submitted(name), [text], `gap ${gap} ms: ${JSON.stringify([ra.body, rb.body])}`);
    assert.equal(rb.body.duplicate, true, `gap ${gap} ms: ${JSON.stringify(rb.body)}`);
  }
});

/**
 * A live draft, typed through the live view, then a send: held past the 5 s
 * quiet (LIVE_KEYS_QUIET_MS) for the 60 s keys window. Each case below was
 * released at ~4.7 s on 3.9.0 and submitted welded to the person's draft.
 */
async function assertHeldPastQuiet(name, draftKeys, text) {
  await liveType(name, draftKeys);
  await wait(300);
  const { body } = await send(name, text);
  assert.equal(body.blockedBy, 'draft', JSON.stringify(body));
  await wait(typing.LIVE_KEYS_QUIET_MS + 2_500);
  const st = await typingOf(name);
  assert.equal(st.queued, 1, `still held: ${JSON.stringify(st)}`);
  assert.equal(st.blockedBy, 'draft');
  assert.ok(!submitted(name).includes(text) && !submitted(name).some((m) => m.endsWith(text)),
    `nothing of ours went into their draft: ${JSON.stringify(submitted(name))}`);
  try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
}

test('5: a short message that occurs inside the draft does not make the draft ours', async () => {
  const name = await startPane('short-in-draft', { typed: 'look at the token cost' });
  await assertHeldPastQuiet(name, ' x', 'ok');
});

test("6: the person's own [Pasted text] marker does not make their draft ours", async () => {
  const name = await startPane('marker-draft', { typed: '[Pasted text #1 +40 lines] summarise this' });
  await assertHeldPastQuiet(name, ' x', 'please check the backup logs for errors tonight');
});

test('7: a draft that is a substring of the last DELIVERED message is still theirs', async () => {
  const name = await startPane('prior-substring');
  const priorMsg = 'look into why the nightly backup failed';
  assert.equal((await send(name, priorMsg)).body.delivered, true);
  await assertHeldPastQuiet(name, 'nightly back', 'also what is the disk usage on heimdall');
  assert.deepEqual(submitted(name), [priorMsg], 'only the earlier message');
});

test('8: a TUI seconds behind its input gets the message once, not twice', async () => {
  const name = await startPane('lag', { lag: 3_600 });
  await wait(300);
  const text = 'summarise the overnight backup report';
  const { body } = await send(name, text);
  assert.equal(body.delivered, true, JSON.stringify(body));
  await wait(4_500);   // let the stand-in drain everything it was sent
  assert.deepEqual(submitted(name), [text], 'never `textext`');
});

test('9: a paste-end marker inside the text cannot split the message', async () => {
  const name = await startPane('esc');
  const { body } = await send(name, 'first part\u001b[201~\rsecond part');
  assert.equal(body.delivered, true, JSON.stringify(body));
  assert.deepEqual(submitted(name), ['first part\nsecond part'], 'one message, both halves');
  assert.equal(composerOf(name), '', 'and nothing left behind in the box');
});
