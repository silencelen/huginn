'use strict';
// hooks/huginn-permission-hook, run for real against a private tmux pane: the
// guards that keep it from ever holding a headless run, and the hand-off.
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
        PATH: process.env.PATH, HUGINN_CLAUDE_STATE_DIR: stateDir, HUGINN_PERM_POLL: '0.05',
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
  tool_input: { command: 'touch x' }, ...over });
const permDir = () => path.join(stateDir, '.perm', SESSION);

test('no TMUX_PANE (cron, headless -p): returns at once, prints nothing, writes nothing', async () => {
  const r = await runHook(ev(), { TMUX_PANE: '' });
  assert.equal(r.code, 0); assert.equal(r.out, ''); assert.ok(r.ms < 2000);
  assert.equal(fs.existsSync(permDir()), false);
});

test('a session id that is not the pane\'s top-level claude (a nested claude -p): returns at once', async () => {
  const r = await runHook(ev({ session_id: 'sid-nested' }));
  assert.equal(r.out, ''); assert.ok(r.ms < 2000);
  assert.equal(fs.existsSync(permDir()), false);
});

test('ExitPlanMode: returns at once (the CLI ignores a hook allow there)', async () => {
  const r = await runHook(ev({ tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } }));
  assert.equal(r.out, ''); assert.ok(r.ms < 2000);
});

test('the hand-off: writes the request, prints the decision appd leaves, cleans up', async () => {
  const decision = { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } };
  const r = await runHook(ev(), { HUGINN_PERM_WAIT: '20' }, () => {
    const iv = setInterval(() => {
      let files = [];
      try { files = fs.readdirSync(permDir()).filter((f) => f.endsWith('.json')); } catch { return; }
      if (!files.length) return;
      clearInterval(iv);
      const req = JSON.parse(fs.readFileSync(path.join(permDir(), files[0]), 'utf8'));
      assert.equal(req.v, 1); assert.equal(req.tool, 'Bash'); assert.equal(req.sessionId, 'sid-top');
      assert.deepEqual(req.input, { command: 'touch x' });
      const ans = path.join(permDir(), `${req.id}.ans`);
      fs.writeFileSync(`${ans}.tmp`, JSON.stringify(decision)); fs.renameSync(`${ans}.tmp`, ans);
    }, 30);
  });
  assert.equal(r.code, 0);
  assert.deepEqual(JSON.parse(r.out), decision);
  assert.deepEqual(fs.readdirSync(permDir()), [], 'request and claimed answer removed');
});

test('a malformed answer is claimed but NOT printed (the dialog stays for the keys)', async () => {
  const r = await runHook(ev(), { HUGINN_PERM_WAIT: '20' }, () => {
    const iv = setInterval(() => {
      let files = [];
      try { files = fs.readdirSync(permDir()).filter((f) => f.endsWith('.json')); } catch { return; }
      if (!files.length) return;
      clearInterval(iv);
      const id = files[0].slice(0, -5);
      fs.writeFileSync(path.join(permDir(), `${id}.ans`), '{"hookSpecificOutput":{"decision":{"behavior":"allow"}}}');
    }, 30);
  });
  assert.equal(r.out, '');
});

test('nobody answers: leaves at its own deadline with nothing printed and nothing left behind', async () => {
  const r = await runHook(ev(), { HUGINN_PERM_WAIT: '1' });
  assert.equal(r.code, 0); assert.equal(r.out, '');
  assert.deepEqual(fs.readdirSync(permDir()), []);
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
