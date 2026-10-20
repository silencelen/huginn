'use strict';
// `huginn local update` across a llama-swap version change. The engine's
// service names its llama-swap by version (runtime/llama-swap-<v>), and a new
// one unpacks beside the old; the restart has to run the new one, not keep the
// old one while `installed` records the new.
//
// The manager runs from a copy whose generated MANIFEST pins tiny local files
// (local-tier-kit.js), every external command goes to a stand-in first on
// PATH, and the stand-in engine is this account running a copy of node from
// the tier's runtime/, which is what the identity check expects. Linux; the
// system-unit half needs root, as that install's update does.
//
// PORT 0 — an ephemeral port, so this file takes NO block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const readline = require('node:readline');
const { spawn, spawnSync } = require('node:child_process');
const { buildKit } = require('./local-tier-kit');

const CLIENT = path.join(__dirname, '..', '..', '..', 'client');
const MGR = path.join(CLIENT, 'huginn-local');
const SHIM = path.join(CLIENT, 'huginn-llm-shim');
const LINUX = process.platform === 'linux';
const ROOT = typeof process.getuid === 'function' && process.getuid() === 0;

let tmp;
let kit;
let t;        // the tier
let home;
let bin;
let units;
let log;
let port;
let engine;

const freePort = () => new Promise((resolve) => {
  const s = net.createServer();
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
});

