'use strict';
// Holding announcements while a display is showing Huginn itself.
//
// Some displays mirror a phone's notifications: whatever the phone posts is drawn
// on them a moment later, on top of what they were showing. When what they were
// showing is Huginn — a client of its own, running on that display — every
// "needs you" and every "finished" lands over the page the person is reading, and
// has to be dismissed before they can go on. So that client asks for a HOLD while
// it is awake: what the daemon would have announced waits, and goes out when the
// hold ends.
//
// A hold is short and has to be renewed. It is kept in memory only, so a display
// that has gone away - closed, out of range, crashed - stops holding by saying
// nothing, and a daemon restart ends it. What is WAITING is persisted with the
// rest of the alert state, so a deploy in the middle of a hold loses nothing.
//
// What waits is not replayed blindly. A question answered during the hold is no
// longer a question, and a session that went back to work has not finished: each
// held alert goes out only if it is still true when the hold ends.
//
// Pure functions over plain data: the daemon owns the clock, the state file and
// the delivery (see alertTickInner and the /v1/alerts/hold routes).

/** The longest one request can hold. A display renews every few seconds while awake. */
const MAX_SECONDS = 120;
/**
 * The longest a hold can run on renewals alone. A display wedged awake with
 * nobody looking at it would otherwise keep a phone silent for as long as it
 * kept asking; this way what waited goes out every ten minutes at the latest,
 * and the next request starts a new hold.
 */
const MAX_RUN_MS = 10 * 60 * 1000;
/** A queue, not an archive: past this the oldest entry goes. */
const MAX_HELD = 50;
const MAX_BY = 40;
const DEFAULT_BY = 'a display';

/** `map[key]` only when the map itself holds the key (a session may be called `__proto__`). */
function own(map, key) {
  return map && Object.prototype.hasOwnProperty.call(map, key) ? map[key] : undefined;
}

/**
 * What POST /v1/alerts/hold was asked for.
 *
 * `by` only ever reaches a log line and the answer to the next caller, and it is
 * still cleaned here: control and formatting characters and line separators
 * removed, so a label cannot break a log line in two or reorder it on screen.
 *
 * @returns {{seconds: number, by: string} | {error: string}}
 */
function holdRequest(body) {
  const b = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const s = b.seconds;
  if (typeof s !== 'number' || !Number.isFinite(s) || s < 1 || s > MAX_SECONDS) {
    return { error: `seconds must be a number from 1 to ${MAX_SECONDS}` };
  }
  let by = DEFAULT_BY;
  if (b.by !== undefined) {
    if (typeof b.by !== 'string') return { error: 'by must be a string' };
    const cleaned = Array.from(b.by.replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '').replace(/\s+/g, ' ').trim())
      .slice(0, MAX_BY).join('').trim();
    if (cleaned) by = cleaned;
  }
  return { seconds: s, by };
}

/** Whether `hold` ({since, until, by}, ms) is live at `now`. */
function live(hold, now) {
  return !!(hold && Number(hold.until) > now);
}

/**
 * Begins a hold, or extends the live one.
 *
 * Extending never shortens: two displays may hold at once, and the one that asks
 * for less must not cut the other short. A hold that has run MAX_RUN_MS on
 * renewals ends on the renewal that crosses it (`released`), and the caller's
 * next request begins a new one.
 *
 * @param hold the current hold or null
 * @param want {seconds, by} from holdRequest
 * @param now  ms
 * @returns {{hold: {since, until, by} | null, released: boolean}}
 */
function extend(hold, want, now) {
  const until = now + Math.round(want.seconds * 1000);
  if (!live(hold, now)) return { hold: { since: now, until, by: want.by }, released: false };
  if (now - Number(hold.since) >= MAX_RUN_MS) return { hold: null, released: true };
  return { hold: { since: Number(hold.since), until: Math.max(Number(hold.until), until), by: want.by }, released: false };
}

/** The hold as the routes report it, in epoch seconds; null when none is live. */
function view(hold, now) {
  if (!live(hold, now)) return null;
  return { since: Math.floor(Number(hold.since) / 1000), until: Math.ceil(Number(hold.until) / 1000), by: String(hold.by || DEFAULT_BY) };
}

/** The queue as read back from the state file: an array of alert objects, or nothing. */
function queue(held) {
  return Array.isArray(held)
    ? held.filter((a) => a && typeof a === 'object' && typeof a.key === 'string' && typeof a.kind === 'string')
    : [];
}

