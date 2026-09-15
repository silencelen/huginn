'use strict';
// Route-level tests for headroom: the usage model, the settings, the sentinels,
// the heads-up and the model ladder — end to end through a real daemon.
//
// SAFETY, and it matters more here than anywhere else in this suite:
//   * NO NETWORK. The usage endpoint and the account-identity endpoint are both
//     local `http.createServer` stubs, wired in with HUGINN_APPD_USAGE_URL and
//     HUGINN_APPD_OAUTH_ACCOUNT_URL. Nothing here reaches api.anthropic.com, and
//     the percentages the tests need are set by writing a file.
//   * NO REAL SESSIONS. Every tmux session is `hr-<pid>-*` on a PRIVATE `-L`
//     socket and runs either an inert `cat` or the stub picker. A stray `/model`
//     into the owner's own pane would rewrite the model of a live conversation.
//   * NO REAL ~/.claude. HUGINN_APPD_CLAUDE_DIR is a scratch dir holding the
//     credentials the stub answers for and a settings.json the ladder must leave
//     BYTE-IDENTICAL — which is the whole point of driving the picker's `s` key
//     rather than typing `/model <name>`.
//   * Telegram is explicitly unset, so a downgrade notification cannot escape.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did once: two
// files sat inside 9700-9949 and the suite passed four times before failing 16
// tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50    -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire        10250 + pid%50    -> 10250-10299
//   routes-desktop     10300 + pid%50    -> 10300-10349
//   routes-headroom    10350 + pid%50    -> 10350-10399   (this file)
//   routes-agent-transcript 10400 + pid%50 -> 10400-10449
//   routes-refresh     10460 + pid%40    -> 10460-10499
//   routes-typing      10500 + pid%50    -> 10500-10549
//
// This file also binds TWO stub servers on ephemeral ports (port 0), so they
// cannot collide with anything.
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10350 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `hr-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

const TURN = JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 });
const FABLE = JSON.stringify({ type: 'assistant', message: { model: 'claude-fable-5-1', content: [{ type: 'text', text: 'done' }] } });
const HUMAN = JSON.stringify({ type: 'user', message: { content: 'actually, wait' } });

let tmp, stateDir, claudeDir, dataDir, headroomDir, token, daemon;
let usageServer, acctServer, usageFile, shimLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
/** A pane that swallows whatever is typed at it. */
function mkSink(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.txt`);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `sh -c 'stty -echo; cat > ${out}'`]);
  madeSessions.add(name);
  return { name, out };
}
/** A pane drawing the `/model` picker. `rows` order is the test's to choose. */
function mkPicker(suffix, rows, mode = 'normal') {
  const name = `${PFX}-${suffix}`;
  const script = path.join(__dirname, 'fixtures', 'stub-picker.js');
  const arg = JSON.stringify(JSON.stringify(rows));
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `${process.execPath} ${script} ${arg} ${mode}`]);
  madeSessions.add(name);
  return name;
}
function writeState(name, { state = 'idle', sessionId, transcript = null } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: sessionId || `sid-${name}`, transcript, cwd: tmp, ts: Math.floor(Date.now() / 1000),
  }));
}
function writeTranscript(name, records) {
  const file = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(file, `${records.join('\n')}\n`);
  return file;
}
/** A fable session at a turn boundary: the state the ladder is allowed to act in. */
function fableSession(suffix, make = mkSink) {
  const made = make(suffix);
  const name = typeof made === 'string' ? made : made.name;
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  return { name, transcript, sink: typeof made === 'string' ? null : made.out };
}
/** The percentages the stub endpoint will answer with from now on. */
function setUsage({ session = 5, weekly_all = 10, weekly_fable = 20, resetsAt = null } = {}) {
  fs.writeFileSync(usageFile, JSON.stringify({ session, weekly_all, weekly_fable, resetsAt }));
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
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
/** Every tmux argv the DAEMON ran, for the "and typed nothing further" checks. */
function tmuxCalls(sessionName) {
  const raw = fs.existsSync(shimLog) ? fs.readFileSync(shimLog, 'utf8') : '';
  return raw.split('\n').filter(Boolean).map((l) => l.split('\t').filter((a) => a !== ''))
    .filter((args) => !sessionName || args.some((a) => a.includes(sessionName)));
}
/**
 * Make the daemon run a headroom pass NOW.
 *
 * The tick's own cadence is a minute at its fastest, so every test drives it
 * through the settings route — which re-evaluates immediately by design, so that
 * a threshold lowered past where the account already sits does something.
 */
async function tick(patch = {}) {
  // ⚠ WAIT OUT THE PLAN TTL FIRST. `setUsage` writes a file the stub reads per
  // request, but the daemon serves one reading of the endpoint for PLAN_TTL_MS
  // (pinned to 1 s for this suite) — so a tick fired immediately after changing
  // the percentages decides on the PREVIOUS numbers, and the test then waits
  // twelve seconds for something that already happened differently.
  await wait(1100);
  const r = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify(patch) });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body;
}
/** Poll a predicate against /v1/headroom rather than sleeping a fixed time. */
async function until(fn, ms = 12_000, what = 'the condition') {
  const deadline = Date.now() + ms;
  let last = null;
  let nextTick = Date.now() + 2_000;
  for (;;) {
    last = (await api('/v1/headroom')).body;
    if (fn(last)) return last;
    if (Date.now() > deadline) {
      throw new Error(`${what} never became true. Last /v1/headroom: ${JSON.stringify(last).slice(0, 700)}`);
    }
    // The tick's own cadence is a minute at its fastest and PATCH does not wait
    // for the pass it starts, so keep nudging: a decision that needs two passes
    // (read the numbers, then act on them) must not depend on luck.
    if (Date.now() > nextTick) {
      nextTick = Date.now() + 2_000;
      await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    }
    await wait(150);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-hr-'));
  stateDir = path.join(tmp, 'state');
  claudeDir = path.join(tmp, 'claude');
  dataDir = path.join(tmp, 'data');
  headroomDir = path.join(dataDir, 'headroom');
  for (const d of [stateDir, claudeDir, dataDir]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // The scratch ~/.claude: credentials the stubs answer for, and the settings
  // file the ladder must never touch.
  fs.writeFileSync(path.join(claudeDir, '.credentials.json'), JSON.stringify({
    claudeAiOauth: {
      accessToken: 'tok-primary', refreshToken: 'rt-primary',
      expiresAt: Date.now() + 3600_000, subscriptionType: 'max', scopes: ['user:profile'],
    },
  }, null, 2));
  fs.writeFileSync(path.join(claudeDir, 'settings.json'),
    `${JSON.stringify({ model: 'claude-fable-5-1', effortLevel: 'xhigh' }, null, 2)}\n`);

  usageFile = path.join(tmp, 'usage.json');
  setUsage({});

  // ---- the usage endpoint, local. Percentages come off disk per request, so a
  // test changes them by writing a file rather than by restarting anything.
  usageServer = http.createServer((req, res) => {
    const u = JSON.parse(fs.readFileSync(usageFile, 'utf8'));
    const resets = u.resetsAt || new Date(Date.now() + 3600_000).toISOString();
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      limits: [
        { kind: 'session', percent: u.session, severity: 'normal', resets_at: resets },
        { kind: 'weekly_all', percent: u.weekly_all, severity: 'normal', resets_at: resets },
        {
          kind: 'weekly_scoped', percent: u.weekly_fable, severity: 'normal', resets_at: resets,
          scope: { model: { display_name: 'Fable' } },
        },
      ],
    }));
  });
  await new Promise((r) => usageServer.listen(0, '127.0.0.1', r));

  // ---- the identity endpoint, local. The email depends on the TOKEN, which is
  // what lets a test prove the plan cache carries identity rather than looking it
  // up separately.
  acctServer = http.createServer((req, res) => {
    const tok = String(req.headers.authorization || '').replace(/^Bearer\s+/, '');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      account: { email_address: `${tok}@example.test`, uuid: `00000000-0000-0000-0000-${tok.slice(-12).padStart(12, '0')}` },
    }));
  });
  await new Promise((r) => acctServer.listen(0, '127.0.0.1', r));

  // The tmux shim records the daemon's argv so a test can assert what it did
  // NOT type. On PATH for the daemon only; our own tmux calls go to the binary.
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  const shimDir = path.join(tmp, 'shim');
  fs.mkdirSync(shimDir);
  shimLog = path.join(tmp, 'tmux-argv.log');
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + '{ printf \'%s\\t\' "$@" | tr \'\\n\' \' \'; printf \'\\n\'; } >> "$HG_TMUX_LOG"\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${shimDir}:${process.env.PATH}`,
      HG_TMUX_LOG: shimLog,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_USAGE_URL: `http://127.0.0.1:${usageServer.address().port}/usage`,
      HUGINN_APPD_OAUTH_ACCOUNT_URL: `http://127.0.0.1:${acctServer.address().port}/account`,
      // One second, so a test can change the stubbed percentages and see them.
      HUGINN_APPD_PLAN_TTL_MS: '1000',
      // A downgrade sends a notification; nothing may leave this host.
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping happily (ping needs no token) and rejects ours, which
  // surfaces as a dozen 401s that read like a code bug and are not one.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) daemon.kill('SIGTERM');
  if (usageServer) usageServer.close();
  if (acctServer) acctServer.close();
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------------ the shape

