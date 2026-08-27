'use strict';
// Which phones are actually still listening — recorded on the host, because the
// phone cannot report that it stopped reporting.
//
// This exists because "notifications never arrive while the phone is asleep" was
// an unfalsifiable complaint: every piece of evidence lived on the device that had
// gone quiet, and the only way to look was to wake it up, which ends the very
// condition being investigated. A listening app has to keep asking this host
// something, so each ask is stamped here. Afterwards the question "did my phone
// keep checking in overnight?" is answered by a machine that was awake all night.
//
// It also decides the Telegram fallback. A duplicate of every alert on two
// channels is worse than one channel: the reader learns to ignore both. So
// Telegram is for when the app has gone quiet, and `appOnline` is what "quiet"
// means, measured rather than assumed.

// How recently a client must have been seen to count as still listening — judged
// against what that particular mechanism PROMISED, not by one universal number.
//
// Getting this wrong is expensive in both directions, and a single threshold cannot
// avoid both. Too short and a phone whose alarm checks in every ten minutes looks
// absent for seven of them, so Telegram duplicates nearly every alert and the reader
// learns to ignore both channels. Too long and a phone that has genuinely left the
// network keeps its claim on delivery, so the fallback arrives late or not at all.
//
// A stream is contractually never silent: a keepalive every 25 seconds, so three
// minutes of nothing means the socket is dead. The background alarm promises far
// less — roughly one check every ten minutes, and Doze may stretch that — so it is
// given room for two missed beats before being declared gone. Same evidence,
// different expectations.
const FRESH_STREAM_MS = 3 * 60 * 1000;
const FRESH_BEAT_MS = 22 * 60 * 1000;

/** Default for a client that never said which mechanism it was using. */
const FRESH_MS = FRESH_BEAT_MS;

function freshnessFor(kind) {
  return kind === 'stream' ? FRESH_STREAM_MS : FRESH_BEAT_MS;
}

/** Forgotten after this long, so a retired handset stops appearing forever. */
const FORGET_MS = 7 * 24 * 60 * 60 * 1000;

function emptyState() {
  return { clients: {} };
}

/**
 * Records a check-in. Mutates and returns `state`; the daemon is single-process
 * and holds the authoritative copy in memory, so there is no snapshot to go stale.
 *
 * @param id      stable per-installation id sent by the app
 * @param info    {kind, ua, notify} — `notify` is the app's own report of whether
 *                the system will actually display what it posts. An app that is
 *                connected but cannot show a notification is NOT a delivery route,
 *                and treating it as one would silence the fallback for nothing.
 */
function noteSeen(state, id, info, now) {
  if (!id) return state;
  const clients = state.clients || (state.clients = {});
  const prev = clients[id] || { firstAt: now, count: 0 };
  clients[id] = {
    firstAt: prev.firstAt ?? now,
    lastAt: now,
    count: (prev.count || 0) + 1,
    kind: info && info.kind ? String(info.kind).slice(0, 24) : (prev.kind ?? null),
    ua: info && info.ua ? String(info.ua).slice(0, 80) : (prev.ua ?? null),
    // Absent means "did not say", which must not upgrade a previous "cannot".
    notify: info && typeof info.notify === 'boolean' ? info.notify : (prev.notify ?? null),
  };
  return state;
}

/** True when some client could plausibly have shown this alert itself. */
function appOnline(state, now) {
  for (const c of Object.values((state && state.clients) || {})) {
    if (c.notify === false) continue;          // connected but muted: not a route
    if (now - (c.lastAt || 0) < freshnessFor(c.kind)) return true;
  }
  return false;
}

/** Newest first, with the age spelled out so a reader need not do arithmetic. */
function listClients(state, now) {
  return Object.entries((state && state.clients) || {})
    .map(([id, c]) => ({
      id,
      kind: c.kind ?? null,
      ua: c.ua ?? null,
      notify: c.notify ?? null,
      firstAt: Math.floor((c.firstAt || 0) / 1000),
      lastAt: Math.floor((c.lastAt || 0) / 1000),
      ageSeconds: Math.max(0, Math.floor((now - (c.lastAt || 0)) / 1000)),
      checkIns: c.count || 0,
      fresh: now - (c.lastAt || 0) < freshnessFor(c.kind),
      /** What this client's own mechanism promised, so `fresh` can be checked. */
      expectedWithinSeconds: Math.floor(freshnessFor(c.kind) / 1000),
    }))
    .sort((a, b) => b.lastAt - a.lastAt);
}

function pruneClients(state, now, forgetMs = FORGET_MS) {
  const clients = (state && state.clients) || {};
  for (const [id, c] of Object.entries(clients)) {
    if (now - (c.lastAt || 0) > forgetMs) delete clients[id];
  }
  return state;
}

/**
 * Forgets one client outright, ahead of its seven days.
 *
 * The prune above answers "has this gone quiet for a week"; this answers
 * "we have PROOF it is gone" — FCM reporting the install's token unregistered,
 * which is what an uninstall looks like from here. Waiting out FORGET_MS in that
 * case leaves a phone listed for a week after it stopped existing, and the whole
 * point of this file is that the list is evidence rather than assumption.
 *
 * @returns true when a row was actually removed, so a caller knows to persist.
 */
function dropClient(state, id) {
  const clients = (state && state.clients) || {};
  if (!id || !Object.prototype.hasOwnProperty.call(clients, id)) return false;
  delete clients[id];
  return true;
}

module.exports = {
  emptyState, noteSeen, appOnline, listClients, pruneClients, dropClient, freshnessFor,
  FRESH_MS, FRESH_STREAM_MS, FRESH_BEAT_MS, FORGET_MS,
};
