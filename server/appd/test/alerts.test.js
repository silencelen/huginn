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
  assert.ok(tg.startsWith('\u25a0 chat finished: Audit the arr stack'), tg);
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
    title: 'jotter needs you',
    text: 'ignored when a question is present',
    question: 'Do you want to create jotter.md?',
    options: [{ number: 1, label: 'Yes' }, { number: 2, label: 'No' }],
  });
  assert.match(text, /jotter needs you/);
  assert.match(text, /^Asked: Do you want to create jotter\.md\?$/m,
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

// The ledger is keyed by session NAME and built prototype-less (#35), so the
// comparisons here spread it: a session may legitimately be called `__proto__`,
// and on a plain literal that key hits the prototype setter instead of being
// stored — the row then vanishes from every alert that reads this map.
const ledger = (...args) => ({ ...carryRunStarts(...args) });

test('a newly running session is stamped now', () => {
  assert.deepStrictEqual(ledger({}, { a: 'running' }, 1000), { a: 1000 });
});

test('a still-running session keeps its ORIGINAL start across observations', () => {
  const first = carryRunStarts({}, { a: 'running' }, 1000);
  const later = carryRunStarts(first, { a: 'running' }, 5000);
  assert.deepStrictEqual({ ...later }, { a: 1000 });
});

test('a session no longer running drops out of the ledger', () => {
  assert.deepStrictEqual(ledger({ a: 900 }, { a: 'idle' }, 1000), {});
  assert.deepStrictEqual(ledger({ a: 900 }, {}, 1000), {});
});

test('idle and attention sessions never enter the ledger', () => {
  assert.deepStrictEqual(ledger({}, { a: 'idle', b: 'attention' }, 1000), {});
});

test('a session named __proto__ keeps its place in the run ledger (#35)', () => {
  const l = carryRunStarts({}, { ['__proto__']: 'running', b: 'running' }, 1000);
  assert.deepEqual(['__proto__', 'b'], Object.keys(l).sort());
  assert.equal(1000, l.__proto__);
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

// --------------------------------------- a chat waiting on a start_session request
//
// 2026-11-03: a chat whose run asked for a new session was simply `running` for
// the hour the request lived, and the watcher's chat rule is a run FINISHING, so
// three requests on one night reached nobody and expired unseen.

const { GLYPH } = require('../lib/state-table');
const CHAT_ID = '11111111-2222-4333-8444-555555555555';
const asks = (title, ...ids) => ({
  title,
  asks: ids.map((id) => ({ id, text: `a chat asks to start a session in /srv/${id}`, fingerprint: `token-${id}` })),
});
const waiting = (chatAsks) => ({ sessions: {}, chats: {}, chatAsks });

test('a chat waiting on a start_session request alerts once per request, buttonless', () => {
  const first = decideAlerts(waiting({}), waiting({ [CHAT_ID]: asks('deploy notes', 'r1') }), {}, NOW);
  assert.deepStrictEqual(first.alerts.map((x) => x.kind), ['chat_attention']);
  const a = first.alerts[0];
  assert.strictEqual(a.subject, CHAT_ID, 'the chat it opens');
  assert.strictEqual(a.title, 'deploy notes needs you');
  assert.strictEqual(a.text, 'a chat asks to start a session in /srv/r1', "the request's own describe() line");
  assert.deepStrictEqual(a.options, [], 'no Start or Decline: only the card shows what Start would paste');
  assert.strictEqual(a.fingerprint, 'token-r1');
  assert.strictEqual(a.requestId, 'r1');
  // Still waiting on the next tick: nothing new to say.
  assert.deepStrictEqual(decideAlerts(waiting({ [CHAT_ID]: asks('deploy notes', 'r1') }),
    waiting({ [CHAT_ID]: asks('deploy notes', 'r1') }), first.sentUpdates, NOW + 10_000).alerts, []);
  // A second request from the same chat is its own news, inside the repeat window too.
  const second = decideAlerts(waiting({ [CHAT_ID]: asks('deploy notes', 'r1') }),
    waiting({ [CHAT_ID]: asks('deploy notes', 'r1', 'r2') }), first.sentUpdates, NOW + 20_000);
  assert.deepStrictEqual(second.alerts.map((x) => x.requestId), ['r2']);
  // Answered or expired: it goes quietly.
  assert.deepStrictEqual(decideAlerts(waiting({ [CHAT_ID]: asks('t', 'r1', 'r2') }), waiting({}), {}, NOW).alerts, []);
});

test('a chat request alert: its title without one, the first look, an old observation, a redelivery', () => {
  assert.strictEqual(decideAlerts(waiting({}), waiting({ [CHAT_ID]: asks(null, 'r3') }), {}, NOW).alerts[0].title,
    'A chat needs you');
  assert.deepStrictEqual(decideAlerts(null, waiting({ [CHAT_ID]: asks('t', 'r4') }), {}, NOW).alerts, [],
    'the first observation never alerts, here as everywhere');
  assert.strictEqual(decideAlerts({ sessions: {}, chats: {} }, waiting({ [CHAT_ID]: asks('t', 'r5') }), {}, NOW).alerts.length, 1,
    'an observation saved before the rule carries no requests: one already waiting is announced once');
  assert.deepStrictEqual(decideAlerts(waiting({}), waiting({ [CHAT_ID]: asks('t', 'r1') }),
    { [`chat-ask:${CHAT_ID}:r1`]: NOW - 1000 }, NOW).alerts, [], 'the same request said again is suppressed by its key');
  const tg = telegramText(decideAlerts(waiting({}), waiting({ [CHAT_ID]: asks('deploy notes', 'r6') }), {}, NOW).alerts[0]);
  assert.strictEqual(tg, `${GLYPH.needsYou} deploy notes needs you\na chat asks to start a session in /srv/r6`,
    'Telegram: the needs-you glyph and a statement');
});

// ------------------------------------------- a project's sessions, as one project
//
// 2026-11-06: a project's sessions hand work to one another, so each one goes on
// and off all through one piece of work, and every member whose turn ran past
// five minutes said "finished" on its own: several notifications for one
// conversation between sessions. A project session now never sends
// session_finished; the project sends ONE project_idle once all of it has been
// quiet for five minutes after a burst that worked five minutes or more, and a
// member that needs you is named by its project and role.

const { projectSessions, carryProjectBursts } = require('../lib/alerts');

const MIN = 60_000;
const GARDEN = { id: 'p-1f2e3d', name: 'Garden', slug: 'garden' };
/** listSessions rows from {name: [state, role]}; a null role is a session outside every project. */
const listed = (spec) => Object.entries(spec).map(([name, [state, role]]) => ({
  name, state, attachedClients: 0,
  project: role ? { ...GARDEN, role, lead: role === 'lead' } : null,
}));
/** The garden's three sessions: its lead, a guide and a vault. */
const garden = (lead, guide, vault) => ({
  'garden-lead': [lead, 'lead'], 'garden-guide': [guide, 'guide'], 'garden-vault': [vault, 'vault'],
});
const quiet = () => garden('idle', 'idle', 'idle');

/**
 * The daemon's alert tick without its I/O: each observation built the way
 * alertTickInner builds it, decided against the last, what was said merged and
 * pruned, and the observation kept as JSON, the way alerts.json keeps it.
 */
function projectWatcher() {
  let prev = null;
  let sent = {};
  const said = [];
  const tick = (spec, atMs) => {
    const rows = Array.isArray(spec) ? spec : listed(spec);
    const nowSec = Math.floor(atMs / 1000);
    const sessions = Object.fromEntries(rows.map((r) => [r.name, r.state]));
    const sessionsSince = carryRunStarts(prev && prev.sessionsSince, sessions, nowSec);
    const sessionsAttached = {};
    for (const r of rows) if (r.attachedClients > 0) sessionsAttached[r.name] = true;
    const sessionsProject = projectSessions(rows);
    const projectBursts = carryProjectBursts(prev && prev.projectBursts, sessions, sessionsProject, sessionsSince, nowSec);
    const next = { sessions, sessionsSince, sessionsAttached, sessionsProject, projectBursts, chats: {} };
    const r = decideAlerts(prev, next, sent, atMs, prev ? atMs - 10_000 : 0);
    sent = pruneSent({ ...sent, ...r.sentUpdates }, atMs);
    prev = JSON.parse(JSON.stringify(next));
    said.push(...r.alerts);
    return r.alerts;
  };
  return { said, tick };
}

test('a project session ending a long run sends no session_finished; a plain session beside it still does', () => {
  const w = projectWatcher();
  w.tick({ ...garden('running', 'running', 'idle'), solo: ['running', null] }, NOW);
  const said = w.tick({ ...quiet(), solo: ['idle', null] }, NOW + 9 * MIN);
  assert.deepStrictEqual(said.map((a) => [a.kind, a.subject]), [['session_finished', 'solo']],
    'nine minutes each: the lead and the guide said finished too');
  assert.strictEqual(said[0].title, 'solo finished');
  assert.strictEqual(said[0].text, 'Ran for 9m, now idle.');
});

test('a project that worked and then went quiet for five minutes is announced once, as the project', () => {
  const w = projectWatcher();
  w.tick(quiet(), NOW);                                          // the first look: a quiet project
  w.tick(garden('running', 'idle', 'idle'), NOW + 1 * MIN);      // the lead starts: a burst opens
  w.tick(garden('idle', 'running', 'idle'), NOW + 10 * MIN);     // it hands to the guide
  w.tick(garden('idle', 'running', 'running'), NOW + 20 * MIN);  // the vault joins in
  w.tick(garden('running', 'idle', 'idle'), NOW + 30 * MIN);     // and back to the lead
  w.tick(quiet(), NOW + 43 * MIN);                               // the last run ends
  assert.deepStrictEqual(w.tick(quiet(), NOW + 47 * MIN), [], 'four quiet minutes is the project between turns');
  const said = w.tick(quiet(), NOW + 48 * MIN);
  assert.deepStrictEqual(said.map((a) => a.kind), ['project_idle']);
  const a = said[0];
  assert.strictEqual(a.subject, GARDEN.id, 'the project, which a tap opens');
  assert.strictEqual(a.title, 'Garden is idle');
  assert.strictEqual(a.text, 'Worked 42m; lead, guide, vault ran.',
    'from the first run start to the last run end; the roles in the order they first ran');
  assert.deepStrictEqual(a.options, [], 'no buttons');
  assert.deepStrictEqual(a.payload, { projectId: GARDEN.id });
  assert.strictEqual(telegramText(a), `${GLYPH.ok} Garden is idle\nWorked 42m; lead, guide, vault ran.`,
    'Telegram: the ok glyph, as for a finished session');
  assert.deepStrictEqual(w.said.map((x) => x.kind), ['project_idle'], 'not one member said finished on the way');
  assert.deepStrictEqual(w.tick(quiet(), NOW + 60 * MIN), [], 'said once');
});

test('a burst that worked under five minutes ends without a word', () => {
  const w = projectWatcher();
  w.tick(quiet(), NOW);
  w.tick(garden('running', 'idle', 'idle'), NOW + 1 * MIN);
  w.tick(garden('idle', 'running', 'idle'), NOW + 3 * MIN);
  w.tick(quiet(), NOW + 5 * MIN);                                // worked four minutes
  assert.deepStrictEqual(w.tick(quiet(), NOW + 11 * MIN), [], 'quiet long enough to end, worked too little to say');
  // The next burst is its own, measured from its own start.
  w.tick(garden('running', 'idle', 'idle'), NOW + 20 * MIN);
  w.tick(quiet(), NOW + 26 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 31 * MIN).map((a) => a.text), ['Worked 6m; lead ran.']);
  assert.deepStrictEqual(w.said.map((a) => a.kind), ['project_idle']);
});

test('a project is not idle while any session of it runs or waits on the owner', () => {
  const w = projectWatcher();
  w.tick(quiet(), NOW);
  w.tick(garden('running', 'idle', 'idle'), NOW + 1 * MIN);
  w.tick(garden('idle', 'running', 'running'), NOW + 10 * MIN);
  // The guide works on for a quarter of an hour after the others stop...
  for (let m = 15; m <= 30; m++) w.tick(garden('idle', 'running', 'idle'), NOW + m * MIN);
  // ...then the vault asks the owner something and waits half an hour for the answer.
  for (let m = 31; m <= 60; m++) w.tick(garden('idle', 'idle', 'attention'), NOW + m * MIN);
  assert.deepStrictEqual(w.said.map((a) => [a.kind, a.title]), [['session_attention', 'Garden · vault needs you']],
    'a member waiting on the owner is blocked, not finished');
  // Answered, and quiet from then on: the quiet period starts at the answer.
  w.tick(quiet(), NOW + 61 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 65 * MIN), []);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 66 * MIN).map((a) => a.text), ['Worked 30m; lead, guide, vault ran.'],
    'worked up to the last moment one of them was running');
});

