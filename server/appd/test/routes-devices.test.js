'use strict';
// Route-level tests for Devices, including a whole remote run.
//
// THE TEST IS THE DEVICE. Because the transport is a pull — register, long-poll,
// post results — a device is nothing but a sequence of HTTP calls, so the entire
// remote path can be exercised with no runner, no second machine and no real
// `claude` anywhere. That is a property of the design worth noticing: a transport
// a test can impersonate is a transport that can be debugged with curl.
//
// SAFETY: no local runs are ever started here, so no `claude` is spawned. Every
// chat these tests make is pinned to a device that only exists in this file.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

// PORT ALLOCATION — every file here binds a real socket and `node --test` runs
// the files CONCURRENTLY, so these ranges must not overlap. They did: this file
// and routes-lifecycle both sat inside 9700-9949, and the suite passed four times
// before failing 16 tests on an unlucky pair of pids. The width is what makes a
// range, not the base, so both are fixed here:
//
//   routes-answer       8788 + pid%900   ->  8788-9687
//   routes-lifecycle    9700 + pid%100   ->  9700-9799
//   routes-rounds       9800 + pid%60    ->  9800-9859
//   routes-devices      9870 + pid%50    ->  9870-9919   (this file)
//   session-identity    9930 + pid%40    ->  9930-9969
//   breaker-fixes       9971 + pid%25    ->  9971-9995
//   routes-modelgate   10000 + pid%50    -> 10000-10049
//   routes-localmodels 10050 + pid%50   -> 10050-10099
//   routes-polish      10100 + pid%50    -> 10100-10149
//
// Also spoken for, outside this directory: scripts/test-llm-shim.js holds
// 18790-18799.
//
// Adding a file? Take the next free block and extend this table, in every file.
const PORT = 9870 + (process.pid % 50);
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

/** Enrols a device, as its runner would on startup. */
async function enrol(over = {}) {
  const { status, body } = await api('/v1/devices', {
    method: 'POST',
    body: JSON.stringify({ name: 'PRESTIGE', platform: 'windows', scope: 'work', ...over }),
  });
  assert.equal(status, 201, JSON.stringify(body));
  return body;
}

/** A chat pinned to a device. Never local, so nothing spawns here. */
async function chatOn(deviceId, mode = 'act') {
  return api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode, host: deviceId }) });
}

