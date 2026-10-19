#!/usr/bin/env node
'use strict';
/**
 * A stand-in for a RUNNING `claude` that honours bracketed paste the way the
 * real TUI does — the half `fake-claude.js` does not model (it strips the
 * markers and submits on any newline, because its subject is the startup race).
 *
 * Shapes taken from real captures (test/fixtures/prompts, SCROLLED_PANE in
 * typing.test.js) and from the 2026-10-02 breaker round that found the bugs
 * these knobs exist for:
 *
 *   - newlines INSIDE ESC[200~ … ESC[201~ stay in the composer as continuation
 *     rows indented by two; only a CR/LF OUTSIDE a paste submits
 *   - the box is a column-0 rule, `❯ ` + first row, the indented rows, a rule,
 *     then two status lines
 *   - a submitted message is echoed above the box as `❯ first row` with its
 *     later rows indented by two, the way Claude Code echoes a prompt
 *   - C-u clears the box (the person giving their draft back)
 *
 * Knobs (env): HG_FAKE_CLAUDE_OUT    file; one JSON-encoded line per submit
 *              HG_FAKE_CLAUDE_TYPED  what the box holds when it first paints
 *              HG_FAKE_LAG_MS        handle every stdin chunk this late, in
 *                                    order — an event loop stalled on a loaded
 *                                    host, NOT a pty that drops bytes
 *              HG_FAKE_DIALOG_FILE   when this file appears, a Bash permission
 *                                    dialog takes the screen the way a
 *                                    background subagent's prompt does — the
 *                                    box is hidden, NOT emptied. Enter answers
 *                                    it with its highlighted row (Yes), Esc
 *                                    declines; either is appended to
 *              HG_FAKE_DIALOG_OUT    ('APPROVED' / 'DENIED'), the file is
 *                                    removed, and the box comes back as it was.
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const LAG = Number(process.env.HG_FAKE_LAG_MS || 0);
const DIALOG_FILE = process.env.HG_FAKE_DIALOG_FILE || '';
const DIALOG_OUT = process.env.HG_FAKE_DIALOG_OUT || '/dev/null';
const RULE = '─'.repeat(70);
const CLEAR = '\x1b[2J\x1b[H';
let buf = process.env.HG_FAKE_CLAUDE_TYPED || '';
let inPaste = false;
let pending = '';
let dialog = false;

// The 2.1.296 shape (permhook.test.js): the dialog's rules at its edge, every
// content row one column in, the caret on Yes.
const DIALOG = [
  '● Bash(rm -rf /srv/cache)',
  RULE,
  ' Bash command',
  '╌'.repeat(70),
  ' rm -rf /srv/cache',
  '╌'.repeat(70),
  ' Do you want to proceed?',
  ' ❯ 1. Yes',
  "   2. Yes, and don't ask again for rm commands in /srv",
  '   3. No',
  ' Esc to cancel · Tab to amend',
];
function answerDialog(verdict) {
  dialog = false;
  fs.appendFileSync(DIALOG_OUT, `${verdict}\n`);
  try { fs.unlinkSync(DIALOG_FILE); } catch { /* the test removed it */ }
  process.stdout.write(CLEAR);
  render();
}

const rowsOf = (s) => {
  const rows = s.split('\n');
  return `❯ ${rows[0]}\n${rows.slice(1).map((r) => `  ${r}\n`).join('')}`;
};
function render() {
  process.stdout.write(`${RULE}\n${rowsOf(buf)}${RULE}\n`
    + '  [fake-bp] Fable 5.1 · ctx 4%\n  ⏵⏵ auto mode on (shift+tab to cycle)\n');
}

function handle(d) {
  if (dialog) {
    // A selector takes keys, not text: Enter picks the highlighted row.
    if (d.includes('\r') || d.includes('\n')) answerDialog('APPROVED');
    else if (d === '\x1b') answerDialog('DENIED');
    return;
  }
  pending += d;
  while (pending.length) {
    if (pending.startsWith('\x1b[200~')) { inPaste = true; pending = pending.slice(6); continue; }
    if (pending.startsWith('\x1b[201~')) { inPaste = false; pending = pending.slice(6); continue; }
    if (pending[0] === '\x1b' && pending.length < 6) return;   // a marker split across reads
    const ch = pending[0];
    pending = pending.slice(1);
    if (ch === '\u0015') { buf = ''; continue; }
    if ((ch === '\r' || ch === '\n') && !inPaste) {
      if (buf.trim()) {
        fs.appendFileSync(OUT, `${JSON.stringify(buf)}\n`);
        process.stdout.write(`\n${rowsOf(buf)}\n● submitted\n`);
      }
      buf = '';
      continue;
    }
    buf += ch === '\r' ? '\n' : ch;
  }
  render();
}

if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => (LAG > 0 ? setTimeout(() => handle(d), LAG) : handle(d)));
process.stdout.write('\x1b[?2004h');
process.stdin.resume();
render();
if (DIALOG_FILE) {
  setInterval(() => {
    if (dialog || !fs.existsSync(DIALOG_FILE)) return;
    dialog = true;
    process.stdout.write(`${CLEAR}${DIALOG.join('\n')}\n`);
  }, 10);
}
