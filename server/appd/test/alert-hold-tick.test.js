'use strict';
// The alert tick under a hold: it decides as it always does and says nothing,
// and the first tick after the hold sends what of it is still true.
//
// A display that mirrors a phone's notifications asks for a hold while it is
// showing Huginn itself (lib/alerthold.js, POST /v1/alerts/hold). The tick must
// not stop LOOKING while one is live: an edge it did not consume would be decided
// again later, and a run that began and ended inside the hold would never be seen
// at all. So the deciding is untouched and only the saying waits, in `held` of the
// alert state, which a restart keeps.
//
// The daemon's own alertTickInner, lifted into a sandbox with the real lib/alerts,
// lib/alerthold, lib/watch and lib/clients, a fake clock, and a push and a relay
// that record what they were given.

const { test } = require('node:test');
const assert = require('node:assert');
const alertsLib = require('../lib/alerts');
const alertHoldLib = require('../lib/alerthold');
const clientsLib = require('../lib/clients');
const questionsLib = require('../lib/questions');
const { digest } = require('../lib/watch');
const { lift } = require('./appd-lift');

const T0 = 1_760_000_000_000;
const MIN = 60_000;

function watcher({ lib = true } = {}) {
  const clock = { ms: T0 };
  class FakeDate extends Date { static now() { return clock.ms; } }
  let saved = { enabled: true, mode: 'always', sent: {}, prev: null, delivered: 0, lastAt: null };
  const world = { sessions: [], chats: [], up: true, pushed: [], relayed: [], logs: [], duringTick: null };
  const copy = (o) => JSON.parse(JSON.stringify(o));
  const stubs = {
    Date: FakeDate,
    restarts: new Map(),
    log: (...a) => { world.logs.push(a.join(' ')); },
    listSessions: async () => {
      if (world.duringTick) { const f = world.duringTick; world.duringTick = null; saved = f(saved); }
      return copy(world.sessions);
    },
    chatStates: () => world.chats,
    chatAsksNow: () => ({}),
    headroomFacts: () => ({}),
    digest,
    decideAlerts: alertsLib.decideAlerts,
    routeAlerts: alertsLib.routeAlerts,
    telegramText: alertsLib.telegramText,
    pruneSent: alertsLib.pruneSent,
    carryRunStarts: alertsLib.carryRunStarts,
    projectSessions: alertsLib.projectSessions,
    carryProjectBursts: alertsLib.carryProjectBursts,
    captureScreen: async () => null,
    questionsFor: () => [],
    questionsLib,
    promptFor: () => ({ prompt: null }),
    deliverPush: async (a) => {
      if (!world.up) return { sent: 0 };
      world.pushed.push(copy(a));
      return { sent: 1 };
    },
    clientsLib,
    clientState: clientsLib.emptyState(),
    deliverTelegram: async (text) => { if (!world.up) return false; world.relayed.push(text); return true; },
    loadAlertState: () => copy(saved),
    saveAlertState: (st) => { saved = copy(st); },
  };
  // Left out on request: a tick with no hold and nothing waiting must not need it.
  if (lib) stubs.alertHoldLib = alertHoldLib;
  const { fns } = lift(['alertTickInner'], stubs);
  // As alertTick runs it: load the saved state, then the tick, with the live hold or none.
  world.tick = async (atMs, hold = null) => { clock.ms = atMs; await fns.alertTickInner(copy(saved), hold); };
  world.held = () => saved.held || [];
  world.said = () => world.pushed.map((a) => `${a.kind}:${a.subject}`);
  return world;
}

const holding = (from, seconds = 3600) => ({ since: from, until: from + seconds * 1000, by: 'test display' });
const row = (name, state) => ({ name, state, attachedClients: 0, project: null });
const chat = (id, { createdMs, finishedRuns, running = false }) => ({
  id, title: `chat ${id}`, running, pending: 0, finishedRuns,
  snippet: `${id}: all done`, createdAt: Math.floor(createdMs / 1000),
});

