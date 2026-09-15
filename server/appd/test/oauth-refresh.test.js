'use strict';
// Refreshing a SAVED, INACTIVE login's OAuth token.
//
// Two of these tests are the reason the feature is allowed to exist at all:
// "the ACTIVE account is never posted" and "invalid_grant marks the record and
// never zeroes it". The first is the difference between a background refresher
// and a background logout — rotating the live account's pair leaves the CLI
// holding a stale refresh token, and the CLI's documented answer to the
// invalid_grant that follows is to blank its own credentials file. The second is
// where we deliberately part company with the CLI: a dead login must stay in the
// list, with its label and its uuid, so the owner knows what to sign back in as.
//
// SAFETY: no test in this file makes a network call. `post` is always a counting
// fake, so a regression that reached for the real token endpoint would fail here
// rather than rotate somebody's live credentials.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, fingerprint } = require('../lib/accounts');
const oauthlock = require('../lib/oauthlock');
const {
  TOKEN_URL, CLIENT_ID, SCOPES,
  buildBody, applyResponse, classifyError, nextRefreshAt, freshnessOf,
  refreshWithStore, refreshTokenWarnDue, deadSince,
} = require('../lib/oauth-refresh');

const UUID_A = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const HOUR = 3600_000;
/** Fast lock timings: the ladder's arithmetic is oauthlock.test.js's problem. */
const FAST = { attempts: 2, retryMs: 5, probeMs: 30, update: 50 };

function creds(refresh, extra = {}) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      expiresAt: 1785187450640,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
      ...extra,
    },
  };
}

function newStore() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oauthref-'));
  const credPath = path.join(root, '.credentials.json');
  return { store: new AccountStore(path.join(root, 'accounts'), credPath), credPath, root };
}

/** A fake token endpoint that counts, so "no network call" is assertable. */
function fakePost(reply) {
  const calls = [];
  const post = async (body) => {
    calls.push(body);
    const r = typeof reply === 'function' ? reply(body, calls.length) : reply;
    return r;
  };
  post.calls = calls;
  return post;
}

const okReply = (over = {}) => ({
  ok: true,
  status: 200,
  json: {
    access_token: 'at-NEW',
    refresh_token: 'rt-NEW',
    expires_in: 28800,
    scope: SCOPES.join(' '),
    ...over,
  },
});

function deps(root, post, extra = {}) {
  return {
    post,
    lock: () => oauthlock.acquire(root, FAST),
    ...extra,
  };
}

// ---- pure shaping ------------------------------------------------------------

test('the request is a JSON refresh grant against the pinned endpoint and public client', () => {
  assert.equal(TOKEN_URL, 'https://platform.claude.com/v1/oauth/token');
  const body = buildBody(creds('rt-1'));
  assert.deepEqual(Object.keys(body).sort(), ['client_id', 'grant_type', 'refresh_token', 'scope']);
  assert.equal(body.grant_type, 'refresh_token');
  assert.equal(body.refresh_token, 'rt-1');
  assert.equal(body.client_id, CLIENT_ID);
  // No client_secret anywhere: there is not one, and inventing a field is a 400
  // that looks like an outage.
  assert.equal(body.client_secret, undefined);
});

test('the full scope set is sent, plus any scopes the record holds beyond it', () => {
  const body = buildBody(creds('rt-1', { scopes: ['user:profile', 'project:thing'] }));
  const sent = body.scope.split(' ');
  for (const s of SCOPES) assert.ok(sent.includes(s), `${s} must survive a refresh`);
  assert.ok(sent.includes('project:thing'), 'an extra grant this host holds is not dropped');
  // Stored profiles carry no clientId, which is what puts us on the CLI's
  // "send the defaults" branch. Sending only the record's own scopes would
  // shrink the grant a little on every refresh.
  assert.ok(sent.length > 2);
});

test('the fallback leg sends only the scopes the record already has', () => {
  const body = buildBody(creds('rt-1', { scopes: ['user:profile', 'user:inference'] }), { legacyScopes: true });
  assert.equal(body.scope, 'user:profile user:inference');
});

test('expiresAt is written as now + expires_in*1000, in absolute milliseconds', () => {
  const now = 1_800_000_000_000;
  const fresh = applyResponse(creds('rt-1'), okReply().json, now);
  assert.equal(fresh.expiresAt, now + 28800 * 1000);
  assert.ok(fresh.expiresAt > 1e12, 'milliseconds, not seconds: a seconds value reads as 1970');
});

