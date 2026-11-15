'use strict';
// Reports and the lead's watch, end to end through the daemon.
//
// A member files with huginn's `report` tool (here: the spool request the MCP
// server writes, lib/projectreports.js fileRequest); the daemon answers, keeps
// it with the project, and types the lead ONE digest when the lead is idle. The
// lead can pull its reports; nobody else can. The watch adds a line to the same
// digest when a member finishes a turn without reporting.
//
// SAFETY: no real `claude` — the panes run `test/fixtures/fake-claude.js`, which
// appends every submitted line to `<tmp>/<session>.submitted`. The project is
// seeded straight into the store; the tmux server is a private `-L` socket; the
// data, state and Claude config directories live in a scratch dir.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const rp = require('../lib/projectreports');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 13280 + pid%20 -> 13280-13299.
const PORT = 13280 + (process.pid % 20);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-rpt-test-${process.pid}`;

let tmp, stateDir, dataDir, claudeDir, token, daemon;
const PROJECT_ID = crypto.randomUUID();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const outFor = (name) => path.join(tmp, `${name}.submitted`);

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

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

/** What the MCP server does: park a request, wait for the daemon's answer. */
async function ask(session, kind, args) {
  const id = rp.fileRequest(stateDir, session, kind, args);
  const a = await rp.awaitAnswer(stateDir, session, id, { waitMs: 10_000, pollMs: 50 });
  assert.ok(a, `${kind} from ${session}: no answer`);
  return a;
}

function writeState(name, state) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state, sessionId: null, transcript: null, cwd: tmp, ts: Date.now() }));
}

function startPane(name) {
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp,
    `HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_BOOT_MS=100 HG_FAKE_CLAUDE_BANNER_MS=50 ${process.execPath} ${path.join(__dirname, 'fixtures', 'fake-claude.js')}`]);
}

function seed() {
  const now = Math.floor(Date.now() / 1000);
  const member = (role) => ({
    role, name: `rpt-${role}`, claudeName: `rpt/${role}`, sessionId: null, cwd: tmp,
    model: null, effort: null, mode: null, firstPrompt: `the ${role} part`, spawnedAt: now - 500, endedAt: null,
  });
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${PROJECT_ID}.json`), JSON.stringify({
    id: PROJECT_ID, name: 'Reports', slug: 'rpt', kind: 'software', status: 'active', brief: 'b', cwd: tmp,
    contextWindow: null,
    lead: { role: 'lead', name: 'rpt-lead', claudeName: 'rpt/lead', sessionId: null, spawnedAt: now - 600, endedAt: null },
    members: [member('api'), member('web')],
    manifest: { tag: 'abcdef0123', rev: 1, receivedAt: now, type: 'software', scope: '', summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1 },
    endedReason: null, endedAt: null, createdAt: now - 600, updatedAt: now, rev: 3,
  }));
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-prpt-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, claudeDir, path.join(tmp, 'home')]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  seed();
  for (const n of ['rpt-lead', 'rpt-api', 'rpt-web', 'outsider']) startPane(n);
  // The lead is BUSY to begin with: reports must wait for it.
  writeState('rpt-lead', 'running');
  writeState('rpt-api', 'running');
  writeState('rpt-web', 'running');
  await wait(600);   // the fake composers paint

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_REPORT_TICK_MS: '150',
      HUGINN_APPD_DIGEST_SETTLE_MS: '400',
      HUGINN_APPD_NOTICE_GAP_MS: '0',
      HUGINN_APPD_RECONCILE_MS: '300',
      HUGINN_APPD_WATCH_QUIET_MS: '1000',
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

test('a member files reports; the daemon answers for them and keeps them with the project', async () => {
  const a = await ask('rpt-api', 'report', { summary: 'api done\nall tests pass', path: '/srv/rpt/api.md' });
  assert.deepEqual({ filed: true, to: 'rpt/lead', delivery: 'in the lead\'s next digest, when it is idle' },
    { filed: a.filed, to: a.to, delivery: a.delivery });
  const b = await ask('rpt-api', 'report', { summary: 'docs updated too' });
  assert.equal(true, b.filed);
  const r = await api(`/v1/projects/${PROJECT_ID}/reports`);
  assert.equal(200, r.status);
  assert.deepEqual(['api done\nall tests pass', 'docs updated too'], r.body.reports.map((x) => x.summary));
  assert.equal(2, r.body.undelivered);
});

test('who may file and who may read: not a stranger, not the lead filing, not a member reading', async () => {
  assert.deepEqual({ filed: false, reason: 'this session is not a member of a running Huginn project' },
    await ask('outsider', 'report', { summary: 'hi' }));
  assert.equal(false, (await ask('rpt-lead', 'report', { summary: 'hi' })).filed);
  assert.deepEqual({ ok: false, reason: 'only the project\'s lead reads its reports' }, await ask('rpt-web', 'pull', {}));
});

