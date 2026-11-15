'use strict';
// A project's soft budget: what its sessions spent this week against a weekly
// figure the owner set, and the thresholds that are said once a week. Pure; the
// daemon keeps the ledger beside the project and does the saying.
//
// Owner decision 2026-11-15: a SOFT budget — a note to the lead and an alert to
// the owner at thresholds, never a hard stop. The unit is the dashboard's own:
// the estimated API-price cost (lib/pricing.js) of what the members' transcripts
// record, which weights cache reads, cache writes and output the way the bill
// does. On a subscription that is a measure of how heavy the work is, not money
// owed.
//
// ⚠ THE WEEK IS A LEDGER, NOT A SUBTRACTION OF TOTALS. A project's total moves
// down when a member leaves the record (its transcript stops being summed), so
// "total now minus total at the start of the week" can go backwards. The ledger
// keeps, per member, what it had spent when the week began (`base`) and the most
// it has been seen at since (`last`); a member that left keeps its `last`, and a
// member that joined mid-week has a base of zero.

const WEEK_MS = 7 * 24 * 3600 * 1000;
const THRESHOLDS = [50, 80, 100];
const MAX_WEEKLY_USD = 100_000;

/** Why this cannot be a project's budget, or null. `null` (no budget) is allowed. */
function budgetProblem(b) {
  if (b === null) return null;
  if (!b || typeof b !== 'object' || Array.isArray(b)) return 'budget is {weeklyUsd} or null';
  const w = b.weeklyUsd;
  if (w !== null && (typeof w !== 'number' || !Number.isFinite(w) || w <= 0 || w > MAX_WEEKLY_USD)) {
    return `budget.weeklyUsd is a positive number up to ${MAX_WEEKLY_USD}, or null for none`;
  }
  return null;
}

/** The budget as stored: `{weeklyUsd}` or null. */
function cleanBudget(b) {
  if (!b || typeof b !== 'object' || typeof b.weeklyUsd !== 'number') return null;
  return { weeklyUsd: Math.round(b.weeklyUsd * 100) / 100 };
}

function emptyLedger(now) { return { v: 1, start: now, base: {}, last: {}, said: [] }; }

/**
 * One look: the ledger carried forward, the week's spend, its share of the
 * budget, and the thresholds crossed for the first time this week.
 *
 * `members` are `{key, usd}` with `usd` the member's whole spend so far (null
 * when it could not be priced: left out). A new week starts `WEEK_MS` after the
 * last one began; its base is what everyone had spent at that moment.
 */
function budgetStep(ledger, members, { now, weeklyUsd }) {
  let l = ledger && ledger.v === 1 ? { ...ledger, base: { ...ledger.base }, last: { ...ledger.last }, said: [...(ledger.said || [])] } : null;
  const priced = (members || []).filter((m) => m && m.key && Number.isFinite(m.usd));
  if (!l) {
    // The first look: the week begins now, and what was spent before it is not this week's.
    l = emptyLedger(now);
    for (const m of priced) { l.base[m.key] = m.usd; l.last[m.key] = m.usd; }
  } else if (now - l.start >= WEEK_MS) {
    const start = l.start + Math.floor((now - l.start) / WEEK_MS) * WEEK_MS;
    const next = emptyLedger(start);
    for (const [k, v] of Object.entries(l.last)) { next.base[k] = v; next.last[k] = v; }
    l = next;
  }
  for (const m of priced) {
    if (!(m.key in l.base)) l.base[m.key] = 0;   // joined this week: all of it is this week's
    l.last[m.key] = Math.max(l.last[m.key] ?? 0, m.usd);
  }
  let spent = 0;
  for (const [k, v] of Object.entries(l.last)) spent += Math.max(0, v - (l.base[k] ?? 0));
  spent = Math.round(spent * 100) / 100;
  const percent = weeklyUsd ? Math.round((spent / weeklyUsd) * 100) : null;
  const crossed = [];
  if (percent !== null) {
    for (const t of THRESHOLDS) {
      if (percent >= t && !l.said.includes(t)) { l.said.push(t); crossed.push(t); }
    }
  }
  return { ledger: l, spentUsd: spent, percent, crossed, weekStart: l.start };
}

/** What the lead is told when a threshold is crossed: the daemon's words. */
function budgetLine(project, { percent, spentUsd, weeklyUsd }) {
  return `Project "${String(project.name || '').replace(/"/g, "'")}" has used ${percent}% of its weekly budget `
    + `(about $${spentUsd.toFixed(2)} of $${weeklyUsd.toFixed(2)}, estimated). Keep messages few and contexts small; `
    + 'the budget is soft and nothing is stopped.';
}

/** The owner's alert: opens the project, offers no verb. */
function budgetPush(project, { percent, spentUsd, weeklyUsd, weekStart }) {
  return {
    kind: 'project_budget',
    key: `project:${project.id}:budget`,
    subject: project.id,
    title: `${project.name} — ${percent}% of its weekly budget`,
    text: `About $${spentUsd.toFixed(2)} of $${weeklyUsd.toFixed(2)} this week (estimated). The lead has been told; nothing is stopped.`,
    options: [],
    payload: { projectId: project.id, percent, spentUsd, weeklyUsd },
    fingerprint: `${project.id}:budget:${weekStart}:${percent >= 100 ? 100 : percent >= 80 ? 80 : 50}`,
  };
}

module.exports = {
  WEEK_MS, THRESHOLDS, MAX_WEEKLY_USD,
  budgetProblem, cleanBudget, emptyLedger, budgetStep, budgetLine, budgetPush,
};
