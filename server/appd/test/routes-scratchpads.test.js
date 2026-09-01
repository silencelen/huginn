'use strict';
// Route-level tests for scratchpads: the CRUD, the revision guard, and the one
// thing the pages exist for — a page reaching a run as text the person chose to
// attach.
//
// A STUB `claude` sits at the front of the daemon's PATH and RECORDS THE PROMPT
// IT WAS GIVEN, which is how the composition is asserted at all: the frame is
// built server-side and never appears in any response, so the only honest place
// to read it is the stdin of the process that received it.
//
// SAFETY: no tmux sessions, no real claude, no live daemon. Everything lives in
// a scratch HUGINN_APPD_DATA removed in after().

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: two files
// once sat inside 9700-9949, and the suite passed four times before failing 16
// tests on an unlucky pair of pids. The width is what makes a range, not the
// base:
//
//   routes-answer        8788 + pid%900  ->  8788-9687
//   routes-lifecycle     9700 + pid%100  ->  9700-9799
//   routes-rounds        9800 + pid%60   ->  9800-9859
//   routes-devices       9870 + pid%50   ->  9870-9919
//   session-identity     9930 + pid%40   ->  9930-9969
//   breaker-fixes        9971 + pid%25   ->  9971-9995
//   routes-modelgate    10000 + pid%50   -> 10000-10049
//   routes-localmodels  10050 + pid%50   -> 10050-10099
//   routes-polish       10100 + pid%50   -> 10100-10149
//   routes-scratchpads  10150 + pid%50   -> 10150-10199   (this file)
//   routes-overview     10200 + pid%50   -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//   routes-desktop      10300 + pid%50   -> 10300-10349
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10150 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');

// One real tmux session, for the half of this feature that does NOT travel as
// text: a page attached to a live pane goes as a path, and the path is written
// per send. Killed in after().
const SESS = `padsess_${process.pid}`;
// Private tmux socket shared with the daemon under test (HUGINN_APPD_TMUX_SOCKET),
// so a session leaked by a SIGKILL never surfaces in the operator's desktop.
// `-L`, not TMUX_TMPDIR: an inherited $TMUX overrides the latter but never -L.
const TMUX_SOCK = `huginn-test-${process.pid}`;

let tmp, token, daemon;

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' }).trim();
}

/** What the pane has actually been sent, which for `-l` keys is its own text. */
function paneText() {
  return sh('tmux', ['capture-pane', '-p', '-t', `=${SESS}:`]);
}

/**
 * Answers a chat turn, and keeps the prompt it was handed.
 *
 * The recording is the point: a scratchpad reference is composed inside the
 * daemon and is not echoed anywhere, so what actually reached the run is only
 * observable here.
 */
const STUB = `#!/usr/bin/env node
// Backticks and newlines are BUILT, not escaped: this source is itself embedded
// in a template literal, and one wrong backslash produces a stub that emits the
// characters backslash-n instead of a line break.
const fs = require('node:fs');
const path = require('node:path');
const NL = String.fromCharCode(10);
let inp = '';
process.stdin.on('data', (c) => { inp += c; });
process.stdin.on('end', () => {
  fs.appendFileSync(path.join(__dirname, '..', 'prompts.log'), JSON.stringify(inp) + NL);
  const slow = inp.includes('HOLD_THE_RUN');
  const finish = () => {
    console.log(JSON.stringify({ type: 'system', subtype: 'init', session_id: 'stub-' + process.pid }));
    console.log(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'noted' }] } }));
    console.log(JSON.stringify({ type: 'result', is_error: false, duration_ms: 5, num_turns: 1 }));
  };
  if (slow) setTimeout(finish, 4000); else finish();
});
`;

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

function prompts() {
  try {
    return fs.readFileSync(path.join(tmp, 'prompts.log'), 'utf8')
      .split('\n').filter(Boolean).map((l) => JSON.parse(l));
  } catch { return []; }
}

/** The prompt matching a marker, once the run has actually been spawned. */
async function waitForPrompt(marker, timeoutMs = 15_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const hit = prompts().find((p) => p.includes(marker));
    if (hit) return hit;
    await wait(100);
  }
  throw new Error(`no run ever received a prompt containing ${marker}`);
}

