'use strict';
// An agent's "last line" comes from a short tail, and is the same line the old
// 256 KB read found.
//
// Since mid-October an agent transcript is mostly REQUEST RECORDS — Claude Code
// writes the whole API request before each call, 40-130 KB a line — so the six
// events the old read wanted sat behind two or three of them, and every row of
// the agents sheet cost a 512 KB read parsed twice. The new read finds the same
// answer in the last few records. Two things are pinned here: how much it reads
// (bounded by the records it needs, not by the file), and that its answer is the
// old one, including every shape where the short window has to give up.
//
// Fixtures are SYNTHETIC: the record shapes of a real agent file, filler text.

const { test, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { agentLastLine } = require('../lib/agents');
const { readTranscript } = require('../lib/transcript');

// Every temp dir this file makes, removed when it is done: some of these
// fixtures are megabytes, and the suite runs on every deploy.
const scratchDirs = [];
const scratch = (dir) => { scratchDirs.push(dir); return dir; };
after(() => { for (const d of scratchDirs) fs.rmSync(d, { recursive: true, force: true }); });

const T0 = 1_800_000_000;
const T = (s) => new Date((T0 + s) * 1000).toISOString();
const L = (o) => `${JSON.stringify(o)}\n`;

/** The old answer: the last six events of readTranscript's own tail read. */
function oldLastLine(file) {
  try {
    const t = readTranscript(file, { limit: 6 });
    for (let i = t.events.length - 1; i >= 0; i--) {
      const e = t.events[i];
      if (e.kind === 'tool') return `${e.name || 'tool'}${e.detail ? ` ${e.detail}` : ''}`.slice(0, 120);
      if ((e.kind === 'assistant' || e.kind === 'thinking') && e.text) {
        const line = e.text.trim().split('\n').find((l) => l.trim());
        if (line) return (e.kind === 'thinking' ? '…' : '') + line.trim().slice(0, 120);
      }
    }
  } catch { }
  return null;
}

let n = 0;
const prompt = (text) => ({ type: 'user', isSidechain: true, timestamp: T(0), message: { role: 'user', content: text } });
/** A request record: `type` first, as the CLI writes it, and a large body. */
const request = (kb, at) => ({
  type: 'api-request', sessionId: 's1', agentId: 'a1', id: `q${++n}`, timestamp: T(at),
  params: { messages: [{ role: 'user', content: 'r'.repeat(kb * 1024) }] },
});
const blob = () => ({ type: 'api-request-blob', sessionId: 's1', agentId: 'a1', hash: `h${++n}`, message: { role: 'user', content: 'b' } });
const asst = (at, block) => ({
  type: 'assistant', isSidechain: true, requestId: `req${at}`, timestamp: T(at),
  message: { role: 'assistant', model: 'claude-opus-5', content: [block] },
});
const thinking = (at, text) => asst(at, { type: 'thinking', thinking: text });
const toolUse = (at, id, name, input) => asst(at, { type: 'tool_use', id, name, input });
const says = (at, text) => asst(at, { type: 'text', text });
const result = (at, id, content = 'ok') => ({
  type: 'user', isSidechain: true, timestamp: T(at), message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content }] },
});
const attachment = (at) => ({ type: 'attachment', isSidechain: true, timestamp: T(at), attachment: { type: 'note', text: 'a' } });
const userSays = (at, text) => ({ type: 'user', isSidechain: true, timestamp: T(at), message: { role: 'user', content: text } });

/** One API call the way a real agent file records it. */
function cycle(at, id, { kb = 100, tool = 'Agent', input = { description: `run ${id}` }, think = `Next: step ${id}` } = {}) {
  return [blob(), request(kb, at), thinking(at + 1, think), toolUse(at + 2, id, tool, input), result(at + 3, id, 'z'.repeat(3000)), attachment(at + 4)];
}

function write(records, { tail = '' } = {}) {
  const dir = scratch(fs.mkdtempSync(path.join(os.tmpdir(), 'lastline-')));
  const f = path.join(dir, 'agent-x.jsonl');
  fs.writeFileSync(f, records.map(L).join('') + tail);
  return f;
}

function bytesRead(fn) {
  let bytes = 0;
  const orig = { readSync: fs.readSync, readFileSync: fs.readFileSync };
  fs.readSync = function (...a) { const r = orig.readSync.apply(fs, a); bytes += r; return r; };
  fs.readFileSync = function (...a) { const r = orig.readFileSync.apply(fs, a); bytes += Buffer.isBuffer(r) ? r.length : Buffer.byteLength(r); return r; };
  let out;
  try { out = fn(); } finally { Object.assign(fs, orig); }
  return { out, bytes };
}