/**
 * What is waiting, as the holder is told about it, so the display that asked for
 * the hold can show the news itself, in its own way, without covering anything.
 */
function heldView(held) {
  return queue(held).map((a) => ({
    key: a.key,
    kind: a.kind,
    subject: a.subject ?? null,
    title: a.title ?? '',
    text: a.text ?? '',
    at: Math.floor((Number(a.heldAt) || 0) / 1000),
  }));
}

/**
 * Adds what a tick decided to the queue.
 *
 *   * An alert replaces an earlier one with the same key (a retried finish is one
 *     finish) and keeps its place at the end.
 *   * A resolution whose question is itself still waiting here takes that
 *     question out and is dropped with it: nothing was announced, so there is
 *     nothing to take down.
 *   * ⚠ ANY OTHER RESOLUTION WAITS TOO. It only takes a notification down, which
 *     covers nothing - but the phone that is told also re-reads the watch digest,
 *     and for the length of a hold that digest stands still (see the watch
 *     route), still saying `attention` for the session. Its baseline, just
 *     cleared by the resolution, would read that as a NEW question and post the
 *     stale one again.
 *   * An entry keeps the time it was first held, across a failed delivery.
 *
 * @param held    the queue so far (as persisted)
 * @param decided alerts in the order they were decided
 * @param now     ms
 * @returns {{held: Array, queued: Array, cancelled: Array}} `queued` and `cancelled`
 *          are what this call did, for the log
 */
function take(held, decided, now) {
  let out = queue(held).slice();
  const queued = []; const cancelled = [];
  for (const a of decided || []) {
    if (!a || typeof a !== 'object' || typeof a.key !== 'string' || typeof a.kind !== 'string') continue;
    if (a.kind === 'session_resolved') {
      const i = out.findIndex((x) => x.kind === 'session_attention' && x.subject === a.subject);
      if (i >= 0) { cancelled.push(out[i]); out.splice(i, 1); continue; }
    }
    out = out.filter((x) => x.key !== a.key);
    const entry = { ...a, heldAt: Number(a.heldAt) || now };
    out.push(entry);
    queued.push(entry);
  }
  while (out.length > MAX_HELD) out.shift();
  return { held: out, queued, cancelled };
}

/**
 * Whether a held alert is still true in `obs`, the observation of the tick that
 * is about to send it (the shape decideAlerts takes).
 */
function stillTrue(a, obs) {
  const o = obs && typeof obs === 'object' ? obs : {};
  switch (a.kind) {
    case 'session_attention':
      return own(o.sessions, a.subject) === 'attention';
    // Takes down a notification posted BEFORE the hold (one posted during it
    // never was: see take). Nothing can have made that untrue.
    case 'session_resolved':
      return true;
    case 'session_finished':
      // Working again, waiting on an answer (its own alert says so) or gone.
      return own(o.sessions, a.subject) === 'idle';
    case 'project_idle': {
      // Busy as lib/alerts carryProjectBursts means it: running, or waiting on
      // the owner. One busy session and the project is not idle any more.
      for (const [name, m] of Object.entries(o.sessionsProject || {})) {
        if (!m || String(m.id) !== String(a.subject)) continue;
        const state = own(o.sessions, name);
        if (state === 'running' || state === 'attention') return false;
      }
      return true;
    }
    case 'chat_finished': {
      const c = own(o.chats, a.subject);
      return !!c && !c.running;
    }
    case 'chat_attention': {
      const cur = own(o.chatAsks, a.subject);
      return !!(cur && Array.isArray(cur.asks) && cur.asks.some((x) => x && x.id === a.requestId));
    }
    default:
      return true;
  }
}

/**
 * Ends the wait: what of the queue is still true, in the order it was held.
 *
 * @returns {{alerts: Array, dropped: Array}} `alerts` still carry `heldAt`, which
 *          is how the tick tells a released alert from one it has just decided
 */
function release(held, obs) {
  const alerts = []; const dropped = [];
  for (const a of queue(held)) (stillTrue(a, obs) ? alerts : dropped).push(a);
  return { alerts, dropped };
}

module.exports = {
  holdRequest, live, extend, view, queue, heldView, take, stillTrue, release,
  MAX_SECONDS, MAX_RUN_MS, MAX_HELD, DEFAULT_BY,
};
