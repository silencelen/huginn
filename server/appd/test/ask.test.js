'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { parseAskSidecar, fuseAskPrompt, degradedAskCard, parsePlanSidecar, labelsMatch } = require('../lib/ask');
const { detectPrompt, promptFingerprint } = require('../lib/pane');

const DIR = path.join(__dirname, 'fixtures', 'prompts');
const pane = (name) => fs.readFileSync(path.join(DIR, name), 'utf8').split('\n');
const rawInput = (name) => JSON.parse(fs.readFileSync(path.join(DIR, name), 'utf8'));
// The sidecar envelope the hook writes around a tool_input.
const sidecarFor = (inputName, tool = 'AskUserQuestion') =>
  parseAskSidecar({ v: 1, tool, sessionId: 's', ts: 1786400000, input: rawInput(inputName) });

test('parseAskSidecar validates and clips', () => {
  assert.equal(parseAskSidecar(null), null);
  assert.equal(parseAskSidecar({ v: 2, input: { questions: [] } }), null, 'unknown version');
  assert.equal(parseAskSidecar({ v: 1, input: {} }), null, 'no questions');
  const s = sidecarFor('ask-simple-80.input.json');
  assert.ok(s);
  assert.equal(s.questions.length, 1);
  assert.deepEqual(s.questions[0].options.map((o) => o.label), ['Red', 'Green', 'Blue']);
});

test('labelsMatch handles truncation in both directions but not tiny collisions', () => {
  assert.ok(labelsMatch('Blue-green with an automated smoke-test', 'Blue-green with an automated smoke-test gate before cutting'));
  assert.ok(labelsMatch('Rolling update with the full pane', 'Rolling update'));   // pane longer (preview column)
  assert.ok(!labelsMatch('Red', 'Recreate'), 'a tiny label must match exactly');
  assert.ok(labelsMatch('Red', 'Red'));
});

test('fusion marries hook labels to the pane run and flags the TUI extras', () => {
  const p = detectPrompt(pane('ask-simple-80.txt'));
  assert.ok(p, 'pane detects');
  assert.equal(p.options.length, 5, 'Red/Green/Blue + Type something + Chat about this');
  const s = sidecarFor('ask-simple-80.input.json');
  const fused = fuseAskPrompt(p, s);
  assert.ok(fused, 'fusion should succeed');
  assert.equal(fused.prompt.source, 'hook');
  // The three real options carry hook labels and are not extras.
  assert.deepEqual(fused.prompt.options.slice(0, 3).map((o) => o.label), ['Red', 'Green', 'Blue']);
  assert.ok(fused.prompt.options.slice(0, 3).every((o) => !o.extra));
  // The trailing TUI rows are flagged.
  assert.ok(fused.prompt.options.slice(3).every((o) => o.extra === true));
  // Exactly one caret survives from the pane.
  assert.equal(fused.prompt.options.filter((o) => o.selected).length, 1);
});

test('HEADLINE: the same question fingerprints identically at 80 and 46 columns once fused', () => {
  const s = sidecarFor('ask-wrapped-desc.input.json');
  const wide = fuseAskPrompt(detectPrompt(pane('ask-wrapped-desc-80.txt')), s);
  const narrow = fuseAskPrompt(detectPrompt(pane('ask-wrapped-desc-46.txt')), s);
  assert.ok(wide && narrow, 'both widths fuse');
  const wf = promptFingerprint(wide.prompt);
  const nf = promptFingerprint(narrow.prompt);
  assert.equal(wf, nf, 'fusion must make the fingerprint width-stable (the whole point)');
  // And the full label is restored, not the width-truncated one.
  assert.match(wide.prompt.options[0].label, /the new pods$/, 'the wrapped tail is back');
  assert.ok(wide.prompt.options[0].description, 'descriptions come through');
});

test('fusion returns null when the sidecar does not match the pane run', () => {
  // A pane showing the color question, but a sidecar for the deployment question.
  const p = detectPrompt(pane('ask-simple-80.txt'));
  const wrong = sidecarFor('ask-wrapped-desc.input.json');
  assert.equal(fuseAskPrompt(p, wrong), null, 'mismatched labels -> pane-only');
});

test('degradedAskCard renders the first question, not answerable', () => {
  const s = sidecarFor('ask-wrapped-desc.input.json');
  const card = degradedAskCard(s, 'abc123abc123');
  assert.equal(card.answerable, false);
  assert.equal(card.fingerprint, 'abc123abc123');
  assert.ok(card.options.length >= 2);
  assert.equal(card.options[0].number, 1);
});

test('parsePlanSidecar carries the plan text the runtime actually ships', () => {
  const plan = parsePlanSidecar({ v: 1, tool: 'ExitPlanMode', ts: 1786400000, input: rawInput('exitplanmode.input.json') });
  assert.ok(plan);
  assert.ok(typeof plan.plan === 'string' && plan.plan.length > 0, 'ExitPlanMode input DOES carry plan text on 2.1.227');
});

// ---- the 2.1.306 schema: previews, and the gated text / number kinds -----------

test('parseAskSidecar keeps option previews and marks every question with its kind', () => {
  const s = sidecarFor('ask-3q-preview.input.json');
  assert.ok(s);
  assert.equal(s.questions.length, 3);
  assert.deepEqual(s.questions.map((q) => q.kind), ['choice', 'choice', 'choice'], 'no kind given means choice');
  assert.equal(s.questions[0].options[0].preview, 'CREATE TABLE users (id serial);');
  assert.equal(s.questions[0].options[1].preview, 'CREATE TABLE users (id integer);');
  assert.equal(s.questions[1].multiSelect, true);
  assert.equal(s.questions[1].options[0].preview, undefined, 'no preview, no field');
});

test('parseAskSidecar accepts text and number questions (no options) beside choice ones', () => {
  const input = { questions: [
    { question: 'Which colour?', header: 'Colour', options: [{ label: 'Red' }, { label: 'Blue' }], multiSelect: false },
    { question: 'What should it be called?', header: 'Name', kind: 'text', placeholder: 'e.g. ledger-api', description: 'Lowercase, hyphens.' },
    { question: 'How many replicas?', header: 'Replicas', kind: 'number', min: 1, max: 9, step: 1, default: 3, unit: 'pods' },
  ] };
  const s = parseAskSidecar({ v: 1, tool: 'AskUserQuestion', sessionId: 's', ts: 1786400000, input });
  assert.ok(s, 'a text question without options is not a malformed sidecar');
  assert.deepEqual(s.questions.map((q) => q.kind), ['choice', 'text', 'number']);
  assert.equal(s.questions[1].placeholder, 'e.g. ledger-api');
  assert.equal(s.questions[1].description, 'Lowercase, hyphens.');
  assert.deepEqual(s.questions[1].options, []);
  assert.equal(s.questions[1].multiSelect, false);
  assert.deepEqual([s.questions[2].min, s.questions[2].max, s.questions[2].step, s.questions[2].default, s.questions[2].unit], [1, 9, 1, 3, 'pods']);
  // A choice question still needs its two options; an unknown kind reads as choice.
  assert.equal(parseAskSidecar({ v: 1, input: { questions: [{ question: 'Q?', kind: 'slider', options: [{ label: 'A' }] }] } }), null);
  // The degraded card over a text question draws the question and no buttons.
  const card = degradedAskCard(s, 'fp');
  assert.equal(card.question, 'Which colour?');
  assert.equal(card.questionCount, 3);
});
