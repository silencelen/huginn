'use strict';
// The project dashboard answers at a CAP, with the members that are ready, and
// walks its cold members a few at a time (lib/projects rollupMembersConcurrent;
// projectDashboard in huginn-appd.js, 2026-11-07).
//
// ⚠ THE FINDING. `GET /v1/projects/<id>/dashboard` measured from the retained
// journal: median 38 ms, p99 109 ms — and 8 answers over 1 s, 3 over 5 s, the
// worst 8.6 s (11 s on an earlier scan). The roll-up awaited one cold member's
// walk after the next, so a project whose members had gone cold (a daemon
// restart, the graph LRU) answered in the sum of their walks, and the page
// showed "Nothing to show yet." the whole time.
//
// SAFETY: lib half — scratch files and injected overviews, no daemon. Route half
// — a daemon with a scratch data root, HOME and Claude directory, an inert
// `claude`, a private tmux socket that never gets a session, and a port the
// kernel just handed out. The slow member is a 60 MB transcript in the scratch
// Claude directory.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const projectsLib = require('../lib/projects');

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const totalsOf = (turns) => ({
  turns, userMessages: turns, toolCalls: 0, errors: 0,
  tokens: { input: 10 * turns, output: 100 * turns, cacheRead: 0, cacheCreation: 0 },
  agentCount: 0, agentTokens: { input: 0, output: 0, cacheRead: 0, cacheCreation: 0 },
  compactions: 0, droppedTokens: 0, filesTouched: 0, models: ['m'], efforts: [], estCost: null,
  startedAt: 1000, lastActivityTs: 2000,
});
const overviewOf = (turns) => ({ totals: totalsOf(turns), rate: { activeRecently: false, tokensPerMin10: 0, tokensPerMin60: 0 } });

/** Members a, b, c with transcripts of known sizes; `slow` names the one whose walk takes `slowMs`. */
function world({ slow = 'b', slowMs = 2_000 } = {}) {
  const sizes = new Map([['/t/a.jsonl', 100], ['/t/b.jsonl', 50_000_000], ['/t/c.jsonl', 300]]);
  const members = ['a', 'b', 'c'].map((r) => ({ role: r, name: `p-${r}`, claudeName: `p/${r}`, sessionId: `sid-${r}`, transcript: `/t/${r}.jsonl` }));
  const cache = new Map();
  const walks = [];
  let inFlight = 0;
  let maxInFlight = 0;
  const io = {
    cache: { get: (k) => cache.get(k), set: (k, v) => cache.set(k, v) },
    sizeOf: (f) => sizes.get(f) ?? null,
    overview: (f, id) => {
      walks.push(id);
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      const turns = id === 'sid-a' ? 3 : id === 'sid-b' ? 40 : 7;
      const ms = id === `sid-${slow}` ? slowMs : 5;
      return new Promise((resolve) => setTimeout(() => { inFlight -= 1; resolve(overviewOf(turns)); }, ms));
    },
  };
  return { members, cache, sizes, walks, io, maxInFlight: () => maxInFlight };
}

test('a dashboard with one slow member answers within the cap, with the others\' numbers and that member pending', async () => {
  const w = world({ slow: 'b', slowMs: 1_500 });
  const t0 = Date.now();
  const { rows, pending } = await projectsLib.rollupMembersConcurrent(w.members, { ...w.io, limit: 4, capMs: 200 });
  const took = Date.now() - t0;
  assert.ok(took < 1_000, `answered at the cap, not at the slow walk (${took} ms)`);
  assert.equal(pending, 1);
  const byRole = Object.fromEntries(rows.map((r) => [r.role, r]));
  assert.equal(byRole.a.overview.totals.turns, 3, 'a is in');
  assert.equal(byRole.c.overview.totals.turns, 7, 'c is in');
  assert.equal(byRole.b.overview, null, 'b has no numbers yet');
  assert.equal(byRole.b.pending, true, 'and says so');
  assert.ok(!byRole.a.pending && !byRole.c.pending);
  const dash = projectsLib.aggregateDashboard(rows);
  assert.equal(dash.totals.turns, 10, 'the totals are the ready members\' alone');
  const row = projectsLib.dashboardMemberRow(byRole.b);
  assert.equal(row.pending, true);
  assert.equal(row.turns, 0);
  assert.equal(projectsLib.dashboardMemberRow(byRole.a).pending, false);

  // Its walk went on: the next poll has it, without walking anything again.
  await wait(1_600);
  const again = await projectsLib.rollupMembersConcurrent(w.members, { ...w.io, limit: 4, capMs: 200 });
  assert.equal(again.pending, 0);
  assert.equal(again.rows.find((r) => r.role === 'b').overview.totals.turns, 40, 'b landed from the walk the first poll started');
  assert.deepEqual(w.walks, ['sid-a', 'sid-b', 'sid-c'], 'three walks in all: nothing was walked twice');
});

