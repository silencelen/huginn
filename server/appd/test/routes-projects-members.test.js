'use strict';
// MEMBERSHIP FOR SESSIONS THIS DAEMON DID NOT SPAWN — the Wave 3 leftover.
//
// A cluster is not always born as one. The ordinary case is a session the owner
// already has open — the scratch shell that turned into the firmware work —
// which belongs in the project beside the ones that were spawned: on the
// dashboard, in the peer relay, in the graceful end. Until 3.5.2 the only way
// into a project was `POST /spawn`, so those sessions could never join one and a
// member that had been dropped could never come back.
//
// THE THREE PROMISES UNDER TEST, each a way this goes wrong while the screen
// still looks right:
//   * ADOPT DOES NOT LAUNCH and DROP DOES NOT END. Both are edits to a record;
//     a membership verb that quietly started or killed a `claude` would be the
//     surprise the whole block exists to avoid.
//   * an adopted member's PEER NAME is read off the native registry, never
//     invented — `claudeName` is what `claude --name` was given at launch, and
//     writing `<slug>/<role>` over a session that never had it would put an
//     address in the record that nothing can deliver to.
//   * a member's TMUX NAME IS NOT ITS OWN. The record stores members by it and
//     the peer registry is joined back through it, so a rename orphans the
//     session from its project silently — which is why the rename route now
//     answers 409 and NAMES the project.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `pmem-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const projectsLib = require('../lib/projects');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11624 + pid%12 -> 11624-11635 (the w6 block, 11600-11649).
const PORT = 11624 + (process.pid % 12);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `pmem-${process.pid}`;
const TMUX_SOCK = `huginn-pmem-${process.pid}`;

let tmp, stateDir, dataDir, claudeDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function liveNames() {
  try { return sh('tmux', ['ls', '-F', '#S']).split('\n').filter(Boolean); } catch { return []; }
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

/** An inert pane. Nothing here needs a composer — membership is a record edit. */
function startPane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, 'sleep 600']);
  return name;
}

/** What huginn-claude-title writes; the adopt route reads sessionId and cwd. */
function writeState(name, { sessionId, cwd = tmp }) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId, transcript: null, cwd, ts: Date.now(),
  }));
}

