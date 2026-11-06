'use strict';
// Fixture-driven regression tests for lib/pane.js, fed REAL captured panes
// (test/fixtures/prompts/, see that dir's README). Captured from a throwaway
// `claude` session on Claude Code 2.1.227, 2026-08-10. These pin the pane-scrape
// fixes made after the 2026-08 audit found prompts vanishing or mislabelled at
// certain widths and around progress rows.
//
// The fusion layer (lib/ask.js) is what makes AskUserQuestion labels
// width-stable; here we only assert what the pane path alone can and cannot do.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const {
  detectPrompt, parseStatusLine, promptFingerprint, parseModelPicker, modelPickerShown,
} = require('../lib/pane');

const DIR = path.join(__dirname, 'fixtures', 'prompts');
const load = (name) => fs.readFileSync(path.join(DIR, name), 'utf8').split('\n');

test('trust dialog: detected, and the question is the safety line, not the OSC-8 link', () => {
  const p = detectPrompt(load('trust-dialog-80.txt'));
  assert.ok(p, 'trust dialog should be detected');
  assert.equal(p.options.length, 2);
  assert.match(p.question, /trust/i);
  assert.doesNotMatch(p.question, /Security guide/i);
  assert.equal(p.multiSelect, false);
});

test('plan approval with a background task on screen is still detected', () => {
  // The regression: a progress/spinner row under the option run used to read as
  // chrome and null the whole prompt. This fixture has a plan body ABOVE the run
  // and a background task running.
  const p = detectPrompt(load('plan-approval-with-task-80.txt'));
  assert.ok(p, 'plan approval should survive a background task row');
  assert.equal(p.options.length, 3);
  assert.match(p.question, /proceed|ready/i);
});

test('plan approval above a numbered plan body: run stops at option 1', () => {
  const p = detectPrompt(load('plan-approval-80.txt'));
  assert.ok(p);
  assert.equal(p.options.length, 3, 'exactly the 3 dialog options, not the plan steps');
  assert.equal(p.options[0].number, 1);
});

// (A real permission dialog is covered by the hand-verified inline fixture in
//  pane.test.js; the captured attempt mistimed onto the composer instead.)

test('multiSelect dialog: checkboxes surface as multiSelect', () => {
  const p = detectPrompt(load('ask-multi-80.txt'));
  assert.ok(p);
  assert.equal(p.multiSelect, true);
  assert.ok(p.options.every((o) => typeof o.checked === 'boolean' || o.number >= 5),
    'the four real options carry checkbox state');
});

test('two-question dialog exposes both tab headers', () => {
  const p1 = detectPrompt(load('ask-2q-tab1-80.txt'));
  assert.ok(p1);
  assert.ok(Array.isArray(p1.headers), 'headers must be returned for a multi-question dialog');
  const labels = p1.headers.map((h) => h.label);
  assert.deepEqual(labels, ['Database', 'Cache']);
  assert.match(p1.question, /database/i, 'question 1 is on screen');
  // Both header checkboxes are ☐ (unanswered) on the first question.
  assert.ok(p1.headers.every((h) => h.checked === false));
});

test('the multi-question review screen is a plain Submit/Cancel prompt', () => {
  // After both questions are answered the TUI shows a review with ☒/☒ headers.
  const p = detectPrompt(load('ask-review-80.txt'));
  assert.ok(p);
  assert.match(p.question, /submit/i);
  assert.equal(p.options.length, 2);
  // Both questions now answered → both header boxes checked.
  assert.ok(p.headers && p.headers.every((h) => h.checked === true));
});

test('wrapped labels: both widths detect the same STRUCTURE (but not the same labels)', () => {
  const wide = detectPrompt(load('ask-wrapped-desc-80.txt'));
  const narrow = detectPrompt(load('ask-wrapped-desc-46.txt'));
  assert.ok(wide && narrow, 'both widths detect');
  assert.equal(wide.options.length, 5);
  assert.equal(narrow.options.length, 5);
  assert.equal(wide.options.filter((o) => o.selected).length, 1);
  assert.equal(narrow.options.filter((o) => o.selected).length, 1);
  assert.match(wide.question, /deployment strategy/i);

  // The point of the fusion layer: pane-only labels DIFFER across widths because
  // a wrapped label's continuation line is dropped, so the pane fingerprint is
  // width-unstable. This assertion documents the defect the hook fusion exists to
  // fix — if these ever become equal by a pane-only change, revisit lib/ask.js.
  const wf = promptFingerprint(wide);
  const nf = promptFingerprint(narrow);
  assert.notEqual(wf, nf, 'pane-only fingerprints differ by width (fusion fixes this)');
});

