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
const { readTranscript, workflowName, digestToolInput, machineText, describeMachineText, humanRemainder, startsAtBoundary, injectedByTool, typedByHuman, skillNameFromBody } = require("../lib/transcript");

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

test('a slash command renders as the command, not as three garbled messages', () => {
  // Running /model writes THREE user records: a caveat, the tagged command, and
  // its stdout. Shown verbatim they looked like messages the user never sent.
  const p = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<local-command-caveat>Caveat: DO NOT respond to these.</local-command-caveat>' } },
    { type: 'user', timestamp: T, message: { content: '<command-name>/model</command-name>\n  <command-message>model</command-message>\n  <command-args>fable</command-args>' } },
    { type: 'user', timestamp: T, message: { content: '<local-command-stdout>Set model to \u001B[1mFable 5\u001B[22m and saved as your default</local-command-stdout>' } },
  ]);
  const r = readTranscript(p);
  assert.deepStrictEqual(r.events.map((e) => e.kind), ['command', 'command_result']);
  assert.strictEqual(r.events[0].text, '/model fable');
  assert.strictEqual(
    r.events[1].text,
    'Set model to Fable 5 and saved as your default',
    'ANSI must be stripped from command output',
  );
});

test('a command with no arguments still renders', () => {
  const p = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<command-name>/clear</command-name>' } },
  ]);
  assert.strictEqual(readTranscript(p).events[0].text, '/clear');
});

test('empty command stdout shows nothing at all', () => {
  const p = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<local-command-stdout></local-command-stdout>' } },
  ]);
  assert.deepStrictEqual(readTranscript(p).events, []);
});

test('a queued slash command is described, not shown as a user bubble', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', timestamp: T, content: '<command-name>/effort</command-name><command-args>max</command-args>' },
  ]);
  const r = readTranscript(p);
  assert.strictEqual(r.events[0].kind, 'command');
  assert.strictEqual(r.events[0].text, '/effort max');
});

test('a real message concatenated with command plumbing is never swallowed', () => {
  // Defensive: if the caveat block and the user's own text ever arrive in one
  // record, losing the message would be the worst outcome this reader can have.
  const p = writeFixture([
    {
      type: 'user', timestamp: T,
      message: {
        content: '<local-command-caveat>Caveat: ignore this.</local-command-caveat>\n' +
          'please also fix the notification permission prompt',
      },
    },
  ]);
  const r = readTranscript(p);
  const user = r.events.filter((e) => e.kind === 'user');
  assert.strictEqual(user.length, 1);
  assert.strictEqual(user[0].text, 'please also fix the notification permission prompt');
});

test('tag debris alone does not become a message', () => {
  const p = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<local-command-caveat>x</local-command-caveat>' } },
  ]);
  assert.deepStrictEqual(readTranscript(p).events, []);
});

test('a message that merely mentions a tag is left alone', () => {
  const text = 'im seeing odd <local-command-caveat> messages in the conversation tab';
  const p = writeFixture([{ type: 'user', timestamp: T, message: { content: text } }]);
  const r = readTranscript(p);
  assert.strictEqual(r.events[0].kind, 'user');
  assert.strictEqual(r.events[0].text, text);
});

test('an /effort command updates the reported effort immediately', () => {
  // Otherwise the control shows the previous value until the next assistant
  // record happens to stamp one, which can be a whole turn away.
  const p = writeFixture([
    { type: 'assistant', timestamp: T, effort: 'high', message: { content: [{ type: 'text', text: 'hi' }] } },
    { type: 'user', timestamp: T, message: { content: '<command-name>/effort</command-name><command-args>max</command-args>' } },
  ]);
  assert.strictEqual(readTranscript(p).effort, 'max');
});

test('an /model command updates the reported model immediately', () => {
  const p = writeFixture([
    { type: 'assistant', timestamp: T, message: { model: 'claude-opus-5', content: [{ type: 'text', text: 'hi' }] } },
    { type: 'user', timestamp: T, message: { content: '<command-name>/model</command-name><command-args>fable</command-args>' } },
  ]);
  assert.strictEqual(readTranscript(p).model, 'fable');
});

test('a later assistant record still wins over an earlier command', () => {
  // Records are read in file order, so whichever happened last is current.
  const p = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<command-name>/effort</command-name><command-args>low</command-args>' } },
    { type: 'assistant', timestamp: T, effort: 'xhigh', message: { content: [{ type: 'text', text: 'hi' }] } },
  ]);
  assert.strictEqual(readTranscript(p).effort, 'xhigh');
});

test('a command that is not a setting does not touch model or effort', () => {
  const p = writeFixture([
    { type: 'assistant', timestamp: T, effort: 'high', message: { content: [{ type: 'text', text: 'hi' }] } },
    { type: 'user', timestamp: T, message: { content: '<command-name>/clear</command-name>' } },
  ]);
  assert.strictEqual(readTranscript(p).effort, 'high');
});


// -------------------------------------------------------------- live activity
//
// What a session is in the middle of, judged from its transcript tail. Feeds the
// conversation work strip; a wrong answer here claims work that is not happening.

const { liveActivity } = require('../lib/transcript');
const NOW = 1_700_000_000;

