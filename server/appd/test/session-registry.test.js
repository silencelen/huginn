'use strict';
// The durable session registry that survives a reboot: what gets recorded, what
// gets pruned, what comes back, and how. Pure module — no daemon, no tmux.
//
// The load-bearing case here is the SIGNAL that tells a reboot from an appd
// restart: restorePlan against an empty live-set (server gone) brings everything
// back, and against a full one (server survived) brings nothing back. Get that
// wrong in either direction and you either lose every session or clone every one.

const { test } = require('node:test');
const assert = require('node:assert');
const reg = require('../lib/session-registry');

const UUID = '2f9a1c4e-1111-2222-3333-abcdef012345';

test('mergeLive records a session it has not seen before', () => {
  const out = reg.mergeLive({}, [{ name: 'dev', createdAt: 5, cwd: '/root/netplan' }], 100);
  assert.deepEqual(Object.keys(out), ['dev']);
  assert.equal(out.dev.createdAt, 5);
  assert.equal(out.dev.cwd, '/root/netplan');
  assert.equal(out.dev.claudeSessionId, null);
  assert.equal(out.dev.updatedAt, 100);
});

test('mergeLive makes a learned id and cwd STICKY across a later empty read', () => {
  // The title-hook state file lives in /run and can be missing or not-yet-written
  // on a given tick. Once we have the id, a blank read must not wipe it — that id
  // is the whole point of the registry.
  let r = reg.mergeLive({}, [{ name: 'dev', createdAt: 5 }], 100);
  r = reg.mergeLive(r, [{ name: 'dev', claudeSessionId: UUID, cwd: '/w' }], 101);
  r = reg.mergeLive(r, [{ name: 'dev' }], 102);            // hook file gone this tick
  assert.equal(r.dev.claudeSessionId, UUID, 'id survives a blank read');
  assert.equal(r.dev.cwd, '/w', 'cwd survives a blank read');
  assert.equal(r.dev.createdAt, 5, 'birth time is never overwritten');
  assert.equal(r.dev.updatedAt, 102, 'updatedAt still moves');
});

test('mergeLive never records the reserved login session', () => {
  const out = reg.mergeLive({}, [{ name: 'login', createdAt: 1 }, { name: 'dev', createdAt: 2 }], 100);
  assert.deepEqual(Object.keys(out), ['dev']);
  assert.ok(reg.isReserved('login'));
});

test('pruneDead drops entries whose session is no longer live, and only those', () => {
  const r = { dev: { name: 'dev' }, old: { name: 'old' }, live2: { name: 'live2' } };
  const { next, removed } = reg.pruneDead(r, new Set(['dev', 'live2']));
  assert.deepEqual(Object.keys(next).sort(), ['dev', 'live2']);
  assert.deepEqual(removed, ['old']);
});

test('pruneDead against an empty live-set clears everything — which is why the daemon never calls it on a failed read', () => {
  const { next, removed } = reg.pruneDead({ a: { name: 'a' }, b: { name: 'b' } }, new Set());
  assert.deepEqual(next, {});
  assert.deepEqual(removed.sort(), ['a', 'b']);
});

test('restorePlan cold boot (server gone) plans every recorded session, oldest first', () => {
  const r = {
    b: { name: 'b', createdAt: 30 },
    a: { name: 'a', createdAt: 10 },
    c: { name: 'c', createdAt: 20 },
  };
  const plan = reg.restorePlan(r, new Set());
  assert.deepEqual(plan.map((e) => e.name), ['a', 'c', 'b'], 'sorted by birth time');
});

test('restorePlan on an appd-only restart (all names still live) plans nothing', () => {
  const r = { a: { name: 'a', createdAt: 1 }, b: { name: 'b', createdAt: 2 } };
  assert.deepEqual(reg.restorePlan(r, new Set(['a', 'b'])), [], 'live sessions are never double-created');
});

test('restorePlan skips a name that is already live and the reserved login', () => {
  const r = {
    a: { name: 'a', createdAt: 1 },
    b: { name: 'b', createdAt: 2 },
    login: { name: 'login', createdAt: 3 },
  };
  assert.deepEqual(reg.restorePlan(r, new Set(['a'])).map((e) => e.name), ['b']);
});

test('resumeCommand resumes by id only when the transcript is still on disk', () => {
  const entry = { name: 'dev', claudeSessionId: UUID };
  const yes = reg.resumeCommand(entry, true);
  assert.equal(yes.canResume, true);
  assert.equal(yes.command, `claude --resume ${UUID}; exec "$SHELL" -l`);

  const no = reg.resumeCommand(entry, false);
  assert.equal(no.canResume, false);
  assert.equal(no.command, 'claude; exec "$SHELL" -l', 'no transcript -> fresh claude, not a broken resume');
});

test('resumeCommand falls back to a fresh session when there is no id', () => {
  const out = reg.resumeCommand({ name: 'dev', claudeSessionId: null }, true);
  assert.equal(out.canResume, false);
  assert.equal(out.command, 'claude; exec "$SHELL" -l');
});

test('resumeCommand refuses a malformed id rather than shell-injecting it', () => {
  // The command becomes the argument tmux hands to /bin/sh -c, so anything but a
  // uuid-shaped id must never reach it. A bad id degrades to a fresh session.
  for (const bad of ['x; rm -rf /', '$(touch pwned)', '`id`', 'not-a-uuid', UUID + 'extra']) {
    const out = reg.resumeCommand({ name: 'dev', claudeSessionId: bad }, true);
    assert.equal(out.canResume, false, `refused: ${bad}`);
    assert.equal(out.command, 'claude; exec "$SHELL" -l');
  }
});
