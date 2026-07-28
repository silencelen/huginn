'use strict';
// Deciding what is worth interrupting somebody for.
//
// Two delivery routes exist and they have opposite characters, so the DECISION is
// kept here, separate from both:
//
//   * the app's foreground service — instant, but only while the app lives;
//   * Telegram from this host — arrives with the app closed, costs no battery,
//     and is the only route that works when the phone has been asleep for hours.
//
// Push therefore means the HOST noticing, not the phone noticing. The rules below
// are the whole reason this is a module and not three lines in a loop: an alert
// that fires twice, or fires for something that did not happen, gets muted by its
// reader and then the useful ones are gone too.

/** Minimum gap before the same subject may alert again. */
const REPEAT_MS = 30 * 60 * 1000;

/**
 * Diffs two observations and returns the alerts worth sending.
 *
 * @param prev  previous {sessions:{name:state}, chats:{id:{running,title}}}
 * @param next  current, same shape
 * @param sent  {key: timestampMs} of what has already been said
 * @param now   ms
 * @returns {{alerts: Array<{key,kind,subject,title,text}>, sentUpdates: object}}
 */
function decideAlerts(prev, next, sent, now) {
  const alerts = [];
  const sentUpdates = {};

  // The first observation has nothing to compare against. Alerting on it would
  // announce everything that was already true when the watcher started, which is
  // noise about the past.
  if (!prev) return { alerts, sentUpdates };

  const fresh = (key) => {
    const last = sent && sent[key];
    return !last || now - last >= REPEAT_MS;
  };

  for (const [name, state] of Object.entries(next.sessions || {})) {
    const before = (prev.sessions || {})[name];
    // Only the TRANSITION into needing an answer. A session that has been waiting
    // for an hour has not become newsworthy again.
    if (state === 'attention' && before !== 'attention') {
      const key = `session:${name}`;
      if (fresh(key)) {
        alerts.push({
          key,
          kind: 'session_attention',
          subject: name,
          title: `${name} needs you`,
          text: `Claude Code session ${name} is waiting for an answer.`,
        });
        sentUpdates[key] = now;
      }
    }
  }

  for (const [id, cur] of Object.entries(next.chats || {})) {
    const before = (prev.chats || {})[id];
    if (!before) continue;                       // a chat that appeared mid-window
    if (before.running && !cur.running) {
      const key = `chat:${id}`;
      if (fresh(key)) {
        const label = cur.title || before.title || 'a chat';
        alerts.push({
          key,
          kind: 'chat_finished',
          subject: id,
          title: 'Chat finished',
          text: `huginn finished: ${label}`,
        });
        sentUpdates[key] = now;
      }
    }
  }

  return { alerts, sentUpdates };
}

/** Drops entries old enough that they can never suppress anything again. */
function pruneSent(sent, now) {
  const out = {};
  for (const [k, t] of Object.entries(sent || {})) {
    if (now - t < REPEAT_MS * 2) out[k] = t;
  }
  return out;
}

module.exports = { decideAlerts, pruneSent, REPEAT_MS };