test('/v1/headroom answers the whole picture, with the account read from the stub', async () => {
  setUsage({ session: 11, weekly_all: 22, weekly_fable: 33 });
  await tick({ cooldownMs: 0 });
  const body = await until((b) => b.accounts && Object.keys(b.accounts).length > 0,
    12_000, 'an account model');
  const slug = Object.keys(body.accounts)[0];
  const a = body.accounts[slug];
  assert.equal(a.live, true);
  assert.equal(a.windows.weekly_fable.percent, 33);
  assert.equal(a.windows.weekly_fable.label, 'Current week (Fable)');
  assert.equal(body.mode, 'ok');
  assert.equal(body.worst.window, 'weekly_fable');
  assert.equal(body.worst.percent, 33);
  for (const key of ['sessions', 'sentinels', 'held', 'resets', 'arbiter', 'settings', 'serverTime']) {
    assert.ok(key in body, `/v1/headroom must carry ${key}`);
  }
  assert.equal(body.settings.ladderPct, 92);
});

test('/v1/status carries the one-line summary the pill draws', async () => {
  setUsage({ session: 4, weekly_all: 8, weekly_fable: 61 });
  await tick({});
  await until((b) => b.accounts && Object.values(b.accounts).some((a) => a.windows.weekly_fable
    && a.windows.weekly_fable.percent === 61), 12_000, 'the 61% reading');
  const { body } = await api('/v1/status');
  assert.ok(body.headroom, 'an older daemon answers null here and the pill hides');
  assert.equal(body.headroom.worstPercent, 61);
  assert.equal(body.headroom.worstLabel, 'Current week (Fable)');
  assert.equal(body.headroom.mode, 'ok');
  assert.deepEqual(body.headroom.sentinels, []);
  assert.equal(body.headroom.paused, 0);
});

