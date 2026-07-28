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
 * How long a session must have been running for its finish to be worth saying.
 *
 * Five minutes, chosen as the point where somebody has plausibly stopped watching.
 * Below it a "finished" notification competes with the screen the owner is already
 * looking at; above it, they walked away and this is the whole point of the app.
 */
const LONG_RUN_MS = 5 * 60 * 1000;

/** "7m", "1h 12m" — enough to know whether it was the slow thing you started. */
function humanDuration(ms) {
  const mins = Math.round(ms / 60000);
  if (mins < 60) return `${mins}m`;
  return `${Math.floor(mins / 60)}h ${mins % 60}m`;
}

/**
 * Diffs two observations and returns the alerts worth sending.
 *
 * @param prev  previous {sessions:{name:state}, chats:{id:{running,title}}}
 * @param next  current, same shape
 * @param sent  {key: timestampMs} of what has already been said
 * @param now   ms
 * @param prevAt when `prev` was observed, ms. Lets a chat born since then be told
 *        apart from one that predates the watcher; 0 disables that distinction,
 *        which is the safe reading for state written before this was recorded.
 * @returns {{alerts: Array<{key,kind,subject,title,text}>, sentUpdates: object}}
 */
function decideAlerts(prev, next, sent, now, prevAt = 0) {
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

    // A long task finishing. The counterpart to a chat finishing, and close to the
    // reason the app exists: start something slow, put the phone down, be told.
    //
    // Gated on how long it ran, because a session goes idle after EVERY turn. A
    // ten-second answer going quiet is not news, and announcing it would mean a
    // notification per exchange — the fastest way to get the whole app muted. The
    // threshold is what separates "I am working in this session" from "I left it
    // running and walked away", and only the second one is worth an interruption.
    if (state === 'idle' && before === 'running') {
      const since = Number((prev.sessionsSince || {})[name]) || 0;
      const ranForMs = since ? now - since * 1000 : 0;
      if (ranForMs >= LONG_RUN_MS) {
        // Keyed by which run finished, so a session that runs long twice reports
        // twice while a redelivery of the same finish stays suppressed.
        const key = `session-done:${name}:${since}`;
        if (fresh(key)) {
          alerts.push({
            key,
            kind: 'session_finished',
            subject: name,
            title: `${name} finished`,
            text: `Ran for ${humanDuration(ranForMs)}, now idle.`,
            ranForMs,
          });
          sentUpdates[key] = now;
        }
      }
    }
  }

  for (const [id, cur] of Object.entries(next.chats || {})) {
    const before = (prev.chats || {})[id];
    // A chat absent from the previous observation is usually history — something
    // that existed before anyone was watching — and announcing it would turn a
    // first look into a burst of notifications about the past.
    //
    // But not always, and the exception is not exotic: a chat CREATED and finished
    // entirely inside one window was never in a previous observation either, and
    // under the old blanket rule it never alerted at all. A one-line question
    // answered in eight seconds is an ordinary thing to ask a phone about, and it
    // was silently the one thing this could not report.
    //
    // Creation time separates the two cleanly. Newer than the last observation
    // means it came into existence while we were watching, so its finish is news;
    // older means it predates us, and it is not.
    if (!before) {
      const createdMs = (Number(cur.createdAt) || 0) * 1000;
      const bornSincePrev = prevAt > 0 && createdMs > prevAt;
      if (!bornSincePrev || !(cur.finishedRuns > 0) || cur.running) continue;
      const key = `chat:${id}:${cur.finishedRuns}`;
      if (fresh(key)) {
        const label = cur.title || 'a chat';
        alerts.push({
          key,
          kind: 'chat_finished',
          subject: id,
          title: label.slice(0, 60),
          text: cur.snippet || 'Finished.',
          label,
        });
        sentUpdates[key] = now;
      }
      continue;
    }
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
        // Titled by the chat and bodied by the answer, the way a message from a
        // person would be. "huginn finished: deploy the thing" reports only that
        // something ended, and the one question it provokes — "and?" — is
        // precisely what the snippet already answers.
        alerts.push({
          key,
          kind: 'chat_finished',
          subject: id,
          title: label.slice(0, 60),
          text: cur.snippet || before.snippet || 'Finished.',
          label,
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

/**
 * The Telegram wording for one alert.
 *
 * Quoting a session's pending question here needed a moment's thought, because the
 * standing rule for this channel is statements only - huginn must never ask the owner
 * something over a path that carries no reply. Reporting WHAT a session is asking is
 * not that: it is a status line about huginn, answered in the app or in tmux, so
 * nothing is left waiting on a reply that cannot arrive. Phrased as a report for
 * exactly that reason ("Asked:", options listed flat) rather than as a question put
 * to the reader.
 *
 * Worth having at all because "a session needs you" says something is waiting without
 * saying what, so the only possible response is to go and look.
 */
function telegramText(a) {
  // A finished chat is titled by the chat itself, which reads correctly on a
  // phone (where the app supplies the context) and ambiguously here, where an
  // unadorned project name arriving out of nowhere could be anything.
  if (a.kind === 'chat_finished') return `\u{1F514} Chat finished: ${a.label || a.title}\n${a.text}`;
  const head = `\u{1F514} ${a.title}`;
  if (a.kind !== 'session_attention' || !a.question) return `${head}\n${a.text}`;
  const opts = (a.options || []).map((o) => `${o.number}) ${o.label}`).join('\n');
  return [head, `Asked: ${a.question}`, opts].filter(Boolean).join('\n');
}

/** Drops entries old enough that they can never suppress anything again. */
function pruneSent(sent, now) {
  const out = {};
  for (const [k, t] of Object.entries(sent || {})) {
    if (now - t < REPEAT_MS * 2) out[k] = t;
  }
  return out;
}

module.exports = { decideAlerts, routeAlerts, telegramText, pruneSent, humanDuration, REPEAT_MS, LONG_RUN_MS };
