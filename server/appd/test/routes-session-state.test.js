'use strict';
// The word the clients colour: `state` on GET /v1/sessions.
//
// THE P1 THIS FILE EXISTS FOR (reported 2026-09-15): "before it would have the
// session be flagged red 'huginn needs you' so the user knew there was a
// question, its not doing that now and when a pending question is holding up a
// session, its status stays as 'working'".
//
// Measured on the host against a live AskUserQuestion under Claude Code 2.1.258,
// with the dialog on screen for every line of it:
//
//   PreToolUse(AskUserQuestion)     -> state file: running   (raises the dialog)
//   ...six seconds...               -> state file: running
//   Notification                    -> state file: attention
//   one subagent Pre/PostToolUse    -> state file: running, and it stays there
//
// The flat state file is last-writer-wins across the whole session, the
// Notification fires once, and while the main thread sits on a dialog the only
// writers left are its background agents. The prompt SIDECAR is the marker that
// belongs to the main thread alone — huginn-claude-title deliberately refuses to
// clear it on another tool's PreToolUse — so it is what decides here.
//
// SAFETY: never touches a real session. Its tmux sessions are named
// `sstate-<pid>-*` on a private socket, run `cat >/dev/null` so nothing typed
// could execute, and are killed in after(). State files go to a scratch
// HUGINN_APPD_STATE_DIR, never the live daemon's /run directory.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 10750 + pid%50 -> 10750-10799.
// The next block, 10800-10849, is routes-session-start.test.js.
const PORT = 10750 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `sstate-${process.pid}`;
// Private tmux socket shared with the daemon under test, so a session leaked by
// a SIGKILL never shows up in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

// The pane a live AskUserQuestion actually draws, captured from a real session.
const ASK_PANE = path.join(__dirname, 'fixtures', 'prompts', 'ask-simple-80.txt');
// The tool_input the hook had for that same dialog — the sidecar's payload.
const ASK_INPUT = require('./fixtures/prompts/ask-simple-80.input.json');

let tmp, stateDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}

/** A pane holding the captured question. `cat` keeps the shell from drawing a
 *  prompt underneath, which would read as chrome and make the run history. */
function mkAsking(suffix) {
  const name = `${PFX}-${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '80', '-y', '40',
    `sh -c 'cat ${ASK_PANE}; cat'`]);
  madeSessions.add(name);
  return name;
}

const now = () => Math.floor(Date.now() / 1000);

/** What huginn-claude-title writes on every state-bearing hook event. */
function writeState(name, state, { sessionId = null, ts = now() } = {}) {
  fs.writeFileSync(path.join(stateDir, name),
    JSON.stringify({ state, sessionId, transcript: null, cwd: tmp, ts }));
}

/** What it writes on the PreToolUse that RAISES an AskUserQuestion.
 *  ⚠ The directories are DOT-PREFIXED (#2): they share STATE_DIR with the flat
 *  per-session state files, and a session may legitimately be called `plan`. */
function writeAskSidecar(name, { sessionId = null, ts = now(), tool = 'AskUserQuestion' } = {}) {
  const dir = path.join(stateDir, tool === 'ExitPlanMode' ? '.plan' : '.ask');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), JSON.stringify({ v: 1, tool, sessionId, ts, input: ASK_INPUT }));
}

function clearAskSidecar(name) {
  fs.rmSync(path.join(stateDir, '.ask', name), { force: true });
  fs.rmSync(path.join(stateDir, '.plan', name), { force: true });
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

/** The row the clients read, or undefined. */
async function row(name) {
  const { status, body } = await api('/v1/sessions');
  assert.equal(status, 200);
  return (body.sessions || []).find((s) => s.name === name);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-sstate-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
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
  for (let i = 0; i < 300; i++) {  // 30s cap: a loaded host has pushed start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping (no token needed) and rejects ours, which surfaces as
  // every test failing 401 — a code bug that is not one. Ask an authenticated
  // question first, and say how to clear it.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

/** SIGTERM, then SIGKILL, and wait until the process is REAPED — the scratch
 *  tree is not ours to delete until its writes have stopped. */
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

test('precondition: the fixture pane really is a question the daemon can read', async () => {
  const name = mkAsking('pre');
  writeState(name, 'running', { sessionId: 'sid-pre' });
  const { status, body } = await api(`/v1/sessions/${name}/screen`);
  assert.equal(status, 200);
  assert.ok(body.prompt, 'detectPrompt found no question in the fixture pane');
  assert.equal(body.prompt.question, 'Pick a color?');
});

test('the hook\'s millisecond `ts` still reports seconds on the wire (#7)', async () => {
  // The hook stamps `ts` in MILLISECONDS now, so the send queue can tell an idle
  // written in the same wall-clock second as an enqueue from one written before
  // it. `stateSince` is what the clients read and what the born-time guard
  // compares against tmux's `#{session_created}` — both seconds — so the daemon
  // normalises rather than passing the new unit through.
  const name = mkAsking('msts');
  const ms = Date.now();
  writeState(name, 'idle', { sessionId: 'sid-msts', ts: ms });
  const r = await row(name);
  assert.ok(r, 'the session must be listed');
  assert.equal(r.state, 'idle');
  assert.equal(r.stateSince, Math.floor(ms / 1000), 'seconds, from a millisecond stamp');

  // …and a state file an OLDER hook wrote, in seconds, is read unchanged.
  const sec = now();
  writeState(name, 'idle', { sessionId: 'sid-msts', ts: sec });
  assert.equal((await row(name)).stateSince, sec);
});

