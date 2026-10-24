'use strict';
// The `/context` table, as Claude Code writes it into the transcript.
//
// Verified against Claude Code 2.1.306 (test/fixtures/transcripts/context-local-command.jsonl):
// `/context` writes two records of `type: "system", subtype: "local_command"`.
// The first carries `<command-name>/context</command-name>`; the second carries
// the whole table, ANSI-coloured, inside `<local-command-stdout>…</local-command-stdout>`:
//
//      Context Usage
//     ⛁ ⛁ ⛁ ⛀ ⛶ ⛶ …   Haiku 5.5                 ← the model beside the first grid rows
//     ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ …   claude-haiku-5-5
//     ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ …   28.7k/1m tokens (3%)
//     ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ …   Estimated usage by category
//     ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ …   ⛁ System prompt: 3.7k tokens (0.4%)
//                        ⛁ Messages: 10 tokens (0.0%)
//                        ⛶ Free space: 938.3k (93.8%)
//                        ⛝ Autocompact buffer: 33k tokens (3.3%)
//     Auto-compact window: 1m tokens
//     MCP tools · /mcp (loaded on-demand)      Loaded: ├ name: N tokens   Available: ├ name
//     Skills · /skills                         ├ name: ~N tokens
//
// Everything here is pure except [scanForward], which reads a transcript. The
// breakdown is parsed from the TRANSCRIPT, not the pane: the pane shows the table
// once and scrolls it away, the transcript keeps it.

const fs = require('node:fs');
const { stripAnsi } = require('./pane');

/** `3.7k` → 3700, `1m` → 1000000, `938.3k` → 938300, `671` → 671, `1,234` → 1234; else null. */
function parseTokens(s) {
  const m = /^(\d+(?:[.,]\d+)*)\s*([kKmM])?$/.exec(String(s || '').trim());
  if (!m) return null;
  const n = Number(m[1].replace(/,/g, ''));
  if (!Number.isFinite(n)) return null;
  const mult = m[2] ? (m[2].toLowerCase() === 'k' ? 1e3 : 1e6) : 1;
  return Math.round(n * mult);
}

