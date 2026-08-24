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
  const scope = SCOPES.includes(b.scope) ? b.scope : SCOPES[0];
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
  return device.locked
    ? table.LOCK_DROPS_TO
    : (SCOPES.includes(device.scope) ? device.scope : SCOPES[0]);
}

/** Whether `scope` is at least as wide as `needed`. */
function scopeAtLeast(scope, needed) {
  const a = SCOPES.indexOf(scope);
  const b = SCOPES.indexOf(needed);
  return a >= 0 && b >= 0 && a >= b;
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
  const needed = MODE_NEEDS[mode] || 'work';
  const scope = effectiveScope(device);
  if (!scopeAtLeast(scope, needed)) {
    return {
      ok: false,
      reason: device.locked
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
function workItem({ id, chatId, prompt, mode, model, effort, resumeSessionId, roundId, now }) {
  return {
    id,
    chatId,
    prompt,
    mode: mode === 'act' ? 'act' : 'ask',
    model: model || null,
    effort: effort || null,
    resumeSessionId: resumeSessionId || null,
    roundId: roundId || null,
    issuedAt: now,
  };
}

module.exports = {
  SCOPES, MODE_NEEDS, FRESH_MS, FORGET_MS,
  emptyState, validateRegistration, effectiveScope, scopeAtLeast,
  canRun, isOnline, noteSeen, pruneDevices, deviceView, workItem,
};
