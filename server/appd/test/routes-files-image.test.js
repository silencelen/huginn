'use strict';
// GET /v1/files/image against a real daemon: the status codes, the headers, the
// conditional GET, and the one thing a pure test cannot see — that `?session=`
// widens the allowlist only for a session that is actually LIVE on tmux.
//
// The containment RULES are asserted in files.test.js, away from HTTP, and this
// file deliberately does not re-litigate them; what is here is the wiring. The
// exception is the symlink case, which is repeated end-to-end on purpose: it is
// the one failure mode where "the resolver is right" and "the route is right"
// could come apart, and the cost of being wrong is the host's /etc.
//
// SAFETY: touches no session of the operator's and no pane of theirs. The daemon
// under test gets a scratch HUGINN_APPD_DATA, a scratch HUGINN_APPD_STATE_DIR, a
// scratch HUGINN_APPD_CLAUDE_SCRATCH (so the real /tmp/claude-0 is never a root
// here) and a PRIVATE tmux socket, so the sessions it creates and lists are on a
// server nobody reads. Every session this file makes is named with the pid and
// killed in after().

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every route file here binds a real socket and `node --test`
// runs the files CONCURRENTLY, so each takes its own block. The full table lives
// in routes-lifecycle.test.js / routes-quick-actions.test.js and is NOT restated
// here: a table copied into twenty files is twenty chances to update nineteen.
//
//   routes-files-image  11300 + pid%50   -> 11300-11349   (this file)
//
// ⚠ 11000-11199 is spoken for OUTSIDE the suite — the edge-hunt workflow runs
// scratch daemons there. Do not take the next block downward from here.
const PORT = 11300 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;
const PFX = `fi${process.pid}`;

// A one-pixel PNG. Real bytes, so a 200 that lied about its type would be
// visible to anything that actually opened the response.
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');
// Small, so the 413 is provable without writing twelve megabytes to disk to
// demonstrate arithmetic the pure test already covers.
const SERVE_MAX = 2048;

let tmp, dataDir, stateDir, uploads, render, scratch, outside, projectDir, tokenFile, token, daemon;
const madeSessions = new Set();

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const nowSec = () => Math.floor(Date.now() / 1000);

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}

/** A raw fetch, so headers and status can be read without parsing a body. */
async function get(query, init = {}) {
  const qs = new URLSearchParams(query).toString();
  const res = await fetch(`${BASE}/v1/files/image?${qs}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, ...(init.headers || {}) },
  });
  const buf = Buffer.from(await res.arrayBuffer());
  let json = null;
  try { json = JSON.parse(buf.toString('utf8')); } catch { /* bytes, not json */ }
  return { status: res.status, headers: res.headers, buf, json };
}

async function startDaemon() {
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: tokenFile,
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_CLAUDE_SCRATCH: scratch,
      HUGINN_APPD_IMAGE_SERVE_MAX: String(SERVE_MAX),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {   // 30s cap: gradle load on this host has pushed start past 10s
    try {
      const r = await fetch(`${BASE}/v1/ping`);
      if (r.status === 200) break;
    } catch { /* not up */ }
    await wait(100);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-fimg-'));
  dataDir = path.join(tmp, 'data');
  stateDir = path.join(tmp, 'state');
  uploads = path.join(dataDir, 'uploads');
  render = path.join(dataDir, 'scratchpads', 'render');
  scratch = path.join(tmp, 'claude-scratch');
  outside = path.join(tmp, 'elsewhere');
  projectDir = path.join(tmp, 'project');
  for (const d of [dataDir, stateDir, uploads, render, scratch, outside,
    projectDir, path.join(projectDir, 'shots')]) {
    fs.mkdirSync(d, { recursive: true });
  }

  fs.writeFileSync(path.join(uploads, 'shot.png'), PNG_1PX);
  fs.writeFileSync(path.join(render, 'chart.gif'), PNG_1PX);
  fs.writeFileSync(path.join(scratch, 'scratch.webp'), PNG_1PX);
  fs.writeFileSync(path.join(uploads, 'page.html'), '<script>alert(1)</script>');
  fs.writeFileSync(path.join(uploads, 'logo.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  fs.writeFileSync(path.join(uploads, 'big.png'), Buffer.alloc(SERVE_MAX + 1));
  fs.writeFileSync(path.join(projectDir, 'shots', 'cwd.jpg'), PNG_1PX);
  // The escape attempt, end to end.
  fs.writeFileSync(path.join(outside, 'secret.png'), 'SECRET-NEVER-SERVE-THIS');
  fs.symlinkSync(path.join(outside, 'secret.png'), path.join(uploads, 'innocent.png'));

  token = crypto.randomBytes(32).toString('hex');
  tokenFile = path.join(tmp, 'token');
  fs.writeFileSync(tokenFile, token, { mode: 0o600 });

  await startDaemon();
  // ⚠ IS THE DAEMON ON THIS PORT OURS? The pid formula gives 50 slots, and a
  // daemon leaked by an earlier run (a test process killed before after() could
  // fire) sits on one, answers /v1/ping happily because ping needs no token, and
  // rejects ours — which surfaces as every test 401ing and reads like a code bug.
  const own = await get({ path: '/nope' });
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(async () => {
  if (daemon) {
    const dead = new Promise((r) => daemon.once('exit', r));
    daemon.kill('SIGTERM');
    await dead;
    daemon = null;
  }
  // -L targets only OUR socket, never the default server the operator's panes use.
  try { sh('tmux', ['kill-server']); } catch { /* none was started */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** A live session on the private socket, with the state file a hook would write. */
function mkSession(suffix, cwd) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '40', 'cat']);
  madeSessions.add(name);
  // AFTER the session exists, and stamped forward: readSessionState discards a
  // state file older than the session it names (ofThisIncarnation), which is how
  // the daemon refuses to hand a new session the corpse of an old one's state.
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${suffix}`, transcript: null, cwd, ts: nowSec() + 2,
  }));
  return name;
}

