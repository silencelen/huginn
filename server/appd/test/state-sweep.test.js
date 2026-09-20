'use strict';
// /run/huginn-claude-state, and what happens to it when a session dies somewhere
// this daemon cannot see.
//
// ⚠ THE FINDING (r2 L9). `clearSessionState` runs on create and on end, through
// the daemon — so anything killed at a terminal, or killed by a reboot, leaks its
// state file. The live host had 40+ of them on 2026-09-19, the oldest dead since
// 2026-07-27 (`adbpredictive`, `pprobe`, `r2-abs-0…12`, `hgv3-0…5`, …), plus a
// 0-byte `huginnv20.tmp` from an interrupted hook write and BOTH spellings of the
// sidecar directories — `ask`, `plan`, `compacting` sitting beside `.ask`,
// `.plan`, `.compacting`, which is the collision the dot-prefix was introduced to
// make unreachable (#2/#3).
//
// The sweep is deliberately timid. A state file is only taken when its session is
// GONE and the file has not been touched for a week, and a tmux read that FAILS
// sweeps nothing at all — a failure to observe is not an observation, the same
// rule listSessions and the registry reconcile carry. Losing a live session's
// state file costs it its state word, its claudeSessionId, its transcript and its
// conversation tab, so the bar for touching one is "the session does not exist".
//
// SAFETY:
//   * PORT 0 — an ephemeral port, so this file takes NO block from the port table
//     in routes-lifecycle.test.js and cannot collide with a sibling.
//   * A PRIVATE tmux socket and a scratch state dir. The one session it starts is
//     named `swp-<pid>-*` and runs an inert `cat >/dev/null`.
//   * No network: an empty scratch ~/.claude, so nothing has a credential.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');
const SOCK = `huginn-sweep-${process.pid}`;
const PFX = `swp-${process.pid}`;
const LIVE = `${PFX}-live`;

/** Long enough ago to be past the keep window whatever that window is set to. */
const LONG_AGO = Date.now() - 30 * 24 * 60 * 60 * 1000;

let tmp, stateDir;

function tmux(...args) { return execFileSync('tmux', ['-L', SOCK, ...args], { encoding: 'utf8' }); }

function writeAged(p, body, whenMs = LONG_AGO) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, body);
  fs.utimesSync(p, whenMs / 1000, whenMs / 1000);
}

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), `appd-sweep-${process.pid}-`));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.writeFileSync(path.join(tmp, 'token'), `${crypto.randomBytes(24).toString('hex')}\n`);
  tmux('new-session', '-d', '-s', LIVE, '-c', tmp, 'cat >/dev/null');
});

after(() => {
  try { tmux('kill-server'); } catch { /* already gone */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** Boot a daemon against the scratch dirs, wait for it to be up, stop it. */
async function boot(extra = {}) {
  const child = spawn(process.execPath, [DAEMON], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: '0',
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: SOCK,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      ...extra,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (b) => { out += b.toString(); });
  child.stderr.on('data', (b) => { out += b.toString(); });
  try {
    for (let i = 0; i < 300 && !/listening on/.test(out); i++) {
      await new Promise((r) => setTimeout(r, 50));
    }
  } finally {
    child.kill('SIGTERM');
    await new Promise((r) => { child.on('exit', r); setTimeout(r, 3000); });
  }
  assert.match(out, /listening on/, `the daemon never started:\n${out}`);
  return out;
}

test('a dead session\'s state leaves at startup, and a live one\'s never does (L9)', async () => {
  const dead = path.join(stateDir, `${PFX}-dead`);
  const live = path.join(stateDir, LIVE);
  const fresh = path.join(stateDir, `${PFX}-fresh`);
  const tmpWrite = path.join(stateDir, `${LIVE}.tmp`);

  writeAged(dead, JSON.stringify({ state: 'idle', sessionId: 'sid-dead' }));
  writeAged(live, JSON.stringify({ state: 'idle', sessionId: 'sid-live' }));
  // Dead, but only just — a session ended an hour ago may be revived, and its
  // transcript mapping is the thing the state file carries.
  writeAged(fresh, JSON.stringify({ state: 'idle', sessionId: 'sid-fresh' }), Date.now() - 60 * 60 * 1000);
  // The 0-byte corpse of an interrupted hook write. Its session IS live, and it
  // still goes: the hook writes and renames in one millisecond-scale step, so a
  // `.tmp` that has survived a week cannot be a write in progress — and it is
  // not the file the session reads, which is the undotted name beside it.
  writeAged(tmpWrite, '');

  // Both spellings of the sidecars, which is the collision the dot-prefix exists
  // to make unreachable — and the legacy directories are still there to collide
  // with until something removes them.
  writeAged(path.join(stateDir, '.ask', `${PFX}-dead`), '{"ts":1}');
  writeAged(path.join(stateDir, '.ask', LIVE), '{"ts":1}');
  writeAged(path.join(stateDir, 'ask', `${PFX}-dead`), '{"ts":1}');
  fs.mkdirSync(path.join(stateDir, 'plan'), { recursive: true });
  fs.mkdirSync(path.join(stateDir, '.plan'), { recursive: true });

  const out = await boot();

  assert.equal(false, fs.existsSync(dead), `the dead session's state file survived:\n${out}`);
  assert.equal(true, fs.existsSync(live), 'A LIVE SESSION\'S STATE FILE MUST NEVER BE TOUCHED');
  assert.equal(true, fs.existsSync(fresh), 'a session dead for an hour is not a session dead for a week');
  assert.equal(false, fs.existsSync(tmpWrite), 'the interrupted write is a corpse, whatever its session');

  assert.equal(false, fs.existsSync(path.join(stateDir, '.ask', `${PFX}-dead`)), 'the dead sidecar too');
  assert.equal(true, fs.existsSync(path.join(stateDir, '.ask', LIVE)), 'and not the live one');
  assert.equal(false, fs.existsSync(path.join(stateDir, 'ask')),
    'an EMPTY legacy directory goes with it — it exists only to be collided with');
  assert.equal(false, fs.existsSync(path.join(stateDir, 'plan')), 'including one that was already empty');
  assert.equal(true, fs.existsSync(path.join(stateDir, '.plan')),
    'the dotted ones are in use and are never removed');

  assert.match(out, /state sweep/, 'and it says what it took, or a silent sweep is unfalsifiable');
});

test('a tmux that will not answer sweeps NOTHING (L9)', async () => {
  // ⚠ A FAILURE TO OBSERVE IS NOT AN OBSERVATION. If a failed listing read as
  // "no sessions are live" this sweep would delete the state of every session on
  // the host the first time tmux hiccuped at startup — and a state file is a
  // session's state word, its claudeSessionId, its transcript path and its
  // conversation tab. The shim below fails `list-sessions` the way a fork that
  // hit EAGAIN fails, which is NOT "no server running".
  const shimDir = path.join(tmp, 'shim');
  fs.mkdirSync(shimDir, { recursive: true });
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + 'for a in "$@"; do\n'
    + '  if [ "$a" = "list-sessions" ]; then\n'
    + '    echo "resource temporarily unavailable" >&2\n'
    + '    exit 1\n'
    + '  fi\n'
    + 'done\n'
    + 'exit 0\n', { mode: 0o755 });

  const doomed = path.join(stateDir, `${PFX}-blind`);
  writeAged(doomed, JSON.stringify({ state: 'idle', sessionId: 'sid-blind' }));

  const out = await boot({ PATH: `${shimDir}:${process.env.PATH}` });
  assert.equal(true, fs.existsSync(doomed),
    `a blind daemon deleted a state file it could not prove was dead:\n${out}`);
});
