'use strict';
// A message carrying characters Claude Code 2.1.30x will not submit: what it
// loses before the paste (typing.sendable), and how the pane is read afterwards
// (squashPane, under every settle, confirm and duplicate check).
//
// The rule for `sendable` is that it never removes a character Claude Code would
// KEEP — an emoji's joiner, a Persian word's ZWNJ, a flag's tags. Everything it
// cannot be sure about is left for Claude Code, and the pane comparisons below
// are what keep the answer honest when it refuses.

const { test } = require('node:test');
const assert = require('node:assert');
const t = require('../lib/typing');

const RULE = '─'.repeat(40);
/** A composer holding `s`, the shape composerText finds. */
const box = (s) => [RULE, `❯ ${s}`, RULE, '  [fake] Fable 5.1 · ctx 4%'];

test('what Claude Code removes in every context is gone before the paste', () => {
  assert.equal(t.sendable('use the auto\u00ADscaler config'), 'use the autoscaler config', 'soft hyphen');
  assert.equal(t.sendable('\uFEFFhello'), 'hello', 'byte order mark');
  assert.equal(t.sendable('a\u2060b'), 'ab', 'word joiner');
  assert.equal(t.sendable('abc\u202Edef'), 'abcdef', 'a bidi override');
  assert.equal(t.sendable('run\u0007 it'), 'run it', 'a control character');
  assert.equal(t.sendable('hi\u{E0049}\u{E0047}\u{E004E}there'), 'hithere', 'tag characters smuggling text');
  // Line breaks it rewrites become the LF it would make of them.
  assert.equal(t.sendable('one\r\ntwo\rthree\u2028four\u000Bfive'), 'one\ntwo\nthree\nfour\nfive');
});

test('a joiner or selector goes only where Claude Code would never keep it', () => {
  assert.equal(t.sendable('use the auto\u200Cscaler config'), 'use the autoscaler config', 'ZWNJ in Latin');
  assert.equal(t.sendable('auto\u200Bscaler'), 'autoscaler', 'ZWSP in Latin');
  assert.equal(t.sendable('ok\uFE0F then'), 'ok then', 'a stray VS16');
  assert.equal(t.sendable('\u200Eleft to right'), 'left to right', 'LRM with no right-to-left text');
  // Kept by Claude Code, so kept here.
  const family = '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}';
  assert.equal(t.sendable(`the ${family} emoji`), `the ${family} emoji`);
  const heart = '❤\uFE0F';
  assert.equal(t.sendable(`thanks ${heart}`), `thanks ${heart}`);
  const persian = 'می\u200Cخواهم';
  assert.equal(t.sendable(persian), persian);
  const england = '\u{1F3F4}\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}';
  assert.equal(t.sendable(`go ${england}`), `go ${england}`, 'the flag keeps its tags');
  // Line by line: a plain line is cleaned beside one that is not.
  assert.equal(t.sendable(`plain\u200Bline\n${family}`), `plainline\n${family}`);
});

test('what pasteSafe already did still happens, and ordinary text is untouched', () => {
  assert.equal(t.sendable('first part\u001b[201~\nsecond part'), 'first part\nsecond part');
  assert.equal(t.sendable('tabs\tand\nnewlines stay'), 'tabs\tand\nnewlines stay');
  assert.equal(t.sendable('café, naïve, 東京, Привет'), 'café, naïve, 東京, Привет');
});

test('the pane is read without the characters it may not show', () => {
  // Refused: Claude Code put the cleaned text back in the box. It has NOT left.
  assert.equal(t.composerCleared(box('use the autoscaler config'), 'use the auto\u200Cscaler config'), false);
  // A character the renderer never draws: the paste has landed all the same.
  assert.equal(t.pasteLanded(box('use the autoscaler config'), 'use the auto\u00ADscaler config'), true);
  // And on the retry the box holds exactly this message, so the send is an Enter.
  assert.equal(t.boxHoldsExactly('café autoscaler config', 'café auto\u200Cscaler config'), true);
  // Ordinary reads are unchanged.
  assert.equal(t.composerCleared(box(''), 'use the autoscaler config'), true);
  assert.equal(t.boxHoldsExactly('somebody else', 'café auto\u200Cscaler config'), false);
});