test('a question on screen is "needs you", even while the state file says running', async () => {
  const name = mkAsking('ask');
  // Exactly what the hook leaves behind on the PreToolUse that RAISES the
  // dialog: the sidecar written, and the flat state file saying `running`
  // because PreToolUse is a running event. There is no Notification yet — on the
  // host it arrives about six seconds later, and the dialog is up the whole time.
  writeState(name, 'running', { sessionId: 'sid-ask' });
  writeAskSidecar(name, { sessionId: 'sid-ask' });

  const r = await row(name);
  assert.ok(r, 'the session must be listed');
  assert.equal(r.state, 'attention',
    'a session holding a question must read as "needs you", not "working"');
});

test('a background agent\'s tool call cannot take a waiting question back to "working"', async () => {
  const name = mkAsking('agent');
  writeState(name, 'running', { sessionId: 'sid-agent' });
  writeAskSidecar(name, { sessionId: 'sid-agent' });
  // The Notification landed: the state file says attention and the row is right.
  writeState(name, 'attention', { sessionId: 'sid-agent' });
  assert.equal((await row(name)).state, 'attention', 'precondition: the Notification was honoured');

  // Now a subagent of this same session runs a tool. huginn-claude-title fires
  // for it too and rewrites the ONE flat state file — and no second Notification
  // is ever coming. This is the line the owner saw: "its status stays as
  // 'working'" while the question sat there unanswered.
  writeState(name, 'running', { sessionId: 'sid-agent' });
  assert.equal((await row(name)).state, 'attention',
    'a subagent\'s PostToolUse must not bury the main thread\'s question');
});

test('answering the question puts the session back to working', async () => {
  const name = mkAsking('answered');
  writeState(name, 'running', { sessionId: 'sid-ans' });
  writeAskSidecar(name, { sessionId: 'sid-ans' });
  assert.equal((await row(name)).state, 'attention', 'precondition: it was asking');

  // PostToolUse(AskUserQuestion) — the hook clears the sidecar and writes running.
  clearAskSidecar(name);
  writeState(name, 'running', { sessionId: 'sid-ans' });
  assert.equal((await row(name)).state, 'running',
    'with the sidecar cleared the flat state file is the answer again');
});

test('an idle session is never dragged into "needs you" by a sidecar', async () => {
  const name = mkAsking('idle');
  // Stop clears the sidecar; if it somehow did not, `idle` is the floor. A turn
  // that has ended is not waiting on a dialog, and a missed clear must not pin a
  // session at "needs you" for the sidecar's whole 24-hour life.
  writeState(name, 'idle', { sessionId: 'sid-idle' });
  writeAskSidecar(name, { sessionId: 'sid-idle' });
  assert.equal((await row(name)).state, 'idle');
});

test('a sidecar from a previous Claude run under the same name is not evidence', async () => {
  const name = mkAsking('stale');
  writeState(name, 'running', { sessionId: 'sid-new' });
  writeAskSidecar(name, { sessionId: 'sid-old' });
  assert.equal((await row(name)).state, 'running',
    'a sidecar belonging to another session id says nothing about this one');
});