async function send(chatId, text = 'have a look') {
  return api(`/v1/chats/${chatId}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
}

/** The device picking up its next job. */
async function poll(deviceId, waitS = 2) {
  return api(`/v1/devices/${deviceId}/work?wait=${waitS}`);
}

async function postEvents(deviceId, workId, lines, extra = {}) {
  return api(`/v1/devices/${deviceId}/work/${workId}/events`, {
    method: 'POST',
    body: JSON.stringify({ lines: lines.map((l) => JSON.stringify(l)), ...extra }),
  });
}

const assistant = (text) => ({ type: 'assistant', message: { content: [{ type: 'text', text }] } });

/**
 * A report block with REAL newlines. Built from char codes rather than escaped:
 * a literal backslash-n never matches the daemon's fence regex, and the failure
 * reads as "the parser is broken" rather than "the test wrote the wrong bytes".
 */
const FENCE = String.fromCharCode(96, 96, 96);
const NL = String.fromCharCode(10);
const REPORT = (json, tag = null) =>
  FENCE + 'huginn-report' + (tag ? ' ' + tag : '') + NL + json + NL + FENCE;

/**
 * The tag the daemon minted for this run, read out of the prompt it handed over.
 *
 * A device is the clearest case for why the tag exists: the machine holding the
 * file system is the one reading logs and pages, so a report block planted in
 * what it read arrives over the same wire as its answer. The tag rides in the
 * work item's prompt, which is the only place it appears.
 */
const tagOf = (prompt) => (String(prompt || '').match(/THIS RUN'S TAG: ([A-Za-z0-9_-]{1,64})/) || [])[1] || null;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-dev-'));
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
  for (let i = 0; i < 100; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
  // ⚠ IS THE DAEMON ON THIS PORT ACTUALLY OURS? The formula above gives few
  // slots, and a daemon leaked by an earlier run — a test process killed before
  // after() could fire — sits on one, answers /v1/ping happily because ping
  // needs no token, and rejects OURS. That surfaced once as twelve tests failing
  // with `401 unauthorized`, which reads like a code bug and is not one. So ask
  // an AUTHENTICATED question before trusting the port, and say plainly what is
  // wrong: `ss -ltnp | grep <port>` then kill it.
  const own = await api('/v1/rounds');
  if (own.status === 401) {
    throw new Error(`port ${PORT} is held by another huginn-appd, probably one leaked by an earlier `
      + `test run — it answers ping but not our token. Find it with: ss -ltnp | grep ${PORT}`);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------- enrolment

test('a device enrols and reports what it will do', async () => {
  const d = await enrol();
  assert.equal(d.name, 'PRESTIGE');
  assert.equal(d.platform, 'windows');
  assert.equal(d.scope, 'work');
  assert.equal(d.effectiveScope, 'work');
  assert.equal(d.online, true);
  assert.match(d.id, /^[0-9a-f-]{36}$/);
});

test('an unknown scope enrols at the floor, not as own', async () => {
  // The floor is generate, the exclusive rung: a junk scope can run nothing a
  // claude device runs. An ABSENT scope still enrols at look (see devices.test).
  const d = await enrol({ name: 'sketchy', scope: 'root' });
  assert.equal(d.scope, 'generate', 'the floor, never the widest');
});

test('a nameless device is refused', async () => {
  const r = await api('/v1/devices', { method: 'POST', body: JSON.stringify({ scope: 'own' }) });
  assert.equal(r.status, 400);
  assert.match(r.body.error, /name/);
});

test('re-enrolling under the same id does not leave a ghost', async () => {
  const first = await enrol({ name: 'restarts' });
  const again = await api('/v1/devices', {
    method: 'POST',
    body: JSON.stringify({ id: first.id, name: 'restarts', scope: 'own' }),
  });
  assert.equal(again.status, 201);
  assert.equal(again.body.id, first.id);
  assert.equal(again.body.registeredAt, first.registeredAt, 'the original enrolment date survives');
  assert.equal(again.body.scope, 'own', 'but what it is willing to do is updated');

  const list = await api('/v1/devices');
  assert.equal(list.body.devices.filter((x) => x.name === 'restarts').length, 1);
});

// ------------------------------------------------------------------ lock

test('a locked device is read-only, and says so where it matters', async () => {
  const d = await enrol({ name: 'locker', scope: 'own' });
  const beat = await api(`/v1/devices/${d.id}/beat`, { method: 'POST', body: JSON.stringify({ locked: true }) });
  assert.equal(beat.status, 200);
  assert.equal(beat.body.effectiveScope, 'look');

  const refused = await chatOn(d.id, 'act');
  assert.equal(refused.status, 409);
  assert.match(refused.body.error, /locked/, 'the reason distinguishes "unlock it" from "widen the scope"');

  // ask still works while locked: reading is what look means.
  const allowed = await chatOn(d.id, 'ask');
  assert.equal(allowed.status, 201);

  await api(`/v1/devices/${d.id}/beat`, { method: 'POST', body: JSON.stringify({ locked: false }) });
  assert.equal((await chatOn(d.id, 'act')).status, 201, 'unlocking restores it');
});

test('a look-scope device cannot be given act work at all', async () => {
  const d = await enrol({ name: 'looker', scope: 'look' });
  const r = await chatOn(d.id, 'act');
  assert.equal(r.status, 409);
  assert.match(r.body.error, /look/);
});

test('a chat cannot be pinned to a device that does not exist', async () => {
  const r = await chatOn(crypto.randomUUID(), 'ask');
  assert.equal(r.status, 404);
});

// ------------------------------------------------------------ a whole run

test('a run happens on the device and comes back through the same pipeline', async () => {
  const d = await enrol({ name: 'runner' });
  const chat = await chatOn(d.id, 'act');
  assert.equal(chat.status, 201);
  assert.equal(chat.body.host, d.id, 'the chat records where it runs');

  assert.equal((await send(chat.body.id, 'audit the build')).status, 202);

  const picked = await poll(d.id);
  assert.equal(picked.status, 200);
  const work = picked.body.work;
  assert.ok(work, 'the device was handed the job');
  assert.equal(work.chatId, chat.body.id);
  assert.equal(work.mode, 'act');
  assert.match(work.prompt, /audit the build/);

  // THE load-bearing assertion of this file: the daemon hands over a request and
  // no authority. If a tool grant ever rides along, one leaked bearer token stops
  // meaning "this host" and starts meaning "the owner's PC".
  const flat = JSON.stringify(work).toLowerCase();
  for (const forbidden of ['allowedtools', 'disallowedtools', 'permission', 'sudo', 'scope']) {
    assert.ok(!flat.includes(forbidden), `work must not carry ${forbidden}: ${flat}`);
  }

  // The device streams back exactly what a local `claude -p` would have printed.
  const mid = await postEvents(d.id, work.id, [assistant('I looked. It builds.')]);
  assert.equal(mid.status, 200);
  assert.equal(mid.body.cancel, false);

  const open = await api(`/v1/chats/${chat.body.id}`);
  assert.equal(open.body.running, true, 'still in flight');
  // The NAME, resolved by the daemon. A client that looked this up itself would
  // print a bare uuid for a device that had since been unenrolled.
  assert.equal(open.body.hostName, 'runner', 'the chat says which machine it runs on');
  const listed = (await api('/v1/chats')).body.chats.find((c) => c.id === chat.body.id);
  assert.equal(listed.hostName, 'runner', 'and so does the list row');
  assert.ok(open.body.messages.some((m) => m.type === 'assistant' && /It builds/.test(m.text || '')),
    'the remote answer is in the transcript, written by the same handler as a local one');

  const done = await postEvents(d.id, work.id, [{ type: 'result', is_error: false }],
    { done: true, exitCode: 0 });
  assert.equal(done.status, 200);
  assert.equal(done.body.done, true);

  const after_ = await api(`/v1/chats/${chat.body.id}`);
  assert.equal(after_.body.running, false, 'settled');
  assert.equal(after_.body.finishedRuns, 1, 'and it left the durable finish mark');
});

test("a remote chat's conversation is readable, though its transcript is on the other machine", async () => {
  // The bug: a chat's reader renders Claude's own transcript file, found under
  // THIS host's ~/.claude/projects. A run on another machine wrote that file
  // there, so the conversation came back empty — no answer, and the user's own
  // message gone with it — while the chat list still showed the text.
  const d = await enrol({ name: 'reader' });
  const chat = await chatOn(d.id, 'ask');
  await send(chat.body.id, 'say something back');

  // Visible BEFORE the device has even picked it up: no session id exists yet,
  // and the old code refused with "chat has not run yet".
  const early = await api(`/v1/chats/${chat.body.id}/transcript`);
  assert.equal(early.status, 200);
  assert.ok(early.body.events.some((e) => e.kind === 'user' && /say something back/.test(e.text || '')),
    'the message you just sent is on screen while the job is still queued');

  const work = (await poll(d.id)).body.work;
  await postEvents(d.id, work.id, [assistant('here you go')]);
  await postEvents(d.id, work.id, [{ type: 'result', is_error: false }], { done: true, exitCode: 0 });

  const full = await api(`/v1/chats/${chat.body.id}/transcript`);
  assert.equal(full.status, 200);
  const kinds = full.body.events.map((e) => e.kind);
  assert.ok(kinds.includes('user') && kinds.includes('assistant'), JSON.stringify(kinds));
  assert.ok(full.body.events.some((e) => e.kind === 'assistant' && /here you go/.test(e.text || '')));
  assert.equal(full.body.hostName, 'reader', 'and it says where it ran');

  // The paging contract, which is the easy thing to get wrong: the reader hands
  // the offset back and APPENDS what returns, so a second read at the same
  // offset must be empty or the conversation doubles on screen.
  const again = await api(`/v1/chats/${chat.body.id}/transcript?offset=${full.body.nextOffset}`);
  assert.equal(again.status, 200);
  assert.deepEqual(again.body.events, [], 'a tail read past the end returns nothing');
});

test('a device failure is READABLE in the conversation, not just in the list', async () => {
  // Errors are recorded as type `error`, and the readers know six kinds — error
  // is not one of them — so emitting it as-is would render as nothing at all.
  const d = await enrol({ name: 'failreader' });
  const chat = await chatOn(d.id, 'ask');
  await send(chat.body.id, 'this will fail');
  const work = (await poll(d.id)).body.work;
  await postEvents(d.id, work.id, [], { done: true, exitCode: 1, error: 'claude is not installed there' });

  const t = await api(`/v1/chats/${chat.body.id}/transcript`);
  assert.ok(t.body.events.some((e) => /not installed/.test(e.text || '')),
    'the reason is in the conversation, in a kind the reader draws');
});

test('the long poll wakes the moment work exists', async () => {
  // The difference between "starts now" and "starts within 25 seconds" is the
  // difference between the feature feeling remote and feeling broken.
  const d = await enrol({ name: 'waker' });
  const chat = await chatOn(d.id, 'ask');
  const parked = poll(d.id, 10);
  await wait(300);
  await send(chat.body.id, 'wake up');

  const answered = await parked;
  assert.equal(answered.status, 200);
  assert.ok(answered.body.work, 'the parked poll was handed the new job, not left to time out');
  assert.equal(answered.body.work.chatId, chat.body.id);

  await postEvents(d.id, answered.body.work.id, [{ type: 'result', is_error: false }],
    { done: true, exitCode: 0 });
});

test('an idle poll comes back empty rather than hanging', async () => {
  const d = await enrol({ name: 'idler' });
  const started = Date.now();
  const r = await poll(d.id, 1);
  assert.equal(r.status, 200);
  assert.equal(r.body.work, null);
  assert.ok(Date.now() - started >= 900, 'it actually waited');
});

test('one machine is never asked to run two things at once', async () => {
  const d = await enrol({ name: 'single' });
  const a = await chatOn(d.id, 'ask');
  const b = await chatOn(d.id, 'ask');
  assert.equal((await send(a.body.id, 'first')).status, 202);

  const second = await send(b.body.id, 'second');
  assert.equal(second.status, 409);
  assert.match(second.body.error, /already running/);

  // Finishing the first frees the machine.
  const work = (await poll(d.id)).body.work;
  await postEvents(d.id, work.id, [{ type: 'result', is_error: false }], { done: true, exitCode: 0 });
  assert.equal((await send(b.body.id, 'second, again')).status, 202);
  const w2 = (await poll(d.id)).body.work;
  await postEvents(d.id, w2.id, [{ type: 'result', is_error: false }], { done: true, exitCode: 0 });
});

test('results are only accepted from the device that owns the run', async () => {
  const owner = await enrol({ name: 'owner' });
  const other = await enrol({ name: 'other' });
  const chat = await chatOn(owner.id, 'ask');
  await send(chat.body.id, 'mine');
  const work = (await poll(owner.id)).body.work;

  const stolen = await postEvents(other.id, work.id, [assistant('I am not that device')]);
  assert.equal(stolen.status, 403);

  const unknown = await postEvents(owner.id, crypto.randomUUID(), [assistant('nothing')]);
  assert.equal(unknown.status, 404);

  await postEvents(owner.id, work.id, [{ type: 'result', is_error: false }], { done: true, exitCode: 0 });
});

test('a device reporting failure records it rather than a silent success', async () => {
  const d = await enrol({ name: 'failer' });
  const chat = await chatOn(d.id, 'ask');
  await send(chat.body.id, 'break');
  const work = (await poll(d.id)).body.work;

  await postEvents(d.id, work.id, [], { done: true, exitCode: 1, error: 'claude is not installed there' });
  const open = await api(`/v1/chats/${chat.body.id}`);
  assert.equal(open.body.running, false);
  assert.ok(open.body.messages.some((m) => m.type === 'error' && /not installed/.test(m.text || '')),
    "the reason reaches the transcript, in the device's own words");
});

test('removing a device stops work being offered to it', async () => {
  const d = await enrol({ name: 'doomed' });
  assert.equal((await api(`/v1/devices/${d.id}`, { method: 'DELETE' })).status, 200);
  assert.equal((await api(`/v1/devices/${d.id}`)).status, 404);
  assert.equal((await chatOn(d.id, 'ask')).status, 404);
});

// --------------------------------------------------- a Round on a Device

test('a Round can name a device, and refuses one that could never run it', async () => {
  const looker = await enrol({ name: 'weekly-looker', scope: 'look' });
  const worker = await enrol({ name: 'weekly-worker', scope: 'work' });
  const schedule = { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' };

  const refused = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({ title: 'nightly build', prompt: 'build it', schedule, mode: 'act', host: looker.id }),
  });
  assert.equal(refused.status, 400);
  assert.match(refused.body.error, /cannot run act/);

  const ok = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({ title: 'nightly build', prompt: 'build it', schedule, mode: 'act', host: worker.id }),
  });
  assert.equal(ok.status, 201);
  assert.equal(ok.body.host, worker.id);
  assert.equal(ok.body.hostName, 'weekly-worker', 'named, so no client resolves an id itself');
});

test("a Round's manual run lands on its device, not on this host", async () => {
  // Step 6 of the release, and it costs nothing: the Round puts its placement on
  // the chat, and the same seam every other caller uses does the rest.
  const d = await enrol({ name: 'round-host' });
  const made = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: 'remote check', prompt: 'check the disk', mode: 'act', host: d.id,
      schedule: { kind: 'weekly', days: [0], at: '19:00', tz: 'America/Los_Angeles' },
    }),
  });
  assert.equal(made.status, 201);

  const fired = await api(`/v1/rounds/${made.body.id}/run`, { method: 'POST' });
  assert.equal(fired.status, 202);

  const work = (await poll(d.id)).body.work;
  assert.ok(work, 'the scheduled job was handed to the device');
  assert.equal(work.chatId, fired.body.chatId);
  assert.equal(work.roundId, made.body.id, 'and it knows which Round it belongs to');
  assert.match(work.prompt, /check the disk/);
  assert.match(work.prompt, /huginn-report/, 'the output contract travels with it');

  // Report back as the device would, and the Round records it like any other.
  await postEvents(d.id, work.id, [
    assistant(REPORT('{"status":"ok","headline":"disk is fine"}', tagOf(work.prompt))),
    { type: 'result', is_error: false },
  ], { done: true, exitCode: 0 });

  const round = await api(`/v1/rounds/${made.body.id}`);
  assert.equal(round.body.lastRun.status, 'ok');
  assert.equal(round.body.lastRun.headline, 'disk is fine');
  assert.equal(round.body.lastRun.malformed, false);
});

// ------------------------------------------------- reachable is not the same as free

test('a device that has not asked for work is not reported as free', async () => {
  // ⚠ remoteRuns is in-memory, so restarting appd wipes it — while the far
  // machine is still running its claude and is SINGLE-JOB: it will not poll
  // again until that child exits, which for a real run is minutes to hours. The
  // daemon reported that device online:true, running:false, queued:0, accepted
  // the next job with a 202, and the job sat undelivered until it was declared
  // "no word for 5 minutes". A heartbeat proves REACHABLE; only asking for work
  // proves FREE, and the daemon was answering the second question with the first.
  const d = await enrol('pollbox');
  const before = (await api('/v1/devices')).body.devices.find((x) => x.id === d.id);
  assert.equal(before.awaitingPoll, true, 'a device that has never polled looks free');

  // One long-poll with no work waiting is enough to prove it is asking.
  await api(`/v1/devices/${d.id}/work?wait=1`);
  const after = (await api('/v1/devices')).body.devices.find((x) => x.id === d.id);
  assert.equal(after.awaitingPoll, false, 'asking for work did not count as asking');
});

test('a generate-scope device is not a place a chat runs', async () => {
  // The exclusive rung, seen from the daemon's pre-check: a serving row can
  // never be offered ask or act, and the refusal is said at the button.
  const dev = await enrol({ name: 'LLMBOX', scope: 'generate' });
  const refused = await chatOn(dev.id, 'ask');
  assert.equal(refused.status, 409, JSON.stringify(refused.body));
  assert.match(refused.body.error, /generate/);
  // And the client wire never carries generate: an unknown chat mode coerces to
  // ask. generate exists only in the WORK ITEM, minted by the daemon itself.
  const made = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'generate' }) });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.equal(made.body.mode, 'ask', 'generate is a work-item mode, not a chat mode');
});

// ------------------------------------------------- the registry on disk
//
// ⚠ saveDevices was the ONE state writer here that was a bare writeFileSync,
// while push.json and clients.json have used tmp+rename since they were written.
// The loader silently starts from EMPTY when it cannot parse the file, so a crash
// or a full disk partway through a write discards every enrolment without a word:
// each machine then re-registers under a fresh id and comes back as a new row
// with no history, while the old rows are simply gone.
test('the device registry is written atomically and kept private', async () => {
  const dev = await enrol({ name: 'ATOMIC' });
  const file = path.join(tmp, 'data', 'devices.json');

  // Present, parseable, and holding what was just enrolled.
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.ok(parsed.devices[dev.id], 'the enrolment reached disk');

  // 0600, like push.json and clients.json: this file names every machine that
  // has offered itself to this daemon.
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);

  // rename(2) is what makes the swap atomic, so the temp file must not survive
  // it — one left behind means the write path is not the one being asserted.
  assert.equal(fs.existsSync(`${file}.tmp`), false, 'the temp file was renamed, not left');
});

test('a deletion is persisted the same way, not just from memory', async () => {
  const dev = await enrol({ name: 'ATOMICGONE' });
  const file = path.join(tmp, 'data', 'devices.json');
  assert.ok(JSON.parse(fs.readFileSync(file, 'utf8')).devices[dev.id]);

  const gone = await api(`/v1/devices/${dev.id}`, { method: 'DELETE' });
  assert.equal(gone.status, 200, JSON.stringify(gone.body));

  const after = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(after.devices[dev.id], undefined, 'a restart must not resurrect it');
  assert.equal(fs.existsSync(`${file}.tmp`), false);
});

// A row can only be retired with the id that made it — the doctrine both the CLI
// and the desktop toggle-off now hold to (keep the handle until the DELETE
// lands). A second DELETE has to be distinguishable from a first, or "already
// gone" and "never landed" look the same to a retrying client.
test('deleting a device twice says so rather than pretending', async () => {
  const dev = await enrol({ name: 'TWICE' });
  assert.equal((await api(`/v1/devices/${dev.id}`, { method: 'DELETE' })).status, 200);
  const again = await api(`/v1/devices/${dev.id}`, { method: 'DELETE' });
  assert.equal(again.status, 404, 'a retry can read this as "the row is gone"');
});
