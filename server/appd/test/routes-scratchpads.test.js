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
const { spawn } = require('node:child_process');
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
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10150 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

let tmp, token, daemon;

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
  const before = (await api('/v1/watch')).body.hash;
  const pad = await mkPad('Noisy', 'first');
  await api(`/v1/scratchpads/${pad.id}`, {
    method: 'PATCH', body: JSON.stringify({ content: 'second', rev: pad.rev }),
  });
  await api(`/v1/scratchpads/${pad.id}`, { method: 'DELETE' });
  assert.equal(before, (await api('/v1/watch')).body.hash);
});
