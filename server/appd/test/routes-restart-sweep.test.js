'use strict';
// The automatic sweep (appd 3.19.0, 2026-11-07): with `cycleOnDeploy` on, stale
// sessions are cycled ONE AT A TIME, oldest first, with a gap between them and
// the load gate before each; an attached session is skipped and stays stale for
// a manual restart; with it off nothing is cycled; and a relaunch that fails is
// retried once and then given up on, the session kept `stale`.
//
// SAFETY: as routes-restart.test.js — no real `claude`, a PATH-shadowed stand-in
// (test/fixtures/fake-claude.js), a scratch HOME, a PRIVATE tmux server named
// for this pid, sessions named `swp-<pid>-*`, everything gone in after(). The
// two daemons run one after the other on the same port.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const paneLib = require('../lib/pane');
const typing = require('../lib/typing');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 13150 + pid%50 -> 13150-13199.
const PORT = 13150 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `swp-${process.pid}`;
const TMUX_SOCK = `huginn-test-${process.pid}`;
const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');
const BOOT_MS = 400;
const BANNER_MS = 200;
const GAP_MS = 600;

let tmp, home, stateDir, token, daemon, logFile, slug, baseEnv;
const clients = [];

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const daemonLog = () => readOr(logFile);
const tmux = (args, opts = {}) => execFileSync('tmux', ['-L', TMUX_SOCK, ...args], { encoding: 'utf8', ...opts }).trim();
const argvLines = (name) => readOr(path.join(tmp, `${name}.argv`)).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));

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
function writeState(name, state, sid, transcript) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state, sessionId: sid, transcript, cwd: tmp, ts: Date.now() }));
}
const composerUp = (name) => until(() => /❯/.test(tmux(['capture-pane', '-p', '-t', `=${name}:`])), 8_000, 100);

/** A session with a conversation to resume, made through the create route. */
async function readySession(suffix) {
  const name = `${PFX}-${suffix}`;
  const r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.ok(await composerUp(name), `${name}: the stand-in never drew its composer`);
  const sid = crypto.randomUUID();
  writeState(name, 'idle', sid, writeTranscript(sid));
  return { name, sid };
}

