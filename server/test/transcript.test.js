'use strict';
// Record shapes here were read off real transcripts in
// ~/.claude/projects/ on 2026-07-27 (a live tmux session and a headless chat):
// assistant records carry thinking/text/tool_use blocks and an isSidechain flag,
// tool results come back inside a "user" record, and ai-title / permission-mode
// arrive as their own record types.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { readTranscript, workflowName, digestToolInput } = require('../lib/transcript');

function writeFixture(records) {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tr-')), 's.jsonl');
  fs.writeFileSync(p, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
  return p;
}

const T = '2026-07-27T08:00:00.000Z';

test('normalizes a full turn: user, thinking, tool with its result, answer', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'check the disk' }, timestamp: T, isSidechain: false },
    {
      type: 'assistant', timestamp: T, isSidechain: false, gitBranch: 'main', cwd: '/root/netplan',
      message: {
        model: 'claude-fable-5',
        content: [
          { type: 'thinking', thinking: 'They want df output.', signature: 'x' },
          { type: 'tool_use', id: 'tu_1', name: 'Bash', input: { command: 'df -h /' } },
        ],
      },
    },
    {
      type: 'user', timestamp: T, isSidechain: false,
      message: { content: [{ type: 'tool_result', tool_use_id: 'tu_1', content: '62% used' }] },
    },
    { type: 'assistant', timestamp: T, isSidechain: false, message: { content: [{ type: 'text', text: 'Disk is at 62%.' }] } },
    { type: 'ai-title', aiTitle: 'Disk check' },
    { type: 'permission-mode', permissionMode: 'auto' },
  ]);

  const r = readTranscript(p);
  assert.deepStrictEqual(r.events.map((e) => e.kind), ['user', 'thinking', 'tool', 'assistant']);
  assert.strictEqual(r.title, 'Disk check');
  assert.strictEqual(r.permissionMode, 'auto');
  assert.strictEqual(r.model, 'claude-fable-5');
  assert.strictEqual(r.gitBranch, 'main');

  const tool = r.events.find((e) => e.kind === 'tool');
  assert.strictEqual(tool.name, 'Bash');
  assert.strictEqual(tool.input, 'df -h /');
  // The result must be folded INTO the tool event, not left as an orphan card.
  assert.strictEqual(tool.result, '62% used');
  assert.strictEqual(tool.ok, true);
});

test('a tool_result does not leak into the conversation as a user message', () => {
  const p = writeFixture([
    { type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 'nope', content: 'raw output' }] }, timestamp: T },
  ]);
  const r = readTranscript(p);
  assert.strictEqual(r.events.filter((e) => e.kind === 'user').length, 0);
  assert.strictEqual(r.events[0].kind, 'tool_result');
});

test('an errored tool is marked not ok', () => {
  const p = writeFixture([
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'tool_use', id: 't', name: 'Bash', input: { command: 'false' } }] } },
    { type: 'user', timestamp: T, message: { content: [{ type: 'tool_result', tool_use_id: 't', content: 'boom', is_error: true }] } },
  ]);
  const tool = readTranscript(p).events.find((e) => e.kind === 'tool');
  assert.strictEqual(tool.ok, false);
  assert.strictEqual(tool.result, 'boom');
});

test('subagent output is flagged so the app can group it', () => {
  const p = writeFixture([
    { type: 'assistant', timestamp: T, isSidechain: true, message: { content: [{ type: 'text', text: 'from the subagent' }] } },
  ]);
  assert.strictEqual(readTranscript(p).events[0].sidechain, true);
});

test('a Workflow call is labelled with the script meta name', () => {
  const script = "export const meta = { name: 'review-changes', description: 'x' }\nphase('Review')";
  const p = writeFixture([
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'tool_use', id: 'w', name: 'Workflow', input: { script } }] } },
  ]);
  assert.strictEqual(readTranscript(p).events[0].detail, 'review-changes');
});

test('tailing by offset returns only what is new', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'first' }, timestamp: T },
  ]);
  const a = readTranscript(p);
  assert.strictEqual(a.events.length, 1);

  fs.appendFileSync(p, JSON.stringify({ type: 'user', message: { content: 'second' }, timestamp: T }) + '\n');
  const b = readTranscript(p, { offset: a.nextOffset });
  assert.strictEqual(b.events.length, 1, 'only the appended record');
  assert.strictEqual(b.events[0].text, 'second');

  // Nothing new: no events, offset unchanged.
  const c = readTranscript(p, { offset: b.nextOffset });
  assert.strictEqual(c.events.length, 0);
  assert.strictEqual(c.nextOffset, b.nextOffset);
});

test('a partially written final line is left for the next read', () => {
  const p = writeFixture([{ type: 'user', message: { content: 'complete' }, timestamp: T }]);
  const half = JSON.stringify({ type: 'user', message: { content: 'incomplete' } }).slice(0, 20);
  fs.appendFileSync(p, half);            // writer caught mid-line
  const r = readTranscript(p);
  assert.strictEqual(r.events.length, 1, 'the torn line is not parsed');

  // Completing the line makes it readable from the returned offset.
  fs.appendFileSync(p, JSON.stringify({ x: 1 }) + '\n');
  const r2 = readTranscript(p, { offset: r.nextOffset });
  assert.ok(r2.nextOffset > r.nextOffset);
});

