'use strict';
// NOTHING DESTRUCTIVE ON ONE LOOK: the projects reconcile against a tmux that
// lies the way a loaded host's did.
//
// ⚠ THE INCIDENT (2026-11-06). In one reconcile tick the daemon dropped every
// member of five projects and archived all five ("the lead session is gone").
// All 23 sessions were alive the whole time, on a tmux server that had been up
// for ten days. One listing had failed with tmux's no-server words while a
// backup loaded the host, `systemctl show` had failed too, the unit's pid read
// as "none" for that "could not tell", and listSessions answered an EMPTY HOST
// — without a line in the log. The records came back from a backup. The same
// blast happened on 2026-10-27 through a swept socket.
//
// What this file holds, against a real daemon on a private tmux server:
//   * no server, and a systemd that cannot be asked: the list is UNKNOWN (503),
//     said once in the log, and no project is touched;
//   * no server, and systemd saying the unit has no main process: an empty host,
//     as at a fresh boot, and the log says why;
//   * a listing that leaves a project's sessions out: nothing dropped or
//     archived, and the next listing that has them forgets the absence;
//   * a session that really ended is still dropped, and a lead that really ended
//     still archives its project: after the window, on tmux's own word;
//   * a probe that cannot reach tmux holds the change;
//   * the unit alive with its socket gone: one SIGUSR1 brings the socket back,
//     and the list is never empty meanwhile.
//
// SAFETY: a PRIVATE tmux socket (`-L`); every session runs an inert `cat` and
// goes with the server in after(). The tmux and the systemctl the DAEMON runs
// are stand-ins (test/tmux-shim.js) that pass through to the real tmux unless
// told to lie; the only pid the systemctl stand-in is ever given is this file's
// own private tmux server. A port the kernel just handed out, scratch paths, an
// empty scratch ~/.claude, a stand-in `claude`.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { lyingTmux, REAL_TMUX } = require('./tmux-shim');

const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');
const TMUX_SOCK = `huginn-reconcile-${process.pid}`;
const PFX = `rcn-${process.pid}`;
// The test knobs: a reconcile every quarter second, written off after 1.5 s.
const RECONCILE_MS = 250;
const WINDOW_MS = 1500;
// How long to wait for the daemon to NOTE an absence — or to act on it, which is
// what 3.17.2 did on the first listing that lacked a session. Generous on
// purpose: that daemon reconciled every 60 s and ignores RECONCILE_MS, and it
// is its write this has to catch. The fixed daemon notes it within a tick.
const NOTICE_MS = 70_000;

let tmp, dataDir, token, daemon, base, logFile, shim;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const tmux = (args) => execFileSync(REAL_TMUX, ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });
const daemonLog = () => { try { return fs.readFileSync(logFile, 'utf8'); } catch { return ''; } };
/** How many times the daemon has said [re] so far. */
const count = (re) => (daemonLog().match(new RegExp(re.source, 'g')) || []).length;
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

