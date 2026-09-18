'use strict';
// Auto-resume, end to end through a real daemon: notice a 429, wait out Claude
// Code's own continuation where it has one, resume where it does not, and re-run
// the headless work that has none at all.
//
// SAFETY, which matters here as much as it does in routes-headroom:
//   * NO NETWORK. The usage and identity endpoints are local `http.createServer`
//     stubs wired in with HUGINN_APPD_USAGE_URL / HUGINN_APPD_OAUTH_ACCOUNT_URL.
//     Nothing reaches api.anthropic.com; the percentages are set by writing a file.
//   * NO REAL SESSIONS. Every tmux session is `rs-<pid>-*` on a PRIVATE `-L`
//     socket running an inert `cat`. The whole point of this file is that the
//     daemon TYPES into panes — a stray phrase in the owner's own session is an
//     instruction to whatever is running there.
//   * NO REAL CLAUDE. A stub `claude` at the front of PATH records its argv and
//     stdin and prints the stream-json a chat run expects.
//   * Telegram is a RECORDER script in the scratch dir, not the real sender: with
//     no FCM key configured `deliverPush` reaches nobody and the notification
//     falls back to Telegram, which is exactly the path under test.
//
// ⚠ TIME. The native grace is 90 s in production; here it is 2 s via
// HUGINN_APPD_NATIVE_GRACE_MS. The arithmetic is still asserted — a phrase that
// lands before `resetsAt + grace` fails — because the bug this prevents (typing
// while the CLI's own continuation is also about to fire, so the task runs
// twice) is invisible in a test that only checks that something eventually
// happened.

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
//   routes-answer       8788 + pid%900  ->  8788-9687
//   routes-lifecycle    9700 + pid%100  ->  9700-9799
//   routes-rounds       9800 + pid%60   ->  9800-9859
//   routes-devices      9870 + pid%50   ->  9870-9919
//   session-identity    9930 + pid%40   ->  9930-9969
//   breaker-fixes       9971 + pid%25   ->  9971-9995
//   routes-modelgate   10000 + pid%50   -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50   -> 10100-10149
//   routes-scratchpads 10150 + pid%50   -> 10150-10199
//   routes-overview    10200 + pid%50   -> 10200-10249
//   push-retire        10250 + pid%50   -> 10250-10299
//   routes-desktop     10300 + pid%50   -> 10300-10349
//   routes-headroom    10350 + pid%50   -> 10350-10399
//   routes-agent-transcript 10400 + pid%50 -> 10400-10449
//   routes-resume      10450 + pid%10   -> 10450-10459   (this file)
//   routes-refresh     10460 + pid%40   -> 10460-10499
//   routes-typing      10500 + pid%50   -> 10500-10549
//   routes-quick-actions 10600 + pid%50  -> 10600-10649
//   routes-session-state 10750 + pid%50  -> 10750-10799
//   routes-session-start 10800 + pid%50  -> 10800-10849
//   routes-paste-settle 10950 + pid%50   -> 10950-10999
//
// ⚠ 10450-10499 was ONE block in the wave-1 contract; refresh took the upper 40
// of it, so this file keeps only the bottom ten. Widening it back re-collides.
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
const PORT = 10450 + (process.pid % 10);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `rs-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

/** The grace the daemon is told to serve, and the slack a real clock needs. */
const GRACE_MS = 10_000;
const SLACK_MS = 400;

const TURN = JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 90 });
const FABLE = JSON.stringify({
  type: 'assistant',
  message: { model: 'claude-fable-5-1', content: [{ type: 'text', text: 'working on it' }] },
});
/**
 * The stall, in the shape the CLI actually writes it (fixture
 * test/fixtures/transcripts/limit-429.jsonl): an ORDINARY assistant record whose
 * model is `<synthetic>`, flagged `isApiErrorMessage` with `apiErrorStatus:429`.
 */
const stall = (at) => JSON.stringify({
  type: 'assistant',
  timestamp: new Date(at).toISOString(),
  message: { model: '<synthetic>', content: [{ type: 'text', text: "You've hit your session limit · resets 3:10am (America/Los_Angeles)" }] },
  isApiErrorMessage: true,
  apiErrorStatus: 429,
  error: 'rate_limit',
});
/** What Claude Code injects when its OWN wait completes (native-rl §1, verbatim). */
const NATIVE = JSON.stringify({
  type: 'user',
  message: {
    role: 'user',
    content: [{ type: 'text', text: 'Your claude.ai usage limit has reset. Continue the task you were working on when the limit was reached; do not repeat work that is already complete.' }],
  },
});
const human = (at) => JSON.stringify({
  type: 'user',
  timestamp: new Date(at).toISOString(),
  message: { role: 'user', content: [{ type: 'text', text: 'never mind, I took it from here' }] },
});

let tmp, stateDir, claudeDir, dataDir, token, daemon;
let usageServer, acctServer, usageFile, tgLog, claudeLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
/** What a pane currently SHOWS, which is the only thing the daemon reads it by. */
function paneText(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
/** Poll a pane until it shows `re`, rather than assuming a fork+printf has landed. */
async function paneShows(name, re, ms = 45_000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const text = paneText(name);
    if (re.test(text)) return text;
    if (Date.now() > deadline) {
      throw new Error(`${name} never showed ${re} within ${ms}ms — pane held: ${JSON.stringify(text).slice(0, 400)}`);
    }
    await wait(100);
  }
}
/** A pane that swallows whatever is typed at it, into a file we can read. */
function mkSink(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.typed`);
  fs.writeFileSync(out, '');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `sh -c 'stty -echo; cat > ${out}'`]);
  madeSessions.add(name);
  return { name, out };
}
/**
 * A sink pane that starts behind a MODAL and opens when a marker file appears.
 *
 * The queue refuses to deliver into a dialog (a message typed at a selector picks
 * one of its rows), so this is how a test holds a resume in the queue for as long
 * as it wants to look at it — and then lets it through.
 */
