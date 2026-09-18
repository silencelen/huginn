'use strict';
// What the daemon says when tmux is ALIVE but not answering.
//
// ⚠ THE TWO ANSWERS THAT LOOKED IDENTICAL. `sessionExists` collapsed any `run()`
// failure — a 10 s timeout, a fork hitting EAGAIN, the tmux server restarting —
// into `found !== name`, i.e. "no such session", so every session-gated route
// 404'd a session that was running and unkilled, DELETE reported 404 without
// killing anything, and GET /screen's 404 made the Android poller set
// `_sessionGone` and eject the viewer with "Session <name> ended" (#14). A
// failure to OBSERVE is not an observation — listSessions had already learned
// that lesson one function over, and its comment says so.
//
// SAFETY: never touches a real session. Every tmux session is named
// `hlth-<pid>-*` on a PRIVATE `-L` socket, runs an inert `cat >/dev/null`, and
// is killed in after(). The tmux the DAEMON sees is a shim that can be told to
// misbehave for one subcommand at a time; the test's own tmux calls go straight
// to the real binary, so the assertions can always see the truth.

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
//   routes-session-names 11400 + pid%20  -> 11400-11419
//   routes-tmux-health   11420 + pid%20  -> 11420-11439   (this file)
//   …plus 11440 + pid%9 -> 11440-11448 for this file's throwaway daemons.
const PORT = 11420 + (process.pid % 20);
const SPARE_PORT = 11440 + (process.pid % 9);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `hlth-${process.pid}`;
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, stateDir, token, daemon, hangFile;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function mkSession(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}
function sessionAlive(name) {
  try { sh('tmux', ['has-session', '-t', `=${name}`]); return true; } catch { return false; }
}
function writeState(name) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${name}`, transcript: null, cwd: tmp, ts: Date.now(),
  }));
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

/** Make the daemon's tmux fail the named subcommands the way a loaded host does. */
function breakTmux(...subs) { fs.writeFileSync(hangFile, subs.join('\n')); }
function fixTmux() { fs.rmSync(hangFile, { force: true }); }

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-health-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(tmp, 'data'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  hangFile = path.join(tmp, 'break-tmux');

  // The shim. When $HG_TMUX_BREAK lists one of this call's arguments, it fails
  // the way a fork that hit EAGAIN fails: a non-zero exit with tmux's own
  // resource message on stderr — a shape that is NOT "no such session", which is
  // the whole point. Otherwise it execs the real tmux.
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  const shimDir = path.join(tmp, 'shim');
  fs.mkdirSync(shimDir);
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + 'if [ -f "$HG_TMUX_BREAK" ]; then\n'
    + '  for a in "$@"; do\n'
    + '    if grep -qx -- "$a" "$HG_TMUX_BREAK" 2>/dev/null; then\n'
    + '      echo "resource temporarily unavailable" >&2\n'
    + '      exit 1\n'
    + '    fi\n'
    + '  done\n'
    + 'fi\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });
  // `claude` is shadowed for the same reason as everywhere else: the create
  // route runs the literal `claude; exec "$SHELL" -l`.
  fs.writeFileSync(path.join(shimDir, 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${shimDir}:${process.env.PATH}`,
      HG_TMUX_BREAK: hangFile,
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
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  fixTmux();
  if (daemon) daemon.kill('SIGTERM');
  for (const s of madeSessions) { try { sh('tmux', ['kill-session', '-t', `=${s}`]); } catch { /* gone */ } }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a session that is genuinely absent still 404s', async () => {
  // The control. tmux answering "there is no such session" is an OBSERVATION,
  // and nothing below may blur it into an error.
  const r = await api(`/v1/sessions/${PFX}-never/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'hello', keys: ['Enter'] }),
  });
  assert.equal(404, r.status, JSON.stringify(r.body));
  assert.match(r.body.error, /no such session/);
});

test('tmux refusing to answer is 503, not "no such session" (#14)', async () => {
  const name = mkSession('keys');
  writeState(name);
  breakTmux('display-message');
  try {
    const r = await api(`/v1/sessions/${name}/keys`, {
      method: 'POST', body: JSON.stringify({ text: 'hello', keys: ['Enter'] }),
    });
    assert.equal(503, r.status, JSON.stringify(r.body));
    assert.match(r.body.error, /tmux is not answering/i);
  } finally { fixTmux(); }
  assert.equal(true, sessionAlive(name), 'the session was alive the whole time');
});

test('GET /screen does not report a live session as ended (#14)', async () => {
  // ⚠ THE ONE 404 THAT IS IRREVERSIBLE. Both clients hand a failed send back to
  // the composer, but the screen poller reads a 404 as `_sessionGone` and ejects
  // the viewer with "Session <name> ended" — a transient blip in the small-hours
  // backup window threw the reader out of a running session.
  const name = mkSession('screen');
  writeState(name);
  breakTmux('display-message', 'capture-pane');
  try {
    const r = await api(`/v1/sessions/${name}/screen`);
    assert.equal(503, r.status, JSON.stringify(r.body));
  } finally { fixTmux(); }
  assert.equal(true, sessionAlive(name));
  assert.equal(200, (await api(`/v1/sessions/${name}/screen`)).status, 'and it comes back by itself');
});

test('DELETE does not report 404 for a kill it never attempted (#14)', async () => {
  const name = mkSession('del');
  writeState(name);
  breakTmux('kill-session');
  try {
    const r = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
    assert.equal(503, r.status, JSON.stringify(r.body));
  } finally { fixTmux(); }
  assert.equal(true, sessionAlive(name), 'the session is alive, and the answer said so');

  const ok = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(200, ok.status, JSON.stringify(ok.body));
  assert.equal(false, sessionAlive(name));
});

test('DELETE of an absent session is 404 and still tidies up after it', async () => {
  // ⚠ A KILL THAT SUCCEEDED AND WHOSE CLIENT WENT AWAY used to leave the state
  // file and the restore-registry row behind, because hardEndSession skipped its
  // bookkeeping on ANY error — and the registry row resurrects the session at the
  // next reboot. "Already gone" is the end state this route is asking for.
  const name = `${PFX}-ghost`;
  writeState(name);
  const r = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(404, r.status, JSON.stringify(r.body));
  assert.equal(false, fs.existsSync(path.join(stateDir, name)),
    'the orphaned state file goes with it');
});

// ------------------------------------------- the daemon's own wiring at startup

test('a gate bound to a different sentinel directory is called out at startup (#28)', async () => {
  // ⚠ A PAUSE BUTTON WIRED TO NOTHING, WITH NO SYMPTOM. HUGINN_APPD_DATA is a
  // documented knob and it moves the daemon's sentinel directory to
  // <dataDir>/headroom, while the bash gate has its own compiled-in
  // /var/lib/huginn-appd/headroom. install-hooks binds the two together now
  // (the appd-libs half of this finding), but a hook installed by an older
  // deploy still points somewhere else: the gate releases every held spawn at
  // waited=0, writes no held row, drops its log into the abandoned directory,
  // and /v1/headroom cheerfully reports the sentinel armed. One line at startup
  // is all this side can do; the repair is a re-run of deploy.sh.
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-gatedir-'));
  const settings = path.join(scratch, 'settings.json');
  fs.writeFileSync(settings, JSON.stringify({
    hooks: {
      SubagentStart: [{
        matcher: '*',
        hooks: [{
          type: 'command',
          command: 'env HUGINN_HEADROOM_DIR=/var/lib/huginn-appd/headroom /opt/huginn-appd/hooks/huginn-headroom-gate',
          timeout: 1800,
        }],
      }],
    },
  }, null, 2));
  const out = path.join(scratch, 'out.log');

  const spawnWith = async (env) => {
    fs.writeFileSync(out, '');
    const fd = fs.openSync(out, 'a');
    const child = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
      env: {
        ...process.env,
        HUGINN_APPD_PORT: String(SPARE_PORT),
        HUGINN_APPD_BIND: '127.0.0.1',
        HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
        HUGINN_APPD_STATE_DIR: path.join(scratch, 'state'),
        HUGINN_APPD_WORKDIR: scratch,
        HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
        HUGINN_CLAUDE_SETTINGS: settings,
        ...env,
      },
      stdio: ['ignore', fd, fd],
    });
    fs.closeSync(fd);
    for (let i = 0; i < 200; i++) {
      if (/listening on/.test(fs.readFileSync(out, 'utf8'))) break;
      await wait(100);
    }
    const text = fs.readFileSync(out, 'utf8');
    child.kill('SIGKILL');
    await wait(200);
    return text;
  };

  const moved = await spawnWith({ HUGINN_APPD_DATA: path.join(scratch, 'data') });
  assert.match(moved, /pause button is wired to nothing/,
    `the mismatch must be said out loud. Log: ${moved.slice(0, 600)}`);

  // …and when they agree, it says nothing at all.
  const matched = await spawnWith({ HUGINN_HEADROOM_DIR: '/var/lib/huginn-appd/headroom' });
  assert.doesNotMatch(matched, /pause button is wired to nothing/);
  assert.match(matched, /listening on/, 'precondition: this daemon came up');

  fs.rmSync(scratch, { recursive: true, force: true });
});

// ------------------------------------- who the daemon thinks is still listening

test('a BUSY watch stream keeps stamping its own client (#32/#41/#44)', async () => {
  // ⚠ THE EVIDENCE INVERSION. The SSE stream stamped its client at connect and
  // then only in the KEEPALIVE branch — and a state frame resets the keepalive
  // clock, so a stream whose digest changes at least once every 25 s never
  // reaches it. Three ordinary interactive sessions on normal turn cycles are
  // enough (measured: 51 state frames, 0 keepalives over seven minutes), and
  // after FRESH_STREAM_MS the most-connected client on the host was recorded as
  // GONE: /v1/clients says so and `appOnline` goes false, which is what gates
  // the Telegram fallback and the Round reports when push reached nobody. The
  // clean victim is Compose Desktop, whose only /v1/watch caller is this stream.
  const id = `streamer-${process.pid}`;
  const name = mkSession('watched');
  writeState(name);
  const ac = new AbortController();
  const res = await fetch(`${BASE}/v1/watch?stream=1`, {
    headers: { authorization: `Bearer ${token}`, 'x-huginn-client': id },
    signal: ac.signal,
  });
  assert.equal(200, res.status);
  const reader = res.body.getReader();
  const frames = [];
  (async () => {
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        frames.push(Buffer.from(value).toString('utf8'));
      }
    } catch { /* aborted */ }
  })();

  const row = async () => (await api('/v1/clients')).body.clients.find((c) => c.id === id);
  const atConnect = (await row()).checkIns;

  // Churn the digest well inside the keepalive window, the way a session going
  // running→idle→running does.
  for (let i = 0; i < 6; i++) {
    fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
      state: i % 2 ? 'running' : 'idle', sessionId: `sid-${name}`, transcript: null, cwd: tmp, ts: Date.now(),
    }));
    await wait(1600);
  }

  const after = await row();
  ac.abort();
  assert.ok(frames.join('').includes('event: state'),
    `precondition: the stream sent state frames. Got: ${frames.join('').slice(0, 300)}`);
  assert.ok(after.checkIns > atConnect,
    `a streaming client must be stamped by its own frames (${atConnect} -> ${after.checkIns})`);
  assert.equal(true, after.fresh, 'and must not read as a client that stopped checking in');
});

// ---------------------------------------- how a client proves this is a daemon

test('every response carries X-Huginn-Appd, the 401 included (contract 2)', async () => {
  // ⚠ WHAT THE HEADER IS FOR. The phone and desktop probe an address before
  // handing it a root-equivalent bearer token, and `probe()` accepted "something
  // answered" — so any HTTP responder on the LAN address the built-in route
  // names was called huginn and auto-switch leaked the token to it (kcore
  // #59/#78). The client side becomes an unauthenticated GET that must answer
  // 401 with the daemon's own error shape; this header is the stronger marker it
  // prefers when it is there, so it has to be on the UNAUTHENTICATED answer too.
  // /v1/ping needs the bearer like everything else, so the UNAUTHENTICATED
  // probe's whole answer is this 401 — which is exactly why the marker has to
  // ride on it.
  const unauth = await fetch(`${BASE}/v1/ping`);
  assert.equal(401, unauth.status);
  assert.match(unauth.headers.get('x-huginn-appd') || '', /^\d+\.\d+\.\d+$/,
    'the refusal is the one response an un-enrolled client can see');
  assert.deepEqual({ error: 'unauthorized' }, await unauth.json());

  const ping = await fetch(`${BASE}/v1/ping`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(200, ping.status);
  assert.equal((await ping.json()).version, ping.headers.get('x-huginn-appd'),
    'and it agrees with the version in the body');

  const authed = await fetch(`${BASE}/v1/sessions`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(200, authed.status);
  assert.ok(authed.headers.get('x-huginn-appd'));
});

test('a malformed JSON body is a 400, not a 500 quoting the parser (#31)', async () => {
  // ⚠ 35 CALL SITES, ONE CATCH. Every route parsed the body inline, so
  // complete-but-invalid JSON reached the router's catch and came back as a 500
  // carrying the raw V8 message — and that same catch echoed ANY thrown message
  // verbatim, so an fs failure in a save path answered with the absolute host
  // path. Neither shipped client can emit invalid JSON, which is why this sat
  // unnoticed; a hand-rolled script or a client bug is all it takes.
  for (const p of ['/v1/quick-actions', '/v1/headroom/settings']) {
    const r = await api(p, { method: 'PATCH', body: '{"stopPct": 70,}' });
    assert.equal(400, r.status, `${p}: ${JSON.stringify(r.body)}`);
    assert.equal('body must be JSON', r.body.error, p);
    assert.doesNotMatch(r.body.error, /JSON\.parse|position|token/i,
      'and it must not quote the parser at the caller');
  }
  // An EMPTY body is still the empty object every optional-body route expects.
  const empty = await api('/v1/headroom/settings', { method: 'PATCH', body: '' });
  assert.equal(200, empty.status, JSON.stringify(empty.body));
});