async function until(fn, ms, what) {
  const deadline = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${what}\n--- daemon log (tail) ---\n${daemonLog().slice(-4000)}`);
    }
    await wait(50);
  }
}

/** A port the kernel just handed out, never a computed one (see routes-tls.test.js). */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => resolve(port)); });
  });
}

async function api(pathname) {
  const res = await fetch(`${base}${pathname}`, { headers: { authorization: `Bearer ${token}` } });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/**
 * A project whose lead and members are live, inert tmux sessions, its record
 * seeded on disk the way routes-projects-end.test.js does it. `bytes` is the
 * record as written: the daemon re-serialises on every write, so "untouched"
 * is byte for byte.
 */
function seedProject(tag, roles = ['docs']) {
  const slug = `${PFX}-${tag}`;
  const name = (role) => `${slug}-${role}`;
  for (const role of ['lead', ...roles]) tmux(['new-session', '-d', '-s', name(role), '-c', tmp, 'cat >/dev/null']);
  const now = Math.floor(Date.now() / 1000);
  const id = crypto.randomUUID();
  const record = {
    id, name: `Seeded ${tag}`, slug, kind: 'infra', status: 'active', brief: 'a seeded record', cwd: tmp,
    lead: { role: 'lead', name: name('lead'), claudeName: `${slug}/lead`, sessionId: null, spawnedAt: now, endedAt: null },
    members: roles.map((role) => ({
      role, name: name(role), claudeName: `${slug}/${role}`, sessionId: null, cwd: tmp,
      model: null, effort: null, mode: null, firstPrompt: null, spawnedAt: now, endedAt: null,
    })),
    manifest: {
      tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '',
      summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1,
    },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  };
  const file = path.join(dataDir, 'projects', `${id}.json`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(record));
  return {
    slug, file, name,
    bytes: fs.readFileSync(file, 'utf8'),
    now: () => fs.readFileSync(file, 'utf8'),
    read: () => JSON.parse(fs.readFileSync(file, 'utf8')),
  };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-reconcile-'));
  dataDir = path.join(tmp, 'data');
  for (const d of ['state', 'data', 'claude', 'home']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  shim = lyingTmux(path.join(tmp, 'shim'), { sock: TMUX_SOCK });
  shim.set('unit', ['fail']);
  // The server is up before the daemon, as on a host that has run for days, and
  // a session outside every project keeps it up while the tests end theirs.
  tmux(['new-session', '-d', '-s', `${PFX}-keep`, '-c', tmp, 'cat >/dev/null']);

  logFile = path.join(tmp, 'daemon.log');
  const fd = fs.openSync(logFile, 'a');
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  daemon = spawn(process.execPath, [DAEMON], {
    env: {
      ...process.env,
      ...shim.env,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      HUGINN_APPD_RECONCILE_MS: String(RECONCILE_MS),
      HUGINN_APPD_ABSENT_CONFIRM_MS: String(WINDOW_MS),
    },
    stdio: ['ignore', fd, fd],
  });
  fs.closeSync(fd);
  for (let i = 0; i < 300; i++) {
    try {
      const r = await fetch(`${base}/v1/ping`, { headers: { authorization: `Bearer ${token}` } });
      if (r.status === 200) break;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await wait(100);
  }
});

after(async () => {
  // Waited for: its reconcile writes the registry every quarter second, and a
  // write landing mid-rmSync leaves the scratch dir behind.
  if (daemon && daemon.exitCode === null) {
    const gone = new Promise((r) => { daemon.on('exit', r); setTimeout(r, 5000); });
    daemon.kill('SIGTERM');
    await gone;
  }
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('no server, and systemd cannot be asked: the list is UNKNOWN, said once, and no project is touched', async () => {
  const p = seedProject('a');
  const unknown = /the session list is UNKNOWN, not empty/;
  const answers = count(/the session list answers again/);
  shim.set('unit', ['fail']);
  shim.set('list-noserver');
  try {
    const r = await api('/v1/sessions');
    assert.equal(r.status, 503,
      `an unobserved host was answered ${r.status} ${JSON.stringify(r.body)}: on 2026-11-06 that [] archived five projects`);
    await until(() => unknown.test(daemonLog()), 5000, 'the decision in the log');
    assert.match(daemonLog(), /tmux: no server answered \(error connecting to .*\(No such file or directory\)\), and systemd could not say whether huginn-tmux\.service holds one \(systemctl failed: Failed to connect to bus: Connection timed out\)/);
    // Reconcile after reconcile, well past the window, on the same failure.
    await wait(WINDOW_MS + 6 * RECONCILE_MS);
    assert.equal(p.now(), p.bytes, 'the project record was rewritten on a list nobody could read');
    assert.equal(count(unknown), 1, 'said once per transition, not once per look');
  } finally {
    shim.clear('list-noserver');
  }
  await until(() => count(/the session list answers again/) > answers, 5000, 'the list to answer again');
  const back = await api('/v1/sessions');
  assert.equal(back.status, 200);
  assert.ok(back.body.sessions.some((s) => s.name === p.name('lead')), 'and every session was there all along');
  assert.equal(p.now(), p.bytes);
});

test('no server, and systemd says the unit has no main process: an empty host, as at a fresh boot', async () => {
  const said = count(/— the host has no sessions/);
  shim.set('unit', ['0']);
  shim.set('list-noserver');
  try {
    const r = await api('/v1/sessions');
    assert.equal(r.status, 200, JSON.stringify(r.body));
    assert.deepEqual(r.body.sessions, [], 'tmux and systemd both said so: that is an observation');
    await until(() => count(/— the host has no sessions/) > said, 5000, 'the reason in the log');
    assert.match(daemonLog(), /tmux: no server \(error connecting to .*\(No such file or directory\)\), and huginn-tmux\.service has no main process — the host has no sessions/);
  } finally {
    shim.clear('list-noserver');
    shim.set('unit', ['fail']);
  }
});

test('ONE listing without a project\'s sessions drops nothing; the next one with them forgets the absence', async () => {
  const p = seedProject('c');
  const missing = new RegExp(`project ${esc(p.slug)}: (the lead|docs) \\(${esc(p.slug)}-(lead|docs)\\) is missing from the session list`);
  const back = new RegExp(`project ${esc(p.slug)}: (the lead|docs) \\(${esc(p.slug)}-(lead|docs)\\) is back in the session list`);
  shim.set('list-hide', [`${p.slug}-`]);
  try {
    // Until the daemon notes the absence, or acts on it the way 3.17.2 did.
    await until(() => count(missing) >= 2 || p.now() !== p.bytes, NOTICE_MS, 'the absence to be noted');
  } finally {
    shim.clear('list-hide');
  }
  assert.equal(p.now(), p.bytes, 'a project was rewritten on a listing that merely left it out');
  await until(() => count(back) >= 2, 5000, 'the next listing to clear the absence');

  // Left out again: a NEW absence, noted afresh, because the first was forgotten.
  shim.set('list-hide', [`${p.slug}-`]);
  try {
    await until(() => count(missing) >= 4, 5000, 'the second absence to be noted afresh');
  } finally {
    shim.clear('list-hide');
  }
  await until(() => count(back) >= 4, 5000, 'and cleared again');
  assert.equal(p.now(), p.bytes, 'nothing was ever written');
});

test('a session that really ended is dropped, and a lead that really ended archives — after the window, on tmux\'s word', async () => {
  const p = seedProject('d', ['docs', 'qa']);
  const noted = new RegExp(`project ${esc(p.slug)}: docs \\(${esc(p.slug)}-docs\\) is missing from the session list`);
  const endedAt = Date.now();
  tmux(['kill-session', '-t', `=${p.name('docs')}`]);
  await until(() => noted.test(daemonLog()) || p.now() !== p.bytes, NOTICE_MS, 'the ended session to be noticed');
  assert.ok(p.read().members.some((m) => m.role === 'docs'), 'dropped on the FIRST listing that lacked it');

  const dropped = await until(() => {
    const r = p.read();
    return !r.members.some((m) => m.role === 'docs') && r;
  }, 10_000, 'docs to be dropped');
  assert.ok(Date.now() - endedAt >= WINDOW_MS, 'not before the window');
  assert.equal(dropped.status, 'active', 'the lead is alive, and so is the project');
  assert.deepEqual(dropped.members.map((m) => m.role), ['qa'], 'only the member that ended');
  assert.match(daemonLog(), new RegExp(`project ${esc(p.slug)}: dropped docs \\(session gone: missing from the session list for at least 1\\.5 s, and tmux has no such session\\)`));

  tmux(['kill-session', '-t', `=${p.name('lead')}`]);
  const archived = await until(() => {
    const r = p.read();
    return r.status === 'archived' && r;
  }, 10_000, 'the project to be archived');
  assert.equal(archived.endedReason, 'the lead session is gone');
  assert.match(daemonLog(), new RegExp(`project ${esc(p.slug)}: archived — the lead session is gone \\(missing`));
});

test('missing for the window but the probe cannot reach tmux: the change is HELD', async () => {
  const p = seedProject('e');
  const held = new RegExp(`project ${esc(p.slug)}: (the lead|docs) \\([^)]*\\) has been missing for [0-9]+ s, `
    + 'but tmux would not confirm it is gone \\(error connecting to .*\\(Connection refused\\)\\) — holding');
  const back = new RegExp(`project ${esc(p.slug)}: (the lead|docs) \\([^)]*\\) is back in the session list`);
  shim.set('has-refuse', [`${p.slug}-`]);
  shim.set('list-hide', [`${p.slug}-`]);
  try {
    await until(() => count(held) >= 2 || p.now() !== p.bytes, NOTICE_MS, 'the probe to be refused');
    assert.equal(p.now(), p.bytes, 'written off on a probe that never reached tmux');
    await wait(6 * RECONCILE_MS);
    assert.equal(p.now(), p.bytes, 'and still held, pass after pass');
    assert.equal(count(held), 2, 'said once per role, not once per pass');
  } finally {
    shim.clear('list-hide');
    shim.clear('has-refuse');
  }
  await until(() => count(back) >= 2, 5000, 'the absence to clear once tmux answers');
  assert.equal(p.now(), p.bytes);
});

test('the unit alive and its socket gone: one SIGUSR1 brings it back, and the list is never empty meanwhile', async () => {
  const p = seedProject('f');
  const serverPid = Number(tmux(['display-message', '-p', '#{pid}']).trim());
  const sockPath = tmux(['display-message', '-p', '#{socket_path}']).trim();
  assert.ok(serverPid > 0 && fs.existsSync(sockPath), `${serverPid} ${sockPath}`);
  // ⚠ This file's own private server, and nothing else, is ever named here.
  shim.set('unit', [String(serverPid)]);
  fs.unlinkSync(sockPath);
  try {
    const first = await api('/v1/sessions');
    assert.ok(first.status === 503 || (first.status === 200 && first.body.sessions.length > 0),
      `a swept socket under a living server was answered ${first.status} ${JSON.stringify(first.body)}`);
    await until(async () => {
      const r = await api('/v1/sessions');
      return r.status === 200 && r.body.sessions.some((s) => s.name === p.name('lead'));
    }, 10_000, 'the list to come back whole');
    assert.ok(fs.existsSync(sockPath), 'the server recreated its socket');
    assert.match(daemonLog(), /asked it to recreate the socket \(SIGUSR1\)/);
    assert.equal(p.now(), p.bytes, 'and no project noticed');
  } finally {
    shim.set('unit', ['fail']);
    // A daemon that never signals leaves the socket gone: put it back, so after()
    // can stop the server.
    if (!fs.existsSync(sockPath)) {
      try { process.kill(serverPid, 'SIGUSR1'); } catch { /* gone */ }
      await wait(500);
    }
  }
});
