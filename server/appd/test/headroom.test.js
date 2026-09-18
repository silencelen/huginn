'use strict';
// The headroom rules, which are all judgment calls: when a window has really
// reset, when a live session should be moved down a model, when a sentinel
// should arm, and — the one that decides whether any of it is trustworthy —
// when the daemon should do NOTHING.
//
// Every case here is cheap to assert and expensive to discover: reproducing
// "the Fable week reset at 3am but the endpoint still reads red" on a live
// account means waiting a week and being awake for it.

const { test } = require('node:test');
const assert = require('node:assert');
const h = require('../lib/headroom');
const { COOLDOWN_MS } = require('../lib/autoswitch');

const NOW = 1_800_000_000_000;
const iso = (ms) => new Date(ms).toISOString();
const S = (patch = {}) => ({ ...h.defaults(), ...patch, accountSwitch: { ...h.defaults().accountSwitch, ...(patch.accountSwitch || {}) } });

const lim = (percent, label = 'Current week', severity = 'normal') => ({ percent, label, severity });
const acct = (slug, ...limits) => ({ slug, email: `${slug}@x`, limits });
const win = (percent, extra = {}) => ({ percent, resetsAt: null, severity: 'normal', label: '', ...extra });

/** The shape the daemon hands `decide` for one live session. */
const sess = (over = {}) => ({
  claudeSessionId: over.claudeSessionId || 'sid-1',
  name: over.name || 'dev',
  state: 'idle',
  model: 'claude-fable-5-1',
  family: 'fable',
  ladder: null,
  headsUpAt: null,
  nativeSwitch: { seenAt: null, to: null },
  humanSetModelAt: null,
  ...over,
});

const types = (v) => v.actions.map((a) => a.type);
const of = (v, type) => v.actions.filter((a) => a.type === type);

// ---------------------------------------------------------------- windowsOf

test('windowsOf picks the three windows out of the limits rows', () => {
  const w = h.windowsOf([
    { kind: 'session', label: 'Current session', percent: 12, resetsAt: iso(NOW + 3600_000) },
    { kind: 'weekly_all', label: 'Current week, all models', percent: 30 },
    { kind: 'weekly_scoped', label: 'Current week (Fable)', percent: 92 },
  ]);
  assert.equal(w.session.percent, 12);
  assert.equal(w.weekly_all.percent, 30);
  assert.equal(w.weekly_fable.percent, 92);
});

test('the Fable window is found by LABEL, never by being the only scoped row', () => {
  // An Opus-scoped week is the same `kind`. Reading it as the Fable pool would
  // ladder every Fable session off a limit Fable is nowhere near.
  const w = h.windowsOf([
    { kind: 'weekly_scoped', label: 'Current week (Opus)', percent: 99 },
    { kind: 'weekly_scoped', label: 'Current week (Fable)', percent: 10 },
  ]);
  assert.equal(w.weekly_fable.percent, 10);
});

test('a window with no figure is absent rather than zero', () => {
  const w = h.windowsOf([{ kind: 'session', label: 'Current session', percent: null }]);
  assert.equal(w.session, null, 'unknown headroom must not read as full headroom');
  assert.deepEqual(h.windowsOf(null), { session: null, weekly_all: null, weekly_fable: null });
});

// ----------------------------------------------------------------- classify

test('classify names the four states at their boundaries', () => {
  const s = S();
  assert.equal(h.classify(84, s), 'ok');
  assert.equal(h.classify(85, s), 'warn', 'headsUpPct is inclusive');
  assert.equal(h.classify(91, s), 'warn');
  assert.equal(h.classify(92, s), 'red', 'ladderPct is inclusive');
  assert.equal(h.classify(100, s), 'exhausted');
});

test('severity exceeded is exhausted whatever the percentage says', () => {
  assert.equal(h.classify({ percent: 3, severity: 'exceeded' }, S()), 'exhausted');
});

test('a window with no number classifies as ok, not as red', () => {
  assert.equal(h.classify(null, S()), 'ok');
  assert.equal(h.classify({ percent: undefined }, S()), 'ok');
});

