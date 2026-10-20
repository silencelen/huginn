'use strict';
// Claude Code's composer in SHELL MODE is still the composer, and nothing is
// submitted into it: its Enter runs the box as a shell command.
//
// Shaped the way Claude Code 2.1.302 draws it (read from its bundle): the same
// column-0 rule above and below the box as in prompt mode, the caret drawn as
// `!` + NBSP in its own colour instead of `❯` + NBSP, and the footer replaced by
// `! for shell mode`.

const { test } = require('node:test');
const assert = require('node:assert');
const t = require('../lib/typing');

const NBSP = ' ';
const R80 = '─'.repeat(80);
const STATUS = '  [bp] Fable 5.1 · ctx 43% · main ~5';
/** A reply of `n` rows, under the person's own echoed message. */
const history = (n) => ['❯ check the deploy log', '', '● Here is what changed in the deploy:',
  ...Array.from({ length: n }, (_, i) => `  - item ${i + 1}: adjusted the config for service ${i + 1}`),
  '', '✻ Worked for 12s', ''];
/** The box in shell mode holding `typed`, as `capture-pane -p` gives it. */
const shellBox = (typed = '') => [R80, `!${NBSP}${typed}`, R80, STATUS, '  ! for shell mode'];
/** …and as `capture-pane -p -e` gives it, which is what the send gate reads. */
const shellBoxEscaped = (typed = '') => [`\u001b[38;5;244m${R80}`,
  `\u001b[38;5;210m!${NBSP}\u001b[39m${typed}`, `\u001b[38;5;244m${R80}`,
  `\u001b[39m  \u001b[38;5;246m${STATUS.trim()}\u001b[39m`, '  \u001b[38;5;210m! for shell mode\u001b[39m'];
const promptBox = (typed = '') => [R80, `❯${NBSP}${typed}`, R80, STATUS,
  '  ⏵⏵ auto mode on (shift+tab to cycle)'];

const MESSAGE = 'please rewrite > notes.txt tomorrow';
const PANES = {
  // The last `❯` echo has scrolled out of the lookback: no caret anywhere near.
  'long reply': [...history(16), ...shellBox('git sta')],
  // The echo is still in reach, and used to be read as the composer instead.
  'short reply': [...history(4), ...shellBox('git sta')],
  'long reply, escapes kept': [...history(16), ...shellBoxEscaped('git sta')],
};

test('the box in shell mode is the composer: drawn, and holding a draft', () => {
  for (const [label, pane] of Object.entries(PANES)) {
    assert.equal(t.composerShellMode(pane), true, label);
    assert.equal(t.composerDrawn(pane), true, `${label}: claude is up`);
    assert.equal(t.composerText(pane, { dropGhost: true }), 'git sta', `${label}: the box, without its \`!\``);
    // A draft held by the person typing it, exactly as one in prompt mode is.
    const draft = t.composerHoldsDraft(t.composerText(pane, { dropGhost: true }), MESSAGE);
    assert.equal(draft, true, label);
    assert.equal(t.draftHold({ draft, keysAgoMs: 5_000 }), true, label);
  }
  assert.equal(t.composerText([...history(16), ...shellBox()]), '', 'an empty box in shell mode is empty');
});

test('nothing is submitted into shell mode: the submit is refused, the way it is at a shell', () => {
  for (const pane of [...Object.values(PANES), [...history(16), ...shellBox()]]) {
    const refusal = t.submitRefusal({
      submit: true, composer: t.composerDrawn(pane), shell: t.shellPrompt(pane), shellMode: t.composerShellMode(pane),
    });
    assert.match(String(refusal), /shell mode/);
  }
  // Live-view typing carries no Enter and is still the person's to send.
  const pane = PANES['long reply'];
  assert.equal(t.submitRefusal({ submit: false, composer: true, shell: false, shellMode: t.composerShellMode(pane) }), null);
});

test('the look before an Enter refuses shell mode, even with our own message in the box', () => {
  // `hadComposer` is read off the pane before the paste, as sendTextToPane does.
  const before = [...history(16), ...shellBox()];
  const pasted = [...history(16), ...shellBox(MESSAGE)];
  const held = t.enterRefusal(pasted, MESSAGE, { hadComposer: t.composerText(before) !== null });
  assert.equal(held && held.blockedBy, 'shell');
  assert.equal(held.shellMode, true);
  // A message that STARTS with `!` puts an empty box into shell mode itself; the
  // box then shows the rest of it, and an Enter would run that.
  const bang = '!important: hold the deploy until the tests pass';
  const prompt = [...history(16), ...promptBox()];
  assert.equal(t.enterRefusal([...history(16), ...shellBox(bang.slice(1))], bang,
    { hadComposer: t.composerText(prompt) !== null })?.blockedBy, 'shell');
});

test('a box in prompt mode, and a `!` row that is not the box, are untouched', () => {
  const live = [...history(4), ...promptBox()];
  assert.equal(t.composerShellMode(live), false);
  assert.equal(t.submitRefusal({ submit: true, composer: t.composerDrawn(live), shell: false, shellMode: false }), null);
  assert.equal(t.enterRefusal([...history(4), ...promptBox(MESSAGE)], MESSAGE, { hadComposer: true }), null);
  // An echoed shell-mode command in the history above a box in prompt mode.
  const echoed = [R80, '! git status', '  ⎿  On branch main', '', ...promptBox()];
  assert.equal(t.composerShellMode(echoed), false);
  assert.equal(t.composerText(echoed), '');
  // A `!` row under a rule with no closing rule under it is not a box.
  assert.equal(t.composerShellMode(['● ok', R80, '! git status']), false);
});
