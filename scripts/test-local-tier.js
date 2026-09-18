'use strict';
// The local tier's PERSISTENCE gate: does this machine still serve after the
// person at the keyboard logs out, and does it say so. Run with:
//   node --test scripts/test-local-tier.js
//
// WHY THIS FILE EXISTS, and why it is a shim rather than a systemd.
//
// Every fact this feature turns on is a fact about a machine nobody is sitting
// at: a headless box with no logind, a laptop that logs out, a host with
// neither pkexec nor sudo. None of them is THIS box. A test that drove the real
// `systemctl` and `loginctl` would assert the state of the runner instead of
// the logic, pass on one machine and fail on the next, and — worst of all —
// would report green for the exact bug this feature exists to remove, because
// on a box where linger happens to be on, "user unit with no linger" never
// occurs. So every external command the manager runs goes through an injectable
// runner (`setRunner`), and the unit files are generated into a temp dir and
// read back as text.
//
// PORTS: this file binds nothing. scripts/test-llm-shim.js holds 18790-18799;
// the appd files own 8788-10799 (table in any server/appd/test/routes-*.js).

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const MGR = path.resolve(__dirname, '..', 'client', 'huginn-local');
const local = require(MGR);

let tmp;
let calls;

/**
 * A recorded, scripted `spawnSync`. `plan` maps a command name to a reply (or
 * to a function of argv); anything unscripted answers like a missing binary,
 * which is the honest default — most of these programs are absent somewhere.
 */
function shim(plan = {}) {
  calls = [];
  local.setRunner((cmd, args = [], opts = {}) => {
    calls.push([cmd, ...args].join(' '));
    const base = path.basename(cmd);
    const entry = plan[base];
    const reply = typeof entry === 'function' ? entry(args, opts) : entry;
    if (reply === undefined) return { status: 127, stdout: '', stderr: `${base}: not found`, error: new Error('ENOENT') };
    return { status: 0, stdout: '', stderr: '', ...reply };
  });
}

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'local-tier-'));
  process.env.HUGINN_LOCAL_DIR = tmp;
  // Pinned so `User=` and the linger subject are the same on every machine
  // this ever runs on, including a CI runner whose user is `runner`.
  process.env.HUGINN_LOCAL_USER = 'owner';
});
after(() => {
  local.setRunner(null);
  fs.rmSync(tmp, { recursive: true, force: true });
});
beforeEach(() => { shim(); });

// --------------------------------------------------------------- unit files
//
// The two unit scopes are two different promises about the machine, and the
// difference is four lines of text. Asserted as TEXT because that is what
// systemd reads — a test that asserted the options object would pass while the
// template drifted.

test('a SYSTEM unit is installed for the machine, not for a session', () => {
  const u = local.unitText('llm', { system: true, user: 'owner', home: '/home/owner', dir: '/srv/hl' });
  assert.match(u, /^WantedBy=multi-user\.target$/m,
    'a system unit wanted by default.target belongs to a user manager and never starts at boot');
  assert.match(u, /^User=owner$/m,
    'a system unit with no User= runs llama-server as root');
  assert.match(u, /^Environment=HOME=\/home\/owner$/m);
  assert.match(u, /^Environment=HUGINN_LOCAL_DIR=\/srv\/hl$/m);
});

test('a USER unit names no user and hangs off the login session', () => {
  const u = local.unitText('llm', { system: false, user: 'owner', home: '/home/owner', dir: '/srv/hl' });
  assert.match(u, /^WantedBy=default\.target$/m);
  assert.doesNotMatch(u, /^User=/m,
    'User= in a --user unit is a startup failure, not a permission');
});

test('the runner unit pins the device dir in both scopes', () => {
  for (const system of [true, false]) {
    const u = local.unitText('runner', { system, dir: '/srv/hl' });
    assert.match(u, /^Environment=HUGINN_DEVICE_DIR=\/srv\/hl\/device$/m,
      'a runner that reads a different config loops forever while systemd calls it healthy');
  }
});

