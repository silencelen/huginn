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
const h = require('../lib/headroom');

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
//   routes-resume      10450 + pid%10    -> 10450-10459
//   routes-refresh     10460 + pid%40    -> 10460-10499
//
// ⚠ 10450-10499 was ONE block in the wave-1 contract; it is SPLIT — resume keeps
// the bottom ten, refresh the upper forty. Widening either back re-collides.
//   routes-typing      10500 + pid%50    -> 10500-10549
//   routes-quick-actions 10600 + pid%50  -> 10600-10649
//   routes-session-state 10750 + pid%50  -> 10750-10799
//   routes-session-start 10800 + pid%50  -> 10800-10849
//   routes-paste-settle 10950 + pid%50   -> 10950-10999
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
// What a NATIVE fallback looks like on the wire: the next assistant record
// simply names a different model, and appd typed nothing to put it there.
const OPUS = JSON.stringify({ type: 'assistant', message: { model: 'claude-opus-5', content: [{ type: 'text', text: 'done' }] } });
const HUMAN = JSON.stringify({ type: 'user', message: { content: 'actually, wait' } });

let tmp, stateDir, claudeDir, dataDir, headroomDir, token, daemon;
let usageServer, acctServer, usageFile, shimLog, tmuxFail, pushLog, seededSwitchAt;
let claudeLog, claudeFail, daemonOpts;
const madeSessions = new Set();
// Set by the native-undo test and read by the one after it: the follow-on
// assertion is about what that undo LEFT BEHIND, so it must be the same session.
let nativeUndoName = null;

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
/**
 * The percentages the stub endpoint will answer with from now on.
 *
 * @param sessionRunning false makes the SESSION row answer `resets_at: null`,
 *   which is how the live endpoint says "no 5-hour window is running" — the one
 *   field the whole keep-awake feature triggers on. It is its own parameter
 *   rather than a value of `resetsAt` because the weeklies keep real reset
 *   times throughout: it is the session row alone that goes null.
 */
