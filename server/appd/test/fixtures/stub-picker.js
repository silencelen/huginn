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
// argv: <rows-json> [mode] [state-dir] [session]
//   rows-json  [{label, family?, desc?, current?}] — deliberately orderable by
//              the test, so a suite can prove the daemon reads LABELS and never
//              row numbers.
//   state-dir, session   where a completed switch is RECORDED, the way
//              hooks/huginn-modelswitch-hook does from PostModelSwitch on every
//              host that runs a current Claude Code; a row's `id` is the model
//              id written as `to`. Without them nothing is recorded.
//
// Modes — what a completed switch leaves behind:
//   normal     the `… for this session only` line on the pane, and the record.
//   nopicker   swallow `/model` and draw nothing: the abort path.
//   confirm    `s` opens the "Switch model?" cache-cost dialog first, the way
//              Claude Code 2.1.296 does whenever the prompt cache is warm
//              (captured live 2026-10-10); Enter on its default row confirms,
//              and then it ends like `normal`.
//   hookonly   the record and NO feedback line on the pane.
//   paneonly   the feedback line and NOTHING ELSE: words on a screen, which
//              is all a model's prose or a stale line can ever be.
//   transcript no hook record and no pane line — only the record Claude Code
//              itself writes into the transcript (env HG_STUB_TRANSCRIPT), a
//              `user` record whose whole content is the command's output
//              (measured on 2.1.296; a host without the hook).
//
// env HG_STUB_STALE=<label> draws an OLD `Set model to <label> for this session
// only` line above the confirm dialog — history left on screen by an earlier
// move, which is not this switch.
//
// Frames CLEAR the screen the way a real TUI does, which matters for more than
// tidiness: `paneReadyForInput` refuses to release a queued send while selector
// rows are drawn near the bottom, so a stub that left its old rows on screen
// would wedge the next thing the daemon tried to type — and the wedge would be
// an artefact of the stub, not of the daemon.

const fs = require('fs');
const path = require('path');

const rows = JSON.parse(process.argv[2] || '[]');
const mode = process.argv[3] || 'normal';
const stateDir = process.argv[4] || null;
const session = process.argv[5] || null;
const transcript = process.env.HG_STUB_TRANSCRIPT || null;
const stale = process.env.HG_STUB_STALE || null;

let cursor = rows.findIndex((r) => r.current);
if (cursor < 0) cursor = 0;
let open = false;
let confirming = false;
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

/** The PostModelSwitch hook's record of the switch. */
function recordHook() {
  if (!stateDir || !session) return;
  const dir = path.join(stateDir, '.modelswitch');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, session), JSON.stringify({
    v: 1, sessionId: `sid-${session}`, from: null, to: rows[cursor].id || rows[cursor].label,
    source: 'picker', ts: Date.now(),
  }));
}

/** Claude Code's own transcript record of the command's output. */
function recordTranscript() {
  if (!transcript) return;
  fs.appendFileSync(transcript, `${JSON.stringify({
    type: 'user',
    message: { role: 'user', content: `<local-command-stdout>Set model to \`${rows[cursor].label}\` for this session only</local-command-stdout>` },
    timestamp: new Date().toISOString(),
  })}\n`);
}

/** The switch has happened: leave behind what this mode says it leaves. */
function switched() {
  const line = mode === 'hookonly' || mode === 'transcript'
    ? '' : `  ⎿  Set model to ${rows[cursor].label} for this session only\n`;
  if (mode !== 'paneonly' && mode !== 'transcript') recordHook();
  if (mode === 'transcript') recordTranscript();
  w(`${CLEAR}${line}`);
  caret();
}

process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', (buf) => {
  const s = buf.toString('utf8');
  if (confirming) {
    if (s.includes('\r') || s.includes('\n')) {
      confirming = false;
      switched();
      return;
    }
    if (s.includes('\u001b')) { confirming = false; w(`${CLEAR}  cancelled\n`); caret(); return; }
    return;
  }
  if (open) {
    // Arrow keys arrive as CSI sequences; a bare ESC is the cancel.
    if (s.includes('[A') || s.includes('OA')) { cursor = Math.max(0, cursor - 1); draw(); return; }
    if (s.includes('[B') || s.includes('OB')) { cursor = Math.min(rows.length - 1, cursor + 1); draw(); return; }
    if (s.includes('s')) {
      open = false;
      if (mode === 'confirm') {
        confirming = true;
        const history = stale ? [`  ⎿  Set model to ${stale} for this session only`, ''] : [];
        w([CLEAR, ...history, '  Switch model?', '  Your next response will be slower and use more tokens',
          `  This conversation is cached for the current model. Switching to ${rows[cursor].label}`,
          '  means the full history gets re-read on your next message.', '',
          `  ❯ 1. Yes, switch to ${rows[cursor].label}`, '    2. No, go back', ''].join('\n'));
        return;
      }
      switched();
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
