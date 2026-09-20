'use strict';
// The archive routes: a session ended ON PURPOSE that keeps the way back.
//
// THE PROMISE UNDER TEST: "a fully ended session that has the correct
// 'claude resume' command stored for easy bring back to life." Every case here
// is a way that promise breaks while everything on screen still looks right —
// a resume command that opens the wrong directory, a transcript Claude Code
// swept out from under a row that still offers Revive, a revive that starts a
// blank conversation and calls it a success, a reboot that resurrects a session
// somebody deliberately archived.
//
// SAFETY: no real claude, no live daemon, and NOT THE OPERATOR'S TMUX. Sessions
// are named `arch-<pid>-*` on a private tmux socket, run `cat >/dev/null` so
// nothing typed into a pane could execute, and are killed in after(). HOME is
// redirected into the scratch dir AND HUGINN_APPD_CLAUDE_DIR points somewhere
// else again, so the revive path writes its restored transcript into a
// temporary store and never near the real one.
//
// ⚠ AND THE TWO ARE DELIBERATELY DIFFERENT DIRECTORIES. This suite used to
// redirect HOME only, which hid the defect it should have caught: the revive
// path built ~/.claude/projects out of `os.homedir()` and ignored
// HUGINN_APPD_CLAUDE_DIR entirely, so a daemon isolated by that knob — the CLI
// gate, every other route suite — restored fixture transcripts into the
// OPERATOR'S real store. With the two split, the restore assertions below read
// CLAUDE_DIR and would fail the moment anybody resolves that path from the
// process's home again.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const sessreg = require('../lib/session-registry');
const archiveLib = require('../lib/archive');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11250 + pid%50 -> 11250-11299. (11000-11249 are held by sibling work.)
const PORT = 11250 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

const PFX = `arch${process.pid}`;
/** The per-transcript copy cap this daemon runs with (HUGINN_APPD_ARCHIVE_CAP). */
const CAP = 20_000;
// Private tmux socket shared with the daemon under test, so a session leaked by
// a SIGKILL never shows up in the operator's desktop. `-L`, not TMUX_TMPDIR: an
// inherited $TMUX overrides the latter but never the former.
const TMUX_SOCK = `huginn-test-${process.pid}`;

// The tool_input a live AskUserQuestion hook records — the sidecar that makes a
// session read `attention` no matter what the flat state file says (the 3.0.6
// promotion, pinned by routes-session-state.test.js).
const ASK_INPUT = require('./fixtures/prompts/ask-simple-80.input.json');

let tmp, stateDir, dataDir, home, claudeDir, token, daemon;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}

/**
 * The session names tmux actually has.
 *
 * ⚠ `tmux ls` EXITS NON-ZERO WITH NO SERVER RUNNING, and archiving the last
 * session is exactly how this suite gets there — so a bare sh() throws and the
 * assertion that the session is GONE fails for being too right.
 */
function liveNames() {
  try { return sh('tmux', ['ls', '-F', '#S']).split('\n').filter(Boolean); } catch { return []; }
}

const now = () => Math.floor(Date.now() / 1000);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** A pane that cannot execute anything typed into it. */
function mkSession(suffix, cwd = tmp) {
  const name = `${PFX}${suffix}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', cwd, '-x', '120', '-y', '40', 'cat >/dev/null']);
  madeSessions.add(name);
  return name;
}

/**
 * A Claude Code transcript with everything the card is built from: the ai-title,
 * the model, the permission mode, the branch, the cwd, and a last thing said.
 */
function writeTranscript(id, { cwd = tmp, extraBytes = 0, lastText = 'all green, 22 tests' } = {}) {
  const dir = path.join(tmp, 'transcripts');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${id}.jsonl`);
  const stamp = new Date().toISOString();
  const lines = [
    { type: 'ai-title', aiTitle: 'Archive session feature', timestamp: stamp, cwd, gitBranch: 'huginn3/w2-archive' },
    { type: 'permission-mode', permissionMode: 'acceptEdits', timestamp: stamp, cwd },
    { type: 'user', message: { content: 'archive this when it settles' }, timestamp: stamp, cwd },
  ];
  // Filler BEFORE the tail, so a cap test can assert that what survived is the
  // END of the conversation and not its beginning.
  for (let i = 0; i < extraBytes / 200; i++) {
    lines.push({
      type: 'assistant',
      message: { model: 'claude-opus-4-5', content: [{ type: 'text', text: `filler ${i} ${'x'.repeat(150)}` }] },
      timestamp: stamp,
      cwd,
    });
  }
  lines.push({
    type: 'assistant',
    message: { model: 'claude-opus-4-5', content: [{ type: 'text', text: lastText }] },
    timestamp: stamp,
    cwd,
    gitBranch: 'huginn3/w2-archive',
  });
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
  return file;
}

