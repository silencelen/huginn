'use strict';
// A project's soft budget (lib/projectbudget.js): a week's spend from a ledger,
// thresholds said once a week, never a stop.

const { test } = require('node:test');
const assert = require('node:assert');
const b = require('../lib/projectbudget');

const DAY = 24 * 3600 * 1000;

test('a budget is {weeklyUsd} with a positive figure, or null', () => {
  for (const ok of [null, { weeklyUsd: 50 }, { weeklyUsd: null }]) assert.equal(null, b.budgetProblem(ok), JSON.stringify(ok));
  for (const bad of [{}, { weeklyUsd: 0 }, { weeklyUsd: -1 }, { weeklyUsd: '50' }, { weeklyUsd: 1e9 }, 50, []]) {
    assert.match(b.budgetProblem(bad), /budget/, JSON.stringify(bad));
  }
  assert.deepEqual({ weeklyUsd: 12.35 }, b.cleanBudget({ weeklyUsd: 12.345 }));
  assert.equal(null, b.cleanBudget({ weeklyUsd: null }));
});

test('the week starts at the first look: what was spent before it is not this week\'s', () => {
  const s1 = b.budgetStep(null, [{ key: 'api|1', usd: 40 }], { now: 0, weeklyUsd: 10 });
  assert.equal(0, s1.spentUsd);
  assert.deepEqual([], s1.crossed);
  const s2 = b.budgetStep(s1.ledger, [{ key: 'api|1', usd: 45 }, { key: 'web|2', usd: 1 }], { now: DAY, weeklyUsd: 10 });
  assert.equal(6, s2.spentUsd, '5 more from api, and all of web (it joined this week)');
  assert.equal(60, s2.percent);
  assert.deepEqual([50], s2.crossed);
  const s3 = b.budgetStep(s2.ledger, [{ key: 'api|1', usd: 49 }], { now: 2 * DAY, weeklyUsd: 10 });
  assert.equal(10, s3.spentUsd, 'web LEFT the record and its spend still counts');
  assert.deepEqual([80, 100], s3.crossed);
  const s4 = b.budgetStep(s3.ledger, [{ key: 'api|1', usd: 60 }], { now: 3 * DAY, weeklyUsd: 10 });
  assert.deepEqual([], s4.crossed, 'each threshold once a week');
  // A new week: base is what everyone had spent; the thresholds may be said again.
  const s5 = b.budgetStep(s4.ledger, [{ key: 'api|1', usd: 66 }], { now: 8 * DAY, weeklyUsd: 10 });
  assert.equal(b.WEEK_MS, s5.weekStart);
  assert.equal(6, s5.spentUsd);
  assert.deepEqual([50], s5.crossed);
  // A member that could not be priced is left out, not counted as zero.
  assert.equal(6, b.budgetStep(s5.ledger, [{ key: 'api|1', usd: null }], { now: 9 * DAY, weeklyUsd: 10 }).spentUsd);
});

test('the lead\'s line and the owner\'s push say it is soft and offer no verb', () => {
  const p = { id: 'p1', name: 'Stick' };
  const at = { percent: 80, spentUsd: 8, weeklyUsd: 10, weekStart: 0 };
  assert.match(b.budgetLine(p, at), /^Project "Stick" has used 80% of its weekly budget \(about \$8\.00 of \$10\.00, estimated\)\..*nothing is stopped\.$/);
  const push = b.budgetPush(p, at);
  assert.equal('project_budget', push.kind);
  assert.deepEqual([], push.options);
  assert.equal('p1', push.payload.projectId);
  assert.equal('p1:budget:0:80', push.fingerprint);
});
