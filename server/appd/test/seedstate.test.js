'use strict';
// lib/seedstate.js — the pure half of reading a session that has NO state file
// (appd 2026-11-09): which argv word names the Claude session id, which
// candidate is taken, when the newest transcript in a directory may stand for a
// session, and the record the daemon seeds.
//
// SAFETY: pure functions. No daemon, no port, no tmux, no fs.
const { test } = require('node:test');
const assert = require('node:assert');
const seed = require('../lib/seedstate');

const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const C = '33333333-3333-4333-8333-333333333333';

test('idFromArgs: --resume, --resume=, -r, --session-id and --session-id= name the id; the last wins', () => {
  assert.equal(seed.idFromArgs(['--resume', A, '--name', 'x/lead']), A);
  assert.equal(seed.idFromArgs([`--resume=${A}`]), A);
  assert.equal(seed.idFromArgs(['-r', A]), A);
  assert.equal(seed.idFromArgs(['--session-id', A]), A);
  assert.equal(seed.idFromArgs([`--session-id=${A}`]), A);
  assert.equal(seed.idFromArgs(['--resume', A, '--resume', B]), B, 'the last of a repeated flag, as the CLI takes it');
  // A whole argv works too: argv[0] is never a flag.
  assert.equal(seed.idFromArgs(['claude', '--resume', A, '--effort', 'high']), A);
});

test('idFromArgs: a bare --resume (the picker), a non-uuid value and no flag at all name nothing', () => {
  assert.equal(seed.idFromArgs(['--resume', '--name', 'x']), null, 'the next word is a flag, not a value');
  assert.equal(seed.idFromArgs(['--resume']), null);
  assert.equal(seed.idFromArgs(['--resume', 'my search text']), null, 'the picker\'s search text is not an id');
  assert.equal(seed.idFromArgs(['--continue']), null);
  assert.equal(seed.idFromArgs(['--name', 'x/lead', '--model', 'opus']), null);
  assert.equal(seed.idFromArgs(null), null);
  assert.equal(seed.idFromArgs([]), null);
});

test('projectSlugs: the two spellings Claude Code has used for a cwd, deduplicated', () => {
  assert.deepEqual(seed.projectSlugs('/root/projects/osid'), ['-root-projects-osid']);
  assert.deepEqual(seed.projectSlugs('/root/.claude/x_y'), ['-root--claude-x-y', '-root-.claude-x_y']);
  assert.deepEqual(seed.projectSlugs(''), []);
  assert.deepEqual(seed.projectSlugs(null), []);
});

test('pickId: the first candidate in order WITH a transcript; an argv id whose transcript is gone falls through', () => {
  const onDisk = new Set([B, C]);
  const has = (id) => onDisk.has(id);
  // The widgetshub-lead shape: relaunched with --resume of an id whose transcript
  // never existed; Claude Code minted a fresh one, which the registry learned.
  assert.deepEqual(seed.pickId([
    { id: A, source: 'argv' }, { id: B, source: 'registry' }, { id: C, source: 'project' },
  ], { hasTranscript: has }), { id: B, source: 'registry' });
  // With the transcript, argv wins.
  onDisk.add(A);
  assert.deepEqual(seed.pickId([
    { id: A, source: 'argv' }, { id: B, source: 'registry' },
  ], { hasTranscript: has }), { id: A, source: 'argv' });
  // Nothing on disk for any of them: null, never a guess.
  assert.equal(seed.pickId([{ id: A, source: 'argv' }], { hasTranscript: () => false }), null);
  // Malformed and empty candidates are skipped.
  assert.deepEqual(seed.pickId([
    { id: null, source: 'argv' }, { id: 'not-a-uuid', source: 'registry' }, { id: C, source: 'project' },
  ], { hasTranscript: has }), { id: C, source: 'project' });
  assert.equal(seed.pickId([], { hasTranscript: has }), null);
  assert.equal(seed.pickId(null, { hasTranscript: has }), null);
});

test('pickId: an id another live session holds is skipped — unless the process\'s own argv says so', () => {
  const has = () => true;
  const claimed = new Set([B]);
  assert.deepEqual(seed.pickId([
    { id: B, source: 'registry' }, { id: C, source: 'project' },
  ], { hasTranscript: has, claimed }), { id: C, source: 'project' }, 'a second Claude on one conversation is never bound from a record');
  assert.deepEqual(seed.pickId([{ id: B, source: 'argv' }], { hasTranscript: has, claimed }),
    { id: B, source: 'argv' }, 'the process\'s own word stands');
});

test('uniqueNewest: exactly one unclaimed transcript newer than the session, or nothing', () => {
  const born = 1_700_000_000_000;
  const files = [
    { id: A, mtimeMs: born - 5_000 },      // older than the session: not its own
    { id: B, mtimeMs: born + 5_000 },
    { id: C, mtimeMs: born + 9_000 },
  ];
  assert.equal(seed.uniqueNewest(files, { bornMs: born, claimed: new Set([C]) }), B);
  assert.equal(seed.uniqueNewest(files, { bornMs: born, claimed: new Set() }), null, 'two candidates: not ours to choose');
  assert.equal(seed.uniqueNewest(files, { bornMs: born, claimed: new Set([B, C]) }), null);
  assert.equal(seed.uniqueNewest([{ id: B, mtimeMs: born }], { bornMs: born }), B, 'the same millisecond counts as after');
  assert.equal(seed.uniqueNewest(files, { bornMs: null }), null, 'no birth time: the newest file in a shared directory is almost never this session\'s');
  assert.equal(seed.uniqueNewest([{ id: 'junk.jsonl', mtimeMs: born + 1 }], { bornMs: born }), null);
  assert.equal(seed.uniqueNewest(null, { bornMs: born }), null);
});

test('livenessWord: the transcript\'s verdict as a state word; an unseen boundary is no word, never running', () => {
  assert.equal(seed.livenessWord({ idle: true, unknown: false }), 'idle');
  assert.equal(seed.livenessWord({ idle: false, unknown: false }), 'running');
  assert.equal(seed.livenessWord({ idle: false, unknown: true }), null);
  assert.equal(seed.livenessWord(null), null);
});

test('seedRecord: the hook\'s fields, ts in milliseconds, marked as seeded with its source', () => {
  const r = seed.seedRecord({ state: 'idle', sessionId: A, transcript: '/t/a.jsonl', cwd: '/w', source: 'argv', nowMs: 1_700_000_000_123 });
  assert.deepEqual(r, { state: 'idle', sessionId: A, transcript: '/t/a.jsonl', cwd: '/w', ts: 1_700_000_000_123, seeded: true, source: 'argv' });
  const bare = seed.seedRecord({ sessionId: A });
  assert.equal(bare.state, null);
  assert.equal(bare.seeded, true);
  assert.equal(bare.source, 'unknown');
  assert.ok(Math.abs(bare.ts - Date.now()) < 5_000, 'stamped now');
});
