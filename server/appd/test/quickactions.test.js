'use strict';
// The quick-action rules, asserted away from HTTP: the defaults, the
// normaliser, the placeholder rules, the revision check and the substitution.
//
// Everything here is pure, so a failure means a RULE is wrong rather than a
// route being wired up badly. The route-level half — the file, /v1/status and
// PATCH /v1/quick-actions — lives in routes-quick-actions.test.js.

const { test } = require('node:test');
const assert = require('node:assert');
const q = require('../lib/quickactions');

// --------------------------------------------------------------- the defaults

test('the built-in wording is the contract text, at rev 0', () => {
  const d = q.defaults();
  // Asserted as LITERALS, not by re-deriving them: these four strings are what
  // both clients show, and the point of the whole module is that there is one
  // copy of them. A test that computed them could not catch a reworded one.
  assert.equal(d.explain, 'Explain this, briefly:\n\n{selection}');
  assert.equal(d.execute, 'Run this and show me the output:\n\n{selection}');
  assert.equal(d.askInNewChat, '{selection}\n\nWhat is going on here?');
  assert.equal(d.quote, '', 'the quote lead-in is empty by default — a bare quote block');
  assert.equal(d.rev, 0);
});

test('defaults() hands back a fresh object each time', () => {
  // The daemon spreads this into the loaded record; a shared object would let
  // one request's edit become the next request's default.
  const a = q.defaults();
  a.explain = 'mutated';
  assert.equal(q.defaults().explain, 'Explain this, briefly:\n\n{selection}');
});

// ------------------------------------------------------------ normalizeTemplate

test('normalizeTemplate drops the dangerous C0 controls but keeps the newline and the tab', () => {
  // Written as escapes, never as literal bytes: a raw ESC in a source file is
  // invisible in a diff and makes git call the file binary.
  const raw = 'a\u0007b\u001b[31mc\u007fd\n\te{selection}';
  const { value, error } = q.normalizeTemplate(raw);
  assert.equal(error, undefined);
  // BEL, ESC and DEL are gone; the newline and the tab are structure, not noise,
  // and the text reaches the pane by bracketed paste where neither is a key.
  assert.equal(value, 'ab[31mcd\n\te{selection}');
});

test('normalizeTemplate folds CRLF and a lone CR into one newline each', () => {
  assert.equal(q.normalizeTemplate('a\r\nb\rc{selection}').value, 'a\nb\nc{selection}');
  // The CRLF is one line break, not two: a Windows client editing the template
  // must not double-space it on every save.
  assert.equal(q.normalizeTemplate('a\r\n\r\nb{selection}').value, 'a\n\nb{selection}');
});

test('normalizeTemplate collapses a run of blank lines to one and trims the ends', () => {
  assert.equal(q.normalizeTemplate('  a\n\n\n\n\nb{selection}  \n').value, 'a\n\nb{selection}');
  // Two survives — the blank line between a lead-in and the selection is the
  // whole shape of the default templates.
  assert.equal(q.normalizeTemplate('a\n\nb{selection}').value, 'a\n\nb{selection}');
});

test('normalizeTemplate caps at 400 characters, measured AFTER normalising', () => {
  const at = `${'x'.repeat(389)}{selection}`;      // 389 + 11 = exactly 400
  assert.equal(q.normalizeTemplate(at).value, at, 'exactly the cap is allowed');
  const over = q.normalizeTemplate(`x${at}`);
  assert.equal(over.value, undefined);
  assert.equal(over.error, 'is too long (max 400 characters)');
  // The trim is part of the measurement: padding is not length.
  assert.equal(q.normalizeTemplate(`   ${at}   `).value, at);
});

test('normalizeTemplate refuses anything that is not text', () => {
  for (const bad of [null, undefined, 7, {}, ['a'], true]) {
    assert.equal(q.normalizeTemplate(bad).error, 'must be text', `${JSON.stringify(bad)} is not a template`);
  }
});

// -------------------------------------------------------------------- validate

test('the three wrapping templates must say where the selection goes, exactly once', () => {
  for (const f of ['explain', 'execute', 'askInNewChat']) {
    assert.deepEqual(q.validate({ [f]: 'no placeholder here' }),
      { ok: false, error: `${f} must contain {selection} exactly once` });
    assert.deepEqual(q.validate({ [f]: '{selection} and {selection}' }),
      { ok: false, error: `${f} must contain {selection} exactly once` },
      'twice is as wrong as never — the selection would be sent twice');
    assert.equal(q.validate({ [f]: `ask: {selection}` }).ok, true);
  }
});

