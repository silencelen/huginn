'use strict';
// The warning that a saved login's refresh token is about to expire.
//
// Past that expiry only an interactive sign-in brings the login back, so the
// warning is the owner's one chance to act in time. Two ways it went quiet:
// the "already warned" mark outlived the expiry it was about (so a login the
// owner had signed back into was never warned again), and the mark was written
// even when the warning had reached nobody.
//
// The refresh timer and the warning live only in huginn-appd.js, so both are
// lifted out of the source and run against a real AccountStore with a fake
// clock and stubbed delivery channels (see lift-appd.js).
//
// SAFETY: no daemon, no network. Every path is under a scratch mkdtemp.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, fingerprint } = require('../lib/accounts');
const oauthRefresh = require('../lib/oauth-refresh');
const { lift } = require('./lift-appd');

const UUID_A = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_B = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const T0 = Date.parse('2026-10-01T00:00:00Z');

function creds(refresh, refreshTokenExpiresAt, expiresAt) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      expiresAt,
      refreshTokenExpiresAt,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
    },
  };
}

/**
 * A store holding the live login A and a saved, inactive B, plus the daemon's
 * refresh timer and warning wired to it.
 */
function rig({ bExpiry }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'expiry-warn-'));
  const credPath = path.join(root, '.credentials.json');
  const accounts = new AccountStore(path.join(root, 'accounts'), credPath);
  const live = creds('rt-A', T0 + 20 * DAY, T0 + 100 * DAY);
  fs.writeFileSync(credPath, JSON.stringify(live));
  accounts.save('a@example.com', live, { accountUuid: UUID_A });
  accounts.save('b@example.com', creds('rt-B', bExpiry, T0 + 100 * DAY), { accountUuid: UUID_B });

  const clock = { now: T0 };
  const channels = { pushSent: 0, telegramOk: true, telegram: [], push: [] };
  class FakeDate extends Date { static now() { return clock.now; } }
  const box = lift(['refreshTick', 'notifyRefreshWarning'], {
    Date: FakeDate,
    accounts,
    oauthRefresh,
    fingerprint,
    log: () => { },
    GLYPH: { needsYou: '!' },
    // Nothing here is due a refresh: every access token above outlives the test.
    refreshProfile: async () => 'not_needed',
    saveIdentified: async () => null,
    deliverPush: async (alert) => { channels.push.push(alert); return { sent: channels.pushSent, dead: 0, failed: 0 }; },
    deliverTelegram: async (text) => { channels.telegram.push(text); return channels.telegramOk; },
  }, 'let refreshBusy = false;');
  const tickAt = async (t) => { clock.now = t; await box.refreshTick(); };
  const warnings = () => channels.push.filter((a) => a.kind === 'account_refresh').length;
  return { accounts, channels, tickAt, warnings };
}

test('a login signed back into is warned again before its NEW refresh token expires', async () => {
  const E1 = T0 + 24 * HOUR;
  const { accounts, tickAt, warnings } = rig({ bExpiry: E1 });

  await tickAt(T0);
  assert.equal(warnings(), 1, 'precondition: the first expiry is announced');
  await tickAt(T0 + HOUR);
  assert.equal(warnings(), 1, 'and only once inside its window');

  // The owner does what the warning said: signs in to B again. The new pair is
  // filed under B's uuid, the way the sign-in route files it, and carries a new
  // thirty-day expiry.
  const E2 = T0 + 12 * HOUR + 30 * DAY;
  accounts.save('b@example.com', creds('rt-B2', E2, T0 + 100 * DAY), { accountUuid: UUID_B });

  await tickAt(E2 - 24 * HOUR);
  assert.equal(warnings(), 2,
    'the new expiry is a new cliff: without this warning the login lapses silently a month later');
  await tickAt(E2 - 23 * HOUR);
  assert.equal(warnings(), 2, 'and it, too, is announced once');
});

test('restating the same expiry — a rotation recomputes it — does not repeat the warning', async () => {
  const E1 = T0 + 24 * HOUR;
  const { accounts, tickAt, warnings } = rig({ bExpiry: E1 });
  await tickAt(T0);
  assert.equal(warnings(), 1);

  // A refresh hands back the same absolute expiry as "now + whole seconds",
  // which lands a little later than it was first written.
  const rec = accounts.readProfile(UUID_B);
  accounts.save('b@example.com', creds('rt-B-R', E1 + 30_000, T0 + 100 * DAY), {
    accountUuid: UUID_B, refresh: rec.refresh,
  });
  await tickAt(T0 + 2 * HOUR);
  assert.equal(warnings(), 1, 'the owner has been told about this one already');
});

test('a warning that reached nobody is not recorded as given, and is tried again', async () => {
  const { accounts, channels, tickAt } = rig({ bExpiry: T0 + 24 * HOUR });
  channels.pushSent = 0;         // no device took the push
  channels.telegramOk = false;   // and the relay failed (or was never set up)

  await tickAt(T0);
  assert.equal(channels.telegram.length, 1, 'precondition: the fallback channel was tried');
  assert.equal((accounts.readProfile(UUID_B).refresh || {}).warnedAt ?? null, null,
    'a warning nobody received must not be filed as delivered');

  await tickAt(T0 + MIN);
  assert.equal(channels.telegram.length, 1, 'not retried on the very next tick: an hour apart, not a minute');

  channels.telegramOk = true;
  await tickAt(T0 + 61 * MIN);
  assert.equal(channels.telegram.length, 2, 'tried again once the hour is up');
  assert.equal(accounts.readProfile(UUID_B).refresh.warnedAt, T0 + 61 * MIN, 'and recorded once it got through');

  await tickAt(T0 + 3 * HOUR);
  assert.equal(channels.telegram.length, 2, 'then it is done');
});

test('a push that reached a device is a delivered warning, with no Telegram copy', async () => {
  const { accounts, channels, tickAt } = rig({ bExpiry: T0 + 24 * HOUR });
  channels.pushSent = 1;
  await tickAt(T0);
  assert.equal(channels.push.length, 1);
  assert.equal(channels.telegram.length, 0);
  assert.equal(accounts.readProfile(UUID_B).refresh.warnedAt, T0);
});