function setUsage({
  session = 5, weekly_all = 10, weekly_fable = 20, resetsAt = null, sessionRunning = true,
} = {}) {
  fs.writeFileSync(usageFile, JSON.stringify({ session, weekly_all, weekly_fable, resetsAt, sessionRunning }));
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
/** Every push the daemon sent, as the FCM data message the phone would see. */
function pushes(kind = null) {
  let raw = '';
  try { raw = fs.readFileSync(pushLog, 'utf8'); } catch { return []; }
  const out = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    let body;
    try { body = JSON.parse(line); } catch { continue; }
    const data = body && body.message && body.message.data;
    if (!data || (kind && data.kind !== kind)) continue;
    out.push(data);
  }
  return out;
}
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

  // A SEEDED arbiter action, written before the daemon starts.
  //
  // `/v1/autoswitch` is a compatibility alias rebuilt from `arbiter.lastAction`,
  // and `AutoswitchEvent.at` has been epoch SECONDS since 2.x. There is no way
  // to make the arbiter switch accounts from this file (it has one login), and
  // the unit that matters is not worth leaving untested — so the state it reads
  // is written by hand.
  seededSwitchAt = Date.now() - 90_000;
  fs.writeFileSync(path.join(dataDir, 'headroom.json'), JSON.stringify({
    v: 1,
    mode: 'ok',
    accounts: {},
    resets: [],
    sessions: {},
    sentinels: { STOP: null, 'STOP-FABLE': null },
    arbiter: {
      lastSwitchAt: seededSwitchAt,
      lastLadderAt: 0,
      lastResumeAt: 0,
      switches: 1,
      lastIdleWarnAt: 0,
      why: 'seeded',
      lastAction: {
        type: 'switch_account',
        at: seededSwitchAt,
        from: 'slug-a',
        to: 'slug-b',
        fromEmail: 'a@example.test',
        toEmail: 'b@example.test',
        fromPercent: 97,
        toPercent: 4,
      },
    },
  }, null, 2));

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
        { kind: 'session', percent: u.session, severity: 'normal', resets_at: u.sessionRunning === false ? null : resets },
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
  // It can also be told to FAIL one call: a glob written into $HG_TMUX_FAIL is
  // matched against the argv once and then consumed. That is the only way to
  // drive tmux refusing a keystroke mid-script, which is the case the ladder's
  // clean-up paths exist for.
  tmuxFail = path.join(tmp, 'tmux-fail');
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + '{ printf \'%s\\t\' "$@" | tr \'\\n\' \' \'; printf \'\\n\'; } >> "$HG_TMUX_LOG"\n'
    + 'if [ -n "${HG_TMUX_FAIL:-}" ] && [ -f "$HG_TMUX_FAIL" ]; then\n'
    + '  pat="$(cat "$HG_TMUX_FAIL")"\n'
    + '  case "$*" in $pat) rm -f "$HG_TMUX_FAIL"; echo "tmux: forced failure" >&2; exit 1 ;; esac\n'
    + 'fi\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });

  // ---- a stub `claude`, for the keep-awake ping and NOTHING else.
  //
  // ⚠ IT MUST NOT SHADOW THE REAL CLI WHOLESALE. The daemon shells out to
  // `claude --version` for the status line and `claude auth status` for the
  // login check, and a stub that answered those would quietly change what the
  // rest of this file is testing. So it intercepts only a `-p` run — the ping —
  // and execs the real binary for everything else, exactly as the tmux shim
  // does. `HG_CLAUDE_FAIL`, when the file exists, makes one `-p` run fail and is
  // then consumed: the only way to drive a ping that never reaches the API.
  claudeLog = path.join(tmp, 'claude-argv.log');
  claudeFail = path.join(tmp, 'claude-fail');
  let realClaude = '';
  try { realClaude = execFileSync('/bin/sh', ['-c', 'command -v claude'], { encoding: 'utf8' }).trim(); } catch { /* none installed */ }
  fs.writeFileSync(path.join(shimDir, 'claude'),
    '#!/bin/sh\n'
    + 'if [ "$1" = "-p" ]; then\n'
    + '  { printf \'%s\\t\' "$@" | tr \'\\n\' \' \'; printf \'\\n\'; } >> "$HG_CLAUDE_LOG"\n'
    + '  if [ -n "${HG_CLAUDE_FAIL:-}" ] && [ -f "$HG_CLAUDE_FAIL" ]; then\n'
    + '    rm -f "$HG_CLAUDE_FAIL"; echo "stub claude: forced failure" >&2; exit 1\n'
    + '  fi\n'
    + '  echo ok\n'
    + '  exit 0\n'
    + 'fi\n'
    + (realClaude ? `exec ${realClaude} "$@"\n` : 'exit 1\n'), { mode: 0o755 });

  // ---- FCM, recorded rather than sent.
  //
  // The only way to see what a notification actually carries: the Telegram
  // fallback is a line of text, so `kind`, `subject`, `options` and `payload` —
  // everything that makes a notification actionable — exist ONLY on the push. A
  // `--require`d fetch shim answers Google's two hosts and writes each send's
  // body to a file. Nothing here reaches Google; the key is a throwaway.
  pushLog = path.join(tmp, 'push-sends.jsonl');
  fs.writeFileSync(pushLog, '');
  const fetchShim = path.join(tmp, 'fetch-shim.js');
  fs.writeFileSync(fetchShim, `'use strict';
const fs = require('node:fs');
const real = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = String(input && input.url ? input.url : input);
  const json = (status, obj) => new Response(JSON.stringify(obj), {
    status, headers: { 'content-type': 'application/json' },
  });
  if (url.includes('oauth2.googleapis.com')) return json(200, { access_token: 'stub', expires_in: 3600 });
  if (url.includes('fcm.googleapis.com')) {
    try { fs.appendFileSync(${JSON.stringify(pushLog)}, ((init && init.body) || '{}') + '\\n'); } catch {}
    return json(200, { name: 'projects/x/messages/1' });
  }
  return real(input, init);
};
`);
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const fcmKey = path.join(tmp, 'fcm-service-account.json');
  fs.writeFileSync(fcmKey, JSON.stringify({
    type: 'service_account',
    project_id: 'huginn-test',
    client_email: 'test@huginn-test.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    token_uri: 'https://oauth2.googleapis.com/token',
  }), { mode: 0o600 });

  // Hoisted so a test can RESTART the daemon with exactly the same environment.
  // The keep-awake guard's whole claim is that it survives a restart, and a
  // second daemon started from a hand-copied env would be proving something
  // else.
  daemonOpts = {
    env: {
      ...process.env,
      NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require ${fetchShim}`.trim(),
      HUGINN_FCM_KEY: fcmKey,
      PATH: `${shimDir}:${process.env.PATH}`,
      HG_TMUX_LOG: shimLog,
      HG_TMUX_FAIL: tmuxFail,
      HG_CLAUDE_LOG: claudeLog,
      HG_CLAUDE_FAIL: claudeFail,
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
      // The consent backstop waits two minutes in production. One millisecond
      // here, so the second pass over an unanswered dialog acts.
      HUGINN_APPD_CONSENT_GRACE_MS: '1',
      // A downgrade sends a notification; nothing may leave this host.
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: 'ignore',
  };
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], daemonOpts);
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
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
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

test('/v1/autoswitch keeps `last.at` in SECONDS, and loses none of the row', async () => {
  // ⚠ SPREAD ORDER. This view was built as `{ at: <seconds>, ...lastAction }`, so
  // the spread's own millisecond `at` WON — silently changing an existing 2.x
  // field by a factor of a thousand. It is the one pre-existing field this
  // compatibility alias touches, and neither shipped client would have said so.
  const { body } = await api('/v1/autoswitch');
  assert.ok(body.last, 'a switch_account lastAction is what this view renders');
  assert.equal(body.last.at, Math.floor(seededSwitchAt / 1000),
    `last.at must be epoch SECONDS, got ${body.last.at}`);
  assert.ok(body.last.at < 1e11, 'and it must not be milliseconds');
  // …and overriding `at` must not drop the rest of the row.
  assert.equal(body.last.type, 'switch_account');
  assert.equal(body.last.toEmail, 'b@example.test');
  assert.equal(body.last.fromPercent, 97);
  assert.equal(body.switches, 1);

  // The same instant on /v1/headroom is MILLISECONDS, which is the split this
  // alias exists to hide — and `serverTime` is ms now too.
  const hr = (await api('/v1/headroom')).body;
  assert.equal(hr.arbiter.lastAction.at, seededSwitchAt, 'headroom epochs are milliseconds');
  assert.ok(hr.serverTime > 1e11, 'including serverTime, which used to be the odd one out');
  assert.equal(typeof hr.arbiter.lastResumeAt, 'number', 'and lastResumeAt is a NUMBER, never null');
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

test('a STORED settings file is validated field by field, not merely parsed', async () => {
  // The file is hand-operable by design ("the operator must be able to arm one by
  // hand"), and it is also what a crash half-writes. It used to be loaded as the
  // BASE of validateSettings, which skips every per-field rule for a key that is
  // not in the PATCH — so nothing could fail, and `/clear` on disk was typed into
  // every stalled session at the next reset.
  const file = path.join(dataDir, 'headroom-settings.json');
  const good = JSON.parse(fs.readFileSync(file, 'utf8'));
  const D = h.defaults();
  const load = async () => (await api('/v1/headroom')).body.settings;
  try {
    // Only the ONE field is wrong, and every cross-field ordering still holds —
    // so nothing but a per-field rule can catch it.
    fs.writeFileSync(file, JSON.stringify({ ...good, resumePhrase: '/clear', stopFablePct: 70 }, null, 2));
    const one = await load();
    assert.notEqual(one.resumePhrase, '/clear',
      'a stored slash command loaded straight into the resume path');
    assert.equal(one.resumePhrase, D.resumePhrase);
    assert.equal(one.stopFablePct, D.stopFablePct,
      'a file that does not validate falls back to the DEFAULTS, not to half of itself');

    // A non-string is the other half: `child.stdin.end(99)` throws inside the
    // paste, which is a resume that fails with a stack trace rather than a phrase.
    fs.writeFileSync(file, JSON.stringify({ ...good, resumePhrase: 99 }, null, 2));
    const two = await load();
    assert.equal(typeof two.resumePhrase, 'string');
    assert.equal(two.resumePhrase, D.resumePhrase);

    // And the heads-up note travels the same pane path, so the same rule applies.
    fs.writeFileSync(file, JSON.stringify({ ...good, headsUpText: '/clear {pct}' }, null, 2));
    const three = await load();
    assert.equal(three.headsUpText, D.headsUpText);
  } finally {
    fs.writeFileSync(file, JSON.stringify(good, null, 2));
  }
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

test('a hand-armed STOP survives the tick and shows on /v1/status (#12)', async () => {
  // ⚠ THE OPERATOR'S ONLY PAUSE BUTTON. No route arms a sentinel — `arm` is
  // called from `writeSentinels` and nowhere else — so `touch $HEADROOM_DIR/STOP`
  // is the documented and unit-tested way to hold every subagent spawn. The
  // tick's else-branch then deleted it, because the plan did not call for STOP:
  // measured at 299 s with no interaction and 23 ms after a settings PATCH, with
  // a journal line ("headroom: cleared STOP") indistinguishable from
  // housekeeping, while a gate that WAS holding released with `waited=0`.
  setUsage({ session: 3, weekly_all: 4, weekly_fable: 5 });     // the plan wants no STOP
  await tick({});
  fs.mkdirSync(headroomDir, { recursive: true });
  fs.writeFileSync(path.join(headroomDir, 'STOP'), '');
  // While it is armed, the one-line status must say so — it read the in-memory
  // sentinels, which a hand-armed file never populates, so /v1/status reported
  // sentinels:[] about a fleet-wide pause that /v1/headroom could see.
  const status = (await api('/v1/status')).body.headroom;
  assert.ok(status.sentinels.includes('STOP'), JSON.stringify(status.sentinels));

  await tick({});
  await tick({});
  assert.equal(true, fs.existsSync(path.join(headroomDir, 'STOP')),
    'the tick must not reap a sentinel it did not arm');
  // …and it is HEARTBEATED, or the gate ages it out after HUGINN_GATE_STALE_S
  // and every held spawn goes through anyway.
  const age = Date.now() - fs.statSync(path.join(headroomDir, 'STOP')).mtimeMs;
  assert.ok(age < 20_000, `the tick must touch it too (age ${age}ms)`);

  fs.rmSync(path.join(headroomDir, 'STOP'), { force: true });
});

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

test('repairing the host default keeps a SYMLINKED settings.json a symlink (#29)', async () => {
  // ⚠ THE FILE THE OWNER KEEPS IN A DOTFILES REPO. `repairDefaultModel` wrote
  // `<file>.tmp` and renameSync'd it over ~/.claude/settings.json without
  // resolving the link, so the symlink was REPLACED by a regular file: later
  // edits in the repo stopped reaching the CLI, silently and permanently. The
  // mode went with it — the tmp is hardcoded 0600, so every host lost whatever
  // permissions the file had, symlink or not. install-hooks.js resolves exactly
  // this case on purpose (realpath + carry the mode); this one did not.
  const settingsFile = path.join(claudeDir, 'settings.json');
  const original = fs.readFileSync(settingsFile);
  const dots = path.join(tmp, 'dotfiles');
  fs.mkdirSync(dots, { recursive: true });
  const real = path.join(dots, 'settings.json');
  fs.writeFileSync(real, `${JSON.stringify({ model: 'claude-opus-5', effortLevel: 'xhigh' }, null, 2)}\n`);
  fs.chmodSync(real, 0o644);
  fs.rmSync(settingsFile, { force: true });
  fs.symlinkSync(real, settingsFile);

  // The native Fable consent dialog, answered in a way that made the CLI persist
  // a new host default. That record is the only thing that arms the repair.
  const { name } = fableSession('symlink');
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.appendFileSync(transcript, `${JSON.stringify({
    type: 'system', subtype: 'model_consent_fallback',
    choice: 'yes_default', toModel: 'claude-opus-5', persisted_as_default: true,
  })}\n`);
  writeState(name, { sessionId: `sid-${name}`, transcript });

  setUsage({ session: 5, weekly_all: 10, weekly_fable: 20 });
  await tick({ cooldownMs: 0 });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (JSON.parse(fs.readFileSync(real, 'utf8')).model === 'claude-fable-5-1') break;
    await wait(400);
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
  }

  assert.equal(true, fs.lstatSync(settingsFile).isSymbolicLink(),
    'the dotfiles link must survive the repair');
  assert.equal('claude-fable-5-1', JSON.parse(fs.readFileSync(real, 'utf8')).model,
    'and the repair must land in the file the link points at');
  assert.equal(0o644, fs.statSync(real).mode & 0o777, 'with the mode it had');

  fs.rmSync(settingsFile, { force: true });
  fs.writeFileSync(settingsFile, original);
});

test('a huge record after the turn does not read as permanently mid-turn (#5)', async () => {
  // ⚠ 9.2% OF THIS HOST'S OWN TRANSCRIPTS. The turn gate read a fixed 64 KB tail,
  // and a large NON-conversational record appended after the boundary (measured:
  // a 94 KB `attachment`) pushes the boundary out of that window: the
  // bookkeeping inside it parses but is not conversational, the fragment of the
  // big record does not parse, and `boundaryFromTail` reported plain "not idle".
  // Every AUTOMATED entry then blocked on 'turn' and was dropped as a timeout
  // ten minutes later — and for a heads-up that is permanent, because
  // `headsUpAt` is stamped on acceptance, so the warning for that week is gone.
  // A human message queued behind the stuck entry waits with it.
  const { name, sink } = fableSession('bigtail');
  const transcript = path.join(tmp, `${name}.jsonl`);
  fs.appendFileSync(transcript, `${JSON.stringify({
    type: 'attachment', content: 'A'.repeat(100 * 1024),
  })}\n`);

  setUsage({ session: 5, weekly_all: 10, weekly_fable: 86 });
  await tick({ cooldownMs: 0 });
  const deadline = Date.now() + 25_000;
  while (Date.now() < deadline) {
    const got = fs.existsSync(sink) ? fs.readFileSync(sink, 'utf8') : '';
    if (got.includes('[huginn headroom]')) break;
    await wait(400);
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
  }
  assert.match(fs.readFileSync(sink, 'utf8'), /\[huginn headroom\]/,
    'the turn gate has to widen its window until it can see the boundary');

  // ⚠ CLEAN UP THIS ONE. Every other session in this file is small; a live
  // session carrying a 100 KB transcript stays in `listSessions` and is re-read
  // by every later tick, which pushes the NEXT test's 12-second `until` over its
  // budget on a loaded host.
  try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* already gone */ }
  madeSessions.delete(name);
  fs.rmSync(path.join(stateDir, name), { force: true });
  fs.rmSync(transcript, { force: true });
});

test('the SECOND Fable window gets its own heads-up (#17)', async () => {
  // ⚠ ONCE EVER, NOT ONCE PER WEEK. `rec.headsUpAt` was written in one place and
  // cleared in none, so the apply guard `if (!rec || rec.headsUpAt) return`
  // dropped every heads-up after the first for the life of that session record —
  // while `decide()` kept EMITTING one, because its own staleness rule
  // (lib/headroom.js: a weekly_fable reset seen after the mark) said the note was
  // due. The drop returns before `lastAction` and before the log line, so the
  // miss left no trace anywhere, and `state.arbiter.why` — set from the verdict
  // before the actions are applied — went on claiming "handed <session> a
  // heads-up at 90% of the Fable week" on every tick while nothing was typed.
  // That sentence is rendered by `huginn headroom` and shipped raw to both
  // clients.
  const { name, sink } = fableSession('hu2');
  const notes = () => (fs.existsSync(sink) ? fs.readFileSync(sink, 'utf8') : '')
    .split('[huginn headroom]').length - 1;
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 86 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.headsUpAt), 12_000, 'the first heads-up');
  await wait(600);
  assert.equal(1, notes(), 'week one, once');

  // Red — `classify` calls a window red at ladderPct — so the reset detector has
  // a red row with a reset time to compare against. (The ladder fires too; it
  // cannot land on a sink pane, and the session stays on Fable, which is all
  // this case needs.)
  const past = new Date(Date.now() - 120_000).toISOString();
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 93, resetsAt: past });
  await tick({});
  await until((b) => Object.values(b.accounts || {}).some((a) => a.red && a.red.weekly_fable),
    20_000, 'the weekly_fable window to read as red');
  // The week rolls over: the percentage drops with the reset time behind us.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 2, resetsAt: past });
  await tick({});
  await until((b) => (b.resets || []).some((r) => r.window === 'weekly_fable'),
    20_000, 'the weekly_fable reset');

  // …and the new week climbs past the threshold again.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 90 });
  await tick({});
  const deadline = Date.now() + 20_000;
  while (notes() < 2 && Date.now() < deadline) {
    await wait(400);
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
  }
  assert.equal(2, notes(), 'week two gets its own warning');
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

test('the title hook\'s idle state is the boundary on a transcript that never gets one', async () => {
  // ⚠ turn_duration IS NOT GUARANTEED. Census of the 25 most recent real
  // transcripts in ~/.claude/projects: it is written 1:1 with
  // system/stop_hook_summary — only when a Stop hook ran — and 14 of them
  // contain none at all. An automated line queued behind a boundary that is
  // never written waits ten minutes and is dropped. The hook's own state file
  // is the second source, and on those sessions the only one.
  const { name, sink, transcript } = fableSession('hookgate');
  // A turn in progress, and this transcript will never say it ended.
  fs.appendFileSync(transcript, `${JSON.stringify({ type: 'assistant', message: { stop_reason: 'tool_use', content: [] } })}\n`);
  writeState(name, { sessionId: `sid-${name}`, transcript, state: 'running' });

  setUsage({ session: 5, weekly_all: 10, weekly_fable: 86 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.headsUpAt), 12_000, 'the heads-up mark');
  await wait(2_500);
  const held = (await api(`/v1/sessions/${name}/typing`)).body;
  assert.equal(held.queued, 1, 'held: the transcript is mid-turn and no marker is coming');
  assert.equal(held.blockedBy, 'turn');
  assert.equal(fs.existsSync(sink) ? fs.readFileSync(sink, 'utf8') : '', '', 'and nothing reached the pane');

  // The Stop hook fires. Its ts is epoch SECONDS, so it must land in a LATER
  // second than the send for the release to be evidence rather than a guess.
  writeState(name, { sessionId: `sid-${name}`, transcript, state: 'idle' });
  // ⚠ SYNCHRONOUS PREDICATE. This file's `until` does `if (fn(last))` with no
  // await, so an async predicate is a Promise — always truthy, and the wait
  // returns on its first poll having proved nothing.
  await until(() => (fs.existsSync(sink) ? fs.readFileSync(sink, 'utf8') : '').includes('[huginn headroom]'),
    15_000, 'the heads-up to land once the hook said idle');
  assert.match(fs.readFileSync(sink, 'utf8'), /\[huginn headroom\]/);
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

test('a ladder job that is merely QUEUED reads as pending, not as a failed delivery', async () => {
  // ⚠ enqueueJob resolves after ONE pump pass, and the picker must not open
  // inside a running turn — so `r.result` is null for a job that has not started.
  // That was indistinguishable from a job that ran and could not confirm, and
  // the record it wrote claimed a move that had not happened.
  const name = mkPicker('pend', [
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  // Mid-turn: the transcript's last record is the owner speaking, so both gates
  // are not open and the job waits for the boundary.
  const transcript = writeTranscript(name, [FABLE, TURN, HUMAN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 95 });
  await tick({ cooldownMs: 0 });
  const held = await until((b) => {
    const row = b.sessions.find((x) => x.name === name);
    return row && row.ladder && row.ladder.delivery === 'pending';
  }, 25_000, 'a pending ladder record');
  const row = held.sessions.find((x) => x.name === name);
  assert.equal(row.ladder.to, 'opus');
  assert.equal(row.family, 'fable', 'the session has NOT moved yet');
  assert.equal(capture(name).includes('Select model'), false, 'the picker must never open mid-turn');

  // The turn ends. The job runs, and its own settle resolves the record.
  fs.appendFileSync(transcript, `${TURN}\n`);
  const done = await until((b) => {
    const r = b.sessions.find((x) => x.name === name);
    return r && r.ladder && r.ladder.delivery === 'confirmed';
  }, 30_000, 'the queued ladder to land');
  assert.equal(done.sessions.find((x) => x.name === name).family, 'opus');
  assert.match(capture(name), /Set model to Opus \(1M context\) for this session only/);
});

test('a /model that cannot be SUBMITTED leaves nothing in the composer', async () => {
  // ⚠ `/model` is already typed by the time the Enter goes. This was the one
  // early exit that skipped the clean-up after typing text, so the pump released
  // the next entry into a composer holding `/model` and the session received
  // `/modelYour usage limit has reset. Continue the task…`.
  const name = mkPicker('entfail', [
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });

  // Spend the heads-up FIRST (once per Fable window), so the only `send-keys …
  // Enter` left for this session is the ladder's own submit.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 88 });
  await tick({ cooldownMs: 0 });
  await until((b) => {
    const r = b.sessions.find((x) => x.name === name);
    return !!(r && r.headsUpAt);
  }, 25_000, 'the heads-up for entfail');

  fs.writeFileSync(tmuxFail, `*send-keys*${name}*Enter*`);
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 95 });
  await tick({ cooldownMs: 0 });
  await until((b) => {
    const r = b.sessions.find((x) => x.name === name);
    return !!(r && r.ladder && r.ladder.delivery === 'delivery_unconfirmed');
  }, 25_000, 'an unconfirmed ladder after the forced Enter failure');

  const keys = tmuxCalls(name).filter((a) => a.includes('send-keys'));
  assert.ok(keys.some((a) => a.includes('Escape')),
    'Esc must close whatever /model opened');
  assert.ok(keys.some((a) => a.includes('C-u')),
    'and the composer must be cleared — /model is sitting in it');
});

