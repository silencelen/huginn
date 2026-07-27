'use strict';
// node --test server/test/
// Fixtures are real `tmux capture-pane` output from live Claude Code panes on
// huginn (2026-07-27). Prompt detection in particular MUST be driven by real
// output: a false positive puts fake buttons in front of the user.

const { test } = require('node:test');
const assert = require('node:assert');
const { screenHash, stripAnsi, previewLines, detectPrompt, extractLoginUrl, parseStatusLine, loginPaneState } = require('../lib/pane');

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

test('extractLoginUrl reads the whole URL from the OSC 8 target', () => {
  // Verbatim shape from a live `claude auth login` pane: the visible label is
  // hard-wrapped at the pane width, but the hyperlink target is intact.
  const url = 'https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e' +
    '&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback' +
    '&scope=org%3Acreate_api_key&code_challenge=PpVpb9rKFa&code_challenge_method=S256&state=rJSMX8ykeC0';
  const lines = [
    'Opening browser to sign in…',
    `If the browser didn't open, visit: ${ESC}[94m${ESC}]8;;${url}${BEL}https://claude.com/cai/oauth/authorize?code=t`,
    'rue&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&response_type=code&redirect_u',
    `ri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback${ESC}[39m`,
    'Paste code here if prompted >',
  ];
  assert.strictEqual(extractLoginUrl(lines), url);
});

test('extractLoginUrl rejoins a wrapped URL when there is no hyperlink', () => {
  const lines = [
    "If the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=t",
    'rue&client_id=abc&response_type=code',
    '',
    'Paste code here if prompted >',
  ];
  assert.strictEqual(
    extractLoginUrl(lines),
    'https://claude.com/cai/oauth/authorize?code=true&client_id=abc&response_type=code',
  );
});

test('extractLoginUrl returns null on a pane with no URL', () => {
  assert.strictEqual(extractLoginUrl(['just a shell prompt $ ', '']), null);
});

test('parseStatusLine reads the live model, branch and mode', () => {
  // Verbatim status lines from live panes.
  const lines = [
    '\u25cf some output',
    '\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
    '\u276f ',
    '  [andrev] Opus 5 \u00b7 main \u00b7 \u26a0 3 sessions in this tree',
    '  \u23f5\u23f5 auto mode on (shift+tab to cycle) \u00b7 \u2190 for agents',
  ];
  const st = parseStatusLine(lines);
  assert.strictEqual(st.model, 'Opus 5');
  assert.strictEqual(st.branch, 'main');
  assert.strictEqual(st.mode, 'auto');
});

test('parseStatusLine reads manual mode and a model with no extras', () => {
  const st = parseStatusLine([
    '  [promptprobe] Fable 5 \u00b7 main',
    '  \u23f8 manual mode on \u00b7 \u2190 for agents',
  ]);
  assert.strictEqual(st.model, 'Fable 5');
  assert.strictEqual(st.mode, 'manual');
});

test('parseStatusLine returns nulls rather than guessing on an unrelated pane', () => {
  const st = parseStatusLine(['$ ls -la', 'total 4', '']);
  assert.strictEqual(st.model, null);
  assert.strictEqual(st.mode, null);
});

test('loginPaneState reports waiting for a code', () => {
  const st = loginPaneState([
    'Opening browser to sign in…',
    "If the browser didn't open, visit: https://claude.com/cai/oauth/authorize?x=1",
    'Paste code here if prompted >',
  ]);
  assert.strictEqual(st.awaitingCode, true);
  assert.strictEqual(st.done, false);
  assert.strictEqual(st.failed, false);
});

test('loginPaneState quotes the line that explains a failure, not a wrap fragment', () => {
  // A pane wraps, so the LAST line is frequently a fragment ("opied.") which is
  // useless to somebody asking why sign-in failed.
  const st = loginPaneState([
    'Paste code here if prompted > deadbeef',
    'The code you entered is invalid or has expired.',
    'Make sure the whole code was c',
    'opied.',
  ]);
  assert.strictEqual(st.failed, true);
  assert.strictEqual(st.message, 'The code you entered is invalid or has expired.');
});

test('loginPaneState reports success', () => {
  const st = loginPaneState(['Login successful. You are logged in as someone@example.com']);
  assert.strictEqual(st.done, true);
  assert.strictEqual(st.failed, false);
});

test('loginPaneState on an empty pane says nothing rather than guessing', () => {
  const st = loginPaneState(['', '   ']);
  assert.strictEqual(st.awaitingCode, false);
  assert.strictEqual(st.done, false);
  assert.strictEqual(st.message, null);
});
