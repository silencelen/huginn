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
const { multiToggleDigits } = require('../lib/pane');

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
  // \u2727, the glyph the LIVE capture actually used. This test asserted the same
  // sentence behind \u2733 and passed while the documented case returned null — the
  // rule was being proven on a glyph nobody had seen it with.
  const got = parseSpinner(['\u2727 Waiting for 1 dynamic workflow to finish', '\u276F go ahead']);
  assert.equal(got, 'Waiting for 1 dynamic workflow to finish');
  // And the rest of the cycling set, so a future edit cannot narrow the class back
  // down to whichever glyph happened to be on screen when it was written.
  for (const g of ['\u2722', '\u2726', '\u2727', '\u2733', '\u273D', '\u273B', '\u2736', '\u2738', '\u2739', '\u273A']) {
    assert.equal(parseSpinner([g + ' Waiting for 1 dynamic workflow to finish']),
      'Waiting for 1 dynamic workflow to finish', `glyph ${g.codePointAt(0).toString(16)}`);
  }
});

const { parseStatusExtras } = require('../lib/pane');

test('workflow progress rows are lifted as the TUI shows them', () => {
  const lines = [
    '  [andrev] Opus 5 \u00B7 main',
    '  \u25EF andvari-polish-wave3  Wave 3   0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens',
    '  \u29C9  audit-board',
  ];
  const px = parseStatusExtras(lines);
  assert.deepEqual(px.durable, [
    'andvari-polish-wave3 \u00B7 Wave 3 \u00B7 0/4 agents done \u00B7 7m 39s \u00B7 \u2193 562.4k tokens',
    'audit-board',
  ]);
  assert.equal(px.transient, null);
});

// Rendered naively these made the strip grow a line and lose it again on
// repeat: they turn over at tool speed, so they are TRANSIENT — one slot the
// client updates in place — never additional rows.
test('per-tool rows are transient, and the tip is nothing at all', () => {
  const px = parseStatusExtras([
    'Running 1 shell command \u00B7 2s\u2026',
    '  \u23FF  Tip: Ask Claude to create a todo list',
  ]);
  assert.deepEqual(px.durable, []);
  assert.equal(px.transient, 'Running 1 shell command \u00B7 2s\u2026');
});

test('the search row is transient too', () => {
  assert.equal(parseStatusExtras(['Searching for 1 pattern\u2026']).transient,
    'Searching for 1 pattern\u2026');
});

test('the bottom-most transient row wins, as the newest redraw', () => {
  const px = parseStatusExtras(['Running 2 shell commands \u00B7 1s\u2026', 'text', 'Searching for 1 pattern\u2026']);
  assert.equal(px.transient, 'Searching for 1 pattern\u2026');
});

test('a running-agents row is durable: it lasts the whole fan-out', () => {
  const px = parseStatusExtras(['Running 3 agents \u00B7 2m\u2026']);
  assert.deepEqual(px.durable, ['Running 3 agents \u00B7 2m\u2026']);
  assert.equal(px.transient, null);
});

test('prose produces no progress rows', () => {
  const px = parseStatusExtras(['plain text', '1. list', '\u276F composer']);
  assert.deepEqual(px.durable, []);
  assert.equal(px.transient, null);
});


test('message bullets in the scroll are not progress rows', () => {
  const px = parseStatusExtras([
    '\u25CF Two clean asks. The agent data source first',
    '\u25CF Write(huginn-app/server/lib/agents.js)',
  ]);
  assert.deepEqual(px.durable, []);
  assert.equal(px.transient, null);
});

test('rows above the pane tail are history, not status', () => {
  const lines = ['Running 1 shell command \u00B7 2s\u2026'].concat(Array(12).fill('scrolled text'));
  assert.equal(parseStatusExtras(lines).transient, null, 'that row scrolled away eleven lines ago');
});


