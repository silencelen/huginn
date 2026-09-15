'use strict';
// install-hooks.js — the merge into ~/.claude/settings.json.
//
// SAFETY: every case runs the real CLI against a COPY in a scratch tmpdir, named
// explicitly with --settings (or through HUGINN_CLAUDE_SETTINGS). The operator's
// own settings file is never the target of a test, and the default path is only
// ever asserted, never written.
//
// PORT ALLOCATION — nothing here binds a socket. install-hooks.js, lib/sentinels
// and hooks/huginn-headroom-gate are filesystem-only, so this file and its two
// siblings (sentinels.test.js, hook-gate.test.js) take NO block from the table
// in routes-lifecycle.test.js and add none to it.

const { test } = require('node:test');
const assert = require('node:assert');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const CLI = path.join(__dirname, '..', 'install-hooks.js');
const FIXTURE = path.join(__dirname, 'fixtures', 'settings-with-title-hook.json');
const SCRIPT = '/opt/huginn-appd/hooks/huginn-headroom-gate';

// The entry that must survive untouched. It is the huginn title hook: it matches
// EVERY tool (`.*`) and it is `async: true`, which is what keeps it from costing
// 10 s on every tool call. A merge that rebuilds the PreToolUse array instead of
// appending to it drops that flag, and the session then stalls on its own title
// updates — with nothing in the diff to explain why.
const TITLE_HOOK_BYTES = [
  '      {',
  '        "matcher": ".*",',
  '        "hooks": [',
  '          {',
  '            "type": "command",',
  '            "command": "/usr/local/bin/huginn-claude-title PreToolUse",',
  '            "timeout": 10,',
  '            "async": true',
  '          }',
  '        ]',
  '      }',
].join('\n');

function scratch() {
  return fs.mkdtempSync(path.join(os.tmpdir(), `inst-hooks-${process.pid}-`));
}

function run(args, { env = {}, expect = 0 } = {}) {
  let status = 0;
  let stdout = '';
  let stderr = '';
  try {
    stdout = execFileSync(process.execPath, [CLI, ...args], {
      encoding: 'utf8',
      env: { ...process.env, HUGINN_CLAUDE_SETTINGS: '', ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (e) {
    status = e.status;
    stdout = e.stdout || '';
    stderr = e.stderr || '';
  }
  assert.equal(status, expect, `exit ${status}\n${stdout}${stderr}`);
  return { stdout, stderr };
}

function copyFixture(dir, name = 'settings.json') {
  const file = path.join(dir, name);
  fs.copyFileSync(FIXTURE, file);
  return file;
}

function ours(hooks, event) {
  return (hooks[event] || []).filter(
    (r) => (r.hooks || []).some((h) => h.command === SCRIPT),
  );
}

test('the title hook survives the merge byte for byte', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const before = fs.readFileSync(file, 'utf8');
  assert.ok(before.includes(TITLE_HOOK_BYTES), 'fixture must carry the async title hook');

  run(['--settings', file, '--script', SCRIPT]);

  const after = fs.readFileSync(file, 'utf8');
  assert.ok(after.includes(TITLE_HOOK_BYTES), 'title hook was rewritten by the merge');
  // And nothing else moved: the whole file is the old bytes plus our two rules.
  const parsed = JSON.parse(after);
  assert.equal(parsed.model, 'claude-fable-5-1');
  assert.equal(parsed.hooks.PreToolUse.length, 2, 'appended, not replaced');
  assert.equal(parsed.hooks.SessionEnd.length, 2, 'untouched events keep their rules');
});

test('our entries carry no async, and the matcher/timeout the spike proved', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  run(['--settings', file, '--script', SCRIPT]);
  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));

  const sub = ours(hooks, 'SubagentStart');
  const pre = ours(hooks, 'PreToolUse');
  assert.equal(sub.length, 1);
  assert.equal(pre.length, 1);
  assert.equal(sub[0].matcher, '*');
  assert.equal(pre[0].matcher, 'Agent|Workflow');
  for (const rule of [sub[0], pre[0]]) {
    assert.equal(rule.hooks.length, 1);
    const h = rule.hooks[0];
    assert.equal(h.type, 'command');
    assert.equal(h.command, SCRIPT);
    // SECONDS. And the gate self-releases at timeout-30.
    assert.equal(h.timeout, 1800);
    // The one that cannot be seen when it is wrong: an async hook is fired and
    // forgotten, so it holds nothing at all.
    assert.ok(!('async' in h), 'the gate must never be async');
  }
});

