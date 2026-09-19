'use strict';
// ONE BOUNDARY RELEASES ONE MESSAGE — the latent bug recorded beside R-24.
//
// THE FAILURE, in the automated lane:
//
//   t0      an automated line is queued for a session that is mid-turn. Held.
//   t0+3s   a SECOND automated line is queued behind it. Also held.
//   t1      the turn ends. The Stop hook writes ONE `{state:"idle", ts:t1}`.
//   t1+ε    `stateVerdict(state, entry.at)` is asked about the head: t1 > t0,
//           so release. It is pasted and submitted.
//   t1+ε'   the SAME pump pass asks about the second entry: t1 > t0+3s too,
//           so release — and the second message is pasted into the turn the
//           first one has just started. Claude Code splices it in
//           (`{"operation":"remove","reason":"absorbed_mid_turn"}`) and the
//           first line's instruction is never carried out.
//
// One idle stamp is one boundary, and the hook writes exactly one per turn. The
// window an entry is judged against therefore starts at the LATER of "when it
// was queued" and "when this queue last typed into the pane" — `Math.max(
// entry.at, q.deliveredAt)` in pumpQueue. After that, only an idle stamped
// after the release is a boundary the next entry may ride.
//
// Human sends are deliberately NOT in this file's subject: decision 57 / the
// 3.5.1 hot-fix says a person's message is delivered at once and never waits
// for a turn. The last test here holds that line — the fix must not resurrect
// #10's one-per-boundary rule for people.
//
// THE DOOR INTO THE AUTOMATED LANE is `POST /v1/projects/:id/message`: appd
// typing on behalf of a peer, `automated:true`, through the send queue and
// every gate on it. The project record is SEEDED on disk rather than created
// through the route, so this file needs no trusted cwd, no real `claude` and no
// spawn — one stand-in pane and a JSON file.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `qbnd-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11600 + pid%12 -> 11600-11611 (the w6 backlog block, 11600-11649).
const PORT = 11600 + (process.pid % 12);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `qbnd-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-qbnd-${process.pid}`;

const BOOT_MS = 400;
const BANNER_MS = 200;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

let tmp, stateDir, dataDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
/**
 * EVERYTHING the pane has seen, scrollback included.
 *
 * ⚠ THE PANE, NOT THE SINK. The stand-in submits on every newline it is handed,
 * and the peer frame is three lines — so a multi-line automated message reaches
 * `.submitted` in pieces and its last line waits for an Enter that the settle
 * logic may reasonably decide not to press. None of that is this file's subject.
 * "Did these bytes go into the pane at all" is, and the pane answers it whatever
 * the fixture does with them afterwards.
 */
function paneText(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-S', '-400', '-t', `=${name}:`]); } catch { return ''; }
}
const inPane = (name, marker) => paneText(name).includes(marker);
const outFor = (name) => path.join(tmp, `${name}.submitted`);
const readOr = (f, dflt = '') => { try { return fs.readFileSync(f, 'utf8'); } catch { return dflt; } };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* no body */ }
  return { status: res.status, body };
}

/** How many submitted lines carry this marker. The peer frame is three lines;
 *  the marker is a line of its own, which is what makes this exact. */
function submitCount(name, marker) {
  return readOr(outFor(name)).split('\n').filter((l) => l.includes(marker)).length;
}
function composerOf(name) {
  return typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
}

/**
 * What huginn-claude-title writes. `ts` is MILLISECONDS (since #7) — and this
 * file's whole subject is which side of a stamp an entry falls on, so the
 * second-granularity spelling would make every assertion here a coin flip.
 */
function writeState(name, state, { transcript, ts = Date.now() } = {}) {
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({
    state, sessionId: `sid-${name}`, transcript, cwd: tmp, ts,
  }));
}

/** A transcript whose last record is an assistant turn STILL RUNNING: the
 *  transcript gate reads "not idle" and never changes its mind by itself. */
function midTurnTranscript(name) {
  const file = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(file, `${JSON.stringify({
    type: 'assistant', message: { stop_reason: 'tool_use', content: [] },
  })}\n`);
  return file;
}

