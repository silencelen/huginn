'use strict';
// The Enter, and whether it lands on the right side of a startup.
//
// THE P1 THIS FILE EXISTS FOR (reported 2026-09-16, after 3.0.7 shipped the
// `starting` gate): "im still seeing some errors with the first chat sent during
// a new session being stuck in the screen views text box but not sent".
//
// Swept against the REAL `claude` 2.1.258 in the daemon's own WORKDIR, launched
// exactly the way `POST /v1/sessions` launches it, pasting exactly the way
// `sendTextToPane` pastes, outcome read from the transcript rather than the pane:
//
//   paste AFTER the composer, +0 ms to +3000 ms in 100 ms steps   31/31 submitted
//   paste ~2.0 s to ~0.85 s BEFORE the composer                    6/6  STUCK IN THE BOX
//   paste ~0.6 s to ~0.3 s BEFORE the composer                     4/4  lost without trace
//
// So there is no window after the composer — 3.0.7's gate is right about when to
// release. The bug is what happens when a send reaches the pane ANYWAY, which it
// still can three ways the gate does not control:
//
//   * the 20 s startup grace expires on a loaded host. Measured in production
//     2026-09-17 23:54:49Z: `typing: mcserver: no composer 22s after launch;
//     sending into the pane as it is`, and the owner's first message 82 ms later.
//   * the session was made by `cc` / `huginn <name>` rather than by the route, so
//     `launchingAt` never had an entry and `startingUp` returns false forever.
//   * a rename inside the grace drops the mark.
//
// Every test here therefore creates its session OUTSIDE the daemon, which is the
// shape all three share: a pane the gate has nothing to say about.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `settle-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 10950 + pid%50 -> 10950-10999.
const PORT = 10950 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `settle-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-settle-${process.pid}`;

// Shorter than the real 2.1 s so the suite does not crawl, long enough that the
// settle loop has to actually wait — and comfortably inside PASTE_SETTLE_MS.
const BOOT_MS = 1000;
const BANNER_MS = 500;
const UP_MS = BOOT_MS + BANNER_MS;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');

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

/** Send text the way a composer does: the Enter rides with the paste. */
function send(name, text) {
  return api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
  });
}

/**
 * Start a fake `claude` the daemon knows nothing about.
 *
 * Deliberately NOT through `POST /v1/sessions`: the whole subject of this file
 * is a pane the startup gate has no mark for, and creating it here is the only
 * way to be certain the gate is out of the picture rather than merely quick.
 */
function startPane(suffix, { prebuf = 'lost' } = {}) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_PREBUF=${prebuf} HG_FAKE_CLAUDE_OUT=${outFor(name)} `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${FAKE}`]);
  return name;
}

/** The composer's own line, read the way lib/typing reads it. */
function composerOf(name) {
  return require('../lib/typing').composerText(capture(name).replace(/\n$/, '').split('\n'));
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-settle-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  // `claude` is shadowed for the one test that goes through the create route.
  const binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `exec ${process.execPath} ${FAKE}\n`, { mode: 0o755 });

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
  for (let i = 0; i < 300; i++) {
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

test('precondition: the stand-in really does strand a paste in the composer', async () => {
  // The file rests on this. `prebuf=stuck` is the measured band — bytes that
  // beat the paint by a second or two SURVIVE, get rendered into the composer
  // when the TUI appears, and lose the `\r` that rode with them. Driven by hand
  // here, with no daemon involved, so a failure means the FIXTURE stopped
  // modelling the bug rather than the daemon regressing.
  const name = startPane('precondition', { prebuf: 'stuck' });
  const text = 'this one is stranded';
  execFileSync('tmux', ['-L', TMUX_SOCK, 'load-buffer', '-b', 'hgstuck', '-'], { input: text });
  sh('tmux', ['paste-buffer', '-b', 'hgstuck', '-p', '-d', '-t', `=${name}:`]);
  await wait(60);
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);

  await wait(UP_MS + 1200);
  assert.equal(readOr(outFor(name)), '', 'nothing may be submitted: the Enter was eaten');
  assert.equal(composerOf(name), text, 'and the message is sitting in the box — the owner\'s screenshot');
});

