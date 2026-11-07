'use strict';
// Session cycling (appd 3.19.0, 2026-11-07): the runtime generation on every
// row, `POST /v1/sessions/<name>/restart` and each of its answers, and the cycle
// itself through a stand-in `claude` — argv replayed with `--resume <id>`, the
// same tmux session, the queue held and nothing dropped — plus a project member
// relaunched with its persona, and the exemptions (no alert, no reconcile drop).
//
// A Claude Code session loads its MCP servers, hooks, settings and binary at
// START, so a session started before a deploy keeps the old wiring until it is
// restarted (one started 10-27 still had no start_session tool on 11-07). The
// daemon hashes those inputs into a generation, marks sessions launched under
// an older one `stale`, and cycles them: `/exit` at a safe point, relaunch with
// `--resume` (lib/runtime.js; cycleSession in huginn-appd.js).
//
// SAFETY: no real `claude` is ever started. The daemon's PATH is shadowed with a
// `claude` that runs test/fixtures/fake-claude.js and answers `--version`; its
// HOME is a scratch directory with its own settings.json and .claude.json; its
// tmux is PRIVATE (`-L huginn-test-<pid>`, through the stand-ins of tmux-shim.js
// so one call can be made to fail the no-server way); every session is named
// `rst-<pid>-*` and dies with the server in after(). The Telegram relay is a
// stand-in that appends to a file. The one path outside the scratch dir is the
// tasks directory Claude Code keeps under /tmp/claude-<uid>/<slug>/ — made for a
// slug that is this test's own scratch dir, and removed.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { lyingTmux, REAL_TMUX } = require('./tmux-shim');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 13100 + pid%50 -> 13100-13149 (routes-questions holds 12800-13099).
const PORT = 13100 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `rst-${process.pid}`;
const TMUX_SOCK = `huginn-test-${process.pid}`;
const DAEMON = path.join(__dirname, '..', 'huginn-appd.js');

// The stand-in's startup phases, short: the suite must not crawl, and the queue
// poll (400 ms) still has to wait for the composer.
const BOOT_MS = 500;
const BANNER_MS = 300;

let tmp, home, dataDir, stateDir, binDir, shim, token, daemon, logFile, slug, tasksRoot, daemonEnv;
const clients = [];       // control-mode tmux clients this file attached

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const daemonLog = () => readOr(logFile);
const tmux = (args, opts = {}) => execFileSync(REAL_TMUX, ['-L', TMUX_SOCK, ...args], { encoding: 'utf8', ...opts }).trim();
/**
 * tmux as the DAEMON would run it. tmux 3.6 gives a new session's pane the PATH
 * of the client that asked for it, not the server's — so a session made by hand
 * here is made from the daemon's environment, the way `cc` runs from root's
 * shell on a server, or its pane would find the real `claude`.
 */
const tmuxAsDaemon = (args) => tmux(args, { env: daemonEnv });

/** Where a session's stand-in records what was submitted, and its argv at each start. */
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const argvFor = (name) => path.join(tmp, `${name}.argv`);
const argvLines = (name) => readOr(argvFor(name)).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));

