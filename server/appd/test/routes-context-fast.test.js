'use strict';
// The context window, the `/context` breakdown, fast mode and the Claude Code
// settings routes, end to end through a real daemon:
//
//   GET  /v1/sessions, /v1/sessions/<name>/screen   context/model/effort/fast/limits/cost
//                                                   from the status-line record
//   GET  /v1/sessions/<name>/context                + the parsed `/context` table
//   POST /v1/sessions/<name>/context/refresh        `/context` through the send queue
//   POST /v1/sessions/<name>/fast                   `/fast on|off` through the send queue
//   GET/PATCH /v1/settings/claude                   fastMode & co in settings.json
//   POST/PATCH /v1/chats                            `fast` on a chat, `--settings` at spawn
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is `ctxf-<pid>-*` on a PRIVATE `-L` socket, killed in after(). The
// state directory, data, HOME and the Claude dir are scratch; `claude` on PATH is
// a stub that records its argv; nothing leaves the host.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { untilDrawn } = require('./until-drawn');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11750 + pid%50 -> 11750-11799.
const PORT = 11750 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `ctxf-${process.pid}`;
const TMUX_SOCK = `huginn-ctxf-${process.pid}`;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude-records.js');
const ASK_PANE = path.join(__dirname, 'fixtures', 'prompts', 'ask-simple-80.txt');
const FAST_PANE = path.join(__dirname, 'fixtures', 'prompts', 'fast-mode-unavailable-80.txt');
const STATUS_JSON = require('./fixtures/statusline-2.1.306.json');
const CONTEXT_FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'context-local-command.jsonl');
const [CTX_CMD, CTX_TABLE] = fs.readFileSync(CONTEXT_FIXTURE, 'utf8').trim().split('\n');
const typing = require('../lib/typing');

/** A `claude` that records its argv and answers one short stream-json run. */
const STUB = `#!/usr/bin/env node
const fs = require('node:fs');
const crypto = require('node:crypto');
const NL = String.fromCharCode(10);
const argv = process.argv.slice(2);
if (!argv.includes('-p')) { console.log('{}'); process.exit(0); }
let inp = '';
let done = false;
const finish = () => {
  if (done) return;
  done = true;
  fs.appendFileSync(process.env.ARGV_TEST_LOG, JSON.stringify({ argv, stdin: inp }) + NL);
  if (!argv.includes('stream-json')) { process.stdout.write('idea' + NL); process.exit(0); }
  console.log(JSON.stringify({ type: 'system', subtype: 'init', session_id: crypto.randomUUID() }));
  console.log(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'ok' }] } }));
  console.log(JSON.stringify({ type: 'result', is_error: false, duration_ms: 5, num_turns: 1 }));
};
process.stdin.on('data', (c) => { inp += c; });
process.stdin.on('end', finish);
setTimeout(finish, 1500);
`;

let tmp, stateDir, dataDir, claudeDir, token, daemon, argvLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
const composerOf = (name) => typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const submitted = (name) => readOr(outFor(name)).split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const calls = () => readOr(argvLog).split('\n').filter(Boolean).map((l) => JSON.parse(l));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}
async function until(fn, ms, what) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await wait(100);
  }
  throw new Error(`timed out waiting for ${what}`);
}

const rec = (o) => JSON.stringify(o);
const PROMPT = rec({ type: 'user', message: { role: 'user', content: 'carry on' }, origin: { kind: 'human' }, promptSource: 'typed' });
const TOOL_USE = rec({ type: 'assistant', message: { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 'toolu_1', name: 'Bash', input: { command: 'make' } }] } });
const END_TURN = rec({ type: 'assistant', message: { stop_reason: 'end_turn', content: [{ type: 'text', text: 'done' }] } });
const TURN = rec({ type: 'system', subtype: 'turn_duration', durationMs: 4000 });

/** What huginn-claude-title writes. */
function writeState(name, state, { sessionId = `sid-${name}`, transcript = null, ts = Date.now() } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state, sessionId, transcript, cwd: tmp, ts }));
}
/** What huginn-statusline writes: the JSON, verbatim. */
function writeStatus(name, json, { ageMs = 0 } = {}) {
  const dir = path.join(stateDir, '.status');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const file = path.join(dir, name);
  fs.writeFileSync(file, `${JSON.stringify(json)}\n`);
  if (ageMs) { const t = (Date.now() - ageMs) / 1000; fs.utimesSync(file, t, t); }
}
const statusFor = (name, over = {}) => ({ ...STATUS_JSON, session_id: `sid-${name}`, ...over });

