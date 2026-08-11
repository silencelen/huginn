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
const { detectPrompt, parseStatusLine, promptFingerprint } = require('../lib/pane');

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
  const s = parseStatusLine(['[jtyper] Fable 5 · ctx 42% · main ~3 · ⚠ 2 sessions in this tree']);
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
