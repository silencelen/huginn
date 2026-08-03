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
const { AccountStore, fingerprint, sameAccount, normUuid, storedUuid } = require('../lib/accounts');

const UUID_A = '79c777a4-d96e-4de2-b95e-bd1f1e758236';
const UUID_B = 'e12d3fa9-fb80-4e4a-b286-36890d487fd4';

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

// ---- identity that survives a rotating refresh token ------------------------
//
// The second way this store lost track of accounts (2026-08-03). Keying on the
// credentials was safe but not stable: OAuth refresh tokens rotate every few
// hours, so one login was filed afresh several times a day — 13 profiles for 3
// real accounts, twelve holding tokens that no longer authenticated. The account
// uuid is the fix precisely because it is the one identifier that does not move.

test('a rotated refresh token updates the account in place, it does not add one', () => {
  const { store } = newStore();
  const id = { accountUuid: UUID_A };
  const first = store.save('work@example.com', creds('r-1'), id);
  const second = store.save('work@example.com', creds('r-2'), id);   // Claude Code refreshed

  assert.strictEqual(first, second, 'the slug must not move when the token does');
  const list = store.list();
  assert.strictEqual(list.length, 1, 'one login is one profile, however often it rotates');
  assert.strictEqual(
    store.readProfile(first).credentials.claudeAiOauth.refreshToken, 'r-2',
    'and it holds the CURRENT token, not the one that has been traded in',
  );
});

test('rotation across many refreshes still leaves exactly one profile', () => {
  // The shape of the live bug: a week of refreshes, one account.
  const { store } = newStore();
  for (let i = 0; i < 20; i++) store.save('work@example.com', creds(`r-${i}`), { accountUuid: UUID_A });
  assert.strictEqual(store.list().length, 1);
  assert.strictEqual(store.list()[0].accountUuid, UUID_A);
});

test('two different accounts never merge, however they are labelled', () => {
  // The catastrophic direction. A wrong label must stay a wrong label.
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'), { accountUuid: UUID_A });
  store.save('work@example.com', creds('r-home'), { accountUuid: UUID_B });   // same label, other account

  const list = store.list();
  assert.strictEqual(list.length, 2, 'both logins must survive');
  assert.deepStrictEqual(new Set(list.map((a) => a.accountUuid)), new Set([UUID_A, UUID_B]));
  assert.strictEqual(store.readProfile(UUID_A).credentials.claudeAiOauth.refreshToken, 'r-work');
  assert.strictEqual(store.readProfile(UUID_B).credentials.claudeAiOauth.refreshToken, 'r-home');
});

test('an account that cannot be identified is KEPT, never dropped', () => {
  // No network, or an access token too old to ask with: the uuid is unknown and
  // the fingerprint is all there is. A surplus profile is a nuisance; a missing
  // one is a login the owner has to go and find again.
  const { store } = newStore();
  const offline = store.save('mystery@example.com', creds('r-unknown'));
  assert.strictEqual(offline, fingerprint(creds('r-unknown')), 'falls back to the credentials');

  store.save('work@example.com', creds('r-work'), { accountUuid: UUID_A });
  store.consolidate();

  assert.ok(store.readProfile(offline), 'the unidentified account is still here');
  assert.strictEqual(store.list().length, 2);
});

test('learning the uuid later adopts the profile instead of duplicating it', () => {
  // Saved while offline, identified once the API is reachable again.
  const { store } = newStore();
  const blind = store.save('work@example.com', creds('r-1'));
  const known = store.save('work@example.com', creds('r-1'), { accountUuid: UUID_A });

  assert.strictEqual(known, UUID_A);
  assert.strictEqual(store.list().length, 1, 'the fingerprint-named copy must not linger');
  assert.strictEqual(fs.existsSync(path.join(store.dir, `${blind}.json`)), false);
});

test('a uuid learned once is remembered when a later save cannot resolve it', () => {
  const { store } = newStore();
  store.save('work@example.com', creds('r-1'), { accountUuid: UUID_A });
  // Same token, no identity to hand: the stored record still recognises it.
  const again = store.save('work@example.com', creds('r-1'));
  assert.strictEqual(again, UUID_A);
  assert.strictEqual(store.list().length, 1);
});

test('a malformed uuid is not identity', () => {
  assert.strictEqual(normUuid('not-a-uuid'), null);
  assert.strictEqual(normUuid(''), null);
  assert.strictEqual(normUuid(null), null);
  assert.strictEqual(normUuid(UUID_A.toUpperCase()), UUID_A, 'case is not meaning');
  const { store } = newStore();
  const slug = store.save('work@example.com', creds('r-1'), { accountUuid: 'nonsense' });
  assert.strictEqual(slug, fingerprint(creds('r-1')), 'falls back rather than trusting it');
});