test('a tall single-question dialog at a narrow width still detects — option 1 is not dropped', () => {
  // 3 options with wrapping descriptions run past 24 rows at 64 cols. The old
  // fixed `lastContent - 24` lookback put option 1 outside the window, so the
  // run started at option 2, the 1..n contiguity check failed, and the whole
  // prompt was discarded — served to the owner only as the degraded "answer on
  // the Screen tab" card for an ordinary single question (2026-08-11).
  const p = detectPrompt(load('ask-tall-desc-64.txt'));
  assert.ok(p, 'the tall narrow dialog must be detected');
  assert.equal(p.options[0].number, 1, 'the run starts at option 1');
  assert.ok(p.options.length >= 3, 'all of the real options are present');
  assert.match(p.question, /approach/i, 'the question above the run is read, not lost past the window');
  assert.equal(p.options.filter((o) => o.selected).length, 1, 'exactly one caret');
});

test('parseStatusLine reads a single-word mode (manual)', () => {
  const s = parseStatusLine(load('statusline-manual-80.txt'));
  assert.equal(s.mode, 'manual');
});

test('parseStatusLine reads the two-word accept-edits mode', () => {
  // Real captured line 2026-08-10; "accept edits on" has no "mode" word, which
  // the old single-word regex could not read.
  const s = parseStatusLine(['  [fixcap1] Fable 5', '  ⏵⏵ accept edits on (shift+tab to cycle) · ← for agents']);
  assert.equal(s.mode, 'accept edits');
});

test('parseStatusLine extracts ctx% AND the real branch (was swallowed into branch)', () => {
  // huginn-statusline.sh: "[sess] Model · ctx N% · branch ~N · ⚠ N sessions…".
  // The old regex put "ctx N%" in `branch` and lost the git branch entirely.
  const s = parseStatusLine(['[jotter] Fable 5 · ctx 42% · main ~3 · ⚠ 2 sessions in this tree']);
  assert.equal(s.model, 'Fable 5');
  assert.equal(s.contextPercent, 42);
  assert.equal(s.branch, 'main', 'the ~3 dirty count is stripped and the branch recovered');
});

test('parseStatusLine tolerates a line with no ctx and no branch', () => {
  const s = parseStatusLine(['[x] Opus 4.8']);
  assert.equal(s.model, 'Opus 4.8');
  assert.equal(s.contextPercent, null);
  assert.equal(s.branch, null);
});

// ---- Claude Code 2.1.296 captures (2026-10-10) ------------------------------

test('2.1.296 picker: versioned labels, several rows per family, newest first', () => {
  const rows = parseModelPicker(load('model-picker-v2-80.txt'));
  assert.ok(rows, 'the picker still parses');
  // The ladder takes the FIRST row of a family, so the newest must come first.
  const first = (f) => rows.find((r) => r.family === f).label;
  assert.equal(first('opus'), 'Opus 5.5');
  assert.equal(first('fable'), 'Fable 5.1');
  assert.equal(first('sonnet'), 'Sonnet 5.5');
  assert.equal(first('haiku'), 'Haiku 5.5');
  assert.equal(rows.find((r) => r.current).label, 'Haiku 5.5');
});

test('2.1.296 `Switch model?` confirmation reads as a dialog naming its target', () => {
  const p = detectPrompt(load('switch-model-confirm-80.txt'));
  assert.equal(p.question, 'Switch model?');
  assert.equal(p.options[0].label, 'Yes, switch to Sonnet 5.5');
});

test('2.1.296 trust dialog (no option numbers, "No, exit" first) is carded as UNNUMBERED', () => {
  // /answer walks it with arrow keys; the send gate (typing.test.js) still holds
  // every send off it, because its default destroys the session.
  const p = detectPrompt(load('trust-dialog-unnumbered-80.txt'));
  assert.equal(p.unnumbered, true);
  assert.match(p.question, /Is this a project you created or one you trust\?/);
  assert.deepEqual(p.options.map((o) => [o.label, o.selected]), [['No, exit', true], ['Yes, I trust this folder', false]]);
});

test('the unnumbered detector admits ONLY the trust dialog: two look-alike rows without its question are not a card', () => {
  const lines = ['Some prose.', '', ' ❯ No, exit', '   Yes, I trust this folder', '', ' Enter to confirm · Esc to cancel'];
  assert.equal(detectPrompt(lines), null);
});

