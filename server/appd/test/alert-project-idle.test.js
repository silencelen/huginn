'use strict';
// A project's sessions reach the phone as ONE project — the PUSH half.
//
// 2026-11-06: a project's sessions hand work to one another, so each went on and
// off all through one piece of work and every member's long turn ending was its
// own "finished" push. alerts.test.js holds the rules; this file holds what the
// daemon's own alertTickInner then hands the push transport and Telegram: lifted
// into a sandbox with the real lib/alerts, lib/watch, lib/clients and
// lib/questions, a fake clock, rows tagged the way listSessions tags them, and a
// push transport that records what it is handed. The last hop, the FCM data
// message, goes through the real lib/fcm sender with a throwaway key and a fetch
// that records instead of sending.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const alertsLib = require('../lib/alerts');
const clientsLib = require('../lib/clients');
const questionsLib = require('../lib/questions');
const { FcmSender } = require('../lib/fcm');
const { GLYPH } = require('../lib/state-table');
const { digest } = require('../lib/watch');
const { lift } = require('./appd-lift');

const T0 = 1_760_000_000_000;
const MIN = 60_000;
const GARDEN = { id: 'p-1f2e3d', name: 'Garden', slug: 'garden' };

function watcher() {
  const clock = { ms: T0 };
  class FakeDate extends Date { static now() { return clock.ms; } }
  let saved = { enabled: true, mode: 'always', sent: {}, prev: null, delivered: 0, lastAt: null };
  const world = { sessions: [], questions: {}, pushUp: true, pushed: [], relayed: [] };
  const copy = (o) => JSON.parse(JSON.stringify(o));
  const { fns } = lift(['alertTickInner'], {
    Date: FakeDate,
    log: () => {},
    listSessions: async () => copy(world.sessions),
    chatStates: () => [],
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

/** The rows listSessions returns for the garden's three sessions, tagged with their project. */
const garden = (lead, guide, vault) => [['garden-lead', lead, 'lead'], ['garden-guide', guide, 'guide'], ['garden-vault', vault, 'vault']]
  .map(([name, state, role]) => ({ name, state, attachedClients: 0, project: { ...GARDEN, role, lead: role === 'lead' } }));

/** Two sessions of the garden working eleven minutes, then quiet from T0 + 12 min. */
async function burst(w) {
  w.sessions = garden('idle', 'idle', 'idle');
  await w.tick(T0);                                                // baseline
  w.sessions = garden('running', 'running', 'idle');
  await w.tick(T0 + 1 * MIN);
  w.sessions = garden('idle', 'running', 'idle');
  await w.tick(T0 + 8 * MIN);
  w.sessions = garden('idle', 'idle', 'idle');
  await w.tick(T0 + 12 * MIN);
  await w.tick(T0 + 16 * MIN);
}

/** The FCM data message lib/fcm builds for one alert: the real sender, a throwaway key, a fetch that records. */
async function fcmData(alert) {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'huginn-project-idle-'));
  const keyFile = path.join(dir, 'sa.json');
  fs.writeFileSync(keyFile, JSON.stringify({
    type: 'service_account', project_id: 'test-project',
    client_email: 'sender@test-project.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    token_uri: 'https://oauth2.googleapis.com/token',
  }));
  const calls = [];
  const replies = [{ status: 200, body: JSON.stringify({ access_token: 'ya29.test', expires_in: 3600 }) }];
  const fetchImpl = async (url, opts) => {
    calls.push({ url: String(url), opts });
    const r = replies.shift() || { status: 200, body: '{}' };
    return { ok: r.status >= 200 && r.status < 300, status: r.status, text: async () => r.body };
  };
  try {
    const r = await new FcmSender(keyFile).send('device-token', alert, fetchImpl);
    assert.equal(r.ok, true, JSON.stringify(r));
    return JSON.parse(calls[calls.length - 1].opts.body).message.data;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('a project going idle is one project_idle push aimed at the project, and Telegram says it once', async () => {
  const w = watcher();
  await burst(w);
  assert.deepEqual(w.pushed, [], 'no member pushed finished: the lead ran seven minutes, the guide eleven');
  await w.tick(T0 + 17 * MIN);                                     // five minutes of quiet
  assert.equal(w.pushed.length, 1, JSON.stringify(w.pushed));
  const p = w.pushed[0];
  assert.equal(p.kind, 'project_idle');
  assert.equal(p.subject, GARDEN.id);
  assert.equal(p.title, 'Garden is idle');
  assert.equal(p.text, 'Worked 11m; lead, guide ran.');
  assert.deepEqual(p.options, [], 'no buttons');
  assert.deepEqual(p.payload, { projectId: GARDEN.id });
  assert.deepEqual(w.relayed, [`${GLYPH.ok} Garden is idle\nWorked 11m; lead, guide ran.`]);
  // On the wire: what the app reads to open the project.
  const data = await fcmData(p);
  assert.equal(data.kind, 'project_idle');
  assert.equal(data.subject, GARDEN.id);
  assert.equal(data.title, 'Garden is idle');
  assert.equal(data.options, '', 'no buttons on the wire either');
  assert.deepEqual(JSON.parse(data.payload), { projectId: GARDEN.id });
  await w.tick(T0 + 18 * MIN);
  await w.tick(T0 + 40 * MIN);
  assert.equal(w.pushed.length, 1, 'once');
});

test('a project_idle that reached nobody is decided again on the next tick', async () => {
  const w = watcher();
  await burst(w);
  w.pushUp = false;                                                // push and relay both down
  await w.tick(T0 + 17 * MIN);
  assert.equal(w.pushed.length + w.relayed.length, 0);
  w.pushUp = true;
  await w.tick(T0 + 17 * MIN + 10_000);
  assert.deepEqual(w.pushed.map((x) => [x.kind, x.subject, x.text]), [['project_idle', GARDEN.id, 'Worked 11m; lead, guide ran.']],
    'the burst went back open, so the next tick ended it again');
  await w.tick(T0 + 18 * MIN);
  assert.equal(w.pushed.length, 1, 'and once it reached somebody, not again');
  assert.equal(w.relayed.length, 1);
});

test('a project member asking: the push names the project and role, and opens the member', async () => {
  const w = watcher();
  w.sessions = garden('running', 'running', 'idle');
  await w.tick(T0);                                                // baseline
  // A question the registry can describe, so the tick rewrites the text as it does for a real one.
  const q = questionsLib.sessionQuestion({
    v: 1, id: '1760000000000001-4242', pid: 4242, ts: T0, expiresAt: T0 + 60 * MIN, kind: 'session',
    requester: { kind: 'session', id: 'garden-guide' }, directory: '/srv/example', prompt: 'summarise the week', name: null,
  });
  w.questions['garden-guide'] = [q];
  w.sessions = garden('running', 'attention', 'idle');
  await w.tick(T0 + MIN);
  assert.deepEqual(w.pushed.map((x) => [x.kind, x.subject, x.title]), [['session_attention', 'garden-guide', 'Garden · guide needs you']]);
  assert.equal(w.pushed[0].text, 'session garden-guide asks to start a session in /srv/example', 'the text is still the question');
  assert.deepEqual(w.relayed, [`${GLYPH.needsYou} Garden · guide needs you\nAsked: session garden-guide asks to start a session in /srv/example`]);
});
