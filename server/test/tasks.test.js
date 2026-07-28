'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const {
  taskDirFor, parsePs, descendantsOf, cleanCommand, extractBgIds, scanTasks,
} = require('../lib/tasks');

// The wrapper argv captured verbatim from a live background task, 2026-07-27.
const WRAPPER = "/bin/bash -c source /root/.claude/shell-snapshots/snapshot-bash-1785137321325-37gn94.sh 2>/dev/null || true && shopt -u extglob 2>/dev/null || true && { \\builtin unalias -- 'unsetenv'; \\builtin unset -f -- 'unsetenv'; } >/dev/null 2>&1 || true && eval 'sleep 500; echo done-marker' < /dev/null && pwd -P >| /tmp/claude-cac4-cwd";

test('the task dir derives its slug from the transcript path, not cwd', () => {
  const dir = taskDirFor('/root/.claude/projects/-root-netplan/abc.jsonl', 'abc', 0);
  assert.equal(dir, '/tmp/claude-0/-root-netplan/abc/tasks');
});

test('no transcript or session means no dir rather than a guess', () => {
  assert.equal(taskDirFor(null, 'abc', 0), null);
  assert.equal(taskDirFor('/x/y.jsonl', null, 0), null);
});

test('ps output parses into a pid map', () => {
  const procs = parsePs('  10     1  100 init\n 20    10  50 /bin/bash -c thing\nbadline\n');
  assert.equal(procs.get(20).ppid, 10);
  assert.equal(procs.get(20).args, '/bin/bash -c thing');
});

test('descendants are transitive, not just direct children', () => {
  const procs = parsePs(' 10 1 0 a\n 20 10 0 b\n 30 20 0 c\n 40 2 0 unrelated\n');
  assert.deepEqual(descendantsOf(procs, 10).sort(), [20, 30]);
});

test('the real wrapper argv cleans to the actual command', () => {
  assert.equal(cleanCommand(WRAPPER), 'sleep 500; echo done-marker');
});

test('bash quote escaping inside the eval body is undone', () => {
  assert.equal(
    cleanCommand("bash -c plumbing && eval 'echo '\\''hi'\\''' < /dev/null && pwd"),
    "echo 'hi'",
  );
});

test('a child process argv passes through as-is', () => {
  assert.equal(cleanCommand('sleep 500'), 'sleep 500');
});

test('background ids are read from launch results', () => {
  const ids = extractBgIds([
    { kind: 'tool', name: 'Bash', result: 'Command running in background with ID: b9d65xwjc. Output is being written to: /tmp/claude-0/x/y/tasks/b9d65xwjc.output.' },
    { kind: 'tool', name: 'Bash', result: 'ordinary output' },
    { kind: 'assistant', text: 'prose' },
  ]);
  assert.deepEqual([...ids], ['b9d65xwjc']);
});

// ------------------------------------------------------------------ scanTasks
// A fake fs standing in for /proc + the tasks dir, so the whole pipeline runs.

function fakeFs({ entries, fdLinks, stats }) {
  return {
    readdirSync(p, opts) {
      if (opts && opts.withFileTypes) {
        return entries.map((e) => ({
          name: e.name,
          isSymbolicLink: () => !!e.link,
        }));
      }
      // /proc/<pid>/fd
      const m = /^\/proc\/(\d+)\/fd$/.exec(p);
      if (m) return Object.keys(fdLinks[m[1]] || {});
      throw new Error('ENOENT');
    },
    readlinkSync(p) {
      const m = /^\/proc\/(\d+)\/fd\/(\d+)$/.exec(p);
      const t = m && (fdLinks[m[1]] || {})[m[2]];
      if (!t) throw new Error('ENOENT');
      return t;
    },
    statSync(p) {
      const st = stats[p];
      if (!st) throw new Error('ENOENT');
      return st;
    },
  };
}

const DIR = '/tmp/claude-0/-slug/sess/tasks';
const NOW = 2_000_000;

test('a held-open, transcript-named task is a running background shell', () => {
  const procs = parsePs(` 100 1 0 claude\n 200 100 0 ${WRAPPER}\n 201 200 0 sleep 500\n`);
  const f = fakeFs({
    entries: [{ name: 'b9d65xwjc.output' }],
    fdLinks: { 200: { 1: `${DIR}/b9d65xwjc.output` }, 201: { 1: `${DIR}/b9d65xwjc.output` } },
    stats: { [`${DIR}/b9d65xwjc.output`]: { birthtimeMs: (NOW - 198) * 1000, ctimeMs: 0, mtimeMs: 0 } },
  });
  const r = scanTasks(DIR, procs, 100, NOW, f, new Set(['b9d65xwjc']));
  assert.equal(r.shells.length, 1);
  assert.equal(r.shells[0].command, 'sleep 500; echo done-marker', 'the wrapper names the whole task');
  assert.equal(r.shells[0].forSeconds, 198);
});

// Measured live: the daemon's own foreground `cp && restart` held a tasks file
// open exactly like a background task. The transcript filter is what excludes it.
test('a held-open task the transcript never called background is ignored', () => {
  const procs = parsePs(` 100 1 0 claude\n 300 100 0 ${WRAPPER}\n`);
  const f = fakeFs({
    entries: [{ name: 'foreground1.output' }],
    fdLinks: { 300: { 1: `${DIR}/foreground1.output` } },
    stats: { [`${DIR}/foreground1.output`]: { birthtimeMs: NOW * 1000 } },
  });
  const r = scanTasks(DIR, procs, 100, NOW, f, new Set(['someOtherId']));
  assert.equal(r.shells.length, 0);
});

test('a finished task nobody holds open is not running', () => {
  const procs = parsePs(' 100 1 0 claude\n');
  const f = fakeFs({
    entries: [{ name: 'b9d65xwjc.output' }],
    fdLinks: {},
    stats: { [`${DIR}/b9d65xwjc.output`]: { birthtimeMs: NOW * 1000 } },
  });
  assert.equal(scanTasks(DIR, procs, 100, NOW, f, new Set(['b9d65xwjc'])).shells.length, 0);
});

test('a file held by a process OUTSIDE the session does not count', () => {
  const procs = parsePs(' 100 1 0 claude\n 900 1 0 someone-else\n');
  const f = fakeFs({
    entries: [{ name: 'b9d65xwjc.output' }],
    fdLinks: { 900: { 1: `${DIR}/b9d65xwjc.output` } },
    stats: { [`${DIR}/b9d65xwjc.output`]: { birthtimeMs: NOW * 1000 } },
  });
  assert.equal(scanTasks(DIR, procs, 100, NOW, f, new Set(['b9d65xwjc'])).shells.length, 0);
});

test('a fresh agent symlink counts as an active agent; a stale one does not', () => {
  const f = fakeFs({
    entries: [
      { name: 'agentA.output', link: true },
      { name: 'agentB.output', link: true },
    ],
    fdLinks: {},
    stats: {
      [`${DIR}/agentA.output`]: { mtimeMs: (NOW - 10) * 1000 },
      [`${DIR}/agentB.output`]: { mtimeMs: (NOW - 600) * 1000 },
    },
  });
  const r = scanTasks(DIR, parsePs(' 100 1 0 claude\n'), 100, NOW, f, new Set());
  assert.equal(r.agents, 1);
});

test('an unreadable dir is an empty answer, not a crash', () => {
  const f = { readdirSync() { throw new Error('ENOENT'); } };
  assert.deepEqual(scanTasks(DIR, new Map(), 100, NOW, f, new Set()), { shells: [], agents: 0 });
});
