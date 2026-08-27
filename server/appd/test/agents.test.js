'use strict';
const test = require('node:test');
const assert = require('node:assert');

// --------------------------------------------------------------- agent files
//
// Layout read off a live workflow: direct agents at subagents/agent-*.jsonl,
// workflow agents one directory deeper, grouped by run.

const { agentsDirFor, listAgentFiles, agentTask } = require('../lib/agents');
const os2 = require('node:os');
const path2 = require('node:path');
const fs2 = require('node:fs');

test('the agents dir sits beside the transcript, keyed by session', () => {
  assert.equal(
    agentsDirFor('/root/.claude/projects/-slug/abc.jsonl', 'abc'),
    '/root/.claude/projects/-slug/abc/subagents');
  assert.equal(agentsDirFor(null, 'abc'), null);
});

test('direct and workflow agents are both found, workflow attributed', () => {
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'agents-'));
  fs2.writeFileSync(path2.join(dir, 'agent-direct1.jsonl'), '{}\n');
  fs2.mkdirSync(path2.join(dir, 'workflows', 'wf_abc-123'), { recursive: true });
  fs2.writeFileSync(path2.join(dir, 'workflows', 'wf_abc-123', 'agent-w1.jsonl'), '{}\n');
  fs2.writeFileSync(path2.join(dir, 'workflows', 'wf_abc-123', 'journal.jsonl'), '{}\n');
  const files = listAgentFiles(dir);
  assert.equal(files.length, 2, 'the journal is not an agent');
  assert.equal(files.find((f) => f.workflow === null).file.endsWith('agent-direct1.jsonl'), true);
  assert.equal(files.find((f) => f.workflow === 'wf_abc-123').file.endsWith('agent-w1.jsonl'), true);
});

test('an agent task is its first user record, read from the head', () => {
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'agents-'));
  const f = path2.join(dir, 'agent-x.jsonl');
  fs2.writeFileSync(f, JSON.stringify({
    type: 'user',
    message: { role: 'user', content: 'Audit the extension pins   for drift' },
  }) + '\n' + JSON.stringify({ type: 'assistant' }) + '\n');
  assert.equal(agentTask(f), 'Audit the extension pins for drift');
});

test('a garbled head yields no task rather than a crash', () => {
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'agents-'));
  const f = path2.join(dir, 'agent-y.jsonl');
  fs2.writeFileSync(f, 'not json\n');
  assert.equal(agentTask(f), null);
});

test('a missing agents dir lists nothing', () => {
  assert.deepEqual(listAgentFiles('/nonexistent-dir-xyz'), []);
});


test('journal result summaries map agent to outcome', () => {
  const os3 = require('node:os');
  const fs3 = require('node:fs');
  const path3 = require('node:path');
  const { journalSummaries } = require('../lib/agents');
  const dir = fs3.mkdtempSync(path3.join(os3.tmpdir(), 'journal-'));
  const f = path3.join(dir, 'journal.jsonl');
  fs3.writeFileSync(f, [
    JSON.stringify({ type: 'started', agentId: 'a1' }),
    JSON.stringify({ type: 'result', agentId: 'a1', result: { summary: 'Traced the   migration; sound.' } }),
    JSON.stringify({ type: 'result', agentId: 'a2', result: 'plain string result' }),
    '{"type":"result","agentId":"a3","result":{"summ',   // mid-write
    '',
  ].join('\n'));
  const m = journalSummaries(f);
  assert.equal(m.get('a1'), 'Traced the migration; sound.');
  assert.equal(m.get('a2'), 'plain string result');
  assert.equal(m.has('a3'), false, 'a half-written line is skipped, not fatal');
});

test('a missing journal is an empty map', () => {
  const { journalSummaries } = require('../lib/agents');
  assert.equal(journalSummaries('/nope/journal.jsonl').size, 0);
});

test('workflow runs are found in BOTH places the CLI writes them', () => {
  // The scan used to look only under subagents/workflows. The CLI also writes a
  // `workflows` directory beside subagents — that is where the run manifests
  // live on this host — and a run whose transcripts landed there reported as no
  // agents at all, which reads on the phone as a fan-out that never started.
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'agents-both-'));
  const subagents = path2.join(dir, 'subagents');
  fs2.mkdirSync(path2.join(subagents, 'workflows', 'wf_inner'), { recursive: true });
  fs2.writeFileSync(path2.join(subagents, 'workflows', 'wf_inner', 'agent-i1.jsonl'), '{}\n');
  fs2.mkdirSync(path2.join(dir, 'workflows', 'wf_sibling'), { recursive: true });
  fs2.writeFileSync(path2.join(dir, 'workflows', 'wf_sibling', 'agent-s1.jsonl'), '{}\n');

  const files = listAgentFiles(subagents);
  assert.equal(files.length, 2);
  assert.deepEqual(files.map((f) => f.workflow).sort(), ['wf_inner', 'wf_sibling']);
});

test('the same run reached through both roots is counted once', () => {
  // Double-listing a run would double every token total computed off these
  // files, which is worse than the blind spot it fixes.
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'agents-dup-'));
  const subagents = path2.join(dir, 'subagents');
  for (const root of [path2.join(subagents, 'workflows'), path2.join(dir, 'workflows')]) {
    fs2.mkdirSync(path2.join(root, 'wf_same'), { recursive: true });
    fs2.writeFileSync(path2.join(root, 'wf_same', 'agent-d1.jsonl'), '{}\n');
  }
  assert.equal(listAgentFiles(subagents).length, 1);
});

test('a settled agent is one with a result line, summary or not', () => {
  // Real journals carry `result:{findings:[…]}` with no summary at all, so
  // "did it finish" cannot be answered by looking for a summary.
  const { journalSettled } = require('../lib/agents');
  const dir = fs2.mkdtempSync(path2.join(os2.tmpdir(), 'settled-'));
  const f = path2.join(dir, 'journal.jsonl');
  fs2.writeFileSync(f, [
    JSON.stringify({ type: 'started', agentId: 'a1' }),
    JSON.stringify({ type: 'result', agentId: 'a1', result: { findings: [{ title: 'x' }] } }),
    JSON.stringify({ type: 'started', agentId: 'a2' }),
    '',
  ].join('\n'));
  const s = journalSettled(f);
  assert.equal(s.has('a1'), true);
  assert.equal(s.has('a2'), false, 'started is not finished');
  assert.equal(journalSettled('/nope/journal.jsonl').size, 0);
});
