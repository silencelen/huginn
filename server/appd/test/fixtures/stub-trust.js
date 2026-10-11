'use strict';
// A stand-in for Claude Code 2.1.296's UNNUMBERED folder-trust dialog, drawn in a
// throwaway tmux pane: Up/Down really move the caret, Enter reports the choice.
// Same frame-clearing as stub-picker.js.
const rows = ['No, exit', 'Yes, I trust this folder'];
let cursor = 0;
let done = false;
const CLEAR = '\u001b[2J\u001b[H';
function draw() {
  process.stdout.write([CLEAR,
    ' Accessing workspace:', '', ' /tmp/fixcap', '',
    " Quick safety check: Is this a project you created or one you trust? (Like your",
    ' own code, a well-known open source project, or work from your team). If not,',
    " take a moment to review what's in this folder first.", '',
    " Claude Code'll be able to read, edit, and execute files here.", '', ' Security guide', '',
    ...rows.map((r, i) => (i === cursor ? ` ❯ ${r}` : `   ${r}`)), '',
    ' Enter to confirm · Esc to cancel', ''].join('\n'));
}
process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', (buf) => {
  if (done) return;
  const s = buf.toString('utf8');
  if (s.includes('[A') || s.includes('OA')) { cursor = Math.max(0, cursor - 1); draw(); return; }
  if (s.includes('[B') || s.includes('OB')) { cursor = Math.min(rows.length - 1, cursor + 1); draw(); return; }
  if (s.includes('\r') || s.includes('\n')) { done = true; process.stdout.write(`${CLEAR}chose: ${rows[cursor]}\n`); return; }
  if (/[0-9]/.test(s)) { process.stdout.write(''); }   // digits do nothing, as in the real dialog
});
draw();
