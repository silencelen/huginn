'use strict';
// These rules decide when somebody's phone buzzes, so the tests are mostly about
// NOT sending: an alert that repeats, or fires for something that did not happen,
// gets the whole channel muted and then the useful ones are gone too.

const { test } = require('node:test');
const assert = require('node:assert');
const { decideAlerts, pruneSent, telegramText, humanDuration, REPEAT_MS } = require("../lib/alerts");

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
  // Named by the chat, so a phone notification reads like a message from it.
  assert.strictEqual(alerts[0].title, 'Audit the arr stack');
  assert.strictEqual(alerts[0].label, 'Audit the arr stack');
});

test('a finished chat carries the answer, not just the fact that it ended', () => {
  const { alerts } = decideAlerts(
    obs({}, { c1: { running: true, title: 'Audit the arr stack' } }),
    obs({}, { c1: { running: false, title: 'Audit the arr stack', snippet: 'Sonarr is on 4.0.10; nothing to do.' } }),
    {}, NOW,
  );
  assert.strictEqual(alerts[0].text, 'Sonarr is on 4.0.10; nothing to do.');
  // Telegram has no app around it to supply the context, so it says the event too.
  const tg = telegramText(alerts[0]);
  assert.ok(tg.startsWith('\u{1F514} Chat finished: Audit the arr stack'), tg);
  assert.ok(tg.includes('Sonarr is on 4.0.10'), tg);
});