test('with no hold and nothing waiting, the tick is the one it always was', async () => {
  // No hold library in reach at all: naming it would throw.
  const w = watcher({ lib: false });
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000);
  assert.deepEqual(w.said(), ['chat_finished:c1']);
  assert.equal(w.relayed.length, 1);
  assert.deepEqual(w.held(), []);
});

test('during a hold the tick decides and says nothing; after it, what waited is said once', async () => {
  const w = watcher();
  const h = holding(T0 + 5000);
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, h);
  assert.deepEqual([w.pushed.length, w.relayed.length], [0, 0], 'nothing on any channel while the hold is live');
  assert.deepEqual(w.held().map((a) => [a.kind, a.subject, a.heldAt]), [['chat_finished', 'c1', T0 + 10_000]]);
  assert.ok(w.logs.includes('alerts: holding chat_finished for c1 (test display)'), w.logs.join('\n'));

  // The edge was consumed: a second tick under the hold decides nothing new.
  await w.tick(T0 + 20_000, h);
  assert.equal(w.held().length, 1);
  assert.equal(w.pushed.length, 0);

  await w.tick(T0 + 30_000);
  assert.deepEqual(w.said(), ['chat_finished:c1']);
  assert.equal(w.relayed.length, 1);
  assert.match(w.relayed[0], /c1: all done/);
  assert.ok(!('heldAt' in w.pushed[0]), 'sent as a fresh alert would be');
  assert.deepEqual(w.held(), []);

  await w.tick(T0 + 40_000);
  assert.equal(w.pushed.length, 1, 'and not again on the ticks after');
});

test('what waited goes out ahead of what the same tick decided', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, holding(T0));
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 }), chat('c2', { createdMs: T0 + 12_000, finishedRuns: 1 })];
  await w.tick(T0 + 20_000);
  assert.deepEqual(w.said(), ['chat_finished:c1', 'chat_finished:c2']);
});

test('a run that finished under the hold is announced after it, if the session is still idle', async () => {
  const w = watcher();
  w.sessions = [row('build', 'running')];
  await w.tick(T0);
  w.sessions = [row('build', 'idle')];
  await w.tick(T0 + 6 * MIN, holding(T0 + 5 * MIN));
  assert.deepEqual(w.held().map((a) => a.kind), ['session_finished']);
  assert.equal(w.pushed.length, 0);
  await w.tick(T0 + 6 * MIN + 10_000);
  assert.deepEqual(w.said(), ['session_finished:build']);
});

test('and is not, once the session is working again', async () => {
  const w = watcher();
  w.sessions = [row('build', 'running')];
  await w.tick(T0);
  w.sessions = [row('build', 'idle')];
  await w.tick(T0 + 6 * MIN, holding(T0 + 5 * MIN));
  w.sessions = [row('build', 'running')];
  await w.tick(T0 + 6 * MIN + 10_000, holding(T0 + 5 * MIN));
  await w.tick(T0 + 6 * MIN + 20_000);
  assert.deepEqual(w.said(), []);
  assert.deepEqual(w.held(), []);
  assert.ok(w.logs.some((l) => /held session_finished for build is no longer true/.test(l)), w.logs.join('\n'));
});

test('a question asked and answered inside the hold is never mentioned', async () => {
  const w = watcher();
  w.sessions = [row('build', 'running')];
  await w.tick(T0);
  w.sessions = [row('build', 'attention')];
  await w.tick(T0 + 10_000, holding(T0));
  assert.deepEqual(w.held().map((a) => a.kind), ['session_attention']);
  w.sessions = [row('build', 'running')];
  await w.tick(T0 + 20_000, holding(T0));
  assert.deepEqual(w.held(), [], 'the answer took the question out and went with it');
  await w.tick(T0 + 30_000);
  assert.deepEqual(w.said(), []);
});