test('an unresolved tool is in-flight work', () => {
  const a = liveActivity([{ kind: 'tool', name: 'Bash', detail: 'npm test', ts: NOW - 30 }], NOW);
  assert.equal(a.tool, 'Bash');
  assert.equal(a.detail, 'npm test');
  assert.equal(a.subagents, 0);
});

test('a tool with its result landed is finished, not activity', () => {
  assert.equal(liveActivity([{ kind: 'tool', name: 'Bash', result: 'ok', ok: true, ts: NOW - 5 }], NOW), null);
});

test('a failed tool is finished too', () => {
  assert.equal(liveActivity([{ kind: 'tool', name: 'Bash', result: 'boom', ok: false, ts: NOW - 5 }], NOW), null);
});

test('unresolved sidechain tools count as active subagents', () => {
  const a = liveActivity([
    { kind: 'tool', name: 'Workflow', detail: 'review-changes', ts: NOW - 60 },
    { kind: 'tool', name: 'Bash', sidechain: true, ts: NOW - 10 },
    { kind: 'tool', name: 'Read', sidechain: true, ts: NOW - 5 },
  ], NOW);
  assert.equal(a.tool, 'Workflow');
  assert.equal(a.detail, 'review-changes');
  assert.equal(a.subagents, 2);
});

test('subagents alone are still activity', () => {
  const a = liveActivity([{ kind: 'tool', name: 'Grep', sidechain: true, ts: NOW - 3 }], NOW);
  assert.equal(a.tool, undefined);
  assert.equal(a.subagents, 1);
});

// A cancelled turn leaves its last call unresolved forever; claiming "running
// npm test" about yesterday would be worse than nothing.
test('a stale unresolved tool is not activity', () => {
  assert.equal(liveActivity([{ kind: 'tool', name: 'Bash', ts: NOW - 7200 }], NOW), null);
});

test('text events are not activity', () => {
  assert.equal(liveActivity([
    { kind: 'user', ts: NOW - 10 }, { kind: 'assistant', ts: NOW - 5 },
  ], NOW), null);
});

test('an empty transcript has no activity', () => {
  assert.equal(liveActivity([], NOW), null);
});


// The notification shape observed live 2026-07-27: a bracketed preamble, then
// the tagged element. It rendered as a full message from the user, twice over —
// the preamble defeated the machine-text test, and tag-stripping left the
// element's inner text as "human remainder".
const REAL_NOTIFICATION = `[SYSTEM NOTIFICATION - NOT USER INPUT]
This is an automated background-task event, NOT a message from the user.
Do NOT interpret this as user acknowledgement.

<task-notification>
<task-id>b9d65xwjc</task-id>
<output-file>/tmp/claude-0/x/tasks/b9d65xwjc.output</output-file>
<status>completed</status>
<summary>Background command "Distinctive background task" completed (exit code 0)</summary>
</task-notification>`;

test('a preambled task notification is machine text', () => {
  assert.equal(machineText(REAL_NOTIFICATION), true);
});

test('it renders as its summary, not as a user message', () => {
  const d = describeMachineText(REAL_NOTIFICATION);
  assert.equal(d.kind, 'system');
  assert.match(d.text, /Distinctive background task.*completed/);
});

test('it leaves NO human remainder to show as a bubble', () => {
  assert.equal(humanRemainder(REAL_NOTIFICATION), '');
});

test('a real message concatenated after a notification still survives', () => {
  const mixed = REAL_NOTIFICATION + '\n\nalso please check the logs when done';
  assert.equal(humanRemainder(mixed), 'also please check the logs when done');
});


// ------------------------------------------------------- AskUserQuestion cards
//
// The tool's input rendered as raw JSON to the user ("AskUserQuestion
// {\"questions\":[{\"question\":\"The push…"). It becomes a structured card.

const { parseAsk } = require('../lib/transcript');

test('a question with options parses into card data', () => {
  const a = parseAsk({ questions: [{
    question: 'Which color should the banner be?',
    header: 'Banner color',
    multiSelect: false,
    options: [{ label: 'Red', description: 'x' }, { label: 'Blue' }],
  }] });
  assert.equal(a.questions[0].question, 'Which color should the banner be?');
  assert.equal(a.questions[0].header, 'Banner color');
  assert.deepEqual(a.questions[0].options, ['Red', 'Blue']);
});

test('string options and missing fields survive', () => {
  const a = parseAsk({ questions: [{ question: 'Q?', options: ['A', 'B'] }] });
  assert.deepEqual(a.questions[0].options, ['A', 'B']);
  assert.equal(a.questions[0].header, null);
});

test('garbage input falls back to the generic card', () => {
  assert.equal(parseAsk(null), null);
  assert.equal(parseAsk({}), null);
  assert.equal(parseAsk({ questions: [{}] }), null);
  assert.equal(parseAsk({ questions: 'nope' }), null);
});

// ------------------------------- tailing a file where bytes and events diverge
//
// Regression, from the owner's phone: a transcript row that Read a photo embeds
// the whole image as base64 — megabytes per photo — so the fixed 256KB tail
// window held zero complete turns after two photos, the initial view began
// mid-turn-2, and the app looked like sending a second image had DELETED the
// first exchange. The tail must be measured in events, not bytes.