test('the thresholds are the owner\'s, not the code\'s', () => {
  assert.equal(h.classify(60, S({ headsUpPct: 55, ladderPct: 70 })), 'warn');
  assert.equal(h.classify(70, S({ headsUpPct: 55, ladderPct: 70 })), 'red');
});

// --------------------------------------------------------------- worstWindow

test('the binding constraint is the fullest window, whichever it is', () => {
  const w = h.worstWindow({ session: win(9), weekly_all: win(30), weekly_fable: win(96) }, S());
  assert.equal(w.window, 'weekly_fable');
  assert.equal(w.percent, 96);
  assert.equal(w.mode, 'red');
});

// -------------------------------------------------------------- detectResets

const redAcct = (percent, resetsAt, since = NOW - 3600_000) => ({
  email: 'a@x',
  windows: { weekly_fable: win(percent, { resetsAt }) },
  red: { session: null, weekly_all: null, weekly_fable: { since, resetsAt } },
});

test('a window that was red, whose clock has passed and now reads low, has reset', () => {
  const prev = { a: redAcct(94, iso(NOW - 1000)) };
  const next = { a: { email: 'a@x', windows: { weekly_fable: win(3) }, red: {} } };
  const r = h.detectResets(prev, next, NOW, S());
  assert.equal(r.length, 1);
  assert.equal(r[0].window, 'weekly_fable');
  assert.equal(r[0].slug, 'a');
  // ⚠ `seenAt` IS MILLISECONDS, like every other epoch this module emits. It was
  // seconds, sitting in the same `resets[]` row as `at` (ms) on /v1/headroom —
  // two epochs a thousand apart in one object, with no field-name tell.
  assert.equal(r[0].seenAt, NOW);
  assert.ok(r[0].seenAt > 1e11);
});

test('RESET BY CALENDAR, STILL RED ON THE WIRE is not a reset', () => {
  // The half that fails silently. `resetsAt` is published in advance and slides
  // past whether or not the account was credited; acting on it alone resumes a
  // session straight back into a full window and burns an attempt.
  const prev = { a: redAcct(94, iso(NOW - 1000)) };
  const next = { a: { email: 'a@x', windows: { weekly_fable: win(94) }, red: {} } };
  assert.deepEqual(h.detectResets(prev, next, NOW, S()), []);
});

test('a low reading BEFORE the clock passes is not a reset either', () => {
  const prev = { a: redAcct(94, iso(NOW + 60_000)) };
  const next = { a: { email: 'a@x', windows: { weekly_fable: win(2) }, red: {} } };
  assert.deepEqual(h.detectResets(prev, next, NOW, S()), []);
});

test('a window that was never red cannot reset', () => {
  const prev = { a: { email: 'a@x', windows: { weekly_fable: win(20, { resetsAt: iso(NOW - 1) }) }, red: { weekly_fable: null } } };
  const next = { a: { email: 'a@x', windows: { weekly_fable: win(1) }, red: {} } };
  assert.deepEqual(h.detectResets(prev, next, NOW, S()), [],
    'an ordinary weekly rollover is not an event anything was waiting for');
});

test('a window still reported exceeded has not reset, whatever its percentage', () => {
  const prev = { a: redAcct(100, iso(NOW - 1000)) };
  const next = { a: { email: 'a@x', windows: { weekly_fable: win(0, { severity: 'exceeded' }) }, red: {} } };
  assert.deepEqual(h.detectResets(prev, next, NOW, S()), []);
});

test('an account that vanished from the next pass reports nothing', () => {
  assert.deepEqual(h.detectResets({ a: redAcct(94, iso(NOW - 1)) }, {}, NOW, S()), []);
  assert.deepEqual(h.detectResets(null, null, NOW, S()), []);
});

// ------------------------------------------------------------ parseLimitError

test('parseLimitError names the window from the two observed shapes', () => {
  const a = h.parseLimitError("You've hit your session limit · resets 10:10pm (America/Los_Angeles)");
  assert.equal(a.window, 'session');
  assert.equal(a.resetsClock, '10:10pm');
  assert.equal(a.tz, 'America/Los_Angeles');
  const b = h.parseLimitError("You're out of usage credits. Run /usage-credits to keep using Fable 5.1 or /model to switch models.");
  assert.equal(b.window, 'weekly_fable');
});

