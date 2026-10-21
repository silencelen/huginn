'use strict';
// backgroundWork — what the session list and every transcript poll ask about a
// session's background shells and agents — runs `ps` and reads the
// transcript's tail only when a shell could actually be running.
//
// Both answer questions about a shell's `.output` file. They used to be asked
// first, for every session on every poll: a `ps` spawn, and a 256 KB tail read
// and parse of the transcript whenever it had grown. Most sessions have no
// tasks directory at all, and an agent's entry is a symlink that neither looks at.
//
// The daemon's REAL backgroundWork, lifted out of huginn-appd.js, over the real
// lib/tasks scanTasks and a scratch tasks directory.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { lift } = require('./appd-lift');
const tasksLib = require('../lib/tasks');

function setup(dir) {
  const calls = { ps: 0, ids: 0 };
  const { fns } = lift(['backgroundWork'], {
    fs,
    process: { getuid: () => 0 },
    readSessionState: () => ({ transcript: '/nowhere/t.jsonl', sessionId: 's1' }),
    taskDirFor: () => dir,
    scanTasks: tasksLib.scanTasks,
    psSnapshot: async () => { calls.ps += 1; return new Map(); },
    knownBackgroundIds: () => { calls.ids += 1; return new Set(); },
  });
  return { backgroundWork: fns.backgroundWork, calls };
}

const scratch = () => fs.mkdtempSync(path.join(os.tmpdir(), 'bgw-'));
// The lifted function builds its objects in its own realm: compare by value.
const plain = (v) => JSON.parse(JSON.stringify(v));

test('a session with no tasks directory costs neither ps nor a transcript read', async () => {
  const { backgroundWork, calls } = setup(path.join(scratch(), 'tasks'));
  assert.deepStrictEqual(plain(await backgroundWork('s', 4242)), { shells: [], agents: 0 });
  assert.deepStrictEqual(calls, { ps: 0, ids: 0 });
});

test('a tasks directory holding only an agent is counted without either', async () => {
  const root = scratch();
  const dir = path.join(root, 'tasks');
  fs.mkdirSync(dir);
  fs.writeFileSync(path.join(root, 'agent-a1.jsonl'), '{}\n');
  fs.symlinkSync(path.join(root, 'agent-a1.jsonl'), path.join(dir, 'a1.output'));
  const { backgroundWork, calls } = setup(dir);
  assert.deepStrictEqual(plain(await backgroundWork('s', 4242)), { shells: [], agents: 1 });
  assert.deepStrictEqual(calls, { ps: 0, ids: 0 });
});

test('a shell output file still gets both', async () => {
  const dir = path.join(scratch(), 'tasks');
  fs.mkdirSync(dir);
  fs.writeFileSync(path.join(dir, 'b77.output'), 'building\n');
  const { backgroundWork, calls } = setup(dir);
  // Nobody holds the file open (the process table is empty), so it is over.
  assert.deepStrictEqual(plain(await backgroundWork('s', 4242)), { shells: [], agents: 0 });
  assert.deepStrictEqual(calls, { ps: 1, ids: 1 });
});