test('a session running again inside the quiet period extends the same burst', () => {
  const w = projectWatcher();
  w.tick(quiet(), NOW);
  w.tick(garden('running', 'idle', 'idle'), NOW + 1 * MIN);
  w.tick(quiet(), NOW + 7 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 11 * MIN), [], 'four quiet minutes');
  w.tick(garden('idle', 'running', 'idle'), NOW + 11.5 * MIN);   // the guide picks it up before five
  w.tick(quiet(), NOW + 20 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 24.5 * MIN), [], 'the quiet period starts again from the last stop');
  assert.deepStrictEqual(w.tick(quiet(), NOW + 25 * MIN).map((a) => a.text), ['Worked 19m; lead, guide ran.'],
    'one burst, from the first start to the last stop');
  assert.deepStrictEqual(w.said.map((a) => a.kind), ['project_idle'], 'once, at the end');
});

test('a later burst of the same project is announced again', () => {
  const w = projectWatcher();
  w.tick(quiet(), NOW);
  w.tick(garden('running', 'idle', 'idle'), NOW + 1 * MIN);
  w.tick(quiet(), NOW + 8 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 13 * MIN).map((a) => a.text), ['Worked 7m; lead ran.']);
  w.tick(garden('idle', 'idle', 'running'), NOW + 14 * MIN);     // a minute later, well inside the repeat window
  w.tick(quiet(), NOW + 24 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 29 * MIN).map((a) => a.text), ['Worked 10m; vault ran.']);
  assert.strictEqual(new Set(w.said.map((a) => a.key)).size, 2, 'each burst under its own key');
});