async function mkBlockedSink(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.typed`);
  const gate = path.join(tmp, `${suffix}.open`);
  fs.writeFileSync(out, '');
  // A REAL caret glyph: /bin/sh's printf does not expand \u.
  const dialog = 'Switch model?\\n\\n ❯ 1. Yes\\n   2. No\\n';
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `sh -c 'stty -echo; printf "${dialog}"; while [ ! -f ${gate} ]; do sleep 0.2; done; clear; printf " ❯ "; cat > ${out}'`]);
  madeSessions.add(name);
  // ⚠ NOT A MODAL UNTIL IT HAS PAINTED, and this is awaited HERE rather than at
  // the call site on purpose: `stalledSession` writes the state file next, and
  // that file is the whole of how the daemon discovers this session. Between the
  // two, the pane is EMPTY — which `paneReadyForInput` reads as "ready for
  // input", so the daemon's own ten-second resume poll types the phrase straight
  // in instead of holding it behind the dialog. The test then waits thirty
  // seconds for a `queuedAt` that already happened as a delivery, and fails as
  // "the resume to be queued behind the dialog never became true". On an idle
  // host the fork+printf wins that race by milliseconds; at load 11-23 on eight
  // cores it does not. Reproduced 2026-09-15.
  await paneShows(name, /Switch model\?/);
  return { name, out, open: () => fs.writeFileSync(gate, '') };
}
function writeState(name, { state = 'idle', sessionId, transcript } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId, transcript, cwd: tmp, ts: Math.floor(Date.now() / 1000),
  }));
}
function writeTranscript(file, records) {
  fs.writeFileSync(file, `${records.join('\n')}\n`);
}
/**
 * A session sitting on a 429.
 *
 * `native` writes Claude Code's own registry entry (`~/.claude/sessions/<pid>.json`)
 * with `entrypoint:'cli'` — the ONE field that says a real interactive wait
 * exists. `restored` writes appd's mark on the durable registry, which says the
 * process that was waiting is gone.
 */
async function stalledSession(suffix, { native = false, restored = false, extra = [],
  at = Date.now() - 5_000, make = mkSink, armStall = true } = {}) {
  const pane = await make(suffix);
  const { name, out } = pane;
  const sid = `00000000-0000-4000-8000-${String(process.pid).padStart(12, '0').slice(-12)}`
    .replace(/.$/, suffix.slice(-1));
  const transcript = path.join(tmp, `${suffix}.jsonl`);
  // `armStall:false` leaves the 429 OUT of the transcript, so the session is
  // visible to the daemon but not yet stalled — the caller arms it once whatever
  // policy the test is about is actually in place. The window between the state
  // file appearing and the next line of a test is small, and the ten-second
  // resume poll does not care how small: it found the stall, decided the window
  // was back and resumed the session before the test had written the override it
  // was asking about. Reproduced at load 11-23 on eight cores, 2026-09-15.
  const stalled = [FABLE, TURN, stall(at), ...extra];
  writeTranscript(transcript, armStall ? stalled : [FABLE, TURN]);
  writeState(name, { sessionId: sid, transcript });
  if (native) {
    const dir = path.join(claudeDir, 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${suffix}.json`), JSON.stringify({
      pid: 1, sessionId: sid, entrypoint: 'cli', kind: 'interactive', version: '2.1.258',
    }));
  }
  if (restored) {
    const file = path.join(dataDir, 'active-sessions.json');
    let o = { version: 1, sessions: {} };
    try { o = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* first */ }
    o.sessions[name] = {
      name, createdAt: Math.floor(at / 1000) - 60, claudeSessionId: sid, cwd: tmp,
      restoredAt: Math.floor(at / 1000) + 1, updatedAt: Math.floor(Date.now() / 1000),
    };
    fs.writeFileSync(file, JSON.stringify(o, null, 2));
  }
  return {
    name, out, sid, transcript, open: pane.open,
    /** Put the 429 in, making the session stallable from this instant. */
    armStall: () => writeTranscript(transcript, stalled),
  };
}
/** The percentages and the reset instant the stub endpoint answers with. */
function setUsage({ session = 5, weekly_all = 10, weekly_fable = 20, resetsAt = null, noClock = false } = {}) {
  fs.writeFileSync(usageFile, JSON.stringify({ session, weekly_all, weekly_fable, resetsAt, noClock }));
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
/** Everything the daemon typed into this session's pane. */
const typed = (out) => { try { return fs.readFileSync(out, 'utf8'); } catch { return ''; } };
/** Every notification that reached a channel (push has no key here, so Telegram). */
const notifications = () => { try { return fs.readFileSync(tgLog, 'utf8'); } catch { return ''; } };
/** Poll a session's send-queue state — `GET /typing` is what the client reads. */
async function untilTyping(name, fn, ms = 30_000, what = 'the queue') {
  const deadline = Date.now() + ms;
  for (;;) {
    const t = (await api(`/v1/sessions/${name}/typing`)).body || {};
    if (fn(t)) return t;
    if (Date.now() > deadline) {
      throw new Error(`${what} never became true within ${ms}ms. Last /typing: ${JSON.stringify(t)}`);
    }
    await wait(150);
  }
}
/**
 * Wait for a line to reach the notification log, rather than reading it once.
 *
 * ⚠ THE PANE WRITE AND THE TELEGRAM WRITE ARE NOT THE SAME AWAIT. A resume types
 * the phrase and then announces itself, and the digest naming a session is
 * written by the pass that resumed it — so a test that observes the phrase
 * landing and reads the log in the next statement is reading it a beat early.
 * Under load that beat is long enough to fail, and the digest it DOES find is
 * some other session's, which reads as the wrong session being announced.
 */
async function untilLogged(re, ms = 30_000, what = 'the notification') {
  const deadline = Date.now() + ms;
  for (;;) {
    const text = notifications();
    if (re.test(text)) return text;
    if (Date.now() > deadline) {
      throw new Error(`${what} never reached the log within ${ms}ms. Log held: ${JSON.stringify(text).slice(0, 600)}`);
    }
    await wait(150);
  }
}
/** Every `claude` the daemon spawned: {argv, stdin}, oldest first. */
function claudeRuns() {
  let raw = '';
  try { raw = fs.readFileSync(claudeLog, 'utf8'); } catch { return []; }
  return raw.split('\n').filter(Boolean).map((l) => JSON.parse(l));
}

/**
 * Make the daemon take a headroom pass NOW.
 *
 * Its own cadence is a minute at its fastest, and the auto-resume poll only runs
 * while something is stalled — so every test drives it through the settings
 * route, which re-evaluates immediately by design.
 */
async function tick(patch = {}) {
  // ⚠ WAIT OUT THE PLAN TTL. `setUsage` writes a file the stub reads per
  // request, but the daemon serves one reading for PLAN_TTL_MS (1 s here), so a
  // tick fired immediately after changing the percentages decides on the
  // PREVIOUS numbers.
  await wait(1100);
  const r = await api('/v1/headroom/settings', { method: 'PATCH', body: JSON.stringify(patch) });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body;
}
/**
 * Poll a predicate rather than sleeping a fixed time, nudging the tick as we go.
 *
 * ⚠ THE `ms` IS A BUDGET, NEVER AN ASSERTION. Nothing in this file proves
 * anything by timing out — the assertions are the lines after each wait — so
 * these deadlines exist only to stop a hung daemon hanging the suite, and they
 * are sized for the FULL run, where thirty files share eight cores, not for one
 * file on an idle host. Several were 20-25 s, which is a comfortable margin
 * alone and a coin toss at load 11-23.
 */
async function until(fn, ms = 45_000, what = 'the condition') {
  const deadline = Date.now() + ms;
  let last = null;
  let nextTick = Date.now() + 1_500;
  for (;;) {
    last = (await api('/v1/headroom')).body;
    if (await fn(last)) return last;
    if (Date.now() > deadline) {
      throw new Error(`${what} never became true. Last /v1/headroom sessions: `
        + `${JSON.stringify((last && last.sessions) || null).slice(0, 700)}`);
    }
    if (Date.now() > nextTick) {
      nextTick = Date.now() + 1_500;
      await api('/v1/headroom/settings', { method: 'PATCH', body: '{}' });
    }
    await wait(150);
  }
}
/**
 * `until`, with the nudging REMOVED — for waits where hurrying the daemon along
 * is what destroys the thing being waited for.
 *
 * `until` PATCHes the settings route every 1.5 s because most of this file is
 * waiting on a decision the headroom pass makes, and its own cadence is a minute
 * at its fastest. That is exactly the wrong medicine when the question is which
 * of two subsystems notices something first.
 */
async function untilQuiet(fn, ms = 45_000, what = 'the condition', about = null) {
  const deadline = Date.now() + ms;
  let last = null;
  for (;;) {
    last = (await api('/v1/headroom')).body;
    if (await fn(last)) return last;
    if (Date.now() > deadline) {
      // ⚠ THE ROW THIS WAIT WAS ABOUT, not the first 700 characters of every
      // session on the host. A truncated dump of the other fixtures says nothing
      // about why this one never settled, and that is what the old message did.
      const row = about ? sessionRow(last, about) : null;
      const q = about ? (await api(`/v1/sessions/${about}/typing`)).body : null;
      throw new Error(`${what} never became true.`
        + ` ${about || 'session'}: ${JSON.stringify(row)}`
        + ` typing: ${JSON.stringify(q)}`);
    }
    await wait(150);
  }
}
const sessionRow = (body, name) => (body.sessions || []).find((s) => s.name === name) || null;


/**
 * The stub `claude`.
 *
 * Run 1 for any chat dies on a usage limit the way a real `-p` run does — an
 * `is_error` result whose `result` string is the apology — because that, and not
 * a transcript record, is the only evidence a headless run leaves. Every later
 * run succeeds. Both record their argv and stdin.
 */
const STUB = `#!/usr/bin/env node
const fs = require('fs');
let stdin = '';
process.stdin.on('data', (d) => { stdin += d; });
process.stdin.on('end', () => {
  const argv = process.argv.slice(2);
  fs.appendFileSync(process.env.HG_CLAUDE_LOG, JSON.stringify({ argv, stdin }) + '\\n');
  const runs = fs.readFileSync(process.env.HG_CLAUDE_LOG, 'utf8').split('\\n').filter(Boolean).length;
  const sid = '11111111-1111-4111-8111-111111111111';
  const say = (o) => process.stdout.write(JSON.stringify(o) + '\\n');
  say({ type: 'system', subtype: 'init', session_id: sid });
  // Keyed on the TEXT, not on a run counter: the files' tests share one stub and
  // one log, and "the first run ever" is not the same as "the run that is meant
  // to hit the limit".
  const limited = /immich backup/.test(stdin) && !argv.includes('--resume');
  // The MEASURED Fable-weekly apology, which names no clock at all. That is the
  // shape that leaves a stall with nothing to wait for.
  const noClock = /fable credits/.test(stdin) && !argv.includes('--resume');
  // A run that takes long enough for a second message to be QUEUED behind it —
  // the window #39 lives in, which is otherwise a race against a 50 ms stub.
  const slow = /take your time/.test(stdin) && !argv.includes('--resume');
  if (slow) { const t = Date.now(); while (Date.now() - t < 2500) {} }
  if (noClock) {
    say({ type: 'result', subtype: 'error', is_error: true, num_turns: 1, duration_ms: 12,
      result: "You're out of usage credits. Run /usage-credits to keep using Fable 5.1 or /model to switch models." });
  } else if (limited) {
    say({ type: 'result', subtype: 'error', is_error: true, num_turns: 1, duration_ms: 12,
      result: "You've hit your session limit · resets 3:10am (America/Los_Angeles)" });
  } else {
    say({ type: 'assistant', message: { model: 'claude-opus-5', content: [{ type: 'text', text: 'done' }] } });
    say({ type: 'result', subtype: 'success', is_error: false, num_turns: 1, duration_ms: 12, result: 'done' });
  }
  process.exit(0);
});
`;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-rs-'));
  stateDir = path.join(tmp, 'state');
  claudeDir = path.join(tmp, 'claude');
  dataDir = path.join(tmp, 'data');
  const bin = path.join(tmp, 'bin');
  for (const d of [stateDir, claudeDir, dataDir, bin]) fs.mkdirSync(d, { recursive: true });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  claudeLog = path.join(tmp, 'claude-runs.jsonl');
  fs.writeFileSync(claudeLog, '');
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });

  // The notification recorder. With no FCM key the daemon's push reaches nobody
  // and falls back here — the documented order, and the one under test.
  tgLog = path.join(tmp, 'telegram.log');
  fs.writeFileSync(tgLog, '');
  const tg = path.join(tmp, 'send-telegram.sh');
  fs.writeFileSync(tg, '#!/bin/sh\nshift; printf \'%s\\n\' "$1" >> "$HG_TG_LOG"\n', { mode: 0o755 });

  fs.writeFileSync(path.join(claudeDir, '.credentials.json'), JSON.stringify({
    claudeAiOauth: {
      accessToken: 'tok-primary', refreshToken: 'rt-primary',
      expiresAt: Date.now() + 3600_000, subscriptionType: 'max', scopes: ['user:profile'],
    },
  }, null, 2));
  fs.writeFileSync(path.join(claudeDir, 'settings.json'),
    `${JSON.stringify({ model: 'claude-fable-5-1' }, null, 2)}\n`);

  usageFile = path.join(tmp, 'usage.json');
  setUsage({});
  usageServer = http.createServer((req, res) => {
    const u = JSON.parse(fs.readFileSync(usageFile, 'utf8'));
    // `noClock: true` answers with percentages and NO reset time — the real
    // shape when a window has not been published yet, and the one that leaves a
    // stall with nothing to wait for.
    const resets = u.noClock ? undefined : (u.resetsAt || new Date(Date.now() + 3600_000).toISOString());
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

  acctServer = http.createServer((req, res) => {
    const tok = String(req.headers.authorization || '').replace(/^Bearer\s+/, '');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      account: { email_address: `${tok}@example.test`, uuid: `00000000-0000-0000-0000-${tok.slice(-12).padStart(12, '0')}` },
    }));
  });
  await new Promise((r) => acctServer.listen(0, '127.0.0.1', r));

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HG_CLAUDE_LOG: claudeLog,
      HG_TG_LOG: tgLog,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_USAGE_URL: `http://127.0.0.1:${usageServer.address().port}/usage`,
      HUGINN_APPD_OAUTH_ACCOUNT_URL: `http://127.0.0.1:${acctServer.address().port}/account`,
      HUGINN_APPD_PLAN_TTL_MS: '1000',
      HUGINN_APPD_NATIVE_GRACE_MS: String(GRACE_MS),
      HUGINN_APPD_TELEGRAM_SCRIPT: tg,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping happily (ping needs no token) and rejects ours, which reads
  // like a dozen code bugs and is none of them.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

