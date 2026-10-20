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
// PORTS: only ports the kernel picks (listen on 0), for the engine identity
// cases at the end. scripts/test-llm-shim.js holds 18790-18799; the appd files
// own 8788-10799 (table in any server/appd/test/routes-*.js).

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const http = require('node:http');
const path = require('node:path');
const readline = require('node:readline');
const { spawnSync, spawn } = require('node:child_process');

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
  assert.equal(local.persistenceVerdict({ systemUnits: false, linger: false, persistent: false }).level, 'fail');
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
// back. CHANGELOG.md, docs/design/local-tier.md and LocalServe.kt all document
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

// ⚠ `off` DELETES local.json, AND ITS OWN MESSAGE INVITES A SECOND RUN.
// "off, not gone: N model file(s) remain ... (or --purge for everything)" sends
// somebody back for `off --purge`, which then arrives with `conf = stored || {}`
// and has lost the install's SCOPE and MODE. On a --system install every
// systemd call in that second run flipped to user scope: the purge deleted
// $HOME/.config/systemd/user/huginn-local-{llm,runner}.service - files this
// install never owned - never touched /etc/systemd/system, and printed
// "ok remove the systemd unit files" and "Off." at exit 0.
test('off then off --purge removes the units it really installed, and only those', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'purge-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  const etc = path.join(root, 'etc');            // a stand-in /etc/systemd/system
  const userUnits = path.join(root, 'home', '.config', 'systemd', 'user');
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'models'), { recursive: true });
  fs.mkdirSync(etc, { recursive: true });
  fs.mkdirSync(userUnits, { recursive: true });
  const log = path.join(root, 'systemctl.log');
  stubBin(bin, 'systemctl', `echo "$@" >> ${log}\nexit 0`);
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  stubRunuser(bin);
  // A deregistration that succeeds, so run 1 gets as far as deleting local.json
  // - which is the precondition this whole case is about.
  fs.writeFileSync(path.join(dir, 'bin', 'huginn-device.js'), 'process.exit(0);\n');
  fs.writeFileSync(path.join(dir, 'models', 'tiny.gguf'), 'x');
  fs.writeFileSync(path.join(dir, 'api-key'), 'secret\n');
  fs.writeFileSync(path.join(dir, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', port: 1, systemUnits: true,
  }));
  for (const n of ['huginn-local-llm', 'huginn-local-runner']) {
    fs.writeFileSync(path.join(etc, `${n}.service`), '[Unit]\n');
  }
  // Not ours, in the directory a scope-guessing purge reached into.
  fs.writeFileSync(path.join(userUnits, 'someone-elses.service'), 'NOT OURS\n');
  const env = { HUGINN_SYSTEMD_SYSTEM_DIR: etc };
  const home = path.join(root, 'home');
  const one = runMgr(['off'], { dir, bin, home, env });
  assert.ok(!fs.existsSync(path.join(dir, 'local.json')),
    'off takes the credentials with it - that is the whole verb: ' + one.stdout + one.stderr);
  fs.rmSync(log, { force: true });
  const two = runMgr(['off', '--purge', '--yes'], { dir, bin, home, env });
  for (const n of ['huginn-local-llm', 'huginn-local-runner']) {
    assert.ok(!fs.existsSync(path.join(etc, `${n}.service`)),
      `${n} was installed in /etc and the purge never looked there: ` + two.stdout + two.stderr);
  }
  assert.ok(fs.existsSync(path.join(userUnits, 'someone-elses.service')),
    'a purge reaches for its OWN unit names, never for a directory');
  // ...and the reload is aimed at the bus the units were actually on. A purge
  // that guessed user scope also ran `systemctl --user daemon-reload`, which
  // tells the wrong manager about a change it did not have.
  const seen = fs.readFileSync(log, 'utf8');
  assert.match(seen, /^daemon-reload$/m,
    'the system bus was never told the units went: ' + seen);
});