function attach(name) {
  const c = spawn('tmux', ['-L', TMUX_SOCK, '-C', 'attach-session', '-t', `=${name}`], { stdio: ['pipe', 'ignore', 'ignore'] });
  clients.push(c);
  return c;
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

function startDaemon(extraEnv = {}) {
  const logFd = fs.openSync(logFile, 'a');
  const child = spawn(process.execPath, [DAEMON], { env: { ...baseEnv, ...extraEnv }, stdio: ['ignore', logFd, logFd] });
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

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-sweep-'));
  home = path.join(tmp, 'home');
  stateDir = path.join(tmp, 'state');
  for (const d of [home, stateDir, path.join(tmp, 'data'), path.join(home, '.claude')]) fs.mkdirSync(d, { recursive: true });
  slug = tmp.replace(/\//g, '-');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(home, '.claude', 'settings.json'), JSON.stringify({
    hooks: { Stop: [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/huginn-claude-title' }] }] },
  }, null, 2));
  fs.writeFileSync(path.join(home, '.claude.json'), JSON.stringify({ mcpServers: { huginn: { type: 'stdio', command: process.execPath, args: ['x.js'] } } }));
  fs.writeFileSync(path.join(tmp, 'pressure'), 'some avg10=0.00 avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n');

  // The stand-in `claude`: `--version` answered, flags recorded per session; a
  // session named `dies` is one whose relaunch fails (exit 1 on `--resume`).
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  const fake = path.join(__dirname, 'fixtures', 'fake-claude.js');
  fs.writeFileSync(path.join(bin, 'claude'),
    '#!/bin/sh\n'
    + 'case "$1" in --version) echo "2.1.316 (Claude Code)"; exit 0;; esac\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `export HG_FAKE_CLAUDE_ARGV_OUT="${tmp}/$name.argv"\n`
    + 'case "$name" in\n'
    + '  *dies*) export HG_FAKE_CLAUDE_DIE_WITH_RESUME=1 ;;\n'
    + '  *draft*) export HG_FAKE_CLAUDE_TYPED="half a thought" ;;\n'
    + `  *exitasks*) export HG_FAKE_CLAUDE_EXIT_ASKS_FILE="${tmp}/$name.exit-asks" ;;\n`
    + 'esac\n'
    + `exec ${process.execPath} ${fake} "$@"\n`, { mode: 0o755 });

  // The install beside the binary, which is where the daemon reads the Claude Code
  // version from (it never runs `claude --version` for it).
  fs.writeFileSync(path.join(bin, 'package.json'), JSON.stringify({ name: '@anthropic-ai/claude-code', version: '2.1.316' }));
  baseEnv = {
    ...process.env,
    PATH: `${bin}:${process.env.PATH}`,
    HOME: home,
    HUGINN_APPD_PORT: String(PORT),
    HUGINN_APPD_BIND: '127.0.0.1',
    HUGINN_APPD_DATA: path.join(tmp, 'data'),
    HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
    HUGINN_APPD_STATE_DIR: stateDir,
    HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    HUGINN_APPD_WORKDIR: tmp,
    HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
    HUGINN_APPD_PRESSURE_FILE: path.join(tmp, 'pressure'),
    HUGINN_APPD_RUNTIME_RECHECK_MS: '400',
    HUGINN_APPD_CYCLE_GAP_MS: String(GAP_MS),
    HUGINN_APPD_RESTART_RETRY_MS: '800',
    // The backoff ladder in seconds (1.5 s, 4.5 s, 9 s, …), the stand-in's
    // `/exit` answered within a second, the summary every 5 s instead of hourly.
    HUGINN_APPD_SWEEP_BACKOFF_MS: '1500',
    HUGINN_APPD_QUIT_WAIT_MS: '1500',
    HUGINN_APPD_EXIT_DIALOG_HOLD_MS: '500',
    HUGINN_APPD_SWEEP_SUMMARY_MS: '5000',
    HG_FAKE_CLAUDE_BOOT_MS: String(BOOT_MS),
    HG_FAKE_CLAUDE_BANNER_MS: String(BANNER_MS),
  };
  execFileSync('tmux', ['-L', TMUX_SOCK, 'start-server', ';', 'set-option', '-g', 'exit-empty', 'off'], { env: baseEnv });
  logFile = path.join(tmp, 'daemon.log');
  daemon = startDaemon();   // cycleOnDeploy at its default: on
  await daemonUp();
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(async () => {
  for (const c of clients) { try { c.kill('SIGTERM'); } catch { /* gone */ } }
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('cycleOnDeploy is on by default, and the sweep cycles stale sessions one at a time, oldest first, skipping an attached one', async () => {
  assert.equal((await status()).cycleOnDeploy, true);
  const first = await readySession('first');
  await wait(1_100);                      // tmux's birth time is in seconds: a clear order
  const second = await readySession('second');
  await wait(1_100);
  const watched = await readySession('watched');
  attach(watched.name);
  await until(() => tmux(['display-message', '-p', '-t', `=${watched.name}:`, '#{session_attached}']) === '1', 3_000, 50);

  const { now } = await bumpGeneration();
  for (const s of [first, second, watched]) assert.equal((await rowOf(s.name)).runtime.stale, true, `${s.name} is stale`);

  // Watch the rows while the sweep works: never two restarting at once.
  let overlap = 0;
  const seenRestarting = new Set();
  const watcher = (async () => {
    const by = Date.now() + 25_000;
    while (Date.now() < by) {
      const rs = (await rows()).filter((r) => r.runtime && r.runtime.restarting);
      if (rs.length > 1) overlap += 1;
      for (const r of rs) seenRestarting.add(r.name);
      if (seenRestarting.has(first.name) && seenRestarting.has(second.name) && rs.length === 0 && argvLines(second.name).length >= 2) break;
      await wait(40);
    }
  })();
  const both = await until(() => (argvLines(first.name).length >= 2 && argvLines(second.name).length >= 2) || null, 25_000, 100);
  await watcher;
  assert.ok(both, `both unattached stale sessions were cycled: ${JSON.stringify([argvLines(first.name), argvLines(second.name)])}`);
  assert.equal(overlap, 0, 'never two cycles at once');
  assert.deepEqual(argvLines(first.name)[1], ['--resume', first.sid]);
  assert.deepEqual(argvLines(second.name)[1], ['--resume', second.sid]);

  // Oldest first, and the gap between them.
  const log = daemonLog();
  const at = (re) => { const m = re.exec(log); return m ? log.indexOf(m[0]) : -1; };
  const firstPicked = at(new RegExp(`restart sweep: picked ${first.name} \\(`));
  const firstDone = at(new RegExp(`restart: ${first.name}: [0-9a-f]{12} -> ${now}`));
  const secondPicked = at(new RegExp(`restart sweep: picked ${second.name} \\(`));
  assert.ok(firstPicked >= 0 && firstDone > firstPicked && secondPicked > firstDone, `the order in the log:\n${log.split('\n').filter((l) => /restart/.test(l)).join('\n')}`);
  assert.ok(/restart sweep: picked \S+ \(3 stale; first in line; /.test(log), `the sweep counted the attached one as stale too:\n${log.split('\n').filter((l) => /restart sweep/.test(l)).join('\n')}`);

  // The attached one is left alone, and stays stale for a manual restart...
  await wait(GAP_MS * 2 + 1_000);
  assert.equal(argvLines(watched.name).length, 1, 'never cycled while attached');
  const wrow = await rowOf(watched.name);
  assert.equal(wrow.runtime.stale, true);
  assert.equal(wrow.runtime.restarting, false);
  for (const s of [first, second]) {
    const r = await rowOf(s.name);
    assert.equal(r.runtime.stale, false, `${s.name} is current now`);
    assert.equal(r.runtime.gen, now);
  }
  // ...until the terminal leaves, when the sweep takes it on its next pass.
  for (const c of clients) c.kill('SIGTERM');
  const later = await until(() => (argvLines(watched.name).length >= 2 ? argvLines(watched.name) : null), 20_000, 100);
  assert.ok(later, 'cycled once unattached');
  assert.deepEqual(later[1], ['--resume', watched.sid]);
});

test('the sweep waits for the lull: a loaded host cycles nothing', async () => {
  const busy = await readySession('loadhold');
  fs.writeFileSync(path.join(tmp, 'pressure'), 'some avg10=42.00 avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n');
  try {
    // Every session is stale again (the three above included), and the sweep
    // would take the oldest first — but the host is loaded, so it takes none.
    await bumpGeneration();
    await wait(GAP_MS * 4);
    assert.equal(argvLines(busy.name).length, 1, 'held by the load gate');
    assert.match(daemonLog(), new RegExp(`restart sweep: ${PFX}-\\S+ is stale, but the host is loaded`));
  } finally {
    fs.writeFileSync(path.join(tmp, 'pressure'), 'some avg10=0.00 avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n');
  }
  // The lull comes: the four stale sessions are cycled, this one in its turn.
  const starts = await until(() => (argvLines(busy.name).length >= 2 ? argvLines(busy.name) : null), 40_000, 100);
  assert.ok(starts, 'cycled once the lull came');
  assert.deepEqual(starts[1], ['--resume', busy.sid]);
});

test('the sweep takes the stale session that is at a safe point NOW, not the oldest: a draft ahead of it is skipped, not waited on', async () => {
  // The sessions above are done with; the line below is these two alone. Not
  // killed mid-cycle: a relaunch whose session vanishes under it waits out the
  // startup grace before the sweep is free again.
  assert.ok(await until(async () => ((await rows()).every((r) => !(r.runtime && r.runtime.restarting)) ? true : null), 30_000, 200), 'no cycle in flight');
  for (const s of ['first', 'second', 'watched', 'loadhold']) { try { tmux(['kill-session', '-t', `=${PFX}-${s}`]); } catch { /* gone */ } }
  const drafty = await readySession('olddraft');     // somebody's words in its box: never at a safe point
  await wait(1_100);
  const idle = await readySession('idleyoung');
  await bumpGeneration();
  // On the old rule the oldest went first and its job sat on the draft for the
  // lane's ten minutes, the sweep with it; the younger idle one was never reached.
  const starts = await until(() => (argvLines(idle.name).length >= 2 ? argvLines(idle.name) : null), 15_000, 100);
  assert.ok(starts, `the younger idle session was cycled first:\n${daemonLog().split('\n').filter((l) => /restart/.test(l)).slice(-12).join('\n')}`);
  assert.deepEqual(starts[1], ['--resume', idle.sid]);
  assert.equal(argvLines(drafty.name).length, 1, 'the one with the draft was not touched');
  assert.match(daemonLog(), new RegExp(`restart sweep: picked ${idle.name} \\(\\d+ stale; skipped ${drafty.name} draft; `), 'one line says who was skipped and why');
  assert.ok(!new RegExp(`restart: ${drafty.name}: queued`).test(daemonLog()), 'a skip at the pick is not an attempt: nothing was queued for it');
  // The draft cleared, it is taken on a later tick: a skip costs it no backoff.
  const keys = await api(`/v1/sessions/${drafty.name}/keys`, { method: 'POST', body: JSON.stringify({ keys: ['C-u'] }) });
  assert.equal(keys.status, 200, JSON.stringify(keys.body));
  const later = await until(() => (argvLines(drafty.name).length >= 2 ? argvLines(drafty.name) : null), 15_000, 100);
  assert.ok(later, 'cycled once its box was empty');
  assert.deepEqual(later[1], ['--resume', drafty.sid]);
});

test('a sweep job that cannot cycle its session yields: backed off longer each time, the next candidate taken in the same tick, and a summary line', async () => {
  // Nothing on disk or on the pane says this one has background work, so the
  // pick takes it — and its `/exit` meets Claude Code's "Background work is
  // running" prompt. The job backs out of the prompt and yields; the tick moves
  // on at once. On the old rule the prompt was left on the pane and the sweep
  // waited out its twenty seconds before anything else happened.
  const asks = await readySession('oldexitasks');
  const flag = path.join(tmp, `${asks.name}.exit-asks`);
  fs.writeFileSync(flag, '1');
  await wait(1_100);
  const next = await readySession('nextidle');
  const logBefore = daemonLog().length;
  await bumpGeneration();
  const starts = await until(() => (argvLines(next.name).length >= 2 ? argvLines(next.name) : null), 12_000, 100);
  const tail = () => daemonLog().slice(logBefore).split('\n').filter((l) => /restart/.test(l)).join('\n');
  assert.ok(starts, `the next candidate was cycled right after the yield:\n${tail()}`);
  const log = daemonLog().slice(logBefore);
  const yielded = new RegExp(`restart sweep: ${asks.name}: claude asked before exiting \\(shell · sleep 900, monitor · r5 probe\\); backed out with Esc; backed off 2 s`).exec(log);
  assert.ok(yielded, `one line: held, why, backed off:\n${tail()}`);
  const pickedAsks = log.indexOf(`restart sweep: picked ${asks.name} (`);
  const pickedNext = log.indexOf(`restart sweep: picked ${next.name} (`);
  assert.ok(pickedAsks >= 0 && pickedAsks < yielded.index && yielded.index < pickedNext, `picked, yielded, then the next pick:\n${tail()}`);
  // The stand-in repaints below rather than clearing: the prompt's rows stay in
  // the scroll, and what matters is that it is no longer the thing on screen.
  const screen = tmux(['capture-pane', '-p', '-t', `=${asks.name}:`]).split('\n');
  assert.equal(paneLib.exitDialog(screen), null, `the prompt was left on its pane:\n${screen.join('\n')}`);
  assert.equal(typing.composerDrawn(screen), true, 'its composer is back');
  // Tried again after its backoff, and backed off three times as long.
  const again = await until(() => (new RegExp(`restart sweep: ${asks.name}: .*; backed off 5 s`).test(daemonLog()) ? true : null), 15_000, 100);
  assert.ok(again, `backed off longer the second time:\n${tail()}`);
  const exits = readOr(path.join(tmp, `${asks.name}.argv.exit`)).split('\n').filter(Boolean);
  assert.ok(exits.length >= 2 && exits.every((l) => l === 'exit'), `two attempts, the prompt never answered: ${JSON.stringify(exits)}`);
  assert.ok(await until(() => (/restart sweep: \d+ stale, \d+ cycled, \d+ backed off in the last \d+ s/.test(daemonLog()) ? true : null), 12_000, 200),
    `the summary line:\n${tail()}`);
  // The work ends: cycled in its turn.
  fs.unlinkSync(flag);
  const done = await until(() => (argvLines(asks.name).length >= 2 ? argvLines(asks.name) : null), 40_000, 100);
  assert.ok(done, `cycled once the work was gone:\n${tail()}`);
  assert.deepEqual(done[1], ['--resume', asks.sid]);
});

test('cycleOnDeploy=false cycles nothing; a relaunch that fails is retried once, then the session is left stale', async () => {
  // The same host, the same sessions, a daemon told not to sweep.
  await reap(daemon);
  const logBefore = daemonLog().length;
  daemon = startDaemon({ HUGINN_APPD_CYCLE_ON_DEPLOY: '0' });
  await daemonUp();
  assert.equal((await status()).cycleOnDeploy, false);
  const quiet = await readySession('quiet');
  const dies = await readySession('dies');
  await bumpGeneration();
  await wait(GAP_MS * 3 + 1_000);
  assert.equal(argvLines(quiet.name).length, 1, 'nothing cycled on its own');
  assert.equal((await rowOf(quiet.name)).runtime.stale, true, 'and it stays stale, for a manual restart');
  assert.ok(!/restart sweep: picked/.test(daemonLog().slice(logBefore)), 'no sweep line from this daemon');

  // A manual restart whose relaunch dies: one retry after RESTART_RETRY_MS, then given up.
  assert.equal((await rowOf(dies.name)).runtime.stale, true);
  const r = await api(`/v1/sessions/${dies.name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  const gaveUp = await until(() => (new RegExp(`restart: ${dies.name}: not restarted \\(relaunch failed twice`).test(daemonLog()) ? true : null), 20_000, 100);
  assert.ok(gaveUp, `gave up after one retry:\n${daemonLog().split('\n').filter((l) => l.includes(dies.name)).join('\n')}`);
  const starts = argvLines(dies.name);
  assert.equal(starts.length, 3, `the first start and exactly two relaunch attempts: ${JSON.stringify(starts)}`);
  assert.deepEqual(starts[1], ['--resume', dies.sid]);
  assert.deepEqual(starts[2], ['--resume', dies.sid]);
  assert.match(daemonLog(), new RegExp(`restart: ${dies.name}: relaunch failed \\(claude exited to the shell\\); one retry in 1s`));
  const row = await rowOf(dies.name);
  assert.equal(row.runtime.restarting, false);
  assert.equal(row.runtime.stale, true, 'kept stale');
  assert.ok(tmux(['has-session', '-t', `=${dies.name}`]) === '', 'the tmux session is still there (at a shell)');
  // Asked again, it is tried again — the give-up is not permanent for a person —
  // and fails for what the pane is now: a shell, with no Claude to quit.
  const again = await api(`/v1/sessions/${dies.name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(again.status, 202, JSON.stringify(again.body));
  const noClaude = await until(() => (new RegExp(`restart: ${dies.name}: not restarted \\(no claude process under the pane\\)`).test(daemonLog()) ? true : null), 15_000, 100);
  assert.ok(noClaude, daemonLog().split('\n').filter((l) => l.includes(dies.name)).slice(-6).join('\n'));
  assert.equal(argvLines(dies.name).length, 3, 'nothing was relaunched into a pane with no Claude in it');
});