test('parseLimitError guesses nothing from text it does not recognise', () => {
  assert.deepEqual(h.parseLimitError('the sky is blue'), { window: null, resetsClock: null, tz: null });
  assert.deepEqual(h.parseLimitError(''), { window: null, resetsClock: null, tz: null });
});

// ---------------------------------------------------------------- the ladder

test('nextDown and canLadderUp read the owner\'s ladder order', () => {
  assert.equal(h.nextDown('fable'), 'opus');
  assert.equal(h.nextDown('sonnet'), null, 'the bottom rung has nowhere to go');
  assert.equal(h.nextDown('haiku'), null, 'a family off the ladder is not on it');
  assert.equal(h.nextDown('fable', ['fable', 'haiku']), 'haiku');
  assert.equal(h.canLadderUp('opus'), true);
  assert.equal(h.canLadderUp('fable'), false);
});

test('familyOf reads ids and picker labels, and refuses the Default row', () => {
  assert.equal(h.familyOf('claude-fable-5-1'), 'fable');
  assert.equal(h.familyOf('Opus (1M context)'), 'opus');
  assert.equal(h.familyOf('Default (recommended)'), null);
});

// -------------------------------------------------------------- sentinelPlan

test('the sentinels arm high and clear low, and hold in between', () => {
  const s = S();                                   // stop 70, stopFable 88, clear 50
  const armed = h.sentinelPlan({ session: win(72) }, s, {});
  assert.equal(armed.STOP, true);
  assert.match(armed.reasons.STOP, /session 72%/);
  // The middle band: below the arming threshold but above the clearing one.
  const held = h.sentinelPlan({ session: win(60) }, s, { STOP: { since: 1, reason: 'session 72%' } });
  assert.equal(held.STOP, true, 'a sentinel that flapped here would release spawns into a full window');
  const cleared = h.sentinelPlan({ session: win(49) }, s, { STOP: { since: 1, reason: 'x' } });
  assert.equal(cleared.STOP, false);
});

test('a STOP the session armed clears when the session resets, whatever the week reads', () => {
  // Seen live 2026-09-18: STOP armed at "session 71%", the 5-hour window reset to
  // 4%, and the sentinel stayed up for hours because the clear branch also wanted
  // weekly_all under clearBelowPct — a perfectly normal 69% week held every spawn.
  const s = S();
  const stale = h.sentinelPlan({ session: win(4), weekly_all: win(69) }, s, { STOP: { since: 1, reason: 'session 71%' } });
  assert.equal(stale.STOP, false, 'the window that armed it has reset; a normal week is no reason to hold');
  // The week's own arm keeps its own hysteresis: armed red, it holds until the
  // week is genuinely low again, not merely no longer red.
  const weekHeld = h.sentinelPlan({ session: win(4), weekly_all: win(69) }, s, { STOP: { since: 1, reason: 'weekly_all 96%' } });
  assert.equal(weekHeld.STOP, true, 'a week-armed STOP holds through the middle band');
  const weekCleared = h.sentinelPlan({ session: win(4), weekly_all: win(12) }, s, { STOP: { since: 1, reason: 'weekly_all 96%' } });
  assert.equal(weekCleared.STOP, false);
  // A red week still arms regardless of what armed it before.
  const red = h.sentinelPlan({ session: win(4), weekly_all: win(96, { severity: 'critical' }) }, S({ headsUpPct: 85, ladderPct: 92 }), { STOP: { since: 1, reason: 'session 71%' } });
  assert.equal(red.STOP, true);
});

