'use strict';
// Refreshing the OAuth token of a SAVED, INACTIVE Claude login.
//
// Claude Code refreshes its own credentials as it runs, which covers exactly one
// account: the active one. Every other saved profile sits in this daemon's store
// with an access token that dies within hours, and from then on its headroom is
// unreadable, `planForCredentials` short-circuits, and the auto-switcher has
// nothing it is allowed to switch to. The fix is to do for the idle profiles
// what the CLI does for the live one — and ONLY for the idle ones.
//
// That "only" is the whole safety story, so it is stated once, here. Refreshing
// the ACTIVE account logs the owner out: the token endpoint ROTATES the pair, so
// our stored copy moves forward while ~/.claude/.credentials.json keeps the old
// refresh token. The next time a `claude` process refreshes, it is handed
// `invalid_grant` — and its documented response is to write refreshToken:"",
// accessToken:"", expiresAt:0 over its own file. The owner is signed out
// mid-session with no visible cause. Hence: the active guard is re-checked
// INSIDE the lock, immediately before the POST, by refresh-token fingerprint
// against whatever the credentials file holds at that instant.
//
// The request/response shapes below were read out of the shipped CLI binary
// (2.1.258) by spike `oauth-refresh.md`; they are not documented anywhere else
// and Anthropic can change them in any release. An unexpected non-200 therefore
// fails CLOSED — it records a status and stops — rather than retrying.
//
// Nothing here logs, prints or returns a token value. Callers get a slug and one
// status word.

const crypto = require('node:crypto');

/** Pinned from CLI 2.1.258. Overridable per-call for tests; never in production. */
const TOKEN_URL = 'https://platform.claude.com/v1/oauth/token';

/**
 * The PUBLIC OAuth client identifier Claude Code ships with — not a secret. No
 * client_secret exists anywhere in this flow; the authorization-code leg uses
 * PKCE and the refresh leg authenticates with the refresh token itself.
 */
const CLIENT_ID = '9d1c250a-e61b-44d9-88ed-5944d1962f5e';

/**
 * The CLI's default scope set, sent in full on every refresh.
 *
 * Stored profiles carry no `clientId`, which puts us on the branch where the CLI
 * sends this whole set rather than the narrower one on the record. Sending only
 * the record's own scopes would shrink the grant a little further on every
 * refresh until something the owner relies on stops working, weeks later, for no
 * traceable reason.
 */
const SCOPES = Object.freeze([
  'user:profile',
  'user:inference',
  'user:sessions:claude_code',
  'user:mcp_servers',
  'user:file_upload',
]);

/** Access tokens are refreshed with this much life left, matching the CLI. */
const SKEW_MS = 5 * 60 * 1000;

/**
 * How long to wait before looking at a profile again when its own expiry cannot
 * say. Roughly one token lifetime, which is the point: a saved inactive profile
 * is PERMANENTLY past `expiresAt` between refreshes, so a scheduler driven by
 * "is it expired" alone would hammer a per-account rate-limited endpoint forever.
 */
const REFRESH_FLOOR_MS = 8 * 60 * 60 * 1000;

/** `fresh` stops at this much remaining life; below it the row reads `expiring`. */
const FRESH_MS = 60 * 60 * 1000;

/** Warn the owner this far ahead of the refresh token's OWN expiry. */
const WARN_AHEAD_MS = 48 * 60 * 60 * 1000;

/**
 * Every status one attempt can end in. Deliberately the CLI's own vocabulary so
 * our logs correlate with its telemetry, plus `invalid_scope`, which is internal:
 * it is never returned to a caller, only used to trigger the one narrow-scope
 * retry described in `buildBody`.
 */
const STATUSES = Object.freeze([
  'refreshed', 'not_needed', 'active_skipped', 'no_such_profile', 'no_refresh_token',
  'not_refreshable', 'refresh_token_expired', 'known_dead_refresh_token', 'account_on_hold',
  'lock_busy', 'lock_timeout', 'lock_error', 'lock_compromised', 'refresh_failed',
]);

/**
 * Accepts a whole profile record, a credentials blob, or a bare oauth object.
 *
 * Callers hold all three shapes — the store keeps `rec.credentials.claudeAiOauth`,
 * `activate` passes `rec.credentials`, and tests build the inner object directly —
 * and a helper that only understood one of them would silently return `{}` for
 * the other two, which reads as "no refresh token" and skips the account.
 */
function oauthOf(x) {
  if (!x || typeof x !== 'object') return {};
  if (x.credentials && x.credentials.claudeAiOauth) return x.credentials.claudeAiOauth;
  if (x.claudeAiOauth) return x.claudeAiOauth;
  return x;
}

