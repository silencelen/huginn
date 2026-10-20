'use strict';
// A background refresh of a saved login must change the login and nothing else.
//
// A stored profile is a copy of Claude Code's whole credentials file, and the
// login (`claudeAiOauth`) is only one key in it: the OAuth logins of the owner's
// MCP servers, their client registrations and secrets, and whatever a later CLI
// adds all sit beside it. A switch installs the stored copy over the live file,
// so a key the refresher drops is a key the owner loses the next time they
// switch to that account.
//
// SAFETY: no test in this file makes a network call. `post` is a counting fake,
// and every path is under a scratch mkdtemp.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore } = require('../lib/accounts');
const oauthlock = require('../lib/oauthlock');
const { refreshWithStore, SCOPES } = require('../lib/oauth-refresh');

const UUID_IDLE = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';
const UUID_LIVE = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const HOUR = 3600_000;
const FAST = { attempts: 2, retryMs: 5, probeMs: 30, update: 50 };

/** Everything in the file that is not the Claude login, the way the CLI keeps it. */
const BESIDE = {
  mcpOAuth: {
    'linear|9f2c': { serverName: 'linear', accessToken: 'mcp-at', refreshToken: 'mcp-rt', expiresAt: 1_900_000_000_000 },
  },
  mcpOAuthClientConfig: { 'linear|9f2c': { clientSecret: 'typed-in-once' } },
  // A key this daemon has never heard of stands for every one a later CLI adds.
  someLaterKey: { kept: true },
};

function login(refresh, over = {}) {
  return {
    accessToken: `at-${refresh}`,
    refreshToken: refresh,
    expiresAt: Date.now() - HOUR,
    scopes: ['user:profile', 'user:inference'],
    subscriptionType: 'max',
    ...over,
  };
}

function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oauthref-keep-'));
  const credPath = path.join(root, '.credentials.json');
  return { root, credPath, store: new AccountStore(path.join(root, 'accounts'), credPath) };
}

const rotate = async (body) => ({
  ok: true,
  status: 200,
  json: {
    access_token: `at-${body.refresh_token}-R`,
    refresh_token: `${body.refresh_token}-R`,
    expires_in: 28800,
    scope: SCOPES.join(' '),
  },
});

test('a background refresh rewrites the login and keeps every other key of the stored file', async () => {
  const { root, credPath, store } = scratch();
  fs.writeFileSync(credPath, JSON.stringify({ claudeAiOauth: login('rt-LIVE', { expiresAt: Date.now() + HOUR }), ...BESIDE }));
  const slug = store.save('idle@example.com', { claudeAiOauth: login('rt-IDLE'), ...BESIDE }, { accountUuid: UUID_IDLE });

  const status = await refreshWithStore(store, slug, { post: rotate, lock: () => oauthlock.acquire(root, FAST) });
  assert.equal(status, 'refreshed');

  const stored = store.readProfile(slug).credentials;
  assert.equal(stored.claudeAiOauth.refreshToken, 'rt-IDLE-R', 'the login itself was rotated');
  for (const [k, v] of Object.entries(BESIDE)) {
    assert.deepEqual(stored[k], v, `${k} must survive a refresh it has nothing to do with`);
  }
});

test('switching to a refreshed profile does not sign the MCP servers out', async () => {
  // The symptom the owner sees: every OAuth MCP server asks to be authorised
  // again after a switch, because the file installed over the live one no
  // longer carried their logins.
  const { root, credPath, store } = scratch();
  fs.writeFileSync(credPath, JSON.stringify({ claudeAiOauth: login('rt-LIVE', { expiresAt: Date.now() + HOUR }), ...BESIDE }));
  store.save('live@example.com', store.readActive(), { accountUuid: UUID_LIVE });
  const idle = store.save('idle@example.com', { claudeAiOauth: login('rt-IDLE'), ...BESIDE }, { accountUuid: UUID_IDLE });

  assert.equal(await refreshWithStore(store, idle, { post: rotate, lock: () => oauthlock.acquire(root, FAST) }), 'refreshed');
  const r = store.activate(idle, 'live@example.com', { held: true });
  assert.ok(r.ok, r.error);

  const live = JSON.parse(fs.readFileSync(credPath, 'utf8'));
  assert.equal(live.claudeAiOauth.refreshToken, 'rt-IDLE-R', 'the switch installed the refreshed login');
  assert.deepEqual(live.mcpOAuth, BESIDE.mcpOAuth, 'and the MCP logins came with it');
  assert.deepEqual(live.mcpOAuthClientConfig, BESIDE.mcpOAuthClientConfig);
  assert.deepEqual(live.someLaterKey, BESIDE.someLaterKey);
});
