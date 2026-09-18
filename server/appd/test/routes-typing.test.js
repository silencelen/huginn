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
//   routes-session-state 10750 + pid%50  -> 10750-10799
//   routes-session-start 10800 + pid%50  -> 10800-10849
//   routes-paste-settle 10950 + pid%50   -> 10950-10999
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

let tmp, stateDir, token, daemon, shimLog, failFile, daemonLog;
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

/** Everything the daemon has written to its journal so far. */
function journal() {
  try { return fs.readFileSync(daemonLog, 'utf8'); } catch { return ''; }
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

  // ⚠ THE DAEMON'S OWN LOG IS AN ASSERTION TARGET HERE. The 3.0.x drop wrote its
  // reason into an in-memory struct and nothing else; journalctl had nothing, so
  // 43 lost messages looked exactly like 43 messages nobody had sent. What the
  // journal says is now part of the contract, so it is captured rather than
  // discarded.
  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
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
    stdio: ['ignore', logFd, logFd],
  });
  fs.closeSync(logFd);
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

test('a HUMAN send mid-turn LANDS AT ONCE — Claude Code\'s own queue takes it', async () => {
  // ⚠ REVERSED IN 3.0.3. 3.0.0 held a person's message here until the turn
  // ended, on the theory that a mid-turn message is absorbed into the running
  // turn. In practice it made the message vanish from the sender's view for
  // as long as an agent turn lasted (the owner's report), and 2.x had typed it
  // at once with the TUI showing it queued under the composer. The turn gate
  // now applies to the daemon's AUTOMATED lines only (see typing.test.js and
  // routes-headroom.test.js); a person's text goes to the pane now.
  const { name, out } = mkSink('midturn');
  writeState(name, { state: 'running', transcript: writeTranscript(name, [TURN, USER]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'wait for me', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, true, 'a running turn does not hold a person\'s message');
  assert.equal(body.queued, 0);
  const st = await typingOf(name);
  assert.equal(st.queued, 0);
  assert.equal(st.blockedBy, null);
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(fs.readFileSync(out, 'utf8'), 'wait for me\n', 'it reached the pane immediately');
});

test('a human send is never left waiting on a transcript that stays busy', async () => {
  // The 3.0.0 hold had no floor: a session inside a long agent turn kept a
  // person's message for the whole turn, then (3.0.0) dropped it at ten
  // minutes. No transcript shape may hold a human send now — not a trailing
  // user record, not an assistant record, not the CLI's bookkeeping.
  const { name, out } = mkSink('release');
  const file = writeTranscript(name, [TURN, USER, JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'working' }] } })]);
  writeState(name, { state: 'running', transcript: file });
  const { body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'released', keys: ['Enter'] }),
  });
  assert.equal(body.delivered, true);
  assert.equal((await typingOf(name)).queued, 0);
  for (let i = 0; i < 40 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(fs.readFileSync(out, 'utf8'), 'released\n');
});

/** A pane drawing nothing but an EMPTY composer — the shape that contradicts a
 *  stale `attention` in the state file. */
function mkComposer(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    'sh -c \'printf "────────────\\n\u276f \\n"; sleep 600\'']);
  madeSessions.add(name);
  return name;
}

test('the hook\'s `attention` holds a PERSON\'s message too (#13)', async () => {
  // ⚠ THE HALF THE PANE CANNOT DO. The modal gate reads the pane, and a pane is
  // a picture: a dialog whose options do not fit, a frame captured mid-redraw,
  // a selector the detector has never seen. The title hook's Notification event
  // is authoritative and says `attention` — and until now `state` was passed to
  // releaseDecision on the AUTOMATED lane only, so the one authoritative source
  // of "a question is waiting" never reached a person's message.
  const name = mkSession('attn');
  writeState(name, { state: 'attention', transcript: writeTranscript(name, [TURN]) });
  const { status, body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'this would answer the question', keys: ['Enter'] }),
  });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, 'a question is waiting');
  const st = await typingOf(name);
  assert.equal(st.blockedBy, 'attention');
  assert.equal(st.queued, 1);
});

test('a STALE attention expires against the pane, not against the state file (#13)', async () => {
  // The state file is sticky — it is rewritten by an event, and no event fires
  // when a question is answered from the keyboard. So the hold is pane-backed:
  // an empty composer on screen is proof there is no question in the way, and a
  // person's message goes. Holding it on a state file nobody will refresh is
  // the #1 failure wearing the other hat.
  const name = mkComposer('attnstale');
  writeState(name, { state: 'attention', transcript: writeTranscript(name, [TURN]) });
  for (let i = 0; i < 40 && !/\u276f/.test(capture(name)); i++) await wait(100);
  assert.match(capture(name), /\u276f/, 'precondition: the composer is drawn and empty');
  const { body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'nothing is in the way', keys: ['Enter'] }),
  });
  assert.equal(body.delivered, true, 'the pane contradicts the state file');
  assert.equal((await typingOf(name)).queued, 0);
});