/** A row in ~/.claude/sessions — where a session's REAL peer name lives. */
function writeNativeRow(sessionId, name) {
  const dir = path.join(claudeDir, 'sessions');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${sessionId}.json`), JSON.stringify({
    sessionId, name, nameSource: 'flag', entrypoint: 'cli', kind: 'interactive',
    status: 'idle', pid: process.pid, procStart: 1,
  }));
}

/** A project record written straight into the store — no lead transcript, no
 *  trusted cwd, no spawn. `tag` is here so publicProject can be checked. */
function seedProject(slug, { tag = 'seeded-tag-abc' } = {}) {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify({
    id,
    name: `Seeded ${slug}`,
    slug,
    kind: 'hardware',
    status: 'active',
    brief: 'a seeded record; nothing here is spawned',
    cwd: tmp,
    lead: {
      role: 'lead', name: `${slug}-lead-gone`, claudeName: `${slug}/lead`,
      sessionId: null, spawnedAt: now, endedAt: null,
    },
    members: [],
    manifest: {
      tag, rev: 1, receivedAt: now, type: 'cluster', scope: '',
      summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1,
    },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  }));
  return id;
}

const stored = (id) => JSON.parse(fs.readFileSync(path.join(dataDir, 'projects', `${id}.json`), 'utf8'));
const adopt = (id, body) => api(`/v1/projects/${id}/members`, { method: 'POST', body: JSON.stringify(body) });
const drop = (id, role) => api(`/v1/projects/${id}/members/${role}`, { method: 'DELETE' });

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-pmem-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, claudeDir]) fs.mkdirSync(d);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: tmp,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/projects');
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

// ------------------------------------------------------------------- adopt

test('a live session is adopted into a project under a role, and the record moves', async () => {
  const name = startPane('adopt');
  writeState(name, { sessionId: 'sid-adopt' });
  writeNativeRow('sid-adopt', 'someone/else');
  const id = seedProject('adp');
  const before = stored(id).rev;

  const r = await adopt(id, { role: 'firmware', name });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(true, r.body.ok);
  assert.equal('firmware', r.body.member.role);
  assert.equal(name, r.body.member.name);
  assert.equal('sid-adopt', r.body.member.sessionId, 'the join key, read off the state file');
  assert.equal(tmp, r.body.member.cwd);
  assert.equal(null, r.body.member.firstPrompt, 'nothing was sent to it — this is a record edit');
  // ⚠ THE PEER NAME IS READ, NOT INVENTED. This session registered as
  // `someone/else`; writing `adp/firmware` over it would put an address in the
  // record that nothing can deliver to.
  assert.equal('someone/else', r.body.member.claudeName);

  assert.equal(before + 1, r.body.project.rev, 'rev moved, which is what every editor checks against');
  assert.deepEqual(['firmware'], stored(id).members.map((x) => x.role), 'and it survived the trip through the file');

  // The session is untouched: still there, still called what it was.
  assert.ok(liveNames().includes(name), 'adopting launches nothing and kills nothing');

  // ⚠ publicProject STILL STRIPS THE TAG. It is the entire control that keeps a
  // planted `huginn-project` block from being acted on as the lead's proposal,
  // and every body carrying a project has to go through the projection — this
  // one included.
  assert.equal('seeded-tag-abc', stored(id).manifest.tag, 'the store keeps it');
  assert.equal(false, 'tag' in r.body.project.manifest, 'and the wire never sees it');
  assert.equal(false, JSON.stringify(r.body).includes('seeded-tag-abc'));

  // And it shows up where membership is read.
  const detail = await api(`/v1/projects/${id}`);
  assert.equal(200, detail.status);
  assert.ok(detail.body.live.some((x) => x.role === 'firmware' && x.name === name));
});

test('with no name, the role\'s conventional session is what gets adopted', async () => {
  // Re-adopting something spawned here and then dropped needs no arguments:
  // `<slug>-<role>` is the only name this project would ever have given it.
  const id = seedProject('cnv');
  const name = startPane('placeholder');
  sh('tmux', ['rename-session', '-t', `=${name}`, 'cnv-docs']);
  madeSessions.add('cnv-docs');

  const r = await adopt(id, { role: 'docs' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal('cnv-docs', r.body.member.name);
  // No state file and no registry row: the label is the conventional name, and
  // the join key is honestly null rather than a guess.
  assert.equal('cnv/docs', r.body.member.claudeName);
  assert.equal(null, r.body.member.sessionId);
});

test('a session that is already in a project cannot be adopted into another', async () => {
  // Two projects both believing they own one session is a dashboard that
  // double-counts it and a graceful end that winds it down twice.
  const name = startPane('taken');
  const home = seedProject('own');
  const other = seedProject('thief');
  assert.equal(201, (await adopt(home, { role: 'worker', name })).status);

  const r = await adopt(other, { role: 'worker', name });
  assert.equal(409, r.status);
  assert.match(r.body.error, /already the worker session/);
  assert.match(r.body.error, /"Seeded own"/, 'and it names the project holding it');
  assert.deepEqual([], stored(other).members, 'nothing was written');
});

test('adopting refuses what it cannot do: no such session, a taken role, the lead', async () => {
  const id = seedProject('ref');
  const name = startPane('ref');
  assert.equal(201, (await adopt(id, { role: 'docs', name })).status);

  const gone = await adopt(id, { role: 'other', name: `${PFX}-not-a-session` });
  assert.equal(404, gone.status, 'a record naming a session that is not there is a row that can never resolve');
  assert.match(gone.body.error, /no session called/);

  const taken = await adopt(id, { role: 'docs', name: startPane('second') });
  assert.equal(400, taken.status);
  assert.match(taken.body.error, /already a docs session/);

  const lead = await adopt(id, { role: 'lead', name: startPane('third') });
  assert.equal(400, lead.status, '"lead" is the lead session\'s own role');

  const badRole = await adopt(id, { role: 'Not A Role', name });
  assert.equal(400, badRole.status);

  assert.deepEqual(['docs'], stored(id).members.map((x) => x.role), 'and none of those wrote anything');
});

// -------------------------------------------------------------------- drop

test('dropping a member leaves the session RUNNING, and says so', async () => {
  // ⚠ "drop" and "end" are one keystroke apart in every client. This route does
  // exactly one of them, and the body says which.
  const name = startPane('dropme');
  const id = seedProject('drp');
  assert.equal(201, (await adopt(id, { role: 'worker', name })).status);
  const before = stored(id).rev;

  const r = await drop(id, 'worker');
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(false, r.body.ended, 'in the body, not only in the documentation');
  assert.equal(name, r.body.dropped.name);
  assert.equal(before + 1, r.body.project.rev);
  assert.deepEqual([], stored(id).members);
  assert.ok(liveNames().includes(name), 'the session is still there, unadopted, exactly where it was');
  assert.equal(false, 'tag' in r.body.project.manifest, 'publicProject on this body too');

  // Dropped and re-adopted is a round trip, not a one-way door.
  const again = await adopt(id, { role: 'worker', name });
  assert.equal(201, again.status, JSON.stringify(again.body));
  assert.deepEqual(['worker'], stored(id).members.map((x) => x.role));

  assert.equal(404, (await drop(id, 'nobody')).status, 'a role this project has no session for');
  assert.equal(409, (await drop(id, 'lead')).status, 'the lead is the project — delete the project instead');
  assert.equal(404, (await api(`/v1/projects/${crypto.randomUUID()}/members`,
    { method: 'POST', body: '{}' })).status, 'a project id that names nothing');
});

// ------------------------------------------------------------------ rename

test('RENAMING a project member is refused, with the project named in the error', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.5.1 this rename succeeds, and everything that
  // knows this session goes quiet at once: the record still names the old
  // session, `joinMembers` cannot find it so the dashboard row reads "not
  // present" forever, and `/message` answers 409 about a session sitting right
  // there. Nothing logs it and no screen shows it.
  const name = startPane('named');
  const id = seedProject('rnm');
  assert.equal(201, (await adopt(id, { role: 'radio', name })).status);

  const to = `${PFX}-renamed`;
  const r = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(409, r.status, JSON.stringify(r.body));
  assert.match(r.body.error, /"Seeded rnm"/, 'the project is NAMED — "drop it first" is only actionable with a which');
  assert.match(r.body.error, /radio session/);
  assert.match(r.body.error, /members/, 'and the way out is in the sentence');
  assert.ok(liveNames().includes(name), 'the session still has the name the project knows it by');
  assert.equal(false, liveNames().includes(to));

  // A rename to the name it already has is a legitimate no-op — the desktop's
  // rename field answers with whatever is in it — and must not be refused.
  const same = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name }) });
  assert.equal(200, same.status, JSON.stringify(same.body));

  // Drop it, and the name is its own again.
  assert.equal(200, (await drop(id, 'radio')).status);
  const after = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(200, after.status, JSON.stringify(after.body));
  madeSessions.add(to);
  assert.ok(liveNames().includes(to));
});

test('the LEAD\'s rename 409 names a fix that works (L6)', async () => {
  // ⚠⚠ THE TWO-HOP DEAD END. The member sentence — "drop it from the project
  // first (DELETE /v1/projects/<id>/members/<role>)" — was said to the lead too,
  // and that route answers the lead with its own 409: "the lead is the project —
  // delete the project instead". So the only instruction the daemon gave pointed
  // at a door the daemon holds shut, and a reader following it learns that by
  // being refused twice. A refusal that names a fix has to name one that works.
  const name = startPane('leadpane');
  const id = seedProject('lrn');
  const rec = stored(id);
  rec.lead.name = name;
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify(rec));

  const to = `${PFX}-leadmoved`;
  const r = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(409, r.status, JSON.stringify(r.body));
  assert.match(r.body.error, /"Seeded lrn"/, 'the project is still named');
  assert.ok(!/members\/lead/.test(r.body.error),
    `it must not point at the route that refuses the lead: ${r.body.error}`);
  assert.match(r.body.error, new RegExp(`DELETE /v1/projects/${id}\\b`),
    `the fix is deleting the PROJECT, and the id is in the sentence: ${r.body.error}`);
  assert.ok(liveNames().includes(name), 'and nothing moved');
  assert.equal(false, liveNames().includes(to));

  // The fix in the sentence is a fix: delete the project (the session keeps
  // running), and the rename goes through.
  assert.equal(200, (await api(`/v1/projects/${id}`, { method: 'DELETE' })).status);
  assert.ok(liveNames().includes(name), 'deleting the project left the session alone');
  const after = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(200, after.status, JSON.stringify(after.body));
  madeSessions.add(to);
});

test('a session in no project renames exactly as it always did', async () => {
  // The guard must cost nothing to everything else on this host.
  const name = startPane('free');
  const to = `${PFX}-free2`;
  const r = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(200, r.status, JSON.stringify(r.body));
  madeSessions.add(to);
  assert.ok(liveNames().includes(to));
  assert.equal(projectsLib.LEAD_ROLE, 'lead', 'the role name this file keeps asserting about');
});
