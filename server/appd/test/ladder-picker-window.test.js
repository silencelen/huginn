'use strict';
// The usage ladder against the `/model` picker a SHORT pane draws, and its
// promise never to leave a picker open.
//
// THE FAILURE (Claude Code 2.1.316, 2026-10-29 → 11-05): at 80x24 — every
// session no client has open, every project member appd spawns — the picker is
// a two-row window onto the list, opened on the session's current model. The
// parser read a window that did not start at row 1 as no picker at all; the
// ladder returned "the model picker never appeared" with nothing pressed, and
// the picker stayed open. Four project sessions sat behind it for a week, and
// every message the apps sent waited behind the dialog until somebody pressed
// Escape on the Screen tab. A window that did start at row 1 parsed, but the
// row to set was hidden under `… +N models`, so the move never happened.
//
// These drive the daemon's REAL applyLadder (lifted, ladder-lift.js) against a
// stand-in Claude Code drawn in-process on a fake clock: exact about time,
// which is what "a picker that opens after the wait" needs, and exact about
// every key that reached the pane, which is what "nothing pressed" needs. The
// stand-in draws the picker the way 2.1.316 does (fixtures/picker-frame.js,
// from the captures): two rows around the highlight, `↑`/`↓`, `… +N models`,
// descriptions wrapped at 80 columns, cyclic Up and Down.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const pane = require('../lib/pane');
const typing = require('../lib/typing');
const headroomLib = require('../lib/headroom');
const { lift } = require('./ladder-lift');
const { pickerFrame, listOn } = require('./fixtures/picker-frame');

const RULE = '─'.repeat(80);
const SID = 'sid-dev';

/**
 * A stand-in Claude Code: a composer, the `/model` picker, the `Switch model?`
 * confirmation. Keys arrive as tmux would deliver them (see tmuxOf).
 *
 * opts
 *   visible     rows the picker draws (2 = an 80x24 pane, 0 = the whole list)
 *   lateMs      the picker opens this long after `/model` (a CPU-starved session)
 *   nopicker    `/model` opens nothing
 *   history     lines drawn above the composer (an answer on screen)
 *   confirm     `s` opens `Switch model?` first; `confirmLabel` names another
 *               model in it; `escBack` makes Escape on it go back to the picker,
 *               the way its own "No, go back" does
 *   keepOpen    the switch is recorded but the picker stays drawn
 *   lagMs       the highlight is drawn this long after it moves (a late redraw)
 */
class FakeClaude {
  constructor(rows, opts = {}) {
    this.rows = rows;
    this.opts = { visible: 2, ...opts };
    this.t = 0;
    this.keys = [];
    this.composer = '';
    this.picker = false;
    this.opensAt = null;
    this.confirming = false;
    this.switched = null;          // { label, id, at }
    this.setDefault = false;       // Enter pressed ON the picker: "set as default"
    this.cursor = Math.max(0, rows.findIndex((r) => r.current));
    this.cursorLog = [{ at: 0, cursor: this.cursor }];
  }

  open() {
    if (!this.picker && this.opensAt != null && this.t >= this.opensAt) { this.picker = true; this.opensAt = null; }
    return this.picker;
  }

  /** The highlight as DRAWN: where it was `lagMs` ago. */
  drawnCursor() {
    const seen = this.t - (this.opts.lagMs || 0);
    let c = this.cursorLog[0].cursor;
    for (const e of this.cursorLog) if (e.at <= seen) c = e.cursor;
    return c;
  }

  move(by) {
    const n = this.rows.length;
    this.cursor = (this.cursor + by + n) % n;
    this.cursorLog.push({ at: this.t, cursor: this.cursor });
  }

