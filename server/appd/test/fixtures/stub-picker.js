'use strict';
// A stand-in for Claude Code's `/model` picker, run inside a throwaway tmux pane.
//
// The ladder's delivery path is the one part of headroom that cannot be asserted
// purely: it types `/model`, waits for a dialog to appear, walks a cursor by
// reading the pane back, and presses `s`. Driving a REAL `claude` for that would
// need a model, an account and a trust dialog, and would spend usage to test the
// thing that exists to save it. So this draws the dialog instead — same rows,
// same glyphs, same `for this session only` confirmation — and every keystroke
// the daemon sends really does have to arrive, in order, for it to answer.
//
// argv: <rows-json> [nopicker]
//   rows-json  [{label, family?, desc?, current?}] — deliberately orderable by
//              the test, so a suite can prove the daemon reads LABELS and never
//              row numbers.
//   nopicker   swallow `/model` and draw nothing: the abort path.
//
// Frames CLEAR the screen the way a real TUI does, which matters for more than
// tidiness: `paneReadyForInput` refuses to release a queued send while selector
// rows are drawn near the bottom, so a stub that left its old rows on screen
// would wedge the next thing the daemon tried to type — and the wedge would be
// an artefact of the stub, not of the daemon.

const rows = JSON.parse(process.argv[2] || '[]');
const mode = process.argv[3] || 'normal';

let cursor = rows.findIndex((r) => r.current);
if (cursor < 0) cursor = 0;
let open = false;
let typed = '';

function w(s) { process.stdout.write(s); }

const CLEAR = '\u001b[2J\u001b[H';
function caret() { w('\n❯ \n'); }

function draw() {
  const out = [CLEAR, '   Select model',
    '   Switch between Claude models. Your pick becomes the default for new sessions.', ''];
  rows.forEach((r, i) => {
    const mark = i === cursor ? '   ❯' : '    ';
    const check = r.current ? ' ✔' : '';
    out.push(`${mark} ${i + 1}. ${r.label}${check}          ${r.desc || 'a model'}`);
  });
  out.push('', '   Enter to set as default · s to use this session only · Esc to cancel', '');
  w(out.join('\n'));
}

process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', (buf) => {
  const s = buf.toString('utf8');
  if (open) {
    // Arrow keys arrive as CSI sequences; a bare ESC is the cancel.
    if (s.includes('[A') || s.includes('OA')) { cursor = Math.max(0, cursor - 1); draw(); return; }
    if (s.includes('[B') || s.includes('OB')) { cursor = Math.min(rows.length - 1, cursor + 1); draw(); return; }
    if (s.includes('s')) {
      open = false;
      w(`${CLEAR}  ⎿  Set model to ${rows[cursor].label} for this session only\n`);
      caret();
      return;
    }
    if (s.includes('\u001b')) { open = false; w(`${CLEAR}  cancelled\n`); caret(); return; }
    return;
  }
  for (const ch of s) {
    if (ch === '\r' || ch === '\n') {
      if (typed.trim() === '/model' && mode !== 'nopicker') { open = true; draw(); }
      else caret();
      typed = '';
    } else {
      typed += ch;
    }
  }
});

caret();
