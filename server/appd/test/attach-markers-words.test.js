'use strict';
// The skin train's send-contract question: the apps used to read a message back
// with pictographs (a camera, a paperclip, a memo pad) and now read it back in
// words ("Photo attached", "File: <name>", "Page: <title>"). This file is the
// daemon's half of that change, and it proves the daemon is indifferent to it.
//
// WHY INDIFFERENT. The pictographs were never on the wire. Every client writes
// the same bracketed markers into the message it sends —
//   [Attached image at <path> — view it with the Read tool.]
//   [Attached file at <path> (<name>) — …]
//   [Scratchpad "<name>"] … [End scratchpad]
// — and only DISPLAYS them as a pill (:core AttachmentText / ScratchpadRules).
// humanizeUserText reads the markers, never the pill, so an older app and a
// newer one send byte-identical text. What could still go wrong is a pill line
// that somebody pasted back into a message (old or new spelling) being mistaken
// for a marker and eaten; the last test pins that it is not.
//
// humanizeUserText is not exported (it lives in the daemon's entry file), so it
// is lifted out of the source with its two frame patterns and run in a sandbox:
// the test exercises the real function, not a copy of it.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'huginn-appd.js'), 'utf8');

function lift() {
  const pick = (startNeedle, endNeedle) => {
    const a = SRC.indexOf(startNeedle);
    assert.ok(a >= 0, `${startNeedle} is gone from huginn-appd.js`);
    const b = SRC.indexOf(endNeedle, a);
    assert.ok(b > a, `${startNeedle} has no ${JSON.stringify(endNeedle)} after it`);
    return SRC.slice(a, b + endNeedle.length);
  };
  const code = [
    pick('const CHAT_FRAME_RE =', ';\n'),
    pick('const SESSION_FRAME_RE =', ';\n'),
    pick('function humanizeUserText(t) {', '\n}\n'),
    'this.humanizeUserText = humanizeUserText;',
  ].join('\n');
  // The daemon has it from lib/transcript; the lifted function needs it too.
  const box = { unwrapPasted: require('../lib/transcript').unwrapPasted };
  vm.runInNewContext(code, box);
  return box.humanizeUserText;
}

const humanize = lift();

const IMAGE = '[Attached image at /var/lib/huginn-appd/uploads/img-1.jpg — view it with the Read tool.]';
const FILE = '[Attached file at /var/lib/huginn-appd/uploads/f-2 (backup.tar.gz) — view it with the Read tool.]';
const PAGE = '[Scratchpad "Hostnames"]\nheimdall .7.10\n[End scratchpad]\n';

test('the wire markers every app sends humanize to words', () => {
  assert.equal(humanize(`what is this?\n\n${IMAGE}`), 'what is this?\n\nphoto');
  assert.equal(humanize(`restore this\n\n${FILE}`), 'restore this\n\nfile');
  assert.equal(humanize(`${PAGE}which one is the standby?`), 'page Hostnames\nwhich one is the standby?');
  assert.equal(humanize(IMAGE), 'photo', 'a message that is only a marker still names what it is');
});

test('no pictograph comes out of the daemon for any marker', () => {
  for (const t of [IMAGE, FILE, PAGE + 'x', `${PAGE}look\n\n${IMAGE}\n${FILE}`]) {
    const out = humanize(t);
    assert.ok(!/[\u{1F300}-\u{1FAFF}]/u.test(out), `a pictograph in ${JSON.stringify(out)}`);
  }
});

test('a pill line pasted back into a message, in either spelling, is left as typed', () => {
  // Old pictograph pills and the new word pills are the person's own text once
  // pasted; neither is a marker, and neither may be eaten or rewritten.
  for (const pill of [
    '\u{1F4F7} Photo attached', '\u{1F4CE} backup.tar.gz', '\u{1F4DD} Hostnames',
    'Photo attached', 'File: backup.tar.gz', 'Page: Hostnames',
  ]) {
    assert.equal(humanize(`earlier I sent\n${pill}`), `earlier I sent\n${pill}`);
  }
});

test('a pasted block titles a chat with what was pasted, not its tags', () => {
  const pasted = '\n\n<pasted_content id="d842">\nbug: empty chats should auto delete\n</pasted_content id="d842">\n';
  assert.equal(humanize(pasted), 'bug: empty chats should auto delete');
});
