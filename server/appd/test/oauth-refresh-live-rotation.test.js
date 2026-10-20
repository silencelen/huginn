'use strict';
// The live login, right after Claude Code rotated it.
//
// The CLI refreshes the login it is running on by itself and writes the new
// pair straight to its credentials file. The daemon's stored copy of that login
// catches up only at its next re-sync, and until then it holds the SUPERSEDED
// refresh token. Telling the live login apart by refresh token alone therefore
// called it idle in that window, and the refresher posted the superseded token:
// `invalid_grant`, a dead mark on the login in use (so switching back to it was
// refused later), and — on a server that treats reuse of a rotated token as
// theft — the owner signed out. These tests pin that the live login is
// recognised by its account too, before the lock and inside it, and that the
// refresh timer folds the live pair in before it looks.
//
// SAFETY: no network. `post` is a counting fake that answers the way a rotating
// server answers a superseded token; every path is under a scratch mkdtemp.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, fingerprint } = require('../lib/accounts');
const oauthlock = require('../lib/oauthlock');
const oauthRefresh = require('../lib/oauth-refresh');
const { lift } = require('./lift-appd');

const { refreshWithStore, SCOPES } = oauthRefresh;
const UUID_A = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_B = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const MIN = 60_000;
const HOUR = 60 * MIN;
const FAST = { attempts: 2, retryMs: 5, probeMs: 30, update: 50 };

function creds(refresh, expiresAt) {
  return {
    claudeAiOauth: {
      accessToken: `at-${refresh}`,
      refreshToken: refresh,
      expiresAt,
      scopes: ['user:profile', 'user:inference'],
      subscriptionType: 'max',
    },
  };
}

/**
 * A scratch ~/.claude: credentials file, the CLI's ~/.claude.json, and a store.
 * `signedInAs` writes the identity block the CLI keeps for its live login.
 */
function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'live-rotation-'));
  const credPath = path.join(root, '.claude', '.credentials.json');
  fs.mkdirSync(path.dirname(credPath), { recursive: true });
  const cfgPath = path.join(root, '.claude.json');
  fs.writeFileSync(cfgPath, JSON.stringify({ unrelated: 'state' }));
  const store = new AccountStore(path.join(root, 'accounts'), credPath, cfgPath);
  const setLive = (c) => fs.writeFileSync(credPath, JSON.stringify(c));
  const signedInAs = (uuid, email) => fs.writeFileSync(cfgPath,
    JSON.stringify({ unrelated: 'state', oauthAccount: { accountUuid: uuid, emailAddress: email } }));
  return { root, credPath, store, setLive, signedInAs };
}

/** A token endpoint that has already rotated every pair it is shown. */
function supersededServer() {
  const posted = [];
  const post = async (body) => {
    posted.push(body.refresh_token);
    return { ok: false, status: 400, text: '{"error":"invalid_grant"}', json: { error: 'invalid_grant' } };
  };
  return { post, posted };
}

test('the live login is not posted between the CLI rotating it and the stored copy catching up', async () => {
  const { root, store, setLive, signedInAs } = scratch();
  // The stored copy, in step a moment ago: three minutes of access token left,
  // inside the window the CLI refreshes in.
  const v1 = creds('rt-A-1', Date.now() + 3 * MIN);
  setLive(v1);
  signedInAs(UUID_A, 'a@example.com');
  store.save('a@example.com', v1, { accountUuid: UUID_A });
  // A running `claude` refreshes: the server rotates rt-A-1 to rt-A-2 and the
  // CLI writes the new pair to its own file. The stored copy still says rt-A-1.
  setLive(creds('rt-A-2', Date.now() + 8 * HOUR));

  const { post, posted } = supersededServer();
  const status = await refreshWithStore(store, UUID_A, { post, lock: () => oauthlock.acquire(root, FAST) });
  assert.equal(status, 'active_skipped');
  assert.deepEqual(posted, [], 'the superseded refresh token of the live login must never be posted');
  assert.equal((store.readProfile(UUID_A).refresh || {}).deadAt ?? null, null,
    'and the login in use is not marked dead');
});

test('a switch and a rotation while we queue for the lock are caught inside it', async () => {
  const { root, store, setLive, signedInAs } = scratch();
  setLive(creds('rt-A-1', Date.now() + 8 * HOUR));
  signedInAs(UUID_A, 'a@example.com');
  store.save('b@example.com', creds('rt-B-1', Date.now() - HOUR), { accountUuid: UUID_B });

  // While the refresher waits for the lock, the owner switches to B and the
  // first `claude` to start rotates B straight away.
  const lock = async () => {
    setLive(creds('rt-B-2', Date.now() + 8 * HOUR));
    signedInAs(UUID_B, 'b@example.com');
    return oauthlock.acquire(root, FAST);
  };
  const { post, posted } = supersededServer();
  assert.equal(await refreshWithStore(store, UUID_B, { post, lock }), 'active_skipped');
  assert.deepEqual(posted, []);
});