test('a giant embedded blob does not eat the conversation above it', () => {
  const blob = 'x'.repeat(600 * 1024);          // one photo-sized ignored row
  const p = writeFixture([
    { type: 'user', message: { content: 'first question' }, timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'first answer' }] } },
    { type: 'attachment', payload: blob, timestamp: T },
    { type: 'user', message: { content: 'second question' }, timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'second answer' }] } },
  ]);
  const t = readTranscript(p);
  const texts = t.events.map((e) => e.text);
  assert.ok(texts.includes('first question'), `lost turn 1: ${JSON.stringify(texts)}`);
  assert.ok(texts.includes('first answer'));
  assert.ok(texts.includes('second question'));
  assert.ok(texts.includes('second answer'));
});

test('a long ordinary transcript still tails instead of reading everything', () => {
  // Hundreds of small rows totalling well past the window: the reader must
  // still truncate rather than always swallowing whole files.
  const rows = [];
  for (let i = 0; i < 4000; i++) {
    rows.push({ type: 'user', message: { content: `message number ${i} ${'pad'.repeat(40)}` }, timestamp: T });
  }
  const p = writeFixture(rows);
  const t = readTranscript(p);
  assert.strictEqual(t.truncated, true, 'expected a tail, not the whole file');
  assert.ok(t.events.length > 0);
  assert.ok(!t.events.some((e) => (e.text || '').startsWith('message number 0 ')), 'the head should be outside the window');
});

// Audit round 4. The queued map held ONE event per content string, so the same
// text sent twice while Claude was busy overwrote the first — it kept a
// `queued` badge forever — and the second `remove` found nothing and pushed a
// third bubble. Two sends must produce exactly two messages, both delivered.

test('the same message queued twice delivers as two, not three', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'first' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'ok', timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'ok', timestamp: T },
    { type: 'queue-operation', operation: 'remove', content: 'ok', timestamp: T },
    { type: 'queue-operation', operation: 'remove', content: 'ok', timestamp: T },
  ]);
  const t = readTranscript(p);
  const oks = t.events.filter((e) => e.text === 'ok');
  assert.strictEqual(oks.length, 2, `expected 2 "ok" bubbles, got ${oks.length}`);
  assert.ok(oks.every((e) => !e.queued), 'a delivered message must not keep its queued badge');
});

test('a message still waiting keeps its badge while its twin is delivered', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'ok', timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'ok', timestamp: T },
    { type: 'queue-operation', operation: 'remove', content: 'ok', timestamp: T },
  ]);
  const oks = readTranscript(p).events.filter((e) => e.text === 'ok');
  assert.strictEqual(oks.length, 2);
  assert.strictEqual(oks.filter((e) => e.queued).length, 1, 'exactly one should still be waiting');
});

// Audit round 6. A truncated tail dropped its first line unconditionally, on the
// assumption that a window always lands mid-record. When it lands exactly ON a
// boundary that line is a WHOLE record, and dropping it lost a real message from
// the top of the view. The rule is now asked of the file rather than assumed.

test('startsAtBoundary distinguishes a whole record from a fragment', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'alpha' }, timestamp: T },
    { type: 'user', message: { content: 'beta' }, timestamp: T },
  ]);
  const raw = fs.readFileSync(p, 'utf8');
  const secondLineStart = raw.indexOf('\n') + 1;

  assert.strictEqual(startsAtBoundary(p, 0), true, 'offset 0 is the start of a record');
  assert.strictEqual(startsAtBoundary(p, secondLineStart), true,
    'the byte after a newline begins a whole record');
  assert.strictEqual(startsAtBoundary(p, secondLineStart + 5), false,
    'mid-line is a fragment');
  assert.strictEqual(startsAtBoundary(p, secondLineStart - 1), false,
    'the newline itself is not the start of a record');
});

test('startsAtBoundary is safe on an unreadable path', () => {
  // Better to slice a possibly-whole line than to throw inside a read.
  assert.strictEqual(startsAtBoundary('/nonexistent/nope.jsonl', 10), false);
});

// Owner report, 2026-07-29: a queued message "doesn't move — the message in the
// original position persists when it should have moved". An enqueue happens
// mid-turn, so the bubble first lands INTERLEAVED into the output of the turn
// still running; un-badging it in place left it stranded above both the rest of
// that answer and the answer it actually prompted.

test('a delivered queued message moves to where it was delivered', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'first question' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'follow up', timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'answer to first' }] } },
    { type: 'queue-operation', operation: 'remove', content: 'follow up', timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'answer to follow up' }] } },
  ]);
  const texts = readTranscript(p).events.map((e) => e.text);
  assert.deepStrictEqual(texts, [
    'first question',
    'answer to first',
    'follow up',            // moved down past the answer it was typed during
    'answer to follow up',
  ], `wrong order: ${JSON.stringify(texts)}`);
});

test('a still-queued message stays at the bottom, badged', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'first question' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'still waiting', timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'answer to first' }] } },
  ]);
  const evs = readTranscript(p).events;
  const last = evs[evs.length - 1];
  assert.strictEqual(last.text, 'still waiting', 'a waiting message belongs at the bottom');
  assert.strictEqual(last.queued, true, 'and it must keep its badge');
});

