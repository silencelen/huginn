'use strict';
// The credentials shape is the real one from ~/.claude/.credentials.json
// (secrets replaced): claudeAiOauth with access/refresh tokens, expiresAt,
// scopes and subscriptionType.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, slugFor, sameAccount } = require('../lib/accounts');

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
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'acct-'));
  const credPath = path.join(root, '.credentials.json');
  return { store: new AccountStore(path.join(root, 'accounts'), credPath), credPath, root };
}

test('slugs are stable, filesystem-safe, and derived from the email', () => {
  assert.strictEqual(slugFor('redacted@example.com'), 'jacob-monahanhosting-com');
  assert.strictEqual(slugFor('A.B+tag@Example.COM'), 'a-b-tag-example-com');
  assert.strictEqual(slugFor(''), 'unknown');
  assert.strictEqual(slugFor(null), 'unknown');
  assert.ok(!slugFor('../../etc/passwd').includes('/'), 'must not escape the directory');
});

test('accounts are identified by refresh token, not by email', () => {
  // The same person can have two logins; a renamed email is still one account.
  assert.ok(sameAccount(creds('r1'), creds('r1')));
  assert.ok(!sameAccount(creds('r1'), creds('r2')));
  assert.ok(!sameAccount(null, creds('r1')));
  assert.ok(!sameAccount({ claudeAiOauth: {} }, { claudeAiOauth: {} }), 'two blanks are not the same account');
});

test('saving then listing marks the one matching the live credentials active', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.save('work@example.com', creds('r-work'));
  store.save('personal@example.com', creds('r-home'));

  const list = store.list();
  assert.strictEqual(list.length, 2);
  const active = list.filter((a) => a.isActive);
  assert.strictEqual(active.length, 1);
  assert.strictEqual(active[0].email, 'work@example.com');
  assert.strictEqual(active[0].subscriptionType, 'max');
});

test('activating swaps the live credentials file', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.save('work@example.com', creds('r-work'));
  store.save('personal@example.com', creds('r-home'));

  const r = store.activate('personal-example-com', 'work@example.com');
  assert.strictEqual(r.ok, true);
  const live = JSON.parse(fs.readFileSync(credPath, 'utf8'));
  assert.strictEqual(live.claudeAiOauth.refreshToken, 'r-home');
  assert.strictEqual(store.list().find((a) => a.isActive).email, 'personal@example.com');
});

test('the outgoing account is snapshotted before a switch, so it is never stranded', () => {
  // The live file can hold a token refreshed since it was last saved; losing
  // that would leave the old account unusable.
  const { store, credPath } = newStore();
  store.save('personal@example.com', creds('r-home'));
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work', { accessToken: 'refreshed-since' })));

  store.activate('personal-example-com', 'work@example.com');

  const saved = store.readProfile('work-example-com');
  assert.ok(saved, 'the account being left must have been saved');
  assert.strictEqual(saved.credentials.claudeAiOauth.accessToken, 'refreshed-since');
});

test('activating an unknown account changes nothing', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  const r = store.activate('does-not-exist', 'work@example.com');
  assert.strictEqual(r.ok, false);
  assert.strictEqual(JSON.parse(fs.readFileSync(credPath, 'utf8')).claudeAiOauth.refreshToken, 'r-work');
});

test('saving the same account twice updates in place rather than duplicating', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  store.save('work@example.com', creds('r-work', { accessToken: 'newer' }));
  const list = store.list();
  assert.strictEqual(list.length, 1);
  assert.strictEqual(store.readProfile('work-example-com').credentials.claudeAiOauth.accessToken, 'newer');
});

test('removing forgets an account', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  assert.strictEqual(store.remove('work-example-com'), true);
  assert.deepStrictEqual(store.list(), []);
  assert.strictEqual(store.remove('work-example-com'), false);
});

test('credentials without an oauth block are not saved', () => {
  const { store } = newStore();
  assert.strictEqual(store.save('x@example.com', { somethingElse: true }), null);
  assert.deepStrictEqual(store.list(), []);
});

test('a corrupt profile is skipped rather than breaking the list', () => {
  const { store } = newStore();
  store.save('good@example.com', creds('r1'));
  fs.writeFileSync(path.join(store.dir, 'broken.json'), '{not json');
  assert.deepStrictEqual(store.list().map((a) => a.email), ['good@example.com']);
});

test('stored profiles are not world readable', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  const mode = fs.statSync(path.join(store.dir, 'work-example-com.json')).mode & 0o777;
  assert.strictEqual(mode, 0o600, 'these are credentials');
});
