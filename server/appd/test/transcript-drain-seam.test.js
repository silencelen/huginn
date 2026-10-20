'use strict';
// A poll that lands between a queue drain and the user records it produced.
//
// ⚠ THE DRAIN'S BOOKKEEPING LIVED FOR ONE CALL. When Claude goes idle with
// messages queued, Claude Code writes a `dequeue` record and then each drained
// message again as an ordinary `user` record — usually in the same write, but
// not always: an old turn's last records can be flushed in between, and a run
// that resumes writes the user record about two seconds later. A resumed read
// knows those user records are echoes only while the dequeue is in the same
// window, so a poll that ended on the dequeue left the next one to draw each
// echo as a NEW bubble: the message twice, and the original's "queued" badge
// never cleared because nothing ever reported the delivery.
//
// The property under test is the one the reader relies on: splitting the poll
// at that seam must not change what the client ends up showing. Each shape is
// read twice — dequeue and echo in one poll, then in two — through the real
// reader and the clients' merge (TranscriptMerge.kt: clearDelivered on what is
// on screen, then the new page appended).
//
// SAFETY: scratch files only.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { readTranscript } = require('../lib/transcript');

const T = (ms) => new Date(Date.UTC(2026, 9, 19, 10, 0, 0) + ms).toISOString();
const user = (ms, text) => ({ type: 'user', timestamp: T(ms), message: { content: text } });
const said = (ms, text) => ({ type: 'assistant', timestamp: T(ms), message: { content: [{ type: 'text', text }] } });
const enqueue = (ms, content) => ({ type: 'queue-operation', operation: 'enqueue', timestamp: T(ms), content });
const dequeue = (ms) => ({ type: 'queue-operation', operation: 'dequeue', timestamp: T(ms) });
const meta = () => ({ type: 'last-prompt', lastPrompt: 'x' });

function clearDelivered(events, delivered) {
  if (!delivered.length) return events;
  const rest = delivered.slice();
  return events.map((ev) => {
    if (!ev.queued) return ev;
    const i = rest.indexOf(ev.text);
    if (i < 0) return ev;
    rest.splice(i, 1);
    const { queued, ...plain } = ev;
    return plain;
  });
}

/** Write `polls` one batch at a time, polling after each; the first poll is a cold open. */
function viewAfter(polls) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-drainseam-'));
  const file = path.join(dir, 's.jsonl');
  fs.writeFileSync(file, '');
  let offset = null;
  let view = [];
  try {
    for (const batch of polls) {
      fs.appendFileSync(file, batch.map((r) => `${JSON.stringify(r)}\n`).join(''));
      const page = readTranscript(file, offset == null ? {} : { offset });
      offset = page.nextOffset;
      view = clearDelivered(view, page.deliveredQueued).concat(page.events);
    }
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
  return view.map((e) => `${e.kind}:${e.text}${e.queued ? ' [queued]' : ''}`);
}

/** The same records, with the second and third polls taken as one. */
function joinedAndSplit(first, beforeSeam, afterSeam) {
  return {
    joined: viewAfter([first, beforeSeam.concat(afterSeam)]),
    split: viewAfter([first, beforeSeam, afterSeam]),
  };
}

const prior = [user(0, 'first question'), said(1000, 'working')];

const SHAPES = {
  'a message queued while busy, drained when the turn ends': [
    [...prior, enqueue(2000, 'do the restart asap')],
    [said(9000, 'answer'), dequeue(9100)],
    [user(9125, 'do the restart asap'), said(12000, 'restarting')],
  ],
  'the old turn\'s last record flushed between the drain and its echo': [
    [...prior, enqueue(2000, 'do the restart asap')],
    [dequeue(9100), said(9050, 'answer'), meta()],
    [user(9125, 'do the restart asap'), said(12000, 'restarting')],
  ],
  'two messages drained, the poll landing between their echoes': [
    [...prior, enqueue(2000, 'first ask'), enqueue(3000, 'second ask')],
    [said(9000, 'answer'), dequeue(9100), user(9125, 'first ask')],
    [user(9126, 'second ask'), said(12000, 'on both')],
  ],
  'a message sent at an idle prompt (enqueue and dequeue together)': [
    [...prior, said(3000, 'done')],
    [enqueue(60000, 'ok ship it'), dequeue(60043)],
    [user(60084, 'ok ship it'), said(63000, 'shipping')],
  ],
  'a resumed run, whose user record lands two seconds after its drain': [
    [...prior, said(3000, 'bye')],
    [enqueue(900000, 'pick this back up'), dequeue(900003)],
    [user(902100, 'pick this back up'), said(906000, 'resuming')],
  ],
};

