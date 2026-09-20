'use strict';
// THE PANE-SIZE LEASE, AND WHO IS ALLOWED TO TAKE IT.
//
// Owner decision 52: only the client that is in LIVE VIEW holds a session's
// pane-size lease. Everything else — a phone with the Screen tab merely on
// display, a desktop window showing the pane while the reader works elsewhere —
// reads the pane AS IT IS and resizes nothing.
//
// The bug this pins: two clients on one session, both reporting their own
// `?cols=&rows=`, walked the OWNER'S real tmux pane between 152x44 and 107x44
// three times in ninety seconds. Neither was typing. The lease is a claim over
// somebody else's terminal, so it now takes an explicit `live=1` to make one,
// and a second live client does NOT get to take one that is already held.
//
// SAFETY: never touches a real session. Every tmux session here is named
// `lease-<pid>-*` on a PRIVATE `-L` socket running an inert `cat`, and is killed
// in after(). State files go to a scratch HUGINN_APPD_STATE_DIR, never /run.
//
// ⚠ ASSERTED AGAINST TMUX ITSELF, not against the daemon's own bookkeeping.
// `#{pane_width}` read straight off the private socket is the only thing that
// says whether the owner's window actually moved; a `sizeLeased` flag in a JSON
// body is the daemon agreeing with itself.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-typing.test.js. `node --test` runs
// these files CONCURRENTLY, so a range that overlaps a sibling's fails on an
// unlucky pair of pids and passes every other time.
//
//   routes-pane-lease  11450 + pid%50   -> 11450-11499   (this file)
const PORT = 11450 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `lease-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-lease-${process.pid}`;

// Short enough that expiry is testable, long enough that a slow box does not
// expire a lease mid-assertion. The sweep interval derives from it in the
// daemon, so this one number shortens both.
const LEASE_MS = 1_500;

let tmp, stateDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(args) {
  return execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });
}

