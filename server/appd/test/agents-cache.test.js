'use strict';
// The agent listing remembers what it read from each agent file.
//
// `GET /v1/sessions/<s>/agents?all=1` is fetched every time a session is opened
// (the stream picker), and it derived every row from scratch: the task from the
// file head, the last line from a tail parse, the sibling meta and the whole run
// journal — for every agent, on every call. A finished agent never changes, so
// the second listing is the same answer read again. These tests count the bytes
// a listing reads, which is deterministic where a stopwatch is not.
//
// Fixtures are SYNTHETIC agent folders written into a temp dir.

const { test, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const agents = require('../lib/agents');

// Every temp dir this file makes, removed when it is done: some of these
// fixtures are megabytes, and the suite runs on every deploy.
const scratchDirs = [];
const scratch = (dir) => { scratchDirs.push(dir); return dir; };
after(() => { for (const d of scratchDirs) fs.rmSync(d, { recursive: true, force: true }); });

const NOW = 1_800_000_000;
const T = (s) => new Date((NOW + s) * 1000).toISOString();
const L = (o) => `${JSON.stringify(o)}\n`;

function task(text, at = -600) {
  return { type: 'user', isSidechain: true, timestamp: T(at), message: { role: 'user', content: text } };
}
function toolUse(id, name, input, at) {
  return {
    type: 'assistant', isSidechain: true, requestId: `req-${id}`, timestamp: T(at),
    message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'tool_use', id, name, input }] },
  };
}
function toolResult(id, at, content = 'ok') {
  return {
    type: 'user', isSidechain: true, timestamp: T(at),
    message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content }] },
  };
}
function says(text, at) {
  return {
    type: 'assistant', isSidechain: true, requestId: `req-t${at}`, timestamp: T(at),
    message: { role: 'assistant', model: 'claude-opus-5', content: [{ type: 'text', text }] },
  };
}

/** One agent transcript plus its meta, `ageS` old. */
function writeAgent(dir, id, { label, records, meta = { agentType: 'general-purpose', spawnDepth: 1 }, ageS = 3600 }) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `agent-${id}.jsonl`);
  fs.writeFileSync(file, [task(label), ...records].map(L).join(''));
  if (meta) fs.writeFileSync(path.join(dir, `agent-${id}.meta.json`), JSON.stringify(meta));
  const at = new Date((NOW - ageS) * 1000);
  fs.utimesSync(file, at, at);
  return file;
}

/** Six direct agents and a two-member workflow run, all settled an hour ago. */
function stage() {
  const root = scratch(fs.mkdtempSync(path.join(os.tmpdir(), 'agents-cache-')));
  const dir = path.join(root, 'subagents');
  for (let i = 0; i < 6; i++) {
    writeAgent(dir, `a00000${i}`, {
      label: `Audit shard ${i}`,
      records: [
        toolUse(`t${i}`, 'Bash', { command: `echo ${i}` }, -500 + i),
        toolResult(`t${i}`, -499 + i, 'x'.repeat(2000)),
        says(`Shard ${i} is clean.`, -498 + i),
      ],
      ageS: 3600 + i,
    });
  }
  const run = path.join(dir, 'workflows', 'wf_test-run');
  for (const id of ['b000001', 'b000002']) {
    writeAgent(run, id, {
      label: `Review ${id}`,
      records: [toolUse(`w${id}`, 'Read', { file_path: '/srv/example/a.txt' }, -400)],
      meta: { agentType: 'workflow-subagent', spawnDepth: 1 },
    });
  }
  fs.writeFileSync(path.join(run, 'journal.jsonl'), [
    { type: 'started', agentId: 'b000001' },
    { type: 'started', agentId: 'b000002' },
    { type: 'result', agentId: 'b000001', result: { summary: 'Nothing to change.' } },
  ].map(L).join(''));
  return { root, dir, run };
}

/**
 * Every byte read through node:fs while `fn` runs, by file. The listing reads
 * through its injected fs AND through lib/transcript's (the last line), and both
 * are this module object, so patching it sees everything.
 */
function counting(fn) {
  const byFd = new Map();
  const reads = [];
  const orig = { openSync: fs.openSync, readSync: fs.readSync, readFileSync: fs.readFileSync, closeSync: fs.closeSync };
  fs.openSync = function (p, ...rest) { const fd = orig.openSync.call(fs, p, ...rest); byFd.set(fd, String(p)); return fd; };
  fs.closeSync = function (fd) { byFd.delete(fd); return orig.closeSync.call(fs, fd); };
  fs.readSync = function (fd, buf, off, len, pos) {
    const n = orig.readSync.call(fs, fd, buf, off, len, pos);
    reads.push({ file: byFd.get(fd), pos, n });
    return n;
  };
  fs.readFileSync = function (p, ...rest) {
    const r = orig.readFileSync.call(fs, p, ...rest);
    reads.push({ file: String(p), pos: 0, n: Buffer.isBuffer(r) ? r.length : Buffer.byteLength(r) });
    return r;
  };
  let out;
  try { out = fn(); } finally { Object.assign(fs, orig); }
  const bytes = reads.reduce((s, r) => s + r.n, 0);
  const of = (file) => reads.filter((r) => r.file === file);
  return { out, reads, bytes, of };
}

const listAll = (dir) => agents.listAgents(dir, NOW, fs, 200, { all: true });

test('a second listing over unchanged agent files reads nothing', () => {
  const { dir } = stage();
  const first = counting(() => listAll(dir));
  assert.equal(first.out.length, 8);
  assert.ok(first.bytes > 0, 'the first listing has to read the files');
  const second = counting(() => listAll(dir));
  assert.deepEqual(second.out, first.out, 'the same rows');
  assert.equal(second.bytes, 0,
    `nothing changed, so nothing is read — read ${second.bytes} bytes from ${[...new Set(second.reads.map((r) => path.basename(r.file || '?')))].join(', ')}`);
});