test('a terminal attached to a project session does not withhold the project going idle', () => {
  const w = projectWatcher();
  const watched = (spec) => listed(spec).map((r) => ({ ...r, attachedClients: r.name === 'garden-lead' ? 1 : 0 }));
  w.tick(watched(quiet()), NOW);
  w.tick(watched(garden('running', 'idle', 'idle')), NOW + 1 * MIN);
  w.tick(watched(quiet()), NOW + 9 * MIN);
  assert.deepStrictEqual(w.tick(watched(quiet()), NOW + 14 * MIN).map((a) => a.kind), ['project_idle']);
});

test('a project session needing you is named by its project and role; a plain session keeps its name', () => {
  const w = projectWatcher();
  w.tick({ ...garden('running', 'running', 'idle'), solo: ['running', null] }, NOW);
  const said = w.tick({ ...garden('attention', 'idle', 'attention'), solo: ['attention', null] }, NOW + MIN);
  assert.deepStrictEqual(said.map((a) => [a.kind, a.subject, a.title]), [
    ['session_attention', 'garden-lead', 'Garden · lead needs you'],
    ['session_attention', 'garden-vault', 'Garden · vault needs you'],
    ['session_attention', 'solo', 'solo needs you'],
  ], 'the subject stays the session, so a tap opens the member that asks');
  assert.strictEqual(said[1].text, 'Claude Code session garden-vault is waiting for an answer.', 'the text is unchanged');
  assert.strictEqual(telegramText(said[1]),
    `${GLYPH.needsYou} Garden · vault needs you\nClaude Code session garden-vault is waiting for an answer.`);
});

