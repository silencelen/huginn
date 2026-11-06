'use strict';
/**
 * NOTHING DESTRUCTIVE ON ONE LOOK: an "absent since" ledger for a reconcile.
 *
 * ⚠ 2026-11-06. One listing of tmux came back empty while every session was
 * alive (see lib/tmux-socket.js), and the projects reconcile — which trusted
 * any listing that did not fail outright — dropped every member of five
 * projects and archived all five in that one tick. A listing is ONE look, taken
 * by a process that can be starved, swapped out or pointed at the wrong server;
 * a member removed or a project archived is a durable write nobody undoes
 * without a backup.
 *
 * So a thing missing from a listing is only NOTED. It becomes DUE once it has
 * been missing from every listing for at least `windowMs`, and the moment a
 * listing has it again the note is forgotten. "Due" is not a licence to write:
 * the caller then probes that one thing directly and writes only when the probe
 * confirms it is gone (lib/tmux-socket.js probeVerdict).
 *
 * In memory on purpose: a restarted daemon starts every clock again, which can
 * only make it slower to drop something, never faster.
 */

/** A ledger whose absences fall due after `windowMs`. */
function absenceLedger(windowMs) {
  const rows = new Map();   // key -> { since, ...whatever the caller notes }
  return {
    /** The key was in this listing: forget it. Returns the row it had while missing, or null. */
    seen(key) {
      const row = rows.get(key) || null;
      rows.delete(key);
      return row;
    },
    /**
     * The key was missing from the listing taken at `now` (ms): 'new' the first
     * time, 'waiting' inside the window, 'due' once it has been missing for at
     * least `windowMs`.
     */
    missing(key, now) {
      const row = rows.get(key);
      if (!row) { rows.set(key, { since: now }); return 'new'; }
      return now - row.since >= windowMs ? 'due' : 'waiting';
    },
    /** The row of a key that is missing (for the caller's own notes), or null. */
    row(key) { return rows.get(key) || null; },
    /** The thing was written off (or left by another road): nothing more to watch. */
    forget(key) { rows.delete(key); },
    /** Forget every key not in `touched`: what this pass did not look at has left the store. */
    retain(touched) {
      for (const key of [...rows.keys()]) if (!touched.has(key)) rows.delete(key);
    },
    get size() { return rows.size; },
    windowMs,
  };
}

module.exports = { absenceLedger };
