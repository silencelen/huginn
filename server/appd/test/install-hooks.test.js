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

// ------------------------------------------------------- W1 security fixes

test('a symlinked settings.json is written THROUGH, keeping the link and the mode', () => {
  // ~/.claude/settings.json linked into a dotfiles repo. readFileSync follows the
  // link and renameSync REPLACES it, so the first deploy used to detach the file:
  // every later repo edit then stopped reaching the CLI, and the gate entry never
  // landed in the tracked copy.
  const dir = scratch();
  const real = path.join(dir, 'dotfiles', 'settings.json');
  fs.mkdirSync(path.dirname(real));
  fs.writeFileSync(real, `${JSON.stringify({ model: 'claude-fable-5-1' }, null, 2)}\n`, { mode: 0o644 });
  const link = path.join(dir, 'settings.json');
  fs.symlinkSync(real, link);

  run(['--settings', link, '--script', SCRIPT]);

  assert.ok(fs.lstatSync(link).isSymbolicLink(), 'the symlink was replaced by a regular file');
  assert.equal(fs.readlinkSync(link), real);
  const parsed = JSON.parse(fs.readFileSync(real, 'utf8'));
  assert.equal(ours(parsed.hooks, 'SubagentStart').length, 1, 'the target never got the gate');
  assert.equal(fs.statSync(real).mode & 0o777, 0o644, 'the target mode was not carried forward');
  assert.deepEqual(fs.readdirSync(path.dirname(real)).sort(), ['settings.json'], 'no tmp left beside the target');
});

test('a present-but-non-object "hooks" is refused, exit 2, nothing written', () => {
  for (const bad of [[], 'nope', 7, null]) {
    const dir = scratch();
    const file = path.join(dir, 'settings.json');
    const body = `${JSON.stringify({ model: 'claude-fable-5-1', hooks: bad }, null, 2)}\n`;
    fs.writeFileSync(file, body);
    const { stderr } = run(['--settings', file, '--script', SCRIPT], { expect: 2 });
    assert.match(stderr, /REFUSING/);
    assert.match(stderr, /hooks/);
    assert.equal(fs.readFileSync(file, 'utf8'), body,
      `hooks: ${JSON.stringify(bad)} was overwritten instead of refused`);
    assert.deepEqual(fs.readdirSync(dir), ['settings.json']);
  }
});

test('a moved $DEST repoints our rule instead of appending a second, dead one', () => {
  // A hook whose command does not exist is read by the CLI as a BLOCK on every
  // Agent/Workflow call, so the old rule must not be left behind.
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  const gone = '/opt/huginn-appd-OLD/hooks/huginn-headroom-gate';
  fs.writeFileSync(file, `${JSON.stringify({
    hooks: {
      SubagentStart: [{ matcher: '*', hooks: [{ type: 'command', command: gone, timeout: 1800 }] }],
      PreToolUse: [
        { matcher: '.*', hooks: [{ type: 'command', command: '/usr/local/bin/huginn-claude-title PreToolUse', async: true }] },
        { matcher: 'Agent|Workflow', hooks: [{ type: 'command', command: gone, timeout: 1800 }] },
      ],
    },
  }, null, 2)}\n`);

  run(['--settings', file, '--script', SCRIPT]);

  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  const commands = (event) => (hooks[event] || []).flatMap((r) => (r.hooks || []).map((h) => h.command));
  assert.deepEqual(commands('SubagentStart'), [SCRIPT], 'the dead command survived');
  assert.deepEqual(commands('PreToolUse'),
    ['/usr/local/bin/huginn-claude-title PreToolUse', SCRIPT],
    'somebody else\'s hook must stay, and ours must be repointed rather than duplicated');
});

test('a second run after a repoint is a no-op', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  run(['--settings', file, '--script', SCRIPT]);
  const after = fs.readFileSync(file, 'utf8');
  const { stdout } = run(['--settings', file, '--script', SCRIPT]);
  assert.match(stdout, /already current/);
  assert.equal(fs.readFileSync(file, 'utf8'), after);
});

// ------------------------------------------------- an empty --settings target