// ------------------------------------------------------------------- settings

test('PATCH refuses a broken setting by NAMING the rule it broke', async () => {
  const bad = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ headsUpPct: 99 }) });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /headsUpPct must be below ladderPct/);

  const worse = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ ladder: ['fable', 'gpt'] }) });
  assert.equal(worse.status, 400);
  assert.match(worse.body.error, /may only contain/);

  const slash = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ resumePhrase: '/clear' }) });
  assert.equal(slash.status, 400);
  assert.match(slash.body.error, /not start with \//);
});

test('a valid PATCH is persisted and answered with the FULL settings', async () => {
  const r = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ stopFablePct: 80 }) });
  assert.equal(r.status, 200);
  assert.equal(r.body.stopFablePct, 80);
  assert.equal(r.body.ladderPct, 92, 'a partial patch must not reset the rest');
  const onDisk = JSON.parse(fs.readFileSync(path.join(dataDir, 'headroom-settings.json'), 'utf8'));
  assert.equal(onDisk.stopFablePct, 80);
  await tick({ stopFablePct: 88 });      // back to the default for later tests
});

// ----------------------------------------------------------------- plan.account

test('/v1/plan says WHOSE usage it is, cached with the numbers', async () => {
  const { body } = await api('/v1/plan');
  assert.ok(body.account, 'the caption has to come from somewhere');
  assert.equal(body.account.email, 'tok-primary@example.test');
  assert.equal(body.account.subscriptionType, 'max');
  assert.ok(Array.isArray(body.limits) && body.limits.length, 'and the numbers came with it');
});

