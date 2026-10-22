'use strict';

// Whether a burst of events from the state-directory watcher needs an alert
// tick now.
//
// ⚠ THE HOOK REWRITES A SESSION'S STATE FILE ON EVERY TOOL CALL. Almost all of
// those writes carry the state the alert tick already saw ("running", with a new
// timestamp), and each one used to cost a whole tick 120 ms later: a tmux
// listing, every session's state and last work, every chat's meta, and a
// rewrite of alerts.json. A busy session made five of those a second, about 8%
// of a core with nobody connected, competing with every route.
//
// What an alert turns on, per session, is one word: the state as
// readSessionState reports it (the file's word, or `attention` while a question
// sidecar is pending). So a burst whose files all still say the word the last
// tick observed changes nothing an alert could fire on, and it ticks only if no
// tick has run for [quietMs] (the finish rule reads who was attached from the
// last tick, so that stays a few seconds fresh). A burst where any word changed,
// a session's file appeared or went, or the watcher could not name the file,
// ticks at once, exactly as before.

/**
 * @param names      file names the watcher reported; null for "unknown".
 * @param stateOf    name -> the session's state word now, or null.
 * @param seen       name -> state word the last alert tick observed, or null
 *                   before any tick has completed.
 * @param isSession  name -> whether the file can be a session's state file.
 * @returns 'tick' or 'skip'.
 */
function verdict({ names, stateOf, seen, isSession, now, lastTickAt, quietMs }) {
  if (!seen) return 'tick';
  for (const name of names) {
    if (name == null) return 'tick';
    if (!isSession(name)) continue;
    const word = stateOf(name) ?? null;
    const had = Object.prototype.hasOwnProperty.call(seen, name) ? seen[name] : undefined;
    if (had === undefined ? word !== null : word !== had) return 'tick';
  }
  return now - lastTickAt >= quietMs ? 'tick' : 'skip';
}

module.exports = { verdict };
