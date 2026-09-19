'use strict';
// Whose text is in the box — and what a send does when the answer is "not ours".
//
// THE P1 THIS FILE EXISTS FOR (2026-09-19 04:04:01.611Z, the owner's own live
// session `huginnv20`, reconstructed from the daemon journal and the session
// transcript):
//
//   03:58:25→03:59:11Z  a burst of POST /v1/sessions/huginnv20/keys — the owner
//                       typing in the Screen tab, up to nine posts a second.
//                       What they left in the composer was `" was think ask
//                       againaskada"`: an unsent draft, invisible to the app's
//                       chat tab and to everything in the daemon.
//   04:00:16.419Z       POST /keys with the message and an Enter. The queue was
//                       held, so it waited.
//   04:00:28.379Z       the same text again.
//   04:00:32.818Z       and again. No retry exists in the client: this is a
//                       person pressing Send at a composer that emptied and then
//                       said nothing. Three POSTs, three queue entries.
//   04:01:03.099Z       copy 1 delivered and submitted — clean.
//   04:04:00.094Z       the turn ends (`system/turn_duration`).
//   04:04:01.611Z       copy 2 is pasted into the box the draft is still in, the
//                       probe is seen ON SCREEN (it is — amid their words), Enter
//                       goes, and the transcript keeps the result:
//                         " was think ask againaskadaask questions again, side
//                          note: i was thinking that if a device can reach…"
//                       The owner's draft was submitted as part of a sentence
//                       they never wrote.
//   04:04:01.802Z       copy 3, 191 ms later, lands mid-turn and Claude Code's
//                       own queue absorbs it (`queue-operation: enqueue`).
//
// Two bugs, and this file is the fail-first for both:
//
//   A  NOTHING ON THE DELIVERY PATH EVER ASKED WHOSE COMPOSER IT WAS. The rule
//      existed (`composerEmpty`) and lived only in the lost-paste RECOVERY, which
//      runs when a paste fails to APPEAR. A paste that lands perfectly on top of
//      a draft never went near it.
//   B  AND THE ROUTE COULD NOT TELL A SECOND PRESS FROM A SECOND MESSAGE.
//
// SAFETY: never touches a real session and never starts a real `claude`. Every
// tmux session is named `draft-<pid>-*` on a PRIVATE `-L` socket and all of them
// are killed in after(). State files go to a scratch HUGINN_APPD_STATE_DIR.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — see the table in routes-lifecycle.test.js; this file owns
// 11550 + pid%50 -> 11550-11599.
const PORT = 11550 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const PFX = `draft-${process.pid}`;
// `-L`, not TMUX_TMPDIR: an inherited $TMUX from the launching pane overrides
// the latter but never the former.
const TMUX_SOCK = `huginn-draft-${process.pid}`;

const BOOT_MS = 600;
const BANNER_MS = 300;
const UP_MS = BOOT_MS + BANNER_MS;

const FAKE = path.join(__dirname, 'fixtures', 'fake-claude.js');
const typing = require('../lib/typing');

let tmp, stateDir, token, daemon, daemonLog;
const madeSessions = new Set();

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
function capture(name) {
  try { return sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]); } catch { return ''; }
}
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

/** Send text the way a composer does: the Enter rides with the paste. */
const send = (name, text) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ text, keys: ['Enter'] }),
});
/** Type the way the Screen tab does: text, and no Enter of its own. */
const liveType = (name, text) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ text }),
});
/** A raw keypress, the Screen tab's other half. */
const liveKey = (name, key) => api(`/v1/sessions/${name}/keys`, {
  method: 'POST', body: JSON.stringify({ keys: [key] }),
});
const typingOf = async (name) => (await api(`/v1/sessions/${name}/typing`)).body;

/** Give the draft back the way a person does — C-u kills the line. */
function clearComposer(name) {
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'C-u']);
}

/** Start a fake `claude` and wait until its composer is actually painted. */
async function startPane(suffix, { typed = '', trust = false, ghost = '' } = {}) {
  const name = `${PFX}-${suffix}`;
  madeSessions.add(name);
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `env HG_FAKE_CLAUDE_OUT=${outFor(name)} HG_FAKE_CLAUDE_TYPED='${typed}' `
    + `HG_FAKE_CLAUDE_TRUST=${trust ? '1' : '0'} HG_FAKE_CLAUDE_GHOST='${ghost}' `
    + `HG_FAKE_CLAUDE_BOOT_MS=${BOOT_MS} HG_FAKE_CLAUDE_BANNER_MS=${BANNER_MS} `
    + `${process.execPath} ${FAKE}`]);
  // A trust pane never draws a composer at all — wait for the dialog instead.
  for (let i = 0; i < 60; i++) {
    if (trust ? /I trust this folder/.test(capture(name)) : composerOf(name) !== null) break;
    await wait(100);
  }
  return name;
}

