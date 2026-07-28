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

// ------------------------------------------------ answering from a notification
//
// The fingerprint is what stops a lock-screen answer landing on a question that has
// since changed. These tests are about which differences must and must not matter.

const { promptFingerprint } = require('../lib/pane');

const PROMPT_A = {
  question: 'Do you want to create jtyper.md?',
  options: [
    { number: 1, label: 'Yes', selected: true },
    { number: 2, label: 'No, tell Claude what to do differently', selected: false },
  ],
};

test('the same question fingerprints the same', () => {
  assert.equal(promptFingerprint(PROMPT_A), promptFingerprint({ ...PROMPT_A }));
});

// Moving the highlight does not change what is being asked, and rejecting an answer
// because the caret had moved would be maddening and pointless.
test('moving the selection caret does not change the fingerprint', () => {
  const moved = {
    question: PROMPT_A.question,
    options: [
      { number: 1, label: 'Yes', selected: false },
      { number: 2, label: 'No, tell Claude what to do differently', selected: true },
    ],
  };
  assert.equal(promptFingerprint(moved), promptFingerprint(PROMPT_A));
});

test('a different question fingerprints differently', () => {
  const other = { ...PROMPT_A, question: 'Do you want to delete jtyper.md?' };
  assert.notEqual(promptFingerprint(other), promptFingerprint(PROMPT_A));
});

// The dangerous case: same wording, different choices. Answering "2" against the
// wrong set accepts something the owner never saw.
test('the same question with different options fingerprints differently', () => {
  const other = {
    question: PROMPT_A.question,
    options: [
      { number: 1, label: 'Yes', selected: true },
      { number: 2, label: 'Yes, and do not ask again', selected: false },
      { number: 3, label: 'No', selected: false },
    ],
  };
  assert.notEqual(promptFingerprint(other), promptFingerprint(PROMPT_A));
});

test('reordered labels are not the same question', () => {
  const other = {
    question: PROMPT_A.question,
    options: [
      { number: 1, label: 'No, tell Claude what to do differently', selected: true },
      { number: 2, label: 'Yes', selected: false },
    ],
  };
  assert.notEqual(promptFingerprint(other), promptFingerprint(PROMPT_A));
});

test('nothing to answer has no fingerprint', () => {
  assert.equal(promptFingerprint(null), null);
  assert.equal(promptFingerprint({ question: 'q', options: [] }), null);
  assert.equal(promptFingerprint({ question: 'q' }), null);
});

test('a fingerprint is short enough to travel in a notification payload', () => {
  assert.ok(promptFingerprint(PROMPT_A).length <= 12);
});


// ---------------------------------------------------------- live status line
//
// The spinner is the only moment-to-moment signal a running turn produces; the
// conversation strip is built from it. Matched by SHAPE (glyph + ellipsis word +
// optional parenthetical) because the verbs are whimsical-random by design.

const { parseSpinner } = require('../lib/pane');

test('the real captured spinner parses, keeping timing and tokens', () => {
  // Verbatim from a live pane, 2026-07-27.
  const got = parseSpinner(['\u273D Gallivanting\u2026 (3m 15s \u00B7 \u2193 10.0k tokens)']);
  assert.equal(got, 'Gallivanting\u2026 \u00B7 3m 15s \u00B7 \u2193 10.0k tokens');
});

test('keyboard hints are dropped from the status', () => {
  const got = parseSpinner(['\u2733 Simmering\u2026 (esc to interrupt \u00B7 45s)']);
  assert.equal(got, 'Simmering\u2026 \u00B7 45s', 'instructions for a terminal the reader is not at');
});

test('a bare spinner with no parenthetical still reads', () => {
  assert.equal(parseSpinner(['\u00B7 Deliberating\u2026']), 'Deliberating\u2026');
});

test('prose and lists are not a spinner', () => {
  assert.equal(parseSpinner(['All done\u2026 for now', '1. a list item']), null);
});

test('composer furniture is not a spinner', () => {
  assert.equal(parseSpinner(['  \u23F5\u23F5 auto mode on (shift+tab to cycle)', '  [x] Fable 5 \u00B7 main']), null);
});

test('the newest spinner wins when the pane holds an older one', () => {
  const got = parseSpinner(['\u273B Musing\u2026 (2s)', 'output text', '\u273D Finalising\u2026 (1m)']);
  assert.equal(got, 'Finalising\u2026 \u00B7 1m');
});

test('ansi colour on the spinner line does not defeat the match', () => {
  const e = '\u001B';
  const got = parseSpinner([e + '[38;5;174m\u273D' + e + '[39m ' + e + '[38;5;180mPondering\u2026' + e + '[39m (12s)']);
  assert.equal(got, 'Pondering\u2026 \u00B7 12s');
});


test('the workflow-wait status is a headline even without an ellipsis', () => {
  // Captured live: this is how a session waiting on a workflow looks, and the
  // ellipsis-only match made exactly this case invisible.
  const got = parseSpinner(['\u2733 Waiting for 1 dynamic workflow to finish', '\u276F go ahead']);
  assert.equal(got, 'Waiting for 1 dynamic workflow to finish');
});

const { parseStatusExtras } = require('../lib/pane');

test('workflow progress rows are lifted as the TUI shows them', () => {
  const lines = [
    '  [andrev] Opus 5 \u00B7 main',
    '  \u25EF andvari-polish-wave3  Wave 3   0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens',
    '  \u29C9  audit-board',
  ];
  assert.deepEqual(parseStatusExtras(lines), [
    'andvari-polish-wave3 \u00B7 Wave 3 \u00B7 0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens',
    'audit-board',
  ]);
});

test('the running-shells row is lifted; the tip is not', () => {
  const lines = [
    'Running 1 shell command \u00B7 2s\u2026',
    '  \u23FF  Tip: Ask Claude to create a todo list',
  ];
  assert.deepEqual(parseStatusExtras(lines), ['Running 1 shell command \u00B7 2s\u2026']);
});

test('prose produces no progress rows', () => {
  assert.deepEqual(parseStatusExtras(['plain text', '1. list', '\u276F composer']), []);
});
