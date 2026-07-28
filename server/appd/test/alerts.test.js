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

// ------------------------------------------------------------------- routing
//
// Routing is tested apart from deciding because they answer different questions:
// deciding is about huginn, routing is about which of your devices is reachable.

const { routeAlerts } = require('../lib/alerts');

const A = [{ key: 'k1', kind: 'chat_finished', subject: 'c1' }];

test('fallback mode delivers when no phone has checked in', () => {
  const r = routeAlerts(A, { mode: 'fallback', appOnline: false });
  assert.equal(r.deliver.length, 1);
  assert.equal(r.held.length, 0);
});

test('fallback mode holds back when the app is reachable', () => {
  const r = routeAlerts(A, { mode: 'fallback', appOnline: true });
  assert.equal(r.deliver.length, 0);
  assert.equal(r.held.length, 1, 'held, so the silence can be explained later');
});

test('always mode delivers even when the app is reachable', () => {
  assert.equal(routeAlerts(A, { mode: 'always', appOnline: true }).deliver.length, 1);
});

test('off mode delivers nothing either way', () => {
  assert.equal(routeAlerts(A, { mode: 'off', appOnline: false }).deliver.length, 0);
  assert.equal(routeAlerts(A, { mode: 'off', appOnline: true }).deliver.length, 0);
});

test('fallback is the default when no mode is given', () => {
  assert.equal(routeAlerts(A, { appOnline: true }).deliver.length, 0);
  assert.equal(routeAlerts(A, {}).deliver.length, 1);
  assert.equal(routeAlerts(A).deliver.length, 1);
});

test('routing never invents an alert', () => {
  for (const mode of ['off', 'fallback', 'always']) {
    for (const online of [true, false]) {
      const r = routeAlerts([], { mode, appOnline: online });
      assert.equal(r.deliver.length + r.held.length, 0, `${mode}/${online}`);
    }
  }
});

// ------------------------------------------- finishes that the timer would miss
//
// Regression, from a measured failure: a chat that ran for five seconds against a
// ten-second alert tick was never observed in the `running` state, so the edge
// never appeared and no finish was ever reported.

test('a chat that began and ended between two observations still alerts', () => {
  const prev = { sessions: {}, chats: { c1: { running: false, finishedRuns: 0 } } };
  // Never seen running: only the counter records that anything happened.
  const next = { sessions: {}, chats: { c1: { running: false, finishedRuns: 1 } } };
  const { alerts } = decideAlerts(prev, next, {}, 1000);
  assert.equal(alerts.length, 1);
  assert.equal(alerts[0].kind, 'chat_finished');
});

test('back-to-back runs alert even though running never dips', () => {
  const prev = { sessions: {}, chats: { c1: { running: true, finishedRuns: 1 } } };
  const next = { sessions: {}, chats: { c1: { running: true, finishedRuns: 2 } } };
  assert.equal(decideAlerts(prev, next, {}, 1000).alerts.length, 1);
});

test('an unchanged counter with no edge stays silent', () => {
  const prev = { sessions: {}, chats: { c1: { running: true, finishedRuns: 3 } } };
  const next = { sessions: {}, chats: { c1: { running: true, finishedRuns: 3 } } };
  assert.equal(decideAlerts(prev, next, {}, 1000).alerts.length, 0);
});

// Two genuine finishes are two events. Keying suppression on the chat alone would
// swallow the second one for half an hour.
test('a later finish of the same chat is not suppressed as a repeat', () => {
  const first = decideAlerts(
    { sessions: {}, chats: { c1: { running: true, finishedRuns: 0 } } },
    { sessions: {}, chats: { c1: { running: false, finishedRuns: 1 } } },
    {}, 1000,
  );
  assert.equal(first.alerts.length, 1);
  const second = decideAlerts(
    { sessions: {}, chats: { c1: { running: true, finishedRuns: 1 } } },
    { sessions: {}, chats: { c1: { running: false, finishedRuns: 2 } } },
    first.sentUpdates, 2000,                       // well inside the repeat window
  );
  assert.equal(second.alerts.length, 1, 'a second finish is a second event');
});

// ...but a retry of the SAME finish must not double-send.
test('re-observing one finish does not alert twice', () => {
  const prev = { sessions: {}, chats: { c1: { running: true, finishedRuns: 0 } } };
  const next = { sessions: {}, chats: { c1: { running: false, finishedRuns: 1 } } };
  const first = decideAlerts(prev, next, {}, 1000);
  const again = decideAlerts(prev, next, first.sentUpdates, 2000);
  assert.equal(again.alerts.length, 0);
});

test('a chat with no counter at all still alerts on the edge', () => {
  const prev = { sessions: {}, chats: { c1: { running: true } } };
  const next = { sessions: {}, chats: { c1: { running: false } } };
  assert.equal(decideAlerts(prev, next, {}, 1000).alerts.length, 1);
});

// ------------------------------------------------------------ Telegram wording
//
// The standing rule for this channel is statements only — huginn must never ask the
// owner something down a path that carries no reply. Quoting what a SESSION is asking
// is a status report, not huginn asking, and these tests pin that shape down.

const { telegramText } = require('../lib/alerts');

test('a question is reported, with its options listed', () => {
  const text = telegramText({
    kind: 'session_attention',
    title: 'jtyper needs you',
    text: 'ignored when a question is present',
    question: 'Do you want to create jtyper.md?',
    options: [{ number: 1, label: 'Yes' }, { number: 2, label: 'No' }],
  });
  assert.match(text, /jtyper needs you/);
  assert.match(text, /^Asked: Do you want to create jtyper\.md\?$/m,
    'framed as a report, not put to the reader');
  assert.match(text, /^1\) Yes$/m);
  assert.match(text, /^2\) No$/m);
});

test('an attention alert with no parsed question keeps its plain wording', () => {
  const text = telegramText({
    kind: 'session_attention',
    title: 'andrev needs you',
    text: 'Claude Code session andrev is waiting for an answer.',
  });
  assert.match(text, /waiting for an answer/);
  assert.doesNotMatch(text, /Asked:/);
});

test('a chat finishing is unaffected by any of this', () => {
  const text = telegramText({ kind: 'chat_finished', title: 'Chat finished', text: 'huginn finished: x' });
  assert.match(text, /Chat finished/);
  assert.match(text, /huginn finished: x/);
  assert.doesNotMatch(text, /Asked:/);
});

test('a question with no options still reports the question', () => {
  const text = telegramText({
    kind: 'session_attention', title: 't needs you', text: 'x',
    question: 'Continue?', options: [],
  });
  assert.match(text, /Asked: Continue\?/);
  // No trailing blank section where the options would have gone.
  assert.doesNotMatch(text, /\n\n/);
});