/** A pane that shows a dialog for `holdMs`, then scrolls it away and draws an
 *  empty composer — a gate that OPENS, which is the moment #10 is about. */
function mkClearingModal(suffix, holdMs = 3) {
  const name = `${PFX}-${suffix}`;
  const dialog = 'Switch model?\\n\\n \u276f 1. Yes, switch to Opus 5\\n   2. No, go back\\n\\n Enter to confirm\\n';
  const blank = '\\n'.repeat(40);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'printf "${dialog}"; sleep ${holdMs}; printf "${blank}────────────\\n\u276f \\n"; cat >/dev/null'`]);
  madeSessions.add(name);
  return name;
}

test('two messages QUEUED behind a dialog do not flush into each other (#10)', async () => {
  // ⚠ WHAT MAKES THESE TWO DIFFERENT FROM AN INTERJECTION. 3.0.3's contract is
  // that a person's message never waits for CLAUDE — a message typed while a
  // turn runs is pasted at once, and Claude Code's own queue shows it. But two
  // messages a person QUEUED as separate prompts are not that: when the gate
  // opened, the drain loop released both in one pass, the second landing ~100 ms
  // into the turn the first had just started, where the TUI splices it in
  // (`absorbed_mid_turn`) and the FIRST instruction is never carried out. So
  // the head goes and the rest wait for a real boundary, like the automated lane.
  const name = mkClearingModal('pair');
  const file = writeTranscript(name, [USER]);          // mid-turn: no boundary
  writeState(name, { state: 'running', transcript: file });
  const a = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'first, do the migration', keys: ['Enter'] }),
  });
  const b = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'second, write it up', keys: ['Enter'] }),
  });
  assert.equal(a.body.delivered, false, 'precondition: the dialog holds both');
  assert.equal(b.body.delivered, false);
  assert.equal((await typingOf(name)).blockedBy, 'modal');

  // The dialog goes. Exactly one message may be released by that.
  let st;
  for (let i = 0; i < 80; i++) {
    st = await typingOf(name);
    if (st.blockedBy !== 'modal') break;
    await wait(150);
  }
  await wait(2000);                                     // let the pass finish
  st = await typingOf(name);
  assert.equal(st.queued, 1, 'one released, one still waiting — not both in one pass');
  assert.equal(st.blockedBy, 'turn', 'the second waits for a real boundary now');

  // …and that boundary is the only thing that frees it.
  appendTranscript(file, TURN);
  st = await drains(name);
  assert.equal(st.queued, 0, 'the turn ended, so the second message goes');
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

test('/typing says HOW LONG the held message has been waiting, not just that it is', async () => {
  // ⚠ THE CLIENT'S HALF OF THE 43-MESSAGE BUG. `blockedBy` alone cannot tell a
  // reader whether their message went a second ago or four minutes ago, so a
  // send held behind a dialog looked exactly like a send that never happened.
  const name = mkModal('waited');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
  const { body } = await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'how long have I been here', keys: ['Enter'] }),
  });
  assert.equal(body.delivered, false, 'precondition: the dialog holds it');
  const first = await typingOf(name);
  assert.equal(typeof first.waitedMs, 'number');
  await wait(1_200);
  const later = await typingOf(name);
  assert.equal(later.blockedBy, 'modal');
  assert.ok(later.waitedMs >= 1_000, `the wait grows while it waits (got ${later.waitedMs})`);
  assert.ok(later.waitedMs > first.waitedMs, 'and it is the HEAD entry\'s age, not a constant');
  assert.equal((await typingOf(mkSession('unwaited'))).waitedMs, 0,
    'a session with nothing queued has waited no time at all');
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

test('keys are never queued: an interrupt jumps a message waiting on a dialog', async () => {
  // An Escape means nothing if it arrives later — by then the thing it was
  // interrupting has finished. (Since 3.0.3 a turn no longer holds a person's
  // message, so the only thing that can hold one is a dialog on screen.)
  const name = mkModal('interrupt');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
  await api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'queued behind the dialog', keys: ['Enter'] }),
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
  // Only a dialog holds a person's message now (3.0.3), so a modal pane is the
  // one way to have something waiting for the row to count.
  const name = mkModal('pending');
  writeState(name, { transcript: writeTranscript(name, [TURN]) });
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