/** The daemon's own refresh bookkeeping for a record, if it has any yet. */
function refreshOf(x) {
  return (x && typeof x === 'object' && x.refresh && typeof x.refresh === 'object') ? x.refresh : null;
}

/**
 * Scopes a record holds beyond the shipped set.
 *
 * Kept as "anything not in SCOPES" rather than a prefix match: the point is to
 * never drop a grant this host was given, and a conservative superset costs
 * nothing (the server ignores scopes it will not issue) while a too-clever
 * filter silently loses one.
 */
function projectScopes(scopes) {
  if (!Array.isArray(scopes)) return [];
  return scopes.filter((s) => typeof s === 'string' && s && !SCOPES.includes(s));
}

/**
 * The JSON body of a refresh POST. JSON, not form-encoded — the only
 * form-encoded refresh grant in the CLI belongs to the unrelated corporate-IdP
 * path, and copying that one is a plausible-looking way to get a 400 forever.
 *
 * `legacyScopes` is the SECOND leg: if the server rejects the full set with an
 * invalid-scope error, the call is retried once with exactly the scopes the
 * record already holds. That fallback exists because the full set is a guess
 * about what this login is entitled to, and a guess must not be able to turn a
 * working profile into a dead one.
 */
function buildBody(rec, opts = {}) {
  const o = oauthOf(rec);
  const own = Array.isArray(o.scopes) ? o.scopes.filter((s) => typeof s === 'string' && s) : [];
  const scope = opts.legacyScopes
    ? own
    : [...SCOPES, ...projectScopes(own)];
  return {
    grant_type: 'refresh_token',
    refresh_token: o.refreshToken,
    client_id: opts.clientId || CLIENT_ID,
    scope: scope.join(' '),
  };
}

/**
 * The token response folded into a replacement `claudeAiOauth` object.
 *
 * Three fields are carried FORWARD rather than read from the response, because
 * the token endpoint does not return them: `subscriptionType` and `rateLimitTier`
 * come from a separate profile fetch the CLI makes, and `refreshTokenExpiresAt`
 * is only present when the server chose to rotate the refresh token's own
 * lifetime. Taking the response's silence as "null" would blank the very fields
 * the auto-switcher and the freshness column read.
 *
 * `refresh_token` is likewise optional: its absence means "keep using the one
 * you sent", and treating it as null would throw the login away.
 */
function applyResponse(rec, json, nowMs = Date.now()) {
  const o = oauthOf(rec);
  const j = json || {};
  const expiresIn = Number(j.expires_in);
  const rtExpiresIn = Number(j.refresh_token_expires_in);
  const scopes = typeof j.scope === 'string'
    ? j.scope.split(' ').filter(Boolean)
    : (Array.isArray(o.scopes) ? o.scopes : []);
  return {
    accessToken: j.access_token,
    refreshToken: j.refresh_token || o.refreshToken,
    expiresAt: Number.isFinite(expiresIn) ? nowMs + expiresIn * 1000 : (o.expiresAt ?? null),
    refreshTokenExpiresAt: Number.isFinite(rtExpiresIn) && rtExpiresIn > 0
      ? nowMs + rtExpiresIn * 1000
      : (o.refreshTokenExpiresAt ?? null),
    scopes,
    subscriptionType: o.subscriptionType ?? null,
    rateLimitTier: o.rateLimitTier ?? null,
  };
}

/**
 * A non-200 turned into one of our status words.
 *
 * `invalid_grant` is the one that matters and the one we handle differently from
 * the CLI: it means the refresh token is dead and only an interactive sign-in
 * recovers the login. The CLI responds by ZEROING the credentials on disk. We
 * mark the record and keep every byte of it, because a stored profile is the
 * only trace that this account was ever here, and an owner staring at a list
 * with a row missing has no idea what to sign back in as.
 */
function classifyError(status, body) {
  const text = typeof body === 'string' ? body : JSON.stringify(body || {});
  const hay = (text || '').toLowerCase();
  if (hay.includes('invalid_grant')) return 'known_dead_refresh_token';
  if (hay.includes('invalid_scope')) return 'invalid_scope';
  if (hay.includes('account_on_hold') || hay.includes('on hold')) return 'account_on_hold';
  // 400s are the endpoint's way of saying the grant itself is wrong, but only
  // the bodies above name WHICH way; anything else stays generic on purpose,
  // because guessing at an unfamiliar body is how a transient error gets
  // recorded as a permanent one.
  return 'refresh_failed';
}

