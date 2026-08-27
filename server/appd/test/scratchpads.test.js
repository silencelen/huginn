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

// ⚠ A PAGE CAN CONTAIN ITS OWN CLOSING MARKER. Pages of pasted conversation
// routinely do — this very repository's tests are full of the literal. An
// untagged frame around such a page ends at the PASTED line: the run reads half
// of what was attached, and every collapser turns the remainder into raw marker
// text sitting in the sender's own message.

test('a page holding the closing marker gets a tag on BOTH ends', () => {
  const composed = s.composeForChat(
    { name: 'Notes', content: 'before\n[End scratchpad]\nafter' },
    'what happened here?',
    'a1b2c3',
  );
  assert.equal(
    composed,
    '[Scratchpad "Notes" #a1b2c3]\nbefore\n[End scratchpad]\nafter\n[End scratchpad #a1b2c3]\n\nwhat happened here?',
  );
});

test('a whole pasted FRAME inside a page survives intact', () => {
  // The realistic shape: somebody keeps a page of things they were sent, and one
  // of them was a message that had a page attached to it.
  const inner = '[Scratchpad "Hostnames"]\nheimdall\n[End scratchpad]';
  const composed = s.composeForChat({ name: 'Archive', content: inner }, 'and this one', 'deadbe');
  assert.ok(composed.startsWith('[Scratchpad "Archive" #deadbe]\n'));
  assert.ok(composed.includes(`\n${inner}\n[End scratchpad #deadbe]\n\nand this one`));
  assert.equal(composed.match(/\[End scratchpad/g).length, 2, 'the inner one is content, not an end');
});

test('a tag is minted only when the content forces one', () => {
  // The untagged literal is the one three other files quote; minting a tag for
  // every page would break all of them for no reason.
  assert.equal(
    s.chatFrame('Main', 'nothing special here'),
    '[Scratchpad "Main"]\nnothing special here\n[End scratchpad]',
  );
  // Mid-line is not a closing line, and is not worth a tag.
  assert.equal(
    s.chatFrame('Main', 'the marker is [End scratchpad] in prose'),
    '[Scratchpad "Main"]\nthe marker is [End scratchpad] in prose\n[End scratchpad]',
  );
});

test('a minted tag is six lowercase hex and matches on both markers', () => {
  const framed = s.chatFrame('Main', '[End scratchpad] at the very top');
  const open = /^\[Scratchpad "Main" #([0-9a-f]{6})\]\n/.exec(framed);
  assert.ok(open, `no tagged open marker: ${JSON.stringify(framed.slice(0, 60))}`);
  assert.ok(framed.endsWith(`\n[End scratchpad #${open[1]}]`), 'the same tag closes it');
});

test('a tag that is already in the content is not the tag that is minted', () => {
  // A collision would close the frame early in exactly the case the tag exists
  // to prevent, so it is re-minted rather than trusted.
  for (let i = 0; i < 40; i++) {
    const framed = s.chatFrame('Main', '[End scratchpad #abc123]\nkeep reading');
    const tag = /^\[Scratchpad "Main" #([0-9a-f]{6})\]/.exec(framed)[1];
    assert.notEqual(tag, 'abc123');
  }
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

test('the SESSION refusal blames the message, because the page is not in it', () => {
  // ⚠ THE CHAT WORDING IS WRONG HERE and points at a fix that cannot work. Only
  // the one-line reference travels into a pane, so "shorten the page" changes
  // the composed length by exactly nothing; the number worth telling somebody is
  // how much room is left for what they typed once the reference has its share.
  const file = '/var/lib/huginn-appd/scratchpads/render/6f1c0f5e-0000-4000-8000-000000000001-1756000000000.md';
  const frame = s.sessionFrame('Deploy notes', file);
  const typed = 'z'.repeat(8_000);
  const composed = s.composeForSession({ name: 'Deploy notes' }, file, typed);

  const why = s.sessionFitProblem(composed, frame, 8000);
  assert.ok(why, 'over the pane cap');
  assert.doesNotMatch(why, /that page and this message/, 'the chat wording must not leak onto this path');
  assert.match(why, /one-line reference/);
  assert.match(why, new RegExp(`${frame.length} of the 8,000`), 'the frame line is named explicitly');
  assert.match(why, new RegExp(`room for ${(8000 - frame.length - 1).toLocaleString('en-US')}`));
  assert.match(why, /this message is 8,000/);
});

test('a session message that fits stays silent', () => {
  const file = '/var/lib/huginn-appd/scratchpads/render/abc-1.md';
  const frame = s.sessionFrame('Main', file);
  const composed = s.composeForSession({ name: 'Main' }, file, 'go');
  assert.equal(s.sessionFitProblem(composed, frame, 8000), null);
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

test('Main sorts first and everything else by NAME, not by recency', () => {
  // ⚠ THE ORDER IS A PLACE. It used to be newest-edit-first, which means typing
  // into a page moves it to the top while you are looking at it: the row under
  // the cursor becomes a different page, and the next click opens that one. A
  // tester typed into the wrong pad twice in one sitting. Recency is already ON
  // the row as `updatedAt`; it does not also get to be the ordering.
  const pads = [
    { id: 'b', name: 'Zebra', updatedAt: 900 },
    { id: 'c', name: 'apple', updatedAt: 300 },
    { id: 'd', name: 'Banana', updatedAt: 800 },
    { id: 'a', name: 'Main', main: true, updatedAt: 1 },
  ];
  assert.deepEqual(s.sortPads(pads).map((p) => p.name), ['Main', 'apple', 'Banana', 'Zebra']);
});

test('the name order ignores case, so a list does not split into two alphabets', () => {
  const pads = [
    { id: '1', name: 'beta' }, { id: '2', name: 'Alpha' }, { id: '3', name: 'GAMMA' },
  ];
  assert.deepEqual(s.sortPads(pads).map((p) => p.name), ['Alpha', 'beta', 'GAMMA']);
});

test('two pages that read the same still have ONE order', () => {
  // Uniqueness is enforced at create time, not at rest: a rename on one device
  // races a create on another, and a comparator that called them equal would
  // leave the list free to swap them between polls.
  const pads = [{ id: 'zzz', name: 'Notes' }, { id: 'aaa', name: 'notes' }];
  assert.deepEqual(s.sortPads(pads).map((p) => p.id), ['aaa', 'zzz']);
  assert.deepEqual(s.sortPads([...pads].reverse()).map((p) => p.id), ['aaa', 'zzz']);
});