test('the downgrade notification carries the buttons AND what they act on', async () => {
  // ⚠ The Telegram fallback is a line of text. `kind`, `subject`, `options` and
  // `payload` exist only on the PUSH — and without a payload an "Undo" button is
  // a word the app cannot aim at anything. All four headroom_* pushes went out
  // as plain text with no options and no payload.
  const reg = await api('/v1/push/register', {
    method: 'POST', body: JSON.stringify({ installId: 'hr-test-install', token: 'tok-hr-test', model: 'Pixel 9' }),
  });
  assert.equal(reg.status, 200, JSON.stringify(reg.body));

  const name = mkPicker('pushd', [
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 96 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((x) => x.name === name && x.ladder && x.ladder.delivery === 'confirmed'),
    25_000, 'a confirmed ladder move');

  const deadline = Date.now() + 10_000;
  let sent = [];
  for (;;) {
    sent = pushes('headroom_downgraded').filter((d) => d.subject === name);
    if (sent.length) break;
    if (Date.now() > deadline) break;
    await wait(200);
  }
  assert.ok(sent.length, `no headroom_downgraded push named ${name}: ${JSON.stringify(pushes())}`);
  const d = sent[sent.length - 1];
  assert.equal(d.subject, name, 'the tmux session name, so the app can open the thing it is about');
  assert.deepEqual(JSON.parse(d.options), ['Undo', 'OK']);
  const payload = JSON.parse(d.payload);
  assert.equal(payload.session, name);
  assert.equal(payload.to, 'opus');
  assert.equal(payload.from, 'fable');
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
  // ⚠ `ok` IS "ACCEPTED", NOT "MOVED". A client reporting success on any 2xx
  // announced "put back on its own model" for an undo still sitting in the
  // queue — under the same toast key as the downgrade it claimed to reverse.
  assert.equal(r.body.applied, true, 'this one ran: the session was at a turn boundary');
  assert.equal(r.body.queued, false);
  assert.equal(r.body.delivery, 'confirmed');
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

test('an undo that only QUEUES says so, and clears the record when it lands', async () => {
  const name = mkPicker('undoq', [
    { label: 'Fable', desc: 'Fable 5.1', current: true },
    { label: 'Opus (1M context)', desc: 'Opus 5' },
  ]);
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 97 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((x) => x.name === name && x.ladder && x.ladder.delivery === 'confirmed'),
    25_000, 'the downgrade to undo');

  // Mid-turn: the picker must not open inside a running turn, so the undo waits.
  fs.appendFileSync(transcript, `${HUMAN}\n`);
  const r = await api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.applied, false, 'nothing has moved yet');
  assert.equal(r.body.queued, true);
  assert.equal(r.body.to, 'fable');
  assert.equal(r.body.delivery, undefined, 'there is no delivery to report on a job that has not run');

  // The turn ends; the job runs; the record clears from the job's own settle.
  fs.appendFileSync(transcript, `${TURN}\n`);
  const back = await until((b) => {
    const x = b.sessions.find((y) => y.name === name);
    return x && !x.ladder;
  }, 30_000, 'the queued undo to land');
  assert.equal(back.sessions.find((x) => x.name === name).family, 'fable');
  assert.match(capture(name), /Set model to Fable for this session only/);
});

test('undo also puts back a session CLAUDE CODE moved off Fable by itself', async () => {
  // ⚠ THE BUTTON THAT COULD NOT BE PRESSED. `offer_ladder_up` pushes
  // "Back to Fable · Stay" for exactly this session — one appd never touched,
  // so it has no `ladder` record — and both clients route that button to this
  // route. The route answered 409 "huginn has not moved this session".
  const name = mkPicker('undonative', [
    { label: 'Fable', desc: 'Fable 5.1' },
    { label: 'Opus (1M context)', desc: 'Opus 5', current: true },
  ]);
  nativeUndoName = name;
  const transcript = writeTranscript(name, [FABLE, TURN]);
  writeState(name, { sessionId: `sid-${name}`, transcript });
  // Deliberately NOT red: the move under test is Claude Code's own, and a
  // ladder_down racing it would write the very record this test must not have.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 20 });
  await tick({ cooldownMs: 0 });
  await until((b) => b.sessions.some((s) => s.name === name && s.family === 'fable'),
    15_000, 'the session to be seen on fable');

  // The fallback happens: the next assistant record is opus.
  fs.writeFileSync(transcript, `${FABLE}\n${TURN}\n${OPUS}\n${TURN}\n`);
  const seen = await until((b) => {
    const s = b.sessions.find((x) => x.name === name);
    return s && s.nativeSwitch && s.nativeSwitch.seenAt;
  }, 20_000, 'the native fallback to be noticed');
  const row = seen.sessions.find((s) => s.name === name);
  assert.equal(row.nativeSwitch.to, 'opus');
  assert.equal(row.ladder, null, 'appd typed nothing, so there is no ladder record');

  const r = await api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.to, 'fable', 'back to the rung the ladder starts on');
  assert.equal(r.body.applied, true, 'this one ran: the session was at a turn boundary');
  assert.equal(r.body.queued, false);
  assert.equal(r.body.delivery, 'confirmed');
  assert.match(capture(name), /Set model to Fable for this session only/);
});