/** The chat's meta as it is ON DISK — where a queued message's text really is. */
function chatMeta(id) {
  return JSON.parse(fs.readFileSync(path.join(tmp, 'data', 'chats', id, 'meta.json'), 'utf8'));
}

/**
 * Waits until no chat is running and none has anything queued.
 *
 * ⚠ THE WATCH DIGEST HASHES `running`, `pending` AND `finishedRuns` PER CHAT
 * (lib/watch.js says exactly which facts belong in it and why). Tests above this
 * one leave runs in flight — one of them deliberately holds a run open for four
 * seconds — so a "the hash did not move" assertion taken while any of them is
 * still finishing is measuring the wrong thing and fails on a busy host. Nothing
 * here starts a run on its own, so once the file is quiet it stays quiet.
 */
async function waitForQuiet(timeoutMs = 30_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const { body } = await api('/v1/chats');
    const busy = (body.chats || []).filter((c) => c.running || c.pending > 0);
    if (!busy.length) return;
    await wait(150);
  }
  throw new Error('chats never went quiet');
}

async function mkPad(name, content = '') {
  const { status, body } = await api('/v1/scratchpads', {
    method: 'POST', body: JSON.stringify({ name, content }),
  });
  assert.equal(status, 201, JSON.stringify(body));
  return body;
}

async function mainPad() {
  const { body } = await api('/v1/scratchpads');
  return body.pads.find((p) => p.main);
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-pads-'));
  const bin = path.join(tmp, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  // `cat` rather than a shell: the pane echoes exactly what is sent to it and
  // never interprets any of it.
  sh('tmux', ['new-session', '-d', '-s', SESS, '-c', tmp, '-x', '200', '-y', '50', 'cat >/dev/null']);

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) { // 30s cap: parallel gradle load on this host has pushed daemon start past 10s
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier
  // run answers /v1/ping happily — ping needs no token — and rejects ours, which
  // surfaces as a wall of 401s that reads like a code bug and is not one. Ask an
  // AUTHENTICATED question before trusting the port.
  const own = await api('/v1/scratchpads');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  try { sh('tmux', ['kill-session', '-t', `=${SESS}`]); } catch { /* gone */ }
  // Reap the private server outright (-L targets only OUR socket, never default).
  try { sh('tmux', ['kill-server']); } catch { /* no server */ }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------------- CRUD

test('the route exists, which is how a client knows the feature is here', async () => {
  // Both clients probe this once per connection and hide EVERY scratchpad
  // control on a 404, so "200 with a list" is a contract and not a detail.
  const { status, body } = await api('/v1/scratchpads');
  assert.equal(status, 200);
  assert.ok(Array.isArray(body.pads));
});

test('listing mints Main, and Main is the first row', async () => {
  const { body } = await api('/v1/scratchpads');
  const main = body.pads.find((p) => p.main);
  assert.ok(main, 'the fallback page a reference with no id resolves to');
  assert.equal('Main', main.name);
  assert.equal(body.pads[0].id, main.id, 'Main leads the list');
});

test('the list carries no content — that is what makes it pollable', async () => {
  await mkPad('Sizes', 'twelve chars');
  const { body } = await api('/v1/scratchpads');
  const row = body.pads.find((p) => p.name === 'Sizes');
  assert.equal(undefined, row.content);
  assert.equal(12, row.size, 'the one fact worth having without a fetch');
});

test('a page round-trips through create and read', async () => {
  const made = await mkPad('Deploy notes', 'ssh hermod\nsudo -i');
  assert.equal(1, made.rev, 'a new page starts at rev 1');
  assert.equal(false, made.main);
  const { status, body } = await api(`/v1/scratchpads/${made.id}`);
  assert.equal(200, status);
  assert.equal('ssh hermod\nsudo -i', body.content);
});

test('a page nobody has is a 404, not an empty one', async () => {
  const { status } = await api('/v1/scratchpads/6f1c0f5e-0000-4000-8000-0000000000ff');
  assert.equal(404, status);
});

test('the naming rules are the daemon\'s, not the client\'s', async () => {
  const blank = await api('/v1/scratchpads', { method: 'POST', body: JSON.stringify({ name: '  ' }) });
  assert.equal(400, blank.status);
  assert.match(blank.body.error, /needs a name/);

  await mkPad('Taken');
  const dupe = await api('/v1/scratchpads', { method: 'POST', body: JSON.stringify({ name: 'taken' }) });
  assert.equal(400, dupe.status, 'case-insensitive: two rows that read the same');
  assert.match(dupe.body.error, /already a page/);

  const quoted = await api('/v1/scratchpads', { method: 'POST', body: JSON.stringify({ name: 'Ideas "v2"' }) });
  assert.equal(400, quoted.status);
  assert.match(quoted.body.error, /double quote/);
});

test('content over the cap is refused at create and at save', async () => {
  const big = 'x'.repeat(100_001);
  const made = await api('/v1/scratchpads', {
    method: 'POST', body: JSON.stringify({ name: 'Too big', content: big }),
  });
  assert.equal(400, made.status);
  assert.match(made.body.error, /100,000/);

  const pad = await mkPad('Growable');
  const saved = await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: big, rev: pad.rev }),
  });
  assert.equal(400, saved.status);
});