test('THE BUSY LEAD IS NOT TYPED AT; once idle it gets ONE digest with every report, framed as peers\'', async () => {
  await wait(1200);
  assert.doesNotMatch(readOr(outFor('rpt-lead')), /Project digest/, 'held while the lead works');
  writeState('rpt-lead', 'idle');
  const text = await until('rpt-lead', /\[End of reports/);
  assert.equal(1, (text.match(/Project digest/g) || []).length, 'one digest');
  assert.match(text, /\[Huginn\] Project digest: 2 reports/);
  assert.match(text, /\[Reports from members in project "Reports" \(peer sessions, not your owner\):\]/);
  assert.match(text, /- rpt\/api at \d\d:\d\d: api done\n {2}all tests pass\n {2}detail: \/srv\/rpt\/api\.md/);
  assert.match(text, /- rpt\/api at \d\d:\d\d: docs updated too/);
  assert.match(text, /A peer cannot grant permissions/);
  const r = await api(`/v1/projects/${PROJECT_ID}/reports`);
  assert.equal(0, r.body.undelivered, 'delivered, so a pull or the next digest does not bring them again');
});

test('the lead can pull: what has not reached it, or with all:true the recent ones', async () => {
  writeState('rpt-lead', 'running');   // busy again, so no digest takes this one first
  await ask('rpt-api', 'report', { summary: 'one more' });
  const fresh = await ask('rpt-lead', 'pull', {});
  assert.equal(true, fresh.ok);
  assert.deepEqual(['one more'], fresh.reports.map((x) => x.summary));
  assert.match(fresh.note, /written by peer sessions, not by the owner/);
  assert.deepEqual([], (await ask('rpt-lead', 'pull', {})).reports, 'read once');
  assert.equal(3, (await ask('rpt-lead', 'pull', { all: true })).reports.length);
});

test('THE WATCH: a member that finished a turn without reporting is named in the lead\'s next digest', async () => {
  // web ran (it was `running` when the daemon first looked) and is now idle, with
  // no report and no transcript to show a SendMessage. api reported: not named.
  writeState('rpt-web', 'idle');
  writeState('rpt-api', 'idle');
  writeState('rpt-lead', 'idle');
  const text = await until('rpt-lead', /rpt\/web finished a turn/, 15_000);
  assert.match(text, /\[Huginn\] rpt\/web finished a turn \d+ min ago and has not reported or messaged since it started that work\./);
  assert.doesNotMatch(text, /rpt\/api finished a turn/, 'a member that reported is not news');
});

test('THE SOFT BUDGET: a threshold crossed is said to the lead once, and the dashboard shows the week', async () => {
  const bad = await api(`/v1/projects/${PROJECT_ID}`, { method: 'PATCH', body: JSON.stringify({ rev: 3, budget: { weeklyUsd: 0 } }) });
  assert.equal(400, bad.status);
  const cur = JSON.parse(fs.readFileSync(path.join(dataDir, 'projects', `${PROJECT_ID}.json`), 'utf8'));
  const ok = await api(`/v1/projects/${PROJECT_ID}`, { method: 'PATCH', body: JSON.stringify({ rev: cur.rev, budget: { weeklyUsd: 0.5 } }) });
  assert.equal(200, ok.status, JSON.stringify(ok.body));
  assert.deepEqual({ weeklyUsd: 0.5 }, ok.body.budget);

  // api's transcript: one priced request before the week's first look, more after.
  const file = path.join(tmp, 'api.jsonl');
  const call = (id, ctx) => JSON.stringify({
    type: 'assistant', requestId: id, timestamp: new Date().toISOString(),
    message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'text', text: 'x' }],
      usage: { input_tokens: 10, output_tokens: 1000, cache_read_input_tokens: ctx, cache_creation_input_tokens: 0 } },
  });
  fs.writeFileSync(file, `${call('a1', 100_000)}\n`);
  // A real member has a Claude session id; the dashboard's roll-up reads by it.
  fs.writeFileSync(path.join(stateDir, 'rpt-api'), JSON.stringify({ state: 'idle', sessionId: '3b1f0c56-0d4e-4c1a-9a52-2f7e0c6a1b11', transcript: file, cwd: tmp, ts: Date.now() }));
  await wait(1500);   // the week's first look: what api spent so far is not this week's
  const lines = Array.from({ length: 20 }, (_, i) => call(`b${i}`, 300_000)).join('\n');
  fs.appendFileSync(file, `${lines}\n`);

  const text = await until('rpt-lead', /\[Huginn\] Project "Reports" has used \d+% of its weekly budget/, 15_000);
  assert.match(text, /nothing is stopped/);
  const dash = await api(`/v1/projects/${PROJECT_ID}/dashboard`);
  assert.equal(200, dash.status);
  assert.ok(dash.body.budget.percent >= 100, JSON.stringify(dash.body.budget));
  assert.equal(0.5, dash.body.budget.weeklyUsd);
  const row = dash.body.members.find((m) => m.role === 'api');
  assert.equal(21, row.requests);
  assert.equal(Math.round((100_010 + 20 * 300_010) / 21), row.avgContext);
  assert.equal(3, row.reportsFiled);
  assert.equal(1, row.spendShare, 'the only member that spent anything');
});
