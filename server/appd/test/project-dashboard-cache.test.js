'use strict';
// The project dashboard's per-member cache, against the two things a parent
// transcript's size cannot see.
//
// ⚠ "UNCHANGED SIZE" WAS READ AS "UNCHANGED ANSWER". rollupMembers skips the
// walk for a member whose transcript has not grown, which is load-bearing (a
// dozen members on a five-second poll must not re-read tens of megabytes). But
// what it then served was not a function of that file alone:
//   * agent spend lives in <sid>/subagents, and a parent writes NOTHING while
//     its agents run (the /graph route keeps a two-number cursor for exactly
//     this), so a Workflow's tokens and cost stayed at their pre-fan-out values
//     for as long as the fan-out lasted;
//   * `rate.activeRecently` is a statement about the clock, so a member polled
//     while it was working stayed "active" for hours after it stopped, and the
//     pace line never said "nothing recent".
//
// SAFETY: lib half — scratch files only. Route half — a daemon with a scratch
// data root, HOME and Claude directory, an inert `claude`, a private tmux socket
// that never gets a session, and a port the kernel just handed out.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const graph = require('../lib/sessiongraph');
const projectsLib = require('../lib/projects');

const L = (o) => `${JSON.stringify(o)}\n`;
const iso = (ms) => new Date(ms).toISOString();
const usage = (out) => ({ input_tokens: 10, output_tokens: out, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 });
const MODEL = 'claude-opus-4-1';

