'use strict';
// What a tmux NAME carries, and for how long.
//
// Most of what the daemon keeps about a session is keyed by its tmux name: the
// state file, the send queue, the soft-end and archive intents, the duplicate
// memory, a project's member table. A name outlives the session that held it —
// a session is killed at a terminal, exits to its shell, is renamed, or a new
// one is made under the same name minutes later — and every case here is a way
// something written down for one session reached a different one: a message
// typed into the wrong conversation, a brand-new session killed seconds after
// it started, a live session's state wiped by somebody else's launch.
//
// SAFETY: no real `claude`. The daemon's PATH is shadowed with a stand-in (the
// fixture `fake-claude.js`, or a small slow-echo composer written below), and
// the panes made directly run `cat >/dev/null`, so nothing typed into any of
// them can execute. NOT THE OPERATOR'S TMUX: a private `-L` socket, every
// session made here is killed in after(), and the server with it. HOME and the
// Claude config directory are redirected into the scratch dir.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 12370 + pid%20 -> 12370-12389.
const PORT = 12370 + (process.pid % 20);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

/** The stand-in's phases: an empty pane, caret-free console text, then the box. */
const BOOT_MS = 600;
const BANNER_MS = 300;
const UP_MS = BOOT_MS + BANNER_MS;
/** How long the slow composer takes to paint what was pasted into it. */
const SLOW_ECHO_MS = 900;

let tmp, stateDir, dataDir, home, claudeDir, token, daemon, daemonLog, binDir;
const madeSessions = new Set();

function sh(cmd, args, opts = {}) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8', ...opts });
}
function alive(name) {
  try { sh('tmux', ['has-session', '-t', `=${name}`]); return true; } catch { return false; }
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Math.floor(Date.now() / 1000);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
/** Where the stand-in records what was SUBMITTED to it, by the name it was launched under. */
const outFor = (name) => path.join(tmp, `${name}.submitted`);

/** Which stand-in a session launched under this name gets; read by the shim at launch. */
function standIn(name, mode) {
  const f = path.join(tmp, 'mode', name);
  if (mode) fs.writeFileSync(f, mode);
  else fs.rmSync(f, { force: true });
}

/** A pane that cannot execute anything typed into it, made the way `cc` would: outside the daemon. */
function mkPane(name, cmd = 'cat >/dev/null') {
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40', cmd]);
  madeSessions.add(name);
  return name;
}

/** What huginn-claude-title writes on every state-bearing hook event. */
function writeState(name, state, { sessionId = null, transcript = null, ts = Date.now() } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state, sessionId, transcript, cwd: tmp, ts }));
}

/** A transcript the archive card can be built from. */
function writeTranscript(id, lastText = 'all done here') {
  const dir = path.join(tmp, 'transcripts');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${id}.jsonl`);
  const stamp = new Date().toISOString();
  fs.writeFileSync(file, [
    { type: 'user', message: { content: 'wrap this up' }, timestamp: stamp, cwd: tmp },
    { type: 'assistant', message: { model: 'claude-opus-4-5', content: [{ type: 'text', text: lastText }] },
      timestamp: stamp, cwd: tmp },
  ].map((l) => JSON.stringify(l)).join('\n') + '\n');
  return file;
}

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** Create a session through the route a client uses, and remember it. */
async function createSession(name) {
  madeSessions.add(name);
  const r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name }) });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  return r.body.name;
}

/** Send text the way a composer does: the Enter rides with the paste. */
function send(name, text) {
  return api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }) });
}

async function untilTrue(fn, ms = 10_000, step = 100) {
  const until = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > until) return false;
    await wait(step);
  }
}

async function archives() {
  const r = await api('/v1/archive');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body.archives;
}

async function listed(name) {
  const r = await api('/v1/sessions');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body.sessions.some((s) => s.name === name);
}

/**
 * The idle the settle timer kills on, held past its stability gate. Two writes
 * more than KILL_STABLE_MS apart, because each write is what drives a tick.
 */
async function settle(name, opts) {
  writeState(name, 'idle', opts);
  await wait(3_400);
  writeState(name, 'idle', opts);
  await wait(1_500);
}

// -------------------------------------------------------------- projects

function manifestBlock(tag, sessions) {
  return ['```huginn-project ' + tag,
    JSON.stringify({ type: 'software', scope: 'the cluster', summary: 'two parts', sessions }),
    '```'].join('\n');
}