test('a slow member that was known before is shown with its last known numbers while it is re-read', async () => {
  const w = world({ slow: 'b', slowMs: 1_500 });
  // Known at a smaller size: a poll from before b wrote more.
  w.cache.set('sid-b', { size: 49_000_000, agentBytes: 0, file: '/t/b.jsonl', overview: overviewOf(33) });
  const { rows, pending } = await projectsLib.rollupMembersConcurrent(w.members, { ...w.io, limit: 4, capMs: 150 });
  assert.equal(pending, 1);
  const b = rows.find((r) => r.role === 'b');
  assert.equal(b.pending, true);
  assert.equal(b.overview.totals.turns, 33, 'the last known numbers, not zero');
  assert.equal(projectsLib.aggregateDashboard(rows).totals.turns, 43, 'counted into the totals');
  // A SMALLER file at the same path is another conversation: nothing known about it.
  const w2 = world({ slow: 'b', slowMs: 1_500 });
  w2.cache.set('sid-b', { size: 60_000_000, agentBytes: 0, file: '/t/b.jsonl', overview: overviewOf(33) });
  const r2 = await projectsLib.rollupMembersConcurrent(w2.members, { ...w2.io, limit: 4, capMs: 150 });
  assert.equal(r2.rows.find((r) => r.role === 'b').overview, null);
  await wait(1_700);   // let both slow walks land before the file ends
});

test('warm members are not recomputed: one walk per member, then none until a transcript grows', async () => {
  const w = world({ slowMs: 5 });
  const poll = () => projectsLib.rollupMembersConcurrent(w.members, { ...w.io, limit: 4, capMs: 1_000 });
  const first = await poll();
  assert.equal(first.pending, 0, 'all three landed inside the cap');
  assert.equal(w.walks.length, 3, 'the first open walks every member once');
  await poll();
  await poll();
  assert.equal(w.walks.length, 3, 'two more polls with nothing appended walk nothing');
  w.sizes.set('/t/c.jsonl', 301);
  const grown = await poll();
  assert.equal(w.walks.length, 4, 'and only the member that grew is walked again');
  assert.deepEqual(w.walks[3], 'sid-c');
  assert.equal(grown.pending, 0);
});

test('cold members are walked a bounded number at a time, and the cap covers the ones not yet started', async () => {
  const members = Array.from({ length: 12 }, (_, i) => ({ role: `r${i}`, name: `p-r${i}`, claudeName: `p/r${i}`, sessionId: `sid-${i}`, transcript: `/t/${i}.jsonl` }));
  const cache = new Map();
  let inFlight = 0;
  let maxInFlight = 0;
  let started = 0;
  const io = {
    cache: { get: (k) => cache.get(k), set: (k, v) => cache.set(k, v) },
    sizeOf: () => 1000,
    overview: () => {
      started += 1;
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      return new Promise((resolve) => setTimeout(() => { inFlight -= 1; resolve(overviewOf(1)); }, 120));
    },
  };
  const { rows, pending } = await projectsLib.rollupMembersConcurrent(members, { ...io, limit: 4, capMs: 200 });
  assert.equal(maxInFlight, 4, 'never more than four walks at once');
  assert.ok(started >= 4 && started <= 8, `only what four workers could begin in 200 ms (${started})`);
  assert.ok(pending >= 4, `the rest are pending (${pending})`);
  assert.equal(rows.filter((r) => r.pending).length, pending);
  assert.equal(rows.length, 12);
  // The workers go on after the answer left: every member lands, still four at a time.
  await wait(12 * 120 / 4 + 400);
  assert.equal(started, 12, 'the walks the cap left behind were still made');
  assert.equal(maxInFlight, 4, 'and never more than four at once');
  assert.equal(inFlight, 0);
  // Unbounded cap on a warm set: nothing to walk, the whole roll-up as rollupMembersAsync answered it.
  const all = await projectsLib.rollupMembersConcurrent(members, { ...io, limit: 4 });
  assert.equal(all.pending, 0);
  assert.equal(all.rows.filter((r) => r.overview).length, 12);
  assert.equal(started, 12, 'warm: not walked again');
});

