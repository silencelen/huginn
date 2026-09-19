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
 * ⚠ AND "GONE" IS ONLY ONE OF THE TWO WAYS IT GOES WRONG. Re-swept 2026-09-17
 * against the same binary, reading the outcome from the transcript rather than
 * the pane, the pre-composer window has two bands and they behave nothing alike:
 *
 *   pasted ~2.0 s to ~0.85 s before the paint   the bytes SURVIVE. The TUI
 *                                               renders them into the composer
 *                                               the instant it paints and drops
 *                                               the `\r` — text in the box,
 *                                               unsent, no transcript record.
 *                                               6/6. THE OWNER'S BUG.
 *   pasted ~0.6 s to ~0.3 s before the paint     the bytes are gone, as above. 4/4.
 *
 * `HG_FAKE_CLAUDE_PREBUF` picks which band this stand-in models, because a fix
 * for one is not a fix for the other and a fixture that can only do 'lost' can
 * only test the half that was already understood.
 *
 * Knobs (env): HG_FAKE_CLAUDE_OUT       file to append submitted lines to
 *              HG_FAKE_CLAUDE_BOOT_MS   empty-pane phase
 *              HG_FAKE_CLAUDE_BANNER_MS console-text phase
 *              HG_FAKE_CLAUDE_TRUST     '1' to draw the trust dialog and never
 *                                       accept input, whatever is typed at it
 *              HG_FAKE_CLAUDE_PREBUF    'lost' (default) — pre-composer bytes are
 *                                       read and discarded into `<out>.lost`, and
 *                                       the composer then paints EMPTY. This is
 *                                       the band a settle wait can only time out
 *                                       on, and the one the re-paste recovery in
 *                                       `sendTextToPane` exists for: `.lost` holds
 *                                       the copy nobody could read and `<out>`
 *                                       must end up holding the message exactly
 *                                       ONCE, never twice.
 *                                       'stuck' — pre-composer bytes are HELD and
 *                                       rendered into the composer at paint time
 *                                       with every newline stripped, i.e. the
 *                                       message arrives and its Enter does not
 *              HG_FAKE_CLAUDE_BANNER_TEXT  what the caret-free console phase prints
 *                                       instead of the default warnings. A pane
 *                                       whose pre-composer text happens to END in
 *                                       a shell prompt terminator is the one shape
 *                                       the unmarked startup rule refuses to hold,
 *                                       so it is how a test can ask whether the
 *                                       MARK is doing the holding.
 *              HG_FAKE_CLAUDE_TYPED     text the composer already holds when it
 *                                       paints — a person who started typing while
 *                                       the paste was being swallowed. Recovery
 *                                       must not re-paste over it, so the daemon's
 *                                       'leave' branch needs a pane in this state
 *                                       to be tested against at all. Since
 *                                       2026-09-19 it is also how the DRAFT GUARD
 *                                       is tested: a box with somebody's words in
 *                                       it that a send must wait for, and that
 *                                       C-u (below) gives back.
 */
const fs = require('node:fs');

const OUT = process.env.HG_FAKE_CLAUDE_OUT || '/dev/null';
const BOOT_MS = Number(process.env.HG_FAKE_CLAUDE_BOOT_MS || 1200);
const BANNER_MS = Number(process.env.HG_FAKE_CLAUDE_BANNER_MS || 600);
const TRUST = process.env.HG_FAKE_CLAUDE_TRUST === '1';
const PREBUF = process.env.HG_FAKE_CLAUDE_PREBUF === 'stuck' ? 'stuck' : 'lost';
const TYPED = process.env.HG_FAKE_CLAUDE_TYPED || '';
/**
 * The dim inline SUGGESTION the real TUI offers in an EMPTY box (P-14) — the
 * last thing typed here, drawn in SGR-2 and taken with →. It is not typed and
 * nobody has agreed to it, but strip the escapes and it is byte-identical to a
 * draft, which is how `composerHoldsDraft` came to hold sends over one.
 */
