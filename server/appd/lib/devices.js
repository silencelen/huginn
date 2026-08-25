'use strict';
// Devices: other machines that can run a chat in THEIR context.
//
// Called devices rather than agents on purpose. `lib/agents.js` in this same
// directory already means "the individual subagents behind a fan-out", and a
// second meaning for the word in one codebase is a bug waiting to be written by
// whoever reads the wrong one first.
//
// The shape is deliberately small: a device is a machine that has told this host
// it exists, long-polls for work, and streams back the same stream-json a local
// `claude -p` produces. Nothing about a remote run is a new pipeline — it is the
// existing chat pipeline with the spawn happening somewhere else.
//
// WHO HOLDS THE POLICY, and it is the whole security story:
//
//   the daemon sends a REQUEST     — "run this prompt, this is the chat"
//   the device applies its SCOPE   — and builds the argv itself
//
// The daemon never sends tool grants. If it did, anyone who reached this daemon
// would own every enrolled machine, and the blast radius of one leaked bearer
// token would stop being "this host" and start being "the owner's PC". Widening
// what a device may do requires touching that device.
//
// Everything here is pure: validation, the scope lattice, presence, and the
// work-item shape. The daemon owns the queue, the long-poll and the HTTP.

/**
 * What a device may be asked to do, narrowest first.
 *
 *   look — read only. No writes, no shell.
 *   work — read, write and shell, inside a root the device declares.
 *   own  — the whole machine.
 *
 * Ordered, because "is this request within scope" is a comparison and a set of
 * unrelated strings cannot answer it.
 */
/**
 * The lattice comes from shared/device-policy.json, via a generated table.
 *
 * Not because this daemon needs the tool lists — it never sends them, and that
 * is the point — but because it holds the SAME ordering the two runners hold,
 * and a lattice that disagreed by one position would have this daemon offering
 * work every device refuses, or withholding work a device would have taken. One
 * looks broken; the other looks dead. Both are silent.
 */
const table = require('./device-policy-table');

const SCOPES = table.SCOPES;

/** Modes a chat can ask for, and the narrowest scope that can honour each. */
const MODE_NEEDS = table.MODE_NEEDS;

const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$/;
const PLATFORMS = ['windows', 'linux', 'macos', 'other'];

/**
 * How recently a device must have been heard from to count as reachable.
 *
 * A device holds a long-poll open and re-opens it immediately, so it is never
 * legitimately silent for long — three missed cycles is already generous. This is
 * the same reasoning as lib/clients' stream freshness, and the same trap applies
 * in reverse: too long and work is queued to a machine that left the building.
 */
const FRESH_MS = 3 * 60 * 1000;

/** Forgotten after this, so a decommissioned laptop stops appearing forever. */
const FORGET_MS = 30 * 24 * 60 * 60 * 1000;

function emptyState() {
  return { devices: {} };
}

/** @returns {{ok: true, device: object} | {ok: false, error: string}} */
function validateRegistration(raw, now) {
  const b = raw && typeof raw === 'object' ? raw : {};
  const name = typeof b.name === 'string' ? b.name.trim() : '';
  if (!NAME_RE.test(name)) {
    return { ok: false, error: 'name must be 1-40 chars of letters, digits, space, dot, dash or underscore' };
  }
  const platform = PLATFORMS.includes(b.platform) ? b.platform : 'other';
  // Normalised the way both runners normalise (trim, lowercase), closing a
  // case-sensitivity divergence. ABSENT means the enrol default (look, a
  // read-only claude device, as it always has); JUNK floors to SCOPES[0] —
  // generate, the exclusive rung — which can run nothing a claude device runs.
  const rawScope = typeof b.scope === 'string' ? b.scope.trim().toLowerCase() : '';
  const scope = rawScope
    ? (SCOPES.includes(rawScope) ? rawScope : SCOPES[0])
    : table.ENROL_DEFAULT;
  return {
    ok: true,
    device: {
      name,
      platform,
      // What the DEVICE says it is willing to do. Advisory here — the device
      // enforces it for real — but it is what the daemon shows and what it uses
      // to refuse work it already knows would be rejected.
      scope,
      // A device that says nothing about being locked is treated as unlocked;
      // one that never reports it simply never drops.
      locked: b.locked === true,
      root: typeof b.root === 'string' ? b.root.slice(0, 300) : null,
      version: typeof b.version === 'string' ? b.version.slice(0, 40) : null,
      // What the device says it SERVES — display only, never routing authority
      // (routing keys on the daemon-minted llmSlug in the row id). Only a
      // generate enrolment gets a catalog; entries are cleaned at ingest and
      // invalid ones dropped rather than failing the registration — refusing
      // would take a live device offline over a display string.
      ...(scope === 'generate' && Array.isArray(b.models) ? {
        models: b.models.slice(0, MAX_MODELS)
          .map((m) => (m && typeof m === 'object') ? m : {})
          .filter((m) => MODEL_SLUG_RE.test(String(m.slug || '')))
          .map((m) => ({ slug: String(m.slug), display: cleanDisplay(m.display) || String(m.slug) })),
      } : {}),
      registeredAt: now,
      lastSeen: now,
    },
  };
}