test('all three windows at once: a session spike arms and its own reset clears', () => {
  // The shape the host actually reads — session, weekly_all and weekly_fable all
  // present — walked across a tick sequence with the plan fed back in, because
  // every clear-side case above passes ONE window and the dead band only exists
  // when a second one is there to hold the gate down. 2026-09-18: a 5-hour spike
  // to 82% armed STOP and a perfectly ordinary 67% week kept it armed after the
  // window reset to 2%, holding every SubagentStart for the rest of the week.
  const s = S();
  const week = win(67);              // mid-band: too low to arm anything, high enough to have blocked the clear
  const fable = win(40);
  let cur = { STOP: null, 'STOP-FABLE': null };
  const seen = [];
  for (const pct of [10, 75, 82, 2, 5, 8]) {
    const plan = h.sentinelPlan({ session: win(pct), weekly_all: week, weekly_fable: fable }, s, cur);
    seen.push(plan.STOP);
    // What the daemon carries forward: arm() is idempotent, so the reason stays
    // the ARMING one for as long as the sentinel is up.
    cur = {
      STOP: plan.STOP
        ? { since: 1, reason: (cur.STOP && cur.STOP.reason) || plan.reasons.STOP }
        : null,
      'STOP-FABLE': plan.STOP_FABLE ? { since: 1, reason: plan.reasons['STOP-FABLE'] } : null,
    };
  }
  assert.deepEqual(seen, [false, true, true, false, false, false],
    'the window that armed STOP reset; nothing else was ever over an arming threshold');
  assert.equal(cur.STOP, null);
});

test('STOP-FABLE has its own threshold and its own hysteresis', () => {
  const s = S();
  assert.equal(h.sentinelPlan({ weekly_fable: win(88) }, s, {}).STOP_FABLE, true);
  assert.equal(h.sentinelPlan({ weekly_fable: win(60) }, s, { 'STOP-FABLE': { since: 1 } }).STOP_FABLE, true);
  assert.equal(h.sentinelPlan({ weekly_fable: win(10) }, s, { 'STOP-FABLE': { since: 1 } }).STOP_FABLE, false);
  assert.equal(h.sentinelPlan({ session: win(90) }, s, {}).STOP_FABLE, false,
    'a full 5-hour window is not a Fable-week problem');
});

test('a red week across all models arms STOP — no rung is left to move to', () => {
  const p = h.sentinelPlan({ weekly_all: win(93), session: win(5) }, S(), {});
  assert.equal(p.STOP, true);
  assert.match(p.reasons.STOP, /weekly_all/);
});

test('an account with no figures arms nothing', () => {
  assert.deepEqual(
    { STOP: false, STOP_FABLE: false },
    (({ STOP, STOP_FABLE }) => ({ STOP, STOP_FABLE }))(h.sentinelPlan({}, S(), {})),
  );
});

// ---- the arbiter: the account-switch cases, moved from autoswitch.test.js ---
//
// Every one of these passed against `decideSwitch` and must pass against
// `decide`, which now owns the lever. The semantics are absorbed, not rewritten.

const SW = (patch = {}) => S({ accountSwitch: { enabled: true, threshold: 95, margin: 20, ...patch } });
const decideSwitchish = (o) => h.decide({
  active: o.active, candidates: o.candidates, sessions: [], now: o.now,
  settings: SW(o.threshold !== undefined ? { threshold: o.threshold } : {}),
  state: { lastSwitchAt: o.lastSwitchAt || 0, lastLadderAt: 0 },
});

test('a healthy active account never switches', () => {
  const v = decideSwitchish({ active: acct('a', lim(60)), candidates: [acct('b', lim(0))], now: NOW });
  assert.equal(of(v, 'switch_account').length, 0);
});

test('an exhausted account switches to the freshest candidate', () => {
  const v = decideSwitchish({
    active: acct('a', lim(97, 'Current week (Fable)')),
    candidates: [acct('b', lim(40)), acct('c', lim(5))],
    now: NOW,
  });
  const d = of(v, 'switch_account')[0];
  assert.equal(d.slug, 'c');
  assert.equal(d.detail.toPercent, 5);
  assert.equal(d.detail.fromPercent, 97);
  assert.equal(d.detail.fromLabel, 'Current week (Fable)');
});

