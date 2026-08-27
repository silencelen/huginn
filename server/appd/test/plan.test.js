'use strict';
// The fixture is the real /api/oauth/usage response captured on huginn
// (2026-08-26), verbatim — including the nulled experiment keys, the one
// experiment key that is NOT null, and an extra-usage block that is switched off
// while $100.55 of it has already been spent. That last combination is the whole
// reason this file was re-captured: the 2026-07-27 fixture it replaces had the
// same shape and the suite still read it as "nothing to show".

const { test } = require('node:test');
const assert = require('node:assert');
const { normalizePlan } = require('../lib/plan');

const REAL = {
  five_hour: { utilization: 17.0, resets_at: '2026-08-27T07:50:00.296153+00:00', limit_dollars: null, used_dollars: null, remaining_dollars: null },
  seven_day: { utilization: 50.0, resets_at: '2026-08-31T17:00:00.296172+00:00', limit_dollars: null, used_dollars: null, remaining_dollars: null },
  seven_day_oauth_apps: null,
  seven_day_opus: null,
  seven_day_sonnet: null,
  seven_day_cowork: null,
  seven_day_omelette: null,
  tangelo: null,
  iguana_necktie: null,
  omelette_promotional: null,
  // Not null, and carrying a utilization — the reason experiment keys are
  // ignored by omission rather than by a null check.
  nimbus_quill: { utilization: 0.0, resets_at: null, limit_dollars: null, used_dollars: null, remaining_dollars: null },
  cinder_cove: null,
  amber_ladder: null,
  extra_usage: {
    is_enabled: false,
    monthly_limit: 10000,
    used_credits: 10055.0,
    utilization: 100.0,
    currency: 'USD',
    decimal_places: 2,
    disabled_reason: 'org_level_disabled_until',
    user_disabled: false,
    spend_limit_reached: true,
    credits_ever_enabled: true,
    daily: null,
    weekly: null,
  },
  limits: [
    { kind: 'session', group: 'session', percent: 17, severity: 'normal', resets_at: '2026-08-27T07:50:00.296153+00:00', scope: null, is_active: false },
    { kind: 'weekly_all', group: 'weekly', percent: 50, severity: 'normal', resets_at: '2026-08-31T17:00:00.296172+00:00', scope: null, is_active: true },
    { kind: 'weekly_scoped', group: 'weekly', percent: 50, severity: 'normal', resets_at: '2026-08-31T17:00:00.296422+00:00', scope: { model: { id: null, display_name: 'Fable' }, surface: null }, is_active: false },
  ],
  spend: {
    used: { amount_minor: 10055, currency: 'USD', exponent: 2 },
    limit: { amount_minor: 10000, currency: 'USD', exponent: 2 },
    percent: 100,
    severity: 'critical',
    enabled: false,
    disabled_reason: 'org_level_disabled_until',
    cap: { money: null, credits: { amount_minor: 10000, exponent: 2 } },
    balance: null,
    auto_reload: null,
    disclaimer: 'Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)',
    can_purchase_credits: false,
    can_toggle: false,
  },
  member_dashboard_available: false,
};

test('the real payload yields the three rows /usage shows', () => {
  const p = normalizePlan(REAL);
  assert.strictEqual(p.limits.length, 3);
  assert.deepStrictEqual(p.limits.map((l) => l.label), [
    'Current session',
    'Current week, all models',
    'Current week (Fable)',
  ]);
  assert.deepStrictEqual(p.limits.map((l) => l.percent), [17, 50, 50]);
  assert.strictEqual(p.limits[1].isActive, true);
  assert.ok(p.limits[0].resetsAt.startsWith('2026-08-27'));
});

test('an experiment key carrying a real utilization is still not a limit row', () => {
  // nimbus_quill arrives as an object with utilization 0. A rule that read every
  // non-null key would put a 0% row on the screen for an experiment nobody is in.
  const p = normalizePlan(REAL);
  assert.deepStrictEqual(p.limits.map((l) => l.kind), ['session', 'weekly_all', 'weekly_scoped']);
});

