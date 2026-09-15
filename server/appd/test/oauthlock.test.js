'use strict';
// The mkdir lock this daemon shares with Claude Code's own token refresher.
//
// Every case here is about a property that is invisible until it is violated,
// at which point the symptom is the owner being signed out of their CLI for no
// reason they can see: two writers rotating one login's token pair. So the
// tests assert the MECHANISM (a directory, a heartbeat, a steal threshold)
// rather than an outcome, because the mechanism is what has to match the CLI's
// byte for byte — a lock that is nearly the same as somebody else's lock is not
// a lock.
//
// SAFETY: every path here is under a scratch mkdtemp. Nothing touches ~/.claude,
// and no test in this file makes a network call of any kind.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { acquire, tryAcquire, lockPaths } = require('../lib/oauthlock');

/** Fast timings so a backoff ladder does not cost the suite fifteen seconds. */
const FAST = { attempts: 2, retryMs: 5, probeMs: 40, update: 20 };

function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oauthlock-'));
  const dir = path.join(root, '.claude');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

test('the lock is a DIRECTORY at both the current and the legacy path', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, FAST);
  assert.ok(h.ok, 'a free lock is taken');
  assert.ok(fs.statSync(paths.current).isDirectory(), 'current lock is a directory, not a file');
  assert.ok(fs.statSync(paths.legacy).isDirectory(), 'legacy lock is a directory, not a file');
  assert.match(paths.current, /\.oauth_refresh\.lock$/);
  assert.match(paths.legacy, /\.claude\.lock$/, 'the legacy lock sits BESIDE the config dir');
  h.release();
  assert.equal(fs.existsSync(paths.current), false, 'release removes the current lock');
  assert.equal(fs.existsSync(paths.legacy), false, 'release removes the legacy lock');
});

test('a held lock is refused, and refusing leaves the holder`s lock alone', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  const held = tryAcquire(dir, FAST);
  assert.ok(held.ok);

  const second = tryAcquire(dir, FAST);
  assert.equal(second.ok, false);
  assert.equal(second.status, 'lock_busy');
  assert.equal(second.contended, 'current');
  assert.ok(fs.existsSync(paths.current), 'the refused attempt must not remove the holder`s lock');
  held.release();
});

test('a lock whose holder stopped heartbeating is stolen at the stale threshold', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  fs.mkdirSync(paths.current);
  // Nothing has touched it for two minutes: by the CLI's own 60 s rule the
  // holder is gone, and refusing forever would mean one crashed process wedging
  // every refresh on this host until somebody noticed and deleted a directory.
  const old = new Date(Date.now() - 120_000);
  fs.utimesSync(paths.current, old, old);

  const h = tryAcquire(dir, FAST);
  assert.ok(h.ok, 'an abandoned lock is taken over');
  assert.ok(Date.now() - fs.statSync(paths.current).mtimeMs < 5_000, 'and its mtime is now ours');
  h.release();
});

test('a lock younger than the stale threshold is NOT stolen', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  fs.mkdirSync(paths.current);           // fresh mtime: a live holder
  const h = tryAcquire(dir, FAST);
  assert.equal(h.ok, false);
  assert.equal(h.status, 'lock_busy');
  assert.ok(fs.existsSync(paths.current));
});

test('the heartbeat moves the mtime, which is what keeps it from being stolen', async () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, FAST);
  const before = fs.statSync(paths.current).mtimeMs;
  await new Promise((r) => setTimeout(r, 30));
  h.beat();
  const after = fs.statSync(paths.current).mtimeMs;
  assert.ok(after > before, `heartbeat must advance the mtime (${before} -> ${after})`);
  assert.equal(h.compromised, false);
  h.release();
});

test('a stolen lock fires onCompromised and aborts the in-flight request', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  let fired = 0;
  const h = tryAcquire(dir, { ...FAST, onCompromised: () => { fired++; } });
  assert.equal(h.signal.aborted, false);

  // Somebody else decided we were stale and re-made the directory. Our own
  // refresh must stop where it is: two refreshers both completing a rotation is
  // precisely the interleaving that leaves one of them holding a dead pair.
  fs.rmSync(paths.current, { recursive: true, force: true });
  fs.mkdirSync(paths.current);
  const ahead = new Date(Date.now() + 5_000);
  fs.utimesSync(paths.current, ahead, ahead);

  h.beat();
  assert.equal(h.compromised, true);
  assert.equal(fired, 1, 'onCompromised fires exactly once');
  assert.equal(h.signal.aborted, true, 'the abort signal is what stops the POST');
  h.release();
});

test('contention on the LEGACY lock does not leave the current one held', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  fs.mkdirSync(paths.legacy);            // somebody holds only the old-style lock

  const h = tryAcquire(dir, FAST);
  assert.equal(h.ok, false);
  assert.equal(h.status, 'lock_busy');
  assert.equal(h.contended, 'legacy');
  assert.equal(fs.existsSync(paths.current), false,
    'half a lock is worse than none: the current one must be released again');
});

test('an exhausted ladder says lock_timeout for a frozen holder and lock_busy for a live one', async () => {
  const frozen = scratch();
  fs.mkdirSync(lockPaths(frozen).current);
  assert.equal((await acquire(frozen, FAST)).status, 'lock_timeout',
    'mtime never moved: somebody died holding it, and that wants looking at');

  const live = scratch();
  const p = lockPaths(live).current;
  fs.mkdirSync(p);
  const beat = setInterval(() => { const n = new Date(); fs.utimesSync(p, n, n); }, 10);
  try {
    assert.equal((await acquire(live, FAST)).status, 'lock_busy',
      'mtime is moving: a real refresh is in flight and we should just come back later');
  } finally { clearInterval(beat); }
});
