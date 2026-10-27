'use strict';
// hooks/huginn-permission-hook, run for real against a private tmux pane: the
// guards that keep it from ever holding a headless run, what it records, and the
// hand-off. Claude Code 2.1.306 payload shapes.
require('./tmux-cleanup'); // its scratch tmux server and socket go with the process
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const HOOK = path.join(__dirname, '..', 'hooks', 'huginn-permission-hook');
const SWITCH_HOOK = path.join(__dirname, '..', 'hooks', 'huginn-modelswitch-hook');
const SOCK = `huginn-permhook-${process.pid}`;
const SESSION = `ph-${process.pid}`;
let stateDir, pane;

const tmux = (args) => execFileSync('tmux', ['-L', SOCK, ...args], { encoding: 'utf8' }).trim();

before(() => {
  stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'permhook-state-'));
  tmux(['new-session', '-d', '-s', SESSION, 'sleep 600']);
  pane = tmux(['display-message', '-p', '-t', `=${SESSION}:`, '#{pane_id}']);
  fs.writeFileSync(path.join(stateDir, SESSION), JSON.stringify({ state: 'attention', sessionId: 'sid-top' }));
});
after(() => {
  try { tmux(['kill-server']); } catch { /* gone */ }
  fs.rmSync(stateDir, { recursive: true, force: true });
});