/**
 * The scope a device will actually honour right now.
 *
 * A locked machine drops to `look`, whatever it is enrolled at. The reasoning is
 * not that a lock screen is a security boundary — it is that nobody is there. A
 * full-scope run with no one watching the machine it runs on is a different
 * proposition from the same run while its owner is sitting in front of it, and
 * the device is the only thing that knows which is true.
 *
 * This is a DISPLAY and PRE-CHECK helper. The device applies the same rule
 * locally; if the two ever disagree, the device wins, because it is the one
 * holding the file system.
 */
function effectiveScope(device) {
  if (!device) return table.LOCK_DROPS_TO;
  const enrolled = SCOPES.includes(device.scope) ? device.scope : SCOPES[0];
  // An exclusive scope ignores the lock drop: a generate run mutates nothing,
  // so there is nothing for a lock to withdraw — and dropping generate to look
  // would sideways-GRANT ask, a claude mode the row has no engine for. (This
  // also fixed a latent widen: a locked junk-scope device used to LIFT to look.)
  if (table.EXCLUSIVE_SCOPES.includes(enrolled)) return enrolled;
  return device.locked ? table.LOCK_DROPS_TO : enrolled;
}

/** Whether `scope` is at least as wide as `needed`. Internal — see scopeCovers. */
function scopeAtLeast(scope, needed) {
  const a = SCOPES.indexOf(scope);
  const b = SCOPES.indexOf(needed);
  return a >= 0 && b >= 0 && a >= b;
}

/**
 * Whether `scope` can honour `needed` — THE exported comparison, and the only
 * one, so no caller can bypass exclusivity with raw lattice math. An exclusive
 * rung (generate) matches only itself, in both directions: rank ordering alone
 * would let `own` satisfy generate, and a claude engine would answer a
 * local-model request — the silent substitution this design bans.
 */
function scopeCovers(scope, needed) {
  if (table.EXCLUSIVE_SCOPES.includes(needed) || table.EXCLUSIVE_SCOPES.includes(scope)) {
    return scope === needed;
  }
  return scopeAtLeast(scope, needed);
}

/**
 * Whether a device can be asked to run a chat in this mode right now.
 *
 * Returns a REASON rather than a boolean, because every no here is something a
 * person has to act on — plug the machine in, unlock it, widen the scope — and
 * "cannot run" with no explanation is the kind of error that gets a feature
 * abandoned rather than fixed.
 */
function canRun(device, mode, now) {
  if (!device) return { ok: false, reason: 'no such device' };
  if (!isOnline(device, now)) return { ok: false, reason: `${device.name} has not checked in` };
  // ⚠ Own-property, and refused outright when it is not one. `MODE_NEEDS` is a
  // plain object, so `MODE_NEEDS.constructor` is a function: this used to refuse
  // by accident (indexOf of a function is -1) rather than on purpose, and echoed
  // the caller's own string back in the reason.
  const needed = (Object.prototype.hasOwnProperty.call(MODE_NEEDS, mode) && typeof MODE_NEEDS[mode] === 'string')
    ? MODE_NEEDS[mode] : null;
  if (needed === null) return { ok: false, reason: `${device.name} was asked for something it does not recognise` };
  const enrolled = SCOPES.includes(device.scope) ? device.scope : SCOPES[0];
  const scope = effectiveScope(device);
  if (!scopeCovers(scope, needed)) {
    // Blame the lock only when unlocking would actually help — the enrolled
    // scope covers the mode and only the lock-drop is in the way. Anything
    // else names the scope, because the scope is what somebody would change.
    const lockedBlame = device.locked && scopeCovers(enrolled, needed);
    return {
      ok: false,
      reason: lockedBlame
        ? `${device.name} is locked, so it is read-only until someone unlocks it`
        : `${device.name} is enrolled as "${device.scope}", which cannot run ${mode}`,
    };
  }
  return { ok: true };
}

function isOnline(device, now) {
  return !!device && now - (device.lastSeen || 0) < FRESH_MS;
}

/** Records a check-in. Mutates and returns `state`. */
function noteSeen(state, id, now, patch = {}) {
  const d = state.devices[id];
  if (!d) return state;
  d.lastSeen = now;
  if (typeof patch.locked === 'boolean') d.locked = patch.locked;
  if (typeof patch.version === 'string') d.version = patch.version.slice(0, 40);
  if (SCOPES.includes(patch.scope)) d.scope = patch.scope;
  return state;
}