test('rollupMembers and rollupMembersAsync answer as they always did, with no pending mark on a landed row', async () => {
  const w = world({ slowMs: 1 });
  const sync = projectsLib.rollupMembers(w.members, { ...w.io, overview: (f, id) => overviewOf(id === 'sid-a' ? 1 : 2) });
  assert.equal(sync.length, 3);
  assert.ok(sync.every((r) => r.overview && !('pending' in r)), JSON.stringify(sync.map((r) => Object.keys(r))));
  const w2 = world({ slowMs: 1 });
  const serial = await projectsLib.rollupMembersAsync(w2.members, w2.io);
  assert.ok(serial.every((r) => r.overview && !('pending' in r)));
  assert.deepEqual(w2.walks, ['sid-a', 'sid-b', 'sid-c']);
});

// ------------------------------------------------------- the route the clients poll

let tmp, token, daemon, base, projectsDir, transcripts, logFile;

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}
const L = (o) => `${JSON.stringify(o)}\n`;
const iso = (ms) => new Date(ms).toISOString();

/** A short, finished conversation. */
function smallTranscript(file, t0) {
  fs.writeFileSync(file,
    L({ type: 'user', timestamp: iso(t0), message: { role: 'user', content: 'take this on' } })
    + L({ type: 'assistant', timestamp: iso(t0 + 4000), requestId: `r-${path.basename(file)}-1`, message: {
      model: 'claude-opus-4-1', role: 'assistant', stop_reason: 'end_turn',
      usage: { input_tokens: 10, output_tokens: 800, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      content: [{ type: 'text', text: 'on it' }],
    } }));
}

/** A conversation of `turns` exchanges, padded so the file is big enough to take seconds to walk. */
function bigTranscript(file, t0, turns) {
  const fd = fs.openSync(file, 'w');
  const pad = 'x'.repeat(400);
  let chunk = '';
  for (let i = 0; i < turns; i++) {
    chunk += L({ type: 'user', timestamp: iso(t0 + i * 2000), message: { role: 'user', content: `step ${i} ${pad}` } })
      + L({ type: 'assistant', timestamp: iso(t0 + i * 2000 + 1000), requestId: `r-${i}`, message: {
        model: 'claude-opus-4-1', role: 'assistant', stop_reason: 'end_turn',
        usage: { input_tokens: 10, output_tokens: 20, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
        content: [{ type: 'text', text: `done ${i} ${pad}` }],
      } });
    if (chunk.length > 4 * 1024 * 1024) { fs.writeSync(fd, chunk); chunk = ''; }
  }
  if (chunk) fs.writeSync(fd, chunk);
  fs.closeSync(fd);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-dashconc-'));
  for (const d of ['data', 'state', 'claude', 'home', 'shim', 'work']) fs.mkdirSync(path.join(tmp, d));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'shim', 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  const work = path.join(tmp, 'work');
  transcripts = path.join(tmp, 'claude', 'projects', work.replace(/\//g, '-'));
  fs.mkdirSync(transcripts, { recursive: true });
  projectsDir = path.join(tmp, 'data', 'projects');
  fs.mkdirSync(projectsDir, { recursive: true });
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  logFile = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(logFile, 'a');
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
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-dashconc-${process.pid}`,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      // Short, so a 60 MB walk is demonstrably longer than it on any host.
      HUGINN_APPD_DASHBOARD_CAP_MS: '300',
    },
    stdio: ['ignore', logFd, logFd],
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

function storeProject(leadSid, memberSid) {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const slug = `dash${id.slice(0, 6)}`;
  fs.writeFileSync(path.join(projectsDir, `${id}.json`), JSON.stringify({
    id, name: 'Dashboard', slug, kind: 'software', status: 'active', cwd: tmp,
    lead: { role: 'lead', name: `${slug}-lead`, claudeName: `${slug}/lead`, sessionId: leadSid },
    members: [{ role: 'slow', name: `${slug}-slow`, claudeName: `${slug}/slow`, sessionId: memberSid, cwd: tmp,
      model: null, effort: null, mode: null, firstPrompt: 'the slow part', spawnedAt: now - 100, endedAt: null }],
    manifest: { tag: slug, rev: 1, sessions: [] },
    createdAt: now, updatedAt: now, rev: 1,
  }, null, 2));
  return id;
}
async function get(pathname) {
  const t0 = Date.now();
  const r = await fetch(`${base}${pathname}`, { headers: { authorization: `Bearer ${token}` } });
  const body = await r.json();
  return { status: r.status, body, ms: Date.now() - t0 };
}

test('the dashboard route answers at the cap with the fast member\'s numbers, the slow one pending, and the next polls fill it in', async () => {
  const lead = crypto.randomUUID();
  const slow = crypto.randomUUID();
  smallTranscript(path.join(transcripts, `${lead}.jsonl`), Date.now() - 60_000);
  bigTranscript(path.join(transcripts, `${slow}.jsonl`), Date.now() - 3600_000, 70_000);
  const size = fs.statSync(path.join(transcripts, `${slow}.jsonl`)).size;
  assert.ok(size > 50 * 1024 * 1024, `the slow transcript is big enough (${size} bytes)`);
  const id = storeProject(lead, slow);

  const first = await get(`/v1/projects/${id}/dashboard`);
  assert.equal(first.status, 200);
  assert.ok(first.ms < 1_500, `answered near the 300 ms cap, not after the walk (${first.ms} ms)`);
  assert.equal(first.body.pending, 1, JSON.stringify(first.body.members.map((m) => [m.role, m.pending, m.turns])));
  const rows = Object.fromEntries(first.body.members.map((m) => [m.role, m]));
  assert.equal(rows.lead.pending, false);
  assert.equal(rows.lead.turns, 1, 'the lead\'s numbers are in');
  assert.equal(rows.slow.pending, true);
  assert.equal(rows.slow.turns, 0, 'the slow member has none yet');
  assert.equal(first.body.totals.turns, 1, 'the totals are the ready member\'s');

  // The daemon keeps answering while the walk goes on in the background.
  const ping = await get('/v1/ping');
  assert.equal(ping.status, 200);
  assert.ok(ping.ms < 1_000, `a request beside the walk is not stalled by it (${ping.ms} ms)`);

  // The walk finishes; a later poll has the member, and never re-reads it.
  const by = Date.now() + 60_000;
  let done = null;
  while (Date.now() < by) {
    await wait(500);
    const r = await get(`/v1/projects/${id}/dashboard`);
    assert.ok(r.ms < 1_500, `every poll answers at the cap (${r.ms} ms)`);
    if (r.body.pending === 0) { done = r.body; break; }
  }
  assert.ok(done, 'the slow member landed within a minute');
  const slowRow = done.members.find((m) => m.role === 'slow');
  assert.equal(slowRow.pending, false);
  assert.equal(slowRow.turns, 70_000, 'with the whole conversation counted');
  assert.equal(done.totals.turns, 70_001);
  const warm = await get(`/v1/projects/${id}/dashboard`);
  assert.ok(warm.ms < 500, `a warm dashboard is quick (${warm.ms} ms)`);
  assert.equal(warm.body.pending, 0);
});