test('a finished chat with nothing recorded still says something', () => {
  const { alerts } = decideAlerts(
    obs({}, { c1: { running: true, title: 'Audit the arr stack' } }),
    obs({}, { c1: { running: false, title: 'Audit the arr stack' } }),
    {}, NOW,
  );
  assert.strictEqual(alerts[0].text, 'Finished.');
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

test('a session going quiet is a RESOLUTION, never person-facing news', () => {
  // Changed contract (2026-07-28): these transitions used to emit nothing, which
  // left an answered question's notification stale in the shade. They now emit
  // session_resolved — phone plumbing that the daemon keeps off Telegram (see
  // the `news` filter in alertTickInner).
  for (const after of [obs({ a: 'idle' }), obs({})]) {
    const { alerts } = decideAlerts(obs({ a: 'attention' }), after, {}, NOW);
    assert.deepStrictEqual(alerts.map((x) => x.kind), ['session_resolved']);
  }
  // And a session that was never waiting resolves nothing by going idle.
  assert.deepStrictEqual(decideAlerts(obs({ a: 'running' }), obs({ a: 'idle' }), {}, NOW).alerts, []);
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

// ------------------------------- a chat that lived entirely inside one window
//
// Same class of bug as the finishedRuns counter: an edge sampled on a timer is
// lossy. A chat CREATED and finished between two observations was absent from the
// previous one, so the "do not announce history" rule skipped it and it never
// alerted AT ALL. A one-line question answered in eight seconds is ordinary, and
// it was silently the one thing this could not report.

const BORN = Math.floor((NOW - 5_000) / 1000);   // created 5s ago, epoch seconds

test('a chat born since the last look and already finished DOES alert', () => {
  const { alerts } = decideAlerts(
    obs({}, {}),
    obs({}, { c1: { running: false, finishedRuns: 1, title: 'Quick question', snippet: 'Yes.', createdAt: BORN } }),
    {}, NOW, NOW - 10_000,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'chat_finished');
  assert.strictEqual(alerts[0].text, 'Yes.');
});

test('a chat that predates the last look is still history, not news', () => {
  const old = Math.floor((NOW - 86_400_000) / 1000);
  const { alerts } = decideAlerts(
    obs({}, {}),
    obs({}, { c1: { running: false, finishedRuns: 3, title: 'Yesterday', createdAt: old } }),
    {}, NOW, NOW - 10_000,
  );
  assert.deepStrictEqual(alerts, []);
});

test('a chat born since the last look but still RUNNING does not alert yet', () => {
  const { alerts } = decideAlerts(
    obs({}, {}),
    obs({}, { c1: { running: true, finishedRuns: 0, createdAt: BORN } }),
    {}, NOW, NOW - 10_000,
  );
  assert.deepStrictEqual(alerts, []);
});

test('without a recorded look-time, a new chat is treated as history', () => {
  // Alert state written before prevAt existed. Silence is the safe reading: the
  // alternative is announcing every chat on disk the first time huginn restarts.
  const { alerts } = decideAlerts(
    obs({}, {}),
    obs({}, { c1: { running: false, finishedRuns: 1, createdAt: BORN } }),
    {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('the born-since path respects the quiet window like any other', () => {
  const sent = { 'chat:c1:1': NOW - 60_000 };
  const { alerts } = decideAlerts(
    obs({}, {}),
    obs({}, { c1: { running: false, finishedRuns: 1, createdAt: BORN } }),
    sent, NOW, NOW - 10_000,
  );
  assert.deepStrictEqual(alerts, []);
});

// ------------------------------------------------- a long task finishing
//
// The counterpart to a chat finishing. Gated on DURATION, because a session goes
// idle after every single turn — without the gate this produces a notification per
// exchange, which is the fastest way to get the whole channel muted.

const SINCE = Math.floor((NOW - 9 * 60 * 1000) / 1000);   // running for 9 minutes

const withSince = (sessions, since) => ({ sessions, sessionsSince: since, chats: {} });

test('a session that ran a long time and went idle alerts', () => {
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, { andrev: SINCE }),
    withSince({ andrev: 'idle' }, {}),
    {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_finished');
  assert.strictEqual(alerts[0].title, 'andrev finished');
  assert.ok(alerts[0].text.includes('9m'), alerts[0].text);
});

test('an ordinary short turn going idle says NOTHING', () => {
  // The whole point of the gate. A ten-second answer is not news.
  const shortAgo = Math.floor((NOW - 10_000) / 1000);
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, { andrev: shortAgo }),
    withSince({ andrev: 'idle' }, {}),
    {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('a session with no recorded start time does not alert', () => {
  // Absent a start, the duration is unknowable and silence is the safe reading.
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, {}),
    withSince({ andrev: 'idle' }, {}),
    {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('running to ATTENTION is not a finish', () => {
  // It stopped to ask something. That is the other alert, and reporting both
  // would say "finished" about a session that is actively waiting on you.
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, { andrev: SINCE }),
    withSince({ andrev: 'attention' }, {}),
    {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_attention');
});

test('the same finish is not reported twice', () => {
  const sent = { [`session-done:andrev:${SINCE}`]: NOW - 60_000 };
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, { andrev: SINCE }),
    withSince({ andrev: 'idle' }, {}),
    sent, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('a second long run reports again, because the key moves with it', () => {
  // Started two minutes after the first, so still seven minutes ago: a DIFFERENT
  // suppression key, and long enough to qualify on its own. (Adding an HOUR here
  // first time round put the start in the FUTURE, making the duration negative
  // and the alert correctly silent — the test was wrong, not the rule.)
  const later = SINCE + 120;
  const sent = { [`session-done:andrev:${SINCE}`]: NOW - 60_000 };
  const { alerts } = decideAlerts(
    withSince({ andrev: 'running' }, { andrev: later }),
    withSince({ andrev: 'idle' }, {}),
    sent, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_finished');
});

test('durations read the way a person would say them', () => {
  assert.strictEqual(humanDuration(7 * 60 * 1000), '7m');
  assert.strictEqual(humanDuration(72 * 60 * 1000), '1h 12m');
  assert.strictEqual(humanDuration(120 * 60 * 1000), '2h 0m');
});

// ---------------------------------------------- questions that stopped waiting
//
// Not news for a person — an instruction to the phone to take a stale "needs you"
// notification down. Answered in tmux, from another device, or the session died:
// in every case a notification inviting a tap whose fingerprint will be refused
// is worse than none.

test('a question answered elsewhere emits a resolution', () => {
  const { alerts } = decideAlerts(
    obs({ andrev: 'attention' }), obs({ andrev: 'running' }), {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_resolved');
  assert.strictEqual(alerts[0].subject, 'andrev');
});

test('a killed session also resolves — prev is what is iterated', () => {
  // Gone from `next` entirely; a loop over next would never see it go.
  const { alerts } = decideAlerts(obs({ andrev: 'attention' }), obs({}), {}, NOW);
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_resolved');
});

test('still waiting is not resolved', () => {
  const { alerts } = decideAlerts(
    obs({ andrev: 'attention' }), obs({ andrev: 'attention' }), {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('resolution bypasses the quiet window — edges cannot repeat', () => {
  // The second answered-question of the half hour must not leave its
  // notification stale because the first one "used up" the window.
  const sent = { 'session-resolved:andrev': NOW - 60_000 };
  const { alerts } = decideAlerts(
    obs({ andrev: 'attention' }), obs({ andrev: 'idle' }), sent, NOW,
  );
  assert.strictEqual(alerts.length, 1);
});

test('a NEW question after a resolution alerts immediately', () => {
  // The repeat guard exists to stop the SAME question re-alerting. Once one was
  // answered, the next is genuinely new — resolution clears the guard the way
  // the daemon merges it: sentUpdates over sent, then pruned.
  const asked = { 'session:andrev': NOW - 60_000 };          // alerted a minute ago
  const r = decideAlerts(obs({ andrev: 'attention' }), obs({ andrev: 'running' }), asked, NOW);
  const merged = pruneSent({ ...asked, ...r.sentUpdates }, NOW);
  assert.ok(!('session:andrev' in merged), 'guard cleared by the resolution');
  const again = decideAlerts(
    obs({ andrev: 'running' }), obs({ andrev: 'attention' }), merged, NOW + 120_000,
  );
  assert.strictEqual(again.alerts.length, 1);
  assert.strictEqual(again.alerts[0].kind, 'session_attention');
});

// --------------------------------------------- the run-start ledger
//
// Regression, measured live: the state file's ts is rewritten by the hook on
// EVERY tool call, so "stateSince" means "seconds since a tool last ran" — and
// the finish gate fed that read near-zero for runs of any length. Multiple
// 15-minute runs, zero alerts. The watcher keeps its own ledger instead.

const { carryRunStarts } = require('../lib/alerts');

test('a newly running session is stamped now', () => {
  assert.deepStrictEqual(carryRunStarts({}, { a: 'running' }, 1000), { a: 1000 });
});

test('a still-running session keeps its ORIGINAL start across observations', () => {
  const first = carryRunStarts({}, { a: 'running' }, 1000);
  const later = carryRunStarts(first, { a: 'running' }, 5000);
  assert.deepStrictEqual(later, { a: 1000 });
});

test('a session no longer running drops out of the ledger', () => {
  assert.deepStrictEqual(carryRunStarts({ a: 900 }, { a: 'idle' }, 1000), {});
  assert.deepStrictEqual(carryRunStarts({ a: 900 }, {}, 1000), {});
});

test('idle and attention sessions never enter the ledger', () => {
  assert.deepStrictEqual(carryRunStarts({}, { a: 'idle', b: 'attention' }, 1000), {});
});

test('the ledger feeds the finish gate end to end', () => {
  // The integration the bug lived in: ledger at t0, finish at t0+9min.
  const t0 = Math.floor(NOW / 1000) - 9 * 60;
  const ledger = carryRunStarts({}, { a: 'running' }, t0);
  const { alerts } = decideAlerts(
    { sessions: { a: 'running' }, sessionsSince: ledger, chats: {} },
    { sessions: { a: 'idle' }, sessionsSince: {}, chats: {} },
    {}, NOW,
  );
  assert.strictEqual(alerts.length, 1);
  assert.strictEqual(alerts[0].kind, 'session_finished');
  assert.ok(alerts[0].text.includes('9m'), alerts[0].text);
});

// -------------------------------------- finishes nobody needs to be told about
//
// A terminal attached to the session means someone was sitting at it, and telling
// them their own screen changed is the redundancy the app already suppresses for
// its open conversation. Gates ONLY the finish: a question always alerts, because
// an attached-but-idle terminal in another room missing a blocking ask is the one
// failure worse than a redundant buzz.

const attendedObs = (state) => ({
  sessions: { a: state },
  sessionsSince: { a: SINCE },
  sessionsAttached: { a: true },
  chats: {},
});

test('a long run finishing while a terminal is attached stays quiet', () => {
  const { alerts } = decideAlerts(
    attendedObs('running'),
    { sessions: { a: 'idle' }, sessionsSince: {}, chats: {} },
    {}, NOW,
  );
  assert.deepStrictEqual(alerts, []);
});

test('the same finish, detached, alerts', () => {
  const detached = { ...attendedObs('running'), sessionsAttached: {} };
  const { alerts } = decideAlerts(
    detached,
    { sessions: { a: 'idle' }, sessionsSince: {}, chats: {} },
    {}, NOW,
  );
  assert.deepStrictEqual(alerts.map((x) => x.kind), ['session_finished']);
});

test('attachment never silences a QUESTION', () => {
  const { alerts } = decideAlerts(
    attendedObs('running'),
    { sessions: { a: 'attention' }, sessionsSince: {}, chats: {} },
    {}, NOW,
  );
  assert.deepStrictEqual(alerts.map((x) => x.kind), ['session_attention']);
});

test('observations that predate the attachment field still alert', () => {
  // Persisted prev from an older daemon has no sessionsAttached at all; absent
  // evidence of attendance, notify — the pre-existing behaviour.
  const { alerts } = decideAlerts(
    { sessions: { a: 'running' }, sessionsSince: { a: SINCE }, chats: {} },
    { sessions: { a: 'idle' }, sessionsSince: {}, chats: {} },
    {}, NOW,
  );
  assert.deepStrictEqual(alerts.map((x) => x.kind), ['session_finished']);
});
