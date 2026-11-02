'use strict';
// lib/contextusage.js — the `/context` table out of Claude Code's transcript.
//
// The fixture is the real record pair Claude Code 2.1.306 wrote for `/context`
// in a throwaway session (fixtures/transcripts/context-local-command.jsonl):
// a `system`/`local_command` `<command-name>` record and the table, ANSI-coloured,
// in a second one. Filesystem-only, in a scratch directory.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cu = require('../lib/contextusage');

const FIXTURE = path.join(__dirname, 'fixtures', 'transcripts', 'context-local-command.jsonl');
const records = () => fs.readFileSync(FIXTURE, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const [CMD, TABLE] = records();

test('token suffixes: k and m, decimals, commas, bare counts', () => {
  assert.equal(cu.parseTokens('3.7k'), 3700);
  assert.equal(cu.parseTokens('20.8k'), 20800);
  assert.equal(cu.parseTokens('938.3k'), 938300);
  assert.equal(cu.parseTokens('33k'), 33000);
  assert.equal(cu.parseTokens('1m'), 1000000);
  assert.equal(cu.parseTokens('1.5M'), 1500000);
  assert.equal(cu.parseTokens('671'), 671);
  assert.equal(cu.parseTokens('1,234'), 1234);
  assert.equal(cu.parseTokens('~480'), null, 'the tilde is stripped by the row regex, not here');
  assert.equal(cu.parseTokens(''), null);
  assert.equal(cu.parseTokens('tokens'), null);
  assert.equal(cu.parseTokens(null), null);
});

test('the real table: every category, with Claude Code\'s ids and kinds', () => {
  const b = cu.breakdownFromRecord(TABLE);
  assert.ok(b, 'the stdout record holds a table');
  assert.equal(b.uuid, '729f5ccf-8b84-4a6a-8bd8-8af1ded28ce1');
  assert.equal(b.asOf, Date.parse('2026-10-24T04:22:39.988Z'));
  assert.deepEqual(b.model, { display: 'Haiku 5.5', id: 'claude-haiku-5-5' });
  assert.equal(b.totalTokens, 28700);
  assert.equal(b.maxTokens, 1000000);
  assert.equal(b.percent, 3);
  assert.deepEqual(b.categories, [
    { id: 'system_prompt', name: 'System prompt', tokens: 3700, percent: 0.4, kind: 'used' },
    { id: 'system_tools', name: 'System tools', tokens: 20800, percent: 2.1, kind: 'used' },
    { id: 'mcp_tools', name: 'MCP tools', tokens: 671, percent: 0.1, kind: 'used' },
    { id: 'mcp_instructions', name: 'MCP server instructions', tokens: 717, percent: 0.1, kind: 'used' },
    { id: 'skills', name: 'Skills', tokens: 2800, percent: 0.3, kind: 'used' },
    { id: 'messages', name: 'Messages', tokens: 10, percent: 0, kind: 'used' },
    { id: 'free_space', name: 'Free space', tokens: 938300, percent: 93.8, kind: 'free' },
    { id: 'autocompact_buffer', name: 'Autocompact buffer', tokens: 33000, percent: 3.3, kind: 'buffer' },
  ]);
  assert.equal(b.autocompactWindow, 1000000);
  assert.equal(b.autocompactSource, null);
});

test('the real table: the MCP tools and the skills, with their per-tool tokens', () => {
  const b = cu.breakdownFromRecord(TABLE);
  assert.deepEqual(b.mcpTools, [
    { name: 'mcp__claude_ai_Claude_Docs__batch', tokens: 165 },
    { name: 'mcp__claude_ai_Claude_Docs__guide', tokens: 208 },
    { name: 'mcp__claude_ai_Claude_Docs__update', tokens: 298 },
  ]);
  assert.deepEqual(b.mcpAvailable, [
    'mcp__claude_ai_Claude_Docs__create', 'mcp__claude_ai_Claude_Docs__delete', 'mcp__claude_ai_Claude_Docs__export',
    'mcp__claude_ai_Claude_Docs__query', 'mcp__claude_ai_Claude_Docs__read',
  ]);
  assert.equal(b.skills.length, 18);
  assert.deepEqual(b.skills[0], { name: 'dataviz', tokens: 480 });
  assert.deepEqual(b.skills[b.skills.length - 1], { name: 'init', tokens: 20 });
  // The used categories plus the free space and the buffer add up to the window.
  const sum = b.categories.reduce((s, c) => s + c.tokens, 0);
  assert.ok(Math.abs(sum - b.maxTokens) < 0.01 * b.maxTokens, `categories sum to ${sum}, window ${b.maxTokens}`);
});

test('the <command-name> record is not the table, and neither is any other record', () => {
  assert.equal(cu.isContextRecord(CMD), false);
  assert.equal(cu.breakdownFromRecord(CMD), null);
  assert.equal(cu.isContextRecord({ type: 'user', message: { content: 'Context Usage' } }), false);
  assert.equal(cu.isContextRecord({ type: 'system', subtype: 'local_command', content: '<local-command-stdout>Catch you later!</local-command-stdout>' }), false);
  assert.equal(cu.breakdownFromRecord(null), null);
});

test('a table with no MCP section and no skills, and the deferred/compact names', () => {
  const text = [
    ' Context Usage',
    '⛁ ⛁ ⛶ ⛶   Fable 5.1',
    '⛶ ⛶ ⛶ ⛶   claude-fable-5-1',
    '⛶ ⛶ ⛶ ⛶   412.5k/1m tokens (41%)',
    '⛶ ⛶ ⛶ ⛶   Estimated usage by category',
    '⛶ ⛶ ⛶ ⛶   ⛁ System prompt: 4.1k tokens (0.4%)',
    '⛶ ⛶ ⛶ ⛶   ⛁ Deferred MCP tools: 1.2k tokens (0.1%)',
    '⛶ ⛶ ⛶ ⛶   ⛁ Memory files: 9.9k tokens (1.0%)',
    '⛶ ⛶ ⛶ ⛶   ⛁ Custom agents: 640 tokens (0.1%)',
    '⛶ ⛶ ⛶ ⛶   ⛁ Messages: 396.7k tokens (39.7%)',
    '           ⛶ Free space: 554.5k (55.4%)',
    '           ⛝ Compact buffer: 33k tokens (3.3%)',
    '',
    'Auto-compact window: 1m tokens (CLAUDE_AUTOCOMPACT_WINDOW)',
  ].join('\n');
  const p = cu.parseContextUsage(text);
  assert.ok(p);
  assert.deepEqual(p.model, { display: 'Fable 5.1', id: 'claude-fable-5-1' });
  assert.equal(p.totalTokens, 412500);
  assert.equal(p.percent, 41);
  assert.deepEqual(p.categories.map((c) => [c.id, c.kind]), [
    ['system_prompt', 'used'], ['mcp_tools_deferred', 'deferred'], ['memory_files', 'used'],
    ['custom_agents', 'used'], ['messages', 'used'], ['free_space', 'free'], ['compact_buffer', 'buffer'],
  ]);
  assert.deepEqual(p.mcpTools, []);
  assert.deepEqual(p.mcpAvailable, []);
  assert.deepEqual(p.skills, []);
  assert.equal(p.autocompactWindow, 1000000);
  assert.equal(p.autocompactSource, 'CLAUDE_AUTOCOMPACT_WINDOW');
});

test('a name Claude Code has not used yet is slugged, with its glyph deciding the kind', () => {
  const p = cu.parseContextUsage('  12k/200k tokens (6%)\n  ⛁ Plugin manifests: 1.1k tokens (0.6%)\n  ⛶ Free space: 150k (75%)');
  assert.deepEqual(p.categories[0], { id: 'plugin_manifests', name: 'Plugin manifests', tokens: 1100, percent: 0.6, kind: 'used' });
  assert.equal(cu.categoryId('MCP server instructions'), 'mcp_instructions');
  assert.equal(cu.categoryId('System tools (deferred)'), 'system_tools_deferred');
});

test('text that is not a table parses to null', () => {
  assert.equal(cu.parseContextUsage(''), null);
  assert.equal(cu.parseContextUsage('Catch you later!'), null);
  assert.equal(cu.parseContextUsage('Loaded\n├ a: 1 tokens'), null);
});

// ---- finding the record in a transcript ------------------------------------

function tmpFile(lines) {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), `cu-${process.pid}-`)), 's.jsonl');
  fs.writeFileSync(p, lines.map((l) => (typeof l === 'string' ? l : JSON.stringify(l))).join('\n') + '\n');
  return p;
}
const big = (n) => JSON.stringify({ type: 'api-request', blob: 'x'.repeat(n) });