test('the two units really are written where the two scopes live', () => {
  assert.equal(local.unitFile('huginn-local-llm', { system: true }),
    '/etc/systemd/system/huginn-local-llm.service');
  assert.equal(local.unitFile('huginn-local-llm', { system: false, home: '/home/owner' }),
    '/home/owner/.config/systemd/user/huginn-local-llm.service');
});

// ----------------------------------------------------------------- linger

test('linger is read out of logind, and "no answer" is not a no', () => {
  shim({ loginctl: { stdout: 'Linger=yes\n' } });
  assert.equal(local.lingerState('owner'), true);
  shim({ loginctl: { stdout: 'Linger=no\n' } });
  assert.equal(local.lingerState('owner'), false);
  // A box with no logind at all. Reading this as `false` would put a permanent
  // false alarm on every machine that runs its units as root.
  shim({});
  assert.equal(local.lingerState('owner'), null);
  // Present but unparseable — same rule.
  shim({ loginctl: { stdout: 'Something=else\n' } });
  assert.equal(local.lingerState('owner'), null);
});

test('enabling linger reports the refusal instead of swallowing it', () => {
  shim({ loginctl: { status: 1, stderr: 'Failed to enable linger: Access denied\n' } });
  const r = local.enableLinger('owner');
  assert.equal(r.ok, false);
  assert.match(r.error, /Access denied/);
  shim({ loginctl: { status: 0 } });
  assert.equal(local.enableLinger('owner').ok, true);
  assert.ok(calls.some((c) => c === 'loginctl enable-linger owner'), calls.join(' | '));
});

// ------------------------------------------------------------ persistence
//
// THE QUESTION. `systemctl --user is-active` answers `active` for a user unit
// five seconds before its owner logs out, so every other signal on the box
// agrees with the wrong answer.

test('persistence is three-valued, and Windows is persistent by construction', () => {
  assert.deepEqual(local.persistence({}, 'win32'), { systemUnits: true, linger: null, persistent: true });
  // A system unit needs no linger and must not claim to have consulted it.
  assert.deepEqual(local.persistence({ systemUnits: true }, 'linux'),
    { systemUnits: true, linger: null, persistent: true });
});

test('a user unit is persistent ONLY when linger says so', () => {
  shim({ loginctl: { stdout: 'Linger=yes\n' } });
  assert.deepEqual(local.persistence({ systemUnits: false }, 'linux'),
    { systemUnits: false, linger: true, persistent: true });
  // ⚠ The default state of every Linux install this manager has ever done.
  shim({ loginctl: { stdout: 'Linger=no\n' } });
  assert.deepEqual(local.persistence({ systemUnits: false }, 'linux'),
    { systemUnits: false, linger: false, persistent: false });
  shim({});
  assert.deepEqual(local.persistence({ systemUnits: false }, 'linux'),
    { systemUnits: false, linger: null, persistent: null });
});

test('the copy names the failure and the way out of it', () => {
  const stops = local.persistenceLine({ systemUnits: false, linger: false, persistent: false }, 'linux');
  assert.match(stops, /STOPS WHEN YOU LOG OUT/);
  assert.match(stops, /huginn local persist/, 'a diagnosis with no action is a complaint');
  assert.match(local.persistenceLine({ systemUnits: true, linger: null, persistent: true }, 'linux'),
    /serves while you are logged out/);
  assert.match(local.persistenceLine({ systemUnits: true, linger: null, persistent: true }, 'win32'),
    /LocalSystem/);
  // "cannot tell" must never borrow the reassuring sentence.
  const unknown = local.persistenceLine({ systemUnits: false, linger: null, persistent: null }, 'linux');
  assert.doesNotMatch(unknown, /serves while you are logged out/);
});