// ------------------------------------------------ the AskUserQuestion dialog
//
// Captured verbatim from a live dialog (Claude Code v2.1.220). Three things
// about its shape defeated the old parser: description lines between options,
// a rule INSIDE the list before the built-in trailing options, and the help
// footer below the run.
const ASK_DIALOG = [
  ' \u2610 Banner color',
  'Which color should the banner be?',
  '\u276F 1. Red',
  '     A vibrant red banner',
  '  2. Blue',
  '     A vibrant blue banner',
  '  3. Green',
  '     A vibrant green banner',
  '  4. Type something.',
  '\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
  '  5. Chat about this',
  'Enter to select \u00B7 \u2191/\u2193 to navigate \u00B7 Esc to cancel',
];

test('the AskUserQuestion dialog parses into a full prompt', () => {
  const p = detectPrompt(ASK_DIALOG);
  assert.ok(p, 'this exact shape rendered as raw JSON to the user');
  assert.equal(p.question, 'Which color should the banner be?');
  assert.deepEqual(p.options.map((o) => o.label),
    ['Red', 'Blue', 'Green', 'Type something.', 'Chat about this']);
  assert.equal(p.options.filter((o) => o.selected).length, 1);
  assert.equal(p.options[0].selected, true);
});

test('the tab header is not mistaken for the question', () => {
  const p = detectPrompt(ASK_DIALOG);
  assert.doesNotMatch(p.question, /Banner color/);
});

test('a scrolled-away dialog with a composer below is still not a prompt', () => {
  const gone = ASK_DIALOG.concat([
    '\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
    '\u276F ',
    '  \u23F5\u23F5 auto mode on',
  ]);
  assert.equal(detectPrompt(gone), null);
});


test('a finished workflow row is dropped: every agent done means done', () => {
  const px = parseStatusExtras([
    '  \u25EF wave-3  Verify   4/4 agents done \u00B7 12m \u00B7 \u2193 900k tokens',
  ]);
  assert.deepEqual(px.durable, []);
});

test('a workflow with agents still out stays', () => {
  const px = parseStatusExtras([
    '  \u25EF wave-3  Verify   2/4 agents done \u00B7 12m',
  ]);
  assert.equal(px.durable.length, 1);
});

test('previews skip footer workflow rows and selector help', () => {
  const got = previewLines([
    'real content line',
    '  \u25EF dead-workflow  Wave 3   4/4 agents done',
    'Enter to select \u00B7 \u2191/\u2193 to navigate',
  ]);
  assert.deepEqual(got, ['real content line']);
});


// ------------------------------------------------ multi-select AskUserQuestion
//
// Captured live: digits TOGGLE, Right opens a review tab, Enter submits.
const MULTI_DIALOG = [
  '\u2190  \u2610 Toppings  \u2714 Submit  \u2192',
  'Which toppings do you want?',
  '\u276F 1. [ ] Cheese',
  '  Add cheese to your order',
  '  2. [\u2714] Mushrooms',
  '  Add mushrooms to your order',
  '  3. [ ] Olives',
  '  4. [ ] Type something',
  '     Submit',
  '\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
  '  5. Chat about this',
  'Enter to select \u00B7 \u2191/\u2193 to navigate \u00B7 Esc to cancel',
];

test('the multi-select dialog parses with checkbox state', () => {
  const p = detectPrompt(MULTI_DIALOG);
  assert.ok(p);
  assert.equal(p.multiSelect, true);
  assert.equal(p.question, 'Which toppings do you want?');
  assert.deepEqual(
    p.options.map((o) => [o.number, o.label, o.checked ?? null]),
    [[1, 'Cheese', false], [2, 'Mushrooms', true], [3, 'Olives', false],
     [4, 'Type something', false], [5, 'Chat about this', null]],
  );
});

test('toggling a box does not change the fingerprint', () => {
  const before = promptFingerprint(detectPrompt(MULTI_DIALOG));
  const toggled = MULTI_DIALOG.map((l) => l.replace('2. [\u2714]', '2. [ ]'));
  assert.equal(promptFingerprint(detectPrompt(toggled)), before,
    'checkbox state is state, not identity');
});

test('a single-select dialog is not multiSelect', () => {
  const p = detectPrompt(ASK_DIALOG);
  assert.equal(p.multiSelect, false);
});