test('a new login relabels the bars in the same breath as the numbers', async () => {
  // The failure this prevents: planCache is one slot keyed on nothing, so an
  // identity resolved SEPARATELY captions the new account's bars with the old
  // email until the cache ages out. Rewriting the credentials is exactly what an
  // activate does to the file the plan is read with.
  fs.writeFileSync(path.join(claudeDir, '.credentials.json'), JSON.stringify({
    claudeAiOauth: {
      accessToken: 'tok-second', refreshToken: 'rt-second',
      expiresAt: Date.now() + 3600_000, subscriptionType: 'max', scopes: ['user:profile'],
    },
  }, null, 2));
  const deadline = Date.now() + 12_000;
  let body;
  for (;;) {
    body = (await api('/v1/plan')).body;
    if (body.account && body.account.email === 'tok-second@example.test') break;
    if (Date.now() > deadline) break;
    await wait(200);
  }
  assert.equal(body.account.email, 'tok-second@example.test');
  assert.ok(body.limits.length, 'identity and numbers travel together, never apart');
});

// ----------------------------------------------------------------- sentinels

test('the sentinels arm on the stubbed numbers, and fable-sessions is written', async () => {
  const { name } = fableSession('sent');
  setUsage({ session: 12, weekly_all: 10, weekly_fable: 95 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sentinels['STOP-FABLE'], 12_000, 'STOP-FABLE');
  assert.ok(fs.existsSync(path.join(headroomDir, 'STOP-FABLE')));
  assert.equal(fs.existsSync(path.join(headroomDir, 'STOP')), false,
    'a full Fable week is not a full 5-hour window');

  // The gate has no daemon access and the SubagentStart payload carries no
  // model, so this list is how a bash hook answers "is the spawning session on
  // Fable?".
  const listed = fs.readFileSync(path.join(headroomDir, 'fable-sessions'), 'utf8').trim().split('\n');
  assert.ok(listed.includes(`sid-${name}`), `fable-sessions must name the session: ${listed}`);

  // A session-window cap arms the other one, and both clear on the way down.
  setUsage({ session: 75, weekly_all: 10, weekly_fable: 95 });
  await tick({});
  await until((b) => b.sentinels.STOP, 12_000, 'STOP');
  setUsage({ session: 3, weekly_all: 4, weekly_fable: 5 });
  await tick({});
  await until((b) => !b.sentinels.STOP && !b.sentinels['STOP-FABLE'], 12_000, 'both sentinels cleared');
  assert.equal(fs.existsSync(path.join(headroomDir, 'STOP')), false);
  assert.equal(fs.existsSync(path.join(headroomDir, 'STOP-FABLE')), false);
  const { body } = await api('/v1/status');
  assert.deepEqual(body.headroom.sentinels, []);
});

// ------------------------------------------------------------------ heads-up

test('a heads-up is typed once, at a turn boundary, at the heads-up threshold', async () => {
  const { name, sink } = fableSession('hu');
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 86 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.headsUpAt), 12_000, 'the heads-up mark');
  await wait(600);
  const typedOnce = fs.readFileSync(sink, 'utf8');
  assert.match(typedOnce, /\[huginn headroom\]/, 'the frame is the point: it is labelled as the daemon');
  assert.match(typedOnce, /86%/);
  assert.match(typedOnce, /move this session to opus at 92%/);

  // A second pass at a higher percentage must not say it again: once per Fable
  // window, not once per tick.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 88 });
  await tick({});
  await wait(1200);
  const after = fs.readFileSync(sink, 'utf8');
  assert.equal(after.split('[huginn headroom]').length - 1, 1, 'exactly one heads-up per window');
});