// ⚠ THE TWO CAPS ARE IN DIFFERENT UNITS and had stopped agreeing. A page is
// capped at 100,000 CHARACTERS and the global request body at 256 KiB of BYTES,
// so for any script whose characters cost three bytes the character cap was
// unreachable: a perfectly legal page died in readBody and came back as the
// outer catch-all "request body too large", which says nothing about pages.

test('a legal page in a three-byte script is not refused for being three-byte', async () => {
  // 90,000 characters — well inside the page cap — and 270,000 bytes, well
  // outside the old body budget. This is an ordinary page of Japanese.
  const content = '例'.repeat(90_000);
  assert.ok(Buffer.byteLength(content, 'utf8') > 256 * 1024, 'the fixture is only a fixture if it is over');
  const made = await api('/v1/scratchpads', {
    method: 'POST', body: JSON.stringify({ name: 'Multibyte', content }),
  });
  assert.equal(201, made.status, JSON.stringify(made.body));
  assert.equal(90_000, made.body.content.length);

  const saved = await api(`/v1/scratchpads/${made.body.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: `${content}例`.slice(1), rev: made.body.rev }),
  });
  assert.equal(200, saved.status, JSON.stringify(saved.body).slice(0, 200));
});

test('an over-cap page is refused in the ROUTE\'s words, not the transport\'s', async () => {
  // 100,001 characters and 300,003 bytes: over the page cap, under the page
  // routes' own body budget — so the answer names the number the person is being
  // held to instead of "request body too large".
  const content = '例'.repeat(100_001);
  const made = await api('/v1/scratchpads', {
    method: 'POST', body: JSON.stringify({ name: 'Multibyte too big', content }),
  });
  assert.equal(400, made.status, JSON.stringify(made.body));
  assert.match(made.body.error, /a page holds at most 100,000 characters/);
  assert.doesNotMatch(made.body.error, /body/);
});

test('a page is deletable and then gone', async () => {
  const pad = await mkPad('Temporary');
  assert.equal(200, (await api(`/v1/scratchpads/${pad.id}`, { method: 'DELETE' })).status);
  assert.equal(404, (await api(`/v1/scratchpads/${pad.id}`)).status);
});

// ------------------------------------------------------------ Main is special

test('Main cannot be deleted, because a reference falls back to it', async () => {
  const main = await mainPad();
  const { status, body } = await api(`/v1/scratchpads/${main.id}`, { method: 'DELETE' });
  assert.equal(400, status);
  assert.match(body.error, /cannot be deleted/);
  assert.equal(200, (await api(`/v1/scratchpads/${main.id}`)).status, 'still there');
});

test('Main cannot be renamed either — every client calls the fallback by that name', async () => {
  const main = await mainPad();
  const { status, body } = await api(`/v1/scratchpads/${main.id}`, {
    method: 'PATCH', body: JSON.stringify({ name: 'Home', rev: main.rev }),
  });
  assert.equal(400, status);
  assert.match(body.error, /cannot be renamed/);
});

test('a SECOND Main cannot be created before the first list has minted one', async () => {
  // ⚠ THE ORDER OF FIRST CONTACT DECIDED THIS. Main is minted lazily by the
  // LIST route, so on an install where a client created a page before it ever
  // listed one, "Main" was not taken — the create was allowed, and the next list
  // minted a second page with the same name. Two rows reading "Main" in the
  // picker, one of them the fallback every unspecified reference resolves to,
  // and nothing on screen to tell them apart.
  //
  // Reproduced by taking Main's file off disk, which is the same state as an
  // install where nothing has listed yet.
  const main = await mainPad();
  fs.unlinkSync(path.join(tmp, 'data', 'scratchpads', `${main.id}.json`));

  const dupe = await api('/v1/scratchpads', { method: 'POST', body: JSON.stringify({ name: 'main' }) });
  assert.equal(400, dupe.status, JSON.stringify(dupe.body));
  assert.match(dupe.body.error, /already a page/);

  const { body } = await api('/v1/scratchpads');
  const mains = body.pads.filter((p2) => p2.main);
  assert.equal(1, mains.length, 'exactly one page is the fallback');
  assert.equal(1, body.pads.filter((p2) => p2.name.toLowerCase() === 'main').length,
    'and exactly one row reads Main');
});

test('Main is still WRITABLE — it is a page, not a placeholder', async () => {
  const main = await mainPad();
  const { status, body } = await api(`/v1/scratchpads/${main.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'the standing list', rev: main.rev }),
  });
  assert.equal(200, status, JSON.stringify(body));
  assert.equal('the standing list', body.content);
});

