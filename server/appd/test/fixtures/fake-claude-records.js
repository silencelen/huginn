#!/usr/bin/env node
'use strict';
/**
 * A stand-in for a RUNNING `claude` that also writes the one transcript record
 * the send queue reads after a delivery: the PROMPT, as a `user` record.
 *
 * The other stand-ins write what they were given to a sink file and leave the
 * transcript alone, so a queue test's transcript never grows — and two questions
 * the queue answers from the transcript could not be asked at all: whose record
 * is the one that just appeared (the 'human' drop rule), and has the turn the
 * delivery started been written yet (the turn gate after a delivery).
 *
 * The record has the shape Claude Code 2.1.30x gives a pasted message, which is
 * the shape it gives a typed one: `origin.kind: "human"`, `promptSource:
 * "typed"`. Paste handling follows fake-claude-bp.js: newlines inside
 * ESC[200~ … ESC[201~ stay in the box, a CR outside a paste submits, C-u clears.
 *
 * Knobs (env): HG_FAKE_CLAUDE_OUT        file; one JSON-encoded line per submit
 *              HG_FAKE_TRANSCRIPT        the transcript the record is appended to
 *              HG_FAKE_RECORD_DELAY_MS   0 (default): the record is on disk
 *                                        before the box redraws, i.e. before any
 *                                        capture can see the submit. More: it
 *                                        lands that much later — Claude Code
 *                                        flushes its transcript in batches, after
 *                                        any synchronous UserPromptSubmit hook.
 *              HG_FAKE_WRAP_CHARS        a prompt this long or longer is recorded
 *                                        the way Claude Code records a paste it
 *                                        collapsed in the box: `\n\n<pasted_content
 *                                        id="d842">\n…\n</pasted_content id="d842">\n`
 *                                        (the shape of 39 of 39 such records on the
 *                                        author's host). 0 (default): never.
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const TRANSCRIPT = process.env.HG_FAKE_TRANSCRIPT || '';
const DELAY = Number(process.env.HG_FAKE_RECORD_DELAY_MS || 0);
const WRAP = Number(process.env.HG_FAKE_WRAP_CHARS || 0);
const RULE = '─'.repeat(70);
let buf = '';
let inPaste = false;
let pending = '';

const rowsOf = (s) => {
  const rows = s.split('\n');
  return `❯ ${rows[0]}\n${rows.slice(1).map((r) => `  ${r}\n`).join('')}`;
};
function render() {
  process.stdout.write(`${RULE}\n${rowsOf(buf)}${RULE}\n`
    + '  [fake-rec] Fable 5.1 · ctx 4%\n  ⏵⏵ auto mode on (shift+tab to cycle)\n');
}

function record(text) {
  if (!TRANSCRIPT) return;
  const content = WRAP > 0 && text.length >= WRAP
    ? `\n\n<pasted_content id="d842">\n${text}\n</pasted_content id="d842">\n`
    : text;
  const line = `${JSON.stringify({
    type: 'user',
    message: { role: 'user', content },
    origin: { kind: 'human' },
    promptSource: 'typed',
    timestamp: new Date().toISOString(),
  })}\n`;
  if (DELAY > 0) setTimeout(() => fs.appendFileSync(TRANSCRIPT, line), DELAY);
  else fs.appendFileSync(TRANSCRIPT, line);
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
        record(buf);
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
process.stdin.on('data', handle);
process.stdout.write('\x1b[?2004h');
process.stdin.resume();
render();
