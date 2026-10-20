'use strict';
// A stand-in for a numbered Claude Code 2.1.302 selector, drawn in a throwaway
// tmux pane, that reacts to keys the way the real Select does:
//
//   a digit on an ordinary row        answers it at once (the Select's onChange)
//   a digit on an `input` row         only moves the caret there (an input row with
//                                     nothing typed in it, e.g. "No, keep planning")
//   Enter while the question is up    answers the row the caret is on
//   Enter after it was answered       submits the composer's draft, the harm a
//                                     blind Enter after an answering digit does
//
// HG_STUB_SPEC (JSON): { question, options: [{label, input?}], draft?, reaskMs? }
//   reaskMs   draw the SAME question again this long after it was answered, once:
//             Claude asking to run the same command a second time
// HG_STUB_KEYS     file every byte typed at the pane is appended to, raw
// HG_STUB_EVENTS   file the stub appends what happened to, one line each
const fs = require('node:fs');

const spec = JSON.parse(process.env.HG_STUB_SPEC);
const KEYS = process.env.HG_STUB_KEYS;
const EVENTS = process.env.HG_STUB_EVENTS;
const CLEAR = '\u001b[2J\u001b[H';
const RULE = '─'.repeat(60);
let caret = 1;
let up = true;
let reasked = false;

const event = (line) => fs.appendFileSync(EVENTS, `${line}\n`);
function draw() {
  const rows = up
    ? [spec.question, '',
      ...spec.options.map((o, i) => `${i + 1 === caret ? '❯' : ' '} ${i + 1}. ${o.label}`), '',
      'Enter to select · Esc to cancel']
    : ['● ok', '', RULE, `❯ ${spec.draft || ''}`, RULE, '  [stub] Fable 5.1 · ctx 4%'];
  process.stdout.write(CLEAR + rows.join('\n') + '\n');
}
function answered(n, how) {
  event(`ANSWERED ${n} by ${how}`);
  up = false;
  caret = 1;
  draw();
  if (spec.reaskMs && !reasked) {
    reasked = true;
    setTimeout(() => { up = true; draw(); event('ASKED AGAIN'); }, spec.reaskMs);
  }
}

process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  fs.appendFileSync(KEYS, d);
  for (const ch of d) {
    if (/[1-9]/.test(ch) && up) {
      const n = Number(ch);
      const row = spec.options[n - 1];
      if (!row) continue;
      if (row.input) { caret = n; draw(); event(`FOCUSED ${n}`); } else answered(n, 'digit');
    } else if (ch === '\r') {
      if (up) answered(caret, 'enter');
      else event(`SUBMITTED ${spec.draft || ''}`);
    }
  }
});
draw();