/** A pane that `cat`s a fixture and then waits: a question, a refusal, whatever the file shows. */
function mkShowing(suffix, file, lastRow, rows = 40) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', String(rows), `sh -c 'cat ${file}; cat'`]);
  madeSessions.add(name);
  untilDrawn(TMUX_SOCK, name, lastRow);
  return name;
}
/** The recording stand-in for a running `claude`, with a transcript and a state. */
async function mkClaude(suffix, { state = 'idle', records = [PROMPT, END_TURN, TURN] } = {}) {
  const name = `${PFX}-${suffix}`;
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(transcript, `${records.join('\n')}\n`);
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_TRANSCRIPT=${transcript} ${process.execPath} ${FAKE}`]);
  await until(() => composerOf(name) !== null, 10_000, `${name}'s composer`);
  writeState(name, state, { transcript });
  return { name, transcript };
}
/** A plain shell: Claude Code has exited here. */
function mkShell(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '24', 'sh -c \'printf "alex@example:~$ "; cat\'']);
  madeSessions.add(name);
  untilDrawn(TMUX_SOCK, name, /alex@example:~\$/);
  return name;
}
const row = async (name, q = '') => ((await api(`/v1/sessions${q}`)).body.sessions || []).find((s) => s.name === name);

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-ctxf-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  claudeDir = path.join(tmp, 'claude');
  const home = path.join(tmp, 'home');
  const bin = path.join(tmp, 'bin');
  for (const d of [stateDir, dataDir, home, claudeDir, bin]) fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });
  argvLog = path.join(tmp, 'claude-calls.jsonl');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  const logFd = fs.openSync(path.join(tmp, 'daemon.log'), 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HOME: home,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
      HUGINN_APPD_USAGE_URL: 'http://127.0.0.1:9/usage',
      HUGINN_APPD_OAUTH_ACCOUNT_URL: 'http://127.0.0.1:9/account',
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
      HUGINN_FCM_KEY: '',
      HUGINN_API_KEY_FILE: '',
      HUGINN_API_KEY_VAR: '',
      ARGV_TEST_LOG: argvLog,
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
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) await reap(daemon);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ---- the status-line record on the wire -------------------------------------------

test('a session with a status-line record carries context, model, effort, fast, limits and cost', async () => {
  const name = mkShowing('status', ASK_PANE, /Esc to cancel/);
  writeState(name, 'running');
  writeStatus(name, statusFor(name));

  for (const q of ['', '?preview=1']) {
    const r = await row(name, q);
    assert.ok(r, `listed (${q || 'plain'})`);
    assert.deepEqual(r.context, { used: 35334, window: 1000000, percent: 4, exceeds200k: false }, q);
    assert.equal(r.contextPercent, 4, 'the older field, filled from the record');
    assert.equal(r.model, 'Haiku 5.5');
    assert.equal(r.effort, 'medium');
    assert.deepEqual(r.fast, { on: false, available: true, reason: null });
    assert.equal(r.limits.fiveHour.usedPercent, 5);
    assert.equal(r.limits.sevenDay.resetsAt, 1793001600);
    assert.equal(r.cost.durationMs, 37177);
  }
  const scr = await api(`/v1/sessions/${name}/screen`);
  assert.equal(scr.status, 200);
  assert.equal(scr.body.context.percent, 4);
  assert.equal(scr.body.contextPercent, 4);
  assert.equal(scr.body.model, 'Haiku 5.5');
  assert.equal(scr.body.effort, 'medium');
  assert.deepEqual(scr.body.fast, { on: false, available: true, reason: null });
  assert.equal(scr.body.cost.usd, STATUS_JSON.cost.total_cost_usd);

  const ctx = await api(`/v1/sessions/${name}/context`);
  assert.equal(ctx.status, 200);
  assert.deepEqual(ctx.body, {
    context: { used: 35334, window: 1000000, percent: 4, exceeds200k: false },
    model: 'Haiku 5.5',
    breakdown: null,
  });
});