test('nothing is typed into a session whose last record is a HUMAN speaking', async () => {
  const { name, sink, transcript } = fableSession('hum');
  // Mid-turn, and a person has the floor: the queue holds, then drops.
  fs.appendFileSync(transcript, `${HUMAN}\n`);
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 87 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name), 12_000, 'the session model');
  await wait(1500);
  assert.equal(fs.existsSync(sink) ? fs.readFileSync(sink, 'utf8') : '', '',
    'a turn that is not over is a turn our line would be absorbed into');
});

// -------------------------------------------------------------- the ladder

test('at the ladder threshold the picker is driven by LABEL and `s` is pressed', async () => {
  // Rows in the CLI's own order.
  const name = mkPicker('lad', [
    { label: 'Default (recommended)', desc: 'Opus 5 with 1M context' },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Sonnet', desc: 'Sonnet 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  const settingsFile = path.join(claudeDir, 'settings.json');
  const before = fs.readFileSync(settingsFile);

  setUsage({ session: 5, weekly_all: 10, weekly_fable: 93 });
  await tick({ cooldownMs: 0 });
  const body = await until(
    (b) => b.sessions.some((s) => s.name === name && s.ladder && s.ladder.delivery === 'confirmed'),
    20_000, 'a confirmed ladder move');
  const row = body.sessions.find((s) => s.name === name);
  assert.equal(row.ladder.from, 'fable');
  assert.equal(row.ladder.to, 'opus');
  assert.equal(row.ladder.sessionOnly, true);
  assert.equal(row.family, 'opus');
  assert.match(capture(name), /Set model to Opus \(1M context\) for this session only/);

  // ⚠ THE WHOLE REASON THE PICKER IS DRIVEN INSTEAD OF `/model opus`: that
  // command always rewrites the host's default, and every concurrent session on
  // this box reads this file.
  assert.deepEqual(fs.readFileSync(settingsFile), before,
    'the host settings.json must be byte-identical after a ladder move');
});

test('the row NUMBER is never trusted: a shuffled picker still lands on opus', async () => {
  // The list is built from the installed CLI's model table and a `claude update`
  // renumbers it. A daemon that remembered "opus is row 2" would press `s` on
  // something else and report success.
  const name = mkPicker('shuf', [
    { label: 'Haiku', desc: 'Haiku 4.5' },
    { label: 'Sonnet', desc: 'Sonnet 5' },
    { label: 'Default (recommended)', desc: 'Opus 5 with 1M context' },
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 94 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.ladder && s.ladder.delivery === 'confirmed'),
    20_000, 'a confirmed ladder move on the shuffled picker');
  const screen = capture(name);
  assert.match(screen, /Set model to Opus \(1M context\) for this session only/);
  assert.doesNotMatch(screen, /Set model to Sonnet/, 'row 2 was Sonnet here');
});

test('a picker that never appears is abandoned quietly, with nothing else typed', async () => {
  const name = mkPicker('nopick', [{ label: 'Fable', current: true }, { label: 'Opus (1M context)' }], 'nopicker');
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 96 });
  await tick({ cooldownMs: 0 });
  const body = await until(
    (b) => b.sessions.some((s) => s.name === name && s.ladder && s.ladder.delivery === 'delivery_unconfirmed'),
    25_000, 'an unconfirmed delivery');
  assert.equal(body.sessions.find((s) => s.name === name).ladder.to, 'opus');
  // Nothing after `/model`: no cursor keys, no `s`. Keys pressed into a pane
  // that is not showing a dialog go wherever the pane happens to be.
  const keys = tmuxCalls(name).filter((a) => a.includes('send-keys'));
  assert.equal(keys.some((a) => a.includes('Up') || a.includes('Down')), false, 'no cursor keys');
  assert.equal(keys.some((a, i) => a.includes('-l') && a[a.length - 1] === 's'), false, 'no `s`');
  assert.equal(capture(name).includes('Select model'), false);
});