test('less dead is not fresh: no switch when the best candidate is also hot', () => {
  const v = decideSwitchish({ active: acct('a', lim(96)), candidates: [acct('b', lim(88))], now: NOW });
  assert.equal(of(v, 'switch_account').length, 0, 'switching 96 to 88 buys minutes and spends the cooldown');
});

test('the cooldown blocks a second switch, whatever the numbers say', () => {
  const v = decideSwitchish({
    active: acct('a', lim(100)), candidates: [acct('b', lim(0))],
    now: NOW, lastSwitchAt: NOW - COOLDOWN_MS + 1000,
  });
  assert.equal(of(v, 'switch_account').length, 0);
});

test('a candidate with no numbers is not a candidate', () => {
  const v = decideSwitchish({
    active: acct('a', lim(99)), candidates: [{ slug: 'b', email: 'b@x', limits: [] }], now: NOW,
  });
  assert.equal(of(v, 'switch_account').length, 0, 'unknown headroom must not be mistaken for headroom');
});

test('a raised threshold is honoured by the decision and by its explanation', () => {
  const args = { active: acct('a', lim(60)), candidates: [acct('b', lim(5))], now: NOW };
  assert.equal(of(decideSwitchish({ ...args, threshold: 50 }), 'switch_account').length, 1);
  assert.equal(of(decideSwitchish({ ...args, threshold: 95 }), 'switch_account').length, 0);
  assert.match(decideSwitchish({ ...args, threshold: 95 }).why, /below the 95% threshold/);
});

test('a candidate with FABLE headroom beats a merely fresher one', () => {
  // Decision 5: a new run landing on an account whose Fable week is spent starts
  // life one rung down for no reason.
  const fableFree = {
    slug: 'f', email: 'f@x',
    windows: { weekly_all: win(20), weekly_fable: win(4) },
  };
  const weeklyOnly = {
    slug: 'w', email: 'w@x',
    windows: { weekly_all: win(2), weekly_fable: win(90) },
  };
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(97) } },
    candidates: [weeklyOnly, fableFree],
    sessions: [], now: NOW, settings: SW(), state: {},
  });
  assert.equal(of(v, 'switch_account')[0].slug, 'f', 'the lower overall number is not the better account');
});

// ---- the arbiter: the model ladder ----------------------------------------

const fableRed = (pct) => ({ slug: 'a', email: 'a@x', windows: { session: win(5), weekly_fable: win(pct) } });