/**
 * When this profile should next be looked at.
 *
 * Driven off the token's own expiry rather than a fixed sweep: while `expiresAt`
 * is still in the future the answer is the CLI's own five-minute skew, and once
 * it has passed the answer is a FLOOR, not "now". Without that floor a profile
 * whose refresh keeps failing — the account on hold, the endpoint down — would
 * be retried every tick forever against a per-account rate limit.
 */
function nextRefreshAt(rec, nowMs = Date.now()) {
  const o = oauthOf(rec);
  const exp = typeof o.expiresAt === 'number' ? o.expiresAt : null;
  if (exp !== null && exp - SKEW_MS > nowMs) return exp - SKEW_MS;
  return nowMs + REFRESH_FLOOR_MS;
}

/**
 * The word shown against a saved account, and the word the activate route gates
 * on.
 *
 * `unrefreshable` beats `refreshing` deliberately: a record whose refresh token
 * has expired cannot become valid however many attempts are in flight, and
 * showing "refreshing…" over a login that is permanently dead is how an owner
 * waits all day for something that is never going to happen.
 */
function freshnessOf(rec, nowMs = Date.now(), opts = {}) {
  const o = oauthOf(rec);
  const r = refreshOf(rec) || opts.refresh || null;
  if (!o.refreshToken) return 'unrefreshable';
  if (r && typeof r.deadAt === 'number' && r.deadAt) return 'unrefreshable';
  if (typeof o.refreshTokenExpiresAt === 'number' && o.refreshTokenExpiresAt <= nowMs) return 'unrefreshable';
  const inFlight = opts.refreshing === true || (r && r.lastStatus === 'refreshing');
  if (inFlight) return 'refreshing';
  const exp = typeof o.expiresAt === 'number' ? o.expiresAt : null;
  if (exp === null) return 'expired';
  if (exp <= nowMs) return 'expired';
  if (exp - nowMs <= FRESH_MS) return 'expiring';
  return 'fresh';
}

/** The date an `unrefreshable` row can be explained by, or null. */
function deadSince(rec) {
  const o = oauthOf(rec);
  const r = refreshOf(rec);
  if (typeof o.refreshTokenExpiresAt === 'number' && o.refreshTokenExpiresAt) return o.refreshTokenExpiresAt;
  if (r && typeof r.deadAt === 'number' && r.deadAt) return r.deadAt;
  if (typeof o.expiresAt === 'number' && o.expiresAt) return o.expiresAt;
  return null;
}

/** True when the refresh token's own expiry is close enough to tell the owner. */
function refreshTokenWarnDue(rec, nowMs = Date.now()) {
  const o = oauthOf(rec);
  const rt = typeof o.refreshTokenExpiresAt === 'number' ? o.refreshTokenExpiresAt : null;
  if (rt === null) return false;
  return rt > nowMs && rt - nowMs <= WARN_AHEAD_MS;
}

/** Same truncated SHA-256 of the refresh token the store keys profiles by. */
function printOf(x) {
  const rt = oauthOf(x).refreshToken;
  if (!rt) return null;
  return crypto.createHash('sha256').update(rt).digest('hex').slice(0, 16);
}

// ---------------------------------------------------------------- the flow
//
// The orchestration lives here rather than in huginn-appd.js so it can be
// driven with a fake `post` and a scratch store, which is the only way the
// dangerous cases — the active guard, `invalid_grant` not zeroing a record, a
// held lock changing nothing on disk — get a test at all. huginn-appd.js keeps
// `refreshProfile(slug)`: it supplies the real HTTP, the real lock and the
// logging, and is the only thing that ever runs on a timer.

/**
 * One refresh attempt against a store, start to finish. Returns a status word.
 *
 * @param store  an AccountStore
 * @param slug   which profile
 * @param deps   {post, lock, now, lockOpts, onStatus}
 *   `post(body, {signal})` -> {ok, status, json, text}. `lock(dir, opts)` ->
 *   {ok, signal, release} | {ok:false, status}. Both are injected so no test
 *   can reach the real token endpoint by accident.
 */