before(async () => {
  if (!LINUX) return;
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'local-swap-'));
  kit = buildKit(path.join(tmp, 'kit'), { manager: MGR, shim: SHIM, swapVersion: 'v2' });
  t = path.join(tmp, 'tier');
  home = path.join(tmp, 'home');
  units = path.join(tmp, 'units');
  bin = path.join(tmp, 'bin');
  log = path.join(tmp, 'systemctl.log');
  for (const d of [path.join(t, 'runtime', '.dl'), path.join(t, 'models'), path.join(t, 'bin'), home, units, bin]) {
    fs.mkdirSync(d, { recursive: true });
  }
  for (const f of [kit.files.llama, kit.files.swap]) fs.copyFileSync(f, path.join(t, 'runtime', '.dl', path.basename(f)));
  for (const f of [kit.files.chat, kit.files.embed]) fs.copyFileSync(f, path.join(t, 'models', path.basename(f)));
  fs.writeFileSync(path.join(t, 'bin', 'huginn-device.js'), 'process.exit(0);\n');
  fs.writeFileSync(path.join(t, 'api-key'), 'the-engine-key\n', { mode: 0o600 });
  // At the restart, the unit says which llama-swap the engine will run.
  fs.writeFileSync(path.join(bin, 'systemctl'), `#!/bin/sh
echo "$@" >> ${log}
case "$*" in *"restart huginn-local-llm"*) cat ${units}/huginn-local-llm.service ${home}/.config/systemd/user/huginn-local-llm.service 2>/dev/null | grep '^ExecStart=' >> ${log} ;; esac
exit 0
`, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'runuser'), '#!/bin/sh\nshift 3\nexec "$@"\n', { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'loginctl'), '#!/bin/sh\necho Linger=yes\n', { mode: 0o755 });

  // The running engine: this account, from a program under runtime/ (a copy:
  // never a link to the real node in a tree that a hand-back may walk).
  port = await freePort();
  const exe = path.join(t, 'runtime', 'engine-node');
  fs.copyFileSync(process.execPath, exe);
  fs.chmodSync(exe, 0o755);
  fs.writeFileSync(path.join(t, 'runtime', 'engine.js'), `require('http').createServer((q, s) => {
  s.writeHead(200, { 'content-type': 'application/json' });
  s.end(JSON.stringify({ data: [{ id: 'tiny-chat' }, { id: 'tiny-embed' }] }));
}).listen(${port}, '127.0.0.1', () => process.stdout.write('ready\\n'));\n`);
  engine = spawn(exe, [path.join(t, 'runtime', 'engine.js')], { cwd: '/', stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((resolve, reject) => {
    readline.createInterface({ input: engine.stdout }).once('line', resolve);
    engine.once('exit', (c) => reject(new Error(`the stand-in engine exited ${c}`)));
  });
});
after(() => {
  if (engine) engine.kill('SIGKILL');
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

/** An install from the v1 pins, in `scope`, with its llm unit as `on` wrote it. */
function installedAt(scope, swapVersion) {
  const system = scope === 'system';
  fs.writeFileSync(path.join(t, 'local.json'), JSON.stringify({
    deviceName: 'box-llm', url: 'http://127.0.0.1:1', mode: 'managed', class: 'C', port, systemUnits: system,
    modelsBySlug: { 'tiny-chat': { file: 'tiny-chat.gguf', display: 'tiny-chat', ctx: 512, gpu: false } },
    defaultModel: 'tiny-chat', llmSlug: 'box',
    installed: { llamaTag: 'b0', swapVersion, models: ['tiny-chat'], manifestHash: '0000000000000000' },
  }), { mode: 0o600 });
  for (const f of [path.join(units, 'huginn-local-llm.service'), path.join(home, '.config', 'systemd', 'user', 'huginn-local-llm.service')]) {
    fs.rmSync(f, { force: true });
  }
  const unit = system ? path.join(units, 'huginn-local-llm.service') : path.join(home, '.config', 'systemd', 'user', 'huginn-local-llm.service');
  fs.mkdirSync(path.dirname(unit), { recursive: true });
  fs.writeFileSync(unit, `[Service]\nExecStart=${path.join(t, 'runtime', `llama-swap-${swapVersion}`, 'llama-swap')} --config ${path.join(t, 'llama-swap.yaml')} --listen 127.0.0.1:8748\n`);
  fs.rmSync(log, { force: true });
  return unit;
}

function update() {
  return spawnSync(process.execPath, [kit.mgr, 'update', '--yes'], {
    encoding: 'utf8',
    timeout: 120000,
    env: {
      PATH: `${bin}:/usr/sbin:/usr/bin:/sbin:/bin`,
      HOME: home,
      HUGINN_LOCAL_DIR: t,
      HUGINN_SHIM_SOURCE: path.join(path.dirname(kit.mgr), 'huginn-llm-shim'),
      HUGINN_SYSTEMD_SYSTEM_DIR: units,
    },
  });
}

for (const scope of ['user', 'system']) {
  const skip = (!LINUX && 'Linux only') || (scope === 'system' && !ROOT && 'a system-unit update runs as root');
  test(`a new llama-swap is the one the restarted ${scope} unit runs`, { skip }, () => {
    installedAt(scope, 'v1');
    const r = update();
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const calls = fs.readFileSync(log, 'utf8').trim().split('\n');
    const reload = calls.findIndex((c) => c === (scope === 'user' ? '--user daemon-reload' : 'daemon-reload'));
    const restart = calls.findIndex((c) => / ?restart huginn-local-llm$/.test(c));
    assert.ok(reload >= 0 && restart > reload, `systemd re-read the unit before the restart: ${calls.join(' | ')}`);
    assert.match(calls[restart + 1], /\/llama-swap-v2\/llama-swap /, 'the restart runs the llama-swap this update installed');
    const conf = JSON.parse(fs.readFileSync(path.join(t, 'local.json'), 'utf8'));
    assert.equal(conf.installed.swapVersion, 'v2');
  });
}

test('an update that keeps llama-swap leaves its unit alone', { skip: !LINUX && 'Linux only' }, () => {
  const unit = installedAt('user', 'v2');
  const before = fs.readFileSync(unit, 'utf8');
  const r = update();
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.equal(fs.readFileSync(unit, 'utf8'), before);
  assert.doesNotMatch(fs.readFileSync(log, 'utf8'), /daemon-reload/);
});