test('sequence numbers stay monotonic after a move', () => {
  const p = writeFixture([
    { type: 'user', message: { content: 'q' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'later', timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'a' }] } },
    { type: 'queue-operation', operation: 'remove', content: 'later', timestamp: T },
  ]);
  const seqs = readTranscript(p).events.map((e) => e.seq);
  assert.deepStrictEqual(seqs, [...seqs].sort((x, y) => x - y), `not monotonic: ${seqs}`);
  assert.strictEqual(new Set(seqs).size, seqs.length, 'duplicate seq would break list keys');
});

// ------------------------------------------------ a DRAINED queue
//
// Owner report, 2026-09-15: "the response is put on top of the initial message
// ... has happened when using the local 'escalate to claude' chat feature".
//
// `remove` and `dequeue` are NOT two names for one thing, and reading the second
// as "the queue emptied, nothing to say about it" is what put the answer above
// the question. Claude Code writes the FIRST prompt of every run as an enqueue
// immediately followed by a dequeue, and THEN as an ordinary `user` record a
// second or two later. With the dequeue a no-op the enqueue's bubble kept its
// `queued` badge forever: the badge floated it to the bottom, and the real
// `user` record was swallowed by the duplicate guard. Every chat and every
// session opened with its own opening prompt printed LAST, and it was loudest in
// the escalate-to-Claude handoff, where the whole conversation is one long
// message and one answer — so the entire screen read upside down.
//
// Record shapes read off the real transcript of the owner's escalated chat,
// session 421fe82c-bec6-45fd-9eb3-c3616eba17c8 on 2026-09-16T05:15Z. Across all
// 907 transcripts on this host, 1,244 of 1,281 drained messages have a matching
// `user` record after them and every `remove`d one has none — which is why a
// drained bubble must swallow the record that follows it and a removed one
// must not.

test('the first prompt of a run is not printed under the answer to it', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'Continuing from a local chat.', timestamp: T },
    { type: 'queue-operation', operation: 'dequeue', timestamp: T },
    { type: 'user', message: { content: 'Continuing from a local chat.' }, timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'Picking it up from here.' }] } },
  ]);
  const evs = readTranscript(p).events;
  const texts = evs.map((e) => e.text);
  assert.deepStrictEqual(texts, ['Continuing from a local chat.', 'Picking it up from here.'],
    `the question belongs above the answer: ${JSON.stringify(texts)}`);
  assert.strictEqual(evs[0].queued, undefined,
    'a delivered message must not keep the badge that floats it to the bottom');
});

test('a queue drained at the end of a turn delivers where it drained', () => {
  // The mid-conversation shape, and the reason a drain MOVES rather than merely
  // un-badging in place: the enqueue happened mid-turn, hundreds of records
  // above, so left where it landed the message sits over the answer it was
  // typed during and over the answer it actually prompted.
  const p = writeFixture([
    { type: 'user', message: { content: 'first question' }, timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'follow up', timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'answer to first' }] } },
    { type: 'queue-operation', operation: 'dequeue', timestamp: T },
    { type: 'user', message: { content: 'follow up' }, timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'answer to follow up' }] } },
  ]);
  const texts = readTranscript(p).events.map((e) => e.text);
  assert.deepStrictEqual(texts, [
    'first question',
    'answer to first',
    'follow up',
    'answer to follow up',
  ], `wrong order: ${JSON.stringify(texts)}`);
});

test('two messages drained together keep send order and neither is doubled', () => {
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'one', timestamp: T },
    { type: 'queue-operation', operation: 'enqueue', content: 'two', timestamp: T },
    { type: 'queue-operation', operation: 'dequeue', timestamp: T },
    { type: 'user', message: { content: 'one' }, timestamp: T },
    { type: 'user', message: { content: 'two' }, timestamp: T },
  ]);
  const evs = readTranscript(p).events;
  const texts = evs.map((e) => e.text);
  assert.deepStrictEqual(texts, ['one', 'two'], `wrong: ${JSON.stringify(texts)}`);
  assert.ok(evs.every((e) => !e.queued),
    'both were delivered; a badge left on either would float it out of order the moment anything else is said');
});

test('the same words sent again after a drain are a second message', () => {
  // The duplicate guard is ONE-SHOT per drained copy. Keyed by content and left
  // standing, it swallowed every later message with the same words — "ok",
  // "continue", "yes" — which is a message that vanishes for no visible reason.
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'continue', timestamp: T },
    { type: 'queue-operation', operation: 'dequeue', timestamp: T },
    { type: 'user', message: { content: 'continue' }, timestamp: T },
    { type: 'assistant', timestamp: T, message: { content: [{ type: 'text', text: 'done' }] } },
    { type: 'user', message: { content: 'continue' }, timestamp: T },
  ]);
  const texts = readTranscript(p).events.map((e) => e.text);
  assert.deepStrictEqual(texts, ['continue', 'done', 'continue'], `wrong: ${JSON.stringify(texts)}`);
});

