'use strict';
// lib/archive.js — the rules an archived session is stored and ordered by.
//
// Every case here is a way the FEATURE'S WHOLE PROMISE breaks while the row on
// screen still looks right: a resume command that starts in the wrong directory,
// a cap that throws away the oldest conversation or the newest one depending on
// which way a comparator points, a transcript copy that keeps the beginning of a
// conversation nobody wanted the beginning of.
//
// Pure: no fs, no tmux, no daemon. The route-level half is routes-archive.test.js.

const { test } = require('node:test');
const assert = require('node:assert');
const archive = require('../lib/archive');

const ID = '0123abcd-0000-4000-8000-00000000abcd';

// ------------------------------------------------------------ resume command

test('the stored command carries the cwd, because a resume in the wrong directory is a different conversation', () => {
  // The transcript is found by session id, but Claude Code opens wherever it is
  // started. `claude --resume <id>` alone is a command that works and lands in
  // the wrong repository.
  assert.equal(
    `cd '/root/netplan' && claude --resume ${ID}`,
    archive.resumeCommand(ID, '/root/netplan'),
  );
});

test('a directory with a space in it survives being pasted into a shell', () => {
  // This string exists to be COPIED. Unquoted, `cd /root/my work` is `cd` with
  // two arguments and a resume that starts in $HOME with no error anywhere.
  assert.equal(
    `cd '/root/my work' && claude --resume ${ID}`,
    archive.resumeCommand(ID, '/root/my work'),
  );
  assert.equal(
    `cd '/root/it'\\''s' && claude --resume ${ID}`,
    archive.resumeCommand(ID, "/root/it's"),
    'an apostrophe in a path must not close the quote it is inside',
  );
});

test('no session id means no command at all, rather than a command that cannot work', () => {
  // A pane whose title hook never fired has nothing to resume. A row offering
  // `claude --resume null` is worse than a row that says it cannot be revived.
  assert.equal(null, archive.resumeCommand(null, '/root/netplan'));
  assert.equal(null, archive.resumeCommand('not-a-uuid', '/root/netplan'));
  assert.equal(null, archive.resumeCommand(`--help ${ID}`, '/root'),
    'a value that starts with -- would become the next flag');
});

test('with no cwd recorded the command still resumes, in whatever directory it is run from', () => {
  assert.equal(`claude --resume ${ID}`, archive.resumeCommand(ID, null));
});

// ------------------------------------------------------------------ the record

test('the record carries every field a client renders, definite rather than absent', () => {
  const rec = archive.buildRecord({
    claudeSessionId: ID,
    tmuxName: 'jtyper',
    title: 'Archive session feature',
    cwd: '/root/netplan',
    model: 'claude-opus-4-5',
    effort: 'high',
    permissionMode: 'acceptEdits',
    gitBranch: 'huginn3/w2-archive',
    archivedAt: 1_700_000_000,
    lastMessage: 'suite green, 1305 tests',
    transcriptPath: '/root/.claude/projects/-root-netplan/x.jsonl',
    transcriptBytes: 4096,
  });
  assert.equal(ID, rec.id, 'keyed by the claude session id, never the tmux name');
  assert.equal(ID, rec.claudeSessionId);
  assert.equal('jtyper', rec.tmuxName);
  assert.equal('Archive session feature', rec.title);
  assert.equal('/root/netplan', rec.cwd);
  assert.equal(`cd '/root/netplan' && claude --resume ${ID}`, rec.resumeCommand);
  assert.equal('claude-opus-4-5', rec.model);
  assert.equal('high', rec.effort);
  assert.equal('acceptEdits', rec.permissionMode);
  assert.equal('huginn3/w2-archive', rec.gitBranch);
  assert.equal(1_700_000_000, rec.archivedAt);
  assert.equal(4096, rec.transcriptBytes);
  assert.equal(false, rec.transcriptTruncated);
  // Null, not missing: the clients decode this and a key that is sometimes
  // absent is a key whose default silently becomes the value.
  assert.equal(null, rec.endedAt, 'null until the session is actually dead');
  assert.equal(null, rec.revivedAt);
  assert.equal(null, rec.revivedAs);
  for (const k of ['id', 'claudeSessionId', 'tmuxName', 'title', 'cwd', 'model', 'effort',
    'permissionMode', 'gitBranch', 'resumeCommand', 'archivedAt', 'endedAt', 'lastMessage',
    'transcriptPath', 'transcriptBytes', 'transcriptTruncated', 'revivedAt', 'revivedAs', 'note']) {
    assert.ok(k in rec, `the record is missing ${k}`);
  }
});