test('that undo clears the native mark and records the human choice', async () => {
  const name = nativeUndoName;
  assert.ok(name, 'the native undo test must have run first');
  const row = (await until((b) => {
    const s = b.sessions.find((x) => x.name === name);
    return s && s.nativeSwitch && !s.nativeSwitch.seenAt;
  }, 20_000, 'the native mark to clear')).sessions.find((s) => s.name === name);
  // ⚠ CLEARED, NOT LEFT STANDING. `decide` refuses to ladder a session carrying
  // a native mark at all — "appd never fights a native switch" — so a mark left
  // behind after the owner has chosen Fable would mean this session could never
  // be moved down again however red the week got.
  assert.equal(row.nativeSwitch.seenAt, null);
  assert.equal(row.ladder, null, 'the in-flight record is gone too');
  assert.equal(row.family, 'fable');
  // And what holds the arbiter off in the meantime is the HUMAN stamp, which is
  // the same thing the appd-laddered undo writes.
  assert.ok(Number(row.humanSetModelAt) > Date.now() - 120_000,
    `humanSetModelAt should be just now, got ${row.humanSetModelAt}`);

  // It must STAY cleared: the transcript still ends on an opus record, and a
  // tick that re-read that as a fresh native fallback would undo the undo.
  await tick({});
  await wait(1500);
  const after = (await api('/v1/headroom')).body.sessions.find((s) => s.name === name);
  assert.equal(after.nativeSwitch.seenAt, null, 'a stale transcript is not a new fallback');
  assert.equal(after.ladder, null);
});

