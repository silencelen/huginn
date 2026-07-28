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