/** What tmux was told to run in the pane, unquoted (see project-restore.test.js). */
function startCommand(name) {
  const raw = tmux(['display-message', '-p', '-t', `=${name}:`, '#{pane_start_command}']);
  const m = /^"(.*)"$/s.exec(raw);
  return m ? m[1].replace(/\\(["$\\])/g, '$1') : raw;
}
const sessionFacts = (name) => {
  const [created, panePid] = tmux(['display-message', '-p', '-t', `=${name}:`, '#{session_created}\t#{pane_pid}']).split('\t');
  return { created, panePid };
};

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

/** Poll until `fn` answers truthy, or give up after `ms` and return its last answer. */
async function until(fn, ms = 10_000, step = 100) {
  const by = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > by) return v;
    await wait(step);
  }
}

/** The transcript Claude Code would keep for this conversation, in the scratch HOME. */
function writeTranscript(sid) {
  const dir = path.join(home, '.claude', 'projects', slug);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${sid}.jsonl`);
  // A finished turn: the user's line and the answer that ended it. The send
  // queue releases a job only at such a boundary (lib/typing isBoundaryRecord);
  // a transcript ending on the question would hold the restart as `turn`.
  const now = new Date().toISOString();
  fs.writeFileSync(file, [
    JSON.stringify({ type: 'user', message: { role: 'user', content: 'hello' }, sessionId: sid, cwd: tmp, timestamp: now }),
    JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: 'hi' }], stop_reason: 'end_turn' }, sessionId: sid, cwd: tmp, timestamp: now }),
  ].join('\n') + '\n');
  return file;
}

/** The state file the title hook would write: `idle` (or another word), with the conversation id. */
function writeState(name, state, sid, transcript) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: sid, transcript, cwd: tmp, ts: Date.now(),
  }));
}

/** A session made the way the apps make one: the create route, no flags. */
async function createViaRoute(suffix) {
  const name = `${PFX}-${suffix}`;
  const r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name, cwd: tmp }) });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  return name;
}

/** A session made the way `cc` makes one, with flags: tmux itself, on the daemon's server. */
function createByHand(suffix, flags) {
  const name = `${PFX}-${suffix}`;
  tmuxAsDaemon(['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', `claude ${flags}; exec "$SHELL" -l`]);
  return name;
}

/** Wait for the stand-in's composer in a pane. */
const composerUp = (name) => until(() => /❯/.test(tmux(['capture-pane', '-p', '-t', `=${name}:`])), 8_000, 100);

/** A session with a conversation to resume: created, composer up, state file idle, transcript on disk. */
async function readySession(suffix, { byHand = null } = {}) {
  const name = byHand === null ? await createViaRoute(suffix) : createByHand(suffix, byHand);
  assert.ok(await composerUp(name), `${name}: the stand-in never drew its composer`);
  const sid = crypto.randomUUID();
  writeState(name, 'idle', sid, writeTranscript(sid));
  return { name, sid };
}

/** A control-mode client attached to the session, kept open: `#{session_attached}` counts it. */
function attach(name) {
  const c = spawn(REAL_TMUX, ['-L', TMUX_SOCK, '-C', 'attach-session', '-t', `=${name}`], { stdio: ['pipe', 'ignore', 'ignore'] });
  clients.push(c);
  return c;
}

/** Was this session cycled: the stand-in started a second time, with these flags? */
const cycled = (name) => until(() => (argvLines(name).length >= 2 ? argvLines(name) : null), 15_000, 100);

function writePressure(avg10) {
  fs.writeFileSync(path.join(tmp, 'pressure'),
    `some avg10=${avg10} avg60=0.00 avg300=0.00 total=0\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n`);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-restart-'));
  home = path.join(tmp, 'home');
  dataDir = path.join(tmp, 'data');
  stateDir = path.join(tmp, 'state');
  for (const d of [home, dataDir, stateDir, path.join(home, '.claude')]) fs.mkdirSync(d, { recursive: true });
  slug = tmp.replace(/\//g, '-');
  tasksRoot = path.join('/tmp', `claude-${process.getuid()}`, slug);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // What a session loads at start, in the daemon's HOME: a settings file with a
  // hook, and the user-scope MCP registration install.sh makes.
  fs.writeFileSync(path.join(home, '.claude', 'settings.json'), JSON.stringify({
    hooks: { Stop: [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/huginn-claude-title' }] }] },
    statusLine: { type: 'command', command: 'huginn-statusline' },
    fastMode: false,
  }, null, 2));
  fs.writeFileSync(path.join(home, '.claude.json'), JSON.stringify({
    numStartups: 3,
    mcpServers: { huginn: { type: 'stdio', command: process.execPath, args: ['/opt/huginn-appd/mcp/huginn-mcp.js'] } },
  }));
  writePressure('0.00');

  // The Telegram relay stand-in: the daemon sources it with `--message <text>`.
  fs.writeFileSync(path.join(tmp, 'tg.sh'), `#!/bin/bash\nprintf '%s\\n' "$2" >> '${path.join(tmp, 'tg.log')}'\n`, { mode: 0o755 });

  // ⚠ `claude` IS SHADOWED. The daemon runs `claude …; exec "$SHELL" -l` in every
  // pane it starts or relaunches, so the stand-in has to be a `claude` earlier on
  // its PATH. It answers `--version` (the daemon reads it for the row's
  // `currentClaude`) and otherwise runs fake-claude.js with the flags it was
  // given, recording them per session. Two names ask for a special pane: `draft`
  // paints with somebody's unsent text, `trust` draws the folder-trust dialog.
  binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  const fake = path.join(__dirname, 'fixtures', 'fake-claude.js');
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\n'
    + 'case "$1" in --version) echo "2.1.316 (Claude Code)"; exit 0;; esac\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `export HG_FAKE_CLAUDE_ARGV_OUT="${tmp}/$name.argv"\n`
    + 'case "$name" in\n'
    + '  *draft*) export HG_FAKE_CLAUDE_TYPED="half a thought" ;;\n'
    + '  *trust*) export HG_FAKE_CLAUDE_TRUST=1 ;;\n'
    + 'esac\n'
    + `exec ${process.execPath} ${fake} "$@"\n`, { mode: 0o755 });
  // The lying tmux and systemctl, for the one 503 case; its own `claude` is
  // removed so the stand-in above is the one found.
  // The install beside the binary, which is where the daemon reads the Claude Code
  // version from (it never runs `claude --version` for it).
  fs.writeFileSync(path.join(binDir, 'package.json'), JSON.stringify({ name: '@anthropic-ai/claude-code', version: '2.1.316' }));
  shim = lyingTmux(path.join(tmp, 'shim'), { sock: TMUX_SOCK });
  fs.rmSync(path.join(tmp, 'shim', 'claude'));

  const env = daemonEnv = {
    ...process.env,
    ...shim.env,
    PATH: `${path.join(tmp, 'shim')}:${binDir}:${process.env.PATH}`,
    HOME: home,
    HUGINN_APPD_PORT: String(PORT),
    HUGINN_APPD_BIND: '127.0.0.1',
    HUGINN_APPD_DATA: dataDir,
    HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
    HUGINN_APPD_STATE_DIR: stateDir,
    HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    HUGINN_APPD_WORKDIR: tmp,
    HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
    HUGINN_APPD_TELEGRAM_SCRIPT: path.join(tmp, 'tg.sh'),
    HUGINN_APPD_PRESSURE_FILE: path.join(tmp, 'pressure'),
    // The sweep is the other file's subject; here every restart is asked for.
    HUGINN_APPD_CYCLE_ON_DEPLOY: '0',
    HUGINN_APPD_RUNTIME_RECHECK_MS: '400',
    HUGINN_APPD_RECONCILE_MS: '600',
    HUGINN_APPD_ABSENT_CONFIRM_MS: '600',
    HG_FAKE_CLAUDE_BOOT_MS: String(BOOT_MS),
    HG_FAKE_CLAUDE_BANNER_MS: String(BANNER_MS),
  };
  // The tmux server is started HERE, with the daemon's environment, so a pane
  // made by hand below finds the same `claude` the daemon's panes do.
  execFileSync(REAL_TMUX, ['-L', TMUX_SOCK, 'start-server', ';', 'set-option', '-g', 'exit-empty', 'off'], { env });

  logFile = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(logFile, 'a');
  daemon = spawn(process.execPath, [DAEMON], { env, stdio: ['ignore', logFd, logFd] });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
  // Alerts on, always to the relay, so a cycle that announced anything would show in tg.log.
  const al = await api('/v1/alerts', { method: 'POST', body: JSON.stringify({ enabled: true, mode: 'always' }) });
  assert.equal(al.status, 200, JSON.stringify(al.body));
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
  for (const c of clients) { try { c.kill('SIGTERM'); } catch { /* gone */ } }
  try { tmux(['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tasksRoot) fs.rmSync(tasksRoot, { recursive: true, force: true });
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------ the generation

test('the status reports the generation, the Claude Code version and cycleOnDeploy', async () => {
  const st = await status();
  assert.equal(st.cycleOnDeploy, false, 'this daemon runs with HUGINN_APPD_CYCLE_ON_DEPLOY=0');
  assert.ok(st.runtime && /^[0-9a-f]{12}$/.test(st.runtime.gen), JSON.stringify(st.runtime));
  assert.equal(st.runtime.claude, '2.1.316', 'read from the package.json beside the binary');
  assert.match(daemonLog(), /runtime: generation [0-9a-f]{12} \(start; claude /);
});

test('a session the daemon launches records the current generation and reads current', async () => {
  const { name } = await readySession('fresh');
  const st = await status();
  const row = await until(async () => { const r = await rowOf(name); return r && r.runtime && r.runtime.gen ? r : null; }, 5_000);
  assert.ok(row, 'the row carries runtime');
  assert.deepEqual(row.runtime, {
    stale: false, gen: st.runtime.gen, current: st.runtime.gen, claude: '2.1.316', currentClaude: '2.1.316', restarting: false,
  });
});

test('the generation changes when a session\'s inputs change, and a session launched under the old one reads stale', async () => {
  const before = (await status()).runtime.gen;
  const { name: older } = await readySession('older');
  // A hook registered since the session started — what install-hooks does on a deploy.
  const settingsFile = path.join(home, '.claude', 'settings.json');
  const settings = JSON.parse(fs.readFileSync(settingsFile, 'utf8'));
  settings.hooks.PostModelSwitch = [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/huginn-modelswitch-hook' }] }];
  fs.writeFileSync(settingsFile, JSON.stringify(settings, null, 2));
  const after = await until(async () => { const g = (await status()).runtime.gen; return g !== before ? g : null; }, 5_000, 100);
  assert.ok(after, 'the generation is re-read within the recheck interval');
  assert.match(daemonLog(), new RegExp(`runtime: generation ${before} -> ${after} \\(re-read`));
  const row = await rowOf(older);
  assert.equal(row.runtime.stale, true, 'launched under the old generation');
  assert.equal(row.runtime.gen, before);
  assert.equal(row.runtime.current, after);
  // A session launched now is on the new one.
  const { name: newer } = await readySession('newer');
  const nrow = await until(async () => { const r = await rowOf(newer); return r && r.runtime.gen === after ? r : null; }, 5_000);
  assert.ok(nrow && nrow.runtime.stale === false, JSON.stringify(nrow && nrow.runtime));
  // And the one key the daemon writes itself does not count: a fast-mode toggle is not a deploy.
  settings.fastMode = true;
  fs.writeFileSync(settingsFile, JSON.stringify(settings, null, 2));
  await wait(1_000);
  assert.equal((await status()).runtime.gen, after, 'fastMode is excluded from the hash');
});

// ------------------------------------------------------------------ the route

test('404 for a session that does not exist, 409 no-claude for one with nothing to resume', async () => {
  const gone = await api(`/v1/sessions/${PFX}-nobody/restart`, { method: 'POST', body: '{}' });
  assert.equal(gone.status, 404);
  // A pane whose Claude has written no state file (no hooks): nothing to resume.
  const name = await createViaRoute('shellish');
  await composerUp(name);
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.equal(r.body.reason, 'no-claude');
  assert.match(r.body.error, /nothing to resume/);
});

test('503 when tmux cannot be observed', async () => {
  const { name } = await readySession('unseen');
  shim.set('noserver', ['display-message']);
  try {
    const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
    assert.equal(r.status, 503, JSON.stringify(r.body));
  } finally { shim.clear('noserver'); }
  assert.equal((await rowOf(name)).runtime.restarting, false, 'nothing was queued on a 503');
});

test('202 blockedBy running: a turn in progress holds the restart until the state file says idle', async () => {
  const { name, sid } = await readySession('busy');
  writeState(name, 'running', sid, readOr(path.join(stateDir, name)) && JSON.parse(readOr(path.join(stateDir, name))).transcript);
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.deepEqual(r.body, { queued: true, blockedBy: 'running' });
  await wait(1_200);
  assert.equal(argvLines(name).length, 1, 'still the first start: nothing was cycled mid-turn');
  const again = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(again.status, 409, '409 while one is queued');
  assert.equal(again.body.reason, 'restarting');
  // The turn ends.
  writeState(name, 'idle', sid, JSON.parse(readOr(path.join(stateDir, name))).transcript);
  const starts = await cycled(name);
  assert.ok(starts, 'cycled once the turn ended');
  assert.deepEqual(starts[1], ['--resume', sid]);
});

test('202 blockedBy dialog: a question waiting, or a dialog on screen, holds it', async () => {
  const { name, sid } = await readySession('asking');
  writeState(name, 'attention', sid, JSON.parse(readOr(path.join(stateDir, name))).transcript);
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'dialog');
  // And the folder-trust dialog, read off the pane with no state word saying so.
  const trusty = await createViaRoute('trust');
  await until(() => /Yes, I trust this folder/.test(tmux(['capture-pane', '-p', '-t', `=${trusty}:`])), 8_000, 100);
  const tsid = crypto.randomUUID();
  writeState(trusty, 'idle', tsid, writeTranscript(tsid));
  const t = await api(`/v1/sessions/${trusty}/restart`, { method: 'POST', body: '{}' });
  assert.equal(t.status, 202, JSON.stringify(t.body));
  assert.equal(t.body.blockedBy, 'dialog');
});

test('202 blockedBy draft: unsent text in the box holds it, and a person\'s keys get past the held job', async () => {
  const { name, sid } = await readySession('draft');
  assert.match(tmux(['capture-pane', '-p', '-t', `=${name}:`]), /half a thought/, 'the stand-in painted with a draft');
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'draft');
  await wait(1_000);
  assert.equal(argvLines(name).length, 1, 'held: nothing cycled over somebody\'s words');
  // The person clears their draft from the Screen tab: a keystroke through the
  // queue, behind the held job — which must not hold it (that C-u is the only
  // thing that can end the hold).
  const keys = await api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ keys: ['C-u'] }) });
  assert.equal(keys.status, 200, JSON.stringify(keys.body));
  const starts = await cycled(name);
  assert.ok(starts, 'cycled once the box was empty');
  assert.deepEqual(starts[1], ['--resume', sid]);
});

test('202 blockedBy background: a background agent holds it until it is done', async () => {
  const { name, sid } = await readySession('bgwork');
  // Claude Code's tasks directory for this conversation, with one live agent in
  // it: a `.output` symlink whose target is fresh (lib/tasks.js).
  const tasks = path.join(tasksRoot, sid, 'tasks');
  fs.mkdirSync(tasks, { recursive: true });
  fs.writeFileSync(path.join(tasks, 'agent-a.log'), 'working\n');
  fs.symlinkSync(path.join(tasks, 'agent-a.log'), path.join(tasks, 'agent-a.output'));
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'background');
  await wait(1_000);
  assert.equal(argvLines(name).length, 1, 'held while the agent runs');
  fs.unlinkSync(path.join(tasks, 'agent-a.output'));
  const starts = await cycled(name);
  assert.ok(starts, 'cycled once the agent was gone');
  assert.deepEqual(starts[1], ['--resume', sid]);
});

test('202 blockedBy attached: a terminal on the session holds it; force skips that hold and only that hold', async () => {
  const { name, sid } = await readySession('watched');
  const client = attach(name);
  await until(() => tmux(['display-message', '-p', '-t', `=${name}:`, '#{session_attached}']) === '1', 3_000, 50);
  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, 'attached');
  await wait(1_000);
  assert.equal(argvLines(name).length, 1, 'held while attached');
  client.kill('SIGTERM');
  const starts = await cycled(name);
  assert.ok(starts, 'cycled once the terminal left');
  assert.deepEqual(starts[1], ['--resume', sid]);

  // Forced: cycled under the attached client, which stays attached (the tmux
  // session is kept; only its pane is respawned).
  const forced = await readySession('forced');
  const c2 = attach(forced.name);
  await until(() => tmux(['display-message', '-p', '-t', `=${forced.name}:`, '#{session_attached}']) === '1', 3_000, 50);
  const f = await api(`/v1/sessions/${forced.name}/restart`, { method: 'POST', body: JSON.stringify({ force: true }) });
  assert.equal(f.status, 202, JSON.stringify(f.body));
  assert.equal(f.body.blockedBy, null);
  const fstarts = await cycled(forced.name);
  assert.ok(fstarts, 'cycled while attached');
  assert.deepEqual(fstarts[1], ['--resume', forced.sid]);
  assert.equal(tmux(['display-message', '-p', '-t', `=${forced.name}:`, '#{session_attached}']), '1', 'the client is still attached');
  c2.kill('SIGTERM');
  // A forced restart skips nothing else: a turn in progress still holds it.
  const b = await readySession('forcedbusy');
  writeState(b.name, 'running', b.sid, JSON.parse(readOr(path.join(stateDir, b.name))).transcript);
  const fb = await api(`/v1/sessions/${b.name}/restart`, { method: 'POST', body: JSON.stringify({ force: true }) });
  assert.equal(fb.status, 202);
  assert.equal(fb.body.blockedBy, 'running');
});

test('202 blockedBy load: the host outside the cuts\' cpu.pressure lull holds an unforced restart', async () => {
  const { name, sid } = await readySession('loaded');
  writePressure('37.10');
  try {
    const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
    assert.equal(r.status, 202, JSON.stringify(r.body));
    assert.equal(r.body.blockedBy, 'load');
    await wait(1_000);
    assert.equal(argvLines(name).length, 1, 'held while the host is loaded');
  } finally { writePressure('0.00'); }
  const starts = await cycled(name);
  assert.ok(starts, 'cycled once the lull came');
  assert.deepEqual(starts[1], ['--resume', sid]);
});

// ------------------------------------------------------------------ the cycle

test('the cycle: /exit, then the same tmux session relaunched with the captured argv and --resume <id>; the queue held, nothing dropped', async () => {
  const old = '00000000-0000-4000-8000-000000000000';
  const { name, sid } = await readySession('cyc', { byHand: `--effort high --name demo/x --resume ${old}` });
  assert.deepEqual(argvLines(name), [['--effort', 'high', '--name', 'demo/x', '--resume', old]], 'the first start, as a `cc`-like session');
  const before = sessionFacts(name);
  // Born since the current generation began, it reads current — until the
  // generation moves on under it: another hook lands, as a deploy's install-hooks
  // does, and the session is on the old one.
  const was = (await status()).runtime.gen;
  const settingsFile = path.join(home, '.claude', 'settings.json');
  const settings = JSON.parse(fs.readFileSync(settingsFile, 'utf8'));
  settings.hooks.PermissionRequest = [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/huginn-permission-hook' }] }];
  fs.writeFileSync(settingsFile, JSON.stringify(settings, null, 2));
  assert.ok(await until(async () => (await status()).runtime.gen !== was, 5_000, 100), 'the generation moved on');
  const stale = await rowOf(name);
  assert.equal(stale.runtime.stale, true, 'launched under the generation before this one');

  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.blockedBy, null, 'idle, no dialog, empty box, nothing in the background, unattached, host quiet');
  // While its Claude is away, the row says so — and a message sent then is HELD, not lost.
  const mid = await until(async () => { const row = await rowOf(name); return row && row.runtime.restarting ? row : null; }, 10_000, 40);
  assert.ok(mid, 'the row reads restarting during the cycle');
  const msg = await api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ text: 'sent while away', keys: ['Enter'] }) });
  assert.equal(msg.status, 200, JSON.stringify(msg.body));
  assert.equal(msg.body.delivered, false, 'held behind the restart');

  const starts = await cycled(name);
  assert.ok(starts, 'the stand-in started a second time');
  assert.deepEqual(starts[1], ['--resume', sid, '--effort', 'high', '--name', 'demo/x'],
    'the captured flags, --resume <this conversation> in place of the old one');
  assert.equal(readOr(`${argvFor(name)}.exit`), 'exit\n', 'the first Claude was asked to quit with /exit');
  const after = sessionFacts(name);
  assert.equal(after.created, before.created, 'the same tmux session (its birth time is unchanged)');
  assert.notEqual(after.panePid, before.panePid, 'with a new process in its pane');
  assert.match(startCommand(name), new RegExp(`^claude --resume ${sid} --effort high --name demo/x; exec "\\$SHELL" -l$`));

  const row = await until(async () => { const x = await rowOf(name); return x && !x.runtime.restarting && !x.runtime.stale ? x : null; }, 10_000);
  assert.ok(row, 'the row is current and no longer restarting');
  assert.equal(row.runtime.gen, (await status()).runtime.gen);
  await until(() => /sent while away/.test(readOr(outFor(name))), 10_000, 100);
  assert.equal(readOr(outFor(name)), 'sent while away\n', 'the held message went in once the composer was back, exactly once');
  assert.match(daemonLog(), new RegExp(`restart: ${name}: (none|[0-9a-f]{12}) -> ${row.runtime.gen}`), 'one line says what happened');
});