const GHOST = process.env.HG_FAKE_CLAUDE_GHOST || '';

/** The box, with the status lines UNDER it — the shape that matters. */
const RULE = '─'.repeat(70);
function drawComposer(typed = '') {
  // A suggestion only ever shows in an empty box, and the first keystroke ends it.
  const body = typed || (GHOST ? `\u001B[2m${GHOST}\u001B[0m` : '');
  process.stdout.write(`${RULE}\n❯ ${body}\n${RULE}\n`
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
let held = '';   // 'stuck' mode only: bytes that arrived before the paint

const clean = (s) => String(s).replace(/\[20[01]~/g, '');

/**
 * ⚠ THE COMPOSER ECHOES WHAT IS IN IT, and it did not before.
 *
 * The real TUI redraws the box with the typed text the moment a paste lands —
 * which is the ONLY thing a "has the paste settled?" check can look at. A
 * stand-in that showed nothing until Enter made every settle wait run to its
 * bound, so the fixture would have reported the timing wrong in both directions.
 */
function render() { drawComposer(clean(buf)); }

// Raw mode for the same reason the real TUI uses it: no kernel echo, and Enter
// arrives as the '\r' tmux actually sends rather than a cooked line.
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  if (!accepting) {
    // 'stuck': the pty holds it and the TUI picks it up at paint time, minus the
    // newline. 'lost': nobody is reading, and the real thing discards it.
    if (PREBUF === 'stuck') { held += d; fs.appendFileSync(`${OUT}.held`, d); }
    else fs.appendFileSync(`${OUT}.lost`, d);
    return;
  }
  buf += d;
  // ⚠ CTRL-U CLEARS THE BOX, because a test needs a way to be the PERSON who
  // gives their draft back. The real TUI kills the line on C-u like every other
  // readline-shaped composer; without it a draft put here with
  // HG_FAKE_CLAUDE_TYPED can only ever be submitted or merged, and "held until
  // the draft is gone, then delivered" — the whole point of the draft guard —
  // has no second half to assert.
  if (buf.includes('\u0015')) {
    buf = buf.slice(buf.lastIndexOf('\u0015') + 1);
    render();
    return;
  }
  for (;;) {
    const i = buf.search(/[\r\n]/);
    if (i < 0) break;
    const line = clean(buf.slice(0, i));
    buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    fs.appendFileSync(OUT, `${line}\n`);
    // What a submitted message looks like in the pane: it leaves the composer,
    // is echoed above it, and the box redraws empty underneath.
    process.stdout.write(`\n❯ ${line}\n\n● submitted\n`);
  }
  render();
});
process.stdin.resume();

setTimeout(() => {
  // Phase 2: console text. Deliberately caret-free — this is the frame that
  // looks like Claude is up and is not.
  process.stdout.write(process.env.HG_FAKE_CLAUDE_BANNER_TEXT
    ? `${process.env.HG_FAKE_CLAUDE_BANNER_TEXT}\n`
    : 'Claude Code v2.1.258\n'
    + 'Permission allow rule (settings): a wildcard before the rest of the command\n'
    + 'matches more than it looks like it does.\n');
  setTimeout(() => {
    if (TRUST) { drawTrust(); return; }   // never accepting: a blind Enter here exits
    accepting = true;
    // ⚠ THE HELD BYTES ARRIVE, THE ENTER DOES NOT. Measured on the real
    // binary: a paste that beat the paint by ~1-2 s is rendered into the
    // composer at paint time with its newline gone, so the message is on
    // screen and no turn ever starts. That is the band the owner kept hitting.
    if (held) { buf = held.replace(/[\r\n]/g, ''); held = ''; }
    // Somebody was typing while the paste went nowhere. Whatever is in the box
    // at paint time is THEIRS, and a recovery that pastes over it turns a lost
    // message into a mangled one.
    else if (TYPED) buf = TYPED;
    render();
  }, BANNER_MS);
}, BOOT_MS);
