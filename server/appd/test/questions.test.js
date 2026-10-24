'use strict';
// lib/questions.js — the request registry: which parked hooks are live questions,
// what an answer body may say, and what a notification may offer as a button.
// Pure, no daemon; the hook payload shapes are Claude Code 2.1.306 captures.
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const q = require('../lib/questions');
const { detectPrompt } = require('../lib/pane');

const FIX = path.join(__dirname, 'fixtures', 'prompts');
const pane = (f) => fs.readFileSync(path.join(FIX, f), 'utf8').split('\n');
const json = (f) => JSON.parse(fs.readFileSync(path.join(FIX, f), 'utf8'));

const NOW = 1_800_000_000_000;
const ASK_INPUT = {
  questions: [
    { question: 'Which colour?', header: 'Colour', multiSelect: false,
      options: [{ label: 'Red', description: 'Select Red' }, { label: 'Blue', description: 'Select Blue' }] },
    { question: 'Which fruits?', header: 'Fruit', multiSelect: true,
      options: [{ label: 'Apple' }, { label: 'Pear, ripe' }, { label: 'Plum' }] },
  ],
};
const req = (over) => ({ id: 'r', pid: 1, sessionId: 's', tool: 'Bash', input: { command: 'touch x' }, ts: NOW - 5000, pane: true, ...over });

test('every parked tool is a question: a permission, an ask confirmed by its sidecar, a plan from its sidecar', () => {
  const list = q.buildQuestions({
    requests: [
      req({ id: 'bash', suggestions: [{ type: 'addDirectories', directories: ['/srv/x'], destination: 'session' }] }),
      req({ id: 'edit', tool: 'Edit', input: { file_path: '/srv/x/a.txt', old_string: 'a', new_string: 'b' }, ts: NOW - 4000, agentId: 'agent-1', agentType: 'Explore' }),
      req({ id: 'ask', tool: 'AskUserQuestion', input: ASK_INPUT, ts: NOW - 3000 }),
      req({ id: 'plan', tool: 'ExitPlanMode', input: { plan: '# P', planFilePath: '/p.md' }, ts: NOW - 2000 }),
    ],
    askInput: JSON.parse(JSON.stringify(ASK_INPUT)),
    plan: { ts: Math.floor((NOW - 1000) / 1000), plan: '# P', planFilePath: '/p.md' },
    now: NOW,
  });
  assert.deepEqual(list.map((x) => [x.id, x.kind, x.toolName, x.bound, x.fallback]), [
    ['bash', 'permission', 'Bash', true, null],
    ['edit', 'permission', 'Edit', true, null],
    ['ask', 'ask', 'AskUserQuestion', true, null],
    ['plan', 'plan', 'ExitPlanMode', true, null],
  ]);
  assert.equal(list[0].suggestions.length, 1, 'the CLI\'s own suggestions ride the question');
  assert.equal(list[1].agentId, 'agent-1');
  assert.equal(list[1].agentType, 'Explore');
  assert.deepEqual(list[3].input, { plan: '# P', planFilePath: '/p.md' }, 'the plan text and file path travel');
  assert.equal(list[3].hookId, 'plan', 'the plan rides the live ExitPlanMode hook when there is one');
  for (const x of list) assert.match(x.fingerprint, /^[0-9a-f]{12}$/);
  assert.equal(new Set(list.map((x) => x.fingerprint)).size, 4, 'four different requests, four identities');
  assert.equal(list.every((x) => x.since > 0), true);
  assert.ok(!('hookId' in q.publicQuestion(list[0])), 'the hook\'s file id stays on the server');
});

test('an ask whose sidecar is gone or differs was answered already: not listed', () => {
  const asks = [req({ id: 'ask', tool: 'AskUserQuestion', input: ASK_INPUT })];
  assert.deepEqual(q.buildQuestions({ requests: asks, askInput: null, now: NOW }), []);
  const other = JSON.parse(JSON.stringify(ASK_INPUT));
  other.questions[0].question = 'Which hue?';
  assert.deepEqual(q.buildQuestions({ requests: asks, askInput: other, now: NOW }), []);
  // Key order is not identity: the sidecar's serialisation may differ from the hook's.
  const reordered = { questions: ASK_INPUT.questions.map((s) => ({ options: s.options, multiSelect: s.multiSelect, header: s.header, question: s.question })) };
  assert.equal(q.buildQuestions({ requests: asks, askInput: reordered, now: NOW }).length, 1);
});