test('doctor FAILS on a user unit with no linger — it does not note it', () => {
  assert.equal(local.persistenceVerdict({ systemUnits: false, linger: false, persistent: false }).level, 'FAIL');
  assert.equal(local.persistenceVerdict({ systemUnits: true, linger: null, persistent: true }).level, 'ok');
  // Unknown is not a failure: a box with no logind to ask has done nothing wrong.
  assert.equal(local.persistenceVerdict({ systemUnits: false, linger: null, persistent: null }).level, 'note');
});

test('the consent card says which kind of services it is about to install', () => {
  assert.match(local.servicePlanLine({ system: true }, 'linux'), /logged out/);
  assert.match(local.servicePlanLine({}, 'win32'), /LocalSystem/);
  // ⚠ The line this feature exists to correct: "always-on" over a user unit.
  const plain = local.servicePlanLine({}, 'linux');
  assert.match(plain, /STOP when you log out/);
  assert.doesNotMatch(plain, /always-on/);
});

// ------------------------------------------------------------- elevation

test('one elevation, preferring the desktop prompt, and null is a real answer', () => {
  shim({ sh: (args) => (/pkexec|sudo/.test(args[1]) ? { stdout: '/usr/bin/x\n' } : undefined) });
  assert.equal(local.elevator({ platform: 'linux', root: false }), 'pkexec');
  shim({ sh: (args) => (/sudo/.test(args[1]) ? { stdout: '/usr/bin/sudo\n' } : { status: 1 }) });
  assert.equal(local.elevator({ platform: 'linux', root: false }), 'sudo');
  // A box with neither still gets a working tier — it gets the linger
  // fallback and is told. Refusing here would be the worse answer.
  shim({ sh: { status: 1 } });
  assert.equal(local.elevator({ platform: 'linux', root: false }), null);
  assert.equal(local.elevator({ platform: 'linux', root: true }), 'root');
  assert.equal(local.elevator({ platform: 'win32', root: false }), null);
});

// ⚠ AND THE LIST IS A CHAIN, NOT A PREFERENCE. elevator() picked the first tool
// that merely EXISTS and no caller ever tried the next one - so on a headless box
// where polkitd is installed but no agent is registered (the default for any ssh
// session without a tty agent) pkexec exits non-zero and a WORKING sudo sat
// unused: `on --system` downgraded to a user unit + linger, `update` hard-aborted
// "nothing was changed", and `persist` tore the user units down and put them
// back. CHANGELOG.md, docs/LOCAL-TIER.md and LocalServe.kt all document
// "pkexec, then sudo" as a chain.
test('a pkexec that cannot ASK falls through to sudo', () => {
  shim({
    sh: (args) => (/pkexec|sudo/.test(args[1]) ? { stdout: '/usr/bin/x\n' } : { status: 1 }),
    // 127 is pkexec's "the authentication failed / no agent" - nothing could ask.
    pkexec: { status: 127, stderr: 'No authentication agent found' },
    sudo: { status: 0 },
  });
  const r = local.elevateOnce(['on', '--yes'], () => {}, { platform: 'linux', root: false });
  assert.equal(r.ok, true, 'sudo was available and would have worked');
  assert.equal(r.tool, 'sudo');
  assert.ok(calls.some((c) => c.startsWith('sudo env ')),
    'sudo was never tried: ' + calls.join(' | '));
});

test('a DISMISSED pkexec dialog does not re-prompt with sudo', () => {
  shim({
    sh: (args) => (/pkexec|sudo/.test(args[1]) ? { stdout: '/usr/bin/x\n' } : { status: 1 }),
    // 126 is "the user dismissed the authentication dialog". A person said no;
    // asking again with sudo would break the one rule this shape exists for.
    pkexec: { status: 126 },
    sudo: { status: 0 },
  });
  const r = local.elevateOnce(['on', '--yes'], () => {}, { platform: 'linux', root: false });
  assert.equal(r.ok, false);
  assert.equal(r.declined, true, 'a dismissed dialog is a decision, not a missing agent');
  assert.ok(!calls.some((c) => c.startsWith('sudo env ')),
    'ONE elevation prompt: ' + calls.join(' | '));
});