test('a title or a preview out of a transcript reaches the row as ONE line with no controls', () => {
  // Both come out of a model, and both are drawn in a terminal by huginn-archive
  // and on one row by two apps. The rounds.js argument, same reason.
  const rec = archive.buildRecord({
    claudeSessionId: ID,
    title: `done${String.fromCharCode(27)}[2K\rALL CLEAR`,
    lastMessage: 'first line\nsecond line',
  });
  assert.equal('done [2K ALL CLEAR', rec.title);
  assert.equal('first line second line', rec.lastMessage);
});

test('an over-long last message is clipped rather than allowed to be the row', () => {
  const rec = archive.buildRecord({ claudeSessionId: ID, lastMessage: 'x'.repeat(1000) });
  assert.equal(archive.MAX_PREVIEW, rec.lastMessage.length);
});

// ------------------------------------------------------------------ list view

test('the two facts that are only true right now are computed, never stored', () => {
  const rec = archive.buildRecord({ claudeSessionId: ID, archivedAt: 1 });
  assert.equal(false, 'live' in rec, 'a stored "live" would be wrong the moment it was written');
  assert.equal(false, 'transcriptPresent' in rec);
  const row = archive.archiveRow(rec, { live: true, transcriptPresent: false });
  assert.equal(true, row.live);
  assert.equal(false, row.transcriptPresent);
  assert.equal(rec.id, row.id, 'and the rest of the record rides along');
});

test('the list is newest archive first — recency is the order here', () => {
  // The opposite rule to the scratchpad picker, deliberately: nobody points at a
  // row in this list from memory, and the one just archived is the one most
  // likely to be wanted back.
  const rows = [
    archive.buildRecord({ claudeSessionId: ID, archivedAt: 100 }),
    archive.buildRecord({ claudeSessionId: '1111abcd-0000-4000-8000-00000000abcd', archivedAt: 300 }),
    archive.buildRecord({ claudeSessionId: '2222abcd-0000-4000-8000-00000000abcd', archivedAt: 200 }),
  ];
  assert.deepEqual([300, 200, 100], archive.sortArchives(rows).map((r) => r.archivedAt));
});

test('two archives in the same second still have a total order', () => {
  const a = archive.buildRecord({ claudeSessionId: '1111abcd-0000-4000-8000-00000000abcd', archivedAt: 7 });
  const b = archive.buildRecord({ claudeSessionId: '2222abcd-0000-4000-8000-00000000abcd', archivedAt: 7 });
  assert.deepEqual(archive.sortArchives([a, b]).map((r) => r.id), archive.sortArchives([b, a]).map((r) => r.id));
});

// ---------------------------------------------------------------- the cap

test('nothing is evicted until the cap is actually exceeded', () => {
  const rows = Array.from({ length: archive.MAX_ARCHIVES }, (_, i) =>
    archive.buildRecord({ claudeSessionId: ID, archivedAt: i + 1 }));
  assert.deepEqual([], archive.evictions(rows));
});

test('the cap evicts the OLDEST archives first, and says which', () => {
  // The direction is the whole test. A comparator pointing the other way keeps
  // the row you just made and throws away the conversation you archived in
  // March — which is the one an archive exists for.
  const rows = [10, 20, 30, 40].map((t) =>
    archive.buildRecord({ claudeSessionId: ID, archivedAt: t }));
  assert.deepEqual([10, 20], archive.evictions(rows, 2).map((r) => r.archivedAt));
});

test('a revived row is not protected from the cap', () => {
  // It is already back; the copy here is the least load-bearing row in the store.
  const rows = [
    archive.buildRecord({ claudeSessionId: ID, archivedAt: 10, revivedAt: 99 }),
    archive.buildRecord({ claudeSessionId: ID, archivedAt: 20 }),
  ];
  assert.deepEqual([10], archive.evictions(rows, 1).map((r) => r.archivedAt));
});

// -------------------------------------------------------------- revive name