/** How many times `text` was submitted to this pane's stand-in. */
function submitCount(name, text) {
  return readOr(outFor(name)).split('\n').filter((l) => l === text).length;
}
/** The composer's own line, read the way lib/typing reads it. */
function composerOf(name) {
  return typing.composerText(capture(name).replace(/\n$/, '').split('\n'));
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-draft-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));

  daemonLog = path.join(tmp, 'daemon.log');
  const logFd = fs.openSync(daemonLog, 'a');
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: stateDir,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_WORKDIR: tmp,
      // The panes here are made outside the daemon and are UP before any send;
      // zero keeps the startup gate out of a file that is about a different one.
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
  const own = await api('/v1/rounds');
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

// ---------------------------------------------------------------- the harness

test('precondition: the stand-in really does hold a draft a paste would merge with', async () => {
  // The file rests on this. Driven by hand, no daemon involved, so a failure
  // means the FIXTURE stopped modelling the owner's pane rather than the daemon
  // regressing — and the merge below is the exact shape of the 04:04:01.611Z
  // transcript record.
  const name = await startPane('precondition', { typed: 'half a thought' });
  assert.equal(composerOf(name), 'half a thought', 'their draft is in the box');

  execFileSync('tmux', ['-L', TMUX_SOCK, 'load-buffer', '-b', 'hgdraft', '-'], { input: 'the message' });
  sh('tmux', ['paste-buffer', '-b', 'hgdraft', '-p', '-d', '-t', `=${name}:`]);
  await wait(120);
  assert.equal(composerOf(name), 'half a thoughtthe message',
    'a paste goes in FRONT of nothing — it joins whatever is already there');
  sh('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
  await wait(300);
  assert.equal(readOr(outFor(name)).trim(), 'half a thoughtthe message',
    'and the Enter submits both as one sentence — the bug, reproduced');

  clearComposer(name);
  await wait(200);
  assert.equal(composerOf(name), '', 'C-u gives the box back, which the tests below need');
});

// -------------------------------------------------------- A. the draft guard

test('a send is HELD while the composer holds a draft, and never merges with it', async () => {
  // ⚠ THE FAIL-FIRST FOR (A). Against 3.4.1 this send is released the instant it
  // arrives — the gate never asked what was in the box — the paste joins the
  // draft, Enter goes, and `.submitted` holds one line with both sentences in
  // it. `delivered` comes back true and `blockedBy` is null.
  // 3.5.1: the guard keys on KEYSTROKES the daemon saw, not on a capture — a
  // capture cannot tell a person's draft from Claude Code's own queued lines,
  // and reading it that way held every send for minutes. So the draft is typed
  // here the way the owner typed theirs: through the live view.
  const draft = 'half a thought I was still having';
  const name = await startPane('held');
  await liveType(name, draft);
  const text = 'the message the app sent';

  const { status, body } = await send(name, text);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.delivered, false, 'it must NOT have been delivered into their box');
  assert.equal(body.blockedBy, 'draft', "and the send's own answer says why, in one word");
  assert.equal(body.queued, 1);

  await wait(1_200);
  assert.equal(composerOf(name), draft, 'their draft is exactly as they left it');
  assert.equal(readOr(outFor(name)), '', 'nothing submitted — not ours, and above all not theirs');
  const st = await typingOf(name);
  assert.equal(st.blockedBy, 'draft', '/typing reports the same word the send did');
  assert.equal(st.queued, 1, 'and the message is still waiting, not lost');
  assert.ok(st.waitedMs >= 900, 'with how long it has waited, so a client can say so');

  // Now be the person: send or clear the draft. The box is free and the message
  // goes — once, whole, and with nothing of theirs attached to it.
  clearComposer(name);
  for (let i = 0; i < 60 && submitCount(name, text) === 0; i++) await wait(100);
  assert.equal(submitCount(name, text), 1, 'delivered exactly once, the moment the box was free');
  assert.equal(readOr(outFor(name)).split('\n').filter((l) => l.trim()).length, 1,
    'and it is the ONLY thing that was ever submitted');
  assert.doesNotMatch(readOr(outFor(name)), /half a thought/,
    "their draft was never submitted — not alone, and not welded to somebody else's sentence");

  const jrnl = readOr(daemonLog);
  assert.match(jrnl, new RegExp(`${name}: holding a send — there is unsent text in the live view`),
    'a hold nobody can see is the bug this replaces: it says so on disk');
  assert.match(jrnl, /half a thought/, 'carrying what was in the box, because that is the evidence');
  assert.match(jrnl, new RegExp(`${name}: the live view's composer is free again`),
    'and says when it let go');
});