test('a plan without a live hook is still a question (keys approve it), stamped by its sidecar', () => {
  const list = q.buildQuestions({ requests: [], plan: { ts: Math.floor(NOW / 1000) - 10, plan: '# P' }, now: NOW });
  assert.equal(list.length, 1);
  assert.equal(list[0].kind, 'plan');
  assert.equal(list[0].hookId, null);
  assert.match(list[0].id, /^plan-\d+$/);
  assert.equal(list[0].bound, true);
  // A sidecar from yesterday says nothing about today.
  assert.deepEqual(q.buildQuestions({ requests: [], plan: { ts: Math.floor(NOW / 1000) - 2 * 86400, plan: '# P' }, now: NOW }), []);
});

test('a pane-backed permission older than a moment under an idle composer was answered by hand: left out', () => {
  const reqs = [req({ id: 'old', ts: NOW - 5000 }), req({ id: 'new', ts: NOW - 500 }), req({ id: 'bg', ts: NOW - 60_000, pane: false })];
  // A dialog is up (or no pane to read): everything is live.
  assert.deepEqual(q.buildQuestions({ requests: reqs, now: NOW, paneIdle: false }).map((x) => x.id), ['bg', 'old', 'new']);
  assert.deepEqual(q.buildQuestions({ requests: reqs, now: NOW, paneIdle: null }).map((x) => x.id), ['bg', 'old', 'new']);
  // The composer is drawn with nothing over it: the old one was answered at the
  // keyboard (a "No" interrupts and fires nothing); the fresh one is about to
  // draw; the background job has no pane at all and keeps its question.
  assert.deepEqual(q.buildQuestions({ requests: reqs, now: NOW, paneIdle: true }).map((x) => x.id), ['bg', 'new']);
});

test('the fingerprint is the request, not the pane: same input same identity, key order ignored, resize irrelevant', () => {
  const a = q.fingerprintFor('permission', 'Bash', { command: 'touch x', description: 'd' });
  const b = q.fingerprintFor('permission', 'Bash', { description: 'd', command: 'touch x' });
  assert.equal(a, b);
  assert.notEqual(a, q.fingerprintFor('permission', 'Bash', { command: 'touch  x', description: 'd' }), 'a space is a different command');
  assert.notEqual(a, q.fingerprintFor('ask', 'Bash', { command: 'touch x', description: 'd' }), 'the kind is part of it');
  const twins = [req({ id: 'a' }), req({ id: 'b', ts: NOW - 4000 })];
  const list = q.buildQuestions({ requests: twins, now: NOW });
  assert.equal(list[0].fingerprint, list[1].fingerprint, 'identical twins share one (the pane breaks the tie on /answer)');
  assert.notEqual(list[0].id, list[1].id);
});

test('the digest moves when a question arrives, leaves or changes', () => {
  const one = q.buildQuestions({ requests: [req({ id: 'a' })], now: NOW });
  const two = q.buildQuestions({ requests: [req({ id: 'a' }), req({ id: 'b', ts: NOW - 1000 })], now: NOW });
  assert.equal(q.digest([]), '');
  assert.match(q.digest(one), /^[0-9a-f]{8}$/);
  assert.notEqual(q.digest(one), q.digest(two));
});

// ---- answers --------------------------------------------------------------------

const askQ = () => q.buildQuestions({ requests: [req({ id: 'ask', tool: 'AskUserQuestion', input: ASK_INPUT })], askInput: ASK_INPUT, now: NOW })[0];
const permQ = (over) => q.buildQuestions({ requests: [req({ id: 'p', suggestions: [
  { type: 'addDirectories', directories: ['/srv/x'], destination: 'session' },
  { type: 'setMode', mode: 'acceptEdits', destination: 'session' },
], ...over })], now: NOW })[0];

