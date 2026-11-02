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

// The Markdown twin's rows: `| System prompt | 2.9k | 0.3% |`, `| mcp__x | srv | 152 |`.
// Split on `|`, not matched with lazy groups: a row regex of four `(.+?)` between
// separators backtracked in cubic time on a long run of `|` with no closing one
// (security scan 2026-11-02, F1), and a user record a peer wrote could carry one.
function mdRow(line) {
  if (line.length < 5 || line[0] !== '|' || line[line.length - 1] !== '|') return null;
  const cells = line.slice(1, -1).split('|').map((c) => c.trim());
  return cells.length === 3 ? cells : null;
}
// No line longer than this is looked at by any regex below: a genuine row is
// under a hundred characters, and a bound here is what keeps a planted record
// from costing more than its length.
const MD_LINE_MAX = 512;
const MD_TOKENS_RE = /^\*\*Tokens:\*\*\s*(\S{1,20})\s*\/\s*(\S{1,20})\s*(?:tokens)?\s*\((\d+(?:\.\d+)?)%\)/;
const MD_MODEL_RE = /^\*\*Model:\*\*\s*(.{1,120}?)\s*$/;

/**
 * The Markdown table Claude Code 2.1.316 writes as `/context`'s second record
 * (an isMeta user record starting "## Context Usage", 2026-11-02), parsed into
 * the same shape as the terminal table. Null when `text` is not that table.
 */
function parseContextMarkdown(text) {
  const lines = String(text || '').split('\n').map((l) => (l.length > MD_LINE_MAX ? '' : l.trim()));
  if (!/^## Context Usage\b/.test(lines[0] || '')) return null;
  const out = {
    model: { display: null, id: null }, totalTokens: null, maxTokens: null, percent: null,
    categories: [], autocompactWindow: null, autocompactSource: null, mcpTools: [], mcpAvailable: [], skills: [],
  };
  let section = 'head';
  let sawTable = false;
  for (const line of lines) {
    if (!line) continue;
    if (/^###\s+Estimated usage by category/i.test(line)) { section = 'categories'; continue; }
    if (/^###\s+MCP tools/i.test(line)) { section = 'mcp'; continue; }
    if (/^###\s+Skills/i.test(line)) { section = 'skills'; continue; }
    if (/^###\s+/.test(line)) { section = 'other'; continue; }
    const ac = AUTOCOMPACT_RE.exec(line.replace(/\*\*/g, ''));
    if (ac) { out.autocompactWindow = parseTokens(ac[1]); out.autocompactSource = ac[2].replace(/^\(|\)$/g, '').trim() || null; continue; }
    if (section === 'head') {
      const m = MD_MODEL_RE.exec(line);
      if (m) { out.model.id = m[1]; out.model.display = m[1]; continue; }
      const t = MD_TOKENS_RE.exec(line);
      if (t) { out.totalTokens = parseTokens(t[1]); out.maxTokens = parseTokens(t[2]); out.percent = Number(t[3]); sawTable = true; }
      continue;
    }
    const cells = mdRow(line);
    if (!cells || /^-+$/.test(cells[0]) || /^(Category|Tool|Skill|Type)$/i.test(cells[0])) continue;
    const row = [line, ...cells];
    if (section === 'categories') {
      const pct = /^(\d+(?:\.\d+)?)%$/.exec(row[3]);
      if (!pct) continue;
      const id = categoryId(row[1]);
      out.categories.push({ id, name: row[1], tokens: parseTokens(row[2]), percent: Number(pct[1]), kind: kindOf(id === 'free_space' ? '⛶' : /buffer/.test(id) ? '⛝' : '', id) });
      sawTable = true;
    } else if (section === 'mcp') {
      out.mcpTools.push({ name: row[1], tokens: parseTokens(row[3].replace(/^~/, '')) });
    } else if (section === 'skills') {
      out.skills.push({ name: row[1], tokens: parseTokens(row[3].replace(/^~/, '')) });
    }
  }
  return sawTable ? out : null;
}

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
/**
 * The text a record carries: `content` on a system record, `message.content`
 * (a string, or text blocks) on a user record.
 */
function recordText(d) {
  if (!d) return '';
  if (typeof d.content === 'string') return d.content;
  const c = d.message && d.message.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.filter((b) => b && b.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('\n');
  return '';
}

/**
 * The `/context` table's text out of a record, whichever of the THREE shapes
 * Claude Code has used, or null:
 *  - 2.1.306: `system`/`local_command`, the terminal table in `<local-command-stdout>`;
 *  - 2.1.310: the same wrapper in a `user` record (this is why the card stopped
 *    refreshing - only the first shape was accepted, 2026-11-02);
 *  - 2.1.316: the system record again, PLUS an isMeta `user` record holding the
 *    Markdown twin ("## Context Usage"), which carries the same figures.
 */
function contextTableText(d) {
  if (!d || (d.type !== 'system' && d.type !== 'user')) return null;
  const text = recordText(d);
  if (d.type === 'user') {
    // ⚠ ONLY WHAT CLAUDE CODE ITSELF WROTE (security scan 2026-11-02, F1). A
    // user record is also where a peer session's message and a `!` command's
    // output land, verbatim; neither may pose as the table. Claude Code's own
    // records of the command are `isMeta` with no `origin`, and the text IS the
    // wrapper or the heading from its first character, not somewhere inside.
    if (d.isMeta !== true || d.origin) return null;
    const t = text.trimStart();
    if (t.startsWith(STDOUT_OPEN)) { const out = commandStdout(t); return out !== null && out.includes('Context Usage') ? out : null; }
    return /^## Context Usage\b/.test(t) ? t.trim() : null;
  }
  const out = commandStdout(text);
  if (out !== null) return out.includes('Context Usage') ? out : null;
  return null;
}

function isContextRecord(d) {
  return contextTableText(d) !== null;
}

/**
 * The wire `breakdown` out of a transcript record, or null when it holds no table.
 *
 *   { asOf: ms, uuid, categories: [{id, name, tokens, percent, kind}], totalTokens,
 *     maxTokens, percent, autocompactWindow, autocompactSource, model: {display, id},
 *     mcpTools: [{name, tokens}], mcpAvailable: [name], skills: [{name, tokens}] }
 */
function breakdownFromRecord(d) {
  const text = contextTableText(d);
  if (text === null) return null;
  const parsed = /^## Context Usage\b/.test(text) ? parseContextMarkdown(text) : parseContextUsage(text);
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
const MD_TAG = Buffer.from('## Context Usage');
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
        if (line.length < 64 || line.indexOf(CONTEXT_MARK) === -1) continue;
        if (line.indexOf(STDOUT_TAG) === -1 && line.indexOf(MD_TAG) === -1) continue;
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
  parseContextMarkdown, contextTableText, recordText,
  parseTokens, categoryId, parseContextUsage, commandStdout, isContextRecord, breakdownFromRecord,
  coldStart, scanForward, COLD_LOOKBACK, CATEGORY_IDS,
};