/** What huginn-claude-title writes on every state-bearing hook event. */
function writeState(name, state, { sessionId, transcript = null, cwd = tmp, ts = now() } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state, sessionId, transcript, cwd, ts }));
}

/** What it writes on the PreToolUse that RAISES an AskUserQuestion. */
function writeAskSidecar(name, sessionId) {
  const dir = path.join(stateDir, '.ask');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), JSON.stringify({
    v: 1, tool: 'AskUserQuestion', sessionId, ts: now(), input: ASK_INPUT,
  }));
}

/**
 * A session created the way a client creates one — through POST /v1/sessions, so
 * it lands on the durable restore registry — with a transcript and a state file
 * standing in for the title hook the stub `claude` does not run.
 */
async function mkRegistered(suffix) {
  const wanted = `${PFX}${suffix}`;
  const r = await api('/v1/sessions', { method: 'POST', body: JSON.stringify({ name: wanted }) });
  assert.equal(201, r.status, JSON.stringify(r.body));
  const name = r.body.name;
  madeSessions.add(name);
  const id = crypto.randomUUID();
  writeState(name, 'idle', { sessionId: id, transcript: writeTranscript(id) });
  return { name, id };
}

/** A whole session ready to archive: pane, transcript, state file. */
function mkArchivable(suffix, opts = {}) {
  const name = mkSession(suffix);
  const id = crypto.randomUUID();
  const transcript = writeTranscript(id, opts);
  writeState(name, opts.state || 'idle', { sessionId: id, transcript });
  return { name, id, transcript };
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

async function archives() {
  const { status, body } = await api('/v1/archive');
  assert.equal(200, status);
  return body.archives;
}

async function archiveRow(id, timeoutMs = 20_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const hit = (await archives()).find((a) => a.id === id);
    if (hit && hit.endedAt) return hit;
    await wait(150);
  }
  throw new Error(`${id} never finished archiving`);
}