function longAgent(cycles, { waiting = true } = {}) {
  const recs = [prompt('Map the build pipeline')];
  for (let c = 0; c < cycles; c++) recs.push(...cycle(10 + c * 10, `t${c}`));
  // A running agent between calls: its newest line is the request it sent.
  if (waiting) recs.push(blob(), request(100, 10 + cycles * 10));
  return write(recs);
}

test('a long agent file costs a short tail, whatever its size', () => {
  const small = longAgent(12);
  const large = longAgent(48);
  assert.ok(fs.statSync(large).size > 4 * 1024 * 1024, 'a few megabytes of agent');
  const a = bytesRead(() => agentLastLine(small));
  const b = bytesRead(() => agentLastLine(large));
  assert.equal(a.out, 'Agent run t11');
  assert.equal(b.out, 'Agent run t47');
  // The old read never took less than its 256 KB window twice (the probe and the
  // read itself), and here doubled past it: six events sit behind two requests.
  assert.ok(b.bytes < 192 * 1024, `read ${b.bytes} bytes for a ${fs.statSync(large).size}-byte file`);
  assert.equal(b.bytes, a.bytes, 'bounded by the records it needs, not by the file');
});

test('the short tail answers what the old read answers', () => {
  const cases = {
    'running, between calls': longAgent(6),
    'running, mid-call': write([prompt('p'), ...cycle(10, 'a1'), ...cycle(20, 'a2'), blob(), request(90, 30), thinking(31, 'Reading the config')]),
    'finished with prose': write([prompt('p'), ...cycle(10, 'a1'), blob(), request(90, 20), says(21, '\n\nAll three shards are clean.\nDetails follow.')]),
    'thinking last': write([prompt('p'), ...cycle(10, 'a1'), blob(), request(50, 20), thinking(21, '  \nWeighing the two fixes')]),
    'a workflow, an agent, a skill and a question': write([prompt('p'),
      toolUse(10, 'w1', 'Workflow', { script: "export const meta = {name: 'triage-sweep'}\n// …" }), result(11, 'w1'),
      toolUse(12, 'g1', 'Agent', { description: 'Check the switches', subagent_type: 'Explore' }), result(13, 'g1'),
      toolUse(14, 's1', 'Skill', { skill: 'incident-triage', args: 'why' }), result(15, 's1', 'Launching skill: incident-triage'),
      { type: 'user', isSidechain: true, timestamp: T(16), sourceToolUseID: 's1', isMeta: true, message: { role: 'user', content: [{ type: 'text', text: 'Base directory for this skill: /srv/example\n\n# Incident triage\nSteps…' }] } },
      toolUse(17, 'q1', 'AskUserQuestion', { questions: [{ question: 'Restart the relay now?', options: [] }] })]),
    // Results whose calls are further back than the short window reaches: the
    // window sees orphans and must look again, wider, before it answers.
    'a burst of results': write([prompt('p'), blob(), request(30, 5),
      ...['r1', 'r2', 'r3', 'r4', 'r5', 'r6'].map((id, i) => toolUse(10 + i, id, 'Read', { file_path: `/srv/example/${id}` })),
      ...['r1', 'r2', 'r3', 'r4', 'r5', 'r6'].map((id, i) => result(20 + i, id, 'y'.repeat(500)))]),
    // Seven messages after the last call: the old read's six events are all
    // messages, so it answers nothing, and so must the new one.
    'seven messages after the work': write([prompt('p'), ...cycle(10, 'a1'), ...[1, 2, 3, 4, 5, 6, 7].map((k) => userSays(30 + k, `note ${k}`))]),
    'a prompt and nothing else': write([prompt('only the task'), attachment(1)]),
    'a line still being written': write([prompt('p'), ...cycle(10, 'a1')], { tail: '{"type":"assistant","message":{"content":[{"type":"text","text":"half' }),
    'no newline anywhere': write([], { tail: JSON.stringify(says(5, 'one record, unterminated')) }),
    'a result bigger than the old window': write([prompt('p'), ...cycle(10, 'a1'), toolUse(20, 'big', 'Read', { file_path: '/srv/example/big.log' }), result(21, 'big', 'q'.repeat(300 * 1024))]),
    'a request bigger than the old window': write([prompt('p'), ...cycle(10, 'a1'), blob(), request(300, 20)]),
    'an empty file': write([]),
  };
  for (const [name, file] of Object.entries(cases)) {
    assert.equal(agentLastLine(file), oldLastLine(file), name);
  }
  // And the cases are not vacuous: they span answers and non-answers.
  const answers = Object.values(cases).map(oldLastLine);
  assert.ok(answers.filter((a) => a === null).length >= 3);
  assert.ok(answers.filter((a) => a !== null).length >= 7);
});

test('a missing file has no last line rather than a crash', () => {
  assert.equal(agentLastLine('/nonexistent/agent-gone.jsonl'), null);
});
