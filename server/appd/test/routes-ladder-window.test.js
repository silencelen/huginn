'use strict';
// The usage ladder through the `/model` picker a SHORT pane draws, end to end:
// a real daemon from the tree, real tmux, and the stub picker drawing Claude
// Code 2.1.316's layout in an 80x24 pane (fixtures/stub-picker.js,
// HG_STUB_WINDOW; the layout is fixtures/picker-frame.js, from the captures).
//
// THE FAILURE (2026-10-29 → 11-05): at 80x24 — every session no client has
// open, every project member appd spawns — the picker is a two-row window onto
// the list, opened on the session's current model. A window that did not start
// at row 1 did not parse, the ladder returned "the model picker never appeared"
// with nothing pressed, and the picker stayed open: four project sessions sat
// behind it for a week, every message from the apps queued behind the dialog
// until somebody pressed Escape on the Screen tab. A window that did start at
// row 1 parsed, but the row to set was under `… +N models` and never reached.
//
// The door in is POST /v1/sessions/:name/headroom/undo against a SEEDED
// headroom record, as in routes-ladder-draft.test.js: the same applyLadder job
// the arbiter queues, run once, with no usage endpoint and no arbiter retry to
// race the assertions. An undo moves a session back to FABLE.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `lwin-<pid>-*` on a PRIVATE `-L` socket, killed in after().
// State, data, HOME and the Claude dir are scratch; the usage endpoints point at
// a closed loopback port and Telegram is blanked, so nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { untilDrawn } = require('./until-drawn');
const { listOn } = require('./fixtures/picker-frame');

// PORT ALLOCATION — the blocks are read from the code by port-blocks.test.js.
// This file owns 12470 + pid%10.
const PORT = 12470 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `lwin-${process.pid}`;
const TMUX_SOCK = `huginn-lwin-${process.pid}`;
const STUB = path.join(__dirname, 'fixtures', 'stub-picker.js');

// The route suite's own picker clocks (huginn-appd.js PICKER_WAIT_MS and
// PICKER_LATE_MS): two seconds to open, ten more watched for a late one. The
// late stand-in opens at six, four seconds clear of either edge on a busy host.
const WAIT_MS = 2_000;
const LATE_MS = 10_000;
const OPENS_LATE_MS = WAIT_MS + 4_000;

let tmp, stateDir, dataDir, token, daemon, shimLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-u', '-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
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

/**
 * Every key the DAEMON sent to this pane, in order, from the tmux shim's log:
 * a literal (`-l -- /model`) as its text, a key by its name.
 */
function keysSent(name) {
  const raw = fs.existsSync(shimLog) ? fs.readFileSync(shimLog, 'utf8') : '';
  const out = [];
  for (const line of raw.split('\n')) {
    const a = line.split('\t').filter((x) => x !== '');
    const i = a.indexOf('send-keys');
    if (i < 0 || a[i + 1] !== '-t' || a[i + 2] !== `=${name}:`) continue;
    const rest = a.slice(i + 3);
    if (rest[0] === '-l') out.push(rest[rest.length - 1]); else out.push(...rest);
  }
  return out;
}
/** The keys after the `/model` and its Enter: what the walk and the clean-up pressed. */
function keysAfterModel(name) {
  const k = keysSent(name);
  const i = k.indexOf('/model');
  assert.ok(i >= 0, `/model was typed into ${name}: ${JSON.stringify(k)}`);
  assert.equal(k[i + 1], 'Enter', 'and submitted');
  return k.slice(i + 2);
}

/** An 80x24 pane drawing the picker as 2.1.316 does, on the given list. */
function startPicker(suffix, rows, { mode = 'confirm', env = {} } = {}) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  const vars = Object.entries({ HG_STUB_WINDOW: '2', ...env }).map(([k, v]) => `${k}=${v}`).join(' ');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24',
    `env ${vars} ${process.execPath} ${STUB} ${JSON.stringify(JSON.stringify(rows))} ${mode} ${JSON.stringify(stateDir)} ${name}`]);
  untilDrawn(TMUX_SOCK, name, /❯/);
  return name;
}