// ------------------------------------------------------------- the revision

test('a save carries the rev it was based on, and bumps it', async () => {
  const pad = await mkPad('Revved', 'one');
  const first = await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'two', rev: pad.rev }),
  });
  assert.equal(200, first.status);
  assert.equal(pad.rev + 1, first.body.rev);
});

test('a save with no rev is refused rather than guessed at', async () => {
  const pad = await mkPad('Unrevved');
  const { status, body } = await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'x' }),
  });
  assert.equal(400, status);
  assert.match(body.error, /rev/);
});

test('a stale save is a 409 carrying the CURRENT page, not a bare refusal', async () => {
  // Two clients autosaving one page is the ordinary case here, so losing has to
  // come with everything the loser needs to adopt the winner's text.
  const pad = await mkPad('Contested', 'start');
  const winner = await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'from the desktop', rev: pad.rev }),
  });
  assert.equal(200, winner.status);

  const loser = await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'from the phone', rev: pad.rev }),
  });
  assert.equal(409, loser.status);
  assert.equal('from the desktop', loser.body.content, 'the server copy travels with the refusal');
  assert.equal(winner.body.rev, loser.body.rev);

  const after = await api(`/v1/scratchpads/${pad.id}`);
  assert.equal('from the desktop', after.body.content, 'the stale text never landed');
});

// --------------------------------------------------------- the reference

test('a chat message with a page attached reaches the run inside the frame', async () => {
  const pad = await mkPad('Hostnames', 'heimdall\nskybox');
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const sent = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST',
    body: JSON.stringify({ text: 'REF_ONE which of these is the standby?', scratchpadId: pad.id }),
  });
  assert.equal(202, sent.status, JSON.stringify(sent.body));
  const prompt = await waitForPrompt('REF_ONE');
  assert.ok(
    prompt.includes('[Scratchpad "Hostnames"]\nheimdall\nskybox\n[End scratchpad]'),
    `the frame did not reach the run: ${JSON.stringify(prompt.slice(0, 200))}`,
  );
  assert.ok(
    prompt.indexOf('[Scratchpad') < prompt.indexOf('REF_ONE'),
    'the page comes first, so the question is read with it already in view',
  );
});

test('no scratchpadId means no page — Main is never attached behind your back', async () => {
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_NONE just a question' }),
  });
  const prompt = await waitForPrompt('REF_NONE');
  assert.ok(!prompt.includes('[Scratchpad'), 'a page reached a conversation nobody attached it to');
});

test('a scratchpadId that is PRESENT and blank is said out loud, not read as Main', async () => {
  // ⚠ ABSENT AND BLANK ARE DIFFERENT ANSWERS. Absent means no page — that rule
  // is what keeps Main out of conversations nobody attached it to. Present and
  // blank is a client that meant to send an id and sent nothing, and every one
  // of these used to coerce to Main: a page silently attached to a message
  // because a picker's state was empty. The only way the client that did it ever
  // finds out is being told.
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  for (const bad of ['', '   ', 0, false, {}, []]) {
    const { status, body } = await api(`/v1/chats/${chat.id}/messages`, {
      method: 'POST', body: JSON.stringify({ text: 'REF_BLANK', scratchpadId: bad }),
    });
    assert.equal(400, status, `${JSON.stringify(bad)} was accepted`);
    assert.match(body.error, /scratchpadId must be a page id or "main"/);
  }
  assert.equal(0, prompts().filter((x) => x.includes('REF_BLANK')).length, 'and nothing was sent');
});

