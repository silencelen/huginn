'use strict';
// The registration tokens FCM delivers to.
//
// Kept as a small pure module because the rules around them are easy to get subtly
// wrong in ways that only show up weeks later:
//
//   * A token ROTATES. Firebase reissues it after a reinstall, a restore, or at its
//     own discretion, so the same phone appears under a new token and the old one is
//     dead. Keying by the app's own installation id lets a re-registration replace
//     its predecessor instead of accumulating beside it — otherwise every reinstall
//     leaves a corpse that is retried until FCM finally reports it gone.
//   * A dead token must be dropped, but only when FCM says the TOKEN is dead. An
//     outage or a bad credential must never be allowed to empty this list, which
//     would silently unregister a working phone with no way back but a reinstall.
//   * The per-install DELIVERY COUNT hangs off the same row, and the phone
//     compares it against its own. A count that restarts without saying so is
//     worse than no count at all — see [mintEpoch].

const crypto = require('node:crypto');

/** Ceiling on stored devices; a household has a handful, not hundreds. */
const MAX_TOKENS = 20;

/**
 * A name for one run of counting.
 *
 * WHY A COUNT NEEDS A NAME. The phone's whole battery story rests on comparing
 * what this host says it sent against what actually arrived: sent no more than
 * arrived means nothing is being dropped, so stay on the hourly alarm however
 * quiet the night. Two counters, two processes, and no way to notice when they
 * stopped counting from the same place — which they eventually do. A token
 * rotation used to rebuild this row and restart at 0; a registration retired
 * over a dead token takes the tally with it; and push.json is re-read on every
 * touch, so a file that is briefly unreadable empties the store and the next
 * write makes it permanent. The phone sees none of that. It sees its own number
 * overtake the host's, concludes nothing is ever dropped, and stops checking —
 * exactly the failure the check exists to catch. On 2026-09-15 the notifications
 * page read "1274 of 916 pushes arrived — nothing dropped".
 *
 * So a count carries the identity of the run it belongs to. Same epoch means the
 * two numbers are still about the same span and may be compared; a different one
 * means this host restarted counting and the phone must rebaseline rather than
 * believe the difference. Minted per install and never derived from the install
 * id, because a store recreated from nothing must not be able to reproduce the
 * epoch it lost.
 */
function mintEpoch(now) {
  return `${Number(now || Date.now()).toString(36)}-${crypto.randomBytes(6).toString('hex')}`;
}

function emptyState() {
  return { tokens: {} };
}

/**
 * Records (or replaces) the token for one installation.
 *
 * @param installId the app's stable per-install id — the same one it uses to check in
 * @returns {{changed: boolean, rotated: boolean}}
 */
function register(state, installId, token, now, info = {}) {
  if (!installId || !token) return { changed: false, rotated: false };
  const tokens = state.tokens || (state.tokens = {});
  const prev = tokens[installId];
  const rotated = !!prev && prev.token !== token;
  if (prev && prev.token === token) {
    // Same token again: refresh the timestamp so it does not look abandoned, but
    // report no change so callers need not persist on every app start.
    prev.seenAt = now;
    // Unless the row predates epochs, in which case this is the cheapest moment
    // to give it one — the app re-registers on every start. The tally is KEPT: it
    // is not wrong, merely unvouched-for, and a fresh epoch is the honest way to
    // say so to a phone that has been counting since before the host was.
    if (!prev.pushEpoch) {
      prev.pushEpoch = mintEpoch(now);
      return { changed: true, rotated: false };
    }
    return { changed: false, rotated: false };
  }
  tokens[installId] = {
    token,
    firstAt: prev?.firstAt ?? now,
    seenAt: now,
    model: info.model ? String(info.model).slice(0, 60) : (prev?.model ?? null),
    failures: 0,
    // ⚠ CARRIED ACROSS A ROTATION, NOT RESET. Firebase reissues a token at its
    // own discretion — after a reinstall, a restore, or for no reason the phone
    // is told. The INSTALL is the same phone and its own received-count does not
    // restart, so restarting this one made the two incomparable from that moment
    // on, with nothing anywhere to say it had happened. That is how a host that
    // had delivered 1321 pushes came to claim 916.
    pushEpoch: prev?.pushEpoch || mintEpoch(now),
    pushes: prev?.pushes || 0,
    lastPushAt: prev?.lastPushAt || 0,
  };

  // Oldest first, so a runaway registrant cannot push out a live phone.
  const ids = Object.keys(tokens);
  if (ids.length > MAX_TOKENS) {
    ids.sort((a, b) => (tokens[a].seenAt || 0) - (tokens[b].seenAt || 0));
    for (const id of ids.slice(0, ids.length - MAX_TOKENS)) delete tokens[id];
  }
  return { changed: true, rotated };
}

/** Every token to send to, with the installation it belongs to. */
function list(state) {
  return Object.entries((state && state.tokens) || {})
    .map(([installId, t]) => ({
      installId,
      token: t.token,
      model: t.model ?? null,
      firstAt: Math.floor((t.firstAt || 0) / 1000),
      seenAt: Math.floor((t.seenAt || 0) / 1000),
      failures: t.failures || 0,
      pushes: t.pushes || 0,
      lastPushAt: Math.floor((t.lastPushAt || 0) / 1000),
      // Null rather than absent on a row written before 3.0.5: a client that
      // cannot see an epoch must not be left guessing whether it simply missed it.
      pushEpoch: t.pushEpoch || null,
    }))
    .sort((a, b) => b.seenAt - a.seenAt);
}