// ⚠ AND THE LOST `mode` IS THE NASTIER HALF. On an ADAPTER install the second
// run deletes <localDir>/api-key - the adopted engine's key, which the code has
// a comment explaining it must preserve - and disables the engine `off` promised
// never to stop. An adapter install only ever wrote the RUNNER unit, so what is
// on disk still says which kind this is.
test('off --purge with no config keeps an adopted engine key and its engine', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'purgead-'));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  const etc = path.join(root, 'etc');
  const log = path.join(root, 'systemctl.log');
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true });
  fs.mkdirSync(etc, { recursive: true });
  stubBin(bin, 'systemctl', `echo "$@" >> ${log}\nexit 0`);
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  stubRunuser(bin);
  fs.writeFileSync(path.join(dir, 'bin', 'huginn-device.js'), 'process.exit(0);\n');
  fs.writeFileSync(path.join(dir, 'api-key'), 'somebody-elses-key\n');
  // Adapter mode: the runner unit only. No local.json - this is the second run.
  fs.writeFileSync(path.join(etc, 'huginn-local-runner.service'), '[Unit]\n');
  const two = runMgr(['off', '--purge-models', '--yes'],
    { dir, bin, home: path.join(root, 'home'), env: { HUGINN_SYSTEMD_SYSTEM_DIR: etc } });
  assert.ok(fs.existsSync(path.join(dir, 'api-key')),
    'the adopted engine authenticates with a key this tier did not mint: ' + two.stdout + two.stderr);
  const seen = fs.existsSync(log) ? fs.readFileSync(log, 'utf8') : '';
  assert.doesNotMatch(seen, /huginn-local-llm/,
    'adapter mode never installed the model server - stopping it takes down somebody else\'s service');
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
  assert.match(r.stdout, /fail {2}persistence/,
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

// ------------------------------------------------- ProgramData lock (F14/F2)
//
// lockTree itself only runs on Windows (icacls), but the whole security of it
// is the DECISION and the ORDER, which are pure. These assert the fixes for
// F14 (a pre-created tree is taken over and its explicit ACEs wiped, not just
// the inherited ones) and the owner/verify logic, without a Windows box.

test('a pre-existing tree is adopted only from SYSTEM, Administrators or the installer', () => {
  const me = 'S-1-5-21-10-20-30-1001';
  assert.ok(local.ownerTrusted('S-1-5-18', me), 'SYSTEM');
  assert.ok(local.ownerTrusted('S-1-5-32-544', me), 'Administrators');
  assert.ok(local.ownerTrusted(me, me), 'the installer');
  // The attack: another standard user pre-created C:\ProgramData\huginn-local.
  assert.ok(!local.ownerTrusted('S-1-5-21-10-20-30-1055', me), 'another local account');
  assert.ok(!local.ownerTrusted('', me), 'an unreadable owner is never trusted');
});

test('lockTree takes ownership and wipes EVERY explicit ACE before granting', () => {
  const me = 'S-1-5-21-10-20-30-1001';
  const steps = local.lockTreeSteps('C:\\ProgramData\\huginn-local', me);
  const joined = steps.map(([cmd, args]) => `${cmd} ${args.join(' ')}`);
  // 1. Administrators own the whole tree first — removes a pre-creator's
  //    implicit WRITE_DAC (the heart of F14). The old lock never did this.
  assert.match(joined[0], /^icacls C:\\ProgramData\\huginn-local \/setowner \*S-1-5-32-544 .*\/t/,
    joined[0]);
  // 2. /reset discards explicit ACEs; /inheritance:r alone (the old code) left
  //    an attacker's explicit full-control entry in place.
  assert.ok(joined.some((s) => /\/reset\b.*\/t/.test(s)), 'no recursive /reset step: ' + joined.join(' | '));
  // 3. The grant removes inheritance, names exactly the three trustees, and
  //    recurses so an adopted dir's existing children are relocked.
  const grant = joined.find((s) => s.includes('/inheritance:r') && s.includes('/grant:r'));
  assert.ok(grant, 'no grant step');
  assert.match(grant, /\*S-1-5-18:\(OI\)\(CI\)F/);
  assert.match(grant, /\*S-1-5-32-544:\(OI\)\(CI\)F/);
  assert.match(grant, new RegExp('\\*' + me.replace(/-/g, '\\-') + ':\\(OI\\)\\(CI\\)RX'));
  assert.match(grant, /\/t\b/, 'the grant must recurse (/t) to fix adopted children');
  // 4. device\ opens to the installer so huginn core can reseed the token.
  //    (path.join uses the HOST separator; icacls only ever runs on Windows,
  //    so match the device step by its arg array, not the separator.)
  const devStep = steps.find(([cmd, args]) => cmd === 'icacls'
    && /[\\/]device$/.test(args[0]) && args.includes(`*${me}:(OI)(CI)M`));
  assert.ok(devStep, 'no device change grant for the installer');
  assert.ok(devStep[1].includes('/t'), 'the device grant must recurse (/t)');
});

test('the lock verify catches a tree that is not actually locked', () => {
  const me = 'S-1-5-21-10-20-30-1001';
  // The good end state: Administrators own it, inheritance off, only our three.
  assert.equal(local.verifyLockDecision(
    { ownerSid: 'S-1-5-32-544', protected: true, sids: ['S-1-5-18', 'S-1-5-32-544', me] }, me), null);
  // Inheritance still on (icacls /inheritance:r silently did nothing).
  assert.match(local.verifyLockDecision(
    { ownerSid: 'S-1-5-32-544', protected: false, sids: ['S-1-5-18'] }, me), /inheritance/);
  // Ownership never moved off the attacker.
  assert.match(local.verifyLockDecision(
    { ownerSid: 'S-1-5-21-10-20-30-1055', protected: true, sids: ['S-1-5-18'] }, me), /owned by/);
  // An attacker ACE survived the reset.
  assert.match(local.verifyLockDecision(
    { ownerSid: 'S-1-5-32-544', protected: true, sids: ['S-1-5-18', 'S-1-5-21-10-20-30-1055'] }, me),
    /did not grant/);
  // A completely unreadable ACL is a soft failure (its own sentence).
  assert.match(local.verifyLockDecision(null, me), /could not read/);
});

test('parseAclLines reads the Get-Acl shape winAclInfo asks for', () => {
  const info = local.parseAclLines(
    'OWNER=S-1-5-32-544\r\nPROTECTED=True\r\nACE=S-1-5-18\r\nACE=S-1-5-32-544\r\nACE=S-1-5-21-1-2-3-1001\r\n');
  assert.equal(info.ownerSid, 'S-1-5-32-544');
  assert.equal(info.protected, true);
  assert.deepEqual(info.sids, ['S-1-5-18', 'S-1-5-32-544', 'S-1-5-21-1-2-3-1001']);
  assert.equal(local.parseAclLines('OWNER=x\nPROTECTED=False').protected, false);
});

// ------------------------------------------ token seeding after the lock (F2/F17)
//
// On Windows the wrapper and the desktop no longer write the root-equivalent
// token into ProgramData themselves; they stage it owner-only in the user
// profile and the elevated manager writes it AFTER lockTree. The write itself
// (random O_EXCL temp, 0600, rename, no residue) is cross-platform and runs here.

test('seedTokenFromStage writes the token only after the lock, 0600, then shreds the stage', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'seed-'));
  const dir = path.join(root, 'data');
  const stage = path.join(root, 'stage-token');
  fs.writeFileSync(stage, '  the-real-token\n'); // as the profile stage holds it
  const savedDir = process.env.HUGINN_LOCAL_DIR;
  const savedSeed = process.env.HUGINN_SEED_TOKEN_FILE;
  try {
    process.env.HUGINN_LOCAL_DIR = dir;
    process.env.HUGINN_SEED_TOKEN_FILE = stage;
    assert.equal(local.seedTokenFromStage(), true);
    const tok = path.join(dir, 'device', 'appd-token');
    assert.equal(fs.readFileSync(tok, 'utf8'), 'the-real-token', 'trimmed token lands');
    if (process.platform !== 'win32') {
      assert.equal(fs.statSync(tok).mode & 0o777, 0o600, 'token is 0600');
    }
    assert.ok(!fs.existsSync(stage), 'the staging copy is shredded');
    // No temp residue left in device\.
    const residue = fs.readdirSync(path.join(dir, 'device')).filter((n) => n.includes('.tmp'));
    assert.deepEqual(residue, [], 'left a temp behind: ' + residue.join(','));
  } finally {
    if (savedDir === undefined) delete process.env.HUGINN_LOCAL_DIR; else process.env.HUGINN_LOCAL_DIR = savedDir;
    if (savedSeed === undefined) delete process.env.HUGINN_SEED_TOKEN_FILE; else process.env.HUGINN_SEED_TOKEN_FILE = savedSeed;
  }
});

