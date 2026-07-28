'use strict';
// Ids here are real ones found in the installed CLI (2026-07-27), including the
// ones that make "Opus" alone ambiguous: claude-opus-5 and claude-opus-4-8 both
// exist and are both selectable.

const { test } = require('node:test');
const assert = require('node:assert');
const { parseModelId, formatModel, selectModels, aliasModels, compareVersions } = require('../lib/models');

test('an id keeps its version when formatted', () => {
  assert.strictEqual(formatModel('claude-opus-5'), 'Opus 5');
  assert.strictEqual(formatModel('claude-opus-4-8'), 'Opus 4.8');
  assert.strictEqual(formatModel('claude-fable-5'), 'Fable 5');
  assert.strictEqual(formatModel('claude-sonnet-4-6'), 'Sonnet 4.6');
});

test('a dated snapshot formats without the date', () => {
  assert.strictEqual(formatModel('claude-haiku-4-5-20251001'), 'Haiku 4.5');
});

test('a display name from the pane is passed through untouched', () => {
  // The status line already says "Opus 5"; reformatting it lost the 5 before.
  assert.strictEqual(formatModel('Opus 5'), 'Opus 5');
  assert.strictEqual(formatModel('Fable 5'), 'Fable 5');
  assert.strictEqual(formatModel('Opus 4.8'), 'Opus 4.8');
});

test('unknown or empty input does not invent a name', () => {
  assert.strictEqual(formatModel(''), null);
  assert.strictEqual(formatModel(null), null);
  assert.strictEqual(formatModel('some-other-model'), 'some-other-model');
});

test('parseModelId splits family from version and drops a release date', () => {
  assert.deepStrictEqual(parseModelId('claude-opus-4-8'), { family: 'opus', version: [4, 8], suffix: '' });
  assert.deepStrictEqual(parseModelId('claude-haiku-4-5-20251001'), { family: 'haiku', version: [4, 5], suffix: '' });
  assert.strictEqual(parseModelId('Opus 5'), null);
  assert.strictEqual(parseModelId('claude-notafamily-5'), null);
});

test('versions sort newest first, including across component counts', () => {
  const v = [[4, 6], [5], [4, 8], [4, 7]].sort(compareVersions);
  assert.deepStrictEqual(v, [[5], [4, 8], [4, 7], [4, 6]]);
});

test('selection offers the newest of each family and keeps the versions distinct', () => {
  const ids = [
    'claude-fable-5', 'claude-fable-5-mythos-5',
    'claude-opus-4', 'claude-opus-4-1', 'claude-opus-4-1-20250805', 'claude-opus-4-1-20250805-v1',
    'claude-opus-4-5', 'claude-opus-4-6', 'claude-opus-4-6-fast', 'claude-opus-4-7',
    'claude-opus-4-8', 'claude-opus-5',
    'claude-sonnet-4-6', 'claude-sonnet-5',
    'claude-haiku-4-5', 'claude-haiku-4-5-20251001',
  ];
  const picked = selectModels(ids);
  assert.deepStrictEqual(
    picked.map((m) => m.display),
    ['Fable 5', 'Opus 5', 'Opus 4.8', 'Sonnet 5', 'Sonnet 4.6', 'Haiku 4.5'],
  );
  picked.forEach((m) => {
    assert.ok(m.id.startsWith('claude-'));
    assert.ok(!/-v\d+$|-fast$|-\d{8}/.test(m.id), `${m.id} is not a plain choice`);
  });
});

test('selection ignores variants that are not a choice', () => {
  const picked = selectModels(['claude-opus-4-6-fast', 'claude-opus-4-6-v1', 'claude-opus-4-6-20251101']);
  assert.deepStrictEqual(picked, []);
});

test('a named variant does not become a duplicate entry', () => {
  // claude-fable-5-mythos-5 is a different model that would otherwise appear as
  // a second, indistinguishable "Fable 5".
  const picked = selectModels(['claude-fable-5', 'claude-fable-5-mythos-5']);
  assert.deepStrictEqual(picked.map((m) => m.id), ['claude-fable-5']);
});

test('the alias fallback is always usable', () => {
  assert.deepStrictEqual(aliasModels().map((m) => m.id), ['fable', 'opus', 'sonnet', 'haiku']);
});
