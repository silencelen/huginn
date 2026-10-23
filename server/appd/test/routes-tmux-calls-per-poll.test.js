'use strict';
// How many tmux processes the polled routes start per request.
//
// Every tmux call is a process spawn, and Node pays for a spawn on the event
// loop: on a loaded host each one is milliseconds during which no other request
// moves. The polled routes made more of them than they needed:
//
//   GET /v1/sessions?preview=1   one capture-pane per session, every poll
//   GET .../screen               display-message, then capture-pane: two per read
//   a long poll that saw a change  peeked (one), then read the screen again (two)
//   GET .../transcript           the existence check, then a second display-message
//                                for the pane's pid
//
// The daemon under test sees a tmux shim that logs every argv and execs the real
// binary. Assertions count only calls that TARGET the session under test, which
// background ticks never do for an idle session with nothing pending.
//
// PORT: taken from the kernel (listen on 0, read it back, close).
//
// SAFETY: never touches a real session. The tmux sessions live on a private
// `-L` socket that after() kills, run `cat`, and the daemon's data, state and
// Claude dir are a scratch directory; every HUGINN_* variable of the calling
// shell is dropped.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { untilDrawn } = require('./until-drawn');

require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const PFX = `tc-${process.pid}`;
const SEP = '\x1f';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let tmp, token, daemon, base, logFile, transcriptFile;

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

/** Every tmux argv the daemon has run since the log was last cleared. */
function calls() {
  const text = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : '';
  return text.split('\n').filter(Boolean).map((l) => l.split(SEP).filter((a, i, all) => i < all.length - 1 || a));
}
/** ...of those, the ones that name session `name` as a target. */
const aimedAt = (name) => calls().filter((argv) => argv.includes(`=${name}:`));
const clearLog = () => fs.writeFileSync(logFile, '');

function mkSession(suffix, text) {
  const name = `${PFX}-${suffix}`;
  tmux(['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', `sh -c 'printf "${text}\\n"; exec cat'`]);
  untilDrawn(TMUX_SOCK, name, new RegExp(text));
  return name;
}

let A, B, C;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-tc-'));
  for (const d of ['state', 'data', 'claude', 'scratch', 'shim']) fs.mkdirSync(path.join(tmp, d));
  logFile = path.join(tmp, 'tmux.log');
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  // One write per call, args joined by \x1f: the format strings carry tabs.
  fs.writeFileSync(path.join(tmp, 'shim', 'tmux'),
    '#!/bin/sh\n'
    + `line=$(printf '%s\\037' "$@")\n`
    + `printf '%s\\n' "$line" >> "$HG_TMUX_LOG"\n`
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });

  A = mkSession('a', 'alpha pane');
  B = mkSession('b', 'bravo pane');
  C = mkSession('c', 'charlie pane');
  await wait(1100);   // state stamped after the sessions exist
  transcriptFile = path.join(tmp, 'claude', 'a.jsonl');
  fs.writeFileSync(transcriptFile, `${JSON.stringify({ type: 'user', message: { content: 'hello' }, timestamp: '2026-10-01T09:00:00.000Z' })}\n`);
  fs.writeFileSync(path.join(tmp, 'state', A), JSON.stringify({
    state: 'idle', sessionId: '1c0ffee0-0000-4000-8000-000000000001', transcript: transcriptFile, cwd: tmp, ts: Date.now(),
  }));

  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(HUGINN_|TMUX)/.test(k)));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HG_TMUX_LOG: logFile,
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_CLAUDE_SCRATCH: path.join(tmp, 'scratch'),
      HUGINN_APPD_WORKDIR: tmp,
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

test('the preview list reads every pane with one tmux process', async () => {
  clearLog();
  const { status, body } = await api('/v1/sessions?preview=1');
  assert.equal(status, 200);
  const rows = Object.fromEntries(body.sessions.map((s) => [s.name, s]));
  // What each row shows is still its own pane.
  assert.deepStrictEqual(rows[A].preview, ['alpha pane']);
  assert.deepStrictEqual(rows[B].preview, ['bravo pane']);
  assert.deepStrictEqual(rows[C].preview, ['charlie pane']);
  const captures = calls().filter((argv) => argv.includes('capture-pane'));
  assert.equal(captures.length, 1, `${captures.length} capture calls:\n${captures.map((c) => c.join(' ')).join('\n')}`);
  for (const name of [A, B, C]) assert.ok(captures[0].includes(`=${name}:`), `${name} was not in the one capture`);
});

