'use strict';
// A test process's scratch tmux servers and their socket files go with it
// (see tmux-cleanup.js for why), and every suite that names a socket loads the
// module that does it.
//
// SAFETY: the servers here are private (`huginn-cleanuptest-<pid>` sockets
// under the user's tmux directory) and each runs only `sleep`; whatever a
// failing run leaves is stopped and removed in finally.

const { test } = require('node:test');
const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const HERE = __dirname;

function tmux(args) {
  const env = { ...process.env };
  delete env.TMUX;
  return spawnSync('tmux', args, { stdio: 'ignore', timeout: 5000, env }).status;
}

function socketDir() {
  return path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`);
}

test('a process that exits with a scratch server running takes the server and its socket with it', () => {
  // The child names its socket for its own pid, as the suites do, starts a
  // server on it, and exits without stopping it.
  const child = spawnSync(process.execPath, ['-e', `
    require(${JSON.stringify(path.join(HERE, 'tmux-cleanup'))});
    const { spawnSync } = require('node:child_process');
    const env = { ...process.env }; delete env.TMUX;
    const name = 'huginn-cleanuptest-' + process.pid;
    const r = spawnSync('tmux', ['-L', name, 'new-session', '-d', '-s', 't', 'sleep 120'], { env });
    process.stdout.write(name + ' ' + r.status);
  `], { encoding: 'utf8', timeout: 20000 });
  const [name, status] = String(child.stdout).trim().split(' ');
  assert.ok(name && name.startsWith('huginn-cleanuptest-'), `child said: ${child.stdout} ${child.stderr}`);
  const sock = path.join(socketDir(), name);
  try {
    assert.equal(status, '0', 'the child started its server');
    assert.notEqual(tmux(['-L', name, 'has-session']), 0, 'the server is stopped with the process');
    assert.equal(fs.existsSync(sock), false, 'and its socket file is gone');
  } finally {
    tmux(['-L', name, 'kill-server']);
    fs.rmSync(sock, { force: true });
  }
});

test('another process\'s sockets are left alone', () => {
  // A live server whose socket does not end in the child's pid.
  const other = `huginn-cleanuptest-other-${process.pid}`;
  const sock = path.join(socketDir(), other);
  try {
    assert.equal(tmux(['-L', other, 'new-session', '-d', '-s', 't', 'sleep 120']), 0);
    const child = spawnSync(process.execPath, ['-e',
      `require(${JSON.stringify(path.join(HERE, 'tmux-cleanup'))});`], { timeout: 20000 });
    assert.equal(child.status, 0);
    assert.equal(tmux(['-L', other, 'has-session']), 0, 'the other server still answers');
    assert.ok(fs.existsSync(sock), 'and its socket is still there');
  } finally {
    tmux(['-L', other, 'kill-server']);
    fs.rmSync(sock, { force: true });
  }
});

test('every suite that names a tmux socket loads the cleanup', () => {
  const loads = /require\('\.\/(tmux-cleanup|no-owner-contact|retry-fetch)'\)/;
  const names = /`huginn-[a-z-]+-\$\{process\.pid\}`|HUGINN_APPD_TMUX_SOCKET/;
  const missing = [];
  for (const f of fs.readdirSync(HERE).filter((n) => n.endsWith('.test.js') && n !== 'tmux-cleanup.test.js')) {
    const src = fs.readFileSync(path.join(HERE, f), 'utf8');
    if (names.test(src) && !loads.test(src)) missing.push(f);
  }
  assert.deepEqual(missing, [], `these leave their tmux sockets behind: ${missing.join(', ')}`);
  // The two it can come through must keep bringing it.
  assert.match(fs.readFileSync(path.join(HERE, 'no-owner-contact.js'), 'utf8'), /require\('\.\/tmux-cleanup'\)/);
  assert.match(fs.readFileSync(path.join(HERE, 'retry-fetch.js'), 'utf8'), /require\('\.\/no-owner-contact'\)/);
});

test('every Go or shell harness that stops a tmux server removes its socket file too', () => {
  const repo = path.resolve(HERE, '..', '..', '..');
  const walk = (dir, out = []) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p, out);
      else if (/\.(go|sh)$/.test(e.name)) out.push(p);
    }
    return out;
  };
  const removes = /os\.Remove\(tmuxSocketPath\(|rm -f "\$\{TMUX_TMPDIR:-\/tmp\}\/tmux-\$\(id -u\)\//;
  const stoppers = [];
  const missing = [];
  for (const f of [...walk(path.join(repo, 'core')), ...walk(path.join(repo, 'scripts'))]) {
    const src = fs.readFileSync(f, 'utf8');
    if (!/kill-server/.test(src)) continue;
    stoppers.push(path.relative(repo, f));
    if (!removes.test(src)) missing.push(path.relative(repo, f));
  }
  assert.ok(stoppers.includes('core/test/e2e.sh'), `the scan no longer finds the e2e script: ${stoppers.join(', ')}`);
  assert.deepEqual(missing, [], `these leave a socket file per run: ${missing.join(', ')}`);
});