// --------------------------------------------------------------- the happy path

test('an image in the uploads root is served with its type, length and nosniff', async () => {
  const r = await get({ path: path.join(uploads, 'shot.png') });
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'image/png');
  assert.equal(r.headers.get('content-length'), String(PNG_1PX.length));
  assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(r.headers.get('cache-control'), 'private, max-age=300');
  assert.ok(r.headers.get('etag'), 'a conditional GET needs something to be conditional on');
  assert.deepEqual(r.buf, PNG_1PX, 'the bytes are the file, not a re-encoding of it');
});

test('the render and claude-scratch roots serve too, each with nosniff', async () => {
  for (const [file, type] of [
    [path.join(render, 'chart.gif'), 'image/gif'],
    [path.join(scratch, 'scratch.webp'), 'image/webp'],
  ]) {
    const r = await get({ path: file });
    assert.equal(r.status, 200, file);
    assert.equal(r.headers.get('content-type'), type, file);
    assert.equal(r.headers.get('x-content-type-options'), 'nosniff', file);
  }
});

// ------------------------------------------------------------ the conditional GET

test('the etag round-trips into a 304 with no body', async () => {
  const first = await get({ path: path.join(uploads, 'shot.png') });
  const etag = first.headers.get('etag');
  const again = await get({ path: path.join(uploads, 'shot.png') },
    { headers: { 'if-none-match': etag } });
  assert.equal(again.status, 304);
  assert.equal(again.buf.length, 0, 'a 304 carries no bytes — that is the entire point');
  assert.equal(again.headers.get('etag'), etag, 'and it repeats the tag it matched');
  assert.equal(again.headers.get('cache-control'), 'private, max-age=300');
});

test('a stale If-None-Match gets the bytes, and a rewritten file changes the tag', async () => {
  const p = path.join(uploads, 'shot.png');
  const stale = await get({ path: p }, { headers: { 'if-none-match': '"0-0"' } });
  assert.equal(stale.status, 200);

  const before = (await get({ path: p })).headers.get('etag');
  // Same bytes, different mtime: the validator must still move, or an edited
  // screenshot keeps showing the old one for five minutes.
  const future = new Date(Date.now() + 10_000);
  fs.utimesSync(p, future, future);
  const after2 = await get({ path: p });
  assert.equal(after2.status, 200);
  assert.notEqual(after2.headers.get('etag'), before);
  // And the OLD tag no longer satisfies the condition.
  const conditional = await get({ path: p }, { headers: { 'if-none-match': before } });
  assert.equal(conditional.status, 200);
});

// ------------------------------------------------------------------- refusals