for (const [name, [first, beforeSeam, afterSeam]] of Object.entries(SHAPES)) {
  test(`a poll between a drain and its echo changes nothing: ${name}`, () => {
    const { joined, split } = joinedAndSplit(first, beforeSeam, afterSeam);
    assert.deepEqual(split, joined, 'the view is the one an unsplit poll gives');
    assert.equal(0, split.filter((l) => l.endsWith('[queued]')).length, 'and nothing is left waiting');
    const users = split.filter((l) => l.startsWith('user:'));
    assert.equal(new Set(users).size, users.length, `and no message twice: ${JSON.stringify(split)}`);
  });
}

// ⚠ NOT EVERY USER RECORD BEHIND A DRAIN IS ITS ECHO. The old turn's own
// records can be flushed behind the dequeue, stamped BEFORE it — seen on this
// host as a person's message written 120 ms after the dequeue that followed it.
// An echo never is (1,663 measured, all 4 ms to 9 s after their drain).
const LATE_FLUSH = [
  [...prior, enqueue(2000, 'do the restart asap')],
  [dequeue(9100)],
  [user(8980, 'stop, wrong branch'), said(8990, 'stopping'), user(11200, 'do the restart asap'), said(13000, 'restarting')],
];

test('a person\'s message the old turn flushed behind the drain is drawn, in one poll', () => {
  const [first, beforeSeam, afterSeam] = LATE_FLUSH;
  const view = viewAfter([first, beforeSeam.concat(afterSeam)]);
  assert.deepEqual(['user:first question', 'user:do the restart asap', 'user:stop, wrong branch'],
    view.filter((l) => l.startsWith('user:')), 'the message is a message, and the echo is not one');
});

test('a person\'s message the old turn flushed behind the drain is drawn across the seam too', () => {
  const { joined, split } = joinedAndSplit(...LATE_FLUSH);
  assert.deepEqual(split, joined);
  assert.equal(1, split.filter((l) => l === 'user:stop, wrong branch').length, JSON.stringify(split));
  assert.equal(1, split.filter((l) => l === 'user:do the restart asap').length, JSON.stringify(split));
});

// The controls: a user record at the seam that is NOT an echo is a message.

test('a message typed after the drained turn began is drawn, seam or not', () => {
  const { joined, split } = joinedAndSplit(
    [...prior, enqueue(2000, 'do the restart asap')],
    [said(9000, 'answer'), dequeue(9100), user(9125, 'do the restart asap'), said(12000, 'restarting')],
    [user(20000, 'and then check the logs'), said(21000, 'checking')],
  );
  assert.deepEqual(split, joined);
  assert.ok(split.includes('user:and then check the logs'), JSON.stringify(split));
});

test('a plain message at the seam with no drain above it is drawn', () => {
  const view = viewAfter([prior, [said(3000, 'done')], [user(60000, 'hello again'), said(61000, 'hi')]]);
  assert.ok(view.includes('user:hello again'), JSON.stringify(view));
});

test('a message typed long after a drain whose turn never wrote is drawn', () => {
  // The bound the in-window guard already has (DRAIN_ECHO_S): an echo lands
  // seconds behind its drain, so ten minutes later it is somebody typing.
  const { joined, split } = joinedAndSplit(
    [...prior, enqueue(2000, 'do the restart asap')],
    [said(9000, 'answer'), dequeue(9100), user(9125, 'do the restart asap')],
    [user(9100 + 600_000, 'anyone there?')],
  );
  assert.deepEqual(split, joined);
  assert.ok(split.includes('user:anyone there?'), JSON.stringify(split));
});