  screen() {
    const lines = [...(this.opts.history || [])];
    if (this.confirming) {
      const label = this.opts.confirmLabel || this.rows[this.cursor].label;
      lines.push(RULE, '  Switch model?', '  Your next response will be slower and use more tokens', '',
        `  This conversation is cached for the current model. Switching to ${label}`,
        '  means the full history gets re-read on your next message.', '',
        `  ❯ 1. Yes, switch to ${label}`, '    2. No, go back', '');
    } else if (this.open()) {
      lines.push(...pickerFrame(this.rows, this.drawnCursor(), { visible: this.opts.visible }));
    } else {
      lines.push('', RULE, `❯ ${this.composer}`, RULE, '  [dev] Fable 5.1 · ctx 4%');
    }
    return lines;
  }

  doSwitch() {
    const r = this.rows[this.cursor];
    this.switched = { label: r.label, id: r.id || r.label, at: this.t };
    this.confirming = false;
    if (!this.opts.keepOpen) this.picker = false;
  }

  type(text) {
    this.keys.push(text);
    if (this.confirming) return;
    if (this.open()) {
      if (text !== 's') return;
      if (this.opts.confirm) { this.confirming = true; this.picker = false; } else this.doSwitch();
      return;
    }
    this.composer += text;
  }

  key(k) {
    this.keys.push(k);
    if (this.confirming) {
      if (k === 'Enter') this.doSwitch();
      if (k === 'Escape') { this.confirming = false; if (this.opts.escBack) this.picker = true; }
      return;
    }
    if (this.open()) {
      if (k === 'Up') this.move(-1);
      if (k === 'Down') this.move(1);
      if (k === 'Escape') this.picker = false;
      if (k === 'Enter') this.setDefault = true;
      return;
    }
    if (this.opensAt != null) return;                    // opening: the keys go nowhere
    if (k === 'C-u') this.composer = '';
    if (k === 'Enter') {
      if (this.composer.trim() === '/model' && !this.opts.nopicker) {
        if (this.opts.lateMs) this.opensAt = this.t + this.opts.lateMs;
        else this.picker = true;
      }
      this.composer = '';
    }
  }

  /** What hooks/huginn-modelswitch-hook would have recorded. */
  hookRecord() {
    return this.switched ? { v: 1, sessionId: SID, to: this.switched.id, ts: this.switched.at } : null;
  }

  /** Every key after the `/model` Enter: what the clean-up and the walk pressed. */
  after() {
    const i = this.keys.indexOf('Enter');
    return i < 0 ? [] : this.keys.slice(i + 1);
  }
}

/** tmux as the daemon calls it, answered by the stand-in. Every call costs 5 ms. */
function tmuxOf(fake) {
  return async (cmd, args) => {
    fake.t += 5;
    if (cmd !== 'tmux') return { err: new Error(`not tmux: ${cmd}`), stdout: '', stderr: '' };
    if (args[0] === 'capture-pane') return { err: null, stdout: `${fake.screen().join('\n')}\n`, stderr: '' };
    if (args[0] === 'send-keys') {
      const rest = args.slice(3);                       // after `-t <target>`
      if (rest[0] === '-l') fake.type(rest[rest.length - 1]); else for (const k of rest) fake.key(k);
    }
    return { err: null, stdout: '', stderr: '' };
  };
}

/** The daemon's applyLadder, wired to the stand-in. */
function ladderFor(fake) {
  const run = tmuxOf(fake);
  const L = lift(['applyLadder', 'paneLines', 'SUBMIT_BEAT_MS', 'PICKER_WAIT_MS', 'LADDER_FEEDBACK_MS', 'MAX_CURSOR_MOVES'], {
    run,
    sleep: async (ms) => { fake.t += ms; },
    Date: { now: () => fake.t },
    process: { env: {} },
    typing,
    headroomLib,
    parseModelPicker: pane.parseModelPicker,
    modelPickerShown: pane.modelPickerShown,
    detectPrompt: pane.detectPrompt,
    // The look before Enter is its own suite's (routes-send-guards); here the
    // Enter simply goes.
    enterAfterLook: async (name) => { await run('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']); return {}; },
    readSessionState: () => ({ sessionId: SID }),
    transcriptPath: () => null,
    transcriptSize: () => null,
    ladderSwitchRecorded: () => false,
    modelswitch: { readSwitch: () => fake.hookRecord() },
    STATE_DIR: '/nonexistent',
  }, { helpers: ['PICKER_LATE_MS', 'PICKER_MOVE_MS', 'PICKER_CLOSE_MS', 'PICKER_ESCAPES'] });
  return { ...L, constant: (n) => vm.runInContext(n, L.box) };
}