test('extra usage survives being switched off after the money was spent', () => {
  // The capture itself: credits disabled at the org for the rest of the month,
  // $100.55 already owed. The old is_enabled gate returned null here, which is
  // exactly the bill the owner could not see.
  const p = normalizePlan(REAL);
  assert.ok(p.extraUsage, 'extra usage must survive a disabled-after-spend account');
  assert.strictEqual(p.extraUsage.usedCredits, 10055);
  assert.strictEqual(p.extraUsage.monthlyLimit, 10000);
  assert.strictEqual(p.extraUsage.spendLimitReached, true);
  assert.strictEqual(p.extraUsage.isEnabled, false);
  assert.strictEqual(p.extraUsage.creditsEverEnabled, true);
  assert.strictEqual(p.extraUsage.disabledReason, 'org_level_disabled_until');
  assert.strictEqual(p.extraUsage.userDisabled, false);
  assert.strictEqual(p.extraUsage.decimalPlaces, 2);
});

test('an account that never enabled credits still shows nothing', () => {
  // It reads 100% used / limit reached against a limit it does not have, which
  // would be a false alarm. This is the case the original gate defended.
  const never = {
    ...REAL,
    extra_usage: { ...REAL.extra_usage, credits_ever_enabled: false, disabled_reason: null },
  };
  assert.strictEqual(normalizePlan(never).extraUsage, null);
  // The spend block is independent of that gate and still parses.
  const spend = normalizePlan(never).spend;
  assert.ok(spend);
  assert.strictEqual(spend.enabled, false);
});

test('a payload with no credits history at all yields no extra usage', () => {
  // credits_ever_enabled absent (an older daemon's payload) is not "true".
  const p = normalizePlan({ ...REAL, extra_usage: { is_enabled: false, utilization: 100 } });
  assert.strictEqual(p.extraUsage, null);
});

test('extra usage is surfaced while it is switched on', () => {
  const p = normalizePlan({ ...REAL, extra_usage: { ...REAL.extra_usage, is_enabled: true, disabled_reason: null } });
  assert.ok(p.extraUsage);
  assert.strictEqual(p.extraUsage.utilization, 100);
  assert.strictEqual(p.extraUsage.isEnabled, true);
  assert.strictEqual(p.extraUsage.disabledReason, null);
});

test('spend keeps money in minor units with its own exponent', () => {
  const s = normalizePlan(REAL).spend;
  assert.strictEqual(s.usedMinor, 10055);
  assert.strictEqual(s.limitMinor, 10000);
  assert.strictEqual(s.exponent, 2);
  assert.strictEqual(s.currency, 'USD');
  assert.strictEqual(s.percent, 100);
  assert.strictEqual(s.severity, 'critical');
  assert.strictEqual(s.enabled, false);
  assert.strictEqual(s.disabledReason, 'org_level_disabled_until');
  assert.strictEqual(s.canPurchaseCredits, false);
  assert.strictEqual(s.canToggle, false);
});

test('a zero-exponent currency is carried unscaled', () => {
  // 5000 JPY is 5000 yen, not 50.00 of anything — the exponent is per currency
  // and this is why the division does not happen here.
  const p = normalizePlan({
    limits: [],
    spend: { used: { amount_minor: 5000, currency: 'JPY', exponent: 0 }, percent: 25, enabled: true },
  });
  assert.strictEqual(p.spend.usedMinor, 5000);
  assert.strictEqual(p.spend.exponent, 0);
  assert.strictEqual(p.spend.currency, 'JPY');
  assert.strictEqual(p.spend.limitMinor, null);
  assert.strictEqual(p.spend.enabled, true);
});

test('a spend block with no amounts still reports its flags', () => {
  const s = normalizePlan({ spend: { percent: null, enabled: false, can_toggle: true } }).spend;
  assert.strictEqual(s.usedMinor, null);
  assert.strictEqual(s.limitMinor, null);
  assert.strictEqual(s.exponent, 2);
  assert.strictEqual(s.currency, 'USD');
  assert.strictEqual(s.percent, null);
  assert.strictEqual(s.canToggle, true);
});

test('a payload with no limits array falls back to the top-level shape', () => {
  const p = normalizePlan({ five_hour: REAL.five_hour, seven_day: REAL.seven_day });
  assert.deepStrictEqual(p.limits.map((l) => l.percent), [17, 50]);
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
  assert.strictEqual(normalizePlan({}).spend, null);
  assert.strictEqual(normalizePlan({ spend: 'nope' }).spend, null);
});
