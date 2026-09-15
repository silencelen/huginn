'use strict';
// Quick-action templates: the wording a client puts in front of a selection.
//
// Both clients grew the same four buttons over a quoted selection — Explain,
// Execute, Ask in new chat, Quote — and each shipped its OWN copy of the prompt
// behind them. Two copies of a string that is supposed to be the same string is
// a drift bug waiting for the first person to reword one of them, so the strings
// moved to the host, beside `softEndPhrase` and for the same reason: there is
// one wording, the host owns it, and either client may edit it.
//
// Everything here is pure. The daemon owns the file and the route; this owns
// what is legal, what the text looks like once it is stored, and how a template
// becomes a message — so the client and the server cannot disagree about any of
// the three.

/** The placeholder the selection is substituted into. Literal, never a regex. */
const PLACEHOLDER = '{selection}';

/**
 * How long a template may be once normalised.
 *
 * 400 rather than "as much as you like" because this text is typed into a
 * TERMINAL ahead of the selection, and a template long enough to push the
 * selection off the top of a pane is not a template any more.
 */
const MAX_TEMPLATE = 400;

/** The four fields, in the order the clients show them. */
const FIELDS = ['explain', 'execute', 'askInNewChat', 'quote'];

/**
 * The three that WRAP the selection, and so must say where it goes.
 *
 * `quote` is the odd one out: it is a lead-in printed above the quote block, not
 * a wrapper, so a `{selection}` in it would be a mistake that silently produced
 * the selection twice. That is why it is rejected rather than ignored.
 */
const WRAPPERS = ['explain', 'execute', 'askInNewChat'];

// The control characters that are dropped outright.
//
// ⚠ NOT the whole C0 range. The newline is KEPT — these are multi-line
// templates and it is the thing separating the lead-in from the selection — and
// the carriage return is converted rather than dropped (below), so a CRLF
// template does not lose its line breaks. The tab (\u0009) is likewise outside
// the ranges: it survives, because the text reaches the pane by bracketed paste,
// where a tab is a tab and not a completion key. What is left is the set with no
// meaning in a template and a great deal of meaning to a terminal emulator: ESC
// and its friends.
const DROP_CTRL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g;

/** The built-in wording, and what a host with no file serves. */
function defaults() {
  return {
    rev: 0,
    explain: 'Explain this, briefly:\n\n{selection}',
    execute: 'Run this and show me the output:\n\n{selection}',
    askInNewChat: '{selection}\n\nWhat is going on here?',
    // The bare quote block, no lead-in. Empty is a deliberate default, not a
    // missing value: most quotes want no preamble at all.
    quote: '',
    updatedAt: 0,
  };
}

/**
 * A template as it will actually be stored.
 *
 * @returns {{value: string} | {error: string}} — the error is a SENTENCE
 *   FRAGMENT with no field name ("is too long (max 400 characters)"), because
 *   the caller knows which field it was asking about and the contract's message
 *   is `<field> <fragment>`. Keeping the field name out of here is what lets one
 *   normaliser serve all four.
 */
function normalizeTemplate(raw) {
  if (typeof raw !== 'string') return { error: 'must be text' };
  const value = raw
    .replace(DROP_CTRL, '')
    // CRLF first, as one unit: handling \r on its own would turn one line break
    // into two.
    .replace(/\r\n?/g, '\n')
    // A blank line between the lead-in and the selection is the point; four of
    // them is a paste accident. Two is the most a template can mean.
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  if (value.length > MAX_TEMPLATE) return { error: `is too long (max ${MAX_TEMPLATE} characters)` };
  return { value };
}

/** How many times `{selection}` appears. Literal scan — no regex, no escaping. */
function placeholderCount(s) {
  let n = 0;
  for (let i = s.indexOf(PLACEHOLDER); i >= 0; i = s.indexOf(PLACEHOLDER, i + PLACEHOLDER.length)) n++;
  return n;
}

/**
 * The fields of a PATCH body, normalised, or the first reason one is refused.
 *
 * A PARTIAL body: a field that is not in the patch is not judged and not
 * returned, so a client that only knows about two of the four cannot blank the
 * other two by omission. Keys that are not fields (`rev`) are ignored here — the
 * stale check is merge's job.
 *
 * @returns {{ok: true, fields: object} | {ok: false, error: string}}
 */