test('an empty or dangling --settings is REFUSED, never the live settings file', () => {
  // `--settings "$VAR"` with VAR unset looked exactly like passing no flag at
  // all, so the tool silently operated on ~/.claude/settings.json and exited 0
  // — proved with `--settings "" --uninstall`, which strips both gate rules out
  // of the live shared file. A dangling `--settings` is worse: it swallows the
  // next flag, so `--settings --dry-run` did a REAL install into a file called
  // `./--dry-run`. `--script` has always refused the same input; this is the
  // asymmetry, not a new rule.
  const { stderr } = run(['--settings', '', '--script', SCRIPT, '--dry-run'], { expect: 2 });
  assert.match(stderr, /--settings needs a path/);

  const swallowed = run(['--settings', '--dry-run', '--script', SCRIPT], { expect: 2 });
  assert.match(swallowed.stderr, /--settings needs a path/);
  assert.equal(fs.existsSync(path.join(process.cwd(), '--dry-run')), false);

  // The env-var default is PRESENCE-based for the same reason: the run() helper
  // above sets HUGINN_CLAUDE_SETTINGS to '' on every invocation precisely so a
  // case that forgets --settings cannot reach the operator's file. It must fail
  // loudly rather than fall back to it.
  const bare = run(['--script', SCRIPT, '--dry-run'], { expect: 2 });
  assert.match(bare.stderr, /HUGINN_CLAUDE_SETTINGS/);
});

test('--script keeps refusing an empty or dangling value', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  assert.match(run(['--settings', file, '--script', ''], { expect: 2 }).stderr, /--script needs a path/);
  assert.match(run(['--settings', file, '--script', '--dry-run'], { expect: 2 }).stderr, /--script needs a path/);
});

// ------------------------------------------- binding the gate's sentinel dir

test('--headroom-dir binds the daemon\'s sentinel directory to the hook command', () => {
  // HUGINN_APPD_DATA moves the daemon's sentinel dir; the gate keeps its own
  // compiled-in /var/lib/huginn-appd/headroom; nothing reconnected them, so the
  // pause button was wired to nothing and said nothing about it (gate releases
  // at waited=0, its log lands in the abandoned dir, /v1/headroom still reports
  // the sentinel armed).
  const dir = scratch();
  const file = copyFixture(dir);
  const HR = '/srv/huginn/headroom';
  const want = `env HUGINN_HEADROOM_DIR=${HR} ${SCRIPT}`;

  const { stdout } = run(['--settings', file, '--script', SCRIPT, '--headroom-dir', HR]);
  assert.match(stdout, /added SubagentStart/);
  const commands = (hooks, event) => (hooks[event] || []).flatMap((r) => (r.hooks || []).map((h) => h.command));
  let { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.deepEqual(commands(hooks, 'SubagentStart'), [want]);
  assert.equal(commands(hooks, 'PreToolUse').includes(want), true);

  // Idempotent: the same run again must rewrite nothing.
  const before = fs.readFileSync(file, 'utf8');
  assert.match(run(['--settings', file, '--script', SCRIPT, '--headroom-dir', HR]).stdout, /already current/);
  assert.equal(fs.readFileSync(file, 'utf8'), before);

  // And an entry installed WITHOUT the dir is repointed in place, not duplicated
  // — the identity is the script's basename, which an `env` prefix does not
  // change.
  run(['--settings', file, '--script', SCRIPT]);
  ({ hooks } = JSON.parse(fs.readFileSync(file, 'utf8')));
  assert.deepEqual(commands(hooks, 'SubagentStart'), [SCRIPT], 'repointed, not a second rule');

  // Uninstall still recognises an env-carrying command as ours.
  run(['--settings', file, '--script', SCRIPT, '--headroom-dir', HR]);
  run(['--settings', file, '--script', SCRIPT, '--uninstall']);
  const after = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(JSON.stringify(after).includes('huginn-headroom-gate'), false);

  assert.match(run(['--settings', file, '--script', SCRIPT, '--headroom-dir', ''], { expect: 2 }).stderr,
    /--headroom-dir needs a path/);
});

test('a path with spaces survives the env prefix', () => {
  const { commandFor, scriptOf } = require('../install-hooks');
  const cmd = commandFor('/opt/my gate/huginn-headroom-gate', '/srv/my data/headroom');
  assert.equal(cmd, "env HUGINN_HEADROOM_DIR='/srv/my data/headroom' '/opt/my gate/huginn-headroom-gate'");
  assert.equal(scriptOf(cmd), '/opt/my gate/huginn-headroom-gate');
  assert.equal(scriptOf('/opt/huginn-appd/hooks/huginn-headroom-gate'), '/opt/huginn-appd/hooks/huginn-headroom-gate');
});

test('the permission hook installs as PermissionRequest "*" timeout 3660, beside the gate, idempotently', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const PERM = '/opt/huginn-appd/hooks/huginn-permission-hook';
  run(['--settings', file, '--script', SCRIPT]);
  run(['--settings', file, '--script', PERM]);
  const once = fs.readFileSync(file, 'utf8');
  run(['--settings', file, '--script', PERM]);
  assert.equal(fs.readFileSync(file, 'utf8'), once, 'a second run is a no-op');
  const { hooks } = JSON.parse(once);
  const rules = (hooks.PermissionRequest || []).filter((r) => (r.hooks || []).some((h) => h.command === PERM));
  assert.equal(rules.length, 1);
  assert.equal(rules[0].matcher, '*');
  assert.equal(rules[0].hooks[0].timeout, 3660);
  assert.equal(rules[0].hooks[0].async, undefined, 'it must hold the prompt, so never async');
  // The gate's own entries are untouched by the second script.
  assert.equal(ours(hooks, 'SubagentStart').length, 1);
  assert.equal(ours(hooks, 'PreToolUse').length, 1);
  assert.equal((hooks.PreToolUse || []).some((r) => r.hooks.some((h) => h.command === PERM)), false);
  // --uninstall of the permission hook takes only its own entry.
  run(['--settings', file, '--script', PERM, '--uninstall']);
  const back = JSON.parse(fs.readFileSync(file, 'utf8')).hooks;
  assert.equal(back.PermissionRequest, undefined);
  assert.equal(ours(back, 'SubagentStart').length, 1);
});

