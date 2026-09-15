'use strict';
// Auto-resume: the rules for bringing a session back after its usage window
// resets, and for staying out of the way when Claude Code is already doing it.
//
// The whole subsystem exists because of one measured fact (spike native-rl §1):
// Claude Code HAS an auto-continue at usage limits (`autoContinueAtUsageLimit`,
// default on) and it is an IN-PROCESS WAIT. It lives on the interactive main
// thread, it injects one specific sentence when the window resets, and it is
// cancelled by the CLI exiting, by a relaunch, by the session being
// backgrounded, and by a reset more than 24 hours out. It does not exist at all
// for `-p` runs, for subagents, or for anything appd restored after a reboot.
//
// So appd does not re-implement the wait; it watches for the native one to work
// and covers the cases where it cannot. That is the difference between the two
// halves of this file: `nativeArmed` / `nativeResumed` / `nativeCancelled` are
// about the CLI's own mechanism, and `eligible` / `resumePlan` are about ours.
//
// Everything here is pure — records in, a verdict out. No filesystem, no clock
// except the `now` the caller passes, no tmux. The daemon's `headroomTick`
// supplies the readings and applies the plan.

const { isLimitStall, parseLimitError, lastNonAttachmentRecord } = require('./limits');
const { partsIn, epochForWallClock } = require('./rounds');

/**
 * How long appd waits for the CLI's own continuation before typing its phrase.
 *
 * 90 seconds is not a guess about the CLI's timer — the native wait fires at the
 * reset instant, and both sides are reading the same clock from a different
 * side of the network. It is the margin for the reset the usage endpoint
 * reports being a little ahead of the reset the CLI is waiting on, plus the poll
 * interval that notices. Typing early is the expensive mistake: the phrase lands
 * in the composer, the native continuation then fires too, and the session runs
 * the task twice.
 */
const NATIVE_GRACE_MS = 90_000;

/**
 * How long an unanswered Fable consent dialog is left for a person.
 *
 * Measured (native-rl §7): an unanswered consent dialog LOSES the turn —
 * `{reason:"model_error"}`, "nothing was sent". So the choice after two minutes
 * is not between answering and waiting; it is between a session-only model
 * change and throwing the turn away.
 */
const CONSENT_GRACE_MS = 120_000;

/** Tries per stall, after which the session is left alone and said so. */
const MAX_ATTEMPTS = 3;

/**
 * The horizon the native wait refuses to arm past, verbatim from the strings:
 * "the usage limit now resets more than 24 hours out, so this task will not
 * resume on its own". A weekly window is normally beyond it, which is precisely
 * the gap appd fills.
 */
const NATIVE_HORIZON_MS = 24 * 60 * 60 * 1000;

/**
 * The sentence Claude Code injects when its own wait completes (native-rl §1,
 * verbatim). Matched loosely at both ends because the record that carries it is
 * an ordinary user message and may be wrapped or prefixed, but the middle is
 * quoted exactly — a paraphrase would match the owner saying something similar.
 */
const NATIVE_CONTINUATION = 'Your claude.ai usage limit has reset. Continue the task you were '
  + 'working on when the limit was reached; do not repeat work that is already complete.';
const NATIVE_CONTINUATION_RE = /usage limit has reset\.\s*Continue the task you were working on/i;

/**
 * Every string the CLI writes when its wait has STOPPED (native-rl §1).
 *
 * Each one means the same thing operationally — nothing is going to continue by
 * itself — but they are kept separate so a log line can say which, and because
 * they are the only evidence that exists: there is no state file, no flag and no
 * event for "the wait died".
 */
const CANCEL_PATTERNS = [
  { why: 'exited', re: /Claude Code exited during the wait/i },
  { why: 'relaunched', re: /Claude Code relaunched during the wait/i },
  { why: 'too-far-out', re: /usage limit now resets more than 24 hours out/i },
  { why: 'cancelled', re: /Automatic continue cancelled/i },
  { why: 'limited-again', re: /Usage limit reached again after you continued/i },
  { why: 'backgrounded', re: /this session moved to the background/i },
];

