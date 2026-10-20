'use strict';
// A chat created and finished between two alert ticks is still announced when the
// tick that saw it could not deliver: the retry after a failed send covers it too.
//
// When an alert reaches nobody (push to no device, relay refused), the tick rolls
// its subject back so the next tick decides it again. For a chat born since the
// last tick there was nothing to roll back to, so it was deleted from the saved
// observation — and the next tick, with prevAt stamped after the chat's createdAt,
// read it as history. A quick `huginn ask` during a WAN blip was never announced.
//
// The daemon's own alertTickInner, lifted into a sandbox with the real lib/alerts,
// lib/watch and lib/clients, a fake clock, and a relay that is down for one tick.

const { test } = require('node:test');
const assert = require('node:assert');
const alertsLib = require('../lib/alerts');
const clientsLib = require('../lib/clients');
const { digest } = require('../lib/watch');
const { lift } = require('./appd-lift');

const T0 = 1_760_000_000_000;

function watcher() {
  const clock = { ms: T0 };
  class FakeDate extends Date { static now() { return clock.ms; } }
  let saved = { enabled: true, sent: {}, prev: null, delivered: 0, lastAt: null };
  const world = { chats: [], relayUp: true, relayed: [] };
  const copy = (o) => JSON.parse(JSON.stringify(o));
  const { fns } = lift(['alertTickInner'], {
    Date: FakeDate,
    log: () => {},
    listSessions: async () => [],
    chatStates: () => world.chats,
    headroomFacts: () => ({}),
    digest,
    decideAlerts: alertsLib.decideAlerts,
    routeAlerts: alertsLib.routeAlerts,
    telegramText: alertsLib.telegramText,
    pruneSent: alertsLib.pruneSent,
    carryRunStarts: alertsLib.carryRunStarts,
    captureScreen: async () => null,
    promptFor: () => ({ prompt: null }),
    deliverPush: async () => ({ sent: 0 }),          // push unconfigured, or no device registered
    clientsLib,
    clientState: clientsLib.emptyState(),            // and no app has checked in
    deliverTelegram: async (text) => {
      if (!world.relayUp) return false;
      world.relayed.push(text);
      return true;
    },
    loadAlertState: () => copy(saved),
    saveAlertState: (st) => { saved = copy(st); },
  });
  // As alertTick runs it: load the saved state, then the tick.
  world.tick = async (atMs) => { clock.ms = atMs; await fns.alertTickInner(copy(saved)); };
  return world;
}

const chat = (id, { createdMs, finishedRuns, running = false }) => ({
  id, title: `chat ${id}`, running, pending: 0, finishedRuns,
  snippet: `${id}: all done`, createdAt: Math.floor(createdMs / 1000),
});

test('a chat born and finished inside a failed tick is announced on the next one', async () => {
  const w = watcher();
  await w.tick(T0);                                        // baseline: nothing yet
  w.chats = [chat('c1', { createdMs: T0 + 3000, finishedRuns: 1 })];
  w.relayUp = false;
  await w.tick(T0 + 10_000);                               // the WAN blip
  assert.equal(w.relayed.length, 0);
  w.relayUp = true;
  await w.tick(T0 + 20_000);
  assert.equal(w.relayed.length, 1, 'the finish was never announced on any channel');
  assert.match(w.relayed[0], /c1: all done/);
  await w.tick(T0 + 30_000);
  assert.equal(w.relayed.length, 1, 'and announced once, not on every tick after');
});

test('a chat that existed before the failed tick is retried, as it always was', async () => {
  const w = watcher();
  w.chats = [chat('c2', { createdMs: T0 - 60_000, finishedRuns: 0, running: true })];
  await w.tick(T0);
  w.chats = [chat('c2', { createdMs: T0 - 60_000, finishedRuns: 1 })];
  w.relayUp = false;
  await w.tick(T0 + 10_000);
  w.relayUp = true;
  await w.tick(T0 + 20_000);
  assert.equal(w.relayed.length, 1);
  assert.match(w.relayed[0], /c2: all done/);
});

test('a newborn chat that finishes again before the retry is announced for its latest run', async () => {
  const w = watcher();
  await w.tick(T0);
  w.chats = [chat('c3', { createdMs: T0 + 2000, finishedRuns: 1 })];
  w.relayUp = false;
  await w.tick(T0 + 10_000);
  w.chats = [chat('c3', { createdMs: T0 + 2000, finishedRuns: 2 })];
  w.relayUp = true;
  await w.tick(T0 + 20_000);
  assert.equal(w.relayed.length, 1);
});