function validate(patch) {
  if (patch == null || typeof patch !== 'object' || Array.isArray(patch)) {
    return { ok: false, error: 'quick actions must be an object' };
  }
  const fields = {};
  for (const f of FIELDS) {
    if (!(f in patch)) continue;
    const n = normalizeTemplate(patch[f]);
    if (n.error) return { ok: false, error: `${f} ${n.error}` };
    const count = placeholderCount(n.value);
    if (WRAPPERS.includes(f)) {
      // Zero AND two are refused for the same reason: the button would do
      // something other than what its name says — drop the selection, or send it
      // twice — and neither failure is visible until it is in the pane.
      if (count !== 1) return { ok: false, error: `${f} must contain ${PLACEHOLDER} exactly once` };
    } else if (count !== 0) {
      return { ok: false, error: `${f} must not contain ${PLACEHOLDER}` };
    }
    fields[f] = n.value;
  }
  return { ok: true, fields };
}

/**
 * Apply a PATCH to the stored record.
 *
 * @returns {{ok: true, record: object, changed: boolean} | {ok: false, status: number, error: string}}
 *
 * `rev` in the patch is an optimistic-concurrency check, not a value to write:
 * the desktop and the phone can both have the Settings editor open, and the
 * second Save would otherwise silently discard the first without either person
 * seeing it happen. Absent `rev` means "I am not claiming to have seen a
 * version" and is allowed — the CLI and a curl have nothing to send.
 *
 * `changed` is false when every field in the patch already held that value, and
 * the rev does NOT move in that case. A no-op Save that bumped the revision
 * would invalidate the OTHER client's rev for nothing, turning its next real
 * edit into a spurious 409 — the concurrency check would be generating the
 * conflicts it exists to catch.
 */
function merge(current, patch, nowSec) {
  const cur = current && typeof current === 'object' && !Array.isArray(current) ? current : {};
  const base = { ...defaults(), ...cur };
  if (patch != null && typeof patch === 'object' && !Array.isArray(patch) && patch.rev != null) {
    if (!Number.isInteger(patch.rev) || patch.rev !== base.rev) {
      return { ok: false, status: 409, error: 'quick actions changed underneath you' };
    }
  }
  const v = validate(patch);
  if (!v.ok) return { ok: false, status: 400, error: v.error };

  const changed = Object.keys(v.fields).some((f) => v.fields[f] !== base[f]);
  if (!changed) return { ok: true, record: base, changed: false };
  const record = { ...base, ...v.fields };
  record.rev = base.rev + 1;
  record.updatedAt = Number.isFinite(nowSec) ? Math.floor(nowSec) : 0;
  return { ok: true, record, changed: true };
}

/**
 * The wire shape — what `/v1/status` carries and what the PATCH answers with.
 *
 * `updatedAt` is stored but not served: it is for a human reading the file, and
 * a field on the status poll that no client renders is a field a client will one
 * day decode wrongly.
 */
function view(rec) {
  const r = { ...defaults(), ...(rec && typeof rec === 'object' ? rec : {}) };
  return {
    rev: r.rev,
    explain: r.explain,
    execute: r.execute,
    askInNewChat: r.askInNewChat,
    quote: r.quote,
  };
}

/**
 * The message a button sends.
 *
 * A single literal substitution of the FIRST `{selection}`, done with indexOf
 * and slices rather than `String.replace`: a selection containing `$&` or `$1`
 * is ordinary code, and `replace` would expand it into part of the template.
 * That is not hypothetical — `$&` is in every sed one-liner anyone would
 * highlight and ask about.
 */
function applyTemplate(tpl, selection) {
  const t = typeof tpl === 'string' ? tpl : '';
  const s = typeof selection === 'string' ? selection : '';
  const i = t.indexOf(PLACEHOLDER);
  if (i < 0) return t;
  return t.slice(0, i) + s + t.slice(i + PLACEHOLDER.length);
}

module.exports = {
  PLACEHOLDER, MAX_TEMPLATE, FIELDS, WRAPPERS,
  defaults, normalizeTemplate, placeholderCount, validate, merge, view, applyTemplate,
};