test('a grown agent file is read again, from its tail, and nothing else is', () => {
  const { dir } = stage();
  // Long enough that its tail is not its whole file.
  const grown = path.join(dir, 'agent-a000003.jsonl');
  const work = [];
  for (let k = 0; k < 60; k++) {
    work.push(toolUse(`p${k}`, 'Read', { file_path: `/srv/example/${k}.txt` }, -480 + k));
    work.push(toolResult(`p${k}`, -480 + k, 'y'.repeat(8000)));
  }
  fs.appendFileSync(grown, work.map(L).join(''));
  const old = new Date((NOW - 3600) * 1000);
  fs.utimesSync(grown, old, old);
  listAll(dir);
  const before = fs.statSync(grown).size;
  fs.appendFileSync(grown, L(toolUse('t3b', 'Grep', { pattern: 'TODO' }, -100)));
  const again = counting(() => listAll(dir));
  const row = again.out.find((a) => a.id === 'a000003');
  assert.equal(row.lastLine, 'Grep', 'the appended record is what it is doing now');
  assert.equal(row.task, 'Audit shard 3', 'the task survives');
  assert.equal(row.bytes, fs.statSync(grown).size);
  const others = again.reads.filter((r) => r.file !== grown && /agent-.*\.jsonl$/.test(r.file || ''));
  assert.deepEqual(others, [], 'no other agent file is read');
  assert.ok(again.of(grown).length > 0, 'the grown one is');
  assert.ok(again.of(grown).every((r) => r.pos > 0),
    'and not from its head: the first record of an append-only file is already known');
  assert.ok(before > 0);
});

test('a journal that grows is read from where it stopped, and a rewrite is read whole', () => {
  const { dir, run } = stage();
  const journal = path.join(run, 'journal.jsonl');
  const first = listAll(dir);
  assert.equal(first.find((a) => a.id === 'b000002').status, null, 'started, not settled');

  const size = fs.statSync(journal).size;
  fs.appendFileSync(journal, L({ type: 'result', agentId: 'b000002', result: { summary: 'Two  fixes   needed.' } }));
  const grown = counting(() => listAll(dir));
  const row = grown.out.find((a) => a.id === 'b000002');
  assert.equal(row.status, 'done');
  assert.equal(row.summary, 'Two fixes needed.');
  const jr = grown.of(journal);
  assert.ok(jr.length > 0, 'the journal is read again');
  assert.ok(jr.every((r) => r.pos >= size), `only the appended bytes: read at ${jr.map((r) => r.pos)}`);

  // Same size, new content, new mtime: not an append, so not trusted as one.
  const text = fs.readFileSync(journal, 'utf8').replace('Two  fixes   needed.', 'Six  fixes   needed.');
  fs.writeFileSync(journal, text);
  const later = new Date(Date.now() + 5000);
  fs.utimesSync(journal, later, later);
  const rewritten = counting(() => listAll(dir));
  assert.equal(rewritten.out.find((a) => a.id === 'b000002').summary, 'Six fixes needed.');
  assert.ok(rewritten.of(journal).some((r) => r.pos === 0), 'read from the top');
});

test('the remembered journal answers exactly what a full read does', () => {
  const dir = scratch(fs.mkdtempSync(path.join(os.tmpdir(), 'journal-cache-')));
  const f = path.join(dir, 'journal.jsonl');
  const lines = [
    L({ type: 'started', agentId: 'x1' }),
    L({ type: 'result', agentId: 'x1', result: 'first' }),
    '{"type":"result","agentId":"x2","result":{"summ',          // mid-write
    'ary":"late"}}\n',
    L({ type: 'failed', agentId: 'x3' }),
    L({ type: 'result', agentId: 'x1', result: { summary: 'second' } }),
    // a whole record whose newline has not landed: journalIndex counts it
    JSON.stringify({ type: 'result', agentId: 'x4', result: { findings: [] } }),
    '\n',
  ];
  const norm = (ix) => ({
    summaries: [...ix.summaries].sort(), settled: [...ix.settled].sort(),
    failed: [...ix.failed].sort(), started: [...ix.started].sort(),
  });
  let at = 0;
  fs.writeFileSync(f, '');
  for (const piece of lines) {
    fs.appendFileSync(f, piece);
    at += piece.length;
    assert.deepEqual(norm(agents.journalIndexCached(f)), norm(agents.journalIndex(f)), `after ${at} bytes`);
  }
  assert.deepEqual(norm(agents.journalIndexCached(f)).settled, ['x1', 'x2', 'x4'],
    'x2 completed by the next write; x4 counted before its newline, once after it');
});

test('a meta that lands after the first listing is picked up without re-reading the transcript', () => {
  const { dir } = stage();
  const file = writeAgent(dir, 'c000001', {
    label: 'Late meta', records: [says('Working on it.', -50)], meta: null, ageS: 7200,
  });
  const first = listAll(dir).find((a) => a.id === 'c000001');
  assert.equal(first.status, 'orphan', 'no meta yet: nothing can attribute it');
  assert.equal(first.agentType, null);

  fs.writeFileSync(path.join(dir, 'agent-c000001.meta.json'), JSON.stringify({ agentType: 'Explore', spawnDepth: 2 }));
  const second = counting(() => listAll(dir));
  const row = second.out.find((a) => a.id === 'c000001');
  assert.equal(row.agentType, 'Explore');
  assert.equal(row.depth, 2);
  assert.equal(row.status, null, 'attributable now, and only /graph can say how it ended');
  assert.deepEqual(second.of(file), [], 'the transcript itself did not change');
});
