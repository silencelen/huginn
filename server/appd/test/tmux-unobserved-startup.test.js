'use strict';
// The two startup paths that act on "what is live" before anything else can,
// on a host whose tmux cannot be read and whose systemd will not say whether
// the server is alive (2026-11-06).
//
// On that night a loaded host failed one listing with tmux's no-server words
// while every session was alive, and `systemctl show` failed with it; the
// daemon read the pair as an empty host and archived five projects. Two other
// readers made the same inference without asking systemd at all:
//
//   * the STATE SWEEP took "no such file or directory" in tmux's stderr as "no
//     session is live", and deletes the state file of every session that is
//     gone and idle for a week: a live session's state word, its Claude session
//     id, its transcript path. And it trusted one listing, where a direct look
//     at the session costs one `has-session`.
//   * the REBOOT RESTORE took the same words as "the box rebooted" and recreates
//     every recorded session, which on a host whose server is alive but
//     unreachable means a second server with duplicates in it.
//
// SAFETY: a PRIVATE tmux socket and scratch dirs; the one session made here runs
// an inert `cat`. The tmux and systemctl the daemon runs are the stand-ins of
// test/tmux-shim.js, and so is its `claude`, which runs nothing. Port 0, no
// network: an empty scratch ~/.claude.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { lyingTmux, REAL_TMUX } = require('./tmux-shim');

const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');
const SOCK = `huginn-unobserved-${process.pid}`;
const PFX = `uno-${process.pid}`;
const LIVE = `${PFX}-live`;
/** Long enough ago to be past the sweep's keep window whatever it is set to. */
const LONG_AGO = Date.now() - 30 * 24 * 60 * 60 * 1000;

let tmp, stateDir, dataDir, shim;

function tmux(...args) { return execFileSync(REAL_TMUX, ['-L', SOCK, ...args], { encoding: 'utf8' }); }
function hasSession(name) { try { tmux('has-session', '-t', `=${name}`); return true; } catch { return false; } }

function writeAged(p, body) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, body);
  fs.utimesSync(p, LONG_AGO / 1000, LONG_AGO / 1000);
}

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), `appd-unobserved-${process.pid}-`));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  for (const d of [stateDir, dataDir, path.join(tmp, 'home'), path.join(tmp, 'claude')]) fs.mkdirSync(d);
  fs.writeFileSync(path.join(tmp, 'token'), `${crypto.randomBytes(24).toString('hex')}\n`);
  shim = lyingTmux(path.join(tmp, 'shim'), { sock: SOCK });
});

after(() => {
  try { tmux('kill-server'); } catch { /* already gone */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** Boot a daemon on the scratch dirs, wait until it listens (the startup paths run first), stop it. */
async function boot() {
  const child = spawn(process.execPath, [DAEMON], {
    env: {
      ...process.env,
      ...shim.env,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: '0',
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: SOCK,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (b) => { out += b.toString(); });
  child.stderr.on('data', (b) => { out += b.toString(); });
  try {
    for (let i = 0; i < 300 && !/listening on/.test(out); i++) await new Promise((r) => setTimeout(r, 50));
  } finally {
    child.kill('SIGTERM');
    await new Promise((r) => { child.on('exit', r); setTimeout(r, 3000); });
  }
  assert.match(out, /listening on/, `the daemon never started:\n${out}`);
  return out;
}

test('no server answering and a systemd that will not say: the startup sweep takes NOTHING', async () => {
  tmux('new-session', '-d', '-s', LIVE, '-c', tmp, 'cat >/dev/null');
  // A live session idle for a month: exactly what the sweep takes once its session is gone.
  const state = path.join(stateDir, LIVE);
  writeAged(state, JSON.stringify({ state: 'idle', sessionId: 'sid-live' }));
  shim.set('unit', ['fail']);
  shim.set('list-noserver');
  let out;
  try { out = await boot(); } finally { shim.clear('list-noserver'); }
  assert.equal(true, fs.existsSync(state),
    `a live session's state was swept because tmux and systemd could not be read:\n${out}`);
  assert.match(out, /state sweep \(startup\): tmux is not answering — nothing swept/);
});

test('a listing that leaves a live session out does not cost it its state: tmux is asked about the name', async () => {
  const state = path.join(stateDir, LIVE);
  writeAged(state, JSON.stringify({ state: 'idle', sessionId: 'sid-live' }));
  // The control beside it: a session that really is gone still has its state taken.
  const dead = path.join(stateDir, `${PFX}-dead`);
  writeAged(dead, JSON.stringify({ state: 'idle', sessionId: 'sid-dead' }));
  shim.set('list-hide', [LIVE]);
  let out;
  try { out = await boot(); } finally { shim.clear('list-hide'); }
  assert.equal(true, hasSession(LIVE), 'the session was alive the whole time');
  assert.equal(true, fs.existsSync(state), `one listing that left it out was taken as its end:\n${out}`);
  assert.equal(false, fs.existsSync(dead), `a session tmux has no such session for still goes:\n${out}`);
  assert.match(out, /1 file\(s\) kept, tmux would not confirm their session gone/);
});

test('no tmux server and a systemd that will not say: the restore recreates nothing', async () => {
  try { tmux('kill-server'); } catch { /* none */ }
  const name = `${PFX}-restore`;
  const now = Math.floor(Date.now() / 1000);
  fs.writeFileSync(path.join(dataDir, 'active-sessions.json'), JSON.stringify({
    version: 1,
    sessions: { [name]: { name, createdAt: now - 600, claudeSessionId: null, cwd: tmp, restoredAt: null, updatedAt: now } },
  }));
  shim.set('unit', ['fail']);
  const out = await boot();
  assert.equal(false, hasSession(name),
    `a host that could not be shown to have rebooted had its sessions recreated:\n${out}`);
  assert.match(out, /session restore: tmux has no server, but systemd could not say whether huginn-tmux\.service holds one \(systemctl failed: .*\) — restoring nothing/);
  const reg = JSON.parse(fs.readFileSync(path.join(dataDir, 'active-sessions.json'), 'utf8'));
  assert.ok(reg.sessions[name], 'and the record is kept for a restart that can tell');
});

test('...and with systemd saying the unit has no main process, it restores as it always did', async () => {
  const name = `${PFX}-restore`;
  try { tmux('kill-server'); } catch { /* none: as the test above left it */ }
  shim.set('unit', ['0']);
  const out = await boot();
  assert.equal(true, hasSession(name), `the reboot restore no longer restores:\n${out}`);
  assert.match(out, /session restore: tmux server is gone \(reboot\?\); 1 recorded session\(s\) to bring back/);
});