async function refreshWithStore(store, slug, deps = {}) {
  const now = typeof deps.now === 'function' ? deps.now : Date.now;
  const post = deps.post;
  const acquire = deps.lock;
  const note = typeof deps.onStatus === 'function' ? deps.onStatus : () => { };
  const record = (status, patch = {}) => {
    try {
      store.recordRefresh(slug, { lastAt: now(), lastStatus: status, ...patch });
    } catch { /* the record is the point, not the bookkeeping */ }
    note(status);
    return status;
  };

  let rec = store.readProfile(slug);
  if (!rec) { note('no_such_profile'); return 'no_such_profile'; }
  let o = oauthOf(rec);
  if (!o.refreshToken) return record('no_refresh_token');

  // An inference-less token cannot be traded for a working one, and a record
  // with neither that scope nor a known subscription is not a Claude Code login
  // at all. Checked before anything touches the network.
  const canRefresh = (Array.isArray(o.scopes) && o.scopes.includes('user:inference')) || !!o.subscriptionType;
  if (!canRefresh) return record('not_refreshable');

  const r0 = refreshOf(rec);
  if (r0 && r0.deadAt) return record('known_dead_refresh_token');
  if (typeof o.refreshTokenExpiresAt === 'number' && o.refreshTokenExpiresAt <= now()) {
    return record('refresh_token_expired');
  }
  if (typeof o.expiresAt === 'number' && o.expiresAt - SKEW_MS > now()) {
    return record('not_needed', { nextAt: nextRefreshAt(rec, now()) });
  }
  // Cheap first look, so the common case never even takes the lock.
  if (printOf(rec.credentials) === printOf(store.readActive())) return record('active_skipped');

  const lock = await acquire();
  if (!lock || !lock.ok) return record((lock && lock.status) || 'lock_error');

  try {
    // Re-read INSIDE the lock. Everything decided above was decided against a
    // file another writer was free to replace in the meantime — and the one
    // decision that must not be stale is the active guard, because between the
    // check above and this line the owner may have switched TO this account.
    rec = store.readProfile(slug);
    if (!rec) return record('no_such_profile');
    o = oauthOf(rec);
    if (!o.refreshToken) return record('no_refresh_token');
    if (printOf(rec.credentials) === printOf(store.readActive())) return record('active_skipped');

    let resp = await post(buildBody(rec), { signal: lock.signal });
    if (!resp.ok && classifyError(resp.status, resp.text ?? resp.json) === 'invalid_scope') {
      // One retry with the record's own scopes — see buildBody.
      resp = await post(buildBody(rec, { legacyScopes: true }), { signal: lock.signal });
    }
    if (!resp.ok) {
      const status = classifyError(resp.status, resp.text ?? resp.json);
      if (status === 'known_dead_refresh_token') {
        // Marked, never zeroed. The tokens stay exactly as they are so the row
        // keeps its label, its history and its uuid, and the owner sees an
        // account to sign back in as rather than a gap.
        return record('known_dead_refresh_token', { deadAt: now() });
      }
      return record(status === 'invalid_scope' ? 'refresh_failed' : status,
        { nextAt: now() + REFRESH_FLOOR_MS });
    }

    const fresh = applyResponse(rec, resp.json, now());
    if (!fresh.accessToken || !fresh.refreshToken) return record('refresh_failed');

    // WRITE-BACK FIRST, before any enrichment. The server has already rotated:
    // the pair we just received exists nowhere but in this process, and the old
    // refresh token is dead. A crash here without this line loses the login for
    // good. Routed through save() so the record is filed under its accountUuid —
    // the slug survives, which is the whole point of the identity ladder — and
    // the superseded pair is archived rather than dropped.
    store.save(rec.email, { claudeAiOauth: fresh }, {
      accountUuid: rec.accountUuid || null,
      taggedId: rec.taggedId || null,
      orgName: rec.orgName || null,
      firstSeen: rec.firstSeen || undefined,
      refresh: {
        ...(refreshOf(rec) || {}),
        lastAt: now(),
        lastStatus: 'refreshed',
        nextAt: nextRefreshAt({ claudeAiOauth: fresh }, now()),
        deadAt: null,
      },
    });
    note('refreshed');
    return 'refreshed';
  } catch (e) {
    if (lock.compromised) return record('lock_compromised');
    return record('refresh_failed', { nextAt: now() + REFRESH_FLOOR_MS });
  } finally {
    try { lock.release(); } catch { /* a stale lock is stolen at 60s anyway */ }
  }
}

module.exports = {
  TOKEN_URL, CLIENT_ID, SCOPES, STATUSES,
  SKEW_MS, REFRESH_FLOOR_MS, FRESH_MS, WARN_AHEAD_MS,
  buildBody, applyResponse, classifyError, nextRefreshAt, freshnessOf,
  deadSince, refreshTokenWarnDue, projectScopes, oauthOf, refreshOf, printOf,
  refreshWithStore,
};
