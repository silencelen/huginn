'use strict';
// A stand-in for Claude Code 2.1.306's ExitPlanMode approval dialog, drawn in a
// throwaway tmux pane (fixtures/prompts/plan-approval-v3-80.txt is the real one):
// a digit on an approve row answers at once (`chose: …`); the digit 3 FOCUSES the
// "Tell Claude what to change" row, typed text replaces its label, and Enter
// sends it (`feedback: …`). Same frame-clearing as stub-trust.js.
const rows = ['Yes, and use auto mode', 'Yes, manually approve edits', 'Tell Claude what to change'];
let cursor = 0;
let typed = '';
let done = false;
const CLEAR = '\u001b[2J\u001b[H';
const RULE = '─'.repeat(80);
const DASH = '╌'.repeat(80);
function draw() {
  const label = (i) => (i === 2 && typed ? typed : rows[i]);
  process.stdout.write([CLEAR,
    RULE, ' Ready to code?', '', ' Here is the plan:', DASH,
    ' 1. Create plan.txt in the working directory.', ' 2. Write the text hi into it.', DASH, '',
    RULE, ' Claude has written up a plan and is ready to execute. Would you like to', ' proceed?', '',
    ...rows.map((_, i) => (i === cursor ? ` ❯ ${i + 1}. ${label(i)}` : `   ${i + 1}. ${label(i)}`)),
    '      shift+tab to approve with this feedback', '',
    ' ctrl+g to edit in Vim · ~/.claude/plans/p.md', ''].join('\n'));
}
process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', (buf) => {
  if (done) return;
  const s = buf.toString('utf8');
  if (s.includes('\r') || s.includes('\n')) {
    done = true;
    process.stdout.write(cursor === 2 ? `${CLEAR}feedback: ${typed}\n` : `${CLEAR}chose: ${rows[cursor]}\n`);
    return;
  }
  if (cursor === 2 && typed) { typed += s; draw(); return; }          // the input row takes everything
  if (s === '1' || s === '2') { done = true; process.stdout.write(`${CLEAR}chose: ${rows[Number(s) - 1]}\n`); return; }
  if (s === '3') { cursor = 2; draw(); return; }                         // focuses the input row
  if (cursor === 2) { typed += s; draw(); }                              // first words into the focused row
});
draw();
