'use strict';
// huginn-local under an elevation: pkexec or sudo re-runs a verb as root for an
// install on system units, and the services run as the account. What that run
// writes as root goes back to the account before anything starts as the
// account, and however the run ends; and a run that started as root and then
// failed is never taken for a declined prompt.
//
// The spawned cases run the manager from a COPY whose generated MANIFEST block
// pins tiny local files, already in place with matching hashes, so nothing is
// downloaded; outside that block the copy is the shipped file byte for byte
// (local-tier-kit.js checks). Every external command goes to a stand-in first
// on PATH, and system units are written to a scratch directory. They need
// Linux and root: root to hand a tree to another account, and to run the
// unelevated half as that account.
//
// PORT 0 — ephemeral ports only, so this file takes NO block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { spawn, spawnSync } = require('node:child_process');
const { buildKit } = require('./local-tier-kit');

const CLIENT = path.join(__dirname, '..', '..', '..', 'client');
const MGR = path.join(CLIENT, 'huginn-local');
const SHIM = path.join(CLIENT, 'huginn-llm-shim');
const local = require(MGR);

const LINUX = process.platform === 'linux';
const ROOT = typeof process.getuid === 'function' && process.getuid() === 0;
const idOf = (flag) => {
  const r = spawnSync('id', [flag, 'nobody'], { encoding: 'utf8' });
  return r.status === 0 && /^\d+$/.test(r.stdout.trim()) ? Number(r.stdout.trim()) : null;
};
const ACCOUNT = LINUX ? { name: 'nobody', uid: idOf('-u'), gid: idOf('-g') } : {};
const SPAWNED = (!LINUX && 'Linux only') || (!ROOT && 'needs root')
  || ((ACCOUNT.uid === null || ACCOUNT.uid === 0) && 'needs a `nobody` account');

let tmp;     // 0711, so the account can reach what is handed to it below
let kit;     // the manager copy and its pinned files

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'local-handback-'));
  fs.chmodSync(tmp, 0o711);
  process.env.HUGINN_LOCAL_DIR = path.join(tmp, 'in-process');
  if (!SPAWNED) kit = buildKit(path.join(tmp, 'kit'), { manager: MGR, shim: SHIM });
});
after(() => {
  local.setRunner(null);
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

/** A stand-in for each command the manager runs; `systemctl` records its calls. */
function stubBin(dir, systemctlBody) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o755 });
  const put = (name, body) => fs.writeFileSync(path.join(dir, name), `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  put('systemctl', systemctlBody);
  put('loginctl', 'echo Linger=no');
  put('nvidia-smi', 'exit 9');
  // An elevated run that STARTS (it leaves the mark it is handed) and then fails.
  const started = `for a in "$@"; do case "$a" in ${local.ELEVATED_MARK}=*) : > "\${a#${local.ELEVATED_MARK}=}" ;; esac; done
