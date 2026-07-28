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
    // Two ways to notice, because the obvious one is lossy. `running` going false
    // is an EDGE, and this runs on a timer: a chat that began and ended between two
    // observations was never seen running, so its finish went unreported — measured,
    // on a run that took five seconds against a ten-second tick. The run counter is
    // a durable fact and catches exactly that case. The edge is kept as well, for
    // chats whose meta predates the counter.
    const ranAgain = (cur.finishedRuns || 0) > (before.finishedRuns || 0);
    if (ranAgain || (before.running && !cur.running)) {
      // Keyed by which run finished, so two genuine finishes both get through while
      // a failed send of the SAME finish is still retried rather than duplicated.
      const key = `chat:${id}:${cur.finishedRuns || 0}`;
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

/**
 * Chooses which of the decided alerts Telegram should actually carry.
 *
 * Kept apart from `decideAlerts` because these are two different questions and
 * conflating them was tempting: "did something happen worth telling you about" is
 * about huginn, whereas "should THIS channel carry it" is about which of your
 * devices is currently reachable. Only the second one changes when a phone goes to
 * sleep, and only the second one should be re-decided per delivery.
 *
 * `fallback` is the default because the alternative is both channels firing for
 * every event, and two notifications for one thing teaches the reader to dismiss
 * without looking — at which point the important one is lost too.
 *
 * @param mode      'off' | 'fallback' | 'always'
 * @param appOnline whether a phone has checked in recently enough to have shown
 *                  this itself, per lib/clients
 * @returns {{deliver: Array, held: Array}} — `held` is reported, not discarded
 *          silently, so "nothing arrived" can be told apart from "nothing happened"
 */
function routeAlerts(alerts, { mode = 'fallback', appOnline = false } = {}) {
  if (mode === 'off') return { deliver: [], held: alerts.slice() };
  if (mode === 'always') return { deliver: alerts.slice(), held: [] };
  return appOnline ? { deliver: [], held: alerts.slice() } : { deliver: alerts.slice(), held: [] };
}

/** Drops entries old enough that they can never suppress anything again. */
function pruneSent(sent, now) {
  const out = {};
  for (const [k, t] of Object.entries(sent || {})) {
    if (now - t < REPEAT_MS * 2) out[k] = t;
  }
  return out;
}

module.exports = { decideAlerts, routeAlerts, pruneSent, REPEAT_MS };