function count(state) {
  return Object.keys((state && state.tokens) || {}).length;
}

/** Aggregate delivery history, which survives a device being dropped. */
function totals(state) {
  return {
    pushed: (state && state.pushed) || 0,
    lastPushAt: Math.floor(((state && state.lastPushAt) || 0) / 1000),
  };
}

/** Forgets a token FCM has declared dead. */
/**
 * Forgets an install, optionally only while it still holds [token].
 *
 * The token check exists because rotation is EXACTLY when a dead-token verdict
 * arrives: FCM answers UNREGISTERED for the token being retired at the same moment
 * the phone registers its replacement. Dropping by installId alone deleted that
 * replacement, so push went silent until the app was opened again — the failure
 * mode the whole heartbeat exists to avoid.
 */
function drop(state, installId, token) {
  const tokens = (state && state.tokens) || {};
  const cur = tokens[installId];
  if (!cur) return false;
  if (token !== undefined && cur.token !== token) return false;
  delete tokens[installId];
  return true;
}

/**
 * Records a transient failure without discarding the token.
 *
 * Counted rather than acted upon: the count is diagnostic, so a phone that is merely
 * off the network for a week is still registered when it comes back.
 */
function noteFailure(state, installId) {
  const t = ((state && state.tokens) || {})[installId];
  if (!t) return;
  t.failures = (t.failures || 0) + 1;
}

/**
 * Records a delivery that got through.
 *
 * Counted as well as cleared, because the original version only recorded FAILURES —
 * so a working push left no trace at all and the only evidence it had happened was a
 * 200 buried in the request log. For a feature whose whole selling point is arriving
 * while you are not watching, "it worked" has to be as visible as "it did not".
 */
function noteSuccess(state, installId, now) {
  const t = ((state && state.tokens) || {})[installId];
  if (!t) return;
  t.failures = 0;
  t.pushes = (t.pushes || 0) + 1;
  // Same lazy mint as [register]: a host that has been up for weeks can deliver
  // to a pre-3.0.5 row long before the app next restarts and re-registers, and
  // a count nobody can name is a count the phone cannot use.
  if (!t.pushEpoch) t.pushEpoch = mintEpoch(now);
  if (now) t.lastPushAt = now;
  state.pushed = (state.pushed || 0) + 1;
  if (now) state.lastPushAt = now;
}

/**
 * How many pushes this host believes it has delivered to one install.
 *
 * Exists so the phone can tell "no push because nothing happened" apart from "no
 * push because FCM is broken" — indistinguishable from the phone alone, and the
 * difference decides how often it has to wake itself up to check. Comparing counts
 * rather than timestamps keeps clock skew out of the answer entirely.
 */
function sentTo(state, installId) {
  const t = ((state && state.tokens) || {})[installId];
  return (t && t.pushes) || 0;
}

/**
 * Which run of counting [sentTo]'s answer belongs to.
 *
 * Null for an install this host has no row for, and for a row written before
 * 3.0.5 — in both cases the honest answer is "unknown", which is not the same as
 * a new epoch and must not make a client rebaseline on its own. See [mintEpoch]
 * for what changing it means.
 */
function epochOf(state, installId) {
  const t = ((state && state.tokens) || {})[installId];
  return (t && t.pushEpoch) || null;
}

/**
 * One pass of the uninstall sweep: ask about every stored token, report back.
 *
 * WHY THIS EXISTS. A phone that has been uninstalled cannot tell this daemon so,
 * and its check-ins simply stop — which looks identical to a phone in a drawer,
 * a phone abroad, and a phone whose owner turned notifications off. On a host
 * that alerts rarely, the token of a phone deleted in March is first discovered
 * to be dead by the first alert in June, because a real send is the only thing
 * that has ever asked. A validate-only probe asks without delivering anything,
 * so the discovery no longer waits on there being bad news to deliver.
 *
 * COLLECTS, DOES NOT APPLY. Same reasoning as deliverPush: this awaits one
 * network round trip per token, and POST /v1/push/register writes the same file
 * meanwhile — a phone registering a rotated token inside that window would be
 * erased by a snapshot taken before the sweep started. The caller re-reads the
 * state and applies these outcomes against THAT.
 *
 * A `dead` verdict carries the token it was about, so the caller's drop can be
 * guarded on the install still holding it. Rotation is precisely when a dead
 * verdict arrives — FCM answers UNREGISTERED for the token being retired at the
 * moment the phone registers its replacement.
 *
 * @param validate  async (token) => {ok, dead, status, error}; the FCM sender's
 *                  probe, or a stub. Never called for an empty state.
 * @returns {Promise<{checked: number, dead: Array, failed: number}>}
 */
async function reconcile(state, validate) {
  const devices = list(state);
  const out = { checked: 0, dead: [], failed: 0 };
  for (const d of devices) {
    out.checked += 1;
    let r;
    try {
      r = await validate(d.token);
    } catch (e) {
      // A thrown probe is a broken SENDER, never a dead token. Counting it as
      // dead would unregister the whole fleet the first time the network hiccups
      // mid-sweep, and nothing could push again until each phone re-registered.
      r = { ok: false, dead: false, status: 0, error: e.message };
    }
    if (r && r.dead) out.dead.push({ installId: d.installId, token: d.token, error: r.error || null });
    else if (!r || !r.ok) out.failed += 1;
  }
  return out;
}

module.exports = {
  emptyState, register, list, count, drop, noteFailure, noteSuccess, totals, sentTo,
  epochOf, mintEpoch, reconcile, MAX_TOKENS,
};
