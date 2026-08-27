'use strict';
// Route-level tests for the LOCAL MODEL family: generate-scope registration,
// the llmSlug handshake, the ?local=1 catalog union, the creation/patch gates
// that pin a local chat to its machine, the Rounds refusal, and the cost gate.
//
// THE TEST IS THE DEVICE, as in routes-devices: a serving machine is a sequence
// of HTTP calls, so the whole path — enrol with a catalog, get picked in the
// catalog, receive generate work — runs with no runner, no shim and no model.
//
// SAFETY: no chat here ever runs on host 'local', so no `claude` is spawned.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. The width is what
// makes a range, not the base:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099   (this file)
//   routes-polish      10100 + pid%50    -> 10100-10149
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 10050 + (process.pid % 50);
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

/** A serving machine, as its manager would enrol it. */
async function enrolLlm(over = {}) {
  const { status, body } = await api('/v1/devices', {
    method: 'POST',
    body: JSON.stringify({
      name: 'gpubox-llm', platform: 'windows', scope: 'generate',
      models: [{ slug: 'qwen3-8b', display: 'Qwen3 8B' }, { slug: 'nomic-embed', display: 'Nomic Embed' }],
      ...over,
    }),
  });
  assert.equal(status, 201, JSON.stringify(body));
  return body;
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-localm-'));
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
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? Ask an authenticated question
  // before trusting the port — a leaked daemon answers ping without a token.
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

test('a generate enrolment gets a minted llmSlug, echoed and stable across rename', async () => {
  const first = await enrolLlm();
  assert.equal(first.scope, 'generate', 'the echo the runner aborts without');
  assert.equal(first.llmSlug, 'gpubox-llm', 'minted from the name');
  assert.deepEqual(first.models.map((m) => m.slug), ['qwen3-8b', 'nomic-embed']);

  const renamed = await enrolLlm({ id: first.id, name: 'shinybox' });
  assert.equal(renamed.id, first.id);
  assert.equal(renamed.llmSlug, 'gpubox-llm', 'id stability beats name freshness — every row id embeds it');
});

test('two same-named serving machines get distinct, prefix-unambiguous slugs', async () => {
  const a = await enrolLlm({ name: 'twin' });
  const b = await enrolLlm({ name: 'twin' });
  assert.notEqual(a.llmSlug, b.llmSlug);
  assert.ok(!b.llmSlug.startsWith(`${a.llmSlug}-`) || a.llmSlug.length < b.llmSlug.length,
    'prefix-with-dash ambiguity is avoided, so composite ids always parse');
});

test('registration hygiene: caps, bad slugs dropped, catalogs only at generate', async () => {
  const many = Array.from({ length: 17 }, (_, i) => ({ slug: `m-${i}`, display: `M ${i}` }));
  const capped = await enrolLlm({ name: 'capbox', models: [...many, { slug: 'BAD SLUG' }] });
  assert.equal(capped.models.length, 16, 'extras dropped, not fatal');

  const claude = await api('/v1/devices', {
    method: 'POST',
    body: JSON.stringify({ name: 'plainbox', platform: 'linux', scope: 'work', models: [{ slug: 'sneaky', display: 'x' }] }),
  });
  assert.equal(claude.status, 201);
  assert.equal(claude.body.models, undefined, 'a claude enrolment carries no catalog');
  assert.equal(claude.body.llmSlug, undefined, 'and no slug');
});

test('the catalog union is opt-in and computed from the registry', async () => {
  const dev = await enrolLlm({ name: 'rowbox' });
  const plain = await api('/v1/models');
  assert.ok(!(plain.body.models || []).some((m) => m.family === 'local'),
    'no local rows without ?local=1 — an old client must never see rows it would 400 on');
  const withLocal = await api('/v1/models?local=1');
  const row = (withLocal.body.models || []).find((m) => m.id === `local-${dev.llmSlug}-qwen3-8b`);
  assert.ok(row, JSON.stringify(withLocal.body.models));
  assert.equal(row.family, 'local');
  assert.equal(row.display, 'Qwen3 8B - rowbox');
  assert.equal(row.available, true);
  assert.equal(row.host, dev.id);
});

test('picking a local row is the host choice: forced ask, forced host, loud refusals', async () => {
  const dev = await enrolLlm({ name: 'chatbox' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;

  const act = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id, mode: 'act' }) });
  assert.equal(act.status, 400, JSON.stringify(act.body));
  assert.match(act.body.error, /ask-only/i);

  const wrongHost = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id, host: 'not-this-machine' }) });
  assert.equal(wrongHost.status, 400);
  assert.match(wrongHost.body.error, /already chooses the machine/);

  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.equal(made.body.mode, 'ask');
  assert.equal(made.body.host, dev.id, 'the row chose the machine');
  assert.equal(made.body.model, id);

  const unserved = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: `local-${dev.llmSlug}-nope` }) });
  assert.equal(unserved.status, 400);
  assert.match(unserved.body.error, /does not serve/);

  const nobody = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: 'local-ghost-model' }) });
  assert.equal(nobody.status, 400);
  assert.match(nobody.body.error, /no enrolled machine/);
});

