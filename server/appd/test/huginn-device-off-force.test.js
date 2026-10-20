'use strict';
// What `huginn-device off --force` tells the operator to paste on the host when
// the daemon could not be reached: the row is still enrolled and only the
// host's token can retire it. That command must carry the token over HTTPS
// pinned to the daemon's own key, never plain http to 127.0.0.1:8787, which
// any account on the host can hold while appd is down.
//
// Nothing printed here is run: the command reads the host's real token, so it
// is only parsed (bash -n).
//
// PORT 0 — an ephemeral port, closed again before use, so this file takes NO
// block from the port table.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const RUNNER = path.join(__dirname, '..', '..', '..', 'client', 'huginn-device');
const ID = '11111111-2222-4333-8444-555555555555';
const FAKE_TOKEN = 'fake-token-for-this-test-only';

let tmp;
before(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hd-offforce-')); });
after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

/** A loopback port nothing is listening on. */
const closedPort = () => new Promise((resolve) => {
  const s = net.createServer();
  s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
});

test('off --force prints a pinned-HTTPS retire command, never a plain-http one', async () => {
  const dev = path.join(tmp, 'device');
  fs.mkdirSync(dev, { recursive: true });
  fs.writeFileSync(path.join(dev, 'device.json'), JSON.stringify({
    id: ID, name: 'box', url: `http://127.0.0.1:${await closedPort()}`, scope: 'work',
  }));
  fs.writeFileSync(path.join(dev, 'appd-token'), `${FAKE_TOKEN}\n`, { mode: 0o600 });
  const env = { ...process.env, HOME: path.join(tmp, 'home'), HUGINN_DEVICE_DIR: dev };
  delete env.HUGINN_APPD_TOKEN;
  delete env.HUGINN_APPD_PLAINTEXT;
  const r = spawnSync(process.execPath, [RUNNER, 'off', '--force'], { encoding: 'utf8', env, timeout: 60000 });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stderr, /STILL enrolled/);
  assert.doesNotMatch(r.stderr, /http:\/\//, 'no plain-http address anywhere in the advice');

  // The indented block after "where the token lives": what gets pasted.
  const lines = r.stderr.split('\n');
  const from = lines.findIndex((l) => /where the token lives/.test(l));
  assert.ok(from >= 0, r.stderr);
  assert.match(lines[from], /as root:$/, 'the token file is root\'s');
  const block = [];
  for (const l of lines.slice(from + 1)) {
    if (!/^ {4}/.test(l)) break;
    block.push(l.trim());
  }
  const cmd = block.join('\n');
  assert.match(cmd, new RegExp(`https://127\\.0\\.0\\.1:8787/v1/devices/${ID}$`));
  assert.match(cmd, /curl -sS -k --pinnedpubkey "sha256\/\/\$PIN" -X DELETE/, 'the pin is the daemon\'s own key');
  assert.match(cmd, /^PIN=\$\(openssl x509 -in \/var\/lib\/huginn-appd\/tls\/cert\.pem -pubkey -noout/);
  assert.match(cmd, /-H @<\(printf 'Authorization: Bearer %s\\n' "\$\(cat \/etc\/huginn-appd\/token\)"\)/,
    'the token reaches curl through a file descriptor, never its command line');
  assert.ok(!(r.stdout + r.stderr).includes(FAKE_TOKEN), 'this machine\'s token is never printed');

  // It pastes as one command: bash parses it (and is not allowed to run it).
  const parse = spawnSync('bash', ['-n', '-c', cmd], { encoding: 'utf8' });
  if (parse.error) return; // no bash on this machine: the text checks above stand
  assert.equal(parse.status, 0, parse.stderr);
});

test('off --force never pastes a device id that is not in the shape appd mints', async () => {
  const dev = path.join(tmp, 'device-odd');
  fs.mkdirSync(dev, { recursive: true });
  const odd = 'x$(touch /tmp/owned);y';
  fs.writeFileSync(path.join(dev, 'device.json'), JSON.stringify({
    id: odd, name: 'box', url: `http://127.0.0.1:${await closedPort()}`, scope: 'work',
  }));
  fs.writeFileSync(path.join(dev, 'appd-token'), `${FAKE_TOKEN}\n`, { mode: 0o600 });
  const env = { ...process.env, HOME: path.join(tmp, 'home2'), HUGINN_DEVICE_DIR: dev };
  delete env.HUGINN_APPD_TOKEN;
  delete env.HUGINN_APPD_PLAINTEXT;
  const r = spawnSync(process.execPath, [RUNNER, 'off', '--force'], { encoding: 'utf8', env, timeout: 60000 });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const url = r.stderr.split('\n').find((l) => /https:\/\/127\.0\.0\.1:8787\/v1\/devices\//.test(l)) || '';
  assert.ok(url, r.stderr);
  assert.ok(!url.includes('$('), `the id as written never reaches the pasted command: ${url}`);
  assert.match(url, /<this machine's device id, from huginn devices>/);
});