test('a dequeue after a remove does not resurrect the delivered message', () => {
  // A drain that runs over an already-empty queue must add nothing: `remove`
  // took the message out, and a second delivery of it would be a message the
  // reader never sent twice.
  const p = writeFixture([
    { type: 'queue-operation', operation: 'enqueue', content: 'do the thing', timestamp: T },
    { type: 'queue-operation', operation: 'remove', content: 'do the thing', timestamp: T },
    { type: 'queue-operation', operation: 'dequeue', timestamp: T },
  ]);
  const evs = readTranscript(p).events;
  assert.deepStrictEqual(evs.map((e) => e.text), ['do the thing']);
  assert.strictEqual(evs[0].queued, undefined);
});

// ------------------------------------------------ a queued message, split
//
// The enqueue and the remove land in DIFFERENT tail windows on nearly every
// send-while-busy: clients poll every 2.5s and real enqueue->remove gaps run to
// minutes. What the orphaned `remove` should do depends on whether the reader
// has already seen the enqueue, and the code used to re-emit the message either
// way — so a tailing client got a SECOND identical bubble while the first kept
// its `queued` badge, on the primary conversation view of both clients.

test('resuming a tail does not duplicate a queued message delivered in a later window', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-split-'));
  const p = path.join(dir, 'session.jsonl');
  const L = (o) => JSON.stringify(o) + '\n';
  try {
    fs.writeFileSync(p,
      L({ type: 'user', message: { content: 'first question' } }) +
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'working' }] } }) +
      L({ type: 'queue-operation', operation: 'enqueue', content: 'also fix the header' }));
    const page1 = readTranscript(p);
    const queuedNow = page1.events.filter((e) => e.kind === 'user' && e.text === 'also fix the header');
    assert.strictEqual(queuedNow.length, 1);
    assert.strictEqual(queuedNow[0].queued, true, 'a waiting message is badged');

    fs.appendFileSync(p,
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'answer' }] } }) +
      L({ type: 'queue-operation', operation: 'remove', content: 'also fix the header' }));
    const page2 = readTranscript(p, { offset: page1.nextOffset });

    const merged = page1.events.concat(page2.events);
    const copies = merged.filter((e) => e.kind === 'user' && e.text === 'also fix the header');
    assert.strictEqual(copies.length, 1, 'the reader already had this message; it must not arrive twice');
    assert.deepStrictEqual(page2.deliveredQueued, ['also fix the header'],
      'the delivery is reported instead, so the badge can be cleared');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('resuming a tail does not duplicate a queued message the DRAIN delivered', () => {
  // The dequeue twin of the split `remove` above, and the commoner shape of the
  // two on a live session: a message typed mid-turn is written as `enqueue`, the
  // drain at turn end as `dequeue`, and Claude Code then writes the drained text
  // as an ordinary `user` record a second or two later. When the turn outlasts a
  // 2.5 s poll the enqueue is in page N and the dequeue + user record in page
  // N+1, where the `queued` map is empty — so nothing was drained, nothing was
  // reported, the page-N bubble kept its badge for the life of the view and the
  // `user` record rendered as a second identical bubble. Replayed on the owner's
  // own transcripts: 23 human-typed drains split a window, 22 of them wrong.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-drain-'));
  const p = path.join(dir, 'session.jsonl');
  const L = (o) => JSON.stringify(o) + '\n';
  try {
    fs.writeFileSync(p,
      L({ type: 'user', message: { content: 'first question' } }) +
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'working' }] } }) +
      L({ type: 'queue-operation', operation: 'enqueue', content: 'do the restart asap' }));
    const page1 = readTranscript(p);
    const badged = page1.events.filter((e) => e.kind === 'user' && e.text === 'do the restart asap');
    assert.strictEqual(badged.length, 1);
    assert.strictEqual(badged[0].queued, true);

    fs.appendFileSync(p,
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'answer' }] } }) +
      L({ type: 'queue-operation', operation: 'dequeue' }) +
      L({ type: 'user', message: { content: 'do the restart asap' } }) +
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'restarting' }] } }));
    const page2 = readTranscript(p, { offset: page1.nextOffset });

    const merged = page1.events.concat(page2.events);
    const copies = merged.filter((e) => e.kind === 'user' && e.text === 'do the restart asap');
    assert.strictEqual(copies.length, 1, 'the reader already had this message; it must not arrive twice');
    assert.deepStrictEqual(page2.deliveredQueued, ['do the restart asap'],
      'the delivery is reported instead, so the badge can be cleared');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a message typed AFTER the drain, past the next answer, is still a message', () => {
  // The bound on the suppression above. Swallowing every later `user` record
  // because a drain happened somewhere above would silently eat the next thing
  // the owner types, which is the worst failure this file can have. The drain
  // covers the records that follow it up to the next assistant record — the turn
  // it fed — and nothing after that.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-drain-bound-'));
  const p = path.join(dir, 'session.jsonl');
  const L = (o) => JSON.stringify(o) + '\n';
  try {
    fs.writeFileSync(p, L({ type: 'queue-operation', operation: 'enqueue', content: 'one' }));
    const page1 = readTranscript(p);
    fs.appendFileSync(p,
      L({ type: 'queue-operation', operation: 'dequeue' }) +
      L({ type: 'user', message: { content: 'one' } }) +
      L({ type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } }) +
      L({ type: 'user', message: { content: 'two' } }));
    const page2 = readTranscript(p, { offset: page1.nextOffset });
    assert.deepStrictEqual(page2.events.map((e) => e.text), ['done', 'two']);
    assert.deepStrictEqual(page2.deliveredQueued, ['one']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a cold open still emits a message the drain delivered', () => {
  // The mirror of the cold-open case below: with no earlier page holding the
  // badged bubble, the `user` record after an orphaned dequeue is the only copy
  // the reader will ever get.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-drain-cold-'));
  const p = path.join(dir, 'session.jsonl');
  const L = (o) => JSON.stringify(o) + '\n';
  try {
    fs.writeFileSync(p,
      L({ type: 'queue-operation', operation: 'dequeue' }) +
      L({ type: 'user', message: { content: 'the follow up' } }));
    const t = readTranscript(p);
    assert.deepStrictEqual(t.events.map((e) => e.text), ['the follow up']);
    assert.deepStrictEqual(t.deliveredQueued, [], 'nothing to reconcile on a cold open');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a cold open still emits a queued message whose enqueue scrolled off', () => {
  // The mirror case, and why the re-emit cannot simply be deleted: with no
  // earlier page to hold it, this is the only copy the reader will ever get.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-cold-'));
  const p = path.join(dir, 'session.jsonl');
  const L = (o) => JSON.stringify(o) + '\n';
  try {
    fs.writeFileSync(p,
      L({ type: 'queue-operation', operation: 'enqueue', content: 'the follow up' }) +
      L({ type: 'queue-operation', operation: 'remove', content: 'the follow up' }));
    const t = readTranscript(p);
    const copies = t.events.filter((e) => e.kind === 'user' && e.text === 'the follow up');
    assert.strictEqual(copies.length, 1);
    assert.ok(!copies[0].queued, 'delivered, so not badged');
    assert.deepStrictEqual(t.deliveredQueued, [], 'nothing to reconcile on a cold open');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------- reading backwards
//
// A cold open reads only the tail, so a long session showed a fraction of itself
// and said "the most recent part" with no way to ask for the rest — measured at
// 51 of 3452 events on a real 28MB transcript. `until` walks back a page at a
// time; the contract that makes it work is that a window's `windowStart` is a
// record boundary, so consecutive pages ABUT: nothing is returned twice and, more
// importantly, nothing falls in a gap between them.

test('paging backwards with until reaches the start and loses nothing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-back-'));
  const p = path.join(dir, 'session.jsonl');
  try {
    const lines = [];
    for (let i = 0; i < 300; i++) {
      lines.push(JSON.stringify({ type: 'user', message: { content: `message number ${i}` } }));
      lines.push(JSON.stringify({
        type: 'assistant',
        message: { content: [{ type: 'text', text: `reply ${i} `.repeat(40) }] },
      }));
    }
    fs.writeFileSync(p, lines.join('\n') + '\n');

    const whole = readTranscript(p, { offset: 0, limit: 100000 });
    assert.ok(whole.events.length > 400, 'fixture must be longer than one window');

    let page = readTranscript(p);
    assert.ok(page.truncated, 'the tail of this file must not be the whole of it');
    const seen = [...page.events];
    let until = page.windowStart;
    let pages = 1;
    while (until > 0 && pages < 200) {
      const q = readTranscript(p, { until, limit: 200 });
      if (!q.events.length && q.windowStart === until) break;
      seen.unshift(...q.events);
      until = q.windowStart;
      pages++;
    }
    assert.strictEqual(until, 0, 'paging must reach the beginning of the file');
    assert.ok(seen.length >= whole.events.length,
      `paged ${seen.length} events but a whole-file read has ${whole.events.length} — a gap`);

    // The specific thing a gap would lose: every user message must be somewhere.
    const texts = new Set(seen.filter((e) => e.kind === 'user').map((e) => e.text));
    for (let i = 0; i < 300; i++) {
      assert.ok(texts.has(`message number ${i}`), `message ${i} fell into a gap between pages`);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('windowStart is zero once the whole file is in view', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tr-small-'));
  const p = path.join(dir, 'session.jsonl');
  try {
    fs.writeFileSync(p, JSON.stringify({ type: 'user', message: { content: 'only message' } }) + '\n');
    const t = readTranscript(p);
    assert.strictEqual(t.windowStart, 0);
    assert.strictEqual(t.truncated, false, 'nothing was cut off, so nothing should claim it was');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------ running out of road
//
// A usage limit is not an error record and not a status: it is an ORDINARY
// assistant record with three extra fields. Against the real one, captured off
// a session that hit the cap (test/fixtures/transcripts/, see its README) —
// because the shape is exactly the kind a synthetic fixture gets subtly wrong.

const { isLimitStall, parseLimitError, lastNonAttachmentRecord } = require('../lib/limits');

const LIMIT_FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'limit-429.jsonl');
const limitRecords = () => fs.readFileSync(LIMIT_FIXTURE, 'utf8')
  .split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));

test('a 429 assistant record renders as an event that says so', () => {
  const t = readTranscript(LIMIT_FIXTURE);
  const last = t.events[t.events.length - 1];
  assert.equal(last.kind, 'assistant',
    'the kind must not change — every client on 2.88.0 renders the text off it');
  assert.equal(last.apiError, 429);
  assert.match(last.text, /hit your session limit/);
  // And nothing else in the window is marked: the five records before it are an
  // ordinary turn, which is the whole difficulty of spotting a stall.
  assert.equal(t.events.filter((e) => e.apiError != null).length, 1);
  assert.equal(t.events.find((e) => e.kind === 'tool').apiError, undefined);
});

test('the real 429 record is a limit stall, with its text', () => {
  const r = limitRecords();
  const v = isLimitStall(r[r.length - 1]);
  assert.equal(v.stalled, true);
  assert.equal(v.status, 429);
  assert.match(v.text, /resets 3:10am/);
});

test('an outage is not a stall, and neither is an ordinary answer', () => {
  const r = limitRecords();
  const real = r[r.length - 1];
  // A 500 is an outage to ride out, not a window to wait for. Waiting for a
  // reset that is not coming is the failure this distinction prevents.
  const five = { ...real, apiErrorStatus: 500 };
  assert.equal(isLimitStall(five).stalled, false);
  assert.equal(isLimitStall(five).status, 500, 'still reported, so a caller can tell them apart');
  // The flag alone, with no status, must not read as "no error".
  const noStatus = { ...real, apiErrorStatus: undefined };
  assert.equal(isLimitStall(noStatus).stalled, false);
  // The last real ASSISTANT answer before the cap — same type, no flags.
  assert.equal(isLimitStall({ type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } }).stalled, false);
  assert.equal(isLimitStall(null).stalled, false);
});

test('the last record ignores attachments and queued messages', () => {
  const r = limitRecords();
  // The CLI appends its own bookkeeping after a turn, and a stalled agent's
  // file routinely ENDS on one. A `records[length-1]` check would see an
  // attachment and conclude the session is merely idle.
  const withNoise = [...r,
    { type: 'attachment', attachment: { type: 'deferred_tools_delta', addedNames: ['WebFetch'] } },
    // Typed while Claude was mid-turn: written BEFORE the 429, never delivered.
    // Counting it as "a human replied" would cancel a resume that should happen.
    { type: 'queue-operation', operation: 'enqueue', content: 'and then check the logs' },
  ];
  assert.equal(lastNonAttachmentRecord(withNoise).apiErrorStatus, 429);
  assert.equal(isLimitStall(lastNonAttachmentRecord(withNoise)).stalled, true);
  // A real human message after the 429 DOES win — that is a cancelled stall.
  const answered = [...withNoise, { type: 'user', message: { content: 'never mind' } }];
  assert.equal(isLimitStall(lastNonAttachmentRecord(answered)).stalled, false);
  assert.equal(lastNonAttachmentRecord([]), null);
});

test('the apology text says which window ran out', () => {
  const r = limitRecords();
  const session = parseLimitError(isLimitStall(r[r.length - 1]).text);
  assert.deepEqual(session, { window: 'session', resetsClock: '3:10am', tz: 'America/Los_Angeles' });

  // The other observed shape. No clock in it at all — the reset time for a
  // weekly window comes from the usage endpoint, never from this text.
  assert.deepEqual(
    parseLimitError("You're out of usage credits. Run /usage-credits to keep using Fable 5.1 or /model to switch models."),
    { window: 'weekly_fable', resetsClock: null, tz: null });

  // Same sentence with no model named: which pool is empty is genuinely
  // unknown, and a guess would send the arbiter to wait on the wrong reset.
  assert.equal(parseLimitError("You're out of usage credits.").window, null);
  // A monthly SPEND limit is not a window — nothing resets, so nothing waits.
  assert.equal(parseLimitError("You've hit your monthly spend limit · raise it at claude.ai/settings/usage").window, null);
  // Garbage in, nulls out — never a throw and never a default window.
  for (const bad of ['', '   ', 'Disk is at 62%.', null, undefined, 42, {}]) {
    assert.deepEqual(parseLimitError(bad), { window: null, resetsClock: null, tz: null });
  }
});

// ------------------------------------------------------- a skill is not a user
//
// Owner report: "when huginn calls a skill, the skill is being printed in the
// session chat tab as a message from the user."
//
// Against the real records (test/fixtures/transcripts/skill-invocation.jsonl,
// see its README), because the shape is the whole difficulty: the injected body
// is a `type: 'user'` record whose text is just the skill's prose, so nothing in
// the CONTENT distinguishes it from something a person typed. Only
// `sourceToolUseID` does.

const SKILL_FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'skill-invocation.jsonl');
const CMD_FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'slash-command.jsonl');

