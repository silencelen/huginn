'use strict';
// Route-level tests for typing a message into a pane: the raised caps, the
// bracketed-paste delivery, the send queue's two gates, and the new
// /v1/sessions/:name/typing poll.
//
// SAFETY: never touches a real session. Every tmux session it makes is named
// `typ-<pid>-*` on a PRIVATE `-L` socket, runs an inert `cat` (so pasted text
// can never execute), and is killed in after(). State files go to a scratch
// HUGINN_APPD_STATE_DIR, never /run.
//
// ⚠ WHY A `cat` PANE AND NOT A REAL CLAUDE. Hermetic: no model, no API, no
// trust dialog, and the delivered bytes are readable off disk. It costs one
// thing — a `cat` pane cannot prove `-p` is load-bearing. tmux only emits the
// ESC[200~ brackets to a pane that has ENABLED bracketed paste (a TUI in raw
// mode does; cat does not), and a cat pane's line discipline has ICRNL on, so
// it turns tmux's CR separator back into LF by itself. Both spellings therefore
// look identical on disk here, while against a real TUI the missing `-p` stores
// '\r' between every line and the pane still LOOKS right (spike D3). So the
// byte test below asserts the bytes, and a separate test asserts `-p` is in the
// argv — via a tmux shim on PATH. Do not "simplify" the second into the first.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const typing = require('../lib/typing');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: two files
// sat inside 9700-9949 and the suite passed four times before failing 16 tests
// on an unlucky pair of pids. The width is what makes a range, not the base:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349
//   routes-typing       10500 + pid%50   -> 10500-10549   (this file)
//   routes-quick-actions 10600 + pid%50  -> 10600-10649
//
// 10350-10499 is reserved for wave 1; wave 2 owns 10500-10649.
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10500 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `typ-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

const TURN = JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: 120 });
const USER = JSON.stringify({ type: 'user', message: { content: 'what a human just said' } });

let tmp, stateDir, token, daemon, shimLog, failFile;
const madeSessions = new Set();

function sh(cmd, args) {
  // The test's OWN tmux calls go straight to the real binary: only the daemon's
  // argv is interesting, and mixing ours into the log would make it unreadable.
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
/** A pane that swallows input, for sends whose bytes we do not need to read back. */
function mkSession(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}
/** A pane that WRITES what it is given to a file, with echo off so the pane stays clean. */
function mkSink(suffix) {
  const name = `${PFX}-${suffix}`;
  const out = path.join(tmp, `${suffix}.txt`);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'stty -echo; cat > ${out}'`]);
  madeSessions.add(name);
  return { name, out };
}
/** A pane drawing a selector dialog — the thing that swallows a message whole. */
function mkModal(suffix) {
  const name = `${PFX}-${suffix}`;
  // A REAL caret glyph: `printf` in /bin/sh does not expand \u, so a \u276f
  // here would draw the escape itself and the dialog would not read as one.
  const dialog = 'Switch model?\\n\\n \u276f 1. Yes, switch to Opus 5\\n   2. No, go back\\n\\n Enter to confirm\\n';
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${dialog}"; sleep 600'`]);
  madeSessions.add(name);
  return name;
}
function writeState(name, { state = 'idle', transcript = null } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: `sid-${name}`, transcript, cwd: tmp, ts: Math.floor(Date.now() / 1000),
  }));
}
/** A stub transcript whose last record decides whether the turn gate is open. */
function writeTranscript(name, records) {
  const file = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(file, records.join('\n') + '\n');
  return file;
}
function appendTranscript(file, record) {
  fs.appendFileSync(file, record + '\n');
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
/** Every tmux argv the DAEMON ran, one call per row, args split on tabs. */
function tmuxCalls(sub) {
  const raw = fs.existsSync(shimLog) ? fs.readFileSync(shimLog, 'utf8') : '';
  return raw.split('\n').filter(Boolean).map((l) => l.split('\t').filter((a) => a !== ''))
    .filter((args) => !sub || args.includes(sub));
}
async function typingOf(name) {
  return (await api(`/v1/sessions/${name}/typing`)).body;
}
/** Poll until the queue for this session has drained, or give up and say so. */
async function drains(name, ms = 6000) {
  const until = Date.now() + ms;
  for (;;) {
    const st = await typingOf(name);
    if (st && st.queued === 0) return st;
    if (Date.now() > until) return st;
    await wait(120);
  }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-typ-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  // The tmux shim: records the daemon's argv, can be told to fail load-buffer,
  // and otherwise execs the real thing. On PATH only for the DAEMON.
  const realTmux = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();
  const shimDir = path.join(tmp, 'shim');
  fs.mkdirSync(shimDir);
  shimLog = path.join(tmp, 'tmux-argv.log');
  failFile = path.join(tmp, 'fail-load-buffer');
  fs.writeFileSync(path.join(shimDir, 'tmux'),
    '#!/bin/sh\n'
    + '{ printf \'%s\\t\' "$@" | tr \'\\n\' \' \'; printf \'\\n\'; } >> "$HG_TMUX_LOG"\n'
    + 'if [ -f "$HG_TMUX_FAIL" ]; then\n'
    + '  for a in "$@"; do\n'
    + '    if [ "$a" = "load-buffer" ]; then echo "forced load-buffer failure" >&2; exit 1; fi\n'
    + '  done\n'
    + 'fi\n'
    + `exec ${realTmux} "$@"\n`, { mode: 0o755 });

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${shimDir}:${process.env.PATH}`,
      HG_TMUX_LOG: shimLog,
      HG_TMUX_FAIL: failFile,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
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
  try { fs.unlinkSync(failFile); } catch { /* not set */ }
  for (const name of madeSessions) {
    try { sh('tmux', ['kill-session', '-t', `=${name}`]); } catch { /* gone */ }
  }
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------------ the caps

test('a message at the new 100,000-character cap is accepted', async () => {
  const name = mkSession('cap');
  // Lines, not one 100,000-char run: a pty in canonical mode discards past
  // MAX_CANON (4096) between newlines, which would be a property of the TEST
  // pane rather than of the route.
  const lines = Array.from({ length: 100 }, (_, i) => `${String(i).padStart(3, '0')} ${'x'.repeat(995)}`).join('\n');
  const text = lines + 'x'.repeat(typing.SESSION_TEXT_MAX - lines.length);
  assert.equal(text.length, 100_000, 'precondition: exactly at the cap');
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.ok, true);
  assert.equal(body.delivered, true, 'no transcript means no turn to be mid-way through');
});

test('one character over the cap is refused in the route\'s own words', async () => {
  const name = mkSession('capover');
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'x'.repeat(typing.SESSION_TEXT_MAX + 1) }),
  });
  assert.equal(status, 400);
  assert.equal(body.error, 'text too long');
});

test('a legal message whose UTF-8 exceeds 256 KB is accepted, not read as a broken body', async () => {
  // ⚠ THE readBody LIMIT RAISE. 90,000 three-byte characters is 270 KB of body
  // for a message 10,000 characters UNDER the cap. Against the 256 KB default
  // this arrives as "body too large" — the sender is told their JSON is
  // malformed, for a message the route was about to accept.
  const name = mkSession('utf8');
  const line = '✦'.repeat(900);
  const text = Array.from({ length: 100 }, () => line).join('\n');
  assert.equal(text.length, 90_099);
  assert.ok(Buffer.byteLength(text, 'utf8') > 256 * 1024, 'precondition: over the old body limit');
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text }),
  });
  assert.equal(status, 200, JSON.stringify(body));
});

// -------------------------------------------------------------- the delivery

test('the delivered bytes are the bytes that were sent: real newlines, no CR', async () => {
  const { name, out } = mkSink('bytes');
  const text = 'line one\nline two\ttabbed\nquote " and backslash \\ and ✦\nlast line';
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  for (let i = 0; i < 60 && !fs.existsSync(out); i++) await wait(100);
  let got = '';
  for (let i = 0; i < 60; i++) {
    got = fs.readFileSync(out, 'utf8');
    if (got.length >= text.length + 1) break;
    await wait(100);
  }
  assert.equal(got, `${text}\n`, 'byte-equal, plus the newline the submitting Enter makes');
  assert.equal(got.includes('\r'), false, 'a CR here is the paste-buffer newline corrupter');
  assert.equal((got.match(/\n/g) || []).length, 4, 'every newline survived as a newline');
});

test('the paste argv carries -p and -d, and no paste-buffer call ever omits -p', async () => {
  // ⚠ THE ONE ASSERTION THAT CATCHES THE SILENT CORRUPTER. Plain paste-buffer
  // replaces every '\n' with '\r': against a real TUI the pane renders three
  // correct lines and the transcript stores 'a\rb\rc'. It looks right and
  // arrives wrong, with no error anywhere — so the flag is asserted at the argv
  // rather than at the bytes, which a cat pane cannot tell apart (see the
  // header). `-d` is the buffer hygiene: no message left on the stack.
  const pastes = tmuxCalls('paste-buffer');
  assert.ok(pastes.length > 0, 'the sends above went through paste-buffer at all');
  for (const args of pastes) {
    assert.ok(args.includes('-p'), `paste-buffer without -p: ${args.join(' ')}`);
    assert.ok(args.includes('-d'), `paste-buffer without -d: ${args.join(' ')}`);
  }
  const loads = tmuxCalls('load-buffer');
  assert.ok(loads.length > 0, 'the payload travelled on stdin, not in argv');
  for (const args of loads) {
    assert.ok(args.some((a) => /^hg-[0-9a-f]{6}$/.test(a)), `no per-send buffer name: ${args.join(' ')}`);
    assert.ok(args.includes('-'), 'load-buffer reads stdin: that is what lifts the 16 KB ceiling');
  }
});

test('a composer send presses Enter ONCE — the paste path already submitted', async () => {
  // keys:["Enter"] from a composer means "submit this message". The paste path
  // presses it with the right beat; pressing it again would send a second,
  // empty message to Claude.
  const { name, out } = mkSink('once');
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'just this', keys: ['Enter'] }),
  });
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(400);
  assert.equal(fs.readFileSync(out, 'utf8'), 'just this\n', 'one line, not a blank second one');
});

// ---------------------------------------------------------------- the gates

test('a send lands at once when the transcript says the last turn finished', async () => {
  const { name, out } = mkSink('idle');
  writeState(name, { transcript: writeTranscript(name, [USER, TURN]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'go ahead', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, true);
  assert.equal(body.queued, 0);
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(fs.readFileSync(out, 'utf8'), 'go ahead\n');
});

test('a send mid-turn is QUEUED, and /typing says what it is waiting for', async () => {
  // ⚠ WHY THIS GATE EXISTS. A message delivered mid-turn is not merely late:
  // the TUI splices it into the running turn ("reason":"absorbed_mid_turn") and
  // the FIRST message's instruction is never carried out. Measured.
  const { name, out } = mkSink('midturn');
  writeState(name, { state: 'running', transcript: writeTranscript(name, [TURN, USER]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'wait for me', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, 'a user record is the last thing in the transcript');
  assert.equal(body.queued, 1);
  assert.equal(body.position, 1);
  const st = await typingOf(name);
  assert.equal(st.queued, 1);
  assert.equal(st.blockedBy, 'turn');
  assert.equal(st.delivering, false);
  // The file exists from the moment the pane's shell opens the redirect, so
  // "nothing arrived" is an EMPTY file, not a missing one.
  assert.equal(fs.readFileSync(out, 'utf8'), '', 'and nothing has reached the pane');
});

test('appending a turn_duration record releases the queued send', async () => {
  const { name, out } = mkSink('release');
  const file = writeTranscript(name, [TURN, USER]);
  writeState(name, { state: 'running', transcript: file });
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'released', keys: ['Enter'] }),
  });
  assert.equal((await typingOf(name)).queued, 1);
  appendTranscript(file, TURN);
  const st = await drains(name);
  assert.equal(st.queued, 0, 'the 400 ms poll re-checks the gate and lets it go');
  assert.equal(st.blockedBy, null);
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(fs.readFileSync(out, 'utf8'), 'released\n');
});

test('a dialog on screen queues a text send and names the modal', async () => {
  const name = mkModal('modaltext');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'this would be swallowed', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, 'the turn gate is open; the dialog is what holds it');
  const st = await typingOf(name);
  assert.equal(st.blockedBy, 'modal');
  assert.match(capture(name), /Switch model\?/, 'precondition: the dialog really is on screen');
});

test('a RAW KEY send into a dialog LANDS: those are the keys that answer it', async () => {
  // ⚠ REVERSED IN 3.0. A raw key is a PERSON driving the pane — the Screen tab
  // sends every keypress this way, plus its dedicated Escape and BTab buttons —
  // and Escape, the arrows, BTab and the digits are precisely the keys that
  // dismiss or navigate a dialog. Refusing them took the terminal keyboard and
  // the Interrupt button away at exactly the moment they are needed, and told
  // the reader to go to the Screen tab they were already on. It broke the
  // shipped 2.88.0 client identically.
  const name = mkModal('modalkeys');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
  assert.match(capture(name), /Switch model\?/, 'precondition: the dialog really is on screen');

  const before = tmuxCalls('Escape').length;
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ keys: ['Escape'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.queued, 0, 'a key is never queued — an interrupt at the next turn boundary is nothing');
  assert.equal(tmuxCalls('Escape').length, before + 1, 'and it went to the pane NOW');

  // BTab and the arrows too: navigating a selector is the whole point. (A DIGIT
  // is not a named key — a dialog is answered by number through /answer, which
  // validates it against a freshly-read fingerprint.)
  for (const k of ['BTab', 'Down', 'Up', 'Tab']) {
    const r = await api(`/v1/sessions/${name}/keys`, { method: 'POST', body: JSON.stringify({ keys: [k] }) });
    assert.equal(r.status, 200, `${k}: ${JSON.stringify(r.body)}`);
  }
});

test('a TEXT send into a dialog is still queued, because there IS somewhere to hold it', async () => {
  // The turn-boundary queue survives the change above: a message delivered into
  // a modal is swallowed with no trace anywhere, and unlike a keystroke it can
  // wait.
  const name = mkModal('modaltext2');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'this would be swallowed', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false);
  assert.equal((await typingOf(name)).blockedBy, 'modal');
});

test('keys are never queued: an interrupt jumps a message waiting on a turn', async () => {
  // An Escape means nothing if it arrives at the next turn boundary — by then
  // the thing it was interrupting has finished.
  const name = mkSession('interrupt');
  writeState(name, { state: 'running', transcript: writeTranscript(name, [TURN, USER]) });
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'queued behind the turn', keys: ['Enter'] }),
  });
  assert.equal((await typingOf(name)).queued, 1);
  const before = tmuxCalls('Escape').length;
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ keys: ['Escape'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.queued, 0, 'the key send queued nothing of its own');
  assert.equal(tmuxCalls('Escape').length, before + 1, 'and the Escape went to the pane now');
  assert.equal((await typingOf(name)).queued, 1, 'while the message is still waiting');
});

// ------------------------------------------------------------ the list + poll

test('a session row carries pendingSends while a message waits', async () => {
  const name = mkSession('pending');
  writeState(name, { state: 'running', transcript: writeTranscript(name, [TURN, USER]) });
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'still waiting', keys: ['Enter'] }),
  });
  const rows = (await api('/v1/sessions')).body.sessions;
  const row = rows.find((r) => r.name === name);
  assert.ok(row, 'the session is listed');
  assert.equal(row.pendingSends, 1);
  const quiet = mkSession('pending-quiet');
  const after = (await api('/v1/sessions')).body.sessions.find((r) => r.name === quiet);
  assert.equal(after.pendingSends, 0, 'and a session with nothing waiting says zero, not null');
});

test('/typing on a session that has never sent is all zeroes', async () => {
  const name = mkSession('quiet');
  const st = await typingOf(name);
  assert.deepEqual(
    { queued: st.queued, delivering: st.delivering, lastError: st.lastError, blockedBy: st.blockedBy },
    { queued: 0, delivering: false, lastError: null, blockedBy: null },
  );
  assert.ok(st.serverTime > 1_700_000_000, 'and a clock the client can compare against');
});

test('/typing 404s a session that does not exist', async () => {
  const { status } = await api(`/v1/sessions/${PFX}-nope/typing`);
  assert.equal(status, 404);
});

// --------------------------------------------------------------- the fallback

test('a failing load-buffer falls back to chunked send-keys, and the text still lands', async () => {
  const { name, out } = mkSink('fallback');
  fs.writeFileSync(failFile, '1');
  try {
    const before = tmuxCalls('-l').length;
    const { status, body } = await api(`/v1/sessions/${name}/keys`, {
      method: 'POST', body: JSON.stringify({ text: 'fallback text', keys: ['Enter'] }),
    });
    assert.equal(status, 200, JSON.stringify(body));
    assert.ok(tmuxCalls('-l').length > before, 'it went out as literal send-keys');
    for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
    await wait(400);
    assert.equal(fs.readFileSync(out, 'utf8'), 'fallback text\n');
  } finally { fs.unlinkSync(failFile); }
});

test('when the buffer is unreachable and the text is too big to type, the route says so', async () => {
  // The fallback is bounded by tmux's command line (16,340 minus the target),
  // and a message delivered in visibly mangled pieces is worse than an error.
  const name = mkSession('toobig');
  fs.writeFileSync(failFile, '1');
  try {
    const text = Array.from({ length: 20 }, () => 'y'.repeat(999)).join('\n');
    assert.ok(!typing.sendKeysFits(text, `=${name}:`), 'precondition: over the command-line budget');
    const { status, body } = await api(`/v1/sessions/${name}/keys`, {
      method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
    });
    assert.equal(status, 503, JSON.stringify(body));
    assert.equal(body.error, 'could not reach the pane buffer');
  } finally { fs.unlinkSync(failFile); }
});

test('an unknown session 404s before anything is typed', async () => {
  const { status, body } = await api(`/v1/sessions/${PFX}-ghost/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'hello?' }),
  });
  assert.equal(status, 404);
  assert.equal(body.error, 'no such session');
});

test('a bad key name is refused BEFORE the text is typed, not after', async () => {
  const { name, out } = mkSink('badkey');
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'half a send', keys: ['NotAKey'] }),
  });
  assert.equal(status, 400, JSON.stringify(body));
  assert.match(body.error, /key not allowed/);
  await wait(400);
  assert.equal(fs.readFileSync(out, 'utf8'), '', 'the pane got nothing: a 400 means nothing happened');
});
