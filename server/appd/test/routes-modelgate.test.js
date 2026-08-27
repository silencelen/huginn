'use strict';
// Route-level tests for the model/effort gate: an unknown NON-EMPTY model or
// effort id is a loud 400 at every route that accepts one, while absent, null
// and empty keep meaning "the host default".
//
// Until appd 2.73.0 the validators returned null for unknown ids, and null means
// "host default" — so a typo'd or foreign id silently changed which model
// answered, billing the subscription with no symptom but the bill. These tests
// pin the loud half AND the unchanged half of that matrix, plus the one
// almost-bypass: a device posting events cannot write a chat's model.
//
// SAFETY: no chat here ever receives a message on a local host, so no `claude`
// is spawned. The one device chat drives the queue with the test as the device.

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
//   routes-modelgate   10000 + pid%50    -> 10000-10049   (this file)
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//   routes-scratchpads 10150 + pid%50    -> 10150-10199
//   routes-overview    10200 + pid%50    -> 10200-10249
//   push-retire         10250 + pid%50   -> 10250-10299
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10000 + (process.pid % 50);
const BASE = `http://127.0.0.1:${PORT}`;

let tmp, token, daemon;

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

const SUNDAY_7PM = { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' };

async function mkChat(over = {}) {
  return api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask', ...over }) });
}
async function mkRound(over = {}) {
  return api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({ title: 'gate check', prompt: 'p', schedule: SUNDAY_7PM, ...over }),
  });
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-modelgate-'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data'));
  fs.mkdirSync(path.join(tmp, 'state'));

  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
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
  // run answers /v1/ping (no token) and rejects ours, which reads as twelve code
  // bugs. Ask an authenticated question before trusting the port.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably leaked by an earlier `
      + `test run. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

test('an unknown model id on chat creation is a 400 naming the id', async () => {
  const { status, body } = await mkChat({ model: 'qwen3-8b' });
  assert.equal(status, 400, JSON.stringify(body));
  assert.match(body.error, /qwen3-8b/, 'the refusal names the id');
  assert.match(body.error, /\/v1\/models/, 'and points at the catalog');
});

test('absent, null and empty model still mean the host default', async () => {
  for (const over of [{}, { model: null }, { model: '' }]) {
    const { status, body } = await mkChat(over);
    assert.equal(status, 201, JSON.stringify(body));
    assert.equal(body.model ?? null, null, `model is the default for ${JSON.stringify(over)}`);
  }
});

test('a family alias and a versioned id both pass and are stored', async () => {
  const a = await mkChat({ model: 'opus' });
  assert.equal(a.status, 201, JSON.stringify(a.body));
  assert.equal(a.body.model, 'opus');
  const b = await mkChat({ model: 'claude-opus-4-8' });
  assert.equal(b.status, 201, JSON.stringify(b.body));
  assert.equal(b.body.model, 'claude-opus-4-8');
});

test('a garbage-shaped model id is refused without echoing raw bytes forever', async () => {
  const { status, body } = await mkChat({ model: 'x'.repeat(200) });
  assert.equal(status, 400);
  assert.ok(body.error.length < 200, 'the refusal truncates what it echoes');
  const nonString = await mkChat({ model: 123 });
  assert.equal(nonString.status, 400, 'a non-string model is refused, not coerced');
});

test('a 400 PATCH does not half-apply the rest of the body', async () => {
  const { body: chat } = await mkChat({});
  const patch = await api(`/v1/chats/${chat.id}`, {
    method: 'PATCH', body: JSON.stringify({ model: 'nope-123', title: 'sneaky rename' }),
  });
  assert.equal(patch.status, 400, JSON.stringify(patch.body));
  const { body: again } = await api(`/v1/chats/${chat.id}`);
  assert.notEqual(again.title, 'sneaky rename', 'the title change did not ride the refused model');
});

test('PATCH model empty clears to the default; an absent key leaves it alone', async () => {
  const { body: chat } = await mkChat({ model: 'opus' });
  const untouched = await api(`/v1/chats/${chat.id}`, {
    method: 'PATCH', body: JSON.stringify({ title: 'renamed' }),
  });
  assert.equal(untouched.status, 200);
  assert.equal(untouched.body.model, 'opus', 'no model key, no model change');
  const cleared = await api(`/v1/chats/${chat.id}`, {
    method: 'PATCH', body: JSON.stringify({ model: '' }),
  });
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.model ?? null, null, 'empty string clears to the default');
});

test('rounds refuse an unknown model at create and at patch, untouched on refusal', async () => {
  const bad = await mkRound({ model: 'qwen3-8b' });
  assert.equal(bad.status, 400, JSON.stringify(bad.body));

  const good = await mkRound({ model: 'opus' });
  assert.equal(good.status, 201, JSON.stringify(good.body));
  const patch = await api(`/v1/rounds/${good.body.id}`, {
    method: 'PATCH', body: JSON.stringify({ model: 'nope-123' }),
  });
  assert.equal(patch.status, 400, JSON.stringify(patch.body));
  const { body: again } = await api(`/v1/rounds/${good.body.id}`);
  assert.equal(again.model, 'opus', 'the round keeps its model after a refused patch');
});

test('effort gets the same matrix: unknown refused, empty clears, known passes', async () => {
  const bad = await mkChat({ effort: 'turbo' });
  assert.equal(bad.status, 400, JSON.stringify(bad.body));
  assert.match(bad.body.error, /low, medium, high/);

  const ok = await mkChat({ effort: 'xhigh' });
  assert.equal(ok.status, 201);
  assert.equal(ok.body.effort, 'xhigh');

  const cleared = await api(`/v1/chats/${ok.body.id}`, {
    method: 'PATCH', body: JSON.stringify({ effort: '' }),
  });
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.effort ?? null, null);

  const badRound = await mkRound({ effort: 'ludicrous' });
  assert.equal(badRound.status, 400, JSON.stringify(badRound.body));
});

test('a device posting events cannot write a chat model', async () => {
  const reg = await api('/v1/devices', {
    method: 'POST',
    body: JSON.stringify({ name: 'GATECHECK', platform: 'linux', scope: 'work' }),
  });
  assert.equal(reg.status, 201, JSON.stringify(reg.body));
  const dev = reg.body;

  const { status, body: chat } = await mkChat({ mode: 'act', host: dev.id });
  assert.equal(status, 201, JSON.stringify(chat));
  const sent = await api(`/v1/chats/${chat.id}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'do the thing' }),
  });
  assert.ok(sent.status < 300, JSON.stringify(sent.body));

  const work = await api(`/v1/devices/${dev.id}/work?wait=2`);
  assert.ok(work.body && work.body.work, 'the device was handed the job');
  const init = { type: 'system', subtype: 'init', session_id: crypto.randomUUID(), model: 'evil-1' };
  const posted = await api(`/v1/devices/${dev.id}/work/${work.body.work.id}/events`, {
    method: 'POST', body: JSON.stringify({ lines: [JSON.stringify(init)] }),
  });
  assert.ok(posted.status < 300, JSON.stringify(posted.body));

  const { body: again } = await api(`/v1/chats/${chat.id}`);
  assert.notEqual(again.model, 'evil-1', 'a device event never sets a model');
  assert.equal(again.model ?? null, null, 'the chat keeps its default');
});
