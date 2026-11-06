'use strict';
// "No server" is not "no session": the per-session reads, the kill and the
// create route on a host whose tmux says there is no server while one is
// running (2026-11-06).
//
// That night one listing failed with tmux's no-server words while a backup
// loaded the host and all 23 sessions ran, and `systemctl show` failed too; the
// listing read the pair as an empty host and five projects were archived. The
// per-session reads made the same inference without asking systemd at all, so
// under the same conditions:
//   * a session-gated route answered 404 for a live session — both clients
//     eject the viewer with "Session <name> ended" on that answer;
//   * the send queue failed every waiting message as "the session it was sent
//     to has ended";
//   * a kill that never reached tmux cleared the session's state file and its
//     restore-registry row, so the conversation lost its tab now and its restore
//     at the next reboot;
//   * a create went on to `tmux new-session`, which starts a server wherever it
//     cannot connect to one — the empty second server of 2026-10-27.
// Now "absent" needs a server saying "can't find session", or tmux's no-server
// words AND systemd saying the unit has no main process; everything else is
// "cannot tell": a 503, a held send, a kill that touches nothing, no server.
//
// SAFETY: a PRIVATE tmux socket (`-L`); every session runs an inert `cat` (the
// stand-in `claude` included) and goes with the server in after(). The tmux and
// the systemctl the DAEMON runs are the stand-ins of test/tmux-shim.js; the only
// pid the systemctl stand-in is ever given is this file's own private tmux
// server. A port the kernel just handed out, scratch paths, an empty ~/.claude.

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
const TMUX_SOCK = `huginn-unknown-${process.pid}`;
const PFX = `unk-${process.pid}`;

let tmp, dataDir, stateDir, token, daemon, base, logFile, shim;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const tmux = (args) => execFileSync(REAL_TMUX, ['-L', TMUX_SOCK, ...args], { encoding: 'utf8' });
const alive = (name) => { try { tmux(['has-session', '-t', `=${name}`]); return true; } catch { return false; } };
const daemonLog = () => { try { return fs.readFileSync(logFile, 'utf8'); } catch { return ''; } };
const registry = () => {
  try { return JSON.parse(fs.readFileSync(path.join(dataDir, 'active-sessions.json'), 'utf8')).sessions || {}; } catch { return {}; }
};

/** A port the kernel just handed out, never a computed one (see routes-tls.test.js). */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => resolve(port)); });
  });
}

