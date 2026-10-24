'use strict';
// GET/POST /v1/sessions/:name/questions — the request registry on the wire —
// against a throwaway daemon and a private tmux socket (the isolation
// routes-permhook uses). A simulated hook claims decisions the way
// hooks/huginn-permission-hook does (rename <id>.ans -> <id>.taken); panes run
// `cat`, so a typed digit is echoed and detectable.
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = 12800 + (process.pid % 300); // 12800-13099 (port-blocks.test.js checks for overlaps)
const BASE = `http://127.0.0.1:${PORT}`;
require('./retry-fetch');
const TMUX_SOCK = `huginn-test-qs-${process.pid}`;
let tmp, token, daemon, stateDir;

const RULE = '─'.repeat(60);
const DASH = '╌'.repeat(60);
// The live 2.1.306 Bash dialog shape (fixtures/prompts/bash-permission-80.txt).
const BASH_PROMPT = [
  RULE, ' Bash command', ' Create empty file probe.txt', DASH, ' touch probe.txt', DASH,
  ' Do you want to proceed?', ' ❯ 1. Yes', '   2. Yes, and always allow access to /srv/x from this project',
  '   3. Yes, and switch to auto mode', '   4. No', '', ' Esc to cancel · Tab to amend',
].join('\\n');
// A one-question AskUserQuestion as 2.1.306 draws it: options, then the TUI's own row.
const ASK_PROMPT = [
  RULE, 'Which colour?', '❯ 1. Red', '     Select Red', '  2. Blue', '     Select Blue', RULE,
  '  Chat about this', 'Enter to select · ↑/↓ to navigate · Esc to cancel',
].join('\\n');
const ASK_INPUT = { questions: [{ question: 'Which colour?', header: 'Colour', multiSelect: false,
  options: [{ label: 'Red', description: 'Select Red' }, { label: 'Blue', description: 'Select Blue' }] }] };
const TWO_Q = { questions: [ASK_INPUT.questions[0], { question: 'Which fruits?', header: 'Fruit', multiSelect: true,
  options: [{ label: 'Apple' }, { label: 'Pear' }, { label: 'Plum' }] }] };

