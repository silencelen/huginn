'use strict';
// Normalizes `ccusage daily --json` into what the app shows.
//
// Split out and tested because the field names are not the obvious ones: the day
// is `period` (not `date`), rows carry their own `totalTokens`, and the range
// total comes from a top-level `totals` object. Guessing `date` here silently
// produced a null "today" with everything else looking fine.

/** @param parsed ccusage JSON  @param todayYmd e.g. "20260727" */
function summarizeUsage(parsed, todayYmd) {
  const days = Array.isArray(parsed && parsed.daily) ? parsed.daily : [];
  const rows = days.map(normalizeDay);
  const totals = (parsed && parsed.totals) || {};
  const isToday = (r) => String(r.date || '').replace(/-/g, '') === String(todayYmd);
  return {
    today: rows.find(isToday) ?? null,
    week: {
      days: rows.length,
      totalTokens: totals.totalTokens ?? rows.reduce((a, b) => a + b.totalTokens, 0),
      inputTokens: totals.inputTokens ?? 0,
      outputTokens: totals.outputTokens ?? 0,
      cacheReadTokens: totals.cacheReadTokens ?? 0,
      cacheCreationTokens: totals.cacheCreationTokens ?? 0,
      costUsd: totals.totalCost ?? null,
    },
    daily: rows,
  };
}

function normalizeDay(d) {
  const input = d.inputTokens ?? 0;
  const output = d.outputTokens ?? 0;
  const cacheCreate = d.cacheCreationTokens ?? 0;
  const cacheRead = d.cacheReadTokens ?? 0;
  return {
    date: d.period ?? d.date ?? null,
    inputTokens: input,
    outputTokens: output,
    cacheCreationTokens: cacheCreate,
    cacheReadTokens: cacheRead,
    totalTokens: d.totalTokens ?? (input + output + cacheCreate + cacheRead),
    costUsd: d.totalCost ?? d.cost ?? null,
  };
}

module.exports = { summarizeUsage, normalizeDay };
