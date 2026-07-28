'use strict';
// These rules decide when somebody's phone buzzes, so the tests are mostly about
// NOT sending: an alert that repeats, or fires for something that did not happen,
// gets the whole channel muted and then the useful ones are gone too.

const { test } = require('node:test');
const assert = require('node:assert');
const { decideAlerts, pruneSent, REPEAT_MS } = require('../lib/alerts');

const NOW = 1_700_000_000_000;
const obs = (sessions, chats = {}) => ({ sessions, chats });

test('a session moving into needing-you alerts once', () => {
  const { alerts } = decideAlerts(
    obs({ andrev: 'running' }), obs({ andrev: 'attention' }), {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_attention');
  assert.strictEqual(alerts[0].title, 'andrev needs you');
});

test('a session that was ALREADY waiting does not alert again', () => {
  // It has not become newsworthy just because we looked again.
  const { alerts } = decideAlerts(
    obs({ andrev: 'attention' }), obs({ andrev: 'attention' }), {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('the first observation never alerts', () => {
  // Otherwise switching alerts on announces everything already true, which is
  // news about the past.
  const { alerts } = decideAlerts(null, obs({ a: 'attention' }, { c1: { running: false } }), {}, NOW);
  assert.deepStrictEqual(alerts, []);
});

test('a finished chat alerts, and is named', () => {
  const { alerts } = decideAlerts(
    obs({}, { c1: { running: true, title: 'Audit the arr stack' } }),
    obs({}, { c1: { running: false, title: 'Audit the arr stack' } }),
    {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'chat_finished');
  assert.ok(alerts[0].text.includes('Audit the arr stack'));
});

test('a chat still running does not alert', () => {
  const { alerts } = decideAlerts(
    obs({}, { c1: { running: true } }), obs({}, { c1: { running: true } }), {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('a chat that appeared already finished does not alert', () => {
  // Seen for the first time with running:false says nothing about a transition.
  const { alerts } = decideAlerts(obs({}, {}), obs({}, { c1: { running: false } }), {}, NOW);
  assert.deepStrictEqual(alerts, []);
});

test('the same subject is not repeated inside the quiet window', () => {
  const sent = { 'session:andrev': NOW - 60_000 };
  const { alerts } = decideAlerts(
    obs({ andrev: 'running' }), obs({ andrev: 'attention' }), sent, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('the same subject may alert again once the window has passed', () => {
  const sent = { 'session:andrev': NOW - REPEAT_MS - 1 };
  const { alerts } = decideAlerts(
    obs({ andrev: 'running' }), obs({ andrev: 'attention' }), sent, NOW,
  );
  assert.strictEqual(alerts.length, 1);
});

test('sending records the time so the next look is suppressed', () => {
  const { alerts, sentUpdates } = decideAlerts(
    obs({ a: 'running' }), obs({ a: 'attention' }), {}, NOW,
  );
  assert.strictEqual(sentUpdates[alerts[0].key], NOW);
});

test('several transitions at once each produce their own alert', () => {
  const { alerts } = decideAlerts(
    obs({ a: 'running', b: 'idle' }, { c1: { running: true } }),
    obs({ a: 'attention', b: 'attention' }, { c1: { running: false } }),
    {}, NOW,
  );
  assert.strictEqual(alerts.length, 3);
  assert.deepStrictEqual(
    alerts.map((x) => x.kind).sort(),
    ['chat_finished', 'session_attention', 'session_attention'],
  );
});

test('a session going quiet or disappearing is not an alert', () => {
  const { alerts } = decideAlerts(
    obs({ a: 'attention' }), obs({ a: 'idle' }), {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
  assert.deepStrictEqual(decideAlerts(obs({ a: 'attention' }), obs({}), {}, NOW).alerts, []);
});

test('pruning forgets entries too old to suppress anything', () => {
  const kept = pruneSent({ recent: NOW - 1000, ancient: NOW - REPEAT_MS * 3 }, NOW);
  assert.deepStrictEqual(Object.keys(kept), ['recent']);
});
