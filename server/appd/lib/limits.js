'use strict';
// Reading a usage limit out of a transcript.
//
// When an account runs out of headroom the CLI does not crash and it does not
// write a status record: it appends an ordinary ASSISTANT record whose text is
// the apology, flagged `isApiErrorMessage:true` with `apiErrorStatus:429` and
// `error:"rate_limit"`, and then the session simply stops. That record is the
// ONLY on-disk evidence that a session is stalled rather than idle — which is
// why auto-resume keys off it (design §4) rather than off a percentage: a
// session at 99% is still working, and a session at 100% whose last record is a
// human message was cancelled by a person, not by the cap.
//
// Everything here is pure: records in, a verdict out. `lib/headroom.js` imports
// these; nothing in this file touches the filesystem, the clock, or the network.

const { textOf } = require('./transcript');

/** The one status that means "out of headroom" rather than "the API is unwell". */
const LIMIT_STATUS = 429;

/**
 * The last record that is actually part of the conversation.
 *
 * Two record types land AFTER the thing they describe and would otherwise
 * masquerade as "the last thing that happened":
 *   - `attachment` — the CLI's own bookkeeping (deferred tool lists, skill
 *     listings). Real transcripts carry several per turn, and one of them is
 *     routinely the final line of a stalled agent's file.
 *   - `queue-operation` — a message typed while Claude was mid-turn. A queued
 *     message is NOT a human answering the stall: it was written before the
 *     429, it never reached the model, and treating it as "a human replied"
 *     would cancel a resume that should happen.
 *
 * Anything else — user, assistant, system, summary, last-prompt — counts.
 */
function lastNonAttachmentRecord(records) {
  if (!Array.isArray(records)) return null;
  for (let i = records.length - 1; i >= 0; i--) {
    const r = records[i];
    if (!r || typeof r !== 'object') continue;
    if (r.type === 'attachment' || r.type === 'queue-operation') continue;
    return r;
  }
  return null;
}

/**
 * Is this record the CLI saying "no more headroom"?
 *
 * All three halves are required and none is redundant: a `type:'assistant'`
 * check alone matches every answer ever written; `isApiErrorMessage` alone also
 * covers 500s and overloaded errors, which are outages to ride out rather than
 * windows to wait for; and the status alone would match a 429 on some future
 * record type. Note the record's `message.model` is `<synthetic>` for these —
 * the text was written by the CLI, not by a model — so nothing downstream
 * should read a model id off a stall.
 *
 * @param {object|null} lastRecord the raw JSONL record, NOT a rendered event
 * @returns {{stalled: boolean, status: number|null, text: string}}
 */
function isLimitStall(lastRecord) {
  const d = lastRecord && typeof lastRecord === 'object' ? lastRecord : null;
  const isApiError = !!d && d.isApiErrorMessage === true;
  // Reported for ANY api-error record, so a caller can tell "out of headroom"
  // from "the API fell over" without re-reading the record.
  const status = isApiError && typeof d.apiErrorStatus === 'number' ? d.apiErrorStatus : null;
  const text = d && d.message ? textOf(d.message.content) : '';
  const stalled = isApiError && d.type === 'assistant' && status === LIMIT_STATUS;
  return { stalled, status, text };
}

/**
 * WHICH window ran out, read off the apology text.
 *
 * Two shapes have been observed on this host (2026-08/09, CLI 2.1.2xx):
 *
 *   "You've hit your session limit · resets 10:10pm (America/Los_Angeles)"
 *   "You're out of usage credits. Run /usage-credits to keep using Fable 5.1
 *    or /model to switch models."
 *
 * The second names the model whose scoped weekly pool is empty, so a Fable
 * mention means `weekly_fable`; without a model name there is no way to tell
 * which pool it was, and a guess would send the arbiter to wait on the wrong
 * reset — so that is `null`, not a default.
 *
 * ⚠ The CLOCK in the text is not the answer to "when does this reset". It has
 * no date, it is in whatever zone the account is billed in, and the authority
 * is the usage endpoint's `resetsAt` for the named window (design §1). It is
 * returned only as the fallback for a session whose endpoint is unreadable, and
 * the caller flags that case `resetsAtSource:'text'`.
 *
 * A monthly SPEND limit ("You've hit your monthly spend limit …") is
 * deliberately not a window: nothing resets, so there is nothing to wait for.
 *
 * @returns {{window: 'session'|'weekly_fable'|'weekly_all'|null,
 *            resetsClock: string|null, tz: string|null}}
 */
function parseLimitError(text) {
  const s = typeof text === 'string' ? text : '';
  const none = { window: null, resetsClock: null, tz: null };
  if (!s.trim()) return none;

  let window = null;
  if (/\bsession limit\b/i.test(s)) window = 'session';
  else if (/\bout of usage credits\b/i.test(s)) window = /\bfable\b/i.test(s) ? 'weekly_fable' : null;
  // Typed but not yet observed in the wild: the all-models weekly cap. Kept
  // because `weekly_all` is part of the contract and a silent null there would
  // read as "unparseable" rather than "this shape has never been seen".
  else if (/\bweekly limit\b/i.test(s)) window = 'weekly_all';
  if (!window) return none;

  // `resets 10:10pm` / `resets 3am`. Spaces inside the clock are normalised
  // away so a caller can compare and format one shape.
  const clock = /\bresets\s+(\d{1,2}(?::\d{2})?)\s*([ap]\.?m\.?)/i.exec(s);
  const resetsClock = clock ? `${clock[1]}${clock[2].replace(/\./g, '').toLowerCase()}` : null;
  // An IANA zone in parentheses, the only form the CLI writes.
  const zone = /\(([A-Za-z]+(?:\/[A-Za-z0-9_+-]+)+)\)/.exec(s);
  return { window, resetsClock, tz: zone ? zone[1] : null };
}

module.exports = { isLimitStall, parseLimitError, lastNonAttachmentRecord, LIMIT_STATUS };