/** A pane that swallows input: nothing typed at it can execute. */
function mkSession(suffix, cols = 100, rows = 30) {
  const name = `${PFX}-${suffix}`;
  sh(['new-session', '-d', '-s', name, '-c', tmp, '-x', String(cols), '-y', String(rows),
    'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}

/** The pane's real geometry, straight off tmux. */
function paneSize(name) {
  const out = sh(['display-message', '-p', '-t', `=${name}:`, '#{pane_width}\t#{pane_height}']);
  const [w, h] = out.trim().split('\t').map(Number);
  return { cols: w, rows: h };
}

/**
 * Whether tmux still has the window pinned at a manual size.
 *
 * ⚠ THE OPPOSITE OF `manual` IS NOT `latest`. Unsetting the option restores
 * whatever the server's inherited default is — `smallest` on a stock tmux 3.6b —
 * so the release assertions say "no longer manual", never "now latest". Pinning
 * the wrong default made the release tests fail against a correct daemon.
 */
function windowSize(name) {
  return sh(['display-message', '-p', '-t', `=${name}:`, '#{window-size}']).trim();
}

/**
 * One call as a named client. The lease is keyed on `X-Huginn-Client`, which is
 * what makes "a second client" mean anything at all.
 */
async function api(pathname, { client = null, method = 'GET' } = {}) {
  const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
  if (client) headers['x-huginn-client'] = client;
  const res = await fetch(BASE + pathname, { method, headers });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-lease-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(tmp, 'data'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

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
      HUGINN_APPD_LEASE_MS: String(LEASE_MS),
    },
    stdio: ['ignore', logFd, logFd],
  });
  fs.closeSync(logFd);
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? The port formula gives few
  // slots, and one leaked by an earlier run — a test process killed before
  // after() could fire — sits on one and refuses OUR token. /v1/ping is
  // authenticated like every other route, so the start loop above never sees
  // its 200 and spins its whole cap, and then every call below 401s: a wall of
  // `401 unauthorized` that reads like a code bug and is not one. So ask an
  // AUTHENTICATED question before trusting the port, and say plainly what is
  // wrong: `ss -ltnp | grep <port>`, then kill it.
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an `
      + `earlier test run. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  for (const name of madeSessions) {
    try { sh(['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh(['kill-server']); } catch { /* no server */ }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------ live is what leases

test('a screen read WITHOUT live renders the pane as it is and resizes nothing', async () => {
  const name = mkSession('view', 100, 30);
  const before = paneSize(name);
  assert.deepEqual(before, { cols: 100, rows: 30 }, 'precondition: the pane starts at 100x30');

  const { status, body } = await api(`/v1/sessions/${name}/screen?cols=60&rows=20`);
  assert.equal(status, 200, JSON.stringify(body));

  assert.deepEqual(paneSize(name), before,
    'a client merely LOOKING at the pane must not move the owner\'s window');
  assert.notEqual(windowSize(name), 'manual',
    'window-size must not be switched to manual by a non-live read');
  assert.equal(body.sizeLeased, false, 'and no lease may be recorded');
  assert.equal(body.width, 100, 'the capture is of the pane as it actually is');
});

test('a screen read WITH live=1 takes the lease and resizes the pane', async () => {
  const name = mkSession('live', 100, 30);
  const { status, body } = await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=1`,
    { client: 'client-a' });
  assert.equal(status, 200, JSON.stringify(body));

  assert.deepEqual(paneSize(name), { cols: 60, rows: 20 },
    'live view is what entitles a client to reshape the window');
  assert.equal(body.sizeLeased, true);
  assert.equal(body.leaseHeldBy, 'client-a', 'the body names who holds it');

  await api(`/v1/sessions/${name}/size`, { client: 'client-a', method: 'DELETE' });
});

test('live=0 is spelt out and means the same as no live at all', async () => {
  const name = mkSession('live0', 100, 30);
  await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=0`, { client: 'client-a' });
  assert.deepEqual(paneSize(name), { cols: 100, rows: 30 });
});

// -------------------------------------------------- one holder, no stealing

test('a second client\'s live=1 does not steal an active lease, and the body says who has it',
  async () => {
    const name = mkSession('steal', 100, 30);
    const first = await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=1`,
      { client: 'client-a' });
    assert.equal(first.body.leaseHeldBy, 'client-a');
    assert.deepEqual(paneSize(name), { cols: 60, rows: 20 });

    // THE OBSERVED BUG, as a request: a second live client with its own geometry.
    const second = await api(`/v1/sessions/${name}/screen?cols=90&rows=25&live=1`,
      { client: 'client-b' });
    assert.equal(second.status, 200, 'the second client still gets its screen');
    assert.deepEqual(paneSize(name), { cols: 60, rows: 20 },
      'the pane must not flap to the second client\'s geometry');
    assert.equal(second.body.leaseHeldBy, 'client-a',
      'and the second client is told whose lease it is, rather than silently ignored');
    assert.equal(second.body.width, 60, 'it reads the pane at the holder\'s size');

    await api(`/v1/sessions/${name}/size`, { client: 'client-a', method: 'DELETE' });
  });

test('the holder renewing its own lease is not stealing', async () => {
  const name = mkSession('renew', 100, 30);
  await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=1`, { client: 'client-a' });
  const again = await api(`/v1/sessions/${name}/screen?cols=70&rows=22&live=1`,
    { client: 'client-a' });
  assert.deepEqual(paneSize(name), { cols: 70, rows: 22 },
    'the holder may change its mind about its own geometry');
  assert.equal(again.body.leaseHeldBy, 'client-a');
  await api(`/v1/sessions/${name}/size`, { client: 'client-a', method: 'DELETE' });
});

// ---------------------------------------------------------------- expiry

test('the lease still expires on its own, and the window is handed back', async () => {
  const name = mkSession('expire', 100, 30);
  await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=1`, { client: 'client-a' });
  assert.equal(windowSize(name), 'manual', 'precondition: the window is pinned');

  // Silence. The whole safety property: a client that stops polling — crashed,
  // out of range, force-quit — cannot leave the owner's window pinned.
  const until = Date.now() + LEASE_MS * 6;
  while (Date.now() < until && windowSize(name) === 'manual') await wait(100);

  assert.notEqual(windowSize(name), 'manual',
    'an unrenewed lease must lapse and give the window back to tmux');

  // And once it has lapsed, the OTHER client may have it.
  const b = await api(`/v1/sessions/${name}/screen?cols=90&rows=25&live=1`,
    { client: 'client-b' });
  assert.equal(b.body.leaseHeldBy, 'client-b');
  assert.deepEqual(paneSize(name), { cols: 90, rows: 25 });
  await api(`/v1/sessions/${name}/size`, { client: 'client-b', method: 'DELETE' });
});

// ------------------------------------------------------------ DELETE /size

test('DELETE /size from a non-holder is a no-op', async () => {
  const name = mkSession('delete', 100, 30);
  await api(`/v1/sessions/${name}/screen?cols=60&rows=20&live=1`, { client: 'client-a' });
  assert.deepEqual(paneSize(name), { cols: 60, rows: 20 });

  const theirs = await api(`/v1/sessions/${name}/size`, { client: 'client-b', method: 'DELETE' });
  assert.equal(theirs.status, 200, 'a release that is not yours is not an error, it is nothing');
  assert.equal(theirs.body.released, false);
  assert.deepEqual(paneSize(name), { cols: 60, rows: 20 },
    'one client leaving its screen tab must not hand back another client\'s lease');
  assert.equal(theirs.body.leaseHeldBy, 'client-a');

  const mine = await api(`/v1/sessions/${name}/size`, { client: 'client-a', method: 'DELETE' });
  assert.equal(mine.body.released, true);
  assert.notEqual(windowSize(name), 'manual', 'the holder\'s own release still works');
});
