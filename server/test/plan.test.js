'use strict';
// The fixture is the real /api/oauth/usage response captured on huginn
// (2026-07-27), including the nulled experiment keys, so an unfamiliar future
// key cannot quietly break the screen.

const { test } = require('node:test');
const assert = require('node:assert');
const { normalizePlan } = require('../lib/plan');

const REAL = {
  five_hour: { utilization: 8.0, resets_at: '2026-07-27T19:00:00.545778+00:00', limit_dollars: null },
  seven_day: { utilization: 12.0, resets_at: '2026-08-03T08:00:00.545801+00:00', limit_dollars: null },
  seven_day_oauth_apps: null, seven_day_opus: null, tangelo: null, iguana_necktie: null,
  extra_usage: {
    is_enabled: false, monthly_limit: 2000, used_credits: 2092.0, utilization: 100.0,
    currency: 'USD', spend_limit_reached: true,
  },
  limits: [
    { kind: 'session', group: 'session', percent: 8, severity: 'normal', resets_at: '2026-07-27T19:00:00.545778+00:00', scope: null, is_active: false },
    { kind: 'weekly_all', group: 'weekly', percent: 12, severity: 'normal', resets_at: '2026-08-03T08:00:00.545801+00:00', scope: null, is_active: false },
    { kind: 'weekly_scoped', group: 'weekly', percent: 19, severity: 'normal', resets_at: '2026-08-03T07:59:59.546119+00:00', scope: { model: { id: null, display_name: 'Fable' }, surface: null }, is_active: true },
  ],
};

test('the real payload yields the three rows /usage shows', () => {
  const p = normalizePlan(REAL);
  assert.strictEqual(p.limits.length, 3);
  assert.deepStrictEqual(p.limits.map((l) => l.label), [
    'Current session',
    'Current week, all models',
    'Current week (Fable)',
  ]);
  assert.deepStrictEqual(p.limits.map((l) => l.percent), [8, 12, 19]);
  assert.strictEqual(p.limits[2].isActive, true);
  assert.ok(p.limits[0].resetsAt.startsWith('2026-07-27'));
});

test('extra usage is hidden while it is disabled', () => {
  // It reads 100% used / limit reached, which would be alarming and irrelevant
  // on an account where the feature is switched off.
  assert.strictEqual(normalizePlan(REAL).extraUsage, null);
});

test('extra usage is surfaced once enabled', () => {
  const p = normalizePlan({ ...REAL, extra_usage: { ...REAL.extra_usage, is_enabled: true } });
  assert.ok(p.extraUsage);
  assert.strictEqual(p.extraUsage.utilization, 100);
  assert.strictEqual(p.extraUsage.spendLimitReached, true);
});

test('a payload with no limits array falls back to the top-level shape', () => {
  const p = normalizePlan({ five_hour: REAL.five_hour, seven_day: REAL.seven_day });
  assert.deepStrictEqual(p.limits.map((l) => l.percent), [8, 12]);
  assert.deepStrictEqual(p.limits.map((l) => l.kind), ['session', 'weekly_all']);
});

test('rows without a percent are dropped rather than shown as zero', () => {
  const p = normalizePlan({ limits: [{ kind: 'session', percent: null }, { kind: 'weekly_all', percent: 5 }] });
  assert.strictEqual(p.limits.length, 1);
  assert.strictEqual(p.limits[0].percent, 5);
});

test('an unknown limit kind still renders with its own name', () => {
  const p = normalizePlan({ limits: [{ kind: 'monthly_experiment', percent: 3 }] });
  assert.strictEqual(p.limits[0].label, 'monthly_experiment');
});

test('empty and malformed payloads do not throw', () => {
  assert.deepStrictEqual(normalizePlan({}).limits, []);
  assert.deepStrictEqual(normalizePlan(null).limits, []);
  assert.deepStrictEqual(normalizePlan({ limits: 'nope' }).limits, []);
});
