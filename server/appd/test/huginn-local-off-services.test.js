'use strict';
// `huginn local off` and the services it has to stop first. An install on
// system units is stopped as root, behind the one elevation the other verbs
// take; and whatever service could not be stopped keeps the row, local.json
// and the keys it runs on, so a second off can still find and finish it.
//
// Every external command goes to a stand-in first on PATH, the deregistration
// is a stand-in runner that only records it was called, and system units live
// in a scratch directory. The elevation cases run the manager as another
// account, so they need Linux and root.
//
// Binds no ports, so no range in the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const MGR = path.join(__dirname, '..', '..', '..', 'client', 'huginn-local');
const ELEVATED_MARK = 'HUGINN_LOCAL_ELEVATED';

const LINUX = process.platform === 'linux';
const ROOT = typeof process.getuid === 'function' && process.getuid() === 0;
const idOf = (flag) => {
  const r = spawnSync('id', [flag, 'nobody'], { encoding: 'utf8' });
  return r.status === 0 && /^\d+$/.test(r.stdout.trim()) ? Number(r.stdout.trim()) : null;
};
const ACCOUNT = LINUX ? { uid: idOf('-u'), gid: idOf('-g') } : {};
const AS_ACCOUNT = (!LINUX && 'Linux only') || (!ROOT && 'needs root, to run the manager as another account')
  || ((ACCOUNT.uid === null || ACCOUNT.uid === 0) && 'needs a `nobody` account');

let tmp;
let mgr;   // a copy anyone can read: the account cannot reach a checkout under /root
before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'local-off-'));
  fs.chmodSync(tmp, 0o711);
  mgr = path.join(tmp, 'huginn-local');
  fs.copyFileSync(MGR, mgr);
  fs.chmodSync(mgr, 0o755);
});
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/**
 * An install as `on` leaves it, with both unit files where its scope keeps
 * them, and a stand-in for every command `off` runs.
 */
function box(name, { systemUnits }) {
  const root = fs.mkdtempSync(path.join(tmp, name));
  const t = path.join(root, 'tier');
  const home = path.join(root, 'home');
  const units = path.join(root, 'units');
  const bin = path.join(root, 'bin');
  for (const d of [path.join(t, 'bin'), path.join(t, 'device'), home, units, bin]) fs.mkdirSync(d, { recursive: true });
  const calledMark = path.join(root, 'deregistered');
  fs.writeFileSync(path.join(t, 'bin', 'huginn-device.js'),
    `require('fs').writeFileSync(${JSON.stringify(calledMark)}, process.argv.slice(2).join(' '));\n`);
  fs.writeFileSync(path.join(t, 'local.json'), JSON.stringify({
    mode: 'managed', class: 'C', deviceName: 'box-llm', port: 1, systemUnits,
  }), { mode: 0o600 });
  fs.writeFileSync(path.join(t, 'api-key'), 'the-engine-key\n', { mode: 0o600 });
  fs.writeFileSync(path.join(t, 'device', 'device.json'), '{"id":"x","url":"http://127.0.0.1:1"}\n', { mode: 0o600 });
  fs.writeFileSync(path.join(t, 'device', 'appd-token'), 'not-a-real-token\n', { mode: 0o600 });
  const unitDir = systemUnits ? units : path.join(home, '.config', 'systemd', 'user');
  fs.mkdirSync(unitDir, { recursive: true });
  for (const n of ['huginn-local-llm', 'huginn-local-runner']) fs.writeFileSync(path.join(unitDir, `${n}.service`), '[Unit]\n');

  const log = path.join(root, 'systemctl.log');
  const put = (n, body) => fs.writeFileSync(path.join(bin, n), `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  // systemd's answer to an account that may not manage system units.
  put('systemctl', `echo "$@" >> ${log}
case "$*" in --user*) ;; *) [ "$(id -u)" = 0 ] || { echo "Failed to disable unit: Access denied" >&2; exit 1; } ;; esac
exit 0`);
  put('runuser', 'shift 3\nexec "$@"');
  put('loginctl', 'echo Linger=yes');
  put('sudo', 'exit 127');
  for (const d of [root, t, home, units, bin]) fs.chmodSync(d, 0o755);
  return { root, t, home, units, bin, log, calledMark };
}

function runOff(b, { asAccount = false, argv = ['off'] } = {}) {
  if (asAccount) spawnSync('chown', ['-R', `${ACCOUNT.uid}:${ACCOUNT.gid}`, b.root]);
  return spawnSync(process.execPath, [mgr, ...argv], {
    encoding: 'utf8',
    cwd: b.home,
    timeout: 60000,
    ...(asAccount ? { uid: ACCOUNT.uid, gid: ACCOUNT.gid } : {}),
    env: {
      PATH: `${b.bin}:/usr/sbin:/usr/bin:/sbin:/bin`,
      HOME: b.home,
      HUGINN_LOCAL_DIR: b.t,
      HUGINN_SYSTEMD_SYSTEM_DIR: b.units,
      ...(asAccount ? {} : { HUGINN_LOCAL_USER: 'owner' }),
    },
  });
}

/** Nothing of what a running service needs, nor the row, was touched. */
function assertKept(b, r) {
  const out = r.stdout + r.stderr;
  assert.ok(!fs.existsSync(b.calledMark), `the row was deregistered under a running service: ${out}`);
  for (const f of ['local.json', 'api-key', path.join('device', 'device.json'), path.join('device', 'appd-token')]) {
    assert.ok(fs.existsSync(path.join(b.t, f)), `${f} was deleted under a running service: ${out}`);
  }
}

test('a service that will not stop keeps its row, its config and its keys', { skip: !LINUX && 'systemd only' }, () => {
  const b = box('stuck-', { systemUnits: false });
  // The engine's unit refuses to come down; the runner's does.
  fs.writeFileSync(path.join(b.bin, 'systemctl'), `#!/bin/sh
