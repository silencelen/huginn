// The finish-detection rule shared by every way of observing the daemon,
// ported from the Android app's WatchCycle.finishedSince (notify/
// WatchCycle.kt). Streaming watcher and scheduled check are two ways of
// ARRIVING at an observation; when each had its own copy of this logic they
// disagreed, and a bug fixed in one stayed alive in the other. There is one
// place to be wrong now.

/**
 * Which chats have finished between two observations.
 *
 * Two ways to notice, because each misses what the other catches. The
 * `finishedRuns` counter sees a chat that began and ended entirely inside one
 * gap — invisible to the running set, and with a scheduled background check
 * that is ordinary rather than exotic. The running-set edge covers a chat
 * whose count was never recorded (including one deleted while it ran).
 *
 * A chat with no previous count contributes nothing: absent a baseline, its
 * counter says only how many times it has ever run, and announcing that would
 * turn a first look into a burst of notifications about history. A counter
 * that went BACKWARDS (daemon restart) is not a finish either.
 */
export const finishedSince = (
  runsBefore: Readonly<Record<string, number>>,
  runsNow: Readonly<Record<string, number>>,
  previouslyRunning: ReadonlySet<string>,
  running: ReadonlySet<string>,
): Set<string> => {
  const finished = new Set<string>()
  for (const [id, now] of Object.entries(runsNow)) {
    if ((runsBefore[id] ?? now) < now) finished.add(id)
  }
  for (const id of previouslyRunning) {
    if (!running.has(id)) finished.add(id)
  }
  return finished
}