/**
 * SIGTERM, then SIGKILL if it will not go, and wait until the process is REAPED.
 *
 * ⚠ SIGTERM IS A REQUEST, AND THE SCRATCH TREE IS NOT OURS UNTIL IT IS ANSWERED.
 * The daemon still has a headroom pass, a state save and a chat write in flight,
 * and on a loaded host those land after this hook has started walking the
 * directory — rimraf deletes a file, the daemon writes another, and the rmdir
 * fails ENOTEMPTY. `node --test` reports that as the whole FILE failing
 * (`not ok N - routes-resume.test.js`) with all thirteen tests inside it green,
 * which reads like a mystery and is a race in the teardown. Reproduced here at
 * load 11-23 on eight cores, 2026-09-15.
 */
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
  if (usageServer) usageServer.close();
  if (acctServer) acctServer.close();
  if (daemon) await reap(daemon);
  // `maxRetries` as well as the reap: a stray write from anything else that
  // shares this tree must not turn a green file red.
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ---------------------------------------------------------------- headless
//
// These run FIRST, while no tmux session exists: the model ladder acts on live
// Fable sessions, and a red Fable window with one on screen would have the
// arbiter driving a picker in the middle of a chat assertion.

test('a chat launched while the Fable week is red is TOLD to use opus', async () => {
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 97 });
  await tick({ cooldownMs: 0 });
  await until(async (b) => b.accounts && Object.values(b.accounts)
    .some((a) => a.windows.weekly_fable && a.windows.weekly_fable.percent === 97),
  45_000, 'the 97% Fable reading');

  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  const chatId = made.body.id;
  const sent = await api(`/v1/chats/${chatId}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'summarise the disk report' }),
  });
  assert.ok(sent.status < 300, JSON.stringify(sent.body));
  await until(async () => claudeRuns().length >= 1, 45_000, 'the chat run to start');
  const run = claudeRuns()[0];
  // EXPLICIT, not left to the CLI's silent swap: `-p` has no dialog channel, so
  // an unattended run that meets the Fable consent gate picks from a hardcoded
  // chain with no say from us and no record on the chat row.
  const i = run.argv.indexOf('--model');
  assert.notEqual(i, -1, `the run should carry --model: ${run.argv.join(' ')}`);
  assert.equal(run.argv[i + 1], 'opus');
  // …and NEVER this, which turns a downgrade into a dead run.
  assert.equal(process.env.CLAUDE_CODE_NO_MODEL_FALLBACK, undefined);
  const row = (await api('/v1/chats')).body.chats.find((c) => c.id === chatId);
  assert.match(row.ranOn || '', /opus \(Fable limit\)/, 'the chat row says WHY it moved');
});

test('a chat whose turn died on the limit is re-run with --resume and the SAME text', async () => {
  // The window is out of room and comes back two seconds from now.
  const resetsAt = new Date(Date.now() + 2_000).toISOString();
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt });
  await tick({ cooldownMs: 0 });
  // ⚠ THE READING HAS TO LAND FIRST. The stall's `resetsAt` is taken from the
  // endpoint at the moment the run dies, so a chat started before the daemon has
  // read 100% records a stall with no reset to wait for.
  await until(async (b) => b.accounts && Object.values(b.accounts)
    .some((a) => a.windows.session && a.windows.session.percent === 100),
  45_000, 'the 100% session reading');

  const before = claudeRuns().length;
  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  const chatId = made.body.id;
  const TEXT = 'check whether the immich backup finished';
  await api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text: TEXT }) });
  await until(async () => claudeRuns().length > before, 45_000, 'the first run');

  // The window comes back.
  setUsage({ session: 3, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  await until(async () => claudeRuns().length >= before + 2, 45_000, 'the re-run');

  const rerun = claudeRuns()[claudeRuns().length - 1];
  const i = rerun.argv.indexOf('--resume');
  assert.notEqual(i, -1, `the re-run must resume the same conversation: ${rerun.argv.join(' ')}`);
  assert.equal(rerun.argv[i + 1], '11111111-1111-4111-8111-111111111111');
  assert.equal(rerun.stdin, TEXT, 'the same turn, re-fed verbatim — it is the only copy left');

  const row = (await api('/v1/chats')).body.chats.find((c) => c.id === chatId);
  assert.equal(row.resumedAfterLimit, true,
    'the mark is also what stops a daemon restart re-running it a second time');
  const msgs = (await api(`/v1/chats/${chatId}`)).body.messages || [];
  assert.ok(msgs.some((m) => m.type === 'system' && /resumed after the limit reset/.test(m.text || '')),
    'the conversation says why it starts again');
});

// ---------------------------------------------------------------- sessions

test('a stalled session is marked on /v1/sessions and announced once', async () => {
  const s = await stalledSession('a', { native: true });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() + 3600_000).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async () => {
    const rows = (await api('/v1/sessions')).body.sessions || [];
    const row = rows.find((r) => r.name === s.name);
    return row && row.headroom && row.headroom.stalled === true;
  }, 45_000, `${s.name} to be marked stalled`);

  const hr = await until(async (b) => !!(sessionRow(b, s.name) || {}).stall,
    45_000, 'the stall record on /v1/headroom');
  const row = sessionRow(hr, s.name);
  assert.equal(row.stall.window, 'session');
  assert.ok(row.stall.resetsAt, 'the reset instant comes from the ENDPOINT, not the clock text');
  assert.equal(row.stall.resetsAtSource, 'endpoint');
  assert.equal(row.stall.nativeArmed, true, 'entrypoint:cli in the native registry, reset inside 24 h');
  assert.equal(row.stall.resumedAt, null);
  await untilLogged(new RegExp(`${s.name} hit the 5-hour limit`), 30_000, 'the limit notification');
  // Nothing typed: the window has not reset.
  assert.equal(typed(s.out), '');
});

test('the phrase is typed only AFTER the native grace, and the notification says so', async () => {
  // ⚠ THE RESET IS SET FAR ENOUGH OUT TO MEASURE. The daemon notices a stall and
  // a fresh reading within a second or two, so a reset that has already passed
  // by the time anything is observed makes the grace unmeasurable — the
  // assertion below would hold with no grace at all. Eight seconds out, with a
  // ten-second grace, means the low reading is in place well before the phrase
  // is allowed to go.
  const resetsAt = Date.now() + 8_000;
  const s = await stalledSession('b', { native: true });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(resetsAt).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on b');

  // The window comes back, before the reset instant is even reached.
  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  await until(async () => typed(s.out).length > 0, 60_000, 'the resume phrase to land');
  const landedAt = Date.now();
  // ⚠ THE ARITHMETIC IS THE TEST. Typing before the grace expires means two
  // continuations — appd's and the CLI's — and the task runs twice.
  assert.ok(landedAt >= resetsAt + GRACE_MS - SLACK_MS,
    `typed ${resetsAt + GRACE_MS - landedAt}ms too early: the native wait was not given its turn`);
  assert.match(typed(s.out), /usage limit has reset/);

  // Recorded, then announced — both after the phrase, neither in the same await.
  const hr = await until(async (b) => {
    const r = sessionRow(b, s.name);
    return !!(r && r.stall && r.stall.how);
  }, 45_000, 'the resume to be written onto the record');
  const row = sessionRow(hr, s.name);
  assert.equal(row.stall.how, 'appd');
  assert.equal(row.stall.attempts, 1);
  assert.ok(hr.arbiter.lastResumeAt, 'the digest fact the phone watches');
  const named = new RegExp(`Usage limit reset · resumed:.*${s.name}`);
  await untilLogged(named, 30_000, `the resumed digest naming ${s.name}`);
  assert.match(notifications(), named);
});

test('a session appd restored gets no grace — its native wait died with the process', async () => {
  const at = Date.now() - 5_000;
  const s = await stalledSession('c', { native: true, restored: true, at });
  // Already past: nothing to wait for but the daemon noticing.
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() - 1_000).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on c');
  const armed = sessionRow((await api('/v1/headroom')).body, s.name).stall.nativeArmed;
  assert.equal(armed, false, '"Claude Code relaunched during the wait…"');

  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  await until(async () => typed(s.out).length > 0, 45_000, 'the immediate resume');
  assert.match(typed(s.out), /usage limit has reset/);
});

test('the CLI continuing by itself is seen, and NOTHING is typed', async () => {
  // ⚠ IN ORDER. The daemon has to see the 429 as the last record FIRST — that is
  // what a stall is — and only then the continuation landing on top of it. A
  // transcript that arrives with both at once was never a stall as far as any
  // reader can tell, and tests the wrong thing.
  const s = await stalledSession('d', { native: true });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() + 1_500).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on d');

  fs.appendFileSync(s.transcript, `${NATIVE}\n`);
  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  const hr = await until(async (b) => {
    const row = sessionRow(b, s.name);
    return !!(row && row.stall && row.stall.how);
  }, 45_000, 'a verdict on d');
  assert.equal(sessionRow(hr, s.name).stall.how, 'native');
  assert.equal(typed(s.out), '', 'two continuations would run the task twice');
  const nativeNamed = new RegExp(`${s.name} \\(native\\)`);
  await untilLogged(nativeNamed, 30_000, `the resumed digest naming ${s.name} (native)`);
  assert.match(notifications(), nativeNamed,
    'a session that came back on its own is still listed — the owner asked what resumed');
});

test('a person answering it first cancels the resume', async () => {
  const s = await stalledSession('e', { native: true });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() + 1_500).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on e');

  fs.appendFileSync(s.transcript, `${human(Date.now())}\n`);
  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  const hr = await until(async (b) => {
    const row = sessionRow(b, s.name);
    return !!(row && row.stall && row.stall.how);
  }, 45_000, 'a verdict on e');
  assert.equal(sessionRow(hr, s.name).stall.how, 'human');
  assert.equal(typed(s.out), '', 'their turn is the conversation now');
});

test('a per-session autoResume:false is honoured, and says which rule stopped it', async () => {
  // ⚠ THE POLICY GOES IN BEFORE THE SESSION CAN STALL. `armStall:false` creates
  // the session without the 429, so there is nothing for the daemon's own
  // ten-second poll to resume while this test is still writing the override it
  // is about. Armed the other way round, that poll can resume the session in the
  // gap between the state file appearing and the POST below landing — and the
  // test then reads `the window reset and appd typed the resume phrase` and
  // blames the override for being ignored. Seen once under parallel gradle load
  // on 2026-09-15; the window is milliseconds on an idle host.
  const s = await stalledSession('f', { native: false, armStall: false });
  // The override is written through the ordinary route, exactly as the phone
  // would write it — `null` there means FOLLOW THE GLOBAL and must not read as
  // false, which is why the API carries three states and not two.
  const put = await api(`/v1/sessions/${s.name}/meta`, {
    method: 'POST', body: JSON.stringify({ autoResume: false }),
  });
  assert.ok(put.status < 300, JSON.stringify(put.body));
  s.armStall();
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() - 1_000).toISOString() });
  await tick({ cooldownMs: 0 });
  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  const hr = await until(async (b) => {
    const row = sessionRow(b, s.name);
    return !!(row && row.stall && row.stall.why);
  }, 45_000, 'a reason on f');
  const row = sessionRow(hr, s.name);
  assert.equal(row.autoResume, false);
  assert.match(row.stall.why, /off for this session/);
  assert.equal(typed(s.out), '');
});

test('the limit notification is sent ONCE per stall, not once per tick', async () => {
  const s = await stalledSession('g', { native: true });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() + 3600_000).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on g');
  const count = () => notifications().split('\n').filter((l) => l.includes(`${s.name} hit the`)).length;
  assert.equal(count(), 1);
  // Several more passes over the same, unchanged stall. A window can take a week
  // to come back; one notification per minute for it would be ten thousand.
  for (let i = 0; i < 4; i++) await tick({});
  await wait(1_000);
  assert.equal(count(), 1, 'the stall did not change, so there was nothing new to say');
});

test('a Round run that dies on the limit files ONE run, after the re-run', async () => {
  const LA = 'America/Los_Angeles';
  const made = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: 'Immich backup check',
      // The marker the stub reads: this run's first attempt dies on the limit.
      prompt: 'verify the immich backup ran',
      schedule: { kind: 'weekly', days: [0], at: '19:00', tz: LA },
    }),
  });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  const roundId = made.body.id;

  const resetsAt = new Date(Date.now() + 2_000).toISOString();
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt });
  await tick({ cooldownMs: 0 });
  await until(async (b) => b.accounts && Object.values(b.accounts)
    .some((a) => a.windows.session && a.windows.session.percent === 100),
  45_000, 'the 100% session reading');

  const before = claudeRuns().length;
  const fired = await api(`/v1/rounds/${roundId}/run`, { method: 'POST', body: '{}' });
  assert.equal(fired.status, 202, JSON.stringify(fired.body));
  await until(async () => claudeRuns().length > before, 45_000, "the round's first attempt");

  // ⚠ NOTHING IS FILED YET. A report now would be "hit the usage limit", and the
  // re-run would file a second one — two runs, two report notifications, for one
  // scheduled job.
  await wait(500);
  const mid = (await api('/v1/rounds')).body.rounds.find((r) => r.id === roundId);
  assert.strictEqual((mid.runs || []).length, 0, 'the run is waiting for the reset, not finished');

  setUsage({ session: 3, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  // ⚠ `until` answers with the /v1/headroom body it polls, not with whatever the
  // predicate found — so the round is captured here rather than returned.
  let after = null;
  await until(async () => {
    const r = (await api('/v1/rounds')).body.rounds.find((x) => x.id === roundId);
    if (r && (r.runs || []).length >= 1) { after = r; return true; }
    return false;
  }, 45_000, "the round's re-run to file its report");
  assert.strictEqual(after.runs.length, 1, 'one scheduled job, one run in the history');
  // ⚠ AND IT IS THE RE-RUN'S VERDICT. Both attempts live in one messages.jsonl,
  // so the first one's failed result is still there when the second succeeds —
  // filing it would tell the owner a completed job hit the usage limit.
  assert.doesNotMatch(String(after.runs[0].headline || ''), /usage limit|session limit/i,
    "the stale failure from the first attempt must not become the round's report");
  const rerun = claudeRuns()[claudeRuns().length - 1];
  assert.ok(rerun.argv.includes('--resume'), 'and it continued the same conversation');
});

// ------------------------------------------------- a resume that only QUEUED

test('a resume held in the queue is NOT recorded as resumed, and settles when it lands', async () => {
  // ⚠ enqueueSend returns after ONE pump pass. A session behind a dialog answers
  // `delivered:false, dropped:null` — and the stall used to be stamped
  // `resumedAt` anyway, on the strength of the send having been ACCEPTED. Ten
  // minutes later the pump dropped the entry for 'timeout' and nothing
  // reconciled it: applyResumes skipped the session forever and noteStall
  // eventually deleted the record. The one session appd most needs to speak to
  // was the one it silently gave up on.
  const s = await stalledSession('q', { make: mkBlockedSink });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() - 1_000).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on q');

  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  const held = await until(async (b) => {
    const st = (sessionRow(b, s.name) || {}).stall;
    return !!(st && st.queuedAt);
  }, 45_000, 'the resume to be queued behind the dialog');
  const q = sessionRow(held, s.name).stall;
  assert.equal(q.resumedAt, null, 'a send that is merely QUEUED is not a resume');
  assert.equal(q.attempts, 0, 'and it has not spent one of the three attempts');
  assert.equal(typed(s.out), '', 'nothing reached the pane while the dialog was up');
  assert.match(q.why, /queued/);

  // The dialog goes; the pump releases; the entry's own settle is what records it.
  s.open();
  await until(async () => typed(s.out).length > 0, 45_000, 'the resume phrase to land');
  const after = await until(async (b) => {
    const st = (sessionRow(b, s.name) || {}).stall;
    return !!(st && st.resumedAt);
  }, 45_000, 'the settle to stamp the record');
  const row = sessionRow(after, s.name).stall;
  assert.equal(row.how, 'appd');
  assert.equal(row.attempts, 1, 'the attempt is spent when the phrase LANDS, not when it is queued');
  assert.equal(row.queuedAt, null);
  assert.match(typed(s.out), /usage limit has reset/);
});

test('a queued resume the pump DROPS leaves the stall unresumed and unspent', async () => {
  // Dropped for 'human': the owner typed while it waited, so the queue bins it.
  // Nothing was typed, so nothing was spent — and the record must say so rather
  // than reading as a resume that happened.
  const s = await stalledSession('h', { make: mkBlockedSink });
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt: new Date(Date.now() - 1_000).toISOString() });
  await tick({ cooldownMs: 0 });
  await until(async (b) => !!(sessionRow(b, s.name) || {}).stall, 45_000, 'the stall on h');

  setUsage({ session: 2, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  await until(async (b) => {
    const st = (sessionRow(b, s.name) || {}).stall;
    return !!(st && st.queuedAt);
  }, 45_000, 'the resume to be queued behind the dialog');

  // The owner speaks. The pump drops the automated send on its next pass.
  fs.appendFileSync(s.transcript, `${human(Date.now())}\n`);

  // ⚠ DO NOT HURRY THE TICK WHILE WAITING FOR THIS. Two subsystems race to
  // notice that human record and both are right: the send queue's pump drops the
  // entry for 'human' and its settle reconciles the stall, or a headroom pass
  // gets there first and files the stall as `how:'human'` — after which the NEXT
  // headroom pass BLANKS a resolved stall record outright, the row this test
  // polls stops existing, and `!st.queuedAt` can never come true. It then waits
  // out the full thirty seconds and fails as "the drop to be reconciled never
  // became true", which is a sentence about scheduling and not about the daemon.
  //
  // The only thing making those passes frequent is `until` itself, which PATCHes
  // the settings route every 1.5 s to re-evaluate immediately. Left alone the
  // headroom cadence is a minute and the pump's 400 ms poll wins every time —
  // so this wait uses `untilQuiet`, which is `until` without the nudging.
  //
  // And even quiet, BOTH outcomes are correct, so the test accepts both: the
  // stall record reconciles the drop in place, or the daemon treats the owner
  // speaking as a resolution and retires the record outright. What must be true
  // either way is the whole point of the test — appd never typed the phrase and
  // never spent an attempt — so that is asserted on EVERY sample rather than on
  // one read at the end, which is the read a retirement can take away.
  const seen = [];
  const after = await untilQuiet(async (b) => {
    const st = (sessionRow(b, s.name) || {}).stall;
    if (st && st.at) {
      seen.push(st);
      assert.notEqual(st.how, 'appd', 'a send that was never delivered is not an appd resume');
      assert.equal(st.attempts, 0, 'and it must not spend one of the three attempts');
      return !st.queuedAt;                                 // reconciled in place
    }
    // No record at all. We only get here having already watched one (the wait
    // above returned on its `queuedAt`), so this is the other correct outcome:
    // the daemon took the owner speaking as a resolution and retired it.
    return seen.length > 0;
  }, 45_000, 'the drop to be reconciled', s.name);

  assert.ok(seen.length, 'the stall was recorded at all');
  assert.equal(typed(s.out), '', 'nothing was typed at all');
  // The queue's own account of it: what the client shows, and the one place the
  // reason survives whatever the stall record does next. POLLED, because the
  // pump's settle and the stall record's reconciliation are not the same
  // instant — whichever of the two this test saw first, the other is moments
  // behind it, and reading either one in the next statement is reading early.
  const q2 = await untilTyping(s.name,
    (t) => t.queued === 0 && /you typed first/.test(String(t.lastError || '')),
    30_000, 'the queue to report the drop');
  assert.equal(q2.queued, 0, 'the entry is out of the queue, not still waiting');
  assert.match(String(q2.lastError || ''), /you typed first/,
    'the queue says why it binned the send, in the words the client shows');
  // And the record itself, while it is still there to speak for itself. The
  // owner speaking is itself a resolution, so it may well end up marked `human`
  // — what it must never say is that appd typed the phrase.
  const row = (sessionRow(after, s.name) || {}).stall;
  if (row && row.at) assert.match(row.why, /dropped|person answered/);
});

// ---------------------------------------- a stall that never learns its clock

test('a stall with no reset time anywhere is given up on, and the held Round is FILED', async () => {
  // ⚠ `noteRunStall` returning true is what HOLDS a Round's report — one
  // scheduled job, one filed run. So a stall that can never become due is a job
  // that reports nothing, for ever, with `currentChatId` still pointing at it and
  // the ten-second poll running for the life of the daemon.
  const made = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: 'Fable credits check',
      prompt: 'fable credits check',
      schedule: { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' },
    }),
  });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  const roundId = made.body.id;

  // Percentages but NO reset times, and an apology that names no clock either.
  setUsage({ session: 5, weekly_all: 10, weekly_fable: 100, noClock: true });
  await tick({ cooldownMs: 0 });
  const before = claudeRuns().length;
  const fired = await api(`/v1/rounds/${roundId}/run`, { method: 'POST', body: '{}' });
  assert.equal(fired.status, 202, JSON.stringify(fired.body));
  await until(async () => claudeRuns().length > before, 45_000, "the round's attempt");

  let chatId = null;
  await until(async () => {
    const r = (await api('/v1/rounds')).body.rounds.find((x) => x.id === roundId);
    chatId = r && r.currentChatId;
    if (!chatId) return false;
    try {
      const m = JSON.parse(fs.readFileSync(path.join(dataDir, 'chats', chatId, 'meta.json'), 'utf8'));
      return !!(m.stall && m.stall.at);
    } catch { return false; }
  }, 45_000, 'the clockless stall to be recorded');

  const metaFile = path.join(dataDir, 'chats', chatId, 'meta.json');
  const m0 = JSON.parse(fs.readFileSync(metaFile, 'utf8'));
  assert.equal(m0.stall.window, 'weekly_fable');
  assert.equal(m0.stall.resetsAt, null,
    'precondition: the apology named no clock and the endpoint published none either');
  const held = (await api('/v1/rounds')).body.rounds.find((x) => x.id === roundId);
  assert.strictEqual((held.runs || []).length, 0, 'precondition: the report is held open');

  // Six hours later (on disk, because a test cannot wait).
  m0.stall.at = Date.now() - 7 * 3600_000;
  fs.writeFileSync(metaFile, JSON.stringify(m0));

  let after = null;
  await until(async () => {
    const r = (await api('/v1/rounds')).body.rounds.find((x) => x.id === roundId);
    if (r && (r.runs || []).length >= 1) { after = r; return true; }
    return false;
  }, 45_000, 'the held round to be filed once appd gives up');
  assert.equal(after.runs[0].status, 'attention',
    'nothing is wrong with the world; something is wrong with the arrangement');
  assert.match(String(after.runs[0].headline || ''), /no reset time/,
    'and the reason travels with it');

  setUsage({ session: 5, weekly_all: 10, weekly_fable: 20 });
  await tick({});
});

// ------------------------------------------------- a stall keeps its own turn

test('a stalled chat does not drain its queue into the dry window (#39)', async () => {
  // ⚠ THE SECOND MESSAGE ATE THE FIRST. `settleRun` honoured the `stalled` flag
  // only on the `roundId` branch; a plain chat fell through to the unconditional
  // `takePending` drain and spawned the queued message against the SAME
  // exhausted window, where it died on the limit too — and that second failure's
  // `noteRunStall` replaced `meta.stall` wholesale, so the stall now carried
  // message B's text and the re-run after the reset answered only B. Message A
  // was never run again: two limit apologies in the chat, and after the reset an
  // answer to the later question only. Measured 100 ms apart.
  const resetsAt = new Date(Date.now() + 6_000).toISOString();
  setUsage({ session: 100, weekly_all: 10, weekly_fable: 20, resetsAt });
  await tick({ cooldownMs: 0 });
  await until(async (b) => b.accounts && Object.values(b.accounts)
    .some((a) => a.windows.session && a.windows.session.percent === 100),
  45_000, 'the 100% session reading');

  const before = claudeRuns().length;
  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  const chatId = made.body.id;
  const A = 'take your time with the immich backup and say when it finished';
  const B = 'and then tell me how full the disk is';
  await api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text: A }) });
  // Queued behind the run that is about to die on the limit.
  const queued = await api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text: B }) });
  assert.equal(202, queued.status, JSON.stringify(queued.body));
  assert.equal(true, queued.body.queued, 'precondition: B really is behind A');

  // The run dies on the limit. B must NOT be spawned against the same window.
  const row = async () => (await api('/v1/chats')).body.chats.find((c) => c.id === chatId);
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline && (await row()).running) await wait(200);
  await wait(1500);
  assert.equal(before + 1, claudeRuns().length,
    `only A ran: ${JSON.stringify(claudeRuns().slice(before).map((r) => r.stdin))}`);
  assert.equal(1, (await row()).pending, 'B stays on the queue, on disk, for the re-run');

  // …and when the window comes back it is A that is re-run, not B.
  setUsage({ session: 3, weekly_all: 10, weekly_fable: 20 });
  await tick({});
  await until(async () => claudeRuns().length >= before + 2, 45_000, 'the re-run');
  const rerun = claudeRuns()[before + 1];
  assert.ok(rerun.argv.includes('--resume'), `the re-run resumes: ${rerun.argv.join(' ')}`);
  assert.equal(A, rerun.stdin, 'the stall kept the turn that actually stalled');

  // …and B, which waited on disk the whole time, goes next.
  await until(async () => claudeRuns().length >= before + 3, 45_000, 'the queued message');
  assert.equal(B, claudeRuns()[before + 2].stdin, 'the queue drains once there is room for it');
  assert.equal(0, (await row()).pending);
});
