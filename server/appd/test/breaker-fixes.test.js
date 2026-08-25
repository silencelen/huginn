'use strict';
// The four root causes the adversarial review found, each pinned by the case that
// proved it. Every one of these failed before the fix and the failure was SILENT:
// the operator's surfaces showed a clean week while the work had not happened, had
// happened twice, or had happened after they pressed stop.
//
// PORT ALLOCATION — `node --test` runs files CONCURRENTLY and each binds a real
// socket, so ranges must not overlap. The width makes the range, not the base:
//
//   routes-answer      8788 + pid%900   ->  8788-9687
//   routes-lifecycle   9700 + pid%100   ->  9700-9799
//   routes-rounds      9800 + pid%60    ->  9800-9859
//   routes-devices     9870 + pid%50    ->  9870-9919
//   session-identity   9930 + pid%40    ->  9930-9969
//   breaker-fixes      9971 + pid%25    ->  9971-9995
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 9971 + (process.pid % 25);
const BASE = `http://127.0.0.1:${PORT}`;
const LA = 'America/Los_Angeles';

// Answers to order, so a test can ask for a report followed by more chatter —
// the shape that used to destroy the report.
const STUB = `#!/usr/bin/env node
const F = String.fromCharCode(96,96,96), NL = String.fromCharCode(10);
let inp=''; process.stdin.on('data',c=>inp+=c);
process.stdin.on('end',()=>{
  const say=(t)=>console.log(JSON.stringify({type:'assistant',message:{content:[{type:'text',text:t}]}}));
  console.log(JSON.stringify({type:'system',subtype:'init',session_id:'stub-'+process.pid}));
  // The tag the daemon minted for this run, echoed exactly as a real run must:
  // a block without it is treated as something the run READ, not something it
  // wrote, which is what stops a report planted in a log from being believed.
  const TAG=(inp.match(/THIS RUN.S TAG: ([A-Za-z0-9_-]{1,64})/)||[])[1]||'';
  const FENCE=F+'huginn-report'+(TAG?' '+TAG:'');
  const block=FENCE+NL+JSON.stringify({status:'action',headline:'disk nearly full',
    items:[{title:'root fs 99%',detail:'d',suggest:'s'}]})+NL+F;
  if (inp.includes('REPORT_THEN_CHATTER')) { say('Here is what I found.'+NL+NL+block); say('Confirmed.'); }
  else if (inp.includes('SLOW')) { say('working'); const t=Date.now(); while(Date.now()-t<4000){} say('done'); }
  else say('all fine'+NL+NL+FENCE+NL+JSON.stringify({status:'ok',headline:'clean',items:[]})+NL+F);
  console.log(JSON.stringify({type:'result',is_error:false,duration_ms:5,num_turns:1}));
});
`;