test('an ask is answered whole: a string per question, an array only for a multi-select, labels with ", " safe as arrays', () => {
  const d = q.decisionFor(askQ(), { answers: { 'Which colour?': 'free text, not an option', 'Which fruits?': ['Apple', 'Pear, ripe'] } });
  assert.equal(d.ok, true);
  assert.equal(d.decision.behavior, 'allow');
  assert.deepEqual(d.decision.updatedInput.questions, ASK_INPUT.questions, 'the shown questions ride unchanged (the CLI refuses a changed field)');
  assert.deepEqual(d.decision.updatedInput.answers, { 'Which colour?': 'free text, not an option', 'Which fruits?': ['Apple', 'Pear, ripe'] });
  assert.equal(q.decisionFor(askQ(), { answers: { 'Which colour?': 'Red' } }).ok, false, 'every question must be answered');
  assert.match(q.decisionFor(askQ(), { answers: { 'Which colour?': 'Red' } }).error, /no answer for "Which fruits\?"/);
  assert.equal(q.decisionFor(askQ(), { answers: { 'Which colour?': ['Red'], 'Which fruits?': ['Apple'] } }).ok, false, 'a list for a single-select question');
  assert.equal(q.decisionFor(askQ(), { answers: { 'Which colour?': 'Red', 'Which fruits?': ['Apple'], 'Which size?': 'L' } }).ok, false, 'an unknown question');
  assert.equal(q.decisionFor(askQ(), { answers: { 'Which colour?': 7, 'Which fruits?': ['Apple'] } }).ok, false, 'a number is not an answer');
  assert.equal(q.decisionFor(askQ(), {}).ok, false);
  assert.equal(q.decisionFor(askQ(), { answers: { 'Which colour?': 'Red', 'Which fruits?': [] } }).ok, true, 'none of a multi-select is an answer');
});

test('annotations (previews, notes) and a free-text response pass through, shape-checked', () => {
  const body = {
    answers: { 'Which colour?': 'Red', 'Which fruits?': ['Plum'] },
    annotations: { 'Which colour?': { preview: 'a swatch', notes: 'the darker one' } },
    response: 'also consider teal',
  };
  const d = q.decisionFor(askQ(), body);
  assert.equal(d.ok, true);
  assert.deepEqual(d.decision.updatedInput.annotations, { 'Which colour?': { preview: 'a swatch', notes: 'the darker one' } });
  assert.equal(d.decision.updatedInput.response, 'also consider teal');
  assert.equal(q.decisionFor(askQ(), { ...body, annotations: { 'Which size?': { notes: 'x' } } }).ok, false, 'an annotation for a question not asked');
  assert.equal(q.decisionFor(askQ(), { ...body, annotations: { 'Which colour?': { rating: 5 } } }).ok, false, 'only preview and notes');
  assert.equal(q.decisionFor(askQ(), { ...body, response: ['x'] }).ok, false);
  assert.equal(q.decisionFor(askQ(), { ...body, response: '' }).ok, true);
  assert.equal('response' in q.decisionFor(askQ(), { ...body, response: '' }).decision.updatedInput, false, 'an empty response is no response');
});

test('a permission: allow, allow with the prompt\'s own suggestions, deny with words (goes on) or without (interrupts)', () => {
  assert.deepEqual(q.decisionFor(permQ(), { behavior: 'allow' }), { ok: true, decision: { behavior: 'allow' } });
  const always = q.decisionFor(permQ(), { behavior: 'allow', updatedPermissions: [{ destination: 'session', type: 'addDirectories', directories: ['/srv/x'] }] });
  assert.equal(always.ok, true);
  assert.deepEqual(always.decision.updatedPermissions, [{ destination: 'session', directories: ['/srv/x'], type: 'addDirectories' }]);
  // Only what the CLI itself suggested may become a standing permission: a
  // client cannot invent a rule, and cannot widen one.
  assert.equal(q.decisionFor(permQ(), { behavior: 'allow', updatedPermissions: [{ type: 'addDirectories', directories: ['/'], destination: 'session' }] }).ok, false);
  assert.equal(q.decisionFor(permQ(), { behavior: 'allow', updatedPermissions: [{ type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'userSettings' }] }).ok, false);
  assert.equal(q.decisionFor(permQ(), { behavior: 'allow', updatedPermissions: [] }).ok, false);
  assert.equal(q.decisionFor(permQ({ suggestions: null }), { behavior: 'allow', updatedPermissions: [{ type: 'setMode', mode: 'auto', destination: 'session' }] }).ok, false, 'no suggestions, nothing to grant');
  assert.deepEqual(q.decisionFor(permQ(), { behavior: 'deny', message: '  use ls instead  ' }),
    { ok: true, decision: { behavior: 'deny', message: 'use ls instead', interrupt: false } });
  assert.deepEqual(q.decisionFor(permQ(), { behavior: 'deny' }),
    { ok: true, decision: { behavior: 'deny', message: q.DEFAULT_DENY, interrupt: true } });
  assert.equal(q.decisionFor(permQ(), { behavior: 'maybe' }).ok, false);
  assert.equal(q.decisionFor(permQ(), { answers: { x: 'y' } }).ok, false, 'an ask body on a permission');
});

