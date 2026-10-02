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
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const LAG = Number(process.env.HG_FAKE_LAG_MS || 0);
const RULE = '─'.repeat(70);
let buf = process.env.HG_FAKE_CLAUDE_TYPED || '';
let inPaste = false;
let pending = '';

const rowsOf = (s) => {
  const rows = s.split('\n');
  return `❯ ${rows[0]}\n${rows.slice(1).map((r) => `  ${r}\n`).join('')}`;
};
function render() {
  process.stdout.write(`${RULE}\n${rowsOf(buf)}${RULE}\n`
    + '  [fake-bp] Fable 5.1 · ctx 4%\n  ⏵⏵ auto mode on (shift+tab to cycle)\n');
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