/** The session at a turn boundary, as the title hook and the transcript say. */
function atBoundary(name) {
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${[
    JSON.stringify({ type: 'user', message: { content: 'run the nightly check' } }),
    JSON.stringify({ type: 'assistant', message: { model: 'claude-opus-5-5', stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } }),
    JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 }),
  ].join('\n')}\n`);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Date.now(),
  }));
}

/** Sessions the ladder moved off Fable, so Undo has something to put back. */
function seedLaddered(names) {
  const sessions = {};
  for (const n of names) {
    sessions[`sid-${n}`] = {
      family: 'opus',
      ladder: { from: 'fable', to: 'opus', at: Date.now() - 60_000, delivery: 'confirmed', sessionOnly: true },
    };
  }
  fs.writeFileSync(path.join(dataDir, 'headroom.json'), JSON.stringify({ v: 1, sessions }));
}

const undo = (name) => api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });

const panes = {};

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-lwin-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  const home = path.join(tmp, 'home');
  const claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, home, claudeDir]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // The tmux shim records the daemon's argv, so a test can say what it did NOT
  // press. On PATH for the daemon only; this file's own tmux calls go straight.
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  const shimDir = path.join(tmp, 'shim');
  fs.mkdirSync(shimDir);
  shimLog = path.join(tmp, 'tmux-argv.log');
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + '{ printf \'%s\\t\' "$@" | tr \'\\n\' \' \'; printf \'\\n\'; } >> "$HG_TMUX_LOG"\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });

  // Every pane is up, and every record seeded, BEFORE the daemon starts: the
  // headroom state is read once.
  panes.below = startPicker('below', listOn('Opus 5.5'));
  panes.newest = startPicker('newest', listOn('Opus 4.8'));
  panes.unread = startPicker('unread', listOn('Opus 5.5'), { env: { HG_STUB_WINDOW: '1' } });
  panes.late = startPicker('late', listOn('Opus 5.5'), { env: { HG_STUB_LATE_MS: String(OPENS_LATE_MS) } });
  panes.none = startPicker('none', listOn('Opus 5.5'), { mode: 'nopicker' });
  for (const n of Object.values(panes)) atBoundary(n);
  seedLaddered(Object.values(panes));

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${shimDir}:${process.env.PATH}`,
      HG_TMUX_LOG: shimLog,
      HOME: home,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
      HUGINN_APPD_PICKER_WAIT_MS: String(WAIT_MS),
      HUGINN_APPD_PICKER_LATE_MS: String(LATE_MS),
      HUGINN_APPD_USAGE_URL: 'http://127.0.0.1:9/usage',
      HUGINN_APPD_OAUTH_ACCOUNT_URL: 'http://127.0.0.1:9/account',
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      HUGINN_FCM_KEY: '',
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/sessions');
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

const arrows = (keys) => keys.filter((k) => k === 'Up' || k === 'Down');

test('a row hidden below the two-row window is reached, set with `s`, and its `Switch model?` confirmed', async () => {
  // Opened on Opus 5.5 (row 2): `  1.` / `❯ 2.` / `… +11 models`, the capture
  // exactly, with Fable 5.1 under the fold. Before: "the picker offers no fable
  // row", Escape, and the session was never put back.
  const name = panes.below;
  const r = await undo(name);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.applied, true, JSON.stringify(r.body));
  assert.equal(r.body.delivery, 'confirmed');
  assert.match(capture(name), /Set model to Fable 5\.1 for this session only/);
  const keys = keysAfterModel(name);
  assert.deepEqual(arrows(keys), ['Up', 'Down', 'Down'], 'up to row 1, then down to the first Fable');
  assert.deepEqual(keys.slice(-2), ['s', 'Enter'], '`s`, then Yes on the confirmation');
});

test('the newest row of the family is set, not the older one drawn beside the highlight', async () => {
  // On Opus 4.8 (row 10) the window is `↑ 9. Fable 5` — its description wrapped
  // onto a second line — over the highlight. Fable 5 is the old one; the row a
  // whole list gives is Fable 5.1, row 3. Before: the window did not parse and
  // the picker was left open.
  const name = panes.newest;
  const r = await undo(name);
  assert.equal(r.body.applied, true, JSON.stringify(r.body));
  const screen = capture(name);
  assert.match(screen, /Set model to Fable 5\.1 for this session only/);
  assert.doesNotMatch(screen, /Set model to Fable 5 for/);
  assert.deepEqual(arrows(keysAfterModel(name)), [...Array(9).fill('Up'), 'Down', 'Down']);
});

test('a picker on screen that cannot be read is closed with Escape, never walked', async () => {
  // One row drawn: heading and footer on screen, nothing a walk can use.
  // Before: "never appeared", nothing pressed — and the picker stayed open.
  const name = panes.unread;
  const r = await undo(name);
  assert.equal(r.body.applied, false);
  assert.equal(r.body.delivery, 'delivery_unconfirmed');
  assert.doesNotMatch(capture(name), /Select model/, 'the picker is closed');
  const keys = keysAfterModel(name);
  assert.ok(keys.includes('Escape'), JSON.stringify(keys));
  assert.deepEqual(arrows(keys), [], 'no cursor keys');
  assert.equal(keys.includes('s'), false, 'no `s`');
});

test('a picker that opens after the wait is closed, not left open', async () => {
  // Drawn 6 s after `/model`, past the 2 s this suite waits for one to use.
  const name = panes.late;
  const r = await undo(name);
  assert.equal(r.body.applied, false);
  assert.doesNotMatch(capture(name), /Select model/, 'the late picker was closed');
  const keys = keysAfterModel(name);
  assert.deepEqual(keys, ['Escape'], 'one Escape, at the picker, and nothing else');
});

test('no picker at all: nothing is pressed after the Enter', async () => {
  // Escape on a busy Claude interrupts the turn, and on a dialog it answers it.
  const name = panes.none;
  const r = await undo(name);
  assert.equal(r.body.applied, false);
  assert.deepEqual(keysAfterModel(name), [], 'no Escape, no C-u, no arrows, no `s`');
});