const arrows = (keys) => keys.filter((k) => k === 'Up' || k === 'Down');

// ---------------------------------------------------------------- reaching it

test('a row hidden BELOW the two-row window is reached and set for this session', async () => {
  // The capture exactly: opened on row 2, `  1.` / `❯ 2. Opus 5.5 ✔` / `… +11
  // models`, and Fable 5.1 under the fold. Before: "the picker offers no fable
  // row", Escape, no move — every Undo of a ladder move on such a pane.
  const fake = new FakeClaude(listOn('Opus 5.5'));
  const r = await ladderFor(fake).applyLadder('dev', 'fable');
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.delivery, 'confirmed');
  assert.equal(fake.switched.label, 'Fable 5.1');
  assert.deepEqual(fake.after(), ['Up', 'Down', 'Down', 's'], 'up to row 1, down to the first Fable, then s');
  assert.equal(fake.open(), false, 'and the picker is gone');
  assert.equal(fake.setDefault, false, 'Enter is never pressed on the picker: it rewrites the host default');
});

test('the NEWEST row of the family is set, not the one beside the highlight or the first one below it', async () => {
  // A session on Fable 5 (row 9): the window shows `↑ 8. Opus 5` over it — an
  // older Opus — and scanning down from it finds Opus 4.8. The row a whole list
  // has always given is Opus 5.5 (row 2), the first of the family. Before: the
  // window started at row 8, did not parse, and the picker was left open.
  const fake = new FakeClaude(listOn('Fable 5'), { confirm: true });
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(fake.switched.label, 'Opus 5.5');
  assert.deepEqual(arrows(fake.after()), [...Array(8).fill('Up'), 'Down']);
  assert.deepEqual(fake.after().slice(-2), ['s', 'Enter'], 'and `Switch model?` is confirmed on its Yes');
  assert.equal(fake.open(), false);
});

test('a picker that redraws late is walked by what it SHOWS, and sets the row it showed', async () => {
  // Every key waits for the highlight to move before the next one. A fixed
  // pause read the old frame, pressed Up again from a highlight that had
  // already moved, and kept going round the wrap until a stale frame showed the
  // target: `s` set Opus 4.6, not the Opus 5.5 on screen, and the move read as
  // confirmed because it was the same family.
  const fake = new FakeClaude(listOn('Fable 5.1'), { visible: 0, lagMs: 500 });
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(fake.switched.label, 'Opus 5.5', 'the row the pane showed when `s` went in');
  assert.deepEqual(arrows(fake.after()), ['Up']);
});

test('a family the list does not offer is looked for down the whole list, then given up with Escape', async () => {
  const rows = [
    { label: 'Default (recommended)', desc: 'Fable 5.1 · Most capable' },
    { label: 'Fable 5.1', current: true, desc: 'For your toughest challenges' },
    { label: 'Haiku 5.5', desc: 'Fastest for quick answers' },
  ];
  const fake = new FakeClaude(rows);
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, false);
  assert.match(r.reason, /offers no opus row/);
  assert.deepEqual(arrows(fake.after()), ['Up', 'Down', 'Down', 'Down'], 'every row seen, and back round to row 1');
  assert.equal(fake.after().includes('s'), false);
  assert.equal(fake.open(), false, 'closed');
});

// ------------------------------------------------------------- closing it

