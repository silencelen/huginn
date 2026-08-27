'use strict';
// The two halves of Rounds polish: what the model is told, and what is believed
// of what it says back.
//
// The prompt half is tested by FACT rather than by wording — the frame it teaches
// (the goal goes first, the report contract is appended, tools come from the mode)
// is copied from lib/rounds.js and huginn-appd.js, and the failure worth catching
// is one of those drifting apart, not a sentence being rephrased.
//
// The parse half is tested against what models actually do when told not to
// decorate: they decorate.

const test = require('node:test');
const assert = require('node:assert');
const { FIELDS, buildPolishPrompt, parsePolish } = require('../lib/polish');
const { promptFor, REPORT_CONTRACT } = require('../lib/rounds');

const DRAFT = {
  title: 'Telegram health check',
  prompt: 'Look at the telegram alerts and tell me if anything is wrong.',
  goal: 'the alerts are reviewed',
  mode: 'ask',
};

// ------------------------------------------------------------- the prompt

test('the prompt teaches the frame a Round actually runs in', () => {
  const p = buildPolishPrompt('prompt', DRAFT, 20_000);
  assert.match(p, /ONE unattended `claude -p` turn/);
  assert.match(p, /GOAL — this run is done when:/, 'the head the daemon prepends');
  assert.match(p, /report contract, appended by the daemon/);
  assert.match(p, /persona is supplied separately/);
  assert.match(p, /nobody is watching/i);
});

test('the frame it teaches is the frame the daemon composes', () => {
  // The one assertion that fails if lib/rounds.js changes shape underneath this
  // file: the head string is quoted here because a Round's run really does open
  // with it, and a polish prompt teaching a stale opening teaches a lie.
  const head = promptFor({ goal: 'G', prompt: 'P' }).split('\n')[0];
  assert.equal('GOAL — this run is done when: G', head, 'the daemon still opens a run this way');
  assert.ok(
    buildPolishPrompt('goal', DRAFT, 500).includes('GOAL — this run is done when:'),
    'and the polish prompt still quotes that opening',
  );
  assert.match(REPORT_CONTRACT, /huginn-report/, 'the contract is still what the daemon appends');
});

test('the mode is stated as a capability, both ways', () => {
  const ask = buildPolishPrompt('prompt', { ...DRAFT, mode: 'ask' }, 20_000);
  assert.match(ask, /CANNOT change anything/);
  const act = buildPolishPrompt('prompt', { ...DRAFT, mode: 'act' }, 20_000);
  assert.match(act, /MAY change things/);
  // The toolsets, mirroring huginn-appd's TOOLS: a rewrite that tells an ask Round
  // to run a shell command is a Round that fails every week with nobody watching.
  assert.match(ask, /ask {2}— Skill, mempalace .*WebFetch, WebSearch/);
  assert.match(ask, /act {2}— all of those plus Bash, Read, Edit, Write, Glob, Grep/);
});

test('an unknown mode is treated as ask, never as act', () => {
  const p = buildPolishPrompt('prompt', { ...DRAFT, mode: 'ACT!' }, 20_000);
  assert.match(p, /CANNOT change anything/, 'the safe reading of a mode we do not know');
});

test('the prompt field is told what the daemon already supplies', () => {
  const p = buildPolishPrompt('prompt', DRAFT, 20_000);
  assert.match(p, /MUST NOT contain/);
  assert.match(p, /report, JSON or output-format instructions/);
  assert.match(p, /a restatement of the goal/);
  assert.match(p, /persona or role framing/);
  // And the rules that make an unattended prompt work at all.
  assert.match(p, /read-vs-change boundary/);
  assert.match(p, /NEW finding from a chronic one/);
  assert.match(p, /if nothing needs anyone, say so and report no items/);
});

test('the goal field is told to write a test, not a topic', () => {
  const p = buildPolishPrompt('goal', DRAFT, 500);
  assert.match(p, /continue\s+that sentence/, 'it is pasted mid-sentence');
  assert.match(p, /answer yes or no/);
  assert.match(p, /honestly failable/);
  assert.match(p, /decidable inside a SINGLE run/);
  assert.match(p, /Not a goal: "review the alerts"/, 'a worked counter-example, not just a rule');
  assert.match(p, /Stay under 500 characters/);
  assert.doesNotMatch(p, /MUST NOT contain/, 'the prompt-field rules do not belong here');
});

test('each field is told its own cap, from the caller', () => {
  assert.match(buildPolishPrompt('prompt', DRAFT, 20_000), /Stay under 20000 characters/);
  assert.match(buildPolishPrompt('goal', DRAFT, 500), /Stay under 500 characters/);
});

test('the draft is quoted back so the rewrite keeps the subject', () => {
  const p = buildPolishPrompt('prompt', DRAFT, 20_000);
  assert.match(p, /Telegram health check/);
  assert.match(p, /Look at the telegram alerts/);
  assert.match(p, /Goal: the alerts are reviewed/);
});

test('an empty half of the draft is named rather than left blank', () => {
  const p = buildPolishPrompt('goal', { prompt: 'do a thing', mode: 'ask' }, 500);
  assert.match(p, /Name: \(not named yet\)/);
  assert.match(p, /Goal: \(empty\)/);
});