test('toggle digits are the diff between current and desired', () => {
  const opts = detectPrompt(MULTI_DIALOG).options;
  // 2 is already checked and wanted: no keystroke. 3 wanted: toggle. 1 not
  // wanted and unchecked: nothing.
  assert.deepEqual(multiToggleDigits(opts, [2, 3]), ['3']);
  // Wanting nothing means UN-toggling 2.
  assert.deepEqual(multiToggleDigits(opts, []), ['2']);
  // A non-checkbox row can never be toggled.
  assert.deepEqual(multiToggleDigits(opts, [2, 5]), []);
});

test('the review tab is an ordinary confirm prompt', () => {
  const p = detectPrompt([
    'Ready to submit your answers?',
    '\u276F 1. Submit answers',
    '  2. Cancel',
  ]);
  assert.ok(p);
  assert.equal(p.multiSelect, false);
  assert.deepEqual(p.options.map((o) => o.label), ['Submit answers', 'Cancel']);
});

// Audit round 3, 2026-07-28. A '>' inside an option's TEXT used to count as the
// selection caret, so two options looked selected, the exactly-one-caret guard
// rejected the dialog, and a permission prompt for any redirecting command
// vanished: no buttons in the app, no options on the notification, no
// lock-screen answer. The caret is only ever drawn at the start of the line.

test('a redirect in an option label does not destroy the prompt', () => {
  const lines = [
    'Bash command',
    '  rm -rf /tmp/scratch',
    '',
    'Do you want to proceed?',
    '❯ 1. Yes',
    "  2. Yes, and don't ask again for echo a > b",
    '  3. No, and tell Claude what to do differently (esc)',
    '',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'prompt vanished because an option mentioned a redirect');
  assert.strictEqual(p.options.length, 3);
  assert.deepStrictEqual(p.options.map((o) => o.selected), [true, false, false]);
});

test('the caret still selects when it is the caret', () => {
  const lines = [
    'Do you want to proceed?',
    '  1. Yes',
    '❯ 2. Yes, and pipe it: a > b',
    '  3. No',
    '',
  ];
  const p = detectPrompt(lines);
  assert.ok(p);
  assert.deepStrictEqual(p.options.map((o) => o.selected), [false, true, false]);
});

test("an ASCII '>' caret at line start still counts", () => {
  const lines = ['Pick one', '> 1. Alpha', '  2. Beta', ''];
  const p = detectPrompt(lines);
  assert.ok(p);
  assert.strictEqual(p.options[0].selected, true);
});

// ---------------------------------------------------------------- footers
//
// Claude Code draws its own help lines under a selector, and it changes them.
// The detector used to require every such line to match one of four hard-coded
// phrases and returned null on anything else — so when the wording moved, plan
// approvals and edit permissions stopped being detected at all. Both clients
// render their prompt card from this function, so the owner's questions vanished
// from the conversation view on phone AND desktop and had to be answered through
// the raw tmux screen.

test('detectPrompt finds a plan approval under an unfamiliar footer', () => {
  // A VERBATIM capture from a live pane, footer and all.
  const lines = [
    '   - Create                                                                     ↓',
    '  ────────────────────────────────────────────────────────────────────────────────',
    '   Claude has written up a plan and is ready to execute. Would you like to proceed?',
    '',
    '   ❯ 1. Yes, and use auto mode',
    '     2. Yes, manually approve edits',
    '     3. Tell Claude what to change',
    '        shift+tab to approve with this feedback',
    '',
    '   ctrl+g to edit in Vim · ~/.claude/plans/create-a-file-called-fancy-cloud.md',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'a live plan approval must be detected');
  assert.strictEqual(p.question, 'Claude has written up a plan and is ready to execute. Would you like to proceed?');
  assert.strictEqual(p.options.length, 3);
  assert.strictEqual(p.options[0].selected, true);
  assert.strictEqual(p.options[1].label, 'Yes, manually approve edits');
});

// ------------------------------------------------- a numbered plan body
//
// The 2026-08-03 footer fix did not go far enough. The step-2 walk treats any
// 2+-space indent as an option's DESCRIPTION line, so it climbed past the plan
// approval's own question and started collecting the plan's numbered steps as
// options; the 1..n contiguity check then discarded the whole prompt. Asking for
// a plan and getting numbered steps is the DEFAULT shape, so this was most plan
// approvals — and it failed exactly like the bug before it: no card on the
// phone, none on the desktop, no options in the notification.
//
// Permission dialogs escaped only by accident. The indent of the question is
// what decides it, and it VARIES in the wild: 3 spaces in the capture below,
// 1 space in another taken the same way minutes later. That is precisely why
// the fix does not key on indentation — the run starts at 1, so the walk stops
// when it prepends option 1 and nothing above can belong to the dialog.

