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
 * Could these two be the same holder of the name? Only a field both sides know
 * can say no: a bare-word state file has no session id, and a birth time is
 * known only once tmux has been asked.
 */
function sameHolder(a, b) {
  if (a.sessionId && b.sessionId && a.sessionId !== b.sessionId) return false;
  if (a.born && b.born && a.born !== b.born) return false;
  return true;
}

/**
 * One observation of a pending soft end.
 *
 * @param pending  the record from createPending (treated immutably; a NEW record
 *                 is returned)
 * @param state    'running' | 'attention' | 'idle' | null (from the state file)
 * @param nowMs
 * @param who      who holds the session's name right now, as far as can be seen:
 *                 { sessionId (the Claude session the state file names),
 *                   born (tmux's birth time for the session) }. Optional.
 * @returns { pending, action } where action is:
 *   'wait'   keep watching
 *   'arm'    first time we saw the run start (informational; still waiting)
 *   'kill'   idle has held long enough — hard-end the session
 *   'cancel' the session asked a question — abandon the auto-end, leave it alone
 *   'expire' the phrase never started a run, or the TTL elapsed — give up
 *   'gone'   the run it was asked of has ended, or the name now belongs to a
 *            different session — drop it, there is nothing left to end
 */
function stepSoftEnd(pending, state, nowMs, who = null) {
  const p = { ...pending };

  // Absolute backstop first: never sit on a pending record forever.
  if (nowMs - p.requestedAt > TTL_MS) return { pending: p, action: 'expire' };

  // ⚠ WHOSE WIND-DOWN THIS IS. The daemon files a pending end under the tmux
  // NAME, and a name outlives its session: Claude exits to the shell and is
  // started again, the session is killed at a terminal and `cc` makes a new one
  // seconds later. The record used to carry nothing else, so the next holder's
  // first stable idle killed it — a session started moments before — and an
  // archive intent riding on it archived the wrong conversation. So the end
  // belongs to the holder it is first seen with (fields learned as they become
  // known), and a different one means it is over.
  if (state != null && who) {
    const was = p.holder || {};
    if (!sameHolder(was, who)) return { pending: p, action: 'gone' };
    p.holder = { sessionId: was.sessionId || who.sessionId || null, born: was.born || who.born || null };
  }
  // And a run that had started and now has no state file has ENDED: the hook's
  // SessionEnd removes the file when Claude exits (and the daemon's own clears
  // happen only where the name changes hands). Waiting on, with the wrap-up's
  // idle timer still set, is what ended the next `claude` started in that pane.
  if (state == null && p.armed) return { pending: p, action: 'gone' };

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

  // No state file, and the run has not started: not yet observed by the hook.
  if (!p.armed && nowMs - p.requestedAt > ARM_TIMEOUT_MS) return { pending: p, action: 'expire' };
  return { pending: p, action: 'wait' };
}

module.exports = { createPending, stepSoftEnd, KILL_STABLE_MS, ARM_TIMEOUT_MS, TTL_MS };