/** Claude Code's category names → the ids the wire carries. Unknown names are slugged. */
const CATEGORY_IDS = {
  'system prompt': 'system_prompt',
  'system tools': 'system_tools',
  'mcp tools': 'mcp_tools',
  'mcp server instructions': 'mcp_instructions',
  'deferred mcp tools': 'mcp_tools_deferred',
  'mcp tools (deferred)': 'mcp_tools_deferred',
  'deferred system tools': 'system_tools_deferred',
  'system tools (deferred)': 'system_tools_deferred',
  'custom agents': 'custom_agents',
  'memory files': 'memory_files',
  skills: 'skills',
  messages: 'messages',
  'autocompact buffer': 'autocompact_buffer',
  'compact buffer': 'compact_buffer',
  'free space': 'free_space',
};
function categoryId(name) {
  const key = name.trim().toLowerCase();
  return CATEGORY_IDS[key] || key.replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

/** The glyph in front of a category row says what KIND of space it is. */
function kindOf(glyph, id) {
  if (/deferred/.test(id)) return 'deferred';
  if (glyph === '⛶') return 'free';
  if (glyph === '⛝') return 'buffer';
  return 'used';
}

// The grid of glyphs drawn down the left of the head rows; what is left is text.
const GRID_RE = /^[\s⛁⛀⛶⛝]*/;
const HEADER_RE = /^(\S+?)\s*\/\s*(\S+?)\s+tokens\s*\((\d+(?:\.\d+)?)%\)$/;
const CATEGORY_RE = /([⛁⛀⛶⛝])\s+([^:⛁⛀⛶⛝]+?):\s+(\S+?)(?:\s+tokens)?\s*\((\d+(?:\.\d+)?)%\)\s*$/;
const AUTOCOMPACT_RE = /^Auto-compact window:\s*(\S+?)\s*tokens?\b\s*(.*)$/i;
const LIST_ROW_RE = /^[├└│]\s*(.+?)(?::\s*~?(\S+?)\s*tokens?)?\s*$/;
const MODEL_ID_RE = /^[a-z][a-z0-9][a-z0-9._[\]-]*$/;

/**
 * The table, parsed. Null when the text is not a `/context` table at all (no
 * header and no category row).
 */
function parseContextUsage(text) {
  const lines = stripAnsi(String(text || '')).split('\n').map((l) => l.replace(/\s+$/, ''));
  const out = {
    model: { display: null, id: null },
    totalTokens: null,
    maxTokens: null,
    percent: null,
    categories: [],
    autocompactWindow: null,
    autocompactSource: null,
    mcpTools: [],
    mcpAvailable: [],
    skills: [],
  };
  let section = 'head';     // head | mcp | skills | other
  let sub = null;           // Loaded | Available under mcp
  let sawTable = false;
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (/^MCP tools\b/.test(line)) { section = 'mcp'; sub = null; continue; }
    if (/^Skills\b/.test(line)) { section = 'skills'; sub = null; continue; }
    const ac = AUTOCOMPACT_RE.exec(line);
    if (ac) {
      out.autocompactWindow = parseTokens(ac[1]);
      const rest = ac[2].replace(/^\(|\)$/g, '').trim();
      out.autocompactSource = rest || null;
      section = 'other';
      continue;
    }
    if (section === 'mcp') {
      if (/^Loaded$/i.test(line)) { sub = 'loaded'; continue; }
      if (/^Available$/i.test(line)) { sub = 'available'; continue; }
      const row = LIST_ROW_RE.exec(line);
      if (!row) continue;
      if (sub === 'available' || row[2] === undefined) out.mcpAvailable.push(row[1].trim());
      else out.mcpTools.push({ name: row[1].trim(), tokens: parseTokens(row[2]) });
      continue;
    }
    if (section === 'skills') {
      const row = LIST_ROW_RE.exec(line);
      if (!row) continue;           // a group heading: Built-in, Project, User
      out.skills.push({ name: row[1].trim(), tokens: row[2] === undefined ? null : parseTokens(row[2]) });
      continue;
    }
    const cat = CATEGORY_RE.exec(raw);
    if (cat) {
      const name = cat[2].trim();
      const id = categoryId(name);
      out.categories.push({
        id, name, tokens: parseTokens(cat[3]), percent: Number(cat[4]), kind: kindOf(cat[1], id),
      });
      sawTable = true;
      continue;
    }
    if (section !== 'head') continue;
    const rest = raw.replace(GRID_RE, '').trim();
    if (!rest || rest === 'Context Usage' || /^Estimated usage by category$/i.test(rest)) continue;
    const head = HEADER_RE.exec(rest);
    if (head) {
      out.totalTokens = parseTokens(head[1]);
      out.maxTokens = parseTokens(head[2]);
      out.percent = Number(head[3]);
      sawTable = true;
      continue;
    }
    if (out.model.display === null) out.model.display = rest;
    else if (out.model.id === null && MODEL_ID_RE.test(rest)) out.model.id = rest;
  }
  return sawTable ? out : null;
}

const STDOUT_OPEN = '<local-command-stdout>';
const STDOUT_CLOSE = '</local-command-stdout>';

/** The stdout of a `local_command` record, or null. indexOf, never a lazy regex. */
function commandStdout(content) {
  const s = String(content || '');
  const i = s.indexOf(STDOUT_OPEN);
  if (i < 0) return null;
  const j = s.indexOf(STDOUT_CLOSE, i + STDOUT_OPEN.length);
  return s.slice(i + STDOUT_OPEN.length, j < 0 ? s.length : j);
}

