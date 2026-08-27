'use strict';
// The scratchpad RULES: what a page may be called, what fits, and exactly what
// the text looks like once a page is attached to a message.
//
// The frames are asserted as LITERALS rather than by round-tripping through a
// helper, because two other files carry copies of them — :core's
// ScratchpadRules collapses them back into a pill, and the daemon's
// humanizeUserText does the same for a chat title. A helper-shaped test would go
// on passing while those two silently stopped matching.

const { test } = require('node:test');
const assert = require('node:assert');
const s = require('../lib/scratchpads');

// -------------------------------------------------------------- naming rules

test('a page needs a name that is actually a name', () => {
  assert.match(s.nameProblem(''), /needs a name/);
  assert.match(s.nameProblem('   '), /needs a name/);
  assert.match(s.nameProblem('\n\t'), /needs a name/, 'whitespace-only after cleaning is still empty');
  assert.equal(s.nameProblem('Deploy notes'), null);
});

test('a name is one line with no controls in it', () => {
  // The name is shown in a picker, on a chip and in a rail tooltip; a terminal
  // renders some of what it is handed, and a newline splits one row into two.
  assert.equal(s.cleanName('Deploy\nnotes'), 'Deploy notes');
  // The ESC goes and its printable tail stays: what this strips is a name
  // that can erase its own row and reprint, not a bracket sequence's letters.
  assert.equal(s.cleanName('Deploy\u001b[2Knotes'), 'Deploy [2Knotes');
  assert.equal(s.cleanName('  spaced   out  '), 'spaced out');
});

test('a double quote is refused, not stripped', () => {
  // It is the frame's own delimiter: a name carrying one would end the marker
  // early and leave the rest of it in the user's message as raw text. Refused
  // rather than silently edited — a page called Ideas "v2" coming back as
  // Ideas v2 is a change nobody asked for.
  assert.match(s.nameProblem('Ideas "v2"'), /double quote/);
});

test('a name longer than the cap is refused rather than truncated', () => {
  assert.equal(s.nameProblem('x'.repeat(s.MAX_NAME)), null);
  assert.match(s.nameProblem('x'.repeat(s.MAX_NAME + 1)), /at most 60/);
});

test('names are unique case-insensitively', () => {
  // Two rows that READ the same is how the wrong page gets attached.
  assert.match(s.nameProblem('notes', ['Notes']), /already a page/);
  assert.match(s.nameProblem('  NOTES  ', ['notes']), /already a page/);
  assert.equal(s.nameProblem('notes', ['Deploy notes']), null, 'a substring is not a collision');
});

test('content has a cap and a reason', () => {
  assert.equal(s.contentProblem(''), null);
  assert.equal(s.contentProblem('x'.repeat(s.MAX_CONTENT)), null);
  assert.match(s.contentProblem('x'.repeat(s.MAX_CONTENT + 1)), /100,000/);
  assert.match(s.contentProblem(null), /must be text/);
});

// ------------------------------------------------------------------- frames

test('the chat frame is the literal both clients collapse', () => {
  assert.equal(
    s.composeForChat({ name: 'Main', content: 'one\ntwo' }, 'what do you make of this?'),
    '[Scratchpad "Main"]\none\ntwo\n[End scratchpad]\n\nwhat do you make of this?',
  );
});

test('an empty page still frames, so the run knows what it was given', () => {
  assert.equal(
    s.composeForChat({ name: 'Main', content: '' }, 'hello'),
    '[Scratchpad "Main"]\n\n[End scratchpad]\n\nhello',
  );
});

test('the session frame names a PATH and never the page itself', () => {
  // A pane takes 8,000 characters and a page holds 100,000, so pasting one in
  // would refuse most of the pages worth attaching.
  const composed = s.composeForSession(
    { name: 'Deploy notes', content: 'x'.repeat(90_000) },
    '/var/lib/huginn-appd/scratchpads/render/abc.md',
    'follow this',
  );
  assert.equal(
    composed,
    '[Scratchpad "Deploy notes" at /var/lib/huginn-appd/scratchpads/render/abc.md — '
      + 'read it before acting on this message.]\nfollow this',
  );
  assert.ok(!composed.includes('xxxx'), 'the content must not travel into a pane');
});

// --------------------------------------------------------------------- fit

test('a page plus a message that will not fit is refused before it is sent', () => {
  const composed = s.composeForChat({ name: 'Main', content: 'y'.repeat(99_990) }, 'go');
  const why = s.fitProblem(composed, 100_000);
  assert.ok(why, 'over the chat cap');
  // Phrased about the PAGE. The person typed two characters, and "text too long"
  // about those two characters reads as a bug in the composer.
  assert.match(why, /that page and this message/);
  assert.match(why, /shorten one of them/);
});

test('the session fit is measured on the COMPOSED line, not on what was typed', () => {
  // 7,950 typed characters are legal on their own and are not legal once the
  // reference line is in front of them — which is exactly the case a check on
  // body.text alone would wave through into a truncated pane paste.
  const path = '/var/lib/huginn-appd/scratchpads/render/6f1c0f5e-0000-4000-8000-000000000001.md';
  const typed = 'z'.repeat(7_950);
  assert.equal(s.fitProblem(typed, 8000), null, 'the typing alone fits');
  const composed = s.composeForSession({ name: 'Deploy notes' }, path, typed);
  assert.ok(s.fitProblem(composed, 8000), 'the composed line does not');
});

test('a page that fits stays silent', () => {
  assert.equal(s.fitProblem(s.composeForChat({ name: 'Main', content: 'short' }, 'go'), 100_000), null);
});

// --------------------------------------------------------------------- views

test('a list row carries the size but never the content', () => {
  const row = s.padRow({
    id: 'abc', name: 'Main', content: 'hello there', main: true,
    createdAt: 10, updatedAt: 20, rev: 3,
  });
  assert.deepEqual(row, {
    id: 'abc', name: 'Main', createdAt: 10, updatedAt: 20, rev: 3, main: true, size: 11,
  });
  assert.ok(!('content' in row), 'the list is cheap precisely because it is not the content');
});

test('Main sorts first and everything else by most recently edited', () => {
  const pads = [
    { id: 'b', name: 'Old', updatedAt: 100 },
    { id: 'c', name: 'New', updatedAt: 300 },
    { id: 'a', name: 'Main', main: true, updatedAt: 1 },
  ];
  assert.deepEqual(s.sortPads(pads).map((p) => p.id), ['a', 'c', 'b']);
});
