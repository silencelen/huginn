'use strict';
// A live session with NO state file (2026-11-09): the daemon derives what the
// file would say — the Claude session id from the pane's `claude --resume <id>`
// argv, else the registry row, else the project record, else the one transcript
// in its cwd's directory that is newer than the session and nobody else's; the
// cwd from /proc; the state from the transcript's own turn boundary — serves it
// on the row, seeds the state file once (marked as the daemon's), and from then
// on the file wins. The restart sweep then cycles such a session like any other,
// and a session with neither an id nor a transcript stays `null` and is never
// cycled.
//
// SAFETY: as routes-restart-sweep.test.js — no real `claude`, a PATH-shadowed
// stand-in (test/fixtures/fake-claude.js), a scratch HOME with its own
// `.claude/projects`, a scratch state dir, a scratch data dir, a PRIVATE tmux
// server named for this pid, sessions named `sds-<pid>-*`, everything gone in
// after(). The sessions are made with `tmux new-session` directly, as `cc`
// makes them, so no create route writes anything for them.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 13200 + pid%50 -> 13200-13249.
const PORT = 13200 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `sds-${process.pid}`;
const TMUX_SOCK = `huginn-test-${process.pid}`;
const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');
const GAP_MS = 600;

let tmp, home, stateDir, dataDir, token, daemon, logFile, slug, baseEnv;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const daemonLog = () => readOr(logFile);
const tmux = (args, opts = {}) => execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8', ...opts }).trim();
const argvLines = (name) => readOr(path.join(tmp, `${name}.argv`)).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
const stateFile = (name) => { try { return JSON.parse(fs.readFileSync(path.join(stateDir, name), 'utf8')); } catch { return null; } };

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
const rows = async () => ((await api('/v1/sessions')).body || {}).sessions || [];
const rowOf = async (name) => (await rows()).find((r) => r.name === name) || null;
const status = async () => (await api('/v1/status')).body;

async function until(fn, ms = 10_000, step = 100) {
  const by = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > by) return v;
    await wait(step);
  }
}