/** The registry restoreSessionsAfterReboot plans from, as it is ON DISK. */
function registryOnDisk() {
  try {
    return JSON.parse(fs.readFileSync(path.join(dataDir, 'active-sessions.json'), 'utf8')).sessions || {};
  } catch { return {}; }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-archive-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  home = path.join(tmp, 'home');
  // NOT under `home`: see the header note. A transcript that landed in
  // `home/.claude` would be the bug, and these tests have to be able to say so.
  claudeDir = path.join(tmp, 'claude');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(dataDir);
  fs.mkdirSync(home);
  fs.mkdirSync(claudeDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  // ⚠ A REVIVE RUNS `claude --resume` IN A REAL PANE. Without a stub in front of
  // it that is the operator's actual Claude Code, started against a fixture
  // transcript, burning quota. The stub holds the pane open and executes nothing.
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  // On the TEST process too, not just the daemon's: whichever tmux call comes
  // first starts the server, and the server hands its own environment to every
  // pane it will ever open.
  process.env.PATH = `${bin}:${process.env.PATH}`;

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      // ⚠ THE REVIVE PATH WRITES INTO <CLAUDE_DIR>/projects. Both knobs are
      // redirected, at different paths, so a test can never put a fixture
      // transcript into the operator's own store — and so that resolving it
      // from the process's home is a FAILURE here rather than a silent escape.
      HOME: home,
      HUGINN_APPD_CLAUDE_DIR: claudeDir,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      // Small enough that a 40 KB fixture is over it. The rule being exercised is
      // tail-and-boundary, which does not care how big the number is.
      HUGINN_APPD_ARCHIVE_CAP: String(CAP),
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {  // 30s cap: a loaded host has pushed start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one.
  const own = await api('/v1/sessions');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  for (const s of madeSessions) { try { sh('tmux', ['kill-session', '-t', `=${s}`]); } catch { /* gone */ } }
  // Reap the private server outright (-L targets only OUR socket, never default).
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

// ------------------------------------------------------------------ the probe

test('the list route exists, which is how a client knows the feature is here', async () => {
  // Both clients probe this once per connection and hide the whole Archived
  // section on a 404. "200 with a list" is a contract, not a detail.
  const { status, body } = await api('/v1/archive');
  assert.equal(200, status);
  assert.ok(Array.isArray(body.archives));
  assert.equal(archiveLib.MAX_ARCHIVES, body.max, 'the cap is on the wire, so a client can say what it is');
});

// ------------------------------------------------------------- the record

test('an immediate archive writes the card and ends the session', async () => {
  const { name, id } = mkArchivable('now');
  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  assert.equal(202, r.status, JSON.stringify(r.body));
  assert.equal(id, r.body.id, 'keyed by the claude session id, never the tmux name');
  assert.equal(true, r.body.archived);

  const row = (await archives()).find((a) => a.id === id);
  assert.ok(row, 'the card is on the list');
  assert.equal(name, row.tmuxName);
  assert.equal('Archive session feature', row.title, "Claude Code's own title, not the tmux name");
  assert.equal(tmp, row.cwd);
  assert.equal('claude-opus-4-5', row.model);
  assert.equal('acceptEdits', row.permissionMode);
  assert.equal('huginn3/w2-archive', row.gitBranch);
  assert.equal('all green, 22 tests', row.lastMessage, 'the last thing that was actually said');
  assert.ok(row.archivedAt > 0);
  assert.ok(row.endedAt > 0, 'endedAt is what separates a finished archive from a crash mid-archive');
  assert.equal(false, row.live);

  // THE POINT OF THE WHOLE FEATURE, asserted as a literal.
  assert.equal(`cd '${tmp}' && claude --resume ${id}`, row.resumeCommand);

  // And the session is actually gone.
  const live = liveNames();
  assert.equal(false, live.includes(name), 'archive ends the session for real');
});

test('a pane that has never run Claude is refused, not archived into a dead row', async () => {
  const name = mkSession('bare');
  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  assert.equal(409, r.status);
  assert.match(r.body.error, /nothing to bring back/);
  assert.ok(liveNames().includes(name), 'and it is left alone');
});

test('archiving a session nobody has is a 404', async () => {
  const r = await api('/v1/sessions/nosuchsession/archive', { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  assert.equal(404, r.status);
});

// ------------------------------------------------------- the graceful guard

test('a waiting question refuses the archive, in words that say what to do', async () => {
  // The 3.0.6 promotion: a live AskUserQuestion sidecar makes a `running`
  // session read `attention`, and archiving one would throw away the answer it
  // is waiting for — and type prose into a numbered prompt on the way out.
  const { name, id } = mkArchivable('asking');
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });
  writeAskSidecar(name, id);

  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST' });
  assert.equal(409, r.status);
  assert.match(r.body.error, /answer the waiting question first/);
  assert.equal(undefined, (await archives()).find((a) => a.id === id), 'and nothing was written');
  assert.ok(liveNames().includes(name), 'and the session is untouched');
});

/** What a pane currently SHOWS, which is the only thing the daemon reads it by. */
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}

/** A session whose pane is showing a selector dialog, with nothing under it. */
function mkAskingPane(suffix) {
  const name = `${PFX}${suffix}`;
  const dialog = 'Allow Bash(rm -rf build)?\\n\\n \u276f 1. Yes\\n   2. Yes, and do not ask again\\n   3. No\\n\\n Enter to select \\u00b7 Esc to cancel\\n';
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '120', '-y', '40',
    `sh -c 'printf "${dialog}"; cat >/dev/null'`]);
  madeSessions.add(name);
  return name;
}

test('a dialog on screen refuses the archive even when the state file says running (#9)', async () => {
  // ⚠ /soft-end WITH THE DESTRUCTIVE HALF TURNED ALL THE WAY UP. The `attention`
  // guard above is the state FILE, and `running` is its normal reading while a
  // plain tool-permission dialog is up — that kind gets no sidecar at all, and a
  // background agent's tool call rewrites the file to `running` while the main
  // thread sits on the question. So the wrap-up phrase went into the selector
  // and the end armed anyway: at the next stable idle the session was killed AND
  // archived, with no wrap-up turn and a success reported to the caller.
  const name = mkAskingPane('dialog');
  const id = crypto.randomUUID();
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });
  for (let i = 0; i < 60 && !/Allow Bash/.test(capture(name)); i++) await wait(100);
  assert.match(capture(name), /Allow Bash/, 'precondition: the dialog is on screen');

  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST' });
  assert.equal(409, r.status, JSON.stringify(r.body));
  await wait(400);
  assert.ok(liveNames().includes(name), 'the session is untouched');
  assert.equal(undefined, (await archives()).find((a) => a.id === id), 'and nothing was written');
  assert.doesNotMatch(capture(name), /wind|wrap|commit your work/i, 'and nothing was typed at the question');
});