test('a plan: approve (keys, manual unless auto is asked for) or feedback, which needs words', () => {
  const plan = q.buildQuestions({ requests: [], plan: { ts: Math.floor(NOW / 1000), plan: '# P' }, now: NOW })[0];
  assert.deepEqual(q.decisionFor(plan, { behavior: 'allow' }), { ok: true, plan: { approve: 'manual' } });
  assert.deepEqual(q.decisionFor(plan, { behavior: 'allow', mode: 'auto' }), { ok: true, plan: { approve: 'auto' } });
  assert.deepEqual(q.decisionFor(plan, { behavior: 'deny', message: 'add a rollback step' }), { ok: true, plan: { feedback: 'add a rollback step' } });
  assert.equal(q.decisionFor(plan, { behavior: 'deny' }).ok, false, 'a plan is sent back with words');
  assert.equal(q.decisionFor(plan, { behavior: 'deny', message: '   ' }).ok, false);
});

// ---- the plan dialog on the pane -------------------------------------------------

test('the plan dialog is recognised on both captured shapes, with its approve rows and its feedback row', () => {
  for (const f of ['plan-approval-v2-80.txt', 'plan-approval-v3-80.txt']) {
    const p = detectPrompt(pane(f));
    assert.ok(q.isPlanPrompt(p), f);
    assert.match(q.planApproveRow(p, 'manual').label, /manually/i, f);
    assert.match(q.planApproveRow(p, 'auto').label, /auto/i, f);
    assert.match(q.planFeedbackRow(p).label, /tell claude what to change/i, f);
  }
  assert.equal(q.isPlanPrompt(detectPrompt(pane('bash-permission-80.txt'))), false, 'a Bash dialog is not a plan');
  assert.equal(q.isPlanPrompt(detectPrompt(pane('ask-simple-v2-80.txt'))), false);
  assert.equal(q.isPlanPrompt(null), false);
});

test('the typed feedback row keeps the same identity as the untouched dialog', () => {
  // Pressing 3 focuses the row and the typed words replace its label on screen;
  // the digit-then-text path checks the fingerprint between the two.
  const before = detectPrompt(pane('plan-approval-v3-80.txt'));
  const typed = detectPrompt(pane('plan-approval-feedback-typed-80.txt'));
  assert.ok(before && typed);
  assert.equal(typed.options.find((o) => o.selected).number, 3, 'the caret sits on the feedback row');
  assert.equal(typed.options[2].label, 'Please also add a step that prints the file afterwards.');
});

// ---- notifications and the legacy /answer route ----------------------------------