test('keystrokes arriving during a pending send keep it held, composer or no composer', async () => {
  // ⚠ THE HALF A CAPTURE CANNOT SEE. A keypress accepted by the route but not yet
  // painted is not in any capture — the owner's burst ran at up to nine posts a
  // second — so an empty-LOOKING box during live-view typing is not an empty box.
  // Against 3.4.1 the send below goes straight into the middle of what they are
  // typing.
  const name = await startPane('keys');
  assert.equal(composerOf(name), '', 'precondition: the box LOOKS empty');

  await liveKey(name, 'BTab');                 // a person at the Screen tab's keyboard
  const { body } = await send(name, 'a message that must wait its turn');
  assert.equal(body.delivered, false, 'the box belongs to whoever is typing into it');
  assert.equal(body.blockedBy, 'draft');

  // Keep typing: the window is rolling, so the hold rolls with it.
  await wait(2_000);
  await liveType(name, 'x');
  await wait(2_000);
  assert.equal((await typingOf(name)).blockedBy, 'draft',
    'still theirs — a fixed deadline would have released this one mid-word');
  assert.equal(submitCount(name, 'a message that must wait its turn'), 0);

  // They stop, and clear what they typed. The window lapses and the message goes.
  clearComposer(name);
  for (let i = 0; i < 100 && submitCount(name, 'a message that must wait its turn') === 0; i++) {
    await wait(100);
  }
  assert.equal(submitCount(name, 'a message that must wait its turn'), 1,
    'delivered once the person stopped, and exactly once');
});

test("the Screen tab's own typing is never held by its own draft", async () => {
  // The guard must not eat the thing it is guarding. Live-view text carries no
  // Enter, IS the draft, and has to land while the box is full — otherwise the
  // terminal keyboard stops working the moment somebody types a second character.
  const name = await startPane('livetype');

  for (const ch of ['a', 'b', 'c']) {
    const { body } = await liveType(name, ch);
    assert.equal(body.delivered, true, `'${ch}' must go straight through`);
    assert.equal(body.blockedBy, null);
  }
  await wait(300);
  assert.equal(composerOf(name), 'abc', 'all three characters are in the box, in order');
  assert.equal(readOr(outFor(name)), '', 'and nothing was submitted, because nothing pressed Enter');
});

test('a draft the LIVE VIEW relayed is theirs for the whole window, not five seconds', async () => {
  // ⚠ THE FAIL-FIRST FOR H1 (round-2 review, 2026-09-19). The test above types
  // the draft one character at a time, which is the only shape that survived
  // `pasteOnce` remembering every paste as appd's own. The clients do NOT type
  // that way: `LiveInput.merge()` coalesces a burst into ONE `{text}` op, an IME
  // commit arrives whole, and a paste into the live-view field arrives whole. So
  // the ENTIRE draft was written into `lastPasted`, `composerHoldsDraft` read it
  // back as our own leftovers, and `draftHold` fell through to the 5-second
  // empty-box quiet instead of the 60-second keystroke window.
  //
  // Against 3.5.2 this send is released ~5 s after the last keystroke, pasted in
  // front of their sentence and submitted with it — `submitCount` of the welded
  // line reads 1 and `blockedBy` has gone null by the first assertion below.
  const draft = 'a whole sentence the owner relayed in one op and is still writing';
  const text = 'MARKER-KAPPA the message that must not join it';
  const name = await startPane('relayed');

  await liveType(name, draft);          // ONE op, the way the clients send it
  const { body } = await send(name, text);
  assert.equal(body.delivered, false, 'a relayed draft is a draft');
  assert.equal(body.blockedBy, 'draft');

  // Past LIVE_KEYS_QUIET_MS (5 s) — the window a draft recorded as "ours" falls
  // back to — and well inside LIVE_KEYS_WINDOW_MS (60 s), which is what a draft
  // the daemon can SEE is actually worth.
  assert.ok(typing.LIVE_KEYS_QUIET_MS < 8_000 && typing.LIVE_KEYS_WINDOW_MS > 8_000,
    'precondition: 8 s is past the quiet and inside the window');
  await wait(8_000);

  const st = await typingOf(name);
  assert.equal(st.blockedBy, 'draft', 'still theirs eight seconds after the last keystroke');
  assert.equal(st.queued, 1, 'and the message is waiting, not welded');
  assert.equal(composerOf(name), draft, 'their sentence is exactly as they left it');
  assert.equal(readOr(outFor(name)), '', 'nothing submitted');

  // They clear it; the message goes, alone.
  clearComposer(name);
  for (let i = 0; i < 80 && submitCount(name, text) === 0; i++) await wait(100);
  assert.equal(submitCount(name, text), 1, 'delivered once, the moment the box was free');
  assert.doesNotMatch(readOr(outFor(name)), /still writing/,
    'and their draft was never part of the sentence that went');
});