test('the modelswitch hook installs as PostModelSwitch "*" timeout 10', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const SW = '/opt/huginn-appd/hooks/huginn-modelswitch-hook';
  run(['--settings', file, '--script', SW]);
  const { hooks } = JSON.parse(fs.readFileSync(file, 'utf8'));
  const rules = (hooks.PostModelSwitch || []).filter((r) => r.hooks.some((h) => h.command === SW));
  assert.equal(rules.length, 1);
  assert.equal(rules[0].matcher, '*');
  assert.equal(rules[0].hooks[0].timeout, 10);
});

// ---- --claude-defaults ----------------------------------------------------------

const { applyClaudeDefaults, CLAUDE_DEFAULTS, FEEDBACK_DEFAULTS, FEEDBACK_ENV } = require('../install-hooks');

test('--claude-defaults adds the four settings where absent and merges into an existing env', () => {
  const dir = scratch();
  const file = copyFixture(dir);
  const before = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(before.fastModePerSessionOptIn, undefined, 'the fixture must not carry it yet');
  const { stdout } = run(['--settings', file, '--claude-defaults']);
  assert.match(stdout, /added fastModePerSessionOptIn/);
  assert.match(stdout, /added env\.CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY/);
  const after = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(after.fastModePerSessionOptIn, true);
  assert.equal(after.feedbackSurveyRate, 0);
  assert.equal(after.feedbackDrafts, 'off');
  // The fixture's own env entry survives beside ours.
  assert.deepEqual(after.env, { MCP_TIMEOUT: '120000', CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: '1' });
  // Nothing else moved: hooks, model, statusLine, plugins.
  assert.deepEqual(after.hooks, before.hooks);
  assert.equal(after.model, before.model);
  assert.deepEqual(after.statusLine, before.statusLine);
  assert.deepEqual(after.enabledPlugins, before.enabledPlugins);
});

test('--claude-defaults never changes a key that is present, and a second run writes nothing', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  fs.writeFileSync(file, `${JSON.stringify({
    feedbackSurveyRate: 1, feedbackDrafts: 'on', fastModePerSessionOptIn: false,
    env: { CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: '0' },
  }, null, 2)}\n`);
  const once = fs.readFileSync(file, 'utf8');
  const { stdout } = run(['--settings', file, '--claude-defaults']);
  assert.match(stdout, /already current/);
  assert.equal(fs.readFileSync(file, 'utf8'), once, 'an operator\'s own values are theirs');
  // And a file without the keys: the second run is byte-identical and not rewritten.
  const file2 = path.join(dir, 'fresh.json');
  fs.writeFileSync(file2, '{}\n');
  run(['--settings', file2, '--claude-defaults']);
  const written = fs.readFileSync(file2, 'utf8');
  const stat1 = fs.statSync(file2);
  run(['--settings', file2, '--claude-defaults']);
  assert.equal(fs.readFileSync(file2, 'utf8'), written);
  assert.equal(fs.statSync(file2).mtimeMs, stat1.mtimeMs);
});

