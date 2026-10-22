'use strict';

// ⚠ A TEST'S SCRATCH TMUX SERVERS DO NOT OUTLIVE ITS PROCESS, AND NEITHER DO
// THEIR SOCKET FILES. Every suite names its private tmux socket after its own
// pid (`huginn-<what>-${process.pid}`, handed to the daemon as
// HUGINN_APPD_TMUX_SOCKET or used with `tmux -L`), and stopping a server with
// kill-server leaves the socket file behind. Every full run of the suites added
// about thirty to the user's tmux directory; 3,201 had piled up by 2026-10-22.
//
// When this process exits, each socket named for ITS pid is stopped (a server a
// test forgot to stop included) and its file removed. Nothing else in the
// directory is touched: another process's sockets end in another pid, and the
// owner's own servers are not `huginn-*-<pid>`.
//
// node --test runs each file in its own process, so this sees only the current
// file's sockets. no-owner-contact.js requires it, so every daemon suite has it;
// tmux-cleanup.test.js holds the other suites that name a socket to it.

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

/** Where tmux keeps this user's sockets for `-L <name>`. */
function socketDir() {
  return path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`);
}

/** The scratch sockets named for [pid]: `huginn-…-<pid>`, as full paths. */
function ownSockets(dir = socketDir(), pid = process.pid) {
  let names;
  try { names = fs.readdirSync(dir); } catch { return []; }
  const suffix = `-${pid}`;
  return names
    .filter((n) => n.startsWith('huginn-') && n.endsWith(suffix))
    .map((n) => path.join(dir, n));
}

/** Stop every server on [pid]'s scratch sockets and remove the socket files. */
function cleanup(dir = socketDir(), pid = process.pid) {
  const env = { ...process.env };
  delete env.TMUX; // a suite run from inside tmux must not reach that server
  for (const sock of ownSockets(dir, pid)) {
    try {
      if (!fs.lstatSync(sock).isSocket()) continue;
    } catch { continue; }
    spawnSync('tmux', ['-S', sock, 'kill-server'], { stdio: 'ignore', timeout: 3000, env });
    try { fs.unlinkSync(sock); } catch { /* already gone */ }
  }
}

// 'exit' handlers must be synchronous, which spawnSync and unlinkSync are.
process.on('exit', () => cleanup());

module.exports = { socketDir, ownSockets, cleanup };
