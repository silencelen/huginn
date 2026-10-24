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