test('undo refuses a session huginn has not moved', async () => {
  const { name } = fableSession('nomove');
  const r = await api(`/v1/sessions/${name}/headroom/undo`, { method: 'POST' });
  assert.equal(r.status, 409);
  assert.match(r.body.error, /has not moved this session/);
  // ⚠ NEITHER RUNG. The route now also accepts a session Claude Code moved
  // itself, so the refusal is no longer "no ladder record" — it is "no ladder
  // record AND no native switch", which is this session.
  const row = (await until((b) => b.sessions.some((s) => s.name === name),
    15_000, 'the session to be seen')).sessions.find((s) => s.name === name);
  assert.equal(row.ladder, null);
  assert.equal(row.nativeSwitch.seenAt, null);
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

// ------------------------------------------------------- the consent backstop

test('the consent auto-answer RE-READS the pane immediately before the digit', async () => {
  // ⚠ The digit is typed at a pane, not routed through /answer — so the ONLY
  // thing standing between "appd answered the dialog" and "appd sent the number
  // 2 as a chat message" is a capture taken immediately before the keystroke.
  // The abort itself is a microsecond-wide race no test can drive; what a test
  // CAN pin down is that the second read happens at all, with nothing between it
  // and the key.
  const name = `${PFX}-consent`;
  const fixture = path.join(__dirname, 'fixtures', 'prompts', 'fable-consent-80.txt');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `sh -c 'cat ${fixture}; sleep 600'`]);
  madeSessions.add(name);
  writeState(name, { state: 'attention', sessionId: `sid-${name}` });
  try {
    const isDigit = (a) => a.includes('send-keys') && a.includes('-l') && a[a.length - 1] === '2';
    const deadline = Date.now() + 20_000;
    let calls = [];
    for (;;) {
      calls = tmuxCalls(name);
      if (calls.some(isDigit)) break;
      if (Date.now() > deadline) {
        throw new Error(`the consent dialog was never answered. tmux calls:\n${calls.map((c) => c.join(' ')).join('\n')}`);
      }
      await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
      await wait(250);
    }
    // The call IMMEDIATELY before the keystroke must be the verification read.
    // It is distinguishable from the decision read by shape: captureScreen asks
    // for escapes and geometry (`capture-pane -p -e`, after a display-message),
    // the verification is a bare `capture-pane -p` with nothing between it and
    // the key.
    const at = calls.findIndex(isDigit);
    const prev = calls[at - 1] || [];
    assert.ok(prev.includes('capture-pane') && !prev.includes('-e'),
      `the digit was pressed against a stale capture; the call before it was: ${prev.join(' ')}`);
    // And it really did answer: a pressed digit is followed by its Enter.
    assert.ok(calls.slice(at + 1).some((a) => a.includes('send-keys') && a.includes('Enter')),
      'the digit must be submitted, not merely typed');
  } finally {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
    madeSessions.delete(name);
    try { fs.unlinkSync(path.join(stateDir, name)); } catch { /* gone */ }
  }
});

