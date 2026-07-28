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

/** Ceiling on stored devices; a household has a handful, not hundreds. */
const MAX_TOKENS = 20;

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
    return { changed: false, rotated: false };
  }
  tokens[installId] = {
    token,
    firstAt: prev?.firstAt ?? now,
    seenAt: now,
    model: info.model ? String(info.model).slice(0, 60) : (prev?.model ?? null),
    failures: 0,
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
    }))
    .sort((a, b) => b.seenAt - a.seenAt);
}

function count(state) {
  return Object.keys((state && state.tokens) || {}).length;
}

/** Forgets a token FCM has declared dead. */
function drop(state, installId) {
  const tokens = (state && state.tokens) || {};
  if (!tokens[installId]) return false;
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

/** Clears the failure count after a delivery gets through. */
function noteSuccess(state, installId) {
  const t = ((state && state.tokens) || {})[installId];
  if (t) t.failures = 0;
}

module.exports = { emptyState, register, list, count, drop, noteFailure, noteSuccess, MAX_TOKENS };