test('a skill body injected by the Skill tool is never a user bubble', () => {
  const t = readTranscript(SKILL_FIXTURE);
  const users = t.events.filter((e) => e.kind === 'user');
  // Exactly one thing in this window was said by a person.
  assert.deepEqual(users.map((e) => e.text),
    ['checking on the meshmonitor and the pihole DNS monitor-down alert']);
  // ...and neither skill's instructions leaked into the conversation anywhere.
  const all = t.events.map((e) => e.text || '').join('\n');
  assert.ok(!/Base directory for this skill/.test(all), 'project-skill body leaked');
  assert.ok(!/Workflow authoring reference/.test(all), 'bundled-skill body leaked');
});

test('a Skill invocation renders as ONE non-user event, naming the skill', () => {
  const t = readTranscript(SKILL_FIXTURE);
  const skills = t.events.filter((e) => e.kind === 'tool' && e.name === 'Skill');
  assert.equal(skills.length, 2, 'one card per invocation, and only one');
  assert.deepEqual(skills.map((e) => e.detail), ['incident-triage', 'workflow-authoring']);
  // Opened, the card is the invocation: the prompt that asked for it and the
  // CLI's own confirmation. Never the raw input JSON, which is what it showed.
  assert.match(skills[0].input, /^Kuma monitor-down spam/);
  assert.match(skills[0].result, /^Launching skill: incident-triage/);
  assert.equal(skills[1].input, '', 'no args means nothing behind the tap, not "{}"');
  // Two invocations, two events, and the whole window is four events plus them.
  assert.deepEqual(t.events.map((e) => e.kind),
    ['user', 'assistant', 'tool', 'assistant', 'tool']);
});