// ------------------------------------ the hook's own namespace under STATE_DIR

/**
 * The REAL hook, with its STATE_DIR rewritten to the scratch dir.
 *
 * ⚠ RUN, NOT IMITATED. #2 is a collision between two things the HOOK writes —
 * the flat per-session state file and the sidecar directories — and every test
 * above writes those with `fs`, which is exactly why a namespace collision
 * could sit in the shipped hook for months. `tmux` is shimmed onto the hook's
 * PATH so its `display-message` lands on this file's private socket.
 */
let hookCopy = null;
function hookFor(name, event, payload) {
  if (!hookCopy) {
    const src = fs.readFileSync(path.join(__dirname, '..', '..', 'bin', 'huginn-claude-title'), 'utf8');
    hookCopy = path.join(tmp, 'huginn-claude-title');
    fs.writeFileSync(hookCopy,
      src.replace('STATE_DIR=/run/huginn-claude-state', `STATE_DIR=${stateDir}`), { mode: 0o755 });
    const shimDir = path.join(tmp, 'hookbin');
    fs.mkdirSync(shimDir, { recursive: true });
    fs.writeFileSync(path.join(shimDir, 'tmux'),
      `#!/bin/sh\nexec ${execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim()} -L ${TMUX_SOCK} "$@"\n`,
      { mode: 0o755 });
  }
  const paneId = sh('tmux', ['list-panes', '-t', `=${name}:`, '-F', '#{pane_id}']).trim().split('\n')[0];
  execFileSync('/bin/bash', [hookCopy, event], {
    input: JSON.stringify(payload),
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${path.join(tmp, 'hookbin')}:${process.env.PATH}`,
      TMUX: 'set-so-the-hook-does-not-no-op',
      TMUX_PANE: paneId,
    },
  });
}

test('a session named `plan` still gets a state file (#2)', async () => {
  // ⚠ TWO THINGS SHARED ONE NAMESPACE. The hook put the flat state file at
  // STATE_DIR/<sess> and its prompt sidecars at STATE_DIR/{ask,plan,compacting}/
  // <sess>, and nothing reserved those three names. Whichever existed first
  // decided which half broke: with the directory there, `mv -f <sess>.tmp <sess>`
  // moved the state JSON INTO it, so that session had no state word, no
  // claudeSessionId, no transcript and no conversation tab — forever, silently,
  // at exit 0. (The other order breaks every sidecar on the host instead.)
  const sibling = mkAsking('sib');
  const plan = 'plan';
  sh('tmux', ['new-session', '-d', '-s', plan, '-c', tmp, '-x', '80', '-y', '40', 'cat >/dev/null']);
  madeSessions.add(plan);

  // The sibling raises a plan approval: this is what creates the directory.
  hookFor(sibling, 'PreToolUse', {
    session_id: 'sid-sib', transcript_path: null, cwd: tmp,
    tool_name: 'ExitPlanMode', tool_input: { plan: 'do the thing' },
  });
  // …and the session called `plan` finishes a turn, which is what writes a file
  // at the very path that directory now occupies.
  hookFor(plan, 'Stop', { session_id: 'sid-plan-sess', transcript_path: null, cwd: tmp });

  const st = fs.statSync(path.join(stateDir, plan));
  assert.equal(true, st.isFile(), 'the state file is a FILE, not the sidecar directory');
  const row0 = await row(plan);
  assert.ok(row0, 'the session is listed');
  assert.equal('idle', row0.state, 'and it has a state word at all');
  assert.equal('sid-plan-sess', row0.claudeSessionId,
    'which is what maps this session to its transcript');

  // And the sibling's plan approval still promotes — the other half of the same
  // collision, where NO session on the host gets a sidecar.
  writeState(sibling, 'running', { sessionId: 'sid-sib' });
  assert.equal('attention', (await row(sibling)).state,
    'the sidecar the hook just wrote is where the daemon looks for it');
});

test('a pending plan approval is "needs you" too', async () => {
  const name = mkAsking('plan');
  writeState(name, 'running', { sessionId: 'sid-plan' });
  writeAskSidecar(name, { sessionId: 'sid-plan', tool: 'ExitPlanMode' });
  assert.equal((await row(name)).state, 'attention',
    'ExitPlanMode holds the session exactly as an AskUserQuestion does');
});