test('a response that omits refresh_token keeps the one we sent', () => {
  const fresh = applyResponse(creds('rt-OLD'), { access_token: 'a', expires_in: 100 }, 0);
  assert.equal(fresh.refreshToken, 'rt-OLD', 'absence means "keep using it", not "you have none"');
});

test('fields the token endpoint does not return are carried forward, not blanked', () => {
  const rec = creds('rt-1', { rateLimitTier: 'tier-3', refreshTokenExpiresAt: 99_000 });
  const fresh = applyResponse(rec, { access_token: 'a', refresh_token: 'b', expires_in: 10 }, 0);
  assert.equal(fresh.subscriptionType, 'max');
  assert.equal(fresh.rateLimitTier, 'tier-3');
  assert.equal(fresh.refreshTokenExpiresAt, 99_000);
});

test('errors are classified into the statuses the rest of the flow branches on', () => {
  assert.equal(classifyError(400, { error: 'invalid_grant' }), 'known_dead_refresh_token');
  assert.equal(classifyError(400, '{"error":"invalid_scope"}'), 'invalid_scope');
  assert.equal(classifyError(403, { error: 'account_on_hold' }), 'account_on_hold');
  assert.equal(classifyError(429, 'Too Many Requests'), 'refresh_failed');
  assert.equal(classifyError(500, ''), 'refresh_failed', 'an unfamiliar body stays generic');
});

test('the next refresh is the 5-minute skew while the token lives, and a floor once it does not', () => {
  const now = 1_800_000_000_000;
  assert.equal(nextRefreshAt(creds('r', { expiresAt: now + HOUR }), now), now + HOUR - 5 * 60_000);
  const past = nextRefreshAt(creds('r', { expiresAt: now - HOUR }), now);
  assert.ok(past >= now + 7 * HOUR,
    'an expired profile must NOT schedule itself for now: it is permanently expired between refreshes');
});

test('freshness uses the whole vocabulary', () => {
  const now = 1_800_000_000_000;
  assert.equal(freshnessOf(creds('r', { expiresAt: now + 5 * HOUR }), now), 'fresh');
  assert.equal(freshnessOf(creds('r', { expiresAt: now + 10 * 60_000 }), now), 'expiring');
  assert.equal(freshnessOf(creds('r', { expiresAt: now - 1 }), now), 'expired');
  assert.equal(freshnessOf(creds('r', { expiresAt: now, refreshTokenExpiresAt: now - 1 }), now), 'unrefreshable');
  assert.equal(freshnessOf({ credentials: creds('r'), refresh: { deadAt: now } }, now), 'unrefreshable');
  assert.equal(freshnessOf(creds('r', { expiresAt: now - 1 }), now, { refreshing: true }), 'refreshing');
  // A permanently dead login beats an in-flight attempt: showing "refreshing" over
  // something that can never succeed is how an owner waits all day for nothing.
  assert.equal(freshnessOf({ credentials: creds('r'), refresh: { deadAt: now } }, now, { refreshing: true }),
    'unrefreshable');
});

test('the refresh-token expiry warning fires inside 48 h and not before', () => {
  const now = 1_800_000_000_000;
  assert.equal(refreshTokenWarnDue(creds('r', { refreshTokenExpiresAt: now + 24 * HOUR }), now), true);
  assert.equal(refreshTokenWarnDue(creds('r', { refreshTokenExpiresAt: now + 96 * HOUR }), now), false);
  assert.equal(refreshTokenWarnDue(creds('r', { refreshTokenExpiresAt: now - 1 }), now), false, 'already past');
  assert.equal(refreshTokenWarnDue(creds('r'), now), false, 'no expiry known: nothing to warn about');
  assert.equal(deadSince(creds('r', { refreshTokenExpiresAt: 42 })), 42);
});

// ---- the flow, against a real store ------------------------------------------