test('a null scratchpadId is still "no page", which is the one blank that means something', async () => {
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const { status } = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_NULL just a question', scratchpadId: null }),
  });
  assert.equal(202, status);
  const prompt = await waitForPrompt('REF_NULL');
  assert.ok(!prompt.includes('[Scratchpad'), 'null is how a client says "clear the attachment"');
});

test('the literal "main" still resolves to Main, for a client with no picker', async () => {
  const main = await mainPad();
  await api(`/v1/scratchpads/${main.id}`, {
    method: 'PATCH',
    body: JSON.stringify({ content: 'MAIN_BY_NAME', rev: (await api(`/v1/scratchpads/${main.id}`)).body.rev }),
  });
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const sent = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_MAIN read it', scratchpadId: 'main' }),
  });
  assert.equal(202, sent.status, JSON.stringify(sent.body));
  const prompt = await waitForPrompt('REF_MAIN');
  assert.ok(prompt.includes('[Scratchpad "Main"]\nMAIN_BY_NAME\n[End scratchpad]'), prompt.slice(0, 200));
});

test('naming a page that is not there is a 404, not a silent send', async () => {
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const { status } = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST',
    body: JSON.stringify({ text: 'REF_GHOST', scratchpadId: '6f1c0f5e-0000-4000-8000-0000000000fe' }),
  });
  assert.equal(404, status);
});

test('a page that will not fit is refused before anything is spawned', async () => {
  const pad = await mkPad('Enormous', 'y'.repeat(99_990));
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const { status, body } = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_BIG please read', scratchpadId: pad.id }),
  });
  assert.equal(413, status);
  assert.match(body.error, /that page and this message/);
  assert.equal(0, prompts().filter((p) => p.includes('REF_BIG')).length, 'nothing was spawned');
});

test('a QUEUED message carries the page as it was when SEND was pressed', async () => {
  // ⚠ THE SNAPSHOT IS THE CONTRACT. Resolving the reference when the queue
  // drains would quote whatever the page said minutes later — after the sender
  // had gone on editing it — so the run would answer about text the person never
  // attached, and the transcript and the prompt would disagree.
  const pad = await mkPad('Moving target', 'FIRST VERSION');
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;

  const held = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'HOLD_THE_RUN please wait' }),
  });
  assert.equal(202, held.status);
  await waitForPrompt('HOLD_THE_RUN');

  const queued = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_QUEUED and now this', scratchpadId: pad.id }),
  });
  assert.equal(202, queued.status, JSON.stringify(queued.body));
  assert.equal(true, queued.body.queued);

  const pending = chatMeta(chat.id).pending;
  assert.equal(1, pending.length);
  assert.ok(pending[0].text.includes('FIRST VERSION'), 'composed at receipt, not at delivery');

  // Now edit the page out from under the queue. The message must not change.
  const fresh = (await api(`/v1/scratchpads/${pad.id}`)).body;
  await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'SECOND VERSION', rev: fresh.rev }),
  });
  const still = chatMeta(chat.id).pending;
  assert.ok(still[0].text.includes('FIRST VERSION'));
  assert.ok(!still[0].text.includes('SECOND VERSION'), 'the queue quoted a page the sender never sent');
});

// ------------------------------------------------------------ the watch digest

