'use strict';
// What a session may be CALLED, and what the daemon says it is called.
//
// Two separate promises, and the second is the one that broke. (1) The name
// rule: letters, digits, underscore and dash, case-folded, and nothing that
// collides with the daemon's own state directories. (2) The readback: every
// route that creates or renames a tmux session must report the name tmux
// actually gave it, never the name it was asked for — tmux silently rewrites
// '.' to '_' and exits 0, so a route that assumes it got what it asked for
// hands the client a name that 404s on everything it does next (#103/#105/#106).
//
// SAFETY: never touches a real session. Every tmux session is named
// `nam-<pid>-*` on a PRIVATE `-L` socket, runs an inert `cat >/dev/null` so
// anything typed into it cannot execute, and is killed in after(). State files
// go to a scratch HUGINN_APPD_STATE_DIR, never /run.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so ranges must not overlap. The full table lives in
// routes-typing.test.js; this file's block is the w3 edge-hunt reservation:
//
//   routes-session-names 11400 + pid%50  -> 11400-11449   (this file)
const PORT = 11400 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `nam-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, stateDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
/** Exactly what tmux holds, which is the only authority on a session's name. */
function liveNames() {
  try {
    return sh('tmux', ['list-sessions', '-F', '#S']).split('\n').map((s) => s.trim()).filter(Boolean);
  } catch { return []; }
}
function mkSession(name) {
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}
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
const create = (name) => api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name }) });
const rename = (from, to) => api(`/v1/sessions/${from}/rename`, {
  method: 'POST', body: JSON.stringify({ name: to }),
});

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-names-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(tmp, 'data'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // ⚠ `claude` IS SHADOWED. The create route runs the literal command
  // `claude; exec "$SHELL" -l`, so the only way to keep the operator's real
  // Claude Code out of these panes is a stand-in earlier on the daemon's PATH.
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  process.env.PATH = `${bin}:${process.env.PATH}`;

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping (no token needed) and rejects ours, which surfaces as a
  // wall of 401s that reads like a code bug and is not one.
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  for (const s of madeSessions) { try { sh('tmux', ['kill-session', '-t', `=${s}`]); } catch { /* gone */ } }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------ the name rule

test('a dotted name is refused at the door, and nothing is created', async () => {
  // A '.' cannot survive tmux, so a name carrying one is a name no session can
  // ever have. Refusing it is what makes every readback below belt-and-braces
  // rather than the only thing standing between a client and a phantom.
  const { status, body } = await create(`${PFX}-my.project`);
  assert.equal(400, status, JSON.stringify(body));
  assert.match(body.error, /\./, 'the message says what is wrong with it');
  assert.equal(false, liveNames().some((n) => n.includes(`${PFX}-my`)),
    'and no half-made session is left behind under the rewritten spelling');
});

test('the daemon\'s own state directories cannot be taken as session names', async () => {
  // ⚠ WHY THESE THREE. The title hook keeps its prompt sidecars in
  // STATE_DIR/.ask, /.plan and /.compacting, and before they were dot-prefixed a
  // session called `plan` shared that namespace: either that session never got a
  // state file (its JSON was moved INTO the directory) or no session on the host
  // got a sidecar at all. Both halves are fixed by the prefix; the names stay
  // reserved so a future reader cannot reintroduce the collision by accident.
  for (const reserved of ['ask', 'plan', 'compacting', 'login']) {
    const { status, body } = await create(reserved);
    assert.equal(400, status, `${reserved}: ${JSON.stringify(body)}`);
    assert.match(body.error, /reserved/i, reserved);
  }
});

test('a name is case-folded, and the 201 says what tmux actually called it', async () => {
  const { status, body } = await create(`${PFX}-MixedCase`);
  assert.equal(201, status, JSON.stringify(body));
  madeSessions.add(body.name);
  assert.equal(`${PFX.toLowerCase()}-mixedcase`, body.name, 'canonically lowercase');
  assert.ok(liveNames().includes(body.name),
    `the 201 named ${body.name}; tmux holds ${JSON.stringify(liveNames())}`);
});

test('dashes survive, because tmux keeps them', async () => {
  const { status, body } = await create(`${PFX}-with-dashes`);
  assert.equal(201, status, JSON.stringify(body));
  madeSessions.add(body.name);
  assert.equal(`${PFX}-with-dashes`, body.name);
  assert.ok(liveNames().includes(body.name));
});

// -------------------------------------------------------------- the rename

test('a rename reports the name tmux ended up with, and refuses a dot (#105)', async () => {
  const from = mkSession(`${PFX}-renamable`);
  const bad = await rename(from, `${PFX}-new.name`);
  assert.equal(400, bad.status, JSON.stringify(bad.body));
  assert.ok(liveNames().includes(from), 'the session is untouched by a refused rename');

  const ok = await rename(from, `${PFX}-Renamed`);
  assert.equal(200, ok.status, JSON.stringify(ok.body));
  madeSessions.add(ok.body.name);
  assert.equal(`${PFX}-renamed`, ok.body.name);
  assert.ok(liveNames().includes(ok.body.name),
    `the 200 named ${ok.body.name}; tmux holds ${JSON.stringify(liveNames())}`);
});

test('a rename cannot move another session\'s state file (#105)', async () => {
  // ⚠ THE SHAPE THAT DESTROYED A BYSTANDER. The old readback was `-t '=<to>'`
  // with no trailing colon, and tmux resolves a colon-less target by the same
  // '.'-to-'_' rewrite — so renaming X to `victim.tail` read back the LIVE
  // session `victim` and the route then migrated X's state file, sidecars, pane
  // lease, send queue and registry row onto it, pumping X's queued text into the
  // victim's pane. The dot ban removes the trigger; this asserts the outcome.
  const victim = mkSession(`${PFX}-victim`);
  const mover = mkSession(`${PFX}-mover`);
  fs.writeFileSync(path.join(stateDir, victim), JSON.stringify({
    state: 'idle', sessionId: 'sid-victim', transcript: null, cwd: tmp, ts: Date.now(),
  }));
  fs.writeFileSync(path.join(stateDir, mover), JSON.stringify({
    state: 'idle', sessionId: 'sid-mover', transcript: null, cwd: tmp, ts: Date.now(),
  }));

  const r = await rename(mover, `${victim}.tail`);
  assert.equal(400, r.status, JSON.stringify(r.body));
  const after = JSON.parse(fs.readFileSync(path.join(stateDir, victim), 'utf8'));
  assert.equal('sid-victim', after.sessionId, 'the bystander still owns its own transcript mapping');
  assert.ok(liveNames().includes(victim));
  assert.ok(liveNames().includes(mover), 'and nothing was renamed');
});

// ------------------------------------------------------------ addressability

test('the sessions list says which rows the app can actually open', async () => {
  // A name outside the rule is still LISTED — it is a real session and hiding it
  // would be worse — but every per-session route will 404 on it, so the row says
  // so rather than letting a client find out by tapping it.
  const ok = mkSession(`${PFX}-plain`);
  const odd = mkSession('a+session');
  const { status, body } = await api('/v1/sessions');
  assert.equal(200, status);
  const row = (n) => body.sessions.find((s) => s.name === n);
  assert.ok(row(ok), 'the ordinary session is listed');
  assert.equal(true, row(ok).addressable);
  assert.ok(row('a+session'), 'and so is the odd one');
  assert.equal(false, row('a+session').addressable);
});