test('a leftover copy of the live login with no uuid of its own is not posted either', async () => {
  // A record saved while the identity endpoint could not answer is keyed by
  // its refresh-token fingerprint. Once that token rotates it is a stale copy
  // of the live login, and only its stored identity block says whose it is.
  const { root, store, setLive, signedInAs } = scratch();
  const old = creds('rt-A-0', Date.now() - HOUR);
  setLive(old);
  signedInAs(UUID_A, 'a@example.com');
  const slug = store.save('a@example.com', old);
  assert.equal(slug, fingerprint(old), 'precondition: keyed by fingerprint, no uuid');
  assert.equal(store.readProfile(slug).oauthAccount.accountUuid, UUID_A, 'precondition: identity block captured live');
  setLive(creds('rt-A-1', Date.now() + 8 * HOUR));

  const { post, posted } = supersededServer();
  assert.equal(await refreshWithStore(store, slug, { post, lock: () => oauthlock.acquire(root, FAST) }), 'active_skipped');
  assert.deepEqual(posted, []);
});

test('an idle account is still refreshed while another one is live', async () => {
  const { root, store, setLive, signedInAs } = scratch();
  setLive(creds('rt-A-1', Date.now() + 8 * HOUR));
  signedInAs(UUID_A, 'a@example.com');
  store.save('b@example.com', creds('rt-B-1', Date.now() - HOUR), { accountUuid: UUID_B });

  const posted = [];
  const post = async (body) => {
    posted.push(body.refresh_token);
    return { ok: true, status: 200, json: { access_token: 'at-B-2', refresh_token: 'rt-B-2', expires_in: 28800, scope: SCOPES.join(' ') } };
  };
  assert.equal(await refreshWithStore(store, UUID_B, { post, lock: () => oauthlock.acquire(root, FAST) }), 'refreshed');
  assert.deepEqual(posted, ['rt-B-1']);
});

// ---- the refresh timer itself (huginn-appd.js), lifted out of the source -------

/** refreshTick against `store`, with the network-facing calls stubbed. */
function timer(store, saveIdentified) {
  const refreshed = [];
  const box = lift(['refreshTick'], {
    accounts: store,
    oauthRefresh,
    fingerprint,
    log: () => { },
    saveIdentified,
    notifyRefreshWarning: async () => true,
    refreshProfile: async (slug) => { refreshed.push(slug); return 'refreshed'; },
  }, 'let refreshBusy = false;');
  return { tick: () => box.refreshTick(), refreshed };
}

test('the refresh timer folds the rotated live pair in first, then leaves the live login alone', async () => {
  // No identity block on disk at all — the CLI rewrites it on its next start
  // after a switch removed it — so this rests on the re-sync alone.
  const { store, setLive } = scratch();
  const v1 = creds('rt-A-1', Date.now() + 3 * MIN);
  setLive(v1);
  store.save('a@example.com', v1, { accountUuid: UUID_A });
  setLive(creds('rt-A-2', Date.now() + 8 * HOUR));

  // saveIdentified files the live pair under the uuid its own token reports.
  const { tick, refreshed } = timer(store, async (label, c) => store.save(label, c, { accountUuid: UUID_A }));
  await tick();
  assert.deepEqual(refreshed, [], 'the live login is never handed to the refresher');
  assert.equal(store.readProfile(UUID_A).credentials.claudeAiOauth.refreshToken, 'rt-A-2',
    'the stored copy caught up before anything looked at it');
});

test('the refresh timer leaves the live login alone when the re-sync could not identify it', async () => {
  // Offline, or the identity endpoint down: the re-sync can only file the new
  // pair under its fingerprint, and the uuid record keeps the superseded token.
  // The CLI's identity block is what still names the account.
  const { store, setLive, signedInAs } = scratch();
  const v1 = creds('rt-A-1', Date.now() + 3 * MIN);
  setLive(v1);
  signedInAs(UUID_A, 'a@example.com');
  store.save('a@example.com', v1, { accountUuid: UUID_A });
  setLive(creds('rt-A-2', Date.now() + 8 * HOUR));

  const { tick, refreshed } = timer(store, async (label, c) => store.save(label, c));
  await tick();
  assert.deepEqual(refreshed, [], 'neither the uuid record nor the new fingerprint copy is the refresher`s');
});
