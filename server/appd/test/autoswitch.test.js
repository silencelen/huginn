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