test('a project member is relaunched the way the reboot restore launches it: persona, effort, no model on a resume', async () => {
  const projectId = crypto.randomUUID();
  const sid = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const member = `${PFX}-rst-api`;
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${projectId}.json`), JSON.stringify({
    id: projectId, name: 'Restore', slug: 'rst', kind: 'software', status: 'active',
    brief: 'bring the cluster back', cwd: tmp,
    lead: { role: 'lead', name: `${PFX}-rst-lead`, claudeName: 'rst/lead', sessionId: crypto.randomUUID(), spawnedAt: now - 600, endedAt: null },
    members: [{
      role: 'api', name: member, claudeName: 'rst/api', sessionId: sid, cwd: tmp,
      model: 'sonnet', effort: 'low', mode: null, firstPrompt: 'the api part', spawnedAt: now - 500, endedAt: null,
    }],
    manifest: { tag: 'abcde12345', rev: 1, receivedAt: now - 550, type: 'software', scope: 'the cluster', summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1 },
    endedReason: null, endedAt: null, createdAt: now - 600, updatedAt: now - 500, rev: 4,
  }, null, 2));
  // The lead's session too, so the reconcile has nothing to archive the project for.
  tmuxAsDaemon(['new-session', '-d', '-s', `${PFX}-rst-lead`, '-c', tmp, 'claude --name rst/lead; exec "$SHELL" -l']);
  const name = createByHand('rst-api', `--name rst/api --model sonnet --effort low --append-system-prompt-file ${tmp}/old-persona.md`);
  assert.equal(name, member);
  await composerUp(name);
  writeState(name, 'idle', sid, writeTranscript(sid));

  const r = await api(`/v1/sessions/${name}/restart`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  const starts = await cycled(name);
  assert.ok(starts, 'relaunched');
  const persona = path.join(dataDir, 'projects', 'render', `${projectId}.api.md`);
  assert.deepEqual(starts[1], ['--resume', sid, '--name', 'rst/api', '--effort', 'low', '--append-system-prompt-file', persona],
    'persona rewritten from the project, effort carried, no --model on a resume');
  assert.ok(fs.existsSync(persona), 'the persona file was written');
  assert.match(fs.readFileSync(persona, 'utf8'), /api/);
  assert.match(daemonLog(), new RegExp(`restart: ${name}: cycling \\(route\\): claude pid \\d+, relaunch by project`));

  // Two reconcile ticks later the member is still there and the project still active.
  await wait(1_500);
  const p = await api(`/v1/projects/${projectId}`);
  assert.equal(p.status, 200, JSON.stringify(p.body));
  const roles = (p.body.members || p.body.project && p.body.project.members || []).map((m) => m.role);
  assert.ok(roles.includes('api'), `the member survived the restart: ${JSON.stringify(p.body).slice(0, 300)}`);
  assert.notEqual((p.body.status || (p.body.project && p.body.project.status)), 'archived');
});

test('no alert for any cycle: nothing finished and nothing was answered', async () => {
  // Alerts have been on since before the first restart. Give the watcher a tick.
  await wait(1_500);
  const tg = readOr(path.join(tmp, 'tg.log'));
  assert.ok(!/finished|answered/.test(tg), `the relay saw: ${tg.slice(0, 400)}`);
  assert.ok(!/session_finished|session_resolved/.test(daemonLog().split('\n').filter((l) => /alerts: sent|alerts: held/.test(l)).join('\n')),
    'no session alert was decided during the cycles');
  // And the sessions list never lost a cycled session.
  assert.ok(!/session registry: dropped/.test(daemonLog()), 'no registry row was pruned');
  assert.ok(!/project rst: dropped|project rst: archived/.test(daemonLog()), 'the project reconcile dropped nothing');
});