test('scanForward finds the latest table past api-request records without decoding them', () => {
  const second = { ...TABLE, uuid: 'later-uuid', timestamp: '2026-10-24T05:00:00.000Z' };
  const file = tmpFile([
    { type: 'user', message: { content: 'hi' } },
    big(300 * 1024),
    CMD, TABLE,
    big(200 * 1024),
    { type: 'assistant', message: { content: [] } },
    CMD, second,
    big(100 * 1024),
  ]);
  const size = fs.statSync(file).size;
  const r = cu.scanForward(file, 0, size);
  assert.ok(r.record);
  assert.equal(r.record.uuid, 'later-uuid', 'the LAST table wins');
  assert.equal(r.scannedTo, size, 'every complete line was read');
  assert.equal(cu.breakdownFromRecord(r.record).asOf, Date.parse('2026-10-24T05:00:00.000Z'));
});

test('scanForward resumes from where it stopped and sees only what is new', () => {
  const file = tmpFile([{ type: 'user', message: { content: 'hi' } }, big(70 * 1024)]);
  const first = cu.scanForward(file, 0, fs.statSync(file).size);
  assert.equal(first.record, null);
  const at = first.scannedTo;
  fs.appendFileSync(file, `${JSON.stringify(CMD)}\n${JSON.stringify(TABLE)}\n`);
  const r = cu.scanForward(file, at, fs.statSync(file).size);
  assert.equal(r.record.uuid, TABLE.uuid);
  assert.equal(r.scannedTo, fs.statSync(file).size);
});