function parentTranscript(file, t0, { workflow = false } = {}) {
  fs.writeFileSync(file,
    L({ type: 'user', timestamp: iso(t0), message: { role: 'user', content: 'take this on' } })
    + L({ type: 'assistant', timestamp: iso(t0 + 4000), requestId: `r-${path.basename(file)}-1`, message: {
      model: MODEL, role: 'assistant', usage: usage(800),
      content: workflow
        ? [{ type: 'tool_use', id: 'toolu_wf', name: 'Workflow', input: { script: 'x' } }]
        : [{ type: 'text', text: 'on it' }],
    } })
    + (workflow
      ? L({ type: 'user', timestamp: iso(t0 + 5000), message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_wf', content: 'Workflow run wf-1 launched' }] } })
      + L({ type: 'assistant', timestamp: iso(t0 + 8000), requestId: `r-${path.basename(file)}-2`, message: { model: MODEL, role: 'assistant', usage: usage(200), content: [{ type: 'text', text: 'launched; waiting' }] } })
      : ''));
}

/** Three workflow agents that bill while the parent writes nothing at all. */
function workflowAgents(dir, sessionId, t0) {
  const wf = path.join(dir, sessionId, 'subagents', 'workflows', 'wf-1');
  fs.mkdirSync(wf, { recursive: true });
  for (let k = 0; k < 3; k++) {
    const f = path.join(wf, `agent-w${k}.jsonl`);
    fs.writeFileSync(f.replace(/\.jsonl$/, '.meta.json'), JSON.stringify({ agentType: 'workflow-subagent', spawnDepth: 1 }));
    let s = L({ type: 'user', timestamp: iso(t0 + 20_000), message: { role: 'user', content: `task ${k}` } });
    for (let i = 0; i < 10; i++) {
      s += L({ type: 'assistant', timestamp: iso(t0 + 20_000 + 60_000 * i), requestId: `w${k}-${i}`,
        message: { model: MODEL, role: 'assistant', usage: usage(4000), content: [{ type: 'text', text: '.' }] } });
    }
    fs.writeFileSync(f, s);
  }
}

// ------------------------------------------------------- the lib, composed as the daemon composes it

/** projectDashboard's composition, with the clock handed in. */
function dashboardAt(members, cache, now) {
  const rows = projectsLib.rollupMembers(members, {
    cache: { get: (k) => cache.get(k), set: (k, v) => cache.set(k, v) },
    sizeOf: (f) => { try { return fs.statSync(f).size; } catch { return null; } },
    agentBytesOf: graph.agentBytesOf,
    overview: (f, id) => graph.sessionOverview(f, id, { now }),
    now,
  });
  return projectsLib.aggregateDashboard(rows);
}
/** What an uncached read says at the same instant. */
function truthAt(members, now) {
  return projectsLib.aggregateDashboard(members.map((m) => ({ overview: graph.sessionOverview(m.transcript, m.sessionId, { now }) })));
}

test('a fan-out\'s spend reaches the dashboard while its parent writes nothing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-dashcache-'));
  const t0 = Date.parse('2026-10-19T10:00:00Z');
  const sid = crypto.randomUUID();
  const file = path.join(dir, `${sid}.jsonl`);
  parentTranscript(file, t0, { workflow: true });
  const members = [{ role: 'lead', name: 'p-lead', claudeName: 'p/lead', lead: true, sessionId: sid, transcript: file }];
  const cache = new Map();
  try {
    const before = dashboardAt(members, cache, t0 + 10_000);
    assert.equal(0, before.totals.agentCount, 'precondition: nothing has fanned out yet');
    const size = fs.statSync(file).size;

    workflowAgents(dir, sid, t0);
    assert.equal(size, fs.statSync(file).size, 'precondition: the parent has not written a byte');
    const now = t0 + 15 * 60_000;
    const shown = dashboardAt(members, cache, now);
    const truth = truthAt(members, now);
    assert.equal(3, shown.totals.agentCount, 'the agents are counted');
    assert.equal(truth.totals.agentTokens.output, shown.totals.agentTokens.output, 'with what they spent');
    assert.ok(shown.totals.agentTokens.output > 0);
    assert.equal(truth.totals.estCost.usd, shown.totals.estCost.usd, 'and the cost includes it');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('"active" is asked of the clock on every poll, not remembered from the last walk', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-dashcache-'));
  const t0 = Date.parse('2026-10-19T12:00:00Z');
  const sid = crypto.randomUUID();
  const file = path.join(dir, `${sid}.jsonl`);
  parentTranscript(file, t0);
  const members = [{ role: 'docs', name: 'p-docs', claudeName: 'p/docs', sessionId: sid, transcript: file }];
  const cache = new Map();
  let walks = 0;
  const io = {
    cache: { get: (k) => cache.get(k), set: (k, v) => cache.set(k, v) },
    sizeOf: (f) => fs.statSync(f).size,
    agentBytesOf: graph.agentBytesOf,
  };
  const at = (now) => projectsLib.aggregateDashboard(projectsLib.rollupMembers(members, {
    ...io, now, overview: (f, id) => { walks++; return graph.sessionOverview(f, id, { now }); },
  }));
  try {
    assert.equal(true, at(t0 + 9_000).rate.activeRecently, 'polled while it was working');
    for (const later of [10 * 60_000, 3 * 3600_000]) {
      assert.equal(false, at(t0 + later).rate.activeRecently, `and quiet ${later / 60_000} minutes on`);
      assert.equal(false, truthAt(members, t0 + later).rate.activeRecently, 'which is what an uncached read says');
    }
    assert.equal(1, walks, 'and still without walking a transcript that has not grown');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

// ------------------------------------------------------- the route the clients poll

let tmp, token, daemon, base, projectsDir, transcripts;

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-dashroute-'));
  for (const d of ['data', 'state', 'claude', 'home', 'shim', 'work']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'shim', 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  const work = path.join(tmp, 'work');
  // Where the daemon looks for a member's transcript when no hook has named it.
  transcripts = path.join(tmp, 'claude', 'projects', work.replace(/\//g, '-'));
  fs.mkdirSync(transcripts, { recursive: true });
  projectsDir = path.join(tmp, 'data', 'projects');
  fs.mkdirSync(projectsDir, { recursive: true });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: work,
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-dashcache-${process.pid}`,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 300; i++) {
    try {
      const r = await fetch(`${base}/v1/ping`, { headers: { authorization: `Bearer ${token}` } });
      if (r.status === 200) break;
      if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await wait(100);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

/** A project whose lead is `sessionId`, stored the way the daemon stores one. */
function storeProject(sessionId) {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const slug = `dash${id.slice(0, 6)}`;
  fs.writeFileSync(path.join(projectsDir, `${id}.json`), JSON.stringify({
    id, name: 'Dashboard', slug, kind: 'software', status: 'active', cwd: tmp,
    lead: { role: 'lead', name: `${slug}-lead`, claudeName: `${slug}/lead`, sessionId },
    members: [], manifest: { tag: slug, rev: 1, sessions: [] },
    createdAt: now, updatedAt: now, rev: 1,
  }, null, 2));
  return id;
}
async function dashboard(id) {
  const r = await fetch(`${base}/v1/projects/${id}/dashboard`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(200, r.status);
  return r.json();
}

test('the dashboard route counts a fan-out that started after it was first polled', async () => {
  const sid = crypto.randomUUID();
  parentTranscript(path.join(transcripts, `${sid}.jsonl`), Date.now() - 60_000, { workflow: true });
  const id = storeProject(sid);
  assert.equal(0, (await dashboard(id)).totals.agentCount, 'precondition: the first poll sees no agents');
  workflowAgents(transcripts, sid, Date.now() - 50_000);
  const after = await dashboard(id);
  assert.equal(3, after.totals.agentCount, 'the next poll sees them, though the parent wrote nothing');
  assert.ok(after.totals.agentTokens.output > 0, 'and what they spent');
});

test('the dashboard route stops calling a quiet member active without it writing a byte', async () => {
  const sid = crypto.randomUUID();
  // Last spoke 85 s ago: "active" means within the last 90.
  parentTranscript(path.join(transcripts, `${sid}.jsonl`), Date.now() - 89_000);
  const id = storeProject(sid);
  assert.equal(true, (await dashboard(id)).rate.activeRecently, 'precondition: active when first polled');
  await wait(6_000);
  assert.equal(false, (await dashboard(id)).rate.activeRecently, 'and not, once 90 s have passed in silence');
});