test("Claude Code's dim ghost suggestion is not a draft and holds nothing", async () => {
  // ⚠ P-14. A suggestion is drawn IN the composer, in dim text, while the box is
  // empty — `❯ <SGR-2>run sleep 10 in the background then say doneB<reset>` with
  // the cursor at column 2. Strip the escapes and it reads as somebody's
  // sentence, which is what `composerHoldsDraft` said about it. Belt-and-braces
  // saved it only for somebody who had not just used the Screen tab; this test
  // puts them inside that window, which is exactly the person the guard is for.
  //
  // Against 3.5.2 the send below comes back `blockedBy:"draft"` and sits there.
  const suggestion = 'run sleep 10 in the background then say doneB';
  const name = await startPane('ghost', { ghost: suggestion });
  // A person at the Screen tab, so the keystroke half of the guard is live and
  // only the CAPTURE is left to decide. C-u rather than BTab: it leaves the box
  // empty, which is the only state a suggestion is ever drawn in.
  await liveKey(name, 'C-u');
  await wait(400);
  assert.equal(composerOf(name), suggestion,
    'precondition: a capture WITHOUT -e cannot tell the ghost from a draft');

  const msg = 'a message that must not wait on a suggestion';
  await send(name, msg);
  // The in-flight-keys quiet (5 s) may hold it, as it does on any pane a
  // keypress just reached — that is the half a capture cannot see and it is
  // correct. What must NOT happen is the 60-second window, which is what a
  // ghost read as a draft buys it.
  assert.ok(typing.LIVE_KEYS_QUIET_MS < 7_000 && typing.LIVE_KEYS_WINDOW_MS > 7_000,
    'precondition: 7 s is past the quiet and inside the window');
  await wait(7_000);

  assert.equal(submitCount(name, msg), 1,
    'delivered: a suggestion nobody typed never held it for the keystroke window');
  const st = await typingOf(name);
  assert.equal(st.queued, 0, 'nothing left waiting');
  assert.equal(st.blockedBy, null, 'and nothing claiming the box');
  assert.doesNotMatch(readOr(outFor(name)), /run sleep 10/,
    "and Claude Code's own suggestion was never submitted as part of it");
});

// ----------------------------------------------------- B. the double delivery

test('the same message POSTed twice while the first is still queued delivers ONCE', async () => {
  // ⚠ THE FAIL-FIRST FOR (B). This is 04:00:16 / 04:00:28 / 04:00:32 in
  // miniature: a held queue, the identical text pressed again because nothing
  // visible happened. Against 3.4.1 both copies are queued and both are
  // delivered — `queued` reads 2 here and `submitCount` reads 2 at the end.
  // 3.5.1: a person's message goes at once (3.0.3), so the re-presses arrive
  // AFTER delivery — and are still the same press. `yes` twice is two answers;
  // a message this long twice in 30 s is one message pressed again.
  const name = await startPane('dupe');
  const text = 'ask questions again, side note: the one that arrived three times';

  const first = await send(name, text);
  assert.equal(first.status, 200);
  for (let i = 0; i < 80 && submitCount(name, text) === 0; i++) await wait(100);
  assert.equal(submitCount(name, text), 1, 'the first press is delivered');

  const second = await send(name, text);
  assert.equal(second.status, 200, 'the second press is not an error — it is the same message');
  assert.equal(second.body.duplicate, true, 'and the route says it recognised it');
  const third = await send(name, text);
  assert.equal(third.body.duplicate, true, 'a third press is the same answer again');

  await wait(1_500);          // long enough for a second copy to have followed
  assert.equal(submitCount(name, text), 1, 'three presses, one message');
  assert.match(readOr(daemonLog), new RegExp(`${name}: the same message was delivered`),
    'and the daemon says it swallowed one, because a silent swallow is the other bug');
});