test('notification buttons: a single single-select question\'s exact labels, Yes / No for a permission, none otherwise', () => {
  const single = q.buildQuestions({
    requests: [req({ id: 'ask', tool: 'AskUserQuestion', input: { questions: [ASK_INPUT.questions[0]] } })],
    askInput: { questions: [ASK_INPUT.questions[0]] }, now: NOW,
  })[0];
  assert.deepEqual(q.notificationButtons(single), [{ number: 1, label: 'Red' }, { number: 2, label: 'Blue' }]);
  assert.deepEqual(q.buttonAnswer(single, 2), { answers: { 'Which colour?': 'Blue' } });
  assert.equal(q.buttonAnswer(single, 3), null, 'no "Type something." button: the TUI row is not in the tool input');
  assert.deepEqual(q.notificationButtons(askQ()), [], 'two questions cannot be answered by one tap');
  const multi = q.buildQuestions({
    requests: [req({ id: 'ask', tool: 'AskUserQuestion', input: { questions: [ASK_INPUT.questions[1]] } })],
    askInput: { questions: [ASK_INPUT.questions[1]] }, now: NOW,
  })[0];
  assert.deepEqual(q.notificationButtons(multi), [], 'a multi-select is a set, not a tap');
  assert.deepEqual(q.notificationButtons(permQ()), [{ number: 1, label: 'Yes' }, { number: 2, label: 'No' }]);
  assert.deepEqual(q.buttonAnswer(permQ(), 1), { behavior: 'allow' });
  assert.deepEqual(q.buttonAnswer(permQ(), 2), { behavior: 'deny' });
  assert.equal(q.buttonAnswer(permQ(), 3), null, 'no one-tap "always allow"');
  const plan = q.buildQuestions({ requests: [], plan: { ts: Math.floor(NOW / 1000), plan: '# P' }, now: NOW })[0];
  assert.deepEqual(q.notificationButtons(plan), [], 'a plan is read before it is approved');
});

test('describe: what is asked, in one line', () => {
  assert.equal(q.describe(permQ()), 'Bash: touch x');
  assert.equal(q.describe(q.buildQuestions({ requests: [req({ id: 'e', tool: 'Edit', input: { file_path: '/srv/a.txt', old_string: 'x', new_string: 'y' } })], now: NOW })[0]), 'Edit: /srv/a.txt');
  const mcp = q.buildQuestions({ requests: [req({ id: 'm', tool: 'mcp__grafana__query_loki_logs', input: { query: '{job="x"}' }, mcpServer: { name: 'grafana', source: 'user' } })], now: NOW })[0];
  assert.equal(mcp.mcpServer, 'grafana');
  assert.equal(q.describe(mcp), 'grafana · query_loki_logs: {job="x"}');
  assert.equal(q.buildQuestions({ requests: [req({ id: 'm', tool: 'mcp__my_server__tool', input: {} })], now: NOW })[0].mcpServer, 'my_server', 'the server read off the name when the hook did not say');
  assert.equal(q.describe(askQ()), 'Which colour?');
});

test('the captured 2.1.306 payloads build questions as they are', () => {
  const bash = json('bash-permission.hook.json');
  const list = q.buildQuestions({ requests: [req({ id: 'b', tool: bash.tool_name, input: bash.tool_input, suggestions: bash.permission_suggestions })], now: NOW });
  assert.equal(list[0].kind, 'permission');
  assert.deepEqual(list[0].suggestions.map((s) => s.type), ['addDirectories', 'setMode']);
  assert.equal(q.describe(list[0]), 'Bash: touch probe.txt');
  const plan = json('exitplanmode-2.1.306.hook.json');
  const req2 = req({ id: 'p', tool: 'ExitPlanMode', input: plan.tool_input });
  const l2 = q.buildQuestions({ requests: [req2], plan: { ts: Math.floor(NOW / 1000), plan: plan.tool_input.plan, planFilePath: plan.tool_input.planFilePath }, now: NOW });
  assert.equal(l2[0].kind, 'plan');
  assert.equal(l2[0].hookId, 'p');
  assert.equal(l2[0].input.planFilePath, plan.tool_input.planFilePath);
});

test('the default-mode plan dialog ("Exit plan mode?", Yes / No) is a plan too; it has no feedback row', () => {
  const p = detectPrompt(pane('plan-approval-manual-80.txt'));
  assert.ok(p, 'the two-row dialog is a prompt');
  assert.deepEqual(p.options.map((o) => o.label), ['Yes, and switch to default (ask each time) for this session', 'No']);
  assert.ok(q.isPlanPrompt(p));
  assert.match(q.planApproveRow(p, 'manual').label, /^Yes/);
  assert.match(q.planApproveRow(p, 'auto').label, /^Yes/, 'the one Yes row is every approval');
  assert.equal(q.planFeedbackRow(p), null, 'feedback here goes through the hook only');
  // A permission dialog shares the Yes / No rows and is NOT a plan.
  assert.equal(q.isPlanPrompt({ question: 'Do you want to proceed?', options: [{ label: 'Yes' }, { label: 'No' }] }), false);
});
