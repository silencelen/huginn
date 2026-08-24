'use strict';
// Route-level tests for Rounds, including a whole run from fire to report.
//
// A STUB `claude` sits at the front of the daemon's PATH, so the full path —
// fire, spawn, stream-json, close handler, report parse, history — is exercised
// without invoking the real CLI, spending tokens, or depending on what a model
// happens to say. The stub reads the prompt it is given and answers to order,
// which is what lets the malformed-report case be tested at all: a real model
// asked to produce a broken block would usually produce a working one.
//
// SAFETY: no tmux sessions, no real claude, no live daemon. Everything lives in a
// scratch HUGINN_APPD_DATA and is removed in after().

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 9860 + (process.pid % 60);
const BASE = `http://127.0.0.1:${PORT}`;
const LA = 'America/Los_Angeles';

let tmp, token, daemon;

/**
 * Answers whatever the prompt asks it for. The Round's prompt is written by the
 * test, so each case orders the shape of report it needs.
 */
const STUB = `#!/usr/bin/env node
// Backticks and newlines are BUILT, not escaped: this source is itself embedded in
// a template literal, and one wrong backslash produces a stub that emits the text
// "\\n" instead of a line break — which fails as a parse bug in the daemon.
const F = String.fromCharCode(96, 96, 96);
const NL = String.fromCharCode(10);
let inp = '';
process.stdin.on('data', (c) => { inp += c; });
process.stdin.on('end', () => {
  let text;
  if (inp.includes('EMIT_PROSE')) {
    text = 'I looked at everything and it seems fine, no block for you.';
  } else if (inp.includes('EMIT_BROKEN')) {
    text = F + 'huginn-report' + NL + '{this is not json}' + NL + F;
  } else {
    const status = (inp.match(/EMIT_STATUS:(\\w+)/) || [])[1] || 'ok';
    const obj = {
      status,
      headline: 'stub report for ' + status,
      items: status === 'ok' ? [] : [{ title: 'a thing', detail: 'it happened', suggest: 'do the thing' }],
    };
    if (inp.includes('EMIT_GOAL_MISS')) obj.goalMet = false;
    else if (inp.includes('EMIT_GOAL_HIT')) obj.goalMet = true;
    const body = JSON.stringify(obj);
    text = 'Here is what I found.' + NL + NL + F + 'huginn-report' + NL + body + NL + F;
  }
  console.log(JSON.stringify({ type: 'system', subtype: 'init', session_id: 'stub-' + process.pid }));
  console.log(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text }] } }));
  console.log(JSON.stringify({ type: 'result', is_error: false, duration_ms: 5, num_turns: 1 }));
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

const SUNDAY_7PM = { kind: 'weekly', days: [0], at: '19:00', tz: LA };

async function mkRound(over = {}) {
  const { status, body } = await api('/v1/rounds', {
    method: 'POST',
    body: JSON.stringify({
      title: 'Telegram health check',
      prompt: 'Review the week of Telegram alerts.',
      schedule: SUNDAY_7PM,
      ...over,
    }),
  });
  assert.equal(status, 201, JSON.stringify(body));
  return body;
}

/** Waits for a Round to record a finished run. */
async function waitForRun(id, timeoutMs = 15_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const { body } = await api(`/v1/rounds/${id}`);
    if (body && body.lastRun) return body;
    await wait(150);
  }
  throw new Error('round never recorded a run');
}

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-rounds-'));
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
  for (let i = 0; i < 100; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not up */ }
    await wait(100);
  }
});

after(() => {
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

// ------------------------------------------------------------------- CRUD

test('a new Round is armed, described, and read-only by default', async () => {
  const r = await mkRound();
  assert.equal(r.cadence, 'Sundays at 7:00 PM', 'the daemon renders the cadence, not the client');
  assert.ok(r.nextRunAt > Date.now(), 'armed forward');
  assert.equal(r.mode, 'ask', 'an unattended run does not get Bash unless asked');
  assert.equal(r.notifyWhen, 'attention');
  assert.equal(r.enabled, true);
  assert.equal(r.timeoutSec, 900, 'not the 2h global cap');
  assert.deepEqual(r.runs, []);
});

test('a schedule that would fire wrong is refused with a reason', async () => {
  for (const schedule of [{ kind: 'weekly', days: [], at: '19:00', tz: LA },
    { kind: 'daily', at: '7:00', tz: LA },
    { kind: 'daily', at: '07:00', tz: 'Mars/Olympus' },
    { kind: 'nope' }]) {
    const { status, body } = await api('/v1/rounds', {
      method: 'POST', body: JSON.stringify({ title: 't', prompt: 'p', schedule }),
    });
    assert.equal(status, 400, JSON.stringify(schedule));
    assert.match(body.error, /schedule/);
  }
});

test('a Round needs a title and a prompt', async () => {
  const a = await api('/v1/rounds', { method: 'POST', body: JSON.stringify({ prompt: 'p', schedule: SUNDAY_7PM }) });
  assert.equal(a.status, 400);
  assert.match(a.body.error, /title/);
  const b = await api('/v1/rounds', { method: 'POST', body: JSON.stringify({ title: 't', schedule: SUNDAY_7PM }) });
  assert.equal(b.status, 400);
  assert.match(b.body.error, /prompt/);
});

test('Rounds list soonest first', async () => {
  const { status, body } = await api('/v1/rounds');
  assert.equal(status, 200);
  assert.ok(body.rounds.length >= 1);
  const times = body.rounds.map((r) => r.nextRunAt || Infinity);
  assert.deepEqual(times, [...times].sort((a, b) => a - b));
});

test('changing the cadence re-arms immediately', async () => {
  const r = await mkRound({ title: 'recadence' });
  const { status, body } = await api(`/v1/rounds/${r.id}`, {
    method: 'PATCH', body: JSON.stringify({ schedule: { kind: 'interval', everyMinutes: 240 } }),
  });
  assert.equal(status, 200);
  assert.equal(body.cadence, 'Every 4 hours');
  assert.notEqual(body.nextRunAt, r.nextRunAt, 'the old slot must not survive the edit');
  assert.ok(body.nextRunAt <= Date.now() + 240 * 60_000 + 5000);
});

test('disabling holds the slot; re-enabling arms from now', async () => {
  const r = await mkRound({ title: 'toggle' });
  const off = await api(`/v1/rounds/${r.id}`, { method: 'PATCH', body: JSON.stringify({ enabled: false }) });
  assert.equal(off.body.enabled, false);
  assert.equal(off.body.nextRunAt, r.nextRunAt, 'left where it was');

  const on = await api(`/v1/rounds/${r.id}`, { method: 'PATCH', body: JSON.stringify({ enabled: true }) });
  assert.equal(on.body.enabled, true);
  assert.ok(on.body.nextRunAt > Date.now());
});

test('a bad patch changes nothing', async () => {
  const r = await mkRound({ title: 'badpatch' });
  const bad = await api(`/v1/rounds/${r.id}`, { method: 'PATCH', body: JSON.stringify({ title: '   ' }) });
  assert.equal(bad.status, 400);
  const { body } = await api(`/v1/rounds/${r.id}`);
  assert.equal(body.title, 'badpatch');
});

// -------------------------------------------------------------- a whole run

test('Run now fires, reports, and records what came back', async () => {
  const r = await mkRound({ title: 'run now', prompt: 'Check things. EMIT_STATUS:attention' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  assert.equal(fired.status, 202);
  assert.ok(fired.body.chatId);

  const done = await waitForRun(r.id);
  assert.equal(done.lastRun.status, 'attention');
  assert.equal(done.lastRun.headline, 'stub report for attention');
  assert.equal(done.lastRun.items.length, 1);
  assert.equal(done.lastRun.items[0].suggest, 'do the thing', 'the next step survives the round trip');
  assert.equal(done.lastRun.malformed, false);
  assert.equal(done.lastRun.manual, true);
  assert.equal(done.lastRun.chatId, fired.body.chatId);
  assert.equal(done.runs.length, 1, 'and it is in the history');
  assert.equal(done.currentChatId, null, 'the slot is released');
});

test('a run that produces no block is still reported, marked malformed', async () => {
  // The failure that matters: a Round whose contract broke must not look like a
  // clean week, because nobody goes looking for a report they were never told
  // was missing.
  const r = await mkRound({ title: 'prose', prompt: 'Say something. EMIT_PROSE' });
  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const done = await waitForRun(r.id);
  assert.equal(done.lastRun.status, 'unknown');
  assert.equal(done.lastRun.malformed, true);
  assert.match(done.lastRun.headline, /seems fine/, 'quotes what it actually said');
});

test('an unparseable block is malformed, not silently dropped', async () => {
  const r = await mkRound({ title: 'broken', prompt: 'Break it. EMIT_BROKEN' });
  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const done = await waitForRun(r.id);
  assert.equal(done.lastRun.status, 'unknown');
  assert.equal(done.lastRun.malformed, true);
  assert.ok(!done.lastRun.headline.includes('```'), 'and does not echo a fence at the reader');
});