async function api(pathname, init = {}) {
  const res = await fetch(`${base}${pathname}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** A session made the way a person makes one, through the daemon: registered, marked as starting. */
async function created(suffix) {
  const name = `${PFX}-${suffix}`;
  const r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state: 'idle', sessionId: `sid-${name}`, transcript: null, cwd: tmp, ts: Date.now(),
  }));
  return name;
}

/** Make every named subcommand the daemon runs fail as if there were no server. */
function noServerFor(...subs) { shim.set('noserver', subs); }

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-unknown-'));
  dataDir = path.join(tmp, 'data');
  stateDir = path.join(tmp, 'state');
  for (const d of [dataDir, stateDir, path.join(tmp, 'claude'), path.join(tmp, 'home')]) fs.mkdirSync(d);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  shim = lyingTmux(path.join(tmp, 'shim'), { sock: TMUX_SOCK });
  shim.set('unit', ['fail']);
  // The server is up before the daemon, as on a host that has run for days.
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
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
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
  shim.clear('noserver');
  if (daemon && daemon.exitCode === null) {
    const gone = new Promise((r) => { daemon.on('exit', r); setTimeout(r, 5000); });
    daemon.kill('SIGTERM');
    await gone;
  }
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a live session tmux cannot see, on a host systemd will not vouch for, is 503 — never 404 "Session ended"', async () => {
  const name = await created('screen');
  noServerFor('display-message', 'capture-pane');
  try {
    const screen = await api(`/v1/sessions/${name}/screen`);
    assert.equal(screen.status, 503, `GET /screen answered ${screen.status} ${JSON.stringify(screen.body)}: `
      + 'a 404 here ejects the viewer with "Session ended" from a session that is running');
    const keys = await api(`/v1/sessions/${name}/keys`, {
      method: 'POST', body: JSON.stringify({ text: 'hello', keys: ['Enter'] }),
    });
    assert.equal(keys.status, 503, JSON.stringify(keys.body));
  } finally {
    shim.clear('noserver');
  }
  assert.equal(alive(name), true, 'the session was running the whole time');
  assert.equal((await api(`/v1/sessions/${name}/screen`)).status, 200, 'and it answers again by itself');
});

test('a queued message is HELD while tmux cannot be observed, never failed as "the session has ended"', async () => {
  const name = await created('queue');
  // A new session's messages wait for its composer (the startup gate), and the
  // stand-in `claude` never draws one: this one stays queued.
  const sent = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'are you there', keys: ['Enter'] }),
  });
  assert.equal(sent.status, 200, JSON.stringify(sent.body));
  assert.equal(sent.body.delivered, false, 'held by the startup gate');
  noServerFor('display-message');
  try {
    await wait(2500);   // six polls of the queue, every one of them unable to see the session
  } finally {
    shim.clear('noserver');
  }
  const typed = await api(`/v1/sessions/${name}/typing`);
  assert.equal(typed.status, 200, JSON.stringify(typed.body));
  assert.equal(typed.body.queued, 1, `the message was taken off the queue: ${JSON.stringify(typed.body)}`);
  assert.doesNotMatch(daemonLog(), new RegExp(`typing: ${name}: delivery failed: the session it was sent to has ended`),
    'failed as "ended" for a session nobody could see');
  assert.equal(alive(name), true);
});

test('a kill tmux could not confirm leaves the session\'s state and its restore entry alone', async () => {
  const name = await created('kill');
  assert.ok(registry()[name], 'registered at birth');
  noServerFor('kill-session');
  let r;
  try {
    r = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  } finally {
    shim.clear('noserver');
  }
  assert.equal(r.status, 503, `DELETE answered ${r.status} ${JSON.stringify(r.body)} for a kill that never reached tmux`);
  assert.equal(alive(name), true, 'nothing was ended');
  assert.equal(fs.existsSync(path.join(stateDir, name)), true, 'its state file — state word, session id, transcript — was cleared');
  assert.ok(registry()[name], 'and its restore entry was dropped: the next reboot would not bring it back');

  // The control: once tmux answers, the same DELETE ends it and tidies up.
  const ok = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(ok.status, 200, JSON.stringify(ok.body));
  assert.equal(alive(name), false);
  assert.equal(fs.existsSync(path.join(stateDir, name)), false);
  assert.equal(registry()[name], undefined);
});

test('a peer message to a member tmux cannot see is 503, not "is not running"', async () => {
  const slug = `${PFX}-proj`;
  for (const role of ['lead', 'docs']) tmux(['new-session', '-d', '-s', `${slug}-${role}`, '-c', tmp, 'cat >/dev/null']);
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const member = (role) => ({
    role, name: `${slug}-${role}`, claudeName: `${slug}/${role}`, sessionId: null, cwd: tmp,
    model: null, effort: null, mode: null, firstPrompt: null, spawnedAt: now, endedAt: null,
  });
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify({
    id, name: 'Seeded', slug, kind: 'infra', status: 'active', brief: 'a seeded record', cwd: tmp,
    lead: { role: 'lead', name: `${slug}-lead`, claudeName: `${slug}/lead`, sessionId: null, spawnedAt: now, endedAt: null },
    members: [member('docs')],
    manifest: { tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '', summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1 },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  }));
  noServerFor('display-message');
  let r;
  try {
    r = await api(`/v1/projects/${id}/message`, {
      method: 'POST', body: JSON.stringify({ from: 'lead', to: 'docs', text: 'status?' }),
    });
  } finally {
    shim.clear('noserver');
  }
  assert.equal(r.status, 503, `answered ${r.status} ${JSON.stringify(r.body)} about a member that is running`);
  assert.equal(alive(`${slug}-docs`), true);
});

test('a create on a host whose tmux says no server and systemd will not say: 503, and no server is started', async () => {
  const name = `${PFX}-new`;
  // `tmux ls` is the server check before a create; the session itself is free.
  noServerFor('ls');
  let r;
  try {
    r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
  } finally {
    shim.clear('noserver');
  }
  assert.equal(r.status, 503, `answered ${r.status} ${JSON.stringify(r.body)}: \`new-session\` starts a server `
    + 'wherever it cannot reach one — the empty second server of 2026-10-27');
  assert.match(r.body.error, /not starting a second one/);
  assert.equal(alive(name), false, 'nothing was created');
  assert.match(daemonLog(), /systemd could not say whether huginn-tmux\.service holds one \(systemctl failed: .*\) — not starting a second server/);
});

test('a create while the unit\'s socket is gone: SIGUSR1, a 503, and the retry lands on the SAME server', async () => {
  const serverPid = Number(tmux(['display-message', '-p', '#{pid}']).trim());
  const sockPath = tmux(['display-message', '-p', '#{socket_path}']).trim();
  assert.ok(serverPid > 0 && fs.existsSync(sockPath), `${serverPid} ${sockPath}`);
  const name = `${PFX}-swept`;
  // ⚠ This file's own private server, and nothing else, is ever named here.
  shim.set('unit', [String(serverPid)]);
  fs.unlinkSync(sockPath);
  try {
    const first = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
    assert.equal(first.status, 503, `a create under a swept socket answered ${first.status} ${JSON.stringify(first.body)}`);
    for (let i = 0; i < 50 && !fs.existsSync(sockPath); i++) await wait(100);
    assert.ok(fs.existsSync(sockPath), 'the server was asked to recreate its socket');
    const again = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
    assert.equal(again.status, 201, JSON.stringify(again.body));
    assert.equal(Number(tmux(['display-message', '-p', '-t', `=${name}:`, '#{pid}']).trim()), serverPid,
      'the session is on the server that was there all along, not on a second one');
  } finally {
    shim.set('unit', ['fail']);
    if (!fs.existsSync(sockPath)) {
      try { process.kill(serverPid, 'SIGUSR1'); } catch { /* gone */ }
      await wait(500);
    }
    // A daemon that started a second server left the first without a socket:
    // stop it by its pid, since it is this file's own.
    let owner = null;
    try { owner = Number(tmux(['display-message', '-p', '#{pid}']).trim()); } catch { /* none */ }
    if (owner && owner !== serverPid) { try { process.kill(serverPid, 'SIGTERM'); } catch { /* gone */ } }
  }
});
