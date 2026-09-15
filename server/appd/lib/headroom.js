'use strict';
// Headroom — the daemon's model of how much room is left, and the one place
// that decides what to do about it.
//
// Before this there were three half-answers living apart: the plan cache knew
// the ACTIVE account's percentages and nothing else, the auto-switcher had one
// lever (change account) that cannot help a session already running — a live
// `claude` holds its token in memory — and nothing at all noticed a window
// RESETTING, which is the event the owner actually waits for at 2am. So the
// usual night was: the 5-hour cap lands, it clears thirty minutes later, and the
// work sits idle until morning.
//
// Everything in this file is PURE: percentages, settings and clock readings in,
// a verdict out. No fs, no network, no Date.now() that was not passed in. The
// tick in huginn-appd.js does the I/O and applies what `decide` returns, which
// is why the judgment calls — the ones that are expensive to get wrong and
// impossible to reproduce on a live account — are all assertable in
// `node --test`.
//
// Vocabulary, used consistently below:
//   window    one of `session` (the 5-hour cap), `weekly_all` (the week, every
//             model) and `weekly_fable` (the week's Fable-scoped pool).
//   family    fable | opus | sonnet | haiku — what the ladder moves between.
//   ladder    a SESSION-ONLY model change appd typed into a live pane.
//   sentinel  a file the hook gate watches; arming one holds new spawns.
//
// ⚠ EVERY TIMESTAMP HERE IS MILLISECONDS — `now`, `lastSwitchAt`, `lastLadderAt`,
// `ladder.at`, `headsUpAt`, `nativeSwitch.seenAt`, `humanSetModelAt`, and the
// reset events. `headroom.json` stores them the same way. The daemon's other
// stores use epoch SECONDS and mixing the two silently turns a 30-minute
// cooldown into a 30-millisecond one, which is a cooldown that does not exist.

const { parseModelId, familyOf } = require('./models');
const { isLimitStall, parseLimitError, lastNonAttachmentRecord } = require('./limits');
// The account-switch decision is absorbed rather than reimplemented: its rules
// and its anti-flap guards were tested against real accounts and there is no
// version of "rewrite them here" that is not a regression waiting to happen.
const { decideSwitch, worstLimit, explain: explainSwitch } = require('./autoswitch');

const FAMILIES = ['fable', 'opus', 'sonnet', 'haiku'];
const WINDOWS = ['session', 'weekly_all', 'weekly_fable'];

/**
 * How long a human's own `/model` keeps appd's hands off that session.
 *
 * A person who has just chosen a model is answering the same question the
 * ladder is about to answer, and being overruled ten seconds later — silently,
 * by a daemon — is the behaviour that makes an automation untrustworthy.
 */
const HUMAN_MODEL_GRACE_MS = 10 * 60 * 1000;

/** The settings a fresh install starts from (design §Data model). */
function defaults() {
  return {
    v: 1,
    headsUpPct: 85,
    ladderPct: 92,
    ladderUpBelowPct: 50,
    stopPct: 70,
    stopFablePct: 88,
    clearBelowPct: 50,
    cooldownMs: 1_800_000,
    ladder: ['fable', 'opus', 'sonnet'],
    defaultModel: 'claude-fable-5-1',
    autoResume: true,
    resumePhrase: 'Your usage limit has reset. Continue the task you were working on when the limit was reached; do not repeat work that is already complete.',
    headsUpText: '[huginn headroom] You are at {pct}% of this account\'s Fable weekly limit. Write a short handoff note now (what is done, what is next, which files matter), then continue. huginn will move this session to {next} at {ladderPct}%.',
    accountSwitch: { enabled: false, threshold: 95, margin: 20 },
  };
}

const PCT_KEYS = ['headsUpPct', 'ladderPct', 'ladderUpBelowPct', 'stopPct', 'stopFablePct', 'clearBelowPct'];

/** C0 controls are invisible in every UI that would show these strings back. */
function stripC0(s, keepNewlines = false) {
  // Explicit escapes, never literal control bytes in source: a stripped-out
  // control character is invisible in a diff and in a review.
  return String(s).replace(keepNewlines
    ? /[\u0000-\u0009\u000B-\u001F\u007F]/g
    : /[\u0000-\u001F\u007F]/g, '');
}

function isInt(n) { return typeof n === 'number' && Number.isInteger(n); }