test('update names the one word that fixes a failed elevation', () => {
  // The refusal used to end at "nothing was changed" and never mention sudo,
  // though `sudo huginn local update` works - the block is gated on !isRoot().
  const msg = local.updateElevationRefusal('exit 127');
  assert.match(msg, /sudo huginn local update/);
  assert.match(msg, /nothing was changed/);
});

test('the elevated argv carries the three variables that decide where it works', () => {
  const [cmd, args] = local.elevationArgv('pkexec', ['on', '--yes'], {
    HOME: '/home/owner', HUGINN_LOCAL_DIR: '/srv/hl', HUGINN_LOCAL_USER: 'owner',
    HUGINN_SHIM_SOURCE: undefined,
  });
  assert.equal(cmd, 'pkexec');
  assert.equal(args[0], 'env', 'pkexec and sudo both wipe the environment');
  assert.ok(args.includes('HOME=/home/owner'),
    'a dropped HOME is an install into /root/.config that reports success');
  assert.ok(args.includes('HUGINN_LOCAL_DIR=/srv/hl'));
  assert.ok(args.includes('HUGINN_LOCAL_USER=owner'),
    'without this the unit installs User=root and linger is enabled for an account with no session');
  assert.ok(!args.some((a) => a.startsWith('HUGINN_SHIM_SOURCE')),
    'an unset variable must not travel as the empty string');
  assert.deepEqual(args.slice(-2), ['on', '--yes']);
});

// --------------------------------------------------------------- persist
//
// The migration is nothing BUT its order, so the order is the test.

test('persist takes the old units down before it puts the new ones up', () => {
  const kinds = local.persistSteps({ home: '/home/owner', user: 'owner' }).map((s) => s.kind);
  assert.deepEqual(kinds, [
    'stop-user', 'disable-user', 'remove-user-units', 'reload-user',
    'write-system-units', 'reload-system', 'enable-system', 'hand-back', 'record',
  ]);
  const steps = local.persistSteps({ home: '/home/owner', user: 'owner' });
  const stop = steps.find((s) => s.kind === 'stop-user');
  assert.deepEqual(stop.names, ['huginn-local-runner', 'huginn-local-llm'],
    'the runner stops FIRST or it re-enrols into the gap');
  const enable = steps.find((s) => s.kind === 'enable-system');
  assert.deepEqual(enable.names, ['huginn-local-llm', 'huginn-local-runner'],
    'the llm comes up first — the same enrolment race the managed install avoids');
  // The old unit FILES go, or a later logout resurrects a second llama-swap
  // onto the port the system unit now holds.
  assert.deepEqual(steps.find((s) => s.kind === 'remove-user-units').files, [
    '/home/owner/.config/systemd/user/huginn-local-runner.service',
    '/home/owner/.config/systemd/user/huginn-local-llm.service',
  ]);
  assert.deepEqual(steps.find((s) => s.kind === 'write-system-units').files, [
    '/etc/systemd/system/huginn-local-llm.service',
    '/etc/systemd/system/huginn-local-runner.service',
  ]);
  // Handing the tree back is not tidiness: api-key is 0600 and the service
  // runs as `User=`, so a root-owned key is a server that 401s its own door.
  assert.equal(steps.find((s) => s.kind === 'hand-back').user, 'owner');
});

// ---------------------------------------------------------------- adapter

test('llama-swap is adoptable, on our port, and it gets the key gate', () => {
  assert.equal(local.ADAPTERS['llama-swap'].url, 'http://127.0.0.1:8748');
  assert.equal(local.ADAPTERS['llama-swap'].keyGate, true,
    'an adopted llama-swap can take an --api-key-file, so it must clear the same gate a managed one does');
  // LM Studio and Ollama commonly run open on loopback; the same hard gate
  // there would refuse every ordinary install, so they keep the note.
  assert.equal(local.ADAPTERS.lmstudio.keyGate, false);
  assert.equal(local.ADAPTERS.ollama.keyGate, false);
});

