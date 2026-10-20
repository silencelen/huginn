'use strict';
// Claude Code's UNNUMBERED selectors (`hideIndexes`) are dialogs too: a send
// waits for one the way it waits for a numbered one, and the same rows drawn
// anywhere but inside a dialog's frame are not read as one.
//
// The layouts are Claude Code 2.1.302's, rebuilt from its bundle: the permission
// frame draws only a top border (a column-0 rule), its title one column in and
// its body further in; the Select draws `❯ label` for the highlighted row and
// every other label two columns right of the caret, with no `N.`. EnterPlanMode
// draws no footer; the decline-first permission ask draws `Esc to cancel · …`.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { detectPrompt, detectUnnumberedSelector } = require('../lib/pane');
const t = require('../lib/typing');

const R80 = '─'.repeat(80);
const CONVERSATION = [
  '● I will look at how the parser handles empty input before changing anything.', '',
  '● Read(src/parser.ts)', '  ⎿  Read 240 lines', '',
];
const NUMBERED_REPLY = [
  '● Here is what I found:', '',
  '  1. The parser assumes a trailing newline.',
  '  2. Empty files reach the tokenizer with no guard.', '',
];
const ENTER_PLAN = [
  R80, ' Enter plan mode?', '',
  '  Claude wants to enter plan mode to explore and design an implementation', '  approach.', '',
  '  In plan mode, Claude will:', '   · Explore the codebase thoroughly', '   · Identify existing patterns',
  '   · Design an implementation strategy', '   · Present a plan for your approval', '',
  '  No code changes will be made until you approve the plan.', '',
  '  ❯ Yes, enter plan mode',
  '    No, start implementing now',
];
const DECLINE_FIRST = [
  R80, ' Bash command', '', '   rm -rf build/ dist/', '   Remove build output', '',
  ' Do you want to proceed?',
  ' ❯ No',
  '   Yes', '',
  ' Esc to cancel · Tab to amend',
];
const STATUS = ['  [bp] Fable 5.1 · main ~5', '  ⏵⏵ auto mode on (shift+tab to cycle)'];
const EMPTY_BOX = [R80, '❯ ', R80, ...STATUS];
/** A 40-row pane: history scrolled up above `rows`, or blank rows under them. */
const scrolled = (rows) => [...Array(Math.max(0, 40 - rows.length)).fill(''), ...rows];
const underfilled = (rows) => [...rows, ...Array(Math.max(0, 40 - rows.length)).fill('')];

const MESSAGE = "use the existing parser, don't write a new one";

const SELECTOR_PANES = {
  'EnterPlanMode': scrolled([...CONVERSATION, ...ENTER_PLAN]),
  'EnterPlanMode under a numbered reply': scrolled([...NUMBERED_REPLY, ...ENTER_PLAN]),
  'EnterPlanMode, blank rows under it': underfilled([...CONVERSATION, ...ENTER_PLAN]),
  'decline-first ask': scrolled([...CONVERSATION, ...DECLINE_FIRST]),
  'decline-first ask, caret moved to Yes': scrolled([...CONVERSATION,
    ...DECLINE_FIRST.map((r) => (r === ' ❯ No' ? '   No' : r === '   Yes' ? ' ❯ Yes' : r))]),
};

test('an unnumbered selector holds a send the way a numbered one does', () => {
  for (const [label, pane] of Object.entries(SELECTOR_PANES)) {
    const why = t.paneReadyForInput(pane).why;
    assert.equal(why, 'modal', label);
    assert.equal(t.paneBlocks(why), true, label);
    // A person's message: only a dialog may hold it, and this is one.
    assert.deepEqual(t.releaseDecision({ idle: true, paneWhy: why }), { release: false, blockedBy: 'modal' }, label);
    // And the look before an Enter sees it too.
    assert.equal(t.enterRefusal(pane, MESSAGE, { hadComposer: true })?.blockedBy, 'modal', label);
  }
});

test('the detector reads the frame, and offers nothing to answer', () => {
  for (const [label, pane] of Object.entries(SELECTOR_PANES)) {
    assert.equal(detectUnnumberedSelector(pane), true, label);
    // Not a prompt card: /screen and /answer see what they always did.
    assert.equal(detectPrompt(pane), null, label);
  }
});

test('the same rows outside a dialog frame are not a dialog', () => {
  const quote = ['● The plan-mode dialog looks like this:', '  ❯ Yes, enter plan mode', '    No, start implementing now', ''];
  const cases = {
    // Claude quoting the rows, with the live composer under them.
    'quoted over a live composer': scrolled([...CONVERSATION, ...quote, ...EMPTY_BOX]),
    // …under a box whose top rule carries a title, which the box reader skips.
    'quoted over a titled box': scrolled([...quote, `${'─'.repeat(66)} proj/role ─`, '❯ ', R80, '  ? for shortcuts']),
    // A draft in the box with the rows pasted into it.
    'pasted into a draft': scrolled([...CONVERSATION, R80, '❯ look at this:', '    ❯ Yes, enter plan mode',
      '      No, start implementing now', R80, ...STATUS]),
    // A file printed at the bottom of a pane, no frame above it.
    'a file at the bottom of a pane': scrolled(['$ cat notes.txt', 'menu:', '  ❯ Yes, enter plan mode',
      '    No, start implementing now']),
    // An echoed message: its caret is the composer's, at column 0.
    'an echoed two-line message': scrolled([...CONVERSATION, '❯ first line of a message', '  second line of it']),
    // A shell prompt drawn under them is the shell's turn.
    'a shell prompt under them': scrolled([R80, ' Enter plan mode?', '  ❯ Yes, enter plan mode',
      '    No, start implementing now', 'root@host:~#']),
  };
  for (const [label, pane] of Object.entries(cases)) {
    assert.equal(t.paneBlocks(t.paneReadyForInput(pane).why), false, label);
  }
});

test('the folder-trust dialog keeps its own name', () => {
  const lines = fs.readFileSync(path.join(__dirname, 'fixtures', 'prompts', 'trust-dialog-unnumbered-80.txt'), 'utf8')
    .replace(/\n$/, '').split('\n');
  assert.equal(t.paneReadyForInput(lines).why, 'trust');
});
