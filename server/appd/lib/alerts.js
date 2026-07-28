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

/**
 * When each currently-running session's run BEGAN, carried forward observation to
 * observation.
 *
 * Exists because the obvious source is wrong: the session state file's `ts` is
 * rewritten by the hook on every tool call, so for a busy session "stateSince"
 * means "seconds since the last tool touched it" — and a duration gate fed that
 * measured near-zero for a run of any length. Measured: multiple 15-minute runs,
 * zero finish alerts, because each looked seconds old when it ended.
 *
 * So the watcher keeps its own ledger: a session seen running keeps the start it
 * was first seen running at; one newly running is stamped now; one not running
 * drops out. A daemon restarted mid-run inherits the start from the persisted
 * previous observation — and when there is none, the run is stamped as starting
 * now, which undercounts and may miss that one finish. Conservative on purpose:
 * inventing an earlier start would fire "finished" for runs that never crossed
 * the threshold.
 *
 * @param prevSince {name: epochSeconds} from the previous observation
 * @param sessions  {name: state} of the current observation
 * @param nowSec    epoch seconds
 */
function carryRunStarts(prevSince, sessions, nowSec) {
  const out = {};
  for (const [name, state] of Object.entries(sessions || {})) {
    if (state !== 'running') continue;
    out[name] = Number((prevSince || {})[name]) || nowSec;
  }
  return out;
}

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

  // Questions that stopped waiting — answered in tmux, from another device, or
  // the session was killed. Not news for a person (nothing to read, nothing to
  // do), but the PHONE needs to hear it: a "needs you" notification for a question
  // that no longer exists sits in the shade inviting a tap whose fingerprint will
  // be refused. Iterated over PREV, because a killed session vanishes from `next`
  // entirely and a loop over `next` would never see it go.
  //
  // Two deliberate departures from the alerts above:
  //   * no quiet-window check — a resolution is an edge, and edges cannot repeat;
  //     gating it would leave the second answered-question of the half hour stale.
  //   * it CLEARS the subject's repeat guard (via the zero written below, which
  //     pruneSent then drops) — the question was answered, so a NEW question from
  //     the same session is genuinely new and must not inherit the old one's
  //     30-minute suppression.
  for (const [name, before] of Object.entries(prev.sessions || {})) {
    if (before !== 'attention') continue;
    if ((next.sessions || {})[name] === 'attention') continue;   // still waiting
    alerts.push({
      key: `session-resolved:${name}`,
      kind: 'session_resolved',
      subject: name,
      title: `${name} answered`,
      text: 'The question was handled elsewhere.',
    });
    sentUpdates[`session:${name}`] = 0;
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

module.exports = { decideAlerts, routeAlerts, telegramText, pruneSent, humanDuration, carryRunStarts, REPEAT_MS, LONG_RUN_MS };