test('an offline serving machine refuses at the button, with the machine named', async () => {
  const dev = await enrolLlm({ name: 'sleepy' });
  // Nothing has beaten for it in FRESH_MS only if we wait — instead register a
  // second daemon-known device and age it by not beating is slow; the honest
  // path: a machine that never enrolled cannot be resolved, and offline-ness is
  // asserted through canServe by aging lastSeen via the beat route being absent
  // for FRESH_MS. That is 3 minutes of wall clock, so this test instead pins the
  // reason string through the resolver on a FORGOTTEN device: delete it.
  const del = await api(`/v1/devices/${dev.id}`, { method: 'DELETE' });
  assert.ok(del.status < 300, JSON.stringify(del.body));
  const gone = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: `local-${dev.llmSlug}-qwen3-8b` }) });
  assert.equal(gone.status, 400);
  assert.match(gone.body.error, /unenrolled|no enrolled machine/);
});

/** Drive one real turn through the work lane, so the chat HAS history. */
async function makeStarted(dev, chatId, text = 'hello') {
  const sent = await api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
  assert.ok(sent.status < 300, JSON.stringify(sent.body));
  const work = await api(`/v1/devices/${dev.id}/work?wait=2`);
  assert.ok(work.body && work.body.work, JSON.stringify(work.body));
  const init = { type: 'system', subtype: 'init', session_id: crypto.randomUUID() };
  const result = { type: 'result', is_error: false, result: 'hi', duration_ms: 5, num_turns: 1 };
  const posted = await api(`/v1/devices/${dev.id}/work/${work.body.work.id}/events`, {
    method: 'POST', body: JSON.stringify({ lines: [JSON.stringify(init), JSON.stringify(result)], done: true, exitCode: 0 }),
  });
  assert.ok(posted.status < 300, JSON.stringify(posted.body));
  await wait(300);
}

test('a STARTED local chat is pinned to its machine — the pin protects history', async () => {
  const dev = await enrolLlm({ name: 'pinbox' });
  const other = await enrolLlm({ name: 'otherbox' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;
  const { body: chat } = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id }) });
  await makeStarted(dev, chat.id);

  const toAct = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ mode: 'act' }) });
  assert.equal(toAct.status, 409, JSON.stringify(toAct.body));

  const toClaude = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: 'opus' }) });
  assert.equal(toClaude.status, 409, 'history lives on the machine; the chat may not leave it');

  const toOther = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: `local-${other.llmSlug}-qwen3-8b` }) });
  assert.equal(toOther.status, 409);
  assert.match(toOther.body.error, /different machine/);

  const sameHost = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: `local-${dev.llmSlug}-nomic-embed` }) });
  assert.equal(sameHost.status, 200, JSON.stringify(sameHost.body));
  assert.equal(sameHost.body.model, `local-${dev.llmSlug}-nomic-embed`);
});

test('an UNSTARTED chat may re-decide its machine — the pin protects history, not emptiness', async () => {
  // The field shape this covers: both clients create the chat FIRST and offer
  // the model second, so "New chat, pick Qwen3" arrived as a PATCH — and was
  // refused with an instruction to start the new chat the user was already in.
  const dev = await enrolLlm({ name: 'freebox' });
  const other = await enrolLlm({ name: 'movebox' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;

  // claude -> local: allowed, host pinned, ask forced.
  const { body: chat } = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  const onto = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: id }) });
  assert.equal(onto.status, 200, JSON.stringify(onto.body));
  assert.equal(onto.body.model, id);
  assert.equal(onto.body.host, dev.id, 'the row choice IS the machine choice');
  assert.equal(onto.body.mode, 'ask', 'ask is forced exactly as at creation');

  // local -> another machine: an empty chat may move.
  const move = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: `local-${other.llmSlug}-qwen3-8b` }) });
  assert.equal(move.status, 200, JSON.stringify(move.body));
  assert.equal(move.body.host, other.id);

  // local -> claude: back onto this host.
  const leave = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: 'opus' }) });
  assert.equal(leave.status, 200, JSON.stringify(leave.body));
  assert.equal(leave.body.host, 'local', 'leaving the machine lands the chat back home');

  // act can still never arrive WITH a local pick, even unstarted.
  const withAct = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ model: id, mode: 'act' }) });
  assert.equal(withAct.status, 400, JSON.stringify(withAct.body));
});