function sh(cmd, args) {
  if (cmd === 'tmux') args = ['-L', TMUX_SOCK, ...args];
  return execFileSync(cmd, args, { encoding: 'utf8' });
}
async function api(pathname, init = {}) {
  const res = await fetch(BASE + pathname, {
    ...init,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  let body = null;
  try { body = await res.json(); } catch { /* none */ }
  return { status: res.status, body };
}
const post = (p, body) => api(p, { method: 'POST', body: JSON.stringify(body) });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-qs-'));
  stateDir = path.join(tmp, 'state');
  fs.mkdirSync(stateDir);
  fs.mkdirSync(path.join(tmp, 'data'));
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  daemon = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      HUGINN_APPD_PORT: String(PORT),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: path.join(tmp, 'data'),
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_TMUX_SOCKET: TMUX_SOCK,
      HUGINN_APPD_STATE_DIR: stateDir,
    },
    stdio: 'ignore',
  });
  daemon.on('error', (e) => { throw e; });
  for (let i = 0; i < 300; i++) {
    try { if ((await api('/v1/ping')).status === 200) break; } catch { /* not yet */ }
    await wait(100);
  }
  if ((await api('/v1/rounds')).status === 401) throw new Error(`port ${PORT} is held by another huginn-appd`);
});
after(() => {
  try { sh('tmux', ['kill-server']); } catch { /* none */ }
  if (daemon) daemon.kill('SIGTERM');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

let n = 0;
/** A pane showing `drawn` (or nothing: an idle composer) and a state file binding the session id. */
async function session(drawn, { composer = false } = {}) {
  const name = `qs-${process.pid}-${n++}`;
  const body = composer ? 'printf "%s\\n" "' + RULE + '" "❯ " "' + RULE + '" "  ⏸ manual mode on"; cat' : `printf "${drawn}\\n"; cat`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30', `sh -c '${body}'`]);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'attention', sessionId: `sid-${name}`, ts: Date.now() }));
  await wait(600);
  return name;
}
/** The plan dialog as a stub that moves and takes text like the real one (fixtures/stub-plan.js). */
async function planSession() {
  const name = `qs-${process.pid}-${n++}`;
  sh('tmux', ['new-session', '-d', '-s', name, '-c', tmp, '-x', '100', '-y', '30',
    `${process.execPath} ${path.join(__dirname, 'fixtures', 'stub-plan.js')}`]);
  fs.writeFileSync(path.join(stateDir, name), JSON.stringify({ state: 'attention', sessionId: `sid-${name}`, ts: Date.now() }));
  await wait(600);
  return name;
}
let seq = 0;
function park(name, fields) {
  const dir = path.join(stateDir, '.perm', name);
  fs.mkdirSync(dir, { recursive: true });
  const id = `${Date.now()}${String(seq++).padStart(6, '0')}-${process.pid}`;
  fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify({
    v: 1, id, pid: process.pid, sessionId: `sid-${name}`, ts: Date.now(), pane: true, ...fields,
  }));
  return { dir, id };
}
function sidecar(kind, name, input) {
  const dir = path.join(stateDir, `.${kind}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), JSON.stringify({
    v: 1, tool: kind === 'ask' ? 'AskUserQuestion' : 'ExitPlanMode', sessionId: `sid-${name}`, ts: Math.floor(Date.now() / 1000), input,
  }));
}
/** A hook that claims whatever answer appears; resolves the decision it got (null after 8 s). */
function fakeHook(dir, id) {
  return new Promise((resolve) => {
    const iv = setInterval(() => {
      try {
        fs.renameSync(path.join(dir, `${id}.ans`), path.join(dir, `${id}.taken`));
        clearInterval(iv);
        resolve(JSON.parse(fs.readFileSync(path.join(dir, `${id}.taken`), 'utf8')));
      } catch { /* not yet */ }
    }, 20);
    setTimeout(() => { clearInterval(iv); resolve(null); }, 8000);
  });
}
const paneText = (name) => sh('tmux', ['capture-pane', '-p', '-t', `=${name}:`]);
const typedOnPane = (name) => paneText(name).split('\n').some((l) => /^\s*[0-9]\s*$/.test(l));
const questions = async (name) => (await api(`/v1/sessions/${name}/questions`)).body.questions;

test('GET lists every parked request as a bound question; /screen carries the same list', async () => {
  const name = await session(BASH_PROMPT);
  assert.deepEqual(await questions(name), [], 'nothing parked, nothing listed');
  park(name, { tool: 'Bash', input: { command: 'touch probe.txt', description: 'Create empty file probe.txt' },
    suggestions: [{ type: 'addDirectories', directories: ['/srv/x'], destination: 'session' }] });
  const qs = await questions(name);
  assert.equal(qs.length, 1);
  const [q] = qs;
  assert.equal(q.kind, 'permission');
  assert.equal(q.toolName, 'Bash');
  assert.equal(q.bound, true);
  assert.equal(q.fallback, null);
  assert.deepEqual(q.input, { command: 'touch probe.txt', description: 'Create empty file probe.txt' });
  assert.equal(q.suggestions.length, 1);
  assert.match(q.fingerprint, /^[0-9a-f]{12}$/);
  assert.ok(q.since > 0);
  assert.ok(!('hookId' in q) && !('pid' in q), 'server-side fields stay there');
  const { body: scr } = await api(`/v1/sessions/${name}/screen`);
  assert.deepEqual(scr.questions, qs, 'the screen poll serves the registry too');
  assert.ok(scr.prompt, 'and the pane prompt still rides for older clients');
  assert.match(scr.hash, /-[0-9a-f]{8}$/, 'the registry is folded into the hash');
});

test('a permission answered allow goes through the hook: nothing typed, via hook', async () => {
  const name = await session(BASH_PROMPT);
  const { dir, id } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q] = await questions(name);
  const got = fakeHook(dir, id);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'allow', fingerprint: q.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual(r.body, { ok: true, via: 'hook' });
  assert.deepEqual(await got, { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } });
  await wait(300);
  assert.equal(typedOnPane(name), false);
});

test('"always allow" grants only what the prompt suggested; the hook gets updatedPermissions', async () => {
  const name = await session(BASH_PROMPT);
  const sugg = [{ type: 'addDirectories', directories: ['/srv/x'], destination: 'session' }];
  const { dir, id } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' }, suggestions: sugg });
  const [q] = await questions(name);
  const bad = await post(`/v1/sessions/${name}/questions/${q.id}`,
    { behavior: 'allow', updatedPermissions: [{ type: 'setMode', mode: 'bypassPermissions', destination: 'userSettings' }], fingerprint: q.fingerprint });
  assert.equal(bad.status, 400, 'a rule the prompt never offered');
  const got = fakeHook(dir, id);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'allow', updatedPermissions: q.suggestions, fingerprint: q.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual((await got).hookSpecificOutput.decision, { behavior: 'allow', updatedPermissions: sugg });
});

test('"No, tell Claude…" is a deny carrying the words, and lets the turn go on', async () => {
  const name = await session(BASH_PROMPT);
  const { dir, id } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q] = await questions(name);
  const got = fakeHook(dir, id);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'deny', message: 'use the existing file', fingerprint: q.fingerprint });
  assert.equal(r.body.via, 'hook');
  assert.deepEqual((await got).hookSpecificOutput.decision, { behavior: 'deny', message: 'use the existing file', interrupt: false });
});

test('a TWO-question ask is answered whole through the hook: a string and an array', async () => {
  const name = await session(ASK_PROMPT);
  sidecar('ask', name, TWO_Q);
  const { dir, id } = park(name, { tool: 'AskUserQuestion', input: TWO_Q });
  const [q] = await questions(name);
  assert.equal(q.kind, 'ask');
  assert.equal(q.input.questions.length, 2);
  const got = fakeHook(dir, id);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, {
    answers: { 'Which colour?': 'teal, actually', 'Which fruits?': ['Apple', 'Pear'] }, fingerprint: q.fingerprint,
  });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, 'hook');
  const d = (await got).hookSpecificOutput.decision;
  assert.equal(d.behavior, 'allow');
  assert.deepEqual(d.updatedInput.answers, { 'Which colour?': 'teal, actually', 'Which fruits?': ['Apple', 'Pear'] });
  assert.deepEqual(d.updatedInput.questions, TWO_Q.questions);
  await wait(300);
  assert.equal(typedOnPane(name), false);
});

test('an ask with half its answers is refused before anything reaches the hook', async () => {
  const name = await session(ASK_PROMPT);
  sidecar('ask', name, TWO_Q);
  const { dir, id } = park(name, { tool: 'AskUserQuestion', input: TWO_Q });
  const [q] = await questions(name);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { answers: { 'Which colour?': 'Red' }, fingerprint: q.fingerprint });
  assert.equal(r.status, 400);
  assert.match(r.body.error, /no answer for/);
  assert.equal(fs.existsSync(path.join(dir, `${id}.ans`)), false);
});

test('an ask whose sidecar is gone (answered at the keyboard) is not a question any more', async () => {
  const name = await session(ASK_PROMPT);
  park(name, { tool: 'AskUserQuestion', input: ASK_INPUT });
  assert.deepEqual(await questions(name), [], 'the hook lingers; the question does not');
  sidecar('ask', name, ASK_INPUT);
  assert.equal((await questions(name)).length, 1);
});

test('gone / changed / unbound: the three refusals', async () => {
  const name = await session(BASH_PROMPT);
  const { id } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q] = await questions(name);
  const changed = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'allow', fingerprint: 'deadbeef0000' });
  assert.equal(changed.status, 409);
  assert.equal(changed.body.reason, 'changed');
  assert.equal(changed.body.question.id, q.id, 'the live question rides the refusal');
  const noFp = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'allow' });
  assert.equal(noFp.status, 400);
  const gone = await post(`/v1/sessions/${name}/questions/${id}x`, { behavior: 'allow', fingerprint: q.fingerprint });
  assert.equal(gone.status, 409);
  assert.equal(gone.body.reason, 'gone');
  // Nobody claims, and the pane shows a DIFFERENT command: unbound, nothing typed.
  const name2 = await session(BASH_PROMPT.replace('touch probe.txt', 'rm -rf /srv/other'));
  park(name2, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q2] = await questions(name2);
  const r = await post(`/v1/sessions/${name2}/questions/${q2.id}`, { behavior: 'allow', fingerprint: q2.fingerprint });
  assert.equal(r.status, 409, JSON.stringify(r.body));
  assert.equal(r.body.reason, 'unbound');
  await wait(300);
  assert.equal(typedOnPane(name2), false, 'a digit never reaches a dialog that is not this request\'s');
});

test('the hook does not claim and the pane shows THIS command: the keys answer it, via keys', async () => {
  const name = await session(BASH_PROMPT);
  park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q] = await questions(name);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'allow', fingerprint: q.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, 'keys');
  await wait(400);
  assert.equal(typedOnPane(name), true, 'the digit for Yes reached the pane');
});

test('a request answered by hand ("No" interrupts: the composer is back, nothing fires) leaves the list', async () => {
  const name = await session('', { composer: true });
  const { dir } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  // Fresh: about to draw. Then older than a moment under an idle composer.
  assert.equal((await questions(name)).length, 1);
  const f = fs.readdirSync(dir).find((x) => x.endsWith('.json'));
  const rec = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  fs.writeFileSync(path.join(dir, f), JSON.stringify({ ...rec, ts: Date.now() - 5000 }));
  assert.deepEqual(await questions(name), []);
  // A background job's request has no pane to be idle: it stays.
  fs.writeFileSync(path.join(dir, f), JSON.stringify({ ...rec, ts: Date.now() - 5000, pane: false }));
  assert.equal((await questions(name)).length, 1);
});

test('a plan: feedback rides the hook (deny + message), approval goes to the keys', async () => {
  const name = await planSession();
  const plan = { plan: '# Plan\n1. Create plan.txt\n2. Write hi into it', planFilePath: '/home/alex/.claude/plans/p.md' };
  sidecar('plan', name, plan);
  const { dir, id } = park(name, { tool: 'ExitPlanMode', input: plan });
  const [q] = await questions(name);
  assert.equal(q.kind, 'plan');
  assert.equal(q.id, id, 'the plan rides the live ExitPlanMode hook');
  assert.equal(q.input.plan, plan.plan);
  assert.equal(q.input.planFilePath, plan.planFilePath);
  const noWords = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'deny', fingerprint: q.fingerprint });
  assert.equal(noWords.status, 400, 'feedback needs words');
  const got = fakeHook(dir, id);
  const fb = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'deny', message: 'add a rollback step', fingerprint: q.fingerprint });
  assert.equal(fb.status, 200, JSON.stringify(fb.body));
  assert.equal(fb.body.via, 'hook');
  assert.deepEqual((await got).hookSpecificOutput.decision, { behavior: 'deny', message: 'add a rollback step', interrupt: false });
  await wait(300);
  assert.equal(typedOnPane(name), false);
  // Approval: the dialog's own "manually approve" row, by digit.
  const name2 = await planSession();
  sidecar('plan', name2, plan);
  const [q2] = await questions(name2);
  assert.match(q2.id, /^plan-\d+$/, 'no hook: the sidecar names the plan');
  const ok = await post(`/v1/sessions/${name2}/questions/${q2.id}`, { behavior: 'allow', fingerprint: q2.fingerprint });
  assert.equal(ok.status, 200, JSON.stringify(ok.body));
  assert.equal(ok.body.via, 'keys');
  await wait(400);
  assert.match(paneText(name2), /chose: Yes, manually approve edits/, 'row 2, the one that keeps the mode');
});

test('plan feedback with no hook is typed into the "Tell Claude what to change" row', async () => {
  const name = await planSession();
  sidecar('plan', name, { plan: '# P' });
  const [q] = await questions(name);
  const r = await post(`/v1/sessions/${name}/questions/${q.id}`, { behavior: 'deny', message: 'print the file too', fingerprint: q.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, 'keys');
  await wait(400);
  assert.match(paneText(name), /feedback: print the file too/, 'the digit focused the row, the words went in, Enter sent them');
});

// ---- the legacy /answer route and notifications read the registry ---------------

test('/answer with a registry fingerprint resolves through the hook, by the registry\'s button numbers', async () => {
  const name = await session(BASH_PROMPT);
  const { dir, id } = park(name, { tool: 'Bash', input: { command: 'touch probe.txt' } });
  const [q] = await questions(name);
  const got = fakeHook(dir, id);
  // Button 2 is the registry's "No" — not the pane's row 2 ("Yes, and always allow…").
  const r = await post(`/v1/sessions/${name}/answer`, { option: 2, fingerprint: q.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, 'hook');
  assert.equal(r.body.label, 'No');
  assert.equal((await got).hookSpecificOutput.decision.behavior, 'deny');
  await wait(300);
  assert.equal(typedOnPane(name), false);
});

test('/answer with the PANE fingerprint still works, and binds a non-Bash tool by uniqueness', async () => {
  const pane = BASH_PROMPT.replace(' Bash command', ' Edit file').replace(DASH + '\\n touch probe.txt\\n' + DASH, ' /srv/x/a.txt');
  const name = await session(pane);
  const { dir, id } = park(name, { tool: 'Edit', input: { file_path: '/srv/x/a.txt', old_string: 'a', new_string: 'b' } });
  const { body: scr } = await api(`/v1/sessions/${name}/screen`);
  assert.ok(scr.prompt, 'the dialog is a prompt');
  const got = fakeHook(dir, id);
  const r = await post(`/v1/sessions/${name}/answer`, { option: 1, fingerprint: scr.prompt.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, 'hook', 'one live request, one permission dialog: each other');
  assert.equal((await got).hookSpecificOutput.decision.behavior, 'allow');
});

test('two live requests behind one dialog: /answer by pane fingerprint types the digit (the pane breaks the tie)', async () => {
  const name = await session(BASH_PROMPT);
  park(name, { tool: 'Edit', input: { file_path: '/srv/x/a.txt' } });
  park(name, { tool: 'Edit', input: { file_path: '/srv/x/b.txt' } });
  const { body: scr } = await api(`/v1/sessions/${name}/screen`);
  const r = await post(`/v1/sessions/${name}/answer`, { option: 1, fingerprint: scr.prompt.fingerprint });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.via, undefined, 'keys');
  await wait(400);
  assert.equal(typedOnPane(name), true);
});
