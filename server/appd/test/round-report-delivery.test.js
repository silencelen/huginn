'use strict';
// A Round's report, and its "did not run" notice, go to the alert relay whenever
// the push reached no device — whether or not an app has checked in lately.
//
// They used to be held whenever any app had checked in recently (a phone's
// ten-minute alarm, an open desktop). That hold is right for alerts, which the
// phone's own watch baseline also sees; nothing shows a Round report without a
// push, so with no FCM key (every self-hosted install), an FCM outage or a dead
// token, a report that needed the owner reached nobody at all.
//
// The daemon's own deliverRoundReport and recordSkippedRound, lifted into a
// sandbox with the real lib/clients deciding "online".

const { test } = require('node:test');
const assert = require('node:assert');
const roundsLib = require('../lib/rounds');
const clientsLib = require('../lib/clients');
const { GLYPH } = require('../lib/state-table');
const { lift } = require('./appd-lift');

const ROUND = { id: 'r1', title: 'PBS check', notifyWhen: 'attention' };
const ACTION = {
  status: 'action', headline: 'PBS sync failing 6 days', goalMet: false, malformed: false,
  items: [{ title: 'sync job s-1 failing', detail: '', suggest: 'check the remote' }], itemsTotal: 1,
};

/** Delivery with this many devices reached by push and these clients checked in. */
function delivery({ sent, clients = [] }) {
  const now = Date.now();
  const clientState = clientsLib.emptyState();
  for (const c of clients) clientsLib.noteSeen(clientState, c.id, c.info, now - c.agoMs);
  const telegram = [];
  const { fns } = lift(['deliverRoundReport', 'recordSkippedRound'], {
    log: () => {}, roundsLib, clientsLib, clientState, GLYPH,
    deliverPush: async () => ({ sent }),
    deliverTelegram: async (text) => { telegram.push(text); return true; },
    loadRound: () => ROUND,
    pushRoundRun: () => {},
  }, { constants: ['ROUND_MARK', 'ROUND_WORD'] });
  return { ...fns, telegram, clientState };
}

const settle = () => new Promise((r) => setTimeout(r, 20));
const PHONE_ALARM = { id: 'phone', info: { kind: 'alarm', notify: true }, agoMs: 9 * 60_000 };
const DESKTOP_STREAM = { id: 'desk', info: { kind: 'stream', notify: true }, agoMs: 20_000 };

test('a report the push delivered to nobody reaches the relay, though the phone checked in', async () => {
  const d = delivery({ sent: 0, clients: [PHONE_ALARM] });
  assert.equal(clientsLib.appOnline(d.clientState, Date.now()), true, 'the premise: the phone counts as online');
  await d.deliverRoundReport(ROUND, ACTION, 'c1');
  assert.equal(d.telegram.length, 1, 'held off the relay because an app had checked in');
  assert.match(d.telegram[0], /PBS check/);
  assert.match(d.telegram[0], /PBS sync failing 6 days/);
});

test('an open desktop does not hold a report either', async () => {
  const d = delivery({ sent: 0, clients: [DESKTOP_STREAM] });
  await d.deliverRoundReport(ROUND, ACTION, 'c1');
  assert.equal(d.telegram.length, 1);
});

test('a "did not run" notice reaches the relay the same way', async () => {
  const d = delivery({ sent: 0, clients: [PHONE_ALARM, DESKTOP_STREAM] });
  d.recordSkippedRound('r1', 'LAPTOP is asleep');
  await settle();
  assert.equal(d.telegram.length, 1);
  assert.match(d.telegram[0], /did not run: LAPTOP is asleep/);
});

test('a push that reached a device still holds the relay, so nothing arrives twice', async () => {
  const d = delivery({ sent: 1, clients: [PHONE_ALARM] });
  await d.deliverRoundReport(ROUND, ACTION, 'c1');
  d.recordSkippedRound('r1', 'LAPTOP is asleep');
  await settle();
  assert.equal(d.telegram.length, 0);
});

test('with no push and no client, the relay carries it (unchanged)', async () => {
  const d = delivery({ sent: 0 });
  await d.deliverRoundReport(ROUND, ACTION, 'c1');
  assert.equal(d.telegram.length, 1);
});