test('a heads-up is typed once per Fable window, not once per tick', () => {
  const first = h.decide({
    active: fableRed(86), candidates: [], sessions: [sess()], now: NOW, settings: S(), state: {},
  });
  const hu = of(first, 'heads_up')[0];
  assert.ok(hu, 'at 86% the session is asked to write its own handoff note');
  assert.equal(hu.pct, 86);
  assert.equal(hu.next, 'opus');

  const again = h.decide({
    active: fableRed(87), candidates: [], sessions: [sess({ headsUpAt: NOW - 60_000 })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(again, 'heads_up').length, 0);
});

test('a session waiting on the owner is not interrupted with a heads-up', () => {
  const v = h.decide({
    active: fableRed(90), candidates: [], sessions: [sess({ state: 'attention' })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(v, 'heads_up').length, 0);
});

test('at the ladder threshold a live session is moved down one rung', () => {
  const v = h.decide({ active: fableRed(93), candidates: [], sessions: [sess()], now: NOW, settings: S(), state: {} });
  const d = of(v, 'ladder_down')[0];
  assert.equal(d.from, 'fable');
  assert.equal(d.to, 'opus');
  assert.equal(d.name, 'dev');
});

test('the cooldown is ONE clock for every session on the host', () => {
  const two = [sess({ claudeSessionId: 's1', name: 'one' }), sess({ claudeSessionId: 's2', name: 'two' })];
  const v = h.decide({ active: fableRed(95), candidates: [], sessions: two, now: NOW, settings: S(), state: {} });
  assert.equal(of(v, 'ladder_down').length, 1,
    'six sessions moving at once is what the owner reads as "everything went to opus"');
});

test('a ladder move inside the cooldown does not happen', () => {
  const v = h.decide({
    active: fableRed(95), candidates: [], sessions: [sess()], now: NOW, settings: S(),
    state: { lastLadderAt: NOW - 60_000 },
  });
  assert.equal(of(v, 'ladder_down').length, 0);
  assert.match(v.why, /cooling down/);
});

test('appd never fights a native switch', () => {
  const v = h.decide({
    active: fableRed(97), candidates: [],
    sessions: [sess({ nativeSwitch: { seenAt: NOW - 5000, to: 'opus' } })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(v, 'ladder_down').length, 0);
  assert.match(v.why, /Claude Code itself/);
});

test('a model the owner has just set by hand is left alone', () => {
  const v = h.decide({
    active: fableRed(97), candidates: [],
    sessions: [sess({ humanSetModelAt: NOW - 60_000 })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(v, 'ladder_down').length, 0);
  assert.match(v.why, /by hand/);
  // …and once the grace has passed, the ladder is free again.
  const later = h.decide({
    active: fableRed(97), candidates: [],
    sessions: [sess({ humanSetModelAt: NOW - h.HUMAN_MODEL_GRACE_MS - 1000 })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(later, 'ladder_down').length, 1);
});

test('a session already moved is not moved again', () => {
  const v = h.decide({
    active: fableRed(97), candidates: [],
    sessions: [sess({ family: 'opus', ladder: { from: 'fable', to: 'opus', at: NOW - 10_000 } })],
    now: NOW, settings: S(), state: {},
  });
  assert.equal(of(v, 'ladder_down').length, 0);
});

test('THE ACCOUNT-VS-LADDER RACE: one lever per tick, and the why says which', () => {
  // Both levers are eligible in the same pass. A switch may free a Fable-capable
  // account for new runs, which can make the ladder move unnecessary — and two
  // interventions landing together is unreadable from the notification.
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(97) } },
    candidates: [{ slug: 'b', email: 'b@x', windows: { weekly_all: win(4), weekly_fable: win(4) } }],
    sessions: [sess()],
    now: NOW, settings: SW(), state: {},
  });
  assert.equal(of(v, 'switch_account').length, 1);
  assert.equal(of(v, 'ladder_down').length, 0);
  assert.match(v.why, /one lever per tick/);
});

test('a laddered session goes back up when the Fable week has actually reset', () => {
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(12) } },
    candidates: [],
    sessions: [sess({ family: 'opus', ladder: { from: 'fable', to: 'opus', at: NOW - 86_400_000 } })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 60_000,
  });
  const up = of(v, 'ladder_up')[0];
  assert.ok(up);
  assert.equal(up.to, 'fable');
});

test('going back up needs the week to be genuinely quiet, not merely reset', () => {
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(60) } },   // above ladderUpBelowPct
    candidates: [],
    sessions: [sess({ family: 'opus', ladder: { from: 'fable', to: 'opus', at: NOW - 86_400_000 } })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 60_000,
  });
  assert.equal(of(v, 'ladder_up').length, 0);
});

test('a ladder still in the queue is IN FLIGHT: no second move, and no move back', () => {
  // `rec.ladder.to` is written the moment the job is accepted by the queue, so a
  // downgrade waiting on a turn boundary used to read as a completed one — and
  // on the next tick, with the week quiet, the session became a ladder_up
  // candidate. appd would then type /model to move it BACK from a rung it had
  // not reached yet.
  const pending = { from: 'fable', to: 'opus', at: NOW - 60_000, delivery: 'pending' };
  const up = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(5) } },
    candidates: [],
    sessions: [sess({ family: 'fable', ladder: pending })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 30_000,
  });
  assert.equal(of(up, 'ladder_up').length, 0, 'a move that has not happened cannot be undone');

  // And the week filling up again must not start a SECOND move behind it.
  const down = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(96) } },
    candidates: [],
    sessions: [sess({ family: 'fable', ladder: pending })],
    now: NOW, settings: S({ cooldownMs: 0 }), state: {},
  });
  assert.equal(of(down, 'ladder_down').length, 0);
  assert.match(down.why, /waiting for a turn boundary/);

  // Once it has actually landed, the ordinary rules resume.
  const landed = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(5) } },
    candidates: [],
    sessions: [sess({ family: 'opus', ladder: { ...pending, delivery: 'confirmed' } })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 30_000,
  });
  assert.equal(of(landed, 'ladder_up').length, 1);
});

