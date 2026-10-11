'use strict';
// lib/permhook.js — matching a tap to a waiting PermissionRequest hook, and the
// two-rename hand-off. The pane shapes are live Claude Code 2.1.296 captures.
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const ph = require('../lib/permhook');
const { detectPrompt } = require('../lib/pane');

const FIX = path.join(__dirname, 'fixtures', 'prompts');
const load = (f) => fs.readFileSync(path.join(FIX, f), 'utf8').split('\n');
const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'permhook-'));
let seq = 0;
function writeReq(stateDir, name, fields) {
  const id = `${Date.now()}${String(seq++).padStart(6, '0')}-${process.pid}`;
  const dir = ph.permDir(stateDir, name);
  fs.mkdirSync(dir, { recursive: true });
  const r = { v: 1, id, pid: process.pid, sessionId: 'sid', ts: Date.now(), ...fields };
  fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify(r));
  return r;
}

// The live permission dialog: an old `touch hello.txt` run sits ABOVE the border.
const BASH_PANE = [
  '● Bash(touch hello.txt)',
  '  ⎿  Done',
  '● Bash(touch hello2.txt)',
  '  ⎿  Waiting…',
  '─'.repeat(80),
  ' Bash command',
  ' Create empty file hello2.txt',
  '╌'.repeat(80),
  ' touch hello2.txt',
  '╌'.repeat(80),
  ' Do you want to proceed?',
  ' ❯ 1. Yes',
  '   2. Yes, and always allow access to /tmp/x from this project',
  '   3. Yes, and switch to auto mode · auto mode handles these prompts for you',
  '   4. No',
  ' Esc to cancel · Tab to amend',
];

test('a tool prompt matches by the command INSIDE the dialog border, not the history above it', () => {
  const prompt = detectPrompt(BASH_PANE);
  const region = ph.dialogRegion(BASH_PANE);
  assert.ok(!region.includes('Bash(touch hello.txt)'), 'the region starts at the border');
  const live = { tool: 'Bash', input: { command: 'touch hello2.txt' } };
  const stale = { tool: 'Bash', input: { command: 'touch hello.txt' } };
  assert.equal(ph.matchesPrompt(live, prompt, region), true);
  assert.equal(ph.matchesPrompt(stale, prompt, region), false,
    'an earlier run of a different command is visible in history but is not this dialog');
});

test('a wrapped command still matches (whitespace is ignored)', () => {
  const long = 'rsync -a --delete /mnt/data/projects/alpha/ /mnt/backup/projects/alpha/';
  const pane = ['─'.repeat(40), ' Bash command', ' rsync -a --delete /mnt/data/proj', ' ects/alpha/ /mnt/backup/projects/alpha/',
    ' Do you want to proceed?', ' ❯ 1. Yes', '   2. No', ' Esc to cancel'];
  assert.equal(ph.matchesPrompt({ tool: 'Bash', input: { command: long } }, detectPrompt(pane), ph.dialogRegion(pane)), true);
});

test('AskUserQuestion matches by question text; a tabbed (multi-question) call never does', () => {
  const prompt = detectPrompt(load('ask-simple-v2-80.txt'));
  const one = { tool: 'AskUserQuestion', input: { questions: [{ question: 'Which color?', header: 'Color',
    options: [{ label: 'Red' }, { label: 'Blue' }], multiSelect: false }] } };
  assert.equal(ph.matchesPrompt(one, prompt, ''), true);
  const two = { tool: 'AskUserQuestion', input: { questions: [one.input.questions[0], { question: 'Size?', options: [] }] } };
  assert.equal(ph.matchesPrompt(two, prompt, ''), false);
  const other = { tool: 'AskUserQuestion', input: { questions: [{ question: 'Which flavour?', options: [] }] } };
  assert.equal(ph.matchesPrompt(other, prompt, ''), false);
});

test('ExitPlanMode is never matched (the CLI ignores a hook allow there)', () => {
  const prompt = detectPrompt(load('plan-approval-v2-80.txt'));
  assert.equal(ph.matchesPrompt({ tool: 'ExitPlanMode', input: { plan: 'x' } }, prompt, ph.dialogRegion(load('plan-approval-v2-80.txt'))), false);
});

test('decisions: Yes allows, No denies with interrupt, anything that changes standing permissions goes to the keys', () => {
  const prompt = detectPrompt(BASH_PANE);
  const req = { tool: 'Bash', input: { command: 'touch hello2.txt' } };
  assert.deepEqual(ph.decisionFor(req, prompt, { option: 1 }), { behavior: 'allow' });
  assert.deepEqual(ph.decisionFor(req, prompt, { option: 4 }),
    { behavior: 'deny', message: 'The owner declined this from huginn.', interrupt: true });
  assert.equal(ph.decisionFor(req, prompt, { option: 2 }), null, 'always-allow');
  assert.equal(ph.decisionFor(req, prompt, { option: 3 }), null, 'switch to auto mode');
});