echo "stand-in elevated run: it started, then failed" >&2
exit 1`;
  put('pkexec', started);
  put('sudo', 'exit 127');
  fs.chmodSync(dir, 0o755);
  return dir;
}

/** A tier directory as an install leaves it: the account's, with the pinned files in place. */
function tierFor(root, conf) {
  const t = path.join(root, 'tier');
  fs.mkdirSync(path.join(t, 'runtime', '.dl'), { recursive: true });
  fs.mkdirSync(path.join(t, 'models'), { recursive: true });
  fs.mkdirSync(path.join(t, 'bin'), { recursive: true });
  fs.copyFileSync(kit.files.llama, path.join(t, 'runtime', '.dl', path.basename(kit.files.llama)));
  fs.copyFileSync(kit.files.swap, path.join(t, 'runtime', '.dl', path.basename(kit.files.swap)));
  fs.copyFileSync(kit.files.chat, path.join(t, 'models', path.basename(kit.files.chat)));
  fs.copyFileSync(kit.files.embed, path.join(t, 'models', path.basename(kit.files.embed)));
  fs.writeFileSync(path.join(t, 'bin', 'huginn-device.js'), 'process.exit(0);\n');
  if (conf) {
    fs.writeFileSync(path.join(t, 'api-key'), `${crypto.randomBytes(16).toString('hex')}\n`, { mode: 0o600 });
    fs.writeFileSync(path.join(t, 'local.json'), JSON.stringify(conf), { mode: 0o600 });
  }
  const home = path.join(root, 'home');
  fs.mkdirSync(home, { recursive: true });
  for (const d of [root, t, home]) spawnSync('chown', ['-R', `${ACCOUNT.uid}:${ACCOUNT.gid}`, d]);
  return { t, home };
}

/** The manager, as the elevated child (root, with the variables elevationArgv carries) or as the account. */
function runMgr(argv, { t, home, bin, asAccount = false, env = {} }) {
  return spawnSync(process.execPath, [kit.mgr, ...argv], {
    encoding: 'utf8',
    cwd: home,
    timeout: 120000,
    ...(asAccount ? { uid: ACCOUNT.uid, gid: ACCOUNT.gid } : {}),
    env: {
      PATH: `${bin}:/usr/sbin:/usr/bin:/sbin:/bin`,
      HOME: home,
      HUGINN_LOCAL_DIR: t,
      HUGINN_SHIM_SOURCE: path.join(path.dirname(kit.mgr), 'huginn-llm-shim'),
      HUGINN_SYSTEMD_SYSTEM_DIR: path.join(path.dirname(t), 'units'),
      ...(asAccount ? {} : { HUGINN_LOCAL_USER: ACCOUNT.name }),
      ...env,
    },
  });
}

const freePort = () => new Promise((resolve) => {
  const s = net.createServer();
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
});
const owner = (f) => fs.statSync(f).uid;
const modeOf = (f) => fs.statSync(f).mode & 0o777;

// ------------------------------------------------------- the mark, in-process

test('a run that started as root and then failed is not a declined prompt', () => {
  const plan = (pkexec) => local.setRunner((cmd, args = []) => {
    if (cmd === 'sh') return { status: 0, stdout: /pkexec/.test(args[1]) ? '/usr/bin/pkexec\n' : '', stderr: '' };
    if (cmd === 'pkexec') return pkexec(args);
    return { status: 127, stdout: '', stderr: '', error: new Error('ENOENT') };
  });
  // The elevated program starts (it writes the mark it was handed), then exits 1.
  plan((args) => {
    const at = args.find((a) => a.startsWith(`${local.ELEVATED_MARK}=`));
    if (at) fs.writeFileSync(at.slice(local.ELEVATED_MARK.length + 1), '');
    return { status: 1, stdout: '', stderr: '' };
  });
  const ran = local.elevateOnce(['on', '--system', '--yes'], () => {}, { platform: 'linux', root: false });
  assert.equal(ran.ok, false);
  assert.equal(ran.ran, true, JSON.stringify(ran));
  assert.equal(ran.declined, false, 'it ran: falling back over what it wrote would be the bug');
  assert.equal(ran.status, 1);
  // The same exit 1 from a prompt that never started anything is still declined.
  plan(() => ({ status: 1, stdout: '', stderr: '' }));
  const no = local.elevateOnce(['on', '--system', '--yes'], () => {}, { platform: 'linux', root: false });
  assert.equal(no.declined, true);
  assert.ok(!no.ran);
});

// ------------------------------------------------- the elevated half, spawned

test('an elevated update hands the tree back, local.json included', { skip: SPAWNED }, async () => {
  const root = fs.mkdtempSync(path.join(tmp, 'update-'));
  const port = await freePort();
  const { t, home } = tierFor(root, {
    deviceName: 'box-llm', url: 'http://127.0.0.1:1', mode: 'managed', class: 'C', port, systemUnits: true,
    modelsBySlug: { old: { file: 'old.gguf', display: 'Old', ctx: 512, gpu: false } }, defaultModel: 'old',
    llmSlug: 'box', installed: { llamaTag: 'b0', swapVersion: 'v1', models: ['old'], manifestHash: '0000000000000000' },
  });
  // The running engine: the account's, from a program under runtime/ (a COPY of
  // node: the hand-back walks this tree, and a hard link would hand node itself
  // to the account).
  const engineExe = path.join(t, 'runtime', 'engine-node');
  fs.copyFileSync(process.execPath, engineExe);
  fs.writeFileSync(path.join(t, 'runtime', 'engine.js'), `require('http').createServer((q, s) => {
  s.writeHead(200, { 'content-type': 'application/json' });
  s.end(JSON.stringify({ data: [{ id: 'tiny-chat' }, { id: 'tiny-embed' }] }));
}).listen(${port}, '127.0.0.1', () => process.stdout.write('ready\\n'));\n`);
  spawnSync('chown', ['-R', `${ACCOUNT.uid}:${ACCOUNT.gid}`, path.join(t, 'runtime')]);
  fs.chmodSync(engineExe, 0o755);
  const engine = spawn(engineExe, [path.join(t, 'runtime', 'engine.js')], {
    cwd: '/', uid: ACCOUNT.uid, gid: ACCOUNT.gid, stdio: ['ignore', 'pipe', 'inherit'],
  });
  try {
    await new Promise((resolve, reject) => {
      readline.createInterface({ input: engine.stdout }).once('line', resolve);
      engine.once('exit', (c) => reject(new Error(`the stand-in engine exited ${c}`)));
    });
    const bin = stubBin(path.join(root, 'bin'), 'exit 0');
    const r = runMgr(['update', '--yes'], { t, home, bin });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /updated: serving tiny-chat, tiny-embed/);
    assert.equal(owner(path.join(t, 'local.json')), ACCOUNT.uid,
      'the services and the shim read local.json as the account: root\'s 0600 copy reads as "not set up"');
    assert.equal(modeOf(path.join(t, 'local.json')), 0o600);
    assert.equal(owner(path.join(t, 'llama-swap.yaml')), ACCOUNT.uid);
    assert.equal(owner(path.join(t, 'runtime', 'llama-b1')), ACCOUNT.uid);
    assert.equal(owner(path.join(t, 'bin', 'huginn-llm-shim.js')), ACCOUNT.uid);
  } finally {
    engine.kill('SIGKILL');
  }
});

test('an elevated on --system hands api-key back before the engine starts as the account', { skip: SPAWNED }, (ctx) => {
  const det = local.detectClass();
  if (det.refuse) { ctx.skip(`\`on\` stops before this here: ${det.refuse}`); return; }
  const root = fs.mkdtempSync(path.join(tmp, 'on-'));
  const { t, home } = tierFor(root, null);
  const seen = path.join(root, 'api-key-at-start');
  // The llm unit starting is where the account first needs the key; stop there.
  const bin = stubBin(path.join(root, 'bin'), `case "$*" in
  *"enable --now huginn-local-llm"*) stat -c '%u %a' "$HUGINN_LOCAL_DIR/api-key" > ${seen}; echo stopped here by the test >&2; exit 1 ;;
esac
exit 0`);
  const r = runMgr(['on', '--system', '--yes', '--url', 'http://127.0.0.1:1'], { t, home, bin });
  assert.ok(fs.existsSync(seen), `the llm unit was never started: ${r.stdout}${r.stderr}`);
  assert.equal(fs.readFileSync(seen, 'utf8').trim(), `${ACCOUNT.uid} 600`,
    'llama-server runs as the account and is handed --api-key-file: it must be able to read it');
  assert.equal(owner(path.join(t, 'local.json')), ACCOUNT.uid, 'and a failed run still hands back what it wrote');
});

