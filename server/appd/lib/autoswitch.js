'use strict';
// Rotating to a fresher Claude account when the active one runs dry.
//
// The owner keeps three Max accounts precisely so a hard limit is never a hard
// stop; until now the rotation was a manual trip to Settings, usually made at
// the exact moment a limit-hit made everything else stall too. The daemon can
// see every saved account's utilization (each stores its own token), so it can
// make the same move by itself at the same threshold a person would.
//
// The DECISION lives here, pure and tested, because the failure modes are all
// judgment errors rather than plumbing errors: switching too eagerly (burning a
// fresh account on a limit that resets in twenty minutes is fine — but
// ping-ponging between two hot accounts is not), switching to an account that
// is nearly as spent, or switching twice in quick succession because the first
// switch had not propagated into the numbers yet.

/** Do not switch unless the active account's binding limit is at least this. */
const THRESHOLD = 95;

/**
 * A candidate must be meaningfully fresher, not merely less dead. Switching
 * 96% -> 88% buys minutes and spends the cooldown; it is not worth doing.
 */
const MARGIN = 20;

/** Minimum gap between switches, so a misjudgment cannot oscillate. */
const COOLDOWN_MS = 30 * 60 * 1000;

/**
 * The binding constraint: whichever limit is fullest. A weekly window at 100%
 * blocks just as hard as a session window at 100%, so all limits compete.
 */
function worstLimit(limits) {
  let worst = null;
  for (const l of limits || []) {
    const pct = l.severity === 'exceeded' ? 100 : l.percent;
    if (typeof pct !== 'number') continue;
    if (!worst || pct > worst.percent) worst = { percent: pct, label: l.label || l.kind || 'limit' };
  }
  return worst;
}

/**
 * @param active     {slug, email, limits}
 * @param candidates [{slug, email, limits}] — the OTHER saved accounts
 * @returns {{to, toEmail, toPercent, from, fromEmail, fromPercent, fromLabel}|null}
 */
function decideSwitch({ active, candidates, now, lastSwitchAt = 0,
  threshold = THRESHOLD, margin = MARGIN, cooldownMs = COOLDOWN_MS }) {
  if (!active) return null;
  if (now - lastSwitchAt < cooldownMs) return null;

  const w = worstLimit(active.limits);
  if (!w || w.percent < threshold) return null;

  let best = null;
  for (const c of candidates || []) {
    if (!c || c.slug === active.slug) continue;
    const cw = worstLimit(c.limits);
    if (!cw) continue;                              // no numbers: not a candidate
    if (!best || cw.percent < best.percent) best = { ...c, percent: cw.percent };
  }
  if (!best) return null;
  if (best.percent > threshold - margin) return null;   // less dead is not fresh

  return {
    to: best.slug,
    toEmail: best.email ?? null,
    toPercent: best.percent,
    from: active.slug,
    fromEmail: active.email ?? null,
    fromPercent: w.percent,
    fromLabel: w.label,
  };
}

module.exports = { decideSwitch, worstLimit, THRESHOLD, MARGIN, COOLDOWN_MS };