test('a numbered plan body does not swallow the approval dialog', () => {
  // VERBATIM from a live pane (16-step plan, ANSI stripped). This exact capture
  // returned null before the fix.
  const lines = [
    '   6. Decide no trailing newline handling needed beyond default.',
    '   7. Prepare the Write tool call.',
    '   8. Set file_path to <cwd>/cloud.txt.',
    '   9. Set content to hi.',
    '   10. Execute the Write tool call.',
    '   11. Confirm the tool reported success.',
    '   12. List the directory to see cloud.txt.',
    '   13. Read cloud.txt back.',
    '   14. Verify the content equals hi.',
    '   15. Confirm no other files were changed.',
    '   16. Report completion to the user.',
    '  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌',
    '',
    '',
    '',
    '',
    '',
    '  ──────────────────────────────────────────────────────────────────────────────────────────────────────────',
    '   Claude has written up a plan and is ready to execute. Would you like to proceed?',
    '',
    '   ❯ 1. Yes, and use auto mode',
    '     2. Yes, manually approve edits',
    '     3. Tell Claude what to change',
    '        shift+tab to approve with this feedback',
    '',
    '   ctrl+g to edit in Vim · ~/.claude/plans/linear-singing-wilkinson.md',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'a plan approval above a NUMBERED plan body must still be detected');
  assert.strictEqual(p.options.length, 3);
  assert.deepStrictEqual(p.options.map((o) => o.number), [1, 2, 3]);
  assert.strictEqual(p.options[0].selected, true);
  assert.match(p.question, /Would you like to proceed\?$/);
});

test('detectPrompt finds an edit permission under its own footer', () => {
  const lines = [
    ' Create file',
    ' hello.txt',
    '╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌',
    '  1 hi',
    '╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌',
    ' Do you want to create hello.txt?',
    ' ❯ 1. Yes',
    '   2. Yes, allow all edits during this session (shift+tab)',
    '   3. No',
    ' Esc to cancel · Tab to amend',
  ];
  const p = detectPrompt(lines);
  assert.ok(p, 'a live edit permission must be detected');
  assert.strictEqual(p.question, 'Do you want to create hello.txt?');
  assert.strictEqual(p.options.length, 3);
});

test('a composer below the run still means the options are history', () => {
  // The forgiving footer must not cost the false-positive protection: the
  // composer being drawn is proof the turn is over. Note the mode line here
  // even CONTAINS "shift+tab", which a phrase-matching fix would have accepted.
  const lines = [
    '● Here is what I would do next:',
    '  1. Rotate the B2 keys',
    '  2. Re-run the audit',
    '',
    '────────────────────────────────────────',
    '❯ ',
    '────────────────────────────────────────',
    '  [andrev] Opus 5 · main',
    '  ⏵⏵ auto mode on (shift+tab to cycle)',
  ];
  assert.strictEqual(detectPrompt(lines), null);
});

test('a status line below the run means the options are history', () => {
  assert.strictEqual(detectPrompt([
    'Pick:', '❯ 1. a', '  2. b', '  [seerr] Fable 5 · main',
  ]), null);
});

test('a spinner below the run means the run is not a live question', () => {
  assert.strictEqual(detectPrompt([
    'Pick:', '❯ 1. a', '  2. b', '✻ Working…',
  ]), null);
});

test('prose piled under a numbered list is still not a prompt', () => {
  // The footer allowance is bounded, so an answer that happens to end in a list
  // and then keeps talking does not become a set of buttons.
  assert.strictEqual(detectPrompt([
    'Here is the plan:',
    '❯ 1. first',
    '  2. second',
    'Then I checked the logs and found the retry loop.',
    'That accounts for the duplicate rows.',
    'I also verified the backup completed.',
    'The remaining question is whether to rotate the key.',
    'I will wait for your call on that.',
  ]), null);
});
