'use strict';
// Route-level tests for POST /v1/rounds/polish — the AI draft of a Round field.
//
// A STUB `claude` sits at the front of the daemon's PATH and answers to order, so
// the whole path — validation, the caged argv, the parse, the cache — is exercised
// without invoking the real CLI or spending tokens. The stub also RECORDS the argv
// it was given, which is how the cage itself is asserted: `--tools ""` and
// `--setting-sources ""` are the difference between a text rewrite and a headless
// claude with huginn's persona and a shell, and nothing else in the suite would
// notice them being dropped.
//
// SAFETY: no chats, no real claude, no live daemon. Everything lives in a scratch
// HUGINN_APPD_DATA removed in after().

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
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149   (this file)
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10100 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

let tmp, token, daemon;

/**
 * Answers to order, and keeps the receipts.
 *
 * The order is read out of the PROMPT — which quotes the draft back — so a test
 * asks for a shape of answer by putting a marker in the draft it posts.
 */
const STUB = `#!/usr/bin/env node
// Backticks are BUILT, not escaped: this source is itself embedded in a template
// literal, and one stray backslash produces a stub that emits the characters
// backslash-n instead of a line break, which then fails as a parse bug upstream.
const fs = require('node:fs');
const path = require('node:path');
const F = String.fromCharCode(96, 96, 96);
const NL = String.fromCharCode(10);
const argv = process.argv.slice(2);
const prompt = argv[argv.length - 1] || '';

// Not every claude the daemon runs is a polish: it also asks for 'auth status'
// and '--version' on its own schedule. Only ours is recorded, or the call count
// this file asserts on would be counting the daemon's housekeeping.
if (!prompt.includes('improving one field of a scheduled')) {
  console.log('{}');
  process.exit(0);
}
fs.appendFileSync(path.join(__dirname, '..', 'calls.log'), JSON.stringify(argv) + NL);

if (prompt.includes('EMIT_CRASH')) {
  process.stderr.write('stub refusing on purpose' + NL);
  process.exit(3);
}
if (prompt.includes('EMIT_SLOW')) {
  // Long enough that a second request lands while this one is still running.
  const until = Date.now() + 600;
  while (Date.now() < until) { /* spin: the point is a call that is still open */ }
}
if (prompt.includes('EMIT_CONTRACT')) {
  process.stdout.write('Read the alerts.' + NL + NL + F + 'huginn-report abc' + NL +
    '{"status":"ok"}' + NL + F + NL);
  process.exit(0);
}
if (prompt.includes('EMIT_FENCED')) {
  process.stdout.write(F + 'text' + NL + 'Polished prompt: Read the alerts and say what changed.' + NL + F + NL);
  process.exit(0);
}
if (prompt.includes('EMIT_LONG')) {
  process.stdout.write('y'.repeat(900) + NL);
  process.exit(0);
}
if (prompt.includes('EMIT_EMPTY')) {
  process.stdout.write(NL + NL);
  process.exit(0);
}
// The default: a plausible rewrite that names which field was asked for, so a
// test can tell a prompt polish from a goal polish by the answer alone.
const field = prompt.includes('REWRITE THE "how will you know it finished" FIELD') ? 'goal' : 'prompt';
process.stdout.write('rewritten ' + field + NL);
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

function polish(body) {
  return api('/v1/rounds/polish', { method: 'POST', body: JSON.stringify(body) });
}

/** Every polish invocation the stub has seen, newest last, as argv arrays. */
function calls() {
  try {
    return fs.readFileSync(path.join(tmp, 'calls.log'), 'utf8')
      .split('\n').filter(Boolean).map((l) => JSON.parse(l));
  } catch { return []; }
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-polish-'));
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
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? A daemon leaked by an earlier run
  // answers /v1/ping (no token) and rejects ours, which reads as a dozen code bugs
  // and is not one. Ask an authenticated question before trusting the port.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------- validation

test('the field must be one the editor actually has', async () => {
  for (const field of [undefined, '', 'title', 'schedule', 42]) {
    const { status, body } = await polish({ field, prompt: 'something' });
    assert.equal(status, 400, JSON.stringify({ field, body }));
    assert.match(body.error, /field must be one of prompt, goal/);
  }
});

test('an empty draft is refused: polish improves, it does not invent', async () => {
  const { status, body } = await polish({ field: 'prompt', title: 'named but empty', prompt: '   ', goal: '' });
  assert.equal(status, 400, JSON.stringify(body));
  assert.match(body.error, /write something first/);
});

test('a goal alone is enough to polish the prompt from', async () => {
  const { status, body } = await polish({ field: 'prompt', goal: 'the disk was checked' });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.polished, 'rewritten prompt');
});

// -------------------------------------------------------------- the cage

test('the polish call is caged, on sonnet, one turn, no tools', async () => {
  await polish({ field: 'prompt', prompt: 'CAGE_CHECK look at the alerts' });
  const argv = calls().find((a) => a.join(' ').includes('CAGE_CHECK'));
  assert.ok(argv, 'the stub recorded no call for this draft');
  assert.equal(argv[0], '-p');
  // ⚠ THE FOUR THAT MATTER. Empty --setting-sources keeps the global CLAUDE.md and
  // huginn's persona out (the hermod lesson); empty --tools means this cannot act;
  // one turn means it cannot wander. A regression here is invisible in the answer.
  assert.equal(argv[argv.indexOf('--setting-sources') + 1], '', 'no CLAUDE.md, global or project');
  assert.equal(argv[argv.indexOf('--tools') + 1], '', 'a rewrite must not be able to DO anything');
  assert.equal(argv[argv.indexOf('--max-turns') + 1], '1');
  assert.equal(argv[argv.indexOf('--model') + 1], 'sonnet', 'not suggest\'s haiku: this text runs for months');
  assert.equal(argv[argv.length - 2], '--', 'the draft is an operand, never parsed as flags');
});

test('the draft reaches the model with its mode and its title', async () => {
  await polish({ field: 'goal', title: 'MODE_CHECK', prompt: 'p', goal: 'g', mode: 'act' });
  const sent = calls().map((a) => a[a.length - 1]).find((s) => s.includes('MODE_CHECK'));
  assert.match(sent, /MAY change things/, 'an act Round is described as one');
  assert.match(sent, /REWRITE THE "how will you know it finished" FIELD/);
});

// ------------------------------------------------------------- answering

test('a polished prompt comes back as a proposal, and nothing is stored', async () => {
  const { status, body } = await polish({ field: 'prompt', prompt: 'PROPOSAL look at the alerts' });
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.polished, 'rewritten prompt');
  assert.equal(body.error, undefined);
  // The endpoint takes no id and writes no file — a polish is editor state, and a
  // Round that changed because somebody pressed Polish would be a Round that
  // rewrote itself.
  const { body: list } = await api('/v1/rounds');
  assert.deepEqual(list.rounds, [], 'polishing must not create a Round');
});

test('decoration comes off the answer before it is offered', async () => {
  const { body } = await polish({ field: 'prompt', prompt: 'EMIT_FENCED' });
  assert.equal(body.polished, 'Read the alerts and say what changed.',
    'the fence and the label the model was told not to write');
});

test('an answer that echoed the report contract is refused, quietly', async () => {
  const { status, body } = await polish({ field: 'prompt', prompt: 'EMIT_CONTRACT' });
  assert.equal(status, 200, 'a model failure is not a server failure');
  assert.match(body.error, /report contract/);
  assert.equal(body.polished, undefined, 'nothing to accept');
});

test('a model that failed degrades to {error}, never a 5xx', async () => {
  const { status, body } = await polish({ field: 'prompt', prompt: 'EMIT_CRASH' });
  assert.equal(status, 200, 'the person is mid-sentence in a text field');
  assert.match(body.error, /unavailable right now/);
});

test('an empty answer is an error rather than an empty field', async () => {
  const { body } = await polish({ field: 'goal', goal: 'EMIT_EMPTY' });
  assert.match(body.error, /said nothing/);
});

test('an over-long goal is clamped to the stored cap and says so', async () => {
  const { body } = await polish({ field: 'goal', goal: 'EMIT_LONG' });
  assert.equal(body.polished.length, 500, 'MAX_ROUND_GOAL, the cap a save would enforce anyway');
  assert.match(body.note, /Trimmed to 500 characters/);
});

// ----------------------------------------------------------- one spend

test('the same draft polished twice costs one call', async () => {
  const draft = { field: 'prompt', title: 'cache', prompt: 'CACHE_ONE alerts', goal: 'g', mode: 'ask' };
  const before = calls().length;
  const a = await polish(draft);
  const b = await polish(draft);
  assert.equal(a.body.polished, b.body.polished);
  assert.equal(calls().length, before + 1, 'a second identical Polish must not buy a second answer');
});

test('one changed character is a different draft and a real call', async () => {
  const before = calls().length;
  await polish({ field: 'prompt', prompt: 'CACHE_TWO alerts' });
  await polish({ field: 'prompt', prompt: 'CACHE_TWO alerts.' });
  assert.equal(calls().length, before + 2);
  // And the same text under the OTHER field is a different question entirely.
  const beforeField = calls().length;
  await polish({ field: 'goal', prompt: 'CACHE_TWO alerts' });
  assert.equal(calls().length, beforeField + 1, 'the field is part of the key');
});

test('two taps during one slow call wait on it instead of doubling it', async () => {
  const draft = { field: 'prompt', prompt: 'EMIT_SLOW single flight' };
  const before = calls().length;
  const [a, b] = await Promise.all([polish(draft), polish(draft)]);
  assert.equal(a.body.polished, b.body.polished);
  assert.equal(calls().length, before + 1, 'a double tap is one spend, not two');
});

test('a failure is NOT cached: pressing Polish again really tries again', async () => {
  // The alternative is a draft that can never be polished until somebody types a
  // character and deletes it, for a failure that is nearly always a timeout.
  const draft = { field: 'prompt', prompt: 'EMIT_CRASH retryable' };
  const before = calls().length;
  await polish(draft);
  await polish(draft);
  assert.equal(calls().length, before + 2);
});