echo "$@" >> ${b.log}
case "$*" in *"disable --now huginn-local-llm"*) echo "Failed to disable unit: Access denied" >&2; exit 1 ;; esac
exit 0
`, { mode: 0o755 });
  const r = runOff(b);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /Not off: 1 service\(s\) did not stop, so nothing else was changed/);
  assertKept(b, r);
  // And so a second off still finds the install, and can finish it.
  fs.writeFileSync(path.join(b.bin, 'systemctl'), `#!/bin/sh\necho "$@" >> ${b.log}\nexit 0\n`, { mode: 0o755 });
  const again = runOff(b);
  assert.equal(again.status, 0, again.stdout + again.stderr);
  assert.ok(fs.existsSync(b.calledMark), 'the second off deregistered');
  assert.ok(!fs.existsSync(path.join(b.t, 'local.json')));
});

test('off on system units elevates once, and does nothing itself', { skip: AS_ACCOUNT }, () => {
  const b = box('elevate-', { systemUnits: true });
  const argsSeen = path.join(b.root, 'pkexec.args');
  // An elevation that works: the elevated off would do the work, so this one must not.
  fs.writeFileSync(path.join(b.bin, 'pkexec'), `#!/bin/sh
printf '%s\\n' "$@" > ${argsSeen}
for a in "$@"; do case "$a" in ${ELEVATED_MARK}=*) : > "\${a#${ELEVATED_MARK}=}" ;; esac; done
exit 0
`, { mode: 0o755 });
  const r = runOff(b, { asAccount: true });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.ok(fs.existsSync(argsSeen), `off never asked for root: ${r.stdout}${r.stderr}`);
  const args = fs.readFileSync(argsSeen, 'utf8').split('\n');
  assert.equal(args[args.length - 2], 'off', 'the elevated run is this same verb');
  assert.ok(args.includes(`HUGINN_LOCAL_DIR=${b.t}`), args.join(' '));
  assert.ok(!fs.existsSync(b.log), 'the unelevated half touched no unit');
  assertKept(b, r);
});

test('a dismissed prompt changes nothing, and says how to do it', { skip: AS_ACCOUNT }, () => {
  const b = box('declined-', { systemUnits: true });
  fs.writeFileSync(path.join(b.bin, 'pkexec'), '#!/bin/sh\nexit 126\n', { mode: 0o755 });
  const r = runOff(b, { asAccount: true });
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /nothing was changed/);
  assert.match(r.stderr, /sudo huginn local off/);
  assert.ok(!fs.existsSync(b.log), 'no unit was touched');
  assertKept(b, r);
});

test('with nothing that can ask for root, off tries and keeps everything the units still need', { skip: AS_ACCOUNT }, () => {
  const b = box('noelev-', { systemUnits: true });
  fs.writeFileSync(path.join(b.bin, 'pkexec'), '#!/bin/sh\nexit 127\n', { mode: 0o755 });
  const r = runOff(b, { asAccount: true });
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, /Not off: 2 service\(s\) did not stop/);
  assert.match(r.stderr, /sudo huginn local off/);
  assertKept(b, r);
  // The second off is not "not set up": the config it needs is still there.
  const again = runOff(b, { asAccount: true });
  assert.doesNotMatch(again.stderr, /not set up/);
});
