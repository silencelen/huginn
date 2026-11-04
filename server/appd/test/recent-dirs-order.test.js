'use strict';
// GET /v1/sessions/recent-dirs, newest first for real (2026-11-03).
//
// The create dialog's "Recent" list came back with the newest session's
// directory LAST (12th of 12): every entry in the live active-sessions.json
// carried the same updatedAt — 27 entries at one second, because the registry
// reconcile re-stamps every live entry on each pass (sessreg.mergeLive) — so a
// sort on updatedAt kept the order the entries were first written, oldest
// first, and with more than eleven directories the newest was cut by the cap.
//
// The daemon's own recentDirs, lifted into a sandbox over a scratch directory
// tree, with the registry handed in.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const sessreg = require('../lib/session-registry');
const { lift } = require('./appd-lift');

/** A registry the way a host whose reconcile has run holds it: oldest written first, one shared updatedAt. */
function reconciled(root, n) {
  const reg = {};
  for (let i = 0; i < n; i++) {
    const cwd = path.join(root, `dir${String(i).padStart(2, '0')}`);
    fs.mkdirSync(cwd);
    reg[`s${i}`] = { name: `s${i}`, createdAt: 1_793_700_000 + i * 60, claudeSessionId: null, cwd, restoredAt: null, updatedAt: 1_793_774_244 };
  }
  return reg;
}

function recentDirsOver(registry, root) {
  const work = path.join(root, 'work');
  fs.mkdirSync(work, { recursive: true });
  const { fns } = lift(['recentDirs'], {
    fs, path, sessreg, WORKDIR: work, DATA_DIR: path.join(root, 'data'), loadRegistry: () => registry,
  });
  return { dirs: fns.recentDirs(), work };
}

test('recent directories are newest-created first when every entry shares one updatedAt', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'recent-dirs-'));
  try {
    const reg = reconciled(root, 13);
    const { dirs, work } = recentDirsOver(reg, root);
    assert.equal(dirs.length, 12, 'the cap');
    assert.equal(dirs[0], work, 'the server default stays first');
    assert.equal(dirs[1], reg.s12.cwd, 'then the newest session');
    assert.deepEqual(dirs.slice(1), [12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2].map((i) => reg[`s${i}`].cwd),
      'newest to oldest; the cap drops the OLDEST');
    assert.equal(dirs.includes(reg.s0.cwd), false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('newestFirst: created, then updated, then the later-written entry; an entry with no stamps last', () => {
  const order = (reg) => sessreg.newestFirst(reg).map((e) => e.name);
  assert.deepEqual(order({
    a: { name: 'a', createdAt: 100, updatedAt: 900 },
    b: { name: 'b', createdAt: 300, updatedAt: 900 },
    c: { name: 'c', createdAt: 200, updatedAt: 900 },
  }), ['b', 'c', 'a'], 'createdAt decides when updatedAt is shared');
  assert.deepEqual(order({
    a: { name: 'a', createdAt: 100, updatedAt: 500 },
    b: { name: 'b', createdAt: 100, updatedAt: 700 },
  }), ['b', 'a'], 'the same second of creation: the later update first');
  assert.deepEqual(order({
    a: { name: 'a', createdAt: 100, updatedAt: 500 },
    b: { name: 'b', createdAt: 100, updatedAt: 500 },
  }), ['b', 'a'], 'and the later-written entry before an exact tie');
  assert.deepEqual(order({
    old: { name: 'old', updatedAt: 999 },
    new: { name: 'new', createdAt: 100, updatedAt: 100 },
  }), ['new', 'old'], 'an entry from before createdAt was recorded sorts after those that have it');
  assert.deepEqual(order(null), []);
  assert.deepEqual(order({ x: null, y: { name: 'y', createdAt: 1 } }), ['y'], 'a broken row is skipped');
});
