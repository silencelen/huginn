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
 * Headroom for an account whose own token can no longer be asked.
 *
 * A stored access token expires within hours and this daemon does not implement
 * the refresh flow, so /usage is readable for the ACTIVE account and for nothing
 * else. Before this, every candidate therefore had unknown headroom and was
 * skipped — which meant the switcher had nothing to switch to and could never
 * fire, no matter how spent the active account got.
 *
 * The last reading taken while that account WAS active is enough, because an
 * account nobody is using does not accrue usage:
 *
 *   * a limit whose window has since rolled over is back to zero — certain, not
 *     estimated, since nothing ran against it in the meantime, and
 *   * a limit still inside its window is at most what it was, so carrying the old
 *     figure forward can only make the candidate look WORSE than it is.
 *
 * Both directions are conservative: this can pass over a fresh account, never
 * switch to a spent one.
 */
function agedLimits(snapshot, now = Date.now()) {
  const rows = (snapshot && Array.isArray(snapshot.limits)) ? snapshot.limits : [];
  return rows.map((l) => {
    const resets = l.resetsAt ? Date.parse(l.resetsAt) : NaN;
    if (Number.isFinite(resets) && resets <= now) {
      return { ...l, percent: 0, severity: 'normal', reset: true };
    }
    return { ...l, reset: false };
  });
}

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

/**
 * Why a tick did nothing, in the same order decideSwitch asks the questions.
 * The switcher is invisible by design until it acts, which makes "it never
 * fires" impossible to tell apart from "it is not needed yet" — so it says.
 */
function explain({ active, candidates, now, lastSwitchAt = 0,
  threshold = THRESHOLD, margin = MARGIN, cooldownMs = COOLDOWN_MS }) {
  if (!active) return 'no active account is identifiable';
  const wait = cooldownMs - (now - lastSwitchAt);
  if (wait > 0) return `cooling down for another ${Math.ceil(wait / 60000)} min`;
  const w = worstLimit(active.limits);
  if (!w) return 'the active account reports no usage figures';
  if (w.percent < threshold) return `active account at ${w.percent}% (${w.label}), below the ${threshold}% threshold`;
  const priced = (candidates || []).filter((c) => c && c.slug !== active.slug && worstLimit(c.limits));
  if (!priced.length) {
    const others = (candidates || []).filter((c) => c && c.slug !== active.slug).length;
    return others
      ? `no headroom known for any of the ${others} other account(s) — none has been active since the daemon started recording it`
      : 'no other account is saved';
  }
  const best = priced.reduce((a, b) => (worstLimit(b.limits).percent < worstLimit(a.limits).percent ? b : a));
  const bp = worstLimit(best.limits).percent;
  return `freshest alternative is ${best.email || best.slug} at ${bp}%, not below the ${threshold - margin}% a switch is worth`;
}

module.exports = { decideSwitch, worstLimit, agedLimits, explain, THRESHOLD, MARGIN, COOLDOWN_MS };