test('seedTokenFromStage is a no-op with nothing staged, and refuses an empty stage', () => {
  const savedSeed = process.env.HUGINN_SEED_TOKEN_FILE;
  try {
    delete process.env.HUGINN_SEED_TOKEN_FILE;
    assert.equal(local.seedTokenFromStage(), false, 'no env = old path, writes nothing');
    const root = fs.mkdtempSync(path.join(tmp, 'seed-empty-'));
    const stage = path.join(root, 'empty');
    fs.writeFileSync(stage, '   \n');
    process.env.HUGINN_SEED_TOKEN_FILE = stage;
    const saved = process.env.HUGINN_LOCAL_DIR;
    process.env.HUGINN_LOCAL_DIR = path.join(root, 'data');
    try {
      assert.throws(() => local.seedTokenFromStage(), /empty/);
    } finally {
      if (saved === undefined) delete process.env.HUGINN_LOCAL_DIR; else process.env.HUGINN_LOCAL_DIR = saved;
    }
  } finally {
    if (savedSeed === undefined) delete process.env.HUGINN_SEED_TOKEN_FILE; else process.env.HUGINN_SEED_TOKEN_FILE = savedSeed;
  }
});

test('writeTokenSecure creates 0600 under a wide umask and replaces an existing file', () => {
  if (process.platform === 'win32') return; // mode bits are POSIX
  const root = fs.mkdtempSync(path.join(tmp, 'wts-'));
  const dest = path.join(root, 'device', 'appd-token');
  const old = process.umask(0); // the umask-after-write trap: a default umask would leave 0644
  try {
    local.writeTokenSecure(dest, 'first');
    assert.equal(fs.statSync(dest).mode & 0o777, 0o600);
    assert.equal(fs.readFileSync(dest, 'utf8'), 'first');
    local.writeTokenSecure(dest, 'second'); // rename replaces
    assert.equal(fs.readFileSync(dest, 'utf8'), 'second');
    assert.equal(fs.statSync(dest).mode & 0o777, 0o600);
  } finally {
    process.umask(old);
  }
});