test('a running turn is WAITED OUT rather than refused, and archived when it settles', async () => {
  // Mid-turn text queues in the composer, and the settle timer will not end
  // anything until idle has held — so an archive asked for mid-turn is accepted
  // and reported as queued, instead of making a person watch for the turn to end.
  const { name, id } = mkArchivable('running');
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });

  const r = await api(`/v1/sessions/${name}/archive`, { method: 'POST' });
  assert.equal(202, r.status, JSON.stringify(r.body));
  assert.equal(false, r.body.archived, 'accepted, not done');
  assert.equal(true, r.body.pending);
  assert.equal(true, r.body.queued, 'the wrap-up is behind the turn');
  assert.ok(r.body.phrase, 'and the HOST said which phrase, never a client copy');

  // Still there while the turn runs. The whole difference from a kill.
  assert.ok(liveNames().includes(name));
  assert.equal(undefined, (await archives()).find((a) => a.id === id));

  // ⚠ THE SETTLE TIMER HAS TO SEE THE RUN BEFORE IT SEES THE IDLE. stepSoftEnd
  // will not end anything on an idle it never saw start (that is the guard
  // against a sub-second turn-boundary idle), so a fixture that jumps straight
  // from "archive asked for" to "idle" expires instead of archiving — which is
  // the real hook's behaviour too, written many times during a turn.
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });
  await wait(500);
  // The turn ends: the hook writes idle, and the settle timer takes it from there.
  writeState(name, 'idle', { sessionId: id, transcript: writeTranscript(id) });
  const row = await archiveRow(id, 30_000);
  assert.equal(name, row.tmuxName);
  assert.equal(`cd '${tmp}' && claude --resume ${id}`, row.resumeCommand);
  assert.equal(false, liveNames().includes(name), 'and only then is it ended');
});

test('a wrap-up that turns into a question cancels the archive and leaves the session alive', async () => {
  // The graceful contract, end to end: a session that stops to ask something is
  // the one state this feature will not end from, even once it has been asked to.
  const { name, id } = mkArchivable('cancels');
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });
  assert.equal(202, (await api(`/v1/sessions/${name}/archive`, { method: 'POST' })).status);

  writeAskSidecar(name, id);
  writeState(name, 'running', { sessionId: id, transcript: writeTranscript(id) });
  await wait(6000);   // well past the 3s settle window the kill would need
  assert.ok(liveNames().includes(name), 'still running');
  assert.equal(undefined, (await archives()).find((a) => a.id === id), 'and never archived');
});

// -------------------------------------------------------- the transcript copy

test('the transcript is COPIED at archive time, because Claude Code deletes its own', async () => {
  // cleanupPeriodDays is 21 on this host. Without a copy the stored resume
  // command silently expires and a revive comes back with amnesia.
  const { name, id, transcript } = mkArchivable('copy');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const kept = path.join(dataDir, 'archive', id, 'transcript.jsonl');
  assert.ok(fs.existsSync(kept), 'the copy lives beside the record, keyed by session id');
  assert.equal(fs.readFileSync(transcript, 'utf8'), fs.readFileSync(kept, 'utf8'));

  const row = (await archives()).find((a) => a.id === id);
  assert.equal(fs.statSync(transcript).size, row.transcriptBytes);
  assert.equal(false, row.transcriptTruncated);
  assert.equal(true, row.transcriptPresent);
});

