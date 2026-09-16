#!/usr/bin/env node
'use strict';
/**
 * A stand-in for `claude` that reproduces the ONE thing the startup race is
 * about: the seconds between `tmux new-session` returning and there being
 * anything in the pane that reads stdin.
 *
 * Every number and every phase here was measured on this host against Claude
 * Code 2.1.258, sampling a real pane every 30 ms from the instant new-session
 * returned (spike `race/`, 2026-09-15):
 *
 *   t+0.03 s  new-session returns. The pane is COMPLETELY EMPTY — 0 non-blank
 *             lines — and stays that way for two seconds.
 *   t+0.8 s   in a cwd whose settings produce warnings, plain console text
 *             scrolls past. No box, no caret, still nothing reading stdin.
 *   t+2.1 s   the whole TUI lands in ONE write: banner, box, caret, status
 *             lines. There is no in-between frame even at 30 ms.
 *
 * ⚠ AND INPUT THAT ARRIVED BEFORE THAT IS GONE. This is the part a `cat` pane
 * cannot model and the reason this file exists: a pty buffers what nobody has
 * read yet, so a `cat` would happily hand back a paste from two seconds ago and
 * the bug would not reproduce. The real thing discards it — a message pasted at
 * t+1.1 s left no composer text, no turn and no transcript record at all. So
 * bytes arriving before the composer are read and thrown away here too, into a
 * `.lost` sidecar the test can assert on.
 *
 * Knobs (env): HG_FAKE_CLAUDE_OUT       file to append submitted lines to
 *              HG_FAKE_CLAUDE_BOOT_MS   empty-pane phase
 *              HG_FAKE_CLAUDE_BANNER_MS console-text phase
 *              HG_FAKE_CLAUDE_TRUST     '1' to draw the trust dialog and never
 *                                       accept input, whatever is typed at it
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const BOOT_MS = Number(process.env.HG_FAKE_CLAUDE_BOOT_MS || 1200);
const BANNER_MS = Number(process.env.HG_FAKE_CLAUDE_BANNER_MS || 600);
const TRUST = process.env.HG_FAKE_CLAUDE_TRUST === '1';

/** The box, with the status lines UNDER it — the shape that matters. */
const RULE = '─'.repeat(70);
function drawComposer(typed = '') {
  process.stdout.write(`${RULE}\n❯ ${typed}\n${RULE}\n`
    + '  [fake] Fable 5.1 · ctx 4%\n'
    + '  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents\n');
}

/** The one dialog whose default is destructive. Drawn INSTEAD of a composer. */
function drawTrust() {
  process.stdout.write('\n Accessing workspace:\n\n'
    + ' Quick safety check: Is this a project you created or one you trust?\n\n'
    + ' ❯ 1. Yes, I trust this folder\n'
    + '   2. No, exit\n\n'
    + ' Enter to confirm · Esc to cancel\n');
}

let accepting = false;
let buf = '';

// Raw mode for the same reason the real TUI uses it: no kernel echo, and Enter
// arrives as the '\r' tmux actually sends rather than a cooked line.
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  if (!accepting) { fs.appendFileSync(`${OUT}.lost`, d); return; }
  buf += d;
  for (;;) {
    const i = buf.search(/[\r\n]/);
    if (i < 0) break;
    const line = buf.slice(0, i).replace(/\[20[01]~/g, '');
    buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    fs.appendFileSync(OUT, `${line}\n`);
    // What a submitted message looks like in the pane: it leaves the composer,
    // is echoed above it, and the box redraws empty underneath.
    process.stdout.write(`\n❯ ${line}\n\n● submitted\n`);
    drawComposer();
  }
});
process.stdin.resume();

setTimeout(() => {
  // Phase 2: console text. Deliberately caret-free — this is the frame that
  // looks like Claude is up and is not.
  process.stdout.write('Claude Code v2.1.258\n'
    + 'Permission allow rule (settings): a wildcard before the rest of the command\n'
    + 'matches more than it looks like it does.\n');
  setTimeout(() => {
    if (TRUST) { drawTrust(); return; }   // never accepting: a blind Enter here exits
    accepting = true;
    drawComposer();
  }, BANNER_MS);
}, BOOT_MS);