test('a session appd did not ladder is never laddered up', () => {
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(5) } },
    candidates: [], sessions: [sess({ family: 'opus', ladder: null })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 60_000,
  });
  assert.equal(of(v, 'ladder_up').length, 0);
});

test('a NATIVE switch is offered a way back, never given one', () => {
  // Measured: a consent swap never returns to Fable by itself, and the owner may
  // prefer to stay on the cheaper model. So it is a notification, not a move.
  const v = h.decide({
    active: { slug: 'a', email: 'a@x', windows: { weekly_fable: win(5) } },
    candidates: [],
    sessions: [sess({ family: 'opus', nativeSwitch: { seenAt: NOW - 86_400_000, to: 'opus' } })],
    now: NOW, settings: S(), state: {}, lastFableResetAt: NOW - 60_000,
  });
  assert.equal(of(v, 'ladder_up').length, 0);
  assert.equal(of(v, 'offer_ladder_up').length, 1);
});

test('the sentinels are decided every pass, and last', () => {
  const v = h.decide({ active: fableRed(93), candidates: [], sessions: [sess()], now: NOW, settings: S(), state: {} });
  assert.equal(types(v)[types(v).length - 1], 'sentinels');
});

test('no identifiable active account is a quiet, honest no', () => {
  const v = h.decide({ active: null, sessions: [sess()], now: NOW, settings: S(), state: {} });
  assert.deepEqual(v.actions, []);
  assert.equal(v.why, 'no active account is identifiable');
});

// ------------------------------------------------------------------- explain

test('explain answers with exactly what decide would have said', () => {
  const input = { active: fableRed(41), candidates: [], sessions: [sess()], now: NOW, settings: S(), state: {} };
  assert.equal(h.explain(input), h.decide(input).why);
  assert.match(h.explain(input), /Fable week at 41%, below the 92%/);
});

test('explain names the session it moved', () => {
  assert.match(
    h.explain({ active: fableRed(94), candidates: [], sessions: [sess({ name: 'jtyper' })], now: NOW, settings: S(), state: {} }),
    /jtyper: fable -> opus at 94%/,
  );
});

// ----------------------------------------------------------- validateSettings

test('a percentage must be a whole number in range', () => {
  assert.match(h.validateSettings({ headsUpPct: 0 }).error, /between 1 and 100/);
  assert.match(h.validateSettings({ headsUpPct: 101 }).error, /between 1 and 100/);
  assert.match(h.validateSettings({ stopPct: 70.5 }).error, /whole percentage/);
  assert.match(h.validateSettings({ clearBelowPct: '40' }).error, /whole percentage/);
});

test('the three orderings that would make the arbiter incoherent are refused', () => {
  assert.match(h.validateSettings({ headsUpPct: 95 }).error, /headsUpPct must be below ladderPct/);
  assert.match(h.validateSettings({ clearBelowPct: 80 }).error, /clearBelowPct must be below stopPct/);
  assert.match(h.validateSettings({ ladderUpBelowPct: 95 }).error, /ladderUpBelowPct must be below ladderPct/);
});

test('a patch is validated against the MERGED settings, not against the defaults', () => {
  // `headsUpPct: 95` is legal or not depending entirely on where ladderPct sits.
  const raised = h.validateSettings({ ladderPct: 98 });
  assert.equal(raised.ok, true);
  assert.equal(h.validateSettings({ headsUpPct: 95 }, raised.settings).ok, true);
});

test('the ladder is a non-empty list of distinct known families', () => {
  assert.match(h.validateSettings({ ladder: [] }).error, /non-empty/);
  assert.match(h.validateSettings({ ladder: ['fable', 'gpt'] }).error, /may only contain/);
  assert.match(h.validateSettings({ ladder: ['fable', 'fable'] }).error, /distinct/);
  assert.equal(h.validateSettings({ ladder: ['fable', 'haiku'] }).ok, true);
});

