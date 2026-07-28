'use strict';
// Normalizes the plan-utilization payload behind Claude Code's `/usage` into the
// few things worth showing on a phone.
//
// The response carries a `limits` array (the same rows `/usage` renders) plus a
// pile of nulled experiment keys; read `limits` and ignore the rest, so an
// unfamiliar future key cannot break the screen.

const LABELS = {
  session: 'Current session',
  weekly_all: 'Current week, all models',
  weekly_scoped: 'Current week',
};

/** @param body parsed /api/oauth/usage response */
function normalizePlan(body) {
  const rows = Array.isArray(body && body.limits) ? body.limits : [];
  const limits = rows.map((l) => {
    const model = l.scope && l.scope.model && l.scope.model.display_name;
    const base = LABELS[l.kind] || l.kind || 'Limit';
    return {
      kind: l.kind ?? null,
      group: l.group ?? null,
      // "Current week (Fable)" reads better than "weekly_scoped".
      label: model ? `${base} (${model})` : base,
      percent: typeof l.percent === 'number' ? l.percent : null,
      severity: l.severity ?? 'normal',
      resetsAt: l.resets_at ?? null,
      isActive: l.is_active === true,
    };
  }).filter((l) => l.percent !== null);

  // Fall back to the older top-level shape if `limits` is ever absent.
  if (!limits.length && body) {
    for (const [key, kind] of [['five_hour', 'session'], ['seven_day', 'weekly_all']]) {
      const v = body[key];
      if (v && typeof v.utilization === 'number') {
        limits.push({
          kind,
          group: kind === 'session' ? 'session' : 'weekly',
          label: LABELS[kind],
          percent: v.utilization,
          severity: 'normal',
          resetsAt: v.resets_at ?? null,
          isActive: false,
        });
      }
    }
  }

  // Extra usage is only meaningful when the account actually has it switched on.
  const eu = body && body.extra_usage;
  const extraUsage = eu && eu.is_enabled
    ? {
      utilization: typeof eu.utilization === 'number' ? eu.utilization : null,
      usedCredits: eu.used_credits ?? null,
      monthlyLimit: eu.monthly_limit ?? null,
      currency: eu.currency ?? 'USD',
      spendLimitReached: eu.spend_limit_reached === true,
    }
    : null;

  return { limits, extraUsage };
}

module.exports = { normalizePlan };