test('a half-written last line is left for the next read', () => {
  const file = tmpFile([{ type: 'user', message: { content: 'hi' } }]);
  const whole = fs.statSync(file).size;
  const line = JSON.stringify(TABLE);
  fs.appendFileSync(file, line.slice(0, line.length - 40));   // no newline, not finished
  const r = cu.scanForward(file, 0, fs.statSync(file).size);
  assert.equal(r.record, null);
  assert.equal(r.scannedTo, whole, 'stopped after the last complete line');
  fs.appendFileSync(file, `${line.slice(line.length - 40)}\n`);
  const r2 = cu.scanForward(file, r.scannedTo, fs.statSync(file).size);
  assert.equal(r2.record.uuid, TABLE.uuid, 'the whole line, read once it was complete');
});

test('a cold start mid-file drops the fragment before its first newline', () => {
  const file = tmpFile([big(20), CMD, TABLE, { type: 'assistant', message: { content: [] } }]);
  const size = fs.statSync(file).size;
  // Start inside the first record: an unaligned read must not try to parse its tail.
  const r = cu.scanForward(file, 10, size, { aligned: false });
  assert.equal(r.record.uuid, TABLE.uuid);
  // Start inside the TABLE record: it is a fragment now, and gone.
  const tableAt = fs.readFileSync(file, 'utf8').indexOf('"local_command","content":"<local-command-stdout>');
  const r2 = cu.scanForward(file, tableAt, size, { aligned: false });
  assert.equal(r2.record, null);
  assert.equal(cu.coldStart(100), 0);
  assert.equal(cu.coldStart(cu.COLD_LOOKBACK + 5), 5);
});

test('scanForward on a file that is not there is empty, not a throw', () => {
  assert.deepEqual(cu.scanForward('/nonexistent/t.jsonl', 0, 10), { record: null, scannedTo: 0 });
});

// ---- Claude Code 2.1.316's Markdown twin, and the 2.1.310 user-record shape (2026-11-02)

const MD = [
  '## Context Usage', '', '**Model:** claude-opus-5-5  ', '**Tokens:** 720.8k / 1m (72%)', '',
  '### Estimated usage by category', '', '| Category | Tokens | Percentage |', '|----------|--------|------------|',
  '| System prompt | 2.9k | 0.3% |', '| System tools | 18.2k | 1.8% |', '| MCP tools (deferred) | 33.1k | 3.3% |',
  '| Memory files | 17.5k | 1.8% |', '| Messages | 672k | 67.2% |', '| Free space | 246.2k | 24.6% |', '| Autocompact buffer | 33k | 3.3% |', '',
  '### MCP Tools', '', '| Tool | Server | Tokens |', '|------|--------|--------|', '| mcp__example__query | example | 616 |', '| mcp__example__list | example | 1.5k |', '',
  '### Memory Files', '', '| Type | Path | Tokens |', '|------|------|--------|', '| Project | /home/me/project/CLAUDE.md | 5.4k |', '',
  '### Skills', '', '| Skill | Source | Tokens |', '|-------|--------|--------|', '| code-review | Built-in | ~400 |', '| deploy | Project | ~120 |',
].join('\n');