// ---- consolidating what rotation already left behind ------------------------

/** A record as the old scheme wrote it: fingerprint-named, uuid only in the block. */
function rotated(store, refresh, email, uuid, savedAt, expiresAt) {
  const rec = {
    slug: fingerprint(creds(refresh)),
    email,
    savedAt,
    firstSeen: savedAt,
    oauthAccount: { accountUuid: uuid, emailAddress: email },
    credentials: creds(refresh, { expiresAt }),
  };
  fs.writeFileSync(path.join(store.dir, `${rec.slug}.json`), JSON.stringify(rec), { mode: 0o600 });
  return rec.slug;
}

test('consolidate folds a rotation history down to one profile per login', () => {
  const { store } = newStore();
  rotated(store, 'r-1', 'work@example.com', UUID_A, 100, 1000);
  rotated(store, 'r-2', 'work@example.com', UUID_A, 200, 2000);
  const newest = rotated(store, 'r-3', 'work@example.com', UUID_A, 300, 3000);
  rotated(store, 'p-1', 'other@example.com', UUID_B, 150, 1500);

  const r = store.consolidate();
  assert.strictEqual(r.merged, 2, 'two logins, both re-keyed');
  // Every old file is moved aside, including the one whose credentials survived
  // into the new record — uniform, and one less special case to get wrong.
  assert.strictEqual(r.archived, 4);

  const list = store.list();
  assert.strictEqual(list.length, 2);
  assert.deepStrictEqual(new Set(list.map((a) => a.slug)), new Set([UUID_A, UUID_B]));
  assert.strictEqual(
    store.readProfile(UUID_A).credentials.claudeAiOauth.refreshToken, 'r-3',
    'the FRESHEST credentials survive — the others no longer authenticate',
  );
  assert.strictEqual(store.readProfile(UUID_A).firstSeen, 100, 'and the login keeps its history');
  assert.ok(newest, 'sanity');
});

test('consolidate archives what it folds in, so nothing is unrecoverable', () => {
  const { store } = newStore();
  rotated(store, 'r-1', 'work@example.com', UUID_A, 100, 1000);
  rotated(store, 'r-2', 'work@example.com', UUID_A, 200, 2000);
  store.consolidate();

  const kept = fs.readdirSync(path.join(store.dir, 'superseded')).sort();
  assert.strictEqual(kept.length, 2, 'the old profiles are moved aside, not deleted');
  const tokens = kept.map((f) =>
    JSON.parse(fs.readFileSync(path.join(store.dir, 'superseded', f), 'utf8'))
      .credentials.claudeAiOauth.refreshToken);
  assert.deepStrictEqual(new Set(tokens), new Set(['r-1', 'r-2']),
    'a wrong grouping would still be handed back in full');
});

test('consolidate is idempotent', () => {
  const { store } = newStore();
  rotated(store, 'r-1', 'work@example.com', UUID_A, 100, 1000);
  rotated(store, 'r-2', 'work@example.com', UUID_A, 200, 2000);

  const first = store.consolidate();
  assert.strictEqual(first.merged, 1);
  const after = JSON.stringify(store.list());

  const second = store.consolidate();
  assert.deepStrictEqual(second, { groups: 0, merged: 0, archived: 0, failed: 0 },
    'a settled store gives it nothing to do');
  assert.strictEqual(JSON.stringify(store.list()), after);
  assert.strictEqual(fs.readdirSync(path.join(store.dir, 'superseded')).length, 2, 'and it does not re-archive');
});

test('consolidate leaves a group alone when its members disagree about who they are', () => {
  // The skew guard. If the identity block and the label ever came apart, the
  // records carrying that uuid are not evidence of anything, and merging them
  // would fold one account's tokens into another's profile.
  const { store } = newStore();
  rotated(store, 'r-1', 'work@example.com', UUID_A, 100, 1000);
  const odd = {
    slug: 'odd', email: 'someone-else@example.com', savedAt: 200, firstSeen: 200,
    oauthAccount: { accountUuid: UUID_A, emailAddress: 'someone-else@example.com' },
    credentials: creds('r-odd', { expiresAt: 9000 }),
  };
  fs.writeFileSync(path.join(store.dir, 'odd.json'), JSON.stringify(odd), { mode: 0o600 });

  const r = store.consolidate();
  assert.strictEqual(r.merged, 0);
  assert.strictEqual(store.list().length, 2, 'both are still here, untouched');
});