test('toggling boxes in the real multi-select capture does not change its fingerprint', () => {
  // The body now feeds the fingerprint, and the tab strip above this question
  // flips ☐ -> ☒ as soon as anything is ticked. That mark is answer STATE: a
  // person who half-answered in tmux must still be able to tap the same card.
  const before = detectPrompt(load('ask-multi-80.txt'));
  const after = detectPrompt(load('ask-multi-toggled-80.txt'));
  assert.ok(before && after);
  assert.equal(promptFingerprint(after), promptFingerprint(before));
});

test('every live dialog capture carries a body digest', () => {
  for (const f of ['plan-approval-80.txt', 'plan-approval-v2-80.txt', 'plan-approval-with-task-80.txt',
    'switch-model-confirm-80.txt', 'trust-dialog-80.txt', 'trust-dialog-unnumbered-80.txt',
    'fable-consent-80.txt', 'ask-simple-v2-80.txt']) {
    const p = detectPrompt(load(f));
    assert.match(String(p && p.bodyDigest), /^[0-9a-f]{12}$/, f);
  }
});

// ---- Claude Code 2.1.306 captures (2026-10-23) ----------------------------------

test('2.1.306: the Bash permission dialog, the plan approval with its feedback row, a three-question ask with previews', () => {
  const fx = (f) => fs.readFileSync(path.join(__dirname, 'fixtures', 'prompts', f), 'utf8').split('\n');
  const bash = detectPrompt(fx('bash-permission-80.txt'));
  assert.ok(bash, 'the Bash dialog is a prompt');
  assert.equal(bash.options.length, 4);
  assert.equal(bash.options[0].label, 'Yes');
  assert.equal(bash.options[3].label, 'No');
  assert.match(bash.options[1].label, /^Yes, and always allow access to /);
  assert.match(bash.options[2].label, /switch to auto mode/);
  assert.equal(bash.multiSelect, false);
  assert.ok(bash.bodyDigest, 'the command is part of the dialog\'s identity');

  const plan = detectPrompt(fx('plan-approval-v3-80.txt'));
  assert.ok(plan);
  assert.deepEqual(plan.options.map((o) => o.label), ['Yes, and use auto mode', 'Yes, manually approve edits', 'Tell Claude what to change']);
  assert.equal(plan.options[0].selected, true);
  const typed = detectPrompt(fx('plan-approval-feedback-typed-80.txt'));
  assert.ok(typed, 'the dialog with words typed into row 3 is still a prompt');
  assert.equal(typed.options[2].selected, true);

  const ask = detectPrompt(fx('ask-3q-preview-80.txt'));
  assert.ok(ask, 'the preview layout is a prompt');
  assert.equal(ask.options.length, 2, 'the TUI\'s "Chat about this" row is unnumbered on this build');
  assert.match(ask.options[0].label, /^PostgreSQL/);
  assert.match(ask.options[1].label, /^SQLite/);
  assert.equal(ask.question, 'Which database should the service use?');
  assert.deepEqual(ask.headers.map((h) => [h.label, h.checked]), [['Database', false], ['Monitors', false], ['Name', false]]);
});

// ---- Claude Code 2.1.316: the picker in a short pane (2026-11-05) ----------------
//
// At 80x24 the `/model` picker draws a WINDOW of two rows, not the list, and
// opens it on the session's current model. The parser read anything but a run
// numbered from 1 as "no picker", the ladder left the picker open, and four
// project sessions sat behind it for a week with every message from the apps
// queued as a dialog. These are the real frames (model-picker-window-*.txt).

/** The frames file: `== <what was pressed>` heads each captured frame. */
function frames() {
  const out = new Map();
  let label = null;
  for (const line of load('model-picker-window-frames-80.txt')) {
    const m = /^== (.*)$/.exec(line);
    if (m) { label = m[1]; out.set(label, []); } else if (label) out.get(label).push(line);
  }
  return out;
}
const brief = (rows) => rows.map((r) => `${r.n}:${r.label}${r.cursor ? '❯' : ''}${r.current ? '✔' : ''}`);

test('2.1.316 short pane, just opened: rows 1-2 of 13, and the count of the rows hidden below', () => {
  const rows = parseModelPicker(load('model-picker-window-80x24.txt'));
  assert.ok(rows, 'the window is read');
  assert.deepEqual(brief(rows), ['1:Default (recommended)', '2:Opus 5.5❯✔']);
  assert.equal(rows.more, 11, '`… +11 models` says how many rows are below the window');
  assert.equal(rows.total, 13, 'so the list is 13 rows long');
  assert.equal(modelPickerShown(load('model-picker-window-80x24.txt')), true);
});

