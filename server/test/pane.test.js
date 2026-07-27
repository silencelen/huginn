'use strict';
// node --test server/test/
// Fixtures are real `tmux capture-pane` output from live Claude Code panes on
// huginn (2026-07-27). Prompt detection in particular MUST be driven by real
// output: a false positive puts fake buttons in front of the user.

const { test } = require('node:test');
const assert = require('node:assert');
const { screenHash, stripAnsi, previewLines, detectPrompt } = require('../lib/pane');

const ESC = '\u001B';
const BEL = '\u0007';

test('screenHash is stable and sensitive to a single cell', () => {
  assert.strictEqual(screenHash('abc'), screenHash('abc'));
  assert.notStrictEqual(screenHash('abc'), screenHash('abd'));
  assert.notStrictEqual(screenHash(''), screenHash(' '));
});

test('stripAnsi removes CSI and OSC without eating text', () => {
  assert.strictEqual(stripAnsi(`${ESC}[38;5;231mhi${ESC}[0m`), 'hi');
  assert.strictEqual(stripAnsi(`a${ESC}]2;title${BEL}b`), 'ab');
  assert.strictEqual(stripAnsi('plain'), 'plain');
  // Unterminated sequence at the pane edge must not throw or leak.
  assert.strictEqual(stripAnsi(`text${ESC}[38;5;`), 'text');
});

test('previewLines skips composer furniture and returns newest last', () => {
  // Verbatim shape of a live pane tail.
  const lines = [
    '● Confirmed, the unreleased ext build is live.',
    '  Ran 1 shell command',
    '✻ Waiting for 1 dynamic workflow to finish',
    '────────────────────────────────────────',
    '❯ ',
    '────────────────────────────────────────',
    '  [andrev] Opus 5 · main',
    '  ⏵⏵ auto mode on (shift+tab to cycle) · ⇧↓ for agents',
    '',
  ];
  const p = previewLines(lines, 2);
  assert.deepStrictEqual(p, ['  Ran 1 shell command'.trim(), '✻ Waiting for 1 dynamic workflow to finish']);
});

test('previewLines tolerates an all-blank pane', () => {
  assert.deepStrictEqual(previewLines(['', '   ', ''], 3), []);
});

test('detectPrompt finds a real permission prompt with the selected option', () => {
  const lines = [
    'Bash command',
    '  rm -rf /tmp/scratch',
    '',
    'Do you want to proceed?',
    '❯ 1. Yes',
    '  2. Yes, and don\'t ask again for rm commands',
    '  3. No, and tell Claude what to do differently (esc)',
    '',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'expected a prompt');
  assert.strictEqual(p.question, 'Do you want to proceed?');
  assert.strictEqual(p.options.length, 3);
  assert.strictEqual(p.options[0].label, 'Yes');
  assert.strictEqual(p.options[0].selected, true);
  assert.strictEqual(p.options[2].number, 3);
});

test('detectPrompt ignores prose that merely contains numbers', () => {
  const lines = [
    'I found 3 issues:',
    'The first is a race in the poller.',
    '2026. That year keeps coming up.',
    '',
  ];
  assert.strictEqual(detectPrompt(lines), null);
});

test('detectPrompt requires options numbered from 1 and contiguous', () => {
  // A list starting at 2 is not a prompt (the real one always starts at 1).
  assert.strictEqual(detectPrompt(['Pick:', '  2. b', '  3. c']), null);
  // A gap means it is not the option run either.
  assert.strictEqual(detectPrompt(['Pick:', '  1. a', '  3. c']), null);
});

test('detectPrompt needs at least two options', () => {
  assert.strictEqual(detectPrompt(['Continue?', '❯ 1. Yes']), null);
});

test('detectPrompt ignores an answered prompt scrolled far up the pane', () => {
  const stale = ['Do you want to proceed?', '❯ 1. Yes', '  2. No'];
  const filler = new Array(20).fill('  some later output line');
  assert.strictEqual(detectPrompt([...stale, ...filler]), null);
});

test('detectPrompt reads through ANSI colouring', () => {
  const lines = [
    `${ESC}[1mDo you want to make this edit?${ESC}[0m`,
    `${ESC}[36m❯ 1.${ESC}[0m Yes`,
    `  ${ESC}[36m2.${ESC}[0m No`,
  ];
  const p = detectPrompt(lines);
  assert.ok(p);
  assert.strictEqual(p.question, 'Do you want to make this edit?');
  assert.strictEqual(p.options.length, 2);
  assert.strictEqual(p.options[0].label, 'Yes');
});

test('detectPrompt REJECTS an assistant answer ending in a numbered list', () => {
  // Regression: this matched every "strict" rule in 2.0.0 and produced fake
  // buttons; tapping one types a digit into Claude's composer.
  const lines = [
    '● Here is what I would do next:',
    '  1. Rotate the B2 keys',
    '  2. Re-run the audit',
    '  3. Push the mirror',
    '',
    '────────────────────────────────────────',
    '❯ ',
    '────────────────────────────────────────',
    '  [andrev] Opus 5 · main',
    '  ⏵⏵ auto mode on (shift+tab to cycle)',
  ];
  assert.strictEqual(detectPrompt(lines), null);
});

test('detectPrompt REJECTS a numbered list with no selection caret', () => {
  assert.strictEqual(detectPrompt(['Options:', '  1. a', '  2. b']), null);
});

test('detectPrompt REJECTS a run with two carets (not a live single selection)', () => {
  assert.strictEqual(detectPrompt(['Pick:', '❯ 1. a', '❯ 2. b']), null);
});

test('detectPrompt accepts the real captured prompt verbatim', () => {
  // Verbatim from a live permission prompt on huginn, 2026-07-27.
  const lines = [
    ' Create file',
    ' …/scratchpad/permtest.txt',
    '   1 ok',
    ' Do you want to create permtest.txt?',
    ' ❯ 1. Yes',
    '   2. Yes, allow all edits in scratchpad/ during this session (shift+tab)',
    '   3. No',
    ' Esc to cancel · Tab to amend',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'the real prompt must still be detected');
  assert.strictEqual(p.question, 'Do you want to create permtest.txt?');
  assert.strictEqual(p.options.length, 3);
  assert.strictEqual(p.options[0].selected, true);
});