// ------------------------------------------------------- engine identity
//
// The manager hands the engine a key in its health check (`on`, `update`,
// `status`, `doctor`) and records, when it adopts an engine, which account
// serves it — the record the shim then holds every connection to. Both go
// through the shim's identity check (engineKit). A listener that is not the
// engine is asked nothing, and is sent no key.

const SHIM_SRC = path.resolve(__dirname, '..', 'client', 'huginn-llm-shim');
const MY_UID = typeof process.getuid === 'function' ? process.getuid() : null;
const LINUX = process.platform === 'linux';

/** The manager as its own process, without blocking this one: the listeners below live in it. */
function runMgrAsync(argv, { dir, bin, home, env = {} } = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [MGR, ...argv], {
      env: {
        ...process.env, PATH: `${bin}:${process.env.PATH}`, HOME: home,
        HUGINN_LOCAL_DIR: dir, HUGINN_LOCAL_USER: 'owner', ...env,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '', stderr = '';
    child.stdout.on('data', (c) => { stdout += c; });
    child.stderr.on('data', (c) => { stderr += c; });
    child.on('close', (status) => resolve({ status, stdout, stderr }));
  });
}

/** An Ollama stand-in in this process: lists one model and records each request. */
async function fakeOllama() {
  const seen = [];
  const srv = http.createServer((req, res) => {
    seen.push({ url: req.url, auth: req.headers.authorization || null });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ models: [{ name: 'tiny:1b' }] }));
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  return { seen, port: srv.address().port, close: () => new Promise((r) => srv.close(() => r())) };
}