test('a skill whose Skill call is above the window still leaves a chip', () => {
  // Paging backwards can start the window between the call and the body, and
  // then the body is the only trace a skill loaded at all. Dropped outright it
  // would be a silent gap; it gets the compact chip instead.
  const p = writeFixture([
    {
      type: 'user', timestamp: T, isMeta: true, sourceToolUseID: 'toolu_gone',
      message: { content: [{ type: 'text', text: 'Base directory for this skill: /root/netplan/.claude/skills/incident-triage\n\n# Incident triage\n' }] },
    },
    {
      type: 'user', timestamp: T, isMeta: true, sourceToolUseID: 'toolu_gone2',
      message: { content: [{ type: 'text', text: '# Workflow authoring reference\n\nA workflow structures work.\n' }] },
    },
  ]);
  const r = readTranscript(p);
  assert.deepEqual(r.events.map((e) => e.kind), ['command', 'command'],
    'a kind both clients already draw, as a centered chip');
  // A project skill names itself through its directory; a bundled one carries
  // no marker, and a guessed name would be worse than an honest generic one.
  assert.deepEqual(r.events.map((e) => e.text), ['Skill: incident-triage', 'Skill loaded']);
  assert.equal(r.events.filter((e) => e.kind === 'user').length, 0);
});

test('a typed slash command is a chip, and the message after it is still the user', () => {
  const t = readTranscript(CMD_FIXTURE);
  // The command and its stdout are both chips — neither is a message — and the
  // sentence typed afterwards is the only user bubble in the window.
  assert.deepEqual(t.events.map((e) => e.kind), ['command', 'command_result', 'user']);
  assert.equal(t.events[0].text, '/chrome');
  assert.equal(t.events[2].text, 'lets look at the 2x32GB kit then');
  assert.equal(t.events.filter((e) => e.kind === 'user').length, 1);
});