test('a picker on screen that cannot be READ is closed with Escape, and the reason says so', async () => {
  // One row drawn: on screen (heading and footer), not walkable. Before: "never
  // appeared", nothing pressed, the picker left open.
  const fake = new FakeClaude(listOn('Fable 5.1'), { visible: 1 });
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, false);
  assert.equal(r.delivery, 'delivery_unconfirmed');
  assert.match(r.reason, /could not be read; it was closed with Escape/);
  assert.deepEqual(fake.after(), ['Escape'], 'one Escape, nothing else');
  fake.t += 60_000;
  assert.equal(fake.open(), false, 'and it stays closed');
});

test('a picker that opens AFTER the wait is closed, not left open', async () => {
  // The session drew its picker after the ladder had stopped waiting for it. It
  // is watched for a while longer, and closed when it comes.
  const probe = ladderFor(new FakeClaude(listOn('Fable 5.1')));
  const lateMs = probe.constant('PICKER_WAIT_MS') + 2_000;
  const fake = new FakeClaude(listOn('Fable 5.1'), { lateMs });
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, false);
  assert.match(r.reason, /appeared too late to be used; it was closed with Escape/);
  assert.deepEqual(fake.after(), ['Escape']);
  fake.t += 60_000;
  assert.equal(fake.open(), false, 'nobody is left to close it later');
});

test('no picker on screen: no key after the Enter, not even with one QUOTED in the answer above the composer', async () => {
  // Escape on a busy Claude interrupts the turn and on a dialog it answers it,
  // so nothing is pressed into a pane that does not show the picker itself. A
  // picture of one — heading, rows and footer in an answer, the composer under
  // it — is not the picker: before, it was walked, and `s` went into the box.
  const quoted = fs.readFileSync(path.join(__dirname, 'fixtures', 'prompts', 'model-picker-window-80x24.txt'), 'utf8')
    .replace(/\n$/, '').split('\n');
  const fake = new FakeClaude(listOn('Fable 5.1'), { nopicker: true, history: ['● It looks like this:', ...quoted] });
  const r = await ladderFor(fake).applyLadder('dev', 'opus');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'the model picker never appeared');
  assert.deepEqual(fake.after(), [], 'no Escape, no C-u, no arrow, no s');
  assert.equal(fake.composer, '', 'nothing typed into the box');
});

test('an Escape that only goes BACK to the picker is followed by another, so the picker is closed', async () => {
  // `Switch model?` names another model (the list moved under the walk): the
  // ladder refuses it with Escape. If that dialog's Escape is its own "No, go
  // back" (not measured; the stand-in assumes it), the session is back in the
  // picker — and the look after the clean-up sees it and closes it.
  const fake = new FakeClaude(listOn('Opus 5.5'), { visible: 0, confirm: true, confirmLabel: 'Fable 5', escBack: true });
  const r = await ladderFor(fake).applyLadder('dev', 'fable');
  assert.equal(r.ok, false);
  assert.match(r.reason, /names Fable 5, not Fable 5\.1/);
  assert.deepEqual(fake.after().slice(-3), ['Escape', 'C-u', 'Escape']);
  assert.equal(fake.open(), false, 'the picker it went back to is closed too');
  assert.equal(fake.switched, null);
});

test('a picker still drawn after the switch landed is closed as well', async () => {
  const fake = new FakeClaude(listOn('Opus 5.5'), { visible: 0, keepOpen: true });
  const r = await ladderFor(fake).applyLadder('dev', 'fable');
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(fake.switched.label, 'Fable 5.1');
  assert.equal(fake.after().filter((k) => k === 'Escape').length, 1);
  assert.equal(fake.open(), false);
});

test('the walk that cannot reach its row closes the picker and never presses s', async () => {
  // A highlight that does not move: the walk stops on the first key that
  // shows nothing, and the picker is not left open behind the failure.
  const fake = new FakeClaude(listOn('Opus 5.5'));
  fake.move = () => { };                               // the keys arrive, nothing moves
  const r = await ladderFor(fake).applyLadder('dev', 'fable');
  assert.equal(r.ok, false);
  assert.match(r.reason, /did not move on Up/);
  assert.equal(fake.after().includes('s'), false);
  assert.equal(fake.open(), false);
});