test('2.1.316 short pane, scrolled: an `↑ N.` row over the highlight is a row, and the run need not start at 1', () => {
  const f = frames();
  const down1 = parseModelPicker(f.get('after Down x1'));
  assert.ok(down1, 'a window opened on row 2 is still the picker');
  assert.deepEqual(brief(down1), ['2:Opus 5.5✔', '3:Fable 5.1❯']);
  assert.equal(down1.more, 10);
  assert.equal(down1.total, 13);
  // Two-digit numbers, and the singular `… +1 model`.
  const at12 = parseModelPicker(f.get('cursor 12'));
  assert.deepEqual(brief(at12), ['11:Opus 4.7', '12:Opus 4.6❯']);
  assert.equal(at12.more, 1, '`… +1 model`, singular');
  assert.equal(at12.total, 13);
});

test('2.1.316 short pane, last row: no `… +` line, nothing hidden below', () => {
  const last = parseModelPicker(frames().get('cursor 13 (last)'));
  assert.deepEqual(brief(last), ['12:Opus 4.6', '13:Sonnet 4.6❯']);
  assert.equal(last.more, 0);
  assert.equal(last.total, 13);
});

test('2.1.316 short pane, wrapped to the top: `❯ 1.` over a `↓ 2.` row', () => {
  const top = parseModelPicker(frames().get('Down from last (wrap)'));
  assert.ok(top, 'the `↓` neighbour is a row, not the end of the run');
  assert.deepEqual(brief(top), ['1:Default (recommended)❯', '2:Opus 5.5✔']);
  assert.equal(top.more, 11);
});

test('2.1.316 short pane: every captured frame is two contiguous rows, one highlight, a 13-row list', () => {
  for (const [label, lines] of frames()) {
    const rows = parseModelPicker(lines);
    assert.ok(rows, `${label}: read`);
    assert.equal(rows.length, 2, `${label}: a two-row window`);
    assert.equal(rows[1].n, rows[0].n + 1, `${label}: contiguous`);
    assert.equal(rows.filter((r) => r.cursor).length, 1, `${label}: one highlight`);
    assert.equal(rows.total, 13, `${label}: the same list`);
    assert.equal(modelPickerShown(lines), true, `${label}: on screen`);
  }
});

test('a description that WRAPS is skipped, not taken for the end of the run (the live wedge, 80 columns)', () => {
  // ASSEMBLED (see the fixtures README): the highlighted row, its wrapped line
  // and `… +2 models` are verbatim from the live pane that stayed open a week.
  const rows = parseModelPicker(load('model-picker-window-wrapped-80.txt'));
  assert.ok(rows, 'the wrapped window is read');
  assert.deepEqual(brief(rows), ['2:Opus (1M context)', '3:Fable❯✔']);
  assert.deepEqual(rows.map((r) => r.family), ['opus', 'fable']);
  assert.equal(rows.more, 2, 'the `… +2 models` under the wrapped line is still seen');
  assert.equal(rows.total, 5);
});

test('2.1.296 tall window: the rows after a wrapped description are read too, and `… +3 models`', () => {
  // Before, the run stopped at row 9's wrapped line, and `↓ 10.` and the count
  // under it were never read.
  const rows = parseModelPicker(load('model-picker-v2-80.txt'));
  assert.equal(rows.length, 10);
  assert.equal(rows[9].label, 'Opus 4.8');
  assert.equal(rows[8].label, 'Fable 5', 'the wrapped row keeps its own label');
  assert.equal(rows.more, 3);
  assert.equal(rows.total, 13);
});

test('the whole list still parses to the same rows, and says nothing is hidden', () => {
  const rows = parseModelPicker(load('model-picker-80.txt'));
  assert.deepEqual(rows.map((r) => ({ ...r })), [
    { n: 1, label: 'Default (recommended)', family: null, current: false, cursor: false },
    { n: 2, label: 'Opus (1M context)', family: 'opus', current: false, cursor: false },
    { n: 3, label: 'Fable', family: 'fable', current: false, cursor: false },
    { n: 4, label: 'Sonnet', family: 'sonnet', current: true, cursor: true },
    { n: 5, label: 'Haiku', family: 'haiku', current: false, cursor: false },
  ]);
  assert.equal(rows.more, 0);
  assert.equal(rows.total, 5);
  assert.deepEqual(Object.keys(rows), ['0', '1', '2', '3', '4'], 'the counts are not rows: callers that map or spread the array see five');
});