test('decisions: AskUserQuestion answers by exact label; "Type something." and "Chat about this" go to the keys', () => {
  const prompt = detectPrompt(load('ask-simple-v2-80.txt'));
  const q = { question: 'Which color?', header: 'Color',
    options: [{ label: 'Red', description: 'Pick red' }, { label: 'Blue', description: 'Pick blue' }], multiSelect: false };
  const req = { tool: 'AskUserQuestion', input: { questions: [q] } };
  assert.deepEqual(ph.decisionFor(req, prompt, { option: 2 }),
    { behavior: 'allow', updatedInput: { questions: [q], answers: { 'Which color?': 'Blue' } } });
  assert.equal(ph.decisionFor(req, prompt, { option: 3 }), null);
  assert.equal(ph.decisionFor(req, prompt, { option: 4 }), null);
  assert.equal(ph.decisionFor(req, prompt, { options: [1, 2] }), null, 'a set for a single-select question');
});

test('decisions: multi-select joins the exact labels with ", " (measured live)', () => {
  const q = { question: 'Which colors?', options: [{ label: 'Red' }, { label: 'Green' }, { label: 'Blue' }], multiSelect: true };
  const prompt = { question: 'Which colors?', multiSelect: true,
    options: [{ number: 1, label: 'Red', checked: false }, { number: 2, label: 'Green', checked: false }, { number: 3, label: 'Blue', checked: false }] };
  const d = ph.decisionFor({ tool: 'AskUserQuestion', input: { questions: [q] } }, prompt, { options: [1, 3] });
  assert.equal(d.updatedInput.answers['Which colors?'], 'Red, Blue');
});

test('liveRequests: dead pids, malformed files, stale and foreign-named files are ignored; oldest first', () => {
  const dir = tmpdir();
  const a = writeReq(dir, 's', { tool: 'Bash', ts: Date.now() - 2000 });
  const b = writeReq(dir, 's', { tool: 'Bash', ts: Date.now() - 1000 });
  writeReq(dir, 's', { tool: 'Bash', pid: 2147483646 });                 // no such process
  writeReq(dir, 's', { tool: 'Bash', ts: Date.now() - 60 * 60 * 1000 }); // an hour old
  fs.writeFileSync(path.join(ph.permDir(dir, 's'), 'junk.json'), '{');
  fs.writeFileSync(path.join(ph.permDir(dir, 's'), '123-1.json'), 'not json');
  const got = ph.liveRequests(dir, 's');
  assert.deepEqual(got.map((r) => r.id), [a.id, b.id]);
  assert.deepEqual(ph.liveRequests(dir, 'nobody'), []);
});

test('pickRequest: two requests with the same content -> the NEWEST (the older is a lingering hook)', () => {
  const prompt = detectPrompt(BASH_PANE);
  const region = ph.dialogRegion(BASH_PANE);
  const old = { id: 'o', tool: 'Bash', input: { command: 'touch hello2.txt' }, ts: 1 };
  const neu = { id: 'n', tool: 'Bash', input: { command: 'touch hello2.txt' }, ts: 2 };
  assert.equal(ph.pickRequest([old, neu], prompt, region).id, 'n');
  assert.equal(ph.pickRequest([{ id: 'x', tool: 'Bash', input: { command: 'ls' }, ts: 3 }], prompt, region), null);
});

test('deliver: a hook that claims the answer -> true, and it received the decision', async () => {
  const dir = tmpdir();
  const r = writeReq(dir, 's', { tool: 'Bash' });
  const ans = path.join(ph.permDir(dir, 's'), `${r.id}.ans`);
  const taken = path.join(ph.permDir(dir, 's'), `${r.id}.taken`);
  const claimer = setInterval(() => { try { fs.renameSync(ans, taken); } catch { /* not yet */ } }, 20);
  const ok = await ph.deliver(dir, 's', r, { behavior: 'allow' }, { waitMs: 2000 });
  clearInterval(claimer);
  assert.equal(ok, true);
  assert.deepEqual(JSON.parse(fs.readFileSync(taken, 'utf8')),
    { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } });
});

test('deliver: nobody claims -> false, and the answer is withdrawn so a late hook cannot act on it', async () => {
  const dir = tmpdir();
  const r = writeReq(dir, 's', { tool: 'Bash' });
  const ok = await ph.deliver(dir, 's', r, { behavior: 'allow' }, { waitMs: 150 });
  assert.equal(ok, false);
  assert.deepEqual(fs.readdirSync(ph.permDir(dir, 's')).filter((f) => !f.endsWith('.json')), []);
});

test('clearRequests removes the whole name', () => {
  const dir = tmpdir();
  writeReq(dir, 's', { tool: 'Bash' });
  ph.clearRequests(dir, 's');
  assert.equal(fs.existsSync(ph.permDir(dir, 's')), false);
});