/** A machine this manager can adopt onto: stub systemd/logind, the shim and a runner that enrols nothing. */
function adoptionBox(name) {
  const root = fs.mkdtempSync(path.join(tmp, name));
  const dir = path.join(root, 'data');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(path.join(dir, 'bin'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'bin', 'huginn-device.js'), 'process.exit(0);\n');
  stubBin(bin, 'systemctl', 'exit 0');
  stubBin(bin, 'loginctl', 'echo Linger=yes');
  stubRunuser(bin);
  return { root, dir, bin, home: path.join(root, 'home'), env: { HUGINN_SHIM_SOURCE: SHIM_SRC } };
}

test('adopting an engine records which account serves it, and the key goes only to that account', { skip: !LINUX && 'Linux only' }, async () => {
  const box = adoptionBox('adopt-id-');
  const ol = await fakeOllama();
  try {
    const r = await runMgrAsync(['on', '--adapter', 'ollama', '--adapter-url', `http://127.0.0.1:${ol.port}`,
      '--adapter-key', 'adapter-secret', '--yes', '--url', 'http://127.0.0.1:1'], box);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const conf = JSON.parse(fs.readFileSync(path.join(box.dir, 'local.json'), 'utf8'));
    assert.equal(conf.adapter.engine.uid, MY_UID, 'the account that accepted the probe');
    assert.equal(conf.adapter.engine.exe, process.execPath, 'and its program, for the record');
    assert.match(r.stdout, new RegExp(`is served by uid ${MY_UID}, pid \\d+`), 'said before the consent question');
    assert.ok(ol.seen.length >= 1 && ol.seen.every((q) => q.auth === 'Bearer adapter-secret'),
      `the list was asked with the key, over the judged connection: ${JSON.stringify(ol.seen)}`);
  } finally { await ol.close(); }
});

test('adopting a port nothing serves is refused before anything is recorded', { skip: !LINUX && 'Linux only' }, async () => {
  const box = adoptionBox('adopt-none-');
  const probe = net.createServer();
  await new Promise((r) => probe.listen(0, '127.0.0.1', r));
  const port = probe.address().port;
  await new Promise((r) => probe.close(r));
  const r = await runMgrAsync(['on', '--adapter', 'ollama', '--adapter-url', `http://127.0.0.1:${port}`, '--yes', '--url', 'http://127.0.0.1:1'], box);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /is not answering/);
  assert.ok(!fs.existsSync(path.join(box.dir, 'local.json')), 'nothing adopted, nothing written');
});

test('update records who serves an engine adopted before identities were', { skip: !LINUX && 'Linux only' }, async () => {
  const box = adoptionBox('adopt-upd-');
  const ol = await fakeOllama();
  try {
    fs.writeFileSync(path.join(box.dir, 'local.json'), JSON.stringify({
      mode: 'adapter', adopted: true, class: 'adapter', deviceName: 'box-llm', systemUnits: false,
      adapter: { kind: 'ollama', url: `http://127.0.0.1:${ol.port}`, key: null },
      modelsBySlug: { 'tiny-1b': { file: 'tiny:1b', display: 'tiny:1b' } }, defaultModel: 'tiny-1b',
    }));
    const before = await runMgrAsync(['status'], box);
    assert.match(before.stdout, /^identity +not recorded, so the shim refuses this engine until it is: huginn local update$/m,
      'status says why every run is refused, and the verb that fixes it');
    const r = await runMgrAsync(['update'], box);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /engine {2}recorded: .* is served by uid/);
    const conf = JSON.parse(fs.readFileSync(path.join(box.dir, 'local.json'), 'utf8'));
    assert.equal(conf.adapter.engine.uid, MY_UID);
    assert.equal(modeOf(path.join(box.dir, 'local.json')), 0o600);
    const after = await runMgrAsync(['status'], box);
    assert.doesNotMatch(after.stdout, /^identity/m, after.stdout);
  } finally { await ol.close(); }
});