test('a path outside every root is 403, and says so without confirming it exists', async () => {
  for (const p of ['/etc/passwd', path.join(outside, 'secret.png'), '/root/.ssh/id_ed25519.png']) {
    const r = await get({ path: p });
    assert.equal(r.status, 403, p);
    assert.equal(r.json.error, 'that path is not in an allowed directory', p);
  }
});

test('⚠ a symlink INSIDE a root pointing out of it is 403, and the secret never ships', async () => {
  const r = await get({ path: path.join(uploads, 'innocent.png') });
  assert.equal(r.status, 403, 'the naive prefix test on the requested string passes this one');
  assert.equal(r.buf.includes('SECRET-NEVER-SERVE-THIS'), false);
});

test('.. traversal out of a root is 403', async () => {
  const r = await get({ path: `${uploads}/../../elsewhere/secret.png` });
  assert.equal(r.status, 403);
});

test('a missing file inside a root is 404', async () => {
  const r = await get({ path: path.join(uploads, 'gone.png') });
  assert.equal(r.status, 404);
  assert.equal(r.json.error, 'no such file');
});

test('a .html inside a root is 415 and is never served as text/html', async () => {
  const r = await get({ path: path.join(uploads, 'page.html') });
  assert.equal(r.status, 415);
  assert.equal(r.json.error, 'not an image this daemon will serve');
  assert.match(r.headers.get('content-type') || '', /application\/json/,
    'the refusal itself must not go out as html either');
});

test('an .svg is 415 and the refusal says why', async () => {
  const r = await get({ path: path.join(uploads, 'logo.svg') });
  assert.equal(r.status, 415);
  assert.match(r.json.error, /script/i,
    'someone will try to re-add svg; the 415 has to argue with them');
});

test('an oversized image is 413', async () => {
  const r = await get({ path: path.join(uploads, 'big.png') });
  assert.equal(r.status, 413);
  assert.equal(r.json.error, 'that image is too large to serve');
});

test('no path at all is 400, not a 500', async () => {
  const r = await get({});
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'path is required');
});

// ------------------------------------------------------------------ the auth gate

test('the route is bearer-gated like every other', async () => {
  const bare = await fetch(`${BASE}/v1/files/image?path=${encodeURIComponent(path.join(uploads, 'shot.png'))}`);
  assert.equal(bare.status, 401);
  const wrong = await fetch(
    `${BASE}/v1/files/image?path=${encodeURIComponent(path.join(uploads, 'shot.png'))}`,
    { headers: { authorization: 'Bearer not-the-token' } });
  assert.equal(wrong.status, 401);
});

// -------------------------------------------------------------- ?session= roots

test('a live session widens the roots to its cwd, absolute and relative alike', async () => {
  const name = mkSession('live', projectDir);
  const abs = await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name });
  assert.equal(abs.status, 200, 'the session cwd is a root while that session lives');
  assert.equal(abs.headers.get('content-type'), 'image/jpeg');
  assert.equal(abs.headers.get('x-content-type-options'), 'nosniff');

  const rel = await get({ path: 'shots/cwd.jpg', session: name });
  assert.equal(rel.status, 200, 'and a relative path hangs off that same cwd');
  assert.deepEqual(rel.buf, PNG_1PX);
});

test('without ?session= the same cwd file is 403 — the root is not ambient', async () => {
  const r = await get({ path: path.join(projectDir, 'shots', 'cwd.jpg') });
  assert.equal(r.status, 403);
  const rel = await get({ path: 'shots/cwd.jpg' });
  assert.equal(rel.status, 400, 'and a relative path with no cwd cannot mean the daemon\'s own');
});

test('⚠ ?session= with an unknown name does not widen anything', async () => {
  const r = await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: `${PFX}-nosuch` });
  assert.equal(r.status, 403);
  // And it must not turn into a 500 or a 404 on the way: an unknown session is
  // simply a root that was never added.
  assert.equal(r.json.error, 'that path is not in an allowed directory');
});

test('⚠ a session whose state file is gone contributes no cwd even while tmux still has it', async () => {
  // The ENDED case. Claude's SessionEnd hook removes the state file; the tmux
  // session can outlive it (and the name outlives both). No state, no cwd, no root.
  const name = mkSession('ended', projectDir);
  assert.equal((await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name })).status, 200,
    'precondition: it widened while the state file was there');
  fs.rmSync(path.join(stateDir, name), { force: true });
  const r = await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name });
  assert.equal(r.status, 403, 'the cwd root goes with the state file');
});