test('a second run is a no-op, byte for byte', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  run(['--settings', file, '--script', SCRIPT]);
  const once = fs.readFileSync(file, 'utf8');
  const stat1 = fs.statSync(file);

  const { stdout } = run(['--settings', file, '--script', SCRIPT]);
  assert.match(stdout, /already current/);
  assert.equal(fs.readFileSync(file, 'utf8'), once, 'idempotent by command');
  assert.equal(fs.statSync(file).mtimeMs, stat1.mtimeMs, 'and the file was not even rewritten');
});

test('--uninstall removes only ours and hands the file back unchanged', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const before = fs.readFileSync(file, 'utf8');
  run(['--settings', file, '--script', SCRIPT]);
  run(['--settings', file, '--script', SCRIPT, '--uninstall']);
  assert.equal(fs.readFileSync(file, 'utf8'), before, 'uninstall is the exact inverse');
});

test('--uninstall leaves a foreign hook that shares our rule', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  fs.writeFileSync(file, `${JSON.stringify({
    hooks: {
      SubagentStart: [{
        matcher: '*',
        hooks: [
          { type: 'command', command: SCRIPT, timeout: 1800 },
          { type: 'command', command: '/usr/local/bin/somebody-else', timeout: 5 },
        ],
      }],
    },
  }, null, 2)}\n`);
  run(['--settings', file, '--script', SCRIPT, '--uninstall']);
  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(hooks.SubagentStart.length, 1);
  assert.deepEqual(hooks.SubagentStart[0].hooks.map((h) => h.command), ['/usr/local/bin/somebody-else']);
});

test('an unparseable settings file is refused, exit 2, nothing written', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  // The shape a half-finished hand edit leaves behind.
  const broken = '{\n  "model": "claude-fable-5-1",\n  "hooks": {\n';
  fs.writeFileSync(file, broken);
  const { stderr } = run(['--settings', file, '--script', SCRIPT], { expect: 2 });
  assert.match(stderr, /REFUSING/);
  assert.equal(fs.readFileSync(file, 'utf8'), broken, 'a file we could not read is a file we do not write');
  assert.deepEqual(fs.readdirSync(dir), ['settings.json'], 'and no tmp left behind');
});

test('--dry-run writes nothing', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const before = fs.readFileSync(file, 'utf8');
  const { stdout } = run(['--settings', file, '--script', SCRIPT, '--dry-run']);
  assert.match(stdout, /would write/);
  assert.match(stdout, /added SubagentStart/);
  assert.equal(fs.readFileSync(file, 'utf8'), before);
  assert.deepEqual(fs.readdirSync(dir), ['settings.json']);
});

test('a settings file with no hooks key gets one, and keeps everything else', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  fs.writeFileSync(file, `${JSON.stringify({ model: 'claude-fable-5-1', env: { MCP_TIMEOUT: '120000' } }, null, 2)}\n`);
  run(['--settings', file, '--script', SCRIPT]);
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(parsed.model, 'claude-fable-5-1');
  assert.deepEqual(parsed.env, { MCP_TIMEOUT: '120000' });
  assert.equal(ours(parsed.hooks, 'SubagentStart').length, 1);
  assert.equal(ours(parsed.hooks, 'PreToolUse').length, 1);
});

test('half-installed: SubagentStart already ours, PreToolUse still missing', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  fs.writeFileSync(file, `${JSON.stringify({
    hooks: {
      SubagentStart: [{ matcher: '*', hooks: [{ type: 'command', command: SCRIPT, timeout: 1800 }] }],
    },
  }, null, 2)}\n`);
  const { stdout } = run(['--settings', file, '--script', SCRIPT]);
  assert.match(stdout, /kept SubagentStart/);
  assert.match(stdout, /added PreToolUse/);
  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(hooks.SubagentStart.length, 1, 'no duplicate rule');
  assert.equal(ours(hooks, 'PreToolUse').length, 1);
});

test('HUGINN_CLAUDE_SETTINGS is the path when --settings is not given', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  run(['--script', SCRIPT], { env: { HUGINN_CLAUDE_SETTINGS: file } });
  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(ours(hooks, 'PreToolUse').length, 1);
});