test('the resume phrase is bounded, stripped, and never a slash command', () => {
  assert.match(h.validateSettings({ resumePhrase: '' }).error, /between 1 and 300/);
  assert.match(h.validateSettings({ resumePhrase: 'x'.repeat(301) }).error, /between 1 and 300/);
  assert.match(h.validateSettings({ resumePhrase: '/clear' }).error, /not start with \//);
  const ok = h.validateSettings({ resumePhrase: 'carry on' });
  assert.equal(ok.settings.resumePhrase, 'carry on', 'C0 controls are stripped, not rejected');
});

test('the heads-up text must be able to say the percentage', () => {
  assert.match(h.validateSettings({ headsUpText: 'you are nearly out' }).error, /\{pct\}/);
  assert.match(h.validateSettings({ headsUpText: `${'x'.repeat(601)}{pct}` }).error, /at most 600/);
  const ok = h.validateSettings({ headsUpText: 'at {pct}%\nwrite a note' });
  assert.equal(ok.ok, true, 'newlines survive; other controls do not');
  assert.ok(ok.settings.headsUpText.includes('\n'));
});

test('the heads-up text is never a slash command either', () => {
  // It travels the same enqueueSend -> sendTextToPane -> Enter path the resume
  // phrase does, and `/clear {pct}` satisfies the {pct} rule, so that check is
  // no help at all here.
  assert.match(h.validateSettings({ headsUpText: '/clear {pct}' }).error, /not start with \//);
  assert.match(h.validateSettings({ headsUpText: '   /model opus {pct}' }).error, /not start with \//);
  // And newlines still survive, because bracketed paste carries them.
  const ok = h.validateSettings({ headsUpText: 'at {pct}%\n/clear is fine on a later line' });
  assert.equal(ok.ok, true);
  assert.ok(ok.settings.headsUpText.includes('\n'));
});

test('defaultModel is a model id or a family alias and nothing else', () => {
  assert.equal(h.validateSettings({ defaultModel: 'opus' }).ok, true);
  assert.equal(h.validateSettings({ defaultModel: 'claude-opus-5' }).ok, true);
  assert.match(h.validateSettings({ defaultModel: '' }).error, /model id or family alias/);
  assert.match(h.validateSettings({ defaultModel: 'gpt-5' }).error, /model id or family alias/);
});

test('the remaining scalars are type-checked rather than coerced', () => {
  assert.match(h.validateSettings({ autoResume: 'yes' }).error, /true or false/);
  assert.match(h.validateSettings({ cooldownMs: -1 }).error, /between 0 and 24 hours/);
  assert.match(h.validateSettings({ accountSwitch: { enabled: 1 } }).error, /true or false/);
  assert.match(h.validateSettings({ accountSwitch: { threshold: 0 } }).error, /between 1 and 100/);
  assert.match(h.validateSettings({ accountSwitch: 'on' }).error, /must be an object/);
  assert.match(h.validateSettings(null).error, /must be an object/);
});

test('an empty patch is valid and returns the settings unchanged', () => {
  const base = h.validateSettings({ ladderPct: 90, headsUpPct: 80 }).settings;
  const v = h.validateSettings({}, base);
  assert.equal(v.ok, true);
  assert.equal(v.settings.ladderPct, 90);
  assert.equal(v.settings.headsUpPct, 80);
});

// --------------------------------------------------------- migrateAutoswitch

test('the old autoswitch settings come across, once', () => {
  assert.deepEqual(h.migrateAutoswitch({ enabled: true, threshold: 88, lastSwitchAt: 12345 }),
    { enabled: true, threshold: 88, margin: 20 });
  assert.deepEqual(h.migrateAutoswitch({}), { enabled: false, threshold: 95, margin: 20 });
  assert.deepEqual(h.migrateAutoswitch(null), { enabled: false, threshold: 95, margin: 20 });
  assert.equal(h.migrateAutoswitch({ enabled: true, threshold: 500 }).threshold, 95,
    'a nonsense stored threshold falls back rather than travelling forward');
});
