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
function runHook(event, env = {}, onSpawn = null) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const sockPath = path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`, SOCK);
    const child = spawn(HOOK, [], {
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