/** A lead's turn ending in a fenced project block, as the lead's transcript holds it. */
function writeLeadTranscript(id, text) {
  const dir = path.join(tmp, 'transcripts');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${id}.jsonl`);
  const stamp = new Date().toISOString();
  fs.writeFileSync(file, [
    JSON.stringify({ type: 'user', message: { content: 'size this' }, origin: { kind: 'human' }, promptSource: 'typed', timestamp: stamp, cwd: tmp }),
    JSON.stringify({ type: 'assistant', message: { model: 'claude-opus-4-5', content: [{ type: 'text', text }] }, timestamp: stamp, cwd: tmp }),
  ].join('\n') + '\n');
  return file;
}

/** The tag lives in the lead's persona and the store, never on the wire. */
function tagOf(id) {
  const m = /THIS PROJECT'S TAG: (\S+)/.exec(readOr(path.join(dataDir, 'projects', 'render', `${id}.lead.md`)));
  return m ? m[1] : null;
}

async function projectUntil(id, ok, ms = 15_000) {
  const until = Date.now() + ms;
  for (;;) {
    const r = await api(`/v1/projects/${id}`);
    if (r.status === 200 && r.body && ok(r.body.project)) return r.body.project;
    if (Date.now() > until) throw new Error(`project ${id} never got there: ${JSON.stringify(r.body)}`);
    await wait(200);
  }
}

// ------------------------------------------------------------------ setup

/**
 * A composer that takes its time to show what was pasted into it.
 *
 * The real TUI paints a paste within a frame or two; this one waits
 * SLOW_ECHO_MS, so a delivery is still IN FLIGHT — pasted, its Enter not yet
 * pressed — for most of a second, and a test can land a rename inside it
 * every time instead of one time in thirty. Same box shape as fake-claude.js,
 * which is the shape lib/typing.js reads.
 */
const SLOW_CLAUDE = `'use strict';
const fs = require('node:fs');
const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const ECHO_MS = Number(process.env.HG_SLOW_ECHO_MS || 900);
const RULE = '\\u2500'.repeat(70);
const draw = (t) => process.stdout.write(RULE + '\\n\\u276f ' + t + '\\n' + RULE + '\\n'
  + '  [fake] Fable 5.1 \\u00b7 ctx 4%\\n  \\u23f5\\u23f5 auto mode on (shift+tab to cycle)\\n');
const clean = (s) => String(s).replace(/\\u001b?\\[20[01]~/g, '');
let buf = '';
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  buf += d;
  for (;;) {
    const i = buf.search(/[\\r\\n]/);
    if (i < 0) break;
    const line = clean(buf.slice(0, i));
    buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    fs.appendFileSync(OUT, line + '\\n');
    process.stdout.write('\\n\\u276f ' + line + '\\n\\n\\u25cf submitted\\n');
    draw('');
  }
  const shown = clean(buf);
  if (shown) setTimeout(() => { if (clean(buf) === shown) draw(shown); }, ECHO_MS);
});
process.stdin.resume();
draw('');
`;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-reuse-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  home = path.join(tmp, 'home');
  claudeDir = path.join(tmp, 'claude');
  for (const d of [stateDir, dataDir, home, claudeDir, path.join(tmp, 'mode')]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // ⚠ `claude` IS SHADOWED. The create route and the project launcher both run
  // a literal `claude …; exec "$SHELL" -l`, so a stand-in earlier on PATH is the
  // only way in front of it. Which stand-in is read off a marker file named for
  // the session, AT LAUNCH — so one name can come back as a different program.
  binDir = path.join(tmp, 'bin');
  fs.mkdirSync(binDir);
  const fake = path.join(__dirname, 'fixtures', 'fake-claude.js');
  const slow = path.join(tmp, 'slow-claude.js');
  fs.writeFileSync(slow, SLOW_CLAUDE);
  fs.writeFileSync(path.join(binDir, 'claude'),
    '#!/bin/sh\n'
    + 'name=$(tmux display-message -p "#S" 2>/dev/null)\n'
    + `export HG_FAKE_CLAUDE_OUT="${tmp}/$name.submitted"\n`
    + `mode=$(cat "${tmp}/mode/$name" 2>/dev/null)\n`
    + 'case "$mode" in\n'
    + `  slow) exec ${process.execPath} ${slow} ;;\n`
    + '  trust) export HG_FAKE_CLAUDE_TRUST=1 ;;\n'
    + 'esac\n'
    + `exec ${process.execPath} ${fake}\n`, { mode: 0o755 });
  // On the test process too: whichever tmux call comes first starts the server,
  // and the server hands its environment to every pane it opens.
  process.env.PATH = `${binDir}:${process.env.PATH}`;

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HOME: home,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_PROJECTS_DIR: path.join(tmp, 'projects'),
      HG_FAKE_CLAUDE_BOOT_MS: String(BOOT_MS),
      HG_FAKE_CLAUDE_BANNER_MS: String(BANNER_MS),
      HG_SLOW_ECHO_MS: String(SLOW_ECHO_MS),
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {   // 30 s cap: a loaded host has pushed start past 10 s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // refuses our token, and every call below would 401 for a reason that is not
  // a code bug. Ask an authenticated question first and say what is wrong.
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it refuses our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

/** SIGTERM, then SIGKILL, and wait until the process is reaped. */
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
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------- a member spawn

test('a member spawn leaves a live session that already has the name alone', async () => {
  // Project `web` with role `api-lead` and project `web-api`'s lead are both
  // `web-api-lead`, and the role grammar allows both. Here that name is held by
  // a live session sitting on a question: its state file, its question sidecar
  // and a live permission hand-off are what the phone's "needs you" stands on.
  const squatter = mkPane('web-api-lead');
  const id = crypto.randomUUID();
  writeState(squatter, 'attention', { sessionId: id });
  fs.mkdirSync(path.join(stateDir, '.ask'), { recursive: true });
  fs.writeFileSync(path.join(stateDir, '.ask', squatter), JSON.stringify({ v: 1, tool: 'AskUserQuestion', sessionId: id, ts: now(), input: {} }));
  const perm = path.join(stateDir, '.perm', squatter);
  fs.mkdirSync(perm, { recursive: true });
  fs.writeFileSync(path.join(perm, 'req-1.json'), JSON.stringify({ id: 'req-1', pid: process.pid, ts: Date.now() }));

  const r = await api('/v1/projects', {
    method: 'POST', body: JSON.stringify({ name: 'Web', kind: 'infra', brief: 'a cluster with an api lead', cwd: tmp }),
  });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  madeSessions.add(r.body.lead.name);
  const leadId = crypto.randomUUID();
  writeState(r.body.lead.name, 'idle', {
    sessionId: leadId,
    transcript: writeLeadTranscript(leadId, manifestBlock(tagOf(r.body.id), [
      { role: 'api-lead', firstPrompt: 'own the api', cwd: null },
    ])),
  });
  await projectUntil(r.body.id, (p) => p.status === 'proposed');

  const s = await api(`/v1/projects/${r.body.id}/spawn`, {
    method: 'POST', body: JSON.stringify({ approve: true, manifestRev: 1 }),
  });
  assert.equal(s.status, 200, JSON.stringify(s.body));
  assert.deepEqual(s.body.failed.map((f) => f.role), ['api-lead'], 'the member is refused: the name is taken');
  assert.match(s.body.failed[0].reason, /duplicate session/);

  assert.ok(alive(squatter), 'the session holding the name is untouched');
  assert.ok(fs.existsSync(path.join(stateDir, squatter)), 'and so is its state file');
  assert.ok(fs.existsSync(path.join(stateDir, '.ask', squatter)), 'its question sidecar');
  assert.ok(fs.existsSync(path.join(perm, 'req-1.json')), 'and its permission hand-off');
  const row = (await api('/v1/sessions')).body.sessions.find((x) => x.name === squatter);
  assert.equal(row && row.state, 'attention', 'it still reads as needing an answer');
});

// ------------------------------------------------------------- a rename

test('a session renamed while its archive is pending is archived when it settles, not just ended', async () => {
  // A graceful archive waits for the wrap-up turn to settle, and a rename in
  // that window is an ordinary thing to do. The archive is what was asked for:
  // ending the session without the card and the transcript copy loses the
  // conversation once Claude Code sweeps its own transcript.
  const name = mkPane('arch-ren');
  const id = crypto.randomUUID();
  const transcript = writeTranscript(id, 'the last thing it said');
  writeState(name, 'running', { sessionId: id, transcript });
  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ note: 'keep me' }) });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.pending, true);
  writeState(name, 'running', { sessionId: id, transcript });   // the wrap-up turn: the settle timer arms
  await wait(500);

  const to = 'arch-renamed';
  madeSessions.add(to);
  const rn = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(rn.status, 200, JSON.stringify(rn.body));
  // The hook names the state file after whatever tmux calls the session now.
  await settle(to, { sessionId: id, transcript });

  const archived = await untilTrue(async () => (await archives()).some((a) => a.id === id && a.endedAt), 20_000, 200);
  assert.ok(archived, 'the conversation was archived when it settled');
  const row = (await archives()).find((a) => a.id === id);
  assert.equal(row.tmuxName, to, 'under the name it had when it ended');
  assert.equal(row.note, 'keep me', 'with the note the archive was asked for with');
  assert.equal(alive(to), false, 'and the session was ended');
});

// ---------------------------------------------------- the duplicate memory

test('a session deleted and made again under its name does not inherit "already sent"', async () => {
  // The 3.5.1 rule answers `duplicate:true` for the same message delivered to
  // the same session seconds ago — the app re-sending a composer that emptied.
  // A NEW session under the old name has never been sent anything, and the
  // ordinary "start over" move is exactly: end it, make it again, send again.
  const name = await createSession('dup-reuse');
  const text = 'please re-run the migration on the staging box';
  const first = await send(name, text);
  assert.equal(first.status, 200, JSON.stringify(first.body));
  assert.ok(await untilTrue(() => readOr(outFor(name)) === `${text}\n`), 'precondition: the first copy went in');

  const del = await api(`/v1/sessions/${name}`, { method: 'DELETE' });
  assert.equal(del.status, 200, JSON.stringify(del.body));
  await createSession(name);
  const again = await send(name, text);
  assert.equal(again.status, 200, JSON.stringify(again.body));
  assert.notEqual(again.body.duplicate, true, 'the new session was never sent this message');
  assert.ok(await untilTrue(() => readOr(outFor(name)) === `${text}\n${text}\n`),
    'and it reaches the new session');
});

test('a session renamed away leaves no "already sent" behind under its old name', async () => {
  // The same memory has to travel WITH a rename: the session that was sent the
  // message is now called something else, and the next session made under the
  // old name is a stranger to it.
  const name = await createSession('dup-ren');
  const text = 'tag the release once the build is green';
  await send(name, text);
  assert.ok(await untilTrue(() => readOr(outFor(name)) === `${text}\n`), 'precondition: the first copy went in');

  const to = 'dup-ren-old';
  madeSessions.add(to);
  const rn = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(rn.status, 200, JSON.stringify(rn.body));
  await createSession(name);
  const again = await send(name, text);
  assert.equal(again.status, 200, JSON.stringify(again.body));
  assert.notEqual(again.body.duplicate, true, 'the new session was never sent this message');
  assert.ok(await untilTrue(() => readOr(outFor(name)) === `${text}\n${text}\n`),
    'and it reaches the new session');
});

// ------------------------------------------------- a rename mid-delivery

test('a rename that lands while a message is being typed waits for it, and loses nothing', async () => {
  // A pump pass reads the session's name once and uses it across every await,
  // and the queue only moves to the new name after tmux has renamed the session.
  // A message pasted at the old name in between was answered "could not reach
  // the pane buffer" and dropped, with every message queued behind it.
  const name = 'slowren';
  standIn(name, 'slow');
  await createSession(name);
  assert.ok(await untilTrue(() => /❯/.test(capture(name))), 'precondition: the composer is up');

  const a = 'first message, painted slowly';
  const b = 'second message, queued behind it';
  const pa = send(name, a);
  assert.ok(await untilTrue(async () => (await api(`/v1/sessions/${name}/typing`)).body.delivering === true, 5_000, 20),
    'precondition: the first message is being typed');
  const pb = send(name, b);

  const to = 'slowren-moved';
  madeSessions.add(to);
  const rn = await api(`/v1/sessions/${name}/rename`, { method: 'POST', body: JSON.stringify({ name: to }) });
  assert.equal(rn.status, 200, JSON.stringify(rn.body));
  const [ra, rb] = await Promise.all([pa, pb]);

  // The stand-in records under the name it was LAUNCHED with; that is the fixture.
  assert.ok(await untilTrue(() => readOr(outFor(name)) === `${a}\n${b}\n`),
    `both messages went in, in order; the pane got ${JSON.stringify(readOr(outFor(name)))}`);
  assert.equal(ra.status, 200, JSON.stringify(ra.body));
  assert.equal(rb.status, 200, JSON.stringify(rb.body));
  const st = (await api(`/v1/sessions/${to}/typing`)).body;
  assert.equal(st.queued, 0, 'nothing left behind');
  assert.equal(st.lastError, null, 'and nothing reported as failed');
});

// ------------------------------------------------------ held messages

test('a message held for a session killed at a terminal is never typed into the next session of that name', async () => {
  // A reply typed in the app while a dialog is up is held. The owner gives up on
  // the session and kills it at a terminal: no hook can resolve a dead pane, so
  // its state file goes on saying `attention`. The next session made under the
  // name — `cc` defaults to one — is a new conversation with full tools and no
  // context, and the old reply would be its first prompt.
  const name = 'held-kill';
  standIn(name, 'trust');
  mkPane(name, 'claude; exec "$SHELL" -l');   // made the way `cc` makes one
  assert.ok(await untilTrue(() => /trust this folder/.test(capture(name))), 'precondition: a dialog is up');
  writeState(name, 'attention', { sessionId: crypto.randomUUID() });
  const text = 'yes, delete both old branches and force-push';
  const r = await send(name, text);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.delivered, false, 'precondition: held by the dialog');

  sh('tmux', ['kill-session', '-t', `=${name}`]);   // at a terminal: no hook, no daemon
  await wait(1_500);
  standIn(name, null);
  mkPane(name, 'claude; exec "$SHELL" -l');
  await wait(UP_MS + 2_500);
  assert.match(capture(name), /❯/, 'precondition: the new session is up and reading input');
  assert.equal(readOr(outFor(name)), '', 'the reply written for the old conversation never reached the new one');
  assert.equal((await api(`/v1/sessions/${name}/typing`)).body.queued, 0, 'nor is it still waiting to');
  assert.match(readOr(daemonLog), new RegExp(`${name}: delivery failed: the session it was sent to has ended`),
    'it failed, and said so');
});

test('a message held for a session replaced in one stroke is not typed into its replacement', async () => {
  // No pump pass ever sees the name missing: the old session dies and the new
  // one is made in a single tmux invocation. The birth time is what tells them
  // apart.
  const name = 'held-swap';
  standIn(name, 'trust');
  mkPane(name, 'claude; exec "$SHELL" -l');
  assert.ok(await untilTrue(() => /trust this folder/.test(capture(name))), 'precondition: a dialog is up');
  const r = await send(name, 'merge it and close the ticket');
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.delivered, false, 'precondition: held by the dialog');

  await wait(1_100);   // tmux stamps a birth in whole seconds; the next one must not share it
  standIn(name, null);
  sh('tmux', ['kill-session', '-t', `=${name}`, ';',
    'new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40', 'claude; exec "$SHELL" -l']);
  await wait(UP_MS + 2_500);
  assert.match(capture(name), /❯/, 'precondition: the new session is up and reading input');
  assert.equal(readOr(outFor(name)), '', 'the message was for the session that was replaced');
  const st = (await api(`/v1/sessions/${name}/typing`)).body;
  assert.equal(st.queued, 0, 'nor is it still waiting to');
  assert.match(st.lastError || '', /replaced by a new one/, 'it failed, and the queue says why');
});