test('the quote lead-in must NOT carry the placeholder', () => {
  assert.deepEqual(q.validate({ quote: 'from the pane: {selection}' }),
    { ok: false, error: 'quote must not contain {selection}' });
  assert.equal(q.validate({ quote: 'from the pane:' }).ok, true);
  assert.equal(q.validate({ quote: '' }).ok, true, 'empty is the default and must stay legal');
});

test('validate judges only the fields that are present, and ignores rev', () => {
  const v = q.validate({ quote: ' note: ', rev: 4, somethingElse: 'x' });
  assert.deepEqual(v, { ok: true, fields: { quote: 'note:' } });
  assert.equal('explain' in v.fields, false, 'an absent field is not judged and not written');
  assert.equal(q.validate(null).ok, false);
  assert.equal(q.validate(['explain']).ok, false, 'an array is not a patch');
});

// ----------------------------------------------------------------------- merge

test('merge bumps rev, stamps updatedAt, and leaves the untouched fields alone', () => {
  const before = q.defaults();
  const r = q.merge(before, { explain: 'Why: {selection}' }, 1_757_900_000);
  assert.equal(r.ok, true);
  assert.equal(r.changed, true);
  assert.equal(r.record.rev, 1);
  assert.equal(r.record.updatedAt, 1_757_900_000);
  assert.equal(r.record.explain, 'Why: {selection}');
  assert.equal(r.record.execute, before.execute, 'a one-field PATCH is not a whole-object PUT');
  assert.equal(r.record.askInNewChat, before.askInNewChat);
  assert.equal(before.rev, 0, 'the record passed in is not mutated');
});

test('a PATCH that changes nothing does not move the rev', () => {
  // A no-op Save that bumped the revision would invalidate the OTHER client's
  // rev for nothing, so the concurrency check would generate the very conflicts
  // it exists to catch.
  const cur = q.merge(q.defaults(), { quote: 'from the pane:' }, 10).record;
  const again = q.merge(cur, { quote: 'from the pane:' }, 99);
  assert.equal(again.ok, true);
  assert.equal(again.changed, false);
  assert.equal(again.record.rev, cur.rev);
  assert.equal(again.record.updatedAt, 10, 'and does not restamp the file either');
  // An empty body is the same story.
  assert.equal(q.merge(cur, {}, 99).record.rev, cur.rev);
});

test('a stale rev is a 409, a matching one is fine, and an absent one is allowed', () => {
  const cur = { ...q.defaults(), rev: 3 };
  assert.deepEqual(q.merge(cur, { rev: 2, quote: 'x' }, 1),
    { ok: false, status: 409, error: 'quick actions changed underneath you' });
  assert.equal(q.merge(cur, { rev: 4, quote: 'x' }, 1).status, 409, 'a rev from the FUTURE is not ours either');
  assert.equal(q.merge(cur, { rev: '3', quote: 'x' }, 1).status, 409, 'a string rev is not a revision');
  assert.equal(q.merge(cur, { rev: 3, quote: 'x' }, 1).record.rev, 4);
  assert.equal(q.merge(cur, { quote: 'x' }, 1).record.rev, 4, 'curl and the CLI have no rev to send');
});

test('merge refuses a bad field with the 400 status and never half-writes', () => {
  const cur = q.merge(q.defaults(), { quote: 'keep me' }, 10).record;
  const r = q.merge(cur, { quote: 'keep me too', explain: 'no placeholder' }, 20);
  assert.deepEqual(r, { ok: false, status: 400, error: 'explain must contain {selection} exactly once' });
  assert.equal(cur.quote, 'keep me', 'the valid half of a refused patch is not applied');
});

// -------------------------------------------------------- view / applyTemplate

test('the wire view is the five fields the clients decode, and not updatedAt', () => {
  const rec = { ...q.defaults(), rev: 2, updatedAt: 1_757_900_000 };
  assert.deepEqual(Object.keys(q.view(rec)).sort(),
    ['askInNewChat', 'execute', 'explain', 'quote', 'rev']);
  assert.equal(q.view(rec).rev, 2);
});

test('applyTemplate substitutes once, literally, even when the selection looks like a replacement pattern', () => {
  // `$&` is in every sed one-liner anyone would highlight and ask about;
  // String.replace would expand it into a second copy of the template.
  const sel = "sed -i 's/x/$& $1/' f";
  assert.equal(q.applyTemplate('Explain this:\n\n{selection}', sel), `Explain this:\n\n${sel}`);
  assert.equal(q.applyTemplate('{selection} then {selection}', 'S'), 'S then {selection}',
    'only the first — a two-placeholder template cannot be stored anyway');
  assert.equal(q.applyTemplate('no placeholder', 'S'), 'no placeholder');
  assert.equal(q.applyTemplate(null, 'S'), '');
});