test('undo puts a laddered session back, and stops the arbiter arguing with it', async () => {
  const name = mkPicker('undo', [
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 97 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.ladder && s.ladder.delivery === 'confirmed'),
    20_000, 'the downgrade to undo');

  const r = await api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.to, 'fable');
  const back = await until((b) => {
    const s = b.sessions.find((x) => x.name === name);
    return s && !s.ladder;
  }, 20_000, 'the ladder record cleared');
  assert.equal(back.sessions.find((s) => s.name === name).family, 'fable');
  assert.match(capture(name), /Set model to Fable for this session only/);

  // The owner has now answered the question the ladder was asking; the next pass
  // must not immediately move it back down.
  await tick({});
  await wait(1500);
  const after = (await api('/v1/headroom')).body.sessions.find((s) => s.name === name);
  assert.equal(after.ladder, null, 'a human model choice holds the ladder off');
});

test('undo refuses a session huginn has not moved', async () => {
  const { name } = fableSession('nomove');
  const r = await api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
  assert.equal(r.status, 409);
  assert.match(r.body.error, /has not moved this session/);
  const gone = await api(`/v1/sessions/${PFX}-nope/headroom/undo`, { method: 'POST' });
  assert.equal(gone.status, 404);
});

// ------------------------------------------------------- the per-session wire

test('a session row carries its headroom block, and autoResume is three-valued', async () => {
  const { name } = fableSession('meta');
  const list = await api('/v1/sessions');
  const row = list.body.sessions.find((s) => s.name === name);
  assert.ok(row.headroom, 'the clients draw the mark from this');
  assert.equal(row.headroom.autoResume, true, 'unset follows the global');
  assert.equal(row.headroom.stalled, false);

  const off = await api(`/v1/sessions/${name}/meta`, { method: 'POST', body: JSON.stringify({ autoResume: false }) });
  assert.equal(off.status, 200);
  assert.equal(off.body.meta.autoResume, false);
  const after = await api('/v1/sessions');
  assert.equal(after.body.sessions.find((s) => s.name === name).headroom.autoResume, false);

  // null is not false: it hands the decision back to the global setting.
  const cleared = await api(`/v1/sessions/${name}/meta`, { method: 'POST', body: JSON.stringify({ autoResume: null }) });
  assert.equal(cleared.body.meta.autoResume, null);
  const bad = await api(`/v1/sessions/${name}/meta`, { method: 'POST', body: JSON.stringify({ autoResume: 'sometimes' }) });
  assert.equal(bad.status, 400);
});

// ------------------------------------------------------------- the old routes

test('/v1/autoswitch still answers its old shape, from the new settings', async () => {
  const get = await api('/v1/autoswitch');
  assert.equal(get.status, 200);
  for (const k of ['enabled', 'switches', 'last', 'accounts', 'threshold', 'idleBecause']) {
    assert.ok(k in get.body, `the shipped clients read ${k}`);
  }
  const post = await api('/v1/autoswitch', { method: 'POST', body: JSON.stringify({ enabled: true, threshold: 90 }) });
  assert.equal(post.status, 200);
  assert.equal(post.body.enabled, true);
  assert.equal(post.body.threshold, 90);
  // …and it landed in headroom-settings.json, not in the retired store.
  const onDisk = JSON.parse(fs.readFileSync(path.join(dataDir, 'headroom-settings.json'), 'utf8'));
  assert.deepEqual(onDisk.accountSwitch, { enabled: true, threshold: 90, margin: 20 });
  assert.equal((await api('/v1/autoswitch')).body.enabled, true);
  await api('/v1/autoswitch', { method: 'POST', body: JSON.stringify({ enabled: false }) });
});