test('a record from another Claude session under the same name, or a stale one, says nothing', async () => {
  const name = mkShowing('stale', ASK_PANE, /Esc to cancel/);
  writeState(name, 'running', { sessionId: 'sid-current' });
  writeStatus(name, statusFor(name, { session_id: 'sid-previous-holder' }));
  let r = await row(name, '?preview=1');
  assert.equal(r.context, null);
  assert.equal(r.contextPercent, null, 'no `ctx N%` on this pane to fall back to either');
  assert.equal(r.fast, null);
  assert.equal(r.cost, null);
  // The right session, but seven hours old.
  writeStatus(name, statusFor(name, { session_id: 'sid-current' }), { ageMs: 7 * 3600_000 });
  r = await row(name);
  assert.equal(r.context, null);
  assert.equal(r.model, null);
  const ctx = await api(`/v1/sessions/${name}/context`);
  assert.deepEqual(ctx.body, { context: null, model: null, breakdown: null });
});

test('the pane says when fast mode cannot be used, in Claude Code\'s words', async () => {
  // As tall as the fixture, so its status line sits at the bottom as a real pane's does.
  const name = mkShowing('fastno', FAST_PANE, /auto mode on/, 16);
  writeState(name, 'idle');
  writeStatus(name, statusFor(name, { fast_mode: true }));
  const r = await row(name, '?preview=1');
  assert.equal(r.fast.on, true);
  assert.equal(r.fast.available, false);
  assert.match(r.fast.reason, /^Fast mode requires usage credits/);
  // The quick list has no pane to read: available until the pane says otherwise.
  assert.deepEqual((await row(name)).fast, { on: true, available: true, reason: null });
  const scr = await api(`/v1/sessions/${name}/screen`);
  assert.equal(scr.body.fast.available, false);
  assert.match(scr.body.fast.reason, /usage credits/);
  // The scraped `[alex] Fable 5.1 · ctx 4%` is still read for the older fields.
  assert.equal(scr.body.liveModel, 'Fable 5.1');
});

// ---- the /context breakdown --------------------------------------------------------

test('GET /context parses the last `/context` table in the transcript, and follows a newer one', async () => {
  const { name, transcript } = await mkClaude('ctx');
  fs.appendFileSync(transcript, `${CTX_CMD}\n${CTX_TABLE}\n`);
  let r = await api(`/v1/sessions/${name}/context`);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.ok(r.body.breakdown, 'a table was found');
  assert.equal(r.body.breakdown.totalTokens, 28700);
  assert.equal(r.body.breakdown.maxTokens, 1000000);
  assert.equal(r.body.breakdown.categories.length, 8);
  assert.deepEqual(r.body.breakdown.categories.map((c) => c.id), [
    'system_prompt', 'system_tools', 'mcp_tools', 'mcp_instructions', 'skills', 'messages', 'free_space', 'autocompact_buffer',
  ]);
  assert.equal(r.body.breakdown.asOf, Date.parse('2026-10-24T04:22:39.988Z'));
  assert.equal(r.body.model, 'Haiku 5.5', 'no status record: the model the table names');
  assert.equal(r.body.context, null);

  // Unchanged transcript: the same answer, from the cache.
  const again = await api(`/v1/sessions/${name}/context`);
  assert.deepEqual(again.body, r.body);

  // A later `/context`, with different numbers: the newer table wins.
  const later = JSON.parse(CTX_TABLE);
  later.uuid = 'ffffffff-0000-0000-0000-000000000001';
  later.timestamp = '2026-10-24T05:00:00.000Z';
  later.content = later.content.replace('28.7k/1m tokens (3%)', '412.5k/1m tokens (41%)');
  fs.appendFileSync(transcript, `${rec({ type: 'assistant', message: { content: [] } })}\n${CTX_CMD}\n${JSON.stringify(later)}\n`);
  r = await api(`/v1/sessions/${name}/context`);
  assert.equal(r.body.breakdown.totalTokens, 412500);
  assert.equal(r.body.breakdown.uuid, later.uuid);
  assert.equal(r.body.breakdown.asOf, Date.parse('2026-10-24T05:00:00.000Z'));

  // And the status record, when there is one, is the live context beside it.
  writeStatus(name, statusFor(name));
  r = await api(`/v1/sessions/${name}/context`);
  assert.equal(r.body.context.percent, 4);
  assert.equal(r.body.breakdown.totalTokens, 412500);
});

