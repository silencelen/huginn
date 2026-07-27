'use strict';
// The digest decides when a watching phone is woken, so what it includes — and
// more importantly what it EXCLUDES — is the whole design. A pane repainting or a
// token count moving must not wake anything up.

const { test } = require('node:test');
const assert = require('node:assert');
const { digest } = require('../lib/watch');

const sessions = [
  { name: 'andrev', state: 'running', activityAt: 100, cols: 80, preview: ['x'] },
  { name: 'uusage', state: null, activityAt: 90 },
];
const chats = [{ id: 'c1', running: false, pending: 0, title: 'Something' }];

test('the same state hashes the same', () => {
  assert.strictEqual(digest(sessions, chats).hash, digest(sessions, chats).hash);
});

test('a session changing state changes the hash', () => {
  const after = [{ ...sessions[0], state: 'attention' }, sessions[1]];
  assert.notStrictEqual(digest(sessions, chats).hash, digest(after, chats).hash);
});

test('a chat starting or finishing changes the hash', () => {
  const running = [{ ...chats[0], running: true }];
  assert.notStrictEqual(digest(sessions, chats).hash, digest(sessions, running).hash);
});

test('a message joining the queue changes the hash', () => {
  const queued = [{ ...chats[0], pending: 1 }];
  assert.notStrictEqual(digest(sessions, chats).hash, digest(sessions, queued).hash);
});

test('activity, geometry and previews do NOT wake a watcher', () => {
  // These change constantly on a busy session; waking for them would make the
  // service a battery drain that tells you nothing.
  const noisy = [
    { ...sessions[0], activityAt: 999, cols: 45, rows: 60, preview: ['completely different'] },
    sessions[1],
  ];
  assert.strictEqual(digest(sessions, chats).hash, digest(noisy, chats).hash);
});

test('a chat title changing does not wake a watcher', () => {
  const retitled = [{ ...chats[0], title: 'Claude renamed this' }];
  assert.strictEqual(digest(sessions, chats).hash, digest(sessions, retitled).hash);
});

test('a new session or chat appearing changes the hash', () => {
  assert.notStrictEqual(
    digest(sessions, chats).hash,
    digest(sessions.concat([{ name: 'new', state: 'idle' }]), chats).hash,
  );
  assert.notStrictEqual(
    digest(sessions, chats).hash,
    digest(sessions, chats.concat([{ id: 'c2', running: true, pending: 0 }])).hash,
  );
});

test('the hash does not depend on listing order', () => {
  const reversed = [sessions[1], sessions[0]];
  assert.strictEqual(digest(sessions, chats).hash, digest(reversed, chats).hash);
});

test('the digest carries the states an alert needs', () => {
  const d = digest(sessions, chats);
  assert.deepStrictEqual(d.sessions, { andrev: 'running', uusage: null });
  assert.strictEqual(d.chats.c1.running, false);
  assert.strictEqual(d.chats.c1.title, 'Something');
});

test('empty input is valid and stable', () => {
  assert.strictEqual(digest([], []).hash, digest(null, null).hash);
});