test('the work item rides as mode generate with the composite id', async () => {
  const dev = await enrolLlm({ name: 'workbox' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;
  const { body: chat } = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id }) });
  const sent = await api(`/v1/chats/${chat.id}/messages`, { method: 'POST', body: JSON.stringify({ text: 'hello there' }) });
  assert.ok(sent.status < 300, JSON.stringify(sent.body));

  const work = await api(`/v1/devices/${dev.id}/work?wait=2`);
  assert.ok(work.body && work.body.work, JSON.stringify(work.body));
  assert.equal(work.body.work.mode, 'generate', 'the daemon translates ask -> generate for the local family');
  assert.equal(work.body.work.model, id, 'the composite id travels verbatim; the shim strips its own prefix');

  // The cost gate: a result frame claiming spend on a local run records none.
  const init = { type: 'system', subtype: 'init', session_id: crypto.randomUUID() };
  const result = { type: 'result', is_error: false, result: 'hi', duration_ms: 5, total_cost_usd: 9.99, num_turns: 1 };
  const posted = await api(`/v1/devices/${dev.id}/work/${work.body.work.id}/events`, {
    method: 'POST', body: JSON.stringify({ lines: [JSON.stringify(init), JSON.stringify(result)], done: true, exitCode: 0 }),
  });
  assert.ok(posted.status < 300, JSON.stringify(posted.body));
  await wait(300);
  const msgs = fs.readFileSync(path.join(tmp, 'data', 'chats', chat.id, 'messages.jsonl'), 'utf8')
    .trim().split('\n').map((l) => JSON.parse(l));
  const res_ = msgs.find((m2) => m2.type === 'result');
  assert.ok(res_, 'the result frame landed');
  assert.equal(res_.costUsd, null, 'engine identity gates the ledger, not the number in the frame');
});

test('rounds refuse the local family at create, patch and fire', async () => {
  const dev = await enrolLlm({ name: 'roundbox' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;
  const sched = { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' };

  const made = await api('/v1/rounds', {
    method: 'POST', body: JSON.stringify({ title: 't', prompt: 'p', schedule: sched, model: id }),
  });
  assert.equal(made.status, 400, JSON.stringify(made.body));
  assert.match(made.body.error, /Claude model/);

  const ok = await api('/v1/rounds', {
    method: 'POST', body: JSON.stringify({ title: 't', prompt: 'p', schedule: sched }),
  });
  assert.equal(ok.status, 201);
  const patched = await api(`/v1/rounds/${ok.body.id}`, { method: 'PATCH', body: JSON.stringify({ model: id }) });
  assert.equal(patched.status, 400, JSON.stringify(patched.body));

  // The defensive fire gate: only reachable by editing the file on disk.
  const roundFiles = fs.readdirSync(path.join(tmp, 'data', 'rounds')).filter((f) => f.includes(ok.body.id));
  assert.equal(roundFiles.length, 1, 'found the round file');
  const rf = path.join(tmp, 'data', 'rounds', roundFiles[0]);
  const round = JSON.parse(fs.readFileSync(rf, 'utf8'));
  round.model = id;
  fs.writeFileSync(rf, JSON.stringify(round));
  const fired = await api(`/v1/rounds/${ok.body.id}/run`, { method: 'POST' });
  assert.equal(fired.status, 400, JSON.stringify(fired.body));
  assert.match(fired.body.error, /local model/);
});

test('refusals name the MACHINE, never the -llm credential', async () => {
  // The UI shows machine names everywhere now; an error that says
  // "namebox-llm" would name a thing the user has never seen.
  await api('/v1/devices', { method: 'POST', body: JSON.stringify({ name: 'NAMEBOX', platform: 'windows', scope: 'own' }) });
  const dev = await enrolLlm({ name: 'namebox-llm' });
  const id = `local-${dev.llmSlug}-qwen3-8b`;
  const { body: chat } = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: id }) });
  await makeStarted(dev, chat.id);
  const toAct = await api(`/v1/chats/${chat.id}`, { method: 'PATCH', body: JSON.stringify({ mode: 'act' }) });
  assert.equal(toAct.status, 409, JSON.stringify(toAct.body));
  assert.match(toAct.body.error, /NAMEBOX/, 'the machine name a person knows');
  assert.ok(!toAct.body.error.includes('namebox-llm'), toAct.body.error);
  // The wrong-model refusal names the machine too.
  const bad = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ model: `local-${dev.llmSlug}-nope` }) });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /NAMEBOX/, bad.body.error);
})