/**
 * Apply a PARTIAL settings patch, or say which rule it broke.
 *
 * Partial by design: the phone's Settings screen sends the one field that moved,
 * and a validator that demanded the whole object would make every edit a
 * read-modify-write race between two clients. The merge happens first and the
 * CROSS-FIELD rules are then checked against the merged result, because
 * `{headsUpPct: 95}` is legal or not depending entirely on where `ladderPct`
 * already sits.
 *
 * `defaultModel` cannot be checked against `discoverModels()` here — that shells
 * out to the installed CLI, and this file does no I/O. The shape check below
 * (a family alias, or something that parses as a model id) is what is pure;
 * pass `knownModels` when the caller has the discovered list to hand.
 *
 * @returns {{ok: true, settings: object} | {ok: false, error: string}}
 */
function validateSettings(patch, base = defaults(), { knownModels = null } = {}) {
  if (patch == null || typeof patch !== 'object' || Array.isArray(patch)) {
    return { ok: false, error: 'settings must be an object' };
  }
  const s = { ...defaults(), ...base };
  s.accountSwitch = { ...defaults().accountSwitch, ...(base && base.accountSwitch) };

  for (const k of PCT_KEYS) {
    if (!(k in patch)) continue;
    const v = patch[k];
    if (!isInt(v) || v < 1 || v > 100) return { ok: false, error: `${k} must be a whole percentage between 1 and 100` };
    s[k] = v;
  }
  if ('cooldownMs' in patch) {
    const v = patch.cooldownMs;
    if (!isInt(v) || v < 0 || v > 24 * 60 * 60 * 1000) {
      return { ok: false, error: 'cooldownMs must be a whole number of milliseconds between 0 and 24 hours' };
    }
    s.cooldownMs = v;
  }
  if ('ladder' in patch) {
    const v = patch.ladder;
    if (!Array.isArray(v) || !v.length) return { ok: false, error: 'ladder must be a non-empty list of model families' };
    const seen = new Set();
    for (const f of v) {
      if (typeof f !== 'string' || !FAMILIES.includes(f)) {
        return { ok: false, error: `ladder may only contain ${FAMILIES.join(', ')}` };
      }
      if (seen.has(f)) return { ok: false, error: 'ladder entries must be distinct' };
      seen.add(f);
    }
    s.ladder = v.slice();
  }
  if ('defaultModel' in patch) {
    const v = patch.defaultModel;
    if (typeof v !== 'string' || !v.trim()) return { ok: false, error: 'defaultModel must be a model id or family alias' };
    const id = v.trim();
    const known = Array.isArray(knownModels) && knownModels.length
      ? knownModels.some((m) => (typeof m === 'string' ? m : m && m.id) === id)
      : false;
    if (!known && !FAMILIES.includes(id) && !parseModelId(id)) {
      return { ok: false, error: 'defaultModel must be a model id or family alias' };
    }
    s.defaultModel = id;
  }
  if ('autoResume' in patch) {
    if (typeof patch.autoResume !== 'boolean') return { ok: false, error: 'autoResume must be true or false' };
    s.autoResume = patch.autoResume;
  }
  if ('resumePhrase' in patch) {
    if (typeof patch.resumePhrase !== 'string') return { ok: false, error: 'resumePhrase must be text' };
    const v = stripC0(patch.resumePhrase).trim();
    if (v.length < 1 || v.length > 300) return { ok: false, error: 'resumePhrase must be between 1 and 300 characters' };
    // A slash command is not a resume: typing `/clear` at a session that has
    // been waiting all night for its window is the worst possible outcome.
    if (v.startsWith('/')) return { ok: false, error: 'resumePhrase must not start with / — a slash command is not a resume' };
    s.resumePhrase = v;
  }
  if ('headsUpText' in patch) {
    if (typeof patch.headsUpText !== 'string') return { ok: false, error: 'headsUpText must be text' };
    const v = stripC0(patch.headsUpText, true);
    if (v.length > 600) return { ok: false, error: 'headsUpText must be at most 600 characters' };
    if (!v.includes('{pct}')) return { ok: false, error: 'headsUpText must contain {pct}' };
    s.headsUpText = v;
  }
  if ('accountSwitch' in patch) {
    const a = patch.accountSwitch;
    if (a == null || typeof a !== 'object' || Array.isArray(a)) return { ok: false, error: 'accountSwitch must be an object' };
    if ('enabled' in a) {
      if (typeof a.enabled !== 'boolean') return { ok: false, error: 'accountSwitch.enabled must be true or false' };
      s.accountSwitch.enabled = a.enabled;
    }
    if ('threshold' in a) {
      if (!isInt(a.threshold) || a.threshold < 1 || a.threshold > 100) {
        return { ok: false, error: 'accountSwitch.threshold must be a whole percentage between 1 and 100' };
      }
      s.accountSwitch.threshold = a.threshold;
    }
    if ('margin' in a) {
      if (!isInt(a.margin) || a.margin < 0 || a.margin > 100) {
        return { ok: false, error: 'accountSwitch.margin must be a whole percentage between 0 and 100' };
      }
      s.accountSwitch.margin = a.margin;
    }
  }

  // Cross-field, on the MERGED object: each of these is a ordering that makes
  // the arbiter incoherent rather than merely odd.
  if (!(s.headsUpPct < s.ladderPct)) return { ok: false, error: 'headsUpPct must be below ladderPct' };
  if (!(s.clearBelowPct < s.stopPct)) return { ok: false, error: 'clearBelowPct must be below stopPct' };
  if (!(s.ladderUpBelowPct < s.ladderPct)) return { ok: false, error: 'ladderUpBelowPct must be below ladderPct' };
  return { ok: true, settings: s };
}

