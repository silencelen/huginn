'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { decideSwitch, worstLimit, COOLDOWN_MS } = require('../lib/autoswitch');

const NOW = 1_800_000_000_000;
const lim = (percent, label = 'Current week', severity = 'normal') => ({ percent, label, severity });
const acct = (slug, ...limits) => ({ slug, email: `${slug}@x`, limits });

test('the binding constraint is the fullest limit, whatever window it is', () => {
  const w = worstLimit([lim(9, 'Current session'), lim(96, 'Current week')]);
  assert.equal(w.percent, 96);
  assert.equal(w.label, 'Current week');
});

test('severity exceeded counts as full even if the percent lags', () => {
  assert.equal(worstLimit([lim(80, 'w', 'exceeded')]).percent, 100);
});

test('a healthy active account never switches', () => {
  const d = decideSwitch({
    active: acct('a', lim(60)), candidates: [acct('b', lim(0))], now: NOW,
  });
  assert.equal(d, null);
});

test('an exhausted account switches to the freshest candidate', () => {
  const d = decideSwitch({
    active: acct('a', lim(97, 'Current week (Fable)')),
    candidates: [acct('b', lim(40)), acct('c', lim(5))],
    now: NOW,
  });
  assert.equal(d.to, 'c');
  assert.equal(d.toPercent, 5);
  assert.equal(d.fromPercent, 97);
  assert.equal(d.fromLabel, 'Current week (Fable)');
});

// The judgment errors this module exists to prevent:

test('less dead is not fresh: no switch when the best candidate is also hot', () => {
  const d = decideSwitch({
    active: acct('a', lim(96)), candidates: [acct('b', lim(88))], now: NOW,
  });
  assert.equal(d, null, 'switching 96 to 88 buys minutes and spends the cooldown');
});

test('the cooldown blocks a second switch, whatever the numbers say', () => {
  const d = decideSwitch({
    active: acct('a', lim(100)), candidates: [acct('b', lim(0))],
    now: NOW, lastSwitchAt: NOW - COOLDOWN_MS + 1000,
  });
  assert.equal(d, null);
});

test('after the cooldown the same situation switches', () => {
  const d = decideSwitch({
    active: acct('a', lim(100)), candidates: [acct('b', lim(0))],
    now: NOW, lastSwitchAt: NOW - COOLDOWN_MS - 1000,
  });
  assert.equal(d.to, 'b');
});

test('a candidate with no numbers is not a candidate', () => {
  const d = decideSwitch({
    active: acct('a', lim(99)), candidates: [{ slug: 'b', email: 'b@x', limits: [] }], now: NOW,
  });
  assert.equal(d, null, 'unknown headroom must not be mistaken for headroom');
});

test('the active account is never its own candidate', () => {
  const d = decideSwitch({
    active: acct('a', lim(99)), candidates: [acct('a', lim(0))], now: NOW,
  });
  assert.equal(d, null);
});

test('no candidates at all is a quiet no', () => {
  assert.equal(decideSwitch({ active: acct('a', lim(99)), candidates: [], now: NOW }), null);
});

test('a session window at 100 triggers even with the week barely used', () => {
  const d = decideSwitch({
    active: acct('a', lim(100, 'Current session'), lim(12, 'Current week')),
    candidates: [acct('b', lim(3, 'Current session'), lim(20, 'Current week'))],
    now: NOW,
  });
  assert.equal(d.to, 'b');
  assert.equal(d.fromLabel, 'Current session');
});

// ---- headroom for an account whose own token can no longer be asked ---------
//
// Why the switcher had never fired once in its life (found 2026-08-03). A stored
// access token expires within hours, so /usage answers for the ACTIVE account and
// for nothing else — every candidate reported unknown headroom, was skipped, and
// the switcher had nothing to switch to no matter how spent the active one got.

const { agedLimits, explain } = require('../lib/autoswitch');
const iso = (ms) => new Date(ms).toISOString();

test('a limit whose window has rolled over is back to zero', () => {
  // Not an estimate: nothing ran against that account while it sat idle.
  const aged = agedLimits({ limits: [{ kind: 'weekly_all', percent: 92, resetsAt: iso(NOW - 1000) }] }, NOW);
  assert.equal(aged[0].percent, 0);
  assert.equal(aged[0].reset, true);
});

test('a limit still inside its window keeps its figure', () => {
  // An idle account cannot have got WORSE, so carrying the old number forward
  // can only make it look less fresh than it is. That is the safe direction.
  const aged = agedLimits({ limits: [{ kind: 'weekly_all', percent: 92, resetsAt: iso(NOW + 60_000) }] }, NOW);
  assert.equal(aged[0].percent, 92);
  assert.equal(aged[0].reset, false);
});

test('an exceeded window that has since reset is no longer exceeded', () => {
  const aged = agedLimits({ limits: [{ percent: 100, severity: 'exceeded', resetsAt: iso(NOW - 1) }] }, NOW);
  assert.equal(worstLimit(aged).percent, 0, 'severity must be cleared with the figure');
});

test('a limit with no reset time keeps its figure rather than being wished away', () => {
  const aged = agedLimits({ limits: [{ percent: 88, resetsAt: null }] }, NOW);
  assert.equal(aged[0].percent, 88);
});

test('no snapshot is no headroom, not zero headroom', () => {
  // Assuming an unknown account is fresh would switch onto a spent one.
  assert.deepEqual(agedLimits(null, NOW), []);
  assert.deepEqual(agedLimits({ limits: null }, NOW), []);
  assert.equal(worstLimit(agedLimits(null, NOW)), null);
});

test('a spent account switches to a candidate priced from a reset snapshot', () => {
  // End to end: this is the case that could not happen before.
  const stale = { limits: [{ kind: 'weekly_all', percent: 96, label: 'Current week', resetsAt: iso(NOW - 86_400_000) }] };
  const d = decideSwitch({
    active: acct('a', lim(97)),
    candidates: [{ slug: 'b', email: 'b@x', limits: agedLimits(stale, NOW) }],
    now: NOW,
  });
  assert.ok(d, 'the switcher can finally act');
  assert.equal(d.to, 'b');
  assert.equal(d.toPercent, 0);
});

// ---- saying why nothing happened -------------------------------------------

test('an idle switcher explains itself in the operator terms', () => {
  assert.match(
    explain({ active: acct('a', lim(54, 'Current week')), candidates: [acct('b', lim(0))], now: NOW }),
    /54%.*below the 95% threshold/,
  );
  assert.match(
    explain({ active: acct('a', lim(97)), candidates: [{ slug: 'b', email: 'b@x', limits: [] }], now: NOW }),
    /no headroom known/,
  );
  assert.match(
    explain({ active: acct('a', lim(97)), candidates: [acct('b', lim(90))], now: NOW }),
    /not below the 75%/,
  );
  assert.match(
    explain({ active: acct('a', lim(97)), candidates: [], now: NOW, lastSwitchAt: NOW - 60_000 }),
    /cooling down/,
  );
});

test('a raised threshold is honoured by both the decision and its explanation', () => {
  // The owner calls 54% "high"; the code calls 95% high. That is taste, so it is
  // a knob — and the two must not be able to disagree about where it is set.
  const args = { active: acct('a', lim(60)), candidates: [acct('b', lim(5))], now: NOW, threshold: 50 };
  assert.ok(decideSwitch(args), 'fires once the bar is where the owner wants it');
  assert.equal(decideSwitch({ ...args, threshold: 95 }), null);
  assert.match(explain({ ...args, threshold: 95 }), /below the 95% threshold/);
});