test('⚠ a killed session stops widening even though its state file remains', async () => {
  // The CORPSE case, which is the other half and the one a state-file-only check
  // gets wrong: a name outlives the session that owned it, and the state dir is
  // full of files for sessions that ended weeks ago.
  const name = mkSession('corpse', projectDir);
  assert.equal((await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name })).status, 200,
    'precondition: it widened while the session was live');
  sh('tmux', ['kill-session', '-t', `=${name}:`]);
  madeSessions.delete(name);
  assert.equal(fs.existsSync(path.join(stateDir, name)), true, 'the state file is deliberately left behind');
  const r = await get({ path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name });
  assert.equal(r.status, 403, 'a dead session must not keep its cwd servable');
});

test('a session cwd does not smuggle its own symlinks or traversals in either', async () => {
  const name = mkSession('escape', projectDir);
  fs.symlinkSync(path.join(outside, 'secret.png'), path.join(projectDir, 'sneaky.png'));
  assert.equal((await get({ path: path.join(projectDir, 'sneaky.png'), session: name })).status, 403);
  assert.equal((await get({ path: '../elsewhere/secret.png', session: name })).status, 403);
});

test('⚠ ?session= is held to the session-name GRAMMAR, not just to being live', async () => {
  // The only session name in the daemon that arrives outside a route path.
  // Every other one comes through `/v1/sessions/([A-Za-z0-9_][A-Za-z0-9_.-]{0,49})`
  // and is shaped by that regex before anything touches it; this one was taken
  // off the query string with nothing but a .trim().
  //
  // WHY THAT MATTERS, and why the escape below is a slash and not a `..`: the
  // name is ALSO a filename, `path.join(STATE_DIR, name)`, and the cwd read out
  // of whatever that join lands on becomes a SERVING ROOT for this route. A `..`
  // happens to be unreachable because tmux rewrites a dot to an underscore and
  // the daemon compares the echo — but that is tmux's spelling rule doing the
  // containing, by accident, and it is not a rule this daemon owns. A SLASH tmux
  // keeps verbatim (measured), so a session called `a/b` resolves a state file
  // one directory down from the state dir, somewhere no hook ever writes and
  // nothing else in this file can reach.
  const loot = path.join(outside, 'loot.png');
  fs.writeFileSync(loot, PNG_1PX);

  const evil = `${PFX}-sub/evil`;
  sh('tmux', ['new-session', '-d', '-s', evil, '-c', tmp, '-x', '80', '-y', '40', 'cat']);
  madeSessions.add(evil);
  assert.equal(sh('tmux', ['display-message', '-p', '-t', `=${evil}:`, '#{session_name}']).trim(),
    evil, 'precondition: tmux keeps a slash in a session name, so this one IS live');
  fs.mkdirSync(path.join(stateDir, `${PFX}-sub`), { recursive: true });
  fs.writeFileSync(path.join(stateDir, evil), JSON.stringify({
    state: 'idle', sessionId: 'sid-evil', transcript: null, cwd: outside, ts: nowSec() + 2,
  }));

  const r = await get({ path: loot, session: evil });
  assert.equal(r.status, 403,
    'a name outside the grammar contributes no root, however live tmux says it is');

  // The control, without which the 403 above could just as well mean "that file
  // was never servable": the SAME file, from the SAME cwd, reached through a
  // name the grammar allows.
  const ok = mkSession('lootable', outside);
  assert.equal((await get({ path: loot, session: ok })).status, 200,
    'the cwd, the file and the plumbing are all fine — it is the name that is refused');
});

// ------------------------------------------------------------------ the sweep

test('every 200 this route can produce carries nosniff', async () => {
  const name = mkSession('sweep', projectDir);
  const all = [
    { path: path.join(uploads, 'shot.png') },
    { path: path.join(render, 'chart.gif') },
    { path: path.join(scratch, 'scratch.webp') },
    { path: path.join(projectDir, 'shots', 'cwd.jpg'), session: name },
    { path: 'shots/cwd.jpg', session: name },
  ];
  for (const q of all) {
    const r = await get(q);
    assert.equal(r.status, 200, JSON.stringify(q));
    assert.equal(r.headers.get('x-content-type-options'), 'nosniff', JSON.stringify(q));
    assert.equal(r.headers.get('cache-control'), 'private, max-age=300', JSON.stringify(q));
  }
});
