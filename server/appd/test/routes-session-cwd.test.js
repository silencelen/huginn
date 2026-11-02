'use strict';
// POST /v1/sessions with a starting directory, and GET /v1/sessions/recent-dirs
// (2026-11-02): the directory must exist on this server, the pane opens there,
// and the create dialog's picker lists it afterwards.
//
// SAFETY: a private tmux socket, a scratch data/state dir, and `claude` shadowed
// by a stand-in on the daemon's PATH, so nothing real is launched.
//
// PORT ALLOCATION (table in routes-lifecycle.test.js):
//   routes-session-cwd  11900 + pid%50  -> 11900-11949   (this file)

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 11900 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-${process.pid}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const PFX = `cwd${process.pid}`;

let tmp, token, daemon, projectDir;
function sh(cmd, args) { if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args]; return execFileSync(cmd, args, { encoding: 'utf8' }); }
async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, { ...init, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) } });
  let body = null; try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-cwd-'));
  for (const d of ['state', 'data', 'bin', 'project', 'archive-dir']) fs.mkdirSync(path.join(tmp, d));
  projectDir = fs.realpathSync(path.join(tmp, 'project'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  // The create route runs the literal `claude; exec "$SHELL" -l`: a stand-in that
  // just stays up is all a pane needs to report where it is.
  fs.writeFileSync(path.join(tmp, 'bin', 'claude'), '#!/bin/sh\nexec sleep 600\n', { mode: 0o755 });
  // An archived session that ran somewhere else: its directory is a recent one too.
  const arch = path.join(tmp, 'data', 'archive', '11111111-1111-4111-8111-111111111111');
  fs.mkdirSync(arch, { recursive: true });
  fs.writeFileSync(path.join(arch, 'record.json'), JSON.stringify({ id: '11111111-1111-4111-8111-111111111111', tmuxName: 'old', cwd: fs.realpathSync(path.join(tmp, 'archive-dir')), archivedAt: '1790000000' }));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env, PATH: `${path.join(tmp, 'bin')}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT), HUGINN_APPD_BIND: '127.0.0.1', HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'), HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK, HUGINN_APPD_WORKDIR: tmp,
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) { try { if ((await api('/v1/ping')).status === 200) break; } catch { } await wait(100); }
});

after(async () => {
  if (daemon) { const dead = new Promise((r) => daemon.once('exit', r)); daemon.kill('SIGTERM'); await dead; }
  try { sh('tmux', ['kill-server']); } catch { }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a starting directory that does not exist, or is not a directory, is refused with the reason', async () => {
  const gone = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: `${PFX}-a`, cwd: path.join(tmp, 'nope') }) });
  assert.equal(gone.status, 400); assert.match(gone.body.error, /no such directory/);
  const file = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: `${PFX}-a`, cwd: path.join(tmp, 'token') }) });
  assert.equal(file.status, 400); assert.match(file.body.error, /not a directory/);
  const rel = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: `${PFX}-a`, cwd: 'project' }) });
  assert.equal(rel.status, 400); assert.match(rel.body.error, /absolute/);
  let listed = ''; try { listed = sh('tmux', ['ls']); } catch { /* no server at all: nothing was created */ }
  assert.equal(listed.includes(`${PFX}-a`), false, 'nothing was created');
});

test('a session opens in the directory asked for, the default when none is, and recent-dirs lists both', async () => {
  const made = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: `${PFX}-b`, cwd: projectDir }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.equal(made.body.cwd, projectDir);
  assert.equal(sh('tmux', ['display-message', '-p', '-t', `=${PFX}-b:`, '#{pane_current_path}']).trim(), projectDir);
  const dflt = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: `${PFX}-c` }) });
  assert.equal(dflt.status, 201);
  assert.equal(fs.realpathSync(dflt.body.cwd), fs.realpathSync(tmp), 'no cwd: the server default');
  const recent = await api('/v1/sessions/recent-dirs');
  assert.equal(recent.status, 200);
  assert.equal(recent.body.dirs[0], tmp, 'the default first');
  assert.ok(recent.body.dirs.includes(projectDir), "the live session's directory");
  assert.ok(recent.body.dirs.includes(fs.realpathSync(path.join(tmp, 'archive-dir'))), "an archived session's directory");
});
