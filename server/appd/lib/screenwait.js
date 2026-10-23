'use strict';

// How long a parked screen long poll waits between peeks of a pane, by how long
// that pane has been quiet.
//
// ⚠ A PEEK IS A TMUX PROCESS (~25 ms of CPU in the child, ~7-9 ms of this event
// loop). A long poll parked on a pane that never changes peeked every 130 ms,
// slowing to 450 ms after its first 24 ticks, and every new poll started fast
// again: one viewer parked on a quiet session cost ~2-3 tmux processes a second
// for as long as it watched. Quietness now belongs to the PANE, so a pane quiet
// for 10 s is peeked every 1.5 s by every poll that parks on it. Input sent
// through the daemon and a change of the session's state wake the pane's polls
// at once and make it "just changed" again, so typing echoes as fast as before.

/** [quiet for less than ms, peek every ms], in order; past the last, QUIET_PEEK_MS. */
const STEPS = [[3_000, 130], [10_000, 450]];
const QUIET_PEEK_MS = 1_500;

function peekInterval(quietForMs) {
  for (const [under, every] of STEPS) if (quietForMs < under) return every;
  return QUIET_PEEK_MS;
}

module.exports = { peekInterval, STEPS, QUIET_PEEK_MS };
