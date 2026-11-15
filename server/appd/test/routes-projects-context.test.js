'use strict';
// The context ceiling of project sessions, end to end through the daemon.
//
// A project session used to ride the model's whole window, compacting at about
// 967k, and every request re-read all of it. The daemon now launches every
// project session with `--autocompact <ceiling>` (the role's own, else the
// project's, 400k by default), measures its context figure against that
// ceiling, compacts a member before typing it a new task when its context is
// large, and tells running members who their peers are when the roster moves.
//
// SAFETY: no real `claude` — `test/fixtures/fake-claude.js` is shadowed onto the
// daemon's PATH and appends every submitted line to `<tmp>/<session>.submitted`.
// A `package.json` beside it is how the daemon learns the CLI version
// (claudeVersionFromPath), and says 2.1.322: a CLI that takes the flag. The tmux
// server is a private `-L` socket; HOME, the data, state and Claude config
// directories all live in a scratch dir.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 13260 + pid%20 -> 13260-13279.
const PORT = 13260 + (process.pid % 20);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-ctx-test-${process.pid}`;

let tmp, stateDir, dataDir, claudeDir, cwd, token, daemon;

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** What tmux was told to run in a session, unquoted. */
function startCommand(name) {
  const raw = sh('tmux', ['display-message', '-p', '-t', `=${name}:`, '#{pane_start_command}']).trim();
  const m = /^"(.*)"$/s.exec(raw);
  return m ? m[1].replace(/\\(["$\\])/g, '$1') : raw;
}

const outFor = (name) => path.join(tmp, `${name}.submitted`);
const stored = (id) => JSON.parse(fs.readFileSync(path.join(dataDir, 'projects', `${id}.json`), 'utf8'));
const persona = (id, role) => readOr(path.join(dataDir, 'projects', 'render', `${id}.${role}.md`));

/** The submitted text of a session once `re` is in it, or a failure that shows what is. */
async function until(name, re, ms = 20_000) {
  const end = Date.now() + ms;
  let text = '';
  while (Date.now() < end) {
    text = readOr(outFor(name));
    if (re.test(text)) return text;
    await wait(100);
  }
  assert.fail(`${name} never received ${re}; it has:\n${text}`);
}

/** A status-line record as huginn-statusline writes it, `used` tokens on a 1M window. */
function writeStatus(name, used) {
  const dir = path.join(stateDir, '.status');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), JSON.stringify({
    model: { id: 'claude-sonnet-5-5', display_name: 'Sonnet 5.5' },
    context_window: {
      context_window_size: 1_000_000,
      // What Claude Code reports under a ceiling: a percentage of the MODEL's window.
      used_percentage: Math.round(used / 10_000),
      current_usage: { input_tokens: 0, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: used },
    },
  }));
}

/** What huginn-claude-title writes for a session running Claude Code. */
function writeState(name) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'idle', sessionId: null, transcript: null, cwd, ts: Date.now() }));
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-pctx-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  claudeDir = path.join(tmp, 'claude');
  cwd = path.join(tmp, 'trusted');
  for (const d of [stateDir, dataDir, claudeDir, cwd, path.join(tmp, 'home')]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  // The trust the create route checks (cwdIsTrusted): CLAUDE_DIR is <tmp>/claude,
  // so the config read is <tmp>/claude.json, never the operator's.
  fs.writeFileSync(path.join(tmp, 'claude.json'), JSON.stringify({
    projects: { [cwd]: { hasTrustDialogAccepted: true, allowedTools: [] } },
  }));

  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'),
    '#!/bin/sh\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `exec ${process.execPath} ${path.join(__dirname, 'fixtures', 'fake-claude.js')}\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'package.json'), JSON.stringify({ name: '@anthropic-ai/claude-code', version: '2.1.322' }));

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
      HUGINN_APPD_REROLE_COMPACT_WAIT_MS: '1500',
      HG_FAKE_CLAUDE_BOOT_MS: '300',
      HG_FAKE_CLAUDE_BANNER_MS: '100',
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
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

let ceil = null;   // the project the later tests share