test('--claude-defaults treats a key present as null as absent (Claude Code leaves fastModePerSessionOptIn: null behind)', () => {
  const dir = scratch();
  const file = path.join(dir, 'settings.json');
  fs.writeFileSync(file, `${JSON.stringify({
    fastMode: true, fastModePerSessionOptIn: null, feedbackDrafts: null,
    env: { CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: null },
  }, null, 2)}\n`);
  const { stdout } = run(['--settings', file, '--claude-defaults']);
  assert.match(stdout, /added fastModePerSessionOptIn/);
  const after = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(after.fastModePerSessionOptIn, true, 'null is nobody\'s choice');
  assert.equal(after.feedbackDrafts, 'off');
  assert.equal(after.feedbackSurveyRate, 0);
  assert.equal(after.env.CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY, '1');
  assert.equal(after.fastMode, true, 'the host-wide flag is the operator\'s and stays');
});

test('--keep-feedback (or HUGINN_KEEP_CLAUDE_FEEDBACK=1) skips the three feedback keys', () => {
  const dir = scratch();
  for (const [name, args, env] of [
    ['flag', ['--keep-feedback'], {}],
    ['env', [], { HUGINN_KEEP_CLAUDE_FEEDBACK: '1' }],
  ]) {
    const file = path.join(dir, `${name}.json`);
    fs.writeFileSync(file, '{"model":"claude-fable-5-1"}\n');
    run(['--settings', file, '--claude-defaults', ...args], { env });
    const o = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.deepEqual(o, { model: 'claude-fable-5-1', fastModePerSessionOptIn: true }, name);
  }
});

test('--claude-defaults keeps the installer\'s rules: unparseable refused, env that is not an object refused, dry run writes nothing', () => {
  const dir = scratch();
  const broken = path.join(dir, 'broken.json');
  fs.writeFileSync(broken, '{\n  "model": "x",\n');
  const r1 = run(['--settings', broken, '--claude-defaults'], { expect: 2 });
  assert.match(r1.stderr, /REFUSING/);
  assert.equal(fs.readFileSync(broken, 'utf8'), '{\n  "model": "x",\n');

  const badEnv = path.join(dir, 'badenv.json');
  fs.writeFileSync(badEnv, '{"env": ["A=1"]}\n');
  const r2 = run(['--settings', badEnv, '--claude-defaults'], { expect: 2 });
  assert.match(r2.stderr, /"env" value that is not an object/);
  assert.equal(fs.readFileSync(badEnv, 'utf8'), '{"env": ["A=1"]}\n');
  // With the feedback keys skipped, env is not ours to judge.
  run(['--settings', badEnv, '--claude-defaults', '--keep-feedback']);
  assert.deepEqual(JSON.parse(fs.readFileSync(badEnv, 'utf8')), { env: ['A=1'], fastModePerSessionOptIn: true });

  const dry = path.join(dir, 'dry.json');
  fs.writeFileSync(dry, '{}\n');
  const r3 = run(['--settings', dry, '--claude-defaults', '--dry-run']);
  assert.match(r3.stdout, /would write/);
  assert.equal(fs.readFileSync(dry, 'utf8'), '{}\n');
  assert.deepEqual(fs.readdirSync(dir).sort(), ['badenv.json', 'broken.json', 'dry.json']);

  run(['--settings', dry, '--claude-defaults', '--uninstall'], { expect: 2 });
});

test('applyClaudeDefaults is the pure heart: adds, keeps, reports', () => {
  const s = { feedbackDrafts: 'on' };
  const { changes } = applyClaudeDefaults(s);
  assert.deepEqual(s, {
    feedbackDrafts: 'on', fastModePerSessionOptIn: true, feedbackSurveyRate: 0,
    env: { CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: '1' },
  });
  assert.deepEqual(changes.filter((c) => c.action === 'kept').map((c) => c.key), ['feedbackDrafts']);
  assert.deepEqual(Object.keys(CLAUDE_DEFAULTS), ['fastModePerSessionOptIn']);
  assert.deepEqual(Object.keys(FEEDBACK_DEFAULTS).sort(), ['feedbackDrafts', 'feedbackSurveyRate']);
  assert.deepEqual(FEEDBACK_ENV, { CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: '1' });
});
