#!/usr/bin/env node
'use strict';
/**
 * A stand-in for a RUNNING `claude` 2.1.30x that will not submit a prompt
 * carrying invisible characters — the half the other stand-ins do not model.
 *
 * Read from the 2.1.302 binary (the prompt submit, ~offset 267136545): when the
 * box holds characters its classifier removes, it removes them, puts the cleaned
 * text back in the box, shows `Removed N invisible character(s) · review and
 * press Enter to send`, and submits NOTHING. The next Enter submits the cleaned
 * text. And the renderer does not draw some of them at all (soft hyphen, ZWSP,
 * LRM/RLM, the U+2060 block, NEL, BEL): a capture never shows those, while
 * ZWNJ, ZWJ, CGJ and the variation selectors are in the capture.
 *
 * ⚠ ONE SIMPLIFICATION, and the tests must stay inside it: the real classifier
 * KEEPS some of these characters in context (a ZWJ inside an emoji sequence, a
 * ZWNJ in Persian or Hindi, VS16 after an emoji). This stand-in removes every
 * one of them, which is what the real one does in Latin text — the only text
 * the tests send it.
 *
 * Paste handling follows fake-claude-bp.js. Knobs (env):
 *   HG_FAKE_CLAUDE_OUT   file; one JSON-encoded line per submit
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const RULE = '─'.repeat(70);
// What 2.1.302 calls invisible (`k` in its classifier), minus TAB and LF.
const INVISIBLE = /[\u0000-\u0008\u000B-\u001F\u007F-\u009F\u00AD\u034F\u061C\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u2028-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\u{E0000}-\u{E0FFF}]/gu;
// Drawn by the renderer (so a capture holds them); the rest of the set is not.
const DRAWN = /[\u200C\u200D\u034F\uFE00-\uFE0F]/u;
let buf = '';
let inPaste = false;
let pending = '';
let notice = '';

const visible = (s) => s.replace(INVISIBLE, (c) => (DRAWN.test(c) ? c : ''));
const rowsOf = (s) => {
  const rows = visible(s).split('\n');
  return `❯ ${rows[0]}\n${rows.slice(1).map((r) => `  ${r}\n`).join('')}`;
};
function render() {
  process.stdout.write(`${notice ? `${notice}\n` : ''}${RULE}\n${rowsOf(buf)}${RULE}\n`
    + '  [fake-inv] Fable 5.1 · ctx 4%\n  ⏵⏵ auto mode on (shift+tab to cycle)\n');
}

function submit() {
  if (!buf.trim()) { buf = ''; return; }
  const cleaned = buf.replace(INVISIBLE, '');
  const removed = [...buf].length - [...cleaned].length;
  if (removed > 0) {
    // Refused: the cleaned text goes back in the box, and nothing is sent.
    buf = cleaned;
    notice = `Removed ${removed} invisible character${removed === 1 ? '' : 's'} · review and press Enter to send`;
    return;
  }
  notice = '';
  fs.appendFileSync(OUT, `${JSON.stringify(buf)}\n`);
  process.stdout.write(`\n${rowsOf(buf)}\n● submitted\n`);
  buf = '';
}

function handle(d) {
  pending += d;
  while (pending.length) {
    if (pending.startsWith('\x1b[200~')) { inPaste = true; pending = pending.slice(6); continue; }
    if (pending.startsWith('\x1b[201~')) { inPaste = false; pending = pending.slice(6); continue; }
    if (pending[0] === '\x1b' && pending.length < 6) return;   // a marker split across reads
    const ch = pending[0];
    pending = pending.slice(1);
    if (ch === '\u0015') { buf = ''; continue; }
    if ((ch === '\r' || ch === '\n') && !inPaste) { submit(); continue; }
    buf += ch === '\r' ? '\n' : ch;
  }
  render();
}

if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', handle);
process.stdout.write('\x1b[?2004h');
process.stdin.resume();
render();
