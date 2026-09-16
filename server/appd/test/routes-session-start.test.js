'use strict';
// The seconds between "session created" and "Claude is up", and the message a
// person sends inside them.
//
// THE P1 THIS FILE EXISTS FOR (reported 2026-09-15): "im noticing a race
// condition where a user creates a session and can send a message before claude
// is brought up in the background, the text seems to still generate paste into
// the claude code session box, but it forces the user to switch to the session
// tab and enter live view to hit enter".
//
// Measured on the host against Claude Code 2.1.258, pasting exactly the way
// lib/typing.js does (load-buffer | paste-buffer -p -d, a 50 ms beat, send-keys
// Enter) into a session launched exactly the way `POST /v1/sessions` does:
//
//   new-session -d returns  t+0.03 s   the pane is COMPLETELY EMPTY
//   pasted at t+1.1 s       the message and its Enter VANISH — no composer
//                           text, no turn, no transcript record, nothing
//   pasted at t+1.8 s       the message submits AND a copy of it reappears in
//                           the composer seconds later, typed but unsent
//   composer drawn          t+2.1 s    the whole TUI in one write
//   pasted at t+2.1 s       normal
//
// Both failure shapes leave the sender looking at a composer that emptied and a
// session that did nothing, which is the screen a DROPPED message draws — the
// exact thing the send queue exists to prevent.
//
// SAFETY: never touches a real session and never starts a real `claude`. The
// daemon's PATH is shadowed with test/fixtures/fake-claude.js, every tmux
// session is named `start-<pid>-*` on a PRIVATE `-L` socket, and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 10800 + pid%50 -> 10800-10849.
const PORT = 10800 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `start-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

// The startup phases the fake claude draws. 1.2 s of empty pane then 0.6 s of
// caret-free console text: shorter than the real 2.1 s so the suite does not
// crawl, long enough that the 400 ms queue poll has to actually wait.
const BOOT_MS = 1200;
const BANNER_MS = 600;
const UP_MS = BOOT_MS + BANNER_MS;

let tmp, stateDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
/** Where a session's fake claude records what was SUBMITTED to it. */
const outFor = (name) => path.join(tmp, `${name}.submitted`);
/** …and what arrived before it was reading, which is the bug's own evidence. */
const lostFor = (name) => `${outFor(name)}.lost`;
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** Create a session through the ROUTE the owner's client uses, and remember it. */
async function createSession(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  const { status, body } = await api('/v1/sessions', {
    method: 'POST', body: JSON.stringify({ name }),
  });
  assert.equal(status, 201, JSON.stringify(body));
  return name;
}

/** Send text the way a composer does: the Enter rides with the paste. */
function send(name, text) {
  return api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
  });
}

async function typingOf(name) {
  return (await api(`/v1/sessions/${name}/typing`)).body;
}

/** Poll until this session's queue has drained, or give up and say where it got to. */
async function drains(name, ms = 10_000) {
  const until = Date.now() + ms;
  for (;;) {
    const st = await typingOf(name);
    if (st && st.queued === 0) return st;
    if (Date.now() > until) return st;
    await wait(120);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-start-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  // ⚠ `claude` IS SHADOWED, and it has to be the create route's own spelling.
  // That route runs the literal command `claude; exec "$SHELL" -l`, so the only
  // way to put a stand-in in its place is a `claude` earlier on the daemon's
  // PATH. Nothing real is ever launched by this file.
  const binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  const fake = path.join(__dirname, 'fixtures', 'fake-claude.js');
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\n'
    // The session name is the only per-session thing the shim needs, and tmux
    // is the one that knows it — $HG_FAKE_CLAUDE_OUT is derived from it here so
    // one PATH entry serves every session the tests make.
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `exec ${process.execPath} ${fake}\n`, { mode: 0o755 });

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${binDir}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HG_FAKE_CLAUDE_BOOT_MS: String(BOOT_MS),
      HG_FAKE_CLAUDE_BANNER_MS: String(BANNER_MS),
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {  // 30s cap: a loaded host has pushed start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping (no token needed) and rejects our token, which surfaces as
  // every test in this file failing 401 — a code bug that is not one.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

/** SIGTERM, then SIGKILL, and wait until the process is REAPED. */
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

// ---------------------------------------------------------------- the harness

test('precondition: a paste that beats the composer really is LOST', async () => {
  // The whole file rests on this. A `cat` pane would model it wrong — a pty
  // buffers what nobody has read yet, so `cat` hands back a two-second-old
  // paste and the bug quietly does not reproduce. The real TUI discards it, and
  // so does the stand-in; this test is what says the stand-in still does.
  const name = await createSession('lost');
  const text = 'this one goes nowhere';
  execFileSync('tmux', ['-L', TMUX_SOCK, 'load-buffer', '-b', 'hgstart', '-'], { input: text });
  sh('tmux', ['paste-buffer', '-b', 'hgstart', '-p', '-d', '-t', `=${name}:`]);
  await wait(60);
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);

  await wait(UP_MS + 1200);
  assert.equal(readOr(outFor(name)), '', 'nothing may be submitted: nobody was reading');
  assert.match(readOr(lostFor(name)), /this one goes nowhere/,
    'the bytes were swallowed by the pre-composer phase, which is the bug');
});

// ------------------------------------------------------------------- the race

test('a message sent the instant a session is created is HELD, then delivered', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.0.6 this send is released immediately (a human
  // text bypasses the turn gate, and `paneReadyForInput`'s 'busy' blocks
  // nothing), the paste lands in an empty pane, and the assertions below fail
  // in the owner's own order: `delivered` comes back true, `.submitted` never
  // appears, and the text is sitting in `.lost`.
  const name = await createSession('race');
  const text = 'the first thing I typed';

  const { status, body } = await send(name, text);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, 'claude is not up yet: this must not go to the pane');
  assert.equal(body.queued, 1, 'held, not dropped — the sender is owed this message');

  const st = await drains(name);
  assert.equal(st.queued, 0, 'the hold must END; a message parked forever is the same bug');
  await wait(400);
  assert.equal(readOr(outFor(name)), `${text}\n`, 'submitted once the composer existed');
  assert.equal(readOr(lostFor(name)), '', 'and never thrown at the pane before that');
  assert.match(capture(name), /● submitted/, 'the pane shows the message going in');
});

test('the wait says what it is waiting FOR: blockedBy is "starting", not a turn', async () => {
  // The clients render this word. "will send when Claude finishes its turn" is
  // the wrong sentence here — Claude has not STARTED one — and a reader told
  // that has no reason to believe the message is coming.
  const name = await createSession('why');
  await send(name, 'what is holding this');
  const st = await typingOf(name);
  assert.equal(st.queued, 1);
  assert.equal(st.blockedBy, 'starting');
  assert.equal(st.lastError, null, 'a wait is not an error');
  await drains(name);
});

test('the hold is ONE-SHOT: the next message goes straight through', async () => {
  // A gate that keeps re-arming would put a 400 ms poll under every send for
  // the life of the session. The mark is retired the moment a composer is seen.
  const name = await createSession('once');
  await send(name, 'first');
  await drains(name);
  await wait(300);

  const t0 = Date.now();
  const { body } = await send(name, 'second');
  assert.equal(body.delivered, true, 'claude is up now; nothing may hold this');
  assert.equal(body.queued, 0);
  assert.ok(Date.now() - t0 < 1000, 'and it must not have waited on a poll');
  await wait(400);
  assert.equal(readOr(outFor(name)), 'first\nsecond\n');
});

// ------------------------------------------------------------ what must NOT be

test('a TRUST dialog is never released, however long the startup gate waits', async () => {
  // The one modal whose default option is destructive ("No, exit"): any Enter
  // at it kills the session. The startup gate must not become a back door into
  // one — it opens on "a caret is on screen", and the trust dialog HAS a caret.
  const name = `${PFX}-trust`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_TRUST=1 HG_FAKE_CLAUDE_OUT=${outFor(name)} `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${path.join(__dirname, 'fixtures', 'fake-claude.js')}`]);

  await wait(UP_MS + 500);
  assert.match(capture(name), /trust this folder/, 'precondition: the dialog is really up');

  const { body } = await send(name, 'this must never be typed at a trust dialog');
  assert.equal(body.delivered, false);

  await wait(1500);
  const st = await typingOf(name);
  assert.equal(st.queued, 1, 'still held');
  assert.equal(st.blockedBy, 'modal', 'and held by the DIALOG, which outranks startup');
  assert.equal(readOr(outFor(name)), '', 'nothing submitted');
  assert.equal(readOr(lostFor(name)), '', 'and not one byte sent at the dialog');
});

test('a pane appd did not launch claude into is never held', async () => {
  // The gate is narrow on purpose. A plain shell has no composer and never will,
  // so a rule keyed on "no caret yet" would hold every non-Claude pane the app
  // can open until its grace ran out. Only sessions appd started `claude` in
  // are marked, and this one was made outside the daemon entirely.
  const name = `${PFX}-shell`;
  madeSessions.add(name);
  const out = path.join(tmp, 'shell.txt');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'stty -echo; cat > ${out}'`]);

  const { body } = await send(name, 'straight through');
  assert.equal(body.delivered, true, 'an unmarked session keeps the old behaviour exactly');
  assert.equal(body.queued, 0);
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(readOr(out), 'straight through\n');
});
