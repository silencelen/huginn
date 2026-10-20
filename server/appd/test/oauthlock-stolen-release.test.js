'use strict';
// What release() may remove once somebody else has taken the lock over.
//
// The lock is stolen when its holder goes quiet for longer than `stale` — an
// event-loop stall, or the whole container frozen mid-refresh. From that moment
// the directory on disk belongs to the thief, which is normally the CLI's own
// refresher in the middle of rotating the live login. Removing it is not
// tidying up: the CLI's next heartbeat reads ENOENT as its OWN lock being
// compromised and aborts, and a third refresher can mkdir straight in while the
// rotation is still in flight. So a release takes away only what is still ours.
//
// SAFETY: every path here is under a scratch mkdtemp. Nothing touches ~/.claude,
// and no test in this file makes a network call of any kind.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { tryAcquire, lockPaths } = require('../lib/oauthlock');

/** No heartbeat timer fires during a test: each one drives beat() itself. */
const QUIET = { update: 3_600_000 };

function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oauthlock-steal-'));
  const dir = path.join(root, '.claude');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** What the CLI's proper-lockfile does to a stale lock: rmdir, mkdir, and an mtime of its own. */
function steal(p) {
  fs.rmSync(p, { recursive: true, force: true });
  fs.mkdirSync(p);
  const later = new Date(Date.now() + 65_000);
  fs.utimesSync(p, later, later);
}

test('a release after the heartbeat saw the steal leaves the thief`s locks in place', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, QUIET);
  assert.ok(h.ok);

  steal(paths.current);
  steal(paths.legacy);
  h.beat();
  assert.equal(h.compromised, true, 'precondition: our heartbeat noticed the steal');

  h.release();
  assert.ok(fs.existsSync(paths.current), 'the thief`s current lock survives our release');
  assert.ok(fs.existsSync(paths.legacy), 'and so does its legacy lock');

  const third = tryAcquire(dir, QUIET);
  assert.equal(third.ok, false, 'a third refresher must not get in while the thief is mid-refresh');
  assert.equal(third.status, 'lock_busy');
});

test('a steal no heartbeat has seen yet is still not ours to remove', () => {
  // The refresher's `finally { release() }` can run before the next beat: the
  // stall that let the lock go stale is the same stall that kept the timer
  // from firing. Ownership is read off the directory at release time.
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, QUIET);
  assert.ok(h.ok);

  steal(paths.current);
  steal(paths.legacy);
  assert.equal(h.compromised, false, 'precondition: nothing has looked yet');

  h.release();
  assert.ok(fs.existsSync(paths.current));
  assert.ok(fs.existsSync(paths.legacy));
});

test('a thief that has only reached the current lock gets it kept, and our legacy one is cleared', () => {
  // The CLI takes the two paths one after the other. Ours on the legacy path is
  // still ours, and leaving it behind would make the thief wait out a minute of
  // staleness for a lock nobody holds.
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, QUIET);
  assert.ok(h.ok);

  steal(paths.current);
  h.release();
  assert.ok(fs.existsSync(paths.current), 'the stolen current lock stays');
  assert.equal(fs.existsSync(paths.legacy), false, 'the legacy lock we still held is removed');
});

test('a lock nobody touched is still removed whole, after heartbeats too', () => {
  const dir = scratch();
  const paths = lockPaths(dir);
  const h = tryAcquire(dir, QUIET);
  assert.ok(h.ok);
  h.beat();
  h.beat();
  assert.equal(h.compromised, false);

  h.release();
  assert.equal(fs.existsSync(paths.current), false);
  assert.equal(fs.existsSync(paths.legacy), false);
  const next = tryAcquire(dir, QUIET);
  assert.ok(next.ok, 'and the next caller gets it at once');
  next.release();
});