test('an over-cap transcript keeps its TAIL, on whole records, and says it was cut', async () => {
  // The end is what a person is coming back for. Keeping the head would archive
  // the first hour of a two-day conversation and present it as the conversation.
  // The cap is driven down by HUGINN_APPD_ARCHIVE_CAP rather than writing a
  // 32 MB fixture; the rule being exercised is tail-and-boundary, which does not
  // care how big the number is.
  const { name, id, transcript } = mkArchivable('bigcopy', { extraBytes: 40_000, lastText: 'THE VERY LAST THING' });
  assert.ok(fs.statSync(transcript).size > CAP, 'the fixture is genuinely over the cap');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });

  const kept = path.join(dataDir, 'archive', id, 'transcript.jsonl');
  const text = fs.readFileSync(kept, 'utf8');
  assert.ok(Buffer.byteLength(text) <= CAP, 'the cap held');
  assert.ok(text.includes('THE VERY LAST THING'), 'the end of the conversation survived');
  assert.equal(false, text.includes('"filler 0 '), 'and the beginning is what went');
  for (const line of text.split('\n').filter(Boolean)) {
    JSON.parse(line);   // ⚠ EVERY KEPT RECORD IS WHOLE. A tail that starts
                        // mid-line would hand readTranscript half a record.
  }

  const row = (await archives()).find((a) => a.id === id);
  assert.equal(true, row.transcriptTruncated, 'and the row never pretends otherwise');
  assert.equal(true, row.transcriptPresent);
});

test('the archived conversation is readable with no live session behind it', async () => {
  // Every other transcript route begins with `sessionExists` and then reads state
  // keyed on the tmux NAME. Both are gone by now, which is why this route reads
  // the row's own path instead.
  const { name, id } = mkArchivable('read');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const { status, body } = await api(`/v1/archive/${id}/transcript`);
  assert.equal(200, status);
  assert.equal(id, body.claudeSessionId);
  assert.equal(true, body.archived);
  assert.ok(body.events.some((e) => e.kind === 'assistant' && e.text.includes('all green')));
});

// ------------------------------------------------------------------- revive

test('a revive takes the old name back and resumes the conversation', async () => {
  const { name, id } = mkArchivable('revive');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(name, r.body.name, 'the old name, because everything still points at it');
  assert.equal(true, r.body.resumed, 'and the conversation came back, not just the name');
  madeSessions.add(r.body.name);
  assert.ok(liveNames().includes(name));

  const cmd = sh('tmux', ['list-panes', '-t', `=${name}:`, '-F', '#{pane_start_command}']).trim();
  assert.match(cmd, new RegExp(`claude --resume ${id}`), 'the pane really ran the resume');

  // The row stays, marked as back. It is the archive list that says "running as
  // x", which is what keeps the sessions list from having to hide anything.
  const row = (await archives()).find((a) => a.id === id);
  assert.ok(row.revivedAt > 0);
  assert.equal(name, row.revivedAs);
  assert.equal(true, row.live);
});

test('a revive whose archived name carries a dot reports the name tmux made (#103)', async () => {
  // ⚠ tmux REWRITES '.' TO '_' AND STILL EXITS 0. The readback asked for the
  // requested name with a trailing colon — `-t '=a.b:'` — which cannot resolve a
  // rewritten name: tmux answered with an empty string and exit 0, and the
  // `|| want` fallback echoed the dotted name back. The 201 named a session that
  // does not exist, `revivedAs` persisted the phantom, and every per-session
  // route on that name 404s. The create route has the identical readback.
  //
  // Dotted names are refused at the door now, but an archive card written by an
  // older daemon still carries one, and that is what this row is.
  const { name, id } = mkArchivable('dotrevive');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const file = path.join(dataDir, 'archive', id, 'record.json');
  const rec = JSON.parse(fs.readFileSync(file, 'utf8'));
  const dotted = `${PFX}-dot.revive`;
  rec.tmuxName = dotted;
  fs.writeFileSync(file, JSON.stringify(rec, null, 2));

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  madeSessions.add(r.body.name);
  madeSessions.add(dotted.replace('.', '_'));
  assert.ok(liveNames().includes(r.body.name),
    `the 201 named ${r.body.name}, and tmux holds ${JSON.stringify(liveNames())}`);
  assert.equal(false, r.body.name.includes('.'), 'no live session can carry a dot');

  const row = (await archives()).find((a) => a.id === id);
  assert.equal(r.body.name, row.revivedAs, 'and the phantom is not persisted either');
  assert.equal(true, row.live);
});

test('a name taken in the meantime makes the revive land on <name>2', async () => {
  const { name, id } = mkArchivable('coll');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  // Somebody else took the name while it was archived — the ordinary case here,
  // where a name is a word like `main` or `dev`.
  mkSession('coll');

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(`${name}2`, r.body.name, 'still recognisably the thing that came back');
  madeSessions.add(r.body.name);
  assert.ok(liveNames().includes(`${name}2`));
});