test('consolidate ignores an identity block that contradicts its own label', () => {
  // Half the guard, at the level below: a block naming a different person than
  // the record is filed under is not identity, so it cannot group anything.
  const { store } = newStore();
  const rec = {
    slug: 'x', email: 'work@example.com', savedAt: 10,
    oauthAccount: { accountUuid: UUID_A, emailAddress: 'stale@example.com' },
    credentials: creds('r-1'),
  };
  assert.strictEqual(storedUuid(rec), null);
  fs.writeFileSync(path.join(store.dir, 'x.json'), JSON.stringify(rec), { mode: 0o600 });
  assert.strictEqual(store.consolidate().merged, 0);
  assert.strictEqual(store.list().length, 1);
});

test('consolidate keeps the account that is live', () => {
  // The one outcome that would be noticed immediately: the owner signed in, and
  // afterwards is not.
  const { store, credPath } = newStore();
  rotated(store, 'r-old', 'work@example.com', UUID_A, 100, 1000);
  rotated(store, 'r-live', 'work@example.com', UUID_A, 300, 3000);
  fs.writeFileSync(credPath, JSON.stringify(creds('r-live', { expiresAt: 3000 })));

  store.consolidate();
  const active = store.list().filter((a) => a.isActive);
  assert.strictEqual(active.length, 1);
  assert.strictEqual(active[0].slug, UUID_A);
});

// ---- headroom remembered across a token expiry ------------------------------

test('a plan reading is remembered on the profile and survives rotation', () => {
  const { store } = newStore();
  const slug = store.save('work@example.com', creds('r-1'), { accountUuid: UUID_A });
  store.recordPlan(slug, { limits: [{ kind: 'weekly_all', percent: 40, resetsAt: '2026-08-09T00:00:00Z' }] });

  store.save('work@example.com', creds('r-2'), { accountUuid: UUID_A });   // refreshed
  const rec = store.readProfile(UUID_A);
  assert.strictEqual(rec.lastPlan.limits[0].percent, 40,
    'the only headroom figure available once the stored token expires');
});

test('recording a plan for an unknown account is a no-op, not a new file', () => {
  const { store } = newStore();
  assert.strictEqual(store.recordPlan('nobody', { limits: [] }), false);
  assert.deepStrictEqual(store.list(), []);
});

// ---- identity, which lives apart from the credentials ----------------------

function newStoreWithConfig() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'acct-'));
  const credPath = path.join(root, '.credentials.json');
  const cfgPath = path.join(root, '.claude.json');
  fs.writeFileSync(cfgPath, JSON.stringify({ unrelated: 'state', projects: { a: 1 } }));
  return {
    store: new AccountStore(path.join(root, 'accounts'), credPath, cfgPath),
    credPath, cfgPath,
  };
}

function idBlock(email) {
  return { emailAddress: email, accountUuid: `uuid-${email}`, organizationName: `${email}'s Organization` };
}

test('the identity block is captured only for the credentials actually in use', () => {
  // It describes whichever login is live, so attaching it to some other
  // account's profile would install the wrong identity on a later switch.
  const { store, credPath, cfgPath } = newStoreWithConfig();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  fs.writeFileSync(cfgPath, JSON.stringify({ oauthAccount: idBlock('work@example.com') }));

  store.save('work@example.com', creds('r-work'));
  store.save('personal@example.com', creds('r-home'));

  assert.strictEqual(store.readProfile(fingerprint(creds('r-work'))).oauthAccount.emailAddress, 'work@example.com');
  assert.strictEqual(store.readProfile(fingerprint(creds('r-home'))).oauthAccount, null,
    'a profile that is not live must not borrow the live identity');
});

test('activating installs that account identity, not the previous one', () => {
  const { store, credPath, cfgPath } = newStoreWithConfig();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  fs.writeFileSync(cfgPath, JSON.stringify({ oauthAccount: idBlock('work@example.com') }));
  store.save('work@example.com', creds('r-work'));

  // Give the other profile an identity as though it had been live before.
  fs.writeFileSync(credPath, JSON.stringify(creds('r-home')));
  fs.writeFileSync(cfgPath, JSON.stringify({ oauthAccount: idBlock('personal@example.com') }));
  store.save('personal@example.com', creds('r-home'));

  const r = store.activate(fingerprint(creds('r-work')), 'personal@example.com');
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.identityRestored, true);
  const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  assert.strictEqual(cfg.oauthAccount.emailAddress, 'work@example.com');
});