// -------------------------------------------------------------------- the fix

test('a message that beats the composer is still SENT, not left in the box', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.0.7 the daemon pastes, waits its fixed 50 ms and
  // presses Enter into a pane that is not reading yet: both go into the pty, the
  // TUI renders the text at paint time and drops the newline, and this assertion
  // fails in the owner's own words — `.submitted` is empty and the composer
  // holds the message. With the settle check the Enter waits for the text to be
  // ON SCREEN, which puts it after the paint, and the message goes.
  const name = startPane('race', { prebuf: 'stuck' });
  const text = 'the first thing I typed';

  const { status, body } = await send(name, text);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, true, 'an ungated pane delivers synchronously, as it always did');

  await wait(600);
  assert.equal(readOr(outFor(name)), `${text}\n`, 'the message was SUBMITTED');
  assert.equal(composerOf(name), '', 'and the composer let go of it');
  assert.match(capture(name), /● submitted/, 'the pane shows the turn starting');
});

test('the settle wait is BOUNDED, and a paste it never sees still gets its Enter', async () => {
  // The other band: bytes that beat the paint by only a few hundred ms are gone
  // entirely (4/4 on the real binary), and no amount of waiting will make them
  // appear. A person's message is delivered or it is an error, never quietly
  // binned — so the Enter goes anyway after the bound, and the one thing that
  // must not happen is silence: the journal carries the line and the pane's own
  // bottom rows, which is how a reader can tell this case from a slow one.
  const name = startPane('lost');          // default prebuf: the bytes are discarded
  const text = 'this one is swallowed whole';

  const t0 = Date.now();
  const { body } = await send(name, text);
  const took = Date.now() - t0;
  assert.equal(body.delivered, true, 'the send is not refused and not queued forever');
  assert.ok(took >= 2_500, `the settle check must actually wait its bound (took ${took}ms)`);
  assert.ok(took < 6_000, `and must not wait past it (took ${took}ms)`);

  await wait(400);
  assert.equal(readOr(outFor(name)), '', 'nothing arrived: these bytes were never readable');
  const jrnl = readOr(daemonLog);
  assert.match(jrnl, new RegExp(`${name}: pasted text never appeared`),
    'a delivery nobody could see must say so on disk');
  assert.match(jrnl, /pane: /, 'with what WAS in the pane, because that is the only evidence');
});

// ------------------------------------------------------- what must not regress

test('a session that is already up is not slowed by the settle check', async () => {
  // The cost of the check on the normal path is one capture-pane. A fixed wait
  // before every Enter would put a second onto every message anyone ever sends,
  // which is a worse bug than the one being fixed.
  const name = startPane('warm');
  await wait(UP_MS + 400);
  assert.notEqual(composerOf(name), null, 'precondition: the composer is up');

  const t0 = Date.now();
  const { body } = await send(name, 'a perfectly ordinary message');
  const took = Date.now() - t0;
  assert.equal(body.delivered, true);
  assert.ok(took < 1_000, `a warm send must not wait on the settle loop (took ${took}ms)`);
  await wait(300);
  assert.equal(readOr(outFor(name)), 'a perfectly ordinary message\n');
});

test('a pane with no composer at all is never reported as a stalled submit', async () => {
  // A plain shell echoes nothing and has no box. It costs the bound — it is
  // indistinguishable from a booting Claude at the moment of the paste, which is
  // the whole reason the bound exists — but it must not also produce the
  // "composer still holds the message" line, or that line stops meaning anything.
  const name = `${PFX}-shell`;
  madeSessions.add(name);
  const out = path.join(tmp, 'shell.txt');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'stty -echo; cat > ${out}'`]);

  const { body } = await send(name, 'straight through');
  assert.equal(body.delivered, true, 'an unmarked session still delivers');
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(readOr(out), 'straight through\n');
  assert.doesNotMatch(readOr(daemonLog), new RegExp(`${name}: composer still holds`),
    'there is no composer here to hold anything');
});