test('a new project is launched under the default ceiling of 400k, and says so', async () => {
  const r = await api('/v1/projects', {
    method: 'POST', body: JSON.stringify({ name: 'Ceil', kind: 'software', brief: 'size it', cwd }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  ceil = r.body;
  assert.equal(400000, ceil.contextWindow);
  assert.match(startCommand('ceil-lead'), /^claude --name ceil\/lead --autocompact 400000 --append-system-prompt-file /);
  assert.equal(400000, stored(ceil.id).lead.contextCeiling, 'what the lead was launched with is on record');
  const list = await api('/v1/projects');
  const row = list.body.projects.find((p) => p.id === ceil.id);
  assert.equal(400000, row.contextWindow);
  assert.equal(400000, row.lead.contextCeiling);
});

test('null leaves Claude Code\'s own window; a value Claude Code would refuse is refused here', async () => {
  const r = await api('/v1/projects', {
    method: 'POST', body: JSON.stringify({ name: 'Wide', kind: 'software', brief: 'size it', cwd, contextWindow: null }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(null, r.body.contextWindow);
  assert.doesNotMatch(startCommand('wide-lead'), /--autocompact/);
  assert.equal(null, stored(r.body.id).lead.contextCeiling);

  // `claude --autocompact 50000` exits at launch, leaving a bare shell in the pane.
  for (const bad of [50000, 2000000, 400000.5, '400k']) {
    const x = await api('/v1/projects', {
      method: 'POST', body: JSON.stringify({ name: `Bad ${String(bad).replace('.', '')}`, kind: 'software', brief: 'b', cwd, contextWindow: bad }),
    });
    assert.equal(400, x.status, `${bad}: ${JSON.stringify(x.body)}`);
    assert.match(x.body.error, /contextWindow/);
  }
});

test('PATCH moves the ceiling for the launches that come after it', async () => {
  const bad = await api(`/v1/projects/${ceil.id}`, { method: 'PATCH', body: JSON.stringify({ rev: stored(ceil.id).rev, contextWindow: 99999 }) });
  assert.equal(400, bad.status);
  const r = await api(`/v1/projects/${ceil.id}`, { method: 'PATCH', body: JSON.stringify({ rev: stored(ceil.id).rev, contextWindow: 250000 }) });
  assert.equal(200, r.status, JSON.stringify(r.body));
  assert.equal(250000, r.body.contextWindow);
  assert.equal(250000, stored(ceil.id).contextWindow);
});

test('members are launched with their role\'s ceiling or the project\'s, and each is told its peers', async () => {
  const edit = await api(`/v1/projects/${ceil.id}`, {
    method: 'PATCH',
    body: JSON.stringify({
      rev: stored(ceil.id).rev,
      manifest: {
        type: 'software', scope: 'two roles', summary: 'api and web',
        sessions: [
          { role: 'api', firstPrompt: 'build the api', context: 200000 },
          { role: 'web', firstPrompt: 'build the web' },
        ],
      },
    }),
  });
  assert.equal(200, edit.status, JSON.stringify(edit.body));
  const sp = await api(`/v1/projects/${ceil.id}/spawn`, {
    method: 'POST', body: JSON.stringify({ approve: true, manifestRev: edit.body.manifest.rev }),
  });
  assert.equal(200, sp.status, JSON.stringify(sp.body));
  assert.deepEqual(['api', 'web'], sp.body.spawned.map((m) => m.role).sort());

  assert.match(startCommand('ceil-api'), / --autocompact 200000 /, 'its own');
  assert.match(startCommand('ceil-web'), / --autocompact 250000 /, 'the project\'s, as patched');
  const s = stored(ceil.id);
  assert.equal(200000, s.members.find((m) => m.role === 'api').contextCeiling);
  assert.equal(250000, s.members.find((m) => m.role === 'web').contextCeiling);

  // The roster: a member spawned FIRST in the batch knows the one after it.
  assert.match(persona(ceil.id, 'api'), /Your peers in this project, by name: "ceil\/web"/);
  assert.match(persona(ceil.id, 'web'), /Your peers in this project, by name: "ceil\/api"/);
  await until('ceil-api', /first task for role api/);
  await until('ceil-web', /first task for role web/);
});

test('THE CONTEXT FIGURE IS A PERCENTAGE OF THE CEILING, not of the model\'s window', async () => {
  // Claude Code's status line says 9% of 1M about a session 45% of the way to its
  // 200k compaction (measured, 2.1.322); the daemon knows the ceiling and divides.
  writeStatus('ceil-api', 90_000);
  const list = await api('/v1/sessions');
  const row = list.body.sessions.find((x) => x.name === 'ceil-api');
  assert.ok(row, JSON.stringify(list.body.sessions.map((x) => x.name)));
  assert.equal(200000, row.context.window);
  assert.equal(45, row.context.percent);
  assert.equal(1000000, row.context.modelWindow, 'what Claude Code reported stays on the wire');
  assert.equal(45, row.contextPercent, 'the older clients\' field too');

  const ctx = await api('/v1/sessions/ceil-api/context');
  assert.equal(200, ctx.status);
  assert.equal(45, ctx.body.context.percent);
});

test('a member holding a large context is compacted before its new task; a small one is not', async () => {
  writeStatus('ceil-web', 150_000);
  const r = await api(`/v1/projects/${ceil.id}/members/web`, { method: 'PATCH', body: JSON.stringify({ description: 'now build the docs site' }) });
  assert.equal(200, r.status, JSON.stringify(r.body));
  const text = await until('ceil-web', /new task for role web/);
  const at = text.indexOf('/compact');
  assert.ok(at >= 0, `no /compact before the task:\n${text}`);
  assert.ok(at < text.indexOf('new task for role web'), 'the compaction comes FIRST');

  // 90k is under the bar: the task goes straight in.
  const r2 = await api(`/v1/projects/${ceil.id}/members/api`, { method: 'PATCH', body: JSON.stringify({ description: 'now the billing api' }) });
  assert.equal(200, r2.status);
  const api2 = await until('ceil-api', /new task for role api/);
  assert.ok(!api2.includes('/compact'), api2);
});

test('when the roster changes, the members already running are told their peers', async () => {
  writeState('ceil-api');
  writeState('ceil-web');
  const r = await api(`/v1/projects/${ceil.id}/members`, {
    method: 'POST', body: JSON.stringify({ role: 'docs', description: 'write the docs', spawn: true }),
  });
  assert.equal(201, r.status, JSON.stringify(r.body));
  for (const name of ['ceil-api', 'ceil-web']) {
    const text = await until(name, /\[Huginn\] The project roster changed/);
    assert.match(text, /Your peers are now: "ceil\/[a-z]+", "ceil\/[a-z]+"; the lead is "ceil\/lead"/);
    assert.match(text, /"ceil\/docs"/);
    assert.match(text, /no reply is needed/);
  }
  assert.match(persona(ceil.id, 'docs'), /by name: "ceil\/api", "ceil\/web"/);
  // The new member's persona already says it: it is not typed a roster frame.
  await wait(500);
  assert.doesNotMatch(readOr(outFor('ceil-docs')), /roster changed/);
});