/** A finished conversation (its last record ends the turn), in the scratch Claude projects dir. */
function writeTranscript(sid) {
  const dir = path.join(home, '.claude', 'projects', slug);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${sid}.jsonl`);
  const now = new Date().toISOString();
  fs.writeFileSync(file, [
    JSON.stringify({ type: 'user', message: { role: 'user', content: 'hello' }, sessionId: sid, cwd: tmp, timestamp: now }),
    JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: 'hi' }], stop_reason: 'end_turn' }, sessionId: sid, cwd: tmp, timestamp: now }),
  ].join('\n') + '\n');
  return file;
}
const composerUp = (name) => until(() => /❯/.test(tmux(['capture-pane', '-p', '-t', `=${name}:`])), 8_000, 100);

/**
 * A session as `cc` makes one: tmux alone, `claude <flags>` in the pane, no state
 * file — nothing here writes one, and the daemon's own polls may have seeded one
 * before the composer is even up, which is the point.
 */
async function launch(suffix, flags = '') {
  const name = `${PFX}-${suffix}`;
  tmux(['new-session', '-d', '-s', name, '-c', tmp, `claude ${flags}; exec "$SHELL" -l`], { env: baseEnv });
  assert.ok(await composerUp(name), `${name}: the stand-in never drew its composer`);
  return name;
}

/** Move the generation on: another hook in settings.json, as a deploy's install-hooks writes. */
async function bumpGeneration() {
  const was = (await status()).runtime.gen;
  const file = path.join(home, '.claude', 'settings.json');
  const settings = JSON.parse(fs.readFileSync(file, 'utf8'));
  settings.hooks[`Hook${crypto.randomBytes(3).toString('hex')}`] = [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/x' }] }];
  fs.writeFileSync(file, JSON.stringify(settings, null, 2));
  const now = await until(async () => { const g = (await status()).runtime.gen; return g !== was ? g : null; }, 5_000, 100);
  assert.ok(now, 'the generation moved on');
  return { was, now };
}

function startDaemon() {
  const logFd = fs.openSync(logFile, 'a');
  const child = spawn(process.execPath, [DAEMON], { env: baseEnv, stdio: ['ignore', logFd, logFd] });
  child.on('error', (e) => { throw e; });
  return child;
}
async function daemonUp() {
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) return; } catch { /* not up */ }
    await wait(100);
  }
  throw new Error('the daemon never answered');
}
function reap(child, ms = 10_000) {
  if (!child || child.exitCode !== null || child.signalCode) return Promise.resolve();
  return new Promise((resolve) => {
    const hard = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* gone */ } }, 3_000);
    const giveUp = setTimeout(resolve, ms);
    const done = () => { clearTimeout(hard); clearTimeout(giveUp); resolve(); };
    child.once('exit', done);
    try { child.kill('SIGTERM'); } catch { done(); }
  });
}

// The sessions the tests share, in the order they are made (oldest first, which
// is the order the sweep takes them in).
const ids = {
  argv: crypto.randomUUID(),       // resumed by its argv; a transcript on disk
  dead: crypto.randomUUID(),       // in an argv, but no transcript anywhere
  registry: crypto.randomUUID(),   // what the registry row says; a transcript on disk
  project: crypto.randomUUID(),    // what the project record says; a transcript on disk
};
const PROJECT_ID = crypto.randomUUID();
const names = {};

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-seed-'));
  home = path.join(tmp, 'home');
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  for (const d of [home, stateDir, dataDir, path.join(dataDir, 'projects'), path.join(home, '.claude')]) fs.mkdirSync(d, { recursive: true });
  slug = tmp.replace(/\//g, '-');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(home, '.claude', 'settings.json'), JSON.stringify({
    hooks: { Stop: [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/huginn-claude-title' }] }] },
  }, null, 2));
  fs.writeFileSync(path.join(home, '.claude.json'), JSON.stringify({ mcpServers: {} }));
  fs.writeFileSync(path.join(tmp, 'pressure'), 'some avg10=0.00 avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n');

  // The stand-in `claude`: `--version` answered, flags recorded per session.
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  const fake = path.join(__dirname, 'fixtures', 'fake-claude.js');
  fs.writeFileSync(path.join(bin, 'claude'),
    '#!/bin/sh\n'
    + 'case "$1" in --version) echo "2.1.316 (Claude Code)"; exit 0;; esac\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `export HG_FAKE_CLAUDE_ARGV_OUT="${tmp}/$name.argv"\n`
    + `exec ${process.execPath} ${fake} "$@"\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'package.json'), JSON.stringify({ name: '@anthropic-ai/claude-code', version: '2.1.316' }));

  // The stores as a running daemon would have left them: a registry row that
  // learned its id from the hook before the file was lost, and a project whose
  // record carries a member's id.
  writeTranscript(ids.argv);
  writeTranscript(ids.registry);
  writeTranscript(ids.project);
  names.argv = `${PFX}-argv`;
  names.registry = `${PFX}-registry`;
  names.project = `${PFX}-project`;
  names.newest = `${PFX}-newest`;
  names.nothing = `${PFX}-nothing`;
  const now = Math.floor(Date.now() / 1000);
  fs.writeFileSync(path.join(dataDir, 'active-sessions.json'), JSON.stringify({
    version: 1,
    sessions: { [names.registry]: { name: names.registry, createdAt: now - 600, claudeSessionId: ids.registry, cwd: tmp, updatedAt: now - 60 } },
  }, null, 2));
  fs.writeFileSync(path.join(dataDir, 'projects', `${PROJECT_ID}.json`), JSON.stringify({
    id: PROJECT_ID, name: 'Seed', slug: 'seed', kind: 'software', status: 'active', brief: 'read the stores', cwd: tmp,
    lead: { role: 'lead', name: names.project, claudeName: 'seed/lead', sessionId: ids.project, spawnedAt: now - 600, endedAt: null },
    members: [],
    manifest: null, endedReason: null, endedAt: null, createdAt: now - 600, updatedAt: now - 500, rev: 2,
  }, null, 2));

  baseEnv = {
    ...process.env,
    PATH: `${bin}:${process.env.PATH}`,
    HOME: home,
    HUGINN_APPD_PORT: String(PORT),
    HUGINN_APPD_BIND: '127.0.0.1',
    HUGINN_APPD_DATA: dataDir,
    HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
    HUGINN_APPD_STATE_DIR: stateDir,
    HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    HUGINN_APPD_WORKDIR: tmp,
    HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
    HUGINN_APPD_PRESSURE_FILE: path.join(tmp, 'pressure'),
    HUGINN_APPD_RECONCILE_MS: '1000',
    // The registry row and the project lead are not live until their test
    // launches them: never written off meanwhile.
    HUGINN_APPD_ABSENT_CONFIRM_MS: '600000',
    HUGINN_APPD_RUNTIME_RECHECK_MS: '400',
    HUGINN_APPD_CYCLE_GAP_MS: String(GAP_MS),
    HUGINN_APPD_RESTART_RETRY_MS: '800',
    HUGINN_APPD_SWEEP_BACKOFF_MS: '1500',
    HUGINN_APPD_QUIT_WAIT_MS: '1500',
    HUGINN_APPD_EXIT_DIALOG_HOLD_MS: '500',
    HUGINN_APPD_SWEEP_SUMMARY_MS: '5000',
    HG_FAKE_CLAUDE_BOOT_MS: '400',
    HG_FAKE_CLAUDE_BANNER_MS: '200',
  };
  execFileSync('tmux', ['-L', TMUX_SOCK, 'start-server', ';', 'set-option', '-g', 'exit-empty', 'off'], { env: baseEnv });
  logFile = path.join(tmp, 'daemon.log');
  daemon = startDaemon();
  await daemonUp();
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(async () => {
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('no state file, `--resume <id>` in the argv: the row has the id, the transcript flag and a state; the file is seeded once, with the cwd, and then wins', async () => {
  await launch('argv', `--resume ${ids.argv} --name seed/argv`);
  const row = await until(async () => { const r = await rowOf(names.argv); return r && r.claudeSessionId ? r : null; }, 8_000, 100);
  assert.ok(row, `the row learned its id: ${JSON.stringify(await rowOf(names.argv))}`);
  assert.equal(row.claudeSessionId, ids.argv);
  assert.equal(row.hasTranscript, true);
  assert.equal(row.state, 'idle', 'the transcript ends on a turn boundary');
  assert.ok(row.stateSince >= row.createdAt, 'idle since no earlier than the session came up');

  // The file the hook would have written, marked as the daemon's.
  const seeded = await until(() => stateFile(names.argv), 3_000, 50);
  assert.ok(seeded, 'the state file was seeded');
  assert.equal(seeded.sessionId, ids.argv);
  assert.equal(seeded.state, 'idle');
  assert.equal(seeded.transcript, path.join(home, '.claude', 'projects', slug, `${ids.argv}.jsonl`));
  assert.equal(fs.realpathSync(seeded.cwd), fs.realpathSync(tmp), 'the cwd from /proc');
  assert.equal(seeded.seeded, true);
  assert.equal(seeded.source, 'argv');
  assert.ok(Number.isInteger(seeded.ts) && seeded.ts > 1e12, 'ts in milliseconds');
  assert.equal((fs.statSync(path.join(stateDir, names.argv)).mode & 0o777), 0o600);
  const lines = daemonLog().split('\n').filter((l) => l.includes(`state: ${names.argv}: no state file; seeded from argv (${ids.argv}, idle`));
  assert.equal(lines.length, 1, `one log line per seeded session:\n${daemonLog().split('\n').filter((l) => /state: /.test(l)).join('\n')}`);

  // Downstream: the registry learns the id and the cwd from the same read.
  const reg = await until(() => {
    try { const s = JSON.parse(fs.readFileSync(path.join(dataDir, 'active-sessions.json'), 'utf8')).sessions[names.argv]; return s && s.claudeSessionId ? s : null; } catch { return null; }
  }, 5_000, 100);
  assert.ok(reg, 'the registry row has the id');
  assert.equal(reg.claudeSessionId, ids.argv);
  assert.equal(fs.realpathSync(reg.cwd), fs.realpathSync(tmp));

  // The file wins from now on: what the hook writes next is what the row says,
  // whatever the argv still carries.
  const other = crypto.randomUUID();
  fs.writeFileSync(path.join(stateDir, names.argv), JSON.stringify({ state: 'attention', sessionId: other, transcript: writeTranscript(other), cwd: tmp, ts: Date.now() }));
  const after1 = await until(async () => { const r = await rowOf(names.argv); return r && r.claudeSessionId === other ? r : null; }, 5_000, 100);
  assert.ok(after1, 'the row reads the file, not the argv');
  assert.equal(after1.state, 'attention');
  // Back to the argv's conversation, as the hook would record it, so the sweep below resumes that one.
  fs.writeFileSync(path.join(stateDir, names.argv), JSON.stringify({ state: 'idle', sessionId: ids.argv, transcript: seeded.transcript, cwd: tmp, ts: Date.now() }));
  await until(async () => { const r = await rowOf(names.argv); return r && r.claudeSessionId === ids.argv ? r : null; }, 5_000, 100);
});

test('an argv id with no transcript falls through to the registry row; a project record names its session too', async () => {
  // The lead relaunched with --resume of an id whose transcript never existed:
  // Claude Code starts a fresh conversation, the hook records it, the registry
  // keeps it — and the argv keeps pointing at nothing.
  await launch('registry', `--resume ${ids.dead} --name seed/registry`);
  const r1 = await until(async () => { const r = await rowOf(names.registry); return r && r.claudeSessionId ? r : null; }, 8_000, 100);
  assert.ok(r1, `the row learned its id from the registry: ${JSON.stringify(await rowOf(names.registry))}`);
  assert.equal(r1.claudeSessionId, ids.registry, 'the registry\'s id, not the dead one in the argv');
  assert.equal(r1.hasTranscript, true);
  assert.equal(r1.state, 'idle');
  const s1 = await until(() => stateFile(names.registry), 3_000, 50);
  assert.equal(s1.source, 'registry');
  assert.equal(s1.sessionId, ids.registry);

  await launch('project', '--name seed/lead');
  const r2 = await until(async () => { const r = await rowOf(names.project); return r && r.claudeSessionId ? r : null; }, 8_000, 100);
  assert.ok(r2, `the row learned its id from the project record: ${JSON.stringify(await rowOf(names.project))}`);
  assert.equal(r2.claudeSessionId, ids.project);
  assert.equal(r2.hasTranscript, true);
  assert.equal(r2.state, 'idle');
  assert.ok(r2.project && r2.project.lead === true, 'listed as the project\'s lead');
  const s2 = await until(() => stateFile(names.project), 3_000, 50);
  assert.equal(s2.source, 'project');
  assert.equal(s2.sessionId, ids.project);
});

test('no argv id and no record: the one transcript in the cwd\'s directory newer than the session and nobody else\'s; two candidates are nobody\'s', async () => {
  await launch('newest', '--name seed/newest');
  // Born in whole seconds: the transcripts below must be newer than the session.
  await wait(1_100);
  assert.equal((await rowOf(names.newest)).claudeSessionId, null, 'nothing to read yet');
  const n1 = crypto.randomUUID();
  const n2 = crypto.randomUUID();
  writeTranscript(n1);
  writeTranscript(n2);
  await wait(1_000);
  assert.equal((await rowOf(names.newest)).claudeSessionId, null, 'two unclaimed transcripts: not ours to choose');
  assert.equal(stateFile(names.newest), null, 'nothing seeded on a guess');
  fs.unlinkSync(path.join(home, '.claude', 'projects', slug, `${n2}.jsonl`));
  const row = await until(async () => { const r = await rowOf(names.newest); return r && r.claudeSessionId ? r : null; }, 8_000, 100);
  assert.ok(row, `the row learned its id from the transcript: ${JSON.stringify(await rowOf(names.newest))}`);
  assert.equal(row.claudeSessionId, n1);
  assert.equal(row.hasTranscript, true);
  assert.equal(row.state, 'idle');
  const s = await until(() => stateFile(names.newest), 3_000, 50);
  assert.equal(s.source, 'transcript');
  assert.equal(s.sessionId, n1);
});

test('a session with neither an argv id nor a transcript stays null', async () => {
  // After the one above has claimed its transcript, so nothing in the directory
  // is unclaimed and newer than this one.
  await launch('nothing', '--name seed/nothing');
  await wait(1_500);
  const row = await rowOf(names.nothing);
  assert.ok(row, 'listed');
  assert.equal(row.claudeSessionId, null);
  assert.equal(row.state, null);
  assert.equal(row.hasTranscript, false);
  assert.equal(stateFile(names.nothing), null, 'nothing seeded');
});

test('the restart sweep cycles the sessions that had no state file, by their derived ids; the one with nothing to resume is never cycled', async () => {
  const { now } = await bumpGeneration();
  for (const n of Object.values(names)) assert.equal((await rowOf(n)).runtime.stale, true, `${n} is stale`);

  const cycled = await until(() => (
    argvLines(names.argv).length >= 2 && argvLines(names.registry).length >= 2
    && argvLines(names.project).length >= 2 && argvLines(names.newest).length >= 2) || null, 40_000, 150);
  assert.ok(cycled, `every session with an id was cycled: ${JSON.stringify(Object.fromEntries(Object.values(names).map((n) => [n, argvLines(n)])))}\n${daemonLog().split('\n').filter((l) => /restart/.test(l)).join('\n')}`);
  assert.deepEqual(argvLines(names.argv)[1], ['--resume', ids.argv, '--name', 'seed/argv']);
  // The dead `--resume` is replaced by the id that has a transcript.
  assert.deepEqual(argvLines(names.registry)[1], ['--resume', ids.registry, '--name', 'seed/registry']);
  // The project lead is relaunched as the reboot restore launches it: by its record.
  assert.deepEqual(argvLines(names.project)[1].slice(0, 2), ['--resume', ids.project]);
  assert.deepEqual(argvLines(names.newest)[1], ['--resume', n1Of(), '--name', 'seed/newest']);
  const log = daemonLog();
  for (const n of [names.argv, names.registry, names.project, names.newest]) {
    assert.match(log, new RegExp(`restart sweep: picked ${n} \\(`), `${n} was picked`);
  }
  for (const n of [names.argv, names.registry, names.newest]) {
    const r = await until(async () => { const x = await rowOf(n); return x && !x.runtime.stale ? x : null; }, 15_000, 100);
    assert.ok(r && r.runtime.gen === now, `${n} is on the new generation`);
  }

  // The one with nothing to resume: never picked, never cycled, still stale.
  await wait(GAP_MS * 3 + 1_000);
  assert.equal(argvLines(names.nothing).length, 1, 'never relaunched');
  assert.doesNotMatch(daemonLog(), new RegExp(`restart sweep: picked ${names.nothing} \\(`));
  assert.doesNotMatch(daemonLog(), new RegExp(`restart: ${names.nothing}: cycling`));
  const left = await rowOf(names.nothing);
  assert.equal(left.runtime.stale, true);
  assert.equal(left.claudeSessionId, null);
});

/** The id the newest-transcript rule bound, read back from its seeded file. */
function n1Of() { return stateFile(names.newest).sessionId; }
