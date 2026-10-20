'use strict';
// A daemon restarted onto a data disk it cannot write still comes up.
//
// ⚠ BOOKKEEPING IS NOT A REASON TO STAY DOWN. Startup records the chat runs a
// restart interrupted and re-delivers the queues they left, and both write to
// the chat store. Those two calls ran bare in the startup chain, whose only
// handler is FATAL + exit(1) — so on a full (ENOSPC) or read-only (EROFS) data
// disk, one chat holding a run marker or a queued message made every start die
// before the port opened, and systemd's restart-on-failure turned it into a
// loop every 3 s. Phone and desktop lost even what needs no disk at all — the
// session list, the screens, typing — which is exactly what the owner would use
// to clean the disk up. The steps around them were already best effort.
//
// The conditions are REAL filesystems (a small tmpfs, remounted read-only, then
// filled), because that is the shape of the bug; a host that cannot mount one
// skips.
//
// SAFETY: the data root is a scratch tmpfs, unmounted in after(). `claude` on
// the daemon's PATH is an inert shim, so a queue that does get delivered starts
// nothing real. A private tmux socket, a scratch HOME and Claude directory, and
// a port the kernel just handed out.

require('./no-owner-contact'); // before any daemon is spawned
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');

const INTERRUPTED = 'aaaaaaaa-0000-4000-8000-000000000001';
const QUEUED = 'bbbbbbbb-0000-4000-8000-000000000002';

let tmp, data, mounted = false, token;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const mount = (args) => { execFileSync('mount', args, { stdio: 'ignore' }); };

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}

/**
 * Start a daemon on the data root, wait until it answers or exits, then stop
 * it. Returns whether it ever answered, how it exited, and its log.
 */
async function boot(label) {
  const port = await freePort();
  const logFile = path.join(tmp, `${label}.log`);
  const fd = fs.openSync(logFile, 'w');
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'huginn-appd.js')], {
    env: {
      ...process.env,
      PATH: `${path.join(tmp, 'shim')}:${process.env.PATH}`,
      HOME: path.join(tmp, 'home'),
      HUGINN_APPD_PORT: String(port),
      HUGINN_APPD_BIND: '127.0.0.1',
      HUGINN_APPD_DATA: data,
      HUGINN_APPD_TOKEN_FILE: path.join(tmp, 'token'),
      HUGINN_APPD_STATE_DIR: path.join(tmp, 'state'),
      HUGINN_APPD_CLAUDE_DIR: path.join(tmp, 'claude'),
      HUGINN_APPD_WORKDIR: tmp,
      HUGINN_APPD_TMUX_SOCKET: `huginn-test-unwritable-${process.pid}`,
      HUGINN_APPD_TELEGRAM_SCRIPT: '',
    },
    stdio: ['ignore', fd, fd],
  });
  fs.closeSync(fd);
  let exitCode = null;
  child.on('exit', (code) => { exitCode = code; });
  const call = async (p) => {
    const r = await fetch(`http://127.0.0.1:${port}${p}`, { headers: { authorization: `Bearer ${token}` } });
    if (r.status === 401) throw new Error(`port ${port} is held by another huginn-appd`);
    return r.status;
  };
  let answered = false;
  let sessions = null;
  for (let i = 0; i < 150 && exitCode === null; i++) {
    try {
      if (await call('/v1/ping') === 200) { answered = true; break; }
    } catch (e) { if (/held by another/.test(e.message)) throw e; }
    await wait(100);
  }
  if (answered) sessions = await call('/v1/sessions');
  // Long enough for a crash that comes AFTER the first answer to show itself too.
  await wait(500);
  const exited = exitCode;
  child.kill('SIGKILL');
  await wait(200);
  return { answered, sessions, exited, log: fs.readFileSync(logFile, 'utf8') };
}