/** Is this transcript record the one that holds a `/context` table? */
function isContextRecord(d) {
  if (!d || d.type !== 'system' || d.subtype !== 'local_command' || typeof d.content !== 'string') return false;
  const out = commandStdout(d.content);
  return out !== null && out.includes('Context Usage');
}

/**
 * The wire `breakdown` out of a transcript record, or null when it holds no table.
 *
 *   { asOf: ms, uuid, categories: [{id, name, tokens, percent, kind}], totalTokens,
 *     maxTokens, percent, autocompactWindow, autocompactSource, model: {display, id},
 *     mcpTools: [{name, tokens}], mcpAvailable: [name], skills: [{name, tokens}] }
 */
function breakdownFromRecord(d) {
  if (!isContextRecord(d)) return null;
  const parsed = parseContextUsage(commandStdout(d.content));
  if (!parsed) return null;
  const asOf = typeof d.timestamp === 'string' ? Date.parse(d.timestamp) : NaN;
  return {
    asOf: Number.isFinite(asOf) ? asOf : null,
    uuid: typeof d.uuid === 'string' ? d.uuid : null,
    ...parsed,
  };
}

// ---- finding the latest record in a transcript --------------------------------
//
// A transcript is read forward from where the last look ended, so a poll of a
// session that has not run `/context` again costs a stat; a cold start (the
// daemon just came up) looks back at most COLD_LOOKBACK bytes, which is many
// turns of ordinary records and several `api-request` ones. Only a line that
// carries both marks is decoded: everything else, the `api-request` records of
// up to a megabyte each included, is passed over on the raw bytes.

const STDOUT_TAG = Buffer.from(STDOUT_OPEN);
const CONTEXT_MARK = Buffer.from('Context Usage');
const COLD_LOOKBACK = 4 * 1024 * 1024;
const SCAN_CHUNK = 1024 * 1024;

/** Where a cold start begins reading a transcript of `size` bytes. */
function coldStart(size) { return size > COLD_LOOKBACK ? size - COLD_LOOKBACK : 0; }

/**
 * The last `/context` record whose line is complete in [from, to), and how far
 * the read got — `scannedTo` is always just past a newline, so the next read can
 * start there with `aligned: true`. A read that starts mid-file (`aligned:
 * false`) drops the fragment before its first newline.
 */
function scanForward(file, from, to, { aligned = true } = {}) {
  let fd;
  try { fd = fs.openSync(file, 'r'); } catch { return { record: null, scannedTo: from }; }
  try {
    let pos = from;
    let record = null;
    let scannedTo = from;
    let skipFirst = !aligned && from > 0;
    let carry = Buffer.alloc(0);
    while (pos < to) {
      const buf = Buffer.alloc(Math.min(SCAN_CHUNK, to - pos));
      const n = fs.readSync(fd, buf, 0, buf.length, pos);
      if (n <= 0) break;
      pos += n;
      const chunk = carry.length ? Buffer.concat([carry, buf.subarray(0, n)]) : buf.subarray(0, n);
      let at = 0;
      for (;;) {
        const nl = chunk.indexOf(0x0a, at);
        if (nl === -1) break;
        const lineStart = at;
        at = nl + 1;
        scannedTo = pos - chunk.length + at;
        if (skipFirst) { skipFirst = false; continue; }
        const line = chunk.subarray(lineStart, nl);
        if (line.length < 64 || line.indexOf(STDOUT_TAG) === -1 || line.indexOf(CONTEXT_MARK) === -1) continue;
        let d;
        try { d = JSON.parse(line.toString('utf8')); } catch { continue; }
        if (isContextRecord(d)) record = d;
      }
      carry = Buffer.from(chunk.subarray(at));
    }
    return { record, scannedTo };
  } finally {
    try { fs.closeSync(fd); } catch { /* closed */ }
  }
}

module.exports = {
  parseTokens, categoryId, parseContextUsage, commandStdout, isContextRecord, breakdownFromRecord,
  coldStart, scanForward, COLD_LOOKBACK, CATEGORY_IDS,
};
