'use strict';
// The headless runner's Windows half: how an npm shim is spawned, and what
// `unit --windows` prints. Binds no ports, so no range in the port table.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const RUNNER = path.join(__dirname, '..', '..', '..', 'client', 'huginn-device');
const R = require(RUNNER);

test('the managed local tier runs a fixed engine and generate scope, not device.json\'s', () => {
  // device.json sits in a directory the installing account can write, so huginn
  // core can reseed the token there. A hand-edited engine or scope must not pick
  // what the LocalSystem runner spawns: under HUGINN_LOCAL_DIR (set only by the
  // locked service definition) the engine is the fixed shim under the tree's own
  // bin\ and the scope is generate, whatever device.json says.
  const poisoned = { scope: 'own', llm: 'C:\\Users\\mallory\\payload.exe', claude: 'C:\\Users\\mallory\\c.exe', url: 'http://x', name: 'rig' };
  const dir = 'C:\\ProgramData\\huginn-local';
  const pinned = R.managedLocalTier(poisoned, dir);
  assert.equal(pinned.scope, 'generate', 'scope is forced to generate, so an ask/act run never reaches claude');
  assert.equal(pinned.llm, path.join(dir, 'bin', 'huginn-llm-shim.js'),
    'the engine is the fixed shim under the tree\'s own bin, never the one named in device.json');
  // A plain, hand-enrolled device (no HUGINN_LOCAL_DIR) is left exactly as it was.
  assert.deepEqual(R.managedLocalTier(poisoned, undefined), poisoned);
  assert.deepEqual(R.managedLocalTier(poisoned, ''), poisoned);
});

test('a .js engine runs through node, a binary runs as itself', () => {
  assert.deepEqual(R.execFor('/x/shim.js', ['-p']), [process.execPath, ['/x/shim.js', '-p']]);
  assert.deepEqual(R.execFor('/usr/local/bin/claude', ['-p']), ['/usr/local/bin/claude', ['-p']]);
});

test('an npm claude.cmd shim runs the cli.js beside it, never a shell', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hd-shim-'));
  try {
    const shim = path.join(dir, 'claude.cmd');
    fs.writeFileSync(shim, '@echo off\r\n');
    // No cli.js yet: spawned as given, so the failure names the real reason.
    assert.deepEqual(R.execFor(shim, ['-p']), [shim, ['-p']]);
    const cli = path.join(dir, 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
    fs.mkdirSync(path.dirname(cli), { recursive: true });
    fs.writeFileSync(cli, '// cli\n');
    assert.deepEqual(R.execFor(shim, ['-p']), [process.execPath, [cli, '-p']]);
    // The EXTENSION's case, not the path's: Windows does not care and the
    // shim is matched by name, while the directory has to exist here on Linux.
    const upper = path.join(dir, 'claude.CMD');
    fs.copyFileSync(shim, upper);
    assert.deepEqual(R.execFor(upper, ['-p'])[0], process.execPath, 'case-insensitive, as Windows is');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('unit --windows prints a scheduled task that runs as the account, forever, at boot', () => {
  const r = spawnSync(process.execPath, [RUNNER, 'unit', '--windows',
    '--user', 'HOME\alex', '--home', 'C:\\Users\\alex.HOME',
    '--node', 'C:\\Program Files\\nodejs\\node.exe', '--self', 'C:\\Users\\alex.HOME\\.huginn\\huginn-device',
    '--conf-dir', 'C:\\Users\\alex.HOME\\.config\\huginn', '--name', 'RIG'], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  const out = r.stdout;
  assert.match(out, /\$user = 'HOME\alex'/, 'the account, quoted for PowerShell');
  assert.match(out, /Get-Credential/, 'the password is asked for, never written');
  assert.ok(!/-Password '/.test(out), 'and never appears as a literal');
  assert.match(out, /New-ScheduledTaskTrigger -AtStartup/);
  assert.match(out, /-ExecutionTimeLimit \(New-TimeSpan -Seconds 0\)/, 'the 72-hour default stop is off');
  assert.match(out, /-RestartCount 999/);
  assert.match(out, /-MultipleInstances IgnoreNew/, 'never two runners under one id');
  assert.match(out, /set HUGINN_DEVICE_DIR=C:\\Users\\alex\.HOME\\\.config\\huginn/, 'the wrapper carries the config dir');
  assert.match(out, /set USERPROFILE=C:\\Users\\alex\.HOME/, 'and the profile claude\'s login lives in');
  assert.match(out, /"C:\\Program Files\\nodejs\\node\.exe" "C:\\Users\\alex\.HOME\\\.huginn\\huginn-device" serve/);
  assert.match(out, /Unregister-ScheduledTask/, 'the undo is printed with it');
});

test('a bad flag to unit --windows is a refusal, not a snippet', () => {
  const r = spawnSync(process.execPath, [RUNNER, 'unit', '--windows', '--bogus'], { encoding: 'utf8' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /huginn-device unit:/);
});