test('the Markdown twin parses to the same shape as the terminal table', () => {
  const { parseContextMarkdown } = require('../lib/contextusage');
  const p = parseContextMarkdown(MD);
  assert.ok(p);
  assert.equal(p.totalTokens, 720800); assert.equal(p.maxTokens, 1000000); assert.equal(p.percent, 72);
  assert.equal(p.model.id, 'claude-opus-5-5');
  assert.deepEqual(p.categories.map((c) => [c.id, c.tokens, c.percent, c.kind]), [
    ['system_prompt', 2900, 0.3, 'used'], ['system_tools', 18200, 1.8, 'used'], ['mcp_tools_deferred', 33100, 3.3, 'deferred'],
    ['memory_files', 17500, 1.8, 'used'], ['messages', 672000, 67.2, 'used'], ['free_space', 246200, 24.6, 'free'], ['autocompact_buffer', 33000, 3.3, 'buffer'],
  ]);
  assert.deepEqual(p.mcpTools, [{ name: 'mcp__example__query', tokens: 616 }, { name: 'mcp__example__list', tokens: 1500 }]);
  assert.deepEqual(p.skills, [{ name: 'code-review', tokens: 400 }, { name: 'deploy', tokens: 120 }]);
  assert.equal(parseContextMarkdown('## Something else\n| a | b | c |'), null);
});

test('every shape Claude Code has written /context in is a context record', () => {
  const { breakdownFromRecord, isContextRecord } = require('../lib/contextusage');
  const table = records().find((d) => d.type === 'system' && d.subtype === 'local_command' && d.content.includes('Context Usage'));
  const legacy = breakdownFromRecord(table);
  assert.ok(legacy && legacy.categories.length > 0, '2.1.306: system/local_command');
  // 2.1.310: the same stdout wrapper, in a user record with text blocks.
  const asUser = { type: 'user', isMeta: true, timestamp: table.timestamp, uuid: 'u1', message: { role: 'user', content: [{ type: 'text', text: table.content }] } };
  assert.ok(isContextRecord(asUser), 'the card stopped refreshing because this shape was refused');
  assert.deepEqual(breakdownFromRecord(asUser).categories, legacy.categories);
  // 2.1.316: the isMeta Markdown twin.
  const twin = { type: 'user', isMeta: true, timestamp: '2026-11-02T01:12:14.196Z', uuid: 'u2', message: { role: 'user', content: MD } };
  const md = breakdownFromRecord(twin);
  assert.equal(md.percent, 72); assert.equal(md.asOf, Date.parse('2026-11-02T01:12:14.196Z'));
  // Not a context record: the command's own bookkeeping, a plain message, a tool result.
  // Only Claude Code's own records (isMeta, no origin) count: a person typing the
  // heading, a peer session's message, or a `!` command's output never do
  // (security scan 2026-11-02, F1).
  assert.equal(isContextRecord({ type: 'user', message: { content: '## Context Usage is what I want to talk about' } }), false);
  assert.equal(isContextRecord({ type: 'user', isMeta: true, origin: { kind: 'peer', name: 'alex' }, message: { content: MD } }), false, 'a peer message is never the table');
  assert.equal(isContextRecord({ type: 'user', isMeta: true, message: { content: 'said first\n' + MD } }), false, 'the heading must be the first thing in the record');
  assert.equal(isContextRecord({ type: 'user', isMeta: true, message: { content: 'x <local-command-stdout>Context Usage</local-command-stdout>' } }), false, 'and so must the wrapper');
  assert.equal(isContextRecord({ type: 'user', message: { content: 'how full is the context?' } }), false);
  assert.equal(isContextRecord({ type: 'user', message: { content: [{ type: 'tool_result', content: '## Context Usage' }] } }), false);
});

test('scanForward finds the Markdown twin, which has no stdout tag', () => {
  const { scanForward } = require('../lib/contextusage');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ctx-md-'));
  const file = path.join(dir, 't.jsonl');
  const lines = [
    JSON.stringify({ type: 'user', message: { role: 'user', content: 'hello there, how full are we?' } }),
    JSON.stringify({ type: 'user', isMeta: true, uuid: 'twin', timestamp: '2026-11-02T01:12:14.196Z', message: { role: 'user', content: MD } }),
    JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: 'about 72%' }] } }),
  ];
  fs.writeFileSync(file, lines.join('\n') + '\n');
  const r = scanForward(file, 0, fs.statSync(file).size);
  assert.ok(r.record, 'found');
  assert.equal(r.record.uuid, 'twin');
  assert.equal(require('../lib/contextusage').breakdownFromRecord(r.record).percent, 72);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('a planted row of 20,000 pipes costs its length, not seconds', () => {
  const { parseContextMarkdown } = require('../lib/contextusage');
  const planted = '## Context Usage\n\n### Estimated usage by category\n\n' + '|'.repeat(20_000) + 'x\n| Messages | 1k | 1% |';
  const t0 = process.hrtime.bigint();
  const p = parseContextMarkdown(planted);
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  assert.ok(ms < 100, `took ${ms.toFixed(0)} ms`);
  assert.deepEqual(p.categories.map((c) => c.id), ['messages'], 'the long line is skipped, the real row read');
});