/** Start a stand-in `claude` and wait until its composer is actually painted. */
async function startPane(suffix) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${FAKE}`]);
  for (let i = 0; i < 60; i++) {
    if (composerOf(name) !== null) break;
    await wait(100);
  }
  return name;
}

/**
 * A project record written straight into the store.
 *
 * The lead is a name that need not exist — `POST /message` only requires the
 * RECIPIENT to be running — so one pane is the whole fixture.
 */
function seedProject(memberTmux, role = 'worker') {
  const id = crypto.randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const slug = 'qbnd';
  const project = {
    id,
    name: 'Queue Boundary',
    slug,
    kind: 'infra',
    status: 'active',
    brief: 'a seeded record; nothing here is spawned',
    cwd: tmp,
    lead: {
      role: 'lead', name: `${slug}-lead`, claudeName: `${slug}/lead`,
      sessionId: null, spawnedAt: now, endedAt: null,
    },
    members: [{
      role, name: memberTmux, claudeName: `${slug}/${role}`,
      sessionId: null, cwd: tmp, model: null, effort: null, mode: null,
      firstPrompt: null, spawnedAt: now, endedAt: null,
    }],
    manifest: {
      tag: 'seeded', rev: 1, receivedAt: now, type: 'cluster', scope: '',
      summary: null, sessions: [], untaggedSeen: false, spawnedRev: 1,
    },
    endedReason: null, endedAt: null, createdAt: now, updatedAt: now, rev: 1,
  };
  fs.mkdirSync(path.join(dataDir, 'projects'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'projects', `${id}.json`), JSON.stringify(project));
  return { id, role };
}

/** One automated line, into the member's queue, through every gate. */
const relay = (id, role, text) => api(`/v1/projects/${id}/message`, {
  method: 'POST', body: JSON.stringify({ from: 'lead', to: role, text }),
});
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qbnd-'));
  stateDir = path.join(tmp, 'state');
  dataDir = path.join(tmp, 'data');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(dataDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: dataDir,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      // The panes here are made outside the daemon and are UP before any send.
      HUGINN_APPD_STARTUP_GRACE_MS: '0',
    },
    stdio: ['ignore', logFd, logFd],
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? One leaked by an earlier run
  // answers /v1/ping (no token needed) and rejects our token, which surfaces as
  // every test in this file failing 401 — a code bug that is not one.
  const own = await api('/v1/projects');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
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

// ------------------------------------------------------------ the fail-first

// One delivery can take PASTE_SETTLE_MS + SUBMIT_CONFIRM_MS (3 s + 1 s) before
// the pump looks at the next entry, so "it did not follow the first one in" has
// to be given longer than that to be false.
const AFTER_DELIVERY_MS = 8_000;

async function until(fn, ms, what) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (fn()) return true;
    await wait(150);
  }
  throw new Error(`timed out waiting for ${what}`);
}

test('TWO automated messages behind ONE idle stamp do not flush into the same turn', async () => {
  // ⚠ THE FAIL-FIRST. Against 3.5.1 both relays are released by the single
  // `{state:"idle"}` below — the second one is pasted into the turn the first
  // just started, and both markers are in the pane within a few seconds.
  const name = await startPane('two');
  const transcript = midTurnTranscript(name);
  writeState(name, 'running', { transcript });
  const { id, role } = seedProject(name);

  const first = await relay(id, role, 'MARKER-ONE');
  assert.equal(202, first.status, JSON.stringify(first.body));
  assert.equal(false, first.body.delivered, 'the turn is not over: nothing is typed yet');
  assert.equal('turn', first.body.blockedBy);

  // A second automated line arrives while the first is still waiting. This is
  // the ordinary case — a heads-up and a resume, two peer relays, a notice and
  // a brief — not a contrived one.
  await wait(400);
  const second = await relay(id, role, 'MARKER-TWO');
  assert.equal(202, second.status, JSON.stringify(second.body));
  assert.equal(false, second.body.delivered);
  assert.equal(2, second.body.queued, 'both are waiting on the same queue');
  assert.equal(false, inPane(name, 'MARKER-ONE'), 'and neither has touched the pane');

  // The Stop hook fires ONCE, which is all it ever does per turn.
  writeState(name, 'idle', { transcript });

  // The head goes.
  await until(() => inPane(name, 'MARKER-ONE'), 20_000, 'the first message to reach the pane');

  // And the second must NOT follow it in.
  await wait(AFTER_DELIVERY_MS);
  assert.equal(false, inPane(name, 'MARKER-TWO'),
    'the second message must NOT ride the same boundary — that is an absorbed_mid_turn splice');
  const held = await typingOf(name);
  assert.equal(1, held.queued, 'it is still waiting, not lost');
  assert.equal('turn', held.blockedBy, 'and it says what it is waiting for');

  // The NEXT turn ends. One boundary, one message — so now it goes.
  writeState(name, 'idle', { transcript, ts: Date.now() });
  await until(() => inPane(name, 'MARKER-TWO'), 20_000, 'the next boundary to release the next message');
});

test('a boundary stamped BEFORE the last delivery is not a new boundary', async () => {
  // The same rule from the other side, and the reason the stamp is
  // `q.deliveredAt` rather than "the last time we looked": an idle written
  // while the queue was mid-paste describes the turn that had JUST ended, not
  // the one the paste started. Re-writing it must not release anything.
  const name = await startPane('stale');
  const transcript = midTurnTranscript(name);
  writeState(name, 'running', { transcript });
  const { id, role } = seedProject(name);

  await relay(id, role, 'STALE-ONE');
  await relay(id, role, 'STALE-TWO');
  const boundaryAt = Date.now();
  writeState(name, 'idle', { transcript, ts: boundaryAt });
  await until(() => inPane(name, 'STALE-ONE'), 20_000, 'the first message to reach the pane');

  // The hook rewrites the SAME boundary (a second Stop event for one turn is
  // ordinary — a stop_hook_summary and a turn_duration are two records).
  writeState(name, 'idle', { transcript, ts: boundaryAt });
  await wait(AFTER_DELIVERY_MS);
  assert.equal(false, inPane(name, 'STALE-TWO'), 'the same boundary, restated, is still one boundary');
});

// ---------------------------------------------- and what must NOT have changed

test('a PERSON\'s two messages still both go at once (decision 57 stands)', async () => {
  // ⚠ THE REGRESSION GUARD. #10 once made a person's follow-up wait out a whole
  // turn and the owner's session stacked 55 sends behind one; the 3.5.1 hot-fix
  // restored 3.0.3's rule that a human send is delivered at once. This fix is
  // about the AUTOMATED lane only, and a `Math.max` reached by the human lane
  // would bring that P1 straight back.
  const name = await startPane('human');
  const transcript = midTurnTranscript(name);
  writeState(name, 'running', { transcript });   // mid-turn, and it stays that way

  const send = (text) => api(`/v1/sessions/${name}/keys`, {
    method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
  });
  const one = await send('PERSON-ONE');
  assert.equal(200, one.status, JSON.stringify(one.body));
  assert.equal(true, one.body.delivered, 'a person never waits for a turn');
  const two = await send('PERSON-TWO');
  assert.equal(true, two.body.delivered, 'and neither does their next one');

  await until(() => submitCount(name, 'PERSON-TWO') === 1, 10_000, 'both to be submitted');
  assert.equal(1, submitCount(name, 'PERSON-ONE'));
});