test('the /context table folds into a card in the conversation, a chip plus a fold, never a message', async () => {
  const { name, transcript } = await mkClaude('ctxchip');
  fs.appendFileSync(transcript, `${CTX_CMD}\n${CTX_TABLE}\n`);
  const t = await api(`/v1/sessions/${name}/transcript`);
  assert.equal(t.status, 200);
  const texts = t.body.events.map((e) => `${e.kind}:${e.text}`);
  assert.ok(texts.includes('command:/context'), JSON.stringify(texts));
  const fold = t.body.events.find((e) => e.fold === 'context');
  assert.ok(fold, JSON.stringify(texts));
  assert.equal(fold.kind, 'system');
  assert.match(String(fold.body), /Context Usage/, 'the table is carried in the fold body');
  for (const e of t.body.events) {
    assert.doesNotMatch(String(e.text), /Context Usage|<command-name>|⛁/, 'the table stays off the conversation line itself');
  }
});

// ---- refresh and fast, through the send queue ------------------------------------

test('POST /context/refresh at a turn boundary types /context; POST /fast types /fast on|off', async () => {
  const { name } = await mkClaude('idle');
  const r = await api(`/v1/sessions/${name}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  assert.equal(r.body.queued, true);
  await until(() => submitted(name).length >= 1, 15_000, '/context to be submitted');
  assert.equal(submitted(name)[0], '/context');

  const on = await api(`/v1/sessions/${name}/fast`, { method: 'POST', body: JSON.stringify({ on: true }) });
  assert.equal(on.status, 202, JSON.stringify(on.body));
  await until(() => submitted(name).length >= 2, 15_000, '/fast on to be submitted');
  assert.equal(submitted(name)[1], '/fast on');

  const off = await api(`/v1/sessions/${name}/fast`, { method: 'POST', body: JSON.stringify({ on: false }) });
  assert.equal(off.status, 202);
  await until(() => submitted(name).length >= 3, 15_000, '/fast off to be submitted');
  assert.equal(submitted(name)[2], '/fast off');

  const bad = await api(`/v1/sessions/${name}/fast`, { method: 'POST', body: JSON.stringify({ on: 'yes' }) });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /true or false/);
  assert.equal(submitted(name).length, 3, 'nothing typed for a bad body');
});

test('409 shell: no Claude recorded here, or a shell prompt where the composer was', async () => {
  const plain = mkShell('shell');
  // No state file at all: nothing says Claude ever ran here.
  let r = await api(`/v1/sessions/${plain}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.equal(r.body.reason, 'shell');
  assert.ok(r.body.error);
  // A state file left by a Claude that has since exited to the shell.
  writeState(plain, 'idle');
  r = await api(`/v1/sessions/${plain}/fast`, { method: 'POST', body: JSON.stringify({ on: true }) });
  assert.equal(r.status, 409);
  assert.equal(r.body.reason, 'shell');
  assert.equal(capture(plain).includes('/fast'), false, 'nothing was typed into the shell');
});

test('409 dialog: a question is waiting, by the state file or by the pane', async () => {
  const asking = mkShowing('dialog', ASK_PANE, /Esc to cancel/);
  writeState(asking, 'attention');
  let r = await api(`/v1/sessions/${asking}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 409);
  assert.equal(r.body.reason, 'dialog');
  // The flat state file says `running` (a background agent rewrote it) but the
  // pane shows the question: still a dialog.
  writeState(asking, 'running');
  fs.rmSync(path.join(stateDir, '.ask'), { recursive: true, force: true });
  r = await api(`/v1/sessions/${asking}/fast`, { method: 'POST', body: JSON.stringify({ on: false }) });
  assert.equal(r.status, 409);
  assert.equal(r.body.reason, 'dialog');
  assert.equal(capture(asking).includes('/fast'), false, 'nothing was typed into the question');
});

test('a slash command never lands after somebody\'s unsent text; the same one twice is busy', async () => {
  const { name } = await mkClaude('busy');
  // Words left in the composer at the keyboard: the command waits for a bare caret.
  sh('tmux', ['send-keys', '-t', `=${name}:`, '-l', 'half a thought']);
  await until(() => /half a thought/.test(composerOf(name) || ''), 10_000, 'the draft to show');
  const first = await api(`/v1/sessions/${name}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(first.status, 202, JSON.stringify(first.body));
  assert.equal(first.body.delivered, false, 'held while the box holds a draft');
  assert.equal(first.body.blockedBy, 'draft');
  const second = await api(`/v1/sessions/${name}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(second.status, 409, JSON.stringify(second.body));
  assert.equal(second.body.reason, 'busy');
  // A different command is its own entry, behind the same hold.
  const fast = await api(`/v1/sessions/${name}/fast`, { method: 'POST', body: JSON.stringify({ on: true }) });
  assert.equal(fast.status, 202);
  assert.equal((await api(`/v1/sessions/${name}/typing`)).body.queued, 2);
  assert.equal(submitted(name).length, 0, 'nothing typed into the draft');
  // The draft is cleared: both go, in order, each on its own.
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']);
  await until(() => submitted(name).length >= 2, 20_000, 'both commands to be submitted');
  assert.deepEqual(submitted(name), ['/context', '/fast on']);
});

test('mid-turn the command is typed, for Claude Code to run at the end of the turn, as /compact is', async () => {
  const { name } = await mkClaude('midturn', { state: 'running', records: [PROMPT, TOOL_USE] });
  const r = await api(`/v1/sessions/${name}/context/refresh`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 202, JSON.stringify(r.body));
  await until(() => submitted(name).length >= 1, 15_000, '/context to be submitted');
  assert.equal(submitted(name)[0], '/context');
});

test('an unknown session is 404 on every new route', async () => {
  for (const [p, init] of [
    [`/v1/sessions/${PFX}-nosuch/context`, {}],
    [`/v1/sessions/${PFX}-nosuch/context/refresh`, { method: 'POST', body: '{}' }],
    [`/v1/sessions/${PFX}-nosuch/fast`, { method: 'POST', body: JSON.stringify({ on: true }) }],
  ]) {
    const r = await api(p, init);
    assert.equal(r.status, 404, `${p}: ${JSON.stringify(r.body)}`);
  }
});

// ---- the Claude Code settings ------------------------------------------------------

test('GET/PATCH /v1/settings/claude: fastMode written through the link, mode kept, nothing else touched', async () => {
  const settingsFile = path.join(claudeDir, 'settings.json');
  // No file yet: nothing is on.
  let r = await api('/v1/settings/claude');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { fastModeDefault: false, fastModePerSession: false, feedbackOff: false, statusLine: false });

  // The file an install leaves, kept in a dotfiles directory behind a link.
  const real = path.join(tmp, 'dotfiles-settings.json');
  const original = {
    model: 'claude-fable-5-1',
    permissions: { defaultMode: 'auto' },
    hooks: { Stop: [{ hooks: [{ type: 'command', command: '/usr/local/bin/huginn-claude-title Stop', async: true }] }] },
    statusLine: { type: 'command', command: '/usr/local/bin/huginn-statusline -- \'/home/alex/status.sh\'', padding: 0 },
    fastModePerSessionOptIn: true,
    feedbackSurveyRate: 0,
    feedbackDrafts: 'off',
    env: { MCP_TIMEOUT: '120000', CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: '1' },
  };
  fs.writeFileSync(real, `${JSON.stringify(original, null, 2)}\n`, { mode: 0o600 });
  fs.symlinkSync(real, settingsFile);
  r = await api('/v1/settings/claude');
  assert.deepEqual(r.body, { fastModeDefault: false, fastModePerSession: true, feedbackOff: true, statusLine: true });

  r = await api('/v1/settings/claude', { method: 'PATCH', body: JSON.stringify({ fastModeDefault: true }) });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.fastModeDefault, true);
  assert.ok(fs.lstatSync(settingsFile).isSymbolicLink(), 'the link is still a link');
  const after = fs.readFileSync(real, 'utf8');
  assert.equal(after, `${JSON.stringify({ ...original, fastMode: true }, null, 2)}\n`, 'one key added, 2-space JSON, trailing newline kept');
  assert.equal(fs.statSync(real).mode & 0o777, 0o600, 'the owner\'s mode is kept');
  assert.deepEqual(fs.readdirSync(tmp).filter((f) => f.includes('settings.json') && f.endsWith('.tmp')), [], 'no temp left');

  // The same value again writes nothing.
  const stat1 = fs.statSync(real);
  r = await api('/v1/settings/claude', { method: 'PATCH', body: JSON.stringify({ fastModeDefault: true }) });
  assert.equal(r.status, 200);
  assert.equal(fs.statSync(real).mtimeMs, stat1.mtimeMs);

  r = await api('/v1/settings/claude', { method: 'PATCH', body: JSON.stringify({ fastModeDefault: false }) });
  assert.equal(r.body.fastModeDefault, false);
  assert.equal(JSON.parse(fs.readFileSync(real, 'utf8')).fastMode, false);

  r = await api('/v1/settings/claude', { method: 'PATCH', body: JSON.stringify({ fastModeDefault: 'yes' }) });
  assert.equal(r.status, 400);
  r = await api('/v1/settings/claude', { method: 'PATCH', body: '{}' });
  assert.equal(r.status, 200, 'an empty patch is the GET');

  // A file that cannot be parsed is refused, not rewritten.
  fs.writeFileSync(real, '{"model": "x",\n');
  r = await api('/v1/settings/claude', { method: 'PATCH', body: JSON.stringify({ fastModeDefault: true }) });
  assert.equal(r.status, 409);
  assert.match(r.body.error, /unparseable/);
  assert.equal(fs.readFileSync(real, 'utf8'), '{"model": "x",\n');
  fs.writeFileSync(real, `${JSON.stringify(original, null, 2)}\n`);
});

// ---- chats -------------------------------------------------------------------------

test('a chat carries `fast`; true or false reaches the run as --settings, null does not', async () => {
  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask', title: 'fast chat', fast: true }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.equal(made.body.fast, true);
  const id = made.body.id;
  assert.equal((await api('/v1/chats')).body.chats.find((c) => c.id === id).fast, true, 'listed with it');

  const bad = await api(`/v1/chats/${id}`, { method: 'PATCH', body: JSON.stringify({ fast: 'quick' }) });
  assert.equal(bad.status, 400);
  const badMake = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask', fast: 1 }) });
  assert.equal(badMake.status, 400);

  const sent = await api(`/v1/chats/${id}/messages`, { method: 'POST', body: JSON.stringify({ text: 'FAST-ON-RUN' }) });
  assert.ok(sent.status < 300, JSON.stringify(sent.body));
  await until(async () => (await api(`/v1/chats/${id}`)).body.running === false && calls().some((c) => c.stdin.includes('FAST-ON-RUN')), 20_000, 'the fast run');
  let run = calls().find((c) => c.stdin.includes('FAST-ON-RUN'));
  let at = run.argv.indexOf('--settings');
  assert.ok(at >= 0, `--settings on argv: ${JSON.stringify(run.argv)}`);
  assert.deepEqual(JSON.parse(run.argv[at + 1]), { fastMode: true });

  const off = await api(`/v1/chats/${id}`, { method: 'PATCH', body: JSON.stringify({ fast: false }) });
  assert.equal(off.status, 200, JSON.stringify(off.body));
  assert.equal(off.body.fast, false);
  await api(`/v1/chats/${id}/messages`, { method: 'POST', body: JSON.stringify({ text: 'FAST-OFF-RUN' }) });
  await until(async () => calls().some((c) => c.stdin.includes('FAST-OFF-RUN')), 20_000, 'the slow run');
  run = calls().find((c) => c.stdin.includes('FAST-OFF-RUN'));
  at = run.argv.indexOf('--settings');
  assert.deepEqual(JSON.parse(run.argv[at + 1]), { fastMode: false });

  const cleared = await api(`/v1/chats/${id}`, { method: 'PATCH', body: JSON.stringify({ fast: null }) });
  assert.equal(cleared.body.fast, null);
  await until(async () => (await api(`/v1/chats/${id}`)).body.running === false, 20_000, 'the chat to settle');
  await api(`/v1/chats/${id}/messages`, { method: 'POST', body: JSON.stringify({ text: 'FAST-DEFAULT-RUN' }) });
  await until(async () => calls().some((c) => c.stdin.includes('FAST-DEFAULT-RUN')), 20_000, 'the default run');
  run = calls().find((c) => c.stdin.includes('FAST-DEFAULT-RUN'));
  assert.equal(run.argv.includes('--settings'), false, 'null leaves the host default alone');

  // A chat that never mentioned it is null.
  const plain = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  assert.equal(plain.body.fast, null);
});
