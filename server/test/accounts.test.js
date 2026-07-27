'use strict';
// The credentials shape is the real one from ~/.claude/.credentials.json
// (secrets replaced). These tests exist because the first version of this store
// LOST an account within minutes of shipping: profiles were keyed by the email
// that `claude auth status` reported while the credentials came from a separate
// file read, so any skew between the two wrote one login's secrets under
// another's name and overwrote it.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { AccountStore, fingerprint, sameAccount } = require('../lib/accounts');

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

// ---- identity: the property whose absence lost an account -------------------

test('a profile is keyed by its credentials, not by the email it is labelled with', () => {
  const { store } = newStore();
  // The same login labelled two different ways is ONE account, updated in place.
  store.save('right@example.com', creds('r1'));
  store.save('wrong@example.com', creds('r1'));
  const list = store.list();
  assert.strictEqual(list.length, 1);
  assert.strictEqual(list[0].email, 'wrong@example.com', 'the latest label wins');
});

test('mislabelling credentials cannot overwrite a different account', () => {
  // This is the exact failure that happened: `auth status` reported one account
  // while the credentials file already held another's.
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  store.save('work@example.com', creds('r-home'));   // wrong label, right secrets

  const list = store.list();
  assert.strictEqual(list.length, 2, 'both logins must survive a wrong label');
  const prints = new Set(list.map((a) => a.slug));
  assert.strictEqual(prints.size, 2);
  assert.strictEqual(
    store.readProfile(fingerprint(creds('r-work'))).credentials.claudeAiOauth.refreshToken,
    'r-work',
    'the first account still holds its own credentials',
  );
});

test('the fingerprint is stable for a login and different across logins', () => {
  assert.strictEqual(fingerprint(creds('r1')), fingerprint(creds('r1', { accessToken: 'rotated' })));
  assert.notStrictEqual(fingerprint(creds('r1')), fingerprint(creds('r2')));
  assert.strictEqual(fingerprint({ claudeAiOauth: {} }), null);
  assert.strictEqual(fingerprint(null), null);
});

test('accounts are compared by refresh token, not by email', () => {
  assert.ok(sameAccount(creds('r1'), creds('r1')));
  assert.ok(!sameAccount(creds('r1'), creds('r2')));
  assert.ok(!sameAccount({ claudeAiOauth: {} }, { claudeAiOauth: {} }));
});

// ---- listing and switching --------------------------------------------------

test('exactly one profile is reported active', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.save('work@example.com', creds('r-work'));
  store.save('personal@example.com', creds('r-home'));
  assert.strictEqual(store.list().filter((a) => a.isActive).length, 1);
});

test('activating swaps the live credentials file', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.save('work@example.com', creds('r-work'));
  store.save('personal@example.com', creds('r-home'));

  const r = store.activate(fingerprint(creds('r-home')), 'work@example.com');
  assert.strictEqual(r.ok, true);
  assert.strictEqual(JSON.parse(fs.readFileSync(credPath, 'utf8')).claudeAiOauth.refreshToken, 'r-home');
  assert.strictEqual(store.list().find((a) => a.isActive).email, 'personal@example.com');
});

test('the outgoing account is snapshotted before a switch, even if we mislabel it', () => {
  const { store, credPath } = newStore();
  store.save('personal@example.com', creds('r-home'));
  // Live holds a token refreshed since it was last saved; losing that would
  // leave the outgoing account unusable.
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work', { accessToken: 'refreshed-since' })));

  store.activate(fingerprint(creds('r-home')), 'a-stale-or-wrong@example.com');

  const saved = store.readProfile(fingerprint(creds('r-work')));
  assert.ok(saved, 'the account being left must still be stored');
  assert.strictEqual(saved.credentials.claudeAiOauth.accessToken, 'refreshed-since');
  assert.strictEqual(store.list().length, 2, 'and it must not have clobbered the incoming one');
});

test('activating an unknown account changes nothing', () => {
  const { store, credPath } = newStore();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  assert.strictEqual(store.activate('deadbeefdeadbeef', 'x@example.com').ok, false);
  assert.strictEqual(JSON.parse(fs.readFileSync(credPath, 'utf8')).claudeAiOauth.refreshToken, 'r-work');
});

// ---- migrating off the broken layout ---------------------------------------

test('migrate re-keys an email-named profile onto its fingerprint', () => {
  const { store } = newStore();
  const rec = { email: 'work@example.com', slug: 'work-example-com', savedAt: 10, credentials: creds('r-work') };
  fs.writeFileSync(path.join(store.dir, 'work-example-com.json'), JSON.stringify(rec), { mode: 0o600 });

  const r = store.migrate();
  assert.strictEqual(r.migrated, 1);
  assert.ok(store.readProfile(fingerprint(creds('r-work'))), 'stored under its fingerprint now');
  assert.strictEqual(fs.existsSync(path.join(store.dir, 'work-example-com.json')), false);
  assert.strictEqual(store.list()[0].email, 'work@example.com', 'the label survives');
});

test('migrate collapses the duplicates the old scheme produced', () => {
  // Two names, one set of credentials: the real symptom of the lost account.
  const { store } = newStore();
  for (const [name, savedAt] of [['a-example-com', 10], ['b-example-com', 20]]) {
    fs.writeFileSync(
      path.join(store.dir, `${name}.json`),
      JSON.stringify({ email: name.replace('-example-com', '@example.com'), savedAt, credentials: creds('r-shared') }),
      { mode: 0o600 },
    );
  }
  const r = store.migrate();
  assert.strictEqual(r.duplicates, 1);
  const list = store.list();
  assert.strictEqual(list.length, 1);
  assert.strictEqual(list[0].email, 'b@example.com', 'the newer label is kept');
});

test('migrate discards a profile with no usable credentials', () => {
  const { store } = newStore();
  fs.writeFileSync(path.join(store.dir, 'junk.json'), JSON.stringify({ email: 'x', credentials: {} }));
  fs.writeFileSync(path.join(store.dir, 'broken.json'), '{not json');
  store.migrate();
  assert.deepStrictEqual(store.list(), []);
});

test('migrate is idempotent', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  assert.deepStrictEqual(store.migrate(), { migrated: 0, duplicates: 0 });
  assert.strictEqual(store.list().length, 1);
});

// ---- housekeeping ----------------------------------------------------------

test('a blank label does not erase a known one', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'));
  store.save(null, creds('r-work', { accessToken: 'refreshed' }));
  assert.strictEqual(store.list()[0].email, 'work@example.com');
});

test('credentials without an oauth block are not saved', () => {
  const { store } = newStore();
  assert.strictEqual(store.save('x@example.com', { somethingElse: true }), null);
  assert.deepStrictEqual(store.list(), []);
});

test('removing forgets an account', () => {
  const { store } = newStore();
  const slug = store.save('work@example.com', creds('r-work'));
  assert.strictEqual(store.remove(slug), true);
  assert.deepStrictEqual(store.list(), []);
  assert.strictEqual(store.remove(slug), false);
});

test('stored profiles are not world readable', () => {
  const { store } = newStore();
  const slug = store.save('work@example.com', creds('r-work'));
  const mode = fs.statSync(path.join(store.dir, `${slug}.json`)).mode & 0o777;
  assert.strictEqual(mode, 0o600, 'these are credentials');
});