test('a huge prompt is clipped into the instruction, not shipped whole', () => {
  const p = buildPolishPrompt('goal', { ...DRAFT, prompt: 'x'.repeat(60_000) }, 500);
  assert.ok(p.length < 30_000, `the instruction ran to ${p.length} characters`);
});

test('the output contract forbids every wrapper the parser then strips', () => {
  const p = buildPolishPrompt('prompt', DRAFT, 20_000);
  assert.match(p, /ANSWER WITH THE FIELD TEXT ALONE/);
  assert.match(p, /no code fence/);
  assert.match(p, /no surrounding\nquotes/);
  assert.match(p, /no "Prompt:" or "Goal:" label/);
});

// -------------------------------------------------------------- the parse

test('a clean answer is passed through untouched', () => {
  assert.deepEqual(parsePolish('goal', '  every alert has been read.  ', 500),
    { polished: 'every alert has been read.' });
});

test('a wrapping fence comes off, with or without an info word', () => {
  assert.equal(parsePolish('prompt', '```\nRead the alerts.\n```', 20_000).polished, 'Read the alerts.');
  assert.equal(parsePolish('prompt', '```text\nRead the alerts.\n```', 20_000).polished, 'Read the alerts.');
});

test('a fence INSIDE the field survives — it may be what the Round is about', () => {
  const body = 'Run this:\n\n```\ndf -h /mnt/data\n```\n\nand report the number.';
  assert.equal(parsePolish('prompt', body, 20_000).polished, body, 'only a WRAPPING fence is decoration');
});

test('one pair of wrapping quotes comes off, straight or curly', () => {
  assert.equal(parsePolish('goal', '"the disk was checked"', 500).polished, 'the disk was checked');
  assert.equal(parsePolish('goal', '“the disk was checked”', 500).polished, 'the disk was checked');
  // ONE pair: a field that legitimately opens and closes on a quoted phrase must
  // not be eaten a layer at a time.
  assert.equal(parsePolish('goal', '""quoted" is in the output"', 500).polished, '"quoted" is in the output');
});

test('a leading label is dropped however it is phrased', () => {
  assert.equal(parsePolish('goal', 'Goal: the disk was checked', 500).polished, 'the disk was checked');
  assert.equal(parsePolish('prompt', 'Improved prompt: Read the alerts.', 20_000).polished, 'Read the alerts.');
  assert.equal(parsePolish('prompt', 'The rewritten prompt field: Read it.', 20_000).polished, 'Read it.');
});

test('a label-shaped sentence inside the field is not a label', () => {
  const body = 'Check the goal: whether the disk filled.';
  assert.equal(parsePolish('prompt', body, 20_000).polished, body, 'the label rule is anchored at the start');
});

test('an answer echoing the report contract is REFUSED, not salvaged', () => {
  // The failure this exists for: a model that read the frame and helpfully wrote
  // the daemon's own contract into the field, which would then fight the real one
  // inside every run for months.
  const echo = 'Read the alerts.\n\n```huginn-report abc123\n{"status":"ok"}\n```';
  assert.match(parsePolish('prompt', echo, 20_000).error, /report contract/);
  assert.match(parsePolish('prompt', 'End with a fenced huginn-report block.', 20_000).error, /report contract/);
  assert.match(parsePolish('prompt', 'Answer with:\n```json\n{"status":"ok"}\n```', 20_000).error, /report contract/);
});

test('a goal is collapsed to one line: it is pasted mid-sentence', () => {
  assert.equal(parsePolish('goal', 'every alert\nhas been read', 500).polished, 'every alert has been read');
  // A prompt is a whole instruction and keeps its shape.
  assert.equal(parsePolish('prompt', 'Do this.\n\nThen that.', 20_000).polished, 'Do this.\n\nThen that.');
});

test('an over-long answer is clamped and SAYS it was', () => {
  const got = parsePolish('goal', 'x'.repeat(600), 500);
  assert.equal(got.polished.length, 500);
  assert.match(got.note, /Trimmed to 500 characters/, 'a silently shortened draft is a lie about what you accepted');
});

test('the clamp is spent on the field, not on the label', () => {
  // Clamping first would fit exactly 500 characters too — six of them the word
  // "Goal:" — so the length alone proves nothing. The CONTENT is the assertion.
  const got = parsePolish('goal', `Goal: ${'x'.repeat(500)}`, 500);
  assert.equal(got.polished, 'x'.repeat(500), 'the label came off before the cap was applied');
  assert.equal(got.note, undefined, 'and nothing was lost, so there is nothing to say');
});

test('nothing usable is an error, never an empty field', () => {
  assert.match(parsePolish('prompt', '', 20_000).error, /said nothing/);
  assert.match(parsePolish('prompt', null, 20_000).error, /said nothing/);
  assert.match(parsePolish('prompt', '```\n\n```', 20_000).error, /nothing was left/);
  assert.match(parsePolish('goal', 'Goal:', 500).error, /nothing was left/);
});

test('the field list is the enum the route validates against', () => {
  assert.deepEqual(FIELDS, ['prompt', 'goal']);
});