function pruneDevices(state, now) {
  for (const [id, d] of Object.entries(state.devices || {})) {
    if (now - (d.lastSeen || 0) > FORGET_MS) delete state.devices[id];
  }
  return state;
}

/** The client-facing view: the record plus what it would have to derive. */
function deviceView(id, device, now) {
  return {
    id,
    name: device.name,
    platform: device.platform,
    scope: device.scope,
    effectiveScope: effectiveScope(device),
    locked: !!device.locked,
    root: device.root ?? null,
    version: device.version ?? null,
    online: isOnline(device, now),
    lastSeen: device.lastSeen ?? null,
    registeredAt: device.registeredAt ?? null,
    ...(device.llmSlug ? { llmSlug: device.llmSlug } : {}),
    ...(Array.isArray(device.models) ? { models: device.models } : {}),
  };
}

/**
 * What a device is handed when it picks up work.
 *
 * Note what is NOT here: no tool list, no permission flags, no `--allowedTools`.
 * The device builds its own argv from its own scope. This carries the REQUEST —
 * which chat, what to say, which conversation to resume — and nothing that
 * would let this daemon widen what the far end is willing to do.
 */
// ------------------------------------------------------------- local models
//
// The composite id of a local-model picker row is `local-<llmSlug>-<modelSlug>`.
// The llmSlug is MINTED BY THE DAEMON at first generate-scope registration and
// echoed back — agreement by handshake, never parallel derivation, so the
// daemon and the shim cannot drift on it. It never changes on rename: id
// stability beats name freshness.

/** Lowercase, non-alphanumeric squeezed to dashes, capped. */
function slugify(name, cap = 16) {
  return String(name || '').toLowerCase().replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '').slice(0, cap).replace(/-+$/g, '');
}

/**
 * Unique against every taken slug INCLUDING prefix-with-dash ambiguity, so
 * `local-<llmSlug>-` prefixes always parse unambiguously: if "box" exists,
 * "box-2" would make `local-box-2-chat` unparseable, so collisions get a hash
 * suffix instead of a counter.
 */
function mintLlmSlug(name, uuid, taken) {
  const base = slugify(name) || String(uuid).replace(/-/g, '').slice(0, 8);
  const clash = taken.some((t) => t === base || t.startsWith(`${base}-`) || base.startsWith(`${t}-`));
  return clash ? `${base.slice(0, 11)}-${String(uuid).replace(/-/g, '').slice(0, 4)}` : base;
}

/** Control characters out, whitespace collapsed, bounded. Display text only. */
function cleanDisplay(s, cap = 60) {
  return String(s || '').replace(/[\x00-\x1f\x7f-\x9f]/g, ' ')
    .replace(/\s+/g, ' ').trim().slice(0, cap);
}

const MODEL_SLUG_RE = /^[a-z0-9][a-z0-9-]{1,29}$/;
const MAX_MODELS = 16;

/**
 * Whether this device can serve a local-model chat right now. The generate
 * sibling of canRun, with reasons in the same actionable style.
 */
function canServe(device, now) {
  if (!device) return { ok: false, reason: 'no enrolled machine serves this model — it may have been unenrolled' };
  if (device.scope !== 'generate') return { ok: false, reason: `${device.name} is not enrolled to serve local models` };
  if (!isOnline(device, now)) return { ok: false, reason: `${device.name} has not checked in — the machine serving this model looks offline` };
  return { ok: true };
}

/**
 * The local rows of GET /v1/models. Computed per request from the in-memory
 * registry, no cache: there is no exec and no disk here, and `available` is
 * time-dependent, so any cache would lie.
 */
function localModelRows(state, now) {
  const rows = [];
  for (const [id, d] of Object.entries((state && state.devices) || {})) {
    if (d.scope !== 'generate' || !d.llmSlug || !Array.isArray(d.models)) continue;
    for (const m of d.models) {
      rows.push({
        id: `local-${d.llmSlug}-${m.slug}`,
        display: `${m.display} - ${d.name}`,
        family: 'local',
        available: isOnline(d, now),
        host: id,
      });
    }
  }
  return rows;
}

function workItem({ id, chatId, prompt, mode, model, effort, resumeSessionId, roundId, now }) {
  return {
    id,
    chatId,
    prompt,
    // generate rides through: relabelling it as ask would be silent engine
    // substitution inside our own daemon. Anything else unknown coerces to ask.
    mode: (mode === 'act' || mode === 'generate') ? mode : 'ask',
    model: model || null,
    effort: effort || null,
    resumeSessionId: resumeSessionId || null,
    roundId: roundId || null,
    issuedAt: now,
  };
}

module.exports = {
  SCOPES, MODE_NEEDS, FRESH_MS, FORGET_MS,
  emptyState, validateRegistration, effectiveScope, scopeCovers,
  canRun, isOnline, noteSeen, pruneDevices, deviceView, workItem,
  slugify, mintLlmSlug, cleanDisplay, canServe, localModelRows,
};
