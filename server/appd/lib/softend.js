'use strict';
// Pure decision logic for "soft end": ask Claude to wrap up, then (when auto is
// on) hard-end the session the moment it settles. Split out of the daemon so the
// timing can be unit-tested without a real tmux session or clock.
//
// A soft end types a wrap-up phrase into the pane. If auto-end is on, the daemon
// then watches the session and kills it once it goes idle and STAYS idle — but a
// wrap-up that asks the owner a question must never be killed, and the moment a
// turn ends the hook can write a sub-second `idle` before the still-queued phrase
// submits and flips back to `running`. Both hazards live here.

const KILL_STABLE_MS = 3000;   // idle must hold this long before we kill
const ARM_TIMEOUT_MS = 60_000; // phrase never started a run -> give up, untouched
const TTL_MS = 6 * 60 * 60 * 1000; // absolute backstop

/** Fresh pending record, taken the moment a soft end is armed. */
function createPending(nowMs) {
  return { requestedAt: nowMs, armed: false, idleSince: null };
}

/**
 * One observation of a pending soft end.
 *
 * @param pending  the record from createPending (treated immutably; a NEW record
 *                 is returned)
 * @param state    'running' | 'attention' | 'idle' | null (from the state file)
 * @param nowMs
 * @returns { pending, action } where action is:
 *   'wait'   keep watching
 *   'arm'    first time we saw the run start (informational; still waiting)
 *   'kill'   idle has held long enough — hard-end the session
 *   'cancel' the session asked a question — abandon the auto-end, leave it alone
 *   'expire' the phrase never started a run, or the TTL elapsed — give up
 */
function stepSoftEnd(pending, state, nowMs) {
  const p = { ...pending };

  // Absolute backstop first: never sit on a pending record forever.
  if (nowMs - p.requestedAt > TTL_MS) return { pending: p, action: 'expire' };

  // A wrap-up that turns into a question must not be followed by a kill. This is
  // the whole reason auto-end is decoupled from anything else.
  if (state === 'attention') return { pending: p, action: 'cancel' };

  if (state === 'running') {
    // The run is going. Clear any idle timer (this is the queued-phrase case:
    // turn ends -> brief idle -> queued wrap-up submits -> running again).
    const wasArmed = p.armed;
    p.armed = true;
    p.idleSince = null;
    return { pending: p, action: wasArmed ? 'wait' : 'arm' };
  }

  if (state === 'idle') {
    if (!p.armed) {
      // This idle predates the phrase actually starting a run. If it never does,
      // the phrase went nowhere (wrong pane, already-answered, etc.) — expire.
      if (nowMs - p.requestedAt > ARM_TIMEOUT_MS) return { pending: p, action: 'expire' };
      return { pending: p, action: 'wait' };
    }
    // Armed and now idle: start (or continue) the stability timer, kill only once
    // it has held, so a sub-second turn-boundary idle can't fire it.
    if (p.idleSince == null) { p.idleSince = nowMs; return { pending: p, action: 'wait' }; }
    if (nowMs - p.idleSince >= KILL_STABLE_MS) return { pending: p, action: 'kill' };
    return { pending: p, action: 'wait' };
  }

  // No state file (session not yet observed by the hook, or between writes).
  if (!p.armed && nowMs - p.requestedAt > ARM_TIMEOUT_MS) return { pending: p, action: 'expire' };
  return { pending: p, action: 'wait' };
}

module.exports = { createPending, stepSoftEnd, KILL_STABLE_MS, ARM_TIMEOUT_MS, TTL_MS };