test('a revive takes the old name back when nothing else holds it', () => {
  assert.equal('jtyper', archive.reviveName('jtyper', ['other', 'main']));
});

test('a taken name becomes <name>2, not a uuid and not a failure', () => {
  assert.equal('jtyper2', archive.reviveName('jtyper', ['jtyper']));
  assert.equal('jtyper3', archive.reviveName('jtyper', ['jtyper', 'jtyper2']));
});

test('a Set of live names is accepted as readily as an array', () => {
  assert.equal('jtyper2', archive.reviveName('jtyper', new Set(['jtyper'])));
});

// ------------------------------------------------------- transcript window

test('a transcript under the cap is copied whole and says so', () => {
  assert.deepEqual({ start: 0, bytes: 1024, truncated: false }, archive.transcriptWindow(1024));
});

test('an over-cap transcript keeps its TAIL, because that is what a person is coming back for', () => {
  // Keeping the head would archive the first hour of a two-day conversation and
  // present it as the conversation.
  const w = archive.transcriptWindow(archive.TRANSCRIPT_CAP_BYTES + 500);
  assert.equal(500, w.start);
  assert.equal(archive.TRANSCRIPT_CAP_BYTES, w.bytes);
  assert.equal(true, w.truncated, 'and it is never silent about it');
});

// -------------------------------------------------------------- last message

test('the preview is the last thing the assistant SAID, not the last tool it ran', () => {
  const events = [
    { kind: 'assistant', text: 'starting the build' },
    { kind: 'tool', name: 'Bash', input: 'gradlew test' },
  ];
  assert.equal('starting the build', archive.lastMessageOf(events));
});

test('a run that ended without speaking falls back to what the person asked', () => {
  assert.equal('archive this one', archive.lastMessageOf([
    { kind: 'user', text: 'archive this one' },
    { kind: 'tool', name: 'Bash', input: 'git commit' },
  ]));
});

test('a subagent talking to itself is not this conversation', () => {
  // Sidechain events are an agent's own transcript. "Read 400 lines of Lists.kt"
  // under a row about a release is the wrong sentence in the wrong place.
  assert.equal('the real answer', archive.lastMessageOf([
    { kind: 'assistant', text: 'the real answer' },
    { kind: 'assistant', text: 'agent chatter', sidechain: true },
  ]));
});

test('nothing said at all is an empty string, not the word undefined', () => {
  assert.equal('', archive.lastMessageOf([]));
  assert.equal('', archive.lastMessageOf(null));
});

// ------------------------------------------------- where a revive puts it back

test('the restored transcript goes under the config dir this daemon was TOLD to use', () => {
  // ⚠ THE BUG THIS PINS: the revive path built this out of `os.homedir()`, so a
  // daemon started with HUGINN_APPD_CLAUDE_DIR pointing at a scratch directory —
  // every route suite, and the CLI's own gate — restored its fixture transcript
  // into the OWNER'S real ~/.claude/projects/, where Claude Code would then
  // happily resume it. Found in a review run, by the log line naming the real
  // path.
  const t = archive.transcriptTarget('/scratch/claude', '/root/netplan', ID);
  assert.equal('/scratch/claude/projects/-root-netplan', t.dir);
  assert.equal(`/scratch/claude/projects/-root-netplan/${ID}.jsonl`, t.file);
  assert.equal('/scratch/claude/projects', archive.projectsRoot('/scratch/claude'));
  // And with the knob unset the answer is the real store, unchanged.
  assert.equal(`/root/.claude/projects/-root-netplan/${ID}.jsonl`,
    archive.transcriptTarget('/root/.claude', '/root/netplan', ID).file);
});

test('the slug is Claude Code\'s own: every slash in the cwd becomes a dash', () => {
  // Not a basename and not an escape — the directory name Claude Code itself
  // writes. A slug that disagrees is a transcript restored where nothing looks.
  assert.equal('/c/projects/-tmp-a-b-c',
    archive.transcriptTarget('/c', '/tmp/a/b/c', ID).dir);
  // A missing cwd must not become the string "null" in a path. (The daemon
  // passes `cwd || WORKDIR`, so this is the belt on the braces.)
  assert.equal('/c/projects', archive.transcriptTarget('/c', null, ID).dir);
});
