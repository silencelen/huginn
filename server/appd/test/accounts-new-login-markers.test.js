'use strict';
// What the refresher's bookkeeping may carry onto a login's NEW tokens.
//
// A saved login whose refresh token came back `invalid_grant` is marked dead,
// and the row tells the owner to sign in again. Signing in again files the new
// pair under the same account uuid — and the dead mark used to come along with
// it, so the brand-new login read `unrefreshable` from birth: refused by the
// switch gate, skipped by the refresher, answered without a request when asked
// to refresh, and marked again by every further sign-in. A dead mark is about
// one refresh token; these tests pin that it stays with that token.
//
// SAFETY: no network. `post` is a counting fake, every path a scratch mkdtemp.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, fingerprint } = require('../lib/accounts');
const oauthlock = require('../lib/oauthlock');
const { refreshWithStore, freshnessOf, SCOPES } = require('../lib/oauth-refresh');

const UUID_A = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_B = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const HOUR = 3600_000;
const DAY = 24 * HOUR;
const FAST = { attempts: 2, retryMs: 5, probeMs: 30, update: 50 };

function creds(refresh, over = {}) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      expiresAt: Date.now() + 8 * HOUR,
      refreshTokenExpiresAt: Date.now() + 30 * DAY,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
      ...over,
    },
  };
}

function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'accounts-markers-'));
  const credPath = path.join(root, '.credentials.json');
  const store = new AccountStore(path.join(root, 'accounts'), credPath);
  fs.writeFileSync(credPath, JSON.stringify(creds('rt-A')));
  store.save('a@example.com', creds('rt-A'), { accountUuid: UUID_A });
  return { root, credPath, store };
}

/** B, saved and then killed by an invalid_grant, warned about first. */
function deadB(store) {
  store.save('b@example.com', creds('rt-B-old', { expiresAt: Date.now() - DAY }), { accountUuid: UUID_B });
  store.recordRefresh(UUID_B, {
    warnedAt: Date.now() - 3 * DAY,
    lastAt: Date.now() - 2 * DAY,
    lastStatus: 'known_dead_refresh_token',
    deadAt: Date.now() - 2 * DAY,
  });
  assert.equal(store.list().find((a) => a.slug === UUID_B).freshness, 'unrefreshable', 'precondition');
}

test('signing in again gives a dead login a clean slate, not the old token`s verdict', () => {
  const { store } = scratch();
  deadB(store);

  // The sign-in route files the new pair under the token's own uuid.
  store.save('b@example.com', creds('rt-B-new'), { accountUuid: UUID_B });

  const row = store.list().find((a) => a.slug === UUID_B);
  assert.equal(row.freshness, 'fresh', 'a login that just signed in is not unrefreshable');
  assert.equal(row.refresh && row.refresh.deadAt, null, 'the dead mark stayed with the dead token');
  assert.notEqual(row.refresh && row.refresh.lastStatus, 'known_dead_refresh_token',
    'and the row does not report the old token`s last outcome as its own');
});

test('a re-signed-in login is refreshed for real once it is idle again', async () => {
  const { root, store } = scratch();
  deadB(store);
  store.save('b@example.com', creds('rt-B-new', { expiresAt: Date.now() - HOUR }), { accountUuid: UUID_B });

  const posts = [];
  const post = async (body) => {
    posts.push(body.refresh_token);
    return {
      ok: true,
      status: 200,
      json: { access_token: 'at-B-R', refresh_token: 'rt-B-R', expires_in: 28800, scope: SCOPES.join(' ') },
    };
  };
  const status = await refreshWithStore(store, UUID_B, { post, lock: () => oauthlock.acquire(root, FAST) });
  assert.equal(status, 'refreshed', 'not known_dead_refresh_token: that verdict was about rt-B-old');
  assert.deepEqual(posts, ['rt-B-new']);
});

test('the same token keeps its dead mark across a rewrite', () => {
  // The other half of the rule, and the reason the block is carried at all:
  // the identity sweep and a switch both re-save a record WITHOUT its refresh
  // block, and a dead token that lost its mark would be posted again forever.
  const { store } = scratch();
  deadB(store);
  const rec = store.readProfile(UUID_B);
  store.save('b@example.com', rec.credentials, { accountUuid: UUID_B });
  assert.ok(store.readProfile(UUID_B).refresh.deadAt, 'same refresh token, same verdict');
  assert.equal(freshnessOf(store.readProfile(UUID_B), Date.now()), 'unrefreshable');
});

test('the expiry warning`s timestamp still travels, for the warning check to judge', () => {
  // warnedAt is about the refresh token's expiry, not the token: a rotation
  // restates the same expiry, and a duplicate warning is what that would cost.
  const { store } = scratch();
  deadB(store);
  const warnedAt = store.readProfile(UUID_B).refresh.warnedAt;
  store.save('b@example.com', creds('rt-B-new'), { accountUuid: UUID_B });
  assert.equal(store.readProfile(UUID_B).refresh.warnedAt, warnedAt);
});

test('folding a rotation history together does not hand an old token`s dead mark to the newest', () => {
  // consolidate() keeps the freshest credentials of a group. When that member
  // has no bookkeeping of its own, an older member's must not stand in for it.
  const { store } = scratch();
  const dir = store.dir;
  const now = Math.floor(Date.now() / 1000);
  const write = (refresh, expiresAt, extra) => {
    const c = creds(refresh, { expiresAt });
    fs.writeFileSync(path.join(dir, `${fingerprint(c)}.json`), JSON.stringify({
      slug: fingerprint(c), email: 'b@example.com', accountUuid: null, firstSeen: now, savedAt: now,
      lastPlan: null, oauthAccount: { accountUuid: UUID_B, emailAddress: 'b@example.com' },
      credentials: c, ...extra,
    }));
  };
  write('rt-B-old', Date.now() - 5 * DAY, { refresh: { deadAt: Date.now() - 4 * DAY, lastStatus: 'known_dead_refresh_token' } });
  write('rt-B-new', Date.now() + 8 * HOUR, { refresh: null });

  store.consolidate();
  const kept = store.readProfile(UUID_B);
  assert.equal(kept.credentials.claudeAiOauth.refreshToken, 'rt-B-new', 'precondition: the newest pair was kept');
  assert.notEqual(freshnessOf(kept, Date.now()), 'unrefreshable', 'and it is not dead on arrival');
});