test('a started-then-failed elevated run is reported, never fallen back from or called "nothing changed"', { skip: SPAWNED }, (ctx) => {
  const det = local.detectClass();
  if (det.refuse) ctx.diagnostic(`the \`on\` half is not run here: ${det.refuse}`);
  const root = fs.mkdtempSync(path.join(tmp, 'ran-'));
  const { t, home } = tierFor(root, null);
  const bin = stubBin(path.join(root, 'bin'), 'exit 1');
  if (!det.refuse) {
    const on = runMgr(['on', '--system', '--yes', '--url', 'http://127.0.0.1:1'], { t, home, bin, asAccount: true });
    assert.equal(on.status, 1, on.stdout + on.stderr);
    assert.match(on.stderr, /stand-in elevated run: it started, then failed/);
    assert.doesNotMatch(on.stdout + on.stderr, /falling back|did not elevate/,
      'the elevated run had started: a user-unit install would go in over what it wrote as root');
    assert.ok(!fs.existsSync(path.join(home, '.config', 'systemd', 'user', 'huginn-local-llm.service')),
      'no user unit was installed beside the system one');
  }
  fs.writeFileSync(path.join(t, 'local.json'), JSON.stringify({ mode: 'managed', class: 'C', port: 1, systemUnits: true }));
  spawnSync('chown', [`${ACCOUNT.uid}:${ACCOUNT.gid}`, path.join(t, 'local.json')]);
  const up = runMgr(['update', '--yes'], { t, home, bin, asAccount: true });
  assert.equal(up.status, 1, up.stdout + up.stderr);
  assert.doesNotMatch(up.stdout + up.stderr, /nothing was changed|could not elevate/,
    'the update ran as root before it failed');
});