test('the first look after a start announces nothing, and a project already quiet has no burst to end', () => {
  // Whatever an observation holds, the first one has nothing to compare against.
  const sec = Math.floor(NOW / 1000);
  const ended = { [GARDEN.id]: {
    name: 'Garden', startedAt: sec - 3600, lastRunningAt: sec - 600, roles: ['lead'], running: false,
    quietSince: sec - 600, endedAt: sec,
  } };
  assert.deepStrictEqual(decideAlerts(null, { sessions: {}, projectBursts: ended, chats: {} }, {}, NOW).alerts, []);
  // A daemon that comes up on a quiet project opens no burst, so it has none to end later.
  const w = projectWatcher();
  assert.deepStrictEqual(w.tick(quiet(), NOW), []);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 10 * MIN), []);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 60 * MIN), []);
  // Its next burst is announced, measured from the run the watcher saw start.
  w.tick(garden('running', 'idle', 'idle'), NOW + 61 * MIN);
  w.tick(quiet(), NOW + 70 * MIN);
  assert.deepStrictEqual(w.tick(quiet(), NOW + 75 * MIN).map((a) => a.text), ['Worked 9m; lead ran.']);
});

test('a project archived or deleted mid-burst is never announced', () => {
  const run = (after) => {
    const w = projectWatcher();
    w.tick(quiet(), NOW);
    w.tick(garden('running', 'running', 'idle'), NOW + 1 * MIN);
    w.tick(quiet(), NOW + 20 * MIN);                             // every session stops: the quiet period begins
    for (const m of [22, 25, 30, 35, 40]) w.tick(after, NOW + m * MIN);
    return w.said;
  };
  assert.deepStrictEqual(run(quiet()).map((a) => a.text), ['Worked 19m; lead, guide ran.'], 'left alone, it is announced');
  // Archived: the list tags nothing with an archived project, so its sessions are plain again.
  assert.deepStrictEqual(run(listed(quiet()).map((r) => ({ ...r, project: null }))), [], 'archived');
  // Deleted with its sessions: gone from the list between two ticks.
  assert.deepStrictEqual(run([]), [], 'deleted');
});