/** Run the hook; resolves {code, out, ms}. `env` overrides; the private socket is how tmux finds the pane. */
function runHook(event, env = {}, onSpawn = null, script = HOOK) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const sockPath = path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`, SOCK);
    const child = spawn(script, [], {
      env: {
        PATH: process.env.PATH, HUGINN_CLAUDE_STATE_DIR: stateDir, HUGINN_PERM_POLL: '0.05', HUGINN_PERM_WAIT: '20',
        TMUX: `${sockPath},0,0`, TMUX_PANE: pane, ...env,
      },
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.on('close', (code) => resolve({ code, out, ms: Date.now() - t0 }));
    child.stdin.end(JSON.stringify(event));
    if (onSpawn) onSpawn(child);
  });
}
const ev = (over = {}) => ({ hook_event_name: 'PermissionRequest', session_id: 'sid-top', tool_name: 'Bash',
  tool_input: { command: 'touch x' }, permission_mode: 'default', cwd: '/srv/x', ...over });
const permDir = (sess = SESSION, dir = stateDir) => path.join(dir, '.perm', sess);
const requestFiles = (sess = SESSION, dir = stateDir) => {
  try { return fs.readdirSync(permDir(sess, dir)).filter((f) => f.endsWith('.json')); } catch { return []; }
};
/** Waits for the hook's request file, then answers it with `decision` (or runs `act`). */
function whenParked(act, sess = SESSION, dir = null) {
  return () => {
    const iv = setInterval(() => {
      const d = dir || stateDir;
      const files = requestFiles(sess, d);
      if (!files.length) return;
      clearInterval(iv);
      const req = JSON.parse(fs.readFileSync(path.join(permDir(sess, d), files[0]), 'utf8'));
      act(req, path.join(permDir(sess, d), `${req.id}.ans`));
    }, 30);
  };
}
const answerWith = (decision) => whenParked((req, ans) => {
  fs.writeFileSync(`${ans}.tmp`, JSON.stringify(decision)); fs.renameSync(`${ans}.tmp`, ans);
});
const ALLOW = { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } };

test('no tmux and no job (cron, headless -p): returns at once, prints nothing, writes nothing', async () => {
  const r = await runHook(ev(), { TMUX: '', TMUX_PANE: '' });
  assert.equal(r.code, 0); assert.equal(r.out, ''); assert.ok(r.ms < 3000);
  assert.deepEqual(requestFiles(), []);
});

test('a session id that is not the pane\'s top-level claude (a nested claude -p): returns at once', async () => {
  const r = await runHook(ev({ session_id: 'sid-nested' }));
  assert.equal(r.out, ''); assert.ok(r.ms < 3000);
  assert.deepEqual(requestFiles(), []);
});

test('EVERY tool parks — Bash, Edit, an MCP tool, AskUserQuestion, ExitPlanMode — and the record carries what the registry shows', async () => {
  const seen = [];
  const cases = [
    ev({ permission_suggestions: [{ type: 'addDirectories', directories: ['/srv/x'], destination: 'session' }] }),
    ev({ tool_name: 'Edit', tool_input: { file_path: '/srv/x/a.txt', old_string: 'a', new_string: 'b' }, agent_id: 'agent-7', agent_type: 'Explore' }),
    ev({ tool_name: 'mcp__grafana__query', tool_input: { q: 'up' }, mcp_server: { name: 'grafana', source: 'user' } }),
    ev({ tool_name: 'AskUserQuestion', tool_input: { questions: [{ question: 'Q?', options: [{ label: 'A' }, { label: 'B' }] }] } }),
    ev({ tool_name: 'ExitPlanMode', tool_input: { plan: '# P', planFilePath: '/home/alex/.claude/plans/p.md' } }),
  ];
  for (const c of cases) {
    const r = await runHook(c, {}, whenParked((req, ans) => {
      seen.push(req);
      fs.writeFileSync(`${ans}.tmp`, JSON.stringify(ALLOW)); fs.renameSync(`${ans}.tmp`, ans);
    }));
    assert.equal(r.code, 0);
    assert.deepEqual(JSON.parse(r.out), ALLOW, c.tool_name);
  }
  assert.deepEqual(seen.map((r) => r.tool), ['Bash', 'Edit', 'mcp__grafana__query', 'AskUserQuestion', 'ExitPlanMode']);
  for (const r of seen) {
    assert.equal(r.v, 1); assert.equal(r.sessionId, 'sid-top'); assert.equal(r.pane, true);
    assert.equal(r.permissionMode, 'default'); assert.equal(r.cwd, '/srv/x');
    assert.ok(Number.isInteger(r.pid) && Math.abs(r.ts - Date.now()) < 60_000);
  }
  assert.deepEqual(seen[0].suggestions, [{ type: 'addDirectories', directories: ['/srv/x'], destination: 'session' }]);
  assert.equal(seen[1].suggestions, null);
  assert.equal(seen[1].agentId, 'agent-7'); assert.equal(seen[1].agentType, 'Explore');
  assert.equal(seen[0].agentId, null);
  assert.deepEqual(seen[2].mcpServer, { name: 'grafana', source: 'user' });
  assert.deepEqual(seen[4].input, { plan: '# P', planFilePath: '/home/alex/.claude/plans/p.md' });
  assert.deepEqual(fs.readdirSync(permDir()), [], 'requests and claimed answers removed');
});

test('a deny with a message is printed as given (ExitPlanMode feedback rides this; measured honoured on 2.1.306)', async () => {
  const deny = { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'deny', message: 'add a rollback step', interrupt: false } } };
  const r = await runHook(ev({ tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } }), {}, answerWith(deny));
  assert.deepEqual(JSON.parse(r.out), deny);
});

test('a malformed answer is claimed but NOT printed (the dialog stays for the keys)', async () => {
  const r = await runHook(ev(), {}, whenParked((req, ans) => {
    fs.writeFileSync(ans, '{"hookSpecificOutput":{"decision":{"behavior":"allow"}}}');
  }));
  assert.equal(r.out, '');
});

test('nobody answers: leaves at its own deadline with nothing printed and nothing left behind', async () => {
  const r = await runHook(ev(), { HUGINN_PERM_WAIT: '1' });
  assert.equal(r.code, 0); assert.equal(r.out, '');
  assert.deepEqual(fs.readdirSync(permDir()), []);
});

test('its request file dropped (the title hook saw the tool\'s PostToolUse): the wait ends at once', async () => {
  const r = await runHook(ev(), { HUGINN_PERM_WAIT: '20' }, whenParked((req) => {
    fs.unlinkSync(path.join(permDir(), `${req.id}.json`));
  }));
  assert.equal(r.code, 0); assert.equal(r.out, '');
  assert.ok(r.ms < 5000, `left within a poll, not at the deadline (${r.ms} ms)`);
});

test('in its slow phase the hook still claims an app answer inside appd\'s hand-off window', async () => {
  // Past FAST_FOR the hook polls at POLL_SLOW; appd's deliver() retracts an
  // answer the hook has not claimed within 2.5 s and falls back to the keys. So
  // the slow poll must stay well inside that window — forced on from the start.
  const ph = require('../lib/permhook');
  let delivering = null;
  const r = await runHook(ev(), { HUGINN_PERM_FAST_FOR: '0', HUGINN_PERM_POLL_SLOW: '1' }, whenParked((req) => {
    delivering = ph.deliver(stateDir, SESSION, req, { behavior: 'allow' });
  }));
  assert.equal(r.code, 0);
  assert.equal(await delivering, true, 'claimed before deliver() gave up and retracted');
  assert.deepEqual(JSON.parse(r.out), ALLOW, 'the decision went out through the hook');
});

test('a background job (no pane anywhere above it, CLAUDE_JOB_DIR set) parks under the session whose state file names its id, pane: false', async () => {
  // Claude Code's bg-pty-host hands a job's hooks no TMUX/TMUX_PANE and no pane
  // sits above them. HUGINN_PERM_FORCE_BG stands in for "no ancestor has them"
  // (a subreaper hands this test's orphans back to a pane of its own).
  const bgDir = fs.mkdtempSync(path.join(os.tmpdir(), 'permhook-bg-'));
  fs.writeFileSync(path.join(bgDir, 'bgsess'), JSON.stringify({ state: 'running', sessionId: 'sid-bg', job: 'job1' }));
  fs.writeFileSync(path.join(bgDir, 'other'), JSON.stringify({ state: 'idle', sessionId: 'sid-other' }));
  const env = { TMUX: '', TMUX_PANE: '', CLAUDE_JOB_DIR: '/nonexistent/jobs/job1/', HUGINN_PERM_FORCE_BG: '1', HUGINN_CLAUDE_STATE_DIR: bgDir };
  let seen = null;
  const r = await runHook(ev({ session_id: 'sid-bg' }), env, whenParked((req, ans) => {
    seen = req;
    fs.writeFileSync(`${ans}.tmp`, JSON.stringify(ALLOW)); fs.renameSync(`${ans}.tmp`, ans);
  }, 'bgsess', bgDir));
  assert.equal(r.code, 0);
  assert.ok(seen, 'parked under the job\'s session');
  assert.equal(seen.pane, false, 'no pane backs a job\'s request');
  assert.equal(seen.sessionId, 'sid-bg');
  assert.deepEqual(JSON.parse(r.out), ALLOW, 'the job\'s prompt was answered through the hook');
  assert.equal(fs.existsSync(path.join(bgDir, '.perm', 'other')), false);
  fs.rmSync(bgDir, { recursive: true, force: true });
});

test('a background job whose session id no state file names: returns at once', async () => {
  const bgDir = fs.mkdtempSync(path.join(os.tmpdir(), 'permhook-bg-'));
  fs.writeFileSync(path.join(bgDir, 'other'), JSON.stringify({ state: 'idle', sessionId: 'sid-other' }));
  const r = await runHook(ev({ session_id: 'sid-unknown' }), { TMUX: '', TMUX_PANE: '', CLAUDE_JOB_DIR: '/nonexistent/jobs/j2/', HUGINN_CLAUDE_STATE_DIR: bgDir });
  assert.equal(r.out, ''); assert.ok(r.ms < 3000);
  assert.equal(fs.existsSync(path.join(bgDir, '.perm')), false);
  fs.rmSync(bgDir, { recursive: true, force: true });
});

// ---- hooks/huginn-modelswitch-hook (PostModelSwitch recorder) ---------------

const sw = (over = {}) => ({ hook_event_name: 'PostModelSwitch', session_id: 'sid-top', from_model: 'claude-fable-5-1',
  to_model: 'claude-opus-5-5', requested_model: 'opus', source: 'picker', ...over });
const swFile = () => path.join(stateDir, '.modelswitch', SESSION);

test('modelswitch hook: records the top-level claude\'s switch', async () => {
  const r = await runHook(sw(), {}, null, SWITCH_HOOK);
  assert.equal(r.code, 0); assert.equal(r.out, '');
  const rec = JSON.parse(fs.readFileSync(swFile(), 'utf8'));
  assert.equal(rec.v, 1); assert.equal(rec.to, 'claude-opus-5-5'); assert.equal(rec.source, 'picker');
  assert.equal(rec.sessionId, 'sid-top'); assert.ok(Math.abs(rec.ts - Date.now()) < 5000);
});

test('modelswitch hook: a nested claude or no tmux pane writes nothing', async () => {
  fs.rmSync(swFile(), { force: true });
  await runHook(sw({ session_id: 'sid-nested' }), {}, null, SWITCH_HOOK);
  await runHook(sw(), { TMUX_PANE: '' }, null, SWITCH_HOOK);
  assert.equal(fs.existsSync(swFile()), false);
});