/** Text out of any record shape the transcript uses. */
function textOfRecord(rec) {
  if (!rec || typeof rec !== 'object') return '';
  const m = rec.message;
  const c = m && m.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) {
    return c.map((b) => (b && typeof b === 'object' && typeof b.text === 'string' ? b.text : '')).join(' ');
  }
  if (typeof rec.text === 'string') return rec.text;
  if (typeof rec.content === 'string') return rec.content;
  return '';
}

/** A record's instant in epoch ms, or null when it carries no timestamp. */
function tsOf(rec) {
  if (!rec || typeof rec !== 'object') return null;
  const t = rec.timestamp ?? rec.ts;
  if (typeof t === 'number') return t > 1e11 ? t : t * 1000;
  if (typeof t === 'string') {
    const n = Date.parse(t);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/**
 * Is this record a PERSON speaking?
 *
 * The same distinction typing.js makes for the drop rule, and for the same
 * reason: `user` records also carry tool_result blocks (the CLI handing a tool's
 * output back to the model) and meta records, and neither is somebody typing. A
 * resume cancelled by a tool result would be cancelled on every stalled session,
 * because a stall almost always follows a tool call.
 */
function isHumanRecord(rec) {
  if (!rec || rec.type !== 'user' || rec.isMeta === true) return false;
  const c = rec.message && rec.message.content;
  if (typeof c === 'string') return !!c.trim();
  if (!Array.isArray(c)) return false;
  return c.some((b) => b && typeof b === 'object' && b.type !== 'tool_result');
}

/**
 * The next instant at which `tz` reads this clock time — the FALLBACK reset,
 * used only when the usage endpoint is unreadable.
 *
 * The clock in a 429's text has no date and no year ("resets 3:10am
 * (America/Los_Angeles)"), so the only thing it can mean is the next time that
 * clock comes round in that zone. Built on rounds.js's wall-clock resolver
 * because that one already survives DST: naive arithmetic here would put a
 * 2 a.m. reset an hour out twice a year, which is a resume that fires early on
 * exactly the night it is least likely to be watched.
 */
function nextOccurrence(resetsClock, tz, nowMs) {
  if (!resetsClock || !tz) return null;
  const m = /^(\d{1,2})(?::(\d{2}))?\s*([ap]m)?$/i.exec(String(resetsClock).trim());
  if (!m) return null;
  let h = Number(m[1]);
  const mi = m[2] ? Number(m[2]) : 0;
  const ap = m[3] ? m[3].toLowerCase() : null;
  if (ap === 'pm' && h < 12) h += 12;
  if (ap === 'am' && h === 12) h = 0;
  if (!Number.isInteger(h) || h > 23 || mi > 59) return null;
  let p;
  try { p = partsIn(tz, nowMs); } catch { return null; }
  let at = epochForWallClock(tz, p.y, p.mo, p.d, h, mi);
  // Strictly in the future: a reset time that has already passed today is
  // tomorrow's, and returning the past one would make the stall look resolvable
  // immediately — the one failure mode that resumes into a still-empty window
  // and burns an attempt.
  if (at <= nowMs) {
    const t = new Date(nowMs + 24 * 60 * 60 * 1000);
    const q = partsIn(tz, t.getTime());
    at = epochForWallClock(tz, q.y, q.mo, q.d, h, mi);
  }
  return at;
}

/**
 * Read a stall off the last transcript record.
 *
 * Returns null for anything that is not the 429 — including a 500, an
 * `overloaded` error and a perfectly ordinary answer. The caller may pass the
 * record directly or the tail it came from; `lastNonAttachmentRecord` is applied
 * to an array so the bookkeeping records that land after a stall (attachments,
 * queue-operations) cannot hide it.
 *
 * `windows` is the usage endpoint's reading — `{session, weekly_all,
 * weekly_fable}` from `headroom.windowsOf` — and it, not the clock in the
 * apology, is where `resetsAt` comes from. The text says WHICH window; the
 * endpoint says when. When the endpoint has nothing for that window the clock is
 * used and the result is flagged, so a caller can tell a fact from an inference.
 *
 * @returns {{at, window, resetsAt, resetsAtSource, text}|null}
 */
function stallOf(lastRecord, windows = {}, { now = Date.now() } = {}) {
  const rec = Array.isArray(lastRecord) ? lastNonAttachmentRecord(lastRecord) : lastRecord;
  const verdict = isLimitStall(rec);
  if (!verdict.stalled) return null;
  const parsed = parseLimitError(verdict.text);
  const w = parsed.window;
  const fromEndpoint = w && windows && windows[w] && windows[w].resetsAt ? windows[w].resetsAt : null;
  let resetsAt = null;
  let resetsAtSource = null;
  if (fromEndpoint) {
    const ms = Date.parse(fromEndpoint);
    if (Number.isFinite(ms)) { resetsAt = ms; resetsAtSource = 'endpoint'; }
  }
  if (resetsAt == null) {
    const fallback = nextOccurrence(parsed.resetsClock, parsed.tz, now);
    if (fallback != null) { resetsAt = fallback; resetsAtSource = 'text'; }
  }
  return {
    at: tsOf(rec) ?? now,
    window: w,
    resetsAt,
    resetsAtSource,
    text: verdict.text.slice(0, 300),
  };
}

/**
 * Everything a transcript tail holds AFTER the stall record.
 *
 * The slice, not a timestamp filter: records are appended in order and the
 * timestamps are written by whatever produced them, so ordering by `timestamp`
 * puts a clock skew between two processes in charge of whether a resume fires.
 * An empty result means the 429 is still the last word — which is the state
 * auto-resume acts on.
 */
function recordsAfterStall(records) {
  const arr = Array.isArray(records) ? records : [];
  for (let i = arr.length - 1; i >= 0; i--) {
    if (isLimitStall(arr[i]).stalled) return arr.slice(i + 1);
  }
  return [];
}

/**
 * Is the CLI's own wait running for this session?
 *
 * Three conditions, all measured, none guessable:
 *
 *   * `entrypoint === 'cli'` in the native registry (`~/.claude/sessions/<pid>.json`,
 *     peer-registry spike §0/§7). NOT `kind`, which reads `"interactive"` even
 *     for `claude -p` and is therefore useless as a discriminator — the spike
 *     found this the hard way.
 *   * appd has not restored or recreated the session since the stall. A restored
 *     session is a NEW process: the wait died with the old one and the strings
 *     say so ("Claude Code relaunched during the wait…").
 *   * the reset is within 24 hours. Past that the native wait refuses to arm at
 *     all, which is the normal case for a weekly window.
 *
 * An absent registry entry is NOT armed: the registry is written by every live
 * `claude`, so its absence means the process is gone or predates the feature,
 * and waiting 90 seconds for a continuation nobody is going to send is a pure
 * delay on the only path that will actually work.
 */
function nativeArmed({ registryEntry = null, restoredAt = null, stallAt = 0, resetsAt = null, now = Date.now() } = {}) {
  if (!registryEntry || typeof registryEntry !== 'object') return false;
  if (registryEntry.entrypoint !== 'cli') return false;
  if (restoredAt != null && Number(restoredAt) >= Number(stallAt || 0)) return false;
  if (resetsAt == null) return false;
  if (Number(resetsAt) - now > NATIVE_HORIZON_MS) return false;
  return true;
}

/**
 * Did the CLI resume this session by itself?
 *
 * Two independent signs, either of which is enough:
 *   * the injected continuation sentence, verbatim — the unambiguous one;
 *   * any human-shaped `user` record timestamped after the reset. This covers
 *     the case where the owner typed instead, which is ALSO "do not type the
 *     phrase" — the difference is recorded by the caller as `how`.
 *
 * Records without a timestamp are judged on the sentence alone, never on
 * position: a tail read is not proof of order across a file that was appended to
 * while it was being read.
 */
function nativeResumed(recordsAfterStall, resetsAt = null) {
  for (const rec of recordsAfterStall || []) {
    const text = textOfRecord(rec);
    if (text && NATIVE_CONTINUATION_RE.test(text)) return true;
    if (resetsAt != null && isHumanRecord(rec)) {
      const at = tsOf(rec);
      if (at != null && at >= Number(resetsAt)) return true;
    }
  }
  return false;
}

/**
 * Did the CLI say its wait has stopped? Returns the reason, or null.
 *
 * These strings are the ONLY evidence — there is no event and no state file —
 * and they appear in ordinary system/assistant records, so the match is on text
 * across the tail rather than on a record type.
 */
function nativeCancelled(records) {
  for (const rec of records || []) {
    const text = textOfRecord(rec);
    if (!text) continue;
    for (const p of CANCEL_PATTERNS) if (p.re.test(text)) return p.why;
  }
  return null;
}

/**
 * May appd resume this session at all?
 *
 * Returns `{ok, why}` and `why` is always populated — a refusal that cannot say
 * why is indistinguishable from a bug, and this decision is taken on a timer
 * with nobody watching.
 *
 * Order matters and is deliberate: the SETTINGS gate is first because an owner
 * who turned this off should never see "attempts exhausted" in a log; the
 * still-stalled check is next because it is the one that protects a live
 * conversation from having a phrase typed into it.
 *
 * @param settings  headroom settings (`autoResume` is the global default)
 * @param meta      session-meta (`autoResume: null|true|false` overrides it)
 * @param stall     the record from `stallOf`, as stored in headroom.json
 * @param records   the transcript tail, newest last
 * @param resetSeen did `detectResets` observe this window resetting?
 * @param percent   the FRESH reading for that window, if there is one
 */
function eligible({ settings = {}, meta = null, stall = null, records = null,
  resetSeen = false, percent = null, now = Date.now() } = {}) {
  const perSession = meta && typeof meta.autoResume === 'boolean' ? meta.autoResume : null;
  const on = perSession === null ? settings.autoResume !== false : perSession;
  if (!on) {
    return { ok: false, why: perSession === false ? 'auto-resume is off for this session' : 'auto-resume is off' };
  }
  if (!stall || !stall.at) return { ok: false, why: 'not stalled' };
  if (stall.resumedAt) return { ok: false, why: 'already resumed' };
  if ((Number(stall.attempts) || 0) >= MAX_ATTEMPTS) {
    return { ok: false, why: `gave up after ${MAX_ATTEMPTS} attempts` };
  }
  // The transcript, not the stored record: a stall is a fact about the past, and
  // what decides now is whether the 429 is still the last thing that happened.
  if (Array.isArray(records) && records.length) {
    const last = lastNonAttachmentRecord(records);
    if (!isLimitStall(last).stalled) {
      return { ok: false, why: isHumanRecord(last) ? 'a person answered it' : 'the session moved on' };
    }
  }
  // Both halves, exactly as detectResets requires them: a calendar time passing
  // is not a reset, and a window that reads low without its time having passed
  // is a different account's reading or a stale cache.
  const clearBelow = Number.isFinite(Number(settings.clearBelowPct)) ? Number(settings.clearBelowPct) : 50;
  const due = stall.resetsAt != null && Number(stall.resetsAt) <= now;
  if (!resetSeen) {
    if (!due) return { ok: false, why: 'the window has not reset yet' };
    if (percent == null) return { ok: false, why: 'no fresh reading of the window yet' };
    if (Number(percent) >= clearBelow) {
      return { ok: false, why: `the reset time passed but the window still reads ${Math.round(percent)}%` };
    }
  }
  return { ok: true, why: 'the window reset and the session is still holding the 429' };
}

/**
 * What to DO about an eligible stall, given what kind of thing stalled.
 *
 * The gaps table from the design, as one function. The actions:
 *
 *   `wait_native`    the CLI's own wait is armed — hold until `graceUntil` and
 *                    look again. Never types anything.
 *   `type_phrase`    an interactive session appd must nudge itself: the resume
 *                    phrase, through the send queue, at a turn boundary.
 *   `rerun_chat`     a headless chat or Round run: re-run the stalled turn with
 *                    `--resume <id>` and the same text.
 *   `requeue_device` a run that was executing on another machine: put the work
 *                    item back.
 *   `skip:<why>`     nothing to do, and the reason is in the action itself so a
 *                    log line and a `/v1/headroom` field can both carry it.
 *
 * `graceUntil` is returned for every action, not just the waiting one, so a
 * caller can log "waited 90s, then typed" without recomputing it.
 */
function resumePlan({ kind = 'session', armed = false, stall = null, resumedNatively = false,
  cancelled = null, now = Date.now(), graceMs = NATIVE_GRACE_MS } = {}) {
  const base = stall && stall.resetsAt != null ? Number(stall.resetsAt) : (stall && stall.at) || now;
  const graceUntil = base + graceMs;
  if (resumedNatively) return { action: 'skip:native', graceUntil, why: 'Claude Code continued it itself' };
  if (kind === 'chat' || kind === 'round') {
    return { action: 'rerun_chat', graceUntil, why: 'a headless run has no wait of its own' };
  }
  if (kind === 'device') {
    return { action: 'requeue_device', graceUntil, why: 'the work item goes back to the machine that had it' };
  }
  // A cancelled native wait is the same position as never having had one: the
  // CLI has said in as many words that nothing will continue on its own.
  if (armed && !cancelled) {
    if (now < graceUntil) return { action: 'wait_native', graceUntil, why: 'giving the CLI its own wait first' };
    return { action: 'type_phrase', graceUntil, why: 'the native wait produced nothing in 90 seconds' };
  }
  return {
    action: 'type_phrase',
    graceUntil,
    why: cancelled ? `the native wait stopped (${cancelled})` : 'no native wait is armed for this session',
  };
}

/**
 * How long a reset event stays usable as proof.
 *
 * `detectResets` fires ONCE, on the tick that sees the drop; the resume that
 * follows may need two or three passes (the queue waits for a turn boundary), so
 * the event is remembered rather than consumed by whoever reads it first.
 */
const RESET_MEMORY_MS = 60 * 60 * 1000;

/**
 * Which windows have reset recently enough to count — FOR THIS ACCOUNT.
 *
 * ⚠ THE ACTIVE ACCOUNT'S RESETS ONLY. `detectResets` runs over every saved
 * login, and for an inactive one the "fresh" reading is aged-forward history: a
 * fabricated `percent: 0` for any window whose reset time has passed. So a
 * second account's stale weekly_fable snapshot rolling over used to emit a
 * weekly_fable reset that satisfied a session stalled on the ACTIVE account's
 * Fable week, which is still full — the phrase is typed, the session re-stalls,
 * and one of only three attempts is gone. Three of those and it is abandoned for
 * the night.
 *
 * A reset carrying no slug at all is kept: it predates the field, and dropping
 * it would silently stop resumes on an upgrade.
 *
 * @returns Map<window, ms of the most recent reset>
 */
function recentResetWindows(resets, { now = Date.now(), activeSlug = null, memoryMs = RESET_MEMORY_MS } = {}) {
  const out = new Map();
  for (const r of resets || []) {
    if (!r || !r.window) continue;
    if (activeSlug && r.slug && r.slug !== activeSlug) continue;
    const at = Number(r.at) || 0;
    if (now - at > memoryMs) continue;
    if (at > (out.get(r.window) || 0)) out.set(r.window, at);
  }
  return out;
}

/**
 * Has THIS stall's window reset SINCE the stall?
 *
 * ⚠ Since the stall, not merely "recently". The reset log is a rolling hour and
 * a host can stall twice in one: reading the earlier window's reset as this
 * stall's confirmation resumes a session straight back into a full window.
 */
function resetSeenFor(map, stall) {
  if (!stall || !stall.window || !map) return false;
  return (map.get(stall.window) || 0) >= (Number(stall.at) || 0);
}

module.exports = {
  NATIVE_GRACE_MS, CONSENT_GRACE_MS, MAX_ATTEMPTS, NATIVE_HORIZON_MS, RESET_MEMORY_MS,
  recentResetWindows, resetSeenFor,
  NATIVE_CONTINUATION, CANCEL_PATTERNS,
  stallOf, recordsAfterStall, nativeArmed, nativeResumed, nativeCancelled, eligible, resumePlan,
  nextOccurrence, isHumanRecord, textOfRecord, tsOf,
};
