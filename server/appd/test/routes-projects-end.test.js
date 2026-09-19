'use strict';
// A PROJECT'S GRACEFUL DELETE MUST NOT TYPE AT A DIALOG — the #9 hole the Wave 3
// projects block still had.
//
// `/soft-end`, `/compact` and the archive route all learned this in 3.3.x: the
// flat state file CANNOT see a dialog (`running` is the normal reading while a
// permission prompt is up, and a background agent's PreToolUse rewrites it to
// `running` while the main thread sits on the question), so the pane is read
// first and the send goes through the queue. `DELETE /v1/projects/:id?end=1`
// never learned it: it called `sendLineToPane`, a raw paste with no gate in
// front of it, and then armed the auto-end on the 200. So a member sitting on a
// folder-trust or permission dialog got the wrap-up phrase typed AT THE
// SELECTOR — where it is swallowed with no trace anywhere — and was queued for a
// kill it had never been asked to prepare for. The same pane, in the same
// second, correctly held a peer message with `blockedBy:"modal"`.
//
// THE FIXTURE: the project record is SEEDED on disk rather than created through
// the route, so this file needs no trusted cwd, no lead transcript and no
// spawn — two stand-in panes and a JSON file. One of them draws the folder-trust
// dialog and never accepts input, appending everything typed at it to a `.lost`
// sidecar. That sidecar is the assertion.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `pend-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11612 + pid%12 -> 11612-11623 (the w6 block, 11600-11649).
const PORT = 11612 + (process.pid % 12);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `pend-${process.pid}`;
const TMUX_SOCK = `huginn-pend-${process.pid}`;

const BOOT_MS = 400;
const BANNER_MS = 200;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

let tmp, stateDir, dataDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const outFor = (name) => path.join(tmp, `${name}.submitted`);
/** Where the stand-in puts bytes that arrived when nothing was reading — which
 *  is what a live dialog does with everything typed at it. */
const lostFor = (name) => `${outFor(name)}.lost`;
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

/** Start a stand-in `claude`; `trust` draws the dialog instead of a composer. */
async function startPane(suffix, { trust = false } = {}) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_TRUST=${trust ? '1' : '0'} `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${FAKE}`]);
  for (let i = 0; i < 80; i++) {
    const painted = trust
      ? /I trust this folder/.test(capture(name))
      : typing.composerText(capture(name).replace(/\n$/, '').split('\n')) !== null;
    if (painted) break;
    await wait(100);
  }
  return name;
}

/** A project record written straight into the store. */
function seedProject(slug, members) {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const project = {
    id,
    name: `Seeded ${slug}`,
    slug,
    kind: 'infra',
    status: 'active',
    brief: 'a seeded record; nothing here is spawned',
    cwd: tmp,
    // A lead that does not exist: `sessionExists` skips it, which is exactly
    // what a project whose lead has already been closed looks like.
    lead: {
      role: 'lead', name: `${slug}-lead-gone`, claudeName: `${slug}/lead`,
      sessionId: null, spawnedAt: now, endedAt: null,
    },
    members: members.map(({ role, name }) => ({
      role, name, claudeName: `${slug}/${role}`,
      sessionId: null, cwd: tmp, model: null, effort: null, mode: null,
      firstPrompt: null, spawnedAt: now, endedAt: null,
    })),
    manifest: {
      tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '',
      summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1,
    },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  };
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify(project));
  return id;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-pend-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(dataDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
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

// ------------------------------------------------------------ the fail-first

test('a member sitting on a dialog is REFUSED, not typed at, by a graceful delete', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.5.1 the phrase is pasted straight at the trust
  // dialog: `.lost` holds it, `ended` names the member, and the auto-end is
  // armed for a session that was never asked to wrap anything up.
  const dialog = await startPane('dialog', { trust: true });
  const plain = await startPane('plain');
  assert.match(capture(dialog), /I trust this folder/, 'the fixture really is on a dialog');
  const id = seedProject('pdlg', [{ role: 'dialog', name: dialog }, { role: 'plain', name: plain }]);

  const r = await api(`/v1/projects/${id}?end=1`, { method: 'DELETE', body: '{}' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal('graceful', r.body.mode);

  // Nothing went at the dialog. This is the whole assertion: the stand-in
  // appends every byte that arrives while it is not accepting input, which is
  // precisely what a live selector does with prose typed into it.
  await wait(1_500);
  assert.equal('', readOr(lostFor(dialog)),
    'the wrap-up phrase must never be typed at a dialog — it is swallowed there with no trace');
  assert.equal('', readOr(outFor(dialog)), 'and nothing was submitted in that pane');
  assert.match(capture(dialog), /I trust this folder/, 'the question is still on screen, unanswered');

  // And it is SAID, in the one place that can say it: the client that asked.
  assert.deepEqual([dialog], r.body.refused.map((x) => x.name));
  assert.equal('pdlg/dialog', r.body.refused[0].claudeName);
  assert.match(r.body.refused[0].why, /folder-trust dialog/);

  // The rest of the project is wound down all the same — one blocked member is
  // not a reason to leave eleven others running.
  assert.deepEqual([plain], r.body.ended);
  for (let i = 0; i < 60 && !readOr(outFor(plain)).includes('prepare to end'); i++) await wait(100);
  assert.match(readOr(outFor(plain)), /Finish outstanding items/, 'the ordinary member got the phrase');

  // The record is gone either way: DELETE deletes.
  assert.equal(404, (await api(`/v1/projects/${id}`)).status);
});

test('the wrap-up phrase rides the send queue, so a person\'s draft still holds it', async () => {
  // The other half of "the same path /soft-end uses": not merely a pane check
  // before a raw paste, but the QUEUE — which is where every other gate lives.
  // A composer with somebody's unsent words in it is the one the draft guard
  // added in 3.5.0, and a raw `sendLineToPane` walked straight past it.
  const name = await startPane('draft');
  const id = seedProject('pdft', [{ role: 'draft', name }]);

  // Be the person, through the live view — the draft guard keys on relayed
  // keystrokes, never on a capture (decision 57).
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'half a thought of my own' }),
  });

  const r = await api(`/v1/projects/${id}?end=1`, { method: 'DELETE', body: '{}' });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.deepEqual([name], r.body.ended, 'it was accepted — it is queued, not refused');
  assert.deepEqual([], r.body.refused);

  await wait(1_500);
  const st = (await api(`/v1/sessions/${name}/typing`)).body;
  assert.equal('draft', st.blockedBy, 'held behind the draft, which a raw paste would have merged with');
  assert.equal(1, st.queued);
  assert.equal('', readOr(outFor(name)), 'and nothing was submitted on top of their words');
  assert.equal('half a thought of my own',
    typing.composerText(capture(name).replace(/\n$/, '').split('\n')),
    'their draft is exactly as they left it');
});