test('the duplicate guard holds behind ANY wait, not just a draft', async () => {
  // ⚠ THE RED-FIRST FOR (B) ON ITS OWN. The draft guard above would hide a broken
  // duplicate rule behind a shorter hold, so this one waits on a dialog instead —
  // the oldest hold in the queue and one nothing here can open. Against 3.4.1
  // `queued` reads 2 and there is no `duplicate` field at all.
  const name = await startPane('dupe-modal', { trust: true });
  const text = 'the same words, pressed twice at a dialog';

  const first = await send(name, text);
  assert.equal(first.body.blockedBy, 'modal', 'precondition: a dialog is holding the queue');
  assert.equal(first.body.queued, 1);

  const second = await send(name, text);
  assert.equal(second.body.duplicate, true, 'the second press is the same message');
  assert.equal(second.body.queued, 1, 'one copy waiting, not two');
  assert.equal((await typingOf(name)).queued, 1, 'and the poll agrees');
});

test('a DIFFERENT message is never mistaken for a second press', async () => {
  // The guard is narrow on purpose: it is about one message pressed twice, not
  // about two messages that arrive close together. Getting this wrong loses a
  // message, which is worse than the bug being fixed.
  const name = await startPane('distinct');

  const a = await send(name, 'the first thing, long enough to count');
  const b = await send(name, 'the second thing, long enough to count');
  assert.notEqual(a.body.duplicate, true);
  assert.notEqual(b.body.duplicate, true, 'different words are a different message');
  for (let i = 0; i < 80 && submitCount(name, 'the second thing, long enough to count') === 0; i++) await wait(100);
  assert.equal(submitCount(name, 'the first thing, long enough to count'), 1);
  assert.equal(submitCount(name, 'the second thing, long enough to count'), 1, 'both went');
});

test('an identical message sent again AFTER the first was delivered still goes', async () => {
  // `yes` twice in a minute is an ordinary thing to mean. The guard only ever
  // looks at a copy that has not gone yet — nothing the sender could see has
  // happened to that one — so a delivered message is never an excuse to swallow
  // the next.
  const name = await startPane('repeat');

  const first = await send(name, 'yes');
  assert.equal(first.body.delivered, true, 'an idle pane delivers synchronously, as it always did');
  await wait(300);
  const second = await send(name, 'yes');
  assert.notEqual(second.body.duplicate, true, 'the first one is gone; this is a new message');
  await wait(400);
  assert.equal(submitCount(name, 'yes'), 2, 'and it was said twice, because that is what was asked');
});

// ------------------------------------------------------- what must not regress

test('an ordinary send into an empty composer is not slowed or held', async () => {
  // The cost of the guard on the normal path is the capture `checkGates` already
  // takes. A message that waits for a box nobody is using would be a worse bug
  // than the one being fixed.
  const name = await startPane('warm');

  const t0 = Date.now();
  const { body } = await send(name, 'a perfectly ordinary message');
  const took = Date.now() - t0;
  assert.equal(body.delivered, true);
  assert.equal(body.blockedBy, null);
  assert.ok(took < 1_500, `a warm send must not wait on the guard (took ${took}ms)`);
  await wait(300);
  assert.equal(readOr(outFor(name)), 'a perfectly ordinary message\n');
});

test('a pane with no composer at all is never held by this rule', async () => {
  // A plain shell has no box to own. Holding a send to one would break every
  // non-Claude pane the app can open, which is why `draft: null` releases.
  const name = `${PFX}-shell`;
  madeSessions.add(name);
  const out = path.join(tmp, 'shell.txt');
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `sh -c 'stty -echo; cat > ${out}'`]);
  await wait(300);

  const { body } = await send(name, 'straight through');
  assert.notEqual(body.blockedBy, 'draft', 'there is no composer here for anybody to own');
  for (let i = 0; i < 60 && !fs.existsSync(out); i++) await wait(100);
  await wait(300);
  assert.equal(readOr(out), 'straight through\n');
});