test('with no stored identity the stale one is REMOVED, never left in place', () => {
  // Leaving it would make the CLI keep naming the previous account while holding
  // another account's tokens — which is exactly what happened before this.
  const { store, credPath, cfgPath } = newStoreWithConfig();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  fs.writeFileSync(cfgPath, JSON.stringify({ oauthAccount: idBlock('work@example.com'), keepMe: true }));
  store.save('personal@example.com', creds('r-home'));   // saved while NOT live: no identity

  const r = store.activate(fingerprint(creds('r-home')), 'work@example.com');
  assert.strictEqual(r.identityRestored, false);
  const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  assert.strictEqual('oauthAccount' in cfg, false, 'the wrong identity must be gone');
  assert.strictEqual(cfg.keepMe, true, 'and the rest of that file untouched');
});

test('patching the identity preserves the rest of a large config file', () => {
  const { store, credPath, cfgPath } = newStoreWithConfig();
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.writeOauthAccount(idBlock('someone@example.com'));
  const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  assert.strictEqual(cfg.unrelated, 'state');
  assert.deepStrictEqual(cfg.projects, { a: 1 });
  assert.strictEqual(cfg.oauthAccount.emailAddress, 'someone@example.com');
});

test('a store with no config path still switches credentials', () => {
  // Identity handling is additive; its absence must not break the swap.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'acct-'));
  const credPath = path.join(root, '.credentials.json');
  const store = new AccountStore(path.join(root, 'accounts'), credPath);
  fs.writeFileSync(credPath, JSON.stringify(creds('r-work')));
  store.save('personal@example.com', creds('r-home'));
  assert.strictEqual(store.activate(fingerprint(creds('r-home')), null).ok, true);
  assert.strictEqual(JSON.parse(fs.readFileSync(credPath, 'utf8')).claudeAiOauth.refreshToken, 'r-home');
});

test('migrate leaves a uuid-keyed store completely alone', () => {
  // It deleted a live account once (2026-08-03): the cleanup pass assumed the
  // record it had just saved was named after the fingerprint, so on a store that
  // had moved to uuid names it removed the file it had only just written. The
  // account came back from the archive; the property is now pinned here.
  const { store } = newStore();
  store.save('work@example.com', creds('r-work'), { accountUuid: UUID_A });
  store.save('other@example.com', creds('r-other'), { accountUuid: UUID_B });
  store.save('mystery@example.com', creds('r-unknown'));
  const before = JSON.stringify(store.list());

  assert.deepStrictEqual(store.migrate(), { migrated: 0, duplicates: 0 });
  assert.strictEqual(store.list().length, 3, 'every account must still be here');
  assert.strictEqual(JSON.stringify(store.list()), before);
});

test('migrate and consolidate can run in either order without losing an account', () => {
  // Both run at every boot, back to back, over and over.
  const { store } = newStore();
  rotated(store, 'r-1', 'work@example.com', UUID_A, 100, 1000);
  rotated(store, 'r-2', 'work@example.com', UUID_A, 200, 2000);
  rotated(store, 'p-1', 'other@example.com', UUID_B, 150, 1500);
  store.save('mystery@example.com', creds('r-unknown'));

  for (let boot = 0; boot < 3; boot++) {
    store.migrate();
    store.consolidate();
    const list = store.list();
    assert.strictEqual(list.length, 3, `boot ${boot}: three logins, three profiles`);
    assert.deepStrictEqual(
      new Set(list.map((a) => a.email)),
      new Set(['work@example.com', 'other@example.com', 'mystery@example.com']),
    );
  }
});

test('save never clears a record that names a different account', () => {
  // The cleanup pass exists to remove stale copies of the login being written.
  // If anything ever put a record naming somebody ELSE into that set, removing
  // it would be the one mistake with no way back — so it is refused outright.
  const { store } = newStore();
  const other = store.save('other@example.com', creds('r-shared'), { accountUuid: UUID_B });
  // Contrive the collision: the same refresh token, claimed by another account.
  store.save('work@example.com', creds('r-shared'), { accountUuid: UUID_A });

  assert.ok(store.readProfile(other), 'the other account is untouched');
  assert.strictEqual(store.list().length, 2);
});

test('a superseded token pair is archived by save, not deleted', () => {
  const { store } = newStore();
  // A stray left by an offline save, later identified as an existing login.
  const stray = store.save('work@example.com', creds('r-old'));
  store.save('work@example.com', creds('r-old'), { accountUuid: UUID_A });   // adopts it
  store.save('work@example.com', creds('r-new'), { accountUuid: UUID_A });   // rotates

  assert.strictEqual(store.list().length, 1);
  assert.strictEqual(store.readProfile(UUID_A).credentials.claudeAiOauth.refreshToken, 'r-new');
  assert.strictEqual(fs.existsSync(path.join(store.dir, `${stray}.json`)), false);
});