test('a question still waiting when the hold ends is asked then', async () => {
  const w = watcher();
  w.sessions = [row('build', 'running')];
  await w.tick(T0);
  w.sessions = [row('build', 'attention')];
  await w.tick(T0 + 10_000, holding(T0));
  await w.tick(T0 + 20_000, holding(T0));
  assert.equal(w.pushed.length, 0);
  await w.tick(T0 + 30_000);
  assert.deepEqual(w.said(), ['session_attention:build']);
  await w.tick(T0 + 40_000);
  assert.equal(w.pushed.length, 1, 'once');
});

test('the answer to a question announced before the hold waits for its end', async () => {
  const w = watcher();
  w.sessions = [row('build', 'running')];
  await w.tick(T0);
  w.sessions = [row('build', 'attention')];
  await w.tick(T0 + 10_000);
  assert.deepEqual(w.said(), ['session_attention:build']);
  w.sessions = [row('build', 'running')];
  await w.tick(T0 + 20_000, holding(T0 + 15_000));
  assert.deepEqual(w.said(), ['session_attention:build'], 'not taken down while the phone is told nothing changed');
  assert.deepEqual(w.held().map((a) => a.kind), ['session_resolved']);
  await w.tick(T0 + 30_000);
  assert.deepEqual(w.said(), ['session_attention:build', 'session_resolved:build']);
});

test('a released alert that reaches nobody is kept, and sent by the tick that can', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, holding(T0));
  w.up = false;                                             // no push lands and the relay refuses
  await w.tick(T0 + 20_000);
  assert.deepEqual([w.pushed.length, w.relayed.length], [0, 0]);
  assert.deepEqual(w.held().map((a) => [a.kind, a.heldAt]), [['chat_finished', T0 + 10_000]], 'back in the queue, as it was held');
  assert.ok(w.logs.some((l) => /held chat_finished for c1 reached nobody/.test(l)), w.logs.join('\n'));
  const lines = w.logs.length;
  await w.tick(T0 + 30_000);
  assert.equal(w.held().length, 1);
  assert.equal(w.logs.filter((l, i) => i >= lines && /reached nobody/.test(l)).length, 1, 'one line a tick, as an edge retry logs');

  w.up = true;
  await w.tick(T0 + 40_000);
  assert.deepEqual(w.said(), ['chat_finished:c1']);
  assert.deepEqual(w.held(), []);
  await w.tick(T0 + 50_000);
  assert.equal(w.pushed.length, 1);
});

test('kept only while it is still true', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, holding(T0));
  w.up = false;
  await w.tick(T0 + 20_000);
  assert.equal(w.held().length, 1);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1, running: true })];   // asked again
  await w.tick(T0 + 30_000);
  assert.deepEqual(w.held(), []);
  w.up = true;
  await w.tick(T0 + 40_000);
  assert.deepEqual(w.said(), []);
});

test('a hold that begins while something still waits keeps it waiting', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, holding(T0));
  w.up = false;
  await w.tick(T0 + 20_000);                                // released, reached nobody, kept
  w.up = true;
  await w.tick(T0 + 30_000, holding(T0 + 25_000));          // a new hold before the retry
  assert.equal(w.pushed.length, 0);
  assert.equal(w.held().length, 1);
  await w.tick(T0 + 40_000);
  assert.deepEqual(w.said(), ['chat_finished:c1']);
});

test('alerts switched off in the middle of a tick: the queue is not brought back', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  await w.tick(T0 + 10_000, holding(T0));
  assert.equal(w.held().length, 1);
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 }), chat('c2', { createdMs: T0 + 12_000, finishedRuns: 1 })];
  // POST /v1/alerts {enabled: false} lands while the tick is looking: it clears the queue.
  w.duringTick = (saved) => { const s = { ...saved, enabled: false }; delete s.held; return s; };
  await w.tick(T0 + 20_000, holding(T0));
  assert.deepEqual(w.held(), [], 'the tick wrote its queue over the switch');
});