test('a pane tmux cannot read leaves the rest of the batch to be read one by one', async () => {
  // tmux abandons a command list at its first failure — a session that ended
  // after it was listed — and prints what it had done so far. The batch keeps
  // the panes it got whole and leaves the rest out, for the caller to read
  // singly as it always did.
  const { lift } = require('./appd-lift');
  const seen = [];
  const { fns } = lift(['capturePanes'], {
    crypto,
    run: async (cmd, args) => {
      seen.push(args);
      const mark = args[args.indexOf('display-message') + 4];
      return { err: new Error('exit 1'), stdout: `one\ntwo\n${mark}\nthree\n`, stderr: "can't find session: gone" };
    },
  });
  const got = await fns.capturePanes(['first', 'gone', 'third']);
  assert.equal(seen.length, 1);
  assert.deepStrictEqual([...got.keys()], ['first']);
  assert.deepStrictEqual([...got.get('first')], ['one', 'two']);
});

test('a screen read is one tmux process', async () => {
  clearLog();
  const { status, body } = await api(`/v1/sessions/${A}/screen`);
  assert.equal(status, 200);
  assert.ok(body.lines.some((l) => l.includes('alpha pane')));
  assert.ok(body.width === 80 && body.height === 24);
  const mine = aimedAt(A);
  assert.equal(mine.length, 1, `${mine.length} calls:\n${mine.map((c) => c.join(' ')).join('\n')}`);
});

test('a long poll that sees the change answers from the read that saw it', async () => {
  const first = await api(`/v1/sessions/${A}/screen`);
  assert.equal(first.status, 200);
  clearLog();
  const parked = api(`/v1/sessions/${A}/screen?hash=${first.body.hash}&wait=10000`);
  await wait(400);
  tmux(['send-keys', '-t', `=${A}:`, '-l', 'typed here']);
  const { status, body } = await parked;
  assert.equal(status, 200);
  assert.ok(!body.unchanged);
  assert.ok(body.lines.some((l) => l.includes('typed here')), 'the answer does not show the change');
  // Every read was ONE process carrying both the geometry and the content —
  // never a peek followed by a second, separate read of the same screen.
  const mine = aimedAt(A);
  assert.ok(mine.length >= 2, `only ${mine.length} calls: the poll never parked`);
  for (const argv of mine) {
    assert.ok(argv.includes('display-message') && argv.includes('capture-pane'),
      `a call that is not one combined read: ${argv.join(' ')}`);
  }
});

test('a transcript poll starts no tmux process of its own', async () => {
  // Its existence check (and the pane's pid) come from the shared session
  // listing: at most that one listing, and nothing aimed at the session.
  const size = fs.statSync(transcriptFile).size;
  clearLog();
  const { status, body } = await api(`/v1/sessions/${A}/transcript?offset=${size}`);
  assert.equal(status, 200);
  assert.deepStrictEqual(body.events, []);
  assert.deepStrictEqual(body.tasks, []);
  assert.equal(body.bgAgents, 0);
  const all = calls();
  assert.equal(aimedAt(A).length, 0, `calls aimed at the session:\n${aimedAt(A).map((c) => c.join(' ')).join('\n')}`);
  assert.ok(all.length <= 1 && all.every((c) => c.includes('list-sessions')),
    `${all.length} calls:\n${all.map((c) => c.join(' ')).join('\n')}`);
});

test('history polls share one session listing', async () => {
  // Five history polls and then the session list, back to back. The polls take
  // their existence check from the shared listing (at most one list-sessions
  // between them, none if a listing from the last few seconds is still good);
  // the list route always asks tmux itself. So one or two in all, never one
  // existence check per poll.
  const size = fs.statSync(transcriptFile).size;
  clearLog();
  for (let i = 0; i < 5; i++) {
    assert.equal((await api(`/v1/sessions/${A}/transcript?offset=${size}`)).status, 200);
  }
  assert.equal((await api('/v1/sessions')).status, 200);
  const lists = calls().filter((c) => c.includes('list-sessions'));
  const checks = calls().filter((c) => c.includes('display-message') && c.includes(`=${A}:`));
  assert.equal(checks.length, 0, `${checks.length} existence checks of their own`);
  assert.ok(lists.length >= 1 && lists.length <= 2, `${lists.length} listings for six requests in about a second`);
});