// ----------------------------------------------------------------- keep awake
//
// The one lane in the daemon that spends the owner's quota with nobody asking,
// so these are end to end against a real daemon rather than against the pure
// decision: the argv that actually reaches a process, the field on the wire
// that the Status line reads, and the guard that has to hold across a restart.
// The stub `claude` records every ping and can be made to fail one.

/**
 * Every keep-awake ping the stub saw, newest last, as argv arrays.
 *
 * ⚠ EMPTY ARGUMENTS ARE KEPT, unlike `tmuxCalls` above. The shim writes
 * `printf '%s\t'` per argument, so the line always ends in one trailing empty
 * field — and dropping every empty field to get rid of it would also drop the
 * two that ARE the cage: `--setting-sources ""` and `--tools ""` are values, not
 * padding, and a test that cannot see them cannot notice them going missing.
 * Only the trailing artefact comes off.
 */
function pings() {
  let raw = '';
  try { raw = fs.readFileSync(claudeLog, 'utf8'); } catch { return []; }
  return raw.split('\n').filter(Boolean).map((l) => {
    const parts = l.split('\t');
    parts.pop();
    return parts;
  });
}

/** Wait for the ping count to reach [n], nudging the tick along the way. */
async function untilPings(n, ms = 20_000) {
  const deadline = Date.now() + ms;
  for (;;) {
    if (pings().length >= n) return pings();
    if (Date.now() > deadline) {
      throw new Error(`expected ${n} keep-awake ping(s), saw ${pings().length}`);
    }
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    await wait(250);
  }
}

