'use strict';
// Regression fixtures for FINDING L11 (2026-08 audit): a plan-approval dialog
// under an ordinary NUMBERED plan body is not detected, so the approval card
// vanishes from the phone and the desktop at once.
//
// This lives outside test/ for the same reason as answer-fingerprint.test.js:
// the assertions describe behaviour the detector does NOT have yet, and
// test/*.test.js is a shipping gate. Un-skip with the fix and move both files
// under test/.
//
// WHY THE CONTROLS MATTER MORE THAN THE FAILING CASE
//
// The failing assertion alone would be satisfied by a detector that returns a
// prompt for everything. The two controls below are what pin the actual
// property: the dialog is detectable on its own, and it is specifically a
// NUMBERED run inside the 24-line window that destroys it. A fix that passes the
// first test and breaks either control has not fixed anything.
//
// FIXTURES MUST BE CAPTURED, NOT WRITTEN. The first draft of this test used an
// invented box-drawn dialog and "reproduced" a failure that did not exist —
// Claude Code does not draw prompts in a box. The shape below matches live
// captures: indent-3 question, indent-3 caret run, plain footer line.

const { test } = require('node:test');
const assert = require('node:assert');
const { detectPrompt } = require('../lib/pane');

/** The approval dialog, with whatever plan body is passed above it. */
function pane(body) {
    return [
        '● Here is the plan:',
        '',
        ...body,
        '',
        '───────────────────────────────────────────────',
        '   Claude has written up a plan. Would you like to proceed?',
        '',
        '   ❯ 1. Yes, and auto-accept edits',
        '     2. Yes, and manually approve edits',
        '     3. No, keep planning',
        '',
        '   shift+tab to approve with this feedback',
    ];
}

const NUMBERED = Array.from({ length: 8 }, (_, i) => `   ${i + 1}. Step ${i + 1} of the plan`);
const DASHED = Array.from({ length: 8 }, (_, i) => `   - Step ${i + 1} of the plan`);

test('CONTROL: the approval dialog is detected when the plan is dash-bulleted', () => {
    const p = detectPrompt(pane(DASHED));
    assert.ok(p, 'the dialog itself must be detectable — if this fails the fixture is wrong, not the detector');
    assert.deepEqual(p.options.map((o) => o.number), [1, 2, 3]);
    assert.equal(p.options[0].selected, true);
});

test('CONTROL: it is also detected when a numbered plan sits outside the 24-line window', () => {
    const far = [...NUMBERED, ...Array.from({ length: 20 }, () => '   ordinary prose line')];
    const p = detectPrompt(pane(far));
    assert.ok(p, 'distance alone must restore detection — this is what proves the walk is the cause');
    assert.deepEqual(p.options.map((o) => o.number), [1, 2, 3]);
});

test('a numbered plan body must not swallow the approval dialog',
    { skip: 'FINDING L11 — not fixed yet' }, () => {
        // The step-2 walk treats 2+ leading spaces as an option description and
        // climbs past the indent-3 question, collecting the plan's own numbered
        // steps as options; the 1..n contiguity check then discards everything.
        // Permission dialogs escape only because their question is indent-1.
        const p = detectPrompt(pane(NUMBERED));
        assert.ok(p, 'a numbered plan is the DEFAULT shape of a Claude plan, not an edge case');
        assert.deepEqual(p.options.map((o) => o.number), [1, 2, 3]);
        assert.equal(p.question, 'Claude has written up a plan. Would you like to proceed?');
    });