let tmp, token, daemon;
const api = async (p, init = {}) => {
  const res = await fetch(BASE + p, { ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) } });
  let body = null; try { body = await res.json(); } catch { /* none */ }
  return { status: res.status, body };
};
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const line = (o) => JSON.stringify(o);
const ASSISTANT = (t) => line({ type: 'assistant', message: { content: [{ type: 'text', text: t }] } });
const INIT = (id) => line({ type: 'system', subtype: 'init', session_id: id });

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-breaker-'));
  const bin = path.join(tmp, 'bin'); fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'claude'), STUB, { mode: 0o755 });
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.mkdirSync(path.join(tmp, 'data')); fs.mkdirSync(path.join(tmp, 'state'));
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: { ...process.env, PATH: `${bin}:${process.env.PATH}`,
      HUGINN_APPD_PORT: String(PORT), HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'), HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'), HUGINN_APPD_WORKDIR: tmp },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 120; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
});
after(() => { if (daemon) daemon.kill('SIGTERM'); if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

const enrol = async (name, scope = 'own') =>
  (await api('/v1/devices', { method: 'POST', body: JSON.stringify({ name, platform: 'linux', scope }) })).body;
const mkChat = async (host, mode = 'ask') =>
  (await api('/v1/chats', { method: 'POST', body: JSON.stringify({ title: 't', mode, host }) })).body;
const say = (id, text) => api(`/v1/chats/${id}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
const poll = async (id, w = 2) => (await api(`/v1/devices/${id}/work?wait=${w}`)).body.work;
const mkRound = async (over = {}) => (await api('/v1/rounds', { method: 'POST', body: JSON.stringify({
  title: 'probe', prompt: 'look at the disks', schedule: { kind: 'weekly', days: [0], at: '19:00', tz: LA }, ...over }) })).body;

// ─────────────────────────────── (c) queued work outliving its run

test('cancelling a remote job takes the work back before the machine sees it', async () => {
  // THE WORST ONE. Stop used to leave the item in the queue, so the device was
  // handed it on its next poll and ran the owner's act prompt for real with full
  // grants — while the chat said it had been cancelled.
  const d = await enrol('cancelbox');
  const c = await mkChat(d.id, 'ask');
  await say(c.id, 'something the owner then stops');
  const stop = await api(`/v1/chats/${c.id}/cancel`, { method: 'POST' });
  assert.ok(stop.status < 400, `cancel: ${stop.status} ${JSON.stringify(stop.body)}`);

  const handed = await poll(d.id, 2);
  assert.strictEqual(handed, null, `a cancelled job was still handed over: ${JSON.stringify(handed)}`);

  const view = (await api(`/v1/devices/${d.id}`)).body;
  assert.strictEqual(view.queued, 0, 'the queue still holds the cancelled item');
  const chat = (await api(`/v1/chats/${c.id}`)).body;
  assert.strictEqual(chat.running, false, 'the chat is still running after a cancel nothing picked up');
});

test('a device that never polls does not get its work weeks later', async () => {
  // loseRemoteRun deleted the run and left the queue entry at the FRONT, so a
  // laptop waking hours later ran a dead job whose every result was 404'd.
  const d = await enrol('sleepybox');
  const c = await mkChat(d.id, 'ask');
  await say(c.id, 'queued for a machine that is asleep');
  assert.strictEqual((await api(`/v1/devices/${d.id}`)).body.queued, 1);
  await api(`/v1/chats/${c.id}/cancel`, { method: 'POST' });
  assert.strictEqual((await api(`/v1/devices/${d.id}`)).body.queued, 0,
    'the withdrawn item is still counted, so the Devices screen lies too');
});

test('deleting a device settles its run instead of leaving the chat running', async () => {
  const d = await enrol('vanishbox');
  const c = await mkChat(d.id, 'ask');
  await say(c.id, 'start');
  const w = await poll(d.id);
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST',
    body: JSON.stringify({ lines: [INIT('s'), ASSISTANT('working')] }) });
  await api(`/v1/devices/${d.id}`, { method: 'DELETE' });
  await wait(500);
  const chat = (await api(`/v1/chats/${c.id}`)).body;
  assert.strictEqual(chat.running, false, 'a run whose device was removed still reads as running');
});

// ─────────────────────────────── (d) the verdict inferred from text

test('a report followed by more chatter is still the report', async () => {
  // An ordinary agentic turn — write the report, run one more tool, say
  // "Confirmed." — used to file the word "Confirmed." as the week's finding while
  // a complete `action` report sat one message earlier in the same transcript.
  const r = await mkRound({ prompt: 'REPORT_THEN_CHATTER' });
  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const until = Date.now() + 20000;
  let last = null;
  while (Date.now() < until) {
    last = (await api(`/v1/rounds/${r.id}`)).body.lastRun;
    if (last) break;
    await wait(150);
  }
  assert.ok(last, 'the round never recorded a run');
  assert.strictEqual(last.status, 'action', `status was ${last.status}, headline ${JSON.stringify(last.headline)}`);
  assert.match(last.headline, /disk nearly full/);
  assert.strictEqual(last.items.length, 1);
  assert.strictEqual(last.malformed, false);
});

test('a delivered report beats a cancel that could not stop the far machine', async () => {
  // On a device this is the NORMAL outcome, not a race: the timeout cannot reach
  // the machine, which finishes cleanly and posts a valid report — and the Round
  // used to file "did not finish" over the top of it.
  const d = await enrol('reportbox');
  const r = await mkRound({ host: d.id, prompt: 'anything' });
  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const w = await poll(d.id, 3);
  assert.ok(w, 'the device was never handed the round');
  const chatId = (await api(`/v1/rounds/${r.id}`)).body.currentChatId;
  // Cancelled AFTER the handover, so the work cannot be withdrawn and the run is
  // genuinely marked cancelled — the state in which the far machine goes on to
  // finish cleanly and deliver a real report.
  await api(`/v1/chats/${chatId}/cancel`, { method: 'POST' });
  // Tagged from the work item the device was handed — the only place the tag
  // appears, and the reason a block arriving from a device can be trusted as its
  // answer rather than as something it read while working.
  const tag = (String(w.prompt || '').match(/THIS RUN'S TAG: ([A-Za-z0-9_-]{1,64})/) || [])[1] || '';
  const block = '```huginn-report ' + tag + '\n' + JSON.stringify({ status: 'ok', headline: '7 of 7 backups verified', items: [] }) + '\n```';
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST',
    body: JSON.stringify({ lines: [INIT('s'), ASSISTANT(block)] }) });
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST',
    body: JSON.stringify({ lines: [], done: true, exitCode: 0 }) });
  await wait(600);
  const last = (await api(`/v1/rounds/${r.id}`)).body.lastRun;
  assert.ok(last, 'no run recorded');
  assert.match(last.headline, /7 of 7 backups verified/,
    `the report was overwritten by the failure: ${JSON.stringify(last.headline)}`);
  assert.strictEqual(last.status, 'ok');
});

// ─────────────────────────────── (b) stale writes across an await

test('a message queued during a round run does not unseal it', async () => {
  // settleRun held a snapshot from before finishRoundRun wrote the seal, so any
  // message typed mid-run wiped `sealed` — and the owner's next question was then
  // filed as the Round's official report.
  const r = await mkRound({ prompt: 'SLOW' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  assert.strictEqual(fired.status, 202);
  const chatId = fired.body.chatId;
  await wait(300);
  await say(chatId, 'a question typed while it was still going');
  const until = Date.now() + 25000;
  let chat = null;
  while (Date.now() < until) {
    chat = (await api(`/v1/chats/${chatId}`)).body;
    if (chat && chat.running === false) break;
    await wait(200);
  }
  assert.ok(chat && chat.running === false, 'the run never finished');
  assert.strictEqual(chat.sealed, true, 'the seal was erased by a stale write');
  const after = await say(chatId, 'and another');
  assert.strictEqual(after.status, 409, 'a sealed run accepted a new message');
});

test('deleting a round stops the work it is doing right now', async () => {
  // "Delete the schedule" used to leave the run with no surface at all — absent
  // from /v1/rounds AND /v1/chats, nothing to press, holding a pool slot for 2h.
  const r = await mkRound({ prompt: 'SLOW' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const chatId = fired.body.chatId;
  await wait(300);
  const del = await api(`/v1/rounds/${r.id}`, { method: 'DELETE' });
  assert.strictEqual(del.status, 200);
  // SHORTER than the stub's own work (4s). Waiting longer would pass on a run
  // that simply finished by itself, which is not what this is testing.
  const until = Date.now() + 2500;
  let chat = null;
  while (Date.now() < until) {
    chat = (await api(`/v1/chats/${chatId}`)).body;
    if (chat && chat.running === false) break;
    await wait(100);
  }
  assert.ok(chat && chat.running === false,
    'the orphaned run is still going — delete left it holding a slot with no surface to stop it');
});

// ───────────────────────── the fence around somebody's personal computer

test('a session id that is really a flag never reaches the device', async () => {
  // `--resume` takes its value OPTIONALLY, so a string starting with "--" does
  // not become the session id — it becomes the next flag. The value arrives
  // inside an init event the DEVICE posts and is echoed back to it verbatim, so
  // an unvalidated string here is authority travelling inside a request.
  const d = await enrol('resumebox');
  const c = await mkChat(d.id, 'ask');
  await say(c.id, 'first turn');
  const w = await poll(d.id, 3);
  assert.ok(w, 'no work handed over');
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST', body: JSON.stringify({
    lines: [line({ type: 'system', subtype: 'init', session_id: '--dangerously-skip-permissions' }),
      ASSISTANT('hello')],
  }) });
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST',
    body: JSON.stringify({ lines: [], done: true, exitCode: 0 }) });
  await wait(400);

  const chat = (await api(`/v1/chats/${c.id}`)).body;
  assert.notStrictEqual(chat.claudeSessionId, '--dangerously-skip-permissions',
    'a flag was stored as this chat\'s session id');

  // And the next turn must not carry it into an argv position.
  await say(c.id, 'second turn');
  const w2 = await poll(d.id, 3);
  assert.ok(w2, 'no second work item');
  assert.ok(!String(w2.resumeSessionId || '').startsWith('--'),
    `a flag reached the device as a resume id: ${JSON.stringify(w2.resumeSessionId)}`);
});

test('a real session id still rides along, or resuming would silently stop working', async () => {
  // The other half of the fix: validation that rejects everything is not a fix.
  const d = await enrol('resumeok');
  const c = await mkChat(d.id, 'ask');
  await say(c.id, 'first');
  const w = await poll(d.id, 3);
  const realId = '4f1c2b8a-9d3e-4a71-8b52-6c0f7e1a2d94';
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST', body: JSON.stringify({
    lines: [line({ type: 'system', subtype: 'init', session_id: realId }), ASSISTANT('hi')] }) });
  await api(`/v1/devices/${d.id}/work/${w.id}/events`, { method: 'POST',
    body: JSON.stringify({ lines: [], done: true, exitCode: 0 }) });
  await wait(400);
  await say(c.id, 'second');
  const w2 = await poll(d.id, 3);
  assert.strictEqual(w2.resumeSessionId, realId, 'a valid session id was dropped');
});

test('a mode nobody defined is refused rather than mapped to a scope', async () => {
  // `MODE_NEEDS.constructor` is a FUNCTION on any plain object. The daemon
  // refused these by accident of indexOf; now it refuses them on purpose, and
  // says something that does not echo the caller's own string back.
  const devices = require('../lib/devices');
  const now = Date.now();
  for (const scope of ['look', 'work', 'own']) {
    const box = { name: 'box', scope, locked: false, lastSeen: now };
    for (const mode of ['constructor', 'toString', '__proto__', 'hasOwnProperty', '']) {
      const v = devices.canRun(box, mode, now);
      assert.strictEqual(v.ok, false, `${scope} accepted mode ${JSON.stringify(mode)}`);
      assert.ok(!v.reason.includes(mode) || mode === '',
        `the refusal echoed the caller's string: ${v.reason}`);
    }
    // and the two real ones still behave
    assert.strictEqual(devices.canRun(box, 'ask', now).ok, true, `${scope} refused ask`);
  }
});