test('a row revived under a DIFFERENT name still reads as live', async () => {
  // ⚠ THE ROW IS MATCHED ON BOTH NAMES. A revive onto a taken name lands on
  // `<name>2` and stamps revivedAs; matched on tmuxName alone the row reports a
  // session that came back ten seconds ago as still archived — and offers Revive
  // again, which is how a second Claude ends up appending to one transcript.
  // Found by a screenshot fixture, which is why this exists.
  const { name, id } = mkArchivable('renamed');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  mkSession('renamed');                       // somebody took the old name back

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  assert.equal(`${name}2`, r.body.name);
  madeSessions.add(r.body.name);

  const row = (await archives()).find((a) => a.id === id);
  assert.equal(true, row.live, 'it is running — under revivedAs, not under tmuxName');
  assert.equal(`${name}2`, row.revivedAs);
  // And the stranger holding the OLD name must not be mistaken for it.
  writeState(name, 'idle', { sessionId: crypto.randomUUID(), transcript: null });
  assert.equal(true, (await archives()).find((a) => a.id === id).live);
});

test('a revive restores the kept transcript when Claude Code has swept its own', async () => {
  // THE 21-DAY CASE, which is the entire reason the copy exists. Without the
  // restore, `claude --resume <id>` finds nothing, opens a blank conversation,
  // and the revive reports success.
  const { name, id, transcript } = mkArchivable('sweep');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  // Claude Code's copy is under CLAUDE_DIR; the fixture's is not. Put one where
  // the daemon looks, then delete it — exactly what the sweep does.
  const slug = tmp.replace(/\//g, '-');
  const claudeCopy = path.join(claudeDir, 'projects', slug, `${id}.jsonl`);
  fs.mkdirSync(path.dirname(claudeCopy), { recursive: true });
  fs.copyFileSync(transcript, claudeCopy);
  fs.rmSync(claudeCopy);

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  madeSessions.add(r.body.name);
  assert.equal(true, r.body.restoredTranscript);
  assert.equal(true, r.body.resumed, 'and only then can the resume actually resume');
  assert.ok(fs.existsSync(claudeCopy), 'put back where Claude Code looks for it');
  assert.equal(fs.readFileSync(transcript, 'utf8'), fs.readFileSync(claudeCopy, 'utf8'));
  // ⚠ AND NOWHERE ELSE. The whole point of HUGINN_APPD_CLAUDE_DIR is that a
  // daemon under test cannot reach the real store; resolving this path from the
  // process's home put fixture transcripts into the operator's own
  // ~/.claude/projects, which is how this was found.
  assert.equal(false, fs.existsSync(path.join(home, '.claude')),
    'the revive wrote under HOME instead of the CLAUDE_DIR it was given');
});

test('a revive NEVER overwrites the transcript Claude Code already has', async () => {
  // Ours may be a truncated TAIL of an over-cap conversation. Writing it over a
  // live file would delete history in the name of restoring it.
  const { name, id, transcript } = mkArchivable('nooverwrite');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const slug = tmp.replace(/\//g, '-');
  const claudeCopy = path.join(claudeDir, 'projects', slug, `${id}.jsonl`);
  fs.mkdirSync(path.dirname(claudeCopy), { recursive: true });
  const newer = fs.readFileSync(transcript, 'utf8')
    + JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'SAID AFTER THE ARCHIVE' }] } }) + '\n';
  fs.writeFileSync(claudeCopy, newer);

  const r = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, r.status, JSON.stringify(r.body));
  madeSessions.add(r.body.name);
  assert.equal(false, r.body.restoredTranscript, 'nothing was restored, because nothing was missing');
  assert.equal(newer, fs.readFileSync(claudeCopy, 'utf8'), 'the newer conversation survived intact');
});

test('reviving a conversation that is already running is refused, not doubled', async () => {
  // Two Claudes appending to one jsonl is how a conversation becomes unreadable
  // to both of them.
  const { name, id } = mkArchivable('dup');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const first = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(201, first.status);
  madeSessions.add(first.body.name);
  // The revived pane has to have written its state file for the daemon to know
  // which conversation it holds — the hook does that live; here we stand in.
  writeState(first.body.name, 'idle', { sessionId: id, transcript: writeTranscript(id) });

  const second = await api(`/v1/archive/${id}/revive`, { method: 'POST' });
  assert.equal(409, second.status);
  assert.match(second.body.error, /already running/);
});

test('reviving something that was never archived is a 404', async () => {
  const r = await api('/v1/archive/6f1c0f5e-0000-4000-8000-0000000000ff/revive', { method: 'POST' });
  assert.equal(404, r.status);
});

