'use strict';
// Who is told that Claude is waiting on a start_session request — the PUSH half.
//
// 2026-11-03: three requests on one night (two from sessions, one from a chat)
// produced no push at all. A session blocked in the tool call reads
// `running` to the hook, and alerts fire on a transition INTO `attention`; a
// chat has no attention alert of any kind. The session list now reports such a
// session as `attention` (routes-session-request.test.js holds the list, the
// watch digest and the Telegram text to it); this file holds what then reaches
// the phone: the daemon's own alertTickInner, lifted into a sandbox with the
// real lib/alerts, lib/watch, lib/clients and lib/questions, a fake clock, and a
// push transport that records what it is handed.

const { test } = require('node:test');
const assert = require('node:assert');
const alertsLib = require('../lib/alerts');
const clientsLib = require('../lib/clients');
const questionsLib = require('../lib/questions');
const { digest } = require('../lib/watch');
const { lift } = require('./appd-lift');

const T0 = 1_760_000_000_000;
const CHAT = '11111111-2222-4333-8444-555555555555';

function watcher() {
  const clock = { ms: T0 };
  class FakeDate extends Date { static now() { return clock.ms; } }
  let saved = { enabled: true, mode: 'always', sent: {}, prev: null, delivered: 0, lastAt: null };
  const world = { sessions: [], questions: {}, chatAsks: {}, pushUp: true, pushed: [], relayed: [] };
  const copy = (o) => JSON.parse(JSON.stringify(o));
  const { fns } = lift(['alertTickInner'], {
    Date: FakeDate,
    restarts: new Map(),        // no session is being cycled onto a new runtime generation here
    log: () => {},
    listSessions: async () => copy(world.sessions),
    chatStates: () => [],
    chatAsksNow: () => copy(world.chatAsks),
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
    questionsFor: (name) => world.questions[name] || [],
    questionsLib,
    promptFor: () => ({ prompt: null }),
    deliverPush: async (a) => {
      if (!world.pushUp) return { sent: 0 };
      world.pushed.push(copy(a));
      return { sent: 1 };
    },
    clientsLib,
    clientState: clientsLib.emptyState(),
    deliverTelegram: async (text) => { if (!world.pushUp) return false; world.relayed.push(text); return true; },
    loadAlertState: () => copy(saved),
    saveAlertState: (st) => { saved = copy(st); },
  });
  world.tick = async (atMs) => { clock.ms = atMs; await fns.alertTickInner(copy(saved)); };
  return world;
}

/** A parked start_session request as lib/sessionreq writes it. */
const request = (requester, id) => ({
  v: 1, id, pid: 4242, ts: T0, expiresAt: T0 + 60 * 60 * 1000, kind: 'session', requester,
  directory: '/srv/example', prompt: 'open the deploy notes and summarise the week', name: null,
});

test('a session waiting on its request: one session_attention push, its text from the registry, no buttons', async () => {
  const w = watcher();
  w.sessions = [{ name: 'planner', state: 'running', attachedClients: 0 }];
  await w.tick(T0);                                                     // baseline
  const q = questionsLib.sessionQuestion(request({ kind: 'session', id: 'planner' }, '1760000000000001-4242'));
  w.questions.planner = [q];
  w.sessions = [{ name: 'planner', state: 'attention', attachedClients: 0 }];   // what the list reports now
  await w.tick(T0 + 10_000);
  assert.equal(w.pushed.length, 1, JSON.stringify(w.pushed));
  const p = w.pushed[0];
  assert.equal(p.kind, 'session_attention');
  assert.equal(p.subject, 'planner');
  assert.equal(p.title, 'planner needs you');
  assert.equal(p.text, 'session planner asks to start a session in /srv/example', 'the describe() line');
  assert.deepEqual(p.options, [], 'no Start, no Decline: the card answers');
  assert.equal(p.fingerprint, q.fingerprint, "the request's content token");
  await w.tick(T0 + 20_000);
  assert.equal(w.pushed.length, 1, 'still waiting is not news');
  w.questions.planner = [];
  w.sessions = [{ name: 'planner', state: 'running', attachedClients: 0 }];     // answered
  await w.tick(T0 + 30_000);
  assert.deepEqual(w.pushed.slice(1).map((x) => x.kind), ['session_resolved'], 'and the phone takes it down');
});

test('a chat waiting on its request: one chat_attention push, retried when it reached nobody', async () => {
  const w = watcher();
  await w.tick(T0);                                                     // baseline
  const q = questionsLib.sessionQuestion(request({ kind: 'chat', id: CHAT }, '1760000000000002-4242'));
  w.chatAsks = { [CHAT]: { title: 'deploy notes', asks: [{ id: q.id, text: questionsLib.describe(q), fingerprint: q.fingerprint }] } };
  w.pushUp = false;                                                     // push and relay both down
  await w.tick(T0 + 10_000);
  assert.equal(w.pushed.length, 0);
  w.pushUp = true;
  await w.tick(T0 + 20_000);
  assert.equal(w.pushed.length, 1, 'the request that reached nobody is decided again');
  const p = w.pushed[0];
  assert.equal(p.kind, 'chat_attention');
  assert.equal(p.subject, CHAT, 'opens the chat');
  assert.equal(p.title, 'deploy notes needs you');
  assert.equal(p.text, 'a chat asks to start a session in /srv/example');
  assert.deepEqual(p.options, []);
  assert.equal(p.fingerprint, q.fingerprint);
  await w.tick(T0 + 30_000);
  await w.tick(T0 + 40_000);
  assert.equal(w.pushed.length, 1, 'once per request, however many ticks see it waiting');
  assert.equal(w.relayed.length, 1, 'Telegram carried it once too');
});