test('keep-awake is OFF on a fresh install, and the wire says so', async () => {
  // It must not arrive switched on by an upgrade: this is the first setting that
  // costs money without being asked for.
  const s = (await api('/v1/headroom')).body.settings;
  assert.equal(s.keepAwake, false);
  assert.equal(s.keepAwakeModel, 'claude-haiku-4-5-20251001');
  assert.equal(s.keepAwakeQuietHours, null);
  assert.equal(pings().length, 0, 'nothing may be spent before it is switched on');
});

test('/v1/status says whether a 5-hour window is RUNNING, which nothing else reported', async () => {
  setUsage({ session: 6, weekly_all: 10, weekly_fable: 20, sessionRunning: true });
  await tick();
  await until((b) => b.accounts && Object.values(b.accounts).some((a) => a.live), 12_000, 'a live account');
  const running = (await api('/v1/status')).body.headroom;
  assert.equal(running.windowRunning, true);
  assert.ok(running.windowResetsAt, 'a running window carries the instant it ends');

  // The tell: the session row's reset goes NULL while the weeklies keep theirs.
  setUsage({ session: 0, weekly_all: 10, weekly_fable: 20, sessionRunning: false });
  await tick();
  const idle = await (async () => {
    const deadline = Date.now() + 12_000;
    for (;;) {
      const b = (await api('/v1/status')).body.headroom;
      if (b.windowRunning === false) return b;
      if (Date.now() > deadline) throw new Error(`windowRunning never went false: ${JSON.stringify(b)}`);
      await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
      await wait(250);
    }
  })();
  assert.equal(idle.windowRunning, false);
  assert.equal(idle.windowResetsAt, null);
  assert.equal(idle.keepAwake.enabled, false);
  assert.equal(idle.keepAwake.keptAwakeToday, 0);
});

test('switched on with no window running, ONE ping goes out — caged, on Haiku', async () => {
  // The argv is asserted off a real process's arguments rather than off the
  // builder, because the builder being right and the spawn being wired to
  // something else is a failure neither a unit test nor a log line would show.
  setUsage({ session: 0, weekly_all: 10, weekly_fable: 20, sessionRunning: false });
  await tick({ keepAwake: true });
  const seen = await untilPings(1);
  const argv = seen[0];
  assert.deepEqual(argv, [
    '-p',
    '--setting-sources', '',
    '--strict-mcp-config',
    '--no-session-persistence',
    '--model', 'claude-haiku-4-5-20251001',
    '--max-turns', '1',
    '--tools', '',
    '--', 'Reply with the single word ok.',
  ]);
  assert.equal(argv.includes('--bare'), false, '--bare never reads OAuth, so it would never touch the window');

  const st = (await api('/v1/status')).body.headroom;
  assert.equal(st.keepAwake.enabled, true);
  assert.equal(st.keepAwake.keptAwakeToday, 1);
  assert.ok(st.keepAwake.lastAt > 0);
  assert.match(String(st.keepAwake.lastAtClock), /^\d{2}:\d{2}$/, 'the HOST formats the clock; :core has no timezone database');
});