test('editing pages never wakes a watching phone', async () => {
  // ⚠ lib/watch.js says exactly which facts belong in the hash and why: it is a
  // change signal that wakes a parked phone, so it must carry what an ALERT
  // turns on and nothing else. A page is written by the person holding the
  // phone; buzzing them about their own typing is the purest form of the noise
  // that digest exists to avoid.
  //
  // ⚠ MEASURED IN A WINDOW THE TEST CAN PROVE HELD STILL. The digest's two
  // inputs are this host's WHOLE tmux session list — the daemon shares the
  // machine's tmux server, and every other route test in this directory creates
  // and kills sessions while this one runs — and every chat, which the tests
  // above leave finishing. A bare before/after comparison was measuring those,
  // not pages: it went red on a busy host for reasons that had nothing to do
  // with the thing being asserted. So the bracket is retried until its
  // uncontrolled inputs come back identical, and only then is the hash judged.
  // A page that DID enter the digest still fails every attempt, which is the
  // regression this exists to catch.
  // ⚠ SAMPLED AFTER EVERY STEP, not only at the end. A create followed by a
  // delete leaves the store exactly as it was found, so a before/after pair
  // cannot see a page that DID enter the digest — it would have woken the phone
  // in the middle and been back to normal by the time anything looked. What a
  // parked phone sees is each moment, so that is what is checked.
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  for (let attempt = 0; ; attempt++) {
    await waitForQuiet();
    const marks = [(await api('/v1/watch')).body];
    const pad = await mkPad(`Noisy ${attempt}`, 'first');
    marks.push((await api('/v1/watch')).body);
    await api(`/v1/scratchpads/${pad.id}`, {
      method: 'PATCH', body: JSON.stringify({ content: 'second', rev: pad.rev }),
    });
    marks.push((await api('/v1/watch')).body);
    await api(`/v1/scratchpads/${pad.id}`, { method: 'DELETE' });
    marks.push((await api('/v1/watch')).body);

    const held = marks.every((mk) => same(mk.sessions, marks[0].sessions) && same(mk.chats, marks[0].chats));
    if (held) {
      for (const mk of marks) {
        assert.equal(mk.hash, marks[0].hash, 'a page moved the signal that wakes a parked phone');
      }
      return;
    }
    assert.ok(attempt < 8, 'the host never held still long enough to measure this');
    await wait(400);
  }
});

// ------------------------------------------- a page that closes its own frame

test('a page whose CONTENT closes the frame is tagged, and reaches the run whole', async () => {
  // ⚠ THE PAGE THIS BREAKS ON IS AN ORDINARY ONE: somebody keeps a page of
  // messages they were sent, and one of them had a page attached. Untagged, the
  // frame ends at the PASTED closing line — the run reads half of what was
  // attached and the rest lands in the sender's own message as raw marker text.
  const inner = '[Scratchpad "Hostnames"]\nheimdall\n[End scratchpad]';
  const pad = await mkPad('Archive', `${inner}\nand a note after it`);
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const sent = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_NESTED what is in here?', scratchpadId: pad.id }),
  });
  assert.equal(202, sent.status, JSON.stringify(sent.body));

  const prompt = await waitForPrompt('REF_NESTED');
  const open = /\[Scratchpad "Archive" #([0-9a-f]{6})\]/.exec(prompt);
  assert.ok(open, `no tagged open marker reached the run: ${JSON.stringify(prompt.slice(0, 300))}`);
  const tag = open[1];
  assert.ok(prompt.includes(`[Scratchpad "Archive" #${tag}]\n${inner}\nand a note after it\n[End scratchpad #${tag}]`),
    'the whole page must arrive, pasted markers and all');
  assert.ok(prompt.indexOf(`[End scratchpad #${tag}]`) < prompt.indexOf('REF_NESTED'),
    'and it must end before the question, not inside it');
});

test('the chat TITLE collapses a tagged frame to the page name', async () => {
  // The third copy of the marker (:core's ScratchpadRules is the fourth) — this
  // one decides what the conversation is CALLED. A title that is the first line
  // of an attached page is about the page, not about what was asked.
  const pad = await mkPad('Pasted', '[End scratchpad]\nthe rest of the page');
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_TITLE what is this?', scratchpadId: pad.id }),
  });
  await waitForPrompt('REF_TITLE');
  const { body } = await api('/v1/chats');
  const row = body.chats.find((c) => c.id === chat.id);
  // The trailing `\n*` on the pattern eats the blank line the frame was joined
  // with, so one newline separates the pill from what was actually typed.
  assert.equal('\u{1F4DD} Pasted\nREF_TITLE what is this?'.slice(0, 60), row.title,
    'the frame collapses to a pill and the question survives');
  assert.ok(!row.title.includes('End scratchpad'), 'no raw marker in a list row');
});