test('garbage lines are skipped rather than failing the whole read', () => {
  const p = writeFixture([{ type: 'user', message: { content: 'good' }, timestamp: T }]);
  fs.appendFileSync(p, 'not json at all\n' + JSON.stringify({ type: 'user', message: { content: 'also good' }, timestamp: T }) + '\n');
  const r = readTranscript(p);
  assert.deepStrictEqual(r.events.map((e) => e.text), ['good', 'also good']);
});

test('limit keeps the newest events and reports truncation', () => {
  const many = [];
  for (let i = 0; i < 50; i++) many.push({ type: 'user', message: { content: `m${i}` }, timestamp: T });
  const r = readTranscript(writeFixture(many), { limit: 5 });
  assert.strictEqual(r.events.length, 5);
  assert.strictEqual(r.events[4].text, 'm49');
  assert.strictEqual(r.truncated, true);
});

test('record types with no user-facing content are dropped', () => {
  const p = writeFixture([
    { type: 'queue-operation', foo: 1 },
    { type: 'attachment', bar: 2 },
    { type: 'last-prompt', baz: 3 },
    { type: 'file-history-snapshot' },
    { type: 'mode', mode: 'normal' },
    { type: 'user', message: { content: 'only me' }, timestamp: T },
  ]);
  const r = readTranscript(p);
  assert.deepStrictEqual(r.events.map((e) => e.kind), ['user']);
});

test('digestToolInput picks the human-meaningful field', () => {
  assert.strictEqual(digestToolInput({ command: 'ls -la' }), 'ls -la');
  assert.strictEqual(digestToolInput({ file_path: '/a/b.kt', content: 'x'.repeat(9999) }), '/a/b.kt');
  assert.strictEqual(digestToolInput({ weird: 1 }), '{"weird":1}');
  assert.strictEqual(digestToolInput(null), '');
  assert.ok(digestToolInput({ command: 'x'.repeat(1000) }).endsWith('…'));
});

test('workflowName returns null when there is no meta name', () => {
  assert.strictEqual(workflowName('phase("x")'), null);
  assert.strictEqual(workflowName(undefined), null);
});

test('a Workflow call shows no raw script blob as input', () => {
  const script = "export const meta = { name: 'audit', description: 'x' }\n" + 'x'.repeat(4000);
  const p = writeFixture([
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'tool_use', id: 'w', name: 'Workflow', input: { script } }] } },
  ]);
  const ev = readTranscript(p).events[0];
  assert.strictEqual(ev.input, '', 'the script must not be shown as input');
  assert.strictEqual(ev.detail, 'audit');
});

test('an Agent call is labelled with its description', () => {
  const p = writeFixture([
    {
      type: 'assistant', timestamp: T,
      message: { content: [{ type: 'tool_use', id: 'a', name: 'Agent', input: { description: 'Find flaky tests', prompt: 'long...' } }] },
    },
  ]);
  assert.strictEqual(readTranscript(p).events[0].detail, 'Find flaky tests');
});

test('a message queued mid-turn is shown, not dropped', () => {
  // Verified against a real transcript: a message typed while Claude is working
  // is written ONLY as queue-operation records and never becomes a `user`
  // record, so dropping these made every follow-up invisible in the app.
  const p = writeFixture([
    { type: 'user', message: { content: 'first' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'second, sent while busy', timestamp: T },
  ]);
  const r = readTranscript(p);
  assert.deepStrictEqual(r.events.map((e) => e.text), ['first', 'second, sent while busy']);
  assert.strictEqual(r.events[1].queued, true, 'still pending, so marked queued');
});

test('a delivered queued message stops being marked queued and is not duplicated', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'do the thing', timestamp: T },
    { type: 'queue-operation', operation: 'remove', content: 'do the thing', timestamp: T },
  ]);
  const r = readTranscript(p);
  assert.strictEqual(r.events.length, 1);
  assert.strictEqual(r.events[0].kind, 'user');
  assert.strictEqual(r.events[0].queued, undefined);
});

test('a remove seen without its enqueue still yields the message', () => {
  // A tail read can start after the enqueue.
  const p = writeFixture([
    { type: 'queue-operation', operation: 'remove', content: 'earlier message', timestamp: T },
  ]);
  assert.deepStrictEqual(readTranscript(p).events.map((e) => e.text), ['earlier message']);
});

test('a dequeue carries no content and adds nothing', () => {
  const p = writeFixture([{ type: 'queue-operation', operation: 'dequeue', timestamp: T }]);
  assert.deepStrictEqual(readTranscript(p).events, []);
});

test('injected machine text is a note, not a user bubble', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: '<task-notification>\n<task-id>x</task-id>\n', timestamp: T },
  ]);
  const r = readTranscript(p);
  assert.strictEqual(r.events[0].kind, 'system');
  assert.strictEqual(r.events[0].text, 'background task reported back');
});

test('a user record duplicating an already-queued message is not shown twice', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'same text', timestamp: T },
    { type: 'user', message: { content: 'same text' }, timestamp: T },
  ]);
  assert.strictEqual(readTranscript(p).events.length, 1);
});