test('and it does NOT ping again while that window is still inside its five hours', async () => {
  // The whole guard: `lastAt` on disk, written before the spawn. Several more
  // ticks with the window still reading idle must add nothing.
  const before = pings().length;
  for (let i = 0; i < 4; i++) {
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    await wait(300);
  }
  assert.equal(pings().length, before, 'a second ping inside the same window is money spent twice');
});

test('THE GUARD SURVIVES A RESTART — lastAt is on disk, not in memory', async () => {
  // This is the case that cannot be reproduced on a live account: a daemon that
  // dies between the stamp and the spawn, or is simply restarted, must come back
  // believing the window has been dealt with.
  const before = pings().length;
  const onDisk = JSON.parse(fs.readFileSync(path.join(dataDir, 'headroom.json'), 'utf8'));
  assert.ok(onDisk.keepAwake && onDisk.keepAwake.lastAt > 0, 'lastAt is persisted, not merely remembered');
  assert.equal(onDisk.keepAwake.keptAwakeToday, 1);

  daemon.kill('SIGTERM');
  await wait(600);
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], daemonOpts);
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  for (let i = 0; i < 4; i++) {
    await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    await wait(300);
  }
  assert.equal(pings().length, before, 'a restart must not buy a second ping for the same window');
  assert.equal((await api('/v1/status')).body.headroom.keepAwake.keptAwakeToday, 1);
});

test('a nearly-spent WEEKLY pool stops the ping, with no session window running', async () => {
  // The two pools run on different clocks: the week can be nearly gone while the
  // 5-hour window sits idle, and starting a window nobody can spend is the one
  // way this feature makes things worse.
  //
  // ⚠ WHICH guard stops it is not separable here, and pretending otherwise would
  // be a test that lies. On the default thresholds a window cannot be red
  // (>= ladderPct 92) without also being past the Fable spawn hold
  // (stopFablePct 88), so the sentinel arms in the same pass — both vetoes are
  // live and either alone is enough. The red-window veto is isolated in
  // test/keepawake.test.js, where the thresholds are the test's to choose. What
  // this asserts is the OUTCOME at the wire, which is the part a client sees.
  const before = pings().length;
  // Clear the once-per-window guard by hand — five hours is not a thing a test
  // can wait for, and this is the file the daemon reads on the next tick.
  const state = JSON.parse(fs.readFileSync(path.join(dataDir, 'headroom.json'), 'utf8'));
  state.keepAwake = { ...state.keepAwake, lastAt: 0, prevAt: 0, retries: 0 };
  fs.writeFileSync(path.join(dataDir, 'headroom.json'), JSON.stringify(state, null, 2));
  daemon.kill('SIGTERM');
  await wait(600);
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], daemonOpts);
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }

  setUsage({ session: 0, weekly_all: 10, weekly_fable: 96, sessionRunning: false });
  await tick();
  // ⚠ WAIT FOR A DECISION TAKEN ON THE RED NUMBERS, not merely for time to
  // pass. Nudging four times and checking the ping count is vacuous: a red week
  // arms STOP-FABLE and sets the arbiter typing at panes, so the ticks that
  // follow are long and few, and the assert lands in a gap where keep-awake was
  // never evaluated at all. This test PASSED with both of its vetoes deleted
  // until the published `why` gave it something real to wait for.
  const held = await until(
    (b) => b.keepAwake && /red|exhausted|armed/.test(String(b.keepAwake.why)),
    20_000,
    'a keep-awake decision taken on the red week',
  );
  assert.match(held.keepAwake.why, /red|exhausted|armed/);
  assert.equal(pings().length, before, 'nothing may be spent while a weekly pool is nearly gone');

  // …and the ping lands the moment the week reads healthy again, which proves
  // the veto was the reason rather than the cleared guard failing to take.
  setUsage({ session: 0, weekly_all: 10, weekly_fable: 20, sessionRunning: false });
  await tick();
  await untilPings(before + 1);
});

test('PATCH refuses broken keep-awake settings by NAMING the rule', async () => {
  const bad = await api('/v1/headroom/settings', {
    method: 'PATCH', body: JSON.stringify({ keepAwakeQuietHours: '1am to 7am' }),
  });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /HH:MM-HH:MM/);

  const badModel = await api('/v1/headroom/settings', {
    method: 'PATCH', body: JSON.stringify({ keepAwakeModel: 'the cheap one' }),
  });
  assert.equal(badModel.status, 400);
  assert.match(badModel.body.error, /model id or family alias/);

  const ok = await api('/v1/headroom/settings', {
    method: 'PATCH', body: JSON.stringify({ keepAwakeQuietHours: '01:00-07:00' }),
  });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.keepAwakeQuietHours, '01:00-07:00');
  // Cleared by an empty string as well as by null — a form must be able to say
  // "no quiet hours" with the control it already has.
  const cleared = await api('/v1/headroom/settings', {
    method: 'PATCH', body: JSON.stringify({ keepAwakeQuietHours: '' }),
  });
  assert.equal(cleared.body.keepAwakeQuietHours, null);
  // And off again, so nothing after this file spends anything.
  await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify({ keepAwake: false }) });
});