function writeChat(id, extra) {
  const dir = path.join(data, 'chats', id);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const ts = Math.floor(Date.now() / 1000) - 60;
  fs.writeFileSync(path.join(dir, 'meta.json'), JSON.stringify({
    id, title: `chat ${id.slice(0, 4)}`, mode: 'ask', host: 'local', turns: 1,
    createdAt: ts, updatedAt: ts, lastSnippet: 'why is the disk full?', ...extra,
  }, null, 2));
  fs.writeFileSync(path.join(dir, 'messages.jsonl'),
    `${JSON.stringify({ type: 'user', text: 'why is the disk full?', ts })}\n`);
}
const metaOf = (id) => JSON.parse(fs.readFileSync(path.join(data, 'chats', id, 'meta.json'), 'utf8'));

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-unwritable-'));
  for (const d of ['state', 'claude', 'home', 'shim', 'data']) fs.mkdirSync(path.join(tmp, d));
  data = path.join(tmp, 'data');
  token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(tmp, 'token'), token, { mode: 0o600 });
  fs.writeFileSync(path.join(tmp, 'shim', 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });
  try { mount(['-t', 'tmpfs', '-o', 'size=4m,mode=0755', 'tmpfs', data]); mounted = true; } catch { return; }
  // An EXISTING install's data root, made by the daemon itself: its directories
  // and its certificate. A first start on an unwritable disk is a different
  // (and honestly fatal) case — there is no certificate to serve.
  const first = await boot('first');
  assert.equal(true, first.answered, `precondition: a first start on a writable disk works:\n${first.log}`);
  // One chat whose run a restart interrupted, and one holding a queued message.
  writeChat(INTERRUPTED, { runStartedAt: Math.floor(Date.now() / 1000) - 30 });
  writeChat(QUEUED, { pending: [{ text: 'and now?', ts: Math.floor(Date.now() / 1000) - 20 }] });
});

after(() => {
  if (mounted) { try { execFileSync('umount', [data], { stdio: 'ignore' }); } catch { /* busy or gone */ } }
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});

test('a read-only data disk: the daemon still answers, and says what it could not record', async (t) => {
  if (!mounted) { t.skip('this host cannot mount a tmpfs'); return; }
  mount(['-o', 'remount,ro', '-t', 'tmpfs', 'tmpfs', data]);
  try {
    assert.throws(() => fs.writeFileSync(path.join(data, 'probe'), 'x'), /EROFS/, 'precondition: really read-only');
    const r = await boot('readonly');
    assert.equal(true, r.answered, `the daemon must come up on a read-only disk (exit ${r.exited}):\n${r.log}`);
    assert.equal(200, r.sessions, 'and serve what needs no disk');
    assert.equal(null, r.exited, `and stay up:\n${r.log}`);
    assert.match(r.log, new RegExp(`chat ${INTERRUPTED}: .*EROFS`), 'the interrupted run that could not be recorded is named');
    assert.match(r.log, new RegExp(`chat ${QUEUED}: .*EROFS`), 'and so is the queue that could not be delivered');
  } finally {
    mount(['-o', 'remount,rw', '-t', 'tmpfs', 'tmpfs', data]);
  }
  assert.ok(metaOf(INTERRUPTED).runStartedAt, 'the marker is still there for the next start');
  assert.equal(1, metaOf(QUEUED).pending.length, 'and the queued message was not dropped');
});

test('a full data disk: the daemon still answers, and the queue is still on disk', async (t) => {
  if (!mounted) { t.skip('this host cannot mount a tmpfs'); return; }
  const filler = path.join(data, 'filler');
  const fd = fs.openSync(filler, 'w');
  const block = Buffer.alloc(64 * 1024);
  try { for (;;) fs.writeSync(fd, block); } catch (e) { assert.equal('ENOSPC', e.code); }
  // tmpfs keeps a page's slack: fill the last of it byte by byte.
  try { for (;;) fs.writeSync(fd, block, 0, 1); } catch (e) { assert.equal('ENOSPC', e.code); }
  fs.closeSync(fd);
  try {
    const r = await boot('full');
    assert.equal(true, r.answered, `the daemon must come up on a full disk (exit ${r.exited}):\n${r.log}`);
    assert.equal(200, r.sessions);
    assert.equal(null, r.exited, `and stay up:\n${r.log}`);
    assert.match(r.log, /ENOSPC/, 'and the log says why the bookkeeping failed');
  } finally { fs.rmSync(filler, { force: true }); }
  assert.equal(1, metaOf(QUEUED).pending.length, 'the queued message was not dropped');
});

test('once there is room again, the next start does the bookkeeping it skipped', async (t) => {
  if (!mounted) { t.skip('this host cannot mount a tmpfs'); return; }
  const r = await boot('recovered');
  assert.equal(true, r.answered, r.log);
  assert.equal(undefined, metaOf(INTERRUPTED).runStartedAt, 'the interrupted run is recorded now');
  const msgs = fs.readFileSync(path.join(data, 'chats', INTERRUPTED, 'messages.jsonl'), 'utf8');
  assert.match(msgs, /interrupted: huginn-appd restarted/);
  assert.deepEqual([], metaOf(QUEUED).pending || [], 'and the queue was delivered');
});