// ----------------------------------------------- what an archive is NOT in

test('an archived session is gone from /v1/sessions, even while tmux still has it', async () => {
  // Which is what keeps the send-target picker, the desktop palette and the
  // home-screen widget correct for free: all three take a plain session list.
  const { name, id } = mkArchivable('hidden');
  const before = await api('/v1/sessions');
  assert.ok(before.body.sessions.some((s) => s.name === name), 'listed while it is a session');

  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  // The crash window, staged: the row is finished but a session of that name and
  // that conversation is somehow live again.
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, 'cat >/dev/null']);
  madeSessions.add(name);
  writeState(name, 'idle', { sessionId: id, transcript: writeTranscript(id) });

  const after_ = await api('/v1/sessions');
  assert.equal(false, after_.body.sessions.some((s) => s.name === name),
    'an archived conversation is not a session any more');
  assert.equal(true, (await archives()).find((a) => a.id === id).live,
    'and it is not hidden — the archive list says it is running');
});

test('an archived session is off the reboot restore list', async () => {
  // restoreSessionsAfterReboot plans from this registry, so a name left in it
  // would resurrect a session somebody deliberately ended — and resurrect it
  // running `claude --resume`, which is the worst version of that.
  // Created THROUGH the route, which is what puts a session on the restore
  // registry from birth (the reconcile timer would do it too, sixty seconds later).
  const keep = await mkRegistered('keepreg');
  const drop = await mkRegistered('dropreg');
  assert.ok(registryOnDisk()[drop.name], 'the registry knows about it while it is alive');

  await api(`/v1/sessions/${drop.name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });

  const reg = registryOnDisk();
  assert.equal(undefined, reg[drop.name], 'archiving takes it straight off the registry');
  const plan = sessreg.restorePlan(reg, new Set()).map((e) => e.name);
  assert.equal(false, plan.includes(drop.name), 'so a cold boot never brings it back');
  assert.ok(plan.includes(keep.name), 'while a session that is merely idle still would be');
});

// ------------------------------------------------------------ retention

test('DELETE removes the row AND the transcript copy it was keeping', async () => {
  const { name, id } = mkArchivable('del');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });
  const dir = path.join(dataDir, 'archive', id);
  assert.ok(fs.existsSync(path.join(dir, 'transcript.jsonl')));

  const r = await api(`/v1/archive/${id}`, { method: 'DELETE' });
  assert.equal(200, r.status);
  assert.equal(false, fs.existsSync(dir), 'the copy is the bulk of what an archive costs');
  assert.equal(undefined, (await archives()).find((a) => a.id === id));
  assert.equal(404, (await api(`/v1/archive/${id}`)).status);
});

test('the cap keeps the newest 64 and evicts the OLDEST, never the other way round', async () => {
  // Written straight into the store rather than through 65 real sessions: the
  // rule under test is the eviction direction, and the direction is what a
  // comparator gets wrong. Every row here is a finished archive like any other.
  const dir = path.join(dataDir, 'archive');
  const ids = [];
  for (let i = 0; i < archiveLib.MAX_ARCHIVES + 3; i++) {
    const id = crypto.randomUUID();
    ids.push(id);
    fs.mkdirSync(path.join(dir, id), { recursive: true });
    fs.writeFileSync(path.join(dir, id, 'record.json'), JSON.stringify(archiveLib.buildRecord({
      claudeSessionId: id,
      tmuxName: `capfill${i}`,
      cwd: tmp,
      archivedAt: 1_000 + i,       // ascending: ids[0] is the oldest
      endedAt: 1_000 + i,
    })));
  }
  // One more real archive, which is what runs the cap.
  const { name, id } = mkArchivable('capnew');
  await api(`/v1/sessions/${name}/archive`, { method: 'POST', body: JSON.stringify({ mode: 'now' }) });

  const rows = await archives();
  assert.equal(archiveLib.MAX_ARCHIVES, rows.length, 'the store is back at the cap');
  assert.ok(rows.some((a) => a.id === id), 'the one just archived is kept');
  assert.equal(false, rows.some((a) => a.id === ids[0]), 'the oldest went first');
  assert.equal(false, fs.existsSync(path.join(dir, ids[0])), 'and its directory went with it');
  assert.ok(rows.some((a) => a.id === ids[ids.length - 1]), 'the newest of the filler survived');
});