const modeOf = (f) => fs.statSync(f).mode & 0o777;

/**
 * A managed install whose api-key exists, and whose runtime/ holds a link to
 * node: the program the stand-in engine runs, so it is "under runtime/".
 */
function managedBox(name, port) {
  const dir = fs.mkdtempSync(path.join(tmp, name));
  fs.writeFileSync(path.join(dir, 'api-key'), 'managed-secret\n', { mode: 0o600 });
  const tree = path.join(dir, 'runtime', 'llama-swap-t');
  fs.mkdirSync(tree, { recursive: true });
  const exe = path.join(tree, 'llama-swap');
  try { fs.linkSync(process.execPath, exe); } catch { fs.copyFileSync(process.execPath, exe); fs.chmodSync(exe, 0o755); }
  const conf = { mode: 'managed', class: 'C', port, systemUnits: false, modelsBySlug: { tiny: {} }, defaultModel: 'tiny' };
  return { dir, exe, conf };
}

/** health() in this process, as this account's own install (serviceUid without an `id` lookup). */
async function healthAs(dir, conf) {
  const saved = { d: process.env.HUGINN_LOCAL_DIR, u: process.env.HUGINN_LOCAL_USER };
  process.env.HUGINN_LOCAL_DIR = dir;
  process.env.HUGINN_LOCAL_USER = os.userInfo().username;
  try { return await local.health(conf); } finally {
    process.env.HUGINN_LOCAL_DIR = saved.d;
    process.env.HUGINN_LOCAL_USER = saved.u;
  }
}