/** `autoswitch.json`'s two fields, folded into `settings.accountSwitch`. One-shot. */
function migrateAutoswitch(oldState) {
  const d = defaults().accountSwitch;
  const o = oldState && typeof oldState === 'object' ? oldState : {};
  const threshold = isInt(o.threshold) && o.threshold >= 1 && o.threshold <= 100 ? o.threshold : d.threshold;
  return { enabled: o.enabled === true, threshold, margin: d.margin };
}

/**
 * A number, or null — with ABSENCE checked before validity.
 *
 * ⚠ `Number(null) === 0` and `Number('') === 0`. A missing percentage coerced
 * through `Number` alone reads as a window at 0% — an account with unknown
 * headroom would look like the freshest one on the host, and the switcher would
 * move onto it.
 */
function numOrNull(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/**
 * The three windows, picked out of `normalizePlan().limits`.
 *
 * ⚠ THE FABLE ROW IS FOUND BY LABEL, not by position or by count. The endpoint
 * returns a `weekly_scoped` row per scoped pool and plan.js folds the model's
 * display name into the label ("Current week (Fable)"); an Opus-scoped week is
 * the same `kind` and would otherwise be read as the Fable pool, which would
 * ladder sessions off Fable for a limit Fable is nowhere near.
 */
function windowsOf(limits) {
  const out = { session: null, weekly_all: null, weekly_fable: null };
  for (const l of limits || []) {
    if (!l || typeof l !== 'object') continue;
    const row = {
      percent: numOrNull(l.percent),
      resetsAt: l.resetsAt ?? null,
      severity: l.severity ?? 'normal',
      label: l.label ?? '',
    };
    if (row.percent === null) continue;
    if (l.kind === 'session') { if (!out.session) out.session = row; continue; }
    if (l.kind === 'weekly_all') { if (!out.weekly_all) out.weekly_all = row; continue; }
    if (l.kind === 'weekly_scoped' && /\(\s*fable\b/i.test(String(l.label || ''))) {
      if (!out.weekly_fable) out.weekly_fable = row;
    }
  }
  return out;
}

/**
 * Where one window sits, in the four words the rest of the system speaks.
 *
 * A window with no figure is `ok`, not `red`: unknown headroom is not an alarm,
 * and every caller that ACTS on a window checks for a number first.
 */
function classify(pct, settings = defaults()) {
  const w = pct && typeof pct === 'object' ? pct : { percent: pct, severity: 'normal' };
  const p = numOrNull(w.percent);
  if (w.severity === 'exceeded') return 'exhausted';
  if (p === null) return 'ok';
  if (p >= 100) return 'exhausted';
  if (p >= settings.ladderPct) return 'red';
  if (p >= settings.headsUpPct) return 'warn';
  return 'ok';
}

/** The worst of the three windows, as `{window, percent, label, resetsAt, mode}`. */
function worstWindow(windows, settings = defaults()) {
  let worst = null;
  for (const name of WINDOWS) {
    const w = windows && windows[name];
    if (!w) continue;
    const p = w.severity === 'exceeded' ? 100 : numOrNull(w.percent);
    if (p === null) continue;
    if (!worst || p > worst.percent) {
      worst = { window: name, percent: p, label: w.label || name, resetsAt: w.resetsAt ?? null, mode: classify(w, settings) };
    }
  }
  return worst;
}

/**
 * Windows that have actually ROLLED OVER since the last pass.
 *
 * BOTH halves are required and this is the whole point of the function. A
 * `resetsAt` that has passed is a CALENDAR fact — the endpoint publishes the
 * boundary in advance and it slides past whether or not the account was
 * credited — so acting on it alone resumes a session straight back into a red
 * window, which burns an attempt and looks to the owner like the resume is
 * broken. The confirmation is a fresh read showing the window genuinely low.
 * The failure mode this exists to prevent has a name: "reset by calendar, still
 * red on the wire".
 *
 * Only windows that were RED last pass can reset: a window that was never red
 * has nothing to wait for, and reporting its ordinary weekly rollover as an
 * event would fire a resume for a session that never stalled.
 */
function detectResets(prevAccounts, nextAccounts, nowMs = Date.now(), settings = defaults()) {
  const out = [];
  for (const [slug, next] of Object.entries(nextAccounts || {})) {
    const prev = (prevAccounts || {})[slug];
    if (!prev || !prev.red) continue;
    for (const window of WINDOWS) {
      const red = prev.red[window];
      if (!red) continue;
      const prevWin = (prev.windows && prev.windows[window]) || null;
      const resetsAt = red.resetsAt ?? (prevWin && prevWin.resetsAt) ?? null;
      const at = resetsAt ? Date.parse(resetsAt) : NaN;
      if (!Number.isFinite(at) || at > nowMs) continue;              // the calendar half
      const fresh = (next && next.windows && next.windows[window]) || null;
      const p = fresh ? numOrNull(fresh.percent) : null;
      if (p === null || fresh.severity === 'exceeded') continue;     // still unreadable / still exceeded
      if (p >= settings.clearBelowPct) continue;                     // the wire half
      out.push({ slug, window, resetsAt, percent: p, seenAt: Math.floor(nowMs / 1000) });
    }
  }
  return out;
}

/** The next rung DOWN, or null at the bottom / off the ladder entirely. */
function nextDown(family, ladder = defaults().ladder) {
  const list = Array.isArray(ladder) && ladder.length ? ladder : defaults().ladder;
  const i = list.indexOf(family);
  if (i < 0 || i >= list.length - 1) return null;
  return list[i + 1];
}

/** Is there a rung ABOVE this family on the ladder? */
function canLadderUp(family, ladder = defaults().ladder) {
  const list = Array.isArray(ladder) && ladder.length ? ladder : defaults().ladder;
  return list.indexOf(family) > 0;
}

/**
 * Which sentinels should exist, given the ACTIVE account's windows.
 *
 * Hysteresis, which is why `current` is an argument: arming at `stopPct` and
 * clearing at the same number makes a sentinel that flaps every tick while the
 * percentage wobbles either side of it, and each flap releases a batch of held
 * spawns into a window that is still full. Arm high, clear low, hold in
 * between — so `current` (what is armed right now) decides the middle band.
 *
 * `weekly_all` going red also arms STOP: a week-wide cap blocks every model, so
 * there is no ladder rung left to move to and holding spawns is the only lever.
 */
function sentinelPlan(activeWindows, settings = defaults(), current = {}) {
  const w = activeWindows || {};
  const sess = w.session ? numOrNull(w.session.percent) : null;
  const all = w.weekly_all ? numOrNull(w.weekly_all.percent) : null;
  const fable = w.weekly_fable ? numOrNull(w.weekly_fable.percent) : null;
  const allMode = w.weekly_all ? classify(w.weekly_all, settings) : 'ok';

  const wasStop = !!current.STOP;
  const wasFable = !!current['STOP-FABLE'];
  const reasons = { STOP: null, 'STOP-FABLE': null };

  let STOP;
  if (sess !== null && sess >= settings.stopPct) {
    STOP = true;
    reasons.STOP = `session ${Math.round(sess)}%`;
  } else if (allMode === 'red' || allMode === 'exhausted') {
    STOP = true;
    reasons.STOP = `weekly_all ${all === null ? 'exceeded' : `${Math.round(all)}%`}`;
  } else if ((sess === null || sess < settings.clearBelowPct) && (all === null || all < settings.clearBelowPct)) {
    STOP = false;
  } else {
    STOP = wasStop;
    if (STOP) reasons.STOP = current.STOP && current.STOP.reason ? current.STOP.reason : 'held';
  }

  let STOP_FABLE;
  if (fable !== null && fable >= settings.stopFablePct) {
    STOP_FABLE = true;
    reasons['STOP-FABLE'] = `weekly_fable ${Math.round(fable)}%`;
  } else if (fable !== null && fable < settings.clearBelowPct) {
    STOP_FABLE = false;
  } else {
    STOP_FABLE = wasFable;
    if (STOP_FABLE) reasons['STOP-FABLE'] = current['STOP-FABLE'] && current['STOP-FABLE'].reason
      ? current['STOP-FABLE'].reason : 'held';
  }

  const parts = [];
  if (STOP) parts.push(`STOP (${reasons.STOP})`);
  if (STOP_FABLE) parts.push(`STOP-FABLE (${reasons['STOP-FABLE']})`);
  return { STOP, STOP_FABLE, reason: parts.length ? parts.join(', ') : 'nothing armed', reasons };
}

// ---- the arbiter ----------------------------------------------------------
//
// One function, one cooldown, one lever per tick. It exists because the levers
// interact: switching account frees Fable headroom for NEW runs, which may make
// a ladder move unnecessary; laddering six sessions in one pass reads to the
// owner as "everything went to opus"; and a session a human just re-modelled is
// not appd's to move. Deciding each of those separately is how a system ends up
// arguing with itself at 3am.

/** decideSwitch's view of an account: a flat limits array. */
function limitsOf(acct) {
  if (!acct) return [];
  if (Array.isArray(acct.limits)) return acct.limits;
  const w = acct.windows || {};
  const rows = [];
  for (const name of WINDOWS) {
    if (!w[name]) continue;
    rows.push({
      kind: name === 'weekly_fable' ? 'weekly_scoped' : name,
      label: w[name].label || name,
      percent: w[name].percent,
      severity: w[name].severity ?? 'normal',
      resetsAt: w[name].resetsAt ?? null,
    });
  }
  return rows;
}

function windowsFor(acct) {
  if (!acct) return {};
  if (acct.windows && typeof acct.windows === 'object') return acct.windows;
  return windowsOf(acct.limits);
}

/** Does this account have room on the FABLE week specifically (decision 5)? */
function hasFableHeadroom(acct, settings) {
  const w = windowsFor(acct).weekly_fable;
  const p = w ? numOrNull(w.percent) : null;
  return p !== null && w.severity !== 'exceeded' && p < settings.headsUpPct;
}

function minutes(ms) { return Math.ceil(ms / 60000); }

/**
 * The arbiter.
 *
 * @param input {{active, candidates, sessions, settings, state, now, lastFableResetAt}}
 *   active      {slug, email, windows|limits}
 *   candidates  [{slug, email, windows|limits, aged}]
 *   sessions    [{claudeSessionId, name, state, model, family, ladder, headsUpAt,
 *                nativeSwitch, humanSetModelAt}]
 *   state       {lastSwitchAt, lastLadderAt}
 *   lastFableResetAt  ms of the most recent weekly_fable reset EVENT for the
 *                active account (from detectResets), or 0. Not in the original
 *                input list; `ladder_up` needs "a reset was seen since ladder.at"
 *                and that fact lives in the resets log, not in a percentage.
 * @returns {{actions: object[], why: string}} applied in order; each re-checked
 *          at apply time by the daemon.
 */
function decide(input = {}) {
  const settings = { ...defaults(), ...(input.settings || {}) };
  settings.accountSwitch = { ...defaults().accountSwitch, ...((input.settings || {}).accountSwitch || {}) };
  const now = Number(input.now) || 0;
  const state = input.state || {};
  const lastSwitchAt = Number(state.lastSwitchAt) || 0;
  const lastLadderAt = Number(state.lastLadderAt) || 0;
  const sessions = Array.isArray(input.sessions) ? input.sessions : [];
  const actions = [];

  if (!input.active) {
    return { actions, why: 'no active account is identifiable' };
  }

  const active = input.active;
  const activeWindows = windowsFor(active);
  const fable = activeWindows.weekly_fable;
  const fablePct = fable ? numOrNull(fable.percent) : null;
  const fableExceeded = !!fable && fable.severity === 'exceeded';
  const fableEffective = fableExceeded ? 100 : fablePct;

  // ---- 1. account switch (decideSwitch's semantics, unchanged) -------------
  let switched = null;
  const acctCfg = settings.accountSwitch;
  if (acctCfg.enabled) {
    const activeForSwitch = { slug: active.slug, email: active.email ?? null, limits: limitsOf(active) };
    const candidates = (input.candidates || []).map((c) => ({
      slug: c.slug, email: c.email ?? null, limits: limitsOf(c), _src: c,
    }));
    const base = decideSwitch({
      active: activeForSwitch,
      candidates,
      now,
      lastSwitchAt,
      threshold: acctCfg.threshold,
      margin: acctCfg.margin,
      cooldownMs: settings.cooldownMs,
    });
    if (base) {
      // Decision 5: among the candidates a switch would accept, one with FABLE
      // headroom beats one that merely has weekly_all headroom — a new run
      // landing on an account whose Fable week is spent starts life one rung
      // down for no reason.
      const bar = acctCfg.threshold - acctCfg.margin;
      const eligible = candidates.filter((c) => {
        if (c.slug === active.slug) return false;
        const w = worstLimit(c.limits);
        return w && w.percent <= bar;
      });
      const fableCapable = eligible.filter((c) => hasFableHeadroom(c._src, settings));
      const pool = fableCapable.length ? fableCapable : eligible;
      let pick = null;
      for (const c of pool) {
        const w = worstLimit(c.limits);
        if (!pick || w.percent < pick.percent) pick = { ...c, percent: w.percent };
      }
      switched = pick && pick.slug !== base.to
        ? { ...base, to: pick.slug, toEmail: pick.email ?? null, toPercent: pick.percent, preferredFable: true }
        : { ...base, preferredFable: fableCapable.some((c) => c.slug === base.to) };
      actions.push({ type: 'switch_account', slug: switched.to, detail: switched });
    }
  }

  // ---- 2. per-session levers ----------------------------------------------
  const headsUp = [];
  let ladderDown = null;
  let ladderUp = null;
  let offerUp = null;
  const blocked = [];
  const ladderReady = now - lastLadderAt >= settings.cooldownMs;
  const lastFableResetAt = Number(input.lastFableResetAt) || 0;

  for (const s of sessions || []) {
    if (!s || !s.claudeSessionId) continue;
    const family = s.family || familyOf(s.model);
    const ladder = s.ladder || null;
    const native = s.nativeSwitch && s.nativeSwitch.seenAt ? s.nativeSwitch : null;

    // heads-up: once per Fable window, and never into a session that is already
    // asking the owner something.
    if (family === 'fable' && fableEffective !== null && fableEffective >= settings.headsUpPct
      && (s.state === 'idle' || s.state === 'running')) {
      const done = Number(s.headsUpAt) || 0;
      // The window rolling over clears it: the next week gets its own warning.
      const stale = done && lastFableResetAt && lastFableResetAt > done;
      if (!done || stale) {
        headsUp.push({
          type: 'heads_up',
          claudeSessionId: s.claudeSessionId,
          name: s.name,
          pct: Math.round(fableEffective),
          next: nextDown('fable', settings.ladder) || 'the next model down',
        });
      }
    }

    // ladder down
    if (!ladderDown && family === 'fable' && fableEffective !== null && fableEffective >= settings.ladderPct) {
      const to = nextDown(family, settings.ladder);
      if (!to) blocked.push(`${s.name} is already at the bottom of the ladder`);
      else if (ladder && ladder.to) blocked.push(`${s.name} is already on ${ladder.to}`);
      else if (native) blocked.push(`${s.name} was moved to ${native.to || 'another model'} by Claude Code itself — appd never fights a native switch`);
      else if (s.humanSetModelAt && now - Number(s.humanSetModelAt) < HUMAN_MODEL_GRACE_MS) {
        blocked.push(`${s.name} had its model set by hand in the last ${minutes(HUMAN_MODEL_GRACE_MS)} min`);
      } else if (!ladderReady) {
        blocked.push(`cooling down for another ${minutes(settings.cooldownMs - (now - lastLadderAt))} min before another model move`);
      } else if (switched) {
        blocked.push('one lever per tick: an account switch is going out this pass, so the ladder waits');
      } else {
        ladderDown = { type: 'ladder_down', claudeSessionId: s.claudeSessionId, name: s.name, from: family, to, pct: Math.round(fableEffective) };
      }
    }

    // ladder up — only for sessions appd itself moved, only once the Fable week
    // has actually reset, only while the session is idle.
    if (!ladderUp && ladder && ladder.to && !native) {
      const reset = lastFableResetAt && lastFableResetAt > (Number(ladder.at) || 0);
      const low = fableEffective !== null && fableEffective < settings.ladderUpBelowPct;
      if (reset && low && s.state === 'idle' && canLadderUp(ladder.to, settings.ladder)) {
        ladderUp = { type: 'ladder_up', claudeSessionId: s.claudeSessionId, name: s.name, from: ladder.to, to: ladder.from };
      }
    }

    // A NATIVE switch is never undone by appd; it is OFFERED back, once, when
    // the week resets. Measured: a consent swap never returns to Fable by
    // itself, and the owner may well prefer to stay on opus.
    if (!offerUp && native && !(ladder && ladder.to)) {
      const reset = lastFableResetAt && lastFableResetAt > (Number(native.seenAt) || 0);
      const low = fableEffective !== null && fableEffective < settings.ladderUpBelowPct;
      if (reset && low && !s.offeredLadderUpAt) {
        offerUp = { type: 'offer_ladder_up', claudeSessionId: s.claudeSessionId, name: s.name, from: native.to || family, to: 'fable' };
      }
    }
  }

  for (const h of headsUp) actions.push(h);
  // At most ONE ladder move per tick, down or up: `lastLadderAt` is global and
  // six sessions changing model in one pass is the thing the owner reads as
  // "everything went to opus".
  if (ladderDown) actions.push(ladderDown);
  else if (ladderUp) actions.push(ladderUp);
  if (offerUp) actions.push(offerUp);

  actions.push({ type: 'sentinels', plan: sentinelPlan(activeWindows, settings, input.sentinels || {}) });

  // ---- why ----------------------------------------------------------------
  let why;
  if (switched) {
    why = `switching to ${switched.toEmail || switched.to} at ${switched.toPercent}% — one lever per tick, so no model ladder this pass`;
  } else if (ladderDown) {
    why = `${ladderDown.name}: fable -> ${ladderDown.to} at ${ladderDown.pct}% of the Fable week`;
  } else if (ladderUp) {
    why = `${ladderUp.name}: back to ${ladderUp.to} — the Fable week reset and is at ${fableEffective === null ? 'an unknown %' : `${Math.round(fableEffective)}%`}`;
  } else if (blocked.length) {
    // A ladder move that was WANTED and refused is the more useful sentence:
    // "it handed out a heads-up" does not answer "why is this session still on
    // fable at 97%".
    why = blocked[0];
  } else if (headsUp.length) {
    why = `handed ${headsUp.map((x) => x.name).join(', ')} a heads-up at ${headsUp[0].pct}% of the Fable week`;
  } else if (acctCfg.enabled) {
    why = explainSwitch({
      active: { slug: active.slug, email: active.email ?? null, limits: limitsOf(active) },
      candidates: (input.candidates || []).map((c) => ({ slug: c.slug, email: c.email ?? null, limits: limitsOf(c) })),
      now,
      lastSwitchAt,
      threshold: acctCfg.threshold,
      margin: acctCfg.margin,
      cooldownMs: settings.cooldownMs,
    });
  } else if (fableEffective !== null) {
    why = `Fable week at ${Math.round(fableEffective)}%, below the ${settings.ladderPct}% the ladder moves at`;
  } else {
    why = 'nothing to do';
  }
  return { actions, why };
}

/** The same sentence `/v1/headroom.why` shows, asked on its own. */
function explain(input = {}) {
  return decide(input).why;
}

module.exports = {
  FAMILIES, WINDOWS, HUMAN_MODEL_GRACE_MS,
  defaults, validateSettings, migrateAutoswitch,
  windowsOf, classify, worstWindow, detectResets,
  familyOf, nextDown, canLadderUp,
  sentinelPlan, decide, explain,
  // Re-exported so the tick and the routes have one import for "headroom", and
  // so `isLimitStall` sits beside the arbiter that consumes it (design §1).
  isLimitStall, parseLimitError, lastNonAttachmentRecord,
};
