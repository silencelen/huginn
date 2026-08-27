'use strict';
// Normalizes the plan-utilization payload behind Claude Code's `/usage` into the
// few things worth showing on a phone.
//
// The response carries a `limits` array (the same rows `/usage` renders), a
// `spend` block (what extra usage has actually cost) plus a pile of experiment
// keys; read the two we name and ignore the rest, so an unfamiliar future key
// cannot break the screen.
//
// The experiment keys seen to date (2026-08-26): seven_day_oauth_apps,
// seven_day_opus, seven_day_sonnet, seven_day_cowork, seven_day_omelette,
// omelette_promotional, tangelo, iguana_necktie, nimbus_quill, cinder_cove,
// amber_ladder. They are ignored by OMISSION — this file reads the keys it
// names and nothing else — rather than by a null check, because they do not all
// arrive null: nimbus_quill has arrived as a real object carrying
// `utilization: 0`, which a "skip the nulls" rule would have promoted onto the
// screen as a limit row reading 0%.

const LABELS = {
  session: 'Current session',
  weekly_all: 'Current week, all models',
  weekly_scoped: 'Current week',
};

/**
 * The money side of extra usage.
 *
 * Amounts arrive as MINOR units with their own exponent — `{ amount_minor:
 * 10055, currency: 'USD', exponent: 2 }` is $100.55 — and they are kept that way
 * all the way to the screen. Dividing here would hand the client a float that
 * cannot represent a cent exactly, and rounding here would decide the display
 * format for a currency this file has never seen; the integer and its exponent
 * are the only lossless thing to carry.
 *
 * `used` and `limit` are the same currency by construction (a limit denominated
 * differently from the spend it caps would be meaningless), so one exponent and
 * one currency describe the pair.
 *
 * @param sp the raw `spend` block, or nothing on an older payload
 */
function normalizeSpend(sp) {
  if (!sp || typeof sp !== 'object') return null;
  const used = sp.used && typeof sp.used === 'object' ? sp.used : null;
  const limit = sp.limit && typeof sp.limit === 'object' ? sp.limit : null;
  const scale = used || limit || {};
  return {
    usedMinor: typeof (used && used.amount_minor) === 'number' ? used.amount_minor : null,
    limitMinor: typeof (limit && limit.amount_minor) === 'number' ? limit.amount_minor : null,
    exponent: typeof scale.exponent === 'number' ? scale.exponent : 2,
    currency: scale.currency || 'USD',
    percent: typeof sp.percent === 'number' ? sp.percent : null,
    // Verbatim: the severity vocabulary is Claude's, and the limit rows already
    // colour by the same words.
    severity: sp.severity ?? null,
    enabled: sp.enabled === true,
    disabledReason: sp.disabled_reason ?? null,
    canPurchaseCredits: sp.can_purchase_credits === true,
    canToggle: sp.can_toggle === true,
  };
}

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

  // Extra usage is meaningful once the account has EVER had credits switched on.
  //
  // The gate used to be `is_enabled` alone, and that is why an owner carrying
  // $100.55 of real extra usage saw nothing at all: the org disabled credits for
  // the rest of the month AFTER the money was spent, so `is_enabled` went false
  // while the balance stayed exactly as owed. "Switched off" is not "never had
  // it" — and it is only the second one the original gate was defending against,
  // because an account that never enabled credits still reports 100% used /
  // limit reached against a limit it does not have. That defence survives
  // unchanged; `credits_ever_enabled` simply asks the right question.
  const eu = body && body.extra_usage;
  const extraUsage = eu && (eu.is_enabled || eu.credits_ever_enabled === true)
    ? {
      utilization: typeof eu.utilization === 'number' ? eu.utilization : null,
      usedCredits: eu.used_credits ?? null,
      monthlyLimit: eu.monthly_limit ?? null,
      currency: eu.currency ?? 'USD',
      spendLimitReached: eu.spend_limit_reached === true,
      // Whether it is on RIGHT NOW, which is a different question from whether
      // there is anything to show, and the one the card's state line asks.
      isEnabled: eu.is_enabled === true,
      creditsEverEnabled: eu.credits_ever_enabled ?? null,
      decimalPlaces: eu.decimal_places ?? null,
      disabledReason: eu.disabled_reason ?? null,
      userDisabled: eu.user_disabled ?? null,
      // Passed through verbatim and unread: observed null on every capture, so
      // their shape is a guess and the client ignores unknown keys anyway.
      daily: eu.daily ?? null,
      weekly: eu.weekly ?? null,
    }
    : null;

  return { limits, extraUsage, spend: normalizeSpend(body && body.spend) };
}

module.exports = { normalizePlan };
