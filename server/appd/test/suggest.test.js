'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { suggestionContext, buildPrompt, parseSuggestions } = require('../lib/suggest');

test('the digest is the last exchange, assistant last', () => {
  const ctx = suggestionContext([
    { kind: 'user', text: 'ship it' },
    { kind: 'assistant', text: 'Shipped. One thing I could not verify: the Fold typing feel.' },
  ]);
  assert.match(ctx, /owner last said:\nship it/);
  assert.match(ctx, /could not verify/);
});

test('no assistant reply means nothing to react to', () => {
  assert.equal(suggestionContext([{ kind: 'user', text: 'hello' }]), null);
  assert.equal(suggestionContext([]), null);
});

test('subagent chatter never reaches the digest', () => {
  const ctx = suggestionContext([
    { kind: 'assistant', text: 'the real reply' },
    { kind: 'assistant', text: 'sidechain noise', sidechain: true },
  ]);
  assert.match(ctx, /the real reply/);
  assert.doesNotMatch(ctx, /sidechain noise/);
});

test('failed tools are mentioned: retrying is a likely next message', () => {
  const ctx = suggestionContext([
    { kind: 'tool', name: 'Bash', ok: false, result: 'boom' },
    { kind: 'assistant', text: 'that failed' },
  ]);
  assert.match(ctx, /1 tool call\(s\) failed/);
});

test('a huge tail is clipped, not shipped', () => {
  const ctx = suggestionContext([
    { kind: 'user', text: 'x'.repeat(5000) },
    { kind: 'assistant', text: 'y'.repeat(5000) },
  ]);
  assert.ok(ctx.length <= 2700);
});

test('the prompt pins the format rules', () => {
  const p = buildPrompt('CTX');
  assert.match(p, /exactly 3 replies/);
  assert.match(p, /one per line/);
  assert.match(p, /the first reply answers it/);
});

// Models decorate even when told not to; the parser undoes it.
test('numbering, bullets and quotes are stripped', () => {
  const got = parseSuggestions('1. Run the tests\n- "Ship it"\n\u2022 \u2018Check the Fold\u2019\n');
  assert.deepEqual(got, ['Run the tests', 'Ship it', 'Check the Fold']);
});

test('preamble lines and blanks are dropped', () => {
  const got = parseSuggestions('Here are 3 suggestions:\n\nYes, deploy it\nNo, wait\n');
  assert.deepEqual(got, ['Yes, deploy it', 'No, wait']);
});

test('never more than three, never duplicates, never essays', () => {
  const got = parseSuggestions(['same', 'same', 'x'.repeat(150), 'two', 'three', 'four'].join('\n'));
  assert.deepEqual(got, ['same', 'two', 'three']);
});

test('garbage in, empty out', () => {
  assert.deepEqual(parseSuggestions(''), []);
  assert.deepEqual(parseSuggestions(null), []);
});