test('refreshing an inactive profile rewrites it in place and keeps the slug', async () => {
  const { store, root } = newStore();
  const slug = store.save('idle@example.com', creds('rt-OLD', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  assert.equal(slug, UUID_A);

  const post = fakePost(okReply());
  const status = await refreshWithStore(store, slug, deps(root, post));
  assert.equal(status, 'refreshed');
  assert.equal(post.calls.length, 1);

  const list = store.list();
  assert.equal(list.length, 1, 'a rotation must not fork a second profile — that bug cost 13 files once');
  assert.equal(list[0].slug, UUID_A, 'the slug survives, which is the whole point of writing by accountUuid');
  assert.equal(list[0].email, 'idle@example.com');
  const rec = store.readProfile(slug);
  assert.equal(rec.credentials.claudeAiOauth.refreshToken, 'rt-NEW');
  assert.equal(rec.credentials.claudeAiOauth.accessToken, 'at-NEW');
  assert.equal(rec.refresh.lastStatus, 'refreshed');
  assert.ok(rec.refresh.nextAt > Date.now(), 'and it schedules itself forward, not for right now');
});

test('the ACTIVE account is never posted to the token endpoint', async () => {
  const { store, credPath, root } = newStore();
  const live = creds('rt-LIVE', { expiresAt: Date.now() - HOUR });
  fs.writeFileSync(credPath, JSON.stringify(live));
  const slug = store.save('live@example.com', live, { accountUuid: UUID_A });

  const post = fakePost(okReply());
  const status = await refreshWithStore(store, slug, deps(root, post));
  assert.equal(status, 'active_skipped');
  assert.equal(post.calls.length, 0,
    'rotating the live pair leaves the CLI with a stale refresh token, and the CLI answers '
    + 'the invalid_grant that follows by blanking its own credentials');
  assert.equal(store.readProfile(slug).credentials.claudeAiOauth.refreshToken, 'rt-LIVE');
});

test('the active guard is re-checked INSIDE the lock, not only before it', async () => {
  const { store, credPath, root } = newStore();
  const rec = creds('rt-X', { expiresAt: Date.now() - HOUR });
  const slug = store.save('x@example.com', rec, { accountUuid: UUID_A });
  const post = fakePost(okReply());
  // The owner switches to this account while we are queueing for the lock.
  const lock = async () => {
    fs.writeFileSync(credPath, JSON.stringify(rec));
    return oauthlock.acquire(root, FAST);
  };
  const status = await refreshWithStore(store, slug, { post, lock });
  assert.equal(status, 'active_skipped');
  assert.equal(post.calls.length, 0);
});

test('invalid_grant marks the record dead and does NOT zero it', async () => {
  const { store, root } = newStore();
  const slug = store.save('dead@example.com', creds('rt-DEAD', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  const post = fakePost({ ok: false, status: 400, text: '{"error":"invalid_grant"}', json: { error: 'invalid_grant' } });

  const status = await refreshWithStore(store, slug, deps(root, post));
  assert.equal(status, 'known_dead_refresh_token');

  const rec = store.readProfile(slug);
  assert.ok(rec, 'the record survives: a missing row tells the owner nothing about what to sign back in as');
  assert.equal(rec.credentials.claudeAiOauth.refreshToken, 'rt-DEAD', 'tokens are left exactly as they were');
  assert.equal(rec.credentials.claudeAiOauth.accessToken, 'at-rt-DEAD');
  assert.ok(rec.refresh.deadAt > 0, 'but it is marked, so nothing retries it');
  assert.equal(store.list()[0].freshness, 'unrefreshable');

  // And a second attempt does not spend another request on it.
  const again = await refreshWithStore(store, slug, deps(root, post));
  assert.equal(again, 'known_dead_refresh_token');
  assert.equal(post.calls.length, 1, 'a known-dead refresh token is never posted twice');
});

test('a response with no refresh_token keeps the old one through the whole flow', async () => {
  const { store, root } = newStore();
  const slug = store.save('keep@example.com', creds('rt-KEEP', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  const post = fakePost({ ok: true, status: 200, json: { access_token: 'at-2', expires_in: 60, scope: SCOPES.join(' ') } });

  assert.equal(await refreshWithStore(store, slug, deps(root, post)), 'refreshed');
  const rec = store.readProfile(slug);
  assert.equal(rec.credentials.claudeAiOauth.refreshToken, 'rt-KEEP');
  assert.equal(rec.credentials.claudeAiOauth.accessToken, 'at-2');
  assert.equal(store.list().length, 1);
});

test('a profile past its refresh token expiry is skipped without a network call', async () => {
  const { store, root } = newStore();
  const slug = store.save('gone@example.com',
    creds('rt-OLD', { expiresAt: Date.now() - HOUR, refreshTokenExpiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  const post = fakePost(okReply());

  const status = await refreshWithStore(store, slug, deps(root, post));
  assert.equal(status, 'refresh_token_expired');
  assert.equal(post.calls.length, 0, 'the CLI posts anyway and eats an invalid_grant; we check first');
  assert.equal(store.list()[0].freshness, 'unrefreshable');
});

test('a token with life left is not refreshed at all', async () => {
  const { store, root } = newStore();
  const slug = store.save('ok@example.com', creds('rt-1', { expiresAt: Date.now() + 5 * HOUR }),
    { accountUuid: UUID_A });
  const post = fakePost(okReply());
  assert.equal(await refreshWithStore(store, slug, deps(root, post)), 'not_needed');
  assert.equal(post.calls.length, 0);
  assert.ok(store.readProfile(slug).refresh.nextAt > Date.now());
});

test('a lock held by another process yields lock_busy and rotates nothing', async () => {
  const { store, root } = newStore();
  const slug = store.save('busy@example.com', creds('rt-HELD', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  const before = JSON.stringify(store.readProfile(slug).credentials);

  // Somebody — the CLI, or a second daemon — is mid-refresh right now, and is
  // heartbeating the lock to say so. That heartbeat is what makes this
  // `lock_busy` (come back later) rather than `lock_timeout` (a holder died).
  const held = oauthlock.lockPaths(root).current;
  fs.mkdirSync(held, { recursive: true });
  const beat = setInterval(() => { const n = new Date(); fs.utimesSync(held, n, n); }, 10);
  const post = fakePost(okReply());
  let status;
  try {
    status = await refreshWithStore(store, slug, deps(root, post));
  } finally { clearInterval(beat); }

  assert.equal(status, 'lock_busy');
  assert.equal(post.calls.length, 0, 'no POST may happen outside the lock');
  assert.equal(JSON.stringify(store.readProfile(slug).credentials), before,
    'the stored token pair is untouched');
  assert.equal(store.list().length, 1);
});

test('a record with no refresh token, or no inference scope, is refused before the lock', async () => {
  const { store, root } = newStore();
  const post = fakePost(okReply());

  const noScope = store.save('ns@example.com',
    creds('rt-NS', { expiresAt: Date.now() - HOUR, scopes: ['user:profile'], subscriptionType: null }));
  assert.equal(await refreshWithStore(store, noScope, deps(root, post)), 'not_refreshable');

  assert.equal(await refreshWithStore(store, 'nosuchslug', deps(root, post)), 'no_such_profile');
  assert.equal(post.calls.length, 0);
});

test('an invalid_scope rejection is retried once with the record`s own scopes', async () => {
  const { store, root } = newStore();
  const slug = store.save('sc@example.com',
    creds('rt-SC', { expiresAt: Date.now() - HOUR, scopes: ['user:profile', 'user:inference'] }),
    { accountUuid: UUID_A });
  const post = fakePost((body, n) => (n === 1
    ? { ok: false, status: 400, text: '{"error":"invalid_scope"}' }
    : okReply({ scope: body.scope })));

  assert.equal(await refreshWithStore(store, slug, deps(root, post)), 'refreshed');
  assert.equal(post.calls.length, 2);
  assert.equal(post.calls[1].scope, 'user:profile user:inference',
    'the second leg asks for exactly what the record already had');
  assert.deepEqual(store.readProfile(slug).credentials.claudeAiOauth.scopes,
    ['user:profile', 'user:inference']);
});

test('a refresh that fails transiently schedules a floor, it does not spin', async () => {
  const { store, root } = newStore();
  const slug = store.save('429@example.com', creds('rt-429', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  const post = fakePost({ ok: false, status: 429, text: 'Too Many Requests' });

  assert.equal(await refreshWithStore(store, slug, deps(root, post)), 'refresh_failed');
  const rec = store.readProfile(slug);
  assert.equal(rec.credentials.claudeAiOauth.refreshToken, 'rt-429', 'nothing rotated');
  assert.equal(rec.refresh.deadAt ?? null, null, 'a rate limit is not a dead login');
  assert.ok(rec.refresh.nextAt - Date.now() > 7 * HOUR,
    'backing off is the whole point: this endpoint rate-limits per account');
  assert.equal(fingerprint(rec.credentials), fingerprint(creds('rt-429')));
});

test('the lock is released whatever the outcome', async () => {
  const { store, root } = newStore();
  const slug = store.save('rel@example.com', creds('rt-R', { expiresAt: Date.now() - HOUR }),
    { accountUuid: UUID_A });
  await refreshWithStore(store, slug, deps(root, fakePost({ ok: false, status: 500, text: 'boom' })));
  assert.equal(fs.existsSync(oauthlock.lockPaths(root).current), false,
    'a failed refresh must not wedge every later one for a minute');
  assert.equal(fs.existsSync(oauthlock.lockPaths(root).legacy), false);
});