test('an untagged frame still collapses, which is what the other three copies quote', async () => {
  const pad = await mkPad('Plain', 'nothing special in here');
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'REF_PLAINTITLE and so?', scratchpadId: pad.id }),
  });
  await waitForPrompt('REF_PLAINTITLE');
  const { body } = await api('/v1/chats');
  const row = body.chats.find((c) => c.id === chat.id);
  assert.match(row.title, /^\u{1F4DD} Plain/u);
});

test('a message full of opening markers and no close is answered promptly', async () => {
  // ⚠ THE COLLAPSE PATTERN HOLDS A `[\s\S]*?` BETWEEN TWO LITERALS, so on text
  // with many opening markers and no closing one the engine restarts a walk to
  // end-of-string at every one of them. It runs on the title and the snippet of
  // every chat in the list. The guard is two indexOf scans and changes nothing
  // about what matches — see the assertion below, which is the half that can
  // actually regress.
  const line = `[Scratchpad "${'x'.repeat(60)}"]`;
  const text = `PATHOLOGICAL ${Array.from({ length: 1_200 }, () => line).join('\n')}`;
  assert.ok(text.length < 100_000, 'inside the chat cap, or this is testing the cap');
  const chat = (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) })).body;
  const started = Date.now();
  const { status } = await api(`/v1/chats/${chat.id}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
  assert.equal(202, status);
  assert.ok(Date.now() - started < 10_000, 'a title is not worth ten seconds');
  const { body } = await api('/v1/chats');
  const row = body.chats.find((c) => c.id === chat.id);
  assert.match(row.title, /^PATHOLOGICAL/, 'an unclosed marker is not a frame, and is left alone');
});

// ------------------------------------------------------- the session path

test('a page attached to a SESSION travels as a path, and the file is what was sent', async () => {
  const pad = await mkPad('Runbook', 'step one\nstep two');
  const sent = await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'follow this', scratchpadId: pad.id }),
  });
  assert.equal(200, sent.status, JSON.stringify(sent.body));
  const pane = paneText();
  const at = /\[Scratchpad "Runbook" at (\S+\.md) /.exec(pane);
  assert.ok(at, `no reference reached the pane: ${JSON.stringify(pane.slice(0, 300))}`);
  assert.equal('step one\nstep two', fs.readFileSync(at[1], 'utf8'));
  assert.ok(pane.includes('follow this'), 'and the message went with it');
});

test('each send gets its OWN file, so an older message never resolves to newer text', async () => {
  // ⚠ THE COMMENT USED TO CLAIM SNAPSHOT SEMANTICS THAT A SHARED FILENAME
  // CANNOT PROVIDE. render/<padId>.md was rewritten on every attach and shared
  // across every session, so a message still sitting in a pane's scrollback
  // named a path that now held the page's CURRENT text — and the run would
  // follow it and answer confidently about words the sender never attached.
  const pad = await mkPad('Moving page', 'VERSION ONE');
  await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'FIRST_SEND', scratchpadId: pad.id }),
  });
  const first = /\[Scratchpad "Moving page" at (\S+\.md) /.exec(paneText());
  assert.ok(first, 'the first send named a file');

  const fresh = (await api(`/v1/scratchpads/${pad.id}`)).body;
  await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'VERSION TWO', rev: fresh.rev }),
  });
  await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'SECOND_SEND', scratchpadId: pad.id }),
  });
  const paths = [...paneText().matchAll(/\[Scratchpad "Moving page" at (\S+\.md) /g)].map((x) => x[1]);
  assert.equal(2, paths.length, 'two sends, two markers');
  assert.notEqual(paths[0], paths[1], 'and two files — a shared name is the whole bug');
  assert.equal('VERSION ONE', fs.readFileSync(paths[0], 'utf8'), 'the older path still reads as it was sent');
  assert.equal('VERSION TWO', fs.readFileSync(paths[1], 'utf8'));
});

test('deleting a page takes every rendered copy with it', async () => {
  const pad = await mkPad('Ephemeral', 'secret-ish');
  await api(`/v1/sessions/${SESS}/keys`, { method: 'POST', body: JSON.stringify({ text: 'a', scratchpadId: pad.id }) });
  await api(`/v1/sessions/${SESS}/keys`, { method: 'POST', body: JSON.stringify({ text: 'b', scratchpadId: pad.id }) });
  const dir = path.join(tmp, 'data', 'scratchpads', 'render');
  assert.equal(2, fs.readdirSync(dir).filter((f) => f.startsWith(`${pad.id}-`)).length);

  assert.equal(200, (await api(`/v1/scratchpads/${pad.id}`, { method: 'DELETE' })).status);
  assert.equal(0, fs.readdirSync(dir).filter((f) => f.startsWith(`${pad.id}-`)).length,
    'a readable path to a page the owner just deleted is the one thing that must not survive');
});

test('render files older than the keep window are pruned on the next write', async () => {
  const pad = await mkPad('Prunable', 'x');
  const dir = path.join(tmp, 'data', 'scratchpads', 'render');
  const ancient = path.join(dir, `${pad.id}-1000000000000.md`);   // 2001
  fs.writeFileSync(ancient, 'from another era');
  assert.ok(fs.existsSync(ancient));
  await api(`/v1/sessions/${SESS}/keys`, { method: 'POST', body: JSON.stringify({ text: 'c', scratchpadId: pad.id }) });
  assert.equal(false, fs.existsSync(ancient), 'the directory does not grow for the life of the daemon');
});

test('a page with NO message sends the reference alone — attaching IS the message', async () => {
  // ⚠ THE REFERENCE USED TO BE DROPPED HERE while the route answered ok. Being
  // told the page was attached, with nothing arriving in the pane, is worse than
  // either sending it or refusing.
  const pad = await mkPad('Alone', 'read me');
  const sent = await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ scratchpadId: pad.id }),
  });
  assert.equal(200, sent.status, JSON.stringify(sent.body));
  const at = /\[Scratchpad "Alone" at (\S+\.md) — read it before acting on this message\.\]/.exec(paneText());
  assert.ok(at, `the frame did not travel on its own: ${JSON.stringify(paneText().slice(-300))}`);
  assert.equal('read me', fs.readFileSync(at[1], 'utf8'));
});

test('the session refusal blames the message, because the page is not in it', async () => {
  // The chat wording ("that page and this message … shorten one of them") points
  // at a fix that cannot work on this path: only the one-line reference travels,
  // so shortening a 90,000-character page changes the composed length by nothing.
  const pad = await mkPad('Pointed at', 'y'.repeat(50_000));
  const { status, body } = await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'z'.repeat(8_000), scratchpadId: pad.id }),
  });
  assert.equal(413, status, JSON.stringify(body));
  assert.doesNotMatch(body.error, /that page and this message/);
  assert.match(body.error, /one-line reference to that page takes \d+ of the 8,000 characters/);
  assert.match(body.error, /this message is 8,000/);
});

test('a blank scratchpadId is refused on the session path too', async () => {
  const { status, body } = await api(`/v1/sessions/${SESS}/keys`, {
    method: 'POST', body: JSON.stringify({ text: 'hello', scratchpadId: '   ' }),
  });
  assert.equal(400, status);
  assert.match(body.error, /scratchpadId must be a page id or "main"/);
});

// ----------------------------------------------------------- the list order

test('the list is Main, then name order — it does not reshuffle while you type', async () => {
  // ⚠ A PICKER IS A PLACE. Ordering by most-recently-edited moves the row you
  // are typing in to the top, so the next click opens a different page; a tester
  // typed into the wrong pad twice in one sitting. Recency is already on the row
  // as `updatedAt` and does not also get to be the order.
  for (const n of ['zeta pad', 'Alpha pad', 'MIDDLE pad']) await mkPad(n);
  const names = () => api('/v1/scratchpads').then((r) => r.body.pads
    .map((p2) => p2.name).filter((n) => n === 'Main' || n.endsWith(' pad')));

  const before = await names();
  assert.deepEqual(before, ['Main', 'Alpha pad', 'MIDDLE pad', 'zeta pad']);

  // Edit the LAST one. Nothing may move.
  const zeta = (await api('/v1/scratchpads')).body.pads.find((p2) => p2.name === 'zeta pad');
  const full = (await api(`/v1/scratchpads/${zeta.id}`)).body;
  await api(`/v1/scratchpads/${zeta.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'just typed something', rev: full.rev }),
  });
  assert.deepEqual(await names(), before, 'the row a person is editing stays where it is');
});