test("a Round's run is not one of the owner's conversations", async () => {
  const r = await mkRound({ title: 'hidden', prompt: 'Check. EMIT_STATUS:ok' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  await waitForRun(r.id);

  const list = await api('/v1/chats');
  assert.equal(list.body.chats.some((c) => c.id === fired.body.chatId), false,
    'listing it would announce every scheduled run twice');

  // Still openable by id, which is how "show me that run" works.
  const chat = await api(`/v1/chats/${fired.body.chatId}`);
  assert.equal(chat.status, 200);
  assert.equal(chat.body.roundId, r.id);
  assert.equal(chat.body.roundStatus, 'ok');
  assert.match(chat.body.title, /^hidden · \d{4}-\d{2}-\d{2}$/);
});

test('a second run cannot start while the first is going', async () => {
  const r = await mkRound({ title: 'overlap', prompt: 'Check. EMIT_STATUS:ok' });
  const first = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  assert.equal(first.status, 202);
  const second = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  // Either the first was still in flight (409) or it had already finished, in
  // which case a second run is legitimate. Both are correct; a 500 is not.
  assert.ok([202, 409].includes(second.status), `got ${second.status}`);
  await waitForRun(r.id);
});

test('a goal is carried into the run and answered', async () => {
  const r = await mkRound({
    title: 'goal hit',
    prompt: 'Do the thing. EMIT_STATUS:ok EMIT_GOAL_HIT',
    goal: 'the thing is done',
  });
  assert.equal(r.goal, 'the thing is done');

  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const done = await waitForRun(r.id);
  assert.equal(done.lastRun.goalMet, true);
  assert.equal(done.lastRun.status, 'ok');
});

test('a run that admits it missed its goal is not reported as a clean week', async () => {
  // The whole point: "ok" plus "I did not finish" must not read as fine.
  const r = await mkRound({
    title: 'goal miss',
    prompt: 'Try. EMIT_STATUS:ok EMIT_GOAL_MISS',
    goal: 'the thing is done',
  });
  await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  const done = await waitForRun(r.id);
  assert.equal(done.lastRun.goalMet, false);
  assert.equal(done.lastRun.status, 'attention', 'promoted');
  assert.equal(done.lastRun.reportedStatus, 'ok', 'and what it actually claimed is still visible');
});

test("a finished run is SEALED: kept for review, closed to new messages", async () => {
  const r = await mkRound({ title: 'sealed', prompt: 'Check. EMIT_STATUS:ok' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  await waitForRun(r.id);
  const chatId = fired.body.chatId;

  // Readable — that is what "available for review" means.
  const open = await api(`/v1/chats/${chatId}`);
  assert.equal(open.status, 200);
  assert.equal(open.body.sealed, true);
  assert.ok(open.body.endedAt > 0);
  assert.ok(open.body.messages.length > 0, 'the conversation is still there');

  // But not continuable. Without this, "auto end" is a label on a row.
  const more = await api(`/v1/chats/${chatId}/messages`, {
    method: 'POST', body: JSON.stringify({ text: 'and another thing' }),
  });
  assert.equal(more.status, 409);
  assert.match(more.body.error, /review/);
});

test('an ordinary chat is never sealed', async () => {
  // The seal belongs to Rounds. A conversation the owner started stays open.
  const { body } = await api('/v1/chats', { method: 'POST', body: JSON.stringify({ mode: 'ask' }) });
  const open = await api(`/v1/chats/${body.id}`);
  assert.ok(!open.body.sealed);
});

test('deleting a Round leaves the reports it already produced', async () => {
  const r = await mkRound({ title: 'doomed', prompt: 'Check. EMIT_STATUS:ok' });
  const fired = await api(`/v1/rounds/${r.id}/run`, { method: 'POST' });
  await waitForRun(r.id);

  assert.equal((await api(`/v1/rounds/${r.id}`, { method: 'DELETE' })).status, 200);
  assert.equal((await api(`/v1/rounds/${r.id}`)).status, 404);
  assert.equal((await api(`/v1/chats/${fired.body.chatId}`)).status, 200,
    'the run it already produced is not destroyed with the schedule');
});

test('an unknown round id is a 404, not a crash', async () => {
  const id = crypto.randomUUID();
  assert.equal((await api(`/v1/rounds/${id}`)).status, 404);
  assert.equal((await api(`/v1/rounds/${id}/run`, { method: 'POST' })).status, 404);
  assert.equal((await api('/v1/rounds/not-a-uuid')).status, 404);
});
