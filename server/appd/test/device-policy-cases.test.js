'use strict';
// One table, three programs.
//
// A remote request becomes an argv on the machine it lands on, and there are two
// programs that build it — the Kotlin runner inside the desktop app, and the Node
// runner for headless machines — plus this daemon, which holds the scope lattice
// so it can say "that machine is locked" at the moment somebody asks.
//
// Nothing makes them agree except this. The failure mode of drift is silent: a
// runner that grants Bash where the policy says `look` behaves perfectly right up
// until the day it matters, and no screen anywhere would look wrong.
//
// The Kotlin half is asserted by DevicePolicyCasesTest against the same matrix.
// Both are compared to the MATRIX rather than to each other, because two
// implementations can be wrong in the same way and a table a person can read
// cannot be wrong quietly.
//
// Binds no ports, so it has no range in the allocation table at the top of the
// route tests.

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

const REPO = path.resolve(__dirname, '../../..');
const CASES = JSON.parse(fs.readFileSync(path.join(REPO, 'shared/device-policy-cases.json'), 'utf8'));
const RUNNER = path.join(REPO, 'client/huginn-device');
const GEN = path.join(REPO, 'scripts/gen-device-policy.js');

const devicesLib = require('../lib/devices');
const table = require('../lib/device-policy-table');

test('the generated files still match the policy they came from', () => {
  // The whole arrangement rests on nobody hand-editing a generated file, which
  // people do, which is why this is a test and not a comment.
  const r = require('node:child_process').spawnSync(process.execPath, [GEN, '--check'], { encoding: 'utf8' });
  assert.strictEqual(r.status, 0,
    `shared/device-policy.json and its generated files have drifted:\n${r.stdout}${r.stderr}`);
});

test('the headless runner produces exactly the argv the matrix says', () => {
  assert.ok(CASES.cases.length >= 12, 'the matrix lost cases');
  for (const c of CASES.cases) {
    const out = JSON.parse(execFileSync(
      process.execPath, [RUNNER, 'print-argv', c.scope, String(c.locked), c.mode],
      { encoding: 'utf8' },
    ));
    const where = `${c.scope}/locked=${c.locked}/${c.mode}`;
    assert.strictEqual(out.effectiveScope, c.effectiveScope, where);
    assert.strictEqual(out.refusal, c.refusal, where);
    assert.strictEqual(out.cwd, c.cwd, where);
    assert.deepStrictEqual(out.argv, c.argv, where);
  }
});

test('no read-only case anywhere in the matrix is handed a shell', () => {
  // Stated independently of the generator, so a policy edit that widened `look`
  // would fail here even though it regenerated cleanly and every diff matched.
  for (const c of CASES.cases) {
    if (c.actGranted) continue;
    const allowed = c.argv[c.argv.indexOf('--allowedTools') + 1];
    const denied = c.argv[c.argv.indexOf('--disallowedTools') + 1];
    assert.ok(!allowed.includes('Bash'), `${c.scope}/${c.locked}/${c.mode} was granted Bash`);
    assert.ok(!allowed.includes('Write'), `${c.scope}/${c.locked}/${c.mode} was granted Write`);
    assert.ok(denied && denied.includes('Bash'), `${c.scope}/${c.locked}/${c.mode} did not DENY Bash`);
  }
});

test('a locked machine is read-only in every scope it can be enrolled at', () => {
  for (const c of CASES.cases.filter((x) => x.locked)) {
    assert.strictEqual(c.effectiveScope, table.LOCK_DROPS_TO, `${c.scope} did not drop when locked`);
    assert.strictEqual(c.actGranted, false, `${c.scope} kept act while locked`);
  }
});

test('the daemon reads the same lattice as the runners', () => {
  assert.deepStrictEqual(devicesLib.SCOPES, table.SCOPES);
  assert.deepStrictEqual(devicesLib.MODE_NEEDS, table.MODE_NEEDS);
});

test('the daemon pre-check agrees with the matrix about what it would refuse', () => {
  // Two different questions that must not give different answers: the daemon
  // decides whether to OFFER the work, the device decides whether to RUN it. A
  // daemon that offered work every device refuses is a feature that looks broken;
  // one that withheld work a device would have taken is a feature that looks dead.
  const now = Date.now();
  for (const c of CASES.cases) {
    const device = { name: 'box', scope: c.scope, locked: c.locked, lastSeen: now };
    const daemon = devicesLib.canRun(device, c.mode, now).ok;
    assert.strictEqual(daemon, c.refusal === null,
      `${c.scope}/locked=${c.locked}/${c.mode}: daemon says ${daemon}, matrix says ${c.refusal === null}`);
  }
});

test('a work item still carries no authority', () => {
  // The invariant the whole scope model rests on, asserted by ABSENCE because
  // that is the only way to catch it being added.
  const item = devicesLib.workItem({
    id: 'w', chatId: 'c', prompt: 'p', mode: 'act', model: 'opus', now: Date.now(),
  });
  const wire = JSON.stringify(item);
  for (const leak of ['allowedTools', 'disallowedTools', 'Bash', 'permission', 'scope', 'cwd', 'root']) {
    assert.ok(!wire.includes(leak), `a work item carried "${leak}": ${wire}`);
  }
});