// ------------------------------------------------------------- statusInfo

test('status answers the logout question, in the fields the desktop decodes', async () => {
  const dir = fs.mkdtempSync(path.join(tmp, 'status-'));
  process.env.HUGINN_LOCAL_DIR = dir;
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', llmSlug: 'box',
    // A closed port: health is not what this test is about, and a hang would be.
    port: 1, systemUnits: false, modelsBySlug: { tiny: {} }, defaultModel: 'tiny',
  }));
  // `runuser` because a test run AS ROOT must aim `systemctl --user` at the
  // owner's manager, not root's — see userSystemctl.
  shim({ loginctl: { stdout: 'Linger=no\n' }, systemctl: { stdout: 'active\n' }, runuser: { stdout: 'active\n' } });
  const s = await local.statusInfo();
  assert.equal(s.setup, true);
  assert.equal(s.systemUnits, false);
  assert.equal(s.linger, false);
  assert.equal(s.persistent, false);
  assert.equal(s.adopted, false);
  // The old fields are still there — the desktop decodes one object.
  assert.equal(s.deviceName, 'box-llm');
  assert.equal(s.services.llm, 'active');

  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'adapter', adapter: { kind: 'llama-swap', url: 'http://127.0.0.1:1' }, adopted: true,
    deviceName: 'box-llm', systemUnits: true,
  }));
  const a = await local.statusInfo();
  assert.equal(a.adopted, true, 'a surface that cannot see this offers to stop a service it does not own');
  assert.equal(a.persistent, true);
  assert.match(a.services.llm, /adopted/);
  process.env.HUGINN_LOCAL_DIR = tmp;
});

// ------------------------------------------------- the verbs, end to end
//
// Driven as real processes with stub systemd/logind on PATH, because the
// ORDER these verbs do things in is the part that can be wrong while every
// unit test passes.