test('a person who types "<command-name>" still gets their message', () => {
  // The CLI writes its command bookkeeping as a `user` record with the same
  // tags a person could type, so the TEXT cannot separate them. `origin.kind`
  // can: the CLI never sets it on its own records. Absent (older transcripts),
  // injected wins — the safe direction, and the behaviour that shipped.
  const typed = writeFixture([
    {
      type: 'user', timestamp: T, promptSource: 'typed', origin: { kind: 'human' },
      message: { content: '<command-name>/model</command-name> why does this show up in my chat?' },
    },
  ]);
  const r = readTranscript(typed);
  assert.deepEqual(r.events.map((e) => e.kind), ['user']);
  assert.match(r.events[0].text, /why does this show up in my chat\?$/);
  assert.equal(r.model, null, 'and it must not be read as actually setting the model');

  // The CLI's own record, byte-identical in content, still collapses to a chip.
  const cli = writeFixture([
    { type: 'user', timestamp: T, message: { content: '<command-name>/model</command-name>\n<command-args>opus</command-args>' } },
  ]);
  const c = readTranscript(cli);
  assert.deepEqual(c.events.map((e) => e.kind), ['command']);
  assert.equal(c.model, 'opus');
});

test('injectedByTool keys on the field, not on isMeta', () => {
  // isMeta is much broader, and two of the things it marks must keep rendering:
  // a resumed session's opening instruction and the owner's own image captions.
  assert.equal(injectedByTool({ isMeta: true, sourceToolUseID: 'toolu_1' }), true);
  assert.equal(injectedByTool({ isMeta: true }), false);
  assert.equal(injectedByTool({ sourceToolUseID: '' }), false);
  assert.equal(injectedByTool({}), false);
  // A resumed session's "Continue from where you left off." is isMeta and IS
  // the instruction the run is answering — it has to stay a message.
  const p = writeFixture([
    { type: 'user', timestamp: T, isMeta: true, message: { content: 'Continue from where you left off.' } },
  ]);
  assert.deepEqual(readTranscript(p).events.map((e) => e.kind), ['user']);
});

test('skillNameFromBody takes the directory, or admits it does not know', () => {
  assert.equal(skillNameFromBody('Base directory for this skill: /root/netplan/.claude/skills/bybrynn-deploy\n\n# x'), 'bybrynn-deploy');
  assert.equal(skillNameFromBody('Base directory for this skill: /tmp/scratch/skills/kratos-kvm/'), 'kratos-kvm');
  assert.equal(skillNameFromBody('# Workflow authoring reference\n\nA workflow...'), '');
  for (const bad of ['', null, undefined, 42]) assert.equal(skillNameFromBody(bad), '');
});