// ───────────────────────── the surfaces must agree with each other

test('a round pinned to a machine that is gone can still be edited', async () => {
  // Both clients send `host` on EVERY save, so a removed device made every edit
  // fail — including changing only the title — with an error naming something the
  // person did not touch. If it was the only device the editor hid the
  // where-it-runs chips too, so there was no way to move the Round back here.
  const d = await enrol('doomedhost');
  const r = await mkRound({ host: d.id });
  await api(`/v1/devices/${d.id}`, { method: 'DELETE' });

  const renamed = await api(`/v1/rounds/${r.id}`, { method: 'PATCH',
    body: JSON.stringify({ title: 'renamed after the machine went', host: d.id, mode: 'ask' }) });
  assert.strictEqual(renamed.status, 200,
    `an edit was refused because of a machine that is gone: ${JSON.stringify(renamed.body)}`);
  assert.strictEqual(renamed.body.title, 'renamed after the machine went');
  assert.strictEqual(renamed.body.host, d.id, 'the edit silently re-homed the round');

  // And it can be brought back to this host.
  const home = await api(`/v1/rounds/${r.id}`, { method: 'PATCH', body: JSON.stringify({ host: 'local' }) });
  assert.strictEqual(home.status, 200);
  assert.strictEqual(home.body.host, 'local');
});

test('moving a round ONTO a device that does not exist is still refused', async () => {
  // The other half: the escape hatch above must not become a way to pin a Round
  // to something that was never there.
  const r = await mkRound();
  const bad = await api(`/v1/rounds/${r.id}`, { method: 'PATCH',
    body: JSON.stringify({ host: '00000000-0000-0000-0000-000000000000' }) });
  assert.strictEqual(bad.status, 400, 'a round was pinned to a device that does not exist');
});

test('an act round cannot be widened onto a look-only device', async () => {
  // The check the escape hatch must not weaken: the device is still THERE, so a
  // mode change that it cannot honour is a real error the owner can act on.
  const d = await enrol('lookonly', 'look');
  const r = await mkRound({ host: d.id, mode: 'ask' });
  const widened = await api(`/v1/rounds/${r.id}`, { method: 'PATCH',
    body: JSON.stringify({ mode: 'act', host: d.id }) });
  assert.strictEqual(widened.status, 400, 'an act round was pinned to a look-only machine');
  assert.match(JSON.stringify(widened.body), /look/);
});