function stubBin(dir, name, body) {
  fs.mkdirSync(dir, { recursive: true });
  const f = path.join(dir, name);
  fs.writeFileSync(f, `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  return f;
}

/**
 * `runuser -u owner -- systemctl …` reduced to `systemctl …`.
 *
 * Present because these tests may run AS ROOT (this host does), and root's
 * `systemctl --user` bus is root's own: the manager therefore aims the call at
 * the owner's manager through runuser, and a stub that did not model that
 * would test a code path nobody takes on a real elevated run.
 */
function stubRunuser(dir) {
  stubBin(dir, 'runuser', 'shift 3\nexec "$@"');
}

function runMgr(argv, { dir, bin, home, env = {} } = {}) {
  return spawnSync(process.execPath, [MGR, ...argv], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      HOME: home,
      HUGINN_LOCAL_DIR: dir,
      HUGINN_LOCAL_USER: 'owner',
      ...env,
    },
  });
}

test('off never stops the engine it adopted', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'off-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(dir, { recursive: true });
  const log = path.join(root, 'systemctl.log');
  stubBin(bin, 'systemctl', `echo "$@" >> ${log}\nexit 0`);
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  stubRunuser(bin);
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'adapter', adopted: true, adapter: { kind: 'llama-swap', url: 'http://127.0.0.1:8748' },
    deviceName: 'box-llm', systemUnits: false,
  }));
  // The deregistration will fail (no daemon, no runner on disk) and `off`
  // exits nonzero — deliberately: the disable steps run BEFORE that, which is
  // exactly the window this asserts.
  runMgr(['off'], { dir, bin, home: root });
  const seen = fs.existsSync(log) ? fs.readFileSync(log, 'utf8') : '';
  assert.match(seen, /huginn-local-runner/, 'the runner is ours and must come down');
  assert.doesNotMatch(seen, /huginn-local-llm/,
    'adapter mode never installed the model server — stopping it takes down a service this tool did not start');
});

test('off still stops both services for a managed install', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'offm-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(dir, { recursive: true });
  const log = path.join(root, 'systemctl.log');
  stubBin(bin, 'systemctl', `echo "$@" >> ${log}\nexit 0`);
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  stubRunuser(bin);
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', port: 1, systemUnits: false,
  }));
  runMgr(['off'], { dir, bin, home: root });
  const seen = fs.readFileSync(log, 'utf8');
  assert.match(seen, /huginn-local-runner/);
  assert.match(seen, /huginn-local-llm/);
});

test('adopting on top of a managed install is refused, not merged', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'adopt-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(dir, { recursive: true });
  stubBin(bin, 'systemctl', 'exit 0');
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  const conf = { mode: 'managed', class: 'C', deviceName: 'box-llm', url: 'http://127.0.0.1:1' };
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify(conf));
  const r = runMgr(['on', '--adapter', 'llama-swap', '--yes', '--url', 'http://127.0.0.1:1'], { dir, bin, home: root });
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /already holds a MANAGED install/);
  // And it changed nothing: local.json and the api key belong to the managed
  // tier, and `off` would then delete a key that is not ours.
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, 'local.json'), 'utf8')), conf);
});

test('an unknown adapter is refused with the list, not swallowed', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'adopt2-'));
  const bin = path.join(root, 'bin');
  stubBin(bin, 'systemctl', 'exit 0');
  const r = runMgr(['on', '--adapter', 'llamaswap', '--yes', '--url', 'http://127.0.0.1:1'],
    { dir: path.join(root, 'data'), bin, home: root });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /llama-swap/);
});

test('doctor exits nonzero on a user unit with no linger, and names it', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'doc-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(dir, { recursive: true });
  stubBin(bin, 'systemctl', 'echo active');
  stubBin(bin, 'loginctl', 'echo Linger=no');
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', port: 1, systemUnits: false,
  }));
  const r = runMgr(['doctor'], { dir, bin, home: root });
  assert.match(r.stdout, /FAIL {2}persistence/,
    'a checkup whose only word for this is a note agrees with the machine\'s own wrong answer');
  assert.notEqual(r.status, 0, 'doctor must FAIL, not merely mention it');

  // ...and it is genuinely conditional: linger on, and this line is ok.
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  const ok = runMgr(['doctor'], { dir, bin, home: root });
  assert.match(ok.stdout, /ok {4}persistence/);
});

test('status prints the persistence line for a human too', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'stat-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(dir, { recursive: true });
  stubBin(bin, 'systemctl', 'echo active');
  stubBin(bin, 'loginctl', 'echo Linger=no');
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', port: 1, systemUnits: false,
  }));
  const r = runMgr(['status'], { dir, bin, home: root });
  assert.match(r.stdout, /^persistence .*STOPS WHEN YOU LOG OUT/m, r.stdout);
  const j = runMgr(['status', '--json'], { dir, bin, home: root });
  const s = JSON.parse(j.stdout.split('\n').find((l) => l.startsWith('{')));
  assert.equal(s.persistent, false);
  assert.equal(s.linger, false);
  assert.equal(s.systemUnits, false);
  assert.equal(s.adopted, false);
});

test('plan tells the consent card how the one prompt would happen', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'plan-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  stubBin(bin, 'pkexec', 'exit 0');
  const r = runMgr(['plan', '--json'], { dir, bin, home: root });
  const p = JSON.parse(r.stdout.split('\n').find((l) => l.trim().startsWith('{')));
  // 'root' when the gate itself runs as root (this host does), 'pkexec'
  // otherwise — the PREFERENCE order is asserted in the unit test above; what
  // matters to the card is that a way exists and is named.
  assert.ok(['root', 'pkexec'].includes(p.elevation), String(p.elevation));
  assert.equal(p.persistent, true, 'an elevator means the install can be system-scoped');
  // And `plan` is still read-only, which is the whole contract of the verb.
  assert.ok(!fs.existsSync(path.join(dir, 'local.json')));
});