test('the health check sends the key to the engine this install started, over the judged connection', { skip: !LINUX && 'Linux only' }, async () => {
  const port = await new Promise((r) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
  const box = managedBox('health-ok-', port);
  const script = path.join(box.dir, 'engine.js');
  fs.writeFileSync(script, `require('http').createServer((q, s) => {
  process.stdout.write(JSON.stringify({ url: q.url, auth: q.headers.authorization || null }) + '\\n');
  s.writeHead(200, { 'content-type': 'application/json' }); s.end('{"data":[{"id":"tiny"}]}');
}).listen(${port}, '127.0.0.1', () => process.stdout.write('{"ready":true}\\n'));\n`);
  const child = spawn(box.exe, [script], { stdio: ['ignore', 'pipe', 'inherit'] });
  const seen = [];
  await new Promise((resolve) => readline.createInterface({ input: child.stdout }).on('line', (l) => {
    const o = JSON.parse(l); if (o.ready) resolve(); else seen.push(o);
  }));
  try {
    const h = await healthAs(box.dir, box.conf);
    assert.equal(h.refused, undefined, JSON.stringify(h));
    assert.deepEqual(h.models, ['tiny']);
    for (let i = 0; i < 20 && !seen.length; i++) await new Promise((r) => setTimeout(r, 50));
    assert.equal(seen[0].auth, 'Bearer managed-secret');
  } finally { child.kill('SIGKILL'); }
});

test('the health check sends a squatter on the engine port nothing — not the key, not a request', { skip: !LINUX && 'Linux only' }, async () => {
  let bytes = 0;
  let conns = 0;
  const sq = net.createServer((s) => {
    conns += 1;
    s.on('error', () => {});
    s.on('data', (d) => {
      bytes += d.length;
      s.end('HTTP/1.1 200 OK\r\ncontent-type: application/json\r\nconnection: close\r\n\r\n{"data":[{"id":"pwned"}]}');
    });
  });
  await new Promise((r) => sq.listen(0, '127.0.0.1', r));
  const box = managedBox('health-sq-', sq.address().port);
  try {
    // This process is the squatter: this account, but not a program from runtime/.
    const h = await healthAs(box.dir, box.conf);
    assert.match(String(h.refused), /not this machine's local engine: pid \d+ runs \S*node, which is not under/, JSON.stringify(h));
    assert.deepEqual(h.models, [], 'nothing it said was taken as the model list');
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(conns, 1, 'it was reached, and judged');
    assert.equal(bytes, 0, 'and sent not one byte, so not the key');
    const s = await (async () => {
      const saved = process.env.HUGINN_LOCAL_DIR;
      process.env.HUGINN_LOCAL_DIR = box.dir;
      process.env.HUGINN_LOCAL_USER = os.userInfo().username;
      fs.writeFileSync(path.join(box.dir, 'local.json'), JSON.stringify(box.conf));
      shim({ systemctl: { stdout: 'active\n' }, loginctl: { stdout: 'Linger=yes\n' }, runuser: { stdout: 'active\n' } });
      try { return await local.statusInfo(); } finally { process.env.HUGINN_LOCAL_DIR = saved; process.env.HUGINN_LOCAL_USER = 'owner'; }
    })();
    assert.match(String(s.engine.refused), /not this machine's local engine/, 'status says so, in the field the desktop decodes');
  } finally { await new Promise((r) => sq.close(r)); }
});

// ------------------------------------------------------- the elevated hand-back
//
// `huginn local update` under sudo writes local.json as root and hands the tree
// back to the account. Two ways that went wrong: an `id` that failed read as
// uid 0 (Number('') is 0), so the tree went to root; and chown followed a link
// the account had planted in its own tree, as root.

test('userIds: an `id` that fails is no account, not root', () => {
  shim({ id: () => ({ status: 1, stdout: '', stderr: 'id: no such user' }) });
  assert.deepEqual(local.userIds('ghost'), { uid: null, gid: null });
  shim({ id: () => ({ status: 0, stdout: 'not-a-number\n' }) });
  assert.deepEqual(local.userIds('odd'), { uid: null, gid: null });
  shim({ id: (args) => ({ stdout: args[0] === '-u' ? '1000\n' : '1001\n' }) });
  assert.deepEqual(local.userIds('owner'), { uid: 1000, gid: 1001 });
});

test('the hand-back changes the tree, never what a link in it names', {
  skip: !(LINUX && typeof process.getuid === 'function' && process.getuid() === 0) && 'Linux, as root',
}, () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'handback-'));
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'handback-target-'));
  const target = path.join(outside, 'root-file');
  fs.writeFileSync(target, 'root file');
  fs.writeFileSync(path.join(d, 'local.json'), '{}');
  fs.symlinkSync(target, path.join(d, 'planted'));
  shim({ id: () => ({ stdout: '65534\n' }) });
  try {
    assert.equal(local.handBackOwnership(d, 'nobody'), true);
    assert.equal(fs.statSync(path.join(d, 'local.json')).uid, 65534);
    assert.equal(fs.lstatSync(path.join(d, 'planted')).uid, 65534, 'the link itself is handed back');
    assert.equal(fs.statSync(target).uid, 0, 'what the link names stays root\'s');
    shim({ id: () => ({ status: 1, stdout: '' }) });
    fs.chownSync(path.join(d, 'local.json'), 0, 0);
    assert.equal(local.handBackOwnership(d, 'ghost'), false, 'an account id cannot name is no hand-back');
    assert.equal(fs.statSync(path.join(d, 'local.json')).uid, 0);
  } finally {
    fs.rmSync(d, { recursive: true, force: true });
    fs.rmSync(outside, { recursive: true, force: true });
  }
});
