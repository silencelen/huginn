'use strict';
// Field names taken from real `ccusage daily --json` output on huginn
// (2026-07-27). The first version of this code guessed `date` and produced a
// silently-null "today", which is why the real shape is pinned here.

const { test } = require('node:test');
const assert = require('node:assert');
const { summarizeUsage, normalizeDay } = require('../lib/usage');

// Verbatim field set from a real row (values reduced, keys untouched).
const realRow = {
  agent: 'all',
  period: '2026-07-27',
  inputTokens: 3681,
  outputTokens: 2215065,
  cacheCreationTokens: 9357659,
  cacheReadTokens: 200080568,
  totalTokens: 219388151,
  totalCost: 335.8815632499998,
  modelsUsed: ['claude-fable-5'],
  modelBreakdowns: [],
  metadata: { agents: ['claude'] },
};

test('the day comes from `period`, which is what ccusage actually emits', () => {
  assert.strictEqual(normalizeDay(realRow).date, '2026-07-27');
  // A payload using `date` (older/other shape) still works.
  assert.strictEqual(normalizeDay({ date: '2026-07-01' }).date, '2026-07-01');
});

test('today is found in a real multi-day range', () => {
  const parsed = {
    daily: [
      { ...realRow, period: '2026-07-26', totalTokens: 102989886, totalCost: 98.99 },
      realRow,
    ],
    totals: {
      inputTokens: 4453, outputTokens: 2215065, cacheCreationTokens: 13484043,
      cacheReadTokens: 306109029, totalTokens: 321812590, totalCost: 434.53,
    },
  };
  const s = summarizeUsage(parsed, '20260727');
  assert.ok(s.today, 'today must be found');
  assert.strictEqual(s.today.date, '2026-07-27');
  assert.strictEqual(s.today.totalTokens, 219388151);
  assert.strictEqual(s.week.days, 2);
});

test('the range total comes from `totals`, not a re-sum of the rows', () => {
  const parsed = {
    daily: [{ ...realRow }],
    totals: { totalTokens: 999, totalCost: 1.5, cacheReadTokens: 5 },
  };
  const s = summarizeUsage(parsed, '20260101');
  assert.strictEqual(s.week.totalTokens, 999);
  assert.strictEqual(s.week.costUsd, 1.5);
  assert.strictEqual(s.week.cacheReadTokens, 5);
});

test('a missing totals block falls back to summing the rows', () => {
  const s = summarizeUsage({ daily: [{ period: 'a', totalTokens: 10 }, { period: 'b', totalTokens: 5 }] }, 'x');
  assert.strictEqual(s.week.totalTokens, 15);
  assert.strictEqual(s.week.costUsd, null);
});

test('a row without totalTokens is summed from its parts', () => {
  const r = normalizeDay({ inputTokens: 1, outputTokens: 2, cacheCreationTokens: 3, cacheReadTokens: 4 });
  assert.strictEqual(r.totalTokens, 10);
});

test('empty or malformed payloads do not throw', () => {
  assert.deepStrictEqual(summarizeUsage({}, '20260727').daily, []);
  assert.deepStrictEqual(summarizeUsage(null, '20260727').daily, []);
  assert.strictEqual(summarizeUsage({ daily: 'nope' }, '20260727').today, null);
});

test('dates are matched regardless of dashes', () => {
  const s = summarizeUsage({ daily: [{ period: '2026-07-27', totalTokens: 1 }] }, '20260727');
  assert.ok(s.today);
});
