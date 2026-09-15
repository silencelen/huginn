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
 * A name -> value map, normalised so the hash depends on the contents and not
 * on the order the daemon happened to iterate its state in. Null values are
 * kept: "stalled, reset time unknown" is a real state and is not the same as
 * not being stalled at all.
 */
function mapOf(v) {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return {};
  const out = {};
  for (const k of Object.keys(v).sort()) {
    const x = v[k];
    out[String(k)] = x == null ? null : String(x);
  }
  return out;
}

/**
 * @param sessions from listSessions()
 * @param chats    from listChats()
 * @param headroom the few headroom facts an alert turns on (huginn-appd's
 *        headroomFacts()): {mode, stalled:[names], stalls:{name->resetsAt},
 *        laddered:{name->family}, lastResumeAt, lastLadderAt,
 *        sentinels:[names]}. Optional — an older caller passing two arguments
 *        gets the same hash it always did for an idle headroom.
 */
function digest(sessions, chats, headroom) {
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
      // The last thing Claude said, carried so a finish notification can quote the
      // answer instead of only announcing that there is one.
      snippet: x.snippet ?? null,
      createdAt: Number(x.createdAt) || 0,
    };
  }
  // Headroom, which IS a change signal: a limit arming a sentinel, a session
  // stalling on a 429, a resume landing and a model ladder moving are all things
  // the phone is meant to hear about while the app is closed. Mode is in because
  // crossing into `red` is what turns the pill and the notification on.
  //
  // ⚠ THIS FUNCTION REBUILDS FROM AN EXPLICIT FIELD LIST. Anything not named
  // here evaporates silently — that is how `snippet` reached the alert code as
  // null every time, and how a headroom fact added upstream would never wake a
  // phone. Add the field in BOTH places: the object below and the `h:` tuple.
  const hr = headroom && typeof headroom === 'object' ? headroom : {};
  const h = {
    mode: typeof hr.mode === 'string' ? hr.mode : 'ok',
    stalled: Array.isArray(hr.stalled) ? [...hr.stalled].map(String).sort() : [],
    // The two MAPS the desktop's notification rules need to build a sentence.
    // `stalled` is a name list and answers "is anything stuck"; LimitHit has to
    // say WHEN it comes back and Downgraded has to say WHAT it moved to, and
    // neither fact is recoverable from a list of names. Values are carried
    // inside the hash rather than beside it because a reset time moving is news
    // — the notification already on the phone is now wrong.
    stalls: mapOf(hr.stalls),
    laddered: mapOf(hr.laddered),
    // ⚠ A NUMBER, NEVER null. `WatchHeadroom.lastResumeAt` is a non-nullable
    // `Long` and nothing in the client tree sets `coerceInputValues`, so one
    // explicit null here fails the whole /v1/watch decode — which is the watch
    // loop, every notification decision and the headroom toasts, on a daemon
    // that has simply never resumed anything yet. 0 is "never", and it reads as
    // never on both sides.
    lastResumeAt: Number(hr.lastResumeAt) || 0,
    lastLadderAt: Number(hr.lastLadderAt) || 0,
    sentinels: Array.isArray(hr.sentinels) ? [...hr.sentinels].map(String).sort() : [],
  };

  // Sorted keys so the hash depends on the values, not on directory order.
  //
  // Note which fields are IN the hash and which are only carried: the hash is a
  // change signal that wakes a parked phone, so it must contain the facts an alert
  // turns on and nothing else. `title` and `snippet` are payload — a chat renamed,
  // or a snippet rewritten mid-run, is not news, and hashing them would wake every
  // watching phone to tell it so.
  const stable = JSON.stringify({
    s: Object.keys(s).sort().map((k) => [k, s[k]]),
    c: Object.keys(c).sort().map((k) => [k, c[k].running, c[k].pending, c[k].finishedRuns]),
    h: [h.mode, h.stalled, h.lastResumeAt, h.lastLadderAt, h.sentinels,
      Object.entries(h.stalls), Object.entries(h.laddered)],
  });
  return {
    hash: createHash('sha1').update(stable).digest('hex').slice(0, 16),
    sessions: s,
    chats: c,
    headroom: h,
  };
}

module.exports = { digest };
