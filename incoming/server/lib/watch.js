'use strict';
// The digest a watching client parks on.
//
// Notifying promptly needs a change signal, not a poll loop: the phone holds one
// request open and the daemon answers the moment anything it would alert on
// actually changes. So this reduces the whole system to the few facts that drive
// an alert — which session wants you, which chat is still running — and hashes
// them. Anything else changing (a pane repainting, a token count) must NOT wake
// the phone up.

const { createHash } = require('node:crypto');

/**
 * @param sessions from listSessions()
 * @param chats    from listChats()
 */
function digest(sessions, chats) {
  const s = {};
  for (const x of sessions || []) s[x.name] = x.state ?? null;
  const c = {};
  for (const x of chats || []) {
    c[x.id] = {
      running: !!x.running,
      pending: Number(x.pending) || 0,
      title: x.title ?? null,
      // A count of completed runs, not a flag. A finish is otherwise only visible
      // as the instant `running` goes false, and anything watching on a timer can
      // miss an edge — a chat that started and finished between two ticks was
      // never seen running at all, so no finish was ever noticed. A counter cannot
      // be missed that way: whenever it is higher than last time, runs completed,
      // however briefly they lasted. It also catches back-to-back runs, where a
      // queued message restarts the chat and `running` never dips.
      finishedRuns: Number(x.finishedRuns) || 0,
    };
  }
  // Sorted keys so the hash depends on the values, not on directory order.
  const stable = JSON.stringify({
    s: Object.keys(s).sort().map((k) => [k, s[k]]),
    c: Object.keys(c).sort().map((k) => [k, c[k].running, c[k].pending, c[k].finishedRuns]),
  });
  return {
    hash: createHash('sha1').update(stable).digest('hex').slice(0, 16),
    sessions: s,
    chats: c,
  };
}

module.exports = { digest };
